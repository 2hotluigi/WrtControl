package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.openwrt.MAX_LABEL_LENGTH
import dev.angel.wrtcontrol.parental.Profile
import dev.angel.wrtcontrol.text

/**
 * Los diálogos de la ficha: el nombre y de quién es. [onSave] recibe solo lo que cambia: el nombre
 * (vacío: el que dice el aparato) o la persona ("": nadie).
 */
@Composable
internal fun EditDialogs(
    label: String,
    profiles: List<Profile>,
    owner: String,
    editingName: Boolean,
    choosingOwner: Boolean,
    onClose: () -> Unit,
    onSave: (DeviceChange) -> Unit,
) {
    if (editingName) {
        NameDialog(label, onDismiss = onClose) { name ->
            onClose()
            onSave(DeviceChange(label = name ?: "", fixedIp = null))
        }
    }
    if (choosingOwner) {
        OwnerDialog(profiles, owner, onDismiss = onClose) { id ->
            onClose()
            if (id != owner) onSave(DeviceChange(label = null, fixedIp = null, owner = id))
        }
    }
}

/** El nombre que ve toda la casa; vacío, el que dice el aparato. */
@Composable
private fun NameDialog(initial: String, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var label by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.device_row_name)) },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it.take(MAX_LABEL_LENGTH) },
                label = { Text(stringResource(R.string.devices_edit_name_label)) },
                supportingText = { Text(stringResource(R.string.devices_edit_name_help)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("device-name"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(label.trim().ifEmpty { null }) }, modifier = Modifier.testTag("device-save")) {
                Text(stringResource(R.string.devices_edit_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.devices_edit_cancel)) } },
    )
}

/** De quién es: una de las personas o nadie ("" en [onPick]). */
@Composable
private fun OwnerDialog(profiles: List<Profile>, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.devices_edit_person_title)) },
        text = {
            Column {
                Text(stringResource(R.string.devices_edit_person_help), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                OwnerOption(stringResource(R.string.devices_edit_person_none), current.isEmpty()) { onPick("") }
                profiles.forEach { p -> OwnerOption(p.name, current == p.id) { onPick(p.id) } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.devices_edit_cancel)) } },
    )
}

@Composable
private fun OwnerOption(name: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(name, modifier = Modifier.padding(start = 8.dp))
    }
}
