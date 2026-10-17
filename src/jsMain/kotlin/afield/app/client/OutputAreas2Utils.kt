package afield.app.client

import afield.app.canvas.invoke

import afield.ai.conversation.AiChatToolFamilies
import afield.ai.conversation.aiChatHistoryHtml
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
