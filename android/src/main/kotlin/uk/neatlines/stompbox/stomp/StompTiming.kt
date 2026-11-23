package uk.neatlines.stompbox.stomp

/**
 * When to play a detected stomp, on the System.nanoTime clock.
 *
 * IMMEDIATE plays now. STEADY plays at a fixed delay after the estimated impact, which removes
 * the BLE connection-interval jitter at the cost of a constant delay. If a frame arrives too late to
 * meet the target (a retransmission, say), it plays immediately and [late] counts it. Raise
 * [StompConfig.steadyDelayMs] if that number climbs.
 */
class StompTiming {
    var late = 0; private set

    fun fireAtNanos(e: StompEvent, c: StompConfig, nowNanos: Long): Long = when (c.timing) {
        Timing.IMMEDIATE -> nowNanos
        Timing.STEADY -> {
            val target = e.sampleNanos + (c.steadyDelayMs * 1e6).toLong()
            if (target < nowNanos) { late++; nowNanos } else target
        }
    }
}
