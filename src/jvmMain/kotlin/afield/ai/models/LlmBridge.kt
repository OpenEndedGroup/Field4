package afield.ai.models

import afield.ai.conversation.IdeChatHelpers

import afield.ai.conversation.TurnContext
import afield.ai.diagnostics.LogSink
import afield.ai.diagnostics.StdoutLog
import afield.ai.diagnostics.log
import afield.ai.tools.ToolExecutionContext
import afield.ai.conversation.AiChatModelIdentity
import com.openai.models.ReasoningEffort

/**
 * The interface the IDE agent loop talks to, with one implementation per provider.
 *
 * Both the OpenAI/OpenRouter engine ([OpenAiIdeCodexBridge], via [OpenAiLlmBridge])
 * and the Anthropic engine ([AnthropicBridge]) implement this, so [IdeChatHelpers]
 * and the installer can drive whichever provider is selected on /welcome without
 * caring which one is behind the interface.
 *
 * The state/result types are the ones [OpenAiIdeCodexBridge] already defines — they
 * carry no OpenAI-SDK types in their signatures, so they double as the neutral
 * currency here. The typealiases below give them provider-neutral names without moving
 * the declarations their callers already reference.
 */

typealias LlmThreadState = OpenAiIdeCodexBridge.ThreadState
typealias LlmSendResult = OpenAiIdeCodexBridge.SendResult
typealias LlmProgressUpdate = OpenAiIdeCodexBridge.ProgressUpdate
typealias LlmToolTranscriptEntry = OpenAiIdeCodexBridge.ToolTranscriptEntry

enum class LlmReasoningEffort { LOW, MEDIUM, HIGH }

/** A provider-scoped local preference; explicit per-call choices still take precedence. */
fun configuredAiReasoningEffort(): LlmReasoningEffort =
    LlmReasoningEffort.entries.firstOrNull {
        it.name.equals(configuredAiModelSettings().reasoningEffort, ignoreCase = true)
    } ?: LlmReasoningEffort.MEDIUM

/**
 * Provider-aware factory: builds the agent engine matching the /welcome provider
 * setting. Shared by the IDE installer and the docs-search bridge so provider
 * selection lives in exactly one place.
 */
fun createLlmBridge(
    model: String = Ai4ModelDefaults.flagshipModel(),
    log: LogSink = StdoutLog,
    reasoningEffort: LlmReasoningEffort = configuredAiReasoningEffort()
): LlmBridge =
    if (configuredAiProvider() == "anthropic")
        AnthropicBridge(model = model, log = log)
    else
        OpenAiLlmBridge(
            OpenAiIdeCodexBridge(
                model = model,
                log = log,
                reasoningEffort = when (reasoningEffort) {
                    LlmReasoningEffort.LOW -> ReasoningEffort.LOW
                    LlmReasoningEffort.MEDIUM -> ReasoningEffort.MEDIUM
                    LlmReasoningEffort.HIGH -> ReasoningEffort.HIGH
                }
            )
        )

/**
 * The bridge every AI4 chat uses: the configured provider at the flagship model. The box panel and the
 * document chat build theirs here, and so does a live `AgentTask`, so a task runs on exactly the
 * engine a person's chat would.
 */
fun createIdeBridge(log: LogSink = StdoutLog): LlmBridge = createLlmBridge(Ai4ModelDefaults.flagshipModel(), log)

interface LlmBridge {
    /** Configured identity while a request is pending; response metadata arrives through progress/results. */
    val modelIdentity: AiChatModelIdentity? get() = null

    fun startThread(
        fileKey: String,
        fileText: String,
        instructions: String = ""
    ): LlmThreadState

    /** Optional durable continuation. Unsupported providers keep using the saved prose transcript. */
    fun checkpointThread(state: LlmThreadState): String? = null

    /** Best effort; return null for incompatible, unavailable or expired continuation state. */
    fun restoreThread(checkpoint: String, report: (String) -> Unit = {}): LlmThreadState? {
        report("unsupported_provider")
        return null
    }

    /** Small, independent text task. Providers may bypass agent setup and tool registration entirely. */
    fun completeText(instructions: String, prompt: String, maxOutputTokens: Long = 256, context: String = ""): String =
        send(startThread("ai4:chat-autocomplete", "", instructions), listOf(context, prompt).filter { it.isNotBlank() }.joinToString("\n\n"),
            maxOutputTokens = maxOutputTokens, maxToolRounds = 0).text

    fun send(
        state: LlmThreadState,
        userPrompt: String,
        fileText: String? = null,
        ctx: ToolExecutionContext = ToolExecutionContext(),
        vectorStoreIds: List<String> = emptyList(),
        maxOutputTokens: Long = 20480,
        maxToolRounds: Int = 24,
        imageDataUrls: List<String> = emptyList(),
        onTextDelta: ((String) -> Unit)? = null,
        onProgress: ((LlmProgressUpdate) -> Unit)? = null,
        // The turn's prompt in parts (TurnContext). A bridge that understands it sends the ephemeral
        // instructions per request and stores only TurnContext.durablePrompt; one that does not sends
        // userPrompt, which callers set to TurnContext.legacyPrompt. Null: userPrompt is everything.
        turnContext: TurnContext? = null
    ): LlmSendResult
}

/**
 * Adapter that presents [OpenAiIdeCodexBridge] as an
 * [LlmBridge]. Overrides carry no default values (Kotlin forbids that on an
 * override); the defaults live on the interface and reach every call site typed
 * as [LlmBridge]. A blank [startThread] instruction defers to the inner bridge's
 * own default so existing behaviour is preserved exactly.
 */
class OpenAiLlmBridge(private val inner: OpenAiIdeCodexBridge) : LlmBridge {
    val delegate: OpenAiIdeCodexBridge get() = inner
    override val modelIdentity: AiChatModelIdentity get() = inner.modelIdentity

    override fun checkpointThread(state: LlmThreadState): String = inner.checkpointThread(state)
    override fun restoreThread(checkpoint: String, report: (String) -> Unit): LlmThreadState? = inner.restoreThread(checkpoint, report)
    override fun completeText(instructions: String, prompt: String, maxOutputTokens: Long, context: String): String =
        inner.completeText(instructions, prompt, maxOutputTokens, context)

    override fun startThread(fileKey: String, fileText: String, instructions: String): LlmThreadState =
        if (instructions.isBlank()) inner.startThread(fileKey, fileText)
        else inner.startThread(fileKey, fileText, instructions)

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
    ): LlmSendResult =
        inner.send(
            state,
            userPrompt,
            fileText,
            ctx,
            vectorStoreIds,
            maxOutputTokens,
            maxToolRounds,
            imageDataUrls,
            onTextDelta,
            onProgress,
            turnContext
        )
}
