package afield

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import kotlin.script.experimental.api.SourceCodeCompletionVariant

/**
 * Stage C client for the out-of-process K2 Analysis-API completion endpoint (`/complete` on the
 * code-use semantic sidecar). Used only when `KotlinEngineConfig.activeCompletionBackend == AA`;
 * [KotlinInterface2.complete] falls back to the K1 delegate whenever this returns null (no sidecar
 * URL configured, HTTP/timeout failure, or the sidecar reports failure). The AA classpath deliberately
 * stays out of the live runtime — this is a thin HTTP bridge, mirroring `CodeUseTelemetryService`.
 */
object AaCompletionClient {

    @Serializable
    private data class Req(
        val snippet: String,
        val cursor: Int,
        val preamble: String = "",
        /** File-scope declarations supplied by a synthetic language/runtime profile. */
        val declarations: String = "",
        val imports: List<String> = emptyList(),
        val implicitReceivers: List<String> = emptyList(),
        val classpathRoots: List<String> = emptyList(),
        val limit: Int = 200,
    )

    @Serializable
    private data class Cand(
        val text: String,
        val displayText: String,
        val tail: String,
        val icon: String,
        val receiverType: String = "",
        val receiverQualifiedName: String = "",
    )

    @Serializable
    private data class Resp(
        val success: Boolean = false,
        val backend: String = "",
        val message: String = "",
        val candidates: List<Cand> = emptyList(),
    )

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1500)).build()
    }

    /** Field's binary classpath (jars + build/classes) — includes afield's own output, unlike the
     *  gradle `jvmRuntimeClasspath`. Computed once; the running JVM's classpath doesn't change. */
    private val classpathRoots: List<String> by lazy {
        (System.getProperty("java.class.path") ?: "").split(File.pathSeparatorChar)
            .map { it.trim() }.filter { it.isNotEmpty() && File(it).exists() }.distinct()
    }

    /**
     * Just the core `kotlin-stdlib` jar(s) from Field's classpath — the analysis scope for
     * "stdlib-only" completion (KotlinJS / head boxes, which have no JVM classpath, only the Kotlin
     * stdlib + their own declarations). Excludes `-jdk7`/`-jdk8` so JVM-only extensions don't leak
     * into completions for code that runs in a JS head. Empty if none found (caller then skips AA).
     */
    fun kotlinStdlibRoots(): List<String> =
        classpathRoots.filter { Regex("""kotlin-stdlib-\d.*\.jar""").matches(File(it).name) }

    private fun endpoint(): URI? {
        // Prefer an explicit completion-sidecar override, else reuse the code-use sidecar URL.
        val base = (System.getProperty("afield.completion.sidecarUrl")?.trim()
            ?: System.getenv("AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL")?.trim()).orEmpty()
        if (base.isBlank()) return null
        return runCatching {
            val u = URI.create(base)
            if (u.path.endsWith("/complete")) u else u.resolve("/complete")
        }.getOrNull()
    }

    /**
     * Outcome of an AA completion attempt. [result] non-null means AA served the completion; null means
     * the caller should fall back to K1. [detail] always explains what happened (for logging).
     */
    data class Outcome(val result: CompletionResult?, val detail: String) {
        val served: Boolean get() = result != null
    }

    fun complete(
        snippet: String,
        cursor: Int,
        preamble: String,
        implicitReceivers: List<String>,
        declarations: String = "",
        timeoutMs: Long = 4000,
        // Restrict the analysis classpath (e.g. stdlib-only for KotlinJS boxes). Null = Field's full
        // classpath (the normal rich-completion universe).
        classpathRootsOverride: List<String>? = null,
    ): Outcome {
        endpoint() ?: return Outcome(null, "no-sidecar-url")
        val roots = classpathRootsOverride?.takeIf { it.isNotEmpty() } ?: classpathRoots
        if (roots.isEmpty()) return Outcome(null, "no-classpath")
        val uri = endpoint()!!
        return runCatching {
            val body = json.encodeToString(
                Req(
                    snippet = snippet,
                    cursor = cursor.coerceIn(0, snippet.length),
                    preamble = preamble,
                    declarations = declarations,
                    implicitReceivers = implicitReceivers,
                    classpathRoots = roots,
                )
            )
            val request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(timeoutMs.coerceIn(200, 30_000)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (response.statusCode() != 200) return Outcome(null, "http-${response.statusCode()}")
            val resp = json.decodeFromString<Resp>(response.body())
            if (!resp.success) return Outcome(null, "server-fail: ${resp.message.take(80)}")
            // Safety net for AA-as-default: an empty AA result (e.g. a preamble/receiver form the
            // headless session can't model, or the session still warming up) falls through to the
            // compiler-grade K1 delegate, so AA is never worse than K1 in coverage.
            if (resp.candidates.isEmpty()) return Outcome(null, "empty${resp.message.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}")

            val prefix = Regex("[A-Za-z0-9_]*$").find(snippet.substring(0, cursor.coerceIn(0, snippet.length)))?.value ?: ""
            val bounds = CompletionTokenBounds(cursor - prefix.length, cursor)
            val metadata = resp.candidates.map {
                SourceCodeCompletionVariant(text = it.text, displayText = it.displayText, tail = it.tail, icon = it.icon).apply {
                    // Mirror the K1 delegate's extraInfo so the server's doc-enrichment pipeline
                    // (member KDoc via `receiverType.member`, AI-draft docs, receiver docs) attaches to
                    // AA candidates too — otherwise dot completions come back doc-less.
                    if (it.receiverType.isNotBlank() || it.receiverQualifiedName.isNotBlank()) {
                        extraInfo = mapOf(
                            "receiverType" to it.receiverType,
                            "receiverQualifiedName" to it.receiverQualifiedName,
                        )
                    }
                }
            }
            Outcome(
                CompletionResult.Success(
                    matches = metadata.map { it.text },
                    bounds = bounds,
                    metadata = metadata,
                    text = snippet,
                    cursor = cursor,
                ),
                "${metadata.size} candidates",
            )
        }.getOrElse {
            Outcome(null, "error ${it.javaClass.simpleName}: ${it.message}")
        }
    }
}
