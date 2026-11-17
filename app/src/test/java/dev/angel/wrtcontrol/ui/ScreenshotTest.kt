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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.angel.wrtcontrol.WrtApp
import dev.angel.wrtcontrol.data.Loadable
import dev.angel.wrtcontrol.data.StatusData
import dev.angel.wrtcontrol.openwrt.BoardInfo
import dev.angel.wrtcontrol.openwrt.Capabilities
import dev.angel.wrtcontrol.openwrt.Device
import dev.angel.wrtcontrol.openwrt.IpAddress
import dev.angel.wrtcontrol.openwrt.LeaseState
import dev.angel.wrtcontrol.openwrt.LinkProblem
import dev.angel.wrtcontrol.openwrt.LuciFeatures
import dev.angel.wrtcontrol.openwrt.NetInterface
import dev.angel.wrtcontrol.openwrt.Presence
import dev.angel.wrtcontrol.openwrt.Radio
import dev.angel.wrtcontrol.openwrt.SystemInfo
import dev.angel.wrtcontrol.openwrt.WifiBand
import dev.angel.wrtcontrol.openwrt.linkAdvice
import dev.angel.wrtcontrol.parental.Pause
import dev.angel.wrtcontrol.parental.Profile
import dev.angel.wrtcontrol.ubus.SessionAcl
import dev.angel.wrtcontrol.ui.home.Attention
import dev.angel.wrtcontrol.ui.home.DevicePauses
import dev.angel.wrtcontrol.ui.home.DevicesContent
import dev.angel.wrtcontrol.ui.home.Overview
import dev.angel.wrtcontrol.ui.home.OverviewContent
import dev.angel.wrtcontrol.ui.home.StatusContent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime

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

    @Test
    fun estado() = both("estado") {
        StatusContent(Loadable(status), capabilities)
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
}
