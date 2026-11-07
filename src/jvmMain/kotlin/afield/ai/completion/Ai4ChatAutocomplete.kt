package afield.ai.completion

/**
 * Inline autocomplete for the AI4 chat composer: while a person types a message to a box's chat, the fast
 * model proposes how the sentence goes on, and the panel shows it as grey text after the cursor that Tab
 * accepts (`outputareas2.js`, `ai-chat-suggest`).
 *
 * Each request is one stateless call. The prompt carries the box's name, the start of the property the chat
 * is about, the last few turns of its conversation and the draft; nothing is kept between requests, so a
 * pause in typing costs one small call and no thread grows. [suggestion] trims the reply to one line and
 * strips whatever of the draft the model repeated, so the panel can append it as is.
 *
 * `AFIELD_AI4_CHAT_AUTOCOMPLETE=off` turns it off for this Field (the panel stops asking); `/autocomplete off`
 * in a chat turns it off in that browser. The model is `AFIELD_AI4_CHAT_AUTOCOMPLETE_MODEL`, defaulting to
 * the fast model.
 */

import afield.ai.conversation.parseAiChatBlocks
import afield.ai.diagnostics.SilentLog
import afield.ai.models.Ai4ModelDefaults
import afield.ai.models.LlmBridge
import afield.ai.models.LlmReasoningEffort
import afield.ai.models.aiProviderConfigRevision
import afield.ai.models.createLlmBridge

object Ai4ChatAutocomplete {
    private const val MAX_CODE_CHARS = 2_000
    private const val MAX_TURNS = 6
    private const val MAX_TURN_CHARS = 700
    private const val MAX_SUGGESTION_CHARS = 120

    internal val instructions = """
        You complete the message a person is typing to an AI assistant inside Field, a live-coding
        environment for graphics, sound and data. The assistant works on one box of code.

        Reply with ONLY the text that should come next after the draft: a few words, at most the rest of
        the current sentence. Do not repeat the draft. Do not answer the message. No quotes, markdown or
        explanation. If the draft ends in the middle of a word, start by finishing that word. If the next
        word is a new word, begin with a space. If nothing sensible follows, reply with nothing.
    """.trimIndent()

    fun enabled(): Boolean = System.getenv("AFIELD_AI4_CHAT_AUTOCOMPLETE")?.trim()?.lowercase() !in setOf("off", "0", "false", "no")

    @Volatile private var bridgeRevision = -1L
    @Volatile private var activeBridge: LlmBridge? = null

    @Synchronized
    private fun bridge(): LlmBridge {
        val revision = aiProviderConfigRevision()
        activeBridge?.takeIf { bridgeRevision == revision }?.let { return it }
        val model = Ai4ModelDefaults.envOrDefault("AFIELD_AI4_CHAT_AUTOCOMPLETE_MODEL", Ai4ModelDefaults.fastModel())
        return createLlmBridge(model = model, log = SilentLog, reasoningEffort = LlmReasoningEffort.LOW).also {
            activeBridge = it
            bridgeRevision = revision
        }
    }

    /** What comes after [draft], or "" when the model has nothing to add. Throws when the provider does. */
    fun complete(boxName: String, property: String, code: String, history: String, draft: String): String {
        val bridge = bridge()
        val state = bridge.startThread(fileKey = "ai4:chat-autocomplete", fileText = "", instructions = instructions)
        val reply = bridge.send(state, prompt(boxName, property, code, history, draft), maxOutputTokens = 400L, maxToolRounds = 0)
        return suggestion(reply.text, draft)
    }

    internal fun prompt(boxName: String, property: String, code: String, history: String, draft: String): String = buildString {
        appendLine("Box: ${boxName.ifBlank { "(unnamed)" }}, editing its `$property` property.")
        if (code.isNotBlank()) {
            appendLine()
            appendLine("Start of `$property`:")
            appendLine("```")
            appendLine(code.take(MAX_CODE_CHARS).trimEnd())
            appendLine("```")
        }
        val turns = parseAiChatBlocks(history)
            .filter { it.role == "you" || it.role == "ai" }
            .takeLast(MAX_TURNS)
        if (turns.isNotEmpty()) {
            appendLine()
            appendLine("Conversation so far (oldest first):")
            turns.forEach { block ->
                val text = block.content
                    .replace(Regex("!\\[[^\\]]*]\\(data:image/[^)]*\\)"), "[image]")
                    .trim()
                // The end of a long reply is what the next message answers.
                val bounded = if (text.length <= MAX_TURN_CHARS) text else "…" + text.takeLast(MAX_TURN_CHARS)
                appendLine("${if (block.role == "you") "Person" else "Assistant"}: $bounded")
            }
        }
        appendLine()
        appendLine("The person's draft, which ends where the cursor is:")
        append("<<<")
        append(draft)
        append(">>>")
    }

    /**
     * The model's [raw] reply as text to append to [draft]: the first line, without quotes or fences, and
     * without any part of the draft it echoed back.
     */
    internal fun suggestion(raw: String, draft: String): String {
        var text = raw.replace("\r", "")
        if (text.trimStart().startsWith("```")) text = text.trimStart().removePrefix("```").substringAfter('\n', "")
        text = text.removePrefix("<<<").substringBefore(">>>")
        text = text.trimEnd().substringBefore('\n')
        val quoted = text.trim()
        if (quoted.length >= 2 && quoted.first() in "\"'`“" && quoted.last() in "\"'`”") text = quoted.substring(1, quoted.length - 1)
        if (text.isBlank()) return ""

        text = withoutEcho(text, draft)
        // "the cir" answered with "circle": the model finished the word by writing all of it.
        val partial = draft.takeLastWhile { it.isLetterOrDigit() }
        val firstWord = text.takeWhile { it.isLetterOrDigit() }
        if (partial.isNotEmpty() && firstWord.length > partial.length && firstWord.startsWith(partial, ignoreCase = true)) {
            text = text.substring(partial.length)
        }
        if (draft.isEmpty() || draft.last().isWhitespace()) text = text.trimStart()
        else if (text.firstOrNull()?.isWhitespace() == true) text = " " + text.trimStart()
        if (text.length > MAX_SUGGESTION_CHARS) {
            val cut = text.lastIndexOf(' ', MAX_SUGGESTION_CHARS)
            text = text.substring(0, if (cut > 0) cut else MAX_SUGGESTION_CHARS)
        }
        return text.trimEnd().takeIf { it.isNotBlank() } ?: ""
    }

    /** Drops the longest tail of [draft] (the whole draft, or four or more characters) that [text] starts with. */
    private fun withoutEcho(text: String, draft: String): String {
        if (draft.isBlank()) return text
        val lead = text.trimStart()
        if (lead.startsWith(draft.trim(), ignoreCase = true)) return lead.substring(draft.trim().length)
        for (start in 0 until draft.length) {
            val tail = draft.substring(start).trimStart()
            if (tail.length < 4) break
            // Only a tail that begins a word: "ape" in "shape" is not an echo of "apex".
            if (start > 0 && !draft[start - 1].isWhitespace()) continue
            if (lead.startsWith(tail, ignoreCase = true)) return lead.substring(tail.length)
        }
        return text
    }
}
