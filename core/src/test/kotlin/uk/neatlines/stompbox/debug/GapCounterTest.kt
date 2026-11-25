package uk.neatlines.stompbox.debug

import org.junit.Assert.assertEquals
import org.junit.Test

class GapCounterTest {
    private val ms = 1_000_000L

    @Test fun countsGapsOverThresholdAndKeepsLongest() {
        val g = GapCounter(250.0)
        listOf(0L, 44, 88, 400, 444, 1000).forEach { g.mark(it * ms) }
        assertEquals(2, g.count)            // 312 ms and 556 ms
        assertEquals(556.0, g.longestMs, 1e-9)
    }

    @Test fun restartSkipsThePause() {
        val g = GapCounter(250.0)
        g.mark(0); g.restart(); g.mark(5000 * ms); g.mark(5044 * ms)
        assertEquals(0, g.count)
        assertEquals(44.0, g.longestMs, 1e-9)
    }

    @Test fun summaryNamesThreshold() {
        val g = GapCounter(200.0)
        g.record(310.0)
        assertEquals("1 >200 ms (longest 310 ms)", g.summary())
    }
}
