package afield.app.server

import afield.io.drawingon.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID

/** The optional native drawing head: durable input and the AVP FLine geometry return wire. */
fun Route.installDrawingOnRoutes(archive: DrawingOnArchive = DrawingOnServices.archiveAt(File(home, ".drawingon"))) {
    DrawingOnServices.archive = archive
    get("/drawingon/buffers") {
        val target = call.request.queryParameters["target"].orEmpty()
        val generation = call.request.queryParameters["generation"].orEmpty()
        val reference = DrawingOnServices.targets[target]?.reference
        if (reference == null || reference.generation != generation) call.respond(HttpStatusCode.NotFound)
        else {
            call.response.header(HttpHeaders.CacheControl, "private, max-age=3600, immutable")
            call.respondBytes(reference.bytes, ContentType.Application.OctetStream)
        }
    }
    webSocket("/drawingon/ws") {
        if (refuseCrossSiteFieldWebSocket("/drawingon/ws")) return@webSocket
        val targetName = call.request.queryParameters["target"] ?: "ipad"
        var peer: Channel<DrawingOnReference>? = null
        var attached: DrawingOnTarget? = null
        var session: String? = null
        var upload = false
        try {
            DrawingOnServices.validTarget(targetName)
            for (frame in incoming) {
                require(frame is Frame.Text && frame.data.size <= 1024 * 1024) { "Expected a capture text frame up to 1 MiB" }
                val message = drawingOnJson.parseToJsonElement(frame.readText()).jsonObject
                if (message["type"]?.jsonPrimitive?.content == "capture.hello") {
                    require(session == null) { "Only one capture per connection" }
                    val id = UUID.fromString(message.getValue("session").jsonPrimitive.content).toString()
                    session = id
                    upload = message["through"] != null && message["through"] != JsonNull
                    val head = withContext(Dispatchers.IO) { archive.bind(targetName, id); archive.head(id) }
                    send(buildJsonObject { put("ready", head); put("session", id) }.toString())
                    // Historical uploads never receive references or create a reference echo loop.
                    if (!upload) {
                        val updates = Channel<DrawingOnReference>(Channel.CONFLATED)
                        peer = updates
                        // A target may be published after the iPad connects; the service registers this peer below.
                        DrawingOnPeers.attach(targetName, updates)
                        attached = DrawingOnServices.targets[targetName]
                        launch { for (reference in updates) send(reference.message.toString()) }
                    }
                    continue
                }
                val id = requireNotNull(session) { "Send capture.hello first" }
                val events = if (message["type"]?.jsonPrimitive?.content == "capture.batch") message.getValue("events").jsonArray.map { it.jsonObject } else listOf(message)
                handleDrawingOnInput(archive, targetName, id, upload, events)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, (error.message ?: "Capture failed").take(110)))
        } finally {
            peer?.let { DrawingOnPeers.detach(targetName, it); attached?.peers?.remove(it); it.close() }
        }
    }
}

private suspend fun DefaultWebSocketServerSession.handleDrawingOnInput(
    archive: DrawingOnArchive, target: String, session: String, upload: Boolean, events: List<JsonObject>
) {
    val result = withContext(Dispatchers.IO) { archive.commit(session, events) }
    send(result.reply.toString())
    DrawingOnServices.targets[target]?.deliver(session, upload, result.fresh)
}

/** Retains subscriptions even when a box publishes its target after the iPad connects. */
internal object DrawingOnPeers {
    private val subscribers = java.util.concurrent.ConcurrentHashMap<String, MutableSet<Channel<DrawingOnReference>>>()
    fun attach(target: String, channel: Channel<DrawingOnReference>) {
        subscribers.computeIfAbsent(target) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(channel)
        DrawingOnServices.targets[target]?.let { owner -> owner.peers.add(channel); owner.reference?.let { channel.trySend(it) } }
    }
    fun attachPublisher(target: DrawingOnTarget) {
        subscribers[target.name]?.forEach { channel -> target.peers.add(channel) }
    }
    fun detach(target: String, channel: Channel<DrawingOnReference>) {
        subscribers[target]?.remove(channel); DrawingOnServices.targets[target]?.peers?.remove(channel)
    }
}
