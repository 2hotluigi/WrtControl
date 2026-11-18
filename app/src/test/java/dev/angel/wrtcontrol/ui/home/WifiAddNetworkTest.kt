package dev.angel.wrtcontrol.ui.home

import dev.angel.wrtcontrol.openwrt.WifiEdit
import dev.angel.wrtcontrol.openwrt.WifiSecurity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiAddNetworkTest {

    private val before = WifiEdit("Casa", "clave-de-casa", WifiSecurity.Wpa2Wpa3, setOf("radio0", "radio1"))

    @Test
    fun `se ofrece guardar la red si cambia como se entra`() {
        assertTrue(offersNewNetwork(before, before.copy(ssid = "Casa nueva")))
        assertTrue(offersNewNetwork(before, before.copy(key = "otra-clave-larga")))
        assertTrue(offersNewNetwork(before, before.copy(security = WifiSecurity.Wpa3)))
    }

    @Test
    fun `no se ofrece si solo cambian las bandas o se apaga`() {
        assertFalse(offersNewNetwork(before, before.copy(radios = setOf("radio1"))))
        assertFalse(offersNewNetwork(before, before.copy(ssid = "Casa nueva", radios = emptySet())))
        assertFalse(offersNewNetwork(before, before))
    }
}
