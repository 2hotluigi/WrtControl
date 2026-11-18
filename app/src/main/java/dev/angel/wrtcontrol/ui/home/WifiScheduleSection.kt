package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import dev.angel.wrtcontrol.ui.NoticeCard
import dev.angel.wrtcontrol.ui.parental.ScheduleDialog
import dev.angel.wrtcontrol.ui.parental.formatSchedule
import dev.angel.wrtcontrol.ui.parental.formatWhen
import java.time.DayOfWeek
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
    Text(stringResource(R.string.wifi_schedule_title), style = MaterialTheme.typography.titleSmall)
    WifiHint(stringResource(R.string.wifi_schedule_help))
    if (schedule == null) {
        if (unavailable) WifiHint(stringResource(R.string.wifi_schedule_denied))
        return
    }
    var editing by remember { mutableStateOf<Schedule?>(null) }
    val list = schedule.schedules
    val now = schedule.now
    val offNow = wifiOffWindow(list, now)
    if (offNow != null && state.someRadioDown) {
        NoticeCard(
            stringResource(R.string.wifi_schedule_off_now_title),
            stringResource(R.string.wifi_schedule_off_now_body, formatWhen(offNow.end, now).text()),
        )
        OutlinedButton(onClick = onTurnOn, enabled = enabled, modifier = Modifier.testTag("wifi-schedule-on")) {
            Text(stringResource(R.string.wifi_schedule_turn_on))
        }
    } else {
        nextWifiOff(list, now)?.let { WifiHint(stringResource(R.string.wifi_schedule_next, formatWhen(it, now).text())) }
    }
    list.forEach { s ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { editing = s }.testTag("wifi-schedule-${s.id}"),
        ) {
            Text(
                formatSchedule(s).text(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp),
            )
            IconButton(onClick = { onChange(list.filter { it.id != s.id }) }, enabled = enabled) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.wifi_schedule_remove))
            }
        }
    }
    TextButton(
        onClick = { editing = newSchedule(((list.maxOfOrNull { it.id.toIntOrNull() ?: 0 } ?: -1) + 1).toString()) },
        enabled = enabled,
        modifier = Modifier.testTag("wifi-schedule-add"),
    ) {
        Icon(painterResource(R.drawable.ic_add), contentDescription = null)
        Text(stringResource(R.string.wifi_schedule_add), modifier = Modifier.padding(start = 8.dp))
    }

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
