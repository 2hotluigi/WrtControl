package dev.angel.wrtcontrol.openwrt

/*
 * La línea del crontab que deja vnStat guardando en la flash (ver Vnstat.kt): se ejecuta una vez y
 * se borra sola. Al terminar, el crontab vuelve a como estaba.
 */

/** Marca la línea de la app en el crontab (y la orden la busca para borrarse). */
private const val VNSTAT_MARK = "wrtc_vnstat"

private const val CONF = "/etc/vnstat.conf"

/**
 * La línea del crontab que deja vnStat guardando en la flash y se borra sola. Sin `#` en la orden
 * (ver arriba); el comentario del final es solo para quien lea el crontab.
 */
internal val VNSTAT_CRON_LINE = "* * * * * mkdir -p $VNSTAT_DIR;/etc/init.d/vnstat stop;" +
    "cp -a /var/lib/vnstat/. $VNSTAT_DIR;" +
    "sed -i -e '/^;*DatabaseDir /d' -e '/^;*SaveInterval /d' $CONF;" +
    "echo 'DatabaseDir \"$VNSTAT_DIR\"' >>$CONF;echo 'SaveInterval $VNSTAT_SAVE_MINUTES' >>$CONF;" +
    "/etc/init.d/vnstat start;sed -i /$VNSTAT_MARK/d $CRONTAB # $VNSTAT_MARK"

/** [crontab] con la línea de la app al final (una sola vez); lo demás, como estaba. */
internal fun withVnstatLine(crontab: String): String =
    (crontabLines(withoutVnstatLine(crontab)) + VNSTAT_CRON_LINE).joinToString("\n") + "\n"

/** [crontab] sin la línea de la app; si no había nada más, vacío. */
internal fun withoutVnstatLine(crontab: String): String {
    val lines = crontabLines(crontab).filter { VNSTAT_MARK !in it }
    return if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
}

/**
 * Cómo dejar el crontab al terminar, si ahora es [current] y antes era [before]: tal cual estaba
 * (byte a byte) si nadie más lo ha tocado mientras tanto; si no, lo de ahora sin la línea de la app.
 */
internal fun restoredCrontab(before: String, current: String): String {
    val clean = withoutVnstatLine(current)
    return if (clean == withoutVnstatLine(before)) before else clean
}

/** ¿Hay algo que hacer en el crontab aparte de la línea de la app? */
internal fun hasOtherJobs(crontab: String): Boolean =
    crontabLines(crontab).any { VNSTAT_MARK !in it && !it.trimStart().startsWith("#") }

private fun crontabLines(crontab: String): List<String> = crontab.lines().filter { it.isNotBlank() }
