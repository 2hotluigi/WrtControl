package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.joinTxt
import dev.angel.wrtcontrol.plural
import dev.angel.wrtcontrol.txt

/*
 * La salud del cable de internet (el que va del módem al router). Un cable con un par dañado tira el
 * enlace cada pocos minutos y lo deja a 100 Mbps, y desde la app parece que falla todo lo demás.
 * El router ya lo cuenta, con lo que permite LuCI:
 *
 * - `luci-rpc.getNetworkDevices`: si hay enlace, a qué velocidad y cuántas veces se ha caído desde
 *   que arrancó.
 * - `network.device.status`: qué velocidades anuncian el router y el aparato del otro lado. Si los
 *   dos anuncian 1000 y el enlace va a 100, el problema es el cable.
 * - `log.read`: cuándo fue cada caída ("eth1: Link is Down"), para decir "13 veces en 10 minutos".
 * - Un ping desde el router, para saber si de verdad se llega a internet.
 */

/** Cómo está el enlace de la salida a internet. Lo que el router no dejó leer va a null. */
data class WanLink(
    /** La tarjeta de red de la salida a internet ("eth1", "wan"). */
    val device: String,
    /** Hay enlace: el cable está puesto y el otro lado responde. */
    val carrier: Boolean,
    val speedMbps: Int?,
    /** Veces que se ha caído el enlace desde que arrancó el router. */
    val dropsSinceBoot: Int?,
    /** Lo más rápido que admiten el router y el aparato del otro lado. */
    val ownMaxMbps: Int? = null,
    val partnerMaxMbps: Int? = null,
    /** Hace cuántos segundos fue cada caída que queda en el registro; null si no se pudo leer. */
    val dropsAgoSeconds: List<Long>? = null,
    /** Un ping desde el router llega a internet; null si no se pudo probar. */
    val internet: Boolean? = null,
)

sealed interface LinkProblem {
    /** No hay enlace: cable suelto o el módem apagado. */
    data object NoCable : LinkProblem

    /** Se cayó [count] veces en los últimos [minutes] minutos. */
    data class Flapping(val count: Int, val minutes: Int) : LinkProblem

    /** Se ha caído [count] veces desde que arrancó (no se sabe cuándo). */
    data class FlappingSinceBoot(val count: Int) : LinkProblem

    /** Va a [mbps] aunque los dos lados admiten [expectedMbps]. */
    data class Slow(val mbps: Int, val expectedMbps: Int) : LinkProblem

    /** El enlace está bien, pero no se llega a internet: es cosa del módem o del proveedor. */
    data object NoInternet : LinkProblem
}

private const val RECENT_MINUTES = 10
private const val HOUR_MINUTES = 60
private const val RECENT_DROPS = 2
private const val HOUR_DROPS = 3

/** Sin el registro: tantas caídas desde el arranque ya no son alguien moviendo el cable. */
private const val BOOT_DROPS = 5
private const val GIGABIT = 1000

/** Lo que le pasa al enlace, lo más grave primero. Vacío: todo bien. */
fun WanLink.problems(): List<LinkProblem> {
    if (!carrier) return listOf(LinkProblem.NoCable)
    return listOfNotNull(flapping(), slow(), LinkProblem.NoInternet.takeIf { internet == false })
}

private fun WanLink.flapping(): LinkProblem? {
    val ago = dropsAgoSeconds ?: return dropsSinceBoot?.takeIf { it >= BOOT_DROPS }?.let { LinkProblem.FlappingSinceBoot(it) }
    val recent = ago.count { it <= RECENT_MINUTES * 60 }
    val lastHour = ago.count { it <= HOUR_MINUTES * 60 }
    return when {
        recent >= RECENT_DROPS -> LinkProblem.Flapping(recent, RECENT_MINUTES)
        lastHour >= HOUR_DROPS -> LinkProblem.Flapping(lastHour, HOUR_MINUTES)
        else -> null
    }
}

/** Solo si los dos lados dan para más: un módem con puerto de 100 no es un cable malo. */
private fun WanLink.slow(): LinkProblem? {
    val expected = minOf(ownMaxMbps ?: 0, partnerMaxMbps ?: 0)
    val speed = speedMbps ?: return null
    return if (expected >= GIGABIT && speed < GIGABIT) LinkProblem.Slow(speed, expected) else null
}

/** Qué pasa y qué hacer, en una frase por problema; null si no hay nada que decir. */
fun linkAdvice(problems: List<LinkProblem>): Txt? {
    if (problems.isEmpty()) return null
    val cable = problems.any { it is LinkProblem.Flapping || it is LinkProblem.FlappingSinceBoot || it is LinkProblem.Slow }
    val parts = problems.map { problem ->
        when (problem) {
            LinkProblem.NoCable -> txt(R.string.link_no_cable)
            is LinkProblem.Flapping -> plural(R.plurals.link_flapping, problem.count, problem.count, problem.minutes)
            is LinkProblem.FlappingSinceBoot -> txt(R.string.link_flapping_since_boot, problem.count)
            is LinkProblem.Slow -> txt(R.string.link_slow, problem.mbps, problem.expectedMbps)
            LinkProblem.NoInternet -> txt(R.string.link_no_internet)
        }
    }
    return (parts + listOfNotNull(txt(R.string.link_replace_cable).takeIf { cable })).joinTxt(" ")
}
