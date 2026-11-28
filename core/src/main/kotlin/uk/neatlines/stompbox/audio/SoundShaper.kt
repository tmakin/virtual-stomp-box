package uk.neatlines.stompbox.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tanh

/**
 * User sound settings for the stomp drum voices.
 *
 * @property pitchSemitones shifts every voice up or down, like a sampler: it is played faster or
 *   slower, so a lower pitch is also longer. Range [PITCH_RANGE].
 * @property tone -1 is dark (low-pass at a few hundred Hz), 0 is neutral (unchanged), +1 is bright
 *   (highs boosted).
 * @property reverb wet level, 0 (dry) to 1. The tail adds up to [REVERB_TAIL_S] seconds to each voice.
 */
data class SoundSettings(val pitchSemitones: Double = 0.0, val tone: Double = 0.0, val reverb: Double = 0.0) {
    fun clamped() = SoundSettings(
        pitchSemitones.coerceIn(-PITCH_RANGE, PITCH_RANGE), tone.coerceIn(-1.0, 1.0), reverb.coerceIn(0.0, 1.0),
    )

    /** "pitch=… tone=… reverb=…", read back by [parse]. */
    fun format() = "pitch=$pitchSemitones tone=$tone reverb=$reverb"

    companion object {
        const val PITCH_RANGE = 12.0
        const val REVERB_TAIL_S = 1.5

        /** Reads [format]'s output; unknown keys and bad numbers fall back to defaults. */
        fun parse(s: String?): SoundSettings {
            if (s.isNullOrBlank()) return SoundSettings()
            val m = s.split(' ').mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1].toDoubleOrNull() } }
                .filter { it.second != null && it.second!!.isFinite() }.associate { it.first to it.second!! }
            return SoundSettings(m["pitch"] ?: 0.0, m["tone"] ?: 0.0, m["reverb"] ?: 0.0).clamped()
        }
    }
}

/** Applies [SoundSettings] to a pre-rendered mono voice. Offline (not real-time): run it off the audio thread. */
object SoundShaper {
    fun apply(dry: FloatArray, s: SoundSettings, rate: Int): FloatArray {
        val c = s.clamped()
        if (c == SoundSettings()) return dry.copyOf()
        var x = pitch(dry, c.pitchSemitones)
        x = tone(x, c.tone, rate)
        if (c.reverb > 0) x = reverb(x, c.reverb, rate)
        return softClip(x, maxOf(peak(dry), 1f))
    }

    /** Resample by 2^(st/12) with linear interpolation: +12 is an octave up and half as long. */
    fun pitch(x: FloatArray, semitones: Double): FloatArray {
        if (semitones == 0.0 || x.isEmpty()) return x.copyOf()
        val ratio = 2.0.pow(semitones / 12)
        val n = (x.size / ratio).toInt().coerceAtLeast(1)
        return FloatArray(n) { i ->
            val p = i * ratio
            val j = p.toInt()
            if (j + 1 >= x.size) x.getOrElse(j) { 0f } else (x[j] + (x[j + 1] - x[j]) * (p - j)).toFloat()
        }
    }

    /** Dark: one-pole low-pass sliding from ~16 kHz down to ~400 Hz. Bright: add up to 2× the content above ~1.5 kHz. */
    fun tone(x: FloatArray, tone: Double, rate: Int): FloatArray {
        if (tone == 0.0) return x.copyOf()
        val out = FloatArray(x.size)
        if (tone < 0) {
            val fc = 16_000 * (400.0 / 16_000).pow(-tone)
            val a = onePole(fc, rate)
            var y = 0.0
            for (i in x.indices) { y += a * (x[i] - y); out[i] = y.toFloat() }
        } else {
            val a = onePole(1_500.0, rate)
            var lp = 0.0
            for (i in x.indices) { lp += a * (x[i] - lp); out[i] = (x[i] + 2 * tone * (x[i] - lp)).toFloat() }
        }
        return out
    }

    /**
     * Small-room Schroeder/Freeverb-style reverb: four damped parallel combs into two series all-passes,
     * mixed in at [wet] on top of the dry signal. The output is longer than the input by the tail.
     */
    fun reverb(x: FloatArray, wet: Double, rate: Int): FloatArray {
        val n = x.size + (SoundSettings.REVERB_TAIL_S * rate).toInt()
        val wetBuf = DoubleArray(n)
        val damp = 0.3
        for (ms in COMB_MS) {
            val d = (ms * rate / 1000).roundToInt()
            // Feedback for a ~1.2 s RT60 at this delay.
            val g = 10.0.pow(-3.0 * ms / 1000 / 1.2)
            val buf = DoubleArray(d); var k = 0; var store = 0.0
            for (i in 0 until n) {
                val input = if (i < x.size) x[i].toDouble() else 0.0
                val out = buf[k]
                store = out * (1 - damp) + store * damp
                buf[k] = input + store * g
                k = (k + 1) % d
                wetBuf[i] += out / COMB_MS.size
            }
        }
        for (ms in ALLPASS_MS) {
            val d = (ms * rate / 1000).roundToInt()
            val buf = DoubleArray(d); var k = 0
            for (i in 0 until n) {
                val b = buf[k]
                val v = wetBuf[i] + b * 0.5
                buf[k] = v
                wetBuf[i] = b - v * 0.5
                k = (k + 1) % d
            }
        }
        // Fade the very end so a cut-off tail does not click.
        val fade = (0.05 * rate).toInt().coerceAtMost(n)
        val gain = 2.5 * wet
        return FloatArray(n) { i ->
            val dry = if (i < x.size) x[i].toDouble() else 0.0
            val f = if (i >= n - fade) (n - i).toDouble() / fade else 1.0
            ((dry + gain * wetBuf[i]) * f).toFloat()
        }
    }

    fun peak(x: FloatArray) = x.maxOfOrNull { abs(it) } ?: 0f

    /**
     * Brightening and reverb can push peaks past full scale. Rather than scaling the whole voice down
     * (which would make the dry hit quieter), only samples above 80% of [ceiling] are squashed,
     * smoothly, so nothing exceeds [ceiling] and the body of the sound keeps its level.
     */
    fun softClip(x: FloatArray, ceiling: Float): FloatArray {
        val knee = 0.8f * ceiling
        val room = ceiling - knee
        for (i in x.indices) {
            val a = abs(x[i])
            if (a > knee) x[i] = Math.copySign(knee + room * tanh((a - knee) / room), x[i])
        }
        return x
    }

    private fun onePole(fc: Double, rate: Int) = 1 - exp(-2 * PI * fc / rate)

    private val COMB_MS = doubleArrayOf(29.7, 37.1, 41.1, 43.7)
    private val ALLPASS_MS = doubleArrayOf(5.0, 1.7)
}
