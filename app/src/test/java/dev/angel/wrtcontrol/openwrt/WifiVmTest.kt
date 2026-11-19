package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.data.RouterAddress
import dev.angel.wrtcontrol.ubus.Credentials
import dev.angel.wrtcontrol.ubus.UbusClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * El Wi-Fi contra un router con radios de verdad. La VM de la CI no tiene ninguna, así que allí se
 * salta sola; sirve con un router físico (el GL-MT6000 de pruebas):
 *
 *   set WRTCONTROL_VM=192.168.8.1
 *   set WRTCONTROL_PASSWORD=...
 *   set WRTCONTROL_SSH_OPTS=-i %USERPROFILE%\.ssh\wrtcontrol_ed25519
 *   gradlew testDebugUnitTest --tests "*WifiVmTest*" --rerun
 *
 * Cambiar el país reinicia las radios que emiten: con alguien conectado al Wi-Fi del router se
 * salta. Al acabar deja el país de cada radio como estaba.
 */
class WifiVmTest {

    private val vm: String? = System.getenv("WRTCONTROL_VM")
    private val sshOptions = System.getenv("WRTCONTROL_SSH_OPTS")?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
    private val password: String = System.getenv("WRTCONTROL_PASSWORD").orEmpty()

    private fun ssh(command: String): String {
        val process = ProcessBuilder(listOf("ssh", "-o", "BatchMode=yes") + sshOptions + listOf("root@$vm", command))
            .redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor(60, TimeUnit.SECONDS)
        return out.trim()
    }

    private fun log(msg: String) = println("[${LocalTime.now().withNano(0)}] $msg")

    @Test
    fun `el pais se lee, se cambia y se deja como estaba en un router con Wi-Fi`(): Unit = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra un router", vm != null)
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val api = WifiApi(UbusClient(RouterAddress.endpoint(vm!!), http, Credentials("root", password)))
        // Sin /etc/config/wireless (una VM) ni siquiera se puede leer.
        val before = api.state()
        assumeTrue("El router no tiene Wi-Fi", before.radios.isNotEmpty())
        assumeTrue("Hay aparatos en el Wi-Fi del router: se les cortaría", before.networks.all { it.clients.isEmpty() })

        val countries = api.countries()
        log("${countries.size} países; ahora: ${before.country ?: "sin poner"}; radios: ${before.radios.map { "${it.name} ${it.band} canal ${it.channel} up=${it.up}" }}")
        assertTrue("la lista del router trae los países de verdad", countries.size > 100 && countries.any { it.code == "MX" } && countries.any { it.code == "US" })
        assertTrue("sin el mundo ni códigos que no son países", countries.none { it.code == "00" })

