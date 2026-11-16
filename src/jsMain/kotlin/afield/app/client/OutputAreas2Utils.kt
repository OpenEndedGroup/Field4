package afield.app.client

import afield.app.canvas.invoke

import afield.ai.conversation.AiChatToolFamilies
import afield.ai.conversation.aiChatHistoryHtml
import afield.ai.conversation.aiChatHistoryUnits
import afield.ai.conversation.aiChatMarkdownHtml
// The renderer is shared with JVM-served pages: aiChatMarkdownHtml / aiChatHistoryHtml (commonMain, aiChatHtml.kt).

@OptIn(ExperimentalJsExport::class)
@JsExport
fun aiChatMarkdownToHtml(markdown: String?): String = aiChatMarkdownHtml(markdown)

private var toolFamilies: AiChatToolFamilies? = null

/**
 * Install the tool-family table (`ai4-chat-tool-families.json`) that round summaries count by family. Until it
 * is installed a round's summary gives calls, time and failures only.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun aiChatSetToolFamilies(json: String) {
    toolFamilies = AiChatToolFamilies.parse(json)
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun aiChatHistoryToHtml(history: String?): String = aiChatHistoryHtml(history, toolFamilies)

// Markdown → HTML for turns already seen. A long chat re-renders on every streamed chunk and all but the tail is
// unchanged, so each block's HTML is kept by its text; the cache is dropped whole when it grows past the limit.
private val markdownCache = HashMap<String, String>()
private const val MARKDOWN_CACHE_LIMIT = 4000

private fun cachedMarkdownHtml(markdown: String?): String {
    val key = markdown ?: return aiChatMarkdownHtml(null)
    markdownCache[key]?.let { return it }
    if (markdownCache.size >= MARKDOWN_CACHE_LIMIT) markdownCache.clear()
    return aiChatMarkdownHtml(key).also { markdownCache[key] = it }
}

/**
 * The transcript as one HTML string per top-level unit (aiChatHistoryUnits, commonMain), for the chat panel's
 * incremental render: it rebuilds only the units whose HTML differs from what it is already showing.
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun aiChatHistoryToUnits(history: String?): Array<String> =
    aiChatHistoryUnits(history, toolFamilies, ::cachedMarkdownHtml).toTypedArray()
