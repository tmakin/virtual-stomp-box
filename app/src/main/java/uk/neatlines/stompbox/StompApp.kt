package uk.neatlines.stompbox

import android.app.Application
import uk.neatlines.stompbox.audio.DrumSynth
import uk.neatlines.stompbox.debug.StompTuningActivity
import uk.neatlines.stompbox.stomp.StompEngine

/** Owns the sensor engine and sound for the whole app, so they survive switching screens. */
class StompApp : Application() {
    lateinit var synth: DrumSynth; private set
    lateinit var engine: StompEngine; private set

    override fun onCreate() {
        super.onCreate()
        synth = DrumSynth(this)
        engine = StompEngine(this, synth)
        StompTuningActivity.engineHolder = engine
    }
}
