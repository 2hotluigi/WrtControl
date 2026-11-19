package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.data.RouterAddress
import dev.angel.wrtcontrol.ubus.Credentials
import dev.angel.wrtcontrol.ubus.UbusClient
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * El router de prueba de la guía de inicio rápido (tools/vm/demo.sh): la imagen oficial de OpenWrt,
 * sin tocar, por un puerto del PC. La app entra con la contraseña que le puso el script y lee lo de
 * la primera pantalla. Solo lee. La CI lo pasa en el trabajo `demo` de vm.yml.
 *
 *   sh tools/vm/demo.sh --local
 *   WRTCONTROL_DEMO=127.0.0.1:8080 WRTCONTROL_DEMO_PASSWORD=$(cat ~/.cache/wrtcontrol-demo/password) \
 *     ./gradlew :app:testDebugUnitTest --tests '*DemoRouterTest*' --rerun
 */
class DemoRouterTest {

    private val address: String? = System.getenv("WRTCONTROL_DEMO")
    private val password: String = System.getenv("WRTCONTROL_DEMO_PASSWORD").orEmpty()

    @Test
    fun `la app entra en el router de prueba y lo entiende`(): Unit = runBlocking {
        assumeTrue("Define WRTCONTROL_DEMO para probar contra el router de prueba", address != null)
        val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val api = OpenWrtApi(UbusClient(RouterAddress.endpoint(address!!), http, Credentials("root", password)))

        val board = api.board()
        assertEquals("x86/64", board.release.target)
        val capabilities = api.capabilities(board, api.systemInfo())
        assertTrue("sin el ACL de LuCI", capabilities.acl.canWrite("network"))

        assertEquals("lan", api.homeLayout().main)
        val interfaces = api.interfaces()
        assertTrue("sin salida a internet: $interfaces", interfaces.any { it.up && it.hasDefaultRoute })
        assertTrue("sin la MAC de la red de casa", !api.routerId(board, interfaces).endsWith("|"))
        api.devices(interfaces)
    }
}
