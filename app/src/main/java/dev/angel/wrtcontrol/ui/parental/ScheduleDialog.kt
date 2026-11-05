package dev.angel.wrtcontrol.ui.parental

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.parental.Schedule
import java.time.DayOfWeek
import java.time.LocalTime

private val PRESETS = listOf(
    "L a V" to setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
    "D a J" to setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
    "S y D" to setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
    "Todos" to WEEK.toSet(),
)

@Composable
fun ScheduleDialog(initial: Schedule, isNew: Boolean, onDismiss: () -> Unit, onConfirm: (Schedule) -> Unit) {
    var days by remember { mutableStateOf(initial.days) }
    var allDay by remember { mutableStateOf(initial.allDay) }
    var start by remember { mutableStateOf(if (initial.allDay) LocalTime.of(21, 0) else initial.start) }
    var end by remember { mutableStateOf(if (initial.allDay) LocalTime.of(7, 0) else initial.end) }
    var picking by remember { mutableStateOf<Boolean?>(null) } // true: inicio, false: fin

    val problem = when {
        days.isEmpty() -> "Elige al menos un día."
        !allDay && start == end -> "La hora de fin debe ser distinta de la de inicio."
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Nuevo horario sin internet" else "Editar horario") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Días en que empieza", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    WEEK.forEach { day ->
                        DayToggle(day, selected = day in days) { days = if (day in days) days - day else days + day }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    PRESETS.forEach { (label, preset) -> TextButton(onClick = { days = preset }) { Text(label) } }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Todo el día", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = allDay, onCheckedChange = { allDay = it })
                }
                if (!allDay) {
                    TimeRow("Desde", formatHour(start)) { picking = true }
                    TimeRow("Hasta", formatHour(end) + if (end < start) " (día siguiente)" else "") { picking = false }
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val s = if (allDay) LocalTime.MIDNIGHT else start
                    val e = if (allDay) LocalTime.MIDNIGHT else end
                    onConfirm(initial.copy(days = days, start = s, end = e))
                },
                enabled = problem == null,
            ) { Text("Aceptar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )

    picking?.let { isStart ->
        TimeDialog(
            title = if (isStart) "Desde" else "Hasta",
            initial = if (isStart) start else end,
            onDismiss = { picking = null },
            onConfirm = { time ->
                if (isStart) start = time else end = time
                picking = null
            },
        )
    }
}

@Composable
private fun DayToggle(day: DayOfWeek, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (selected) colors.primary else colors.surfaceVariant,
        contentColor = if (selected) colors.onPrimary else colors.onSurfaceVariant,
        modifier = Modifier
            .size(36.dp)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .semantics {
                contentDescription = day.spanishName()
                this.selected = selected
            },
    ) {
        Box(contentAlignment = Alignment.Center) { Text(day.letter(), style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun TimeRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(title: String, initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("Aceptar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
