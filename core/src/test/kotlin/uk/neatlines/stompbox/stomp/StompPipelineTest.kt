package uk.neatlines.stompbox.stomp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.neatlines.stompbox.wit.WitSample

class StompPipelineTest {
    private val still = WitSample(0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f)

    @Test
    fun restartClockStartsAgainFromTheNextSample() {
        val p = StompPipeline(StompConfig.SHOE) { _, _ -> }
        var t = 0L
        repeat(100) { p.onSample(still, t); t += 5_000_000 }
        assertEquals(99L, p.clock.index)
        // Switching link: samples resume 10 s later. Without a restart the clock would place the
        // next one at its old position in the sequence.
        p.restartClock()
        t += 10_000_000_000
        p.onSample(still, t)
        assertEquals(0L, p.clock.index)
        assertTrue(kotlin.math.abs(p.clock.lastLagNanos) < 1_000_000)
    }
}
