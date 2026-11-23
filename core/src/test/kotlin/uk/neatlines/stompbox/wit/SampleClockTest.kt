package uk.neatlines.stompbox.wit

import org.junit.Assert.assertTrue
import org.junit.Test
import uk.neatlines.stompbox.latency.LatencyStats
import uk.neatlines.stompbox.sim.Sim

class SampleClockTest {
    /** Spread (p5–p95, ms) of estimated − true sample time, after a warm-up. */
    private fun errorSpread(trueTimes: List<Long>, arrivals: List<Long>, rateHz: Double, skip: Int = 800): LatencyStats {
        val clock = SampleClock(rateHz)
        val err = trueTimes.indices.map { clock.onSample(arrivals[it]) - trueTimes[it] }
        return LatencyStats.of(err.drop(skip).map { it / 1e6 })!!
    }

    @Test fun removesConnectionIntervalJitter() {
        val sim = Sim()
        val t = List(6000) { it * sim.periodNanos }
        val a = sim.arrivals(t, ciMs = 11.25)
        val raw = LatencyStats.of(t.indices.map { (a[it] - t[it]) / 1e6 })!!
        val est = errorSpread(t, a, 200.0)
        assertTrue("raw arrival spread ${raw.spread90} should be ~CI", raw.spread90 > 8)
        assertTrue("clock spread ${est.spread90} ms", est.spread90 < 1.0)
    }

    @Test fun recoversFromDroppedFrames() {
        val sim = Sim(seed = 3)
        val all = List(8000) { it * sim.periodNanos }
        val keep = all.indices.filter { it !in 3000 until 3010 }     // ten frames lost, unseen by the clock
        val t = keep.map { all[it] }
        val a = sim.arrivals(t)
        val clock = SampleClock(200.0)
        val err = t.indices.map { clock.onSample(a[it]) - t[it] }
        val after = LatencyStats.of(err.drop(4500).map { it / 1e6 })!!
        val before = LatencyStats.of(err.subList(1000, 2990).map { it / 1e6 })!!
        assertTrue("resynced ${clock.resyncs}", clock.resyncs >= 1)
        assertTrue("offset after drop ${after.median} vs before ${before.median}", kotlin.math.abs(after.median - before.median) < 1.0)
    }

    @Test fun tracksSensorCrystalError() {
        val sim = Sim(seed = 5)
        val t = List(20000) { (it * sim.periodNanos * 1.003).toLong() }   // sensor 0.3 % slow
        val a = sim.arrivals(t)
        val est = errorSpread(t, a, 200.0, skip = 9000)
        assertTrue("spread ${est.spread90} ms", est.spread90 < 1.5)
    }
}
