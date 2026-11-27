package uk.neatlines.stompbox.stomp

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.neatlines.stompbox.wit.WitSample

class EagerTriggerTest {
    private fun sample(az: Float) = WitSample(0f, 0f, az, 0f, 0f, 0f, 0f, 0f, 0f)

    /** 1 s still, then a jolt just under the 0.5 g threshold as the last sample of a packet. */
    private fun run(eagerRatio: Double, joltG: Float): StompDetector {
        val d = StompDetector(StompConfig.SHOE.copy(minThresholdG = 0.5, eagerRatio = eagerRatio), onStomp = {})
        var t = 0L
        repeat(200) { d.onSample(sample(1f), t, t); t += 5_000_000 }
        d.onSample(sample(1f + joltG), t, t)
        d.onPacketEnd()
        return d
    }

    @Test fun offByDefault() = assertEquals(0, run(0.0, 0.5f).triggers)

    @Test fun firesOnLastSampleOverRatio() {
        val d = run(0.8, 0.5f)          // high-passed ≈ 0.44 g: over 0.8 × 0.5, under 0.5
        assertEquals(1, d.triggers); assertEquals(1, d.eagerTriggers)
    }

    @Test fun ignoresSmallJolt() = assertEquals(0, run(0.8, 0.3f).triggers)
}
