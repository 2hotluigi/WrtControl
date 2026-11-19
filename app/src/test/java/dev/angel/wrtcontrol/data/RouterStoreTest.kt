package dev.angel.wrtcontrol.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Lo que se guarda por router en el teléfono (con el DataStore de verdad, en Robolectric). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RouterStoreTest {

    private val store = RouterStore(ApplicationProvider.getApplicationContext(), SecretBox())

    @Test
    fun `las funciones comprobadas se guardan por router y no se mezclan`() = runTest {
        val casa = "192.168.8.1|GL-MT6000|AA:BB:CC:00:00:01"
        val otro = "192.168.1.1|Router de ejemplo|AA:BB:CC:00:00:02"
        assertEquals(emptySet<String>(), store.verified(casa).first())

        store.addVerified(casa, "Parental")
        store.addVerified(casa, "Sqm")
        store.addVerified(casa, "Parental")
        store.addVerified(otro, "Upnp")

        assertEquals(setOf("Parental", "Sqm"), store.verified(casa).first())
        assertEquals(setOf("Upnp"), store.verified(otro).first())
        // La dirección se escribe como sea: es la misma.
        assertEquals(setOf("Parental", "Sqm"), store.verified(casa.uppercase()).first())
    }
}
