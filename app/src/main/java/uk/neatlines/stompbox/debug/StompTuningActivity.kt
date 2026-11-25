package uk.neatlines.stompbox.debug

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import uk.neatlines.stompbox.StompApp
import uk.neatlines.stompbox.audio.DrumSynth
import uk.neatlines.stompbox.latency.LatencyMeter
import uk.neatlines.stompbox.stomp.Feature
import uk.neatlines.stompbox.stomp.Mode
import uk.neatlines.stompbox.stomp.StompConfig
import uk.neatlines.stompbox.stomp.StompEngine
import uk.neatlines.stompbox.stomp.Timing
import uk.neatlines.stompbox.ui.UiStallWatch
import uk.neatlines.stompbox.ui.padForSystemBars
import java.io.File
import kotlin.math.round

/**
 * Live debug and tuning for the WT901 stomp sensor. Built in code (no layout XML) so it drops into
 * the app as one file. Every change applies immediately and is saved for the current mode.
 *
 * Top to bottom: status (link, battery, calibration), latency test, live plot, last stomp,
 * toggles, calibration and recording, sliders.
 *
 * The app must pass its engine in via [engineHolder] before starting this activity.
 */
class StompTuningActivity : Activity() {
    companion object {
        var engineHolder: StompEngine? = null
        private const val PACER_MS = 2000L
        private val PACER_IDLE = Color.rgb(60, 60, 60)
        private val PACER_FLASH = Color.rgb(80, 230, 90)
    }

    private lateinit var engine: StompEngine
    private lateinit var status: TextView
    private lateinit var latencyText: TextView
    private lateinit var latencyButton: Button
    private lateinit var pacer: TextView
    private lateinit var last: TextView
    private lateinit var saved: TextView
    private lateinit var plot: StompPlotView
    private val refreshers = ArrayList<() -> Unit>()     // re-read settings into buttons and sliders
    private var lastFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = engineHolder ?: run { finish(); return }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        status = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        col.addView(status)

        // ---- latency test
        col.addView(header("Latency test"))
        col.addView(TextView(this).apply {
            textSize = 12f
            text = "Phone on the floor next to your foot, sound on (phone speaker, not Bluetooth). " +
                "Stomp once each time the bar below flashes (every 2 s). The mic hears the impact and the drum."
        })
        latencyButton = button("Start latency test") { toggleLatency() }
        col.addView(latencyButton)
        // Silent visual pacer: a sound would confuse the mic's onset detection.
        pacer = TextView(this).apply {
            textSize = 22f; gravity = android.view.Gravity.CENTER; setTextColor(Color.BLACK)
            setBackgroundColor(PACER_IDLE); text = ""
        }
        col.addView(pacer, LinearLayout.LayoutParams(MATCH_PARENT, 140))
        latencyText = TextView(this).apply { textSize = 15f; setTextIsSelectable(true) }
        col.addView(latencyText)

        // ---- live view
        col.addView(header("Live"))
        plot = StompPlotView(this)
        col.addView(plot, LinearLayout.LayoutParams(MATCH_PARENT, 520))
        last = TextView(this).apply { textSize = 16f; setTextColor(Color.GREEN) }
        col.addView(last)

        // ---- settings
        col.addView(header("Settings"))
        col.addView(row(
            cycler({ "Mode: ${engine.mode}" }) { engine.mode = if (engine.mode == Mode.SHOE) Mode.LEG else Mode.SHOE },
            cycler({ "Feature: ${engine.config.feature}" }) {
                set { it.copy(feature = if (it.feature == Feature.VERTICAL) Feature.JERK else Feature.VERTICAL) }
            },
        ))
        col.addView(row(
            cycler({ "Timing: ${engine.config.timing}" }) {
                set { it.copy(timing = if (it.timing == Timing.IMMEDIATE) Timing.STEADY else Timing.IMMEDIATE) }
            },
            cycler({ "Sensor rate: ${engine.rateHz} Hz" }) {
                val r = StompEngine.RATES
                engine.rateHz = r[(r.indexOf(engine.rateHz) + 1) % r.size]
            },
        ))
        val synth = (application as StompApp).synth
        col.addView(cycler({ "Audio engine: ${if (synth.engine == DrumSynth.Engine.AAUDIO) "AAudio (MMAP)" else "AudioTrack"}" }) {
            synth.engine = if (synth.engine == DrumSynth.Engine.AAUDIO) DrumSynth.Engine.AUDIOTRACK else DrumSynth.Engine.AAUDIO
        })
        col.addView(button("Calibrate: stand still for 1 s") { b ->
            b.text = "hold still…"
            engine.calibrateRest { runOnUiThread { b.text = "Calibrate: stand still for 1 s ✓" } }
        })

