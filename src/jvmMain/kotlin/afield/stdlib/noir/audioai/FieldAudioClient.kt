@file:afield.CompletionCategory("Audio AI")

package afield.stdlib.noir.audioai

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.ConnectException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The verbs of Field Audio: one client object whose every method is a request to the model
 * service, plus the typed constructors for the two operations that ship with real controls.
 *
 * The protocol types — [AssetRegion], [ModelOperation], [JobRecord] and the rest — are the
 * vocabulary; this is what you can *do* with it. The shape to hold on to is that nothing here
 * blocks and nothing here runs a model in the JVM: a call returns
 * a `CompletableFuture` that completes on the client's own executor, and the model work happens in
 * a Python worker that may take minutes. Composing futures (`thenCompose`, `thenAccept`) is
 * therefore the only correct way to use this from a box — a `.get()` on Field's runloop freezes
 * the editor for as long as the model takes.
 *
 * Argument validation happens here, in the JVM, before anything is sent: `require` failures name
 * the constraint. Everything the service rejects instead arrives as a [FieldAudioException]
 * carrying the service's own [StructuredError], and a job that fails after being accepted arrives
 * as a [FieldAudioJobException] from one of the `await…` helpers.
 */

/**
 * The service said no, and said why.
 *
 * Every non-2xx response becomes one of these. When the body carried the service's own error
 * envelope, [error] holds it and the exception message is `CODE: message`; when it did not —
 * a proxy error page, a crash before the handler — [error] is null and the message falls back
 * to the status plus the first 500 characters of the body. Check `error?.code` before matching
 * on message text, and `error?.retryable` before retrying.
 */
class FieldAudioException(
    /** HTTP status of the response that failed. */
    val statusCode: Int,
    /** The response body verbatim, for the cases [error] could not be decoded from it. */
    val responseBody: String,
    /** The service's structured error, when the body contained one. See [StructuredError.code]. */
    val error: StructuredError? = null,
) : RuntimeException(
    error?.let { "${it.code}: ${it.message}" }
        ?: "Field Audio HTTP $statusCode: ${responseBody.take(500)}"
)

/**
 * A job was accepted and then did not finish successfully.
 *
 * Raised by `await`-composing helpers ([awaitCandidates], [awaitCodec], [awaitAnalysis]) when a
 * job reaches a terminal state other than `COMPLETE` — so cancelled, failed, or
 * `CANCEL_UNSUPPORTED` — because there is no result to hand back. [state] says which, and
 * [error] carries the service's explanation when the job recorded one.
 */
class FieldAudioJobException(
    /** The job that ended badly; still fetchable with [FieldAudioClient.inspect]. */
    val jobId: String,
    /** The terminal state it ended in — anything but `COMPLETE`. */
    val state: JobState,
    /** Why, when the job recorded a structured error. Null for a plain cancellation. */
    val error: StructuredError?,
) : RuntimeException(
    error?.let { "${it.code}: ${it.message}" }
        ?: "audio job $jobId ended as $state"
)

/**
 * There was no conversation: the service could not be reached, or did not answer in the protocol.
 *
 * Distinct from [FieldAudioException], which is the service saying no in its own words, and from
 * [FieldAudioJobException], which is a job of its own that went wrong. This one means the request
 * never landed — a refused connection because the Python service in `services/field-audio-ai` is
 * not running, a connect timeout — or that a 2xx body was not the JSON the endpoint promises.
 *
 * It exists because the alternative was unusable. `java.net.ConnectException` from
 * `HttpClient.sendAsync` carries **no message at all**, so a box whose only problem was that the
 * service had not been started displayed an empty error: not the address, not the port, not even the
 * word audio. [baseUri] and [requestPath] say where and what.
 */
class FieldAudioTransportException(
    /** The client's base address — the thing to check is running, or to point somewhere else. */
    val baseUri: URI,
    /** Path of the request that failed, e.g. `/v1/assets/import`. */
    val requestPath: String,
    message: String,
    cause: Throwable?,
) : RuntimeException(message, cause)

