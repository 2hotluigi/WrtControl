package dev.angel.wrtcontrol.openwrt

import androidx.annotation.StringRes
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.txt
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * El Wi-Fi de casa, en /etc/config/wireless: una sección `wifi-device` por radio (2,4 GHz, 5 GHz…)
 * y una `wifi-iface` por red que emite. "Tu Wi-Fi" son las redes de punto de acceso unidas a la red
 * de casa (`lan`), una por radio. La app les pone a todas el mismo nombre, clave y seguridad: el
 * aparato elige la banda. Encender o apagar una banda es encender o apagar su red, no la radio
 * (que puede servir a otras redes, como la de invitados).
 *
 * El ACL de LuCI deja leer y escribir `wireless`, y `luci-rpc getWirelessDevices` dice qué radios
 * están emitiendo y con qué redes. `iwinfo assoclist` dice qué aparatos hay conectados a cada una.
 */

/** Cómo se protege la red. [uci]: el valor de `encryption`. */
enum class WifiSecurity(val uci: String, @param:StringRes val label: Int) {
    /** WPA3 para quien lo sabe y WPA2 para el resto: lo que se recomienda hoy. */
    Wpa2Wpa3("sae-mixed", R.string.wifi_security_mixed),
    Wpa3("sae", R.string.wifi_security_wpa3),
    Wpa2("psk2", R.string.wifi_security_wpa2),
    Open("none", R.string.wifi_security_open),
}

/** La seguridad de un `encryption` de UCI ("psk2+ccmp" es WPA2), o null si es otra (WEP, empresa…). */
fun wifiSecurity(encryption: String?): WifiSecurity? {
    val base = encryption?.substringBefore('+')?.ifEmpty { null } ?: "none"
    return WifiSecurity.entries.firstOrNull { it.uci == base }
}

/**
 * Una radio Wi-Fi: [up] si está emitiendo ahora. [country]: el país que tiene puesto (ISO), o null.
 * [channel]: el canal configurado ("36", "auto"), o null. [htmode]: estándar y ancho ("HE80").
 * [htmodes]: los que admite la radio (vacío si el router no lo dice).
 */
data class WifiRadio(
    val name: String,
    val band: WifiBand?,
    val enabled: Boolean,
    val up: Boolean,
    val country: String? = null,
    val channel: String? = null,
    val htmode: String? = null,
    val htmodes: List<String> = emptyList(),
    /** El `txpower` puesto (dBm; null: toda la que se pueda) y el tope que admite la radio (null: no se sabe). */
    val txpower: Int? = null,
    val maxPower: Int? = null,
) {
    /** El ancho del canal en MHz, o null si no se sabe. */
    val width: Int? get() = htmodeWidth(htmode)

    /** ¿Puede emitir a [width] MHz? */
    fun offers(width: Int): Boolean = htmodeFor(htmode, width, htmodes) != null

    /**
     * ¿Puede tardar en volver a emitir? En los canales de 5 GHz que comparte con los radares (DFS),
     * en "auto", que puede elegir uno, y a 160 MHz, que siempre pisa alguno, el router escucha un
     * minuto antes de emitir.
     */
    val mayWaitForRadar: Boolean
        get() = band == WifiBand.Band5 &&
            (channel == null || channel == "auto" || channel.toIntOrNull() in DFS_CHANNELS || width == WIDTH_160)
}

private val DFS_CHANNELS = 52..144

/**
 * Una red que emite el router. [ifname]: su interfaz si está emitiendo ahora ("phy0-ap0"), null si
 * no. [clients]: las MAC conectadas a ella ahora mismo (vacío si no se puede saber).
 */
data class WifiNetwork(
    val section: String,
    val radio: String,
    val ssid: String,
    val encryption: String,
    val key: String?,
    val enabled: Boolean,
    val networks: List<String>,
    val ifname: String? = null,
    val clients: List<String> = emptyList(),
    /** Con qué fuerza oye el router a cada conectado (MAC → dBm), de los que lo diga. */
    val signals: Map<String, Int> = emptyMap(),
    /** Es de "Tu Wi-Fi": un punto de acceso de la red de casa (ver HomeNetwork.kt). */
    val isHome: Boolean = DEFAULT_HOME_NETWORK in networks,
) {
    val security: WifiSecurity? get() = wifiSecurity(encryption)
}

data class WifiState(val radios: List<WifiRadio>, val networks: List<WifiNetwork>) {
    /** "Tu Wi-Fi": la red de casa de cada radio (la primera, si hubiera varias). */
    val home: List<WifiNetwork> get() = radios.mapNotNull { r -> networks.firstOrNull { it.radio == r.name && it.isHome } }

