package dev.angel.wrtcontrol.ui

import dev.angel.wrtcontrol.openwrt.DeviceEdit
import dev.angel.wrtcontrol.openwrt.DevicesApi
import dev.angel.wrtcontrol.openwrt.IpAddress
import dev.angel.wrtcontrol.openwrt.NetInterface
import dev.angel.wrtcontrol.parental.ApplyPhase
import dev.angel.wrtcontrol.parental.ApplyProgress
import dev.angel.wrtcontrol.parental.ChangeRevertedException
import dev.angel.wrtcontrol.parental.FakeOpenWrt
import dev.angel.wrtcontrol.raw
import dev.angel.wrtcontrol.ubus.Credentials
import dev.angel.wrtcontrol.ubus.ok
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Un cambio se ve igual en todas las pantallas: qué se hace, por dónde va y cómo acaba. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChangeRunnerTest {

    private fun TestScope.runner() = ChangeRunner(this, describe = { raw(it.message.orEmpty()) }, log = { _, _ -> })

    @Test
    fun `mientras dura un cambio se sabe cual es y por donde va, y no empieza otro`() = runTest {
        val runner = runner()
        val gate = CompletableDeferred<Unit>()
        var finished = 0
        var succeeded = 0

        val started = runner.run(raw("Guardando"), onSuccess = { succeeded++ }, onFinished = { finished++ }) {
            currentCoroutineContext()[ApplyProgress]!!.report(ApplyPhase.Verifying)
            gate.await()
        }
        runCurrent()

        assertTrue(started)
        assertEquals(ChangeProgress(raw("Guardando"), ApplyPhase.Verifying), runner.current)
        assertEquals(raw("Guardando"), runner.busy)
        assertFalse("con uno en curso, otro no empieza", runner.run(raw("Otro")) {})

        gate.complete(Unit)
        runCurrent()
        assertNull(runner.current)
        assertNull(runner.error)
        assertEquals(1 to 1, succeeded to finished)
    }

    @Test
    fun `si falla queda el motivo en palabras y se vuelve a leer lo que haya cambiado`() = runTest {
        val runner = runner()
        var finished = 0
        var succeeded = 0

        runner.run(raw("Guardando"), onSuccess = { succeeded++ }, onFinished = { finished++ }) { error("no se pudo") }
        runCurrent()

        assertNull(runner.current)
        assertEquals(raw("no se pudo"), runner.error)
        assertEquals(0 to 1, succeeded to finished)
        // El siguiente cambio borra el error del anterior.
        runner.run(raw("Otra vez")) {}
        assertNull(runner.error)
    }

    private val warning = ChangePreview(raw("Bloquear la tele"), notes = listOf(raw("Se queda sin internet")))

    @Test
    fun `un cambio con algo que avisar espera a que el usuario lo acepte`() = runTest {
        val runner = runner()
        var done = 0

        runner.propose(warning) { runner.run(raw("Bloqueando")) { done++ } }
        runCurrent()
        assertEquals(warning, runner.proposal)
        assertEquals("todavía no se ha tocado el router", 0, done)

        runner.accept()
        runCurrent()
        assertNull(runner.proposal)
        assertEquals(1, done)
        // Aceptar dos veces no lo repite.
        runner.accept()
        runCurrent()
        assertEquals(1, done)
    }

    @Test
    fun `si el usuario lo deja, no se hace`() = runTest {
        val runner = runner()
        var done = 0

        runner.propose(warning) { done++ }
        runner.dismiss()
        runner.accept()

        assertNull(runner.proposal)
        assertEquals(0, done)
    }

    @Test
    fun `sin nada que avisar se hace ya, y con un cambio en curso no se propone otro`() = runTest {
        val runner = runner()
        val gate = CompletableDeferred<Unit>()
        var done = 0

        runner.propose(null) { done++ }
        runner.propose(ChangePreview(raw("Guardar la tele"))) { done++ }
        assertEquals(2, done)
        assertNull(runner.proposal)

        runner.run(raw("Guardando")) { gate.await() }
        runCurrent()
        runner.propose(warning) { done++ }
        assertNull(runner.proposal)
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, done)
    }

    private val interfaces = listOf(NetInterface("lan", up = true, proto = "static", ipv4 = listOf(IpAddress("192.168.1.1", 24))))

    private fun TestScope.devices(fake: FakeOpenWrt): DevicesApi {
        fake.clock = { testScheduler.currentTime }
        fake.section("cfg01", "dnsmasq", "domain" to "lan")
        return DevicesApi(fake.router.client(Credentials("root", ""), io = StandardTestDispatcher(testScheduler)))
    }

    private suspend fun phasesOf(block: suspend () -> Unit): List<ApplyPhase> {
        val phases = mutableListOf<ApplyPhase>()
        withContext(ApplyProgress { phases += it }) {
            try {
                block()
            } catch (_: ChangeRevertedException) {
                // lo que interesa aquí son las fases
            }
        }
        return phases
    }

    @Test
    fun `un cambio que funciona pasa por aplicar, comprobar y confirmar`() = runTest {
        val api = devices(FakeOpenWrt())
        val phases = phasesOf { api.save(DeviceEdit("AA:BB:CC:DD:EE:01", "Tele", null), interfaces) }
        assertEquals(listOf(ApplyPhase.Applying, ApplyPhase.Verifying, ApplyPhase.Confirming), phases)
    }

    @Test
    fun `uno que no funciona acaba deshaciendose, sin confirmar`() = runTest {
        val fake = FakeOpenWrt()
        val api = devices(fake)
        fake.intercept = { call -> if (call.name == "file.exec") ok("""{"code":1,"stdout":";; no servers could be reached\n"}""") else null }

        val phases = phasesOf { api.save(DeviceEdit("AA:BB:CC:DD:EE:01", "Tele", null), interfaces) }
        advanceUntilIdle()

        assertEquals(listOf(ApplyPhase.Applying, ApplyPhase.Verifying, ApplyPhase.Reverting), phases)
    }

    @Test
    fun `el ejecutor lleva las fases de un cambio de verdad hasta la pantalla`() = runTest {
        val api = devices(FakeOpenWrt())
        val seen = mutableListOf<ApplyPhase?>()
        val runner = runner()

        runner.run(raw("Guardando"), onFinished = { seen += runner.current?.phase }) {
            api.save(DeviceEdit("AA:BB:CC:DD:EE:01", "Tele", null), interfaces)
        }
        advanceUntilIdle()

        assertEquals("la última fase antes de terminar", listOf<ApplyPhase?>(ApplyPhase.Confirming), seen)
        assertNull(runner.current)
        assertNull(runner.error)
    }
}
