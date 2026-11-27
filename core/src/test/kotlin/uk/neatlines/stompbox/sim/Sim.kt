package uk.neatlines.stompbox.sim

import uk.neatlines.stompbox.stomp.AngleConvention
import uk.neatlines.stompbox.wit.WitSample
import kotlin.math.exp
import kotlin.random.Random

/**
 * Synthetic WT901 stream for tests: sensor lying flat on a shoe, 200 Hz, with stomps.
 * A stomp tilts the foot for 150 ms (heel or toe), then hits: a decaying ringing spike along gravity.
 */
class Sim(val rateHz: Int = 200, seed: Int = 1, val noiseG: Double = 0.02) {
    val rnd = Random(seed)
    val periodNanos = 1_000_000_000L / rateHz

    data class Stomp(val atSample: Int, val peakG: Double, val pitchDeg: Double)

    /** Samples with their true measurement time (ns from 0). */
    fun stream(totalSamples: Int, stomps: List<Stomp>, conv: AngleConvention = AngleConvention()): List<Pair<Long, WitSample>> =
        List(totalSamples) { n ->
            var pitch = 0.0
            var spike = 0.0
            for (s in stomps) {
                val k = n - s.atSample
                if (k in -30..5) pitch = s.pitchDeg              // foot tilted before and during the hit
                if (k in 0..15) spike += s.peakG * exp(-k / 2.0) * (if (k % 2 == 0) 1.0 else -0.6)
            }
            val g = conv.gravity(0.0, pitch)
            fun nz() = rnd.nextDouble(-noiseG, noiseG)
            n * periodNanos to WitSample(
                (g.x * (1 + spike) + nz()).toFloat(), (g.y * (1 + spike) + nz()).toFloat(), (g.z * (1 + spike) + nz()).toFloat(),
                rnd.nextDouble(-2.0, 2.0).toFloat(), rnd.nextDouble(-2.0, 2.0).toFloat(), rnd.nextDouble(-2.0, 2.0).toFloat(),
                0f, pitch.toFloat(), 0f,
            )
        }

    /**
     * BLE delivery: each sample waits for the next connection event after it is ready, plus a
     * random host delay; occasionally an event is missed and the frame waits one more interval.
     */
    fun arrivals(sampleTimes: List<Long>, ciMs: Double = 11.25, baseDelayMs: Double = 2.0, missProb: Double = 0.02): List<Long> {
        val ci = (ciMs * 1e6).toLong()
        val phase = rnd.nextLong(ci)
        var last = 0L
        return sampleTimes.map { t ->
            var ev = ((t - phase) / ci + 1) * ci + phase
            while (rnd.nextDouble() < missProb) ev += ci
            val a = maxOf(last, ev + (baseDelayMs * 1e6).toLong() + rnd.nextLong(300_000))
            last = a
            a
        }
    }
}
