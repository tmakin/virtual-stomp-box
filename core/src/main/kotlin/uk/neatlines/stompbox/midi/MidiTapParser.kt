package uk.neatlines.stompbox.midi

/**
 * Turns the MIDI byte stream from a [android.media.midi.MidiReceiver] into note-on taps.
 *
 * Android's MIDI service has already removed the BLE-MIDI packet framing (header and timestamp
 * bytes), so this sees plain MIDI. It handles running status, ignores note-on with velocity 0
 * (which means note-off), skips real-time bytes that can appear mid-message, and drops SysEx.
 *
 * Not thread-safe: call from the single thread the receiver runs on.
 */
class MidiTapParser(
    /** Accept note-ons on this 0-based channel only, or any channel if null. */
    var channel: Int? = null,
) {
    private var status = 0
    private var data1 = -1
    private var inSysex = false

    /**
     * Parses [count] bytes from [msg] starting at [offset].
     * Calls [onNote] with (note, velocity 1..127) for every note-on.
     */
    inline fun parse(msg: ByteArray, offset: Int, count: Int, onNote: (note: Int, velocity: Int) -> Unit) {
        for (i in offset until offset + count) {
            val r = feed(msg[i].toInt() and 0xFF)
            if (r >= 0) onNote(r shr 8, r and 0xFF)
        }
    }

    /** Feeds one byte. Returns (note shl 8 or velocity) for a complete note-on, else -1. */
    fun feed(b: Int): Int {
        if (b >= 0xF8) return -1                       // real-time: transparent
        if (b >= 0x80) {
            when {
                b == 0xF0 -> { inSysex = true; status = 0 }
                b == 0xF7 -> { inSysex = false; status = 0 }
                b >= 0xF0 -> status = 0                // system common cancels running status
                else -> { inSysex = false; status = b }
            }
            data1 = -1
            return -1
        }
        if (inSysex || status == 0) return -1
        val type = status and 0xF0
        val oneDataByte = type == 0xC0 || type == 0xD0
        if (oneDataByte) return -1
        if (data1 < 0) { data1 = b; return -1 }
        val d1 = data1
        data1 = -1                                     // running status: next byte starts a new message
        if (type != 0x90 || b == 0) return -1
        val ch = channel
        if (ch != null && (status and 0x0F) != ch) return -1
        return (d1 shl 8) or b
    }

    fun reset() { status = 0; data1 = -1; inSysex = false }
}
