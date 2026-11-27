package uk.neatlines.stompbox.wit

/**
 * Gives each sensor sample an estimate of when it was *measured*, on the phone's clock.
 *
 * The WT901 frame carries no timestamp, and BLE delivers frames in bursts, one burst per
 * connection event (every 11.25–15 ms on a Pixel), so arrival times jitter by a whole connection
 * interval. But the sensor samples at a steady rate. Sample n was taken at about
 * `offset + n × period`, where `offset` is the *smallest* `arrival − n × period` seen: the frames
 * that got through with the least delay.
 *
 * - `period` is re-estimated from the arrival rate over a long window, which tracks the sensor's
 *   crystal error.
 * - `offset` creeps forward slowly ([creepNanosPerSample]) so the estimate follows drift, and jumps
 *   when every frame in the last [windowSamples] arrived late by more than [resyncNanos]. That
 *   happens after dropped frames, which this protocol cannot otherwise detect.
 *
 * The estimate lags the true time by roughly the minimum link delay (a few ms), which is a
 * constant and does not add jitter.
 */
class SampleClock(
    nominalRateHz: Double,
    private val windowSamples: Int = 400,           // 2 s at 200 Hz
    private val rateWindowSamples: Int = 4000,      // 20 s at 200 Hz
    private val creepNanosPerSample: Long = 2_000L, // 0.4 ms/s at 200 Hz
    private val resyncNanos: Long = 4_000_000L,
) {
    var periodNanos: Double = 1e9 / nominalRateHz; private set
    var index: Long = -1; private set
    var resyncs = 0; private set

    private var offset = Long.MAX_VALUE
    private var originIndex = 0L        // index that `offset` refers to, so period changes don't jump the clock
    private val recentResidual = LongArray(windowSamples)
    private val arrivalHistory = LongArray(rateWindowSamples)

    /** Start again at a new nominal rate (after the sensor's rate register is changed). */
    fun reset(nominalRateHz: Double) {
        periodNanos = 1e9 / nominalRateHz; index = -1; offset = Long.MAX_VALUE; originIndex = 0
        recentResidual.fill(0); arrivalHistory.fill(0)
    }

    /** Arrival time minus estimated sample time of the latest sample: link delay plus a constant. */
    var lastLagNanos: Long = 0; private set

    /** Call once per sample, in order, with its arrival time. Returns the estimated sample time. */
    fun onSample(arrivalNanos: Long): Long {
        index++
        arrivalHistory[(index % rateWindowSamples).toInt()] = arrivalNanos
        if (index >= rateWindowSamples && index % windowSamples == 0L) {
            val old = arrivalHistory[((index + 1) % rateWindowSamples).toInt()]
            val est = (arrivalNanos - old).toDouble() / (rateWindowSamples - 1)
            // Re-anchor at the current index before changing the period, then accept only sane values.
            if (est in periodNanos * 0.9..periodNanos * 1.1) {
                offset = predict(index); originIndex = index; periodNanos = est
            }
        }
        if (offset == Long.MAX_VALUE) { offset = arrivalNanos; originIndex = index }
        var residual = arrivalNanos - predict(index)
        if (residual < 0) { offset += residual; residual = 0 }
        recentResidual[(index % windowSamples).toInt()] = residual
        if (index >= windowSamples && index % (windowSamples / 4) == 0L) {
            val minLate = recentResidual.min()
            if (minLate > resyncNanos) {
                offset += minLate; resyncs++
                for (k in recentResidual.indices) recentResidual[k] -= minLate
            }
        }
        offset += creepNanosPerSample
        val t = predict(index)
        lastLagNanos = arrivalNanos - t
        return t
    }

    private fun predict(i: Long): Long = offset + ((i - originIndex) * periodNanos).toLong()
}
