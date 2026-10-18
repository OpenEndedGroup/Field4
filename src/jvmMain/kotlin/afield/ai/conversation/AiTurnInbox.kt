package afield.ai.conversation

import afield.app.protocol.AiAssist

/**
 * The queue for messages a person sends to a box's chat while its AI turn is still running.
 *
 * The bridge hands waiting messages to the model at the next tool-round boundary, and whatever is left
 * when the turn ends becomes the box's next turn. See [AiTurnInbox].
 */

/**
 * Messages a person sends to a box's chat while that box's AI turn is still running.
 *
 * Until 2026-10-04 the server refused such a message with a notification. The panel had already shown
 * its own copy, so the running turn's next streamed update wiped it, and the agent never saw it. Now the
 * message waits here. The bridge hands whatever is waiting to the model at its next tool-round boundary,
 * as a user message after that round's tool results ([drainForModel]), so a correction reaches an agent
 * in the middle of a long task. Whatever is still waiting when the turn ends (the model stopped calling
 * tools before it could be handed over) becomes the box's next turn ([close]).
 *
 * The transcript shows a waiting message as a `<<queued>>` block after the live turn ([queuedMarkdown]),
 * never saved, and a delivered one as an ordinary `<<you>>` block at the point in the turn where the
 * model received it.
 */
class AiTurnInbox {
    data class Pending(
        /** What the panel shows: the message as typed, `/box` references expanded, attachments as images. */
        val shown: String,
        /** What the model reads: a dictated message carries its speech-recognition preface. */
        val forModel: String,
        val images: List<String> = emptyList(),
        /** The request as the panel sent it, for the follow-up turn when the message is never delivered. */
        val assist: AiAssist? = null,
        /** The websocket it came from, so the follow-up turn's notifications go back to it. */
        val session: io.ktor.websocket.WebSocketSession? = null,
        /** Field wrote it (the outcome of builds a turn awaited), not the person. */
        val fromField: Boolean = false,
    )

    private val pending = mutableListOf<Pending>()
    private var closed = false

    /** Called, on the bridge's thread, with the messages it has just handed to the model. */
    @Volatile
    var onDelivered: ((List<Pending>) -> Unit)? = null
    /** Called by whoever queued a message, after [offer] and outside any lock, so the turn can show it. */
    @Volatile
    var onQueued: (() -> Unit)? = null

    /** Queue [message]; false once the turn has ended and taken what was left. */
    @Synchronized
    fun offer(message: Pending): Boolean {
        if (closed) return false
        pending += message
        return true
    }

    @Synchronized fun queued(): List<Pending> = pending.toList()

    /** The waiting messages as transcript blocks, to show after the live turn; empty when none wait. */
    fun queuedMarkdown(): String = queued().joinToString("") { "<<queued>>\n${it.shown.trim()}\n" }

    /** Take everything waiting, for the bridge to send now, and report it delivered. */
    fun drainForModel(): List<Pending> {
        val taken = synchronized(this) { pending.toList().also { pending.clear() } }
        if (taken.isNotEmpty()) onDelivered?.invoke(taken)
        return taken
    }

    /** The turn has ended: refuse further messages and return those never delivered, for the next turn. */
    @Synchronized
    fun close(): List<Pending> {
        closed = true
        return pending.toList().also { pending.clear() }
    }

    companion object {
        /**
         * The user message a bridge sends for [messages]. The model is told when they were written, because
         * a message that arrives between tool calls otherwise reads as if it preceded the work already done.
         */
        fun modelText(messages: List<Pending>): String = buildString {
            val (field, person) = messages.partition { it.fromField }
            if (person.isNotEmpty()) {
                append("[The user sent ")
                append(if (person.size == 1) "this message" else "these ${person.size} messages")
                append(" while you were working on their request. Take it into account from here on: it can ")
                append("correct, redirect or stop the work.]\n\n")
                append(person.joinToString("\n\n") { it.forModel.trim() })
            }
            if (field.isNotEmpty()) {
                if (person.isNotEmpty()) append("\n\n")
                append("[Field sent this while you were working, not the user: builds an earlier turn awaited have ")
                append("finished. Take their outcome into account from here on.]\n\n")
                append(field.joinToString("\n\n") { it.forModel.trim() })
            }
        }

        /** Several undelivered messages, as the one request their follow-up turn sends. */
        fun combined(messages: List<Pending>): AiAssist? {
            val last = messages.lastOrNull()?.assist ?: return null
            val texts = messages.mapNotNull { it.assist?.extraPrompt?.trim()?.takeIf { t -> t.isNotEmpty() } }
            return last.copy(
                extraPrompt = texts.joinToString("\n\n"),
                images = messages.flatMap { it.assist?.images.orEmpty() },
                dictated = messages.any { it.assist?.dictated == true },
            )
        }
    }
}
