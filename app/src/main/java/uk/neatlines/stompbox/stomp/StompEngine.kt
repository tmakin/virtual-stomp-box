package uk.neatlines.stompbox.stomp

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import uk.neatlines.stompbox.debug.LinkStats
import uk.neatlines.stompbox.debug.StompLogWriter
import uk.neatlines.stompbox.input.LinkMode
import uk.neatlines.stompbox.input.WitBleSource
import uk.neatlines.stompbox.latency.LatencyMeter
import uk.neatlines.stompbox.latency.LatencyProbe
import uk.neatlines.stompbox.wit.WitSample
import java.io.File
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.locks.LockSupport

/** What the app's sound engine has to provide. */
fun interface StompOutput {
    /** Play now. Called on the GATT thread (IMMEDIATE) or the scheduler thread (STEADY). */
    fun play(note: Int, velocity: Int)
}

/**
 * The WT901 stomp input for the app: BLE source → [StompPipeline] → sound, plus everything the
 * debug screen needs (live frames, link stats, calibration, recording, settings per mode).
 *
 * ```
 * val engine = StompEngine(context) { note, vel -> soundEngine.trigger(note, vel) }
 * engine.start()            // scans for a "WT…" device, or start(savedAddress)
 * ```
 */
class StompEngine(private val context: Context, private val output: StompOutput) {
    private val prefs = context.getSharedPreferences("stomp", Context.MODE_PRIVATE)

    val pipeline = StompPipeline(loadConfig(loadMode()), loadPlacement(), prefs.getInt("rateHz", 200)) { e, at -> onStomp(e, at) }
    val source = WitBleSource(context, pipeline.rateHz, onSample = ::onSample)

    /** Sensor output rate, Hz (50, 100 or 200). Lower it if the link can't carry every frame. */
    var rateHz: Int
        get() = pipeline.rateHz
        set(v) { prefs.edit().putInt("rateHz", v).apply(); source.rateHz = v; pipeline.rateHz = v }

    /** BLE link setup (MTU / PHY); persisted, and changing it reconnects. */
    var linkMode: LinkMode
        get() = source.linkMode
        set(v) { prefs.edit().putString("linkMode", v.name).apply(); source.linkMode = v }

    init {
        source.linkMode = runCatching { LinkMode.valueOf(prefs.getString("linkMode", null)!!) }.getOrDefault(LinkMode.DEFAULT_MTU)
    }

    /** Latest stomps for the UI, newest last. */
    val recentStomps = ArrayDeque<StompEvent>()
    /** For the home screen's flash; called on the GATT thread. */
    @Volatile var onStompUi: ((StompEvent) -> Unit)? = null
    @Volatile var lastSample: WitSample? = null; private set

    var config: StompConfig
        get() = pipeline.config
        set(v) { pipeline.config = v; saveConfig(v) }

    var mode: Mode
        get() = config.mode
        set(m) { prefs.edit().putString("mode", m.name).apply(); pipeline.config = loadConfig(m) }

    fun start(address: String? = null) = source.start(address)
    fun stop() { stopRecording(); stopLatencyTest(); source.stop() }
    fun link(): LinkStats.Snapshot = pipeline.link.snapshot()

    // ---- the latency path -------------------------------------------------------------------

