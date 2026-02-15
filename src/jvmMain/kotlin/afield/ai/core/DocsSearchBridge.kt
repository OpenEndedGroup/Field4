package afield.ai.core

import afield.ai.KotlinSourceIndex
import afield.logger.lprintln
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.ChatModel
import com.openai.models.Reasoning
import com.openai.models.ReasoningEffort
import com.openai.models.ResponseFormatText
import com.openai.models.conversations.ConversationCreateParams
import com.openai.models.conversations.items.ItemCreateParams
import com.openai.models.responses.*
import com.openai.core.JsonValue
import com.openai.helpers.ResponseAccumulator
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
        
        // Add system instructions
        val instructionItem = ItemCreateParams.builder()
            .type(ItemCreateParams.Type.MESSAGE)
            .message(
                ItemCreateParams.Message.builder()
                    .role(ItemCreateParams.Message.Role.SYSTEM)
                    .addTextContent(systemInstructions)
                    .build()
            )
            .build()
        
        client.conversations().items().create(conversationId, instructionItem)
        
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
        
        // Add user message with query
        val userItem = ItemCreateParams.builder()
            .type(ItemCreateParams.Type.MESSAGE)
            .message(
                ItemCreateParams.Message.builder()
                    .role(ItemCreateParams.Message.Role.USER)
                    .addTextContent(query)
                    .build()
            )
            .build()
        
        client.conversations().items().create(state.conversationId, userItem)
        
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
        
        if (state.previousResponseId != null) {
            paramsBuilder.previousResponseId(state.previousResponseId)
        }
        
        if (vectorStoreIds.isNotEmpty()) {
            paramsBuilder.tools(
                ResponseCreateParams.Tool.ofFileSearch(
                    ResponseCreateParams.Tool.FileSearch.builder()
                        .vectorStoreIds(vectorStoreIds)
                        .build()
                )
            )
        }
        
        paramsBuilder.modalities(ResponseCreateParams.Modality.TEXT)
        paramsBuilder.text(
            ResponseTextConfig.builder()
                .format(ResponseFormatText.builder().build())
                .build()
        )
        
        val params = paramsBuilder.build()
        
        val accum = ResponseAccumulator()
        val textDelta = StringBuilder()
        
        try {
            client.conversations()
                .responses()
                .create(state.conversationId, params)
                .stream()
                .forEach { event ->
                    accum.accumulate(event)
                    
                    when (event) {
                        is ResponseStreamEvent.ResponseTextDelta -> {
                            val delta = event.delta()
                            textDelta.append(delta)
                            onTextDelta?.invoke(delta)
                        }
                        is ResponseStreamEvent.ResponseTextDone -> {
                            info("Response text done")
                        }
                        is ResponseStreamEvent.ResponseDone -> {
                            info("Response complete")
                        }
                        else -> {
                            // Ignore other events
                        }
                    }
                }
            
            val response = accum.response()
            val newResponseId = response.id()
            val fullText = textDelta.toString()
            
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
    
    data class SearchResult(
        val success: Boolean,
        val text: String,
        val newState: SearchState
    )
}
