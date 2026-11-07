/**
 * Keeps an AI4 turn alive through a provider's rate limits and brief outages by retrying the one model
 * request that failed, never the tool round or the user's turn around it.
 *
 * [AiRateLimitRecovery] waits at least as long as the provider asks, with exponential backoff capped at
 * 30 s plus jitter, for up to 120 retries or ten minutes in all; a transport or server error without a
 * rate-limit hint is retried only twice. When it gives up, the error says so and the conversation and
 * the tool work already done are kept. [PartialModelResponseException] is the other ending: a stream that
 * failed after delivering part of its answer keeps that part and is not replayed.
 */

package afield.ai.conversation

import afield.ai.models.OpenAiIdeCodexBridge
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicRetryableException
import com.anthropic.errors.AnthropicServiceException
import com.openai.errors.OpenAIServiceException
import com.openai.errors.OpenAIIoException
import com.openai.errors.OpenAIRetryableException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.max
import kotlin.random.Random

internal class ModelResponseFailure(val reason: String, detail: String) : IllegalStateException("Model response $reason: $detail")

internal class PartialModelResponseException(cause: Throwable) : IllegalStateException(
    "The model stream ended after delivering part of its answer. The partial answer is retained; it was not automatically replayed." +
        generateSequence(cause) { it.cause }.take(12).filterIsInstance<ModelResponseFailure>().firstOrNull()
            ?.let { " Provider reason: ${it.reason.take(100).replace(Regex("[^a-zA-Z0-9_ -]"), " ")}." }.orEmpty(), cause
)

/** Retries a single model request, never a tool round or a user turn. */
internal class AiRateLimitRecovery(
    private val maxRetries: Int = 120,
    private val budgetMs: Long = 600_000L,
    private val backoffCapMs: Long = 30_000L,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val jitter: () -> Long = { Random.nextLong(100L, 501L) }
) {
    data class Wait(val retry: Int, val remainingMs: Long, val elapsedMs: Long)

    fun <T> run(onWait: (Wait) -> Unit = {}, onRetry: () -> Unit = {}, request: () -> T): T {
        val started = clock()
        var retries = 0
        var transportRetries = 0
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("AI request interrupted")
            try {
                return request()
            } catch (failure: Exception) {
                if (isAiTurnCancellation(failure)) throw failure
                val rateLimitHint = temporaryRateLimitDelay(failure)
                val hint = rateLimitHint ?: temporaryTransportDelay(failure) ?: throw failure
                // Preserve the SDK's two brief transport/server retries without a nested loop.
                if (rateLimitHint == null && transportRetries++ >= 2) throw failure
                val elapsed = clock() - started
                // The cap limits our backoff, never the provider's minimum waiting period.
                val backoff = (1_000L shl retries.coerceAtMost(18)).coerceAtMost(backoffCapMs)
                val delay = max(hint, backoff) + jitter().coerceAtLeast(0)
                if (retries >= maxRetries || elapsed + delay > budgetMs) {
                    throw IllegalStateException(
                        "The provider remained temporarily unavailable after ${retries + 1} attempts " +
                            "(${elapsed / 1000}s). Automatic recovery stopped at its limit; " +
                            "the conversation and completed tool work are retained. ${failure.message}", failure
                    )
                }
                retries++
                val until = clock() + delay
                while (clock() < until) {
                    val remaining = until - clock()
                    onWait(Wait(retries, remaining, clock() - started))
                    try {
                        sleep(remaining.coerceAtMost(1_000L))
                    } catch (interrupted: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw interrupted
                    }
                }
                onRetry()
            }
        }
    }

    companion object {
        /** The limits every bridge reads, so one setting governs OpenAI and Anthropic requests alike. */
        fun fromConfig() = AiRateLimitRecovery(
            maxRetries = (System.getenv("AFIELD_AI_RATE_LIMIT_RETRIES") ?: System.getProperty("afield.ai.rateLimitRetries"))
                ?.toIntOrNull()?.coerceIn(0, 120) ?: 120,
            budgetMs = (System.getenv("AFIELD_AI_RATE_LIMIT_BUDGET_MS") ?: System.getProperty("afield.ai.rateLimitBudgetMs"))
                ?.toLongOrNull()?.coerceIn(0, 3_600_000) ?: 600_000,
            backoffCapMs = (System.getenv("AFIELD_AI_RATE_LIMIT_MAX_DELAY_MS") ?: System.getProperty("afield.ai.rateLimitMaxDelayMs"))
                ?.toLongOrNull()?.coerceIn(250, 300_000) ?: 30_000
        )
    }
}

