package dev.angel.wrtcontrol.data

import dev.angel.wrtcontrol.raw
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class FeedTest {

    /** Cuántas veces se ha leído del router. */
    private var reads = 0

    private fun TestScope.feed(load: suspend () -> String = { "dato ${++reads}" }) =
        Feed(backgroundScope, { raw("fallo: ${it.message}") }, { testScheduler.currentTime }, load)

    private fun TestScope.watching(feed: Feed<String>, every: kotlin.time.Duration, seen: MutableList<Loadable<String>>? = null) =
        backgroundScope.launch { feed.watch(every).collect { seen?.add(it) } }

    @Test
    fun `si nadie la mira no se lee`() = runTest {
        val feed = feed()
        advanceTimeBy(1.minutes)
        assertEquals(0, reads)
        assertTrue(feed.state.value.isLoading)
    }

    @Test
    fun `dos pantallas a la vez - una sola lectura por vuelta`() = runTest {
        val feed = feed()
        watching(feed, 5.seconds)
        watching(feed, 5.seconds)
        runCurrent()
        assertEquals(1, reads)
        advanceTimeBy(5.5.seconds)
        assertEquals(2, reads)
        assertEquals("dato 2", feed.state.value.data)
    }

    @Test
    fun `manda la que la quiere mas a menudo, y cuando se va se vuelve al ritmo de la otra`() = runTest {
        val feed = feed()
        watching(feed, 15.seconds)
        val fast = watching(feed, 5.seconds)
        advanceTimeBy(15.5.seconds) // 0, 5, 10 y 15 s
        assertEquals(4, reads)
        fast.cancel()
        advanceTimeBy(30.seconds) // 30 y 45 s
        assertEquals(6, reads)
    }

    @Test
    fun `al cambiar de pantalla se ve lo que ya se sabia y no se repite lo recien leido`() = runTest {
        val feed = feed()
        val first = watching(feed, 5.seconds)
        advanceTimeBy(1.seconds)
        first.cancel()
        advanceTimeBy(1.seconds)

        val seen = mutableListOf<Loadable<String>>()
        watching(feed, 10.seconds, seen)
        runCurrent()
        assertEquals(listOf("dato 1"), seen.map { it.data })
        advanceTimeBy(7.seconds) // han pasado 9 s desde la lectura: aún vale para quien la quiere cada 10
        assertEquals(1, reads)
        advanceTimeBy(1.5.seconds)
        assertEquals(2, reads)
    }

    @Test
    fun `al volver despues de un rato se enseña lo viejo y se vuelve a leer enseguida`() = runTest {
        val feed = feed()
        val first = watching(feed, 5.seconds)
        runCurrent()
        first.cancel()
        advanceTimeBy(10.minutes)
        assertEquals(1, reads)

        val seen = mutableListOf<Loadable<String>>()
        watching(feed, 5.seconds, seen)
        runCurrent()
        assertEquals(listOf("dato 1", "dato 2"), seen.map { it.data })
    }

    @Test
    fun `fresh - vale lo reciente y se pide lo que ya es viejo`() = runTest {
        val feed = feed()
        assertEquals("dato 1", feed.fresh())
        assertEquals("dato 1", feed.fresh(2.seconds))
        advanceTimeBy(3.seconds)
        assertEquals("dato 2", feed.fresh(2.seconds))
        assertEquals("dato 3", feed.fresh())
    }

    @Test
    fun `fresh - quien llega a media lectura la espera en vez de pedir otra`() = runTest {
        val feed = feed {
            delay(100)
            "dato ${++reads}"
        }
        val both = listOf(async { feed.fresh() }, async { feed.fresh() }).awaitAll()
        assertEquals(listOf("dato 1", "dato 1"), both)
        assertEquals(1, reads)
    }

    @Test
    fun `fresh - la lectura termina aunque se vaya quien la pidio`() = runTest {
        val feed = feed {
            delay(100)
            "dato ${++reads}"
        }
        val asker = launch { feed.fresh() }
        advanceTimeBy(50)
        asker.cancel()
        advanceTimeBy(100)
        assertEquals("dato 1", feed.state.value.data)
    }

    @Test
    fun `un fallo conserva lo ultimo bueno, lo explica y se reintenta a su ritmo`() = runTest {
        val feed = feed {
            if (++reads == 2) throw IOException("sin respuesta")
            "dato $reads"
        }
        watching(feed, 5.seconds)
        advanceTimeBy(5.5.seconds)
        assertEquals("dato 1", feed.state.value.data)
        assertEquals(raw("fallo: sin respuesta"), feed.state.value.error)
        advanceTimeBy(5.seconds)
        assertEquals(Loadable("dato 3"), feed.state.value)
    }

    @Test
    fun `fresh lanza el fallo de la lectura`() = runTest {
        val feed = feed { throw IOException("sin respuesta") }
        try {
            feed.fresh()
            fail("tenía que fallar")
        } catch (e: IOException) {
            assertEquals("sin respuesta", e.message)
        }
        assertEquals(raw("fallo: sin respuesta"), feed.state.value.error)
        assertNull(feed.state.value.data)
    }

    @Test
    fun `un tiempo agotado dentro de la lectura es un fallo mas - el sondeo sigue`() = runTest {
        val feed = feed {
            if (++reads == 1) withTimeout(50) { delay(1_000) }
            "dato $reads"
        }
        watching(feed, 5.seconds)
        advanceTimeBy(1.seconds)
        assertTrue(feed.state.value.error != null)
        advanceTimeBy(5.seconds)
        assertEquals(Loadable("dato 2"), feed.state.value)
    }

    @Test
    fun `refresh - se vuelve a leer ya, sin esperar a la vuelta`() = runTest {
        val feed = feed()
        watching(feed, 15.seconds)
        advanceTimeBy(1.seconds)
        feed.refresh()
        runCurrent()
        assertEquals("dato 2", feed.state.value.data)
        advanceTimeBy(14.seconds) // la siguiente se cuenta desde esta
        assertEquals(2, reads)
        advanceTimeBy(1.5.seconds)
        assertEquals(3, reads)
    }

    @Test
    fun `refresh a media lectura - lo que llega es de antes del cambio y se lee otra vez`() = runTest {
        var router = "antes"
        val feed = feed {
            val read = router
            delay(100)
            reads++
            read
        }
        watching(feed, 15.seconds)
        advanceTimeBy(50)
        router = "después"
        feed.refresh()
        advanceTimeBy(100)
        assertEquals("antes", feed.state.value.data)
        advanceTimeBy(100)
        assertEquals("después", feed.state.value.data)
        assertEquals(2, reads)
    }

    @Test
    fun `fresh tras un refresh no se conforma con la lectura que ya estaba en marcha`() = runTest {
        var router = "antes"
        val feed = feed {
            val read = router
            delay(100)
            read
        }
        val early = async { feed.fresh() }
        advanceTimeBy(50)
        router = "después"
        feed.refresh()
        assertEquals("después", feed.fresh())
        assertEquals("antes", early.await())
    }

    @Test
    fun `refresh sin que nadie mire no lee - se lee en cuanto alguien mira`() = runTest {
        val feed = feed()
        val first = watching(feed, 15.seconds)
        runCurrent()
        first.cancel()
        runCurrent()
        feed.refresh()
        advanceTimeBy(1.seconds)
        assertEquals(1, reads)
        watching(feed, 15.seconds)
        runCurrent()
        assertEquals(2, reads)
    }

    @Test
    fun `reset - se olvida lo leido y no se publica lo que venia de la sesion anterior`() = runTest {
        var router = "router viejo"
        val feed = feed {
            val read = router
            delay(100)
            read
        }
        val seen = mutableListOf<Loadable<String>>()
        watching(feed, 5.seconds, seen)
        advanceTimeBy(150)
        assertEquals("router viejo", feed.state.value.data)

        advanceTimeBy(5_000) // ya hay otra lectura del router viejo en marcha
        router = "router nuevo"
        feed.reset()
        assertTrue(feed.state.value.isLoading)
        advanceTimeBy(1.seconds)
        assertEquals(listOf("router viejo", null, "router nuevo"), seen.map { it.data }.drop(1))
    }

    @Test
    fun `reset - quien esperaba la lectura de la sesion anterior no recibe sus datos`() = runTest {
        val feed = feed {
            delay(100)
            "router viejo"
        }
        val asker = async { runCatching { feed.fresh() } }
        advanceTimeBy(50)
        feed.reset()
        advanceTimeBy(100)
        assertTrue(asker.await().exceptionOrNull() is SupersededException)
        assertTrue(feed.state.value.isLoading)
    }

    @Test
    fun `watch empieza por lo ultimo que se sabe`() = runTest {
        val feed = feed()
        feed.fresh()
        assertEquals("dato 1", feed.watch(1.minutes).first().data)
    }
}
