package afield.ai.core

import afield.ai.KotlinSourceIndex
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.helpers.ResponseAccumulator
import com.openai.models.ChatModel
import com.openai.models.Reasoning
import com.openai.models.ReasoningEffort
import com.openai.models.ResponseFormatText
import com.openai.models.conversations.ConversationCreateParams
import com.openai.models.conversations.items.ItemCreateParams
import com.openai.models.responses.*
import com.openai.core.JsonValue
import java.util.*

/**
 * AI bridge specifically designed for documentation and code search.
 * Uses the filestore mirror to provide context about Field4 codebase.
 */
class DocsSearchBridge(
    private val client: OpenAIClient = OpenAIOkHttpClient.fromEnv(),
    model: String = System.getenv("AFIELD_DOCS_SEARCH_MODEL")?.takeIf { it.isNotBlank() } ?: ChatModel.GPT_5_2.toString(),
    private val log: LogSink = StdoutLog,
    private val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM
) {
    private val fallbackModel: ChatModel = ChatModel.of(
        System.getenv("AFIELD_DOCS_SEARCH_FALLBACK_MODEL")?.takeIf { it.isNotBlank() } ?: ChatModel.GPT_5_2.toString()
    )
    @Volatile
    private var activeModel: ChatModel = ChatModel.of(model)
    private val trace: TraceLogger? = TraceLogger.fromEnv()
    private val textConfig: ResponseTextConfig =
        ResponseTextConfig.builder().format(ResponseFormatText.builder().build()).build()
    private val reasoningConfig: Reasoning =
        Reasoning.builder()
            .effort(reasoningEffort)
            .summary(Reasoning.Summary.DETAILED)
            .build()
    
    private fun info(message: String) = log.log("info,docs.search", message)
    private fun warn(message: String) = log.log("warning,docs.search", message)
    private fun error(message: String) = log.log("error,docs.search", message)
    
    init {
        info("Initialized DocsSearchBridge(model=$activeModel fallback=$fallbackModel)")
    }
    
    data class SearchState(
        val conversationId: String,
        val previousResponseId: String? = null
    )
    
    /**
     * System instructions for the documentation search assistant
     */
    private val systemInstructions = """
        You are a specialized Field4 documentation and code search assistant.

        Grounding requirements:
        - For Field4 API, behavior, or implementation questions, use file_search results from the vector store first.
        - Prefer retrieved code and docs over prior knowledge.
        - If retrieval is weak or missing, explicitly say what you could not verify.
        - Include concrete file paths for referenced implementations.

        Response quality requirements:
        1. Prioritize relevant real code examples from the indexed codebase
        2. Include KDoc or inline documentation context when available
        3. Explain practical usage and caveats
        4. Suggest related APIs only when supported by retrieved context
        5. Be concise but complete

        Output format:
        - Use markdown with clear headings
        - Use language-tagged code blocks
        - End with a short Sources section listing referenced file paths
    """.trimIndent()
    
    fun startConversation(): SearchState {
        info("startConversation()")
        val t0 = System.nanoTime()
        
        val metadata = ConversationCreateParams.Metadata.builder()
            .putAdditionalProperty("purpose", JsonValue.from("docs_search"))
            .putAdditionalProperty("session_id", JsonValue.from(UUID.randomUUID().toString()))
            .build()
        
        val convParams = ConversationCreateParams.builder()
            .metadata(metadata)
            .build()
        
        val conversationId = client.conversations().create(convParams).id()

        appendMessage(conversationId, ResponseInputItem.Message.Role.SYSTEM, systemInstructions)
        
        val ms = (System.nanoTime() - t0) / 1_000_000
        info("startConversation(): conversationId=$conversationId (${ms}ms)")
        
        return SearchState(conversationId)
    }
    
    /**
     * Search for documentation and code with streaming support
     */
    fun search(
        state: SearchState,
        query: String,
        onTextDelta: ((String) -> Unit)? = null
    ): SearchResult {
        val t0 = System.nanoTime()
        
        info("search(): query='$query' conversationId=${state.conversationId}")
        
        trace?.log(
            "DOCS_SEARCH_QUERY",
            buildString {
                appendLine("conversationId=${state.conversationId}")
                appendLine("query=$query")
            }
        )

        // Get vector store IDs from KotlinSourceIndex if available
        val vectorStoreIds = mutableListOf<String>()
        KotlinSourceIndex.latestIndex?.vectorStoreId?.let { vsId ->
            vectorStoreIds.add(vsId)
            info("Using vector store: $vsId")
        }
        GlslIncludeLibraryIndex.currentVectorStoreIdsForQuery(query).forEach { vsId ->
            vectorStoreIds.add(vsId)
            info("Using GLSL include vector store: $vsId")
        }
        if (vectorStoreIds.isEmpty()) {
            warn("No vector store available; docs search is running ungrounded")
        }

        val groundedQuery =
            if (vectorStoreIds.isNotEmpty()) {
                buildString {
                    appendLine("Use file_search against the indexed Field4 codebase for this request.")
                    appendLine("If the question is about GLSL include libraries, use file_search against indexed GLSL include libraries too.")
                    appendLine("If retrieval does not support a claim, state that clearly.")
                    appendLine()
                    append(query)
                }
            } else {
                query
            }
        
        fun buildParams(modelToUse: ChatModel): ResponseCreateParams {
            val paramsBuilder = ResponseCreateParams.builder()
                .model(modelToUse)
                .maxOutputTokens(16384L)
                .input(groundedQuery)

            if (state.previousResponseId == null) {
                paramsBuilder.conversation(state.conversationId)
            } else {
                paramsBuilder.previousResponseId(state.previousResponseId)
            }

            if (vectorStoreIds.isNotEmpty()) {
                paramsBuilder.addFileSearchTool(vectorStoreIds)
            }

            paramsBuilder.text(textConfig)
            paramsBuilder.reasoning(reasoningConfig)
            return paramsBuilder.build()
        }

        try {
            val streamedText = StringBuilder()
            val response = try {
                createResponse(buildParams(activeModel)) { delta ->
                    streamedText.append(delta)
                    onTextDelta?.invoke(delta)
                }
            } catch (e: Exception) {
                if (isMissingModelError(e) && activeModel != fallbackModel) {
                    warn("Model '$activeModel' unavailable; retrying with '$fallbackModel'")
                    activeModel = fallbackModel
                    streamedText.setLength(0)
                    createResponse(buildParams(activeModel)) { delta ->
                        streamedText.append(delta)
                        onTextDelta?.invoke(delta)
                    }
                } else {
                    throw e
                }
            }
            val newResponseId = response.id()
            val fullText = streamedText.toString().ifBlank {
                extractOutputTextWithNewlines(response)
            }
            if (fullText.isBlank()) {
                warn("search(): response had no output_text")
            }

            val ms = (System.nanoTime() - t0) / 1_000_000
            info("search() complete: chars=${fullText.length} time=${ms}ms")

            trace?.log(
                "DOCS_SEARCH_RESPONSE",
                buildString {
                    appendLine("conversationId=${state.conversationId}")
                    appendLine("responseId=$newResponseId")
                    appendLine("chars=${fullText.length}")
                    appendLine()
                    append(fullText)
                }
            )

            return SearchResult(
                success = true,
                text = fullText,
                newState = state.copy(previousResponseId = newResponseId)
            )

        } catch (e: Exception) {
            error("search() failed: ${e.message}")
            e.printStackTrace()
            return SearchResult(
                success = false,
                text = "Error: ${e.message}",
                newState = state
            )
        }
    }

    private fun appendMessage(conversationId: String, role: ResponseInputItem.Message.Role, text: String) {
        val message = ResponseInputItem.Message.builder()
            .role(role)
            .addInputTextContent(text)
            .build()

        val params = ItemCreateParams.builder()
            .conversationId(conversationId)
            .addItem(message)
            .build()

        client.conversations().items().create(conversationId, params)
    }

    private fun createResponse(params: ResponseCreateParams, onTextDelta: (String) -> Unit): Response {
        return try {
            val acc = ResponseAccumulator.create()
            client.async().responses().createStreaming(params).subscribe({ event ->
                acc.accumulate(event)
                event.outputTextDelta().ifPresent { delta ->
                    val chunk = delta.delta()
                    if (chunk.isNotEmpty()) onTextDelta(chunk)
                }
            }).onCompleteFuture().join()

            acc.response()
        } catch (t: Throwable) {
            warn(
                "streaming response failed; falling back to non-streaming create. " +
                    "cause=${renderThrowableChain(t)}"
            )
            client.responses().create(params)
        }
    }

    private fun isMissingModelError(error: Throwable): Boolean {
        var cursor: Throwable? = error
        while (cursor != null) {
            val message = cursor.message?.lowercase(Locale.US) ?: ""
            if ((message.contains("requested model") && message.contains("does not exist")) ||
                message.contains("model_not_found")
            ) {
                return true
            }
            cursor = cursor.cause
        }
        return false
    }

    private fun extractOutputTextWithNewlines(response: Response): String {
        val parts = mutableListOf<String>()
        response.output().forEach { item ->
            if (item.isMessage()) {
                val msg = item.message().orElse(null)
                msg?.content()?.forEach { content ->
                    val outText = content.outputText().orElse(null)
                    if (outText != null) parts += outText.text()
                }
            }
        }
        return parts.joinToString(separator = "\n").trimEnd()
    }

    private fun renderThrowableChain(error: Throwable): String {
        val seen = linkedSetOf<String>()
        var cursor: Throwable? = error
        while (cursor != null) {
            val label = buildString {
                append(cursor::class.java.simpleName.ifBlank { cursor::class.java.name })
                cursor.message?.takeIf { it.isNotBlank() }?.let {
                    append(": ")
                    append(it)
                }
            }
            seen += label
            cursor = cursor.cause
        }
        return seen.joinToString(" <- ")
    }
    
    data class SearchResult(
        val success: Boolean,
        val text: String,
        val newState: SearchState
    )
}
