package afield.ai.core

import afield.ai.SemanticRetrievalHit
import afield.ai.SemanticRetrievalQueryRequest
import afield.ai.SemanticRetrievalService
import java.util.UUID

/**
 * Docs-search engine for the documentation iframe.
 *
 * Retrieval is **local**: Field's native Lucene hybrid index (BM25 + symbol +
 * vector via the optional ONNX embedder) over the `field-internals`, `workspace`,
 * and `documentation` corpora ([SemanticRetrievalService]), plus the local GLSL
 * include-library manifest ([GlslIncludeLibraryIndex]) for shader questions.
 * Synthesis runs through the active [LlmBridge], so docs search works on whichever
 * provider is selected on /welcome (OpenAI, OpenRouter, or Anthropic/Claude) with
 * no per-query retrieval cost and no source upload to any hosted vector store.
 *
 * Public surface ([SearchState], [SearchResult], [startConversation], [search])
 * is unchanged from the previous OpenAI-hosted-`file_search` implementation, so
 * [installDocsSearch] and the panel's markdown formatting are untouched.
 */
class DocsSearchBridge(
    private val log: LogSink = StdoutLog,
    model: String = Ai4ModelDefaults.envOrDefault("AFIELD_DOCS_SEARCH_MODEL", Ai4ModelDefaults.flagshipModel()),
    private val bridge: LlmBridge = createLlmBridge(model, log)
) {
    private val trace: TraceLogger? = TraceLogger.fromEnv()

    private fun info(message: String) = log.log("info,docs.search", message)
    private fun warn(message: String) = log.log("warning,docs.search", message)
    private fun error(message: String) = log.log("error,docs.search", message)

    private val maxHits: Int =
        (System.getenv("AFIELD_DOCS_SEARCH_MAX_HITS") ?: "12").toIntOrNull()?.coerceIn(1, 40) ?: 12
    private val perHitChars: Int =
        (System.getenv("AFIELD_DOCS_SEARCH_HIT_CHARS") ?: "1400").toIntOrNull()?.coerceIn(200, 8000) ?: 1400

    init {
        info("Initialized DocsSearchBridge(model=$model, retrieval=local-lucene)")
    }

    /** Opaque to the installer; carries the synthesis thread whose history the bridge maintains. */
    data class SearchState(val thread: LlmThreadState)

    data class SearchResult(
        val success: Boolean,
        val text: String,
        val newState: SearchState
    )

    private val systemInstructions = """
        You are a specialized Field4 documentation and code search assistant.

        You are given retrieved excerpts from Field4's own source, the current workspace,
        its documentation, and (for shader questions) its GLSL include libraries. Ground
        every answer in those excerpts:
        - Prefer the retrieved code and docs over prior knowledge.
        - If the excerpts do not support a claim, say so explicitly rather than guessing.
        - Cite concrete file paths for anything you reference.

        Response quality:
        1. Lead with the most relevant real code/doc from the excerpts.
        2. Explain practical usage and caveats.
        3. Suggest related APIs only when the excerpts support them.
        4. Be concise but complete.

        Output format:
        - Markdown with clear headings and language-tagged code blocks.
        - End with a short "Sources" section listing the referenced file paths.
    """.trimIndent()

    fun startConversation(): SearchState {
        val thread = bridge.startThread(
            fileKey = "docs-search:${UUID.randomUUID()}",
            fileText = "",
            instructions = systemInstructions
        )
        info("startConversation(): thread=${thread.localThreadId}")
        return SearchState(thread)
    }

    fun search(
        state: SearchState,
        query: String,
        onTextDelta: ((String) -> Unit)? = null
    ): SearchResult {
        val t0 = System.nanoTime()
        info("search(): query='$query' thread=${state.thread.localThreadId}")

        val retrieval = runCatching {
            SemanticRetrievalService.query(
                SemanticRetrievalQueryRequest(
                    query = query,
                    limit = maxHits,
                    rebuildIfEmpty = true,
                    includeCapsule = true,
                    includeSource = true
                )
            )
        }.onFailure { warn("semantic retrieval failed: ${it.message}") }.getOrNull()

        val hits = retrieval?.results.orEmpty()
        val glsl = runCatching { GlslIncludeLibraryIndex.contextAttachmentFor(query) }
            .onFailure { warn("glsl include lookup failed: ${it.message}") }
            .getOrNull()

        if (hits.isEmpty() && glsl == null) {
            warn("no local retrieval context for query; answering ungrounded")
        } else {
            info("retrieved ${hits.size} hit(s)${if (glsl != null) " + glsl includes" else ""}")
        }

        val context = buildContext(hits, glsl?.code)
        val prompt = buildString {
            if (context.isNotBlank()) {
                appendLine("Retrieved Field4 context for the question below. Ground your answer in it and cite file paths.")
                appendLine()
                appendLine(context)
                appendLine()
            } else {
                appendLine("No local retrieval context was found for this question. Answer from general Field4 knowledge and say clearly what you could not verify.")
                appendLine()
            }
            append("Question: ")
            append(query)
        }

        trace?.log("DOCS_SEARCH_QUERY", "thread=${state.thread.localThreadId} hits=${hits.size}\n$prompt")

        return try {
            val result = bridge.send(
                state = state.thread,
                userPrompt = prompt,
                maxOutputTokens = 16384L,
                onTextDelta = onTextDelta
            )
            val text = result.text
            if (text.isBlank()) warn("search(): synthesis returned no text")
            val ms = (System.nanoTime() - t0) / 1_000_000
            info("search() complete: chars=${text.length} time=${ms}ms")
            trace?.log("DOCS_SEARCH_RESPONSE", "thread=${state.thread.localThreadId} chars=${text.length}\n$text")
            SearchResult(success = text.isNotBlank(), text = text, newState = state)
        } catch (e: Exception) {
            error("search() failed: ${e.message}")
            SearchResult(success = false, text = "Error: ${e.message}", newState = state)
        }
    }

    private fun buildContext(hits: List<SemanticRetrievalHit>, glslIncludes: String?): String {
        val sb = StringBuilder()
        hits.forEachIndexed { i, hit ->
            val body = hit.sourcePreview.ifBlank { hit.capsule }.trim()
            sb.appendLine("[${i + 1}] ${hit.path}${if (hit.line > 0) ":${hit.line}" else ""}${if (hit.symbol.isNotBlank()) "  (${hit.symbol})" else ""}")
            if (hit.title.isNotBlank() && hit.title != hit.symbol) sb.appendLine(hit.title)
            if (body.isNotBlank()) {
                val lang = hit.language.ifBlank { "" }
                sb.appendLine("```$lang")
                sb.appendLine(truncate(body, perHitChars))
                sb.appendLine("```")
            }
            sb.appendLine()
        }
        if (!glslIncludes.isNullOrBlank()) {
            sb.appendLine("[GLSL include libraries]")
            sb.appendLine("```glsl")
            sb.appendLine(truncate(glslIncludes, perHitChars * 2))
            sb.appendLine("```")
        }
        return sb.toString().trim()
    }

    private fun truncate(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max) + "\n… (truncated)"
}
