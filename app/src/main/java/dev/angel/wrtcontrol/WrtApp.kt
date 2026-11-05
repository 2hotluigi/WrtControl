package dev.angel.wrtcontrol

import android.app.Application
import dev.angel.wrtcontrol.data.AppSettings
import dev.angel.wrtcontrol.data.RouterRepository
import dev.angel.wrtcontrol.data.RouterStore
import dev.angel.wrtcontrol.data.SecretBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class WrtApp : Application() {

    /** Ámbito de la app: vive mientras viva el proceso. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var repository: RouterRepository
        private set

    lateinit var settings: AppSettings
        private set

    override fun onCreate() {
        super.onCreate()
        val http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
        repository = RouterRepository(RouterStore(this, SecretBox()), http)
        settings = AppSettings(this)
        appScope.launch { repository.restore() }
    }
}
