package uk.neatlines.stompbox.latency

import org.junit.Assert.assertEquals
import org.junit.Test

class TapPairerTest {
    private fun ms(vararg v: Double) = v.map { (it * 1e6).toLong() }

    @Test fun pairsEventsWithTheirSounds() {
        val r = TapPairer.pair(ms(100.0, 600.0, 1100.0), ms(109.0, 611.5, 1107.0))
        assertEquals(listOf(9.0, 11.5, 7.0), r.delaysMs)
        assertEquals(0, r.missed)
        assertEquals(0, r.falseTriggers)
    }

    @Test fun countsMissesAndFalseTriggers() {
        // 600 ms tap has no event; 900 ms event has no sound.
        val r = TapPairer.pair(ms(100.0, 600.0, 1100.0), ms(110.0, 900.0, 1110.0))
        assertEquals(listOf(10.0, 10.0), r.delaysMs)
        assertEquals(1, r.missed)
        assertEquals(1, r.falseTriggers)
    }

    @Test fun eventSlightlyBeforeSoundIsAccepted() {
        val r = TapPairer.pair(ms(100.0), ms(99.0))
        assertEquals(listOf(-1.0), r.delaysMs)
    }

    @Test fun trailingSoundIsAMiss() {
        val r = TapPairer.pair(ms(100.0, 500.0), ms(108.0))
        assertEquals(1, r.missed)
    }

    @Test fun statsMedianAndP95() {
        val s = LatencyStats.of((1..100).map { it.toDouble() })!!
        assertEquals(50.0, s.median, 1e-9)
        assertEquals(95.0, s.p95, 1e-9)
        assertEquals(90.0, s.spread90, 1e-9)
    }
}