        // Lo que hay en UCI, tal cual (también "00" o vacío), para devolverlo igual.
        val original = before.radios.associate { it.name to ssh("uci -q get wireless.${it.name}.country") }
        val other = if (before.country == "US") "MX" else "US"
        try {
            val t0 = System.currentTimeMillis()
            api.setCountry(other, phoneOnIt = false)
            log("país $other puesto y confirmado en ${System.currentTimeMillis() - t0} ms")
            val after = api.state()
            assertEquals(other, after.country)
            assertEquals("lo que emitía sigue emitiendo", before.radios.filter { it.up }.map { it.name }, after.radios.filter { it.up }.map { it.name })
            before.radios.forEach { assertEquals(other, ssh("uci -q get wireless.${it.name}.country")) }
            // Ponerlo otra vez no hace nada.
            api.setCountry(other, phoneOnIt = false)
        } finally {
            val restore = original.entries.joinToString("; ") { (radio, country) ->
                if (country.isEmpty()) "uci -q delete wireless.$radio.country" else "uci set wireless.$radio.country=$country"
            }
            ssh("$restore; uci commit wireless; wifi reload")
            log("país devuelto: ${original.mapValues { it.value.ifEmpty { "sin poner" } }}")
        }
        original.forEach { (radio, country) -> assertEquals(country, ssh("uci -q get wireless.$radio.country")) }
    }

    @Test
    fun `el ancho se lee, se cambia y se deja como estaba en un router con Wi-Fi`(): Unit = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra un router", vm != null)
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val api = WifiApi(UbusClient(RouterAddress.endpoint(vm!!), http, Credentials("root", password)))
        val before = api.state()
        assumeTrue("El router no tiene Wi-Fi", before.radios.isNotEmpty())
        assumeTrue("Hay aparatos en el Wi-Fi del router: se les cortaría", before.networks.all { it.clients.isEmpty() })
        log("anchos: ${before.radios.map { "${it.name} ${it.htmode} de ${it.htmodes}" }}")
        assertTrue("el router dice qué anchos admite cada radio", before.radios.all { it.htmodes.isNotEmpty() && it.width != null })

        // Una radio que dé a elegir entre lo que ofrece la app, a un ancho que no pise radares.
        val radio = before.radios.firstOrNull { r -> widthChoices(r.band).filter { it != WIDTH_160 }.count { r.offers(it) } > 1 }
        assumeTrue("Ninguna radio da a elegir el ancho", radio != null)
        val other = widthChoices(radio!!.band).first { it != WIDTH_160 && it != radio.width && radio.offers(it) }
        val original = ssh("uci -q get wireless.${radio.name}.htmode")
        try {
            val t0 = System.currentTimeMillis()
            api.setWidth(radio.name, other, phoneOnIt = false)
            log("${radio.name} a $other MHz en ${System.currentTimeMillis() - t0} ms: ${ssh("uci -q get wireless.${radio.name}.htmode")}")
            val after = api.state().radios.single { it.name == radio.name }
            assertEquals(other, after.width)
            assertEquals("se conserva el estándar", radio.htmode!!.takeWhile { it.isLetter() }, after.htmode!!.takeWhile { it.isLetter() })
            assertEquals("sigue levantada", radio.up, after.up)
        } finally {
            ssh("uci set wireless.${radio.name}.htmode=$original; uci commit wireless; wifi reload")
        }
        assertEquals(original, ssh("uci -q get wireless.${radio.name}.htmode"))
    }

    @Test
    fun `el router escanea, se le propone canal y el cambio se deja como estaba`(): Unit = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra un router", vm != null)
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val api = WifiApi(UbusClient(RouterAddress.endpoint(vm!!), http, Credentials("root", password)))
        val before = api.state()
        assumeTrue("El router no tiene Wi-Fi", before.radios.isNotEmpty())
        assumeTrue("Hay aparatos en el Wi-Fi del router: se les cortaría", before.networks.all { it.clients.isEmpty() })

        val advice = before.radios.associate { radio ->
            val t0 = System.currentTimeMillis()
            val found = api.channelAdvice(radio.name)
            log("${radio.name} (${radio.band}, canal ${radio.channel}): $found en ${System.currentTimeMillis() - t0} ms")
            radio to found
        }
        assertTrue("el router escanea y hay consejo para cada banda", advice.values.all { it != null })

        // Se cambia de verdad el canal de 2,4 GHz (sin radares) y se devuelve.
        val radio = before.radios.firstOrNull { it.band == WifiBand.Band2 }
        assumeTrue("No hay radio de 2,4 GHz", radio != null)
        val original = ssh("uci -q get wireless.${radio!!.name}.channel")
        val target = listOf(11, 6, 1).first { it.toString() != original }
        try {
            api.setChannel(radio.name, target, phoneOnIt = false)
            assertEquals(target.toString(), ssh("uci -q get wireless.${radio.name}.channel"))
            assertEquals("sigue levantada", radio.up, api.state().radios.single { it.name == radio.name }.up)
        } finally {
            ssh("uci set wireless.${radio.name}.channel=$original; uci commit wireless; wifi reload")
        }
        assertEquals(original, ssh("uci -q get wireless.${radio.name}.channel"))
    }

    @Test
    fun `la potencia se lee, se baja y se deja como estaba en un router con Wi-Fi`(): Unit = runBlocking {
        assumeTrue("Define WRTCONTROL_VM para probar contra un router", vm != null)
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val api = WifiApi(UbusClient(RouterAddress.endpoint(vm!!), http, Credentials("root", password)))
        val before = api.state()
        assumeTrue("El router no tiene Wi-Fi", before.radios.isNotEmpty())
        assumeTrue("Hay aparatos en el Wi-Fi del router: se les cortaría", before.networks.all { it.clients.isEmpty() })
        log("potencia: ${before.radios.map { "${it.name} txpower=${it.txpower} tope=${it.maxPower}" }}")
        assertTrue("el router dice el tope de cada radio", before.radios.all { it.maxPower != null })

        val radio = before.radios.first()
        val original = ssh("uci -q get wireless.${radio.name}.txpower")
        val other = if (wifiPower(radio.txpower, radio.maxPower) == WifiPower.Medium) WifiPower.Low else WifiPower.Medium
        try {
            api.setPower(radio.name, other, phoneOnIt = false)
            val after = api.state().radios.single { it.name == radio.name }
            log("${radio.name} en $other: txpower=${after.txpower}")
            assertEquals(radio.maxPower!! - other.below, after.txpower)
            assertEquals(other, wifiPower(after.txpower, after.maxPower))
            // "Toda" quita la opción.
            api.setPower(radio.name, WifiPower.Full, phoneOnIt = false)
            assertEquals("", ssh("uci -q get wireless.${radio.name}.txpower"))
        } finally {
            val restore = if (original.isEmpty()) "uci -q delete wireless.${radio.name}.txpower" else "uci set wireless.${radio.name}.txpower=$original"
            ssh("$restore; uci commit wireless; wifi reload")
        }
        assertEquals(original, ssh("uci -q get wireless.${radio.name}.txpower"))
    }
}
