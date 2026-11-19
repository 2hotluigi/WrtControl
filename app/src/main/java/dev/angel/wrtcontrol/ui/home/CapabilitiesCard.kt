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
import dev.angel.wrtcontrol.openwrt.notVerifiedDetail
import dev.angel.wrtcontrol.openwrt.wifiSummary
import dev.angel.wrtcontrol.text
import dev.angel.wrtcontrol.ui.InfoCard
import dev.angel.wrtcontrol.ui.StatusPill

/**
 * Cada función de la app: si este router puede con ella, si hay que instalar algo o por qué no.
 * [verified]: las que se han visto funcionando aquí ("funciona aquí": la app las activó y comprobó,
 * o las vio en marcha; ver RouterRepository.markVerified). Las demás que puede, "sin probar aquí",
 * con si la versión de OpenWrt está probada. null: no se dice (en la puesta en marcha).
 */
@Composable
fun CapabilitiesCard(capabilities: Capabilities, verified: Set<Feature>? = null) {
    InfoCard(stringResource(R.string.capability_title)) {
        Feature.entries.forEach { feature ->
            FeatureRow(feature, capabilities.availability(feature), verified?.let { feature in it }, capabilities.version)
        }
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

/** [verified]: si ya funciona en este router (null: no se sabe). [version]: la de OpenWrt del router. */
@Composable
private fun FeatureRow(feature: Feature, availability: Availability, verified: Boolean?, version: String) {
    val (label, ok) = when (availability) {
        Availability.Ready -> when (verified) {
            null -> stringResource(R.string.capability_ready) to true
            true -> stringResource(R.string.capability_verified) to true
            false -> stringResource(R.string.capability_not_verified) to null
        }
        is Availability.Installable -> stringResource(R.string.capability_installable) to null
        is Availability.Unsupported -> stringResource(R.string.capability_unsupported) to false
    }
    val detail = when (availability) {
        Availability.Ready -> if (verified == false) notVerifiedDetail(version).text() else null
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
