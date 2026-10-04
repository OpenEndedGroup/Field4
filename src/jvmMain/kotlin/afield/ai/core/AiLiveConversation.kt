package afield.ai.core

import afield.AiChatModelIdentity

/** Ordered live transcript: tool waits cannot move earlier explanations below newer activity. */
internal class AiLiveConversation {
    private sealed interface Entry
    private data class Text(val value: StringBuilder) : Entry
    private data class Activity(val value: LlmProgressUpdate) : Entry
    private data class User(val value: String) : Entry
    private val entries = mutableListOf<Entry>()

    @Synchronized fun text(delta: String) {
        if (delta.isEmpty()) return
        val tail = entries.lastOrNull() as? Text
        if (tail != null) tail.value.append(delta) else entries += Text(StringBuilder(delta))
    }

    /** A message the person sent while the turn ran, at the point the model received it ([AiTurnInbox]). */
    @Synchronized
    fun user(message: String) {
        if (message.isNotBlank()) entries += User(message.trim())
    }

    @Synchronized fun progress(update: LlmProgressUpdate) {
        if (update.title.isBlank()) return
        val tail = entries.lastOrNull() as? Activity
        // Replace only transient activity, never an explanation, result or failure.
        if (update.phase != null && tail?.value?.phase in setOf("model", "tool", "build")) {
            entries.removeAt(entries.lastIndex)
        }
        entries += Activity(update)
    }

    @Synchronized fun render(prefix: String, model: AiChatModelIdentity?, live: Boolean = true,
        finalText: String? = null, additions: String = ""): String {
        val snapshot = entries.toMutableList()
        // The bridge's final text is the final response, not all earlier commentary.
        // Replace its streamed copy (possibly followed by Completed), preserving earlier turns.
        if (finalText != null) {
            val lastText = snapshot.indexOfLast { it is Text }
            val streamed = (snapshot.getOrNull(lastText) as? Text)?.value?.toString()?.trim().orEmpty()
            val suffix = snapshot.drop(lastText + 1)
            if (streamed.isNotEmpty() && finalText.trimStart().startsWith(streamed) &&
                suffix.all { it is Activity && (it.value.phase == "completed" || it.value.phase == null) }) {
                snapshot.removeAt(lastText)
            }
        }
        return buildString {
            append(prefix.trimEnd()); if (isNotEmpty()) append("\n")
            snapshot.forEachIndexed { index, entry ->
                when (entry) {
                    is Text -> {
                        append("<<ai>>\n"); model?.let { append(it.marker()) }
                        append(entry.value); append("\n")
                    }
                    is User -> { append("<<you>>\n"); append(entry.value); append("\n") }
                    is Activity -> {
                        append(if (live && index == snapshot.lastIndex) "<<progress-open>>\n" else "<<progress>>\n")
                        append(entry.value.title.trim()); append("\n")
                        if (entry.value.body.isNotBlank()) { append(entry.value.body.trim()); append("\n") }
                    }
                }
            }
            if (additions.isNotBlank()) { append(additions.trimEnd()); append("\n") }
            if (finalText != null) {
                append("<<ai>>\n"); model?.let { append(it.marker()) }; append(finalText)
            }
        }
    }
}
