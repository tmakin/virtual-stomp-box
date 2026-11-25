package uk.neatlines.stompbox.input

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import uk.neatlines.stompbox.debug.GapCounter
import uk.neatlines.stompbox.debug.GattInfo
import uk.neatlines.stompbox.wit.Wit
import uk.neatlines.stompbox.wit.WitPacketParser
import uk.neatlines.stompbox.wit.WitSample

/**
 * Connects to a WitMotion WT901 BLE (BWT901BLE5.0), sets it to 200 Hz with the widest filter, and
 * delivers every sample with its arrival time on the System.nanoTime clock.
 *
 * Replaces the WitMotion SDK's DeviceModel, which rounds values, keeps only the latest sample in a
 * map, and polls magnetometer and battery registers every 500 ms. This class:
 *  - takes the arrival time first thing in the GATT callback and parses on that thread (no hop);
 *  - asks for CONNECTION_PRIORITY_HIGH (11.25–15 ms interval), the 2M PHY and a 247-byte MTU. The
 *    sensor still sends one notification of ~8 frames about every 44 ms (its own packing timer);
 *  - sends every write through one queue, one at a time: Android rejects a write while another
 *    is in flight, which silently dropped setup commands before;
 *  - reads back the rate and bandwidth registers and writes them only when they differ
 *    (each write ends with SAVE, so it would otherwise rewrite flash on every connect);
 *  - reads the battery every 10 s.
 *
 * Needs BLUETOOTH_SCAN (neverForLocation) and BLUETOOTH_CONNECT.
 */
