package dev.angel.wrtcontrol

import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Convierte un [Txt] como lo haría el teléfono en español, leyendo app/src/main/res/values: así
 * las pruebas de la JVM comprueban los textos de verdad sin Android.
 */
object TestStrings {

    private val valuesDir = listOf("src/main/res/values", "app/src/main/res/values").map(::File).first { it.isDirectory }

    private val strings = mutableMapOf<String, String>()
    private val plurals = mutableMapOf<String, Map<String, String>>()

    init {
        val factory = DocumentBuilderFactory.newInstance()
        valuesDir.listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }!!.forEach { f ->
            val doc = factory.newDocumentBuilder().parse(f)
            val s = doc.getElementsByTagName("string")
            for (i in 0 until s.length) {
                val e = s.item(i) as Element
                strings[e.getAttribute("name")] = unescape(e.textContent)
            }
            val p = doc.getElementsByTagName("plurals")
            for (i in 0 until p.length) {
                val e = p.item(i) as Element
                val items = e.getElementsByTagName("item")
                plurals[e.getAttribute("name")] = (0 until items.length).associate {
                    val item = items.item(it) as Element
                    item.getAttribute("quantity") to unescape(item.textContent)
                }
            }
        }
    }

    /** Nombre del recurso a partir de su id (los de R son los mismos en las pruebas). */
    private fun name(id: Int, kind: String): String =
        Class.forName("dev.angel.wrtcontrol.R\$$kind").fields.first { it.getInt(null) == id }.name

    /** Como aapt: comillas que conservan espacios, y \' \" \n \\. */
    private fun unescape(text: String): String {
        val t = text.trim().let { if (it.length >= 2 && it.startsWith('"') && it.endsWith('"')) it.substring(1, it.length - 1) else it }
        return t.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("\\@", "@").replace("\\?", "?")
            .replace("\\\\", "\\")
    }

    private val es = Locale.forLanguageTag("es")

    fun resolve(t: Txt): String = when (t) {
        is Txt.Res -> format(strings.getValue(name(t.id, "string")), t.args)
        is Txt.Plural -> {
            val forms = plurals.getValue(name(t.id, "plurals"))
            val quantity = when {
                t.count == 1 -> "one"
                t.count != 0 && t.count % 1_000_000 == 0 -> "many"
                else -> "other"
            }
            format(forms[quantity] ?: forms.getValue("other"), t.args)
        }
        is Txt.Raw -> t.text
        is Txt.Joined -> t.parts.joinToString(t.separator) { resolve(it) }
    }

    private fun format(pattern: String, args: List<Any>): String =
        if (args.isEmpty()) pattern else String.format(es, pattern, *args.map { if (it is Txt) resolve(it) else it }.toTypedArray())
}

/** Atajo para las pruebas: el texto en español. */
val Txt.es: String get() = TestStrings.resolve(this)
