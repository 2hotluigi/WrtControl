package dev.angel.wrtcontrol.ubus

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.serializer
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Sesión "nula" de rpcd: solo permite session.login y session.access. */
const val NULL_SESSION = "00000000000000000000000000000000"

/** uhttpd devuelve este código tanto si la sesión no existe como si el ACL no permite la llamada. */
private const val RPC_ACCESS_DENIED = -32002

/** rpcd responde con este estado cuando el usuario o la contraseña no son válidos. */
private const val UBUS_PERMISSION_DENIED = 6

private val JSON_MEDIA_TYPE = "application/json".toMediaType()
private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()

/** Tras un login, cuánto se vigila si la sesión era la de un cambio pendiente (60 s de plazo y margen). */
private const val RECLAIM_WINDOW_MS = 120_000L
private const val CHANGES_CHECK_MS = 10_000L

/** Lo que espera la app a un comando de cgi-exec: algo más que el `script_timeout` de uhttpd (60 s). */
const val CGI_TIMEOUT_SECONDS = 75L

/** Como encodeURIComponent de JavaScript (cgi-io no entiende una '/' sin codificar). */
internal fun uriComponent(text: String): String =
    java.net.URLEncoder.encode(text, "UTF-8") // la versión con Charset es de Android 13
        .replace("+", "%20")
        .replace("%21", "!")
        .replace("%27", "'")
        .replace("%28", "(")
        .replace("%29", ")")
        .replace("%7E", "~")

data class Credentials(val username: String, val password: String) {
    override fun toString() = "Credentials(username=$username, password=***)"
}

@Serializable
data class SessionInfo(
    @SerialName("ubus_rpc_session") val id: String,
    val timeout: Int = 0,
    val expires: Int = 0,
)

val UbusJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

/** Algo que puede hacer llamadas ubus: el cliente compartido o una [UbusSession] propia. */
interface UbusCaller {
    suspend fun call(target: String, method: String, args: JsonObject = JsonObject(emptyMap())): JsonElement?
}

/** Igual que [UbusCaller.call], pero decodifica la respuesta al tipo pedido. */
suspend inline fun <reified T> UbusCaller.callAs(
    target: String,
    method: String,
    args: JsonObject = JsonObject(emptyMap()),
): T = decodeUbus(UbusJson, serializer<T>(), call(target, method, args), "$target.$method")

@PublishedApi
internal fun <T> decodeUbus(
    json: Json,
    deserializer: kotlinx.serialization.DeserializationStrategy<T>,
    data: JsonElement?,
    what: String,
): T = try {
    // ubus puede responder solo `[0]` (sin datos): se trata como un objeto vacío.
    json.decodeFromJsonElement(deserializer, data?.takeUnless { it is JsonNull } ?: JsonObject(emptyMap()))
} catch (e: SerializationException) {
    throw UbusException.Protocol("Respuesta inesperada de $what: ${e.message}", e)
} catch (e: IllegalArgumentException) {
    throw UbusException.Protocol("Respuesta inesperada de $what: ${e.message}", e)
}

/**
 * Sesión propia para una serie de cambios (ver `parental.SafeApply`). rpcd guarda los cambios
 * UCI pendientes por sesión: si algo falla a medias basta con abandonarla, porque el ACL de
 * LuCI no permite `uci revert`. No se renueva sola: si caduca, los cambios pendientes se pierden
 * y se lanza [UbusException.SessionLost].
 */
class UbusSession internal constructor(private val client: UbusClient, val id: String) : UbusCaller {
    override suspend fun call(target: String, method: String, args: JsonObject): JsonElement? =
        client.callInSession(id, target, method, args)

    /** Otra sesión del mismo usuario (rpcd devuelve esta si sigue teniendo un apply pendiente). */
    internal suspend fun reopen(): UbusSession = client.openSession()
}

/**
 * Cliente JSON-RPC para ubus: el endpoint `/ubus` de uhttpd-mod-ubus, el mismo que usa LuCI.
 *
 * - Llamada:   `{"method":"call","params":[sesión, objeto, método, argumentos]}`
 * - Respuesta: `{"result":[códigoUbus, datos?]}` o `{"error":{"code","message"}}`
 *
 * Si tiene credenciales, renueva la sesión de forma transparente cuando caduca
 * (rpcd las invalida tras unos minutos sin uso o al reiniciar el router).
 */
