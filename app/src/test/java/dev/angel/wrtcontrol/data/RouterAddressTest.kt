package dev.angel.wrtcontrol.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RouterAddressTest {
    private fun ep(address: String) = RouterAddress.endpoint(address).toString()

    @Test
    fun `acepta las formas habituales`() {
        assertEquals("http://192.168.1.1/ubus", ep("192.168.1.1"))
        assertEquals("http://192.168.1.1/ubus", ep("  192.168.1.1/ "))
        assertEquals("http://router.lan:8080/ubus", ep("router.lan:8080"))
        assertEquals("https://192.168.1.1/ubus", ep("https://192.168.1.1"))
        assertEquals("http://192.168.1.1/ubus", ep("http://192.168.1.1/cgi-bin/luci?x=1"))
    }

    @Test
    fun `acepta IPv6 con y sin corchetes`() {
        assertEquals("http://[2001:db8::1]/ubus", ep("2001:db8::1"))
        assertEquals("http://[2001:db8::1]:8080/ubus", ep("[2001:db8::1]:8080"))
    }

    @Test
    fun `rechaza direcciones vacias o invalidas`() {
        assertThrows(IllegalArgumentException::class.java) { ep("   ") }
        assertThrows(IllegalArgumentException::class.java) { ep("http://") }
    }
}
