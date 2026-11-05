package dev.angel.wrtcontrol.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** Ajustes de la app (no del router). */
class AppSettings(private val context: Context) {

    /** Pedir la huella o el PIN del teléfono al abrir la app. */
    val appLock: Flow<Boolean> = context.settingsDataStore.data.map { it[APP_LOCK] == true }

    suspend fun setAppLock(on: Boolean) {
        context.settingsDataStore.edit { it[APP_LOCK] = on }
    }

    /**
     * Desbloqueo de esta ejecución de la app. Vive en memoria del proceso a propósito: sobrevive
     * a girar la pantalla, pero si Android cierra la app en segundo plano se vuelve a pedir (con
     * rememberSaveable, Android lo restauraría y se saltaría el bloqueo).
     */
    var unlocked by mutableStateOf(false)

    /** Cuándo pasó la app a segundo plano (SystemClock.elapsedRealtime), o 0. */
    @Volatile
    var stoppedAt: Long = 0L

    private companion object {
        val APP_LOCK = booleanPreferencesKey("app_lock")
    }
}
