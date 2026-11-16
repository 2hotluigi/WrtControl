package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.es
import dev.angel.wrtcontrol.ubus.UbusJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkHealthTest {

    private val good = WanLink("eth1", carrier = true, speedMbps = 1000, dropsSinceBoot = 0, ownMaxMbps = 2500, partnerMaxMbps = 1000, dropsAgoSeconds = emptyList(), internet = true)

    @Test
    fun `enlace sano - nada que decir`() {
        assertTrue(good.problems().isEmpty())
        assertNull(linkAdvice(good.problems()))
    }

    @Test
    fun `el caso del cable con un par dañado - caidas seguidas y a 100`() {
        val ago = (1..13).map { it * 40L } // 13 caídas en menos de 10 minutos
        val bad = good.copy(speedMbps = 100, dropsSinceBoot = 40, dropsAgoSeconds = ago + listOf(5_000L))
        assertEquals(listOf(LinkProblem.Flapping(13, 10), LinkProblem.Slow(100, 1000)), bad.problems())
        assertEquals(
            "El cable de internet se desconectó 13 veces en los últimos 10 minutos. Va a 100 Mbps, aunque el router y el módem " +
                "admiten 1000. Suele ser el cable: cambia el que va del módem al router.",
            linkAdvice(bad.problems())!!.es,
        )
    }

    @Test
    fun `una caida suelta no es un cable malo - varias en una hora si`() {
        assertTrue(good.copy(dropsSinceBoot = 1, dropsAgoSeconds = listOf(120)).problems().isEmpty())
        assertEquals(
            listOf(LinkProblem.Flapping(3, 60)),
            good.copy(dropsSinceBoot = 3, dropsAgoSeconds = listOf(900, 1_800, 3_000)).problems(),
        )
        // Las de ayer ya no cuentan.
        assertTrue(good.copy(dropsSinceBoot = 9, dropsAgoSeconds = List(9) { 90_000L + it }).problems().isEmpty())
    }

    @Test
    fun `sin el registro se cuenta desde el arranque`() {
        assertEquals(listOf(LinkProblem.FlappingSinceBoot(7)), good.copy(dropsSinceBoot = 7, dropsAgoSeconds = null).problems())
        assertTrue(good.copy(dropsSinceBoot = 2, dropsAgoSeconds = null).problems().isEmpty())
        assertEquals(
            "El cable de internet se ha desconectado 7 veces desde que se encendió el router. Suele ser el cable: cambia el que va " +
                "del módem al router.",
            linkAdvice(listOf(LinkProblem.FlappingSinceBoot(7)))!!.es,
        )
    }

    @Test
    fun `lento solo si los dos lados dan para mas`() {
        // Un módem con puerto de 100: no es el cable.
        assertTrue(good.copy(speedMbps = 100, partnerMaxMbps = 100).problems().isEmpty())
        // Si no se sabe qué anuncia el otro lado, no se acusa al cable.
        assertTrue(good.copy(speedMbps = 100, partnerMaxMbps = null).problems().isEmpty())
        assertEquals(listOf(LinkProblem.Slow(100, 1000)), good.copy(speedMbps = 100).problems())
        // A 1000 con un router de 2500 y un módem de 1000 va todo lo que puede.
        assertTrue(good.copy(ownMaxMbps = 2500, partnerMaxMbps = 1000).problems().isEmpty())
    }

    @Test
    fun `sin enlace - cable suelto o modem apagado, y nada mas`() {
        val down = good.copy(carrier = false, speedMbps = null, dropsAgoSeconds = listOf(10, 20, 30), internet = null)
        assertEquals(listOf(LinkProblem.NoCable), down.problems())
        assertTrue("módem" in linkAdvice(down.problems())!!.es)
    }

    @Test
    fun `con enlace pero sin internet - es cosa del modem o del proveedor, no del cable`() {
        val advice = linkAdvice(good.copy(internet = false).problems())!!.es
        assertTrue(advice, "proveedor" in advice && "cambia el que va" !in advice)
        assertTrue("sin poder probarlo no se dice nada", good.copy(internet = null).problems().isEmpty())
    }

    // ---------- lo que cuenta el router ----------

    @Test
    fun `velocidades anunciadas`() {
        assertEquals(2500, maxAdvertisedMbps(listOf("10baseT-H", "100baseT-F", "1000baseT-F", "2500baseT-F")))
        assertEquals(1000, maxAdvertisedMbps(listOf("1000baseT-H", "1000baseT-F", "100baseT-F")))
        assertNull(maxAdvertisedMbps(emptyList()))
    }

    @Test
    fun `caidas en el registro - solo las de esa tarjeta, con hace cuanto`() {
        val log = listOf(
            LogJson.Line("kernel: [ 100.1] mtk_soc_eth 15100000.ethernet eth1: Link is Down", time = 1_000_000_000),
            LogJson.Line("kernel: [ 101.9] mtk_soc_eth 15100000.ethernet eth1: Link is Up - 100Mbps/Full", time = 1_000_002_000),
            LogJson.Line("kernel: [ 150.0] mt7530-mdio lan1: Link is Down", time = 1_000_050_000),
            LogJson.Line("eth1: Link is Down", time = 1_000_300_000),
            LogJson.Line("kernel: veth1: Link is Down", time = 1_000_301_000),
        )
        assertEquals(listOf(600L, 300L), linkDrops(log, "eth1", nowSeconds = 1_000_600))
        // Un reloj del router atrasado no da tiempos negativos.
        assertEquals(listOf(0L, 0L), linkDrops(log, "eth1", nowSeconds = 1))
    }

    @Test
    fun `la tarjeta de la salida a internet - tambien con la WAN caida`() {
        val lan = NetInterface("lan", up = true, device = "br-lan")
        val wan = NetInterface("wan", up = true, device = "eth1", ipv4 = listOf(IpAddress("203.0.113.7", 24)), routes = listOf(Route("0.0.0.0", 0)))
        assertEquals("eth1", wanDevice(listOf(lan, wan)))
        assertEquals("eth1", wanDevice(listOf(lan, wan.copy(up = false, ipv4 = emptyList(), routes = emptyList()))))
        assertNull(wanDevice(listOf(lan)))
    }

    @Test
    fun `lectura - con enlace y sin el`() {
        val up = UbusJson.decodeFromString<NetworkDeviceJson>(
            """{"link":{"speed":1000,"duplex":"full","carrier":true,"changes":5,"up_count":3,"down_count":2},"devtype":"ethernet"}""",
        ).link!!
        val adv = UbusJson.decodeFromString<DeviceLinkJson>(
            """{"carrier":true,"speed":"1000F","link-advertising":["1000baseT-F","2500baseT-F"],"link-partner-advertising":["100baseT-F","1000baseT-F"]}""",
        )
        assertEquals(
            WanLink("eth1", true, 1000, 2, ownMaxMbps = 2500, partnerMaxMbps = 1000, dropsAgoSeconds = listOf(60), internet = true),
            wanLink("eth1", up, adv, listOf(60), true),
        )
        val down = UbusJson.decodeFromString<NetworkDeviceJson>("""{"link":{"speed":-1,"carrier":false,"down_count":4}}""").link!!
        val link = wanLink("eth1", down, null, null, null)
        assertEquals(false, link.carrier)
        assertNull(link.speedMbps)
        assertEquals(4, link.dropsSinceBoot)
    }
}
