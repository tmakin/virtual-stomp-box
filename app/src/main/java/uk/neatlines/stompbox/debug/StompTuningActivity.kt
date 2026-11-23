package uk.neatlines.stompbox.debug

import android.app.Activity
import android.content.Intent
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
import androidx.core.content.FileProvider
import uk.neatlines.stompbox.stomp.Feature
import uk.neatlines.stompbox.stomp.Mode
import uk.neatlines.stompbox.stomp.StompConfig
import uk.neatlines.stompbox.stomp.StompEngine
import uk.neatlines.stompbox.stomp.Timing
import uk.neatlines.stompbox.stomp.Zone

/**
 * Live debug and tuning for the WT901 stomp sensor. Built in code (no layout XML) so it drops into
 * the app as one file. Every change applies immediately and is saved for the current mode.
 *
 * Top to bottom: status (link, battery, calibration), live plot, last stomp, mode / feature /
 * timing toggles, calibration and recording buttons, sliders.
 *
 * The app must pass its engine in via [engineHolder] before starting this activity.
 */
class StompTuningActivity : Activity() {
    companion object { var engineHolder: StompEngine? = null }

    private lateinit var engine: StompEngine
    private lateinit var status: TextView
    private lateinit var last: TextView
    private lateinit var plot: StompPlotView
    private val sliders = ArrayList<() -> Unit>()       // refresh slider positions after a mode switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine = engineHolder ?: run { finish(); return }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }

        status = TextView(this).apply { textSize = 12f }
        col.addView(status)
        plot = StompPlotView(this).apply {
            zoneOf = { idx -> synchronized(engine.recentStomps) { engine.recentStomps.lastOrNull { it.sampleIndex == idx }?.zone }.let { zoneColor(it) } }
        }
        col.addView(plot, LinearLayout.LayoutParams(MATCH_PARENT, 520))
        last = TextView(this).apply { textSize = 18f }
        col.addView(last)