    private fun onSample(s: WitSample, arrivalNanos: Long) {
        val p = lastSample
        if (latencyProbe != null && p != null) {
            val dx = s.ax - p.ax; val dy = s.ay - p.ay; val dz = s.az - p.az
            latency.sample(arrivalNanos, kotlin.math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()))
        }
        lastSample = s
        if (source.flushing) return
        recorder?.sample(arrivalNanos, s)
        calibration?.add(s)
        pipeline.onSample(s, arrivalNanos)
    }

    private fun onStomp(e: StompEvent, fireAtNanos: Long) {
        if (fireAtNanos <= System.nanoTime()) output.play(e.note, e.velocity) else scheduler.schedule(fireAtNanos, e)
        recorder?.stomp(e, fireAtNanos)
        if (latencyProbe != null) latency.stomp(e.arrivalNanos, maxOf(fireAtNanos, e.arrivalNanos))
        synchronized(recentStomps) { recentStomps.addLast(e); while (recentStomps.size > 50) recentStomps.removeFirst() }
        onStompUi?.invoke(e)
        // Live debug without the app UI: adb logcat -s Stomp
        Log.d("Stomp", "zone=${e.zone} vel=${e.velocity} peak=%.2f tilt=%.1f lag=%.1fms".format(
            e.peak, e.tiltDeg, (e.arrivalNanos - e.sampleNanos) / 1e6))
    }

    /** STEADY timing: sleeps until each stomp's target time, then plays it. */
    private val scheduler = object {
        private val queue = PriorityBlockingQueue<Pair<Long, StompEvent>>(16, compareBy { it.first })
        private val thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (true) {
                val (at, e) = queue.take()
                // Park until ~0.5 ms before, then spin: parkNanos alone can overshoot by a millisecond.
                while (true) {
                    val left = at - System.nanoTime()
                    if (left <= 0) break
                    if (left > 500_000) LockSupport.parkNanos(left - 500_000) else Thread.yield()
                    if (queue.peek()?.let { it.first < at } == true) { queue.put(at to e); break }
                }
                if (at <= System.nanoTime()) output.play(e.note, e.velocity)
            }
        }, "stomp-scheduler").apply { isDaemon = true; start() }

        fun schedule(at: Long, e: StompEvent) = queue.put(at to e)
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

    @Volatile private var recorder: StompLogWriter? = null
    private var recordFile: File? = null
    private var probe: LatencyProbe? = null

    val isRecording get() = recorder != null

    /** Records raw samples and stomps; with [withMic] also the impact sounds, for latency figures. */
    fun startRecording(withMic: Boolean): File {
        val dir = File(context.getExternalFilesDir(null), "stomp-logs").apply { mkdirs() }
        val f = File(dir, "stomp-%tY%<tm%<td-%<tH%<tM%<tS.csv".format(java.util.Date()))
        val w = StompLogWriter(f.bufferedWriter(bufferSize = 1 shl 16))   // large buffer: fewer disk writes on the GATT thread
        w.header(config, pipeline.placement, mapOf(
            "rateHz" to "$rateHz", "rateReg" to "${source.rateRegister}", "bandwidthReg" to "${source.bandwidthRegister}",
            "batteryV" to "${source.batteryVolts}", "device" to (source.deviceName ?: "?"),
            "phone" to "${Build.MANUFACTURER} ${Build.MODEL}", "android" to "${Build.VERSION.SDK_INT}",
        ))
        val micAllowed = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (withMic && micAllowed) probe = LatencyProbe().also { it.start() }
        recordFile = f; recorder = w
        return f
    }

    /**
     * Copies a recording to the phone's Downloads/StompBox folder, where the Files app, a USB
     * cable (no adb) and any upload page can see it. Returns the folder-relative name, or null.
     */
    fun saveToDownloads(f: File): String? = runCatching {
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
        val w = recorder ?: return null
        recorder = null
        probe?.let { p -> p.stop(); p.onsetNanos().forEach(w::onset) }
        probe = null
        w.close()
        return recordFile
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

    private fun loadMode() = prefs.getString("mode", null)?.let { Mode.valueOf(it) } ?: Mode.SHOE

    private fun loadConfig(m: Mode): StompConfig {
        val saved = prefs.getString("config.$m", null) ?: return StompConfig.forMode(m)
        return runCatching { StompConfig.forMode(m).with(saved.split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }) }
            .getOrDefault(StompConfig.forMode(m))
            .copy(zonesEnabled = false)     // single stomp for the first iteration
    }

    private fun saveConfig(c: StompConfig) =
        prefs.edit().putString("config.${c.mode}", c.toMap().entries.joinToString(" ") { "${it.key}=${it.value}" }).apply()

    private fun loadPlacement(): Placement = runCatching {
        val p = prefs.getString("placement", null)!!.split(';')
        fun v(s: String) = s.split(',').map { it.toDouble() }.let { V3(it[0], it[1], it[2]) }
        val c = p[1].split(',').map { it.toInt() }
        Placement(v(p[0]), AngleConvention(c[0], c[1]), p[2].toDouble(), p[3].takeIf { it.isNotEmpty() }?.let(::v))
    }.getOrDefault(Placement.DEFAULT)

    companion object { val RATES = listOf(200, 100, 50, 10) }
}
