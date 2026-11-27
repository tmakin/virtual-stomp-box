package uk.neatlines.stompbox.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import uk.neatlines.stompbox.stomp.StompOutput
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Minimal low-latency drum voice for testing: a synthesised kick (note 36), snare (38) and click
 * (anything else), rendered on its own thread into a low-latency AudioTrack.
 *
 * Output routing: a USB audio interface, USB-C headphones or a wired / line output is used as
 * soon as it is plugged in (the phone speaker's path adds ~70 ms of DSP on a Pixel). On every
 * device change the track is rebuilt for that device, starting at two bursts of buffer.
 *
 * Buffer tuning, as Oboe does it: the buffer starts small and grows by one burst whenever the
 * track underruns, so each device settles at the smallest buffer it can keep fed.
 *
 * Two engines ([engine], saved): AUDIOTRACK (Java, Android's mixer) and AAUDIO, a native AAudio
 * stream asking for exclusive (MMAP) mode, which skips the mixer where the device supports it
 * (Pixel speaker; usually not USB, where it falls back to shared).
 */
class DrumSynth(context: Context) : StompOutput {
    @Volatile var muted = false

    enum class Engine { AUDIOTRACK, AAUDIO }
    private val prefs = context.getSharedPreferences("stomp", Context.MODE_PRIVATE)
    @Volatile var engine: Engine = runCatching { Engine.valueOf(prefs.getString("audioEngine", null)!!) }.getOrDefault(Engine.AUDIOTRACK)
        set(v) { field = v; prefs.edit().putString("audioEngine", v.name).apply(); rebuild = true }
    /** The AAudio stream is open and playing (sounds go to it instead of the AudioTrack). */
    @Volatile private var aaudioOpen = false
    /** AAudio granted exclusive (MMAP) mode; false means it fell back to shared. */
    @Volatile var exclusiveGranted = false; private set

    private val am = context.getSystemService(AudioManager::class.java)
    private val rate = 48_000      // native on Pixels and on almost every USB interface
    private val speakerBurst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 192

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

    // ---- what the status line shows
    /** Write → sound-out delay estimated from AudioTrack timestamps, ms (NaN until known). */
    @Volatile var outputLatencyMs = Double.NaN; private set
    /** True if Android granted the low-latency (fast mixer) path this track asked for. */
    @Volatile var lowLatencyGranted = false; private set
    /** Current buffer, ms. */
    @Volatile var bufferMs = 0.0; private set
    /** Underruns (the track ran dry: an audible glitch) since the track was built. */
    @Volatile var underruns = 0; private set
    /** Where the sound actually goes: "USB headset 'Foo'", "phone speaker". */
    @Volatile var deviceLabel = label(null); private set

    /** One line for the status screens. */
    fun summary() = if (engine == Engine.AAUDIO)
        "$deviceLabel, AAudio %s, buffer %.1f ms, output latency %.0f ms, underruns %d".format(
            if (!aaudioOpen) "NOT OPEN" else if (exclusiveGranted) "exclusive (MMAP)" else "shared (exclusive refused)",
            bufferMs, outputLatencyMs, underruns)
    else "$deviceLabel, AudioTrack, buffer %.1f ms, output latency %.0f ms, fast path %s, underruns %d".format(
        bufferMs, outputLatencyMs, if (lowLatencyGranted) "yes" else "NO", underruns)

    @Volatile private var wanted: AudioDeviceInfo? = null
    @Volatile private var rebuild = true
    private val main = Handler(Looper.getMainLooper())
    private val onRouted = AudioRouting.OnRoutingChangedListener { r -> deviceLabel = label(r.routedDevice) }

    init {
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = pickDevice()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = pickDevice()
        }, main)
        pickDevice()
        Thread(::renderLoop, "drum-synth").apply { isDaemon = true; start() }
    }

    override fun play(note: Int, velocity: Int) {
        if (muted) return
        val data = when (note) { 36 -> kick; 38 -> snare; else -> click }
        val v = velocity / 127f
        val gain = 0.15f + 0.85f * v * v
        if (aaudioOpen) AAudioDrum.nativePlay(when (note) { 36 -> 0; 38 -> 1; else -> 2 }, gain)
        else pending += Voice(data, gain)
    }

    /** Prefer an external output: USB first, then wired / line. Null means the default (speaker). */
    private fun pickDevice() {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val best = PREFERRED.firstNotNullOfOrNull { type -> outs.firstOrNull { it.type == type } }
        if (best?.id != wanted?.id) { wanted = best; rebuild = true }
    }

    private fun buildTrack(device: AudioDeviceInfo?): AudioTrack {
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            // Stereo: some USB interfaces only take two channels, and a mono track then loses the fast path.
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(speakerBurst * 16 * 2 * 4)     // room to grow to 16 bursts
            .build()
        if (device != null) t.setPreferredDevice(device)
        t.addOnRoutingChangedListener(onRouted, main)
        return t
    }

    private fun renderLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val burst = speakerBurst     // the speaker's burst also suits USB; the buffer grows if not
        val mono = FloatArray(burst)
        val stereo = FloatArray(burst * 2)
        val voices = ArrayList<Voice>()
        val ts = AudioTimestamp()
        var track: AudioTrack? = null
        var written = 0L
        var checks = 0
        var aaudioLoaded = false
        val info = DoubleArray(8)
        while (true) {
            if (engine == Engine.AAUDIO) {
                if (track != null) { runCatching { track!!.pause(); track!!.flush(); track!!.release() }; track = null }
                if (!aaudioLoaded) {
                    AAudioDrum.nativeLoad(0, kick); AAudioDrum.nativeLoad(1, snare); AAudioDrum.nativeLoad(2, click)
                    aaudioLoaded = true
                }
                val stale = aaudioOpen && AAudioDrum.nativeInfo(info) && info[7] != 0.0   // device gone
                if (rebuild || stale || !aaudioOpen) {
                    rebuild = false
                    aaudioOpen = false
                    aaudioOpen = AAudioDrum.nativeOpen(wanted?.id ?: 0, true)
                    underruns = 0; outputLatencyMs = Double.NaN
                    if (!aaudioOpen) { Thread.sleep(1000); continue }
                }
                AAudioDrum.info()?.let { i ->
                    exclusiveGranted = i.exclusive; bufferMs = i.bufferMs; underruns = i.xruns
                    if (!i.latencyMs.isNaN()) outputLatencyMs = i.latencyMs
                    deviceLabel = label(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == i.deviceId })
                }
                Thread.sleep(100)
                continue
            }
            if (aaudioOpen) { aaudioOpen = false; AAudioDrum.nativeClose(); rebuild = true }
            if (rebuild || track == null) {
                rebuild = false
                track?.let { runCatching { it.pause(); it.flush(); it.release() } }
                val device = wanted
                track = buildTrack(device)
                track.setBufferSizeInFrames(burst * 2)
                track.play()
                written = 0; underruns = 0; outputLatencyMs = Double.NaN
                lowLatencyGranted = track.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
                deviceLabel = label(track.routedDevice ?: device)
            }
            val tr = track!!
            while (true) voices += pending.poll() ?: break
            mono.fill(0f)
            val it = voices.iterator()
            while (it.hasNext()) {
                val v = it.next()
                val n = minOf(burst, v.data.size - v.pos)
                for (i in 0 until n) mono[i] += v.data[v.pos + i] * v.gain
                v.pos += n
                if (v.pos >= v.data.size) it.remove()
            }
            for (i in 0 until burst) { val x = mono[i].coerceIn(-1f, 1f); stereo[2 * i] = x; stereo[2 * i + 1] = x }
            if (tr.write(stereo, 0, burst * 2, AudioTrack.WRITE_BLOCKING) < 0) { rebuild = true; continue }
            written += burst

            // Grow the buffer by one burst after an underrun (checked every ~10 writes).
            if (++checks % 10 == 0) {
                val u = tr.underrunCount
                if (u > underruns) {
                    underruns = u
                    val cap = tr.bufferCapacityInFrames
                    if (tr.bufferSizeInFrames + burst <= cap) tr.setBufferSizeInFrames(tr.bufferSizeInFrames + burst)
                }
                bufferMs = tr.bufferSizeInFrames * 1000.0 / rate
                // Frames written but not yet out of the device, from the track's own timestamp.
                if (tr.getTimestamp(ts)) {
                    val queued = (written - ts.framePosition) * 1e3 / rate
                    outputLatencyMs = queued - (System.nanoTime() - ts.nanoTime) / 1e6
                }
            }
        }
    }

    private fun render(seconds: Double, f: (Double) -> Double) =
        FloatArray((seconds * rate).toInt()) { (f(it.toDouble() / rate) * 0.9).toFloat() }

    private companion object {
        val PREFERRED = listOf(
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        )

        fun label(d: AudioDeviceInfo?) =
            if (d == null || d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) "phone speaker" else "${typeName(d.type)} '${d.productName}'"

        fun typeName(t: Int) = when (t) {
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB audio"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB accessory"
            AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE -> "line out"
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headphones"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth (slow!)"
            else -> "device type $t"
        }
    }
}