/** The exception a box needs to read, not the `CompletableFuture` bookkeeping around it. */
internal fun Throwable.unwrapCompletion(): Throwable = (this as? CompletionException)?.cause ?: this

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
    /** Where this client points, with any trailing slash removed so relative paths resolve cleanly. */
    val baseUri: URI = URI.create(baseUrl.trimEnd('/'))
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .executor(callbackExecutor)
        // Uvicorn does not implement Java HttpClient's cleartext h2c upgrade;
        // forcing HTTP/1.1 preserves POST bodies on this loopback protocol.
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /** The shared Field Audio wire format. Prefer [FieldAudioClient.json] when no client is needed. */
    val json: Json = Companion.json

    /** Liveness only: the raw body of `GET /health`. It starts nothing and loads no model. */
    fun health(): CompletableFuture<String> =
        send(HttpRequest.newBuilder(resolve("/health")).GET().build(), null)

    /**
     * Every backend the service knows about, including the ones it cannot currently run.
     *
     * Check [ModelCapability.available] before submitting: an unavailable model is still listed, with
     * [ModelCapability.unavailableReason] saying what is missing.
     */
    fun models(): CompletableFuture<List<ModelCapability>> =
        send(
            HttpRequest.newBuilder(resolve("/v1/models")).GET().build(),
            ListSerializer(ModelCapability.serializer()),
        )

    /**
     * Upload a WAV file and get the asset it became.
     *
     * Importing is idempotent because assets are content-addressed: the same bytes yield the same
     * [AudioAsset.id], so re-importing a file you already have costs one upload and creates nothing.
     * The file is streamed from disk rather than read into the JVM.
     */
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

    /** Fetch one asset and all its representations by content address. */
    fun describe(assetId: String): CompletableFuture<AudioAsset> =
        send(
            HttpRequest.newBuilder(resolve("/v1/assets/$assetId")).GET().build(),
            AudioAsset.serializer(),
        )

    /**
     * The one asset whose original filename matches [pattern] — and an error if that is not exactly one.
     *
     * This is the ergonomic entry point for live coding (`assetByFilename("*Nancy*")`): it fails loudly
     * on both zero matches and several, naming the ids it found, rather than silently picking one. Use
     * [assetsByFilename] when several matches are a legitimate answer.
     */
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

    /**
     * Every asset whose original filename matches [pattern], preferring [preferredRole] representations.
     *
     * [pattern] must not be blank. The default role, `canonical_project`, is the 48 kHz project copy
     * the service makes at import — the one you almost always want to work from rather than the raw file.
     */
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

    /**
     * A min/max envelope of one representation's window, for drawing.
     *
     * [startSeconds] must be non-negative, [durationSeconds] positive or null for "to the end", and
     * [points] within 16..4096. Every source frame in the window contributes, so this is safe to ask
     * for over a long recording and unforgiving about single-sample transients. `AudioVisualizer` calls
     * this endpoint itself as the user zooms; call it directly only when you are drawing the waveform
     * yourself.
     */
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

    /**
     * Submit an operation and get the job it created — the start of every piece of model work.
     *
     * Returns as soon as the service has accepted it, which is long before the work is done; follow
     * the job with [await], [watch], or one of the `await…` helpers that goes straight to the result.
     * An identical request that has been served before comes back with [JobAccepted.cacheHit] true
     * and already complete.
     */
    fun submit(operation: ModelOperation): CompletableFuture<JobAccepted> =
        postJson("/v1/operations", json.encodeToString(operation), JobAccepted.serializer())

    /** Ask the authoritative service to expose exactly how a board reaches this backend. */
    fun compileGesture(
        board: GestureBoard,
        operation: String = "generate.style",
        parameters: JsonObject = JsonObject(emptyMap()),
    ): CompletableFuture<GestureCompilation> = postJson(
        "/v1/gesture-boards/compile",
        json.encodeToString(GestureCompileRequest(board, operation, parameters)),
        GestureCompilation.serializer(),
    )

    /** The durable state of one job right now. This is the authority, not the progress stream. */
    fun inspect(jobId: String): CompletableFuture<JobRecord> =
        send(
            HttpRequest.newBuilder(resolve("/v1/jobs/$jobId")).GET().build(),
            JobRecord.serializer(),
        )

    /** Recent jobs, newest first, optionally filtered to one [state]. [limit] must be within 1..500. */
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

    /**
     * Ask for a job to stop, and get its state back.
     *
     * A request, not a guarantee — see [JobState]. A backend that cannot be interrupted ends as
     * `CANCEL_UNSUPPORTED` and one that finished first ends as `COMPLETED_BEFORE_CANCELLATION`, which
     * still has results.
     */
    fun cancel(jobId: String): CompletableFuture<JobRecord> =
        postJson("/v1/jobs/$jobId/cancel", "{}", JobRecord.serializer())

    /** Fetch a stored candidate family by id — as taken from [JobRecord.candidateSetId]. */
    fun candidateSet(id: String): CompletableFuture<CandidateSet> =
        send(
            HttpRequest.newBuilder(resolve("/v1/candidate-sets/$id")).GET().build(),
            CandidateSet.serializer(),
        )

    /** Fetch a stored codec round trip by id — as taken from [JobRecord.codecResultId]. */
    fun codecResult(id: String): CompletableFuture<CodecReconstructionResult> =
        send(
            HttpRequest.newBuilder(resolve("/v1/codec-results/$id")).GET().build(),
            CodecReconstructionResult.serializer(),
        )

    /** Fetch a stored analysis by id — as taken from [JobRecord.analysisResultId]. */
    fun analysisResult(id: String): CompletableFuture<AnalysisResult> =
        send(
            HttpRequest.newBuilder(resolve("/v1/analysis-results/$id")).GET().build(),
            AnalysisResult.serializer(),
        )

    /**
     * The plot-sized view of an analysis: at most [points] samples per series, 16..4096.
     *
     * Ask for this to draw something; download [AnalysisResult.data] to measure something. The curves
     * are downsampled server-side, so this never transfers the full `.npz`.
     */
    fun analysisPreview(id: String, points: Int = 1200): CompletableFuture<AnalysisPreview> {
        require(points in 16..4096) { "analysis preview points must be between 16 and 4096" }
        return send(
            HttpRequest.newBuilder(resolve("/v1/analysis-results/$id/preview?points=$points"))
                .GET().build(),
            AnalysisPreview.serializer(),
        )
    }

    /** Fetch bounded segment/neighbour metadata and playable handles for a corpus analysis result. */
    fun corpusPreview(id: String): CompletableFuture<CorpusPreview> =
        send(
            HttpRequest.newBuilder(resolve("/v1/analysis-results/$id/corpus")).GET().build(),
            CorpusPreview.serializer(),
        )

    /**
     * Write a rating, selection flag and note back onto one candidate.
     *
     * The judgement replaces whatever was there — it is a PUT, not a merge, so send the fields you
     * want to keep. This is the one call that changes server state other than by creating assets.
     */
    fun judge(candidateSetId: String, candidateIndex: Int, judgement: CandidateJudgement): CompletableFuture<String> =
        putJson(
            "/v1/candidate-sets/$candidateSetId/candidates/$candidateIndex",
            json.encodeToString(judgement),
        )

    /**
     * Fetch a representation's bytes to [destination], creating parent directories, and return the path.
     *
     * The whole body is held in memory before it is written, so this is for clips and tensors rather
     * than for hour-long recordings. It resolves [AssetRepresentation.binaryUrl] against this client's
     * base URI, so pass the representation rather than a URL you built yourself.
     */
    fun download(representation: AssetRepresentation, destination: Path): CompletableFuture<Path> {
        val request = HttpRequest.newBuilder(resolve(representation.binaryUrl)).GET().build()
        val path = request.uri().path
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
            .handleAsync({ response, transport ->
                if (transport != null) throw unreachable(path, transport.unwrapCompletion())
                requireSuccess(response.statusCode(), String(response.body().take(512).toByteArray()))
                destination.parent?.let(Files::createDirectories)
                Files.write(destination, response.body())
                destination
            }, callbackExecutor)
    }

    /**
     * Poll durable job state without occupying Field's runloop.
     *
     * Cancelling the returned future stops the polling. That is the only way to stop it — a job
     * whose worker has wedged never reaches a terminal state, and this loop is otherwise happy to
     * ask about it every [pollEvery] for the lifetime of the JVM.
     */
    fun await(jobId: String, pollEvery: Duration = Duration.ofMillis(200)): CompletableFuture<JobRecord> {
        val result = CompletableFuture<JobRecord>()
        fun poll() {
            if (result.isDone) return
            inspect(jobId).whenComplete { job, error ->
                when {
                    result.isDone -> Unit
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

    /**
     * Poll a `generate.*` job to the end and hand back its candidate family.
     *
     * The short way from [submit] to something you can audition. Fails with [FieldAudioJobException]
     * if the job ends as anything but `COMPLETE`.
     */
    fun awaitCandidates(accepted: JobAccepted): CompletableFuture<CandidateSet> =
        awaitResult(accepted) { job ->
            candidateSet(requireNotNull(job.candidateSetId) { "completed job has no candidate set" })
        }

    /**
     * Poll a `codec.*` job to the end and hand back its reconstruction result.
     *
     * Fails with [FieldAudioJobException] if the job ends as anything but `COMPLETE`.
     */
    fun awaitCodec(accepted: JobAccepted): CompletableFuture<CodecReconstructionResult> =
        awaitResult(accepted) { job ->
            codecResult(requireNotNull(job.codecResultId) { "completed codec job has no result" })
        }

    /**
     * Poll an `analysis.*` job to the end and hand back its analysis result.
     *
     * Fails with [FieldAudioJobException] if the job ends as anything but `COMPLETE`.
     */
    fun awaitAnalysis(accepted: JobAccepted): CompletableFuture<AnalysisResult> =
        awaitResult(accepted) { job ->
            analysisResult(requireNotNull(job.analysisResultId) {
                "completed analysis job has no result"
            })
        }

    /**
     * Await one job and fetch whatever it stored, or fail with [FieldAudioJobException].
     *
     * Cancellation has to be forwarded by hand: cancelling a `thenCompose` stage says nothing about
     * the stage it was composed onto, so without this the poll loop would outlive the future that
     * is the caller's only handle on it.
     */
    private fun <T> awaitResult(
        accepted: JobAccepted,
        fetch: (JobRecord) -> CompletableFuture<T>,
    ): CompletableFuture<T> {
        val polling = await(accepted.jobId)
        val result = polling.thenCompose { job ->
            if (job.state != JobState.COMPLETE) {
                CompletableFuture.failedFuture(FieldAudioJobException(job.id, job.state, job.error))
            } else {
                fetch(job)
            }
        }
        result.whenComplete { _, error -> if (error is CancellationException) polling.cancel(false) }
        return result
    }

    /**
     * Subscribe to progress history plus live events. The server closes the socket
     * after a terminal event. The returned socket can be used to cancel the subscription.
     *
     * A handshake that never lands — the service is not running, or the job is unknown to it — fails
     * the returned future with [FieldAudioTransportException]. [onError] is left alone: an error on
     * an established socket is the caller's to interpret.
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
        }).handle { socket, transport ->
            if (transport != null) throw unreachable("/v1/events/$jobId", transport.unwrapCompletion())
            socket
        }
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

    private fun <T> send(request: HttpRequest, serializer: KSerializer<T>?): CompletableFuture<T> {
        val path = request.uri().path
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .handleAsync({ response, transport ->
                if (transport != null) throw unreachable(path, transport.unwrapCompletion())
                requireSuccess(response.statusCode(), response.body())
                @Suppress("UNCHECKED_CAST")
                if (serializer == null) response.body() as T
                else decode(path, response.body(), serializer)
            }, callbackExecutor)
    }

    private fun requireSuccess(status: Int, body: String) {
        if (status !in 200..299) throw decodeFieldAudioError(status, body, json)
    }

    private fun <T> decode(path: String, body: String, serializer: KSerializer<T>): T =
        try {
            json.decodeFromString(serializer, body)
        } catch (failure: SerializationException) {
            throw FieldAudioTransportException(
                baseUri, path,
                "Field Audio answered $baseUri$path with something this Field cannot read: " +
                    "${failure.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()}. " +
                    "Body began: ${body.take(200)}",
                failure,
            )
        }

    /**
     * The service is not there, or did not finish answering.
     *
     * Worth spelling out at length because the underlying throwable often says nothing: a refused
     * loopback connection is a `ConnectException` with a null message.
     */
    private fun unreachable(path: String, cause: Throwable): FieldAudioTransportException {
        // Two different silences to fill. A refused loopback connection is a `ConnectException`
        // whose message is null *and* whose whole cause chain is empty — measured against a closed
        // port, so there is no "Connection refused" text anywhere to quote — which is why it gets a
        // clause of its own. Other transport faults (TLS, DNS) normally do carry a message, though
        // sometimes only on a cause, so take the first one the chain offers.
        val kind = cause::class.simpleName ?: cause::class.java.name
        val detail = generateSequence(cause) { it.cause }
            .take(8)
            .mapNotNull { it.message?.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?: when (cause) {
                is ConnectException -> "nothing accepted the connection"
                is HttpConnectTimeoutException -> "the connection timed out"
                else -> null
            }
        val reason = if (detail == null || detail == kind) kind else "$kind: $detail"
        return FieldAudioTransportException(
            baseUri, path,
            "Field Audio is not answering at $baseUri$path: $reason. The service is a separate " +
                "process — start it from services/field-audio-ai — or construct FieldAudioClient " +
                "with the address of one that is already running.",
            cause,
        )
    }

    companion object {
        /**
         * The exact JSON configuration Field Audio speaks, available without constructing an HTTP
         * client.
         *
         * `ignoreUnknownKeys` is what lets an older Field talk to a newer service; `explicitNulls =
         * false` omits nulls on the way out, which matters because the service *forbids* unknown
         * fields and treats an explicit null differently from an absent one.
         */
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            prettyPrint = false
        }

        private val DEFAULT_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor()
    }
}

/** Global controls exposed by the pinned MusicGen-Style checkpoint. */
data class MusicGenStyleControls(
    /** Sample from the distribution rather than taking the argmax. Off makes output deterministic and dull. */
    val useSampling: Boolean = true,
    /** Keep only the k most likely tokens at each step; 0 disables the cut. Must be non-negative. */
    val topK: Int = 250,
    /** Nucleus sampling threshold within 0..1; 0 disables it. Use one of [topK] and [topP], not both. */
    val topP: Double = 0.0,
    /** Softmax temperature, strictly positive. Above 1 wanders, below 1 commits. */
    val temperature: Double = 1.0,
    /** Classifier-free guidance strength, non-negative. Higher follows the conditioning harder and varies less. */
    val cfg: Double = 3.0,
    /**
     * Balance between the text prompt and the style excerpt, non-negative.
     *
     * Only sent when a prompt is non-blank — with no text there is nothing to balance against.
     */
    val cfgBeta: Double? = 5.0,
    /** Run guidance as two forward passes instead of one batched pass: slower, occasionally different. */
    val twoStepCfg: Boolean = false,
    /**
     * Size of the style bottleneck, strictly positive. Larger keeps more of the source's identity;
     * smaller abstracts it into gesture.
     */
    val styleQuantization: Int = 1,
    /**
     * How much of the source window the style encoder actually reads, in seconds.
     *
     * Positive, and never longer than the window itself — `styleCandidates` checks both. Null means
     * the whole window.
     */
    val styleExcerptSeconds: Double? = null,
    /** Temporal downsampling of the style representation, strictly positive. Null leaves the model's default. */
    val styleDownsampleFactor: Int? = null,
    /** How many EnCodec codebooks the style path uses, strictly positive. Fewer is a coarser style. */
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
