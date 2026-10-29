package afield.ai.tools

import afield.ai.diagnostics.NotifyLog
import afield.ai.diagnostics.log
import afield.ai.models.aiProviderConfigRevision
import afield.ai.agent.*
import afield.ai.conversation.*
import afield.ai.tools.*
import afield.animation.gesture.*
import afield.animation.interpolation.*
import afield.animation.keyframes.*
import afield.app.diagnostics.*
import afield.app.notifications.*
import afield.app.protocol.*
import afield.app.server.*
import afield.app.settings.*
import afield.audio.synth.*
import afield.data.table.*
import afield.document.context.*
import afield.document.graph.*
import afield.document.history.*
import afield.document.persistence.*
import afield.document.properties.*
import afield.document.workspace.*
import afield.graphics.color.*
import afield.graphics.paint.*
import afield.graphics.vulkan.*
import afield.math.functions.*
import afield.media.capture.*
import afield.media.stream.*
import afield.media.video.*
import afield.runtime.completion.*
import afield.runtime.execution.*
import afield.runtime.kotlin.*
import afield.runtime.lifecycle.*
import afield.tools.automation.*
import afield.tools.docs.*
import afield.ui.controls.*
import afield.ui.layout.*
import afield.ui.notation.*
import afield.ui.output.*
import afield.ui.output.templates.*
import afield.ui.slides.*
import afield.ui.visualization.*
import afield.app.diagnostics.logging.lprintln
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * The server side of the documentation search box: the `docs_search_input` Field message handler.
 *
 * The prefix of `Message.uid` before `_` is the browser session key. One
 * [DocsSearchBridge.SearchState] is retained per key for the lifetime of this installer; there is no
 * eviction and no reset message, though every state is dropped when the AI provider settings change.
 * Work runs on the process-wide IO scope and replies are marshalled through [inMain]. Phase 1 is
 * provisional HTML and phase 20 is terminal HTML. The incoming phase is not consulted.
 */

private val docsSearchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Installs the `docs_search_input` handler, which answers a query with provisional then final HTML. */
fun installDocsSearch() {
    var docsSearchBridge: DocsSearchBridge? = null
    val searchStateBySession = ConcurrentHashMap<String, DocsSearchBridge.SearchState>()
    var bridgeRevision = aiProviderConfigRevision()
    
    handlers["docs_search_input"] = mutableSetOf({ message, reply ->
        val input = Json.decodeFromJsonElement<DocsSearch_input>(message.payload!!)
        val revision = aiProviderConfigRevision()
        if (revision != bridgeRevision) {
            docsSearchBridge = null
            searchStateBySession.clear()
            bridgeRevision = revision
            lprintln("info,docs.search", "AI provider settings changed; cleared documentation-search conversations")
        }
        
        if (docsSearchBridge == null) {
            val uiLog = NotifyLog(label = "Docs Search")
            docsSearchBridge = DocsSearchBridge(log = uiLog)
            notify("Initializing Documentation Search", "Docs Search")
        }
        
        // Use a session ID from the message or create a default
        val sessionId = message.uid.substringBefore("_")
        
        docsSearchScope.launch {
            try {
                // Get or create search state for this session
                val state = searchStateBySession.computeIfAbsent(sessionId) {
                    docsSearchBridge!!.startConversation()
                }
                
                // Send intermediate "thinking" response
                inMain {
                    reply(Message("docs_search_input").payload(DocsSearch_input(
                        value = "<div class='status-message status-message--searching'><span class='loading-spinner' aria-hidden='true'></span><span>Searching documentation and code...</span></div>",
                        phase = 1
                    )))
                }
                
                // Perform the search with streaming
                val accumulatedText = StringBuilder()
                val result = docsSearchBridge!!.search(state, input.value) { delta ->
                    accumulatedText.append(delta)
                    inMain {
                        reply(Message("docs_search_input").payload(DocsSearch_input(
                            value = formatMarkdownResponse(accumulatedText.toString()),
                            phase = 1
                        )))
                    }
                }
                
                // Update the state
                searchStateBySession[sessionId] = result.newState
                
                // Send final response
                inMain {
                    if (result.success) {
                        reply(Message("docs_search_input").payload(DocsSearch_input(
                            value = formatMarkdownResponse(result.text),
                            phase = 20
                        )))
                    } else {
                        reply(Message("docs_search_input").payload(DocsSearch_input(
                            value = "<div style='color: red;'>${escapeHtml(result.text)}</div>",
                            phase = 20
                        )))
                    }
                }
            } catch (e: Exception) {
                lprintln("error,docs.search", "Search failed: ${e.message}")
                e.printStackTrace()
                inMain {
                    reply(Message("docs_search_input").payload(DocsSearch_input(
                        value = "<div style='color: red;'>Error: ${escapeHtml(e.message ?: e::class.java.simpleName)}</div>",
                        phase = 20
                    )))
                }
            }
        }
        
        Handled.yes
    })
    
    lprintln("info,docs.search", "Documentation search installed")
}

