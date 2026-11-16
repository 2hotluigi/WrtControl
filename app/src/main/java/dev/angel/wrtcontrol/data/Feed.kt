package dev.angel.wrtcontrol.data

import dev.angel.wrtcontrol.Txt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/** La lectura se pidió con una sesión que ya no es la de ahora: su resultado no vale. */
internal class SupersededException : Exception("La lectura es de una sesión anterior")

/**
 * Una lectura del router que comparten las pantallas. Se pide una sola vez aunque la miren varias,
 * al ritmo de la que la quiere más a menudo, y solo mientras alguna la mira. Lo último que se leyó
 * queda en [state], así que al cambiar de pantalla no hay que esperar a que vuelva a cargar.
 *
 * [load] lee del router; [describe] traduce un fallo a lo que se le dice al usuario; [now] es un
 * reloj en milisegundos que no retrocede.
 */
class Feed<T : Any>(
    private val scope: CoroutineScope,
    private val describe: (Throwable) -> Txt,
    private val now: () -> Long,
    private val load: suspend () -> T,
) {
    private val _state = MutableStateFlow(Loadable<T>())

    /** Lo último que se sabe. Mirarlo aquí no hace que se lea del router: para eso, [watch]. */
    val state: StateFlow<Loadable<T>> = _state.asStateFlow()

    private val lock = Any()

    /** Cada cuánto la quiere cada pantalla que la está mirando. */
    private val watchers = ArrayList<Duration>()

    /** El ritmo de ahora: el más corto de [watchers]; null si nadie mira. */
    private val pace = MutableStateFlow<Duration?>(null)

    /** Despierta a la espera entre lecturas: algo cambió y hay que volver a mirar cuándo toca. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Cambia con cada [reset]: una lectura que empezó en otra sesión no se publica. */
    private var session = 0

    /** La lectura en marcha, para que quien llegue la espere en vez de pedir otra. */
    private var running: Deferred<Result<T>>? = null

    /** Cuándo terminó el último intento y cuándo el último que salió bien. */
    private var triedAt: Long? = null
    private var loadedAt: Long? = null

    /** Lo leído ya no vale (se cambió algo en el router) aunque sea reciente. */
    private var stale = false

    init {
        scope.launch {
            pace.collectLatest { every ->
                if (every == null) return@collectLatest
                while (true) {
                    val wait = synchronized(lock) { if (stale) 0 else triedAt?.let { it + every.inWholeMilliseconds - now() } ?: 0 }
                    if (wait > 0) withTimeoutOrNull(wait) { wake.receive() } else read()
                }
            }
        }
    }

    /**
     * Lo que se sabe y lo que se vaya leyendo, como mucho cada [every], mientras se recoja el flujo.
     * Empieza por lo último leído, aunque sea viejo, y entonces se vuelve a leer enseguida.
     */
    fun watch(every: Duration): Flow<Loadable<T>> = flow {
        synchronized(lock) {
            watchers += every
            pace.value = watchers.min()
        }
        try {
            emitAll(_state)
        } finally {
            synchronized(lock) {
                watchers -= every
                pace.value = watchers.minOrNull()
            }
        }
    }

    /**
     * El dato, para usarlo ahora. Vale el último si se leyó hace menos de [maxAge]; si no, se lee (o
     * se espera a la lectura en marcha). Lanza el fallo de la lectura.
     */
    suspend fun fresh(maxAge: Duration = Duration.ZERO): T = recent(maxAge) ?: read().getOrThrow()

    /** Lo último leído, si sigue valiendo y tiene menos de [maxAge]. */
    private fun recent(maxAge: Duration): T? = synchronized(lock) {
        val at = loadedAt
        if (stale || at == null || now() - at >= maxAge.inWholeMilliseconds) null else _state.value.data
    }

    /**
     * Lo leído ya no vale (se acaba de cambiar algo en el router). Si alguien lo mira se vuelve a
     * leer ya; si no, en cuanto lo miren.
     */
    fun refresh() {
        synchronized(lock) { stale = true }
        wake.trySend(Unit)
    }

    /** Otra sesión (otro router, o se salió): se olvida todo y lo que estuviera leyéndose no cuenta. */
    fun reset() {
        synchronized(lock) {
            session++
            running = null
            triedAt = null
            loadedAt = null
            stale = false
            _state.value = Loadable()
        }
        wake.trySend(Unit)
    }

    /** Una lectura que haya empezado después del último [refresh]: la que está en marcha o una nueva. */
    private suspend fun read(): Result<T> {
        while (true) {
            val (job, outdated) = synchronized(lock) {
                val current = running
                if (current != null) {
                    current to stale
                } else {
                    stale = false
                    newRead().also { running = it } to false
                }
            }
            // Se arranca ya fuera del candado y con `running` puesto: nunca termina antes de estar apuntada.
            job.start()
            val result = job.await()
            if (!outdated) return result
        }
    }

    /** Corre en [scope], no en quien la pide: si ese se va, la lectura termina igual para los demás. */
    private fun newRead(): Deferred<Result<T>> {
        val mine = session
        return scope.async(start = CoroutineStart.LAZY) {
            val result = try {
                Result.success(load())
            } catch (e: CancellationException) {
                // Si no es esta lectura la que se canceló, es un tiempo agotado de dentro: un fallo más.
                ensureActive()
                Result.failure(e)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Result.failure(e)
            }
            synchronized(lock) {
                if (mine != session) return@async Result.failure(SupersededException())
                running = null
                val at = now()
                triedAt = at
                result.fold(
                    onSuccess = {
                        loadedAt = at
                        _state.value = Loadable(it)
                    },
                    onFailure = { _state.value = Loadable(_state.value.data, describe(it)) },
                )
            }
            result
        }
    }
}
