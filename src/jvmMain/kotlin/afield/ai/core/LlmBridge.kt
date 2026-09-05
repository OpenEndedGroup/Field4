package afield.ai.core

import com.openai.models.ReasoningEffort

/**
 * Provider-neutral seam for the IDE agent loop.
 *
 * Both the OpenAI/OpenRouter engine ([OpenAiIdeCodexBridge], via [OpenAiLlmBridge])
 * and the Anthropic engine ([AnthropicBridge]) implement this, so [IdeChatHelpers]
 * and the installer can drive whichever provider is selected on /welcome without
 * caring which one is behind the interface.
 *
 * The state/result types are the ones [OpenAiIdeCodexBridge] already defines — they
 * carry no OpenAI-SDK types in their signatures, so they double as the neutral
 * currency here. The typealiases below give them provider-neutral names without
 * moving the declarations (which 13 files reference).
 */

typealias LlmThreadState = OpenAiIdeCodexBridge.ThreadState
typealias LlmSendResult = OpenAiIdeCodexBridge.SendResult
typealias LlmProgressUpdate = OpenAiIdeCodexBridge.ProgressUpdate
typealias LlmToolTranscriptEntry = OpenAiIdeCodexBridge.ToolTranscriptEntry

enum class LlmReasoningEffort { LOW, MEDIUM, HIGH }

/**
 * Provider-aware factory: builds the agent engine matching the /welcome provider
 * setting. Shared by the IDE installer and the docs-search bridge so provider
 * selection lives in exactly one place.
 */
fun createLlmBridge(
    model: String = Ai4ModelDefaults.flagshipModel(),
    log: LogSink = StdoutLog,
    reasoningEffort: LlmReasoningEffort = LlmReasoningEffort.MEDIUM
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

interface LlmBridge {
    fun startThread(
        fileKey: String,
        fileText: String,
        instructions: String = ""
    ): LlmThreadState

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
        onProgress: ((LlmProgressUpdate) -> Unit)? = null
    ): LlmSendResult
}

/**
 * Adapter that presents the existing (untouched) [OpenAiIdeCodexBridge] as an
 * [LlmBridge]. Overrides carry no default values (Kotlin forbids that on an
 * override); the defaults live on the interface and reach every call site typed
 * as [LlmBridge]. A blank [startThread] instruction defers to the inner bridge's
 * own default so existing behaviour is preserved exactly.
 */
class OpenAiLlmBridge(private val inner: OpenAiIdeCodexBridge) : LlmBridge {
    val delegate: OpenAiIdeCodexBridge get() = inner

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
        onProgress: ((LlmProgressUpdate) -> Unit)?
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
            onProgress
        )
}
