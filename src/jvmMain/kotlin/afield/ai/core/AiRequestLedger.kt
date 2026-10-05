package afield.ai.core

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
            if (!usageRecorded.compareAndSet(false, true)) return
            val usage = response.usage().orElse(null)
            append(metadata + mapOf(
                "event" to "response_usage", "responseId" to response.id(),
                "responseModel" to response.model().toString(), "responseStatus" to response.status().orElse(null)?.toString(),
                "usageKnown" to (usage != null), "inputTokens" to usage?.inputTokens(),
                "cachedInputTokens" to usage?.inputTokensDetails()?.cachedTokens(),
                "cacheWriteTokens" to usage?.inputTokensDetails()?._additionalProperties()?.get("cache_write_tokens")?.convert(Long::class.javaObjectType),
                "outputTokens" to usage?.outputTokens(), "reasoningTokens" to usage?.outputTokensDetails()?.reasoningTokens(),
                "totalTokens" to usage?.totalTokens()
            ))
        }

        fun finish(failure: Throwable? = null) {
            val chain = generateSequence(failure) { it.cause }.take(12).toList()
            val http = chain.filterIsInstance<OpenAIServiceException>().firstOrNull()
            val provider = chain.filterIsInstance<ModelResponseFailure>().firstOrNull()
            val headers = listOf("x-request-id", "retry-after", "x-ratelimit-limit-tokens", "x-ratelimit-remaining-tokens",
                "x-ratelimit-reset-tokens", "x-ratelimit-limit-requests", "x-ratelimit-remaining-requests", "x-ratelimit-reset-requests")
                .mapNotNull { key -> http?.headers()?.values(key)?.firstOrNull()?.let { key to it.take(200) } }.toMap()
            val messages = chain.joinToString("\n") { it.message.orEmpty() }
            fun count(name: String): Long? = Regex("(?i)\\b$name\\s*:?\\s*(\\d+)").find(messages)?.groupValues?.get(1)?.toLongOrNull()
            append(metadata + mapOf(
                "event" to "attempt_finished", "success" to (failure == null),
                "elapsedMs" to (System.nanoTime() - started) / 1_000_000,
                "errorType" to failure?.javaClass?.simpleName, "providerCode" to provider?.reason?.take(100),
                "httpStatus" to http?.statusCode(), "rateLimited" to (failure != null && Regex("(?i)rate[ _-]?limit|\\b429\\b").containsMatchIn(messages)),
                "limitTokens" to count("Limit"), "usedTokens" to count("Used"), "requestedTokens" to count("Requested"),
                "responseHeaders" to headers
            ))
        }
    }
}
