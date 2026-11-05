package dev.angel.wrtcontrol.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.angel.wrtcontrol.data.AppSettings
import dev.angel.wrtcontrol.data.Connection
import dev.angel.wrtcontrol.data.RouterRepository
import dev.angel.wrtcontrol.ui.home.HomeScreen
import dev.angel.wrtcontrol.ui.login.LoginScreen

val LocalRepository = staticCompositionLocalOf<RouterRepository> { error("RouterRepository no proporcionado") }

@Composable
fun AppRoot(repository: RouterRepository, settings: AppSettings) {
    CompositionLocalProvider(LocalRepository provides repository, LocalAppSettings provides settings) {
        val connection by repository.state.collectAsStateWithLifecycle()
        when (val c = connection) {
            Connection.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is Connection.SignedOut -> LoginScreen(c)
            is Connection.SignedIn -> HomeScreen(c)
        }
    }
}
