package afield.stdlib.noir.audioai

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The Field Audio service, in this JVM, on a real socket — and nothing else.
 *
 * The seam it exists to test ([FieldAudioClient], [analyzeRenderedAudio]) is HTTP plus one
 * WebSocket, so a stub that is a function call proves nothing: the point of this class is that the
 * *real* client's `java.net.http.HttpClient`, its real JSON, its real socket lifetime and its real
 * `CompletableFuture` plumbing all run unmodified against it. It listens on an ephemeral loopback
 * port and hands back [baseUrl].
 *
 * ### It is modelled on the service, not on the client
 *
 * Every behaviour below was read out of `services/field-audio-ai` (`api.py`, `assets.py`,
 * `jobs.py`, `protocol/models.py`) rather than inferred from the Kotlin, because a stub inferred
 * from the code under test cannot disagree with it:
 *
 * - `POST /v1/assets/import` defaults `role="original"` and creates **no** `canonical_project`
 *   representation. Duration, sample rate and channel count come from the uploaded WAV's own
 *   header, parsed here (`assets.py` uses `soundfile`; when that fails it reports *nothing*, which
 *   is what [omitImportedDuration] models).
 * - `POST /v1/operations` answers **202**, and answers a repeat of an identical request from cache
 *   with `cache_hit = true` on a fresh job that is born `COMPLETE`, having already published its
 *   terminal progress event.
 * - Region validation is the service's, to the service's tolerance:
 *   `start + duration > asset_duration + 1e-9` is a **422** carrying the structured
 *   `{"error": {…}}` envelope. Not-found paths are FastAPI `HTTPException`s, so they carry
 *   `{"detail": …}` and *no* envelope.
 * - Requests are decoded **strictly**. Every service model is a Pydantic `StrictModel`
 *   (`extra="forbid"`), so a field Field invents is a 422 there and a 422 here — see
 *   [protocolViolations].
 * - `AssetRegion.asset_id` must match `^sha256:[0-9a-f]{64}$` and `duration_seconds` must be `> 0`.
 * - The events socket replays history, then streams, then closes 1000 on the first terminal event.
 *
 * ### What it deliberately does not model
 *
 * No models, no `.npz` bytes, no waveform summaries, no candidate generation, no codec, no gesture
 * compilation, no SQLite durability across restarts, and no `canonical_project` derivation. The
 * request hash is a SHA-256 of the request JSON minus `force_regenerate`, which is the same *idea*
 * as `JobSupervisor.request_hash` but not the same string — it does not fold in the backend
 * revision, so a hash from here will never match a hash from the real service. Unknown-job event
 * subscriptions are refused with an HTTP **404 handshake failure** rather than the service's
 * accept-then-`close(4404)`; the client observes a failed `buildAsync` either way, but the close
 * code differs. See `dev_docs/field_audio_service_stub.md`.
 */
class FieldAudioServiceStub : AutoCloseable {

    /** How one submitted operation should behave. Keyed by operation name in [script]. */
    data class ScriptedJob(
        /** Non-terminal narration, emitted in order before the outcome. */
        val progress: List<Pair<JobState, Double>> = listOf(
            JobState.ANALYSING to 0.4,
            JobState.WRITING_ASSETS to 0.86,
        ),
        /** The terminal state to end in. Ignored when [neverFinishes] is set. */
        val outcome: JobState = JobState.COMPLETE,
        /** The service's own explanation, recorded on the job and sent with the terminal event. */
        val failure: StructuredError? = null,
        /** Emit [progress] but never a terminal event, like a worker that wedged. */
        val neverFinishes: Boolean = false,
        /** Reach `COMPLETE` with no `analysis_result_id`, which the client must not silently accept. */
        val withoutResult: Boolean = false,
        /** Wall time between events. Small on purpose; the tests assert ordering, not timing. */
        val stepDelayMillis: Long = 10,
    )

