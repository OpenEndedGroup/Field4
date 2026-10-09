package afield.ai.core

import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.Message
import com.fasterxml.jackson.databind.ObjectMapper
import com.openai.errors.OpenAIServiceException
import com.openai.models.responses.Response
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Content-free, per-process request accounting shared by all AI4 conversations and completions. */
internal object AiRequestLedger {
    private val mapper = ObjectMapper()
    private val process = ProcessHandle.current()
    private val instanceId = UUID.randomUUID().toString()
    private val writeLock = Any()
    private val warned = AtomicBoolean()
    private val diagnosticHeaders = listOf("x-request-id", "request-id", "retry-after", "x-ratelimit-limit-tokens",
        "x-ratelimit-remaining-tokens", "x-ratelimit-reset-tokens", "x-ratelimit-limit-requests", "x-ratelimit-remaining-requests",
        "x-ratelimit-reset-requests") + listOf("requests", "tokens", "input-tokens", "output-tokens").flatMap { bucket ->
            listOf("limit", "remaining", "reset").map { "anthropic-ratelimit-$bucket-$it" } }
    private val identity = mapOf(
        "schemaVersion" to 1, "instanceId" to instanceId, "pid" to process.pid(),
        "processStartedAt" to process.info().startInstant().orElse(null)?.toString(),
        "checkout" to Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize().toString(),
        "port" to System.getenv("PORT")
    )

    fun request(metadata: Map<String, Any?>): Request = Request(metadata + ("requestId" to UUID.randomUUID().toString()))

    internal fun endpoint(baseUrl: String): String = runCatching {
        val u = URI(baseUrl)
        URI(u.scheme, null, u.host, u.port, u.path, null, null).toString()
    }.getOrDefault("unknown")

    private fun append(fields: Map<String, Any?>) {
        try {
            val now = Instant.now()
            val root = Path.of(System.getProperty("afield.ai.usageLog.path")
                ?: System.getenv("AFIELD_AI_USAGE_LOG_PATH")
                ?: Path.of(System.getProperty("user.home"), ".afield", "logs", "ai_usage").toString())
            synchronized(writeLock) {
                Files.createDirectories(root)
                // One file per JVM/day: separate processes never interleave writes or share a file lock.
                val path = root.resolve("${now.toString().take(10)}-${process.pid()}-$instanceId.jsonl")
                Files.writeString(path, mapper.writeValueAsString(identity + fields + ("timestamp" to now.toString())) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        } catch (failure: Exception) {
            if (warned.compareAndSet(false, true)) System.err.println(
                "AI request accounting could not be written (${failure.javaClass.simpleName}); check the AI usage log directory.")
        }
    }

    class Request internal constructor(private val metadata: Map<String, Any?>) {
        private var attemptNumber = 0
        fun attempt(): Attempt = Attempt(metadata + ("attempt" to ++attemptNumber))
        fun retry(retry: Int, delayMs: Long) = append(metadata + mapOf("event" to "retry_wait", "retry" to retry, "delayMs" to delayMs))
    }

    class Attempt internal constructor(private val metadata: Map<String, Any?>) {
        private val started = System.nanoTime()
        private val usageRecorded = AtomicBoolean()
        init { append(metadata + ("event" to "attempt_started")) }

        fun response(response: Response) {
            // Failed/incomplete SSE responses can still contain billed usage. Record before validation.
            val usage = response.usage().orElse(null)
            usage(mapOf(
                "responseId" to response.id(),
                "responseModel" to response.model().toString(), "responseStatus" to response.status().orElse(null)?.toString(),
                "usageKnown" to (usage != null), "inputTokens" to usage?.inputTokens(),
                "cachedInputTokens" to usage?.inputTokensDetails()?.cachedTokens(),
                "cacheWriteTokens" to usage?.inputTokensDetails()?._additionalProperties()?.get("cache_write_tokens")?.convert(Long::class.javaObjectType),
                "outputTokens" to usage?.outputTokens(), "reasoningTokens" to usage?.outputTokensDetails()?.reasoningTokens(),
                "totalTokens" to usage?.totalTokens()
            ))
        }

        /**
         * An Anthropic message. Its `input_tokens` leaves out the prompt tokens read from or written to the cache, so
         * they are added back here: input is the whole prompt for every provider, and cached input part of it.
         * Anthropic reports no total and no separate reasoning count (thinking is inside output).
         */
        fun response(message: Message) {
            val usage = runCatching { message.usage() }.getOrNull()
            val cacheRead = usage?.cacheReadInputTokens()?.orElse(null)
            val cacheWrite = usage?.cacheCreationInputTokens()?.orElse(null)
            val input = usage?.let { it.inputTokens() + (cacheRead ?: 0L) + (cacheWrite ?: 0L) }
            val output = usage?.outputTokens()
            usage(mapOf(
                "responseId" to message.id(), "responseModel" to message.model().asString(),
                "stopReason" to message.stopReason().orElse(null)?.asString(),
                "usageKnown" to (usage != null), "inputTokens" to input,
                "cachedInputTokens" to cacheRead, "cacheWriteTokens" to cacheWrite,
                "outputTokens" to output, "reasoningTokens" to null,
                "totalTokens" to if (input != null && output != null) input + output else null
            ))
        }

        private fun usage(fields: Map<String, Any?>) {
            if (!usageRecorded.compareAndSet(false, true)) return
            append(metadata + ("event" to "response_usage") + fields)
        }

        fun finish(failure: Throwable? = null) {
            val chain = generateSequence(failure) { it.cause }.take(12).toList()
            val http = chain.filterIsInstance<OpenAIServiceException>().firstOrNull()
            val anthropic = chain.filterIsInstance<AnthropicServiceException>().firstOrNull()
            val provider = chain.filterIsInstance<ModelResponseFailure>().firstOrNull()
            val headers = diagnosticHeaders.mapNotNull { key ->
                (http?.headers()?.values(key) ?: anthropic?.headers()?.values(key))?.firstOrNull()?.let { key to it.take(200) }
            }.toMap()
            val messages = chain.joinToString("\n") { it.message.orEmpty() }
            fun count(name: String): Long? = Regex("(?i)\\b$name\\s*:?\\s*(\\d+)").find(messages)?.groupValues?.get(1)?.toLongOrNull()
            append(metadata + mapOf(
                "event" to "attempt_finished", "success" to (failure == null),
                "elapsedMs" to (System.nanoTime() - started) / 1_000_000,
                "errorType" to failure?.javaClass?.simpleName,
                "providerCode" to (provider?.reason ?: anthropic?.let { runCatching { it.errorType().orElse(null)?.asString() }.getOrNull() })?.take(100),
                "httpStatus" to (http?.statusCode() ?: anthropic?.statusCode()), "rateLimited" to (failure != null && Regex("(?i)rate[ _-]?limit|\\b429\\b").containsMatchIn(messages)),
                "limitTokens" to count("Limit"), "usedTokens" to count("Used"), "requestedTokens" to count("Requested"),
                "responseHeaders" to headers
            ))
        }
    }
}