/**
 * The provider's HTTP error in a failure chain, from either SDK. [shouldRetry] is Anthropic's `x-should-retry`,
 * which its SDK obeyed before the bridge took the retries over; the OpenAI path has never read it.
 */
private class HttpFailure(val status: Int, val errorType: String?, val shouldRetry: Boolean?, val header: (String) -> List<String>)

private fun httpFailure(chain: List<Throwable>): HttpFailure? = chain.firstNotNullOfOrNull { e ->
    when (e) {
        is OpenAIServiceException -> HttpFailure(e.statusCode(), null, null) { e.headers().values(it) }
        is AnthropicServiceException -> HttpFailure(e.statusCode(), runCatching { e.errorType().orElse(null)?.asString() }.getOrNull(),
            e.headers().values("x-should-retry").firstOrNull()?.trim()?.lowercase()?.toBooleanStrictOrNull()) { e.headers().values(it) }
        else -> null
    }
}

/** Null means a different failure; zero means a temporary limit without a server delay hint. */
internal fun temporaryRateLimitDelay(failure: Throwable): Long? {
    val seen = mutableSetOf<Throwable>()
    val chain = generateSequence(failure) { it.cause }.takeWhile { seen.add(it) }.toList()
    if (chain.any { it is PartialModelResponseException }) return null
    val message = chain.joinToString("\n") { it.message.orEmpty() }
    val lower = message.lowercase()
    if (listOf("insufficient_quota", "exceeded your current quota", "billing", "spend limit", "request too large", "context_length_exceeded", "maximum context length").any { it in lower }) return null
    val http = httpFailure(chain)
    if (http?.shouldRetry == false) return null
    // Anthropic's 529 overloaded_error is the Responses API's server_is_overloaded: wait it out like a rate limit.
    // A local inference server (llama.cpp, llama-swap) answers 503 "Loading model" while it loads weights,
    // which takes tens of seconds; two brief transport retries gave up after ~4 s and lost a turn (2026-10-22).
    val loading = Regex("(?i)loading model").containsMatchIn(message) && (http?.status == 503 || Regex("\\b503\\b").containsMatchIn(message))
    val overloaded = http?.status == 529 || http?.errorType == "overloaded_error" || loading
    if (http?.status != 429 && !overloaded && !Regex("(?i)rate[ _-]?limit|\\b429\\b|slow_down|server_is_overloaded").containsMatchIn(message)) return null
    // Some TPM errors state a request larger than the whole minute's capacity: waiting cannot help.
    val limit = Regex("(?i)\\blimit\\s*:?\\s*(\\d+)").find(message)?.groupValues?.get(1)?.toLongOrNull()
    val requested = Regex("(?i)\\brequested\\s*:?\\s*(\\d+)").find(message)?.groupValues?.get(1)?.toLongOrNull()
    if (limit != null && requested != null && requested > limit) return null
    val bodyHint = OpenAiIdeCodexBridge.retryDelayMsForRateLimitMessage(message, multiplier = 1.0) ?: 0L
    return max(bodyHint, retryAfterDelay(http))
}

private fun temporaryTransportDelay(failure: Throwable): Long? {
    val seen = mutableSetOf<Throwable>()
    val chain = generateSequence(failure) { it.cause }.takeWhile { seen.add(it) }.toList()
    if (chain.any { it is PartialModelResponseException }) return null
    val http = httpFailure(chain)
    if (http != null) {
        if (http.shouldRetry == false) return null
        if (http.shouldRetry != true && http.status !in listOf(408, 409) && http.status !in 500..599) return null
        return retryAfterDelay(http)
    }
    return if (chain.any { it is OpenAIIoException || it is OpenAIRetryableException || it is AnthropicIoException || it is AnthropicRetryableException }) 0L else null
}

/** The server's minimum wait: `retry-after-ms`, or `retry-after` in seconds or as an HTTP date; the longer when both are sent. */
private fun retryAfterDelay(http: HttpFailure?): Long {
    if (http == null) return 0L
    val ms = http.header("retry-after-ms").mapNotNull { value ->
        value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { ceil(it).toLong() }
    }
    val after = http.header("retry-after").mapNotNull { value ->
        value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { ceil(it * 1000).toLong() }
            ?: runCatching { (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0) }.getOrNull()
    }
    return (ms + after).maxOrNull() ?: 0L
}
