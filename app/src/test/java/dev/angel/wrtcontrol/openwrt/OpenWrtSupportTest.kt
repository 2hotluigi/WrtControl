package dev.angel.wrtcontrol.openwrt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenWrtSupportTest {

    @Test
    fun `versiones probadas, que pueden funcionar y antiguas`() {
        for (v in listOf("24.10.0", "24.10.8", "25.12.6", "25.12-SNAPSHOT", "SNAPSHOT", "26.06.0-rc1")) {
            assertEquals(v, OpenWrtSupport.Tested, openWrtSupport(v))
            assertNull(v, openWrtSupportNotice(v))
        }
        for (v in listOf("22.03.7", "23.05.6", "23.05-SNAPSHOT")) {
            assertEquals(v, OpenWrtSupport.MayWork, openWrtSupport(v))
            assertTrue(v, openWrtSupportNotice(v)!!.contains("24.10 o superior"))
        }
        for (v in listOf("21.02.7", "19.07.10")) {
            assertEquals(v, OpenWrtSupport.Unsupported, openWrtSupport(v))
            assertTrue(v, openWrtSupportNotice(v)!!.contains("demasiado antiguo"))
        }
        assertEquals(OpenWrtSupport.Unknown, openWrtSupport(""))
        assertNull(openWrtSupportNotice("ImmortalWrt"))
    }
}
