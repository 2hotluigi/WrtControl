package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.ubus.UbusJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class VnstatTest {

    private fun json(text: String) = UbusJson.decodeFromString(VnstatJson.serializer(), text)

    /** Como lo da vnStat 2.13: creado a las 7 del día 19, guardado por última vez a las 10:23 del 21. */
    private fun iface(traffic: String) =
        """{"vnstatversion":"2.13","jsonversion":"2","interfaces":[{"name":"eth1","alias":"",""" +
            """"created":{"date":{"year":2026,"month":11,"day":19},"timestamp":1795072852},""" +
            """"updated":{"date":{"year":2026,"month":11,"day":21},"time":{"hour":10,"minute":23},"timestamp":1795250580},""" +
            """"traffic":{"total":{"rx":1,"tx":2},$traffic}}]}"""

    @Test
    fun `historial - huecos a cero, hasta la ultima vez que guardo`() {
        val hours = json(
            iface(
                """"hour":[""" +
                    """{"id":1,"date":{"year":2026,"month":11,"day":20},"time":{"hour":23,"minute":0},"timestamp":0,"rx":500,"tx":50},""" +
                    """{"id":2,"date":{"year":2026,"month":11,"day":21},"time":{"hour":10,"minute":0},"timestamp":0,"rx":700,"tx":70}]""",
            ),
        )
        val days = json(
            iface(
                """"day":[{"id":1,"date":{"year":2026,"month":11,"day":19},"timestamp":0,"rx":1000,"tx":100},""" +
                    """{"id":2,"date":{"year":2026,"month":11,"day":21},"timestamp":0,"rx":3000,"tx":300}]""",
            ),
        )
        val history = parseUsageHistory(hours, days, "eth1")!!

        assertEquals(24, history.hours.size)
        assertEquals(LocalDateTime.of(2026, 11, 20, 11, 0), history.hours.first().start)
        assertEquals(UsageBucket(LocalDateTime.of(2026, 11, 21, 10, 0), 700, 70), history.hours.last())
        assertEquals(UsageBucket(LocalDateTime.of(2026, 11, 20, 23, 0), 500, 50), history.hours.single { it.receivedBytes == 500L })
        assertEquals(22, history.hours.count { it.totalBytes == 0L })

        // Días: desde que empezó a mirar la tarjeta (el 19), no 30 días vacíos.
        assertEquals(listOf(19, 20, 21), history.days.map { it.start.dayOfMonth })
        assertEquals(listOf(1100L, 0L, 3300L), history.days.map { it.totalBytes })
        assertEquals(LocalDate.of(2026, 11, 19).atStartOfDay(), history.days.first().start)
    }

    @Test
    fun `historial - recien empezado solo cubre desde que se creo`() {
        val text = """{"interfaces":[{"name":"eth1","created":{"date":{"year":2026,"month":11,"day":21},"time":{"hour":9,"minute":40}},""" +
            """"updated":{"date":{"year":2026,"month":11,"day":21},"time":{"hour":10,"minute":5}},"traffic":{"hour":[],"day":[]}}]}"""
        val history = parseUsageHistory(json(text), json(text), "eth1")!!
        assertEquals(listOf(9, 10), history.hours.map { it.start.hour })
        assertEquals(1, history.days.size)
    }

    /** Lo que dio el GL-MT6000 recién instalado: `created` sin hora; el router va 6 horas por detrás de UTC. */
    @Test
    fun `historial - la hora de inicio sale del timestamp y la diferencia horaria del router`() {
        val text = """{"interfaces":[{"name":"eth1","created":{"date":{"year":2026,"month":11,"day":23},"timestamp":1795479898},""" +
            """"updated":{"date":{"year":2026,"month":11,"day":23},"time":{"hour":18,"minute":25},"timestamp":1795479900},""" +
            """"traffic":{"hour":[{"id":1,"date":{"year":2026,"month":11,"day":23},"time":{"hour":18,"minute":0},"rx":8266,"tx":27736}]}}]}"""
        val history = parseUsageHistory(json(text), json(text), "eth1")!!
        assertEquals("una sola hora, no desde medianoche", listOf(UsageBucket(LocalDateTime.of(2026, 11, 23, 18, 0), 8266, 27736)), history.hours)
    }

    @Test
    fun `historial - otra tarjeta o sin datos`() {
        assertNull(parseUsageHistory(json(iface(""""hour":[]""")), VnstatJson(), "wan"))
        // "Database is empty": la app lo lee como sin interfaces.
        assertNull(parseUsageHistory(VnstatJson(), VnstatJson(), "eth1"))
    }

    @Test
    fun `tarjetas que conoce vnStat`() {
        assertEquals(setOf("eth1"), vnstatDevices(iface(""""fiveminute":[]""")))
        assertEquals(emptySet<String>(), vnstatDevices("Error: Database is empty."))
    }

    @Test
    fun `configuracion - guarda en la flash cada hora`() {
        val stock = ";DatabaseDir \"/var/lib/vnstat\"\n;SaveInterval 5\n;OfflineSaveInterval 30\n"
        assertFalse(vnstatKeepsData(stock))
        val done = "DatabaseDir \"/etc/wrtc_vnstat\"\nSaveInterval 60\n;OfflineSaveInterval 30\n"
        assertTrue(vnstatKeepsData(done))
        assertFalse("en la flash pero guardando cada 5 minutos", vnstatKeepsData("DatabaseDir \"/etc/wrtc_vnstat\"\n;SaveInterval 5\n"))
    }

    @Test
    fun `crontab - la linea de la app se pone una vez y se quita sin tocar lo demas`() {
        val other = "# mis tareas\n0 4 * * * /sbin/reboot\n"
        val with = withVnstatLine(other)
        assertEquals(other + VNSTAT_CRON_LINE + "\n", with)
        assertEquals("solo una vez", with, withVnstatLine(with))
        assertEquals(other, withoutVnstatLine(with))
        assertEquals("", withoutVnstatLine(withVnstatLine("")))
        assertTrue(hasOtherJobs(with))
        assertFalse("un comentario no es una tarea", hasOtherJobs(withVnstatLine("# nada\n")))
    }

    @Test
    fun `la orden de cron no lleva almohadilla - BusyBox cortaria ahi la linea`() {
        val command = VNSTAT_CRON_LINE.substringBeforeLast(" # ")
        assertFalse(command, '#' in command)
        assertFalse("cron trata % como salto de línea", '%' in command)
        // Se borra sola: la orden busca la marca que lleva la línea.
        assertTrue("sed -i /wrtc_vnstat/d $CRONTAB" in command && VNSTAT_CRON_LINE.endsWith("# wrtc_vnstat"))
    }
}