    /** Las radios con la red de casa encendida. */
    val homeOn: Set<String>
        get() = home.filter { n -> n.enabled && radios.any { it.name == n.radio && it.enabled } }.map { it.radio }.toSet()

    /** Las bandas tienen nombres o seguridad distintos: al guardar quedarán iguales. */
    val homeDiffers: Boolean get() = home.map { Triple(it.ssid, it.encryption, it.key) }.distinct().size > 1

    /**
     * Alguna radio encendida en la configuración no está emitiendo: lo que deja `wifi down` (el
     * horario del Wi-Fi). Las radios sin redes encendidas siguen "levantadas", así que no cuentan.
     */
    val someRadioDown: Boolean get() = radios.any { it.enabled && !it.up }

    /** El país del Wi-Fi si todas las radios tienen el mismo; null si falta en alguna o no coinciden. */
    val country: String? get() = radios.map { it.country }.distinct().singleOrNull()
}

/** Lo demás que dice `iwinfo`: la señal de cada conectado (interfaz → MAC → dBm) y el tope de potencia de cada radio. */
internal data class WifiExtras(val signals: Map<String, Map<String, Int>> = emptyMap(), val maxPower: Map<String, Int> = emptyMap())

/**
 * Lee el estado: las secciones de `wireless`, lo que dice `getWirelessDevices` y los conectados por
 * interfaz. [home]: las redes de casa ([HomeLayout.home]); sus puntos de acceso son "Tu Wi-Fi".
 */
@Suppress("LongParameterList") // Lo que se ha leído del router, cada cosa por su lado.
internal fun parseWifi(
    sections: List<UciSection>,
    devices: Map<String, JsonObject>,
    clients: Map<String, List<String>> = emptyMap(),
    htmodes: Map<String, List<String>> = emptyMap(),
    extras: WifiExtras = WifiExtras(),
    home: List<String> = listOf(DEFAULT_HOME_NETWORK),
): WifiState {
    val running = devices.mapValues { (_, d) -> runningInterfaces(d) }
    val radios = sections.filter { it.type == "wifi-device" }.map { s ->
        val status = devices[s.name]
        WifiRadio(
            name = s.name,
            band = bandOf(s.text("band"), s.text("hwmode")),
            enabled = s.text("disabled") != "1",
            up = (status?.get("up") as? JsonPrimitive)?.contentOrNull == "true",
            // "00" es "el mundo": lo mismo que no haberlo puesto.
            country = s.text("country")?.uppercase()?.takeIf { COUNTRY_CODE.matches(it) },
            channel = s.text("channel"),
            htmode = s.text("htmode"),
            htmodes = htmodes[s.name].orEmpty(),
            txpower = s.text("txpower")?.toIntOrNull(),
            maxPower = extras.maxPower[s.name],
        )
    }
    val networks = sections.filter { it.type == "wifi-iface" && (it.text("mode") ?: "ap") == "ap" }.mapNotNull { s ->
        val radio = s.text("device") ?: return@mapNotNull null
        val ifname = running[radio]?.get(s.name)
        WifiNetwork(
            section = s.name,
            radio = radio,
            ssid = s.text("ssid").orEmpty(),
            encryption = s.text("encryption") ?: "none",
            key = s.text("key"),
            enabled = s.text("disabled") != "1",
            networks = s.items("network"),
            ifname = ifname,
            clients = ifname?.let { clients[it] }.orEmpty(),
            signals = ifname?.let { extras.signals[it] }.orEmpty(),
            isHome = s.items("network").any { it in home },
        )
    }
    return WifiState(radios.sortedBy { it.name }, networks)
}

private fun bandOf(band: String?, hwmode: String?): WifiBand? = when {
    band == "2g" -> WifiBand.Band2
    band == "5g" -> WifiBand.Band5
    band == "6g" -> WifiBand.Band6
    // Antes de 21.02 no había `band`: 11a es 5 GHz y lo demás, 2,4.
    hwmode?.contains('a') == true -> WifiBand.Band5
    hwmode != null -> WifiBand.Band2
    else -> null
}

