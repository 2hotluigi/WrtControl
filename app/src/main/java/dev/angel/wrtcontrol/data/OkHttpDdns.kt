package dev.angel.wrtcontrol.data

import dev.angel.wrtcontrol.addons.DdnsHttp
import dev.angel.wrtcontrol.addons.DdnsHttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Las respuestas son de una línea: lo demás sobra. */
private const val MAX_BODY = 2_000

/**
 * Peticiones al proveedor del DNS dinámico desde el teléfono. Con un cliente propio, sin el túnel
 * del acceso remoto: el proveedor está en internet, no detrás del router.
 */

class OkHttpDdns(private val client: OkHttpClient) : DdnsHttp {
    override suspend fun get(url: String): DdnsHttpResponse = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).header("User-Agent", "WrtControl").build()).execute().use { r ->
            DdnsHttpResponse(r.code, r.body.string().take(MAX_BODY))
        }
    }
}
