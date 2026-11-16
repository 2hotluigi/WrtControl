package dev.angel.wrtcontrol.ui.addons

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.angel.wrtcontrol.Txt
import dev.angel.wrtcontrol.addons.IpFamily
import dev.angel.wrtcontrol.addons.ReachabilityAdvice
import dev.angel.wrtcontrol.addons.RouterReachContext
import dev.angel.wrtcontrol.addons.adviseReachability
import dev.angel.wrtcontrol.addons.checkFromOutside
import dev.angel.wrtcontrol.data.RouterRepository
import dev.angel.wrtcontrol.ui.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** La prueba "¿se llega desde fuera?". */
sealed interface OutsideCheck {
    data object Running : OutsideCheck

    data class Done(val advice: ReachabilityAdvice) : OutsideCheck

    data class Failed(val message: Txt) : OutsideCheck
}

/**
 * Saludo al router desde los datos móviles, por IPv6 y por IPv4, con la clave de este teléfono.
 * No toca el router: solo comprueba lo que hará la app cuando esté fuera.
 */
class OutsideCheckViewModel(private val repo: RouterRepository) : ViewModel() {

    /** Resultado de la última prueba (null: no se ha hecho o ya no vale). */
    var check by mutableStateOf<OutsideCheck?>(null)

    /** Hay con qué probar: la librería del túnel y el servicio de red de Android. */
    val available: Boolean get() = repo.outside != null

    // La librería de Go (gomobile) lanza java.lang.Exception a secas.
    @Suppress("TooGenericExceptionCaught")
    fun run(data: AddonsData) {
        val outside = repo.outside
        val tunnel = data.tunnel?.takeIf { data.phoneHasAccess }
        if (outside == null || tunnel == null || check == OutsideCheck.Running) return
        check = OutsideCheck.Running
        val s = data.state
        viewModelScope.launch {
            check = try {
                val report = checkFromOutside(outside, tunnel, s.ddnsDomain, s.publicAddresses)
                val ddns = s.ownDdns.takeIf { it.isNotEmpty() }?.map { if (it.ipv6) IpFamily.V6 else IpFamily.V4 }?.toSet()
                val router = RouterReachContext(
                    port = tunnel.port,
                    privateWanIpv4 = s.internetIpv4.takeIf { s.behindNat },
                    ddnsFamilies = ddns,
                    publicAddresses = s.publicAddresses,
                )
                OutsideCheck.Done(adviseReachability(report, router))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("WrtControl", "Falló la prueba desde fuera", e)
                OutsideCheck.Failed(e.userMessage())
            }
        }
    }
}
