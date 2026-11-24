package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
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
import dev.angel.wrtcontrol.ui.design.GroupCard
import dev.angel.wrtcontrol.ui.design.GroupFooter
import dev.angel.wrtcontrol.ui.design.GroupRow
import dev.angel.wrtcontrol.ui.design.LocalPalette
import dev.angel.wrtcontrol.ui.design.SectionHeader

/**
 * Cada función de la app: si este router puede con ella, si hay que instalar algo o por qué no.
 * [verified]: las que se han visto funcionando aquí ("funciona aquí": la app las activó y comprobó,
 * o las vio en marcha; ver RouterRepository.markVerified). Las demás que puede, "sin probar aquí",
 * con si la versión de OpenWrt está probada. null: no se dice (en la puesta en marcha).
 */
@Composable
fun CapabilitiesCard(capabilities: Capabilities, verified: Set<Feature>? = null) {
    Column {
        SectionHeader(stringResource(R.string.capability_title))
        GroupCard {
            Feature.entries.forEachIndexed { i, feature ->
                FeatureRow(
                    feature,
                    capabilities.availability(feature),
                    verified?.let { feature in it },
                    capabilities.version,
                    divider = i < Feature.entries.lastIndex,
                )
            }
        }
        GroupFooter(
            listOfNotNull(
                wifiSummary(capabilities.radios).text(),
                stringResource(R.string.capability_low_memory).takeIf { capabilities.lowMemory },
            ).joinToString("\n\n"),
            Modifier.padding(top = 6.dp),
        )
    }
}

/** [verified]: si ya funciona en este router (null: no se sabe). [version]: la de OpenWrt del router. */
@Composable
private fun FeatureRow(feature: Feature, availability: Availability, verified: Boolean?, version: String, divider: Boolean) {
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
    val palette = LocalPalette.current
    GroupRow(
        feature.title.text(),
        value = label,
        valueColor = when (ok) {
            true -> palette.good
            false -> palette.bad
            null -> palette.secondary
        },
        supporting = detail,
        supportingLines = 4,
        labelLines = 2,
        divider = divider,
        modifier = Modifier.testTag("capability-${feature.name}"),
    )
}
