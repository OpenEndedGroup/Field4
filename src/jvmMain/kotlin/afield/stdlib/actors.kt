package afield.stdlib

import afield.*
import afield.logger.lprintln
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.reflect.KClass

/**
 * Envelope wrapping a user payload with sender/receiver metadata.
 */
data class Envelope<T : Any>(
    val payload: T,
    val from: Box?,
    val to: Box,
    val time: Long = System.currentTimeMillis()
)

/**
 * Receiver for suspend message handlers.  Provides [frame] to suspend until
 * the next RunLoop tick and [reply] to send a value back to a waiting
 * [sendAndAwait] caller.
 */
class MessageScope<T : Any>(val envelope: Envelope<T>, private val deferred: CompletableDeferred<Any?>?, internal val handlerBox: Box?) {

    val payload: T get() = envelope.payload
    val from: Box? get() = envelope.from
    val to: Box get() = envelope.to
    val time: Long get() = envelope.time

    /**
     * Suspend until the next RunLoop frame.  Because the handler coroutine
     * is dispatched on [Runloop.runLoop.main] (the [afield.Framer]),
     * calling [kotlinx.coroutines.yield] parks it until the Framer is
     * pumped again.
     */
    suspend fun frame() {
        yield()
    }

    /**
     * Provide a return value to a [sendAndAwait] caller.
     * If the message was sent with plain [send], this is a no-op.
     * Can be called at most once; subsequent calls are ignored.
     */
    fun reply(value: Any?) {
        deferred?.complete(value)
        // emit reply arc: handler → original sender
        val senderBox = envelope.from
        if (senderBox != null && handlerBox != null) {
            val arcPayload = ActorArc(
                senderUid = senderBox.id,
                targetUid = senderBox.id,
                handlerUid = handlerBox.id,
                isReply = true
            )
            lprintln("actors", "actor-arc reply: handler=${arcPayload.handlerUid} -> sender=${arcPayload.senderUid}")
            afield.send(Message("actor-arc").payload(arcPayload))
        } else {
            lprintln("actors", "actor-arc reply skipped: senderBox=${senderBox?.id} handlerBox=${handlerBox?.id}")
        }
    }
}

private const val ACTOR_HANDLERS_PROP = "_actorHandlers"
private const val ACTOR_MAILBOX_PROP = "_actorMailbox"
private const val RUNLOOP_HOOK_KEY = "afield.stdlib.actors"

// -- internal envelope with optional completion slot --------------------------

internal class PendingMessage(
    val envelope: Envelope<*>,
    val deferred: CompletableDeferred<Any?>?  // null for fire-and-forget send()
)

// -- per-box handler storage --------------------------------------------------

@Suppress("UNCHECKED_CAST")
private fun Box.handlers(): MutableMap<KClass<*>, suspend MessageScope<*>.() -> Unit> {
    var map = getPropertyRaw(ACTOR_HANDLERS_PROP) as? MutableMap<KClass<*>, suspend MessageScope<*>.() -> Unit>
    if (map == null) {
        map = mutableMapOf()
        setPropertyRaw(ACTOR_HANDLERS_PROP, map, doResync = false)
    }
    return map
}

@Suppress("UNCHECKED_CAST")
private fun Box.mailbox(): ConcurrentLinkedQueue<PendingMessage> {
    var q = getPropertyRaw(ACTOR_MAILBOX_PROP) as? ConcurrentLinkedQueue<PendingMessage>
    if (q == null) {
        q = ConcurrentLinkedQueue()
        setPropertyRaw(ACTOR_MAILBOX_PROP, q, doResync = false)
    }
    return q
}

// -- public API: register a handler -------------------------------------------

/**
 * Register a typed, suspendable message handler on this box.
 *
 * ```kotlin
 * onMessage<Foo> {
 *     // `this` is MessageScope<Foo>
 *     // payload, from, to, time are available directly
 *     println("got $payload")
 *
 *     frame()          // suspend until the next RunLoop tick
 *     reply(42)        // send a value back to sendAndAwait callers
 * }
 * ```
 *
 * The handler runs as a coroutine on the RunLoop's [afield.Framer] dispatcher,
 * so it can call [MessageScope.frame] to yield across frames.
 *
 * Handler lookup walks breadth-first up the parent graph:
 * if the target box has no handler for a type, ancestors are checked.
 */
