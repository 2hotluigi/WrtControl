package dev.angel.wrtcontrol

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources

/**
 * Un texto para el usuario que todavía no está en un idioma: el recurso y sus datos. La lógica
 * (errores, consejos, formatos) lo devuelve sin necesitar un Context, y la interfaz lo convierte
 * en el idioma del teléfono con [text] o [resolve]. Los datos pueden ser otros [Txt].
 *
 * En las pruebas de la JVM se convierte con TestStrings (lee los strings.xml en español).
 */
sealed interface Txt {
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : Txt

    /** Con plurales: [count] elige la forma (1 sitio / 3 sitios). */
    data class Plural(@param:PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : Txt

    /** Algo que no se traduce: un nombre, una dirección, lo que dice el router. */
    data class Raw(val text: String) : Txt

    /** Varios textos seguidos, con un separador que no se traduce (" · ", "\n"). */
    data class Joined(val parts: List<Txt>, val separator: String) : Txt
}

fun txt(@StringRes id: Int, vararg args: Any): Txt = Txt.Res(id, args.toList())

fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): Txt = Txt.Plural(id, count, args.toList())

fun raw(text: String): Txt = Txt.Raw(text)

fun List<Txt>.joinTxt(separator: String): Txt = Txt.Joined(this, separator)

/** El texto en el idioma de [res]. */
fun Txt.resolve(res: Resources): String = when (this) {
    // Sin datos, tal cual: con getString(id, vacío) Android formatea igual y un "%" suelto rompería.
    is Txt.Res -> if (args.isEmpty()) res.getString(id) else res.getString(id, *args.map { it.resolveArg(res) }.toTypedArray())
    is Txt.Plural ->
        if (args.isEmpty()) {
            res.getQuantityString(id, count)
        } else {
            res.getQuantityString(id, count, *args.map { it.resolveArg(res) }.toTypedArray())
        }
    is Txt.Raw -> text
    is Txt.Joined -> parts.joinToString(separator) { it.resolve(res) }
}

private fun Any.resolveArg(res: Resources): Any = if (this is Txt) resolve(res) else this

/** El texto en el idioma de la app (se recompone si cambia). */
@Composable
fun Txt.text(): String = resolve(LocalResources.current)
