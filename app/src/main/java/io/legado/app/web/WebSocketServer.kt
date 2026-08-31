package io.legado.app.web

import fi.iki.elonen.NanoWSD
import io.legado.app.web.controller.SourceDebugWebSocket

class WebSocketServer(hostname: String, port: Int) : NanoWSD(hostname, port) {

    override fun openWebSocket(handshake: IHTTPSession): WebSocket? {
        return if (handshake.uri == "/sourceDebug") {
            SourceDebugWebSocket(handshake)
        } else null
    }
}
