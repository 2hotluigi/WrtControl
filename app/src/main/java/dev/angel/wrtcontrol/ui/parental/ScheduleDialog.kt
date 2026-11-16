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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.parental.Schedule
import dev.angel.wrtcontrol.text
import java.time.DayOfWeek
import java.time.LocalTime

private val PRESETS = listOf(
    R.string.parental_preset_weekdays to setOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
    ),
    R.string.parental_preset_sun_thu to setOf(
        DayOfWeek.SUNDAY,
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
    ),
    R.string.parental_preset_weekend to setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
    R.string.parental_preset_every_day to WEEK.toSet(),
)

@Composable
fun ScheduleDialog(initial: Schedule, isNew: Boolean, onDismiss: () -> Unit, onConfirm: (Schedule) -> Unit) {
    var days by remember { mutableStateOf(initial.days) }
    var allDay by remember { mutableStateOf(initial.allDay) }
    var start by remember { mutableStateOf(if (initial.allDay) LocalTime.of(21, 0) else initial.start) }
    var end by remember { mutableStateOf(if (initial.allDay) LocalTime.of(7, 0) else initial.end) }
    var picking by remember { mutableStateOf<Boolean?>(null) } // true: inicio, false: fin

    val problem = when {
        days.isEmpty() -> stringResource(R.string.parental_schedule_no_days)
        !allDay && start == end -> stringResource(R.string.parental_schedule_same_time)
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.parental_schedule_new_title else R.string.parental_schedule_edit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.parental_schedule_start_days), style = MaterialTheme.typography.labelLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    WEEK.forEach { day ->
                        DayToggle(day, selected = day in days) { days = if (day in days) days - day else days + day }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    PRESETS.forEach { (label, preset) -> TextButton(onClick = { days = preset }) { Text(stringResource(label)) } }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.parental_schedule_all_day),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = allDay, onCheckedChange = { allDay = it })
                }
                if (!allDay) {
                    TimeRow(stringResource(R.string.parental_schedule_from), formatHour(start)) { picking = true }
                    TimeRow(
                        stringResource(R.string.parental_schedule_until),
                        if (end < start) stringResource(R.string.parental_schedule_next_day, formatHour(end)) else formatHour(end),
                    ) { picking = false }
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
            ) { Text(stringResource(R.string.parental_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.parental_cancel)) } },
    )

    picking?.let { isStart ->
        TimeDialog(
            title = stringResource(if (isStart) R.string.parental_schedule_from else R.string.parental_schedule_until),
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
    val name = day.dayName().text()
    Surface(
        shape = CircleShape,
        color = if (selected) colors.primary else colors.surfaceVariant,
        contentColor = if (selected) colors.onPrimary else colors.onSurfaceVariant,
        modifier = Modifier
            .size(36.dp)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .semantics {
                contentDescription = name
                this.selected = selected
            },
    ) {
        Box(contentAlignment = Alignment.Center) { Text(day.letter().text(), style = MaterialTheme.typography.labelLarge) }
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
        confirmButton = { TextButton(onClick = { onConfirm(LocalTime.of(
            state.hour,
            state.minute,
        )) }) { Text(stringResource(R.string.parental_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.parental_cancel)) } },
    )
}