inline fun <reified T : Any> Box.onMessage(noinline handler: suspend MessageScope<T>.() -> Unit) {
    onMessage(T::class, handler)
}

@Suppress("UNCHECKED_CAST")
fun <T : Any> Box.onMessage(type: KClass<T>, handler: suspend MessageScope<T>.() -> Unit) {
    handlers()[type] = handler as suspend MessageScope<*>.() -> Unit
    ensureActorPump()
}

// -- public API: send (fire-and-forget) ---------------------------------------

/**
 * Enqueue [payload] for delivery to this box on the next RunLoop frame.
 * The sender is inferred from [Box.context] (may be null if called outside
 * a box execution context).
 */
fun <T : Any> Box.send(payload: T) {
    val sender = runCatching { Box.context.get() }.getOrNull()
    mailbox().add(PendingMessage(Envelope(payload, from = sender, to = this), deferred = null))
    ensureActorPump()
}

// -- public API: go (coroutine with error reporting) --------------------------

/**
 * Launch a suspendable block on the RunLoop's Framer dispatcher with proper
 * error reporting back to the editor.
 *
 * Use this instead of bare `inMain { }` when you want exceptions to appear as
 * red marks and inline errors in the box, just like normal execution errors.
 *
 * ```kotlin
 * go {
 *     val result = otherBox.sendAndAwait<MyRequest, Int>(MyRequest("hi"))
 *     println(result)
 * }
 * ```
 */
@OptIn(DelicateCoroutinesApi::class)
fun Box.go(block: suspend CoroutineScope.() -> Unit) {
    val box = this
    inMain {
        Box.withBoxContext(box) {
            try {
                block()
            } catch (_: CancellationException) {
            } catch (e: Throwable) {
                lprintln("error,actors", "go {} threw on box '${box.name}': ${e.message}")
                lprintln("error,actors", e.stackTraceToString())
                reportBoxError(box, e)
            }
        }
    }
}

// -- public API: send and await handler completion ----------------------------

sealed class SendResult<out T> {
    /** Handler ran to completion.  [value] is whatever [MessageScope.reply] provided (null if reply was never called). */
    data class Ok<T>(val value: T?) : SendResult<T>()
    /** No handler was found for this message type on the target box or any of its ancestors. */
    data object NoHandler : SendResult<Nothing>()
    /** The handler threw an exception. */
    data class Error(val exception: Throwable) : SendResult<Nothing>()

    /** Transform the value if Ok, otherwise pass through the failure unchanged. */
    inline fun <R> map(transform: (T?) -> R): SendResult<R> = when (this) {
        is Ok -> Ok(transform(value))
        is NoHandler -> NoHandler
        is Error -> Error(exception)
    }

    /** Return the value if Ok, otherwise null. */
    fun getOrNull(): T? = when (this) {
        is Ok -> value
        else -> null
    }

    /** Return the value if Ok, otherwise [default]. */
    inline fun getOrElse(default: () -> @UnsafeVariance T?): T? = when (this) {
        is Ok -> value
        else -> default()
    }

    /** Run [action] if Ok, return this for chaining. */
    inline fun onOk(action: (T?) -> Unit): SendResult<T> {
        if (this is Ok) action(value)
        return this
    }

    /** Run [action] if not Ok, return this for chaining. */
    inline fun onFailure(action: (SendResult<@UnsafeVariance T>) -> Unit): SendResult<T> {
        if (this !is Ok) action(this)
        return this
    }
}

/**
 * Send [payload] and suspend until the handler finishes (or is not found).
 *
 * The handler can call [MessageScope.reply] to provide a typed return value.
 * If the handler completes without calling reply, the result value is `null`.
 *
 * Must be called from a coroutine (e.g. inside `inMain { }`, a `frames { }` block,
 * or another `onMessage` handler).
 */
@Suppress("UNCHECKED_CAST")
suspend fun <T : Any, R> Box.sendAndAwait(payload: T): SendResult<R> {
    val sender = runCatching { Box.context.get() }.getOrNull()
    val envelope = Envelope(payload, from = sender, to = this)

    // check for handler existence eagerly so we can return NoHandler without
    // waiting a frame
    if (findHandler(payload::class) == null) {
        return SendResult.NoHandler
    }

    val deferred = CompletableDeferred<Any?>()
    mailbox().add(PendingMessage(envelope, deferred))
    ensureActorPump()

    return try {
        val value = deferred.await()
        SendResult.Ok(value as? R)
    } catch (e: Throwable) {
        SendResult.Error(e)
    }
}

