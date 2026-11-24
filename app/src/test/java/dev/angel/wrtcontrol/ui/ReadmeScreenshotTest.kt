package dev.angel.wrtcontrol.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.angel.wrtcontrol.WrtApp
import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.openwrt.GuestWifi
import dev.angel.wrtcontrol.openwrt.LeaseState
import dev.angel.wrtcontrol.openwrt.Presence
import dev.angel.wrtcontrol.openwrt.WifiBand
import dev.angel.wrtcontrol.openwrt.WifiNetwork
import dev.angel.wrtcontrol.openwrt.WifiRadio
import dev.angel.wrtcontrol.openwrt.WifiState
import dev.angel.wrtcontrol.parental.ApplyPhase
import dev.angel.wrtcontrol.parental.ContentFilter
import dev.angel.wrtcontrol.parental.FilterLevel
import dev.angel.wrtcontrol.parental.ParentalSnapshot
import dev.angel.wrtcontrol.parental.Pause
import dev.angel.wrtcontrol.parental.Profile
import dev.angel.wrtcontrol.parental.Schedule
import dev.angel.wrtcontrol.raw
import dev.angel.wrtcontrol.ui.design.CardGap
import dev.angel.wrtcontrol.ui.home.AppFrame
import dev.angel.wrtcontrol.ui.home.DeviceChange
import dev.angel.wrtcontrol.ui.home.DevicePauses
import dev.angel.wrtcontrol.ui.home.DevicesContent
import dev.angel.wrtcontrol.ui.home.GuestContent
import dev.angel.wrtcontrol.ui.home.HomeWifi
import dev.angel.wrtcontrol.ui.home.Overview
import dev.angel.wrtcontrol.ui.home.OverviewContent
import dev.angel.wrtcontrol.ui.home.Section
import dev.angel.wrtcontrol.ui.home.WifiContent
import dev.angel.wrtcontrol.ui.home.devicePreview
import dev.angel.wrtcontrol.ui.parental.ParentalData
import dev.angel.wrtcontrol.ui.parental.ProfileCard
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
 * Las capturas del README (`docs/screenshots/<idioma>/`): pantallas completas, con la barra de
 * arriba y las pestañas, y datos de ejemplo (ninguno de un router de verdad). Se comparan como las
 * de [ScreenshotTest]: si el aspecto de la app cambia, el README se queda al día con
 * `gradlew recordRoborazziDebug`.
 */
abstract class ReadmeScreenshots(private val lang: String) {

