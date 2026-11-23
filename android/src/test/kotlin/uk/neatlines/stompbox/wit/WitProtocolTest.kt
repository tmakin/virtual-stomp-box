package uk.neatlines.stompbox.wit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WitProtocolTest {
    private fun frame(kind: Int, vararg v: Int): ByteArray {
        val b = ByteArray(20); b[0] = 0x55; b[1] = kind.toByte()
        v.forEachIndexed { i, x -> b[2 + 2 * i] = (x and 0xFF).toByte(); b[3 + 2 * i] = (x shr 8 and 0xFF).toByte() }
        return b
    }

    private fun bytes(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }

    @Test fun commandsMatchWitMotionSdk() {
        assertArrayEquals(bytes(0xFF, 0xAA, 0x69, 0x88, 0xB5), Wit.UNLOCK)
        assertArrayEquals(bytes(0xFF, 0xAA, 0x00, 0x00, 0x00), Wit.SAVE)
        assertArrayEquals(bytes(0xFF, 0xAA, 0x03, 0x0B, 0x00), Wit.setRate(200))
        assertArrayEquals(bytes(0xFF, 0xAA, 0x1F, 0x02, 0x00), Wit.setBandwidth(188))
        assertArrayEquals(bytes(0xFF, 0xAA, 0x27, 0x64, 0x00), Wit.readRegister(Wit.REG_BATTERY))
    }

    @Test fun parsesSampleInPhysicalUnits() {
        val out = ArrayList<WitSample>()
        // +1 g on z, -0.5 g on x, 1000 °/s gyro x, roll 90°, pitch -45°
        WitPacketParser({ out += it }).feed(frame(0x61, -1024, 0, 2048, 16384, 0, 0, 16384, -8192, 0))
        val s = out.single()
        assertEquals(-0.5f, s.ax, 1e-4f); assertEquals(1f, s.az, 1e-4f)
        assertEquals(1000f, s.gx, 1e-2f); assertEquals(90f, s.roll, 1e-3f); assertEquals(-45f, s.pitch, 1e-3f)
    }

    @Test fun handlesSplitFramesGarbageAndRegisterReplies() {
        val samples = ArrayList<WitSample>()
        val regs = ArrayList<Pair<Int, ShortArray>>()
        val p = WitPacketParser({ samples += it }, { r, v -> regs += r to v })
        val stream = bytes(0x00, 0x55, 0x12) + frame(0x61, 2048) + frame(0x71, Wit.REG_BATTERY, 395) + frame(0x61, 4096)
        // Feed in awkward chunks.
        var i = 0
        for (n in listOf(5, 17, 1, 30, 100)) { val k = minOf(n, stream.size - i); if (k > 0) p.feed(stream, i, k); i += k }
        assertEquals(2, samples.size)
        assertEquals(2.0f, samples[1].ax, 1e-4f)
        assertEquals(Wit.REG_BATTERY, regs.single().first)
        assertEquals(395, regs.single().second[0].toInt())
        assertEquals(3L, p.badBytes)
    }

    @Test fun batteryTable() {
        assertEquals(100, Wit.batteryPercent(4.1)); assertEquals(50, Wit.batteryPercent(3.80)); assertEquals(0, Wit.batteryPercent(3.3))
    }
}
