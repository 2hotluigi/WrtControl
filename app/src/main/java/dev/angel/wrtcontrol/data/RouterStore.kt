package dev.angel.wrtcontrol.data

import android.content.Context
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

    /** Olvida la contraseña pero conserva dirección y usuario para rellenar el formulario. */
    suspend fun forgetPassword() {
        context.routerDataStore.edit { it.remove(PASSWORD) }
    }

    private companion object {
        val ADDRESS = stringPreferencesKey("address")
        val USERNAME = stringPreferencesKey("username")
        val PASSWORD = stringPreferencesKey("password_encrypted")
    }
}
