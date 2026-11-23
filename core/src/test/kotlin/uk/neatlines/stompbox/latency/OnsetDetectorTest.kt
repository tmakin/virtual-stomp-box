package uk.neatlines.stompbox.latency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.exp
import kotlin.math.sin

class OnsetDetectorTest {
    private val sr = 48_000

    /** Room noise with decaying 2 kHz "knocks" starting at the given frames. */
    private fun signal(frames: Int, hits: List<Int>, seed: Long = 1): FloatArray {
        val r = Random(seed)
        val x = FloatArray(frames) { (r.nextGaussian() * 0.002).toFloat() }
        for (h in hits) for (i in 0 until sr / 10) {
            if (h + i >= frames) break
            x[h + i] += (0.5 * exp(-i / (sr * 0.01)) * sin(2 * Math.PI * 2000 * i / sr)).toFloat()
        }
        return x
    }

    @Test fun findsEachHitWithinAMillisecond() {
        val hits = listOf(10_000, 30_000, 50_000, 70_000)
        val x = signal(90_000, hits)
        val found = ArrayList<Long>()
        val d = OnsetDetector(sr)
        // Feed in 192-frame buffers like AudioRecord would.
        var f = 0
        while (f < x.size) {
            val n = minOf(192, x.size - f)
            d.process(x.copyOfRange(f, f + n), n, f.toLong()) { found += it }
            f += n
        }
        assertEquals(hits.size, found.size)
        for ((h, o) in hits.zip(found)) assertTrue("onset $o for hit $h", o - h in 0..48)
    }

    @Test fun ringOutDoesNotRetrigger() {
        val x = signal(20_000, listOf(5_000))
        var count = 0
        OnsetDetector(sr).process(x, x.size, 0) { count++ }
        assertEquals(1, count)
    }

    @Test fun quietNoiseNeverTriggers() {
        val x = signal(sr * 2, emptyList())
        var count = 0
        OnsetDetector(sr).process(x, x.size, 0) { count++ }
        assertEquals(0, count)
    }
}