@SuppressLint("MissingPermission")
class WitBleSource(
    private val context: Context,
    rateHz: Int = 200,
    private val bandwidthHz: Int = 188,
    /** Called on the GATT binder thread. Keep it short: it is on the latency path. */
    private val onSample: (WitSample, arrivalNanos: Long) -> Unit,
) {
    /** Output rate to set on the sensor. Changing it while connected reconfigures the sensor. */
    @Volatile var rateHz: Int = rateHz
        set(v) { require(v in Wit.RATE_CODES); field = v; handler.post { configureIfNeeded() } }
    @Volatile var status: String = "idle"; private set
    @Volatile var batteryVolts: Double = Double.NaN; private set
    @Volatile var rateRegister: Int = -1; private set
    @Volatile var bandwidthRegister: Int = -1; private set
    @Volatile var deviceName: String? = null; private set
    @Volatile var mtu = 23; private set
    /** The sensor's GATT services and characteristics, and any firmware-update services among them. */
    @Volatile var gattServices: String = "?"; private set
    @Volatile var updateServices: List<String> = emptyList(); private set
    /** Device Information strings (firmware revision etc.) and the raw 0x2E/0x2F version registers. */
    @Volatile var deviceInfo: String = ""; private set
    @Volatile var versionRegisters: String = "?"; private set
    /** Connection parameters as last reported by the stack (hidden callback; -1 until reported). */
    @Volatile var connIntervalMs: Double = -1.0; private set
    @Volatile var connLatency: Int = -1; private set
    @Volatile var connTimeoutMs: Int = -1; private set
    @Volatile var notifications = 0L; private set
    private var framesAtConnect = 0L
    val framesPerNotification: Double get() = if (notifications == 0L) 0.0 else (parser.frames - framesAtConnect).toDouble() / notifications

    // ---- stall diagnostics: is a stall in the link (gaps, reconnects) or elsewhere?
    /** Times the link dropped and was reconnected since [start]. */
    @Volatile var reconnects = 0; private set
    /** Gaps between notifications while streaming (normally ~44 ms; over 250 ms counts as a stall). */
    val gaps = GapCounter(thresholdMs = 250.0)
    /** Writes that got no reply in time, or that Android still refused after [MAX_TRIES] tries. */
    @Volatile var failedWrites = 0; private set

    /** Register-read round trip (write `FF AA 27 40` → `55 71` reply), timed every 5 s. */
    val pingSummary: String get() {
        val v = synchronized(pings) { pings.sorted() }
        if (v.isEmpty()) return "no replies yet"
        return "median %.0f ms, min %.0f, max %.0f (n=%d)".format(v[v.size / 2], v.first(), v.last(), v.size)
    }
    private val pings = ArrayDeque<Double>()
    @Volatile private var pingSentAt = 0L
    var onStatus: ((String) -> Unit)? = null

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val worker = HandlerThread("wit-ble").apply { start() }
    private val handler = Handler(worker.looper)
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var write: BluetoothGattCharacteristic? = null
    private var arrival = 0L
    private var scanCallback: ScanCallback? = null
    @Volatile private var stopped = true
    /** True from [start] until [stop], including while scanning or reconnecting. */
    val running get() = !stopped
    private val parser = WitPacketParser(
        onSample = { s -> onSample(s, arrival) },
        onRegisters = { reg, v -> onRegisters(reg, v) },
    )

    /** Connects to [address] if given, otherwise to the first device whose name starts with "WT". */
    fun start(address: String? = null) {
        if (!stopped) return     // already running: a second start would open a second connection
        stopped = false; reconnects = 0; gaps.reset(); failedWrites = 0
        if (address != null) { handler.post { if (!stopped) connect(manager.adapter.getRemoteDevice(address)) }; return }
        setStatus("scanning")
        val scanner = manager.adapter.bluetoothLeScanner
        val cb = object : ScanCallback() {
            // Results already queued can still arrive after stopScan; connect to the first one only.
            private var found = false
            override fun onScanResult(type: Int, r: ScanResult) {
                if (found) return
                val name = r.scanRecord?.deviceName ?: r.device.name ?: return
                if (!name.startsWith("WT")) return
                found = true
                scanner.stopScan(this)
                handler.post { if (!stopped && gatt == null) connect(r.device) }
            }
            override fun onScanFailed(errorCode: Int) = setStatus("scan failed $errorCode")
        }
        scanCallback = cb
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        handler.postDelayed({ scanner.stopScan(cb); if (gatt == null) setStatus("no WT901 found") }, 15_000)
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        handler.post(::resetWrites)
        scanCallback?.let { runCatching { manager.adapter.bluetoothLeScanner?.stopScan(it) } }; scanCallback = null
        gatt?.disconnect(); gatt?.close(); gatt = null; write = null
        setStatus("idle")
    }

    /** Queue a raw command for the sensor. */
    fun send(cmd: ByteArray) = handler.post { enqueue(cmd) }

    private fun connect(device: BluetoothDevice) {
        deviceName = device.name ?: device.address
        setStatus("connecting $deviceName")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setStatus("connected, setting up")
                mtu = 23; notifications = 0; framesAtConnect = parser.frames; gaps.restart()
                connIntervalMs = -1.0; connLatency = -1; connTimeoutMs = -1
                synchronized(pings) { pings.clear() }
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
                handler.postDelayed({ if (!g.requestMtu(247)) g.discoverServices() }, 200)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                g.close()
                handler.post {
                    // A stale connection (already replaced or stopped) must not touch the current one.
                    if (gatt !== g) return@post
                    // Drop every timer and queued write of the old connection, or they pile up on each reconnect.
                    handler.removeCallbacksAndMessages(null)
                    resetWrites()
                    gatt = null; write = null
                    if (stopped) return@post
                    reconnects++
                    setStatus("disconnected ($st), reconnecting")
                    handler.postDelayed({ if (!stopped && gatt == null) connect(g.device) }, 1000)
                }
            }
        }

        // Hidden in the SDK (@hide) but called by the stack; the same trick Nordic's BLE library uses.
        @Suppress("unused")
        fun onConnectionUpdated(g: BluetoothGatt, interval: Int, latency: Int, timeout: Int, st: Int) {
            connIntervalMs = interval * 1.25; connLatency = latency; connTimeoutMs = timeout * 10
            Log.i(TAG, "connection updated: interval ${interval * 1.25} ms, peripheral latency $latency, timeout ${timeout * 10} ms, status $st")
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) { this@WitBleSource.mtu = mtu; g.discoverServices() }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            gattServices = GattInfo.describe(g.services.map { s -> s.uuid to s.characteristics.map { it.uuid } })
            updateServices = GattInfo.updateServices(g.services.map { it.uuid })
            Log.i(TAG, "GATT: $gattServices")
            infoToRead = g.getService(GattInfo.DEVICE_INFO)?.characteristics
                ?.filter { it.uuid in GattInfo.DEVICE_INFO_STRINGS }.orEmpty()
            val svc = g.getService(Wit.SERVICE) ?: return setStatus("not a WT901 (no FFE5 service)")
            val notify = svc.getCharacteristic(Wit.NOTIFY)
            write = svc.getCharacteristic(Wit.WRITE)
            g.setCharacteristicNotification(notify, true)
            g.writeDescriptor(notify.getDescriptor(Wit.CCCD), BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            // A callback from a connection that stop() or a reconnect has replaced would start timers
            // that never get cancelled.
            if (g !== gatt) return
            setStatus("streaming")
            handler.post {
                deviceInfo = ""
                infoToRead.forEach { writes.addLast(Write(ByteArray(0), 0, false, read = it)) }
                enqueue(Wit.readRegister(Wit.REG_VERSION))
                enqueue(Wit.readRegister(Wit.REG_RATE))
                enqueue(Wit.readRegister(Wit.REG_BANDWIDTH))
                enqueue(Wit.readRegister(Wit.REG_BATTERY))
            }
            handler.postDelayed({ configureIfNeeded() }, 1000)
            handler.postDelayed(batteryPoll, 10_000)
            handler.postDelayed(ping, 3000)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) = completed(g, st)

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, st: Int) {
            val name = GattInfo.DEVICE_INFO_STRINGS[c.uuid]
            if (g === gatt && name != null && st == BluetoothGatt.GATT_SUCCESS) {
                val text = String(value, Charsets.UTF_8).trim { it <= ' ' }
                deviceInfo = listOf(deviceInfo, "$name \"$text\"").filter { it.isNotEmpty() }.joinToString(", ")
            }
            completed(g, st)
        }

        /** A queued write or read got its reply: release the queue for the next one. */
        private fun completed(g: BluetoothGatt, st: Int) {
            handler.post {
                // Ignore a reply from an old connection, or one that comes after its write timed out:
                // it must not cancel the timeout of the write now in flight or release the queue early.
                if (g !== gatt || inFlight == null) return@post
                if (st != BluetoothGatt.GATT_SUCCESS) failedWrites++
                handler.removeCallbacks(writeTimeout)
                inFlight = null; writeBusy = false
                pump()
            }
        }

        // Android 13+ delivers the value directly; arrival time is taken before anything else.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            arrival = System.nanoTime()
            if (g !== gatt) return
            gaps.mark(arrival)
            notifications++
            parser.feed(value)
        }
    }

    private val batteryPoll = object : Runnable {
        override fun run() {
            if (gatt == null) return     // disconnected or stopped: let the timer die
            enqueue(Wit.readRegister(Wit.REG_BATTERY)); handler.postDelayed(this, 10_000)
        }
    }

    private val ping = object : Runnable {
        override fun run() {
            if (gatt == null) return
            enqueue(Wit.readRegister(Wit.REG_TEMP), isPing = true)
            handler.postDelayed(this, 5000)
        }
    }

    private fun onRegisters(reg: Int, v: ShortArray) {
        when (reg) {
            Wit.REG_TEMP -> {
                val sent = pingSentAt
                if (sent != 0L) {
                    pingSentAt = 0L
                    synchronized(pings) { pings.addLast((arrival - sent) / 1e6); while (pings.size > 30) pings.removeFirst() }
                }
            }
            Wit.REG_RATE -> rateRegister = v[0].toInt()
            Wit.REG_BANDWIDTH -> bandwidthRegister = v[0].toInt()
            Wit.REG_BATTERY -> batteryVolts = v[0] / 100.0
            Wit.REG_VERSION -> versionRegisters = "0x2E = 0x%04X, 0x2F = 0x%04X".format(v[0].toInt() and 0xFFFF, v[1].toInt() and 0xFFFF)
        }
    }

    private fun configureIfNeeded() {
        if (gatt == null || write == null) return
        val wantRate = Wit.RATE_CODES.getValue(rateHz)
        val wantBw = Wit.BANDWIDTH_CODES.getValue(bandwidthHz)
        if (rateRegister == wantRate && bandwidthRegister == wantBw) return
        Log.i(TAG, "configuring: rate reg $rateRegister→$wantRate, bandwidth reg $bandwidthRegister→$wantBw")
        Wit.configure(rateHz, bandwidthHz).forEach { enqueue(it, gapMs = 100) }
        enqueue(Wit.readRegister(Wit.REG_RATE), gapMs = 200)
        enqueue(Wit.readRegister(Wit.REG_BANDWIDTH))
    }

    // ---- write queue (handler thread only) ---------------------------------------------------

    /** A command to write, or (with [read]) a characteristic to read; both go through one queue. */
    private class Write(val cmd: ByteArray, val gapMs: Long, val isPing: Boolean, val read: BluetoothGattCharacteristic? = null) { var tries = 0 }
    /** Device Information characteristics to read once streaming starts. */
    private var infoToRead: List<BluetoothGattCharacteristic> = emptyList()
    private val writes = ArrayDeque<Write>()
    /** A write is scheduled or in flight; the next one waits. */
    private var writeBusy = false
    /** The write Android accepted and whose onCharacteristicWrite hasn't come yet. */
    private var inFlight: Write? = null
    private val writeTimeout = Runnable { failedWrites++; inFlight = null; writeBusy = false; pump() }

    /** [gapMs]: wait this long before sending (the WitMotion SDKs leave ~100 ms between setup commands). */
    private fun enqueue(cmd: ByteArray, gapMs: Long = 0, isPing: Boolean = false) {
        writes.addLast(Write(cmd, gapMs, isPing)); pump()
    }

    private fun resetWrites() { writes.clear(); inFlight = null; writeBusy = false }

    private fun pump() {
        if (writeBusy) return
        val w = writes.removeFirstOrNull() ?: return
        writeBusy = true
        handler.postDelayed({
            val g = gatt; val c = write
            if (g == null || c == null) { resetWrites(); return@postDelayed }
            if (w.isPing) pingSentAt = System.nanoTime()
            val rc = if (w.read != null) { if (g.readCharacteristic(w.read)) BluetoothStatusCodes.SUCCESS else -1 }
                else g.writeCharacteristic(c, w.cmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            if (rc == BluetoothStatusCodes.SUCCESS) {
                inFlight = w
                handler.postDelayed(writeTimeout, 1000)
            } else {
                // Usually "busy": the stack still waits on an earlier write (e.g. one that timed out
                // here). Put it back at the front and retry, so a setup command is never skipped.
                writeBusy = false
                if (++w.tries < MAX_TRIES) writes.addFirst(Write(w.cmd, 0, w.isPing, w.read).also { it.tries = w.tries })
                else { failedWrites++; Log.w(TAG, "write dropped after $MAX_TRIES tries (rc $rc)") }
                handler.postDelayed({ pump() }, RETRY_MS)
            }
        }, w.gapMs)
    }

    private fun setStatus(s: String) { status = s; Log.i(TAG, s); onStatus?.invoke(s) }

    private companion object {
        const val TAG = "WitBle"
        /** A refused write is retried every [RETRY_MS] up to [MAX_TRIES] times (~1 s). */
        const val RETRY_MS = 50L
        const val MAX_TRIES = 20
    }
}
