package uk.neatlines.stompbox

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import uk.neatlines.stompbox.audio.SoundSettings
import uk.neatlines.stompbox.debug.StompTuningActivity
import uk.neatlines.stompbox.stomp.StompEvent
import uk.neatlines.stompbox.ui.padForSystemBars

/**
 * Test app home screen: connect to the WT901, see and hear each stomp, open the tuning screen.
 * Layout is built in code to keep the app to a handful of files.
 */
class MainActivity : Activity() {
    private val app get() = application as StompApp
    private lateinit var status: TextView
    private lateinit var big: TextView
    private lateinit var connect: Button
    private lateinit var link: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; padForSystemBars(32) }

        status = TextView(this).apply { textSize = 13f }
        big = TextView(this).apply {
            textSize = 48f; gravity = Gravity.CENTER; text = "—"; setTextColor(Color.WHITE)
        }
        connect = Button(this).apply { text = "Connect sensor"; setOnClickListener { toggleConnect() } }
        link = Button(this).apply {
            isAllCaps = false
            setOnClickListener { app.engine.wired = !app.engine.wired; showConnected() }
        }
        val tune = Button(this).apply {
            text = "Tune & debug"
            setOnClickListener { startActivity(Intent(this@MainActivity, StompTuningActivity::class.java)) }
        }
        val mute = Button(this).apply {
            text = "Sound: on"
            setOnClickListener { app.synth.muted = !app.synth.muted; text = if (app.synth.muted) "Sound: off" else "Sound: on" }
        }

        col.addView(status)
        col.addView(big, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        col.addView(link, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(connect, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val sound = Button(this).apply { text = "Sound settings"; setOnClickListener { soundDialog() } }
        col.addView(sound, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(LinearLayout(this).apply {
            addView(tune, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(mute, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })
        setContentView(col)

        app.engine.onStompUi = { e -> runOnUiThread { show(e) } }
        onUsbAttached(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); onUsbAttached(intent) }

    /** Opened by plugging in the sensor (see res/xml/usb_device_filter.xml): switch to wired and connect. */
    private fun onUsbAttached(i: Intent?) {
        if (i?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        app.engine.wired = true
        if (!app.engine.isRunning) app.engine.start()
        showConnected()
    }

    override fun onResume() { super.onResume(); showConnected(); status.post(refresh) }
    override fun onPause() { super.onPause(); status.removeCallbacks(refresh) }

    private fun toggleConnect() {
        // The engine outlives this screen, so ask it rather than keeping a flag that a recreated
        // activity would lose (and then start a second connection).
        if (app.engine.isRunning) { app.engine.stop(); showConnected(); return }
        // USB asks for its own permission. Before Android 13 Bluetooth isn't supported and its
        // source just says so, so don't ask for permissions that don't exist there.
        if (app.engine.wired || Build.VERSION.SDK_INT < 33) { app.engine.start(); showConnected(); return }
        val needed = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) { requestPermissions(needed.toTypedArray(), 1); return }
        app.engine.start()
        showConnected()
    }

    private fun showConnected() {
        connect.text = if (app.engine.isRunning) "Disconnect" else "Connect sensor"
        link.text = "Sensor link: ${app.engine.source.kind} (tap to switch)"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 1 && results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) toggleConnect()
        else if (requestCode == 1) status.text = "Bluetooth permission is needed to find the sensor."
    }

    /** Pitch, tone and reverb sliders. Each change is saved and heard on the next stomp (or Test). */
    private fun soundDialog() {
        val synth = app.synth
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val refreshers = ArrayList<() -> Unit>()
        fun slider(steps: Int, toValue: (Int) -> Double, toStep: (SoundSettings) -> Int,
                   label: (SoundSettings) -> String, put: (SoundSettings, Double) -> SoundSettings) {
            val text = TextView(this).apply { textSize = 15f }
            val bar = SeekBar(this).apply { max = steps }
            val sync = { text.text = label(synth.sound); bar.progress = toStep(synth.sound) }
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) { synth.sound = put(synth.sound, toValue(p)); text.text = label(synth.sound) }
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
            sync(); refreshers += sync
            col.addView(text); col.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        val r = SoundSettings.PITCH_RANGE.toInt()
        slider(2 * r, { (it - r).toDouble() }, { it.pitchSemitones.toInt() + r },
            { "Pitch: %+d semitones".format(it.pitchSemitones.toInt()) }) { s, v -> s.copy(pitchSemitones = v) }
        slider(40, { (it - 20) / 20.0 }, { Math.round(it.tone * 20).toInt() + 20 }, {
            val t = Math.round(it.tone * 100).toInt()
            "Tone: " + when { t < 0 -> "darker ($t)"; t > 0 -> "brighter (+$t)"; else -> "neutral" }
        }) { s, v -> s.copy(tone = v) }
        slider(20, { it / 20.0 }, { Math.round(it.reverb * 20).toInt() },
            { if (it.reverb == 0.0) "Reverb: off" else "Reverb: %d%%".format(Math.round(it.reverb * 100)) }) { s, v -> s.copy(reverb = v) }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Sound")
            .setView(col)
            .setPositiveButton("Done", null)
            .setNeutralButton("Test", null)
            .setNegativeButton("Reset", null)
            .show()
        // Kick (36) is the stomp's default voice. Set as listeners here so the buttons don't close the dialog.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { synth.test() }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { synth.sound = SoundSettings(); refreshers.forEach { it() } }
    }

    private fun show(e: StompEvent) {
        big.text = "STOMP\n${e.velocity}"
        big.setBackgroundColor(0xFF2E7D32.toInt())
        big.animate().cancel()
        big.alpha = 1f
        big.animate().alpha(0.35f).setDuration(250).start()
    }

    private val refresh = object : Runnable {
        override fun run() {
            val e = app.engine; val s = e.source
            status.text = buildString {
                append("Build ${BuildConfig.BUILD_LABEL}\n")
                append("Sensor (${s.kind}): ${s.deviceName ?: "-"} · ${s.status}")
                if (!s.batteryVolts.isNaN()) append(" · ${if (e.wired) "supply" else "battery"} %.2f V".format(s.batteryVolts))
                append("\nMode ${e.config.mode} · ${e.rateHz} Hz · stomps ${e.pipeline.detector.triggers}")
                append("\nAudio: ${app.synth.summary()}")
                if (s.reconnects > 0 || s.gaps.count > 0) append("\ngaps ${s.gaps.summary()} · reconnects ${s.reconnects}")
                if (s.status == "streaming") append("\n").append(e.link().format())
            }
            status.postDelayed(this, 500)
        }
    }
}