class UbusClient(
    private val endpoint: HttpUrl,
    private val http: OkHttpClient,
    credentials: Credentials? = null,
    @PublishedApi internal val json: Json = UbusJson,
    /** Donde se hacen las peticiones HTTP; las pruebas pasan el de `runTest`. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : UbusCaller {
    @Volatile
    var session: String? = null
        private set

    @Volatile
    private var credentials: Credentials? = credentials

    private val loginMutex = Mutex()
    private val nextId = AtomicLong(1)

    /** Cuándo se obtuvo la sesión compartida y cuándo se miró por última vez si tiene cambios. */
    @Volatile
    private var sessionSince = 0L

    @Volatile
    private var changesCheckedAt = 0L

    /** Inicia sesión y, si funciona, guarda las credenciales para renovarla más adelante. */
    suspend fun login(credentials: Credentials): SessionInfo {
        val info = newSession(credentials)
        this.credentials = credentials
        session = info.id
        sessionSince = System.currentTimeMillis()
        changesCheckedAt = 0L
        return info
    }

    /**
     * Abre una sesión aparte con las credenciales guardadas, sin tocar la que usan las
     * consultas periódicas. Ojo: si hay un `uci apply` pendiente de confirmar del mismo
     * usuario, rpcd devuelve esa sesión en vez de una nueva (para poder confirmarlo).
     */
    /** Cambia las credenciales para los próximos inicios de sesión (p. ej. tras cambiar la contraseña). */
    fun updateCredentials(credentials: Credentials) {
        this.credentials = credentials
    }

    suspend fun openSession(): UbusSession {
        val creds = credentials ?: throw UbusException.SessionExpired()
        return UbusSession(this, newSession(creds).id)
    }

    private suspend fun newSession(credentials: Credentials): SessionInfo {
        val args = buildJsonObject {
            put("username", credentials.username)
            put("password", credentials.password)
        }
        val data = try {
            rawCall(NULL_SESSION, "session", "login", args)
        } catch (e: UbusException.Status) {
            throw if (e.code == UBUS_PERMISSION_DENIED) UbusException.AuthFailed() else e
        } catch (_: UbusException.AccessDenied) {
            throw UbusException.AuthFailed()
        }
        return decode(serializer<SessionInfo>(), data, "session.login")
    }

    internal suspend fun callInSession(sid: String, target: String, method: String, args: JsonObject): JsonElement? =
        try {
            rawCall(sid, target, method, args)
        } catch (e: UbusException.AccessDenied) {
            if (isSessionAlive(sid)) throw e
            throw UbusException.SessionLost()
        }

    /**
     * Olvida la sesión y las credenciales. No se llama a session.destroy porque el ACL
     * de root no lo permite; la sesión caduca sola en el router.
     */
    fun forget() {
        session = null
        credentials = null
    }

    /** Llama a `objeto.método` con la sesión actual y devuelve los datos crudos. */
    override suspend fun call(target: String, method: String, args: JsonObject): JsonElement? {
        var sid = session ?: renewSession(expired = null)
        if (target == "uci" && method == "get" && hasPhantomChanges(sid)) sid = renewSession(expired = sid)
        return try {
            rawCall(sid, target, method, args)
        } catch (e: UbusException.AccessDenied) {
            // -32002 es ambiguo: averiguamos si la sesión sigue viva.
            if (isSessionAlive(sid)) throw e
            rawCall(renewSession(expired = sid), target, method, args)
        }
    }

    /**
     * Ejecuta [command] con `cgi-exec` de cgi-io, como `fs.exec_direct` de LuCI (la página de
     * software lo usa para instalar paquetes). A diferencia de `file.exec`, que rpcd corta a los
     * 30 s, aquí manda el `script_timeout` de uhttpd (60 s por defecto). Devuelve la salida
     * estándar del comando; el ACL (`cgi-io exec` y el comando exacto en `file`) decide si se
     * permite.
     */
    suspend fun cgiExec(command: String, params: List<String>, timeoutSeconds: Long = CGI_TIMEOUT_SECONDS): String {
        val sid = session ?: renewSession(expired = null)
        return try {
            rawCgiExec(sid, command, params, timeoutSeconds)
        } catch (e: UbusException.AccessDenied) {
            // Una sesión caducada también da 403 ("Exec permission denied"): se mira cuál es.
            if (isSessionAlive(sid)) throw e
            rawCgiExec(renewSession(expired = sid), command, params, timeoutSeconds)
        }
    }

    private suspend fun rawCgiExec(sid: String, command: String, params: List<String>, timeoutSeconds: Long): String {
        // Como LuCI: barras invertidas y espacios escapados con '\', y todo en encodeURIComponent.
        val line = (listOf(command) + params).joinToString(" ") { it.replace("\\", "\\\\").replace(Regex("(\\s)"), "\\\\$1") }
        val body = "sessionid=${uriComponent(sid)}&command=${uriComponent(line)}&stderr=0"
        val url = endpoint.newBuilder().encodedPath("/cgi-bin/cgi-exec").query(null).build()
        val request = Request.Builder().url(url).post(body.toRequestBody(FORM_MEDIA_TYPE)).build()
        val client = http.newBuilder()
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds + 10, TimeUnit.SECONDS)
            .build()
        return withContext(io) {
            try {
                client.newCall(request).execute().use { response ->
                    val text = response.body.string()
                    when (response.code) {
                        200 -> text
                        403 -> throw UbusException.AccessDenied(command)
                        404 -> throw UbusException.Status(4, command)
                        else -> throw UbusException.Network("El router respondió HTTP ${response.code} a $command", response.code)
                    }
                }
            } catch (e: IOException) {
                throw UbusException.Network("No se pudo contactar con el router (${e.message ?: e.javaClass.simpleName})", cause = e)
            }
        }
    }

    /** Igual que [call], pero decodifica la respuesta al tipo pedido. */
    suspend inline fun <reified T> callAs(
        target: String,
        method: String,
        args: JsonObject = JsonObject(emptyMap()),
    ): T = decode(serializer<T>(), call(target, method, args), "$target.$method")

    @PublishedApi
    internal fun <T> decode(
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
        data: JsonElement?,
        what: String,
    ): T = decodeUbus(json, deserializer, data, what)

    /**
     * Obtiene una sesión válida. Si [expired] ya no es la sesión actual, otra corrutina la
     * renovó mientras tanto y se reutiliza; así varias llamadas concurrentes hacen un solo login.
     */
    private suspend fun renewSession(expired: String?): String = loginMutex.withLock {
        session?.let { current -> if (current != expired) return@withLock current }
        session = null
        val creds = credentials ?: throw UbusException.SessionExpired()
        login(creds).id
    }

    /**
     * rpcd da la sesión de un `uci apply` pendiente de confirmar a cualquier login del mismo usuario.
     * Si la sesión compartida resulta ser una de esas (p. ej. la app se cerró a mitad de un cambio y
     * volvió a entrar) y el router lo deshace al vencer el plazo, rpcd le devuelve esos cambios como
     * pendientes, y `uci get` los mezclaría con la configuración: la app enseñaría puesto un cambio
     * deshecho. La sesión compartida nunca prepara cambios: si tiene alguno, se abandona.
     */
    private suspend fun hasPhantomChanges(sid: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - sessionSince > RECLAIM_WINDOW_MS || now - changesCheckedAt < CHANGES_CHECK_MS) return false
        changesCheckedAt = now
        val changes = try {
            rawCall(sid, "uci", "changes", JsonObject(emptyMap()))
        } catch (_: UbusException) {
            return false
        }
        val pending = ((changes as? JsonObject)?.get("changes") as? JsonObject)?.isNotEmpty() == true
        return pending && session == sid
    }

    /** session.access está permitido para cualquier sesión válida (grupo "unauthenticated"). */
    private suspend fun isSessionAlive(sid: String): Boolean = try {
        rawCall(
            sid, "session", "access",
            buildJsonObject {
                put("scope", "ubus")
                put("object", "session")
                put("function", "access")
            },
        )
        true
    } catch (_: UbusException.AccessDenied) {
        false
    }

    private suspend fun rawCall(sid: String, target: String, method: String, args: JsonObject): JsonElement? {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", nextId.getAndIncrement())
            put("method", "call")
            putJsonArray("params") {
                add(sid)
                add(target)
                add(method)
                add(args)
            }
        }.toString()

        val request = Request.Builder().url(endpoint).post(body.toRequestBody(JSON_MEDIA_TYPE)).build()

        val text = withContext(io) {
            try {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw UbusException.Network("El router respondió HTTP ${response.code}", response.code)
                    }
                    response.body.string()
                }
            } catch (e: IOException) {
                throw UbusException.Network("No se pudo contactar con el router (${e.message ?: e.javaClass.simpleName})", cause = e)
            }
        }

        val message = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw UbusException.Protocol("El router no respondió con JSON (¿es la dirección de un OpenWrt?)", e)
        }

        message["error"]?.let { error ->
            val code = error.jsonObject["code"]?.jsonPrimitive?.intOrNull
            if (code == RPC_ACCESS_DENIED) throw UbusException.AccessDenied("$target.$method")
            val text = error.jsonObject["message"]?.jsonPrimitive?.content ?: "error $code"
            throw UbusException.Protocol("$target.$method: $text")
        }

        val result = try {
            message["result"]?.jsonArray
        } catch (e: IllegalArgumentException) {
            null
        } ?: throw UbusException.Protocol("$target.$method: respuesta sin resultado")

        val status = result.firstOrNull()?.jsonPrimitive?.int ?: 9
        if (status != 0) throw UbusException.Status(status, "$target.$method")
        return result.getOrNull(1)
    }
}
