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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import dev.angel.wrtcontrol.openwrt.WifiCountry
import dev.angel.wrtcontrol.openwrt.WifiState
import dev.angel.wrtcontrol.openwrt.openWrtSupportNotice
import dev.angel.wrtcontrol.openwrt.remembered
import dev.angel.wrtcontrol.text
import dev.angel.wrtcontrol.txt
import dev.angel.wrtcontrol.ui.ApplyingBanner
import dev.angel.wrtcontrol.ui.ChangePreview
import dev.angel.wrtcontrol.ui.ChangePreviewHost
import dev.angel.wrtcontrol.ui.ChangeProgress
import dev.angel.wrtcontrol.ui.ChangeRunner
import dev.angel.wrtcontrol.ui.ErrorBanner
import dev.angel.wrtcontrol.ui.InfoCard
import dev.angel.wrtcontrol.ui.InfoRow
import dev.angel.wrtcontrol.ui.LocalAppLocked
import dev.angel.wrtcontrol.ui.LocalRepository
import dev.angel.wrtcontrol.ui.NoticeCard
import dev.angel.wrtcontrol.ui.Proposals
import dev.angel.wrtcontrol.ui.home.CapabilitiesCard
import dev.angel.wrtcontrol.ui.home.CountrySection
import dev.angel.wrtcontrol.ui.home.countryPreview
import dev.angel.wrtcontrol.ui.home.phoneOnWifi
import dev.angel.wrtcontrol.ui.home.suggestedCountry
import dev.angel.wrtcontrol.ui.parental.PasswordDialog
import dev.angel.wrtcontrol.ui.parental.phoneAddresses
import dev.angel.wrtcontrol.ui.passwordPreview
import dev.angel.wrtcontrol.ui.watchAsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/*
 * La primera vez que este teléfono entra en un router, antes de la app: qué router es, si está
 * protegido, en qué país está su Wi-Fi (si no lo tiene puesto), qué puede hacer la app con él y
 * qué va a cambiar en él. Nada de esto toca el router salvo poner la contraseña o el país, si
 * faltan y el usuario quiere.
 */

/** Los pasos, en orden. */
enum class SetupStep(@StringRes val title: Int) {
    Router(R.string.setup_router_title),
    Password(R.string.setup_password_title),

    /** Solo si el router tiene Wi-Fi sin país: sin él, usa los canales y la potencia más limitados. */
    Country(R.string.setup_country_title),
    Features(R.string.setup_features_title),
    Changes(R.string.setup_changes_title),
}

/** Los pasos que tocan: el del país, solo si hace falta. */
fun setupSteps(askCountry: Boolean): List<SetupStep> = SetupStep.entries.filter { it != SetupStep.Country || askCountry }

/** El modelo y la versión casi nunca cambian. */
private val SETUP_REFRESH = 1.minutes

/** El Wi-Fi y los equipos: para saber si falta el país y si este teléfono va por ese Wi-Fi. */
private val SETUP_WIFI_REFRESH = 15.seconds

class SetupViewModel(private val repo: RouterRepository) : ViewModel() {
    private val changes = ChangeRunner(viewModelScope)

    val progress: ChangeProgress? get() = changes.current

    val proposals: Proposals get() = changes

    var error: Txt?
        get() = changes.error
        set(value) {
            changes.error = value
        }

    /** Antes se enseña qué supone cambiarla (ver [passwordPreview]). */
    fun setPassword(password: String, onDone: () -> Unit) = changes.propose(passwordPreview()) {
        changes.run(txt(R.string.parental_busy_password), onSuccess = onDone) { repo.changePassword(password) }
    }

    /** Los países que conoce el router: null mientras se leen, vacío si no deja leerlos. */
    var countries by mutableStateOf<List<WifiCountry>?>(null)
        private set

