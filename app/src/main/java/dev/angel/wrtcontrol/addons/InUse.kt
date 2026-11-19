package dev.angel.wrtcontrol.addons

import dev.angel.wrtcontrol.openwrt.Feature

/**
 * Las funciones de los paquetes que se ven en marcha y funcionando en el router, aunque no las
 * activara este teléfono (otro teléfono, LuCI, una versión anterior de la app): cuentan como
 * "funciona aquí" en Estado (ver RouterRepository.markVerified).
 *
 * - Acceso remoto: su interfaz levantada y algún teléfono que se haya conectado alguna vez.
 * - SQM: una cola activa en la salida a internet.
 * - UPnP: activado y en marcha.
 * - Nombre fijo: el de la app activo, con dirección registrada y sin errores desde la última vez.
 */
fun AddonsState.inUse(): Set<Feature> = buildSet {
    if (remote != null && wireguard.orEmpty().any(::remoteReached)) add(Feature.RemoteAccess)
    if (sqm.orEmpty().any { it.enabled && it.device in internetDevices }) add(Feature.Sqm)
    if (upnp?.enabled == true && upnp.running == true) add(Feature.Upnp)
    if (ownDdns.any(::ddnsWorking)) add(Feature.DynamicDns)
}

/** El túnel del acceso remoto, levantado y con algún teléfono que haya llegado alguna vez. */
private fun remoteReached(tunnel: WireGuardInterface): Boolean =
    tunnel.name == VPN_INTERFACE && tunnel.up && tunnel.peers.any { (it.lastHandshake ?: 0) > 0 }

/** El nombre fijo, activo y al día: con dirección registrada y sin errores desde la última vez. */
private fun ddnsWorking(service: DdnsService): Boolean =
    service.enabled && service.running && service.registeredIp != null && service.lastError == null
