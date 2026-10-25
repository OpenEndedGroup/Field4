package afield.media.webrtc

import afield.app.diagnostics.logging.lprintln
import afield.app.server.refuseCrossSiteFieldWebSocket
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `/ws/webrtc-signaling?network=<id>` and `/api/webrtc-config?network=<id>`: what the sink and
 * source pages talk to. Installed beside Field's other WebSockets. Both answer only for networks
 * box code has registered (`WebRTCNetwork.aaaframe`/`join`, or the local relay), so a client cannot
 * make Field connect anywhere else; the guard decisions are in `dev_docs/server_surface_inventory.md`.
 */
fun Route.installWebRTCSignalingRoutes() {
    get("/api/webrtc-config") {
        val network = WebRTCNetwork.lookup(call.request.queryParameters["network"])
            ?: return@get call.respond(HttpStatusCode.NotFound, "unknown WebRTC network; register it with WebRTCNetwork.aaaframe(...)")
        val config = runCatching { withContext(Dispatchers.IO) { WebRTCConfig.forNetwork(network) } }
            .getOrElse { error -> unreachableConfig(network, error) }
        call.respondText(config.toString(), ContentType.Application.Json)
    }

    webSocket("/ws/webrtc-signaling") {
        // The pages that use this are Field's own (the output-area sink, the hidden source page).
        if (refuseCrossSiteFieldWebSocket("/ws/webrtc-signaling")) return@webSocket
        val network = WebRTCNetwork.lookup(call.request.queryParameters["network"])
        if (network == null) {
            close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "unknown WebRTC network"))
            return@webSocket
        }
        // Outgoing frames go through one channel so neither the relay nor the upstream listener
        // ever suspends or blocks on this socket.
        val outgoing = Channel<String>(Channel.UNLIMITED)
        val writer = launch { for (text in outgoing) send(Frame.Text(text)) }
        val peer = object : SignalingPeer { override fun deliver(text: String) { outgoing.trySend(text) } }
        if (network.isLocal) {
            val relay = LocalSignalingRelay.shared
            val id = relay.connect(peer)
            try {
                for (frame in incoming) if (frame is Frame.Text) relay.receive(id, frame.readText())
            } finally {
                relay.disconnect(id)
                outgoing.close()
                writer.cancel()
            }
        } else {
            val closed = CompletableDeferred<String>()
            val upstream = UpstreamSignaling(network, onText = peer::deliver, onClose = { closed.complete(it) })
            val reader = launch {
                for (frame in incoming) if (frame is Frame.Text) upstream.send(frame.readText())
                closed.complete("page closed")
            }
            val reason = closed.await()
            if (reason != "page closed") {
                lprintln("info,webrtc", "aaaframe signalling for ${network.baseUrl} ended: $reason")
                outgoing.trySend(fieldErrorMessage(reason))
            }
            upstream.close()
            outgoing.close()
            writer.join()
            reader.cancel()
            close(CloseReason(CloseReason.Codes.NORMAL, reason.take(100)))
        }
    }
}
