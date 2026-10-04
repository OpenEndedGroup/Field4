package afield.ai.core

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
}

/** Null means a different failure; zero means a temporary limit without a server delay hint. */
internal fun temporaryRateLimitDelay(failure: Throwable): Long? {
    val seen = mutableSetOf<Throwable>()
    val chain = generateSequence(failure) { it.cause }.takeWhile { seen.add(it) }.toList()
    if (chain.any { it is PartialModelResponseException }) return null
    val message = chain.joinToString("\n") { it.message.orEmpty() }
    val lower = message.lowercase()
    if (listOf("insufficient_quota", "exceeded your current quota", "billing", "spend limit", "request too large", "context_length_exceeded", "maximum context length").any { it in lower }) return null
    val http = chain.filterIsInstance<OpenAIServiceException>().firstOrNull()
    if (http?.statusCode() != 429 && !Regex("(?i)rate[ _-]?limit|\\b429\\b|slow_down|server_is_overloaded").containsMatchIn(message)) return null
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
    val http = chain.filterIsInstance<OpenAIServiceException>().firstOrNull()
    if (http != null) {
        if (http.statusCode() !in listOf(408, 409) && http.statusCode() !in 500..599) return null
        return retryAfterDelay(http)
    }
    return if (chain.any { it is OpenAIIoException || it is OpenAIRetryableException }) 0L else null
}

private fun retryAfterDelay(http: OpenAIServiceException?): Long =
    http?.headers()?.values("retry-after")?.mapNotNull { value ->
        value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }?.let { ceil(it * 1000).toLong() }
            ?: runCatching { (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0) }.getOrNull()
    }?.maxOrNull() ?: 0L
