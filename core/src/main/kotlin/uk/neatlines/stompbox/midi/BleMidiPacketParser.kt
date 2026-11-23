package uk.neatlines.stompbox.midi

/**
 * Parses raw BLE-MIDI packets (as received from the GATT characteristic, before Android's MIDI
 * service touches them) into note-on taps with the sender's 13-bit millisecond timestamp.
 *
 * Packet layout (MIDI Association BLE-MIDI spec):
 *   header 1 0 t12..t7 | timestamp 1 t6..t0 | status | data... | [timestamp | status | data...]...
 * Running status may follow a data byte with no new timestamp.
 *
 * Used by the raw-GATT input path so its latency can be compared with android.media.midi.
 */
class BleMidiPacketParser(channel: Int? = null) {
    private val midi = MidiTapParser(channel)

    /** Calls [onNote] with (note, velocity, senderMillis13) for each note-on in [packet]. */
    fun parse(packet: ByteArray, onNote: (note: Int, velocity: Int, senderMillis: Int) -> Unit) {
        if (packet.size < 3) return
        val header = packet[0].toInt() and 0xFF
        if (header and 0xC0 != 0x80) return
        val tsHigh = header and 0x3F
        var ts = 0
        var expectTimestamp = true
        for (i in 1 until packet.size) {
            val b = packet[i].toInt() and 0xFF
            if (expectTimestamp && b and 0x80 != 0) {
                ts = (tsHigh shl 7) or (b and 0x7F)
                expectTimestamp = false
                continue
            }
            if (b and 0x80 == 0) expectTimestamp = true  // after a data byte a timestamp may come next
            val r = midi.feed(b)
            if (r >= 0) onNote(r shr 8, r and 0xFF, ts)
        }
    }
}
