package uk.neatlines.stompbox.stomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.neatlines.stompbox.latency.LatencyStats
import uk.neatlines.stompbox.sim.Sim

class StompDetectorTest {
    private val conv = AngleConvention()
    private val placement = Placement.calibrate(conv.gravity(0.0, 0.0), 0.0, 0.0, tiltAcc = conv.gravity(0.0, 15.0))

    private fun stomps(n: Int, gap: Int = 100) = List(n) { i ->
        Sim.Stomp(200 + i * gap, peakG = 2.0 + (i % 4), pitchDeg = listOf(0.0, 14.0, -14.0)[i % 3])
    }

    private fun runDetector(cfg: StompConfig, stomps: List<Sim.Stomp>, total: Int = 200 + stomps.size * 100 + 200): List<StompEvent> {
        val out = ArrayList<StompEvent>()
        val d = StompDetector(cfg, placement, onStomp = { out += it })
        for ((t, s) in Sim().stream(total, stomps)) d.onSample(s, t, t)
        return out
    }

    @Test fun detectsEveryStompOnceWithZones() {
        val st = stomps(30)
        for (feature in Feature.entries) {
            val ev = runDetector(StompConfig.SHOE.copy(feature = feature, zonesEnabled = true), st)
            assertEquals("$feature count", st.size, ev.size)
            ev.zip(st).forEach { (e, s) ->
                assertEquals("$feature timing", s.atSample.toLong(), e.sampleIndex)
                val want = when { s.pitchDeg > 5 -> Zone.HEEL; s.pitchDeg < -5 -> Zone.TOE; else -> Zone.FLAT }
                if (feature == Feature.VERTICAL) assertEquals(want, e.zone)
            }
        }
    }

    @Test fun quietStreamNeverTriggers() {
        assertTrue(runDetector(StompConfig.SHOE, emptyList(), total = 20000).isEmpty())
        assertTrue(runDetector(StompConfig.LEG, emptyList(), total = 20000).isEmpty())
    }

    @Test fun deadTimeBlocksSecondHitButAllowsFastRolls() {
        // Two hits 40 ms apart: inside the 90 ms dead time, so one event.
        val close = listOf(Sim.Stomp(200, 3.0, 0.0), Sim.Stomp(208, 3.0, 0.0))
        assertEquals(1, runDetector(StompConfig.SHOE, close).size)
        // 120 ms apart (500 bpm 8ths): two events.
        val apart = listOf(Sim.Stomp(200, 3.0, 0.0), Sim.Stomp(224, 3.0, 0.0))
        assertEquals(2, runDetector(StompConfig.SHOE, apart).size)
    }

    @Test fun harderStompsGetHigherVelocity() {
        val ev = runDetector(StompConfig.SHOE, listOf(Sim.Stomp(200, 1.8, 0.0), Sim.Stomp(400, 5.0, 0.0)))
        assertTrue("${ev.map { it.velocity }}", ev[1].velocity > ev[0].velocity + 30)
    }

    @Test fun steadyTimingRemovesBleJitter() {
        val sim = Sim(seed = 7)
        val st = stomps(60)
        val samples = sim.stream(200 + 60 * 100 + 200, st)
        val arrivals = sim.arrivals(samples.map { it.first })
        fun spread(timing: Timing): LatencyStats {
            val fired = ArrayList<Pair<StompEvent, Long>>()
            val p = StompPipeline(StompConfig.SHOE.copy(timing = timing), placement) { e, at -> fired += e to at }
            samples.forEachIndexed { i, (_, s) -> p.onSample(s, arrivals[i]) }
            assertEquals(st.size, fired.size)
            return LatencyStats.of(fired.map { (e, at) -> (at - e.sampleIndex * sim.periodNanos) / 1e6 })!!
        }
        val imm = spread(Timing.IMMEDIATE)
        val steady = spread(Timing.STEADY)
        assertTrue("immediate ±${imm.spread90 / 2}", imm.spread90 / 2 > 4)
        assertTrue("steady ±${steady.spread90 / 2}", steady.spread90 / 2 < 1)
    }

    @Test fun configRoundTrips() {
        val c = StompConfig.LEG.copy(minThresholdG = 0.9, timing = Timing.STEADY)
        assertEquals(c, StompConfig().with(c.toMap()))
    }
}
