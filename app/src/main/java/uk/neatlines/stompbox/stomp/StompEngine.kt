package uk.neatlines.stompbox.stomp

import android.content.Context
import android.os.Build
import android.util.Log
import uk.neatlines.stompbox.debug.LinkStats
import uk.neatlines.stompbox.debug.StompLogWriter
import uk.neatlines.stompbox.input.SensorSource
import uk.neatlines.stompbox.input.WitBleSource
import uk.neatlines.stompbox.input.WitUsbSource
import uk.neatlines.stompbox.latency.LatencyMeter
import uk.neatlines.stompbox.latency.LatencyProbe
import uk.neatlines.stompbox.wit.WitSample
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** What the app's sound engine has to provide. */
fun interface StompOutput {
    /** Play now. Called on the sensor's read thread. */
    fun play(note: Int, velocity: Int)
}

/**
 * The WT901 stomp input for the app: sensor link (Bluetooth or USB cable) → [StompPipeline] → sound, plus everything the
 * debug screen needs (live frames, link stats, calibration, recording, settings per mode).
 *
 * ```
 * val engine = StompEngine(context) { note, vel -> soundEngine.trigger(note, vel) }
 * engine.start()            // Bluetooth: scans for a "WT…" device; wired: opens the USB sensor
 * ```
 */
class StompEngine(private val context: Context, private val output: StompOutput) {
    private val prefs = context.getSharedPreferences("stomp", Context.MODE_PRIVATE)

    val pipeline = StompPipeline(loadConfig(loadMode()), loadPlacement(), loadRate()) { e, at -> onStomp(e, at) }
    val ble = WitBleSource(context, pipeline.rateHz, onSample = ::onSample, onPacketEnd = pipeline::onPacketEnd)
    val usb = WitUsbSource(context, pipeline.rateHz, onSample = ::onSample).apply { onOpened = pipeline::restartClock }

    /**
     * Read the sensor over its USB cable instead of Bluetooth: one frame every 5 ms with no
     * Bluetooth packing. Switching while connected disconnects and connects over the new link.
     */
    var wired: Boolean = prefs.getBoolean("wired", false)
        set(v) {
            if (v == field) return
            val wasRunning = isRunning
            if (wasRunning) stop()
            field = v; prefs.edit().putBoolean("wired", v).apply()
            if (wasRunning) start()
        }

    /** The link in use. */
    val source: SensorSource get() = if (wired) usb else ble

    /** Sensor output rate, Hz (50, 100 or 200). Lower it if the link can't carry every frame. */
    var rateHz: Int
        get() = pipeline.rateHz
        set(v) { prefs.edit().putInt("rateHz", v).apply(); ble.rateHz = v; usb.rateHz = v; pipeline.rateHz = v }

    /** Latest stomps for the UI, newest last. */
    val recentStomps = ArrayDeque<StompEvent>()
    /** For the home screen's flash; called on the sensor's read thread. */
    @Volatile var onStompUi: ((StompEvent) -> Unit)? = null
    @Volatile var lastSample: WitSample? = null; private set

    var config: StompConfig
        get() = pipeline.config
        set(v) { pipeline.config = v; saveConfig(v) }

    var mode: Mode
        get() = config.mode
        set(m) { prefs.edit().putString("mode", m.name).apply(); pipeline.config = loadConfig(m) }

    fun start() { pipeline.restartClock(); source.start() }
    /** True from [start] until [stop] (scanning, connected or reconnecting). */
    val isRunning get() = ble.running || usb.running
    fun stop() { stopRecording(); stopLatencyTest(); ble.stop(); usb.stop() }
    fun link(): LinkStats.Snapshot = pipeline.link.snapshot()

    // ---- the latency path -------------------------------------------------------------------

