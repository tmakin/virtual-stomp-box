package uk.neatlines.stompbox.wit

import java.util.UUID

/**
 * WitMotion BWT901BLE5.0 (WT901 BLE) protocol, taken from WitMotion's own SDKs
 * (github.com/WITMOTION/WitBluetooth_BWT901BLE5_0: Android_Java DeviceModel.java, Unity WT901BLE.cs,
 * Python device_model.py).
 *
 * The sensor notifies 20-byte frames on [NOTIFY]:
 *  - `55 61` + 9 little-endian int16: acc xyz (±16 g), gyro xyz (±2000 °/s), angle xyz (±180°).
 *    There is **no timestamp or sequence number** in this frame (see [SampleClock]).
 *  - `55 71 RL RH` + 8 int16 registers starting at register RL: the reply to [readRegister].
 */
object Wit {
    val SERVICE: UUID = UUID.fromString("0000ffe5-0000-1000-8000-00805f9a34fb")
    val NOTIFY: UUID = UUID.fromString("0000ffe4-0000-1000-8000-00805f9a34fb")
    val WRITE: UUID = UUID.fromString("0000ffe9-0000-1000-8000-00805f9a34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val REG_SAVE = 0x00
    const val REG_RATE = 0x03
    const val REG_BANDWIDTH = 0x1F
    const val REG_MAG = 0x3A
    const val REG_QUAT = 0x51
    const val REG_TEMP = 0x40
    const val REG_BATTERY = 0x64
    const val REG_UNLOCK = 0x69

    /** Return-rate register values, from the SDK's SetRRate / Unity SetReturnRate(0x0b) = 200 Hz. */
    val RATE_CODES = linkedMapOf(1 to 0x03, 5 to 0x05, 10 to 0x06, 50 to 0x08, 100 to 0x09, 200 to 0x0B)

    /**
     * Bandwidth register values as the Android SDK's SetBandwidth maps them. One public protocol
     * table is offset by one from this, so the app reads the register back after writing it and
     * shows the raw value in the debug screen. Use the widest (188 Hz) for stomps: the default
     * narrow filter smears the impact spike over several samples and delays it.
     */
    val BANDWIDTH_CODES = linkedMapOf(188 to 0x02, 98 to 0x03, 42 to 0x04, 20 to 0x05, 10 to 0x06, 5 to 0x07)

    fun write(reg: Int, value: Int): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xAA.toByte(), reg.toByte(), (value and 0xFF).toByte(), (value shr 8 and 0xFF).toByte())

    val UNLOCK: ByteArray = write(REG_UNLOCK, 0xB588)    // FF AA 69 88 B5
    val SAVE: ByteArray = write(REG_SAVE, 0x0000)        // FF AA 00 00 00

    fun setRate(hz: Int): ByteArray = write(REG_RATE, RATE_CODES[hz] ?: error("unsupported rate $hz Hz"))
    fun setBandwidth(hz: Int): ByteArray = write(REG_BANDWIDTH, BANDWIDTH_CODES[hz] ?: error("unsupported bandwidth $hz Hz"))
    fun readRegister(reg: Int): ByteArray = write(0x27, reg)

    /**
     * Commands to configure the sensor, each sent as its own write ~100 ms apart (the SDKs sleep
     * 100 ms between them). Ends with SAVE so the setting survives a power cycle; only send this when
     * the read-back differs, to avoid rewriting flash on every connect.
     */
    fun configure(rateHz: Int, bandwidthHz: Int): List<ByteArray> =
        listOf(UNLOCK, setRate(rateHz), UNLOCK, setBandwidth(bandwidthHz), SAVE)

    /** Battery voltage → percent, same table as the WitMotion SDK. */
    fun batteryPercent(volts: Double): Int = when {
        volts > 3.96 -> 100; volts > 3.93 -> 90; volts > 3.87 -> 75; volts > 3.82 -> 60
        volts > 3.79 -> 50; volts > 3.77 -> 40; volts > 3.73 -> 30; volts > 3.70 -> 20
        volts > 3.68 -> 15; volts > 3.50 -> 10; volts > 3.40 -> 5; else -> 0
    }
}

/** One 0x61 frame in physical units, in the sensor's own axes. */
data class WitSample(
    val ax: Float, val ay: Float, val az: Float,    // g
    val gx: Float, val gy: Float, val gz: Float,    // °/s
    val roll: Float, val pitch: Float, val yaw: Float, // °, sensor fusion output (angle X, Y, Z)
)

/**
 * Splits notification payloads into frames. Keeps a remainder between calls, so a frame split
 * across two notifications (possible with a larger MTU) still parses. Not thread-safe: feed it from
 * the GATT callback thread only.
 */
class WitPacketParser(
    private val onSample: (WitSample) -> Unit,
    private val onRegisters: (startReg: Int, values: ShortArray) -> Unit = { _, _ -> },
) {
    private val buf = ByteArray(256)
    private var len = 0
    var badBytes = 0L; private set
    var frames = 0L; private set

    fun feed(data: ByteArray, offset: Int = 0, count: Int = data.size - offset) {
        var i = offset
        while (i < offset + count) {
            val n = minOf(buf.size - len, offset + count - i)
            System.arraycopy(data, i, buf, len, n)
            len += n; i += n
            drain()
        }
    }

    private fun drain() {
        var p = 0
        while (len - p >= 2) {
            val kind = buf[p + 1].toInt() and 0xFF
            if (buf[p] != 0x55.toByte() || (kind != 0x61 && kind != 0x71)) { p++; badBytes++; continue }
            if (len - p < 20) break
            if (kind == 0x61) {
                onSample(
                    WitSample(
                        s16(p + 2) * ACC, s16(p + 4) * ACC, s16(p + 6) * ACC,
                        s16(p + 8) * GYRO, s16(p + 10) * GYRO, s16(p + 12) * GYRO,
                        s16(p + 14) * ANG, s16(p + 16) * ANG, s16(p + 18) * ANG,
                    )
                )
            } else {
                onRegisters(buf[p + 2].toInt() and 0xFF, ShortArray(8) { s16(p + 4 + 2 * it).toShort() })
            }
            frames++
            p += 20
        }
        System.arraycopy(buf, p, buf, 0, len - p)
        len -= p
    }

    private fun s16(i: Int): Int = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort().toInt()

    private companion object {
        const val ACC = 16f / 32768f
        const val GYRO = 2000f / 32768f
        const val ANG = 180f / 32768f
    }
}
