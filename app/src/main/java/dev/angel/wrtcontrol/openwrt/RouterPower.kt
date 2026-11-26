package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ubus.UbusCaller
import dev.angel.wrtcontrol.ubus.UbusException

/*
 * Reiniciar el router y devolverlo a como salió de fábrica, igual que LuCI: `system.reboot` (grupo
 * luci-mod-system-reboot) y `/sbin/firstboot -r -y` (luci-mod-system-flash, la página "Copia de
 * seguridad / Grabar firmware"). Son lo único de la app que deja la casa sin internet a propósito:
 * solo se hacen cuando el usuario lo confirma.
 */

private const val FIRSTBOOT = "/sbin/firstboot"
private val FIRSTBOOT_PARAMS = listOf("-r", "-y")

/** El ACL deja reiniciar el router. */
val Capabilities.canReboot: Boolean get() = acl.canCall("system", "reboot")

/** El ACL deja borrar los ajustes (el comando exacto, como lo lanza LuCI). */
val Capabilities.canFactoryReset: Boolean
    get() = acl.canCall("file", "exec") && acl.canFile((listOf(FIRSTBOOT) + FIRSTBOOT_PARAMS).joinToString(" "), "exec")

/** Reiniciar el router y borrar sus ajustes. */
class RouterPower(private val ubus: UbusCaller) {

    /** Reinicia el router. Contesta enseguida y se apaga poco después. */
    suspend fun reboot() {
        ubus.call("system", "reboot")
    }

    /**
     * Borra todos los ajustes del router y lo reinicia. Lo normal es que no conteste: se reinicia
     * sin terminar la llamada y la conexión se corta, así que un corte cuenta como que empezó. Si el
     * router dice que no (sin permiso, o el comando falla), lanza la excepción y no se ha borrado nada.
     */
    suspend fun factoryReset() {
        val result = try {
            ubus.exec(FIRSTBOOT, FIRSTBOOT_PARAMS)
        } catch (_: UbusException.Network) {
            return
        }
        if (result.code != 0) throw UbusException.Refused(txt(R.string.power_reset_failed))
    }
}
