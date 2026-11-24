package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.ui.design.BarAction
import dev.angel.wrtcontrol.ui.design.CenterTopBar
import dev.angel.wrtcontrol.ui.design.FloatingNavBar
import dev.angel.wrtcontrol.ui.design.LocalPalette
import dev.angel.wrtcontrol.ui.design.NavItem

/** Las de la píldora: todas menos Seguridad. */
private val TABS = listOf(Section.Overview, Section.Devices, Section.Family)

/**
 * El marco de la app: la barra de arriba y la navegación flotante. En una sección, [title] con
 * [subtitle] debajo y el engranaje de Ajustes ([onSettings]); en una página interior ([pageTitle]),
 * la vuelta atrás ([onBack]) y sin pestañas.
 */
@Composable
fun AppFrame(
    title: String,
    section: Section,
    onSection: (Section) -> Unit,
    subtitle: String? = null,
    subtitleAccent: Boolean = false,
    pageTitle: String? = null,
    onBack: () -> Unit = {},
    onSettings: () -> Unit = {},
    securityBadge: Boolean = false,
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = palette.background,
        topBar = {
            if (pageTitle != null) {
                CenterTopBar(pageTitle, start = BarAction(R.drawable.ic_back, stringResource(R.string.common_back), onBack, "more-back"))
            } else {
                CenterTopBar(
                    title,
                    subtitle = subtitle,
                    subtitleAccent = subtitleAccent,
                    end = BarAction(R.drawable.ic_settings, stringResource(R.string.settings_title), onSettings, "open-settings"),
                    subtitleTag = "router-link",
                )
            }
        },
        bottomBar = {
            if (pageTitle == null) {
                FloatingNavBar(
                    items = TABS.map { NavItem(it.icon, stringResource(it.label), "tab-${it.name}") },
                    selected = TABS.indexOf(section),
                    onSelect = { onSection(TABS[it]) },
                    round = NavItem(Section.Security.icon, stringResource(Section.Security.label), "tab-Security"),
                    roundSelected = section == Section.Security,
                    roundBadge = securityBadge,
                    onRound = { onSection(Section.Security) },
                )
            }
        },
    ) { padding -> Box(Modifier.padding(padding)) { content() } }
}
