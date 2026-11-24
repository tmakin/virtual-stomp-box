package uk.neatlines.stompbox.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import uk.neatlines.stompbox.stomp.StompOutput
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Minimal low-latency drum voice for testing: a synthesised kick (note 36), snare (38) and click
 * (anything else). It runs its own render thread on a low-latency AudioTrack, with the buffer
 * kept at two device bursts, so a stomp is heard about 10 ms after [play].
 */
class DrumSynth(context: Context) : StompOutput {
    @Volatile var muted = false

    private val am = context.getSystemService(AudioManager::class.java)
    private val rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48_000
    private val burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 192

    // Phone speakers barely reproduce a kick's 50–150 Hz body, so the attack gets a 2 ms click
    // and a short burst of noise: that is what you hear first (and what the latency test's mic catches).
    private val kick = Random(2).let { r -> render(0.45) { t ->
        0.8 * sin(2 * PI * (50 * t + 100 * 0.03 * (1 - exp(-t / 0.03)))) * exp(-t / 0.22) +
            0.6 * sin(2 * PI * 3000 * t) * exp(-t / 0.002) + 0.4 * r.nextDouble(-1.0, 1.0) * exp(-t / 0.004)
    } }
    private val snare = Random(1).let { r -> render(0.3) { t -> (r.nextDouble(-1.0, 1.0) * 0.7 * exp(-t / 0.10)) + 0.5 * sin(2 * PI * 185 * t) * exp(-t / 0.05) } }
    private val click = render(0.03) { t -> sin(2 * PI * 2000 * t) * exp(-t / 0.006) }

    private class Voice(val data: FloatArray, val gain: Float) { var pos = 0 }
    private val pending = ConcurrentLinkedQueue<Voice>()

    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(burst * 4 * 4)
        .build()

    init {
        track.setBufferSizeInFrames(burst * 2)
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = FloatArray(burst)
            val voices = ArrayList<Voice>()
            val ts = AudioTimestamp()
            var written = 0L
            track.play()
            while (true) {
                while (true) voices += pending.poll() ?: break
                buf.fill(0f)
                val it = voices.iterator()
                while (it.hasNext()) {
                    val v = it.next()
                    val n = minOf(burst, v.data.size - v.pos)
                    for (i in 0 until n) buf[i] += v.data[v.pos + i] * v.gain
                    v.pos += n
                    if (v.pos >= v.data.size) it.remove()
                }
                for (i in buf.indices) buf[i] = buf[i].coerceIn(-1f, 1f)
                track.write(buf, 0, burst, AudioTrack.WRITE_BLOCKING)
                written += burst
                // Frames written but not yet out of the speaker, from the track's own timestamp.
                if (track.getTimestamp(ts)) {
                    val queued = (written - ts.framePosition) * 1e3 / rate
                    outputLatencyMs = queued - (System.nanoTime() - ts.nanoTime) / 1e6
                }
            }
        }, "drum-synth").apply { isDaemon = true; start() }
    }

    /** Write → speaker delay estimated from AudioTrack timestamps, ms (NaN until known). */
    @Volatile var outputLatencyMs = Double.NaN; private set

    /** True if Android granted the low-latency (fast mixer) path this track asked for. */
    val lowLatencyGranted: Boolean get() = track.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY

    /** Output latency the platform reports for this track's buffer, ms. */
    val bufferMs: Double get() = track.bufferSizeInFrames * 1000.0 / rate

    override fun play(note: Int, velocity: Int) {
        if (muted) return
        val data = when (note) { 36 -> kick; 38 -> snare; else -> click }
        val v = velocity / 127f
        pending += Voice(data, 0.15f + 0.85f * v * v)
    }

    private fun render(seconds: Double, f: (Double) -> Double) =
        FloatArray((seconds * rate).toInt()) { (f(it.toDouble() / rate) * 0.9).toFloat() }
}
