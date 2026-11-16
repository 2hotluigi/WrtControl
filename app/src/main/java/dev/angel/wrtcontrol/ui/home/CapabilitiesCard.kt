package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.openwrt.Availability
import dev.angel.wrtcontrol.openwrt.Capabilities
import dev.angel.wrtcontrol.openwrt.Feature
import dev.angel.wrtcontrol.openwrt.availability
import dev.angel.wrtcontrol.openwrt.wifiSummary
import dev.angel.wrtcontrol.text
import dev.angel.wrtcontrol.ui.InfoCard
import dev.angel.wrtcontrol.ui.StatusPill

/** Cada función de la app: si este router puede con ella, si hay que instalar algo o por qué no. */
@Composable
fun CapabilitiesCard(capabilities: Capabilities) {
    InfoCard(stringResource(R.string.capability_title)) {
        Feature.entries.forEach { FeatureRow(it, capabilities.availability(it)) }
        Text(
            wifiSummary(capabilities.radios).text(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (capabilities.lowMemory) {
            Text(
                stringResource(R.string.capability_low_memory),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FeatureRow(feature: Feature, availability: Availability) {
    val (label, ok) = when (availability) {
        Availability.Ready -> stringResource(R.string.capability_ready) to true
        is Availability.Installable -> stringResource(R.string.capability_installable) to null
        is Availability.Unsupported -> stringResource(R.string.capability_unsupported) to false
    }
    val detail = when (availability) {
        Availability.Ready -> null
        is Availability.Installable -> stringResource(R.string.capability_installable_detail, availability.packages.joinToString())
        is Availability.Unsupported -> availability.reason.text()
    }
    Column(Modifier.fillMaxWidth().testTag("capability-${feature.name}"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(feature.title.text(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            StatusPill(label, ok)
        }
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
