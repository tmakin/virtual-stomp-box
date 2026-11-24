package uk.neatlines.stompbox.latency

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatencyMeterTest {
    private val ms = 1_000_000L

    @Test fun splitsDetectOutputAndTotal() {
        val m = LatencyMeter()
        // Three stomps 1.5 s apart: impact heard at t, detected 380 ms later, drum heard 30 ms after play().
        for (k in 0 until 3) {
            val t = 10_000 * ms + k * 1500 * ms
            m.onset(t)
            m.stomp(t + 380 * ms, t + 381 * ms)
            m.onset(t + 411 * ms)
        }
        val r = m.poll(20_000 * ms)
        assertEquals(3, r.size)
        r.forEach {
            assertEquals(380.0, it.detectMs, 0.01)
            assertEquals(30.0, it.outputMs!!, 0.01)
            assertEquals(411.0, it.totalMs!!, 0.01)
        }
    }

    @Test fun waitsForLateOnsetsAndHandlesMissingDrum() {
        val m = LatencyMeter()
        m.onset(1000 * ms)
        m.stomp(1020 * ms, 1020 * ms)
        assertEquals(0, m.poll(1100 * ms).size)          // not settled yet
        val r = m.poll(3000 * ms)
        assertEquals(1, r.size)
        assertEquals(20.0, r[0].detectMs, 0.01)
        assertNull(r[0].totalMs)                           // sound off: no drum heard
    }

    @Test fun countsStompsWithNoImpactHeard() {
        val m = LatencyMeter()
        m.stomp(5000 * ms, 5000 * ms)
        assertEquals(0, m.poll(9000 * ms).size)
        assertEquals(1, m.unpaired)
    }

    @Test fun ringingImpactUsesFirstOnset() {
        val m = LatencyMeter()
        m.onset(1000 * ms); m.onset(1040 * ms); m.onset(1090 * ms)   // one impact, ringing
        m.stomp(1100 * ms, 1100 * ms)
        m.onset(1130 * ms)
        val r = m.poll(5000 * ms).single()
        assertEquals(100.0, r.detectMs, 0.01)
        assertEquals(130.0, r.totalMs!!, 0.01)
    }
}
