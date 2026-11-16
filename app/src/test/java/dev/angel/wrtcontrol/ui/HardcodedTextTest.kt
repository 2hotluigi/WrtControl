package dev.angel.wrtcontrol.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Los textos de las pantallas van en strings.xml (español en values/, inglés en values-en/), no en
 * el código. Falla si en un fichero de la interfaz aparece un texto fijo donde se muestra algo.
 */
class HardcodedTextTest {

    private val uiDir = listOf("src/main/java/dev/angel/wrtcontrol/ui", "app/src/main/java/dev/angel/wrtcontrol/ui")
        .map(::File).first { it.isDirectory }

    /** Llamadas que muestran texto, con el literal que les llega. */
    private val shown = Regex(
        """\b(?:Text|InfoCard|InfoRow|NoticeCard|ErrorBanner|StatusPill|OutlinedButton|TextButton)\(\s*"([^"]*)"""" +
            """|\b(?:contentDescription|label|title|text|placeholder|supportingText)\s*=\s*"([^"]*)"""",
    )

    /** Nombres técnicos que no se traducen. */
    private val technical = Regex("""\b(?:IPv4|IPv6|DNS|MAC|IP|DHCP|WireGuard|OpenWrt|LuCI|SQM|UPnP|WrtControl)\b""")

    private val template = Regex("""\$\{[^}]*}|\$\w+""")

    /** ¿Lleva el literal palabras, aparte de variables (`$x`, `${...}`) y nombres técnicos? */
    private fun hasWords(literal: String): Boolean =
        literal.replace(template, "").replace(technical, "").contains(Regex("""\p{L}{2,}"""))

    @Test
    fun `las pantallas no llevan textos fijos`() {
        val offenders = uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    val code = line.substringBefore("//")
                    val literals = shown.findAll(code).map { it.groupValues[1] + it.groupValues[2] }
                    if (literals.any(::hasWords)) "${f.relativeTo(uiDir).invariantSeparatorsPath}:${i + 1}: ${line.trim()}" else null
                }
            }
            .toList()
        assertTrue("Textos fijos (pásalos a strings.xml):\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `la comprobacion detecta lo que debe`() {
        assertTrue(hasWords("Conectar"))
        assertTrue(hasWords("Hola \$name"))
        assertFalse(hasWords("\$address\n\${device.mac}"))
        assertFalse(hasWords("IPv6"))
        assertFalse(shown.containsMatchIn("ErrorText(x)"))
    }
}
