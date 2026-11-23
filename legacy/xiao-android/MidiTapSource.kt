package uk.neatlines.stompbox.input

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.Process
import android.os.SystemClock
import uk.neatlines.stompbox.detect.TapEvent
import uk.neatlines.stompbox.midi.MidiTapParser
import java.util.UUID

/**
 * Taps from a MIDI foot sensor that detects taps itself (the XIAO firmware, Freedrum, or any
 * BLE-MIDI / USB-MIDI device), through Android's built-in MIDI service. No vendor SDK.
 *
 * On [start] it opens the first MIDI device that is already attached (USB cable, or BLE opened
 * by another app); if there is none it scans for the BLE-MIDI service and opens the first device
 * it finds. Every note-on becomes a [TapEvent] passed to [onTap] on a high-priority thread.
 *
 * Needs BLUETOOTH_SCAN + BLUETOOTH_CONNECT (API 31+) or ACCESS_FINE_LOCATION (API 29–30) at
 * runtime for the BLE path. USB needs no permission.
 */
class MidiTapSource(
    context: Context,
    private val onTap: (TapEvent) -> Unit,
) {
    private val midi = context.getSystemService(Context.MIDI_SERVICE) as MidiManager?
    private val bluetooth = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
    private val parser = MidiTapParser()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: MidiDevice? = null
    private var port: MidiOutputPort? = null
    private var scanning = false

    /** Human-readable connection state for the status line. */
    @Volatile var status: String = "idle"
        private set

    /**
     * Arrival time minus the timestamp Android attached to the message, ms. For BLE MIDI the
     * service derives that timestamp from the sender's BLE-MIDI timestamp, so this is an estimate
     * of the radio + stack delay. 0 if the timestamp was missing.
     */
    @Volatile var linkLatencyMs = 0f
        private set

    /** Optional hook for the latency test: arrival time of each tap, System.nanoTime base. */
    @Volatile var onArrival: ((Long) -> Unit)? = null

    val isAvailable get() = midi != null

    fun start() {
        val m = midi ?: run { status = "no MIDI service"; return }
        if (thread != null) return
        val t = HandlerThread("midi-taps", Process.THREAD_PRIORITY_URGENT_AUDIO).also { it.start() }
        thread = t
        handler = Handler(t.looper)
        parser.reset()

        @Suppress("DEPRECATION") // getDevices() is fine for a single sensor; getDevicesForTransport needs API 33
        val attached = m.devices.firstOrNull { it.outputPortCount > 0 }
        if (attached != null) {
            status = "opening ${name(attached)}"
            m.openDevice(attached, ::onOpened, handler)
        } else {
            startScan()
        }
    }

    fun stop() {
        stopScan()
        port?.close(); port = null
        device?.close(); device = null
        thread?.quitSafely(); thread = null
        handler = null
        status = "idle"
    }

    // ---------------------------------------------------------------------------------------

    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            val arrival = System.nanoTime()
            parser.parse(msg, offset, count) { _, velocity ->
                // TapEvent uses elapsedRealtimeNanos; MIDI timestamps use System.nanoTime.
                val stamp = if (timestamp in 1..arrival) timestamp else arrival
                linkLatencyMs = if (stamp == arrival) 0f else (arrival - stamp) / 1e6f
                val eventTime = SystemClock.elapsedRealtimeNanos() - (arrival - stamp)
                onTap(TapEvent(eventTime, velocity / 127f))
                onArrival?.invoke(arrival)
            }
        }
    }

    private fun onOpened(d: MidiDevice?) {
        if (d == null) { status = "open failed"; return }
        device = d
        val p = d.openOutputPort(0)
        if (p == null) { status = "no output port"; return }
        p.connect(receiver)
        port = p
        status = "connected: ${name(d.info)}"
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val scanner = bluetooth?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: run { status = "Bluetooth off"; return }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BLE_MIDI_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true
            status = "scanning for BLE MIDI…"
        } catch (e: SecurityException) {
            status = "Bluetooth permission needed"
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { bluetooth?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: SecurityException) {}
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            stopScan()
            openBle(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            status = "scan failed ($errorCode)"
        }
    }

    @SuppressLint("MissingPermission")
    private fun openBle(dev: BluetoothDevice) {
        val m = midi ?: return
        status = "connecting ${dev.name ?: dev.address}"
        // BluetoothMidiService owns the GATT connection and asks for a high-priority
        // (short) connection interval itself; the firmware also requests 7.5–11.25 ms.
        m.openBluetoothDevice(dev, ::onOpened, handler)
    }

    private fun name(info: MidiDeviceInfo): String =
        info.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI device"

    companion object {
        val BLE_MIDI_SERVICE: UUID = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
        val BLE_MIDI_IO: UUID = UUID.fromString("7772E5DB-3868-4112-A1A9-F2669D106BF3")
    }
}