/** De `getWirelessDevices`: sección de cada red que emite → su interfaz. */
private fun runningInterfaces(device: JsonObject): Map<String, String> =
    (device["interfaces"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { element: JsonElement ->
        val iface = element as? JsonObject ?: return@mapNotNull null
        val section = (iface["section"] as? JsonPrimitive)?.contentOrNull
        val ifname = (iface["ifname"] as? JsonPrimitive)?.contentOrNull
        if (section != null && ifname != null) section to ifname else null
    }.toMap()

/** Lo que el usuario quiere para "Tu Wi-Fi". [radios]: las que emiten la red; las demás, apagadas. */
data class WifiEdit(val ssid: String, val key: String, val security: WifiSecurity, val radios: Set<String>)

private const val MAX_SSID_BYTES = 32
private const val MIN_KEY = 8
private const val MAX_KEY = 63
private const val HEX_KEY = 64
private val HEX = Regex("^[0-9a-fA-F]{$HEX_KEY}$")

/** Por qué [edit] no se puede guardar, o null si se puede. */
fun wifiProblem(edit: WifiEdit): Txt? {
    val ssidBytes = edit.ssid.toByteArray(Charsets.UTF_8).size
    val key = edit.key
    val printable = key.all { it.code in 0x20..0x7e }
    return when {
        edit.ssid.isBlank() -> txt(R.string.wifi_error_ssid_empty)
        ssidBytes > MAX_SSID_BYTES || edit.ssid.any { it.isISOControl() } -> txt(R.string.wifi_error_ssid_long)
        edit.security == WifiSecurity.Open -> null
        HEX.matches(key) -> null
        key.length !in MIN_KEY..MAX_KEY || !printable -> txt(R.string.wifi_error_key)
        else -> null
    }
}

/** Un cambio en `wireless`. */
internal sealed interface WifiChange {
    data class Set(val section: String, val values: Map<String, String>) : WifiChange

    data class DropOption(val section: String, val option: String) : WifiChange

    data class Add(val section: UciSection) : WifiChange
}

/**
 * Lo que hay que cambiar en [sections] para que "Tu Wi-Fi" quede como dice [edit]. [home]: las
 * redes de casa, con la principal primero; en ella se crea la red de una radio que no la tenía.
 */
internal fun wifiPlan(sections: List<UciSection>, edit: WifiEdit, home: List<String> = listOf(DEFAULT_HOME_NETWORK)): List<WifiChange> {
    val open = edit.security == WifiSecurity.Open
    val taken = sections.map { it.name }.toMutableSet()
    return sections.filter { it.type == "wifi-device" }.flatMap { radio ->
        val on = radio.name in edit.radios
        val wanted = buildMap {
            put("ssid", edit.ssid)
            put("encryption", edit.security.uci)
            if (!open) put("key", edit.key)
            put("disabled", if (on) "0" else "1")
        }
        val iface = sections.firstOrNull { s ->
            s.type == "wifi-iface" && s.text("device") == radio.name && (s.text("mode") ?: "ap") == "ap" &&
                s.items("network").any { it in home }
        }
        buildList {
            if (on && radio.text("disabled") == "1") add(WifiChange.Set(radio.name, mapOf("disabled" to "0")))
            when {
                iface == null && on -> add(WifiChange.Add(newHomeIface(radio.name, home.first(), taken, wanted)))
                iface == null -> Unit
                else -> addAll(ifaceChanges(iface, wanted, open))
            }
        }
    }
}

private fun ifaceChanges(iface: UciSection, wanted: Map<String, String>, open: Boolean): List<WifiChange> {
    // "disabled" sin poner es lo mismo que "0".
    val current = { key: String -> iface.text(key) ?: if (key == "disabled") "0" else null }
    val set = wanted.filter { (key, value) -> current(key) != value }
    return listOfNotNull(
        set.takeIf { it.isNotEmpty() }?.let { WifiChange.Set(iface.name, it) },
        WifiChange.DropOption(iface.name, "key").takeIf { open && "key" in iface.options },
    )
}

private fun newHomeIface(radio: String, network: String, taken: MutableSet<String>, values: Map<String, String>): UciSection {
    val name = generateSequence(0) { it + 1 }.map { if (it == 0) "home_$radio" else "home_${radio}_$it" }.first { it !in taken }
    taken += name
    return UciSection(
        name,
        "wifi-iface",
        (mapOf("device" to radio, "mode" to "ap", "network" to network) + values).mapValues { (_, v) -> UciValue.Text(v) },
    )
}

/**
 * ¿Ya emite el router como dice [edit]? Cada radio con la red encendida, levantada, sin cambios
 * pendientes y con su red emitiendo; las apagadas, sin ella.
 */
internal fun wifiLive(state: WifiState, devices: Map<String, JsonObject>, edit: WifiEdit): Boolean = state.radios.all { radio ->
    val pending = (devices[radio.name]?.get("pending") as? JsonPrimitive)?.contentOrNull == "true"
    val home = state.home.firstOrNull { it.radio == radio.name }
    val emitting = home?.ifname != null
    if (radio.name in edit.radios) radio.up && !pending && home?.ssid == edit.ssid && emitting else !emitting
}
