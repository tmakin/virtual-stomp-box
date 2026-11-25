package uk.neatlines.stompbox.input

import uk.neatlines.stompbox.debug.GapCounter

/**
 * A link to the WT901: Bluetooth ([WitBleSource]) or the USB cable ([WitUsbSource]). Both deliver
 * the same `55 61` frames, each with its arrival time on the System.nanoTime clock.
 */
interface SensorSource {
    /** "Bluetooth" or "USB cable", for the UI. */
    val kind: String
    /** Output rate to set on the sensor. Changing it while connected reconfigures the sensor. */
    var rateHz: Int
    val status: String
    val deviceName: String?
    val batteryVolts: Double
    val rateRegister: Int
    val bandwidthRegister: Int
    /** Times the link dropped and was reconnected since [start]. */
    val reconnects: Int
    /** Gaps in the incoming data while streaming. */
    val gaps: GapCounter
    /** Writes to the sensor that failed. */
    val failedWrites: Int
    /** Register-read round trip (write `FF AA 27 40` → `55 71` reply), timed every 5 s. */
    val pingSummary: String
    /** True from [start] until [stop], including while waiting or reconnecting. */
    val running: Boolean
    fun start()
    fun stop()
}
