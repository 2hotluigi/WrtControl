package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.openwrt.WifiSchedule
import dev.angel.wrtcontrol.openwrt.WifiState
import dev.angel.wrtcontrol.openwrt.nextWifiOff
import dev.angel.wrtcontrol.openwrt.wifiOffWindow
import dev.angel.wrtcontrol.parental.Schedule
import dev.angel.wrtcontrol.text
import dev.angel.wrtcontrol.ui.design.GroupCard
import dev.angel.wrtcontrol.ui.design.GroupRow
import dev.angel.wrtcontrol.ui.design.LocalPalette
import dev.angel.wrtcontrol.ui.design.RowIcon
import dev.angel.wrtcontrol.ui.design.SectionHeader
import dev.angel.wrtcontrol.ui.parental.ScheduleDialog
import dev.angel.wrtcontrol.ui.parental.formatSchedule
import dev.angel.wrtcontrol.ui.parental.formatWhen
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime

private val PROPOSED_OFF: LocalTime = LocalTime.parse("23:00")
private val PROPOSED_ON: LocalTime = LocalTime.parse("07:00")

/** Lo que se propone al añadir un horario: de domingo a jueves, de 23:00 a 07:00. */
private fun newSchedule(id: String) = Schedule(
    id = id,
    days = setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY),
    start = PROPOSED_OFF,
    end = PROPOSED_ON,
)

/**
 * "Horario": las franjas en las que el router apaga su Wi-Fi. [schedule]: null mientras se lee o
 * si el router no deja leerlo ([unavailable]). Cada cambio se guarda en el momento con [onChange].
 */
@Composable
fun ScheduleSection(
    state: WifiState,
    schedule: WifiSchedule?,
    unavailable: Boolean,
    enabled: Boolean,
    onChange: (List<Schedule>) -> Unit,
    onTurnOn: () -> Unit,
) {
    var editing by remember { mutableStateOf<Schedule?>(null) }
    Column {
        SectionHeader(stringResource(R.string.wifi_schedule_title))
        if (schedule == null) {
            WifiHint(stringResource(R.string.wifi_schedule_help))
            if (unavailable) WifiHint(stringResource(R.string.wifi_schedule_denied))
            return@Column
        }
        val list = schedule.schedules
        val now = schedule.now
        val offNow = wifiOffWindow(list, now)?.takeIf { state.someRadioDown }
        GroupCard {
            if (offNow != null) OffNowRows(offNow.end, now, enabled, onTurnOn)
            list.forEach { s -> ScheduleRow(s, enabled, onEdit = { editing = s }, onRemove = { onChange(list.filter { it.id != s.id }) }) }
            GroupRow(
                stringResource(R.string.wifi_schedule_add),
                leading = { RowIcon(R.drawable.ic_add, LocalPalette.current.accent) },
                labelColor = LocalPalette.current.accent,
                divider = false,
                onClick = { if (enabled) editing = newSchedule(nextId(list)) },
                modifier = Modifier.testTag("wifi-schedule-add"),
            )
        }
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (offNow == null) {
                nextWifiOff(list, now)?.let { WifiHint(stringResource(R.string.wifi_schedule_next, formatWhen(it, now).text())) }
            }
            WifiHint(stringResource(R.string.wifi_schedule_help))
        }
    }
    val list = schedule?.schedules ?: return
    editing?.let { s ->
        val isNew = list.none { it.id == s.id }
        ScheduleDialog(
            initial = s,
            isNew = isNew,
            newTitle = R.string.wifi_schedule_new_title,
            onDismiss = { editing = null },
            onConfirm = { updated ->
                val next = if (isNew) list + updated else list.map { if (it.id == updated.id) updated else it }
                editing = null
                // Uno que dejaría el Wi-Fi apagado a todas horas lo rechaza el router (WifiApi) con su motivo.
                onChange(next)
            },
        )
    }
}

/** El id del siguiente horario: uno más que el mayor. */
private fun nextId(list: List<Schedule>): String = ((list.maxOfOrNull { it.id.toIntOrNull() ?: 0 } ?: -1) + 1).toString()

/** El Wi-Fi está apagado por horario: hasta cuándo, y encenderlo ya. */
@Composable
private fun OffNowRows(end: LocalDateTime, now: LocalDateTime, enabled: Boolean, onTurnOn: () -> Unit) {
    GroupRow(
        stringResource(R.string.wifi_schedule_off_now_title),
        supporting = stringResource(R.string.wifi_schedule_off_now_body, formatWhen(end, now).text()),
        supportingLines = 3,
        leading = { RowIcon(R.drawable.ic_wifi, LocalPalette.current.warning) },
    )
    GroupRow(
        stringResource(R.string.wifi_schedule_turn_on),
        labelColor = LocalPalette.current.accent,
        onClick = { if (enabled) onTurnOn() },
        modifier = Modifier.testTag("wifi-schedule-on"),
    )
}

/** Una franja: tocarla la edita; la papelera la quita. */
@Composable
private fun ScheduleRow(schedule: Schedule, enabled: Boolean, onEdit: () -> Unit, onRemove: () -> Unit) {
    GroupRow(
        formatSchedule(schedule).text(),
        labelLines = 2,
        onClick = { if (enabled) onEdit() },
        modifier = Modifier.testTag("wifi-schedule-${schedule.id}"),
        trailing = {
            IconButton(onClick = onRemove, enabled = enabled) {
                Icon(
                    painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.wifi_schedule_remove),
                    tint = LocalPalette.current.secondary,
                )
            }
        },
    )
}