    private val strictJson = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }
    private val wire = FieldAudioClient.json

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val assets = ConcurrentHashMap<String, AudioAsset>()
    private val jobs = ConcurrentHashMap<String, JobRecord>()
    private val analyses = ConcurrentHashMap<String, AnalysisResult>()
    private val cachedByRequestHash = ConcurrentHashMap<String, String>()
    private val history = ConcurrentHashMap<String, MutableList<ProgressEvent>>()
    private val subscribers = ConcurrentHashMap<String, MutableSet<Channel<ProgressEvent>>>()
    private val jobIds = AtomicInteger()

    // ---- observation -------------------------------------------------------------------------

    private val liveSockets = AtomicInteger()
    private val peakSockets = AtomicInteger()
    private val acceptedSockets = AtomicInteger()
    private val inspects = ConcurrentHashMap<String, AtomicInteger>()
    private val submitted = Collections.synchronizedList(mutableListOf<ModelOperation>())
    private val jobsByOperation = ConcurrentHashMap<String, String>()
    private val violations = Collections.synchronizedList(mutableListOf<String>())
    private val imports = AtomicInteger()

    /** Events sockets open right now. A leak shows up here as a number that never falls to zero. */
    val openEventSockets: Int get() = liveSockets.get()

    /** The most sockets ever open at once — one per analysis leg, if the client opens one per leg. */
    val peakEventSockets: Int get() = peakSockets.get()

    /** Events sockets accepted since the stub started, closed or not. One per `watch` call. */
    val eventSocketsOpened: Int get() = acceptedSockets.get()

    /** How many times `GET /v1/jobs/{id}` was asked about this job. A poll that will not stop grows. */
    fun jobPolls(jobId: String): Int = inspects[jobId]?.get() ?: 0

    /** Every operation the client submitted, decoded from the wire, in order. */
    val submittedOperations: List<ModelOperation> get() = submitted.toList()

    /** The job id this stub gave the first submission of [operation], or null if it never arrived. */
    fun jobIdFor(operation: String): String? = jobsByOperation[operation]

    /** How many WAV uploads arrived. Content addressing means a re-import is still an upload. */
    val importCount: Int get() = imports.get()

    /**
     * Requests the *service* would have rejected as malformed — an unknown field, a bad asset id, a
     * non-positive duration. Assert this is empty in any test that claims the seam speaks protocol.
     */
    val protocolViolations: List<String> get() = violations.toList()

    // ---- scripting ---------------------------------------------------------------------------

    private val scripts = ConcurrentHashMap<String, ScriptedJob>()

    /** Script one operation name, e.g. `script("analysis.pitch_poly", ScriptedJob(...))`. */
    fun script(operation: String, job: ScriptedJob) {
        scripts[operation] = job
    }

    /** What an unscripted operation does. */
    var defaultScript: ScriptedJob = ScriptedJob()

    /** Report an imported WAV with no duration, sample rate or channels, as a `soundfile` failure does. */
    var omitImportedDuration: Boolean = false

    /** Report an imported asset with no WAV representation at all. */
    var omitImportedWavRepresentation: Boolean = false

    /** Answer `POST /v1/assets/import` with exactly these bytes, whatever they are. */
    var importResponseOverride: String? = null

    /** Answer `GET /v1/analysis-results/{id}` with exactly these bytes, whatever they are. */
    var analysisResponseOverride: String? = null

    // ---- server ------------------------------------------------------------------------------

    private val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { stubModule() }
        .also { it.start(wait = false) }

    /** Where a [FieldAudioClient] should point to reach this stub. */
    val baseUrl: String = "http://127.0.0.1:" +
        runBlocking { server.engine.resolvedConnectors().first().port }

    override fun close() {
        scope.cancel()
        server.stop(0, 500)
    }

    private fun Application.stubModule() {
        install(WebSockets)

        // The real service closes an unknown job's socket with 4404 *before* accepting it, which the
        // client sees as a failed handshake. Ktor accepts before the handler runs, so refuse the
        // upgrade here instead; the client still sees a failed handshake.
        intercept(ApplicationCallPipeline.Plugins) {
            val path = call.request.path()
            if (path.startsWith("/v1/events/") && !jobs.containsKey(path.removePrefix("/v1/events/"))) {
                call.respondText(
                    """{"detail":"job not found"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )
                finish()
            }
        }

        routing {
            get("/health") {
                call.respondText("""{"ok":true,"version":"stub","project_root":"/dev/null"}""")
            }

            post("/v1/assets/import") {
                imports.incrementAndGet()
                val bytes = call.receive<ByteArray>()
                importResponseOverride?.let {
                    call.respondText(it, ContentType.Application.Json)
                    return@post
                }
                val filename = call.request.queryParameters["filename"] ?: "source.wav"
                call.respondJson(importAsset(bytes, filename), AudioAsset.serializer())
            }

            get("/v1/assets/{id...}") {
                val id = call.parameters.getAll("id")?.joinToString("/").orEmpty()
                val asset = assets[id] ?: return@get call.detail(HttpStatusCode.NotFound, "asset not found")
                call.respondJson(asset, AudioAsset.serializer())
            }

            post("/v1/operations") {
                val body = call.receiveText()
                val operation = try {
                    strictJson.decodeFromString(ModelOperation.serializer(), body)
                } catch (failure: Exception) {
                    violations += "POST /v1/operations rejected: ${failure.message}"
                    return@post call.detail(
                        HttpStatusCode.UnprocessableEntity,
                        "request body is not an OperationRequest: ${failure.message}",
                    )
                }
                submitted += operation
                accept(call, operation)
            }

            get("/v1/jobs/{jobId}") {
                val jobId = call.parameters["jobId"].orEmpty()
                inspects.computeIfAbsent(jobId) { AtomicInteger() }.incrementAndGet()
                val job = jobs[jobId] ?: return@get call.detail(HttpStatusCode.NotFound, "job not found")
                call.respondJson(job, JobRecord.serializer())
            }

            get("/v1/analysis-results/{id}") {
                analysisResponseOverride?.let {
                    call.respondText(it, ContentType.Application.Json)
                    return@get
                }
                val result = analyses[call.parameters["id"]]
                    ?: return@get call.detail(HttpStatusCode.NotFound, "analysis result not found")
                call.respondJson(result, AnalysisResult.serializer())
            }

            webSocket("/v1/events/{jobId}") {
                val jobId = call.parameters["jobId"].orEmpty()
                acceptedSockets.incrementAndGet()
                liveSockets.incrementAndGet().let { open ->
                    peakSockets.updateAndGet { peak -> maxOf(peak, open) }
                }
                val queue = Channel<ProgressEvent>(Channel.UNLIMITED)
                val replay: List<ProgressEvent>
                synchronized(this@FieldAudioServiceStub) {
                    replay = history[jobId]?.toList().orEmpty()
                    subscribers.computeIfAbsent(jobId) {
                        Collections.synchronizedSet(mutableSetOf())
                    } += queue
                }
                try {
                    for (event in replay) {
                        send(Frame.Text(wire.encodeToString(ProgressEvent.serializer(), event)))
                        if (event.state.terminal) {
                            close(CloseReason(CloseReason.Codes.NORMAL, ""))
                            return@webSocket
                        }
                    }
                    while (true) {
                        val event = queue.receive()
                        send(Frame.Text(wire.encodeToString(ProgressEvent.serializer(), event)))
                        if (event.state.terminal) {
                            close(CloseReason(CloseReason.Codes.NORMAL, ""))
                            return@webSocket
                        }
                    }
                } finally {
                    subscribers[jobId]?.remove(queue)
                    queue.close()
                    liveSockets.decrementAndGet()
                }
            }
        }
    }

    // ---- service behaviour -------------------------------------------------------------------

    private fun importAsset(bytes: ByteArray, filename: String): AudioAsset {
        val id = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val header = if (omitImportedDuration) null else wavHeader(bytes)
        val representation = AssetRepresentation(
            id = "rep_" + id.removePrefix("sha256:").take(24),
            assetId = id,
            role = "original",
            mediaType = "audio/wav",
            sampleRate = header?.sampleRate,
            channels = header?.channels,
            frameCount = header?.frames,
            durationSeconds = header?.let { it.frames.toDouble() / it.sampleRate },
            binaryUrl = "/v1/representations/rep_${id.removePrefix("sha256:").take(24)}/content",
            metadata = buildJsonObject { put("original_filename", filename) },
        )
        val asset = AudioAsset(
            id = id,
            byteSize = bytes.size.toLong(),
            createdAt = TIMESTAMP,
            representations = if (omitImportedWavRepresentation) emptyList() else listOf(representation),
        )
        assets[id] = asset
        return asset
    }

    private suspend fun accept(call: ApplicationCall, operation: ModelOperation) {
        val region = operation.inputs.firstOrNull()
            ?: return call.detail(
                HttpStatusCode.UnprocessableEntity,
                "operation requires at least one source region",
            )
        if (!ASSET_ID.matches(region.assetId) || region.durationSeconds <= 0.0) {
            violations += "POST /v1/operations region is not an AssetRegion: $region"
            return call.detail(HttpStatusCode.UnprocessableEntity, "inputs.0 is not a valid AssetRegion")
        }
        val asset = assets[region.assetId]
            ?: return call.detail(HttpStatusCode.NotFound, "asset not found: ${region.assetId}")
        val total = asset.representations.firstNotNullOfOrNull { it.durationSeconds }
        if (total != null && region.startSeconds + region.durationSeconds > total + 1e-9) {
            val end = region.startSeconds + region.durationSeconds
            return call.envelope(
                HttpStatusCode.UnprocessableEntity,
                StructuredError(
                    code = "INVALID_TIME_RANGE",
                    message = "source range [%.6f, %.6f) exceeds asset duration %.6f seconds"
                        .format(region.startSeconds, end, total),
                    details = buildJsonObject {
                        put("asset_id", region.assetId)
                        put("start_seconds", region.startSeconds)
                        put("duration_seconds", region.durationSeconds)
                        put("end_seconds", end)
                        put("asset_duration_seconds", total)
                    },
                ),
            )
        }

        val script = scripts[operation.operation] ?: defaultScript
        val backend = operation.backendPreference ?: "stub.backend"
        val requestHash = requestHash(operation)
        val jobId = "job_%08x".format(jobIds.incrementAndGet())
        jobsByOperation.putIfAbsent(operation.operation, jobId)

        val cached = if (operation.forceRegenerate) null else cachedByRequestHash[requestHash]
        if (cached != null) {
            jobs[jobId] = JobRecord(
                id = jobId, operationId = "op_$jobId", requestHash = requestHash,
                state = JobState.COMPLETE, resolvedBackend = backend, progress = 1.0,
                createdAt = TIMESTAMP, updatedAt = TIMESTAMP,
                analysisResultId = cached, cacheHit = true,
            )
            publish(jobId, JobState.COMPLETE, 1.0, "cached result ready")
            call.respondJson(
                JobAccepted(jobId, JobState.COMPLETE, requestHash, backend, cacheHit = true),
                JobAccepted.serializer(),
                HttpStatusCode.Accepted,
            )
            return
        }

        jobs[jobId] = JobRecord(
            id = jobId, operationId = "op_$jobId", requestHash = requestHash,
            state = JobState.QUEUED, resolvedBackend = backend, progress = 0.0,
            createdAt = TIMESTAMP, updatedAt = TIMESTAMP,
        )
        publish(jobId, JobState.QUEUED, 0.0, "job queued")
        scope.launch { run(jobId, requestHash, region, operation, script) }
        call.respondJson(
            JobAccepted(jobId, JobState.QUEUED, requestHash, backend),
            JobAccepted.serializer(),
            HttpStatusCode.Accepted,
        )
    }

    private suspend fun run(
        jobId: String,
        requestHash: String,
        region: AssetRegion,
        operation: ModelOperation,
        script: ScriptedJob,
    ) {
        for ((state, progress) in script.progress) {
            delay(script.stepDelayMillis)
            update(jobId) { it.copy(state = state, progress = progress) }
            publish(jobId, state, progress, state.name.lowercase())
        }
        if (script.neverFinishes) return
        delay(script.stepDelayMillis)
        if (script.outcome == JobState.COMPLETE && !script.withoutResult) {
            val resultId = "analysis_${jobId.removePrefix("job_")}"
            analyses[resultId] = analysisResult(resultId, jobId, requestHash, region, operation)
            cachedByRequestHash[requestHash] = resultId
            update(jobId) {
                it.copy(state = JobState.COMPLETE, progress = 1.0, analysisResultId = resultId)
            }
        } else {
            update(jobId) {
                it.copy(state = script.outcome, progress = 1.0, error = script.failure)
            }
        }
        publish(jobId, script.outcome, 1.0, script.failure?.message ?: "analysis complete")
    }

    private fun analysisResult(
        resultId: String,
        jobId: String,
        requestHash: String,
        region: AssetRegion,
        operation: ModelOperation,
    ): AnalysisResult {
        val role = operation.operation.replaceFirst("analysis.", "analysis_")
        return AnalysisResult(
            id = resultId,
            operationId = "op_$jobId",
            requestHash = requestHash,
            source = region,
            data = AssetRepresentation(
                id = "rep_${resultId}", assetId = "sha256:" + "0".repeat(64), role = role,
                mediaType = "application/x-npz",
                binaryUrl = "/v1/representations/rep_$resultId/content",
            ),
            createdAt = TIMESTAMP,
        )
    }

    private fun update(jobId: String, edit: (JobRecord) -> JobRecord) {
        jobs.compute(jobId) { _, existing -> existing?.let(edit) }
    }

    private fun publish(jobId: String, state: JobState, progress: Double, message: String) {
        val event: ProgressEvent
        val listeners: List<Channel<ProgressEvent>>
        synchronized(this) {
            val recorded = history.computeIfAbsent(jobId) { Collections.synchronizedList(mutableListOf()) }
            event = ProgressEvent(
                sequence = recorded.size.toLong(), jobId = jobId, state = state,
                progress = progress, message = message, at = TIMESTAMP,
            )
            recorded += event
            listeners = subscribers[jobId]?.toList().orEmpty()
        }
        listeners.forEach { it.trySend(event) }
    }

    private fun requestHash(operation: ModelOperation): String {
        val canonical = wire.encodeToString(
            ModelOperation.serializer(),
            operation.copy(forceRegenerate = false),
        )
        return "sha256:" + MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private suspend fun <T> ApplicationCall.respondJson(
        value: T,
        serializer: KSerializer<T>,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respondText(wire.encodeToString(serializer, value), ContentType.Application.Json, status)

    /** FastAPI's `HTTPException` shape: a `detail`, and no structured error envelope. */
    private suspend fun ApplicationCall.detail(status: HttpStatusCode, message: String) =
        respondText(
            wire.encodeToString(JsonObject.serializer(), buildJsonObject { put("detail", message) }),
            ContentType.Application.Json,
            status,
        )

    /** The service's own error envelope, which is what carries a [StructuredError]. */
    private suspend fun ApplicationCall.envelope(status: HttpStatusCode, error: StructuredError) =
        respondText(
            """{"error":${wire.encodeToString(StructuredError.serializer(), error)}}""",
            ContentType.Application.Json,
            status,
        )

    private companion object {
        /** Fixed so a test can assert on a body without matching a clock. */
        const val TIMESTAMP = "2026-08-14T03:00:00+00:00"
        val ASSET_ID = Regex("^sha256:[0-9a-f]{64}$")
    }
}

/** What a WAV header says about itself, read the way the service's `soundfile` call reads it. */
internal data class StubWavHeader(val sampleRate: Int, val channels: Int, val frames: Long)

/**
 * Parse a RIFF/WAVE header by hand.
 *
 * Deliberately independent of anything in `afield.four.audio` and of the production header reader,
 * so that a test asserting the two agree is cross-validation rather than a tautology.
 */
internal fun wavHeader(bytes: ByteArray): StubWavHeader? {
    if (bytes.size < 44) return null
    if (String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF") return null
    if (String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") return null
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    var offset = 12
    var channels = 0
    var sampleRate = 0
    var bitsPerSample = 0
    var dataBytes = -1L
    while (offset + 8 <= bytes.size) {
        val chunk = String(bytes, offset, 4, Charsets.US_ASCII)
        val declared = buffer.getInt(offset + 4).toLong() and 0xffffffffL
        val body = offset + 8
        val available = (bytes.size - body).toLong()
        when (chunk) {
            "fmt " -> if (available >= 16) {
                channels = buffer.getShort(body + 2).toInt()
                sampleRate = buffer.getInt(body + 4)
                bitsPerSample = buffer.getShort(body + 14).toInt()
            }
            "data" -> dataBytes = minOf(declared, available)
        }
        val advance = minOf(declared, available) + (declared and 1L)
        offset = body + advance.toInt()
        if (advance <= 0L) break
    }
    if (channels <= 0 || sampleRate <= 0 || bitsPerSample <= 0 || dataBytes < 0) return null
    val frameBytes = channels * (bitsPerSample / 8)
    if (frameBytes <= 0) return null
    return StubWavHeader(sampleRate, channels, dataBytes / frameBytes)
}
