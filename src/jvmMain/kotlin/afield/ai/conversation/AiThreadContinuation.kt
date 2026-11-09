package afield.ai.conversation

import afield.ai.models.LlmBridge
import afield.ai.models.LlmThreadState
import afield.document.graph.Box
import afield.app.diagnostics.logging.lprintln
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Provider-neutral optional checkpoint, valid only for the exact saved branch and assistance mode. */
object AiThreadContinuation {
    const val PROPERTY = "ai4_continuation"
    const val DIAGNOSTICS_PROPERTY = "_ai4_continuation_diagnostics"

    private fun record(box: Box, event: String, reason: String, bridge: LlmBridge, state: LlmThreadState? = null) {
        val line = buildJsonObject {
            put("at", java.time.Instant.now().toString())
            put("box", box.id)
            put("event", event)
            put("reason", reason)
            bridge.modelIdentity?.let { put("provider", it.provider); put("model", it.requestedModel) }
            state?.let {
                put("fileKey", it.fileKey)
                put("conversationId", it.conversationId)
                it.previousResponseId?.let { id -> put("responseId", id) }
            }
        }.toString()
        synchronized(box) {
            val previous = (box.getPropertyRaw(DIAGNOSTICS_PROPERTY) as? String).orEmpty()
            box.setPropertyRaw(DIAGNOSTICS_PROPERTY, (previous.lineSequence().filter { it.isNotBlank() }.toList() + line)
                .takeLast(12).joinToString("\n"), doResync = false)
        }
        lprintln(if (event == "checkpoint_failed" || reason.startsWith("hosted_lookup_") || reason.startsWith("checkpoint_error_"))
            "warning,ai4.continuation" else "info,ai4.continuation", line)
    }

    fun diagnostics(box: Box): String = (box.getPropertyRaw(DIAGNOSTICS_PROPERTY) as? String)?.takeIf { it.isNotBlank() }
        ?.let { "### Conversation continuation\n\n```json\n$it\n```" }.orEmpty()

    fun save(box: Box, bridge: LlmBridge, state: LlmThreadState, mode: String) {
        val checkpoint = try { bridge.checkpointThread(state) } catch (error: Exception) {
            record(box, "checkpoint_failed", error::class.java.simpleName, bridge, state)
            return
        } ?: run { record(box, "checkpoint_skipped", "unsupported_provider", bridge, state); return }
        box.setPropertyRaw(PROPERTY, buildJsonObject {
            put("version", 1)
            put("mode", mode)
            put("fileKey", state.fileKey)
            put("savedAt", java.time.Instant.now().toString())
            put("transcriptHash", sha256(AiBoxConversation.read(box)))
            put("checkpoint", checkpoint)
        }.toString(), doResync = false)
        record(box, "checkpoint_saved", "ready", bridge, state)
    }

    /** Never substitutes a provider or a model; the bridge decides whether its own checkpoint is usable. */
    fun restore(box: Box, bridge: LlmBridge, fileKey: String, mode: String, transcript: String): LlmThreadState? = runCatching {
        fun fallback(reason: String): LlmThreadState? { record(box, "transcript_fallback", reason, bridge); return null }
        val text = (box.getPropertyRaw(PROPERTY) as? String)?.takeIf { it.isNotBlank() } ?: return fallback("no_checkpoint")
        val saved = Json.parseToJsonElement(text) as? JsonObject ?: return fallback("invalid_checkpoint")
        val mismatch = when {
            saved["version"]?.jsonPrimitive?.content != "1" -> "checkpoint_version_changed"
            saved["mode"]?.jsonPrimitive?.content != mode -> "assistance_mode_changed"
            saved["fileKey"]?.jsonPrimitive?.content != fileKey -> "code_property_changed"
            saved["transcriptHash"]?.jsonPrimitive?.content != sha256(transcript) -> "transcript_changed_or_rewound"
            else -> null
        }
        if (mismatch != null) return fallback(mismatch)
        var reason = "provider_declined_checkpoint"
        val state = bridge.restoreThread(saved.getValue("checkpoint").jsonPrimitive.content) { reason = it }
            ?.takeIf { it.fileKey == fileKey }
        record(box, if (state == null) "transcript_fallback" else "checkpoint_resumed", reason, bridge, state)
        state
    }.onFailure { record(box, "transcript_fallback", "checkpoint_error_${it::class.java.simpleName}", bridge) }.getOrNull()

    fun clear(box: Box) = box.setPropertyRaw(PROPERTY, "", doResync = false)
}
