package afield.ai.agent

import afield.AiChatModelIdentity
import afield.Box
import afield.Message
import afield.SessionInformation
import afield.Set_output
import afield._send
import afield.ai.core.AiBoxConversation
import afield.displayOpen
import afield.payload

/**
 * Writes an agent task into a box's AI4 chat transcript (`ai4_chat_history`), in the transcript's
 * own markup (`<<you>>`, `<<ai>>`, `<<progress>>`, `<<model:…>>`), and pushes it to the box's
 * `ai-chat` panel. The panel then renders the task like any other turn, and the person can continue
 * the conversation from there.
 *
 * A live task needs only [appendVerdict]: the real `ai-assist` handler already wrote the turn.
 * A scripted task has no handler turn, so [appendScriptedTurn] writes the whole exchange, with each
 * scripted tool call as a progress entry, the way a live turn's tool calls appear.
 */
object AgentTaskTranscript {

    val SCRIPTED_IDENTITY = AiChatModelIdentity("scripted", "no model", responseModel = "scripted replay")

    fun AgentCheck.spec(): String = when (kind) {
        "connected" -> "$kind:$parent:$child"
        "output-contains" -> "$kind:$box:$text"
        "output-ints-include" -> "$kind:$box:${ints.joinToString(",")}"
        "property-contains", "property-lacks" -> "$kind:$box:$property:$text"
        "output-colour" -> "$kind:$box:$text:$fraction"
        "output-rising" -> "$kind:$box:$text:${fraction.toInt()}"
        else -> "$kind:$box"
    }

    private fun AgentCheck.subject(): String = if (kind == "connected") "$parent→$child" else box

    /** The verdict block: a one-line summary (`✓ box-exists:Primes  ✓ … — PASS`), then one line per check with its detail. */
    fun verdictBlock(result: AgentTaskResult): String = buildString {
        append("<<progress>>\n")
        // The panel shows a progress entry's first line and folds the rest, so the first line carries
        // the whole verdict: every check's mark and name, then the outcome.
        append(result.checks.joinToString("  ") { (if (it.passed) "✓ " else "✗ ") + it.check.kind + ":" + it.check.subject() })
        append(if (result.checks.isEmpty()) "No checks — ${result.verdict.uppercase()}" else " — ${result.verdict.uppercase()}")
        if (result.mode == "scripted") append(" (scripted replay, no model was called)")
        append("\n")
        if (result.error.isNotBlank()) append("error: ${result.error.take(600)}\n")
        result.checks.forEach { c ->
            append(if (c.passed) "✓ " else "✗ ")
            append(c.check.spec())
            append(" — ")
            append(c.detail.lineSequence().first().take(200))
            append("\n")
        }
    }

    fun appendVerdict(info: SessionInformation, box: Box, result: AgentTaskResult) =
        write(info, box, AiBoxConversation.read(box).withTrailingNewline() + verdictBlock(result))

    fun appendScriptedTurn(info: SessionInformation, box: Box, goal: String, result: AgentTaskResult) {
        val turn = buildString {
            append("<<you>>\n").append(goal.trim()).append("\n")
            append("<<ai>>\n").append(SCRIPTED_IDENTITY.marker())
            append("<<progress>>\nScripted replay — no model was called\n")
            result.setupCalls.forEach { call -> append(progressFor(call, "Setup: ")) }
            result.toolCalls.forEach { call -> append(progressFor(call, "")) }
            append("<<ai>>\n").append(SCRIPTED_IDENTITY.marker())
            append(result.finalText.ifBlank { "(the script gave no final text)" }.trim()).append("\n")
        }
        write(info, box, AiBoxConversation.read(box).withTrailingNewline() + turn + verdictBlock(result))
    }

    /** A task that could not start: the person sees why in the panel, not only in a return value. */
    fun appendError(info: SessionInformation, box: Box, goal: String, message: String) {
        val turn = "<<you>>\n${goal.trim()}\n<<progress>>\nAgent task not started\n$message\n"
        write(info, box, AiBoxConversation.read(box).withTrailingNewline() + turn)
    }

    private fun progressFor(call: AgentToolCallRecord, prefix: String): String = buildString {
        append("<<progress>>\n")
        append(prefix).append(call.tool).append(if (call.ok) " finished" else " failed").append("\n")
        append(call.arguments.take(400)).append("\n")
        if (!call.ok) {
            val message = runCatching { afield.ai.core.aiToolMapper.readTree(call.result).get("message")?.asText() }.getOrNull()
            if (!message.isNullOrBlank()) append(message.take(400)).append("\n")
        }
    }

    private fun String.withTrailingNewline() = if (isEmpty() || endsWith("\n")) this else this + "\n"

    private fun write(info: SessionInformation, box: Box, transcript: String) {
        AiBoxConversation.write(box, transcript)
        _send(Message("set-output").payload(Set_output(box.id, "outputarea", "ai-chat", transcript)), document = Regex.escape(info.filename))
        runCatching { box.displayOpen("ai-chat") }
    }
}
