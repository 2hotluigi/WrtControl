package dev.angel.wrtcontrol.parental

import dev.angel.wrtcontrol.openwrt.Feature

/**
 * Qué funciones quedan en marcha en el router con [this], la configuración que acaba de aplicar y
 * confirmar la app (ver RouterRepository.markVerified): el control parental, si hay algo que lo use
 * (perfiles, pausas de un aparato suelto, cuarentena…), y el filtro, si algún perfil lo tiene.
 */
fun ParentalConfig.verifiedFeatures(): Set<Feature> = buildSet {
    if (profiles.isNotEmpty() || settings.copy(admins = emptyList()) != ParentalSettings()) add(Feature.Parental)
    if (profiles.any { it.needsDns }) add(Feature.ContentFilter)
}

/**
 * Lo que se ve funcionando ahora en el router (ver [verifiedFeatures]): solo si las reglas que
 * espera la app están cargadas en fw4, y el filtro, si además están en marcha los DNS de los perfiles.
 */
fun ParentalSnapshot.inUse(): Set<Feature> {
    if (rulesLoaded != true) return emptySet()
    val dns = dnsRunning
    return config.verifiedFeatures().filter { it != Feature.ContentFilter || (!dns.isNullOrEmpty() && dns.values.all { up -> up }) }.toSet()
}
