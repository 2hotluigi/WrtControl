package dev.angel.wrtcontrol.addons

import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.data.TunnelConfig
import dev.angel.wrtcontrol.es
import dev.angel.wrtcontrol.txt
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class ReachabilityTest {

    private val config = TunnelConfig("r", "p", "k", "s", enabled = true, port = 51820)

    /** Datos móviles de mentira: [answers] por dirección probada; [dns], lo que resuelve el nombre. */
    private class FakeOutside(
        val answers: Map<String, ProbeOutcome>,
        val dns: List<String>? = null,
        val available: Boolean = true,
    ) : OutsideNetwork {
        val probed = mutableListOf<String>()

        override suspend fun <T> withMobileData(block: suspend (OutsideLink) -> T): T {
            if (!available) throw NoMobileDataException(txt(R.string.addons_reach_no_mobile_data))
            return block(object : OutsideLink {
                override fun resolve(host: String): List<InetAddress> =
                    dns?.map { InetAddress.getByName(it) } ?: throw UnknownHostException(host)

                override fun probe(config: TunnelConfig, endpoint: String): ProbeOutcome {
                    synchronized(probed) { probed += endpoint }
                    return answers[endpoint] ?: ProbeOutcome.NoReply
                }
            })
        }
    }

    private val v6 = "2001:db8:1::2"
    private val v4 = "203.0.113.5"

    @Test
    fun `prueba la IPv6 y la IPv4 publicas del router`() = runTest {
        val outside = FakeOutside(mapOf("[$v6]:51820" to ProbeOutcome.Reached, "$v4:51820" to ProbeOutcome.Reached))
        val report = checkFromOutside(outside, config, null, listOf(v6, v4))
        assertEquals(setOf("[$v6]:51820", "$v4:51820"), outside.probed.toSet())
        assertEquals(setOf(IpFamily.V4, IpFamily.V6), report.reached)
        assertNull(report.published)
    }

    @Test
    fun `sin IPv4 publica en el router prueba la que publique el DNS`() = runTest {
        val outside = FakeOutside(emptyMap(), dns = listOf(v6, "198.51.100.9"))
        val report = checkFromOutside(outside, config, "casa.example.org", listOf(v6))
        assertEquals(setOf("[$v6]:51820", "198.51.100.9:51820"), outside.probed.toSet())
        assertEquals(listOf(v6, "198.51.100.9"), report.published)
    }

    @Test
    fun `un nombre que no resuelve no impide probar`() = runTest {
        val outside = FakeOutside(mapOf("[$v6]:51820" to ProbeOutcome.Reached))
        val report = checkFromOutside(outside, config, "casa.example.org", listOf(v6))
        assertEquals(emptyList<String>(), report.published)
        assertEquals(setOf(IpFamily.V6), report.reached)
    }

    @Test(expected = NoMobileDataException::class)
    fun `sin datos moviles se explica`() = runTest {
        checkFromOutside(FakeOutside(emptyMap(), available = false), config, null, listOf(v6))
    }

    private fun ctx(ddns: Set<IpFamily>? = setOf(IpFamily.V6), privateWan: String? = null, public: List<String> = listOf(v6)) =
        RouterReachContext(port = 51820, privateWanIpv4 = privateWan, ddnsFamilies = ddns, publicAddresses = public)

    @Test
    fun `el modem que filtra el IPv6 se senala`() {
        val report = ReachabilityReport(listOf(FamilyProbe(IpFamily.V6, v6, ProbeOutcome.NoReply)), listOf(v6))
        val advice = adviseReachability(report, ctx(privateWan = "192.168.1.2"))
        assertFalse(advice.ok)
        assertTrue(advice.texts[0], "cortafuegos IPv6" in advice.texts[0] && "DMZ" in advice.texts[0])
        assertTrue(advice.texts[1], "192.168.1.2" in advice.texts[1])
        assertNull(advice.publishOnly)
    }

    @Test
    fun `si la IPv4 publicada no entra propone publicar solo IPv6`() {
        val report = ReachabilityReport(
            listOf(FamilyProbe(IpFamily.V6, v6, ProbeOutcome.Reached), FamilyProbe(IpFamily.V4, v4, ProbeOutcome.NoReply)),
            listOf(v6, v4),
        )
        val advice = adviseReachability(report, ctx(ddns = setOf(IpFamily.V4, IpFamily.V6), public = listOf(v6, v4)))
        assertTrue(advice.ok)
        assertEquals(setOf(IpFamily.V6), advice.publishOnly)
        assertTrue(advice.texts.last(), "Mejor publicar solo IPv6" in advice.texts.last())
    }

    @Test
    fun `datos moviles sin IPv6 no culpan al router`() {
        val report = ReachabilityReport(
            listOf(FamilyProbe(IpFamily.V6, v6, ProbeOutcome.CannotSend), FamilyProbe(IpFamily.V4, v4, ProbeOutcome.Reached)),
            listOf(v6, v4),
        )
        val advice = adviseReachability(report, ctx(ddns = setOf(IpFamily.V4, IpFamily.V6), public = listOf(v6, v4)))
        assertTrue(advice.ok)
        assertTrue(advice.texts[0], "no tienen IPv6" in advice.texts[0])
        assertNull("no se quita una familia que no se pudo probar", advice.publishOnly)
    }

    @Test
    fun `avisa de una direccion vieja en el DNS`() {
        val report = ReachabilityReport(listOf(FamilyProbe(IpFamily.V6, v6, ProbeOutcome.Reached)), listOf("2001:db8:1::99"))
        val advice = adviseReachability(report, ctx())
        assertTrue(advice.texts.joinToString(), advice.texts.any { "2001:db8:1::99" in it && "ya no es del router" in it })
    }

    @Test
    fun `sin DNS dinamico recuerda configurarlo`() {
        val report = ReachabilityReport(listOf(FamilyProbe(IpFamily.V6, v6, ProbeOutcome.Reached)), null)
        val advice = adviseReachability(report, ctx(ddns = null))
        assertTrue(advice.texts.last(), "Sin DNS dinámico" in advice.texts.last())
    }

    @Test
    fun `detras de otro router sin IPv4 conocida lo explica`() {
        val report = ReachabilityReport(listOf(FamilyProbe(IpFamily.V6, v6, ProbeOutcome.Reached)), null)
        val advice = adviseReachability(report, ctx(ddns = null, privateWan = "192.168.1.2"))
        assertTrue(advice.texts[1], "detrás de otro aparato o de un CGNAT" in advice.texts[1])
    }

    // Lo que pasó en un teléfono real: datos móviles solo IPv4, router detrás del módem y un nombre
    // que solo publica IPv6 (que Android no da en esa red).
    @Test
    fun `sin IPv6 en los datos no se da por perdido el router ni el nombre`() {
        val report = ReachabilityReport(listOf(FamilyProbe(IpFamily.V6, v6, ProbeOutcome.CannotSend)), emptyList())
        val advice = adviseReachability(report, ctx(privateWan = "192.168.1.2"))
        assertFalse(advice.ok)
        assertTrue(advice.untested)
        assertFalse(advice.texts.joinToString(), advice.texts.any { "no da ninguna dirección" in it })
    }

    @Test
    fun `un router que no contesta no es una prueba pendiente`() {
        val report = ReachabilityReport(listOf(FamilyProbe(IpFamily.V6, v6, ProbeOutcome.NoReply)), emptyList())
        val advice = adviseReachability(report, ctx())
        assertFalse(advice.untested)
        assertTrue(advice.texts.joinToString(), advice.texts.any { "no da ninguna dirección" in it })
    }
}

/** Las líneas del consejo, en español. */
private val ReachabilityAdvice.texts: List<String> get() = lines.map { it.es }
