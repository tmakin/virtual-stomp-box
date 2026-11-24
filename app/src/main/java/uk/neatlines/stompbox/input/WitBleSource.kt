package uk.neatlines.stompbox.input

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
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
 *  - asks for CONNECTION_PRIORITY_HIGH (11.25–15 ms interval) and a 247-byte MTU so several frames
 *    fit in one connection event;
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
    /**
     * How the connection is set up; changing it reconnects. Experiment: the WitMotion app shows no
     * lag, and it (as far as we know) keeps the default 23-byte MTU and 1M PHY, so the sensor sends
     * one frame per notification. With MTU 247 the sensor packs ~8 frames per notification.
     */
    @Volatile var linkMode: LinkMode = LinkMode.DEFAULT_MTU
        set(v) { field = v; handler.post { gatt?.disconnect() } }   // the disconnect handler reconnects
    @Volatile var mtu = 23; private set
    @Volatile var status: String = "idle"; private set
    @Volatile var batteryVolts: Double = Double.NaN; private set
    @Volatile var rateRegister: Int = -1; private set
    @Volatile var bandwidthRegister: Int = -1; private set
    @Volatile var deviceName: String? = null; private set
    /** Connection parameters as last reported by the stack (hidden callback; -1 until reported). */
    @Volatile var connIntervalMs: Double = -1.0; private set
    @Volatile var connLatency: Int = -1; private set
    @Volatile var connTimeoutMs: Int = -1; private set
    @Volatile var notifications = 0L; private set
    /** True while [flush] has the sensor at 1 Hz; samples then are not real-time and should be ignored. */
    @Volatile var flushing = false; private set
    @Volatile var flushes = 0; private set
    /**
     * Round trip of a register read (write `FF AA 27 40` → `55 71` reply), timed every 2 s. The
     * reply comes back on the same notify path as the data, so if the ~420 ms data delay is a send
     * queue in the sensor, the round trip includes it; if the round trip is short, the delay is in
     * the sensor's sampling or filtering instead.
     */
    val pingSummary: String get() {
        val v = synchronized(pings) { pings.sorted() }
        if (v.isEmpty()) return "no replies yet"
        return "median %.0f ms, min %.0f, max %.0f (n=%d)".format(v[v.size / 2], v.first(), v.last(), v.size)
    }
    private val pings = ArrayDeque<Double>()
    @Volatile private var pingSentAt = 0L
    private var framesAtConnect = 0L
    val framesPerNotification: Double get() = if (notifications == 0L) 0.0 else (parser.frames - framesAtConnect).toDouble() / notifications
    var onStatus: ((String) -> Unit)? = null

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val worker = HandlerThread("wit-ble").apply { start() }
    private val handler = Handler(worker.looper)
    private var gatt: BluetoothGatt? = null
    private var write: BluetoothGattCharacteristic? = null
    private var arrival = 0L
    private var scanCallback: ScanCallback? = null
    @Volatile private var stopped = true
    private val parser = WitPacketParser(
        onSample = { s -> onSample(s, arrival) },
        onRegisters = { reg, v -> onRegisters(reg, v) },
    )

    /** Connects to [address] if given, otherwise to the first device whose name starts with "WT". */
    fun start(address: String? = null) {
        stopped = false
        if (address != null) { connect(manager.adapter.getRemoteDevice(address)); return }
        setStatus("scanning")
        val scanner = manager.adapter.bluetoothLeScanner
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: r.device.name ?: return
                if (!name.startsWith("WT")) return
                scanner.stopScan(this)
                handler.post { connect(r.device) }
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
        scanCallback?.let { runCatching { manager.adapter.bluetoothLeScanner?.stopScan(it) } }; scanCallback = null
        gatt?.disconnect(); gatt?.close(); gatt = null; write = null
        setStatus("idle")
    }

    /** Re-zero the yaw / angle reference on the sensor (WitMotion "angle reference"). */
    fun send(cmd: ByteArray) = handler.post { writeNow(cmd) }

    private fun connect(device: BluetoothDevice) {
        deviceName = device.name ?: device.address
        setStatus("connecting $deviceName")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                setStatus("connected, setting up (${linkMode.label})")
                mtu = 23; notifications = 0; framesAtConnect = parser.frames
                connIntervalMs = -1.0; connLatency = -1; connTimeoutMs = -1
                synchronized(pings) { pings.clear() }
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                if (linkMode.phy2m) g.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
                handler.postDelayed({ if (!linkMode.bigMtu || !g.requestMtu(247)) g.discoverServices() }, 200)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                setStatus("disconnected ($st), retrying")
                g.close(); gatt = null; write = null
                if (!stopped) handler.postDelayed({ connect(g.device) }, 1000)
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
            val svc = g.getService(Wit.SERVICE) ?: return setStatus("not a WT901 (no FFE5 service)")
            val notify = svc.getCharacteristic(Wit.NOTIFY)
            write = svc.getCharacteristic(Wit.WRITE)
            g.setCharacteristicNotification(notify, true)
            g.writeDescriptor(notify.getDescriptor(Wit.CCCD), BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            setStatus("streaming")
            handler.postDelayed({ writeNow(Wit.readRegister(Wit.REG_RATE)) }, 300)
            handler.postDelayed({ writeNow(Wit.readRegister(Wit.REG_BANDWIDTH)) }, 600)
            handler.postDelayed({ configureIfNeeded() }, 1200)
            handler.postDelayed(batteryPoll, 1500)
            handler.postDelayed(ping, 2000)
            // The sensor changes the connection parameters ~4 s after connecting; drain its send
            // queue once that has happened (see docs/research-journal.md).
            handler.postDelayed({ flush() }, 7000)
            // The sensor can ask for its own (slower) connection parameters after connecting; the
            // first phone test saw a 45 ms interval. Ask again for the fast one once it has settled.
            for (d in longArrayOf(2500, 6000)) handler.postDelayed({
                gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            }, d)
        }

        // Android 13+ delivers the value directly; arrival time is taken before anything else.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            arrival = System.nanoTime()
            notifications++
            parser.feed(value)
        }
    }

    private val batteryPoll = object : Runnable {
        override fun run() { writeNow(Wit.readRegister(Wit.REG_BATTERY)); handler.postDelayed(this, 10_000) }
    }

    private val ping = object : Runnable {
        override fun run() {
            if (gatt == null) return
            if (!flushing) { pingSentAt = System.nanoTime(); writeNow(Wit.readRegister(Wit.REG_TEMP)) }
            handler.postDelayed(this, 2000)
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
        }
    }

    private fun configureIfNeeded() {
        if (gatt == null || write == null) return
        val wantRate = Wit.RATE_CODES.getValue(rateHz)
        val wantBw = Wit.BANDWIDTH_CODES.getValue(bandwidthHz)
        if (rateRegister == wantRate && bandwidthRegister == wantBw) return
        Log.i(TAG, "configuring: rate reg $rateRegister→$wantRate, bandwidth reg $bandwidthRegister→$wantBw")
        Wit.configure(rateHz, bandwidthHz).forEachIndexed { i, cmd -> handler.postDelayed({ writeNow(cmd) }, 150L * i) }
        handler.postDelayed({ writeNow(Wit.readRegister(Wit.REG_RATE)) }, 1500)
        handler.postDelayed({ writeNow(Wit.readRegister(Wit.REG_BANDWIDTH)) }, 1800)
    }

    /**
     * Experiment: hypothesis that the sensor keeps a queue of notifications that never drains (it
     * sends about as fast as it produces). Drop to 1 Hz for [ms] so the link can empty the queue,
     * then go back to [rateHz]. RAM only: nothing is saved to flash.
     */
    fun flush(ms: Long = 2500) = handler.post {
        if (gatt == null || write == null || flushing) return@post
        flushing = true; flushes++
        setStatus("streaming (flushing sensor queue)")
        Wit.setRateNoSave(1).forEachIndexed { i, c -> handler.postDelayed({ writeNow(c) }, 120L * i) }
        handler.postDelayed({
            Wit.setRateNoSave(rateHz).forEachIndexed { i, c -> handler.postDelayed({ writeNow(c) }, 120L * i) }
            handler.postDelayed({ flushing = false; setStatus("streaming") }, 400)
        }, ms)
    }

    private fun writeNow(cmd: ByteArray) {
        val g = gatt ?: return
        val c = write ?: return
        g.writeCharacteristic(c, cmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    private fun setStatus(s: String) { status = s; Log.i(TAG, s); onStatus?.invoke(s) }

    private companion object { const val TAG = "WitBle" }
}

enum class LinkMode(val label: String, val bigMtu: Boolean, val phy2m: Boolean) {
    DEFAULT_MTU("MTU 23, 1M PHY", false, false),
    DEFAULT_MTU_2M("MTU 23, 2M PHY", false, true),
    BIG_MTU("MTU 247, 2M PHY", true, true),
}
