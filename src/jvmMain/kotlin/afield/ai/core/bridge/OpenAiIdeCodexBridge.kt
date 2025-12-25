package afield.ai.core.bridge

import afield.ai.core.logging.LogSink
import afield.ai.core.logging.StdoutLog
import afield.ai.core.logging.TraceLogger
import afield.ai.core.tools.Commentary
import afield.ai.core.tools.ToolExecutionContext
import afield.ai.core.tools.ToolRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.core.JsonValue
import com.openai.helpers.ResponseAccumulator
import com.openai.models.ChatModel
import com.openai.models.Reasoning
import com.openai.models.ReasoningEffort
import com.openai.models.ResponseFormatText
import com.openai.models.conversations.ConversationCreateParams
import com.openai.models.conversations.items.ItemCreateParams
import com.openai.models.responses.*
import java.util.*

class OpenAiIdeCodexBridge(
    private val client: OpenAIClient = OpenAIOkHttpClient.fromEnv(),
    model: String = ChatModel.GPT_5_1_CODEX.toString(),
    private val log: LogSink = StdoutLog,
    private val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM
) {
    private val chatModel: ChatModel = ChatModel.of(model)
    private val mapper: ObjectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
    private val tools = ToolRegistry()
    private val trace: TraceLogger? = TraceLogger.fromEnv()
    private val textConfig: ResponseTextConfig =
        ResponseTextConfig.builder().format(ResponseFormatText.builder().build()).build()
    private val reasoningConfig: Reasoning =
        Reasoning.builder()
            .effort(reasoningEffort)
            .summary(Reasoning.Summary.DETAILED)
            .build()

    init {
        registerBuiltIns()
        log.log("Initialized OpenAiIdeCodexBridge(model=$chatModel)")
    }

    fun <T : Any> registerTool(toolClass: Class<T>, handler: (ToolExecutionContext, T) -> Any) {
        tools.register(toolClass, handler)
    }

    fun startThread(
        fileKey: String,
        fileText: String,
        instructions: String = defaultThreadInstructions
    ): ThreadState {
        log.log("startThread(): fileKey=$fileKey")
        val t0 = System.nanoTime()

        val localThreadId = UUID.randomUUID().toString()
        val metadata = ConversationCreateParams.Metadata.builder()
            .putAdditionalProperty("ide_file_key", JsonValue.from(fileKey))
            .putAdditionalProperty("local_thread_id", JsonValue.from(localThreadId))
            .build()

        val convParams = ConversationCreateParams.builder().metadata(metadata).build()
        val conversationId = client.conversations().create(convParams).id()

        appendDeveloperMessage(conversationId, instructions)
        appendUserMessage(
            conversationId,
            buildString {
                appendLine("IDE_FILE_CONTEXT:")
                appendLine("key: $fileKey")
                appendLine("content:")
                appendLine(fileText)
            }
        )

        val ms = (System.nanoTime() - t0) / 1_000_000
        log.log("startThread(): conversationId=$conversationId localThreadId=$localThreadId (${ms}ms)")
        return ThreadState(localThreadId, conversationId, fileKey)
    }

    /**
        * Send a prompt, chaining with previous_response_id when available.
        *
        * IMPORTANT: With the Responses API, when the model returns function calls,
        * send function call outputs back as **FunctionCallOutput input items**
        * linked by call_id and set previous_response_id to the tool-call response.
        *
        * Putting tool results in ordinary messages is NOT sufficient; the API will error:
        * "No tool output found for function call <call_id>"
        */
    fun send(
        state: ThreadState,
        userPrompt: String,
        fileText: String? = null,
        ctx: ToolExecutionContext = ToolExecutionContext(),
        vectorStoreIds: List<String> = emptyList(),
        maxOutputTokens: Long = 20480,
        maxToolRounds: Int = 8,
        onTextDelta: ((String) -> Unit)? = null
    ): SendResult {
        val t0 = System.nanoTime()

        val inputText = buildString {
            if (fileText != null) {
                appendLine("IDE_FILE_UPDATE:")
                appendLine("key: ${state.fileKey}")
                appendLine("content:")
                appendLine(fileText)
                appendLine()
            }
            appendLine(userPrompt)
        }

        trace?.log(
            "PROMPT",
            buildString {
                appendLine("conversationId=${state.conversationId}")
                appendLine("previousResponseId=${state.previousResponseId ?: "<none>"}")
                appendLine("fileKey=${state.fileKey}")
                appendLine("inputChars=${inputText.length}")
                if (vectorStoreIds.isNotEmpty()) {
                    appendLine("vectorStoreIds=${vectorStoreIds.joinToString(",")}")
                }
                appendLine()
                append(inputText)
            }
        )

        log.log(
            "convId=${state.conversationId} fileKey=${state.fileKey} inputChars=${inputText.length} " +
                "maxOutputTokens=$maxOutputTokens attachments=${ctx.attachments.size}"
        )

        val paramsBuilder = ResponseCreateParams.builder()
            .model(chatModel)
            .input(inputText)
            .maxOutputTokens(maxOutputTokens)
        if (state.previousResponseId == null) {
            paramsBuilder.conversation(state.conversationId)
        } else {
            paramsBuilder.previousResponseId(state.previousResponseId)
        }
        paramsBuilder.text(textConfig)
        paramsBuilder.reasoning(reasoningConfig)
        tools.addAllTo(paramsBuilder)
        if (vectorStoreIds.isNotEmpty() && supportsFileSearch()) {
            paramsBuilder.addFileSearchTool(vectorStoreIds)
        }

        log.log("running prompt <<spinner>>")
        var response: Response = createResponse(paramsBuilder.build(), onTextDelta)
        if (onTextDelta != null) {
            println()
        }
        state.previousResponseId = response.id()
        log.log("initial response id=${response.id()} outputItems=${response.output().size} <<spinner>>")

        val commentaryMessages = mutableListOf<String>()

        fun logOutputShape(resp: Response) {
            val out = resp.output()
            val msgCount = out.count { it.isMessage() }
            val fnCount = out.count { it.isFunctionCall() }
            val otherCount = out.size - msgCount - fnCount
            log.log("output shape: messages=$msgCount functionCalls=$fnCount other=$otherCount")
            if (resp.error().isPresent) {
                val e = resp.error().get()
                log.log("response.error code=${e.code()} message=${e.message()}")
            }
            val msgStatuses = out.mapNotNull { it.message().orElse(null)?.status() }
            if (msgStatuses.isNotEmpty()) {
                val labels = msgStatuses.joinToString(",") { it.value().toString().lowercase() }
                log.log("message status(es)=$labels")
                if (msgStatuses.any { it.value() == ResponseOutputMessage.Status.Value.INCOMPLETE }) {
                    log.log("WARNING: message status incomplete; output may be truncated.")
                }
            }
            if (msgCount == 0) logOutputDetails(resp)
        }

        logOutputShape(response)

        var rounds = 0
        while (rounds < maxToolRounds) {
            val calls = response.output()
                .filter { it.isFunctionCall() }
                .mapNotNull { it.functionCall().orElse(null) }

            if (calls.isEmpty()) {
                log.log("no tool calls; finishing")
                break
            }

            log.log("toolCalls=${calls.size} (round ${rounds + 1}/$maxToolRounds)")
            val toolOutputs = mutableListOf<ResponseInputItem>()
            for (call in calls) {
                log.log("tool: name=${call.name()} callId=${call.callId()} ${call.arguments()}")
                trace?.log(
                    "TOOL_CALL",
                    "name=${call.name()} callId=${call.callId()}\nargs=${call.arguments()}"
                )

                val toolResult: Any = try {
                    tools.execute(ctx, call)
                } catch (e: Exception) {
                    log.log("tool: ERROR name=${call.name()} msg=${e.message}")
                    mapOf(
                        "error" to true,
                        "tool" to call.name(),
                        "message" to (e.message ?: e::class.java.name)
                    )
                }

                trace?.log(
                    "TOOL_OUTPUT",
                    buildString {
                        appendLine("name=${call.name()} callId=${call.callId()}")
                        val json = runCatching { mapper.writeValueAsString(toolResult) }
                            .getOrElse { toolResult.toString() }
                        append(json)
                    }
                )

                if (call.name() == Commentary::class.java.simpleName) {
                    // Extract commentary regardless of handler output
                    try {
                        val c = call.arguments(Commentary::class.java)
                        commentaryMessages += c.text
                        log.log("tool: Commentary captured (${c.text.length} chars)")
                    } catch (e: Exception) {
                        log.log("tool: Commentary parse failed: ${e.message}")
                    }
                }

                val outputItem = ResponseInputItem.FunctionCallOutput.builder()
                    .callId(call.callId())
                    .outputAsJson(toolResult)
                    .build()
                toolOutputs += ResponseInputItem.ofFunctionCallOutput(outputItem)
            }

            log.log("creating response (follow-up) <<spinner>>")
            val followupBuilder = ResponseCreateParams.builder()
                .model(chatModel)
                .previousResponseId(response.id())
                .input(ResponseCreateParams.Input.ofResponse(toolOutputs))
                .maxOutputTokens(maxOutputTokens)
            followupBuilder.text(textConfig)
            followupBuilder.reasoning(reasoningConfig)
            tools.addAllTo(followupBuilder)
            if (vectorStoreIds.isNotEmpty() && supportsFileSearch()) {
                followupBuilder.addFileSearchTool(vectorStoreIds)
            }

            response = createResponse(followupBuilder.build(), onTextDelta)
            if (onTextDelta != null) {
                println()
            }
            state.previousResponseId = response.id()
            log.log("follow-up response id=${response.id()} outputItems=${response.output().size}")
            logOutputShape(response)

            rounds++
        }

        val text = extractOutputTextWithNewlines(response)
        val reasoningSummaries = extractReasoningSummaries(response)
        trace?.log(
            "RESPONSE",
            buildString {
                appendLine("responseId=${response.id()}")
                appendLine("textChars=${text.length}")
                appendLine("commentaryCount=${commentaryMessages.size}")
                appendLine()
                append(text)
            }
        )
        if (text.isBlank() && reasoningSummaries.isNotEmpty()) {
            commentaryMessages += reasoningSummaries
            val combined = reasoningSummaries.joinToString(" ").trim()
            log.log("reasoning summary (no output_text): ${truncate(combined, 400)}")
        }

        if (text.isBlank()) {
            // This is a very common integration gotcha; log a big hint.
            log.log("WARNING: model returned no output_text. " +
                "If output shape shows messages=0, your model may be returning non-text items. " +
                "Try increasing maxOutputTokens or simplifying prompt.")
        }

        val ms = (System.nanoTime() - t0) / 1_000_000
        log.log(
            "done responseId=${response.id()} textChars=${text.length} " +
                "commentaryCount=${commentaryMessages.size} (${ms}ms)"
        )

        return SendResult(
            threadState = state,
            text = text,
            rawResponseId = response.id(),
            commentary = commentaryMessages.toList()
        )
    }

    fun threadStateToJson(state: ThreadState): String = mapper.writeValueAsString(state)
    fun threadStateFromJson(json: String): ThreadState = mapper.readValue(json)

    data class SendResult(
        val threadState: ThreadState,
        val text: String,
        val rawResponseId: String,
        val commentary: List<String> = emptyList()
    )

    data class ThreadState(
        val localThreadId: String,
        val conversationId: String,
        val fileKey: String,
        var previousResponseId: String? = null
    )

    /* ------------------------- Conversations helpers ------------------------ */

    private fun appendUserMessage(conversationId: String, text: String) =
        appendMessage(conversationId, ResponseInputItem.Message.Role.USER, text)

    private fun appendDeveloperMessage(conversationId: String, text: String) =
        appendMessage(conversationId, ResponseInputItem.Message.Role.DEVELOPER, text)

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

    private fun createResponse(params: ResponseCreateParams, onTextDelta: ((String) -> Unit)?): Response {
        if (onTextDelta == null) return client.responses().create(params)

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

    private fun logOutputDetails(response: Response) {
        response.output().forEachIndexed { idx, item ->
            val desc = when {
                item.isMessage() -> {
                    val msg = item.message().orElse(null)
                    if (msg == null) {
                        "message"
                    } else {
                        val pieces = msg.content().map { content ->
                            when {
                                content.outputText().isPresent -> {
                                    val len = content.outputText().get().text().length
                                    "output_text(len=$len)"
                                }
                                content.refusal().isPresent -> "refusal"
                                else -> "content_unknown"
                            }
                        }
                        "message status=${msg.status()} content=${pieces.joinToString(",")}"
                    }
                }
                item.isFunctionCall() -> {
                    val call = item.functionCall().orElse(null)
                    if (call == null) "function_call" else "function_call name=${call.name()}"
                }
                item.isReasoning() -> {
                    val reasoning = item.reasoning().orElse(null)
                    if (reasoning == null) {
                        "reasoning"
                    } else {
                        val summary = try {
                            reasoning.summary().joinToString(" ") { it.text() }
                        } catch (_: Exception) {
                            ""
                        }
                        val summaryPart = if (summary.isBlank()) "" else " summary=${truncate(summary, 200)}"
                        "reasoning status=${reasoning.status().orElse(null)}$summaryPart"
                    }
                }
                item.isCompaction() -> "compaction"
                item.isFileSearchCall() -> "file_search_call"
                item.isWebSearchCall() -> "web_search_call"
                item.isComputerCall() -> "computer_call"
                item.isImageGenerationCall() -> "image_generation_call"
                item.isCodeInterpreterCall() -> "code_interpreter_call"
                item.isLocalShellCall() -> "local_shell_call"
                item.isShellCall() -> "shell_call"
                item.isShellCallOutput() -> "shell_call_output"
                item.isApplyPatchCall() -> "apply_patch_call"
                item.isApplyPatchCallOutput() -> "apply_patch_call_output"
                item.isMcpCall() -> "mcp_call"
                item.isMcpListTools() -> "mcp_list_tools"
                item.isMcpApprovalRequest() -> "mcp_approval_request"
                item.isCustomToolCall() -> "custom_tool_call"
                else -> "unknown"
            }
            log.log("output[$idx] $desc")
            if (desc == "unknown") {
                val raw = item._json().orElse(null)?.toString()
                if (raw != null) log.log("output[$idx] raw=${truncate(raw, 400)}")
            }
        }
    }

    private fun extractReasoningSummaries(response: Response): List<String> =
        response.output().mapNotNull { item ->
            val reasoning = item.reasoning().orElse(null) ?: return@mapNotNull null
            val summary = try {
                reasoning.summary().joinToString(" ") { it.text() }.trim()
            } catch (_: Exception) {
                ""
            }
            summary.ifBlank { null }
        }

    private fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.substring(0, max) + "..."

    private fun supportsFileSearch(): Boolean = chatModel != ChatModel.GPT_5_1_CODEX

    /* ------------------------------ Tools ---------------------------------- */

    private fun registerBuiltIns() {
        // Commentary: handler can be a no-op acknowledgement; we extract text from call args anyway.
        tools.register(Commentary::class.java) { _, _ -> mapOf("ok" to true) }

        // Attachments
        tools.register(afield.ai.core.tools.ListAttachmentsRequest::class.java) { ctx, _ ->
            afield.ai.core.tools.ListAttachmentsResult(
                attachments = ctx.attachments.map { afield.ai.core.tools.AttachmentInfo(it.fileName, it.language) }
            )
        }
        tools.register(afield.ai.core.tools.AttachmentRequest::class.java) { ctx, req ->
            val hit = ctx.attachments.firstOrNull { it.fileName == req.file }
                ?: throw IllegalArgumentException("No attachment named '${req.file}'. Call ListAttachments first.")
            afield.ai.core.tools.AttachmentResponse(hit.fileName, hit.language, hit.code)
        }

        // Kotlin-only tools (only work if provider is supplied in ctx)
        tools.register(afield.ai.core.tools.ListDefinitionsRequest::class.java) { ctx, _ ->
            ctx.listDefinitions?.invoke()
                ?: throw IllegalStateException("ListDefinitions not available for this request.")
        }
        tools.register(afield.ai.core.tools.ListContentsRequest::class.java) { ctx, _ ->
            ctx.listFileContents?.invoke()
                ?: throw IllegalStateException("ListContents not available for this request.")
        }
        tools.register(afield.ai.core.tools.EditFileRequest::class.java) { ctx, req ->
            ctx.editOtherFile?.invoke(req)
                ?: throw IllegalStateException("EditFile not available for this request.")
        }

        // Optional extra tool
        tools.register(afield.ai.core.tools.WorkspaceSymbolSearch::class.java) { ctx, req ->
            ctx.workspaceSymbolSearch?.invoke(req)
                ?: throw IllegalStateException("WorkspaceSymbolSearch not available for this request.")
        }
    }

    companion object {
        val defaultThreadInstructions: String = """
You are an IDE-embedded code assistant working in a novel live-coding environment.
Return only code for the current file in responses (no markdown fences, no diffs, no prose).
If you have non-code thoughts, call the Commentary tool. Commentary, reasoning and questions there are welcome if you use the tool.
        """.trimIndent()

        val defaultChatThreadInstructions: String = """
You are an IDE-embedded assistant working in a novel live-coding environment.
Respond in markdown. You may discuss, explain, and propose options.
If you need to change code, use the available tools rather than dumping entire files.
        """.trimIndent()
    }
}
