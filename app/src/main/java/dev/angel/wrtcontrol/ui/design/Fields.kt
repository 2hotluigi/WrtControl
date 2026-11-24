package dev.angel.wrtcontrol.ui.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.R

/** Lo que ocupa la etiqueta de un campo: los valores de un grupo empiezan todos a la misma altura. */
private val FieldLabelWidth = 112.dp

/**
 * Un campo de texto dentro de un grupo: [label] a la izquierda y lo que se escribe a la derecha.
 * [hidden]: se escribe con puntos (una contraseña). [trailing]: p. ej. "Mostrar".
 */
@Composable
fun FieldRow(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hidden: Boolean = false,
    password: Boolean = false,
    divider: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .drawBehind {
                if (divider) {
                    val y = size.height - 0.5.dp.toPx()
                    drawLine(palette.separator, Offset(16.dp.toPx(), y), Offset(size.width, y), strokeWidth = 1.dp.toPx() / 2)
                }
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = palette.label, modifier = Modifier.width(FieldLabelWidth))
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = if (enabled) palette.label else palette.tertiary),
            cursorBrush = SolidColor(palette.accent),
            visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = if (password) KeyboardOptions(keyboardType = KeyboardType.Password) else KeyboardOptions.Default,
            modifier = modifier.weight(1f).padding(vertical = 14.dp),
        )
        trailing?.invoke()
    }
}

/** Una opción de las que se elige una: lleva la marca si es la elegida. */
@Composable
fun CheckRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    divider: Boolean = true,
) {
    val palette = LocalPalette.current
    GroupRow(
        label,
        modifier.semantics {
            this.selected = selected
            role = Role.RadioButton
        },
        labelLines = 2,
        labelColor = if (enabled) null else palette.tertiary,
        divider = divider,
        onClick = if (enabled) onClick else null,
        trailing = {
            if (selected) {
                Icon(
                    painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = if (enabled) palette.accent else palette.tertiary,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    )
}
