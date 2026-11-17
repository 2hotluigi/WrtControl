package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.ubus.Credentials
import dev.angel.wrtcontrol.ubus.FakeRouter
import dev.angel.wrtcontrol.ubus.ok
import dev.angel.wrtcontrol.ubus.status
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nombres por mDNS, con lo que contestó de verdad el nslookup de BusyBox 1.37 en el GL-MT6000. */
class MdnsTest {

    private val castId = "fc98ff4eb9b4f8c18e8da22714f57dc8"
    private val head = "Server:\t\t192.168.8.235\nAddress:\t192.168.8.235:5353\n\n"

    @Test
    fun `el nombre de equipo sale del PTR de su direccion`() {
        val answer = parseMdnsAnswer("Server:\t\t192.168.8.224\n\n224.8.168.192.in-addr.arpa\tname = twohotraspberry.local\n")
        assertEquals(listOf("twohotraspberry.local"), answer.names)
        assertEquals("twohotraspberry", mdnsHostName(answer.names.single()))
    }

    @Test
    fun `un nombre de equipo que es solo un identificador no vale`() {
        assertNull(mdnsHostName("fc98ff4e-b9b4-f8c1-8e8d-a22714f57dc8.local"))
        assertNull(mdnsHostName(".local"))
    }

    @Test
    fun `un aparato sin mDNS tiene el puerto cerrado`() {
        val answer = parseMdnsAnswer("nslookup: read: Connection refused\n")
        assertTrue(answer.refused)
        assertTrue(answer.names.isEmpty())
    }

    @Test
    fun `de los servicios que anuncia se elige el que suele llevar nombre`() {
        assertEquals("_googlecast._tcp", namedService(listOf("_amzn-wplay._tcp.local", "_googlecast._tcp.local")))
        assertEquals("_device-info._tcp", namedService(listOf("_workstation._tcp.local", "_smb._tcp.local", "_device-info._tcp.local")))
        assertNull(namedService(listOf("_amzn-wplay._tcp.local")))
    }

    @Test
    fun `el nombre de cada servicio se limpia a su manera`() {
        assertEquals("OTT Xview+ AV1", mdnsServiceName("OTT-Xview+-AV1-$castId._googlecast._tcp.local", "_googlecast._tcp"))
        assertEquals("Sala", mdnsServiceName("AABBCCDDEEFF@Sala._raop._tcp.local", "_raop._tcp"))
        assertEquals("pi", mdnsServiceName("pi [dc:a6:32:7f:15:4e]._workstation._tcp.local", "_workstation._tcp"))
        assertEquals("Tele de la sala", mdnsServiceName("Tele\\032de\\032la\\032sala._airplay._tcp.local", "_airplay._tcp"))
        assertNull(mdnsServiceName("_airplay._tcp.local", "_airplay._tcp"))
    }

    @Test
    fun `lo que diga el aparato no pasa de ser un nombre corto y sin caracteres raros`() {
        val hostile = "A".repeat(200) + "\u0007._airplay._tcp.local"
        assertEquals(MAX_LABEL_LENGTH, mdnsServiceName(hostile, "_airplay._tcp")?.length)
    }

    /** Un router de pega cuyo nslookup contesta [answers] según lo que se pregunte. */
    private fun TestScope.router(asked: MutableList<String>, answers: (String) -> String?) = FakeRouter { call ->
        when (call.name) {
            "session.login" -> ok("""{"ubus_rpc_session":"s1","timeout":300,"expires":300}""")
            "file.exec" -> {
                val params = call.args["params"]!!.jsonArray.map { it.jsonPrimitive.content }
                asked += params[params.size - 2]
                ok(buildJsonObject { put("code", 0); put("stdout", JsonPrimitive(answers(params[params.size - 2]).orEmpty())) }.toString())
            }
            else -> status(4)
        }
    }.client(Credentials("root", "x"), io = StandardTestDispatcher(testScheduler))

    @Test
    fun `con nombre de equipo no hace falta preguntar mas`() = runTest {
        val asked = mutableListOf<String>()
        val client = router(asked) { "224.8.168.192.in-addr.arpa\tname = twohotraspberry.local\n" }

        assertEquals("twohotraspberry", client.mdnsName("192.168.8.224"))
        assertEquals(listOf("224.8.168.192.in-addr.arpa"), asked)
    }

    @Test
    fun `sin nombre de equipo se le pregunta por sus servicios`() = runTest {
        val asked = mutableListOf<String>()
        val client = router(asked) { question ->
            when (question) {
                "_services._dns-sd._udp.local" -> head + "_services._dns-sd._udp.local\tname = _googlecast._tcp.local\n"
                "_googlecast._tcp.local" -> head + "_googlecast._tcp.local\tname = OTT-Xview+-AV1-$castId._googlecast._tcp.local\n"
                else -> null
            }
        }

        assertEquals("OTT Xview+ AV1", client.mdnsName("192.168.8.235"))
        assertEquals(listOf("235.8.168.192.in-addr.arpa", "_services._dns-sd._udp.local", "_googlecast._tcp.local"), asked)
    }

    @Test
    fun `con el puerto cerrado no se insiste, y lo que no es una direccion ni se pregunta`() = runTest {
        val asked = mutableListOf<String>()
        val client = router(asked) { "nslookup: read: Connection refused\n" }

        assertNull(client.mdnsName("192.168.8.178"))
        assertEquals(1, asked.size)
        assertNull(client.mdnsName("192.168.8.1; reboot"))
        assertNull(client.mdnsName("fe80::1"))
        assertEquals(1, asked.size)
    }

    @Test
    fun `si el router no deja usar nslookup, no hay nombre y no falla`() = runTest {
        val client = FakeRouter { call ->
            if (call.name == "session.login") ok("""{"ubus_rpc_session":"s1","timeout":300,"expires":300}""") else status(6)
        }.client(Credentials("root", "x"), io = StandardTestDispatcher(testScheduler))

        assertNull(client.mdnsName("192.168.8.235"))
    }
}
