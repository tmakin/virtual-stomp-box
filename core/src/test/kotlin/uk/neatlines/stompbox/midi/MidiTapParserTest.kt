package uk.neatlines.stompbox.midi

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiTapParserTest {
    private fun notes(p: MidiTapParser, vararg bytes: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        p.parse(arr, 0, arr.size) { n, v -> out += n to v }
        return out
    }

    @Test fun singleNoteOn() {
        assertEquals(listOf(36 to 100), notes(MidiTapParser(), 0x99, 36, 100))
    }

    @Test fun runningStatus() {
        assertEquals(listOf(36 to 100, 38 to 90), notes(MidiTapParser(), 0x99, 36, 100, 38, 90))
    }

    @Test fun velocityZeroAndNoteOffIgnored() {
        assertEquals(emptyList<Pair<Int, Int>>(), notes(MidiTapParser(), 0x99, 36, 0, 0x89, 36, 64))
    }

    @Test fun realTimeInsideMessageIsTransparent() {
        assertEquals(listOf(36 to 100), notes(MidiTapParser(), 0x99, 36, 0xF8, 100))
    }

    @Test fun otherMessagesAndSysexSkipped() {
        val p = MidiTapParser()
        assertEquals(
            listOf(40 to 70),
            notes(p, 0xB0, 1, 64, 0xC0, 5, 0xF0, 0x7D, 36, 100, 0xF7, 0xD0, 9, 0x90, 40, 70),
        )
    }

    @Test fun channelFilter() {
        val p = MidiTapParser(channel = 9)
        assertEquals(listOf(36 to 100), notes(p, 0x90, 36, 100, 0x99, 36, 100))
    }

    @Test fun splitAcrossCalls() {
        val p = MidiTapParser()
        assertEquals(emptyList<Pair<Int, Int>>(), notes(p, 0x99, 36))
        assertEquals(listOf(36 to 100), notes(p, 100))
    }
}