        // ---- recording and export
        col.addView(header("Record & export"))
        val mic = CheckBox(this).apply { text = "with mic" }
        col.addView(row(
            button("Record") { b ->
                if (mic.isChecked && !hasMic()) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
                else if (!engine.isRecording) { engine.startRecording(mic.isChecked); b.text = "Stop & save"; saved.text = "recording…" }
                else { engine.stopRecording()?.let(::onRecorded); b.text = "Record" }
            },
            mic,
        ))
        col.addView(row(
            button("Share last recording") { lastFile?.let(::share) ?: toast("Record something first") },
            button("Copy status") { copyStatus() },
        ))
        saved = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        col.addView(saved)

        // ---- detector
        col.addView(header("Detector"))
        slider(col, "Min threshold", "g", 0.2, StompEngine.MAX_THRESHOLD_G, 0.02, { it.minThresholdG }) { c, v -> c.copy(minThresholdG = v) }
        slider(col, "Noise factor", "×", 2.0, 15.0, 0.5, { it.noiseFactor }) { c, v -> c.copy(noiseFactor = v) }
        slider(col, "Dead time", "ms", 30.0, 300.0, 5.0, { it.deadTimeMs }) { c, v -> c.copy(deadTimeMs = v) }
        slider(col, "High-pass", "Hz", 0.5, 20.0, 0.5, { it.highPassHz }) { c, v -> c.copy(highPassHz = v) }
        slider(col, "Peak wait", "samples", 0.0, 3.0, 1.0, { it.peakWaitSamples.toDouble() }) { c, v -> c.copy(peakWaitSamples = v.toInt()) }
        slider(col, "Velocity full scale", "g", 1.0, 16.0, 0.5, { it.velocityFullScaleG }) { c, v -> c.copy(velocityFullScaleG = v) }
        slider(col, "Velocity curve", "", 0.2, 2.0, 0.05, { it.velocityCurve }) { c, v -> c.copy(velocityCurve = v) }
        slider(col, "Gyro gate (0 = off)", "°/s", 0.0, 400.0, 10.0, { it.gyroGateDps }) { c, v -> c.copy(gyroGateDps = v) }
        slider(col, "Steady delay", "ms", 10.0, 60.0, 1.0, { it.steadyDelayMs }) { c, v -> c.copy(steadyDelayMs = v) }
        col.addView(button("Reset this mode to defaults") { set { StompConfig.forMode(it.mode) } })

