package uk.neatlines.stompbox.latency

import kotlin.math.ceil
import kotlin.math.sqrt

/** Summary of a latency run, all in milliseconds. */
data class LatencyStats(
    val n: Int,
    val median: Double,
    val p95: Double,
    val min: Double,
    val max: Double,
    val mean: Double,
    val stdDev: Double,
    /** p95 − p5: the spread that contains 90 % of taps. Half of it is the "± jitter" figure. */
    val spread90: Double,
) {
    fun format(): String = String.format(
        java.util.Locale.US,
        "n=%d  median %.1f ms  p95 %.1f ms  min %.1f  max %.1f  sd %.1f  spread(p5–p95) %.1f ms (±%.1f)",
        n, median, p95, min, max, stdDev, spread90, spread90 / 2,
    )

    companion object {
        fun of(valuesMs: Collection<Double>): LatencyStats? {
            if (valuesMs.isEmpty()) return null
            val s = valuesMs.sorted()
            val mean = s.average()
            val sd = sqrt(s.sumOf { (it - mean) * (it - mean) } / s.size)
            return LatencyStats(
                n = s.size,
                median = percentile(s, 50.0),
                p95 = percentile(s, 95.0),
                min = s.first(),
                max = s.last(),
                mean = mean,
                stdDev = sd,
                spread90 = percentile(s, 95.0) - percentile(s, 5.0),
            )
        }

        /** Nearest-rank percentile of an ascending list. */
        fun percentile(sorted: List<Double>, p: Double): Double {
            val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
            return sorted[rank - 1]
        }
    }
}
