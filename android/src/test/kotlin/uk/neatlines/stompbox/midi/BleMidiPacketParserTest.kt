package uk.neatlines.stompbox.midi

import org.junit.Assert.assertEquals
import org.junit.Test

class BleMidiPacketParserTest {
    private fun parse(vararg b: Int): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>()
        BleMidiPacketParser().parse(ByteArray(b.size) { b[it].toByte() }) { n, v, t -> out += Triple(n, v, t) }
        return out
    }

    @Test fun firmwarePacket() {
        // Same layout the XIAO firmware sends: ts = 0x1234 & 0x1FFF.
        val ts = 0x1234 and 0x1FFF
        val got = parse(0x80 or ((ts shr 7) and 0x3F), 0x80 or (ts and 0x7F), 0x99, 36, 100)
        assertEquals(listOf(Triple(36, 100, ts)), got)
    }

    @Test fun twoMessagesWithOwnTimestamps() {
        val got = parse(0x81, 0x85, 0x99, 36, 100, 0x86, 0x99, 38, 90)
        assertEquals(listOf(Triple(36, 100, 133), Triple(38, 90, 134)), got)
    }

    @Test fun runningStatusWithoutTimestamp() {
        val got = parse(0x80, 0x80, 0x99, 36, 100, 38, 90)
        assertEquals(listOf(Triple(36, 100, 0), Triple(38, 90, 0)), got)
    }

    @Test fun badHeaderIgnored() {
        assertEquals(emptyList<Triple<Int, Int, Int>>(), parse(0x40, 0x80, 0x99, 36, 100))
    }
}
