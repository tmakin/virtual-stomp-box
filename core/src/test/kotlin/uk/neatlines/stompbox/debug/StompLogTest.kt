package uk.neatlines.stompbox.debug

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.neatlines.stompbox.sim.Sim
import uk.neatlines.stompbox.stomp.AngleConvention
import uk.neatlines.stompbox.stomp.Placement
import uk.neatlines.stompbox.stomp.StompConfig
import uk.neatlines.stompbox.stomp.StompEvent
import uk.neatlines.stompbox.stomp.StompPipeline
import java.io.StringReader
import java.io.StringWriter

class StompLogTest {
    @Test fun recordingReplaysToTheSameStomps() {
        val conv = AngleConvention()
        val placement = Placement.calibrate(conv.gravity(0.0, 0.0), 0.0, 0.0, conv.gravity(0.0, 15.0))
        val cfg = StompConfig.SHOE.copy(minThresholdG = 1.1)
        val sim = Sim()
        val samples = sim.stream(3000, List(10) { Sim.Stomp(200 + it * 250, 3.0, if (it % 2 == 0) 12.0 else 0.0) })
        val arrivals = sim.arrivals(samples.map { it.first })

        val sw = StringWriter()
        val w = StompLogWriter(sw)
        w.header(cfg, placement, mapOf("rateHz" to "200", "phone" to "Pixel 8"))
        val live = ArrayList<StompEvent>()
        val p = StompPipeline(cfg, placement) { e, at -> live += e; w.stomp(e, at) }
        samples.forEachIndexed { i, (t, s) -> w.sample(arrivals[i], s); p.onSample(s, arrivals[i]); if (i % 500 == 0) w.onset(t) }
        w.flush()

        val log = StompLog.read(StringReader(sw.toString()))
        assertEquals(cfg, log.config)
        assertEquals("Pixel_8", log.info["phone"])
        assertEquals(samples.size, log.samples.size)
        assertEquals(6, log.onsets.size)
        assertEquals(live.size, log.stomps.size)

        val replayed = ArrayList<StompEvent>()
        val r = StompPipeline(log.config, log.placement) { e, _ -> replayed += e }
        log.samples.forEach { (t, s) -> r.onSample(s, t) }
        assertEquals(live.map { it.sampleIndex to it.zone }, replayed.map { it.sampleIndex to it.zone })
    }
}
