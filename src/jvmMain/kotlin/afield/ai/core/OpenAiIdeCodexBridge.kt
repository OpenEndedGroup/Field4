package afield.ai.core

import afield.AiChatModelIdentity
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
import kotlin.math.ceil
import kotlin.math.min

class OpenAiIdeCodexBridge(
    private val runtimeConfig: AiProviderRuntimeConfig = activeAiProviderRuntimeConfig(),
    private val client: OpenAIClient = openAiClientFromFieldConfig(),
    model: String = Ai4ModelDefaults.flagshipModel(),
    private val log: LogSink = StdoutLog,
    private val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM
) {
    val modelName: String = model
    val modelIdentity: AiChatModelIdentity get() = AiChatModelIdentity(runtimeConfig.provider, modelName)
    private fun responseIdentity(response: Response): AiChatModelIdentity {
        val reported = response.model()
        val name = when {
            reported.isString() -> reported.asString()
            reported.isChat() -> reported.asChat().asString()
            reported.isOnly() -> reported.asOnly().asString()
            else -> reported._json().orElse(null)?.asString()?.orElse(null)
        }
        return modelIdentity.copy(responseModel = name)
    }
    private val chatModel: ChatModel = ChatModel.of(modelName)
    private val mapper: ObjectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
    private val tools = ToolRegistry()
    private val trace: TraceLogger? = TraceLogger.fromEnv()
    private val toolIoLog: AiToolIoLogger? = AiToolIoLogger.fromEnvOrDefault(log)
    private val textConfig: ResponseTextConfig =
        ResponseTextConfig.builder().format(ResponseFormatText.builder().build()).build()
    private val reasoningConfig: Reasoning =
        Reasoning.builder()
            .effort(reasoningEffort)
            .summary(Reasoning.Summary.DETAILED)
            .build()
    private val statelessHistory = ConcurrentHashMap<String, MutableList<ResponseInputItem>>()
    private val maxRateLimitRetries: Int =
        (System.getenv("AFIELD_AI_RATE_LIMIT_RETRIES") ?: System.getProperty("afield.ai.rateLimitRetries") ?: "4")
            .toIntOrNull()
            ?.coerceIn(0, 12)
            ?: 4
    private val maxRateLimitDelayMs: Long =
        (System.getenv("AFIELD_AI_RATE_LIMIT_MAX_DELAY_MS") ?: System.getProperty("afield.ai.rateLimitMaxDelayMs") ?: "30000")
            .toLongOrNull()
            ?.coerceIn(250L, 300_000L)
            ?: 30_000L
    private fun info(message: String) = log.log("info,ai.bridge", message)
    private fun warn(message: String) = log.log("warning,ai.bridge", message)
    private fun error(message: String) = log.log("error,ai.bridge", message)

    init {
        registerBuiltIns()
        info("Initialized OpenAiIdeCodexBridge(model=$chatModel)")
    }

    fun <T : Any> registerTool(toolClass: Class<T>, handler: (ToolExecutionContext, T) -> Any) {
        tools.register(toolClass, handler = handler)
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
        onProgress: ((ProgressUpdate) -> Unit)? = null,
        turnContext: TurnContext? = null
    ): SendResult {
        val t0 = System.nanoTime()

        // OpenRouter's stateless Responses path keeps its own history and its support for a per-request
        // `instructions` is unverified, so it stays on the one-string prompt.
        val tc = turnContext?.takeIf { !runtimeConfig.isOpenRouter }
        // Sent with every request of this turn and never carried to the next: the Responses API does not
        // carry `instructions` over a previous_response_id, which is exactly what is wanted here.
        val ephemeralInstructions = tc?.allEphemeralText()?.takeIf { it.isNotBlank() }
        val inputText = buildString {
            if (fileText != null) {
                appendLine("IDE_FILE_UPDATE:")
                appendLine("key: ${state.fileKey}")
                appendLine("content:")
                appendLine(fileText)
                appendLine()
            }
            appendLine(tc?.durablePrompt() ?: userPrompt)
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

        val toolIoSession = runCatching {
            toolIoLog?.begin(
                mapOf(
                    "model" to chatModel.toString(),
                    "provider" to runtimeConfig.provider,
                    "reasoningEffort" to if (runtimeConfig.isOpenRouter) "provider-default" else reasoningEffort.toString(),
                    "conversationId" to state.conversationId,
                    "localThreadId" to state.localThreadId,
                    "previousResponseId" to (state.previousResponseId ?: ""),
                    "fileKey" to state.fileKey,
                    "inputChars" to inputText.length,
                    "attachmentCount" to ctx.attachments.size,
                    "imageCount" to imageDataUrls.size,
                    "vectorStoreIds" to vectorStoreIds,
                    "maxOutputTokens" to maxOutputTokens,
                    "maxToolRounds" to maxToolRounds,
                    "prompt" to inputText,
                    "contextPlacement" to if (ephemeralInstructions != null) "instructions" else "inline",
                    "instructionsChars" to (ephemeralInstructions?.length ?: 0),
                    "instructionHash" to tc?.instructionHash(),
                    "fragments" to tc?.fragments?.map { mapOf("source" to it.source, "retention" to it.retention.name,
                        "stability" to it.stability.name, "chars" to it.chars) }
                )
            )
        }.onFailure { throwable ->
            warn("tool I/O log unavailable: ${throwable.message ?: throwable::class.java.simpleName}")
        }.getOrNull()
        var toolIoFailureReported = false
        fun toolIo(event: String, fields: Map<String, Any?> = emptyMap()) {
            val session = toolIoSession ?: return
            val logger = toolIoLog ?: return
            runCatching {
                logger.event(session, event, fields)
            }.onFailure { throwable ->
                if (!toolIoFailureReported) {
                    toolIoFailureReported = true
                    warn("tool I/O log write failed: ${throwable.message ?: throwable::class.java.simpleName}")
                }
            }
        }

        var modelElapsedMs = 0L
        var toolElapsedMs = 0L
        var terminalRecorded = false
        var activePhase = "preparing"
        var activeToolStarted: Long? = null
        var inputTokens: Long? = null
        var outputTokens: Long? = null
        var cacheReadTokens: Long? = null
        fun <T> modelRequest(phase: String, action: () -> T): T {
            activePhase = "model:$phase"
            toolIo("model_request", mapOf("phase" to phase))
            onProgress?.invoke(ProgressUpdate("Waiting for model", phase = "model"))
            val started = System.nanoTime()
            var succeeded = false
            try {
                return action().also { result ->
                    succeeded = true
                    (result as? Response)?.usage()?.orElse(null)?.let { usage ->
                        inputTokens = (inputTokens ?: 0L) + usage.inputTokens()
                        outputTokens = (outputTokens ?: 0L) + usage.outputTokens()
                        runCatching { usage.inputTokensDetails().cachedTokens() }.getOrNull()?.let { cached ->
                            cacheReadTokens = (cacheReadTokens ?: 0L) + cached
                        }
                    }
                }
            } finally {
                val elapsed = (System.nanoTime() - started) / 1_000_000
                modelElapsedMs += elapsed
                toolIo("model_request_end", mapOf("phase" to phase, "elapsedMs" to elapsed, "success" to succeeded))
            }
        }

        val callCtx = ctx.copy(
            promoted = ctx.promoted ?: state.promotedTools,
            progress = ctx.progress ?: onProgress?.let { p -> { line: String -> p(ProgressUpdate(title = line, phase = "tool")) } },
        )
        try {
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
        applyTurnContext(paramsBuilder, ephemeralInstructions)
        tools.addAllTo(paramsBuilder, callCtx)
        if (vectorStoreIds.isNotEmpty() && supportsFileSearch()) {
            paramsBuilder.addFileSearchTool(vectorStoreIds)
        }

        info("running prompt <<spinner>>")
        var response: Response = modelRequest("initial") { createResponse(paramsBuilder.build(), onTextDelta) }
        if (onTextDelta != null) {
            info("")
        }
        state.previousResponseId = response.id()
        appendStatelessHistory(state, promptItems + inputItemsFromOutput(response))
        info("initial response id=${response.id()} outputItems=${response.output().size} <<spinner>>")
        toolIo(
            "response",
            mapOf(
                "phase" to "initial",
                "responseId" to response.id(),
                "responseModel" to response.model().toString(),
                "outputItems" to response.output().size,
                "messageCount" to response.output().count { it.isMessage() },
                "functionCallCount" to response.output().count { it.isFunctionCall() }
            )
        )

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
                throw IllegalStateException("Model response failed: ${e.code()} ${e.message()}")
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
                toolIo(
                    "tool_call",
                    mapOf(
                        "round" to (rounds + 1),
                        "toolName" to call.name(),
                        "callId" to call.callId(),
                        "arguments" to call.arguments(),
                        "argumentSummary" to argumentSummary
                    )
                )
                onProgress?.invoke(
                    ProgressUpdate(
                        title = "Running ${call.name()}",
                        phase = "tool"
                    )
                )
                trace?.log(
                    "TOOL_CALL",
                    "name=${call.name()} callId=${call.callId()}\nargs=${call.arguments()}"
                )

                activePhase = "tool:${call.name()}"
                val toolStarted = System.nanoTime()
                activeToolStarted = toolStarted
                val toolResult: Any = try {
                    tools.execute(callCtx, call)
                } catch (e: Exception) {
                    if (isAiTurnCancellation(e)) throw e
                    error("tool: ERROR name=${call.name()} msg=${e.message}")
                    toolIo(
                        "tool_exception",
                        mapOf(
                            "round" to (rounds + 1),
                            "toolName" to call.name(),
                            "callId" to call.callId(),
                            "exceptionType" to e::class.java.name,
                            "message" to (e.message ?: "")
                        )
                    )
                    mapOf(
                        "error" to true,
                        "tool" to call.name(),
                        "message" to (e.message ?: e::class.java.name)
                    )
                }

                val toolResultJson = runCatching { mapper.writeValueAsString(toolResult) }
                    .getOrElse { toolResult.toString() }
                val toolMs = (System.nanoTime() - toolStarted) / 1_000_000
                toolElapsedMs += toolMs
                activeToolStarted = null
                val toolResultSummary = summarizeToolResult(toolResult)
                val toolFailed = runCatching { mapper.readTree(toolResultJson).let {
                    it["success"]?.asBoolean() == false || it["error"]?.asBoolean() == true
                } }.getOrDefault(false)
                toolTranscript += ToolTranscriptEntry(
                    name = call.name(),
                    callId = call.callId(),
                    argumentsJson = call.arguments(),
                    resultSummary = toolResultSummary,
                    resultJson = toolResultJson,
                    elapsedMs = toolMs
                )
                toolTranscript += afield.ai.core.program.nestedTranscriptEntries(call.callId(), toolResult)

                trace?.log(
                    "TOOL_OUTPUT",
                    buildString {
                        appendLine("name=${call.name()} callId=${call.callId()}")
                        append(toolResultJson)
                    }
                )
                info("tool: result name=${call.name()} $toolResultSummary")
                toolIo(
                    "tool_result",
                    mapOf(
                        "round" to (rounds + 1),
                        "toolName" to call.name(),
                        "callId" to call.callId(),
                        "summary" to toolResultSummary,
                        "elapsedMs" to toolMs,
                        "result" to toolResultJson,
                        // The images are @JsonIgnore'd out of `result` on purpose, so without this the
                        // log cannot tell whether the model was shown a picture — which is exactly the
                        // variable the tool-use improvement loop needs when a geometry tool is involved.
                        "imageCount" to ((toolResult as? CarriesToolImages)?.toolImages?.size ?: 0),
                        "imageCaptions" to
                            ((toolResult as? CarriesToolImages)?.toolImages?.map { it.caption } ?: emptyList())
                    )
                )
                afield.ai.core.program.nestedToolIoEvents(rounds + 1, call.callId(), toolResult)
                    .forEach { (event, fields) -> toolIo(event, fields) }
                onProgress?.invoke(
                    ProgressUpdate(
                        title = "${call.name()} ${if (toolFailed) "failed" else "finished"} (${toolMs} ms)",
                        body = if (toolFailed) toolResultSummary else "",
                        phase = "tool_result"
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

                // Any tool result may carry images, including ReloadShaders' post-reload screenshot.
                //
                // That one used to be handled separately, just above here, by matching
                // ReloadShadersResult by name -- and its settle-delay wording was the stated reason to
                // keep the two paths apart. The wording now rides along as the image's caption, which
                // removes the duplication and, more to the point, the divergence: a by-name special
                // case is per-bridge by construction, and AnthropicBridge never grew its half, so
                // under that provider the screenshot was dropped in silence. One path, both bridges.
                val carried = (toolResult as? CarriesToolImages)?.toolImages
                    .orEmpty()
                    .filter { it.dataUrl.isNotBlank() }
                if (carried.isNotEmpty()) {
                    conversationAdditions += buildString {
                        appendLine("<<ai>>")
                        append(responseIdentity(response).marker())
                        carried.forEachIndexed { index, image ->
                            if (index > 0) appendLine()
                            if (image.caption.isNotBlank()) {
                                appendLine("_${image.caption}_")
                                appendLine()
                            }
                            append("![tool image](")
                            append(image.dataUrl)
                            append(")")
                            appendLine()
                        }
                    }
                    val builder = ResponseInputItem.Message.builder()
                        .role(ResponseInputItem.Message.Role.USER)
                        .addInputTextContent(
                            "The ${call.name()} tool returned ${carried.size} image(s): " +
                                carried.mapIndexed { index, image ->
                                    "(${index + 1}) ${image.caption.ifBlank { "no caption" }}"
                                }.joinToString("; ") +
                                ". Inspect them against what the user asked for. If what you see is not " +
                                "what was asked for, fix it before finalizing."
                        )
                    carried.forEach { image ->
                        builder.addContent(
                            ResponseInputImage.builder()
                                .imageUrl(image.dataUrl)
                                .detail(ResponseInputImage.Detail.HIGH)
                                .build()
                        )
                    }
                    toolOutputs += ResponseInputItem.ofMessage(builder.build())
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
            applyTurnContext(followupBuilder, ephemeralInstructions)
            tools.addAllTo(followupBuilder, callCtx)
            if (vectorStoreIds.isNotEmpty() && supportsFileSearch()) {
                followupBuilder.addFileSearchTool(vectorStoreIds)
            }

            response = modelRequest("followup") { createResponse(followupBuilder.build(), onTextDelta) }
            if (onTextDelta != null) {
                info("")
            }
            state.previousResponseId = response.id()
            appendStatelessHistory(state, toolOutputs + inputItemsFromOutput(response))
            info("follow-up response id=${response.id()} outputItems=${response.output().size}")
            toolIo(
                "response",
                mapOf(
                    "phase" to "followup",
                    "round" to (rounds + 1),
                    "responseId" to response.id(),
                    "responseModel" to response.model().toString(),
                    "outputItems" to response.output().size,
                    "messageCount" to response.output().count { it.isMessage() },
                    "functionCallCount" to response.output().count { it.isFunctionCall() }
                )
            )
            logOutputShape(response)
            emitResponseProgress(response, onProgress)

            rounds++
        }

        val pendingCalls = response.output()
            .filter { it.isFunctionCall() }
            .mapNotNull { it.functionCall().orElse(null) }
        val hitToolRoundCap = pendingCalls.isNotEmpty()
        if (pendingCalls.isNotEmpty()) {
            warn(
                "maxToolRounds=$maxToolRounds reached with ${pendingCalls.size} pending tool call(s); " +
                    "requesting a final answer without further tools."
            )
            toolIo(
                "max_tool_rounds",
                mapOf(
                    "maxToolRounds" to maxToolRounds,
                    "pendingToolCalls" to pendingCalls.map { it.name() },
                    "pendingCallIds" to pendingCalls.map { it.callId() }
                )
            )
            response = modelRequest("forced_final") { createForcedFinalResponse(
                state = state,
                previousResponse = response,
                pendingCalls = pendingCalls,
                maxOutputTokens = maxOutputTokens,
                onTextDelta = onTextDelta,
                ephemeralInstructions = ephemeralInstructions
            ) }
            if (onTextDelta != null) {
                info("")
            }
            state.previousResponseId = response.id()
            info("forced-final response id=${response.id()} outputItems=${response.output().size}")
            toolIo(
                "response",
                mapOf(
                    "phase" to "forced_final",
                    "responseId" to response.id(),
                    "outputItems" to response.output().size,
                    "messageCount" to response.output().count { it.isMessage() },
                    "functionCallCount" to response.output().count { it.isFunctionCall() }
                )
            )
            logOutputShape(response)
        }

        onProgress?.invoke(ProgressUpdate("", modelIdentity = responseIdentity(response)))
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
        terminalRecorded = true
        toolIo(
            "session_end",
            mapOf(
                "responseId" to response.id(),
                "textChars" to text.length,
                "commentaryCount" to commentaryMessages.size,
                "toolCallCount" to toolTranscript.size,
                "elapsedMs" to ms,
                "status" to "completed",
                "modelElapsedMs" to modelElapsedMs,
                "toolElapsedMs" to toolElapsedMs
            )
        )
        onProgress?.invoke(ProgressUpdate("Completed", phase = "completed"))

        return SendResult(
            threadState = state,
            text = text,
            rawResponseId = response.id(),
            commentary = commentaryMessages.toList(),
            conversationAdditions = conversationAdditions.toList(),
            toolTranscript = toolTranscript.toList(),
            modelIdentity = responseIdentity(response),
            rounds = rounds,
            hitToolRoundCap = hitToolRoundCap,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cacheReadTokens = cacheReadTokens,
            contextReport = (turnContext?.report(if (ephemeralInstructions != null) "instructions" else "inline"))
                ?.copy(inputTokens = inputTokens, cacheReadTokens = cacheReadTokens)?.withTools(tools, callCtx)
        )
        } catch (failure: Throwable) {
            val cancelled = isAiTurnCancellation(failure)
            activeToolStarted?.let { toolElapsedMs += (System.nanoTime() - it) / 1_000_000 }
            if (!terminalRecorded) {
                terminalRecorded = true
                toolIo(if (cancelled) "session_cancelled" else "session_failed", mapOf(
                    "status" to if (cancelled) "cancelled" else "failed",
                    "phase" to activePhase,
                    "elapsedMs" to (System.nanoTime() - t0) / 1_000_000,
                    "modelElapsedMs" to modelElapsedMs, "toolElapsedMs" to toolElapsedMs,
                    "exceptionType" to failure::class.java.name, "message" to (failure.message ?: "")))
                runCatching { onProgress?.invoke(ProgressUpdate(
                    if (cancelled) "Cancelled" else "Failed during $activePhase",
                    phase = if (cancelled) "cancelled" else "failed")) }
            }
            throw failure
        }
    }

    fun threadStateToJson(state: ThreadState): String = mapper.writeValueAsString(state)
    fun threadStateFromJson(json: String): ThreadState = mapper.readValue(json)

    data class SendResult(
        val threadState: ThreadState,
        val text: String,
        val rawResponseId: String,
        val commentary: List<String> = emptyList(),
        val conversationAdditions: List<String> = emptyList(),
        val toolTranscript: List<ToolTranscriptEntry> = emptyList(),
        val modelIdentity: AiChatModelIdentity? = null,
        /** Tool rounds this turn used; -1 when the bridge does not say. */
        val rounds: Int = -1,
        /** True when the turn hit maxToolRounds and the bridge forced a final answer. */
        val hitToolRoundCap: Boolean = false,
        /** Summed over every model request in the turn; null when the provider did not report usage. */
        val inputTokens: Long? = null,
        val outputTokens: Long? = null,
        /**
         * Input tokens served from the provider's prompt cache, summed over the turn (a subset of
         * [inputTokens] on OpenAI; reported separately by Anthropic). Null when the provider did not say.
         */
        val cacheReadTokens: Long? = null,
        /** Input tokens written to the prompt cache this turn (Anthropic only); null when not reported. */
        val cacheWriteTokens: Long? = null,
        /** What the turn sent besides the conversation, and where: see [TurnContext]. Null for a one-string turn. */
        val contextReport: ContextReport? = null
    )

    data class ToolTranscriptEntry(
        val name: String,
        val callId: String,
        val argumentsJson: String,
        val resultSummary: String,
        val resultJson: String,
        /** Wall time the handler took; -1 when the bridge did not measure it. */
        val elapsedMs: Long = -1,
        /**
         * The call this one ran inside, or "" for a call the model made itself. A tool program's
         * nested calls carry the program call's id here, and their own id is `<parent>/<n>`.
         */
        val parentCallId: String = ""
    )

    data class ProgressUpdate(
        val title: String,
        val body: String = "",
        val modelIdentity: AiChatModelIdentity? = null,
        val phase: String? = null
    )

    data class ThreadState(
        val localThreadId: String,
        val conversationId: String,
        val fileKey: String,
        val initialInstructions: String = "",
        val initialFileText: String = "",
        var previousResponseId: String? = null
    ) {
        /**
         * Deferred tools this conversation has loaded (DescribeToolsRequest). Thread-local on purpose: a new
         * provider thread — a fresh conversation, or a rewound branch — starts with only the DIRECT tools.
         */
        val promotedTools: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    }

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

    private fun createResponse(params: ResponseCreateParams, onTextDelta: ((String) -> Unit)?): Response =
        withRateLimitRetry("response-create") {
            createResponseOnce(params, onTextDelta).also { response ->
                info("response provider=${runtimeConfig.provider} requestedModel=$modelName " +
                    "responseModel=${response.model()} responseId=${response.id()} status=${response.status().orElse(null)}")
            }
        }

    private fun createResponseOnce(params: ResponseCreateParams, onTextDelta: ((String) -> Unit)?): Response {
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
            if (isAiTurnCancellation(t)) throw t
            if (rateLimitRetryDelayMs(t) != null) {
                traceResponseError("streaming", t)
                throw t
            }
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

    private fun withRateLimitRetry(stage: String, block: () -> Response): Response {
        var attempt = 0
        var lastError: Throwable? = null
        while (attempt <= maxRateLimitRetries) {
            try {
                return block()
            } catch (t: Throwable) {
                lastError = t
                val delayMs = rateLimitRetryDelayMs(t)
                if (delayMs == null || attempt >= maxRateLimitRetries) {
                    if (delayMs != null) {
                        warn(
                            "$stage rate-limit retry budget exhausted after ${attempt + 1} attempt(s); " +
                                "last suggested delay=${delayMs}ms"
                        )
                    }
                    throw t
                }
                val boundedDelay = min(delayMs, maxRateLimitDelayMs)
                warn(
                    "$stage rate limited; retrying in ${boundedDelay}ms " +
                        "(attempt ${attempt + 2}/${maxRateLimitRetries + 1}, suggested*1.5=${delayMs}ms)"
                )
                try {
                    Thread.sleep(boundedDelay)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw t
                }
                attempt++
            }
        }
        throw lastError ?: IllegalStateException("$stage failed without an exception")
    }

    private fun rateLimitRetryDelayMs(t: Throwable): Long? =
        retryDelayMsForRateLimitMessage(renderThrowableChain(t), multiplier = 1.5)

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

    /**
     * The turn's ephemeral instructions, on every request builder of the turn (initial, follow-up, forced
     * final) because none of them carries over.
     *
     * The cache key is the instructions' hash, not the thread: the instructions are the start of every
     * request, so conversations with the same tools share a prefix, and a key routes requests that share
     * it to the same cache. A per-thread key (the first cut, 2026-10-02) did the opposite — every new
     * conversation started cold — and the measured cache-hit share of single-turn eval tasks fell from
     * 86% to 74%, nearly doubling the uncached input.
     */
    private fun applyTurnContext(builder: ResponseCreateParams.Builder, ephemeralInstructions: String?) {
        if (ephemeralInstructions == null) return
        builder.instructions(ephemeralInstructions)
        builder.promptCacheKey("afield-ai4-" + sha256(ephemeralInstructions).take(16))
    }

    private fun createForcedFinalResponse(
        state: ThreadState,
        previousResponse: Response,
        pendingCalls: List<ResponseFunctionToolCall>,
        maxOutputTokens: Long,
        onTextDelta: ((String) -> Unit)?,
        ephemeralInstructions: String? = null
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
        applyTurnContext(builder, ephemeralInstructions)
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
        onProgress(ProgressUpdate("", modelIdentity = responseIdentity(response)))
        val summaries = extractReasoningSummaries(response)
        val calls = response.output()
            .filter { it.isFunctionCall() }
            .mapNotNull { it.functionCall().orElse(null) }
        if (summaries.isEmpty()) return

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
        // Anything outside core that can describe itself. This used to be a `when` branch naming one
        // domain cluster's result type, which put a reference to that cluster in the AI plumbing; the
        // interface inverts it, and now any tool result gets a useful log line by implementing it.
        is SummarisesForToolLog -> truncate(toolResult.toolLogSummary(), 400)
        is Map<*, *> ->
            "keys=${toolResult.keys.joinToString(",") { it.toString() }}"
        else -> "type=${toolResult::class.java.simpleName}"
    }

    private fun supportsFileSearch(): Boolean = !runtimeConfig.isOpenRouter && chatModel != ChatModel.GPT_5_1_CODEX

    /* ------------------------------ Tools ---------------------------------- */

    private fun registerBuiltIns() {
        registerBuiltInAgentTools(tools)
    }

    companion object {
        internal fun retryDelayMsForRateLimitMessage(message: String, multiplier: Double = 1.5): Long? {
            if (!message.contains("429") && !message.contains("rate limit", ignoreCase = true)) return null
            val hintedMs = retryHintMs(message) ?: return null
            return ceil(hintedMs * multiplier).toLong().coerceAtLeast(1L)
        }

        private fun retryHintMs(message: String): Double? {
            val ms = Regex("""(?i)(?:try\s+again\s+in|retry\s+after)\s+([0-9]+(?:\.[0-9]+)?)\s*ms\b""")
                .find(message)
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()
            if (ms != null) return ms

            val seconds = Regex("""(?i)(?:try\s+again\s+in|retry\s+after)\s+([0-9]+(?:\.[0-9]+)?)\s*s(?:ec(?:ond)?s?)?\b""")
                .find(message)
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()
            if (seconds != null) return seconds * 1000.0

            val retryAfterSeconds = Regex("""(?i)\bretry-after\s*[:=]\s*([0-9]+(?:\.[0-9]+)?)\b""")
                .find(message)
                ?.groupValues
                ?.getOrNull(1)
                ?.toDoubleOrNull()
            if (retryAfterSeconds != null) return retryAfterSeconds * 1000.0

            return null
        }

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
