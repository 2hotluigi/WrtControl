package dev.angel.wrtcontrol

import android.app.Application
import dev.angel.wrtcontrol.data.AppSettings
import dev.angel.wrtcontrol.data.RouterRepository
import dev.angel.wrtcontrol.data.RouterStore
import dev.angel.wrtcontrol.data.RouterTransport
import dev.angel.wrtcontrol.data.tunnelEngine
import kotlinx.coroutines.delay
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
        val transport = RouterTransport(tunnelEngine())
        val http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .connectionPool(transport.pool)
            .socketFactory(transport.socketFactory)
            .dns(transport.dns)
            .addInterceptor(transport.interceptor)
            .build()
        repository = RouterRepository(RouterStore(this, SecretBox()), http, transport)
        // Con el túnel puesto: quitarlo al volver a casa o si lleva un rato sin usarse.
        appScope.launch(Dispatchers.IO) {
            while (true) {
                delay(15_000)
                transport.stopIfIdle()
                transport.checkDirect()
            }
        }
        settings = AppSettings(this)
        appScope.launch { repository.restore() }
    }
}
