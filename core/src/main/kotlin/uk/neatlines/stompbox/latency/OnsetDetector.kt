package uk.neatlines.stompbox.latency

import kotlin.math.abs
import kotlin.math.max

/**
 * Finds the start of sharp impact sounds in microphone audio, for latency testing.
 *
 * A one-pole high-pass removes rumble and DC, then an onset is the first sample whose
 * magnitude exceeds both an absolute floor and [ratio] times the running noise level.
 * After an onset the detector is deaf for [refractoryFrames] so the ring-out of the same hit
 * is not counted again. Accuracy is one sample (~21 µs at 48 kHz) plus the rise time of the hit.
 */
class OnsetDetector(
    sampleRate: Int,
    private val ratio: Float = 12f,
    private val absFloor: Float = 0.02f,
    refractoryMs: Float = 150f,
    highPassHz: Float = 200f,
) {
    private val refractoryFrames = (sampleRate * refractoryMs / 1000f).toLong()
    private val hpA: Float = run {
        val rc = 1f / (2f * Math.PI.toFloat() * highPassHz)
        val dt = 1f / sampleRate
        rc / (rc + dt)
    }
    private val noiseA = 1f - 1f / (sampleRate * 0.2f)    // ~200 ms noise follower
    private var prevX = 0f
    private var prevY = 0f
    private var noise = 0.001f
    private var lastOnset = Long.MIN_VALUE / 2

    /**
     * Processes [count] samples (−1..1) that start at absolute frame [firstFrame].
     * Calls [onOnset] with the absolute frame of each onset.
     */
    fun process(samples: FloatArray, count: Int, firstFrame: Long, onOnset: (frame: Long) -> Unit) {
        for (i in 0 until count) {
            val x = samples[i]
            val y = hpA * (prevY + x - prevX)
            prevX = x; prevY = y
            val m = abs(y)
            val frame = firstFrame + i
            val thr = max(absFloor, noise * ratio)
            if (m > thr && frame - lastOnset > refractoryFrames) {
                lastOnset = frame
                onOnset(frame)
            }
            // Learn the noise level only outside hits, so a hit's tail does not raise it.
            if (frame - lastOnset > refractoryFrames) noise = noiseA * noise + (1 - noiseA) * m
        }
    }
}
