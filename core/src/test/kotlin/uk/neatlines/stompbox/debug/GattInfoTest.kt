package uk.neatlines.stompbox.debug

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class GattInfoTest {
    private val wit = GattInfo.sig(0xFFE5)
    private val dfu = GattInfo.sig(0xFE59)
    private val custom = UUID.fromString("12345678-1234-1234-1234-123456789abc")

    @Test fun describesServicesWithNamesAndCharacteristics() {
        assertEquals(
            "FFE5 WitMotion data [FFE4 FFE9], 12345678-1234-1234-1234-123456789abc [2A26]",
            GattInfo.describe(listOf(wit to listOf(GattInfo.sig(0xFFE4), GattInfo.sig(0xFFE9)), custom to listOf(GattInfo.sig(0x2A26)))),
        )
    }

    @Test fun findsUpdateServices() {
        assertEquals(listOf("Nordic Secure DFU"), GattInfo.updateServices(listOf(wit, dfu, custom)))
        assertEquals(emptyList<String>(), GattInfo.updateServices(listOf(wit)))
    }
}
