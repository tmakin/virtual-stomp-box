package uk.neatlines.stompbox.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class SoundShaperTest {
    private val rate = 48_000
    private fun sine(hz: Double, s: Double = 0.2) = FloatArray((s * rate).toInt()) { (0.8 * sin(2 * PI * hz * it / rate)).toFloat() }
    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size) =
        kotlin.math.sqrt((from until to).sumOf { x[it].toDouble() * x[it] } / (to - from))
    private fun zeroCrossings(x: FloatArray) = (1 until x.size).count { (x[it - 1] < 0) != (x[it] < 0) }

    @Test fun defaultsLeaveTheSoundUnchanged() {
        val x = sine(200.0)
        assertArrayEquals(x, SoundShaper.apply(x, SoundSettings(), rate), 0f)
    }

    @Test fun octaveUpDoublesFrequencyAndHalvesLength() {
        val x = sine(200.0)
        val y = SoundShaper.pitch(x, 12.0)
        assertEquals(x.size / 2, y.size)
        assertEquals(zeroCrossings(x).toDouble(), zeroCrossings(y).toDouble(), 2.0)   // same cycles in half the time
        assertEquals(x.size * 2.0, SoundShaper.pitch(x, -12.0).size.toDouble(), 1.0)
    }

    @Test fun darkCutsHighsBrightBoostsThem() {
        val hi = sine(5_000.0); val lo = sine(100.0)
        assertTrue(rms(SoundShaper.tone(hi, -1.0, rate)) < 0.2 * rms(hi))
        assertTrue(rms(SoundShaper.tone(lo, -1.0, rate)) > 0.8 * rms(lo))
        assertTrue(rms(SoundShaper.tone(hi, 1.0, rate)) > 1.8 * rms(hi))
        assertTrue(abs(rms(SoundShaper.tone(lo, 1.0, rate)) - rms(lo)) < 0.15 * rms(lo))
    }

    @Test fun reverbAddsADecayingTailWithoutClipping() {
        val click = FloatArray(2_000) { if (it < 100) 0.9f else 0f }
        val y = SoundShaper.apply(click, SoundSettings(reverb = 1.0), rate)
        assertEquals(click.size + (SoundSettings.REVERB_TAIL_S * rate).toInt(), y.size)
        val early = rms(y, 3_000, 8_000); val late = rms(y, 40_000, 45_000)
        assertTrue("tail present", early > 1e-3)
        assertTrue("tail decays", late < early / 4)
        assertTrue(SoundShaper.peak(y) <= 1f)
        assertEquals(0f, y.last(), 1e-6f)
    }

    @Test fun brightKeepsTheBodyLevelAndStaysUnderFullScale() {
        // A 100 Hz body with a loud 5 kHz attack: the bright boost pushes the attack past full scale.
        val x = FloatArray(9_600) { i -> val t = i.toDouble() / rate
            (0.6 * sin(2 * PI * 100 * t) + (if (i < 480) 0.4 * sin(2 * PI * 5_000 * t) else 0.0)).toFloat() }
        val y = SoundShaper.apply(x, SoundSettings(tone = 1.0), rate)
        assertTrue(SoundShaper.peak(y) <= 1f)
        val body = rms(x, 2_400, 9_600); val bodyOut = rms(y, 2_400, 9_600)
        assertEquals("body not turned down", body, bodyOut, 0.1 * body)
    }

    @Test fun settingsRoundTripAndClamp() {
        val s = SoundSettings(-3.0, 0.4, 0.25)
        assertEquals(s, SoundSettings.parse(s.format()))
        assertEquals(SoundSettings(12.0, -1.0, 1.0), SoundSettings.parse("pitch=40 tone=-5 reverb=9 junk=x"))
        assertEquals(SoundSettings(), SoundSettings.parse(null))
        assertEquals(SoundSettings(), SoundSettings.parse("pitch=NaN"))
    }
}
