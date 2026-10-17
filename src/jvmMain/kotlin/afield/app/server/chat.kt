package afield.app.server

import afield.ai.agent.*
import afield.ai.conversation.*
import afield.ai.tools.*
import afield.animation.gesture.*
import afield.animation.interpolation.*
import afield.animation.keyframes.*
import afield.app.diagnostics.*
import afield.app.notifications.*
import afield.app.protocol.*
import afield.app.server.*
import afield.app.settings.*
import afield.audio.synth.*
import afield.data.table.*
import afield.document.context.*
import afield.document.graph.*
import afield.document.history.*
import afield.document.persistence.*
import afield.document.properties.*
import afield.document.workspace.*
import afield.graphics.color.*
import afield.graphics.paint.*
import afield.graphics.vulkan.*
import afield.math.functions.*
import afield.media.capture.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.completion.*
import afield.runtime.execution.*
import afield.runtime.kotlin.*
import afield.runtime.lifecycle.*
import afield.tools.automation.*
import afield.tools.docs.*
import afield.ui.controls.*
import afield.ui.layout.*
import afield.ui.notation.*
import afield.ui.output.*
import afield.ui.output.templates.*
import afield.ui.slides.*
import afield.ui.visualization.*
import afield.app.diagnostics.logging.lprintln
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Install Field's guide-chat RPC bridge.
 *
 * `load-chat-messages` and `post-chat-message` proxy to the Flask service selected by
 * `CHAT_SERVER_URL` (default `http://localhost:5002`). Loads reply only to their requester; a
 * successful post broadcasts `chat-message-posted` through Field so visible widgets reload the
 * thread. Field's browser does not connect directly to the Flask Socket.IO endpoint.
 *
 * Requests run in [GlobalScope] and the client is process-lived. A load failure is represented as
 * an empty list; a post failure is logged and has no success/error reply. The service is therefore
 * optional and intentionally outside slideshow execution.
 */
@RunsAt(installHandlers)
@OptIn(DelicateCoroutinesApi::class)
fun chat() {
    // Chat server configuration
    val chatServerUrl = System.getenv("CHAT_SERVER_URL") ?: "http://localhost:5002"
    val chatHttpClient = HttpClient(CIO) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json()
        }
    }

    handlers["load-chat-messages"] = mutableSetOf({ message, reply ->
        val request = Json.decodeFromJsonElement<LoadChatMessages>(message.payload!!)
        lprintln("debug,chat", "Loading chat messages for ${request.slideshowId}/${request.slideNumber}")

        GlobalScope.launch {
            try {
                val response: HttpResponse = chatHttpClient.get("$chatServerUrl/chat/${request.slideshowId}/${request.slideNumber}")
                val messagesJson = response.bodyAsText()
                val messages = Json.decodeFromString<List<ChatMessage>>(messagesJson)

                reply(Message("chat-messages-loaded").payload(
                    ChatMessagesLoaded(
                        slideshowId = request.slideshowId,
                        slideNumber = request.slideNumber,
                        messages = messages
                    )
                ))
            } catch (e: Exception) {
                lprintln("error,chat", "Error loading chat messages: ${e.message}")
                // Send empty list on error
                reply(Message("chat-messages-loaded").payload(
                    ChatMessagesLoaded(
                        slideshowId = request.slideshowId,
                        slideNumber = request.slideNumber,
                        messages = emptyList()
                    )
                ))
            }
        }

        Handled.yes
    })

    handlers["post-chat-message"] = mutableSetOf({ message, reply ->
        val request = Json.decodeFromJsonElement<PostChatMessage>(message.payload!!)
        lprintln("debug,chat", "Posting chat message: ${request.username}: ${request.message}")

        GlobalScope.launch {
            try {
                val response: HttpResponse = chatHttpClient.post("$chatServerUrl/chat/${request.slideshowId}/${request.slideNumber}") {
                    contentType(ContentType.Application.Json)
                    setBody(mapOf(
                        "username" to request.username,
                        "message" to request.message
                    ))
                }

                val messageJson = response.bodyAsText()
                val postedMessage = Json.decodeFromString<ChatMessage>(messageJson)

                // Broadcast to all connected clients in this session
                _send(Message("chat-message-posted").payload(
                    ChatMessagePosted(
                        slideshowId = request.slideshowId,
                        slideNumber = request.slideNumber,
                        message = postedMessage
                    )
                ))
            } catch (e: Exception) {
                lprintln("error,chat", "Error posting chat message: ${e.message}")
                e.printStackTrace()
            }
        }

        Handled.yes
    })
}
