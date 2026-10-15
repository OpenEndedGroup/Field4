@file:afield.runtime.completion.CompletionCategory("Workspace")

package afield.ui.output

import afield.app.diagnostics.named
import afield.app.protocol.Output
import afield.data.table.div
import afield.data.table.minus
import afield.data.table.plus
import afield.data.table.rem
import afield.data.table.times
import afield.document.graph.box
import afield.runtime.completion.CompletionCategory
import afield.runtime.execution.plus
import afield.runtime.lifecycle.plus
import afield.runtime.lifecycle.rem
import afield.ui.notation.with

import afield.app.protocol.Handled
import afield.app.protocol.Message
import afield.app.protocol.OutputRequest
import afield.app.protocol.OutputRequestResult
import afield.app.protocol.handlers
import afield.app.protocol.payload
import afield.app.server.ReadOnlyDocuments
import afield.app.server.thisSessionInfo
import afield.audio.synth.*
import afield.data.table.get
import afield.document.graph.Box
import afield.document.graph.registerStopHook
import afield.graphics.vulkan.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.lifecycle.Runloop
import afield.runtime.lifecycle.RunsAt
import afield.runtime.lifecycle.installHandlers
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * Bind an output iframe's JSON requests to this box, using Field's existing WebSocket server.
 * Import `/output_requests.js` in the iframe and call `outputRequests(binding.id).request(data)`.
 * Kotlin tools call the same binding's suspend [OutputRequestBinding.request] directly.
 *
 * Requests are bounded and serialized per binding. The handler starts on the document's Runloop
 * with its box context; use `withContext` for expensive work. Re-running this named binding or
 * stopping/deleting its box closes it, including outstanding requests. Old browser references fail
 * explicitly; render the new binding id when replacing a binding. No listener or port is created.
 */
fun Box.outputRequests(name: String, handler: suspend (JsonElement) -> JsonElement): OutputRequestBinding =
    bindOutputRequests(this, name, Runloop.runLoop.main, handler)

/** Look up a live named request binding on this exact box in this document. */
fun Box.outputRequests(name: String): OutputRequestBinding =
    (properties["_outputRequests:$name"] as? OutputRequestBinding)?.also { it.requireLive() }
        ?: kotlin.error("No live output request binding '$name' on box '$id'; run its source box first")

/** One box's asynchronous, serialized JSON callback, shared by browser tools and Kotlin callers. */
class OutputRequestBinding internal constructor(
    private val owner: Box, val name: String, dispatcher: CoroutineContext,
    private val handler: suspend (JsonElement) -> JsonElement,
) : AutoCloseable {
    /** Opaque, run-specific browser capability; never persists a JVM callback or opens a server. */
    val id: String = UUID.randomUUID().toString()
    private data class Pending(val data: JsonElement, val result: CompletableDeferred<JsonElement>)
    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher + thisSessionInfo.asContextElement(owner.inside))
    private val queue = Channel<Pending>(64)
    internal var stopHook: Runnable? = null

    init {
        scope.launch {
            try {
                for (pending in queue) {
                    try {
                        requireLive()
                        val value = Box.withBoxContext(owner) { handler(pending.data) }
                        pending.result.complete(value)
                    } catch (e: CancellationException) {
                        pending.result.completeExceptionally(e); throw e
                    } catch (e: Exception) {
                        pending.result.completeExceptionally(e)
                    }
                }
            } finally {
                while (true) {
                    val pending = queue.tryReceive().getOrNull() ?: break
                    pending.result.completeExceptionally(IllegalStateException("Output request binding closed"))
                }
            }
        }
    }

    internal fun requireLive() {
        check(!closed.get() && (owner.inside.boxes[owner.id] === owner || owner.inside.root === owner)) {
            "Output request source is no longer live; refresh the box output"
        }
        check(!ReadOnlyDocuments.blocksWritesFrom(owner)) { "This document is read-only" }
    }

    internal fun submit(data: JsonElement): Deferred<JsonElement> {
        val result = CompletableDeferred<JsonElement>()
        try {
            requireLive()
            require(data.toString().length <= 6_000_000) { "Output request is too large" }
            check(queue.trySend(Pending(data, result)).isSuccess) { "Output request queue is full or closed" }
        } catch (e: Exception) { result.completeExceptionally(e) }
        return result
    }

    /** Await the same serialized callback used by this binding's browser clients, without HTTP. */
    suspend fun request(data: JsonElement = JsonNull): JsonElement = submit(data).await()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        outputRequestBindings.remove(id, this)
        queue.close(); scope.cancel("Output request binding closed")
        val key = "_outputRequests:$name"
        if (owner.properties[key] === this) owner.properties.remove(key)
        stopHook?.run(); stopHook = null
    }
}

private val outputRequestBindings = ConcurrentHashMap<String, OutputRequestBinding>()

internal fun bindOutputRequests(owner: Box, name: String, dispatcher: CoroutineContext,
    handler: suspend (JsonElement) -> JsonElement): OutputRequestBinding {
    require(name.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) { "Output request name must be a simple word" }
    check(!ReadOnlyDocuments.blocksWritesFrom(owner)) { "This document is read-only" }
    val key = "_outputRequests:$name"
    (owner.properties[key] as? OutputRequestBinding)?.close()
    val binding = OutputRequestBinding(owner, name, dispatcher, handler)
    owner.properties[key] = binding
    outputRequestBindings[binding.id] = binding
    binding.stopHook = owner.registerStopHook(Runnable { binding.close() })
    return binding
}

internal fun submitOutputRequest(request: OutputRequest): Deferred<JsonElement> =
    outputRequestBindings[request.binding]?.submit(request.data)
        ?: CompletableDeferred<JsonElement>().apply {
            completeExceptionally(IllegalStateException("Output request binding is unavailable; refresh the box output"))
        }

@OptIn(ExperimentalCoroutinesApi::class)
@RunsAt(installHandlers)
fun installOutputRequestHandler() {
    handlers["output-request"] = mutableSetOf({ message, reply ->
        try {
            val request = Json.decodeFromJsonElement<OutputRequest>(message.payload ?: JsonNull)
            // Enqueue synchronously, before the next ordered /ws message is dispatched.
            val result = submitOutputRequest(request)
            result.invokeOnCompletion { error ->
                val response = if (error != null) OutputRequestResult(false, error = error.message?.take(2000) ?: "Output request failed")
                else OutputRequestResult(true, result.getCompleted())
                reply(Message("output-request-result").payload(response))
            }
        } catch (e: Exception) {
            reply(Message("output-request-result").payload(OutputRequestResult(false, error = e.message?.take(2000))))
        }
        Handled.yes
    })
}