// -- dispatch -----------------------------------------------------------------

/**
 * Report a runtime exception to the editor as a red mark + inline error,
 * extracting the source line from the stack trace.
 */
private fun reportBoxError(box: Box, e: Throwable) {
    var fallbackFirstLine = -1
    var firstLineInThisBox = -1
    e.stackTrace.forEach { frame ->
        if (fallbackFirstLine == -1) {
            fallbackFirstLine = frame.lineNumber
        }
        if (frame.fileName?.contains(box.id) == true && firstLineInThisBox == -1) {
            firstLineInThisBox = frame.lineNumber
        }
    }
    val firstLine = if (firstLineInThisBox != -1) firstLineInThisBox else fallbackFirstLine
    val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.name
    box.error(message, line = firstLine)
}

/**
 * Find a handler for [type] by walking breadth-first up the parent graph,
 * starting at [this] box.  Returns the owning box and handler, or null if
 * no handler is registered anywhere in the ancestry.
 */
@Suppress("UNCHECKED_CAST")
private fun Box.findHandler(type: KClass<*>): Pair<Box, suspend MessageScope<*>.() -> Unit>? {
    val seen = mutableSetOf<Box>()
    var fringe = setOf(this)

    while (fringe.isNotEmpty()) {
        for (box in fringe) {
            val h = box.handlers()[type]
            if (h != null) return box to h
        }
        seen.addAll(fringe)
        fringe = fringe.flatMap { it.parents }.toSet() - seen
    }
    return null
}

/**
 * Drain this box's mailbox, launching each handler as a coroutine on the
 * RunLoop's Framer dispatcher so it can call [MessageScope.frame].
 */
@OptIn(DelicateCoroutinesApi::class)
private fun Box.drainMailbox() {
    val mbox = mailbox()
    var budget = 1000  // safety cap per frame
    while (budget-- > 0) {
        val pending = mbox.poll() ?: break
        val result = findHandler(pending.envelope.payload::class)
        if (result == null) {
            lprintln("warning,actors", "no handler for ${pending.envelope.payload::class.simpleName} on box '${name}' (${id})")
            pending.deferred?.complete(null)
            continue
        }
        val (handlerBox, handler) = result
        val targetBox = this

        // emit send arc: sender → handler (and handler → target if different)
        val arcPayload = ActorArc(
            senderUid = pending.envelope.from?.id,
            targetUid = targetBox.id,
            handlerUid = handlerBox.id,
            isReply = false
        )
        lprintln("actors", "actor-arc send: sender=${arcPayload.senderUid} target=${arcPayload.targetUid} handler=${arcPayload.handlerUid}")
        afield.send(Message("actor-arc").payload(arcPayload))

        // launch on the Framer so the handler can call frame() / yield()
        inMain {
            Box.withBoxContext(targetBox) {
                val scope = MessageScope(pending.envelope as Envelope<Any>, pending.deferred, handlerBox)
                try {
                    scope.handler()
                    // if handler never called reply(), complete with null
                    pending.deferred?.complete(null)
                } catch (e: CancellationException) {
                    // coroutine cancellation is normal, don't report
                } catch (e: Throwable) {
                    lprintln("error,actors", "actor handler threw on box '${targetBox.name}': ${e.message}")
                    lprintln("error,actors", e.stackTraceToString())
                    pending.deferred?.completeExceptionally(e)
                    reportBoxError(targetBox, e)
                }
            }
        }
    }
}

// -- RunLoop integration ------------------------------------------------------

private val registeredBoxes = ConcurrentLinkedQueue<Box>()

private fun ensureActorPump() {
    Runloop.runLoop.preRun[RUNLOOP_HOOK_KEY] = sequence {
        while (true) {
            val snapshot = registeredBoxes.toList()
            for (box in snapshot) {
                box.drainMailbox()
            }
            yield(Unit)
        }
    }
}

/**
 * Make sure this box is in the pump set so its mailbox gets drained each frame.
 */
private fun Box.ensureActorPump() {
    if (!registeredBoxes.contains(this)) {
        registeredBoxes.add(this)
    }
    afield.stdlib.ensureActorPump()
}
