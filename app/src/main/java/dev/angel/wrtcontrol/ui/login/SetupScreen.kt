package dev.angel.wrtcontrol.ui.login

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.angel.wrtcontrol.R
import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.data.Connection
import dev.angel.wrtcontrol.data.RouterRepository
import dev.angel.wrtcontrol.openwrt.BoardInfo
import dev.angel.wrtcontrol.openwrt.Capabilities
import dev.angel.wrtcontrol.openwrt.openWrtSupportNotice
import dev.angel.wrtcontrol.text
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ui.ChangeProgress
import dev.angel.wrtcontrol.ui.ChangeRunner
import dev.angel.wrtcontrol.ui.InfoCard
import dev.angel.wrtcontrol.ui.InfoRow
import dev.angel.wrtcontrol.ui.LocalAppLocked
import dev.angel.wrtcontrol.ui.LocalRepository
import dev.angel.wrtcontrol.ui.NoticeCard
import dev.angel.wrtcontrol.ui.home.CapabilitiesCard
import dev.angel.wrtcontrol.ui.parental.PasswordDialog
import dev.angel.wrtcontrol.ui.watchAsState
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes

/*
 * La primera vez que este teléfono entra en un router, antes de la app: qué router es, si está
 * protegido, qué puede hacer la app con él y qué va a cambiar en él. Nada de esto toca el router
 * salvo poner la contraseña, si no tenía y el usuario quiere.
 */

/** Los pasos, en orden. */
enum class SetupStep(@StringRes val title: Int) {
    Router(R.string.setup_router_title),
    Password(R.string.setup_password_title),
    Features(R.string.setup_features_title),
    Changes(R.string.setup_changes_title),
}

/** El modelo y la versión casi nunca cambian. */
private val SETUP_REFRESH = 1.minutes

class SetupViewModel(private val repo: RouterRepository) : ViewModel() {
    private val changes = ChangeRunner(viewModelScope)

    val progress: ChangeProgress? get() = changes.current

    var error: Txt?
        get() = changes.error
        set(value) {
            changes.error = value
        }

    fun setPassword(password: String, onDone: () -> Unit) {
        changes.run(txt(R.string.parental_busy_password), onSuccess = onDone) { repo.changePassword(password) }
    }

    /** Hecha o saltada, la puesta en marcha no se vuelve a ofrecer para este router. */
    fun finish() {
        viewModelScope.launch { repo.finishSetup() }
    }
}

@Composable
fun SetupScreen(connection: Connection.SignedIn) {
    val repo = LocalRepository.current
    val vm: SetupViewModel = viewModel(key = "setup:${connection.address}") { SetupViewModel(repo) }
    val board by repo.monitor.board.watchAsState(SETUP_REFRESH)
    val capabilities by repo.monitor.capabilities.watchAsState(SETUP_REFRESH)
    var step by rememberSaveable { mutableIntStateOf(0) }
    var settingPassword by remember { mutableStateOf(false) }
    val locked = LocalAppLocked.current

    SetupContent(
        step = SetupStep.entries[step],
        emptyPassword = connection.emptyPassword,
        board = board.data,
        capabilities = capabilities.data,
        problem = board.error?.text() ?: capabilities.error?.text(),
        onSetPassword = { settingPassword = true },
        onBack = { step-- },
        onNext = { step++ },
        onFinish = vm::finish,
    )
    if (settingPassword && !locked) {
        PasswordDialog(
            busy = vm.progress != null,
            error = vm.error?.text(),
            onSubmit = { password -> vm.setPassword(password) { settingPassword = false } },
            onDismiss = {
                settingPassword = false
                vm.error = null
            },
        )
    }
}

/**
 * [board] y [capabilities] null mientras no se han leído. [problem]: por qué no se pudo leer algo
 * del router, si pasó; la puesta en marcha se puede terminar igual.
 */
@Composable
fun SetupContent(
    step: SetupStep,
    emptyPassword: Boolean,
    board: BoardInfo?,
    capabilities: Capabilities?,
    problem: String? = null,
    onSetPassword: () -> Unit = {},
    onBack: () -> Unit = {},
    onNext: () -> Unit = {},
    onFinish: () -> Unit = {},
) {
    val steps = SetupStep.entries
    val last = step == steps.last()
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.setup_step, step.ordinal + 1, steps.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (!last) {
                    TextButton(onClick = onFinish, modifier = Modifier.testTag("setup-skip")) { Text(stringResource(R.string.setup_skip)) }
                }
            }
            LinearProgressIndicator(progress = { (step.ordinal + 1f) / steps.size }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(step.title), style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (step) {
                    SetupStep.Router -> RouterStep(board, problem)
                    SetupStep.Password -> PasswordStep(emptyPassword, onSetPassword)
                    SetupStep.Features -> FeaturesStep(capabilities, problem)
                    SetupStep.Changes -> ChangesStep()
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (step.ordinal > 0) TextButton(onClick = onBack) { Text(stringResource(R.string.setup_back)) }
                Spacer(Modifier.weight(1f))
                Button(onClick = if (last) onFinish else onNext, modifier = Modifier.testTag("setup-next")) {
                    Text(stringResource(if (last) R.string.setup_finish else R.string.setup_next))
                }
            }
        }
    }
}

@Composable
private fun Body(@StringRes text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun Loading(problem: String?) {
    Text(
        problem ?: stringResource(R.string.setup_router_loading),
        style = MaterialTheme.typography.bodyMedium,
        color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RouterStep(board: BoardInfo?, problem: String?) {
    Body(R.string.setup_router_body)
    if (board == null) {
        Loading(problem)
        return
    }
    InfoCard(board.hostname) {
        InfoRow(stringResource(R.string.status_model), board.model)
        InfoRow(stringResource(R.string.setup_router_version), board.release.version)
    }
    val notice = openWrtSupportNotice(board.release.version)
    if (notice != null) {
        NoticeCard(stringResource(R.string.status_untested_openwrt), notice.text())
    } else {
        Text(
            stringResource(R.string.setup_router_tested),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PasswordStep(emptyPassword: Boolean, onSetPassword: () -> Unit) {
    if (!emptyPassword) {
        Body(R.string.setup_password_ok)
        return
    }
    Body(R.string.setup_password_missing)
    FilledTonalButton(onClick = onSetPassword, modifier = Modifier.testTag("setup-password")) {
        Text(stringResource(R.string.setup_password_set))
    }
    Text(
        stringResource(R.string.setup_password_later),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun FeaturesStep(capabilities: Capabilities?, problem: String?) {
    Body(R.string.setup_features_body)
    if (capabilities == null) Loading(problem) else CapabilitiesCard(capabilities)
}

@Composable
private fun ChangesStep() {
    Body(R.string.setup_changes_nothing)
    Body(R.string.setup_changes_when)
    Body(R.string.setup_changes_safe)
    Body(R.string.setup_changes_luci)
}
