package dev.angel.wrtcontrol.ubus

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException

/** Llamada ubus registrada por [FakeRouter]. */
data class RecordedCall(val sid: String, val target: String, val method: String, val args: JsonObject) {
    val name get() = "$target.$method"
}

/** Respuesta que debe dar el router falso. */
sealed interface Reply {
    data class Result(val json: String) : Reply
    data class Http(val code: Int) : Reply
    data object NetworkError : Reply
}

fun ok(data: String? = null) = Reply.Result(if (data == null) """{"result":[0]}""" else """{"result":[0,$data]}""")
fun status(code: Int) = Reply.Result("""{"result":[$code]}""")
val accessDenied = Reply.Result("""{"error":{"code":-32002,"message":"Access denied"}}""")

/**
 * Router falso a nivel de OkHttp: intercepta cada petición, decodifica la llamada ubus
 * y responde según [handler]. No abre sockets.
 */
class FakeRouter(private val handler: (RecordedCall) -> Reply) {
    val calls = mutableListOf<RecordedCall>()

    val http: OkHttpClient = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val request = chain.request()
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        val msg = Json.parseToJsonElement(body).jsonObject
        val params = msg.getValue("params").jsonArray
        val call = RecordedCall(
            sid = params[0].jsonPrimitive.content,
            target = params[1].jsonPrimitive.content,
            method = params[2].jsonPrimitive.content,
            args = params[3].jsonObject,
        )
        synchronized(calls) { calls += call }

        val (code, json) = when (val reply = handler(call)) {
            is Reply.Result -> 200 to reply.json.replaceFirst("{", """{"jsonrpc":"2.0","id":${msg["id"]},""")
            is Reply.Http -> reply.code to ""
            Reply.NetworkError -> throw IOException("Connection refused")
        }
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .body(json.toResponseBody("application/json".toMediaType()))
            .build()
    }).build()

    fun client(credentials: Credentials? = null, io: CoroutineDispatcher = Dispatchers.IO) =
        UbusClient("http://router.test/ubus".toHttpUrl(), http, credentials, io = io)
}
