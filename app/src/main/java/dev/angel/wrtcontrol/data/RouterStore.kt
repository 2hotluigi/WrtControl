package dev.angel.wrtcontrol.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.routerDataStore by preferencesDataStore(name = "router")

data class SavedRouter(val address: String, val username: String, val password: String?)

/** Router configurado. La dirección y el usuario van en claro; la contraseña, cifrada. */
class RouterStore(private val context: Context, private val box: SecretBox) {

    suspend fun load(): SavedRouter? {
        val prefs = context.routerDataStore.data.first()
        val address = prefs[ADDRESS] ?: return null
        val username = prefs[USERNAME] ?: return null
        val password = prefs[PASSWORD]?.let(box::decrypt)
        return SavedRouter(address, username, password)
    }

    suspend fun save(router: SavedRouter) {
        val encrypted = router.password?.let(box::encrypt)
        context.routerDataStore.edit { prefs ->
            prefs[ADDRESS] = router.address
            prefs[USERNAME] = router.username
            if (encrypted != null) prefs[PASSWORD] = encrypted else prefs.remove(PASSWORD)
        }
    }

    /** Copia de los perfiles de control parental de un router (ver parental/Backup.kt). */
    suspend fun loadBackup(address: String): String? = context.routerDataStore.data.first()[backupKey(address)]

    suspend fun saveBackup(address: String, json: String?) {
        context.routerDataStore.edit { prefs ->
            if (json == null) prefs.remove(backupKey(address)) else prefs[backupKey(address)] = json
        }
    }

    suspend fun backupPending(key: String): Boolean = context.routerDataStore.data.first()[pendingKey(key)] == true

    suspend fun setBackupPending(key: String, pending: Boolean) {
        context.routerDataStore.edit { prefs -> if (pending) prefs[pendingKey(key)] = true else prefs.remove(pendingKey(key)) }
    }

    /** Configuración del acceso remoto para el router de [address] (cifrada, lleva una clave privada). */
    suspend fun loadTunnel(address: String): String? =
        context.routerDataStore.data.first()[tunnelKey(address)]?.let(box::decrypt)

    suspend fun saveTunnel(address: String, json: String?) {
        val encrypted = json?.let(box::encrypt)
        context.routerDataStore.edit { prefs ->
            if (encrypted == null) prefs.remove(tunnelKey(address)) else prefs[tunnelKey(address)] = encrypted
        }
    }

    /** Olvida la contraseña pero conserva dirección y usuario para rellenar el formulario. */
    suspend fun forgetPassword() {
        context.routerDataStore.edit { it.remove(PASSWORD) }
    }

    private companion object {
        fun backupKey(key: String) = stringPreferencesKey("parental_backup:" + key.trim().lowercase())
        fun pendingKey(key: String) = booleanPreferencesKey("parental_backup_pending:" + key.trim().lowercase())
        fun tunnelKey(address: String) = stringPreferencesKey("tunnel:" + address.trim().lowercase())
        val ADDRESS = stringPreferencesKey("address")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD = stringPreferencesKey("password_encrypted")
    }
}
