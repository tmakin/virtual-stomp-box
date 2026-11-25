package uk.neatlines.stompbox.debug

import java.util.UUID

/**
 * Describes a sensor's GATT table in one line, naming the services that matter for firmware work:
 * a firmware-update (DFU) service means the sensor can be updated over Bluetooth, and that an
 * update could be captured to get at the firmware.
 */
object GattInfo {
    private const val BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    val DEVICE_INFO: UUID = sig(0x180A)
    /** Device Information strings worth showing, by characteristic. */
    val DEVICE_INFO_STRINGS = linkedMapOf(
        sig(0x2A29) to "maker", sig(0x2A24) to "model", sig(0x2A27) to "hardware",
        sig(0x2A26) to "firmware", sig(0x2A28) to "software",
    )

    private val NAMES = mapOf(
        sig(0x1800) to "GAP", sig(0x1801) to "GATT", DEVICE_INFO to "Device Info",
        sig(0x180F) to "Battery", sig(0xFFE5) to "WitMotion data",
        sig(0xFE59) to "Nordic Secure DFU",
        UUID.fromString("00001530-1212-efde-1523-785feabcd123") to "Nordic legacy DFU",
        UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e") to "Nordic UART",
        UUID.fromString("8d53dc1d-1db7-4cd3-868b-8a527460aa84") to "SMP (MCUboot DFU)",
    )
    private val DFU = setOf("Nordic Secure DFU", "Nordic legacy DFU", "SMP (MCUboot DFU)")

    fun sig(short: Int): UUID = UUID.fromString("%08x%s".format(short, BASE_SUFFIX))

    /** "FFE5" for a Bluetooth SIG-base UUID, the full UUID otherwise. */
    fun short(u: UUID): String {
        val s = u.toString()
        return if (s.endsWith(BASE_SUFFIX) && s.startsWith("0000")) s.substring(4, 8).uppercase() else s
    }

    /** e.g. "1800 GAP [2A00 2A01], FFE5 WitMotion data [FFE4 FFE9]". */
    fun describe(services: List<Pair<UUID, List<UUID>>>): String =
        services.joinToString(", ") { (svc, chars) ->
            listOfNotNull(short(svc), NAMES[svc]).joinToString(" ") +
                chars.joinToString(" ", " [", "]") { short(it) }
        }

    /** The firmware-update services present, or an empty list. */
    fun updateServices(services: List<UUID>): List<String> = services.mapNotNull { NAMES[it] }.filter { it in DFU }
}
