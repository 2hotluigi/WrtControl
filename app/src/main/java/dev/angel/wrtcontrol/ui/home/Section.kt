package dev.angel.wrtcontrol.ui.home

import androidx.annotation.StringRes
import dev.angel.wrtcontrol.R

/** Las secciones de la app: tres pestañas y Seguridad, en el botón redondo aparte. */
enum class Section(@param:StringRes val label: Int, val icon: Int) {
    Overview(R.string.home_tab_overview, R.drawable.ic_home),
    Devices(R.string.home_tab_devices, R.drawable.ic_devices),
    Family(R.string.home_tab_family, R.drawable.ic_family),
    Security(R.string.home_tab_security, R.drawable.ic_shield),
}