        col.addView(row(
            toggle("Mode") { engine.mode = if (engine.mode == Mode.SHOE) Mode.LEG else Mode.SHOE; sliders.forEach { it() } },
            toggle("Feature") { set { it.copy(feature = if (it.feature == Feature.VERTICAL) Feature.JERK else Feature.VERTICAL) } },
            toggle("Timing") { set { it.copy(timing = if (it.timing == Timing.IMMEDIATE) Timing.STEADY else Timing.IMMEDIATE) } },
        ))
        col.addView(row(
            button("Calibrate: stand still") { b -> b.text = "hold still…"; engine.calibrateRest { runOnUiThread { b.text = "Calibrate: stand still ✓" } } },
            button("Calibrate: toes up") { b -> b.text = "hold…"; engine.calibrateTilt { runOnUiThread { b.text = "Calibrate: toes up ✓" } } },
        ))
        val mic = CheckBox(this).apply { text = "with mic (latency)" }
        col.addView(row(
            button("Record") { b ->
                if (mic.isChecked && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 2)
                } else if (!engine.isRecording) { engine.startRecording(mic.isChecked); b.text = "Stop & share" }
                else { engine.stopRecording()?.let(::share); b.text = "Record" }
            },
            mic,
        ))

        slider(col, "Min threshold", "g", 0.2, 5.0, 0.05, { it.minThresholdG }) { c, v -> c.copy(minThresholdG = v) }
        slider(col, "Noise factor", "×", 2.0, 15.0, 0.5, { it.noiseFactor }) { c, v -> c.copy(noiseFactor = v) }
        slider(col, "Dead time", "ms", 30.0, 300.0, 5.0, { it.deadTimeMs }) { c, v -> c.copy(deadTimeMs = v) }
        slider(col, "High-pass", "Hz", 0.5, 20.0, 0.5, { it.highPassHz }) { c, v -> c.copy(highPassHz = v) }
        slider(col, "Peak wait", "samples", 0.0, 3.0, 1.0, { it.peakWaitSamples.toDouble() }) { c, v -> c.copy(peakWaitSamples = v.toInt()) }
        slider(col, "Velocity full scale", "g", 1.0, 16.0, 0.5, { it.velocityFullScaleG }) { c, v -> c.copy(velocityFullScaleG = v) }
        slider(col, "Velocity curve", "", 0.2, 2.0, 0.05, { it.velocityCurve }) { c, v -> c.copy(velocityCurve = v) }
        slider(col, "Heel tilt", "°", 2.0, 30.0, 1.0, { it.heelTiltDeg }) { c, v -> c.copy(heelTiltDeg = v) }
        slider(col, "Toe tilt", "°", 2.0, 30.0, 1.0, { it.toeTiltDeg }) { c, v -> c.copy(toeTiltDeg = v) }
        slider(col, "Gyro gate (0 = off)", "°/s", 0.0, 400.0, 10.0, { it.gyroGateDps }) { c, v -> c.copy(gyroGateDps = v) }
        slider(col, "Steady delay", "ms", 10.0, 60.0, 1.0, { it.steadyDelayMs }) { c, v -> c.copy(steadyDelayMs = v) }
        col.addView(button("Reset this mode to defaults") { set { StompConfig.forMode(it.mode) }; sliders.forEach { it() } })

        setContentView(ScrollView(this).apply { addView(col) })
        engine.pipeline.detector.onDebug = plot::add
        status.post(refresh)
    }

    override fun onDestroy() { super.onDestroy(); if (::engine.isInitialized) engine.pipeline.detector.onDebug = null }

    private val refresh = object : Runnable {
        override fun run() {
            val c = engine.config; val p = engine.pipeline.placement; val src = engine.source
            status.text = buildString {
                append("${src.deviceName ?: "-"}: ${src.status}  battery %.2f V\n".format(src.batteryVolts))
                append("rate reg ${src.rateRegister} (want 0x0B), bandwidth reg ${src.bandwidthRegister} (want 0x02)\n")
                append(engine.link().format()).append('\n')
                append("mode ${c.mode}  feature ${c.feature}  timing ${c.timing}${if (c.timing == Timing.STEADY) " (late ${engine.pipeline.timing.late})" else ""}\n")
                append("calibration: angle error %.1f° %s, tilt axis %s, suggests %s".format(p.conventionErrorDeg,
                    if (p.conventionErrorDeg > 10) "(angles ignored)" else "", if (p.tiltAxis == null) "not set" else "set", p.suggestedMode))
            }
            engine.recentStomps.let { synchronized(it) { it.lastOrNull() } }?.let { e ->
                last.text = "last: ${e.zone}  vel ${e.velocity}  peak %.2f g  tilt %.1f°  lag %.1f ms  (total ${engine.pipeline.detector.triggers})"
                    .format(e.peak, e.tiltDeg, (e.arrivalNanos - e.sampleNanos) / 1e6)
                last.setTextColor(zoneColor(e.zone))
            }
            status.postDelayed(this, 250)
        }
    }

    private fun set(f: (StompConfig) -> StompConfig) { engine.config = f(engine.config) }

    private fun slider(parent: LinearLayout, name: String, unit: String, min: Double, max: Double, step: Double,
                       get: (StompConfig) -> Double, put: (StompConfig, Double) -> StompConfig) {
        val label = TextView(this)
        val bar = SeekBar(this).apply { this.max = ((max - min) / step).toInt() }
        fun show(v: Double) { label.text = "$name: %.2f %s".format(v, unit) }
        val sync = { val v = get(engine.config); bar.progress = ((v - min) / step).toInt(); show(v) }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = min + p * step; show(v); set { put(it, v) }
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        sync(); sliders += sync
        parent.addView(label); parent.addView(bar)
    }

    private fun share(f: java.io.File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share stomp log"))
    }

    private fun zoneColor(z: Zone?) = when (z) { Zone.HEEL -> 0xFFFF9800.toInt(); Zone.TOE -> Color.MAGENTA; else -> Color.GREEN }
    private fun toggle(label: String, onClick: () -> Unit) = Button(this).apply { text = label; setOnClickListener { onClick() } }
    private fun button(label: String, onClick: (Button) -> Unit) = Button(this).apply { text = label; setOnClickListener { onClick(this) } }
    private fun row(vararg v: android.view.View) = LinearLayout(this).apply {
        v.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
    }
}
