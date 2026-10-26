/**
 * The OpenAI implementation of [LlmBridge]: Field's agent loop over the Responses API.
 *
 * Each turn sends the conversation and the shared tool definitions, runs the function calls the
 * model returns through the [ToolRegistry], and feeds the outputs back until the model answers in
 * text. Against OpenAI the history lives in a server-side conversation; against OpenRouter, which
 * has none, the bridge keeps it locally per thread. Rate-limit waits, suspended turns and the
 * request ledger are handled here too.
 */

package afield.ai.models

import afield.ai.conversation.SuspendsTurn
import afield.ai.tools.DescribeToolsRequest
import afield.ai.tools.excerpt

import afield.ai.conversation.AiRateLimitRecovery
import afield.ai.conversation.AiRequestLedger
import afield.ai.conversation.AiTurnInbox
import afield.ai.conversation.ContextReport
import afield.ai.conversation.ModelResponseFailure
import afield.ai.conversation.PartialModelResponseException
import afield.ai.conversation.PendingToolOutput
import afield.ai.conversation.Stability
import afield.ai.conversation.SuspendedRound
import afield.ai.conversation.TurnContext
import afield.ai.conversation.TurnSuspension
import afield.ai.conversation.aiToolFailureEndsTurn
import afield.ai.conversation.aiToolFailureMessage
import afield.ai.conversation.aiToolNotRunOutput
import afield.ai.conversation.isAiTurnCancellation
import afield.ai.conversation.sha256
import afield.ai.conversation.temporaryRateLimitDelay
import afield.ai.conversation.toolActivity
import afield.ai.conversation.userNotice
import afield.ai.conversation.withTools
import afield.ai.diagnostics.AiToolIoLogger
import afield.ai.diagnostics.LogSink
import afield.ai.diagnostics.StdoutLog
import afield.ai.diagnostics.TraceLogger
import afield.ai.diagnostics.log
import afield.ai.tools.AttachmentResponse
import afield.ai.tools.CarriesToolImages
import afield.ai.tools.ChecklistResult
import afield.ai.tools.Commentary
import afield.ai.tools.CompileKotlinResult
import afield.ai.tools.EditFileRequest
import afield.ai.tools.EditFileResponse
import afield.ai.tools.EvaluateKotlinExpressionResult
import afield.ai.tools.ListContentsResult
import afield.ai.tools.ListDefinitionsResult
import afield.ai.tools.ListKotlinContextVariablesResult
import afield.ai.tools.ReloadShadersResult
import afield.ai.tools.ScratchpadResult
import afield.ai.tools.SummarisesForToolLog
import afield.ai.tools.ToolExecutionContext
import afield.ai.tools.ToolRegistry
import afield.ai.tools.WorkspaceSymbolSearchResult
import afield.ai.tools.registerBuiltInAgentTools
import afield.ai.conversation.AiChatModelIdentity
import afield.ai.retrieval.RelatedContextDiscovery
import com.fasterxml.jackson.annotation.JsonIgnore
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

    /** The history saved for [state]'s stateless conversation, which a request may send fitted; for tests. */
    internal fun savedHistory(state: ThreadState): List<ResponseInputItem> =
        statelessHistory[state.localThreadId].orEmpty().toList()
    /**
     * The stateless provider's context window in tokens: configured ([configuredLocalAiContextTokens]), else
     * learned from the server's first refusal ([StatelessHistory.contextOverflow]). Null = not known yet.
     */
    @Volatile
    private var contextTokens: Int? = if (runtimeConfig.provider == "local") configuredLocalAiContextTokens() else null
    /** Characters per input token, from the last response's reported usage (tool schemas included). */
    @Volatile
    private var charsPerToken: Double = StatelessHistory.DEFAULT_CHARS_PER_TOKEN
    private val rateLimitRecovery = AiRateLimitRecovery.fromConfig()
    // Field owns the retry deadline and visible waiting status; do not multiply attempts in the SDK.
    private val responses = client.responses().withOptions { it.maxRetries(0) }
    private val asyncResponses = client.async().responses().withOptions { it.maxRetries(0) }
    private val supportsContextManagement = runtimeConfig.provider == "openai" && modelName.startsWith("gpt-6")
    // Pi exposes tools through its catalogue/program surface. Keep provider-side search only
    // for the legacy arm; making an already-promoted tool load a second time is redundant.
    private val deferTools = supportsContextManagement && !afield.ai.tools.program.ToolPrograms.enabled &&
        (System.getenv("AFIELD_AI_DEFER_TOOLS") ?: System.getProperty("afield.ai.deferTools") ?: "true").toBoolean()
    private val compactThreshold = (System.getenv("AFIELD_AI_COMPACT_THRESHOLD") ?: System.getProperty("afield.ai.compactThreshold"))
        ?.toLongOrNull()?.coerceIn(16_000, 400_000) ?: 96_000L
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
        if (runtimeConfig.usesStatelessResponses) {
            val conversationId = "${runtimeConfig.provider}:$localThreadId"
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
        return ThreadState(localThreadId, conversationId, fileKey, initialInstructions = instructions, initialFileText = fileText)
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
        val tc = turnContext?.takeIf { !runtimeConfig.usesStatelessResponses }
        // Stable instructions go into the conversation once, as a developer message, and again only when
        // they change. Measured 2026-10-02: sent instead as every request's `instructions`, they never
        // formed a prompt-cache prefix shared between conversations — each task's first request paid ~8k
        // uncached tokens instead of ~4k, and the cached share of single-turn eval tasks fell from 86% to
        // 74%. As the conversation's own opening message they are the same prefix in every conversation,
        // and a long conversation still holds one copy, not one per turn.
        val stableInstructions = tc?.ephemeralText(Stability.STABLE)?.takeIf { it.isNotBlank() }
            ?.let { "$it\n\n$currentToolTurnInstructions" }
        val stableHash = stableInstructions?.let { sha256(it).take(16) }
        val stableItem = if (stableInstructions != null && stableHash != state.stableInstructionsHash) {
            val text = if (state.stableInstructionsHash == null) stableInstructions
                else "Updated tool and context instructions; they replace the earlier ones.\n\n$stableInstructions"
            buildMessageItem(ResponseInputItem.Message.Role.DEVELOPER, text)
        } else null
        // The volatile rest (guide and tool catalogues, ~2k chars) goes in front of the user message, and so is
        // stored with it. Not as the request's `instructions`, though that would never be stored: measured on
        // four tasks in one build, any `instructions` parameter at all left every conversation's first request
        // cold (~7.5k uncached tokens each), where the same text in the input let conversations share their
        // cached prefix (~4k).
        val volatileText = tc?.ephemeralText(Stability.VOLATILE)?.takeIf { it.isNotBlank() }
        if (runtimeConfig.usesStatelessResponses) settleStatelessHistory(state)
        fun inputTextFor(prompt: String) = buildString {
            if (fileText != null && fileText != state.lastFileText) {
                appendLine("IDE_FILE_UPDATE:")
                appendLine("key: ${state.fileKey}")
                appendLine("content:")
                appendLine(fileText)
                appendLine()
            }
            if (volatileText != null) {
                appendLine(volatileText)
                appendLine()
            }
            if (state.lastTurnHitToolRoundCap) {
                appendLine(TOOL_ROUNDS_RESET_NOTE)
                appendLine()
            }
            appendLine(prompt)
        }
        val inputText = inputTextFor(tc?.durablePrompt() ?: userPrompt)
        // What a stateless history keeps of this prompt once the turn is over: the request and its durable
        // context, not the instructions, which every newer prompt carries again.
        val durableInputText = turnContext?.takeIf { runtimeConfig.usesStatelessResponses }
            ?.let { inputTextFor(it.durablePrompt()) }?.takeIf { it.length < inputText.length }

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
                    "reasoningEffort" to if (runtimeConfig.usesStatelessResponses) "provider-default" else reasoningEffort.toString(),
                    "conversationId" to state.conversationId,
                    // The person's own words, apart from the instructions around them (tools/ai4_conversation_review.py).
                    "request" to turnContext?.rest,
                    "localThreadId" to state.localThreadId,
                    "previousResponseId" to (state.previousResponseId ?: ""),
                    "continuesCappedTurn" to (state.cappedTurnContinuation != null),
                    "continuesSuspendedTurn" to (state.suspendedRound != null),
                    "fileKey" to state.fileKey,
                    "inputChars" to inputText.length,
                    "attachmentCount" to ctx.attachments.size,
                    "imageCount" to imageDataUrls.size,
                    "vectorStoreIds" to vectorStoreIds,
                    "maxOutputTokens" to maxOutputTokens,
                    "maxToolRounds" to maxToolRounds,
                    "prompt" to inputText,
                    "contextPlacement" to if (tc != null) "developer-once+input" else "inline",
                    "volatileChars" to (volatileText?.length ?: 0),
                    "stableInstructionsSent" to (stableItem != null),
                    "stableInstructionsChars" to (stableInstructions?.length ?: 0),
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

        // Capture attribution on the caller thread; streaming callbacks run on SDK threads.
        val requestAttribution = mapOf(
            "provider" to runtimeConfig.provider, "endpoint" to AiRequestLedger.endpoint(runtimeConfig.baseUrl),
            "model" to modelName, "reasoningEffort" to reasoningEffort.toString(),
            "fileKey" to state.fileKey,
            "documentPath" to (ctx.focus?.document ?: ctx.fieldGraph?.info?.filename),
            "documentName" to ctx.fieldGraph?.info?.documentName,
            "conversationId" to state.conversationId, "localThreadId" to state.localThreadId,
            "turnId" to UUID.randomUUID().toString(), "toolIoSessionId" to toolIoSession?.id
        )
        fun requestLedger(phase: String) = AiRequestLedger.request(requestAttribution + mapOf(
            "phase" to phase, "maxOutputTokens" to maxOutputTokens
        ))

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
                        val inputDetails = runCatching { usage.inputTokensDetails() }.getOrNull()
                        val cachedTokens = runCatching { inputDetails?.cachedTokens() }.getOrNull()
                        cachedTokens?.let { cached ->
                            cacheReadTokens = (cacheReadTokens ?: 0L) + cached
                        }
                        toolIo("model_usage", mapOf(
                            "phase" to phase, "responseId" to result.id(),
                            "inputTokens" to usage.inputTokens(),
                            "cachedInputTokens" to cachedTokens,
                            "cacheWriteTokens" to inputDetails?._additionalProperties()?.get("cache_write_tokens")?.convert(Long::class.javaObjectType),
                            "outputTokens" to usage.outputTokens(),
                            "reasoningTokens" to runCatching { usage.outputTokensDetails().reasoningTokens() }.getOrNull(),
                            "compactionItems" to result.output().count { it.isCompaction() }
                        ))
                    }
                }
            } finally {
                val elapsed = (System.nanoTime() - started) / 1_000_000
                modelElapsedMs += elapsed
                toolIo("model_request_end", mapOf("phase" to phase, "elapsedMs" to elapsed, "success" to succeeded))
            }
        }

        val callCtx = ctx.copy(
            reportProgress = onProgress ?: ctx.reportProgress,
            promoted = ctx.promoted ?: state.promotedTools,
            progress = ctx.progress ?: onProgress?.let { p -> { line: String -> p(ProgressUpdate(title = line, phase = "tool")) } },
        )
        // The calls the provider holds that it has no outputs for yet, and the outputs this turn has for
        // them so far. A turn that fails while there are any leaves an output for each to the next one.
        var openCalls: List<PendingToolCall> = emptyList()
        var openOutputs: List<PendingToolOutput> = emptyList()
        try {
        // After a suspended turn, the outputs of the calls its last response made; after a capped turn, the
        // calls it left unrun and its forced final answer. Either goes ahead of this prompt.
        val continuationItems = state.suspendedRound?.outputs.orEmpty().map { functionOutput(it.callId, it.outputJson) } +
            state.cappedTurnContinuation?.let(::continuationItems).orEmpty()
        val currentPrompt = buildPromptItems(inputText, imageDataUrls).single()
        val promptItems = continuationItems + listOfNotNull(stableItem) + currentPrompt

        val paramsBuilder = ResponseCreateParams.builder()
            .model(chatModel)
            .maxOutputTokens(maxOutputTokens)
            .toolChoice(ToolChoiceOptions.AUTO)

        if (runtimeConfig.usesStatelessResponses) {
            paramsBuilder.input(ResponseCreateParams.Input.ofResponse(historyForRequest(state, promptItems, maxOutputTokens) + promptItems))
        } else if (imageDataUrls.isEmpty() && stableItem == null && continuationItems.isEmpty()) {
            paramsBuilder.input(inputText)
        } else {
            paramsBuilder.input(ResponseCreateParams.Input.ofResponse(promptItems))
        }
        if (runtimeConfig.usesStatelessResponses) {
            // OpenRouter's Responses API is stateless; local history is sent in input instead.
        } else if (state.previousResponseId == null) {
            paramsBuilder.conversation(state.conversationId)
        } else {
            paramsBuilder.previousResponseId(state.previousResponseId)
        }
        paramsBuilder.text(textConfig)
        addReasoningIfSupported(paramsBuilder)
        tools.addAllTo(paramsBuilder, callCtx, deferLoading = deferTools, loadedTools = state.loadedTools)
        if (vectorStoreIds.isNotEmpty() && supportsFileSearch()) {
            paramsBuilder.addFileSearchTool(vectorStoreIds)
        }

        info("running prompt <<spinner>>")
        var response: Response = modelRequest("initial") {
            createFittedResponse(state, currentPrompt, paramsBuilder, promptItems, maxOutputTokens, onTextDelta, onProgress, requestLedger("initial"))
        }
        if (stableItem != null) state.stableInstructionsHash = stableHash
        state.cappedTurnContinuation = null
        state.suspendedRound = null
        if (onTextDelta != null) {
            info("")
        }
        state.previousResponseId = response.id()
        if (fileText != null) state.lastFileText = fileText
        if (runtimeConfig.usesStatelessResponses) {
            // Marked before it is stored, so the item cap cannot drop the turn it belongs to.
            val prompt = promptItems.last()
            state.currentPrompt = prompt
            state.settleOnNextTurn = durableInputText?.let { prompt to buildPromptItems(it, imageDataUrls).single() }
        }
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
            rememberDiscoveredTools(state, resp) { event, fields -> toolIo(event, fields) }
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
        var suspension: TurnSuspension? = null
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
            val roundResults = mutableListOf<Any>()
            val roundOutputs = mutableListOf<PendingToolOutput>()
            openCalls = calls.map { PendingToolCall(it.callId(), it.name()) }
            openOutputs = roundOutputs
            for (call in calls) {
                log.toolActivity(call.name())
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
                } catch (e: Throwable) {
                    if (aiToolFailureEndsTurn(e)) throw e
                    val message = aiToolFailureMessage(e)
                    error("tool: ERROR name=${call.name()} msg=$message")
                    toolIo(
                        "tool_exception",
                        mapOf(
                            "round" to (rounds + 1),
                            "toolName" to call.name(),
                            "callId" to call.callId(),
                            "exceptionType" to e::class.java.name,
                            "message" to message
                        )
                    )
                    mapOf(
                        "error" to true,
                        "tool" to call.name(),
                        "message" to message
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
                if (toolFailed) log.userNotice("An action could not finish. Details are in the chat and Tool pool.", attention = true)
                toolTranscript += ToolTranscriptEntry(
                    name = call.name(),
                    callId = call.callId(),
                    argumentsJson = call.arguments(),
                    resultSummary = toolResultSummary,
                    resultJson = toolResultJson,
                    elapsedMs = toolMs
                )
                toolTranscript += afield.ai.tools.program.nestedTranscriptEntries(call.callId(), toolResult)

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
                afield.ai.tools.program.nestedToolIoEvents(rounds + 1, call.callId(), toolResult)
                    .forEach { (event, fields) -> toolIo(event, fields) }
                onProgress?.invoke(
                    ProgressUpdate(
                        title = "${call.name()} ${if (toolFailed) "failed" else "finished"} (${toolMs} ms)",
                        body = afield.ai.tools.program.nestedTraceMarkdown(toolResult) ?: if (toolFailed) toolResultSummary else "",
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
                roundResults += toolResult
                roundOutputs += PendingToolOutput(call.callId(), call.name(), toolResultJson, toolFailed)

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
            // A tool ended the turn (AwaitStudyBuildsRequest, AskUserQuestionRequest): no follow-up request. The outputs go with the
            // next turn, and whatever the person wrote meanwhile stays in the inbox, which becomes a turn of its own.
            // An image a co-scheduled call returned is not carried over; its JSON output is.
            TurnSuspension.of(roundResults)?.let { awaited ->
                suspension = awaited
                if (runtimeConfig.usesStatelessResponses) appendStatelessHistory(state, roundOutputs.map { functionOutput(it.callId, it.outputJson) })
                else state.suspendedRound = SuspendedRound(roundOutputs.toList())
                openCalls = emptyList()
                rounds++
                info("turn suspended after round $rounds: waiting for ${awaited.describe()} without the model")
                toolIo("turn_suspended", mapOf("round" to rounds, "jobIds" to awaited.jobs.map { it.jobId },
                    "deadlineMs" to awaited.deadlineMs, "pendingCallIds" to roundOutputs.map { it.callId }))
            }
            if (suspension != null) break

            // What the person wrote while this round ran (AiTurnInbox), after every call's output.
            callCtx.inbox?.drainForModel()?.takeIf { it.isNotEmpty() }?.let { taken ->
                val text = AiTurnInbox.modelText(taken)
                toolOutputs += buildMessageItem(ResponseInputItem.Message.Role.USER, text, taken.flatMap { it.images })
                info("user interjection: ${taken.size} message(s), ${text.length} chars")
                toolIo("user_interjection", mapOf("round" to (rounds + 1), "messages" to taken.size, "text" to text))
            }

            info("creating response (follow-up) <<spinner>>")
            val followupBuilder = ResponseCreateParams.builder()
                .model(chatModel)
                .maxOutputTokens(maxOutputTokens)
                .toolChoice(ToolChoiceOptions.AUTO)
            if (runtimeConfig.usesStatelessResponses) {
                followupBuilder.input(ResponseCreateParams.Input.ofResponse(historyForRequest(state, toolOutputs, maxOutputTokens) + toolOutputs))
            } else {
                followupBuilder.previousResponseId(response.id())
                followupBuilder.input(ResponseCreateParams.Input.ofResponse(toolOutputs))
            }
            followupBuilder.text(textConfig)
            addReasoningIfSupported(followupBuilder)
            tools.addAllTo(followupBuilder, callCtx, deferLoading = deferTools, loadedTools = state.loadedTools)
            if (vectorStoreIds.isNotEmpty() && supportsFileSearch()) {
                followupBuilder.addFileSearchTool(vectorStoreIds)
            }

            response = modelRequest("followup") {
                createFittedResponse(state, currentPrompt, followupBuilder, toolOutputs, maxOutputTokens, onTextDelta, onProgress, requestLedger("followup"))
            }
            if (onTextDelta != null) {
                info("")
            }
            state.previousResponseId = response.id()
            openCalls = emptyList()
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

        // A suspended turn's last calls all ran; their outputs wait in suspendedRound, not for a forced final.
        val pendingCalls = if (suspension != null) emptyList() else response.output()
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
            openCalls = pendingCalls.map { PendingToolCall(it.callId(), it.name()) }
            openOutputs = emptyList()
            response = modelRequest("forced_final") { createForcedFinalResponse(
                state = state,
                currentPrompt = currentPrompt,
                previousResponse = response,
                pendingCalls = pendingCalls,
                maxOutputTokens = maxOutputTokens,
                onTextDelta = onTextDelta,
                onProgress = onProgress,
                ctx = callCtx,
                vectorStoreIds = vectorStoreIds,
                ledger = requestLedger("forced_final")
            ) }
            if (onTextDelta != null) {
                info("")
            }
            // The next turn branches from the response that made the pending calls, which
            // previousResponseId still names, never from this one: see CappedTurnContinuation.
            state.cappedTurnContinuation = CappedTurnContinuation(
                pendingCalls = openCalls,
                finalText = extractOutputTextWithNewlines(response)
            )
            openCalls = emptyList()
            info("forced-final response id=${response.id()} outputItems=${response.output().size}; " +
                "the next turn continues from ${state.previousResponseId}")
            toolIo(
                "response",
                mapOf(
                    "phase" to "forced_final",
                    "responseId" to response.id(),
                    "outputItems" to response.output().size,
                    "messageCount" to response.output().count { it.isMessage() },
                    "functionCallCount" to response.output().count { it.isFunctionCall() },
                    "nextTurnContinuesFrom" to state.previousResponseId
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
                "status" to if (suspension != null) "suspended" else "completed",
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
            suspension = suspension,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cacheReadTokens = cacheReadTokens,
            contextReport = (turnContext?.report(if (tc != null) "developer-once+input" else "inline"))
                ?.copy(inputTokens = inputTokens, cacheReadTokens = cacheReadTokens)?.withTools(tools, callCtx)
        )
        } catch (failure: Throwable) {
            val cancelled = isAiTurnCancellation(failure)
            if (openCalls.isNotEmpty()) runCatching { answerOpenCalls(state, openCalls, openOutputs, failure) }
            // A stateless thread outlives a failed turn (Ai4Installer keeps it), so a request that never got an
            // answer stays in its history: "can you try that again?" must have something to refer to.
            if (runtimeConfig.usesStatelessResponses && state.currentPrompt == null && !cancelled) runCatching {
                appendStatelessHistory(state, listOf(StatelessHistory.userText(
                    (durableInputText ?: inputText).trimEnd() + "\n\n[This request failed before it was answered: ${failure.message?.take(160)}]")))
            }
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
        /**
         * Set when a tool ended the turn to wait ([SuspendsTurn]): what it waits for. The caller arranges
         * the resumption; the thread state holds the last round's outputs for the turn that resumes it.
         */
        val suspension: TurnSuspension? = null,
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
        var previousResponseId: String? = null,
        var lastFileText: String? = initialFileText,
        val loadedTools: MutableSet<String> = linkedSetOf()
    ) {
        /**
         * Deferred tools this conversation has loaded (DescribeToolsRequest). Thread-local on purpose: a new
         * provider thread — a fresh conversation, or a rewound branch — starts with only the DIRECT tools.
         */
        val promotedTools: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** The hash of the stable instructions this conversation already holds as a developer message. */
        @Volatile
        var stableInstructionsHash: String? = null

        /**
         * Set when the last turn ended at its tool-round cap: what the next turn sends ahead of its own
         * prompt. [previousResponseId] then still names the response that made the pending calls, not the
         * forced final one, because nothing may chain onto the forced final request. Cleared once the next
         * turn's first request succeeds, so a turn that fails before then sends it again.
         */
        @Volatile
        var cappedTurnContinuation: CappedTurnContinuation? = null

        /**
         * Set when the last turn was suspended by a tool ([SuspendsTurn]): the outputs of its last round, which
         * the next turn sends first. [previousResponseId] still names the response that made those calls.
         * Cleared once the next turn's first request succeeds. The OpenRouter path appends the outputs to
         * its local history instead and never sets it.
         */
        @Volatile
        var suspendedRound: SuspendedRound? = null

        /** Stateless history: this turn's full prompt item, which nothing may drop while the turn runs. */
        @get:JsonIgnore
        @Volatile
        var currentPrompt: ResponseInputItem? = null

        /** Stateless history: the finished turn's full prompt and the durable item that replaces it. */
        @get:JsonIgnore
        @Volatile
        var settleOnNextTurn: Pair<ResponseInputItem, ResponseInputItem>? = null

        /** The last turn ended at its tool-round cap; the next turn's input says the limit has reset. */
        @get:JsonIgnore
        val lastTurnHitToolRoundCap: Boolean get() = cappedTurnContinuation != null
    }

    /**
     * How a capped turn's answer reaches the conversation: as plain assistant text after "not run"
     * outputs for its pending calls, sent with the next turn, rather than as the forced final response
     * itself. That response was made under "summarize ... without calling tools", and what the server
     * keeps of it does not stay request-scoped, even with the stop sent as `instructions`. On
     * house-machine-11 (2026-10-05) the forced final request also compacted the conversation (the next
     * turn's input was 55k tokens, down from 100k); the next two turns, chained onto it, were offered
     * every tool with tool_choice auto and answered "tool execution is still disabled here" without a
     * call, though the first of them carried the reset note. 2026-10-04 had been the same, without the note.
     */
    data class CappedTurnContinuation(
        val pendingCalls: List<PendingToolCall> = emptyList(),
        val finalText: String = ""
    )

    data class PendingToolCall(val callId: String, val name: String)

    // Hosted discovery state can be lost to compaction while repeat searches return no tools.
    // Keep the already-discovered subset callable in later requests. Definitions still come
    // from the current registry, so removed tools and changed document scope stay authoritative.
    private fun rememberDiscoveredTools(state: ThreadState, response: Response, record: (String, Map<String, Any?>) -> Unit) {
        response.output().forEach { item ->
            val raw = item._json().orElse(null)?.asObject()?.orElse(null) ?: return@forEach
            when (raw["type"]?.asString()?.orElse(null)) {
                "tool_search_call" -> record("tool_search_call", mapOf("responseId" to response.id(), "arguments" to raw["arguments"]?.toString()))
                "tool_search_output" -> {
                    val names = raw["tools"]?.asArray()?.orElse(null).orEmpty().mapNotNull {
                        it.asObject().orElse(null)?.get("name")?.asString()?.orElse(null)
                    }
                    state.loadedTools.addAll(names)
                    record("tool_search_output", mapOf("responseId" to response.id(), "loadedTools" to names, "knownTools" to state.loadedTools.sorted()))
                }
            }
        }
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

    private fun createResponse(
        state: ThreadState,
        currentPrompt: ResponseInputItem,
        params: ResponseCreateParams,
        onTextDelta: ((String) -> Unit)?,
        onProgress: ((ProgressUpdate) -> Unit)?,
        ledger: AiRequestLedger.Request
    ): Response {
        var prepared = params.toBuilder().apply {
            if (supportsContextManagement) contextManagement(listOf(
                ResponseCreateParams.ContextManagement.builder().type("compaction").compactThreshold(compactThreshold).build()
            ))
        }.build()
        var emittedText = false
        var lastRetry = 0
        var contextRetries = 0
        var retainedHistory: List<ResponseInputItem>? = null
        val originalHistory = if (runtimeConfig.provider == "local") historyForRequest(state) else emptyList()
        val responseInput = params.input().orElse(null)
        val pendingInput: List<ResponseInputItem>? = if (runtimeConfig.provider == "local" && responseInput?.isResponse() == true) {
            responseInput.asResponse().takeIf { it.take(originalHistory.size) == originalHistory }?.drop(originalHistory.size)
        } else null
        while (true) {
            try {
                val response = rateLimitRecovery.run(
                    onWait = { wait ->
                        if (lastRetry != wait.retry) {
                            lastRetry = wait.retry
                            ledger.retry(wait.retry, wait.remainingMs)
                            log.userNotice("The AI service is busy. Retrying automatically in ${(wait.remainingMs + 999) / 1000}s; completed work is kept.", attention = true)
                            warn("response-create temporarily limited; retry ${wait.retry} in ${wait.remainingMs}ms")
                        }
                        onProgress?.invoke(ProgressUpdate(
                            "Provider busy · retrying in ${(wait.remainingMs + 999) / 1000}s",
                            "Waiting automatically. Completed tool work is retained.", phase = "model"
                        ))
                    },
                    onRetry = { onProgress?.invoke(ProgressUpdate("Waiting for model", phase = "model")) }
                ) {
                    val attempt = ledger.attempt()
                    try {
                        createResponseOnce(prepared, onTextDelta?.let { sink -> { chunk -> emittedText = true; sink(chunk) } }, attempt).also { response ->
                            attempt.response(response)
                            validateModelResponse(response)
                            attempt.finish()
                            info("response provider=${runtimeConfig.provider} requestedModel=$modelName " +
                                "responseModel=${response.model()} responseId=${response.id()} status=${response.status().orElse(null)}")
                        }
                    } catch (failure: Exception) {
                        attempt.finish(failure)
                        if (emittedText && !isAiTurnCancellation(failure)) {
                            // A provider can send a temporary rate limit inside an already-started SSE
                            // response. Only this unfinished model request is retried: tool dispatch and
                            // previousResponseId advancement happen after createResponse returns. Discard
                            // its accumulator, including any unfinished function calls, but retain the
                            // visible partial answer with an explicit boundary before the next attempt.
                            if (onProgress != null && temporaryRateLimitDelay(failure) != null) {
                                onProgress(ProgressUpdate(
                                    "Answer interrupted by a temporary provider limit",
                                    "Partial text is retained. Retrying the unfinished model request; completed tool work will not run again.",
                                    phase = "interrupted"
                                ))
                                emittedText = false
                            } else throw PartialModelResponseException(failure)
                        }
                        throw failure
                    }
                }
                retainedHistory?.let { statelessHistory[state.localThreadId] = it.toMutableList() }
                return response
            } catch (failure: Exception) {
                if (runtimeConfig.provider != "local" || emittedText || !isLocalContextRecoveryFailure(failure)) throw failure
                val problem = if (isLocalEmptyAnswer(failure)) "returned no answer" else "exceeds its context window"
                if (contextRetries >= 2) throw LocalContextRecoveryExhausted(
                    "The local model still $problem after two history reductions. " +
                        "Start a fresh conversation or reduce the current input; completed tool work is retained.", failure
                )
                val currentInput = pendingInput ?: throw failure
                val shorter = shortenLocalHistory(
                    retainedHistory ?: originalHistory, currentInput, currentPrompt, seedHistory(state).size
                ) ?: throw failure
                retainedHistory = shorter
                prepared = prepared.toBuilder().input(ResponseCreateParams.Input.ofResponse(shorter + currentInput)).build()
                contextRetries++
                ledger.retry(contextRetries, 0)
                val title = if (isLocalEmptyAnswer(failure)) "Local model returned no answer" else "Local model context full"
                warn("$title; shortened history and retrying model request ($contextRetries/2)")
                log.userNotice("$title. Shortening older history and retrying automatically; completed tool work is retained.", attention = true)
                onProgress?.invoke(ProgressUpdate(
                    "$title · retrying ($contextRetries/2)",
                    "Older exchanges are omitted. The current prompt and latest tool results are retained.", phase = "model"
                ))
            }
        }
    }

    private fun createResponseOnce(params: ResponseCreateParams, onTextDelta: ((String) -> Unit)?, attempt: AiRequestLedger.Attempt): Response {
        if (onTextDelta == null) return responses.create(params)
        val acc = ResponseAccumulator.create()
        val stream = asyncResponses.createStreaming(params)
        try {
            stream.subscribe({ event ->
                // The accumulator only yields completed responses. Error/failed/incomplete
                // events need explicit handling or their cause becomes "Completed response
                // is not yet received", hiding both rate-limit recovery and useful feedback.
                event.error().ifPresent { throw ModelResponseFailure(it.code().orElse("error"), it.message()) }
                event.failed().ifPresent { attempt.response(it.response()); validateModelResponse(it.response()) }
                event.incomplete().ifPresent { attempt.response(it.response()); validateModelResponse(it.response()) }
                acc.accumulate(event)
                event.outputTextDelta().ifPresent { delta ->
                    val chunk = delta.delta()
                    if (chunk.isNotEmpty()) onTextDelta(chunk)
                }
            }).onCompleteFuture().get()
            return acc.response()
        } catch (failure: Exception) {
            traceResponseError("streaming", failure)
            throw failure
        } finally {
            stream.close()
        }
    }

    private fun validateModelResponse(response: Response) {
        response.error().ifPresent { throw ModelResponseFailure(it.code().toString(), it.message()) }
        when (response.status().orElse(null)?.toString()) {
            "incomplete" -> throw ModelResponseFailure(
                response.incompleteDetails().orElse(null)?.reason()?.orElse(null)?.toString() ?: "incomplete",
                "The provider ended before completing the answer. No function calls from this response were executed."
            )
            "failed" -> throw ModelResponseFailure("failed", "The provider returned a failed response without error details.")
        }
        if (runtimeConfig.provider == "local" && response.output().none { it.isFunctionCall() } &&
            extractOutputTextWithNewlines(response).isBlank()) {
            val refusal = response.output().mapNotNull { it.message().orElse(null) }
                .flatMap { it.content() }.mapNotNull { it.refusal().orElse(null)?.refusal() }.joinToString("\n")
            if (refusal.isNotBlank()) throw ModelResponseFailure("refusal", refusal)
            throw ModelResponseFailure("local_empty_answer",
                "The local model returned no answer or tool call. It may have exhausted its context or output budget while thinking. " +
                    "Start a fresh conversation or reduce the input if automatic recovery cannot make room.")
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
        currentPrompt: ResponseInputItem,
        previousResponse: Response,
        pendingCalls: List<ResponseFunctionToolCall>,
        maxOutputTokens: Long,
        onTextDelta: ((String) -> Unit)?,
        onProgress: ((ProgressUpdate) -> Unit)?,
        ctx: ToolExecutionContext,
        vectorStoreIds: List<String>,
        ledger: AiRequestLedger.Request
    ): Response {
        // No later request chains onto this one (see CappedTurnContinuation), so the stop can be input,
        // where it keeps the cached prefix. As `instructions` it made this request read nothing from cache:
        // 100,750 of 100,823 input tokens were uncached on house-machine-11.
        val inputItems = pendingCalls.map { notRunOutput(it.callId(), it.name()) } +
            buildMessageItem(ResponseInputItem.Message.Role.DEVELOPER, FORCED_FINAL_INSTRUCTION)

        val builder = ResponseCreateParams.builder()
            .model(chatModel)
            .maxOutputTokens(maxOutputTokens)
        if (runtimeConfig.usesStatelessResponses) {
            builder.input(ResponseCreateParams.Input.ofResponse(historyForRequest(state, inputItems, maxOutputTokens) + inputItems))
        } else {
            builder.previousResponseId(previousResponse.id())
            builder.input(ResponseCreateParams.Input.ofResponse(inputItems))
        }
        builder.text(textConfig)
        addReasoningIfSupported(builder)
        info("creating response (forced-final) <<spinner>>")
        tools.addAllTo(builder, ctx, deferLoading = deferTools, loadedTools = state.loadedTools)
        if (vectorStoreIds.isNotEmpty() && supportsFileSearch()) builder.addFileSearchTool(vectorStoreIds)
        builder.toolChoice(ToolChoiceOptions.NONE)
        // Stateless history gets nothing from this request either: its stop instruction and its reasoning
        // under that stop stay out, and the next turn adds the continuation as it does on the direct path.
        return createResponse(state, currentPrompt, builder.build(), onTextDelta, onProgress, ledger)
    }

    /**
     * Leave the next turn an output for each of [calls], the ones a failed turn left the provider holding
     * without outputs: what [outputs] has for a call that ran, a "not finished" error for the rest. Without
     * them the thread is poisoned: `previous_response_id` names a response whose function calls have no
     * outputs, and the provider refuses every later request on it ("No tool output found for function call").
     * The chained path sends them as the opening of the next turn, as after a suspended one; the stateless
     * path appends them to its own history, which already holds the calls.
     */
    private fun answerOpenCalls(state: ThreadState, calls: List<PendingToolCall>, outputs: List<PendingToolOutput>, failure: Throwable) {
        val byId = outputs.associateBy { it.callId }
        val answered = calls.map { byId[it.callId] ?: aiToolNotRunOutput(it.callId, it.name, failure) }
        if (runtimeConfig.usesStatelessResponses) appendStatelessHistory(state, answered.map { functionOutput(it.callId, it.outputJson) })
        else state.suspendedRound = SuspendedRound(answered)
        warn("turn failed with ${calls.size} call(s) unanswered by the provider; the next turn opens with their outputs")
    }

    private fun functionOutput(callId: String, outputJson: String): ResponseInputItem =
        ResponseInputItem.ofFunctionCallOutput(ResponseInputItem.FunctionCallOutput.builder().callId(callId).output(outputJson).build())

    private fun notRunOutput(callId: String, name: String): ResponseInputItem =
        ResponseInputItem.ofFunctionCallOutput(
            ResponseInputItem.FunctionCallOutput.builder()
                .callId(callId)
                .outputAsJson(
                    mapOf(
                        "error" to true,
                        "aborted" to true,
                        "tool" to name,
                        "reason" to "max_tool_rounds_reached",
                        "message" to "Not run: the turn's tool budget ran out first. A later turn may call it again."
                    )
                )
                .build()
        )

    /** The next turn's opening input items after a capped turn; see [CappedTurnContinuation]. */
    private fun continuationItems(continuation: CappedTurnContinuation): List<ResponseInputItem> =
        continuation.pendingCalls.map { notRunOutput(it.callId, it.name) } +
            listOfNotNull(continuation.finalText.takeIf { it.isNotBlank() }?.let { text ->
                ResponseInputItem.ofEasyInputMessage(
                    EasyInputMessage.builder().role(EasyInputMessage.Role.ASSISTANT).content(text).build()
                )
            })

    private val FORCED_FINAL_INSTRUCTION =
        "The tool budget for this turn has been reached. For this response only, summarize the verified " +
            "result and unfinished work without calling tools. The aborted calls did not execute. A later " +
            "user turn has its own tool budget."

    // Repairs older response chains once in the stable developer prefix.
    // Ordinary requests keep the measured shared-cache layout.
    private val currentToolTurnInstructions =
        "Tools available on this request may be used when needed, within the current permissions " +
            "and restrictions. A tool budget or temporary tool-stop instruction from an earlier " +
            "user turn does not disable tools for the current user turn. This grants no additional authorization."

    // Said once, in the input of the turn after a capped one, after that turn's continuation items.
    private val TOOL_ROUNDS_RESET_NOTE =
        "The previous turn stopped at its tool-round limit. That limit has reset: this turn has its own full " +
            "tool budget, so use tools as the request needs."

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

    /**
     * The stored history to send ahead of [tail], first fitted to the context window when that is known:
     * older turns go whole, and, as a last resort, this turn's own long outputs are cut. See [StatelessHistory].
     */
    private fun historyForRequest(state: ThreadState, tail: List<ResponseInputItem> = emptyList(), maxOutputTokens: Long = 0): List<ResponseInputItem> {
        val history = statelessHistory.computeIfAbsent(state.localThreadId) { seedHistory(state).toMutableList() }
        val window = contextTokens ?: return history.toList()
        val budgetChars = (StatelessHistory.inputBudget(window, maxOutputTokens) * charsPerToken).toLong()
        val tailChars = StatelessHistory.chars(tail)
        fun over() = StatelessHistory.chars(history) + tailChars > budgetChars
        if (!over()) return history.toList()
        val before = StatelessHistory.chars(history)
        val seedCount = seedHistory(state).size
        val protectFrom = state.currentPrompt?.let { p -> history.indexOfFirst { it === p } }?.takeIf { it >= 0 } ?: history.size
        val removed = StatelessHistory.dropOldestTurns(history, seedCount, protectFrom, ::over)
        if (over()) {
            val turnStart = state.currentPrompt?.let { p -> history.indexOfFirst { it === p } }?.takeIf { it >= 0 } ?: seedCount
            StatelessHistory.settle(history, turnStart + 1)
        }
        warn("stateless history fitted to ${window}-token window: $before -> ${StatelessHistory.chars(history)} chars " +
            "($removed item(s) dropped, ${"%.2f".format(charsPerToken)} chars/token, tail $tailChars chars)" +
            if (over()) "; still over budget" else "")
        return history.toList()
    }

    /** At a turn boundary: the finished turn's prompt becomes its durable text, and old outputs and images shrink. */
    private fun settleStatelessHistory(state: ThreadState) {
        val history = statelessHistory[state.localThreadId] ?: return
        StatelessHistory.settle(history, seedHistory(state).size, replace = state.settleOnNextTurn)
        state.settleOnNextTurn = null
        state.currentPrompt = null
    }

    /**
     * [createResponse] for a request built by [builder], whose input ends with [tail]. On a stateless
     * provider a refusal for size teaches [contextTokens] and [charsPerToken], and the request is fitted and
     * sent once more instead of failing the turn (it failed, and lost the thread, on 2026-10-22).
     */
    private fun createFittedResponse(
        state: ThreadState,
        currentPrompt: ResponseInputItem,
        builder: ResponseCreateParams.Builder,
        tail: List<ResponseInputItem>,
        maxOutputTokens: Long,
        onTextDelta: ((String) -> Unit)?,
        onProgress: ((ProgressUpdate) -> Unit)?,
        ledger: AiRequestLedger.Request
    ): Response {
        if (!runtimeConfig.usesStatelessResponses) return createResponse(state, currentPrompt, builder.build(), onTextDelta, onProgress, ledger)
        val sentChars = StatelessHistory.chars(historyForRequest(state, tail, maxOutputTokens)) + StatelessHistory.chars(tail)
        val response = try {
            createResponse(state, currentPrompt, builder.build(), onTextDelta, onProgress, ledger)
        } catch (failure: Exception) {
            val overflow = StatelessHistory.contextOverflow(failure) ?: throw failure
            contextTokens = overflow.availableTokens
            overflow.requestedTokens?.takeIf { it > 0 }?.let { charsPerToken = (sentChars.toDouble() / it).coerceIn(1.0, 8.0) }
            // The local recovery inside createResponse already shortened the history twice; keep what the
            // error says about the window, but do not retry a third time.
            if (failure is LocalContextRecoveryExhausted) throw failure
            warn("provider refused ${overflow.requestedTokens} tokens in a ${overflow.availableTokens}-token window; fitting the history and retrying")
            onProgress?.invoke(ProgressUpdate("Conversation trimmed to fit the model's context window", phase = "model"))
            builder.input(ResponseCreateParams.Input.ofResponse(historyForRequest(state, tail, maxOutputTokens) + tail))
            createResponse(state, currentPrompt, builder.build(), onTextDelta, onProgress, ledger)
        }
        runCatching { response.usage().orElse(null)?.inputTokens() }.getOrNull()?.takeIf { it > 0 }?.let { tokens ->
            charsPerToken = (sentChars.toDouble() / tokens).coerceIn(1.0, 8.0)
        }
        return response
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
        if (!runtimeConfig.usesStatelessResponses || items.isEmpty()) return
        val history = statelessHistory.computeIfAbsent(state.localThreadId) { seedHistory(state).toMutableList() }
        history += items
        val maxItems = System.getenv(if (runtimeConfig.provider == "local") "AFIELD_LOCAL_AI_HISTORY_ITEMS" else "AFIELD_OPENROUTER_HISTORY_ITEMS")?.toIntOrNull()?.coerceAtLeast(8) ?: 80
        if (history.size > maxItems) {
            // Whole earlier turns only: cutting at an arbitrary item left function calls without their outputs.
            val protectFrom = state.currentPrompt?.let { p -> history.indexOfFirst { it === p } }?.takeIf { it >= 0 } ?: history.size
            StatelessHistory.dropOldestTurns(history, seedHistory(state).size, protectFrom) { history.size > maxItems }
        }
    }

    private fun addReasoningIfSupported(builder: ResponseCreateParams.Builder) {
        if (!runtimeConfig.usesStatelessResponses) builder.reasoning(reasoningConfig)
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
        // Reasoning summaries describe model deliberation, not completed work. The chat
        // already streams its user-facing commentary; tools and build waits report actual
        // execution progress. Do not insert generated summaries as execution milestones.
        onProgress?.invoke(ProgressUpdate("", modelIdentity = responseIdentity(response)))
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
                "offset=${toolResult.offset} total=${toolResult.totalChars} excerpt=${toolResult.isExcerpt} next=${toolResult.nextOffset} " +
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
        // A failure's message is what the tool disclosure should show, not the map's keys.
        is Map<*, *> -> (toolResult["message"] as? String)?.takeIf { toolResult["error"] == true || toolResult["success"] == false }
            ?.let { "failed: ${truncate(it, 300)}" }
            ?: "keys=${toolResult.keys.joinToString(",") { it.toString() }}"
        else -> "type=${toolResult::class.java.simpleName}"
    }

    private fun supportsFileSearch(): Boolean = !runtimeConfig.usesStatelessResponses && chatModel != ChatModel.GPT_5_1_CODEX

    /* ------------------------------ Tools ---------------------------------- */

    private fun registerBuiltIns() {
        registerBuiltInAgentTools(tools)
    }

    companion object {
        internal fun retryDelayMsForRateLimitMessage(message: String, multiplier: Double = 1.5): Long? {
            if (!Regex("(?i)rate[ _-]?limit|\\b429\\b|slow_down|server_is_overloaded").containsMatchIn(message)) return null
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
