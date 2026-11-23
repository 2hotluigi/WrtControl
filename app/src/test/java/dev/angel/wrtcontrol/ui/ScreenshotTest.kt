package dev.angel.wrtcontrol.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.angel.wrtcontrol.WrtApp
import dev.angel.wrtcontrol.data.Loadable
import dev.angel.wrtcontrol.data.StatusData
import dev.angel.wrtcontrol.data.UsageState
import dev.angel.wrtcontrol.openwrt.BoardInfo
import dev.angel.wrtcontrol.openwrt.Capabilities
import dev.angel.wrtcontrol.openwrt.ChannelAdvice
import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.openwrt.Feature
import dev.angel.wrtcontrol.openwrt.GuestWifi
import dev.angel.wrtcontrol.openwrt.IpAddress
import dev.angel.wrtcontrol.openwrt.LeaseState
import dev.angel.wrtcontrol.openwrt.LinkProblem
import dev.angel.wrtcontrol.openwrt.LuciFeatures
import dev.angel.wrtcontrol.openwrt.NetInterface
import dev.angel.wrtcontrol.openwrt.Presence
import dev.angel.wrtcontrol.openwrt.Radio
import dev.angel.wrtcontrol.openwrt.SystemInfo
import dev.angel.wrtcontrol.openwrt.UsageBucket
import dev.angel.wrtcontrol.openwrt.UsageHistory
import dev.angel.wrtcontrol.openwrt.UsagePeriod
import dev.angel.wrtcontrol.openwrt.WanUsage
import dev.angel.wrtcontrol.openwrt.WifiBand
import dev.angel.wrtcontrol.openwrt.WifiEdit
import dev.angel.wrtcontrol.openwrt.WifiNetwork
import dev.angel.wrtcontrol.openwrt.WifiRadio
import dev.angel.wrtcontrol.openwrt.WifiSchedule
import dev.angel.wrtcontrol.openwrt.WifiSecurity
import dev.angel.wrtcontrol.openwrt.WifiState
import dev.angel.wrtcontrol.openwrt.linkAdvice
import dev.angel.wrtcontrol.parental.ApplyPhase
import dev.angel.wrtcontrol.parental.Pause
import dev.angel.wrtcontrol.parental.Profile
import dev.angel.wrtcontrol.parental.Schedule
import dev.angel.wrtcontrol.raw
import dev.angel.wrtcontrol.ubus.SessionAcl
import dev.angel.wrtcontrol.ui.home.AddNetworkCard
import dev.angel.wrtcontrol.ui.home.Attention
import dev.angel.wrtcontrol.ui.home.ChannelSearch
import dev.angel.wrtcontrol.ui.home.ChannelSection
import dev.angel.wrtcontrol.ui.home.ClientsSection
import dev.angel.wrtcontrol.ui.home.CountrySection
import dev.angel.wrtcontrol.ui.home.DeviceChange
import dev.angel.wrtcontrol.ui.home.DevicePauses
import dev.angel.wrtcontrol.ui.home.DevicesContent
import dev.angel.wrtcontrol.ui.home.GuestContent
import dev.angel.wrtcontrol.ui.home.HomeWifi
import dev.angel.wrtcontrol.ui.home.Overview
import dev.angel.wrtcontrol.ui.home.OverviewContent
import dev.angel.wrtcontrol.ui.home.PairScreen
import dev.angel.wrtcontrol.ui.home.PowerSection
import dev.angel.wrtcontrol.ui.home.ReportCard
import dev.angel.wrtcontrol.ui.home.ReportUi
import dev.angel.wrtcontrol.ui.home.ScheduleSection
import dev.angel.wrtcontrol.ui.home.StatusContent
import dev.angel.wrtcontrol.ui.home.UsageContent
import dev.angel.wrtcontrol.ui.home.WidthSection
import dev.angel.wrtcontrol.ui.home.WifiContent
import dev.angel.wrtcontrol.ui.home.blockPreview
import dev.angel.wrtcontrol.ui.home.devicePreview
import dev.angel.wrtcontrol.ui.login.CountryStepState
import dev.angel.wrtcontrol.ui.login.SetupContent
import dev.angel.wrtcontrol.ui.login.SetupStep
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Capturas de los componentes y de una pantalla con datos de ejemplo, en claro y en oscuro. Se
 * comparan con las guardadas en `app/src/test/screenshots`: si el aspecto cambia sin querer, falla.
 *
 * - Comparar (lo que hace la CI): `gradlew verifyRoborazziDebug`
 * - Guardar las nuevas tras un cambio de aspecto buscado: `gradlew recordRoborazziDebug`
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "es-w360dp-h800dp-xhdpi")
class ScreenshotTest {

