package afield.ai.models

import afield.ai.conversation.ModelResponseFailure
import afield.ai.conversation.PartialModelResponseException
import afield.ai.conversation.isAiTurnCancellation
import com.openai.errors.OpenAIServiceException
import com.openai.models.responses.ResponseInputItem

/**
 * The local model still failed after [createResponse]'s two history reductions. A distinct type so
 * that context fitting above it (`createFittedResponse`) can learn the window from the error without
 * starting a third round of retries.
 */
internal class LocalContextRecoveryExhausted(message: String, cause: Throwable) : IllegalStateException(message, cause)

internal fun isLocalEmptyAnswer(failure: Throwable): Boolean =
    generateSequence(failure) { it.cause }.take(12).any { it is ModelResponseFailure && it.reason == "local_empty_answer" }

internal fun isLocalContextRecoveryFailure(failure: Throwable): Boolean {
    val chain = generateSequence(failure) { it.cause }.take(12).toList()
    if (chain.any { it is PartialModelResponseException || isAiTurnCancellation(it) }) return false
    if (isLocalEmptyAnswer(failure)) return true
    val http = chain.filterIsInstance<OpenAIServiceException>().firstOrNull()
    if (http != null && http.statusCode() != 400) return false
    if (http == null && chain.none { it is ModelResponseFailure }) return false
    val message = chain.joinToString("\n") { it.message.orEmpty() }
    return Regex(
        "context_(?:length|window)_exceeded|(?:exceeds?|exceeded).{0,100}context|" +
            "maximum context length|context.{0,60}(?:exceeded|too (?:long|large))",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(message)
}

/** Shorten old exchanges only; the rejected request's new input is never edited or replayed. */
internal fun shortenLocalHistory(
    history: List<ResponseInputItem>,
    pendingInput: List<ResponseInputItem>,
    currentPrompt: ResponseInputItem,
    seedCount: Int
): List<ResponseInputItem>? {
    fun callId(item: ResponseInputItem): String? =
        item.functionCall().orElse(null)?.callId() ?: item.functionCallOutput().orElse(null)?.callId()

    val pendingIds = pendingInput.mapNotNull(::callId).toSet()
    val promptIndex = history.indexOfLast { it == currentPrompt }
    val protected = history.indices.filter { index ->
        index < seedCount || index == promptIndex || callId(history[index])?.let { it in pendingIds } == true
    }.toSet()
    val pairs = history.indices.filter { callId(history[it]) != null }.groupBy { callId(history[it]) }
    val removed = mutableSetOf<Int>()
    val originalChars = (history + pendingInput).sumOf { it.toString().length.toLong() }
    var remainingChars = originalChars
    for (index in history.indices) {
        val group = callId(history[index])?.let { pairs[it] } ?: listOf(index)
        if (group.any { it in protected }) continue
        for (candidate in group) {
            if (removed.add(candidate)) remainingChars -= history[candidate].toString().length
        }
        if (remainingChars <= originalChars / 2) break
    }
    if (removed.isEmpty()) return null
    val notice = ResponseInputItem.ofMessage(ResponseInputItem.Message.builder()
        .role(ResponseInputItem.Message.Role.DEVELOPER)
        .addInputTextContent(
            "The local model's context window was exceeded. Field omitted older conversation exchanges " +
                "and is retrying this model request. The current prompt and latest tool results are retained. " +
                "Completed tool work has not been replayed. Use tools to re-read any older details you need."
        ).build())
    val shortened = history.filterIndexed { index, _ -> index !in removed }.toMutableList()
    shortened.add(seedCount.coerceAtMost(shortened.size), notice)
    // Character counts choose a smaller payload; they are not a token estimate or a context budget.
    return shortened.takeIf { (it + pendingInput).sumOf { item -> item.toString().length.toLong() } < originalChars }
}
