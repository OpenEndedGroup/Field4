package afield.ai.core

import afield.AiChatModelIdentity
import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolChoiceNone
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ToolResultBlockParam
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Anthropic (Claude) implementation of [LlmBridge], mirroring [OpenAiIdeCodexBridge]'s
 * agent loop over the Messages API.
 *
 * Reuses the shared tool layer verbatim: [registerBuiltInAgentTools] populates the same
 * [ToolRegistry] the OpenAI bridge uses, and [ToolRegistry.describe]/[ToolRegistry.executeByName]
 * provide the provider-neutral tool enumeration + dispatch. The Messages API is stateless
 * (no server-side conversation / previous_response_id), so history is kept locally per thread —
 * the same shape as the OpenAI bridge's OpenRouter `statelessHistory` branch.
 *
 * v1 runs the loop non-streaming and delivers assistant text through `onTextDelta` at the end;
 * `onProgress` (tool use/finish, commentary) works live. True token streaming via
 * `client.messages().createStreaming(...)` and image/thinking support are follow-ups.
 */
class AnthropicBridge(
    private var client: AnthropicClient = anthropicClientFromFieldConfig(),
    private val model: String = Ai4ModelDefaults.flagshipModel(),
    private val log: LogSink = StdoutLog,
    /** Where [client] sends its requests, for the request ledger: the SDK client does not say. */
    private val baseUrl: String = configuredAnthropicBaseUrl() ?: ANTHROPIC_DEFAULT_BASE_URL
) : LlmBridge {
    override val modelIdentity: AiChatModelIdentity get() = AiChatModelIdentity("anthropic", model)
    private val tools = ToolRegistry()

    /**
     * The same per-turn JSONL the OpenAI bridge writes (`~/.afield/logs/ai4_tool_io/`). Without it the
     * tool-use improvement loop, and any usage count taken from those logs, only ever saw OpenAI turns.
     */
    private val toolIoLog: AiToolIoLogger? = AiToolIoLogger.fromEnvOrDefault(log)

    /** Per-thread message history (stateless API — we resend the full transcript each turn). */
    private val history = ConcurrentHashMap<String, MutableList<MessageParam>>()

    private val maxRateLimitRetries: Int =
        (System.getenv("AFIELD_AI_RATE_LIMIT_RETRIES") ?: System.getProperty("afield.ai.rateLimitRetries") ?: "4")
            .toIntOrNull()?.coerceIn(0, 12) ?: 4
    private val maxRateLimitDelayMs: Long =
        (System.getenv("AFIELD_AI_RATE_LIMIT_MAX_DELAY_MS") ?: System.getProperty("afield.ai.rateLimitMaxDelayMs") ?: "30000")
            .toLongOrNull()?.coerceIn(250L, 300_000L) ?: 30_000L

    private fun info(message: String) = log.log("info,ai.bridge.anthropic", message)
    private fun warn(message: String) = log.log("warning,ai.bridge.anthropic", message)
    private fun error(message: String) = log.log("error,ai.bridge.anthropic", message)

    init {
        registerBuiltInAgentTools(tools)
        info("Initialized AnthropicBridge(model=$model)")
    }

    /** As [OpenAiIdeCodexBridge.registerTool]: one more tool for this bridge only (tests use it for a probe). */
    fun <T : Any> registerTool(toolClass: Class<T>, handler: (ToolExecutionContext, T) -> Any) {
        tools.register(toolClass, handler = handler)
    }

    override fun startThread(fileKey: String, fileText: String, instructions: String): LlmThreadState {
        val localThreadId = UUID.randomUUID().toString()
        val state = OpenAiIdeCodexBridge.ThreadState(
            localThreadId = localThreadId,
            conversationId = "anthropic:$localThreadId",
            fileKey = fileKey,
            initialInstructions = instructions.ifBlank { OpenAiIdeCodexBridge.defaultThreadInstructions },
            initialFileText = fileText
        )
        info("startThread(): fileKey=$fileKey localThreadId=$localThreadId")
        return state
    }

    override fun send(
        state: LlmThreadState,
        userPrompt: String,
        fileText: String?,
        ctx: ToolExecutionContext,
        vectorStoreIds: List<String>,
        maxOutputTokens: Long,
        maxToolRounds: Int,
        imageDataUrls: List<String>,
        onTextDelta: ((String) -> Unit)?,
        onProgress: ((LlmProgressUpdate) -> Unit)?,
        turnContext: TurnContext?
    ): LlmSendResult {
        val t0 = System.nanoTime()
        if (imageDataUrls.isNotEmpty()) {
            warn("imageDataUrls (${imageDataUrls.size}) ignored — image input is a v1 follow-up for the Anthropic bridge.")
        }
        if (vectorStoreIds.isNotEmpty()) {
            warn("vectorStoreIds ignored — Anthropic has no hosted file_search; wire retrieval as a tool instead.")
        }

        val system = state.initialInstructions.ifBlank { OpenAiIdeCodexBridge.defaultThreadInstructions }
        val messages = history.getOrPut(state.localThreadId) { mutableListOf() }
        // With a TurnContext: the stable instructions ride in the system prompt (cached), the volatile ones
        // on the newest message only, and the stored history keeps just the durable prompt.
        val tc = turnContext
        val systemBlocks = anthropicSystemBlocks(system, tc?.ephemeralText(Stability.STABLE).orEmpty(), cached = tc != null)
        val volatilePrefix = tc?.ephemeralText(Stability.VOLATILE).orEmpty()

        // Seed the initial file context once per thread (mirrors OpenAiIdeCodexBridge.startThread).
        if (messages.isEmpty() && state.initialFileText.isNotBlank()) {
            messages += userText(
                buildString {
                    appendLine("IDE_FILE_CONTEXT:")
                    appendLine("key: ${state.fileKey}")
                    appendLine("content:")
                    appendLine(state.initialFileText)
                }
            )
        }

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
        // After a suspended turn, its last round's tool results open this turn's message, ahead of the prompt.
        val suspendedResults = state.suspendedRound?.outputs.orEmpty()
        fun turnMessage(text: String): MessageParam = if (suspendedResults.isEmpty()) userText(text) else
            MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(
                suspendedResults.map { ContentBlockParam.ofToolResult(toolResultBlock(it.callId, it.outputJson, it.isError, it.outputJson, ::warn)) } +
                    ContentBlockParam.ofText(TextBlockParam.builder().text(text).build())).build()
        val turnMessageIndex = messages.size
        messages += turnMessage(if (volatilePrefix.isEmpty()) inputText else "$volatilePrefix\n\n$inputText")
        // In the history now, so a retry after a failed request does not send the results twice.
        state.suspendedRound = null

        info("convId=${state.conversationId} fileKey=${state.fileKey} inputChars=${inputText.length} maxOutputTokens=$maxOutputTokens")

        // Field names match OpenAiIdeCodexBridge's, so one reader (tools/ai_tool_usage.py, the
        // improvement monitor) covers both providers.
        val toolIoSession = runCatching {
            toolIoLog?.begin(
                mapOf(
                    "model" to model,
                    "provider" to "anthropic",
                    "reasoningEffort" to "provider-default",
                    "conversationId" to state.conversationId,
                    "localThreadId" to state.localThreadId,
                    "fileKey" to state.fileKey,
                    "inputChars" to inputText.length,
                    "attachmentCount" to ctx.attachments.size,
                    "imageCount" to imageDataUrls.size,
                    "maxOutputTokens" to maxOutputTokens,
                    "maxToolRounds" to maxToolRounds,
                    "prompt" to inputText
                )
            )
        }.onFailure { warn("tool I/O log unavailable: ${it.message ?: it::class.java.simpleName}") }.getOrNull()
        var toolIoFailureReported = false
        fun toolIo(event: String, fields: Map<String, Any?> = emptyMap()) {
            val session = toolIoSession ?: return
            val logger = toolIoLog ?: return
            runCatching { logger.event(session, event, fields) }.onFailure {
                if (!toolIoFailureReported) {
                    toolIoFailureReported = true
                    warn("tool I/O log write failed: ${it.message ?: it::class.java.simpleName}")
                }
            }
        }

        // The request ledger's attribution, as OpenAiIdeCodexBridge's, so tools/ai_usage.py counts both providers.
        val requestAttribution = mapOf(
            "provider" to "anthropic", "endpoint" to AiRequestLedger.endpoint(baseUrl),
            "model" to model, "reasoningEffort" to "provider-default",
            "fileKey" to state.fileKey,
            "documentPath" to (ctx.focus?.document ?: ctx.fieldGraph?.info?.filename),
            "documentName" to ctx.fieldGraph?.info?.documentName,
            "conversationId" to state.conversationId, "localThreadId" to state.localThreadId,
            "turnId" to UUID.randomUUID().toString(), "toolIoSessionId" to toolIoSession?.id
        )
        fun requestLedger(phase: String) = AiRequestLedger.request(requestAttribution + mapOf(
            "phase" to phase, "maxOutputTokens" to maxOutputTokens
        ))

        val callCtx = ctx.copy(
            reportProgress = onProgress ?: ctx.reportProgress,
            promoted = ctx.promoted ?: state.promotedTools,
            progress = ctx.progress ?: onProgress?.let { p -> { line: String -> p(LlmProgressUpdate(line)) } },
        )
        // Rebuilt each round: a DescribeToolsRequest in one round adds schemas to the next.
        fun toolDefs() = buildToolDefs(callCtx).let { if (tc != null) withToolCacheBreakpoint(it) else it }
        val commentary = mutableListOf<String>()
        val conversationAdditions = mutableListOf<String>()
        val transcript = mutableListOf<LlmToolTranscriptEntry>()

        var inputTokens = 0L
        var outputTokens = 0L
        var cacheReadTokens: Long? = null
        var cacheWriteTokens: Long? = null
        fun countUsage(message: Message) {
            runCatching { message.usage() }.getOrNull()?.let {
                inputTokens += it.inputTokens()
                outputTokens += it.outputTokens()
                it.cacheReadInputTokens().orElse(null)?.let { n -> cacheReadTokens = (cacheReadTokens ?: 0L) + n }
                it.cacheCreationInputTokens().orElse(null)?.let { n -> cacheWriteTokens = (cacheWriteTokens ?: 0L) + n }
            }
        }
        var toolElapsedMs = 0L
        var activePhase = "model:initial"
        try {
        var hitToolRoundCap = false
        var response = createMessage(buildParams(systemBlocks, messages, toolDefs(), maxOutputTokens, cacheTail = tc != null), requestLedger("initial"))
        countUsage(response)
        onProgress?.invoke(LlmProgressUpdate("", modelIdentity = modelIdentity.copy(responseModel = response.model().asString())))
        messages += response.toParam()
        logShape("initial", response)

        var rounds = 0
        var suspension: TurnSuspension? = null
        while (response.stopReason().orElse(null) == StopReason.TOOL_USE) {
            val toolUses = response.content().mapNotNull { it.toolUse().orElse(null) }
            if (toolUses.isEmpty()) break

            val resultBlocks = mutableListOf<ContentBlockParam>()
            val roundResults = mutableListOf<Any>()
            val roundOutputs = mutableListOf<PendingToolOutput>()
            for (use in toolUses) {
                val name = use.name()
                log.toolActivity(name)
                val argsJson = runCatching { toJsonString(use._input()) }.getOrElse { "{}" }
                info("tool: name=$name id=${use.id()} args=${truncate(argsJson, 400)}")
                toolIo("tool_call", mapOf("round" to (rounds + 1), "toolName" to name, "callId" to use.id(), "arguments" to argsJson))
                onProgress?.invoke(LlmProgressUpdate("Using $name", truncate(argsJson, 700)))

                activePhase = "tool:$name"
                val started = System.nanoTime()
                val result: Any = try {
                    tools.executeByName(callCtx, name, argsJson)
                } catch (e: Exception) {
                    // A cancelled turn must end the turn, not become a tool error the model is asked about.
                    if (isAiTurnCancellation(e)) throw e
                    error("tool: ERROR name=$name msg=${e.message}")
                    toolIo("tool_exception", mapOf(
                        "round" to (rounds + 1), "toolName" to name, "callId" to use.id(),
                        "exceptionType" to e::class.java.name, "message" to (e.message ?: "")))
                    mapOf("error" to true, "tool" to name, "message" to (e.message ?: e::class.java.name))
                }
                val toolMs = (System.nanoTime() - started) / 1_000_000
                toolElapsedMs += toolMs
                val resultJson = runCatching { aiToolMapper.writeValueAsString(result) }.getOrElse { result.toString() }
                if (runCatching { aiToolMapper.readTree(resultJson).let { it["success"]?.asBoolean() == false || it["error"]?.asBoolean() == true } }.getOrDefault(false)) {
                    log.userNotice("An action could not finish. Details are in the chat and Tool pool.", attention = true)
                }
                val summary = truncate(resultJson, 300)
                transcript += LlmToolTranscriptEntry(name, use.id(), argsJson, summary, resultJson, elapsedMs = toolMs)
                transcript += afield.ai.core.program.nestedTranscriptEntries(use.id(), result)
                toolIo("tool_result", mapOf(
                    "round" to (rounds + 1), "toolName" to name, "callId" to use.id(), "summary" to summary,
                    "elapsedMs" to toolMs, "result" to resultJson,
                    "imageCount" to ((result as? CarriesToolImages)?.toolImages?.size ?: 0)))
                afield.ai.core.program.nestedToolIoEvents(rounds + 1, use.id(), result).forEach { (event, fields) -> toolIo(event, fields) }
                onProgress?.invoke(LlmProgressUpdate("$name finished ($toolMs ms)", afield.ai.core.program.nestedTraceMarkdown(result) ?: summary))

                if (name == Commentary::class.java.simpleName) {
                    runCatching { aiToolMapper.readValue(argsJson.ifBlank { "{}" }, Commentary::class.java).text }
                        .onSuccess { text ->
                            commentary += text
                            onProgress?.invoke(LlmProgressUpdate("Commentary", text))
                        }
                }
                // The panel's copy of the conversation.
                //
                // The *model* gets these images inside the `tool_result`, via `toolResultBlock` — which
                // is the better shape here and is why this bridge nests rather than appending. But the
                // panel is plain markdown assembled from `conversationAdditions`, and this bridge only
                // ever put prose there. So a ReloadShaders visual check rendered as a sentence claiming
                // a screenshot had been captured, with no screenshot under it.
                //
                // It used to have a second job, worth recording because it is why the omission went
                // unnoticed for so long: `Ai4Installer` decided whether the render window could be
                // captured *at all* by searching the rendered history for a `data:image/`. Nothing on
                // this provider ever wrote one, so that test could never come true, so `visualCheck`
                // was refused in perpetuity and the missing attachment here had nothing to attach.
                // That gate is gone; this block is now only about what the person sees.
                val carried = (result as? CarriesToolImages)?.toolImages
                    .orEmpty()
                    .filter { it.dataUrl.isNotBlank() }
                if (carried.isNotEmpty()) {
                    conversationAdditions += buildString {
                        appendLine("<<ai>>")
                        append(modelIdentity.copy(responseModel = response.model().asString()).marker())
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
                }

                val isError = (result as? Map<*, *>)?.get("error") == true
                resultBlocks += ContentBlockParam.ofToolResult(
                    toolResultBlock(use.id(), resultJson, isError, result, ::warn)
                )
                roundResults += result
                roundOutputs += PendingToolOutput(use.id(), name, resultJson, isError)
            }

            // A tool ended the turn (AwaitStudyBuildsRequest): no follow-up request. The results open the next
            // turn's message instead; what the person wrote meanwhile stays in the inbox for a turn of its own.
            TurnSuspension.of(roundResults)?.let { awaited ->
                suspension = awaited
                state.suspendedRound = SuspendedRound(roundOutputs.toList())
                rounds++
                info("turn suspended after round $rounds: waiting for ${awaited.jobs.size} build job(s) without the model")
                toolIo("turn_suspended", mapOf("round" to rounds, "jobIds" to awaited.jobs.map { it.jobId },
                    "deadlineMs" to awaited.deadlineMs, "pendingCallIds" to roundOutputs.map { it.callId }))
            }
            if (suspension != null) break

            // What the person wrote while this round ran (AiTurnInbox): a text block after the tool results,
            // which must come first in the message. Their images are dropped, as a turn's own are here.
            callCtx.inbox?.drainForModel()?.takeIf { it.isNotEmpty() }?.let { taken ->
                val text = AiTurnInbox.modelText(taken)
                resultBlocks += ContentBlockParam.ofText(TextBlockParam.builder().text(text).build())
                if (taken.any { it.images.isNotEmpty() }) warn("images sent while the turn ran are ignored by the Anthropic bridge.")
                info("user interjection: ${taken.size} message(s), ${text.length} chars")
                toolIo("user_interjection", mapOf("round" to (rounds + 1), "messages" to taken.size, "text" to text))
            }

            messages += MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(resultBlocks)
                .build()

            rounds++
            // At the round cap, force a final answer (results are already paired above) by switching tool use
            // off rather than leaving the tools out: the Messages API 400s a request whose messages carry
            // tool_use/tool_result blocks but which defines no tools ("must define tools"), and a changed tool
            // list would also miss the cached prefix. Nothing is added to the messages, so no later turn
            // inherits a stop.
            val includeTools = rounds < maxToolRounds
            if (!includeTools) {
                warn("maxToolRounds=$maxToolRounds reached; requesting a final answer with tool_choice none.")
            }
            if (!includeTools) hitToolRoundCap = true
            val phase = if (includeTools) "followup" else "forced_final"
            activePhase = "model:$phase"
            response = createMessage(buildParams(systemBlocks, messages, toolDefs(), maxOutputTokens, cacheTail = tc != null, toolsDisabled = !includeTools),
                requestLedger(phase))
            countUsage(response)
            onProgress?.invoke(LlmProgressUpdate("", modelIdentity = modelIdentity.copy(responseModel = response.model().asString())))
            messages += response.toParam()
            logShape(phase, response)
            if (!includeTools) break
        }

        val text = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        if (text.isBlank()) {
            warn("model returned no text. stopReason=${response.stopReason().orElse(null)} — try higher maxOutputTokens.")
        }
        onTextDelta?.invoke(text)

        val ms = (System.nanoTime() - t0) / 1_000_000
        info("done id=${response.id()} textChars=${text.length} commentary=${commentary.size} rounds=$rounds (${ms}ms)")
        toolIo("session_end", mapOf(
            "responseId" to response.id(), "textChars" to text.length, "commentaryCount" to commentary.size,
            "toolCallCount" to transcript.size, "elapsedMs" to ms, "status" to if (suspension != null) "suspended" else "completed",
            "toolElapsedMs" to toolElapsedMs, "rounds" to rounds,
            "inputTokens" to inputTokens, "outputTokens" to outputTokens,
            "cacheReadTokens" to cacheReadTokens, "cacheWriteTokens" to cacheWriteTokens))

        return OpenAiIdeCodexBridge.SendResult(
            threadState = state,
            text = text,
            rawResponseId = response.id(),
            commentary = commentary.toList(),
            conversationAdditions = conversationAdditions.toList(),
            toolTranscript = transcript.toList(),
            modelIdentity = modelIdentity.copy(responseModel = response.model().asString()),
            rounds = rounds,
            hitToolRoundCap = hitToolRoundCap,
            suspension = suspension,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            cacheReadTokens = cacheReadTokens,
            cacheWriteTokens = cacheWriteTokens,
            contextReport = tc?.report("system+prefix")
                ?.copy(inputTokens = inputTokens, cacheReadTokens = cacheReadTokens, cacheWriteTokens = cacheWriteTokens)
                ?.withTools(tools, callCtx)
        )
        } catch (failure: Throwable) {
            val cancelled = isAiTurnCancellation(failure)
            toolIo(if (cancelled) "session_cancelled" else "session_failed", mapOf(
                "status" to if (cancelled) "cancelled" else "failed",
                "phase" to activePhase,
                "elapsedMs" to (System.nanoTime() - t0) / 1_000_000,
                "toolElapsedMs" to toolElapsedMs,
                "exceptionType" to failure::class.java.name, "message" to (failure.message ?: "")))
            throw failure
        } finally {
            // The volatile prefix was for this turn's requests only. Whatever happened, the stored message
            // goes back to the durable text, so the next turn's history does not carry it.
            if (volatilePrefix.isNotEmpty() && turnMessageIndex < messages.size) messages[turnMessageIndex] = turnMessage(inputText)
        }
    }

    // ---- request construction ------------------------------------------------

    internal fun buildParams(
        system: List<TextBlockParam>,
        messages: List<MessageParam>,
        toolDefs: List<Tool>,
        maxOutputTokens: Long,
        cacheTail: Boolean = false,
        toolsDisabled: Boolean = false
    ): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxOutputTokens)
            .systemOfTextBlockParams(system)
            .messages(messages)
        // Automatic caching: a breakpoint that moves with the end of the conversation, so each tool round
        // reads the rounds before it from cache. With the tool and system breakpoints that makes three of four.
        if (cacheTail) builder.cacheControl(CacheControlEphemeral.builder().build())
        toolDefs.forEach { builder.addTool(it) }
        // `none`, unlike `any`/`tool`, is accepted by every current model.
        if (toolsDisabled) builder.toolChoice(ToolChoiceNone.builder().build())
        return builder.build()
    }

    private fun userText(text: String): MessageParam =
        MessageParam.builder().role(MessageParam.Role.USER).content(text).build()

    /** Build Anthropic [Tool] defs from the shared registry's provider-neutral descriptors. */
    private fun buildToolDefs(ctx: ToolExecutionContext): List<Tool> =
        tools.describe(ctx).mapNotNull { element ->
            val obj = element.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val desc = obj["description"]?.jsonPrimitive?.contentOrNull
            val schema = obj["inputSchema"]?.jsonObject
            val props = schema?.get("properties")?.jsonObject ?: JsonObject(emptyMap())
            val required = (schema?.get("required") as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()

            val propsBuilder = Tool.InputSchema.Properties.builder()
            props.forEach { (key, value) -> propsBuilder.putAdditionalProperty(key, JsonValue.from(kxToJava(value))) }
            val inputSchema = Tool.InputSchema.builder()
                .properties(propsBuilder.build())
                .apply { if (required.isNotEmpty()) required(required) }
                .build()

            Tool.builder()
                .name(name)
                .apply { if (!desc.isNullOrBlank()) description(desc) }
                .inputSchema(inputSchema)
                .build()
        }

    // ---- create with rate-limit + auth-refresh retry -------------------------

    /** One logical request: each `create` is an attempt in [ledger], recorded before the call and after its outcome. */
    private fun createMessage(params: MessageCreateParams, ledger: AiRequestLedger.Request): Message {
        var attempt = 0
        var refreshedAuth = false
        while (true) {
            val recorded = ledger.attempt()
            try {
                return client.messages().create(params).also { recorded.response(it); recorded.finish() }
            } catch (e: UnauthorizedException) {
                recorded.finish(e)
                if (!refreshedAuth) {
                    warn("401 from Anthropic; refreshing OAuth token and retrying once.")
                    invalidateAnthropicOAuthToken()
                    client = anthropicClientFromFieldConfig(forceRefresh = true)
                    refreshedAuth = true
                    continue
                }
                throw e
            } catch (e: AnthropicServiceException) {
                recorded.finish(e)
                val delay = OpenAiIdeCodexBridge.retryDelayMsForRateLimitMessage(e.message ?: "")
                if (delay != null && attempt < maxRateLimitRetries) {
                    val wait = min(delay, maxRateLimitDelayMs)
                    ledger.retry(attempt + 1, wait)
                    warn("rate limited (attempt ${attempt + 1}/$maxRateLimitRetries); waiting ${wait}ms.")
                    log.userNotice("The AI service is busy. Retrying automatically in ${(wait + 999) / 1000}s; completed work is kept.", attention = true)
                    Thread.sleep(wait)
                    attempt++
                    continue
                }
                throw e
            } catch (e: Exception) {
                recorded.finish(e)
                throw e
            }
        }
    }

    // ---- helpers -------------------------------------------------------------

    private fun logShape(phase: String, resp: Message) {
        val text = resp.content().count { it.isText() }
        val toolUse = resp.content().count { it.isToolUse() }
        info("$phase response id=${resp.id()} text=$text toolUse=$toolUse stop=${resp.stopReason().orElse(null)}")
    }

    private fun toJsonString(input: JsonValue): String =
        aiToolMapper.writeValueAsString(input.convert(Any::class.java))

    private fun truncate(s: String, max: Int): String = if (s.length <= max) s else s.take(max) + "…"

    private fun kxToJava(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonObject -> e.mapValues { kxToJava(it.value) }
        is JsonArray -> e.map { kxToJava(it) }
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            e.longOrNull != null -> e.longOrNull
            e.doubleOrNull != null -> e.doubleOrNull
            else -> e.content
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Tool results that carry pictures
// ---------------------------------------------------------------------------------------------

/**
 * The largest base64 payload this will send for one image, in characters.
 *
 * The Messages API rejects an oversized image with a 400 that fails the whole turn, taking the text
 * result down with a picture that was only ever a bonus. Five megabytes of base64 is comfortably under
 * the documented per-request ceiling and far above anything Field's CPU rasteriser produces — the
 * 640×480 render in `public/docs/assets/ai_tool_demo/` is 84 KB, so about 112 KB of base64, roughly 2%
 * of this. A tool that manages to exceed it loses its image and keeps its answer.
 */
internal const val ANTHROPIC_MAX_IMAGE_BASE64_CHARS = 5 * 1024 * 1024

/**
 * Build the `tool_result` block for one tool call, attaching any images the result carries.
 *
 * # Why this is a function rather than four lines inline
 *
 * It was four lines inline, and the four lines were wrong in a way nothing could see. [CarriesToolImages]
 * is documented as "the bridge appends the images to the conversation", and this bridge never read the
 * interface at all: every tool result went out as `content(resultJson)`, a bare string. So under
 * `AFIELD_AI_PROVIDER=anthropic` a geometry tool's whole point — letting the model *look* at what it
 * did — silently evaporated, and the only symptom was a model reasoning about a picture it had never
 * been shown.
 *
 * It was an unfinished feature rather than a limitation. The Messages API has accepted images *inside*
 * a `tool_result`'s content for as long as this bridge has existed, and the SDK models it as
 * [ToolResultBlockParam.Content.Block.ofImage]. Nothing needed inventing; the conversion was missing.
 *
 * Pulling it out here is what makes it testable without a network or an API key: `AnthropicToolImageTest`
 * builds the real parameter object and asserts the image block is in it, which is the object the SDK
 * serialises into the request body.
 *
 * # Shape
 *
 * Images go inside the `tool_result` rather than in a following user message — which is what the OpenAI
 * bridge does (`OpenAiIdeCodexBridge.kt:433`) because the Responses API has no equivalent nesting. Here
 * the nesting exists and is better: the picture is bound to the call it came from, so a turn with three
 * tool calls cannot leave the model guessing which image answers which.
 *
 * Each image is preceded by its caption as a text block, for the same reason the OpenAI path does it: a
 * bare picture in a conversation is much less useful than one that says what question it answers.
 *
 * @param onOversize called with a human-readable reason when an image is dropped, so a silent drop is
 *   at least a logged one. Dropping the image and keeping the text is deliberate: a 400 from an
 *   oversized attachment would lose the answer as well.
 */
internal fun toolResultBlock(
    toolUseId: String,
    resultJson: String,
    isError: Boolean,
    result: Any,
    onOversize: (String) -> Unit = {},
): ToolResultBlockParam {
    val builder = ToolResultBlockParam.builder().toolUseId(toolUseId)
    if (isError) builder.isError(true)

    val carried = (result as? CarriesToolImages)?.toolImages.orEmpty()
        .filter { it.dataUrl.isNotBlank() }
    if (carried.isEmpty()) return builder.content(resultJson).build()

    val blocks = mutableListOf<ToolResultBlockParam.Content.Block>()
    blocks += ToolResultBlockParam.Content.Block.ofText(TextBlockParam.builder().text(resultJson).build())

    for (image in carried) {
        val decoded = decodeDataUrl(image.dataUrl)
        if (decoded == null) {
            onOversize("dropped a tool image: not a base64 data URL this bridge can send")
            continue
        }
        val (mediaType, data) = decoded
        if (data.length > ANTHROPIC_MAX_IMAGE_BASE64_CHARS) {
            onOversize(
                "dropped a tool image: ${data.length} base64 chars exceeds the " +
                    "$ANTHROPIC_MAX_IMAGE_BASE64_CHARS limit; the text result was still sent"
            )
            continue
        }
        if (image.caption.isNotBlank()) {
            blocks += ToolResultBlockParam.Content.Block.ofText(
                TextBlockParam.builder().text(image.caption).build()
            )
        }
        blocks += ToolResultBlockParam.Content.Block.ofImage(
            ImageBlockParam.builder()
                .source(Base64ImageSource.builder().mediaType(mediaType).data(data).build())
                .build()
        )
    }

    // Every image was rejected, so there is nothing a block list buys over the plain string.
    if (blocks.size == 1) return builder.content(resultJson).build()
    return builder.contentOfBlocks(blocks).build()
}

/**
 * Split a `data:image/png;base64,…` URL into the media type the API wants and the payload.
 *
 * Returns `null` for anything else — a remote `https:` URL, a non-image type, a media type Anthropic
 * does not accept, or a malformed string. [ToolImage.dataUrl] is documented as a data URL, so a caller
 * that passes something else is making a mistake this cannot repair by guessing.
 */
internal fun decodeDataUrl(dataUrl: String): Pair<Base64ImageSource.MediaType, String>? {
    val match = Regex("""^data:(image/[a-zA-Z0-9.+-]+);base64,(.*)$""", RegexOption.DOT_MATCHES_ALL)
        .find(dataUrl.trim()) ?: return null
    val (mime, data) = match.destructured
    if (data.isBlank()) return null
    val mediaType = when (mime.lowercase()) {
        "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG
        "image/jpeg", "image/jpg" -> Base64ImageSource.MediaType.IMAGE_JPEG
        "image/gif" -> Base64ImageSource.MediaType.IMAGE_GIF
        "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP
        else -> return null
    }
    return mediaType to data
}

/**
 * The system prompt as blocks: the thread's own instructions, then this turn's stable ephemeral ones. With
 * [cached], the last block carries a cache breakpoint, so a conversation's every request reads the system
 * prompt (and the tools before it) from cache until a stable instruction actually changes.
 */
internal fun anthropicSystemBlocks(threadInstructions: String, stableInstructions: String, cached: Boolean): List<TextBlockParam> {
    val texts = listOf(threadInstructions, stableInstructions).filter { it.isNotBlank() }
    return texts.mapIndexed { index, text ->
        TextBlockParam.builder().text(text)
            .apply { if (cached && index == texts.lastIndex) cacheControl(CacheControlEphemeral.builder().build()) }
            .build()
    }
}

/** The tool list with a cache breakpoint on its last definition: tools come first in the prompt, and rarely change. */
internal fun withToolCacheBreakpoint(defs: List<Tool>): List<Tool> =
    if (defs.isEmpty()) defs
    else defs.dropLast(1) + defs.last().toBuilder().cacheControl(CacheControlEphemeral.builder().build()).build()