    private fun capture(name: String, dark: Boolean, content: @Composable () -> Unit) {
        captureRoboImage("src/test/screenshots/$name-${if (dark) "oscuro" else "claro"}.png", roborazziOptions = options) {
            WrtTheme(dark = dark) { Surface(color = MaterialTheme.colorScheme.background) { content() } }
        }
    }

    /** Windows y Linux no suavizan las letras exactamente igual: se tolera hasta un 1 % de la imagen. */
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f))

    private fun both(name: String, content: @Composable () -> Unit) {
        capture(name, dark = false, content)
        capture(name, dark = true, content)
    }

    @Test
    fun componentes() = both("componentes") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Internet funciona", style = MaterialTheme.typography.headlineSmall)
            Text("Sin cortes desde hace 6 h", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            InfoCard("Sistema", trailing = { StatusPill("activa", true) }) {
                InfoRow("Modelo", "Router de ejemplo")
                InfoRow("Encendido", "6 h 26 min")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill("disponible", true)
                StatusPill("se instalará", null)
                StatusPill("no compatible", false)
            }
            NoticeCard("2 dispositivos nuevos", "Esperan tu aprobación para tener internet.")
            ErrorBanner("El router no responde. Comprueba que estás en la red de casa.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {}) { Text("Pausar a…") }
                OutlinedButton(onClick = {}) { Text("Wi-Fi de invitados") }
            }
        }
    }

    /** Un cambio en curso, como se ve en cualquier pantalla: sin fases, en cada paso y deshaciéndose. */
    @Test
    fun aplicando() = both("aplicando") {
        // Como en una vista previa: la barra de progreso se dibuja quieta (animada, la captura no acabaría).
        CompositionLocalProvider(LocalInspectionMode provides true) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ApplyingBanner(ChangeProgress(raw("Instalando WireGuard…")))
                ApplyingBanner(ChangeProgress(raw("Guardando «Tele de la sala»…"), ApplyPhase.Applying))
                ApplyingBanner(ChangeProgress(raw("Pausando a Ana…"), ApplyPhase.Verifying))
                ApplyingBanner(ChangeProgress(raw("Activando la cuarentena…"), ApplyPhase.Confirming))
                ApplyingBanner(ChangeProgress(raw("Activando UPnP…"), ApplyPhase.Reverting))
            }
        }
    }

    /** Lo que se enseña antes de un cambio que tiene algo que avisar: guardar un aparato, bloquearlo y la contraseña. */
    @Test
    fun `vista previa`() = both("vista-previa") {
        val ip = "192.168.1.42"
        val tablet = Device("9C:29:76:00:00:01", "tablet", listOf(ip), emptyList(), LeaseState.Expires(3600), label = "Tableta")
        val change = DeviceChange(label = "Tableta de Ana", fixedIp = ip, owner = "a1")
        val saving = devicePreview(raw("Tableta"), tablet, change, listOf(Profile("a1", "Ana", emptyList())))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChangePreviewBody(saving)
            ChangePreviewBody(blockPreview(raw("Tele de la sala")))
            ChangePreviewBody(passwordPreview())
        }
    }

    /** El Wi-Fi como lo deja la app: las dos bandas con el mismo nombre, y cuántos hay en cada una. */
    @Test
    fun wifi() = both("wifi") {
        val radios = listOf(
            WifiRadio("radio0", WifiBand.Band2, enabled = true, up = true, htmode = "HE20", htmodes = listOf("HE20", "HE40")),
            WifiRadio("radio1", WifiBand.Band5, enabled = true, up = true, htmode = "HE80", htmodes = listOf("HE80", "HE160")),
        )
        fun network(radio: String, ifname: String, clients: Int) = WifiNetwork(
            section = "default_$radio", radio = radio, ssid = "Casa", encryption = "sae-mixed", key = "clave-de-casa",
            enabled = true, networks = listOf("lan"), ifname = ifname, clients = List(clients) { "AA:00:00:00:00:%02X".format(it) },
        )
        // Sin país, como viene de fábrica: el aviso propone el del teléfono.
        WifiContent(WifiState(radios, listOf(network("radio0", "phy0-ap0", 3), network("radio1", "phy1-ap0", 9))), suggestedCountry = "MX")
    }

    /** El Wi-Fi de invitados encendido: nombre, contraseña a la vista y el QR para la visita. */
    @Test
    fun invitados() = both("invitados") {
        val radios = listOf(WifiRadio("radio0", WifiBand.Band2, enabled = true, up = true))
        val guest = WifiNetwork(
            section = "wrtcguest_radio0", radio = "radio0", ssid = "Casa invitados", encryption = "sae-mixed", key = "hk7m2pqx4n",
            enabled = true, networks = listOf("wrtcguest"), ifname = "phy0-ap1", clients = listOf("AA:00:00:00:00:01", "AA:00:00:00:00:02"),
        )
        GuestContent(WifiState(radios, listOf(guest)))
    }

    /** Un router sin radios (un PC con OpenWrt, el router de prueba): ni interruptor ni campos. */
    @Test
    fun invitadosSinWifi() = both("invitados-sin-wifi") {
        GuestContent(WifiState(emptyList(), emptyList()))
    }

    /** Quién hay conectado, con su señal (el de peor señal, primero), y la potencia de una banda. */
    @Test
    fun wifiPotencia() = both("wifi-potencia") {
        val radios = listOf(WifiRadio("radio1", WifiBand.Band5, enabled = true, up = true, txpower = 14, maxPower = 20))
        val network = WifiNetwork(
            section = "default_radio1", radio = "radio1", ssid = "Casa", encryption = "sae-mixed", key = "clave-de-casa", enabled = true,
            networks = listOf("lan"), ifname = "phy1-ap0", clients = listOf("AA:00:00:00:00:01", "AA:00:00:00:00:02", "AA:00:00:00:00:03"),
            signals = mapOf("AA:00:00:00:00:01" to -48, "AA:00:00:00:00:02" to -81, "AA:00:00:00:00:03" to -66),
        )
        val names = mapOf("AA:00:00:00:00:01" to "Tele de la sala", "AA:00:00:00:00:02" to "Portátil de Ana", "AA:00:00:00:00:03" to "Aparato Samsung")
        val state = WifiState(radios, listOf(network))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ClientsSection(state, names)
            PowerSection(state, enabled = true, onPick = { _, _ -> })
        }
    }

    /** El horario del Wi-Fi a medianoche de un lunes: apagado por la franja de noche, con "Encender ahora". */
    @Test
    fun wifiHorario() = both("wifi-horario") {
        val radios = listOf(WifiRadio("radio0", WifiBand.Band2, enabled = true, up = false))
        val nights = setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        val schedules = listOf(
            Schedule("0", nights, LocalTime.of(23, 0), LocalTime.of(7, 0)),
            Schedule("1", setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), LocalTime.of(14, 0), LocalTime.of(16, 0)),
        )
        val schedule = WifiSchedule(schedules, LocalDateTime.of(2026, 11, 16, 23, 30))
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScheduleSection(WifiState(radios, emptyList()), schedule, unavailable = false, enabled = true, onChange = {}, onTurnOn = {})
        }
    }

    /** Tras cambiar el nombre del Wi-Fi: guardarlo en este teléfono con un toque. */
    @Test
    fun wifiGuardarRed() = both("wifi-guardar-red") {
        Column(Modifier.padding(16.dp)) {
            AddNetworkCard(WifiEdit("Casa nueva", "clave-de-casa", WifiSecurity.Wpa2Wpa3, setOf("radio0")), onDone = {})
        }
    }

    /** El canal: una banda con un canal más libre que proponer y otra que ya está bien. */
    @Test
    fun wifiCanal() = both("wifi-canal") {
        val radios = listOf(
            WifiRadio("radio0", WifiBand.Band2, enabled = true, up = true, channel = "1"),
            WifiRadio("radio1", WifiBand.Band5, enabled = true, up = true, channel = "36"),
        )
        val searches = mapOf(
            "radio0" to ChannelSearch.Found(ChannelAdvice(best = 11, bestNeighbors = 0, currentNeighbors = 11, better = true)),
            "radio1" to ChannelSearch.Found(ChannelAdvice(best = 36, bestNeighbors = 8, currentNeighbors = 8, better = false)),
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChannelSection(WifiState(radios, emptyList()), searches, enabled = true, onFind = {}, onUse = { _, _ -> })
        }
    }

    /** Lo que queda bajo el pliegue en Wi-Fi: el país ya puesto y "Velocidad y alcance" de cada banda. */
    @Test
    fun wifiAjustes() = both("wifi-ajustes") {
        val radios = listOf(
            WifiRadio("radio0", WifiBand.Band2, enabled = true, up = true, country = "MX", htmode = "HE20", htmodes = listOf("HE20", "HE40")),
            WifiRadio("radio1", WifiBand.Band5, enabled = true, up = true, country = "MX", htmode = "HE80", htmodes = listOf("HE80", "HE160")),
        )
        val state = WifiState(radios, emptyList())
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CountrySection(state, countries = emptyList(), suggested = null, enabled = true, onPick = {})
            WidthSection(state, enabled = true, onPick = { _, _ -> })
        }
    }

    @Test
    fun inicio() = both("inicio") {
        OverviewContent(
            Overview(
                online = true,
                onlineSeconds = 23_160,
                devices = 16,
                profiles = 3,
                paused = 1,
                newDevices = 2,
                cableMbps = 100,
                linkAdvice = linkAdvice(listOf(LinkProblem.Flapping(13, 10), LinkProblem.Slow(100, 1000))),
                attention = listOf(Attention.Cable, Attention.NewDevices),
            ),
            wifi = HomeWifi.On("Casa", 12),
            guest = GuestWifi("Casa invitados", "hk7m2pqx4n", on = true, clients = listOf("AA:00:00:00:00:01")),
        )
    }

    @Test
    fun `inicio sin internet`() = both("inicio-sin-internet") {
        OverviewContent(Overview(online = false, onlineSeconds = null, devices = 16, profiles = 0, paused = 0, newDevices = 0, attention = emptyList()))
    }

    @Test
    fun dispositivos() = both("dispositivos") {
        // La de la app de verdad, leída de sus assets como en el teléfono: si el empaquetado la
        // cambia de nombre o no la incluye, aquí se nota.
        val vendors = (RuntimeEnvironment.getApplication() as WrtApp).vendors
        assertEquals("Raspberry Pi Foundation", vendors.vendor("B8:27:EB:00:00:02"))
        fun device(mac: String, name: String?, ip: String, lease: LeaseState = LeaseState.Expires(40_000)) =
            Device(mac, name, listOf(ip), emptyList(), lease)
        DevicesContent(
            listOf(
                // De una persona y en línea; con nombre puesto y dirección fija; sin actividad; desconectado.
                device("9C:29:76:00:00:01", "moto-g-de-Ana", "192.168.1.20").copy(presence = Presence.Online),
                device("B8:27:EB:00:00:02", "Servidor de casa", "192.168.1.21", LeaseState.Permanent)
                    .copy(label = "Servidor de casa", fixedIp = "192.168.1.21", presence = Presence.Online),
                device("24:6F:28:00:00:03", null, "192.168.1.22").copy(presence = Presence.Idle),
                device("A6:11:22:00:00:04", null, "192.168.1.23").copy(presence = Presence.Offline),
                device("00:99:99:00:00:05", null, "192.168.1.24", LeaseState.None),
            ),
            vendors,
            profiles = listOf(Profile("a1", "Ana", listOf("9C:29:76:00:00:01"))),
            // Uno sin internet un rato y otro bloqueado.
            pauses = DevicePauses(
                mapOf("9C:29:76:00:00:01" to Pause.Until(LocalDateTime.of(2026, 11, 5, 19, 30)), "24:6F:28:00:00:03" to Pause.Indefinite),
                LocalDateTime.of(2026, 11, 5, 18, 0),
            ),
            onOpen = {},
        )
    }

    /** Los pasos de la puesta en marcha, con un router sin contraseña ni país del Wi-Fi (el caso que más enseña). */
    @Test
    fun `puesta en marcha`() {
        val radios = listOf(WifiRadio("radio0", WifiBand.Band2, enabled = true, up = true), WifiRadio("radio1", WifiBand.Band5, enabled = true, up = true))
        val country = CountryStepState(WifiState(radios, emptyList()), countries = emptyList(), suggested = "MX")
        SetupStep.entries.forEach { step ->
            both("puesta-en-marcha-${step.ordinal + 1}") {
                SetupContent(step, emptyPassword = true, board = status.board, capabilities = capabilities, country = country)
            }
        }
    }

    /** El código para añadir otro teléfono: blanco y negro también en oscuro, para que lo lea cualquier cámara. */
    @Test
    fun `anadir otro telefono`() = both("anadir-telefono") {
        PairScreen("192.168.8.1", "root")
    }

    /** La tarjeta para compartir el informe del router, al final de Más → Router. */
    @Test
    fun `informe del router`() = both("informe-router") {
        ReportCard(ReportUi(onShare = {}))
    }

    /** El consumo de las últimas 24 horas (con más por la noche, la tele) y lo contado desde el arranque. */
    @Test
    fun `uso de internet`() = both("uso-de-internet") {
        UsageContent(Loadable(UsageState(sinceBoot, usageHistory)), UsagePeriod.Day, onPeriod = {})
    }

    /** Los últimos 30 días. */
    @Test
    fun `uso de internet en un mes`() = both("uso-de-internet-mes") {
        UsageContent(Loadable(UsageState(sinceBoot, usageHistory)), UsagePeriod.Month, onPeriod = {})
    }

    /** Sin historial guardado en el router: solo lo que cuenta desde que se encendió. */
    @Test
    fun `uso de internet sin historial`() = both("uso-de-internet-sin-historial") {
        UsageContent(Loadable(UsageState(sinceBoot)), UsagePeriod.Day, onPeriod = {})
    }

    private val sinceBoot = WanUsage("wan", receivedBytes = 183L * GIB, sentBytes = 21L * GIB, sinceSeconds = 9 * DAY_SECONDS + 4 * 3600)

    private val usageHistory: UsageHistory = run {
        val now = LocalDateTime.of(2026, 11, 23, 14, 0)
        // Poco de madrugada, algo de día y lo más por la noche.
        val evening = listOf(2, 1, 1, 1, 1, 1, 3, 6, 5, 4, 4, 5, 6, 5, 4, 5, 7, 10, 14, 18, 20, 17, 11, 5)
        val hours = (0 until 24).map { i ->
            val start = now.minusHours(23L - i)
            val received = evening[start.hour] * 90L * MIB
            UsageBucket(start, received, received / 9)
        }
        val days = (0 until 30).map { i ->
            val start = now.toLocalDate().atStartOfDay().minusDays(29L - i)
            val weekend = start.dayOfWeek == DayOfWeek.SATURDAY || start.dayOfWeek == DayOfWeek.SUNDAY
            val received = ((if (weekend) 26L else 17L) + (i * 7 % 5)) * GIB
            UsageBucket(start, received, received / 8)
        }
        UsageHistory(hours, days)
    }

    /** Con funciones que ya funcionan en este router y otras sin probar aquí. */
    @Test
    fun estado() = both("estado") {
        StatusContent(Loadable(status), capabilities, verified = setOf(Feature.Parental))
    }

    private val status = StatusData(
        BoardInfo(
            hostname = "Casa",
            model = "Router de ejemplo",
            kernel = "6.12.0",
            release = BoardInfo.Release(version = "25.12.0", description = "OpenWrt 25.12.0"),
        ),
        SystemInfo(
            uptime = 23_160,
            load = listOf(6554, 3277, 1311),
            memory = SystemInfo.Memory(total = 512L * 1024 * 1024, available = 380L * 1024 * 1024),
            root = SystemInfo.Disk(total = 100_000, used = 18_000, avail = 82_000),
        ),
        listOf(NetInterface("lan", up = true, proto = "static", device = "br-lan", ipv4 = listOf(IpAddress("192.168.1.1", 24)), uptime = 23_100)),
    )

    // Un router al que le faltan cosas, para que salgan los tres estados: disponible, se instalará y no compatible.
    private val capabilities = Capabilities(
        version = "25.12.0",
        target = "x86/64",
        memoryBytes = 512L * 1024 * 1024,
        freeStorageKiB = 82_000,
        objects = setOf("uci"),
        acl = SessionAcl(
            uci = mapOf("firewall" to setOf("write"), "dhcp" to setOf("write"), "ddns" to setOf("write")),
            file = mapOf("/etc/luci-uploads/*" to setOf("write"), "/usr/libexec/package-manager-call install *" to setOf("exec")),
        ),
        features = LuciFeatures(firewall4 = true, wifi = true, dnsmasq = null),
        radios = listOf(
            Radio("radio0", WifiBand.Band2, setOf("n", "ax"), enabled = true, networks = 1),
            Radio("radio1", WifiBand.Band5, setOf("ac", "ax"), enabled = true, networks = 1),
        ),
    )

    private companion object {
        const val MIB = 1024L * 1024
        const val GIB = 1024L * MIB
        const val DAY_SECONDS = 86_400L
    }
}