    init {
        viewModelScope.launch {
            countries = try {
                repo.use { it.wifi.countries() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /** Pone el país [code] al Wi-Fi; antes enseña [preview] si el cambio reinicia un Wi-Fi que emite. */
    fun setCountry(code: String, phoneOnIt: Boolean, preview: ChangePreview) = changes.propose(preview) {
        changes.run(txt(R.string.wifi_country_busy), onFinished = { repo.monitor.wifi.refresh() }) {
            repo.use { it.wifi.setCountry(code, phoneOnIt) }
        }
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
    val wifi by repo.monitor.wifi.watchAsState(SETUP_WIFI_REFRESH)
    val devices by repo.monitor.devices.watchAsState(SETUP_WIFI_REFRESH)
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(SetupStep.Router) }
    // Una vez que se pide el país, el paso se queda aunque ya esté puesto (si no, desaparecería al ponerlo).
    var askCountry by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(wifi.data) {
        if (wifi.data?.let { it.radios.isNotEmpty() && it.radios.any { r -> r.country == null } } == true) askCountry = true
    }
    var settingPassword by remember { mutableStateOf(false) }
    val locked = LocalAppLocked.current
    val steps = setupSteps(askCountry)

    SetupContent(
        step = step,
        steps = steps,
        emptyPassword = connection.emptyPassword,
        board = board.data,
        capabilities = capabilities.data,
        problem = board.error?.text() ?: capabilities.error?.text(),
        onSetPassword = { settingPassword = true },
        onBack = { step = steps[(steps.indexOf(step) - 1).coerceAtLeast(0)] },
        onNext = { step = steps[(steps.indexOf(step) + 1).coerceAtMost(steps.lastIndex)] },
        onFinish = vm::finish,
        country = wifi.data?.let { state ->
            CountryStepState(state, vm.countries, vm.countries?.let { suggestedCountry(context, it) }, vm.progress, vm.error?.text())
        },
        onCountry = { code ->
            wifi.data?.let { state ->
                val ips = phoneAddresses(context)
                val phoneMacs = devices.data.orEmpty().filter { d -> !d.remembered && d.ipv4.any { it in ips } }.map { it.mac }.toSet()
                vm.setCountry(code, phoneOnWifi(state, phoneMacs), countryPreview(state, code, phoneMacs))
            }
        },
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
    ChangePreviewHost(vm.proposals)
}

/**
 * [board] y [capabilities] null mientras no se han leído. [problem]: por qué no se pudo leer algo
 * del router, si pasó; la puesta en marcha se puede terminar igual.
 */
@Composable
fun SetupContent(
    step: SetupStep,
    steps: List<SetupStep> = SetupStep.entries,
    emptyPassword: Boolean,
    board: BoardInfo?,
    capabilities: Capabilities?,
    problem: String? = null,
    onSetPassword: () -> Unit = {},
    onBack: () -> Unit = {},
    onNext: () -> Unit = {},
    onFinish: () -> Unit = {},
    country: CountryStepState? = null,
    onCountry: (String) -> Unit = {},
) {
    val last = step == steps.last()
    val position = steps.indexOf(step).coerceAtLeast(0)
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.setup_step, position + 1, steps.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (!last) {
                    TextButton(onClick = onFinish, modifier = Modifier.testTag("setup-skip")) { Text(stringResource(R.string.setup_skip)) }
                }
            }
            LinearProgressIndicator(progress = { (position + 1f) / steps.size }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(step.title), style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (step) {
                    SetupStep.Router -> RouterStep(board, problem)
                    SetupStep.Password -> PasswordStep(emptyPassword, onSetPassword)
                    SetupStep.Country -> CountryStep(country, onCountry)
                    SetupStep.Features -> FeaturesStep(capabilities, problem)
                    SetupStep.Changes -> ChangesStep()
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (position > 0) TextButton(onClick = onBack) { Text(stringResource(R.string.setup_back)) }
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

/** Lo que necesita el paso del país: el Wi-Fi, los países del router, el del teléfono y cómo va el cambio. */
data class CountryStepState(
    val wifi: WifiState,
    val countries: List<WifiCountry>?,
    val suggested: String?,
    val progress: ChangeProgress? = null,
    val error: String? = null,
)

@Composable
private fun CountryStep(state: CountryStepState?, onPick: (String) -> Unit) {
    Body(R.string.setup_country_body)
    if (state == null) {
        Loading(null)
        return
    }
    CountrySection(state.wifi, state.countries, state.suggested, enabled = state.progress == null, onPick = onPick)
    if (state.error != null) ErrorBanner(state.error)
    if (state.progress != null) ApplyingBanner(state.progress)
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