    /** Los textos de ejemplo, en el idioma de la captura. */
    private fun t(en: String, es: String) = if (lang == "en") en else es

    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f))

    /** Una pantalla completa con el marco de la app: [section] es la pestaña; con [title], una página interior. */
    private fun screen(name: String, section: Section, title: String? = null, content: @Composable () -> Unit) {
        captureRoboImage("../docs/screenshots/$lang/$name.png", roborazziOptions = options) {
            // Las barras de progreso se dibujan quietas, como en una vista previa.
            CompositionLocalProvider(LocalInspectionMode provides true) {
                WrtTheme(dark = false) {
                    AppFrame(
                        title = t("Home", "Casa"),
                        subtitle = "192.168.1.1",
                        section = section,
                        onSection = {},
                        pageTitle = title,
                    ) { content() }
                }
            }
        }
    }

    private val now = LocalDateTime.of(2026, 11, 5, 18, 0)
    private val anaPhone = "9C:29:76:00:00:01"

    @Test
    fun home() = screen("home", Section.Overview) {
        OverviewContent(
            Overview(
                online = true, onlineSeconds = 9 * 86_400L + 4 * 3600, devices = 14, profiles = 2, paused = 1, newDevices = 1,
                cableMbps = 1000, attention = listOf(dev.angel.wrtcontrol.ui.home.Attention.NewDevices),
            ),
            wifi = HomeWifi.On(t("Home", "Casa"), 11),
            guest = GuestWifi(t("Home guests", "Casa invitados"), "hk7m2pqx4n", on = true, clients = listOf("AA:00:00:00:00:01")),
        )
    }

    @Test
    fun devices() = screen("devices", Section.Devices) {
        val vendors = (RuntimeEnvironment.getApplication() as WrtApp).vendors
        fun device(mac: String, name: String?, ip: String, lease: LeaseState = LeaseState.Expires(40_000)) =
            Device(mac, name, listOf(ip), emptyList(), lease)
        DevicesContent(
            listOf(
                device(anaPhone, t("anas-phone", "movil-de-ana"), "192.168.1.20").copy(presence = Presence.Online),
                device("B8:27:EB:00:00:02", null, "192.168.1.21", LeaseState.Permanent)
                    .copy(label = t("Home server", "Servidor de casa"), fixedIp = "192.168.1.21", presence = Presence.Online),
                device("F0:EF:86:00:00:06", null, "192.168.1.25").copy(label = t("Living room TV", "Tele de la sala"), presence = Presence.Online),
                device("24:6F:28:00:00:03", null, "192.168.1.22").copy(presence = Presence.Idle),
                device("A6:11:22:00:00:04", null, "192.168.1.23").copy(presence = Presence.Offline),
            ),
            vendors,
            profiles = listOf(Profile("a1", "Ana", listOf(anaPhone))),
            pauses = DevicePauses(mapOf("24:6F:28:00:00:03" to Pause.Indefinite), now),
            onOpen = {},
        )
    }

    @Test
    fun family() = screen("family", Section.Family) {
        val school = setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        val profiles = listOf(
            Profile(
                "a1", "Ana", listOf(anaPhone),
                pause = Pause.Until(now.plusMinutes(45)),
                schedules = listOf(Schedule("s1", school, LocalTime.of(21, 30), LocalTime.of(7, 0))),
                filter = ContentFilter(level = FilterLevel.Strict),
            ),
            Profile(
                "b2", "Leo", listOf("3C:22:FB:00:00:07"),
                schedules = listOf(Schedule("s2", school, LocalTime.of(22, 30), LocalTime.of(7, 0))),
                filter = ContentFilter(level = FilterLevel.Adult, services = setOf("tiktok")),
            ),
        )
        val devices = listOf(
            Device(anaPhone, t("Ana's phone", "Móvil de Ana"), listOf("192.168.1.20"), emptyList(), LeaseState.Expires(40_000)),
            Device("3C:22:FB:00:00:07", t("Leo's laptop", "Portátil de Leo"), listOf("192.168.1.26"), emptyList(), LeaseState.Expires(40_000)),
        )
        val snapshot = ParentalSnapshot(
            profiles = profiles, routerNow = now, routerOffsetSeconds = 0, routerZone = null,
            flowOffloading = false, markConflicts = emptyList(),
        )
        val data = ParentalData(snapshot, devices)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(CardGap)) {
            profiles.forEach { ProfileCard(it, data, busy = false, onEdit = {}, onPause = {}, onResume = {}, onExtraTime = {}, onClearExtraTime = {}) }
        }
    }

    @Test
    fun wifi() = screen("wifi", Section.Overview, title = "Wi-Fi") {
        val radios = listOf(
            WifiRadio("radio0", WifiBand.Band2, enabled = true, up = true, country = "MX", htmode = "HE20", htmodes = listOf("HE20", "HE40")),
            WifiRadio("radio1", WifiBand.Band5, enabled = true, up = true, country = "MX", htmode = "HE80", htmodes = listOf("HE80", "HE160")),
        )
        fun network(radio: String, ifname: String, clients: Int) = WifiNetwork(
            section = "default_$radio", radio = radio, ssid = t("Home", "Casa"), encryption = "sae-mixed", key = "home-sweet-home",
            enabled = true, networks = listOf("lan"), ifname = ifname, clients = List(clients) { "AA:00:00:00:00:%02X".format(it) },
        )
        WifiContent(WifiState(radios, listOf(network("radio0", "phy0-ap0", 3), network("radio1", "phy1-ap0", 8))))
    }

    @Test
    fun guests() = screen("guests", Section.Overview, title = t("Guest Wi-Fi", "Wi-Fi de invitados")) {
        val radios = listOf(WifiRadio("radio0", WifiBand.Band2, enabled = true, up = true))
        val guest = WifiNetwork(
            section = "wrtcguest_radio0", radio = "radio0", ssid = t("Home guests", "Casa invitados"), encryption = "sae-mixed", key = "hk7m2pqx4n",
            enabled = true, networks = listOf("wrtcguest"), ifname = "phy0-ap1", clients = listOf("AA:00:00:00:00:01"),
        )
        GuestContent(WifiState(radios, listOf(guest)))
    }

    /** Lo que distingue a la app: antes, qué va a cambiar; durante, aplicar, comprobar y confirmar. */
    @Test
    fun safety() = screen("safe-changes", Section.Devices) {
        val ip = "192.168.1.42"
        val tablet = Device("9C:29:76:00:00:08", "tablet", listOf(ip), emptyList(), LeaseState.Expires(3600), label = t("Tablet", "Tableta"))
        val change = DeviceChange(label = t("Ana's tablet", "Tableta de Ana"), fixedIp = ip, owner = "a1")
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Como sale en la app: en un diálogo sobre la pantalla.
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                Column(Modifier.padding(8.dp)) {
                    ChangePreviewBody(devicePreview(raw(t("Tablet", "Tableta")), tablet, change, listOf(Profile("a1", "Ana", emptyList()))))
                }
            }
            ApplyingBanner(ChangeProgress(raw(t("Pausing Ana…", "Pausando a Ana…")), ApplyPhase.Verifying))
            ApplyingBanner(ChangeProgress(raw(t("Turning on UPnP…", "Activando UPnP…")), ApplyPhase.Reverting))
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w393dp-h852dp-xhdpi")
class ReadmeScreenshotsEnglish : ReadmeScreenshots("en")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "es-w393dp-h852dp-xhdpi")
class ReadmeScreenshotsSpanish : ReadmeScreenshots("es")
