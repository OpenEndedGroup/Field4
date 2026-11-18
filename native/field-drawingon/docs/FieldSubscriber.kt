// Run in a Field Kotlin box before connecting the iPad.
// Start tools/receiver.py on this machine first. This receives NEW events only.
// The receiver's captures/<session>.ndjson files contain the full replay history.
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletionStage
import kotlinx.serialization.json.*

val drawingOnInbox = ArrayBlockingQueue<JsonObject>(512)
val drawingOnStatus = java.util.concurrent.atomic.AtomicReference("connecting")
val drawingOnSocket = HttpClient.newHttpClient().newWebSocketBuilder()
    .buildAsync(URI.create("ws://127.0.0.1:8765/watch"), object : WebSocket.Listener {
        val text = StringBuilder()
        override fun onOpen(socket: WebSocket) {
            drawingOnStatus.set("connected")
            socket.request(1)
        }
        override fun onText(socket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            text.append(data)
            if (last) {
                val event = Json.parseToJsonElement(text.toString()).jsonObject
                text.setLength(0)
                if (!drawingOnInbox.offer(event)) {
                    drawingOnStatus.set("consumer fell behind; recover from capture archive")
                    socket.abort()
                    return null
                }
            }
            socket.request(1)
            return null
        }
        override fun onError(socket: WebSocket, error: Throwable) {
            drawingOnStatus.set(error.message ?: "socket error")
        }
        override fun onClose(socket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            drawingOnStatus.set("closed: $statusCode $reason")
            return null
        }
    })

// All Field work happens on its loop, never on the WebSocket callback thread.
loop {
    repeat(128) {
        val event = drawingOnInbox.poll() ?: return@repeat
        val type = event.getValue("type").jsonPrimitive.content
        val timestamp = event.getValue("t").jsonPrimitive.double
        val payload = event.getValue("data").jsonObject
        when (type) {
            "stroke.samples", "stroke.correct" -> {
                val strokeID = payload.getValue("stroke").jsonPrimitive.content
                val samples = payload.getValue("samples").jsonArray
                // Feed samples to your architecture model here, preserving strokeID,
                // sample id and t. Corrections replace a previous sample with the same id.
            }
            "audio.buffer" -> {
                val pcm = java.util.Base64.getDecoder().decode(payload.getValue("pcm").jsonPrimitive.content)
                val floats = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                // First frame is at timestamp; subsequent frames are 1/sampleRate apart.
            }
            "canvas.viewport", "canvas.transform" -> {
                // Full affine view matrix plus exact visible corners and drawingBounds.
                // Drawing geometry uses sample drawingX/Y; preciseX/Y retain raw display positions.
            }
            "layers.state", "stroke.visibility" -> println("$timestamp $type $payload")
        }
    }
}

// When finished, stop the Field loop and close the subscriber:
// drawingOnSocket.thenAccept { it.sendClose(WebSocket.NORMAL_CLOSURE, "done") }
