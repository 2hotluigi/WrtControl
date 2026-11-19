package dev.angel.wrtcontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * En Android las expresiones regulares las compila ICU, que lee "[:" como el principio de una clase
 * POSIX ("[:alpha:]") y falla al cargar la clase: la app se cierra. En la JVM de las pruebas
 * compilan bien, así que se revisa el código: ninguna `Regex("…")` de la app puede llevar "[:"
 * (se escribe "[-:]" en vez de "[:-]", por ejemplo).
 */
class IcuRegexTest {

    /** El texto de cada `Regex("…")` de [source]. */
    private fun literals(source: String): List<String> =
        Regex("""Regex\("((?:[^"\\]|\\.)*)"""").findAll(source).map { it.groupValues[1] }.toList()

    @Test
    fun `ninguna expresion regular lleva corchete y dos puntos`() {
        val found = File("src/main/java").walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            literals(file.readText()).filter { "[:" in it }.map { "${file.name}: $it" }
        }.toList()
        assertTrue("Expresiones que ICU no compila: $found", found.isEmpty())
    }

    @Test
    fun `la revision encuentra las expresiones`() {
        val source = """val a = Regex("([0-9A-Fa-f]{2}[:-]){5}") ; val b = Regex("\\d{1,3}\\.")"""
        assertEquals(listOf("([0-9A-Fa-f]{2}[:-]){5}", "\\\\d{1,3}\\\\."), literals(source))
    }
}
