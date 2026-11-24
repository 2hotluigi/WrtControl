package dev.angel.wrtcontrol.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.routerDataStore by preferencesDataStore(name = "router")

/** [routerId]: modelo y MAC de la LAN del router la última vez que se llegó a él (ver OpenWrtApi.routerId). */
data class SavedRouter(val address: String, val username: String, val password: String?, val routerId: String? = null)

/** Router configurado. La dirección y el usuario van en claro; la contraseña, cifrada. */
@Suppress("TooManyFunctions") // Un almacén: leer y guardar cada cosa, sin más lógica.
class RouterStore(private val context: Context, private val box: SecretBox) {

    suspend fun load(): SavedRouter? {
        val prefs = context.routerDataStore.data.first()
        val address = prefs[ADDRESS] ?: return null
        val username = prefs[USERNAME] ?: return null
        val password = prefs[PASSWORD]?.let(box::decrypt)
        return SavedRouter(address, username, password, prefs[ROUTER_ID])
    }

    suspend fun save(router: SavedRouter) {
        val encrypted = router.password?.let(box::encrypt)
        context.routerDataStore.edit { prefs ->
            // Otra dirección es (en principio) otro router: el identificador se vuelve a averiguar.
            if (prefs[ADDRESS] != router.address) prefs.remove(ROUTER_ID)
            prefs[ADDRESS] = router.address
            prefs[USERNAME] = router.username
            if (encrypted != null) prefs[PASSWORD] = encrypted else prefs.remove(PASSWORD)
            router.routerId?.let { prefs[ROUTER_ID] = it }
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

    /**
     * Las funciones que la app ya puso en marcha y comprobó en el router de [key] (nombres de
     * `openwrt.Feature`; ver RouterRepository.markVerified).
     */
    fun verified(key: String): Flow<Set<String>> =
        context.routerDataStore.data.map { it[verifiedKey(key)].orEmpty() }.distinctUntilChanged()

    suspend fun addVerified(key: String, feature: String) {
        context.routerDataStore.edit { prefs -> prefs[verifiedKey(key)] = prefs[verifiedKey(key)].orEmpty() + feature }
    }

    /** ¿Ya se hizo en este teléfono la puesta en marcha del router de [address]? */
    suspend fun setupDone(address: String): Boolean = context.routerDataStore.data.first()[setupKey(address)] == true

    suspend fun setSetupDone(address: String) {
        context.routerDataStore.edit { it[setupKey(address)] = true }
    }

    /**
     * El router de [address] se restableció: lo que se sabía de él ya no vale. Vuelve a ofrecerse
     * la puesta en marcha y se olvida qué funcionaba ([verifiedKey], ver [verified]).
     */
    suspend fun forgetAfterReset(address: String, verifiedKey: String) {
        context.routerDataStore.edit {
            it.remove(setupKey(address))
            it.remove(verifiedKey(verifiedKey))
        }
    }

    suspend fun saveRouterId(routerId: String) {
        context.routerDataStore.edit { it[ROUTER_ID] = routerId }
    }

    /**
     * Configuración del acceso remoto para el router [routerId] en [address] (cifrada: lleva una
     * clave privada). Por router y no solo por dirección: dos routers en 192.168.1.1 son dos claves.
     */
    suspend fun loadTunnel(address: String, routerId: String): String? =
        context.routerDataStore.data.first()[tunnelKey(address, routerId)]?.let(box::decrypt)

    suspend fun saveTunnel(address: String, routerId: String, json: String?) {
        val encrypted = json?.let(box::encrypt)
        val key = tunnelKey(address, routerId)
        context.routerDataStore.edit { prefs ->
            if (encrypted == null) prefs.remove(key) else prefs[key] = encrypted
        }
    }

    /** Olvida la contraseña pero conserva dirección y usuario para rellenar el formulario. */
    suspend fun forgetPassword() {
        context.routerDataStore.edit { it.remove(PASSWORD) }
    }

    private companion object {
        fun backupKey(key: String) = stringPreferencesKey("parental_backup:" + key.trim().lowercase())
        fun pendingKey(key: String) = booleanPreferencesKey("parental_backup_pending:" + key.trim().lowercase())
        fun tunnelKey(address: String, routerId: String) = stringPreferencesKey("tunnel:" + address.trim().lowercase() + "|" + routerId)
        fun verifiedKey(key: String) = stringSetPreferencesKey("verified:" + key.trim().lowercase())
        fun setupKey(address: String) = booleanPreferencesKey("setup_done:" + address.trim().lowercase())
        val ROUTER_ID = stringPreferencesKey("router_id")
        val ADDRESS = stringPreferencesKey("address")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD = stringPreferencesKey("password_encrypted")
    }
}
