package dev.angel.wrtcontrol.parental

import dev.angel.wrtcontrol.openwrt.Feature
import dev.angel.wrtcontrol.ubus.Credentials
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Qué deja en marcha en el router una configuración del control parental (ver Verified.kt). */
class VerifiedTest {

    private val ana = Profile("a1", "Ana", listOf("9C:29:76:00:00:01"))

    @Test
    fun `sin nada que lo use no queda nada en marcha`() {
        assertEquals(emptySet<Feature>(), ParentalConfig(emptyList()).verifiedFeatures())
        // Este teléfono como administrador no es usar el control parental.
        val onlyAdmin = ParentalConfig(emptyList(), ParentalSettings(admins = listOf("02:00:00:00:00:01")))
        assertEquals(emptySet<Feature>(), onlyAdmin.verifiedFeatures())
    }

    @Test
    fun `un perfil o la cuarentena ponen en marcha el control parental`() {
        assertEquals(setOf(Feature.Parental), ParentalConfig(listOf(ana)).verifiedFeatures())
        assertEquals(setOf(Feature.Parental), ParentalConfig(emptyList(), ParentalSettings(quarantine = true)).verifiedFeatures())
    }

    @Test
    fun `el filtro cuenta solo si algun perfil con aparatos lo tiene`() {
        val filtered = ana.copy(filter = ContentFilter(level = FilterLevel.Adult))
        assertEquals(setOf(Feature.Parental, Feature.ContentFilter), ParentalConfig(listOf(filtered)).verifiedFeatures())
        // Sin aparatos, el filtro no se aplica a nadie.
        assertEquals(setOf(Feature.Parental), ParentalConfig(listOf(filtered.copy(macs = emptyList()))).verifiedFeatures())
    }

    /** Con el router de mentira: lo que se ve en marcha es lo que fw4 tiene cargado. */
    @Test
    fun `en marcha solo con las reglas cargadas`() = runTest {
        val fake = FakeOpenWrt().apply {
            section("cfg01", "defaults", "input" to "REJECT")
            section("cfg03", "system", "hostname" to "OpenWrt", "timezone" to "CST6", "zonename" to "America/Mexico_City")
            section("cfg04", "zone", "name" to "lan", "network" to listOf("lan"))
            section("cfg05", "zone", "name" to "wan", "masq" to "1", "network" to listOf("wan"))
            section("cfg06", "dnsmasq", "domain" to "lan")
            section("cfg07", "forwarding", "src" to "lan", "dest" to "wan")
            section("lan", "dhcp", "interface" to "lan")
        }
        fake.clock = { testScheduler.currentTime }
        val api = ParentalApi(fake.router.client(Credentials("root", ""), io = StandardTestDispatcher(testScheduler)))
        assertEquals(emptySet<Feature>(), api.load().inUse())

        api.updateProfiles { profiles, _ -> profiles + ana }
        val loaded = api.load()
        assertEquals(true, loaded.rulesLoaded)
        assertEquals(setOf(Feature.Parental), loaded.inUse())
        // Si fw4 no tiene las reglas (otro las quitó, o falló al cargar), no se ve funcionando.
        assertEquals(emptySet<Feature>(), loaded.copy(firewall = null).inUse())
    }
}
