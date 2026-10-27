package afield.ai.conversation

import afield.ai.tools.CaptureRenderWindowRequest
import afield.ai.tools.CompileKotlinRequest
import afield.ai.tools.EditFileRequest
import afield.ai.tools.ReloadShadersRequest

import afield.ai.diagnostics.LogEvent
import afield.ai.diagnostics.LogSink
import afield.ai.diagnostics.log

/**
 * Which AI4 log lines become a toast on the canvas, and which stay in the log.
 *
 * A turn logs a great deal, and almost none of it is addressed to the person watching. Only events
 * carrying the `ai.user` channel are candidates, which is what `userNotice` writes; everything else
 * a bridge or a tool logs is diagnostics, and the log viewer is where it belongs.
 *
 * Candidates are then thinned twice. The same text repeated inside fifteen seconds is dropped, and
 * an ordinary notice arriving within five seconds of the last one is dropped as well, so a burst of
 * activity shows one line rather than six. A notice also carrying `warning` or `error` skips the
 * five-second gate and is held back only by the repeat test.
 *
 * `toolActivity` is the other half of the policy. It maps a handful of tool names to one short
 * phrase each, "Checking the view…" or "Updating the document…", and says nothing for the rest:
 * reads, discovery and unfamiliar tools are already visible in the chat panel and the tool pool,
 * and a tool's implementation name is not a description of what was asked for.
 */

/** Only deliberately authored user messages reach toasts; diagnostics stay in the log. */
internal class AiToastPolicy(private val dedupeWindowMs: Long = 1_000) {
    private var lastActivityAt: Long? = null
    private var lastMessage: String? = null
    private var lastMessageAt: Long = 0

    @Synchronized
    fun message(event: LogEvent, now: Long): String? {
        val channels = event.channel.split(',')
        if ("ai.user" !in channels) return null
        val text = event.message.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return null
        if (lastMessage == text && now - lastMessageAt < maxOf(15_000, dedupeWindowMs)) return null
        val attention = "warning" in channels || "error" in channels
        if (!attention && lastActivityAt?.let { now - it < 5_000 } == true) return null
        if (!attention) lastActivityAt = now
        lastMessage = text
        lastMessageAt = now
        return text
    }
}

internal fun LogSink.userNotice(text: String, attention: Boolean = false) =
    log(if (attention) "warning,ai.user" else "info,ai.user", text)

/** No arguments, paths, generated identifiers or result payloads enter this surface. */
internal fun LogSink.toolActivity(name: String) {
    val text = when (name) {
        "ViewBoxOutputRequest", "CaptureRenderWindowRequest" -> "Checking the view…"
        "EditFileRequest", "SetBoxPropertyRequest" -> "Updating the document…"
        "CompileKotlinRequest" -> "Checking the code…"
        "LaunchBoxRequest", "RunBoxRequest" -> "Running a document step…"
        "ReloadShadersRequest" -> "Updating the rendered view…"
        "DocumentGuideWriteRequest" -> "Saving guidance for this document…"
        // Reads, discovery and unfamiliar tools are already visible in chat / Tool pool.
        // Do not guess user intent from a tool's implementation name.
        else -> return
    }
    userNotice(text)
}
