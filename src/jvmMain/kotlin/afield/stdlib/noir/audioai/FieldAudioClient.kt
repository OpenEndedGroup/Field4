@file:afield.CompletionCategory("Audio AI")

package afield.stdlib.noir.audioai

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FieldAudioException(
    val statusCode: Int,
    val responseBody: String,
    val error: StructuredError? = null,
) : RuntimeException(
    error?.let { "${it.code}: ${it.message}" }
        ?: "Field Audio HTTP $statusCode: ${responseBody.take(500)}"
)

class FieldAudioJobException(
    val jobId: String,
    val state: JobState,
    val error: StructuredError?,
) : RuntimeException(
    error?.let { "${it.code}: ${it.message}" }
        ?: "audio job $jobId ended as $state"
)

@Serializable
private data class FieldAudioErrorEnvelope(val error: StructuredError)

internal fun decodeFieldAudioError(status: Int, body: String, json: Json): FieldAudioException {
    val structured = runCatching {
        json.decodeFromString(FieldAudioErrorEnvelope.serializer(), body).error
    }.getOrNull()
    return FieldAudioException(status, body, structured)
}

/**
 * Non-blocking, backend-neutral client for Field's audio model service.
 *
 * All HTTP completions occur on [callbackExecutor], never Field's Runloop or its
 * audio callback. Consumers decide when and how to marshal UI updates.
 */
