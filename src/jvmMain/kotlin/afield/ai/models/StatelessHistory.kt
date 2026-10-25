package afield.ai.models

import com.openai.models.responses.ResponseInputItem

/**
 * Keeping a stateless provider's conversation (local models, OpenRouter) inside its context window.
 *
 * A stateless provider is sent its whole conversation on every request, so the conversation is ours to
 * size. Before this, it was capped only by item count, and the first real conversation with a local model
 * (qwen3.8-27b on llama.cpp, 64k context, 2026-10-22) ran out of room in its ninth turn: each turn's user
 * message carried the full ~24k-character instructions, and every one of those copies stayed in history.
 * Three things keep it inside now:
 *
 * - [settle], once per turn boundary: the finished turn's prompt is swapped for its durable text (the
 *   request without the instructions, which the newest message always carries), and older tool outputs and
 *   images are cut down. Done once, so the history stays a stable prefix for the server's prompt cache.
 * - [dropOldestTurns], when an estimate says the next request will not fit: whole turns go from the front,
 *   always at a user message, so no function call loses its output.
 * - [contextOverflow] reads the server's own refusal ("request (65833 tokens) exceeds the available context
 *   size (65536 tokens)"), which gives the window and calibrates the estimate; the bridge then fits and retries.
 */
internal object StatelessHistory {
    /** An image is counted as this many characters (~1,500 tokens), whatever its data URL's length. */
    const val IMAGE_CHARS = 4_500
    /** Settled tool outputs longer than this are cut to [SETTLED_OUTPUT_HEAD] plus a note. */
    const val SETTLED_OUTPUT_CHARS = 1_500
    const val SETTLED_OUTPUT_HEAD = 1_200
    /** Until a response reports its usage: a conservative ratio for code- and JSON-heavy input. */
    const val DEFAULT_CHARS_PER_TOKEN = 3.0

    const val DROPPED_NOTE = "[Earlier turns of this conversation were dropped to fit the model's context window. " +
        "Read boxes again with tools rather than relying on memory of them.]"

    fun chars(item: ResponseInputItem): Int = when {
        item.isMessage() -> item.asMessage().content().sumOf { c ->
            when {
                c.isInputText() -> c.asInputText().text().length
                c.isInputImage() -> IMAGE_CHARS
                else -> c.toString().length
            }
        }
        item.isFunctionCallOutput() -> item.asFunctionCallOutput().output().let { if (it.isString()) it.asString().length else it.toString().length }
        else -> item.toString().length
    }

    fun chars(items: List<ResponseInputItem>): Long = items.sumOf { chars(it).toLong() }

    fun isUserMessage(item: ResponseInputItem): Boolean = when {
        item.isMessage() -> item.asMessage().role() == ResponseInputItem.Message.Role.USER
        item.isEasyInputMessage() -> item.asEasyInputMessage().role().toString().equals("user", ignoreCase = true)
        else -> false
    }

    private fun isDroppedNote(item: ResponseInputItem): Boolean =
        item.isMessage() && item.asMessage().content().singleOrNull()?.inputText()?.orElse(null)?.text() == DROPPED_NOTE

    fun userText(text: String): ResponseInputItem = ResponseInputItem.ofMessage(
        ResponseInputItem.Message.builder().role(ResponseInputItem.Message.Role.USER).addInputTextContent(text).build()
    )

    /** [item] with a long function output cut to its head, or null when it is already short enough. */
    fun shrunkOutput(item: ResponseInputItem): ResponseInputItem? {
        if (!item.isFunctionCallOutput()) return null
        val output = item.asFunctionCallOutput()
        val text = output.output().takeIf { it.isString() }?.asString() ?: return null
        if (text.length <= SETTLED_OUTPUT_CHARS) return null
        val cut = text.take(SETTLED_OUTPUT_HEAD) +
            "…[${text.length - SETTLED_OUTPUT_HEAD} more chars of this earlier tool result were trimmed to save context; call the tool again if you need them]"
        return ResponseInputItem.ofFunctionCallOutput(
            ResponseInputItem.FunctionCallOutput.builder().callId(output.callId()).output(cut).build()
        )
    }

    /** [item] without its images, or null when it has none. */
    fun withoutImages(item: ResponseInputItem): ResponseInputItem? {
        if (!item.isMessage()) return null
        val message = item.asMessage()
        val images = message.content().count { it.isInputImage() }
        if (images == 0) return null
        val builder = ResponseInputItem.Message.builder().role(message.role())
        message.content().filter { it.isInputText() }.forEach { builder.addInputTextContent(it.asInputText().text()) }
        builder.addInputTextContent("[$images image(s) from an earlier turn omitted to save context.]")
        return ResponseInputItem.ofMessage(builder.build())
    }

    /**
     * Shrink history[from, until) in place: [replace] first (the finished turn's full prompt for its
     * durable one, by identity), then long outputs and images.
     */
    fun settle(history: MutableList<ResponseInputItem>, from: Int, until: Int = history.size,
               replace: Pair<ResponseInputItem, ResponseInputItem>? = null) {
        for (i in from until until.coerceAtMost(history.size)) {
            var item = history[i]
            if (replace != null && item === replace.first) item = replace.second
            item = shrunkOutput(item) ?: item
            item = withoutImages(item) ?: item
            history[i] = item
        }
    }

    /**
     * Remove whole turns after the first [seedCount] items while [over] says the history is too big. A turn
     * ends where the next user message starts, so every removed call goes with its output. Nothing at or
     * after [protectFrom] (the current turn) is removed. Returns the number of items removed.
     */
    fun dropOldestTurns(history: MutableList<ResponseInputItem>, seedCount: Int, protectFrom: Int, over: () -> Boolean): Int {
        var protect = protectFrom.coerceAtMost(history.size)
        val start = if (history.size > seedCount && isDroppedNote(history[seedCount])) seedCount + 1 else seedCount
        var removed = 0
        while (over() && start < protect) {
            var end = start + 1
            while (end < protect && !isUserMessage(history[end])) end++
            repeat(end - start) { history.removeAt(start) }
            removed += end - start
            protect -= end - start
        }
        if (removed > 0 && start == seedCount) history.add(seedCount, userText(DROPPED_NOTE))
        return removed
    }

    data class Overflow(val requestedTokens: Int?, val availableTokens: Int)

    private val llamaCpp = Regex("(?i)request \\((\\d+) tokens\\) exceeds the available context size \\((\\d+) tokens\\)")
    private val vllm = Regex("(?is)maximum context length is (\\d+) tokens.*?(?:requested|resulted in|you requested) (\\d+) tokens")

    /** The context window a refused request reports, from llama.cpp's or vLLM's wording, or null. */
    fun contextOverflow(failure: Throwable): Overflow? {
        val seen = mutableSetOf<Throwable>()
        val message = generateSequence(failure) { it.cause }.takeWhile { seen.add(it) }.joinToString("\n") { it.message.orEmpty() }
        llamaCpp.find(message)?.let { return Overflow(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        vllm.find(message)?.let { return Overflow(it.groupValues[2].toInt(), it.groupValues[1].toInt()) }
        return null
    }

    /** Tokens a request may use for its input in a window of [contextTokens], leaving room to answer. */
    fun inputBudget(contextTokens: Int, maxOutputTokens: Long): Long =
        (contextTokens * 0.9).toLong() - maxOutputTokens.coerceAtMost(contextTokens / 4L)
}
