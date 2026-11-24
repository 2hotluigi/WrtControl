package dev.angel.wrtcontrol.openwrt

import dev.angel.wrtcontrol.ubus.SessionAcl
import dev.angel.wrtcontrol.ubus.UbusCaller
import dev.angel.wrtcontrol.ubus.UbusException
import dev.angel.wrtcontrol.ubus.UbusJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterPowerTest {

    /** Lo que apuntan luci-mod-system-reboot y luci-mod-system-flash en 24.10 (lo que importa aquí). */
    private val luciAcl = UbusJson.decodeFromString<SessionAcl>(
        """{
          "ubus": {"file": ["exec", "read", "stat", "remove", "write"], "system": ["reboot", "validate_firmware_image"]},
          "file": {"/sbin/reboot": ["exec"], "/sbin/firstboot -r -y": ["exec"], "/sbin/sysupgrade --list-backup": ["exec"]}
        }""",
    )

    private fun caps(acl: SessionAcl) = Capabilities("24.10.4", "x86/64", 0, null, null, acl, null, emptyList())

    /** Un router que apunta lo que se le pide y contesta con [reply]. */
    private class Recorder(private val reply: (String, JsonObject) -> JsonElement?) : UbusCaller {
        val calls = mutableListOf<Pair<String, JsonObject>>()

        override suspend fun call(target: String, method: String, args: JsonObject): JsonElement? {
            calls += "$target.$method" to args
            return reply("$target.$method", args)
        }
    }

    private fun exec(code: Int) = UbusJson.parseToJsonElement("""{"code":$code}""")

    @Test
    fun `con el ACL de LuCI se puede reiniciar y restablecer`() {
        assertTrue(caps(luciAcl).canReboot)
        assertTrue(caps(luciAcl).canFactoryReset)
    }

    @Test
    fun `sin esos grupos no se ofrece`() {
        val readOnly = SessionAcl(ubus = mapOf("system" to setOf("board", "info"), "file" to setOf("exec")))
        assertFalse(caps(readOnly).canReboot)
        assertFalse(caps(readOnly).canFactoryReset)
        // El comando con otros parámetros no vale: rpcd compara la línea entera.
        val other = SessionAcl(ubus = mapOf("file" to setOf("exec")), file = mapOf("/sbin/firstboot" to setOf("exec")))
        assertFalse(caps(other).canFactoryReset)
    }

    @Test
    fun `reiniciar es system reboot`() = runTest {
        val router = Recorder { _, _ -> null }
        RouterPower(router).reboot()
        assertEquals(listOf("system.reboot"), router.calls.map { it.first })
    }

    @Test
    fun `restablecer lanza firstboot como LuCI`() = runTest {
        val router = Recorder { _, _ -> exec(0) }
        RouterPower(router).factoryReset()
        val (name, args) = router.calls.single()
        assertEquals("file.exec", name)
        assertEquals("/sbin/firstboot", args.getValue("command").jsonPrimitive.content)
        assertEquals(listOf("-r", "-y"), args.getValue("params").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `si el router se corta al reiniciarse, cuenta como empezado`() = runTest {
        val router = Recorder { _, _ -> throw UbusException.Network("corte") }
        RouterPower(router).factoryReset()
    }

    @Test
    fun `si el comando falla o no hay permiso, lo dice`() = runTest {
        val failed = runCatching { RouterPower(Recorder { _, _ -> exec(1) }).factoryReset() }.exceptionOrNull()
        assertTrue(failed is UbusException.Refused)
        val denied = runCatching {
            RouterPower(Recorder { _, _ -> throw UbusException.AccessDenied("file.exec") }).factoryReset()
        }.exceptionOrNull()
        assertTrue(denied is UbusException.AccessDenied)
    }
}
