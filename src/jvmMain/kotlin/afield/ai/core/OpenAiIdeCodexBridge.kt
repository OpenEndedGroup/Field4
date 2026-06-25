package afield.ai.core

import afield.ai.RelatedContextDiscovery
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.openai.client.OpenAIClient
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
import java.util.concurrent.ConcurrentHashMap

class OpenAiIdeCodexBridge(
    private val runtimeConfig: AiProviderRuntimeConfig = activeAiProviderRuntimeConfig(),
    private val client: OpenAIClient = openAiClientFromFieldConfig(),
    model: String = Ai4ModelDefaults.flagshipModel(),
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
    private val statelessHistory = ConcurrentHashMap<String, MutableList<ResponseInputItem>>()
    private fun info(message: String) = log.log("info,ai.bridge", message)
    private fun warn(message: String) = log.log("warning,ai.bridge", message)
    private fun error(message: String) = log.log("error,ai.bridge", message)

    init {
        registerBuiltIns()
        info("Initialized OpenAiIdeCodexBridge(model=$chatModel)")
    }

    fun <T : Any> registerTool(toolClass: Class<T>, handler: (ToolExecutionContext, T) -> Any) {
        tools.register(toolClass, handler)
    }

    fun startThread(
        fileKey: String,
        fileText: String,
        instructions: String = defaultThreadInstructions
    ): ThreadState {
        info("startThread(): fileKey=$fileKey")
        val t0 = System.nanoTime()

        val localThreadId = UUID.randomUUID().toString()
        if (runtimeConfig.isOpenRouter) {
            val conversationId = "openrouter:$localThreadId"
            val ms = (System.nanoTime() - t0) / 1_000_000
            info("startThread(): stateless conversationId=$conversationId localThreadId=$localThreadId (${ms}ms)")
            return ThreadState(
                localThreadId = localThreadId,
                conversationId = conversationId,
                fileKey = fileKey,
                initialInstructions = instructions,
                initialFileText = fileText
            )
        }

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
        info("startThread(): conversationId=$conversationId localThreadId=$localThreadId (${ms}ms)")
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
        maxToolRounds: Int = 24,
        imageDataUrls: List<String> = emptyList(),
        onTextDelta: ((String) -> Unit)? = null,
        onProgress: ((ProgressUpdate) -> Unit)? = null
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
                if (imageDataUrls.isNotEmpty()) {
                    appendLine("images=${imageDataUrls.size}")
                }
                if (vectorStoreIds.isNotEmpty()) {
                    appendLine("vectorStoreIds=${vectorStoreIds.joinToString(",")}")
                }
                appendLine()
                append(inputText)
            }
        )

        info(
            "convId=${state.conversationId} fileKey=${state.fileKey} inputChars=${inputText.length} " +
                "maxOutputTokens=$maxOutputTokens attachments=${ctx.attachments.size} images=${imageDataUrls.size}"
        )

        val promptItems = buildPromptItems(inputText, imageDataUrls)

        val paramsBuilder = ResponseCreateParams.builder()
            .model(chatModel)
            .maxOutputTokens(maxOutputTokens)

        if (runtimeConfig.isOpenRouter) {
            paramsBuilder.input(ResponseCreateParams.Input.ofResponse(historyForRequest(state) + promptItems))
        } else if (imageDataUrls.isEmpty()) {
            paramsBuilder.input(inputText)
        } else {
            paramsBuilder.input(ResponseCreateParams.Input.ofResponse(promptItems))
        }
        if (runtimeConfig.isOpenRouter) {
            // OpenRouter's Responses API is stateless; local history is sent in input instead.
        } else if (state.previousResponseId == null) {
            paramsBuilder.conversation(state.conversationId)
        } else {
            paramsBuilder.previousResponseId(state.previousResponseId)
        }
        paramsBuilder.text(textConfig)
        addReasoningIfSupported(paramsBuilder)
        tools.addAllTo(paramsBuilder)
        if (vectorStoreIds.isNotEmpty() && supportsFileSearch()) {
            paramsBuilder.addFileSearchTool(vectorStoreIds)
        }

        info("running prompt <<spinner>>")
        var response: Response = createResponse(paramsBuilder.build(), onTextDelta)
        if (onTextDelta != null) {
            info("")
        }
        state.previousResponseId = response.id()
        appendStatelessHistory(state, promptItems + inputItemsFromOutput(response))
        info("initial response id=${response.id()} outputItems=${response.output().size} <<spinner>>")

        val commentaryMessages = mutableListOf<String>()
        val conversationAdditions = mutableListOf<String>()
        val toolTranscript = mutableListOf<ToolTranscriptEntry>()

        fun logOutputShape(resp: Response) {
            val out = resp.output()
            val msgCount = out.count { it.isMessage() }
            val fnCount = out.count { it.isFunctionCall() }
            val otherCount = out.size - msgCount - fnCount
            info("output shape: messages=$msgCount functionCalls=$fnCount other=$otherCount")
            if (resp.error().isPresent) {
                val e = resp.error().get()
                error("response.error code=${e.code()} message=${e.message()}")
            }
            val msgStatuses = out.mapNotNull { it.message().orElse(null)?.status() }
            if (msgStatuses.isNotEmpty()) {
                val labels = msgStatuses.joinToString(",") { it.value().toString().lowercase() }
                info("message status(es)=$labels")
                if (msgStatuses.any { it.value() == ResponseOutputMessage.Status.Value.INCOMPLETE }) {
                    warn("message status incomplete; output may be truncated.")
                }
            }
            if (msgCount == 0) logOutputDetails(resp)
        }

        logOutputShape(response)
        emitResponseProgress(response, onProgress)

        var rounds = 0
        while (rounds < maxToolRounds) {
            val calls = response.output()
                .filter { it.isFunctionCall() }
                .mapNotNull { it.functionCall().orElse(null) }

            if (calls.isEmpty()) {
                info("no tool calls; finishing")
                break
            }

            info("toolCalls=${calls.size} (round ${rounds + 1}/$maxToolRounds)")
            val toolOutputs = mutableListOf<ResponseInputItem>()
            for (call in calls) {
                val argumentSummary = summarizeToolArguments(call.name(), call.arguments())
                info("tool: name=${call.name()} callId=${call.callId()} $argumentSummary")
                onProgress?.invoke(
                    ProgressUpdate(
                        title = "Using ${call.name()}",
                        body = truncate(argumentSummary, 700)
                    )
                )
                trace?.log(
                    "TOOL_CALL",
                    "name=${call.name()} callId=${call.callId()}\nargs=${call.arguments()}"
                )

                val toolResult: Any = try {
                    tools.execute(ctx, call)
                } catch (e: Exception) {
                    error("tool: ERROR name=${call.name()} msg=${e.message}")
                    mapOf(
                        "error" to true,
                        "tool" to call.name(),
                        "message" to (e.message ?: e::class.java.name)
                    )
                }

                val toolResultJson = runCatching { mapper.writeValueAsString(toolResult) }
                    .getOrElse { toolResult.toString() }
                val toolResultSummary = summarizeToolResult(toolResult)
                toolTranscript += ToolTranscriptEntry(
                    name = call.name(),
                    callId = call.callId(),
                    argumentsJson = call.arguments(),
                    resultSummary = toolResultSummary,
                    resultJson = toolResultJson
                )

                trace?.log(
                    "TOOL_OUTPUT",
                    buildString {
                        appendLine("name=${call.name()} callId=${call.callId()}")
                        append(toolResultJson)
                    }
                )
                info("tool: result name=${call.name()} $toolResultSummary")
                onProgress?.invoke(
                    ProgressUpdate(
                        title = "${call.name()} finished",
                        body = toolResultSummary
                    )
                )

                if (call.name() == Commentary::class.java.simpleName) {
                    // Extract commentary regardless of handler output
                    try {
                        val c = call.arguments(Commentary::class.java)
                        commentaryMessages += c.text
                        info("tool: Commentary captured (${c.text.length} chars)")
                        onProgress?.invoke(
                            ProgressUpdate(
                                title = "Commentary",
                                body = c.text
                            )
                        )
                    } catch (e: Exception) {
                        error("tool: Commentary parse failed: ${e.message}")
                    }
                }

                val outputItem = ResponseInputItem.FunctionCallOutput.builder()
                    .callId(call.callId())
                    .outputAsJson(toolResult)
                    .build()
                toolOutputs += ResponseInputItem.ofFunctionCallOutput(outputItem)

                if (toolResult is ReloadShadersResult && toolResult.visualDataUrl.isNotBlank()) {
                    conversationAdditions += buildString {
                        appendLine("<<ai>>")
                        appendLine("_Post-shader-reload screenshot captured after the settle delay._")
                        if (toolResult.visualCheckMessage.isNotBlank()) {
                            appendLine()
                            appendLine("_${toolResult.visualCheckMessage}_")
                        }
                        appendLine()
                        append("![post-shader-reload screenshot](")
                        append(toolResult.visualDataUrl)
                        append(")")
                    }
                    val visualMessage = ResponseInputItem.Message.builder()
                        .role(ResponseInputItem.Message.Role.USER)
                        .addInputTextContent(
                            "Post-shader-reload screenshot captured after the requested settle delay. " +
                                "Inspect this image in the context of the user's request and any earlier screenshot. " +
                                "If the requested visual result is not achieved, edit/reload again before finalizing."
                        )
                        .addContent(
                            ResponseInputImage.builder()
                                .imageUrl(toolResult.visualDataUrl)
                                .detail(ResponseInputImage.Detail.HIGH)
                                .build()
                        )
                        .build()
                    toolOutputs += ResponseInputItem.ofMessage(visualMessage)
                }
            }

            info("creating response (follow-up) <<spinner>>")
            val followupBuilder = ResponseCreateParams.builder()
                .model(chatModel)
                .maxOutputTokens(maxOutputTokens)
            if (runtimeConfig.isOpenRouter) {
                followupBuilder.input(ResponseCreateParams.Input.ofResponse(historyForRequest(state) + toolOutputs))
            } else {
                followupBuilder.previousResponseId(response.id())
                followupBuilder.input(ResponseCreateParams.Input.ofResponse(toolOutputs))
            }
            followupBuilder.text(textConfig)
            addReasoningIfSupported(followupBuilder)
            tools.addAllTo(followupBuilder)
            if (vectorStoreIds.isNotEmpty() && supportsFileSearch()) {
                followupBuilder.addFileSearchTool(vectorStoreIds)
            }

            response = createResponse(followupBuilder.build(), onTextDelta)
            if (onTextDelta != null) {
                info("")
            }
            state.previousResponseId = response.id()
            appendStatelessHistory(state, toolOutputs + inputItemsFromOutput(response))
            info("follow-up response id=${response.id()} outputItems=${response.output().size}")
            logOutputShape(response)
            emitResponseProgress(response, onProgress)

            rounds++
        }

        val pendingCalls = response.output()
            .filter { it.isFunctionCall() }
            .mapNotNull { it.functionCall().orElse(null) }
        if (pendingCalls.isNotEmpty()) {
            warn(
                "maxToolRounds=$maxToolRounds reached with ${pendingCalls.size} pending tool call(s); " +
                    "requesting a final answer without further tools."
            )
            response = createForcedFinalResponse(
                state = state,
                previousResponse = response,
                pendingCalls = pendingCalls,
                maxOutputTokens = maxOutputTokens,
                onTextDelta = onTextDelta
            )
            if (onTextDelta != null) {
                info("")
            }
            state.previousResponseId = response.id()
            info("forced-final response id=${response.id()} outputItems=${response.output().size}")
            logOutputShape(response)
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
            info("reasoning summary (no output_text): ${truncate(combined, 40000)}")
        }

        if (text.isBlank()) {
            // This is a very common integration gotcha; log a big hint.
            warn(
                "model returned no output_text. " +
                "If output shape shows messages=0, your model may be returning non-text items. " +
                "Try increasing maxOutputTokens or simplifying prompt."
            )
        }

        val ms = (System.nanoTime() - t0) / 1_000_000
        info(
            "done responseId=${response.id()} textChars=${text.length} " +
                "commentaryCount=${commentaryMessages.size} (${ms}ms)"
        )

        return SendResult(
            threadState = state,
            text = text,
            rawResponseId = response.id(),
            commentary = commentaryMessages.toList(),
            conversationAdditions = conversationAdditions.toList(),
            toolTranscript = toolTranscript.toList()
        )
    }

    fun threadStateToJson(state: ThreadState): String = mapper.writeValueAsString(state)
    fun threadStateFromJson(json: String): ThreadState = mapper.readValue(json)

    data class SendResult(
        val threadState: ThreadState,
        val text: String,
        val rawResponseId: String,
        val commentary: List<String> = emptyList(),
        val conversationAdditions: List<String> = emptyList(),
        val toolTranscript: List<ToolTranscriptEntry> = emptyList()
    )

    data class ToolTranscriptEntry(
        val name: String,
        val callId: String,
        val argumentsJson: String,
        val resultSummary: String,
        val resultJson: String
    )

    data class ProgressUpdate(
        val title: String,
        val body: String = ""
    )

    data class ThreadState(
        val localThreadId: String,
        val conversationId: String,
        val fileKey: String,
        val initialInstructions: String = "",
        val initialFileText: String = "",
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
        if (onTextDelta == null) {
            return try {
                client.responses().create(params)
            } catch (t: Throwable) {
                traceResponseError("create", t)
                throw t
            }
        }

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
            val rendered = renderThrowableChain(t)
            warn(
                "streaming response failed; falling back to non-streaming create. " +
                    "cause=${truncate(rendered, 600)}"
            )
            try {
                client.responses().create(params)
            } catch (fallback: Throwable) {
                traceResponseError("streaming-fallback", fallback)
                throw fallback
            }
        }
    }

    private fun traceResponseError(stage: String, error: Throwable) {
        val rendered = renderThrowableChain(error)
        trace?.log(
            "RESPONSE_ERROR",
            buildString {
                appendLine("stage=$stage")
                appendLine("cause=$rendered")
                appendLine()
                append(error.stackTraceToString())
            }
        )
    }

    private fun createForcedFinalResponse(
        state: ThreadState,
        previousResponse: Response,
        pendingCalls: List<ResponseFunctionToolCall>,
        maxOutputTokens: Long,
        onTextDelta: ((String) -> Unit)?
    ): Response {
        val inputItems = mutableListOf<ResponseInputItem>()
        pendingCalls.forEach { call ->
            val outputItem = ResponseInputItem.FunctionCallOutput.builder()
                .callId(call.callId())
                .outputAsJson(
                    mapOf(
                        "error" to true,
                        "aborted" to true,
                        "tool" to call.name(),
                        "reason" to "max_tool_rounds_reached",
                        "message" to "Tool call aborted so the assistant can answer directly."
                    )
                )
                .build()
            inputItems += ResponseInputItem.ofFunctionCallOutput(outputItem)
        }

        val stopUsingTools = ResponseInputItem.Message.builder()
            .role(ResponseInputItem.Message.Role.DEVELOPER)
            .addInputTextContent(
                "Stop using tools for this turn. Answer the user directly in text now using the context " +
                    "already gathered. If you remain uncertain, say so briefly instead of calling more tools."
            )
            .build()
        inputItems += ResponseInputItem.ofMessage(stopUsingTools)

        val builder = ResponseCreateParams.builder()
            .model(chatModel)
            .maxOutputTokens(maxOutputTokens)
        if (runtimeConfig.isOpenRouter) {
            builder.input(ResponseCreateParams.Input.ofResponse(historyForRequest(state) + inputItems))
        } else {
            builder.previousResponseId(previousResponse.id())
            builder.input(ResponseCreateParams.Input.ofResponse(inputItems))
        }
        builder.text(textConfig)
        addReasoningIfSupported(builder)
        info("creating response (forced-final) <<spinner>>")
        return createResponse(builder.build(), onTextDelta)
    }

    private fun buildMessageItem(
        role: ResponseInputItem.Message.Role,
        text: String,
        imageDataUrls: List<String> = emptyList()
    ): ResponseInputItem {
        val message = ResponseInputItem.Message.builder()
            .role(role)
            .addInputTextContent(text)
            .apply {
                imageDataUrls.forEach { url ->
                    try {
                        addContent(
                            ResponseInputImage.builder()
                                .imageUrl(url)
                                .detail(ResponseInputImage.Detail.HIGH)
                                .build()
                        )
                    } catch (_: Exception) {
                        // Ignore malformed image strings but keep the rest flowing.
                    }
                }
            }
            .build()
        return ResponseInputItem.ofMessage(message)
    }

    private fun buildPromptItems(inputText: String, imageDataUrls: List<String>): List<ResponseInputItem> =
        listOf(buildMessageItem(ResponseInputItem.Message.Role.USER, inputText, imageDataUrls))

    private fun seedHistory(state: ThreadState): List<ResponseInputItem> {
        val seed = mutableListOf<ResponseInputItem>()
        if (state.initialInstructions.isNotBlank()) {
            seed += buildMessageItem(ResponseInputItem.Message.Role.DEVELOPER, state.initialInstructions)
        }
        if (state.initialFileText.isNotBlank()) {
            seed += buildMessageItem(
                ResponseInputItem.Message.Role.USER,
                buildString {
                    appendLine("IDE_FILE_CONTEXT:")
                    appendLine("key: ${state.fileKey}")
                    appendLine("content:")
                    appendLine(state.initialFileText)
                }
            )
        }
        return seed
    }

    private fun historyForRequest(state: ThreadState): List<ResponseInputItem> {
        val history = statelessHistory.computeIfAbsent(state.localThreadId) { seedHistory(state).toMutableList() }
        return history.toList()
    }

    private fun inputItemsFromOutput(response: Response): List<ResponseInputItem> =
        response.output().mapNotNull { item ->
            when {
                item.message().isPresent -> ResponseInputItem.ofResponseOutputMessage(item.message().get())
                item.functionCall().isPresent -> ResponseInputItem.ofFunctionCall(item.functionCall().get())
                item.reasoning().isPresent -> ResponseInputItem.ofReasoning(item.reasoning().get())
                else -> null
            }
        }

    private fun appendStatelessHistory(state: ThreadState, items: List<ResponseInputItem>) {
        if (!runtimeConfig.isOpenRouter || items.isEmpty()) return
        val history = statelessHistory.computeIfAbsent(state.localThreadId) { seedHistory(state).toMutableList() }
        history += items
        val maxItems = System.getenv("AFIELD_OPENROUTER_HISTORY_ITEMS")?.toIntOrNull()?.coerceAtLeast(8) ?: 80
        if (history.size > maxItems) {
            val seedCount = seedHistory(state).size
            val removeCount = (history.size - maxItems).coerceAtMost((history.size - seedCount).coerceAtLeast(0))
            repeat(removeCount) {
                if (history.size > seedCount) history.removeAt(seedCount)
            }
        }
    }

    private fun addReasoningIfSupported(builder: ResponseCreateParams.Builder) {
        if (!runtimeConfig.isOpenRouter) builder.reasoning(reasoningConfig)
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
                        val summaryPart = if (summary.isBlank()) "" else " summary=${truncate(summary, 20000)}"
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
            info("output[$idx] $desc")
            if (desc == "unknown") {
                val raw = item._json().orElse(null)?.toString()
                if (raw != null) warn("output[$idx] raw=${raw}")
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

    private fun emitResponseProgress(response: Response, onProgress: ((ProgressUpdate) -> Unit)?) {
        if (onProgress == null) return
        val summaries = extractReasoningSummaries(response)
        val calls = response.output()
            .filter { it.isFunctionCall() }
            .mapNotNull { it.functionCall().orElse(null) }
        if (summaries.isEmpty() && calls.isEmpty()) return

        val firstSummary = summaries.firstOrNull().orEmpty()
        val title = progressTitle(firstSummary).ifBlank {
            if (calls.isNotEmpty()) {
                "Planning tool use"
            } else {
                "Thinking"
            }
        }
        val body = buildString {
            if (summaries.isNotEmpty()) {
                append(summaries.joinToString("\n\n"))
            }
            if (calls.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Next tool")
                if (calls.size > 1) append("s")
                append(": ")
                append(calls.joinToString(", ") { "`${it.name()}`" })
            }
        }
        onProgress(ProgressUpdate(title = title, body = body))
    }

    private fun progressTitle(summary: String): String {
        val first = summary.lineSequence().firstOrNull { it.isNotBlank() } ?: return ""
        return first
            .replace(Regex("[*_#`]"), "")
            .trim()
            .take(80)
    }

    private fun truncate(text: String, max: Int): String =
        if (text.length <= max) text else text.substring(0, max) + "..."

    private fun summarizeToolArguments(toolName: String, arguments: String): String {
        if (toolName != EditFileRequest::class.java.simpleName) return truncate(arguments, 2_000)
        return runCatching {
            val req = mapper.readValue(arguments, EditFileRequest::class.java)
            """{"file":${mapper.writeValueAsString(req.file)},"newContentChars":${req.newContent.length},"newContentPreview":${mapper.writeValueAsString(truncate(req.newContent, 240))}}"""
        }.getOrElse {
            truncate(arguments, 2_000)
        }
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

    private fun summarizeToolResult(toolResult: Any): String = when (toolResult) {
        is CompileKotlinResult ->
            "success=${toolResult.success} diagnostics=${toolResult.diagnostics.size} " +
                "setupChanges=${toolResult.setupChanges.count { it.changed || it.cacheChanged }}"
        is ReloadShadersResult ->
            "success=${toolResult.success} reloaded=${toolResult.reloaded.joinToString(",")} " +
                "errors=${toolResult.errors.size} visual=${toolResult.visualCheckAttached}"
        is ScratchpadResult ->
            "success=${toolResult.success} chars=${toolResult.charCount} message=${truncate(toolResult.message, 120)}"
        is ChecklistResult ->
            "success=${toolResult.success} items=${toolResult.items.size} message=${truncate(toolResult.message, 120)}"
        is WorkspaceSymbolSearchResult -> {
            val preview = toolResult.matches.take(3).joinToString(" | ") { match ->
                val guideHint = when {
                    match.relatedGuides.isNotEmpty() || match.relatedExamples.isNotEmpty() ->
                        " docs=${match.relatedGuides.size}/${match.relatedExamples.size}"
                    else -> ""
                }
                "${match.symbol}@${match.filePath ?: "?"}:${match.line ?: 0}$guideHint"
            }
            "matches=${toolResult.matches.size}" + if (preview.isNotBlank()) " top=$preview" else ""
        }
        is AttachmentResponse ->
            "file=${toolResult.file} language=${toolResult.language} chars=${toolResult.content.length} " +
                "guides=${toolResult.relatedGuides.size} examples=${toolResult.relatedExamples.size}"
        is ListContentsResult -> {
            val totalChars = toolResult.contents.sumOf { it.content.length }
            val graphHints = toolResult.preambleHints.count { it.graphRelevant }
            "entries=${toolResult.contents.size} primary=${toolResult.fileRef ?: "<none>"} " +
                "chars=$totalChars preambleHints=${toolResult.preambleHints.size} graphHints=$graphHints"
        }
        is ListDefinitionsResult -> {
            val preview = toolResult.definitions.take(5).joinToString(",") { it.name }
            "definitions=${toolResult.definitions.size}" + if (preview.isNotBlank()) " top=$preview" else ""
        }
        is ListKotlinContextVariablesResult -> {
            val preview = toolResult.variables.take(5).joinToString(",") { it.name }
            "success=${toolResult.success} variables=${toolResult.variables.size}" +
                if (preview.isNotBlank()) " top=$preview" else ""
        }
        is EvaluateKotlinExpressionResult ->
            "success=${toolResult.success} kind=${toolResult.valueKind} type=${toolResult.type} " +
                "string=${truncate(toolResult.stringValue.ifBlank { toolResult.message }, 200)}"
        is EditFileResponse ->
            "success=${toolResult.success}" + (toolResult.message?.let { " message=${truncate(it, 200)}" } ?: "")
        is Map<*, *> ->
            "keys=${toolResult.keys.joinToString(",") { it.toString() }}"
        else -> "type=${toolResult::class.java.simpleName}"
    }

    private fun supportsFileSearch(): Boolean = !runtimeConfig.isOpenRouter && chatModel != ChatModel.GPT_5_1_CODEX

    /* ------------------------------ Tools ---------------------------------- */

    private fun registerBuiltIns() {
        // Commentary: handler can be a no-op acknowledgement; we extract text from call args anyway.
        tools.register(Commentary::class.java) { _, _ -> mapOf("ok" to true) }

        // Attachments
        tools.register(ListAttachmentsRequest::class.java) { ctx, _ ->
            ListAttachmentsResult(
                attachments = ctx.attachments.map { AttachmentInfo(it.fileName, it.language, it.description) }
            )
        }
        tools.register(AttachmentRequest::class.java) { ctx, req ->
            val requested = req.file.trim()
            if (requested.isEmpty()) {
                throw IllegalArgumentException("Attachment name is empty.")
            }

            fun normalizePath(value: String): String = value.replace('\\', '/').trim()

            val normalizedRequested = normalizePath(requested)
            val hit = ctx.attachments.firstOrNull { it.fileName == requested }
                ?: ctx.attachments.firstOrNull { normalizePath(it.fileName) == normalizedRequested }
                ?: ctx.attachments.firstOrNull {
                    val normalizedCandidate = normalizePath(it.fileName)
                    normalizedCandidate.endsWith("/$normalizedRequested")
                }

            if (hit != null) {
                val related = RelatedContextDiscovery.forPath(hit.fileName)
                AttachmentResponse(
                    file = hit.fileName,
                    language = hit.language,
                    content = hit.code,
                    relatedGuides = related.guides,
                    relatedExamples = related.examples
                )
            } else {
                ctx.resolveAttachment?.invoke(requested)
                    ?: throw IllegalArgumentException(
                        "No attachment or resolvable file named '$requested'. Call ListAttachments first."
                    )
            }
        }

        // Kotlin-only tools (only work if provider is supplied in ctx)
        tools.register(ListDefinitionsRequest::class.java) { ctx, _ ->
            ctx.listDefinitions?.invoke()
                ?: throw IllegalStateException("ListDefinitions not available for this request.")
        }
        tools.register(ListContentsRequest::class.java) { ctx, _ ->
            ctx.listFileContents?.invoke()
                ?: throw IllegalStateException("ListContents not available for this request.")
        }
        tools.register(ListKotlinContextVariablesRequest::class.java) { ctx, req ->
            ctx.listKotlinContextVariables?.invoke(req)
                ?: throw IllegalStateException("ListKotlinContextVariables not available for this request.")
        }
        tools.register(EvaluateKotlinExpressionRequest::class.java) { ctx, req ->
            ctx.evaluateKotlinExpression?.invoke(req)
                ?: throw IllegalStateException("EvaluateKotlinExpression not available for this request.")
        }
        tools.register(EditFileRequest::class.java) { ctx, req ->
            ctx.editOtherFile?.invoke(req)
                ?: throw IllegalStateException("EditFile not available for this request.")
        }
        tools.register(CompileKotlinRequest::class.java) { ctx, req ->
            ctx.compileKotlin?.invoke(req)
                ?: throw IllegalStateException("CompileKotlin not available for this request.")
        }
        tools.register(ReloadShadersRequest::class.java) { ctx, req ->
            ctx.reloadShaders?.invoke(req)
                ?: throw IllegalStateException("ReloadShaders not available for this request.")
        }
        tools.register(ScratchpadReadRequest::class.java) { ctx, _ ->
            ctx.agentMemory?.readScratchpad()
                ?: throw IllegalStateException("Scratchpad not available for this request.")
        }
        tools.register(ScratchpadUpdateRequest::class.java) { ctx, req ->
            ctx.agentMemory?.updateScratchpad(req)
                ?: throw IllegalStateException("Scratchpad not available for this request.")
        }
        tools.register(ChecklistReadRequest::class.java) { ctx, _ ->
            ctx.agentMemory?.readChecklist()
                ?: throw IllegalStateException("Checklist not available for this request.")
        }
        tools.register(ChecklistUpdateRequest::class.java) { ctx, req ->
            ctx.agentMemory?.updateChecklist(req)
                ?: throw IllegalStateException("Checklist not available for this request.")
        }

        // Optional extra tool
        tools.register(WorkspaceSymbolSearch::class.java) { ctx, req ->
            ctx.workspaceSymbolSearch?.invoke(req)
                ?: throw IllegalStateException("WorkspaceSymbolSearch not available for this request.")
        }
    }

    companion object {
        val defaultThreadInstructions: String = """
You are an IDE-embedded code assistant working in a novel live-coding environment.
Return only code for the current file in responses (no markdown fences, no diffs, no prose).
If you have non-code thoughts, call the Commentary tool. Commentary, reasoning and questions there are welcome if you use the tool.
When verification tools are available, treat them as part of the edit loop: compile Kotlin after Kotlin edits, reload shaders after shader edits, inspect any post-reload screenshots for visual requests, fix failures, and repeat before finalizing.
        """.trimIndent()

        val defaultChatThreadInstructions: String = """
You are an IDE-embedded assistant working in a novel live-coding environment.
Respond in markdown. You may discuss, explain, and propose options.
If you need to change code, use the available tools rather than dumping entire files.
When verification tools are available, treat them as part of the edit loop: compile Kotlin after Kotlin edits, reload shaders after shader edits, inspect any post-reload screenshots for visual requests, fix failures, and repeat before finalizing.
        """.trimIndent()
    }
}
