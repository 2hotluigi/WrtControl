package dev.angel.wrtcontrol.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.angel.wrtcontrol.BuildConfig
import dev.angel.wrtcontrol.data.Connection
import dev.angel.wrtcontrol.data.RouterRepository
import dev.angel.wrtcontrol.ui.ErrorBanner
import dev.angel.wrtcontrol.ui.LocalRepository
import dev.angel.wrtcontrol.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class LoginViewModel(private val repo: RouterRepository, initial: Connection.SignedOut) : ViewModel() {
    var address by mutableStateOf(initial.lastAddress ?: BuildConfig.DEFAULT_ROUTER)
    var username by mutableStateOf(initial.lastUsername ?: "root")
    var password by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun connect() {
        if (busy) return
        busy = true
        error = null
        viewModelScope.launch {
            try {
                repo.connect(address, username.trim(), password)
                password = "" // queda cifrada en RouterStore; no hace falta en memoria
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage()
            } finally {
                busy = false
            }
        }
    }
}

@Composable
fun LoginScreen(state: Connection.SignedOut) {
    val repo = LocalRepository.current
    val vm: LoginViewModel = viewModel { LoginViewModel(repo, state) }

    Surface(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Conectar al router", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Usa la dirección del router OpenWrt (22.03 o superior) en tu red y sus credenciales de LuCI.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.notice?.let { ErrorBanner(it) }

                OutlinedTextField(
                    value = vm.address,
                    onValueChange = { vm.address = it },
                    label = { Text("Dirección del router") },
                    placeholder = { Text("192.168.1.1") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag("address"),
                )
                OutlinedTextField(
                    value = vm.username,
                    onValueChange = { vm.username = it },
                    label = { Text("Usuario") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag("username"),
                )
                OutlinedTextField(
                    value = vm.password,
                    onValueChange = { vm.password = it },
                    label = { Text("Contraseña") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { vm.connect() }),
                    modifier = Modifier.fillMaxWidth().testTag("password"),
                )
                vm.error?.let { ErrorBanner(it) }
                Button(
                    onClick = vm::connect,
                    enabled = !vm.busy,
                    modifier = Modifier.fillMaxWidth().testTag("connect"),
                ) {
                    if (vm.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Conectar")
                }
            }
        }
    }
}
