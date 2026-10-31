package uk.neatlines.stompbox.latency

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Process
import java.io.File
import java.util.Collections

/**
 * Measures impact → event-in-app latency for any tap source, using the phone's microphone as
 * the reference clock (step 1–3 of "Measuring latency" in the handoff).
 *
 * Strike the sensor on a hard surface close to the phone. The mic hears the impact; the sensor
 * event arrives in the app some milliseconds later. Both are put on the System.nanoTime clock:
 * mic frames through [AudioRecord.getTimestamp] (which reports when a frame was captured, so the
 * input buffer delay is removed), sensor events by calling [onEvent] the moment they arrive.
 *
 * Usage:
 * ```
 * val probe = LatencyProbe()
 * midiSource.onArrival = probe::onEvent       // or accelSource / gattSource
 * probe.start()                               // needs RECORD_AUDIO
 * // … tap 50+ times, at least ~0.3 s apart …
 * val result = probe.stop()
 * Log.i("latency", result.stats?.format())
 * probe.writeCsv(File(filesDir, "latency.csv"))
 * ```
 * Cross-check a few taps against 240 fps slow-motion video (step 4): a constant offset between
 * the two methods points at a timestamp error, not link latency.
 */
class LatencyProbe(private val sampleRate: Int = 48_000) {
    private val onsets = Collections.synchronizedList(ArrayList<Long>())
    private val events = Collections.synchronizedList(ArrayList<Long>())
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Number of impacts heard so far, for a live counter in the UI. */
    val heard: Int get() = onsets.size
    val received: Int get() = events.size

    /** Call from the tap source's thread with System.nanoTime() at arrival. */
    fun onEvent(arrivalNanos: Long) {
        if (running) events += arrivalNanos
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        onsets.clear(); events.clear()
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        // UNPROCESSED skips noise suppression / AGC, which would smear the attack of the click.
        val rec = AudioRecord(
            MediaRecorder.AudioSource.UNPROCESSED, sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT, maxOf(minBuf, sampleRate / 10 * 4),
        )
        running = true
        thread = Thread({ loop(rec) }, "latency-probe").also { it.start() }
    }

    fun stop(): TapPairer.Result {
        running = false
        thread?.join(500)
        thread = null
        return TapPairer.pair(onsets.toList(), events.toList())
    }

    /** One row per paired tap plus the raw lists, for a spreadsheet. */
    fun writeCsv(file: File) {
        val r = TapPairer.pair(onsets.toList(), events.toList())
        file.printWriter().use { w ->
            w.println("tap,delay_ms")
            r.delaysMs.forEachIndexed { i, d -> w.println("${i + 1},${"%.3f".format(java.util.Locale.US, d)}") }
            w.println()
            w.println("# ${r.stats?.format() ?: "no pairs"}  missed=${r.missed} false=${r.falseTriggers}")
            w.println("# raw onsets_ns=${onsets.joinToString(" ")}")
            w.println("# raw events_ns=${events.joinToString(" ")}")
        }
    }

    private fun loop(rec: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val detector = OnsetDetector(sampleRate)
        val buf = FloatArray(sampleRate / 100)       // 10 ms reads
        val ts = AudioTimestamp()
        var frame = 0L
        rec.startRecording()
        try {
            while (running) {
                val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n <= 0) continue
                val haveTs = rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS
                val first = frame
                detector.process(buf, n, first) { f ->
                    // Without a timestamp, fall back to "now minus the frames still queued" (less accurate).
                    val t = if (haveTs) ts.nanoTime + (f - ts.framePosition) * 1_000_000_000L / sampleRate
                    else System.nanoTime() - (first + n - f) * 1_000_000_000L / sampleRate
                    onsets += t
                }
                frame += n
            }
        } finally {
            rec.stop()
            rec.release()
        }
    }
}
