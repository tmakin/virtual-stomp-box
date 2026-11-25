package uk.neatlines.stompbox

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
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
    private var connected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; padForSystemBars(32) }

        status = TextView(this).apply { textSize = 13f }
        big = TextView(this).apply {
            textSize = 48f; gravity = Gravity.CENTER; text = "—"; setTextColor(Color.WHITE)
        }
        connect = Button(this).apply { text = "Connect sensor"; setOnClickListener { toggleConnect() } }
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
        col.addView(connect, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        col.addView(LinearLayout(this).apply {
            addView(tune, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(mute, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })
        setContentView(col)

        app.engine.onStompUi = { e -> runOnUiThread { show(e) } }
    }

    override fun onResume() { super.onResume(); status.post(refresh) }
    override fun onPause() { super.onPause(); status.removeCallbacks(refresh) }

    private fun toggleConnect() {
        if (connected) { app.engine.stop(); connected = false; connect.text = "Connect sensor"; return }
        val needed = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) { requestPermissions(needed.toTypedArray(), 1); return }
        app.engine.start()
        connected = true; connect.text = "Disconnect"
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 1 && results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) toggleConnect()
        else if (requestCode == 1) status.text = "Bluetooth permission is needed to find the sensor."
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
                append("Sensor: ${s.deviceName ?: "-"} · ${s.status}")
                if (!s.batteryVolts.isNaN()) append(" · battery %.2f V".format(s.batteryVolts))
                append("\nMode ${e.config.mode} · timing ${e.config.timing} · ${e.rateHz} Hz · stomps ${e.pipeline.detector.triggers}")
                append("\nAudio: ${app.synth.summary()}")
                if (s.reconnects > 0 || s.gaps.count > 0) append("\nBLE gaps ${s.gaps.summary()} · reconnects ${s.reconnects}")
                if (s.status == "streaming") append("\n").append(e.link().format())
            }
            status.postDelayed(this, 500)
        }
    }
}
