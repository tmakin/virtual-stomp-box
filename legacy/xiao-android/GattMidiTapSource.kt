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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import uk.neatlines.stompbox.detect.TapEvent
import uk.neatlines.stompbox.midi.BleMidiPacketParser
import java.util.UUID

/**
 * Same BLE-MIDI sensor as [MidiTapSource], but read straight from the GATT characteristic,
 * bypassing Android's MIDI service. Exists to measure whether the MIDI service adds delay
 * (an open question in the handoff). Use one source or the other, not both at once.
 *
 * Requests CONNECTION_PRIORITY_HIGH. Android has no public API for the interval it then grants;
 * read it from a Bluetooth HCI snoop log (LE Connection Update Complete event), see the README.
 */
@SuppressLint("MissingPermission")
class GattMidiTapSource(
    private val context: Context,
    private val onTap: (TapEvent) -> Unit,
) {
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val parser = BleMidiPacketParser()
    private var gatt: BluetoothGatt? = null
    private var scanning = false

    @Volatile var status = "idle"
        private set

    @Volatile var onArrival: ((Long) -> Unit)? = null

    fun start() {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: run { status = "Bluetooth off"; return }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(MidiTapSource.BLE_MIDI_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        scanning = true
        status = "scanning (raw GATT)…"
    }

    fun stop() {
        if (scanning) { scanning = false; adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        gatt?.close(); gatt = null
        status = "idle"
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            scanning = false
            adapter?.bluetoothLeScanner?.stopScan(this)
            connect(result.device)
        }
    }

    private fun connect(dev: BluetoothDevice) {
        status = "connecting ${dev.address}"
        gatt = dev.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                g.discoverServices()
                status = "discovering"
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                status = "disconnected"
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            val chr = g.getService(MidiTapSource.BLE_MIDI_SERVICE)?.getCharacteristic(MidiTapSource.BLE_MIDI_IO)
                ?: run { status = "no MIDI characteristic"; return }
            g.setCharacteristicNotification(chr, true)
            val cccd = chr.getDescriptor(CCCD) ?: return
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            status = "connected (raw GATT)"
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            handle(value)

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 33) handle(c.value ?: return)
        }
    }

    private fun handle(packet: ByteArray) {
        val arrival = System.nanoTime()
        parser.parse(packet) { _, velocity, _ ->
            onTap(TapEvent(SystemClock.elapsedRealtimeNanos(), velocity / 127f))
            onArrival?.invoke(arrival)
        }
    }

    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
