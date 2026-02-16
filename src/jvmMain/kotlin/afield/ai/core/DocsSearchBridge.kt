package afield.ai.core

import afield.ai.KotlinSourceIndex
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.helpers.ResponseAccumulator
import com.openai.models.ChatModel
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
    model: String = ChatModel.GPT_5_2.toString(),
    private val log: LogSink = StdoutLog
) {
    private val chatModel: ChatModel = ChatModel.of(model)
    private val trace: TraceLogger? = TraceLogger.fromEnv()
    private val textConfig: ResponseTextConfig =
        ResponseTextConfig.builder().format(ResponseFormatText.builder().build()).build()
    
    private fun info(message: String) = log.log("info,docs.search", message)
    private fun warn(message: String) = log.log("warning,docs.search", message)
    private fun error(message: String) = log.log("error,docs.search", message)
    
    init {
        info("Initialized DocsSearchBridge(model=$chatModel)")
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
        
        Your primary role is to help users discover and understand:
        - Field4 API documentation and usage examples
        - Code implementations in the Field4 codebase
        - KDoc comments and inline documentation
        - Best practices and patterns used in Field4
        
        When responding:
        1. Prioritize showing relevant code examples with syntax highlighting
        2. Include KDoc comments when available
        3. Explain how to use the code in practical scenarios
        4. Reference file paths when discussing specific implementations
        5. Format code blocks properly with language annotations
        6. Be concise but thorough
        
        You have access to the Field4 codebase through a vector store that includes:
        - All Kotlin source files from src/jvmMain/kotlin
        - KDoc comments and inline documentation
        - Code structure and patterns
        
        When the user asks about code or documentation:
        - Search the codebase for relevant examples
        - Show actual implementations when possible
        - Explain the context and usage
        - Suggest related functionality
        
        Format your responses in markdown with:
        - Clear headings (## for sections, ### for subsections)
        - Code blocks with language annotations (```kotlin, ```glsl, etc.)
        - Inline code with `backticks`
        - Lists for multiple items
        - Bold for emphasis
        
        Be helpful and educational. If you can write example code to demonstrate a concept, do so.
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

        appendMessage(state.conversationId, ResponseInputItem.Message.Role.USER, query)
        
        // Get vector store IDs from KotlinSourceIndex if available
        val vectorStoreIds = mutableListOf<String>()
        KotlinSourceIndex.latestIndex?.vectorStoreId?.let { vsId ->
            vectorStoreIds.add(vsId)
            info("Using vector store: $vsId")
        }
        
        // Create response request
        val paramsBuilder = ResponseCreateParams.builder()
            .model(chatModel)
            .maxOutputTokens(16384L)

        if (state.previousResponseId == null) {
            paramsBuilder.conversation(state.conversationId)
        } else {
            paramsBuilder.previousResponseId(state.previousResponseId)
        }

        if (vectorStoreIds.isNotEmpty()) {
            paramsBuilder.addFileSearchTool(vectorStoreIds)
        }

        paramsBuilder.text(textConfig)

        val params = paramsBuilder.build()

        try {
            val streamedText = StringBuilder()
            val response = createResponse(params) { delta ->
                streamedText.append(delta)
                onTextDelta?.invoke(delta)
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
        val acc = ResponseAccumulator.create()
        client.async().responses().createStreaming(params).subscribe({ event ->
            acc.accumulate(event)
            event.outputTextDelta().ifPresent { delta ->
                val chunk = delta.delta()
                if (chunk.isNotEmpty()) onTextDelta(chunk)
            }
        }).onCompleteFuture().join()

        return acc.response()
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
    
    data class SearchResult(
        val success: Boolean,
        val text: String,
        val newState: SearchState
    )
}
