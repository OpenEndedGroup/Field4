package afield.ai.conversation

import afield.ai.tools.CaptureRenderWindowRequest
import afield.ai.tools.CompileKotlinRequest
import afield.ai.tools.EditFileRequest
import afield.ai.tools.ReloadShadersRequest

import afield.ai.diagnostics.LogEvent
import afield.ai.diagnostics.LogSink
import afield.ai.diagnostics.log
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
