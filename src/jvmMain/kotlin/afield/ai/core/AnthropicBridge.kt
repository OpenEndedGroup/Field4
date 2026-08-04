package afield.ai.core

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.Tool
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
    private val log: LogSink = StdoutLog
) : LlmBridge {
    private val tools = ToolRegistry()

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
        onProgress: ((LlmProgressUpdate) -> Unit)?
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
            appendLine(userPrompt)
        }
        messages += userText(inputText)

        info("convId=${state.conversationId} fileKey=${state.fileKey} inputChars=${inputText.length} maxOutputTokens=$maxOutputTokens")

        val toolDefs = buildToolDefs(ctx)
        val commentary = mutableListOf<String>()
        val conversationAdditions = mutableListOf<String>()
        val transcript = mutableListOf<LlmToolTranscriptEntry>()

        var response = createMessage(buildParams(system, messages, toolDefs, maxOutputTokens))
        messages += response.toParam()
        logShape("initial", response)

        var rounds = 0
        while (response.stopReason().orElse(null) == StopReason.TOOL_USE) {
            val toolUses = response.content().mapNotNull { it.toolUse().orElse(null) }
            if (toolUses.isEmpty()) break

            val resultBlocks = mutableListOf<ContentBlockParam>()
            for (use in toolUses) {
                val name = use.name()
                val argsJson = runCatching { toJsonString(use._input()) }.getOrElse { "{}" }
                info("tool: name=$name id=${use.id()} args=${truncate(argsJson, 400)}")
                onProgress?.invoke(LlmProgressUpdate("Using $name", truncate(argsJson, 700)))

                val result: Any = try {
                    tools.executeByName(ctx, name, argsJson)
                } catch (e: Exception) {
                    error("tool: ERROR name=$name msg=${e.message}")
                    mapOf("error" to true, "tool" to name, "message" to (e.message ?: e::class.java.name))
                }
                val resultJson = runCatching { aiToolMapper.writeValueAsString(result) }.getOrElse { result.toString() }
                val summary = truncate(resultJson, 300)
                transcript += LlmToolTranscriptEntry(name, use.id(), argsJson, summary, resultJson)
                onProgress?.invoke(LlmProgressUpdate("$name finished", summary))

                if (name == Commentary::class.java.simpleName) {
                    runCatching { aiToolMapper.readValue(argsJson.ifBlank { "{}" }, Commentary::class.java).text }
                        .onSuccess { text ->
                            commentary += text
                            onProgress?.invoke(LlmProgressUpdate("Commentary", text))
                        }
                }
                if (result is ReloadShadersResult && result.visualDataUrl.isNotBlank()) {
                    conversationAdditions += buildString {
                        appendLine("<<ai>>")
                        appendLine("_Post-shader-reload screenshot captured after the settle delay._")
                        if (result.visualCheckMessage.isNotBlank()) {
                            appendLine(); appendLine("_${result.visualCheckMessage}_")
                        }
                    }
                }

                val isError = (result as? Map<*, *>)?.get("error") == true
                resultBlocks += ContentBlockParam.ofToolResult(
                    ToolResultBlockParam.builder()
                        .toolUseId(use.id())
                        .content(resultJson)
                        .apply { if (isError) isError(true) }
                        .build()
                )
            }

            messages += MessageParam.builder()
                .role(MessageParam.Role.USER)
                .contentOfBlockParams(resultBlocks)
                .build()

            rounds++
            // At the round cap, force a final answer with no tools (results are already paired above).
            val includeTools = rounds < maxToolRounds
            if (!includeTools) {
                warn("maxToolRounds=$maxToolRounds reached; requesting a final answer without further tools.")
            }
            response = createMessage(buildParams(system, messages, if (includeTools) toolDefs else emptyList(), maxOutputTokens))
            messages += response.toParam()
            logShape(if (includeTools) "followup" else "forced_final", response)
            if (!includeTools) break
        }

        val text = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        if (text.isBlank()) {
            warn("model returned no text. stopReason=${response.stopReason().orElse(null)} — try higher maxOutputTokens.")
        }
        onTextDelta?.invoke(text)

        val ms = (System.nanoTime() - t0) / 1_000_000
        info("done id=${response.id()} textChars=${text.length} commentary=${commentary.size} rounds=$rounds (${ms}ms)")

        return OpenAiIdeCodexBridge.SendResult(
            threadState = state,
            text = text,
            rawResponseId = response.id(),
            commentary = commentary.toList(),
            conversationAdditions = conversationAdditions.toList(),
            toolTranscript = transcript.toList()
        )
    }

    // ---- request construction ------------------------------------------------

    private fun buildParams(
        system: String,
        messages: List<MessageParam>,
        toolDefs: List<Tool>,
        maxOutputTokens: Long
    ): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(model)
            .maxTokens(maxOutputTokens)
            .system(system)
            .messages(messages)
        toolDefs.forEach { builder.addTool(it) }
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

    private fun createMessage(params: MessageCreateParams): Message {
        var attempt = 0
        var refreshedAuth = false
        while (true) {
            try {
                return client.messages().create(params)
            } catch (e: UnauthorizedException) {
                if (!refreshedAuth) {
                    warn("401 from Anthropic; refreshing OAuth token and retrying once.")
                    invalidateAnthropicOAuthToken()
                    client = anthropicClientFromFieldConfig(forceRefresh = true)
                    refreshedAuth = true
                    continue
                }
                throw e
            } catch (e: AnthropicServiceException) {
                val delay = OpenAiIdeCodexBridge.retryDelayMsForRateLimitMessage(e.message ?: "")
                if (delay != null && attempt < maxRateLimitRetries) {
                    val wait = min(delay, maxRateLimitDelayMs)
                    warn("rate limited (attempt ${attempt + 1}/$maxRateLimitRetries); waiting ${wait}ms.")
                    Thread.sleep(wait)
                    attempt++
                    continue
                }
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
