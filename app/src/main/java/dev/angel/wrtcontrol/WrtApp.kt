package dev.angel.wrtcontrol

import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import dev.angel.wrtcontrol.data.AppSettings
import dev.angel.wrtcontrol.data.OkHttpDdns
import dev.angel.wrtcontrol.data.RouterRepository
import dev.angel.wrtcontrol.data.RouterStore
import dev.angel.wrtcontrol.data.RouterTransport
import dev.angel.wrtcontrol.data.SecretBox
import dev.angel.wrtcontrol.data.outsideNetwork
import dev.angel.wrtcontrol.data.tunnelEngine
import dev.angel.wrtcontrol.openwrt.VendorTable
import dev.angel.wrtcontrol.ui.userMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

    /** De qué fabricante es cada prefijo de MAC; va dentro de la app y se lee al usarla. */
    val vendors = VendorTable { assets.open("oui.txt.gz") }

    override fun onCreate() {
        super.onCreate()
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val transport = RouterTransport(
            tunnelEngine(),
            // En Wi-Fi o cable el router puede estar en esta red: primero se prueba su IP de casa.
            onLocalNetwork = {
                val caps = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
                caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            },
        )
        val http = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .connectionPool(transport.pool)
            .socketFactory(transport.socketFactory)
            .dns(transport.dns)
            .addInterceptor(transport.interceptor)
            .build()
        // Directo a internet, sin el túnel: para el proveedor del DNS dinámico.
        val direct = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
        repository = RouterRepository(
            appScope,
            RouterStore(this, SecretBox()),
            http,
            transport,
            OkHttpDdns(direct),
            outsideNetwork(connectivity),
            describe = { it.userMessage() },
            // Cuenta también el tiempo con el teléfono dormido: al volver, lo leído se sabe viejo.
            clock = SystemClock::elapsedRealtime,
        )
        // Con el túnel puesto: quitarlo si lleva un rato sin usarse o si ya no contesta el router.
        appScope.launch(Dispatchers.IO) {
            while (true) {
                delay(15_000)
                transport.stopIfIdle()
                transport.checkAlive()
            }
        }
        settings = AppSettings(this)
        appScope.launch { repository.restore() }
    }
}
