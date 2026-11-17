package dev.angel.wrtcontrol.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.data.pairingLink
import dev.angel.wrtcontrol.data.qrModules
import dev.angel.wrtcontrol.ui.InfoCard
import dev.angel.wrtcontrol.ui.InfoRow

private val QR_SIZE = 240.dp
private val QR_MARGIN = 16.dp

/**
 * Añadir otro teléfono: un código QR con la dirección del router y el usuario, para que el otro
 * no tenga que escribirlos. La contraseña no va en el código. Si la dirección con la que se entró
 * no vale para un código (no es una IPv4 de casa), se dan los datos para escribirlos.
 */
@Composable
fun PairScreen(address: String, username: String) {
    val link = remember(address, username) { pairingLink(address, username) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val intro = if (link != null) R.string.pair_intro else R.string.pair_intro_manual
        Text(stringResource(intro), style = MaterialTheme.typography.bodyLarge)
        if (link != null) {
            QrCode(link.toUri(), stringResource(R.string.pair_qr_description), Modifier.align(Alignment.CenterHorizontally))
            Steps(R.string.pair_step_install, R.string.pair_step_wifi, R.string.pair_step_scan, R.string.pair_step_password)
        } else {
            Steps(R.string.pair_step_install, R.string.pair_step_wifi, R.string.pair_step_type, R.string.pair_step_password)
        }
        InfoCard(stringResource(R.string.pair_manual_title)) {
            InfoRow(stringResource(R.string.login_address), address)
            InfoRow(stringResource(R.string.login_username), username)
        }
        Text(
            stringResource(R.string.pair_password_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Steps(vararg steps: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { index, step ->
            Text(stringResource(R.string.pair_step, index + 1, stringResource(step)), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** El código QR de [text], negro sobre blanco también con el tema oscuro: así lo leen todas las cámaras. */
@Composable
fun QrCode(text: String, description: String, modifier: Modifier = Modifier) {
    val modules = remember(text) { qrModules(text) }
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(QR_MARGIN)
            .testTag("pair-qr")
            .semantics { contentDescription = description },
    ) {
        Canvas(Modifier.size(QR_SIZE)) {
            if (modules.isEmpty()) return@Canvas
            val cell = size.width / modules.size
            modules.forEachIndexed { y, row ->
                row.forEachIndexed { x, dark ->
                    // Un pelo más grande que la celda, para que no queden rayas claras entre módulos.
                    if (dark) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}