    private fun onSample(s: WitSample, arrivalNanos: Long) {
        val p = lastSample
        if (latencyProbe != null && p != null) {
            val dx = s.ax - p.ax; val dy = s.ay - p.ay; val dz = s.az - p.az
            latency.sample(arrivalNanos, kotlin.math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()))
        }
        lastSample = s
        recording?.post { it.sample(arrivalNanos, s) }
        calibration?.add(s)
        pipeline.onSample(s, arrivalNanos)
    }

    private fun onStomp(e: StompEvent, firedNanos: Long) {
        output.play(e.note, e.velocity)
        recording?.post { it.stomp(e, firedNanos) }
        if (latencyProbe != null) latency.stomp(e.arrivalNanos, firedNanos)
        synchronized(recentStomps) { recentStomps.addLast(e); while (recentStomps.size > 50) recentStomps.removeFirst() }
        onStompUi?.invoke(e)
        // Live debug without the app UI: adb logcat -s Stomp
        Log.d("Stomp", "zone=${e.zone} vel=${e.velocity} peak=%.2f tilt=%.1f lag=%.1fms".format(
            e.peak, e.tiltDeg, (e.arrivalNanos - e.sampleNanos) / 1e6))
    }

    // ---- calibration ------------------------------------------------------------------------

    private class Accumulator { var n = 0; var ax = 0.0; var ay = 0.0; var az = 0.0; var roll = 0.0; var pitch = 0.0
        fun add(s: WitSample) { n++; ax += s.ax; ay += s.ay; az += s.az; roll += s.roll; pitch += s.pitch }
        fun acc() = V3(ax / n, ay / n, az / n)
    }
    @Volatile private var calibration: Accumulator? = null
    private var restSample: Accumulator? = null

    /** Stand still for [ms] while this runs. Then optionally [calibrateTilt]. Calls back on a worker thread. */
    fun calibrateRest(ms: Long = 1000, done: (Placement) -> Unit) = collect(ms) { a ->
        restSample = a
        apply(Placement.calibrate(a.acc(), a.roll / a.n, a.pitch / a.n), done)
    }

    /** Shoe: lift the toes, heel on the floor. Leg: swing the lower leg forward. Hold for [ms]. */
    fun calibrateTilt(ms: Long = 1000, done: (Placement) -> Unit) = collect(ms) { a ->
        val r = restSample ?: return@collect
        apply(Placement.calibrate(r.acc(), r.roll / r.n, r.pitch / r.n, tiltAcc = a.acc()), done)
    }

    private fun collect(ms: Long, then: (Accumulator) -> Unit) {
        val a = Accumulator(); calibration = a
        Thread { Thread.sleep(ms); calibration = null; if (a.n > 10) then(a) }.start()
    }

    private fun apply(p: Placement, done: (Placement) -> Unit) {
        pipeline.placement = p
        prefs.edit().putString("placement", "${p.rest.x},${p.rest.y},${p.rest.z};${p.convention.rollSign},${p.convention.pitchSign};" +
            "${p.conventionErrorDeg};${p.tiltAxis?.let { "${it.x},${it.y},${it.z}" } ?: ""}").apply()
        done(p)
    }

    // ---- recording --------------------------------------------------------------------------

    /** A log writer plus the one thread that formats and writes it, off the sensor's read thread. */
    private class Recording(val writer: StompLogWriter, val file: File) {
        private val exec: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "stomp-log") }
        /** Queues a write; dropped if the recording has just been stopped. */
        fun post(block: (StompLogWriter) -> Unit) {
            try { exec.execute { block(writer) } } catch (_: RejectedExecutionException) {}
        }
        /** Waits (up to 2 s) for queued writes; the caller then finishes and closes [writer]. */
        fun drain() { exec.shutdown(); exec.awaitTermination(2, TimeUnit.SECONDS) }
    }

    @Volatile private var recording: Recording? = null
    private var probe: LatencyProbe? = null

    val isRecording get() = recording != null

    /** Records raw samples and stomps; with [withMic] also the impact sounds, for latency figures. */
    fun startRecording(withMic: Boolean): File {
        val dir = File(context.getExternalFilesDir(null), "stomp-logs").apply { mkdirs() }
        val f = File(dir, "stomp-%tY%<tm%<td-%<tH%<tM%<tS.csv".format(java.util.Date()))
        val w = StompLogWriter(f.bufferedWriter(bufferSize = 1 shl 16))
        w.header(config, pipeline.placement, mapOf(
            "link" to source.kind, "rateHz" to "$rateHz", "rateReg" to "${source.rateRegister}", "bandwidthReg" to "${source.bandwidthRegister}",
            "batteryV" to "${source.batteryVolts}", "device" to (source.deviceName ?: "?"),
            "phone" to "${Build.MANUFACTURER} ${Build.MODEL}", "android" to "${Build.VERSION.SDK_INT}",
            "audio" to ((output as? uk.neatlines.stompbox.audio.DrumSynth)?.summary() ?: "?"),
        ))
        val micAllowed = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (withMic && micAllowed) probe = LatencyProbe().also { it.start() }
        recording = Recording(w, f)
        return f
    }

    /**
     * Copies a recording to the phone's Downloads/StompBox folder, where the Files app, a USB
     * cable (no adb) and any upload page can see it. Returns the folder-relative name, or null.
     */
    fun saveToDownloads(f: File): String? = if (Build.VERSION.SDK_INT < 29) null else runCatching {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/StompBox")
        }
        val r = context.contentResolver
        val uri = r.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        r.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
        "Download/StompBox/${f.name}"
    }.onFailure { Log.w("Stomp", "save to Downloads failed", it) }.getOrNull()

    fun stopRecording(): File? {
        val r = recording ?: return null
        recording = null
        r.drain()
        probe?.let { p -> p.stop(); p.onsetNanos().forEach(r.writer::onset) }
        probe = null
        r.writer.close()
        return r.file
    }

    // ---- live latency test ------------------------------------------------------------------

    val latency = LatencyMeter()
    @Volatile private var latencyProbe: LatencyProbe? = null
    val isLatencyTesting get() = latencyProbe != null

    /** Listens with the mic and scores every stomp (see [LatencyMeter]). Needs RECORD_AUDIO. */
    fun startLatencyTest(): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        latency.clear()
        // Short refractory: with low latency the drum comes only tens of ms after the impact.
        latencyProbe = LatencyProbe(refractoryMs = 30f).also { it.onOnset = latency::onset; it.start() }
        return true
    }

    fun stopLatencyTest() { latencyProbe?.stop(); latencyProbe = null }

    // ---- settings ---------------------------------------------------------------------------

    /** A saved rate that has since been dropped from [RATES] falls back to the fastest. */
    private fun loadRate() = prefs.getInt("rateHz", RATES[0]).takeIf { it in RATES } ?: RATES[0]

    private fun loadMode() = prefs.getString("mode", null)?.let { Mode.valueOf(it) } ?: Mode.SHOE

    private fun loadConfig(m: Mode): StompConfig {
        val saved = prefs.getString("config.$m", null) ?: return StompConfig.forMode(m)
        return runCatching { StompConfig.forMode(m).with(saved.split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }) }
            .getOrDefault(StompConfig.forMode(m))
            .let { it.copy(zonesEnabled = false,      // single stomp for the first iteration
                minThresholdG = it.minThresholdG.coerceAtMost(MAX_THRESHOLD_G)) }
    }

    private fun saveConfig(c: StompConfig) =
        prefs.edit().putString("config.${c.mode}", c.toMap().entries.joinToString(" ") { "${it.key}=${it.value}" }).apply()

    private fun loadPlacement(): Placement = runCatching {
        val p = prefs.getString("placement", null)!!.split(';')
        fun v(s: String) = s.split(',').map { it.toDouble() }.let { V3(it[0], it[1], it[2]) }
        val c = p[1].split(',').map { it.toInt() }
        Placement(v(p[0]), AngleConvention(c[0], c[1]), p[2].toDouble(), p[3].takeIf { it.isNotEmpty() }?.let(::v))
    }.getOrDefault(Placement.DEFAULT)

    companion object {
        val RATES = listOf(200, 100, 50)
        const val MAX_THRESHOLD_G = 2.0
    }
}