/**
 * Format markdown response with syntax highlighting and copy buttons
 */
private fun formatMarkdownResponse(markdown: String): String {
    return buildString {
        append("<div class='docs-search-response'>")
        
        // Simple markdown to HTML conversion
        // This is basic - in production, you might want a proper markdown parser
        val lines = markdown.lines()
        var inCodeBlock = false
        var codeLanguage = ""
        val codeBuffer = StringBuilder()
        
        for (line in lines) {
            when {
                line.startsWith("```") -> {
                    if (inCodeBlock) {
                        // End code block
                        append(formatCodeBlock(codeBuffer.toString(), codeLanguage))
                        codeBuffer.clear()
                        inCodeBlock = false
                        codeLanguage = ""
                    } else {
                        // Start code block
                        inCodeBlock = true
                        codeLanguage = line.removePrefix("```").trim()
                    }
                }
                inCodeBlock -> {
                    codeBuffer.appendLine(line)
                }
                line.startsWith("### ") -> {
                    append("<h3>${escapeHtml(line.removePrefix("### "))}</h3>")
                }
                line.startsWith("## ") -> {
                    append("<h2>${escapeHtml(line.removePrefix("## "))}</h2>")
                }
                line.startsWith("# ") -> {
                    append("<h1>${escapeHtml(line.removePrefix("# "))}</h1>")
                }
                line.startsWith("- ") -> {
                    append("<li>${formatInlineMarkdown(line.removePrefix("- "))}</li>")
                }
                line.isBlank() -> {
                    append("<br>")
                }
                else -> {
                    append("<p>${formatInlineMarkdown(line)}</p>")
                }
            }
        }
        
        // Handle unclosed code block
        if (inCodeBlock) {
            append(formatCodeBlock(codeBuffer.toString(), codeLanguage))
        }
        
        append("</div>")
    }
}

/**
 * Format inline markdown (bold, inline code, etc.)
 */
private fun formatInlineMarkdown(text: String): String {
    var result = escapeHtml(text)
    
    // Inline code: `code`
    result = result.replace(Regex("`([^`]+)`")) { match ->
        "<code>${match.groupValues[1]}</code>"
    }
    
    // Bold: **text**
    result = result.replace(Regex("\\*\\*([^*]+)\\*\\*")) { match ->
        "<strong>${match.groupValues[1]}</strong>"
    }
    
    // Italic: *text*
    result = result.replace(Regex("\\*([^*]+)\\*")) { match ->
        "<em>${match.groupValues[1]}</em>"
    }
    
    return result
}

/**
 * Format a code block with syntax highlighting and copy button
 */
private fun formatCodeBlock(code: String, language: String): String {
    val escapedCode = escapeHtml(code.trimEnd())
    val codeId = "code_${java.util.UUID.randomUUID()}"
    
    return buildString {
        append("<div class='code-block-container' style='position: relative; margin: 10px 0;'>")
        
        // Header with language and copy button
        append("<div class='code-block-header' style='")
        append("display: flex; justify-content: space-between; align-items: center; ")
        append("background: rgba(0, 0, 0, 0.5); padding: 5px 10px; ")
        append("border-top-left-radius: 5px; border-top-right-radius: 5px;")
        append("'>")
        
        if (language.isNotEmpty()) {
            append("<span style='color: var(--field-cyan, #48c9ff); font-size: 0.85em;'>")
            append(escapeHtml(language))
            append("</span>")
        } else {
            append("<span></span>")
        }
        
        append("<button class='copy-code-button' ")
        append("onclick='navigator.clipboard.writeText(document.getElementById(\"$codeId\").textContent); ")
        append("this.textContent = \"Copied!\"; setTimeout(() => this.textContent = \"Copy\", 2000);' ")
        append("style='")
        append("background: rgba(72, 201, 255, 0.2); border: 1px solid var(--field-cyan, #48c9ff); ")
        append("color: var(--field-cyan, #48c9ff); padding: 4px 12px; border-radius: 3px; ")
        append("cursor: pointer; font-size: 0.85em; font-family: inherit;")
        append("'>Copy</button>")
        
        append("</div>")
        
        // Code content
        append("<pre style='")
        append("background: rgba(0, 0, 0, 0.4); padding: 15px; margin: 0; ")
        append("border-bottom-left-radius: 5px; border-bottom-right-radius: 5px; ")
        append("border-left: 3px solid var(--field-cyan, #48c9ff); overflow-x: auto;")
        append("'><code id='$codeId' class='language-$language'>")
        append(escapedCode)
        append("</code></pre>")
        
        append("</div>")
    }
}

/**
 * Escape HTML special characters
 */
private fun escapeHtml(text: String): String {
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
