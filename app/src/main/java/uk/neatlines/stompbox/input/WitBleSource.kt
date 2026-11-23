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
    private val rateHz: Int = 200,
    private val bandwidthHz: Int = 188,
    /** Called on the GATT binder thread. Keep it short: it is on the latency path. */
    private val onSample: (WitSample, arrivalNanos: Long) -> Unit,
) {
    @Volatile var status: String = "idle"; private set
    @Volatile var batteryVolts: Double = Double.NaN; private set
    @Volatile var rateRegister: Int = -1; private set
    @Volatile var bandwidthRegister: Int = -1; private set
    @Volatile var deviceName: String? = null; private set
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
                setStatus("connected, setting up")
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_OPTION_NO_PREFERRED)
                handler.postDelayed({ if (!g.requestMtu(247)) g.discoverServices() }, 200)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                setStatus("disconnected ($st), retrying")
                g.close(); gatt = null; write = null
                if (!stopped) handler.postDelayed({ connect(g.device) }, 1000)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, st: Int) { g.discoverServices() }

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
        }

        // Android 13+ delivers the value directly; arrival time is taken before anything else.
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            arrival = System.nanoTime()
            parser.feed(value)
        }
    }

    private val batteryPoll = object : Runnable {
        override fun run() { writeNow(Wit.readRegister(Wit.REG_BATTERY)); handler.postDelayed(this, 10_000) }
    }

    private fun onRegisters(reg: Int, v: ShortArray) {
        when (reg) {
            Wit.REG_RATE -> rateRegister = v[0].toInt()
            Wit.REG_BANDWIDTH -> bandwidthRegister = v[0].toInt()
            Wit.REG_BATTERY -> batteryVolts = v[0] / 100.0
        }
    }

    private fun configureIfNeeded() {
        val wantRate = Wit.RATE_CODES.getValue(rateHz)
        val wantBw = Wit.BANDWIDTH_CODES.getValue(bandwidthHz)
        if (rateRegister == wantRate && bandwidthRegister == wantBw) return
        Log.i(TAG, "configuring: rate reg $rateRegister→$wantRate, bandwidth reg $bandwidthRegister→$wantBw")
        Wit.configure(rateHz, bandwidthHz).forEachIndexed { i, cmd -> handler.postDelayed({ writeNow(cmd) }, 150L * i) }
        handler.postDelayed({ writeNow(Wit.readRegister(Wit.REG_RATE)) }, 1500)
        handler.postDelayed({ writeNow(Wit.readRegister(Wit.REG_BANDWIDTH)) }, 1800)
    }

    private fun writeNow(cmd: ByteArray) {
        val g = gatt ?: return
        val c = write ?: return
        g.writeCharacteristic(c, cmd, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
    }

    private fun setStatus(s: String) { status = s; Log.i(TAG, s); onStatus?.invoke(s) }

    private companion object { const val TAG = "WitBle" }
}