        setContentView(ScrollView(this).apply { addView(col); padForSystemBars(24); clipToPadding = false })
        engine.pipeline.detector.onDebug = plot::add
        latencyButton.text = if (engine.isLatencyTesting) "Stop latency test" else "Start latency test"
        if (engine.isLatencyTesting) pacer.post(pace)
    }

    override fun onResume() {
        super.onResume()
        if (::engine.isInitialized) status.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        if (::engine.isInitialized) status.removeCallbacks(refresh)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::engine.isInitialized) { engine.pipeline.detector.onDebug = null; status.removeCallbacks(refresh); pacer.removeCallbacks(pace) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (results.firstOrNull() != PackageManager.PERMISSION_GRANTED) { toast("Microphone permission is needed for this"); return }
        if (requestCode == 3) toggleLatency() else toast("Microphone allowed. Tap Record again.")
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun toggleLatency() {
        if (engine.isLatencyTesting) {
            engine.stopLatencyTest(); latencyButton.text = "Start latency test"
            pacer.removeCallbacks(pace); pacer.setBackgroundColor(PACER_IDLE); pacer.text = ""
            return
        }
        if (!hasMic()) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 3); return }
        if (engine.startLatencyTest()) {
            latencyButton.text = "Stop latency test"
            pacer.removeCallbacks(pace); pacer.postDelayed(pace, 1000)
        }
    }

    /** Flashes the pacer bar for 250 ms every [PACER_MS], with a countdown in between. */
    private val pace = object : Runnable {
        private var beatAt = 0L
        override fun run() {
            val now = android.os.SystemClock.uptimeMillis()
            if (now >= beatAt) beatAt = now + PACER_MS
            val left = beatAt - now
            val flashing = PACER_MS - left < 250
            pacer.setBackgroundColor(if (flashing) PACER_FLASH else PACER_IDLE)
            pacer.text = if (flashing) "STOMP" else "%.1f".format(left / 1000.0)
            pacer.postDelayed(this, if (flashing) 250 - (PACER_MS - left) + 1 else minOf(100L, left))
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            status.text = statusText()
            val r = engine.latency.poll(System.nanoTime())
            if (engine.isLatencyTesting || r.isNotEmpty()) {
                latencyText.text = LatencyMeter.summary(r, engine.latency.unpaired)
            }
            engine.recentStomps.let { synchronized(it) { it.lastOrNull() } }?.let { e ->
                last.text = "last stomp: vel ${e.velocity}  peak %.2f g  (total ${engine.pipeline.detector.triggers})".format(e.peak)
            }
            status.postDelayed(this, 250)
        }
    }

    private fun statusText(): String {
        val c = engine.config; val p = engine.pipeline.placement; val src = engine.source; val link = engine.link()
        return buildString {
            append("build ${uk.neatlines.stompbox.BuildConfig.BUILD_LABEL}\n")
            append("${src.deviceName ?: "-"}: ${src.status}")
            if (!src.batteryVolts.isNaN()) append("  battery %.2f V".format(src.batteryVolts))
            append("\nsensor set to ${engine.rateHz} Hz (rate reg ${src.rateRegister}, bandwidth reg ${src.bandwidthRegister})")
            append("\nBLE: MTU ${src.mtu}, interval %s, peripheral latency %s, timeout %s, %.2f frames/notification".format(
                if (src.connIntervalMs < 0) "?" else "%.2f ms".format(src.connIntervalMs),
                if (src.connLatency < 0) "?" else "${src.connLatency}",
                if (src.connTimeoutMs < 0) "?" else "${src.connTimeoutMs} ms",
                src.framesPerNotification))
            append("\nping (register read round trip): ${src.pingSummary}")
            append("\nfirmware: ${src.deviceInfo.ifEmpty { "no Device Info strings" }}; version regs ${src.versionRegisters}")
            append("\nupdate service: ${src.updateServices.joinToString().ifEmpty { "none found" }}")
            append("\nGATT: ${src.gattServices}")
            append("\n").append(link.format())
            if (src.status == "streaming" && link.samples > 400 && link.sampleRateHz < 0.9 * engine.rateHz) {
                append("\n⚠ receiving %.0f of %d frames/s: the link isn't keeping up, so frames queue in the sensor and add delay. Try a lower sensor rate."
                    .format(link.sampleRateHz, engine.rateHz))
            }
            append("\nmode ${c.mode}  feature ${c.feature}  timing ${c.timing}")
            if (c.timing == Timing.STEADY) append(" (late ${engine.pipeline.timing.late})")
            val synth = (application as StompApp).synth
            append("\naudio: ${synth.summary()}")
            append("\nstalls: BLE gaps ${src.gaps.summary()}, reconnects ${src.reconnects}, failed writes ${src.failedWrites}; " +
                "UI freezes ${UiStallWatch.freezes.summary()}")
            append("\ncalibration: angle error %.1f°%s".format(p.conventionErrorDeg, if (p.conventionErrorDeg > 10) " (angles ignored)" else ""))
        }
    }

    private fun copyStatus() {
        val text = statusText() + "\n\nlatency: " + LatencyMeter.summary(engine.latency.poll(System.nanoTime()), engine.latency.unpaired) +
            "\nconfig: " + engine.config.toMap().entries.joinToString(" ") { "${it.key}=${it.value}" } +
            "\nphone: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}"
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("stomp status", text))
        toast("Status copied: paste it into a message")
    }

    private fun onRecorded(f: File) {
        lastFile = f
        val where = engine.saveToDownloads(f)
        saved.text = if (where != null) "Saved to $where (Files app › Downloads › StompBox)." else "Saved in app storage: ${f.name}"
        share(f)
    }

    private fun share(f: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share stomp log"))
    }

    private fun set(f: (StompConfig) -> StompConfig) { engine.config = f(engine.config); refreshers.forEach { it() } }

    private fun slider(parent: LinearLayout, name: String, unit: String, min: Double, max: Double, step: Double,
                       get: (StompConfig) -> Double, put: (StompConfig, Double) -> StompConfig) {
        val label = TextView(this)
        val bar = SeekBar(this).apply { this.max = round((max - min) / step).toInt() }
        fun show(v: Double) { label.text = "$name: %.2f %s".format(v, unit) }
        val sync = { val v = get(engine.config); bar.progress = round((v - min) / step).toInt(); show(v) }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = min + p * step; show(v); engine.config = put(engine.config, v)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        sync(); refreshers += sync
        parent.addView(label); parent.addView(bar)
    }

    /** A button that shows the current value and changes it on tap. */
    private fun cycler(label: () -> String, onClick: () -> Unit): Button = Button(this).apply {
        text = label(); isAllCaps = false
        refreshers += { text = label() }
        setOnClickListener { onClick(); refreshers.forEach { it() } }
    }

    private fun header(t: String) = TextView(this).apply { text = t; textSize = 17f; setPadding(0, 28, 0, 6) }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
    private fun button(label: String, onClick: (Button) -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick(this) }
    }
    private fun row(vararg v: android.view.View) = LinearLayout(this).apply {
        v.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
    }
}
