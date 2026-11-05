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
import java.util.concurrent.atomic.AtomicLong

/** Sesión "nula" de rpcd: solo permite session.login y session.access. */
const val NULL_SESSION = "00000000000000000000000000000000"

/** uhttpd devuelve este código tanto si la sesión no existe como si el ACL no permite la llamada. */
private const val RPC_ACCESS_DENIED = -32002

/** rpcd responde con este estado cuando el usuario o la contraseña no son válidos. */
private const val UBUS_PERMISSION_DENIED = 6

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

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

    /** Inicia sesión y, si funciona, guarda las credenciales para renovarla más adelante. */
    suspend fun login(credentials: Credentials): SessionInfo {
        val info = newSession(credentials)
        this.credentials = credentials
        session = info.id
        return info
    }

    /**
     * Abre una sesión aparte con las credenciales guardadas, sin tocar la que usan las
     * consultas periódicas. Ojo: si hay un `uci apply` pendiente de confirmar del mismo
     * usuario, rpcd devuelve esa sesión en vez de una nueva (para poder confirmarlo).
     */
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
        val sid = session ?: renewSession(expired = null)
        return try {
            rawCall(sid, target, method, args)
        } catch (e: UbusException.AccessDenied) {
            // -32002 es ambiguo: averiguamos si la sesión sigue viva.
            if (isSessionAlive(sid)) throw e
            rawCall(renewSession(expired = sid), target, method, args)
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
