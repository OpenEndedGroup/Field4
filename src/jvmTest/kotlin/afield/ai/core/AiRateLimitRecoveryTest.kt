package afield.ai.core

import com.anthropic.core.JsonValue
import com.anthropic.core.http.Headers
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.RateLimitException
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletionException
import kotlin.test.*

class AiRateLimitRecoveryTest {
    @Test fun briefTransportFailuresKeepTheSdkTwoRetryAllowance() {
        var clock = 0L
        var calls = 0
        val recovery = AiRateLimitRecovery(clock = { clock }, sleep = { clock += it }, jitter = { 0 })
        assertEquals("recovered", recovery.run {
            if (++calls <= 2) throw com.openai.errors.OpenAIIoException("Connection reset")
            "recovered"
        })
        assertEquals(3, calls)
        calls = 0
        assertFailsWith<com.openai.errors.OpenAIIoException> {
            recovery.run { calls++; throw com.openai.errors.OpenAIIoException("Connection reset") }
        }
        assertEquals(3, calls)
    }

    @Test fun repeatedShortLimitsRecoverBeyondTheOldFiveAttempts() {
        var clock = 0L
        var calls = 0
        val waits = mutableListOf<AiRateLimitRecovery.Wait>()
        val recovery = AiRateLimitRecovery(clock = { clock }, sleep = { clock += it }, jitter = { 0 })
        val result = recovery.run(onWait = { waits += it }) {
            if (++calls <= 7) error("rate limit exceeded: please try again in 3.66s")
            "continued"
        }
        assertEquals("continued", result)
        assertEquals(8, calls)
        assertEquals(7, waits.maxOf { it.retry })
        assertTrue(waits.all { it.remainingMs > 0 })
    }

    @Test fun serverMinimumIsNeverShortenedToTheBackoffCap() {
        var clock = 0L
        var calls = 0
        AiRateLimitRecovery(backoffCapMs = 30_000, clock = { clock }, sleep = { clock += it }, jitter = { 0 }).run {
            if (++calls == 1) error("429 Retry-After: 120")
        }
        assertEquals(120_000L, clock)
    }

    @Test fun aDelayBeyondTheBudgetDoesNotCauseAnEarlyRetry() {
        var calls = 0
        val e = assertFailsWith<IllegalStateException> {
            AiRateLimitRecovery(budgetMs = 5_000, clock = { 0 }, sleep = { fail("must not wait or shorten") }, jitter = { 0 }).run {
                calls++; error("rate_limit_exceeded retry after 20s")
            }
        }
        assertEquals(1, calls)
        assertTrue(e.message!!.contains("Automatic recovery stopped"))
    }

    @Test fun quotaOversizeAndUnrelatedFailuresAreNotRetried() {
        for (message in listOf("429 insufficient_quota", "429 billing limit", "429 spend limit", "Rate limit: Limit 500, Requested 501", "context_length_exceeded", "model_not_found", "IO failure")) {
            var calls = 0
            val error = IllegalStateException(message)
            val caught = assertFails {
                AiRateLimitRecovery(sleep = { fail("must not retry $message") }).run { calls++; throw error }
            }
            assertSame(error, caught)
            assertEquals(1, calls)
        }
        assertEquals(0L, temporaryRateLimitDelay(IllegalStateException("rate_limit_exceeded")))
        assertEquals(0L, temporaryRateLimitDelay(IllegalStateException("server_is_overloaded")))
    }

    @Test fun interruptedWaitPropagatesCancellationRatherThanTheRateError() {
        try {
            assertFailsWith<InterruptedException> {
                AiRateLimitRecovery(sleep = { throw InterruptedException("user stopped") }).run {
                    error("429 retry after 2s")
                }
            }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
        val wrapped = CompletionException(CancellationException("429"))
        assertSame(wrapped, assertFails { AiRateLimitRecovery().run { throw wrapped } })
    }

    /** Anthropic's failures, retried as its SDK retried them before the bridge turned the SDK's retries off. */
    @Test fun anthropicFailuresAreRetriedAsItsSdkRetriedThem() {
        fun body(type: String) = JsonValue.from(mapOf("type" to "error", "error" to mapOf("type" to type, "message" to "PRIVATE")))
        fun headers(vararg pairs: Pair<String, String>) = Headers.builder().apply { pairs.forEach { (k, v) -> put(k, v) } }.build()
        fun limited(vararg pairs: Pair<String, String>) = RateLimitException.builder().headers(headers(*pairs)).body(body("rate_limit_error")).build()
        fun server(status: Int, type: String, vararg pairs: Pair<String, String>) =
            InternalServerException.builder().statusCode(status).headers(headers(*pairs)).body(body(type)).build()

        assertEquals(2_000L, temporaryRateLimitDelay(limited("retry-after" to "2")), "the header alone is the hint")
        assertEquals(1_500L, temporaryRateLimitDelay(limited("retry-after-ms" to "1500", "retry-after" to "1")), "the longer of the two")
        assertEquals(0L, temporaryRateLimitDelay(server(529, "overloaded_error")), "a 529 is waited out like a rate limit")
        assertNull(temporaryRateLimitDelay(server(500, "api_error")), "a 500 is a brief server failure, not a rate limit")
        assertNull(temporaryRateLimitDelay(limited("x-should-retry" to "false")))

        var clock = 0L
        fun calls(failure: Exception, succeedOn: Int = Int.MAX_VALUE): Int {
            var n = 0
            runCatching {
                AiRateLimitRecovery(clock = { clock }, sleep = { clock += it }, jitter = { 0 }).run { if (++n < succeedOn) throw failure }
            }
            return n
        }
        assertEquals(6, calls(server(529, "overloaded_error"), succeedOn = 6), "a 529 goes on past a 500's two retries")
        assertEquals(3, calls(server(503, "api_error", "retry-after-ms" to "10")))
        assertEquals(3, calls(com.anthropic.errors.AnthropicIoException("Connection reset")))
        assertEquals(1, calls(server(500, "api_error", "x-should-retry" to "false")))
        assertEquals(1, calls(BadRequestException.builder().headers(headers()).body(body("invalid_request_error")).build()))
        assertEquals(3, calls(BadRequestException.builder().headers(headers("x-should-retry" to "true")).body(body("invalid_request_error")).build()),
            "x-should-retry: true makes any status retryable, as in the SDK")
    }

    @Test fun partialAnswersAreNeverReplayed() {
        val failure = PartialModelResponseException(IllegalStateException("rate limit exceeded"))
        assertNull(temporaryRateLimitDelay(failure))
        assertSame(failure, assertFails { AiRateLimitRecovery().run { throw failure } })
        val partialIo = PartialModelResponseException(com.openai.errors.OpenAIIoException("Connection reset"))
        assertSame(partialIo, assertFails { AiRateLimitRecovery(sleep = { fail("must not replay") }).run { throw partialIo } })
    }
}
