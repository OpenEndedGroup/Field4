package afield.server

import afield.*
import afield.logger.lprintln
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Chat server integration for guide/slideshow discussion functionality.
 *
 * This module handles communication with the Flask chat server, providing
 * handlers for loading and posting chat messages to per-slide discussion threads.
 */

@RunsAt(installHandlers)
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