class FieldAudioClient(
    baseUrl: String = "http://127.0.0.1:8765",
    private val callbackExecutor: Executor = DEFAULT_EXECUTOR,
) {
    val baseUri: URI = URI.create(baseUrl.trimEnd('/'))
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .executor(callbackExecutor)
        // Uvicorn does not implement Java HttpClient's cleartext h2c upgrade;
        // forcing HTTP/1.1 preserves POST bodies on this loopback protocol.
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = false
    }

    fun health(): CompletableFuture<String> =
        send(HttpRequest.newBuilder(resolve("/health")).GET().build(), null)

    fun models(): CompletableFuture<List<ModelCapability>> =
        send(
            HttpRequest.newBuilder(resolve("/v1/models")).GET().build(),
            ListSerializer(ModelCapability.serializer()),
        )

    fun importWav(path: Path): CompletableFuture<AudioAsset> {
        val filename = URLEncoder.encode(path.fileName.toString(), Charsets.UTF_8)
        val request = HttpRequest.newBuilder(
            resolve("/v1/assets/import?filename=$filename&media_type=audio%2Fwav")
        )
            .header("Content-Type", "application/octet-stream")
            .POST(HttpRequest.BodyPublishers.ofFile(path))
            .build()
        return send(request, AudioAsset.serializer())
    }

    fun describe(assetId: String): CompletableFuture<AudioAsset> =
        send(
            HttpRequest.newBuilder(resolve("/v1/assets/$assetId")).GET().build(),
            AudioAsset.serializer(),
        )

    fun assetByFilename(
        pattern: String,
        preferredRole: String? = "canonical_project",
    ): CompletableFuture<AudioAsset> = assetsByFilename(pattern, preferredRole).thenApply { matches ->
        require(matches.size == 1) {
            when {
                matches.isEmpty() -> "no audio asset filename matches '$pattern'"
                else -> "audio asset filename pattern '$pattern' matched ${matches.size} assets: " +
                    matches.joinToString { it.id }
            }
        }
        matches.single()
    }

    fun assetsByFilename(
        pattern: String,
        preferredRole: String? = "canonical_project",
    ): CompletableFuture<List<AudioAsset>> {
        require(pattern.isNotBlank()) { "filename pattern must not be blank" }
        val encodedPattern = URLEncoder.encode(pattern, Charsets.UTF_8)
        val role = preferredRole?.let {
            "&preferred_role=${URLEncoder.encode(it, Charsets.UTF_8)}"
        }.orEmpty()
        return send(
            HttpRequest.newBuilder(resolve("/v1/assets/search?pattern=$encodedPattern$role"))
                .GET().build(),
            ListSerializer(AudioAsset.serializer()),
        )
    }

    fun waveform(
        representationId: String,
        startSeconds: Double = 0.0,
        durationSeconds: Double? = null,
        points: Int = 1200,
    ): CompletableFuture<WaveformSummary> {
        require(startSeconds >= 0.0) { "startSeconds must be non-negative" }
        require(durationSeconds == null || durationSeconds > 0.0) { "durationSeconds must be positive" }
        require(points in 16..4096) { "points must be between 16 and 4096" }
        val query = buildString {
            append("?start_seconds=").append(startSeconds)
            durationSeconds?.let { append("&duration_seconds=").append(it) }
            append("&points=").append(points)
        }
        return send(
            HttpRequest.newBuilder(resolve("/v1/representations/$representationId/waveform$query"))
                .GET().build(),
            WaveformSummary.serializer(),
        )
    }

    fun submit(operation: ModelOperation): CompletableFuture<JobAccepted> =
        postJson("/v1/operations", json.encodeToString(operation), JobAccepted.serializer())

    fun inspect(jobId: String): CompletableFuture<JobRecord> =
        send(
            HttpRequest.newBuilder(resolve("/v1/jobs/$jobId")).GET().build(),
            JobRecord.serializer(),
        )

    fun jobs(state: JobState? = null, limit: Int = 100): CompletableFuture<List<JobSummary>> {
        require(limit in 1..500) { "job list limit must be between 1 and 500" }
        val stateQuery = state?.let {
            "&state=${URLEncoder.encode(json.encodeToString(JobState.serializer(), it).trim('"'), Charsets.UTF_8)}"
        }.orEmpty()
        return send(
            HttpRequest.newBuilder(resolve("/v1/jobs?limit=$limit$stateQuery")).GET().build(),
            ListSerializer(JobSummary.serializer()),
        )
    }

    fun cancel(jobId: String): CompletableFuture<JobRecord> =
        postJson("/v1/jobs/$jobId/cancel", "{}", JobRecord.serializer())

    fun candidateSet(id: String): CompletableFuture<CandidateSet> =
        send(
            HttpRequest.newBuilder(resolve("/v1/candidate-sets/$id")).GET().build(),
            CandidateSet.serializer(),
        )

    fun codecResult(id: String): CompletableFuture<CodecReconstructionResult> =
        send(
            HttpRequest.newBuilder(resolve("/v1/codec-results/$id")).GET().build(),
            CodecReconstructionResult.serializer(),
        )

    fun analysisResult(id: String): CompletableFuture<AnalysisResult> =
        send(
            HttpRequest.newBuilder(resolve("/v1/analysis-results/$id")).GET().build(),
            AnalysisResult.serializer(),
        )

    fun analysisPreview(id: String, points: Int = 1200): CompletableFuture<AnalysisPreview> {
        require(points in 16..4096) { "analysis preview points must be between 16 and 4096" }
        return send(
            HttpRequest.newBuilder(resolve("/v1/analysis-results/$id/preview?points=$points"))
                .GET().build(),
            AnalysisPreview.serializer(),
        )
    }

    fun judge(candidateSetId: String, candidateIndex: Int, judgement: CandidateJudgement): CompletableFuture<String> =
        putJson(
            "/v1/candidate-sets/$candidateSetId/candidates/$candidateIndex",
            json.encodeToString(judgement),
        )

    fun download(representation: AssetRepresentation, destination: Path): CompletableFuture<Path> {
        val request = HttpRequest.newBuilder(resolve(representation.binaryUrl)).GET().build()
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
            .thenApplyAsync({ response ->
                requireSuccess(response.statusCode(), String(response.body().take(512).toByteArray()))
                destination.parent?.let(Files::createDirectories)
                Files.write(destination, response.body())
                destination
            }, callbackExecutor)
    }

    /** Poll durable job state without occupying Field's runloop. */
    fun await(jobId: String, pollEvery: Duration = Duration.ofMillis(200)): CompletableFuture<JobRecord> {
        val result = CompletableFuture<JobRecord>()
        fun poll() {
            inspect(jobId).whenComplete { job, error ->
                when {
                    error != null -> result.completeExceptionally(error)
                    job.state.terminal -> result.complete(job)
                    else -> CompletableFuture.delayedExecutor(
                        pollEvery.toMillis(), TimeUnit.MILLISECONDS, callbackExecutor
                    ).execute(::poll)
                }
            }
        }
        poll()
        return result
    }

    fun awaitCandidates(accepted: JobAccepted): CompletableFuture<CandidateSet> =
        await(accepted.jobId).thenCompose { job ->
            if (job.state != JobState.COMPLETE) {
                CompletableFuture.failedFuture(
                    FieldAudioJobException(job.id, job.state, job.error)
                )
            } else {
                candidateSet(requireNotNull(job.candidateSetId) { "completed job has no candidate set" })
            }
        }

    fun awaitCodec(accepted: JobAccepted): CompletableFuture<CodecReconstructionResult> =
        await(accepted.jobId).thenCompose { job ->
            if (job.state != JobState.COMPLETE) {
                CompletableFuture.failedFuture(
                    FieldAudioJobException(job.id, job.state, job.error)
                )
            } else {
                codecResult(requireNotNull(job.codecResultId) { "completed codec job has no result" })
            }
        }

    fun awaitAnalysis(accepted: JobAccepted): CompletableFuture<AnalysisResult> =
        await(accepted.jobId).thenCompose { job ->
            if (job.state != JobState.COMPLETE) {
                CompletableFuture.failedFuture(
                    FieldAudioJobException(job.id, job.state, job.error)
                )
            } else {
                analysisResult(requireNotNull(job.analysisResultId) {
                    "completed analysis job has no result"
                })
            }
        }

    /**
     * Subscribe to progress history plus live events. The server closes the socket
     * after a terminal event. The returned socket can be used to cancel the subscription.
     */
    fun watch(
        jobId: String,
        onEvent: (ProgressEvent) -> Unit,
        onError: (Throwable) -> Unit = {},
    ): CompletableFuture<WebSocket> {
        val websocketUri = URI.create(
            baseUri.toString().replaceFirst("http://", "ws://").replaceFirst("https://", "wss://") +
                "/v1/events/$jobId"
        )
        val text = StringBuilder()
        return http.newWebSocketBuilder().buildAsync(websocketUri, object : WebSocket.Listener {
            override fun onOpen(webSocket: WebSocket) {
                webSocket.request(1)
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
                text.append(data)
                if (last) {
                    val eventText = text.toString()
                    text.setLength(0)
                    runCatching { json.decodeFromString(ProgressEvent.serializer(), eventText) }
                        .onSuccess(onEvent)
                        .onFailure(onError)
                }
                webSocket.request(1)
                return CompletableFuture.completedFuture(null)
            }

            override fun onError(webSocket: WebSocket, error: Throwable) = onError(error)
        })
    }

    private fun resolve(path: String): URI = baseUri.resolve(path)

    private fun <T> postJson(path: String, body: String, serializer: KSerializer<T>): CompletableFuture<T> =
        send(
            HttpRequest.newBuilder(resolve(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            serializer,
        )

    private fun putJson(path: String, body: String): CompletableFuture<String> =
        send(
            HttpRequest.newBuilder(resolve(path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            null,
        )

    private fun <T> send(request: HttpRequest, serializer: KSerializer<T>?): CompletableFuture<T> =
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApplyAsync({ response ->
                requireSuccess(response.statusCode(), response.body())
                @Suppress("UNCHECKED_CAST")
                if (serializer == null) response.body() as T
                else json.decodeFromString(serializer, response.body())
            }, callbackExecutor)

    private fun requireSuccess(status: Int, body: String) {
        if (status !in 200..299) throw decodeFieldAudioError(status, body, json)
    }

    companion object {
        private val DEFAULT_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor()
    }
}

/** Global controls exposed by the pinned MusicGen-Style checkpoint. */
data class MusicGenStyleControls(
    val useSampling: Boolean = true,
    val topK: Int = 250,
    val topP: Double = 0.0,
    val temperature: Double = 1.0,
    val cfg: Double = 3.0,
    val cfgBeta: Double? = 5.0,
    val twoStepCfg: Boolean = false,
    val styleQuantization: Int = 1,
    val styleExcerptSeconds: Double? = null,
    val styleDownsampleFactor: Int? = null,
    val styleEncodecCodebooks: Int? = null,
) {
    init {
        require(topK >= 0) { "topK must be non-negative" }
        require(topP in 0.0..1.0) { "topP must be between zero and one" }
        require(temperature > 0.0) { "temperature must be positive" }
        require(cfg >= 0.0) { "cfg must be non-negative" }
        require(cfgBeta == null || cfgBeta >= 0.0) { "cfgBeta must be non-negative" }
        require(styleQuantization > 0) { "styleQuantization must be positive" }
        require(styleExcerptSeconds == null || styleExcerptSeconds > 0.0) {
            "styleExcerptSeconds must be positive"
        }
        require(styleDownsampleFactor == null || styleDownsampleFactor > 0) {
            "styleDownsampleFactor must be positive"
        }
        require(styleEncodecCodebooks == null || styleEncodecCodebooks > 0) {
            "styleEncodecCodebooks must be positive"
        }
    }
}

/** Ergonomic live-code constructor for the source-window style instrument. */
fun styleCandidates(
    source: AssetRegion,
    prompt: String = "",
    durationSeconds: Double = 12.0,
    seeds: LongRange = 0L until 8L,
    backend: String? = null,
    requestedBy: String? = null,
    controls: MusicGenStyleControls = MusicGenStyleControls(),
    forceRegenerate: Boolean = false,
): ModelOperation {
    require(source.durationSeconds in 1.5..4.5) {
        "MusicGen-Style source window must be between 1.5 and 4.5 seconds"
    }
    require(durationSeconds > 0.0) { "durationSeconds must be positive" }
    require(controls.styleExcerptSeconds == null || controls.styleExcerptSeconds <= source.durationSeconds) {
        "styleExcerptSeconds cannot exceed the source window"
    }
    val seedList = seeds.toList()
    require(seedList.isNotEmpty()) { "seeds must not be empty" }
    return ModelOperation(
        operation = "generate.style",
        inputs = listOf(source),
        parameters = buildJsonObject {
            put("duration_seconds", durationSeconds)
            if (prompt.isNotBlank()) put("prompt", prompt)
            put("use_sampling", controls.useSampling)
            put("top_k", controls.topK)
            put("top_p", controls.topP)
            put("temperature", controls.temperature)
            put("cfg", controls.cfg)
            if (prompt.isNotBlank()) controls.cfgBeta?.let { put("cfg_beta", it) }
            put("two_step_cfg", controls.twoStepCfg)
            put("style_quantization", controls.styleQuantization)
            controls.styleExcerptSeconds?.let { put("style_excerpt_seconds", it) }
            controls.styleDownsampleFactor?.let { put("style_downsample_factor", it) }
            controls.styleEncodecCodebooks?.let { put("style_encodec_codebooks", it) }
        },
        seeds = seedList,
        candidateCount = seedList.size,
        backendPreference = backend,
        requestedBy = requestedBy,
        forceRegenerate = forceRegenerate,
    )
}

/** Exact EnCodec encode/decode round trip for one source selection. */
fun codecReconstruction(
    source: AssetRegion,
    numCodebooks: Int? = null,
    backend: String? = "audiocraft.encodec_musicgen_style_32khz",
    requestedBy: String? = null,
    forceRegenerate: Boolean = false,
): ModelOperation = ModelOperation(
    operation = "codec.reconstruct",
    inputs = listOf(source),
    parameters = buildJsonObject {
        numCodebooks?.let { put("num_codebooks", it) }
    },
    candidateCount = 1,
    backendPreference = backend,
    requestedBy = requestedBy,
    forceRegenerate = forceRegenerate,
)
