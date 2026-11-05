package dev.angel.wrtcontrol.data

import dev.angel.wrtcontrol.go.wrtcvpn.Wrtcvpn

/** El túnel de verdad: la librería de tunnel/ (wireguard-go + netstack) compilada con gomobile. */
class GoTunnelEngine : TunnelEngine {
    override fun start(config: TunnelConfig, endpoint: String, target: String): TunnelHandle {
        val t = Wrtcvpn.start(
            config.privateKey, config.routerPublicKey, config.presharedKey,
            endpoint, config.phoneAddress, target, 0,
        )
        return object : TunnelHandle {
            override val localPort = t.localPort().toInt()

            override fun lastHandshake(): Long = t.lastHandshake()

            override fun setEndpoint(endpoint: String) = t.setEndpoint(endpoint)

            override fun stop() = t.stop()
        }
    }
}

/** El motor del túnel, o null si la librería nativa no se puede cargar (p. ej. otra arquitectura). */
fun tunnelEngine(): TunnelEngine? = try {
    Class.forName("dev.angel.wrtcontrol.go.wrtcvpn.Wrtcvpn")
    GoTunnelEngine()
} catch (_: Throwable) {
    null
}
