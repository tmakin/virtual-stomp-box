package uk.neatlines.stompbox.debug

import uk.neatlines.stompbox.latency.LatencyStats

/**
 * What the BLE link is actually doing, worked out from frame arrival times alone.
 *
 * Frames that arrive within [burstGapNanos] of each other came in the same connection event, so
 * the gap between bursts is the connection interval, which is the number that sets the jitter.
 * Keeps the last [capacity] samples, enough for a few seconds at 200 Hz.
 */
class LinkStats(private val capacity: Int = 2000, private val burstGapNanos: Long = 1_500_000L) {
    private val arrivals = LongArray(capacity)
    private val lags = LongArray(capacity)
    private var n = 0L

    fun add(arrivalNanos: Long, clockLagNanos: Long) {
        val i = (n % capacity).toInt()
        arrivals[i] = arrivalNanos; lags[i] = clockLagNanos; n++
    }

    data class Snapshot(
        val samples: Int,
        val sampleRateHz: Double,
        val framesPerBurst: Double,
        /** Gap between bursts: median ≈ the connection interval. */
        val burstGapMs: LatencyStats?,
        /** Arrival minus estimated sample time: the link delay seen by IMMEDIATE timing. */
        val lagMs: LatencyStats?,
    ) {
        fun format(): String = buildString {
            append("rate %.1f Hz, %.2f frames/event".format(java.util.Locale.US, sampleRateHz, framesPerBurst))
            burstGapMs?.let { append(", interval median %.2f ms p95 %.2f".format(java.util.Locale.US, it.median, it.p95)) }
            lagMs?.let { append(", lag median %.1f p95 %.1f max %.1f ms".format(java.util.Locale.US, it.median, it.p95, it.max)) }
        }
    }

    fun snapshot(): Snapshot {
        val m = minOf(n, capacity.toLong()).toInt()
        if (m < 2) return Snapshot(m, 0.0, 0.0, null, null)
        val start = n - m
        val t = LongArray(m) { arrivals[((start + it) % capacity).toInt()] }
        val gaps = ArrayList<Double>()
        var bursts = 1
        for (k in 1 until m) {
            val d = t[k] - t[k - 1]
            if (d > burstGapNanos) { bursts++; gaps += d / 1e6 }
        }
        val rate = (m - 1) * 1e9 / (t[m - 1] - t[0]).coerceAtLeast(1)
        val lag = List(m) { lags[((start + it) % capacity).toInt()] / 1e6 }
        return Snapshot(m, rate, m.toDouble() / bursts, LatencyStats.of(gaps), LatencyStats.of(lag))
    }
}
