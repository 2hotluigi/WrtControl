package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.ubus.UbusJson
import dev.angel.wrtcontrol.ui.formatScale
import dev.angel.wrtcontrol.ui.niceCeilBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class UsageTest {

    @Test
    fun `contadores de la salida a internet - lo que da network device status`() {
        // Recortado de un OpenWrt 25.12 (la tarjeta de la WAN de un router con DSA).
        val json = UbusJson.decodeFromString<DeviceStatsJson>(
            """{"external":false,"present":true,"type":"Network device","up":true,"carrier":true,"mtu":1500,"macaddr":"02:00:00:00:00:01",
              |"statistics":{"collisions":0,"rx_frame_errors":0,"tx_compressed":0,"multicast":12,"rx_bytes":53687091200,"tx_bytes":5368709120,
              |"rx_packets":41000000,"tx_packets":9000000,"rx_errors":0,"tx_errors":0}}
            """.trimMargin(),
        )
        val usage = wanUsage("wan", json, uptimeSeconds = 5 * DAY)!!
        assertEquals(WanUsage("wan", 50L * GIB, 5L * GIB, 5 * DAY), usage)
        assertEquals(55L * GIB, usage.totalBytes)
        assertEquals(11L * GIB, usage.perDayBytes)
    }

    @Test
    fun `sin contadores no hay consumo que enseñar`() {
        assertNull(wanUsage("wan", UbusJson.decodeFromString<DeviceStatsJson>("""{"up":true}"""), 100))
        assertNull(wanUsage("wan", UbusJson.decodeFromString<DeviceStatsJson>("""{"statistics":{"rx_bytes":10}}"""), 100))
    }

    @Test
    fun `la media por dia solo con un dia encendido como poco`() {
        assertNull(WanUsage("wan", 3 * GIB, GIB, DAY - 1).perDayBytes)
        assertEquals(2 * GIB, WanUsage("wan", 3 * GIB, GIB, 2 * DAY).perDayBytes)
        // Cifras enormes sin que se desborde (bytes × segundos de un día no cabe en un Long).
        assertEquals(PIB, WanUsage("wan", 1000 * PIB, 0, 1000 * DAY).perDayBytes)
    }

    @Test
    fun `cada periodo coge lo suyo y los totales suman`() {
        val now = LocalDateTime.of(2026, 11, 23, 14, 0)
        val hours = (0 until 48).map { UsageBucket(now.minusHours(47L - it), 100L * it, 10L * it) }
        val days = (0 until 40).map { UsageBucket(now.toLocalDate().atStartOfDay().minusDays(39L - it), 1000L, 100L) }
        val history = UsageHistory(hours, days)

        val day = history.buckets(UsagePeriod.Day)
        assertEquals(24, day.size)
        assertEquals(now, day.last().start)
        assertEquals(now.minusHours(23), day.first().start)
        assertEquals(7, history.buckets(UsagePeriod.Week).size)
        assertEquals(30, history.buckets(UsagePeriod.Month).size)

        val totals = history.buckets(UsagePeriod.Month).totals()
        assertEquals(UsageTotals(30_000, 3_000), totals)
        assertEquals(33_000, totals.totalBytes)
        // Con menos de lo que pide el periodo, lo que haya.
        assertEquals(2, UsageHistory(hours.take(2), emptyList()).buckets(UsagePeriod.Day).size)
    }

    @Test
    fun `escala redonda para la grafica`() {
        assertEquals(1, niceCeilBytes(0))
        assertEquals(500, niceCeilBytes(487))
        assertEquals(1024, niceCeilBytes(900))
        assertEquals(2 * MIB, niceCeilBytes(MIB + 1))
        assertEquals(500 * MIB, niceCeilBytes(487 * MIB))
        assertEquals(GIB, niceCeilBytes(GIB))
        assertEquals(15 * GIB, niceCeilBytes(11 * GIB))
        assertEquals(30 * GIB, niceCeilBytes(29 * GIB))
    }

    @Test
    fun `marcas del eje sin decimales de sobra`() {
        assertEquals("20 GiB", formatScale(20 * GIB))
        assertEquals("7.5 GiB", formatScale(15 * GIB / 2))
        assertEquals("500 MiB", formatScale(500 * MIB))
    }

    private companion object {
        const val DAY = 86_400L
        const val MIB = 1024L * 1024
        const val GIB = 1024L * MIB
        const val PIB = 1024L * 1024 * GIB
    }
}
