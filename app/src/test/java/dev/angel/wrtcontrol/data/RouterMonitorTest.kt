package dev.angel.wrtcontrol.data

import dev.angel.wrtcontrol.openwrt.OpenWrtApi
import dev.angel.wrtcontrol.raw
import dev.angel.wrtcontrol.ubus.Credentials
import dev.angel.wrtcontrol.ubus.FakeRouter
import dev.angel.wrtcontrol.ubus.Reply
import dev.angel.wrtcontrol.ubus.ok
import dev.angel.wrtcontrol.ubus.status
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RouterMonitorTest {

    // Lo que contesta el router de pega; las pruebas lo cambian para simular otro router o un fallo.
    private var lanMac = "AA:BB:CC:00:00:01"
    private var wanAddress = "203.0.113.7"
    private var deviceStatusFails = false
    private var hints = """{"11:22:33:44:55:66":{"name":"tele","ipaddrs":["192.168.1.20"]}}"""

    /** Lo que contesta el nslookup del router (null: el router no deja ejecutar nada). */
    private var nslookup: String? = null

    /** La tabla de vecinos (null: el router no deja verla). */
    private var neighbors: String? = null

    private val router = FakeRouter { call ->
        when (call.name) {
            "session.login" -> ok("""{"ubus_rpc_session":"s1","timeout":300,"expires":300}""")
            "system.board" -> ok("""{"hostname":"casa","model":"GL-MT6000"}""")
            "system.info" -> ok("""{"uptime":42}""")
            "network.interface.dump" -> ok(
                """{"interface":[
                    {"interface":"loopback","up":true},
                    {"interface":"lan","up":true,"device":"br-lan","ipv4-address":[{"address":"192.168.1.1","mask":24}]},
                    {"interface":"wan","up":true,"device":"eth1","ipv4-address":[{"address":"$wanAddress","mask":24}],
                     "route":[{"target":"0.0.0.0","mask":0}]}]}""",
            )
            "network.device.status" -> if (deviceStatusFails) Reply.NetworkError else ok("""{"macaddr":"$lanMac"}""")
            "luci-rpc.getHostHints" -> ok(hints)
            "file.exec" -> {
                val command = call.args["command"].toString()
                val out = if ("nslookup" in command) nslookup else neighbors.takeIf { "/sbin/ip" in command }
                if (out == null) status(4) else ok(buildJsonObject { put("code", 0); put("stdout", out) }.toString())
            }
            "luci-rpc.getDHCPLeases" -> ok("{}")
            else -> status(4)
        }
    }

    private fun count(name: String) = synchronized(router.calls) { router.calls.count { it.name == name } }

    private fun TestScope.monitor(): RouterMonitor {
        val api = OpenWrtApi(router.client(Credentials("root", "x"), io = StandardTestDispatcher(testScheduler)))
        val access = object : RouterAccess {
            override suspend fun <T> use(block: suspend (OpenWrtApi) -> T): T = block(api)
        }
        return RouterMonitor(backgroundScope, access, { raw(it.message.orEmpty()) }, { testScheduler.currentTime })
    }

    private fun TestScope.watching(feed: Feed<*>, every: Duration) = backgroundScope.launch { feed.watch(every).collect {} }

    @Test
    fun `Estado y Dispositivos a la vez - las redes se piden una vez por vuelta y el modelo una sola`() = runTest {
        val monitor = monitor()
        watching(monitor.status, 5.seconds)
        watching(monitor.devices, 10.seconds)
        advanceTimeBy(10.5.seconds)

        // Estado a los 0, 5 y 10 s; Dispositivos a los 0 y 10 s, con las redes que ya leyó Estado.
        assertEquals(3, count("network.interface.dump"))
        assertEquals(3, count("system.info"))
        assertEquals(1, count("system.board"))
        assertEquals(2, count("luci-rpc.getHostHints"))
        assertEquals("casa", monitor.status.state.value.data?.board?.hostname)
        assertEquals(listOf("lan", "wan"), monitor.status.state.value.data?.interfaces?.map { it.name })
        assertEquals("tele", monitor.devices.state.value.data?.single()?.name)
    }

    @Test
    fun `a un aparato sin nombre se le pregunta aparte y la lista lo trae en cuanto contesta`() = runTest {
        hints = """{"11:22:33:44:55:77":{"ipaddrs":["192.168.1.30"]}}"""
        nslookup = "30.1.168.192.in-addr.arpa\tname = impresora.local\n"
        neighbors = "192.168.1.30 dev br-lan lladdr 11:22:33:44:55:77 REACHABLE\n"
        val monitor = monitor()
        watching(monitor.devices, 10.seconds)
        runCurrent()

        assertEquals("impresora", monitor.devices.state.value.data?.single()?.name)
        // La lista se leyó dos veces (sin nombre y, al contestar, con él) y se preguntó una sola.
        assertEquals(2, count("luci-rpc.getHostHints"))
        advanceTimeBy(30.seconds)
        assertEquals(1, synchronized(router.calls) { router.calls.count { "nslookup" in it.args.toString() } })

        // Otro router: lo aprendido del anterior no vale.
        nslookup = null
        monitor.reset()
        runCurrent()
        assertEquals(null, monitor.devices.state.value.data?.single()?.name)
    }

    @Test
    fun `una pantalla que necesita los equipos encuentra los que ya miraba otra`() = runTest {
        val monitor = monitor()
        watching(monitor.devices, 10.seconds)
        runCurrent()
        assertEquals("tele", monitor.devices.fresh(5.seconds).single().name)
        assertEquals(1, count("luci-rpc.getHostHints"))
    }

    @Test
    fun `saber que router es aprovecha lo ya leido y se averigua una vez por sesion`() = runTest {
        val monitor = monitor()
        monitor.status.fresh()
        val before = router.calls.size

        assertEquals("GL-MT6000|AA:BB:CC:00:00:01", monitor.identity.fresh(Duration.INFINITE))
        assertEquals(listOf("network.device.status"), router.calls.drop(before).map { it.name })
        advanceTimeBy(600.seconds)
        monitor.identity.fresh(Duration.INFINITE)
        assertEquals(before + 1, router.calls.size)

        // Otra sesión puede ser otro router en la misma dirección.
        lanMac = "AA:BB:CC:00:00:02"
        monitor.reset()
        assertEquals("GL-MT6000|AA:BB:CC:00:00:02", monitor.identity.fresh(Duration.INFINITE))
    }

    @Test
    fun `reset olvida todo lo leido`() = runTest {
        val monitor = monitor()
        monitor.status.fresh()
        monitor.devices.fresh()
        monitor.reset()
        assertTrue(listOf(monitor.board, monitor.system, monitor.interfaces, monitor.status, monitor.devices).all { it.state.value.isLoading })
    }

    // ---------- lo que se apunta a partir de lo leído ----------

    private fun TestScope.follow(monitor: RouterMonitor): MutableList<String> {
        val log = mutableListOf<String>()
        backgroundScope.followRouter(
            monitor,
            onIdentified = { log += "router $it" },
            onAddresses = { endpoints, hostname, known -> log += "direcciones $endpoints $hostname $known" },
        )
        return log
    }

    @Test
    fun `con la primera lectura se apunta que router es y despues por donde se llega a el`() = runTest {
        val monitor = monitor()
        val log = follow(monitor)
        runCurrent()
        assertEquals("apuntar no hace leer", 0, router.calls.size)

        // Vale cualquier pantalla que lea las redes; aquí, la de dispositivos.
        watching(monitor.devices, 10.seconds)
        runCurrent()
        assertEquals(listOf("router GL-MT6000|AA:BB:CC:00:00:01", "direcciones [203.0.113.7] null false"), log)

        // Mientras las redes no cambien no hay nada nuevo que apuntar.
        advanceTimeBy(30.seconds)
        assertEquals(2, log.size)
        assertEquals(1, count("network.device.status"))
    }

    @Test
    fun `cuando cambia la direccion publica se apunta la nueva`() = runTest {
        val monitor = monitor()
        val log = follow(monitor)
        watching(monitor.devices, 10.seconds)
        runCurrent()
        wanAddress = "198.51.100.9"
        advanceTimeBy(10.5.seconds)
        assertEquals("direcciones [198.51.100.9] null false", log.last())
        assertEquals(1, count("network.device.status"))
    }

    @Test
    fun `otra sesion - antes de apuntar direcciones se averigua de que router son`() = runTest {
        val monitor = monitor()
        val log = follow(monitor)
        watching(monitor.devices, 10.seconds)
        runCurrent()
        log.clear()

        lanMac = "AA:BB:CC:00:00:02"
        wanAddress = "198.51.100.9"
        monitor.reset()
        runCurrent()
        assertEquals(listOf("router GL-MT6000|AA:BB:CC:00:00:02", "direcciones [198.51.100.9] null false"), log)
    }

    @Test
    fun `si no se llega a saber que router es no se apuntan direcciones - se reintenta`() = runTest {
        val monitor = monitor()
        val log = follow(monitor)
        deviceStatusFails = true
        watching(monitor.devices, 10.seconds)
        runCurrent()
        assertEquals(emptyList<String>(), log)

        deviceStatusFails = false
        wanAddress = "198.51.100.9"
        advanceTimeBy(10.5.seconds)
        assertEquals(listOf("router GL-MT6000|AA:BB:CC:00:00:01", "direcciones [198.51.100.9] null false"), log)
    }
}
