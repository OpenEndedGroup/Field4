@file:afield.CompletionCategory("Audio AI")

package afield.stdlib.noir.audioai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * The wire vocabulary of Field Audio — one Kotlin type per JSON object in the pinned service
 * contract, and nothing else.
 *
 * Field Audio runs models out of process: a long-lived Python service owns inference, and a box
 * talks to it over HTTP and one WebSocket through [FieldAudioClient]. These are the values that
 * cross that boundary. Every `SerialName` here is the snake_case name the service uses, and the
 * committed, model-free snapshot of the same contract is published as
 * [the Field Audio service reference](../field_audio_service_reference.md) — if the two disagree,
 * the service is right and this file is stale.
 *
 * ### Three facts that explain the shapes
 *
 * - **Results are assets, not samples.** Everything the service produces is written once, named by
 *   the SHA-256 of its bytes (`sha256:` followed by 64 hex digits) and referred to afterwards by
 *   id. That is why a result type carries an [AssetRepresentation] rather than a `FloatArray`: the
 *   audio is still on the service, and `FieldAudioClient.download` is how bytes reach the JVM.
 * - **Work is a job, not a call.** Submitting an operation returns a [JobAccepted] immediately;
 *   the outcome arrives later as a [JobRecord] you poll or a stream of [ProgressEvent]s you watch.
 * - **Identical requests are not repeated.** The service hashes the request (ignoring
 *   [ModelOperation.forceRegenerate]) into [JobRecord.requestHash] and returns the stored result
 *   when it has seen that hash before, with `cacheHit = true` and a job that is already complete.
 *
 * ### Times, ids and timestamps
 *
 * All times and durations are **seconds** as `Double` unless the property name says otherwise.
 * `createdAt`, `updatedAt` and [ProgressEvent.at] are ISO-8601 UTC timestamp *strings*, kept as
 * text because nothing in Field parses them and a string round-trips through project persistence
 * unchanged. Ids are opaque and prefixed by kind: `sha256:` for assets, `rep_` for
 * representations, `job_`, `op_`, `cset_`, `codec_` and `analysis_` for the rest.
 *
 * The client decodes with `ignoreUnknownKeys`, so a newer service that adds a field will not break
 * an older Field; the reverse is not true, because the service rejects unknown fields it is sent.
 */

/**
 * A time window inside one asset — the unit of "what should the model listen to".
 *
 * This is the input side of every operation: [ModelOperation.inputs] is a list of these, and each
 * result echoes back the region it was computed from. Nothing is clamped, so a window that runs
 * past the end of the asset is an `INVALID_TIME_RANGE` error rather than a shorter answer.
 */
@Serializable
data class AssetRegion(
    /** Content address of the asset to read, of the form `sha256:` plus 64 hex digits. */
    @SerialName("asset_id")
    val assetId: String,
    /** Offset of the window from the start of the asset, in seconds; must be non-negative. */
    @SerialName("start_seconds")
    val startSeconds: Double,
    /**
     * Length of the window in seconds; must be strictly positive.
     *
     * Individual operations narrow this further — `styleCandidates` refuses a source window
     * outside 1.5–4.5 seconds, because that is what the pinned MusicGen-Style checkpoint accepts.
     */
    @SerialName("duration_seconds")
    val durationSeconds: Double,
)

/**
 * One concrete encoding of one asset's bytes: what format they are in, and where to fetch them.
 *
 * An [AudioAsset] is the bytes; a representation is a *view* of them the service has materialised,
 * named by [role]. The roles you will meet are `canonical_project` (the 48 kHz project copy of an
 * import), `model_native` and `audition_proxy` (a generated candidate at the model's own rate, and
 * its 48 kHz playable resample), `splice_preview` (source crossfaded into candidate),
 * `codec_model_input`, `codec_reconstruction_native`, `codec_reconstruction_proxy` and
 * `encodec_tokens` for a codec round trip, and `analysis_…` for an analysis product.
 *
 * The audio properties are null whenever the representation is not audio at all — the token tensor
 * is `application/x-npy`, a Basic Pitch artifact is MIDI — so read them defensively.
 */
@Serializable
data class AssetRepresentation(
    /** Stable representation id: `rep_` followed by a digest of the asset, role and format. */
    val id: String,
    /** Content address of the asset these bytes belong to. */
    @SerialName("asset_id")
    val assetId: String,
    /** What this representation is *for*, as one of the role names described above. */
    val role: String,
    /** IANA media type of the bytes, e.g. `audio/wav` or `application/x-npy`. */
    @SerialName("media_type")
    val mediaType: String,
    /** Sample rate of these bytes in Hz, or null when the representation is not audio. */
    @SerialName("sample_rate")
    val sampleRate: Int? = null,
    /** Channel count of these bytes, or null when the representation is not audio. */
    val channels: Int? = null,
    /** Length in sample frames, or null when the representation is not audio. */
    @SerialName("frame_count")
    val frameCount: Long? = null,
    /** Length in seconds, or null when the representation is not audio. */
    @SerialName("duration_seconds")
    val durationSeconds: Double? = null,
    /**
     * The rate the producing model actually worked at, when [sampleRate] is a resample of it.
     *
     * A 48 kHz `audition_proxy` of a 32 kHz MusicGen output carries `sampleRate = 48000` and
     * `nativeSampleRate = 32000`. That difference is what the candidate board's provenance line
     * reports, and it is the honest answer to "am I listening to the model or to a resampler?".
     */
    @SerialName("native_sample_rate")
    val nativeSampleRate: Int? = null,
    /** How the resample to [sampleRate] was done, when one happened; null when nothing was resampled. */
    @SerialName("resampling_method")
    val resamplingMethod: String? = null,
    /**
     * Service-relative path to the bytes: `/v1/representations/{id}/content`.
     *
     * A path and not an absolute URL on purpose — `FieldAudioClient.download` resolves it against
     * the client's base URI, and the in-box HTML views resolve it against the same-origin
     * `/field-audio` proxy prefix.
     */
    @SerialName("binary_url")
    val binaryUrl: String,
    /**
     * Free-form provenance the producing step attached — backend id and revision, operation and
     * request hash, seed, source asset, decoded WAV header. Nothing in Field interprets it.
     */
    val metadata: JsonObject = JsonObject(emptyMap()),
)

/**
 * An immutable blob of audio on the service, plus every representation of it.
 *
 * Assets are content-addressed and never edited: importing the same file twice yields the same
 * [id], and every derived thing — a proxy, a reconstruction, a token tensor — is a *new* asset
 * rather than a mutation of an old one. `FieldAudioClient.importWav` makes one, `describe` fetches
 * one by id, and `assetByFilename` finds one you imported earlier.
 */
@Serializable
data class AudioAsset(
    /** Content address of the bytes: `sha256:` followed by 64 hex digits. */
    val id: String,
    /** Size of the underlying bytes, in bytes. */
    @SerialName("byte_size")
    val byteSize: Long,
    /** ISO-8601 UTC timestamp of the first time these bytes were stored. */
    @SerialName("created_at")
    val createdAt: String,
    /** Every materialised view of this asset, oldest first. See [AssetRepresentation.role]. */
    val representations: List<AssetRepresentation>,
    /** Free-form provenance recorded at import or generation time; not interpreted by Field. */
    val metadata: JsonObject = JsonObject(emptyMap()),
) {
    /**
     * Duration of the first representation that declares one, in seconds, or null if none does.
     *
     * A convenience for the common single-representation case. When an asset has several
     * representations this is *whichever the service listed first*, not a canonical answer — pick
     * the representation you mean and read [AssetRepresentation.durationSeconds] from it.
     */
    val durationSeconds: Double? get() = representations.firstNotNullOfOrNull { it.durationSeconds }

    /** Sample rate of the first representation that declares one, with the caveat on [durationSeconds]. */
    val sampleRate: Int? get() = representations.firstNotNullOfOrNull { it.sampleRate }

    /** Channel count of the first representation that declares one, with the caveat on [durationSeconds]. */
    val channels: Int? get() = representations.firstNotNullOfOrNull { it.channels }
}

/**
 * A min/max envelope of one representation over one time window — what a waveform display draws.
 *
 * The service scans every frame of the interval in blocks and keeps the extremes, so this is a
 * *transient-preserving* summary rather than a decimation: a single-sample click survives being
 * summarised to 1200 points. Ask for one with `FieldAudioClient.waveform`, or let `AudioVisualizer`
 * fetch them as the user zooms.
 */
@Serializable
data class WaveformSummary(
    /** The representation that was summarised. */
    @SerialName("representation_id")
    val representationId: String,
    /** Start of the summarised window, in seconds from the start of the representation. */
    @SerialName("start_seconds")
    val startSeconds: Double,
    /** Length of the summarised window, in seconds. */
    @SerialName("duration_seconds")
    val durationSeconds: Double,
    /** Sample rate of the underlying audio in Hz — the envelope itself is not sampled at this rate. */
    @SerialName("sample_rate")
    val sampleRate: Int,
    /** Number of channels, and so the outer length of [minimum] and [maximum]. */
    val channels: Int,
    /** Points per channel. Never more than the window's frame count, so a short window returns fewer. */
    @SerialName("point_count")
    val pointCount: Int,
    /** Per-channel minima, indexed `[channel][point]`, nominally in −1..1. */
    val minimum: List<List<Float>>,
    /** Per-channel maxima, indexed `[channel][point]`, nominally in −1..1. */
    val maximum: List<List<Float>>,
)

/**
 * A request for the service to do something — the one type you build by hand, or nearly.
 *
 * The operation vocabulary is deliberately Field's rather than any model library's:
 * `generate.style`, `codec.reconstruct`, `analysis.beats`, `analysis.pitch_mono`,
 * `analysis.pitch_poly`. Prefer the typed constructors — `styleCandidates`, `codecReconstruction`,
 * `multiScaleBeatAnalysis`, `monophonicPitchAnalysis`, `polyphonicPitchAnalysis` — which validate
 * their arguments in the JVM and fill [parameters] with the keys the matching backend expects.
 * Build one of these directly only for an operation that has no constructor yet.
 */
@Serializable
data class ModelOperation(
    /** The dotted operation name, e.g. `generate.style`; an unknown one is `UNSUPPORTED_OPERATION`. */
    val operation: String,
    /** Source windows to work from. Every operation that ships today reads `inputs[0]` and no more. */
    val inputs: List<AssetRegion> = emptyList(),
    /** Operation-specific settings in the service's snake_case keys. The typed constructors fill this. */
    val parameters: JsonObject = JsonObject(emptyMap()),
    /**
     * One RNG seed per candidate, or empty to let the service choose.
     *
     * If non-empty it must hold exactly [candidateCount] entries — the service rejects any other
     * length outright. Seeds are what make a candidate family reproducible: same seeds, same
     * parameters, same audio out, and in fact a cache hit rather than a second run.
     */
    val seeds: List<Long> = emptyList(),
    /** How many alternatives to generate, 1..64. Analysis and codec operations use 1. */
    @SerialName("candidate_count")
    val candidateCount: Int = 8,
    /**
     * Which backend should serve this, e.g. `analysis.librosa` or
     * `audiocraft.encodec_musicgen_style_32khz`. Null lets the service resolve one, and the choice
     * it made comes back as [JobAccepted.resolvedBackend].
     */
    @SerialName("backend_preference")
    val backendPreference: String? = null,
    /** Free-form attribution, carried through to [JobSummary.requestedBy]; useful once a job list gets busy. */
    @SerialName("requested_by")
    val requestedBy: String? = null,
    /**
     * Bypass the result cache and run the model again.
     *
     * It is excluded from the request hash, so setting it does not create a *different* request —
     * it recomputes this one. You want it when the backend changed underneath a hash, and almost
     * never otherwise: a cache hit returns the same bytes in milliseconds.
     */
    @SerialName("force_regenerate")
    val forceRegenerate: Boolean = false,
)

/**
 * Where a job has got to. Most of these are progress narration; four of them are the end.
 *
 * The states are ordinary lifecycle stages — queued, loading the model, preparing audio, encoding,
 * analysing, decoding, generating, writing assets, making proxies — and which of them a given
 * operation passes through depends on the operation. Do not switch on a non-terminal state to
 * decide anything durable; use [terminal], which is what `FieldAudioClient.await` polls on.
 *
 * Cancellation is a request, not a guarantee: a backend that cannot stop answers
 * `CANCEL_UNSUPPORTED`, and one that finishes first answers `COMPLETED_BEFORE_CANCELLATION` —
 * which, unlike `CANCELLED`, means the results exist.
 */
@Serializable
enum class JobState {
    @SerialName("queued") QUEUED,
    @SerialName("loading_model") LOADING_MODEL,
    @SerialName("preparing_audio") PREPARING_AUDIO,
    @SerialName("encoding_source") ENCODING_SOURCE,
    @SerialName("analysing") ANALYSING,
    @SerialName("decoding") DECODING,
    @SerialName("generating") GENERATING,
    @SerialName("writing_assets") WRITING_ASSETS,
    @SerialName("making_proxies") MAKING_PROXIES,
    @SerialName("complete") COMPLETE,
    @SerialName("cancel_requested") CANCEL_REQUESTED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("cancel_unsupported") CANCEL_UNSUPPORTED,
    @SerialName("completed_before_cancellation") COMPLETED_BEFORE_CANCELLATION,
    @SerialName("failed") FAILED;

    /**
     * True once the job will not change again: `COMPLETE`, `CANCELLED`,
     * `COMPLETED_BEFORE_CANCELLATION` or `FAILED`.
     *
     * Terminal does not mean successful — only `COMPLETE` and `COMPLETED_BEFORE_CANCELLATION`
     * leave results behind, which is why the `await…` helpers check for `COMPLETE` explicitly and
     * fail with [FieldAudioJobException] otherwise.
     */
    val terminal: Boolean get() = this in setOf(COMPLETE, CANCELLED, COMPLETED_BEFORE_CANCELLATION, FAILED)
}

/**
 * The immediate answer to submitting a [ModelOperation]: a job exists, and here is how to follow it.
 *
 * This says nothing about the work being done — [state] is usually `QUEUED` — except in the one
 * case that matters for latency: when [cacheHit] is true the job was born `COMPLETE` and the
 * result is already there to fetch.
 */
@Serializable
data class JobAccepted(
    /** Id of the job to poll (`FieldAudioClient.inspect`) or watch (`watch`): `job_` plus a UUID. */
    @SerialName("job_id")
    val jobId: String,
    /** State at acceptance: `QUEUED` for real work, `COMPLETE` when [cacheHit] is true. */
    val state: JobState,
    /** Hash of the submitted request; identical requests share it, which is how caching happens. */
    @SerialName("request_hash")
    val requestHash: String,
    /** The backend the service actually chose, whether or not one was preferred. */
    @SerialName("resolved_backend")
    val resolvedBackend: String,
    /** True when this request had been served before and no model was run. */
    @SerialName("cache_hit")
    val cacheHit: Boolean = false,
)

/**
 * A failure the service could name, as opposed to an HTTP status.
 *
 * It arrives two ways: in the body of a non-2xx response, where `FieldAudioClient` wraps it in a
 * [FieldAudioException], and on [JobRecord.error] for a job that failed after being accepted.
 */
@Serializable
data class StructuredError(
    /**
     * A code from the service's fixed vocabulary — `ASSET_NOT_FOUND`, `INVALID_TIME_RANGE`,
     * `UNSUPPORTED_OPERATION`, `MODEL_NOT_AVAILABLE`, `MODEL_LOAD_FAILED`, `MODEL_OUT_OF_MEMORY`,
     * `GENERATION_FAILED`, `ENCODING_FAILED`, `DECODING_FAILED`, `ANALYSIS_FAILED`,
     * `RESAMPLING_FAILED`, `JOB_CANCELLED`, `WORKER_UNAVAILABLE`, `PROTOCOL_VERSION_MISMATCH`,
     * `CACHE_CORRUPTION`. Typed as a `String` so an unrecognised future code still decodes.
     */
    val code: String,
    /** Human-readable detail. Used as-is in the exception message; not machine-parseable. */
    val message: String,
    /** The service's own hint that retrying unchanged might work. A job that fails mid-run sets it false. */
    val retryable: Boolean = false,
    /** Extra machine-readable context, e.g. the worker exception type. */
    val details: JsonObject = JsonObject(emptyMap()),
)

/**
 * The durable state of one job — the thing polling returns.
 *
 * Which of the three result-id properties is set tells you what kind of work this was, and each is
 * null until the job reaches `COMPLETE`. [JobSummary] is this record plus the two fields the job
 * *list* adds; fetch this one when you have a job id, and that one when you are browsing.
 */
@Serializable
data class JobRecord(
    /** The job id: `job_` plus a UUID. */
    val id: String,
    /** The stored operation this job ran, `op_` plus a UUID. Several jobs share one on a cache hit. */
    @SerialName("operation_id")
    val operationId: String,
    /** Hash of the request; the cache key. */
    @SerialName("request_hash")
    val requestHash: String,
    /** Where the job has got to. Check [JobState.terminal] before reading a result id. */
    val state: JobState,
    /** The backend that ran, or is running, this job. */
    @SerialName("resolved_backend")
    val resolvedBackend: String,
    /**
     * Fraction of the job done, 0..1.
     *
     * Narration, not a schedule: each stage reports whatever it knows, so it moves in jumps and its
     * rate says nothing about the time remaining. A cache hit is stored at 1.0.
     */
    val progress: Double,
    /** ISO-8601 UTC timestamp of when the job was accepted. */
    @SerialName("created_at")
    val createdAt: String,
    /** ISO-8601 UTC timestamp of the last state or progress change. */
    @SerialName("updated_at")
    val updatedAt: String,
    /** Why the job failed, when it did. Null in every other state. */
    val error: StructuredError? = null,
    /** Set when a `generate.*` job completed: fetch it with `FieldAudioClient.candidateSet`. */
    @SerialName("candidate_set_id")
    val candidateSetId: String? = null,
    /** Set when a `codec.*` job completed: fetch it with `FieldAudioClient.codecResult`. */
    @SerialName("codec_result_id")
    val codecResultId: String? = null,
    /** Set when an `analysis.*` job completed: fetch it with `FieldAudioClient.analysisResult`. */
    @SerialName("analysis_result_id")
    val analysisResultId: String? = null,
    /** True when this job was answered from the result cache without running a model. */
    @SerialName("cache_hit")
    val cacheHit: Boolean = false,
)

/**
 * A [JobRecord] as it appears in the job *list*, with the two fields that make a list readable:
 * what was asked for, and by whom.
 *
 * The service models this as a subclass; Kotlin has no such relationship here, so the shared fields
 * are repeated and mean exactly what they mean on [JobRecord]. `FieldAudioClient.jobs` returns
 * these, optionally filtered by state.
 */
@Serializable
data class JobSummary(
    /** The job id: `job_` plus a UUID. */
    val id: String,
    /** The stored operation this job ran. */
    @SerialName("operation_id")
    val operationId: String,
    /** Hash of the request; the cache key. */
    @SerialName("request_hash")
    val requestHash: String,
    /** Where the job has got to. */
    val state: JobState,
    /** The backend that ran, or is running, this job. */
    @SerialName("resolved_backend")
    val resolvedBackend: String,
    /** Fraction of the job done, 0..1; see [JobRecord.progress]. */
    val progress: Double,
    /** ISO-8601 UTC timestamp of when the job was accepted. */
    @SerialName("created_at")
    val createdAt: String,
    /** ISO-8601 UTC timestamp of the last state or progress change. */
    @SerialName("updated_at")
    val updatedAt: String,
    /** Why the job failed, when it did. */
    val error: StructuredError? = null,
    /** Set when a `generate.*` job completed. */
    @SerialName("candidate_set_id")
    val candidateSetId: String? = null,
    /** Set when a `codec.*` job completed. */
    @SerialName("codec_result_id")
    val codecResultId: String? = null,
    /** Set when an `analysis.*` job completed. */
    @SerialName("analysis_result_id")
    val analysisResultId: String? = null,
    /** True when this job was answered from the result cache. */
    @SerialName("cache_hit")
    val cacheHit: Boolean = false,
    /** The dotted operation name that was requested — the column a job list is really scanned by. */
    val operation: String,
    /** Whatever [ModelOperation.requestedBy] was set to, or null. */
    @SerialName("requested_by")
    val requestedBy: String? = null,
)

/**
 * One alternative in a generated family, with its provenance and your verdict on it.
 *
 * A candidate spans three assets, which is the thing to hold on to: [asset] is the model's own
 * output, while the proxy and the splice preview are *separate* derived assets, so
 * [proxyRepresentation] does not belong to [asset]. Audition the proxy — the 48 kHz resample every
 * browser can play — and keep the native for anything that must not be resampled again.
 */
@Serializable
data class Candidate(
    /** The model-native output asset, described in full so no second request is needed. */
    val asset: AudioAsset,
    /** The stored operation that produced the whole family. */
    @SerialName("operation_id")
    val operationId: String,
    /** Position in the family, from zero. The candidate board displays it one-based. */
    val index: Int,
    /** The RNG seed this alternative was generated with — resubmit it to get this take back. */
    val seed: Long,
    /** Representation id of the model's own output, at the model's own sample rate. */
    @SerialName("native_representation_id")
    val nativeRepresentationId: String,
    /** Representation id of the 48 kHz WAV to audition. This is the one to play. */
    @SerialName("proxy_representation_id")
    val proxyRepresentationId: String,
    /** Representation id of the source crossfaded into this candidate, when the backend made one. */
    @SerialName("splice_preview_representation_id")
    val splicePreviewRepresentationId: String? = null,
    /** Inline metadata for [nativeRepresentationId], when the service inlined it. */
    @SerialName("native_representation")
    val nativeRepresentation: AssetRepresentation? = null,
    /**
     * Inline metadata for [proxyRepresentationId], when the service inlined it.
     *
     * `candidateBoardHtml` requires this to be present — it needs the binary URL and the duration
     * to build a player without a round trip — and fails loudly rather than dropping a card.
     */
    @SerialName("proxy_representation")
    val proxyRepresentation: AssetRepresentation? = null,
    /** Inline metadata for [splicePreviewRepresentationId], when there is one. */
    @SerialName("splice_preview_representation")
    val splicePreviewRepresentation: AssetRepresentation? = null,
    /** A backend-computed score, if that backend computes one. Not comparable across backends. */
    val score: Double? = null,
    /** Your rating, as last written back with `FieldAudioClient.judge`. The board's slider spans −1..1. */
    val rating: Double? = null,
    /** Your selection flag, as last written back. */
    val selected: Boolean = false,
    /** Your note, as last written back. */
    val annotation: String? = null,
)

/**
 * The whole family a `generate.*` job produced, in one immutable, re-fetchable value.
 *
 * `candidateBoardHtml` turns one of these into the audition-and-judge board;
 * `FieldAudioClient.awaitCandidates` is the short way from a submitted operation to this.
 */
@Serializable
data class CandidateSet(
    /** Id of the set: `cset_` plus a UUID. */
    val id: String,
    /** The stored operation that produced it. */
    @SerialName("operation_id")
    val operationId: String,
    /** Hash of the request; two identical requests resolve to the same stored set. */
    @SerialName("request_hash")
    val requestHash: String,
    /** The alternatives, in [Candidate.index] order. */
    val candidates: List<Candidate>,
    /** ISO-8601 UTC timestamp of when the set was written. */
    @SerialName("created_at")
    val createdAt: String,
)

/**
 * How far a codec round trip's output is from its input, by three different measures.
 *
 * All three compare the decoded reconstruction against the *model input* — the windowed, resampled
 * audio the codec actually saw — and not against the original import, so they measure the codec
 * rather than the resampler.
 */
@Serializable
data class ReconstructionMetrics(
    /** Root-mean-square error in normalised sample units; 0 is a perfect reconstruction. */
    val rmse: Double,
    /** Signal-to-noise ratio in dB, when the backend reports one. Higher is better. */
    @SerialName("snr_db")
    val snrDb: Double? = null,
    /** Relative spectral distance, when the backend reports one. Lower is better. */
    @SerialName("spectral_convergence")
    val spectralConvergence: Double? = null,
)

/**
 * Everything a `codec.reconstruct` job produced: what went in, the discrete tokens, what came back
 * out, and how far apart the two ends are.
 *
 * This is the value the EnCodec microscope is built from — `FieldAudioClient.codecVisualizationHtml`
 * downloads [modelInput], [reconstructionNative] and [tokens] and renders all three together.
 */
@Serializable
data class CodecReconstructionResult(
    /** Id of the result: `codec_` plus a UUID. */
    val id: String,
    /** The stored operation that produced it. */
    @SerialName("operation_id")
    val operationId: String,
    /** Hash of the request. */
    @SerialName("request_hash")
    val requestHash: String,
    /** The source window that was reconstructed. */
    val source: AssetRegion,
    /** The audio the codec actually saw: the window, resampled to the model's rate. Compare against this. */
    @SerialName("model_input")
    val modelInput: AssetRepresentation,
    /** The discrete codes, as a C-order integer `.npy` tensor — read it with [NpyIntTensor]. */
    val tokens: AssetRepresentation,
    /** The decoded reconstruction at the model's own sample rate. */
    @SerialName("reconstruction_native")
    val reconstructionNative: AssetRepresentation,
    /** The same reconstruction resampled to 48 kHz for audition. */
    @SerialName("reconstruction_proxy")
    val reconstructionProxy: AssetRepresentation,
    /** How close [reconstructionNative] is to [modelInput]. */
    val metrics: ReconstructionMetrics,
    /**
     * The backend's description of the codec itself. `CodecVisualization.load` reads `frame_rate`,
     * `cardinality` and `model_name` out of here, falling back to EnCodec's 50 Hz / 2048 defaults.
     */
    val codec: JsonObject,
    /** ISO-8601 UTC timestamp of when the result was written. */
    @SerialName("created_at")
    val createdAt: String,
)

/**
 * Everything an `analysis.*` job produced — as stored assets, not as numbers.
 *
 * [data] is an `.npz` archive of the full-resolution arrays and [artifacts] holds anything else the
 * backend emitted (a MIDI transcription, for instance). Nothing here is plottable as it stands: for
 * a display, ask for the downsampled [AnalysisPreview] through `FieldAudioClient.analysisPreview`,
 * or hand this whole value to `analysisVisualizationHtml`, which fetches the preview in the
 * browser. Download [data] only when you want the exact arrays in the JVM.
 */
@Serializable
data class AnalysisResult(
    /** Id of the result: `analysis_` plus a UUID. */
    val id: String,
    /** The stored operation that produced it. */
    @SerialName("operation_id")
    val operationId: String,
    /** Hash of the request. */
    @SerialName("request_hash")
    val requestHash: String,
    /** The source window that was analysed. */
    val source: AssetRegion,
    /** The main product: an `.npz` of the analysis arrays, with an `analysis_…` role. */
    val data: AssetRepresentation,
    /** Secondary products — transcribed MIDI, for example. Empty for backends that emit none. */
    val artifacts: List<AssetRepresentation> = emptyList(),
    /** Backend-chosen headline numbers, e.g. an overall tempo estimate. Free-form. */
    val summary: JsonObject = JsonObject(emptyMap()),
    /** The parameters the analysis actually ran with, as the backend resolved them. Free-form. */
    val analysis: JsonObject = JsonObject(emptyMap()),
    /** ISO-8601 UTC timestamp of when the result was written. */
    @SerialName("created_at")
    val createdAt: String,
)

/**
 * One named curve inside an [AnalysisPreview]: a time axis, a value axis, and what to call them.
 *
 * [timesSeconds] and [values] are parallel and the same length. Either can hold nulls, and a null
 * means the underlying array had a non-finite value there — an unvoiced frame in a pYIN F0 track,
 * most often. Skip those points; do not interpolate through them, because "no pitch here" is a fact
 * rather than a gap.
 */
@Serializable
data class AnalysisSeries(
    /** Machine name, e.g. `onset_strength`, `frequency_hz`, `tempo_0_bpm`. */
    val name: String,
    /** Human label for a legend, e.g. `tempo · 4 s`. Falls back to [name]. */
    val label: String,
    /** Unit of [values] where one applies, e.g. `Hz` or `bpm`; null for a dimensionless curve. */
    val unit: String? = null,
    /** Sample times in seconds from the start of the analysed window; null where non-finite. */
    @SerialName("times_seconds")
    val timesSeconds: List<Double?>,
    /** The curve itself, parallel to [timesSeconds]; null where non-finite. */
    val values: List<Double?>,
    /** Per-series extras, e.g. the `scale_seconds` a multi-scale tempo track was measured at. */
    val metadata: JsonObject = JsonObject(emptyMap()),
)

/**
 * One transcribed note from polyphonic analysis: when, how high, how loud.
 *
 * Only `analysis.pitch_poly` produces these; every other analysis leaves [AnalysisPreview.notes]
 * empty.
 */
@Serializable
data class AnalysisNote(
    /** Onset, in seconds from the start of the analysed window. */
    @SerialName("start_seconds")
    val startSeconds: Double,
    /** Offset, in seconds from the start of the analysed window. */
    @SerialName("end_seconds")
    val endSeconds: Double,
    /** MIDI note number — 60 is middle C. */
    val midi: Int,
    /** Note amplitude as the transcriber reported it; the built-in viewer draws it as a 0..1 opacity. */
    val amplitude: Double,
)

/**
 * A plot-sized view of an [AnalysisResult], built server-side so the browser never sees the `.npz`.
 *
 * The service keeps at most `points` evenly-spaced samples per series (16..4096, default 1200),
 * which is why this is safe to fetch for a long recording and why it is *not* the value to measure
 * anything from — download [AnalysisResult.data] for that. Which fields are populated depends on
 * [operation]: `analysis.beats` fills [beatTimesSeconds] and a series per tempo scale,
 * `analysis.pitch_mono` fills F0 and voiced-probability series, and `analysis.pitch_poly` fills
 * [notes] plus two activation series.
 */
@Serializable
data class AnalysisPreview(
    /** The dotted operation this preview came from — the discriminator for everything below. */
    val operation: String,
    /** Length of the analysed window in seconds; the natural x-axis extent. */
    @SerialName("duration_seconds")
    val durationSeconds: Double,
    /** The curves, already downsampled. See [AnalysisSeries] for the null convention. */
    val series: List<AnalysisSeries>,
    /** Detected beat positions in seconds, for `analysis.beats`; empty otherwise. Not downsampled. */
    @SerialName("beat_times_seconds")
    val beatTimesSeconds: List<Double> = emptyList(),
    /** Transcribed notes, for `analysis.pitch_poly`; empty otherwise. */
    val notes: List<AnalysisNote> = emptyList(),
    /** The same headline numbers as [AnalysisResult.summary]. */
    val summary: JsonObject = JsonObject(emptyMap()),
    /** The same resolved parameters as [AnalysisResult.analysis]. */
    val analysis: JsonObject = JsonObject(emptyMap()),
)

/**
 * One frame of a job's live narration, delivered over the WebSocket `FieldAudioClient.watch` opens.
 *
 * Subscribing replays the history first and then streams, so a late subscriber still sees the whole
 * story; the service closes the socket after a terminal event. These are for showing a human what
 * is happening — the durable answer is always the [JobRecord].
 */
@Serializable
data class ProgressEvent(
    /** Monotonically increasing within one job; use it to order or de-duplicate replayed events. */
    val sequence: Long,
    /** The job this event belongs to. */
    @SerialName("job_id")
    val jobId: String,
    /** The state the job entered. */
    val state: JobState,
    /** Fraction done, 0..1; see [JobRecord.progress]. */
    val progress: Double,
    /** A short human-readable note, e.g. "making 48 kHz audition proxy". Null when there is nothing to say. */
    val message: String? = null,
    /** Which candidate this event is about, for a family generated one at a time. */
    @SerialName("candidate_index")
    val candidateIndex: Int? = null,
    /** ISO-8601 UTC timestamp of the event. */
    val at: String,
)

/**
 * What one model can do, as reported by `FieldAudioClient.models` — the honest answer to "can I ask
 * for this?" before submitting anything.
 *
 * A model that is installed but unusable is still listed, with [available] false and
 * [unavailableReason] saying why, so a UI can show the gap rather than silently omit it.
 */
@Serializable
data class ModelCapability(
    /** Backend id: the same string [ModelOperation.backendPreference] takes. */
    @SerialName("model_id")
    val modelId: String,
    /** Checkpoint revision, recorded into the provenance of everything this model produces. */
    val revision: String,
    /** The dotted operations this backend serves. */
    val operations: List<String>,
    /** Sample rates the model works at natively, in Hz. Output is resampled to 48 kHz for audition. */
    @SerialName("native_sample_rates")
    val nativeSampleRates: List<Int>,
    /** Channel layouts supported, each `mono` or `stereo`. */
    val channels: List<String>,
    /** Longest output the model will produce, in seconds; null when unbounded or unknown. */
    @SerialName("max_duration_seconds")
    val maxDurationSeconds: Double? = null,
    /** Whether [ModelOperation.seeds] does anything here. If false, results are not reproducible. */
    @SerialName("supports_seed")
    val supportsSeed: Boolean,
    /** Whether a cancel request can actually stop it, or will end as `CANCEL_UNSUPPORTED`. */
    @SerialName("supports_cancellation")
    val supportsCancellation: Boolean,
    /** The conditioning inputs this model accepts, e.g. a text prompt or a style excerpt. */
    val conditioning: List<String>,
    /** False when the model is known but cannot run right now. */
    val available: Boolean,
    /** Why [available] is false — a missing weight file, an unavailable worker environment. */
    @SerialName("unavailable_reason")
    val unavailableReason: String? = null,
)

/** Your verdict on one candidate, written back with `FieldAudioClient.judge`. Every field is optional. */
@Serializable
data class CandidateJudgement(
    /** How good it is. The candidate board's slider spans −1..1 in steps of 0.1; the service stores what it is sent. */
    val rating: Double? = null,
    /** Whether this take is the keeper. Nothing enforces one selection per set. */
    val selected: Boolean = false,
    /** A free-text note. */
    val annotation: String? = null,
)

/**
 * One operation and everything that came of it, as saved into a Field document.
 *
 * This is the *reference* to the work, not the work: no audio is stored here, only ids and the
 * judgements you made, which is what keeps a Field document small and keeps Python a worker rather
 * than the authority on your project. Reopening a document and re-fetching these ids gets the same
 * bytes back, because assets are immutable and content-addressed.
 */
@Serializable
data class SavedAudioOperation(
    /** The request as submitted — enough to resubmit it exactly. */
    val request: ModelOperation,
    /** What the service said when it accepted it. */
    val accepted: JobAccepted,
    /** The last job state seen, if one was recorded before the document was saved. */
    val job: JobRecord? = null,
    /** The candidate family, for a `generate.*` operation that finished. */
    @SerialName("candidate_set")
    val candidateSet: CandidateSet? = null,
    /** The codec result, for a `codec.*` operation that finished. */
    @SerialName("codec_result")
    val codecResult: CodecReconstructionResult? = null,
    /** The analysis result, for an `analysis.*` operation that finished. */
    @SerialName("analysis_result")
    val analysisResult: AnalysisResult? = null,
    /** Ratings and notes by [Candidate.index], as last saved. */
    val judgements: Map<Int, CandidateJudgement> = emptyMap(),
)

/**
 * The whole Field-side audio project: which assets it refers to, and what was done with them.
 *
 * [FieldAudioPersistence] stores one of these as JSON in a box property, so it travels with the
 * document. Everything in it is an id or a judgement — see [SavedAudioOperation].
 */
@Serializable
data class FieldAudioProjectState(
    /** Format version of this saved state. Bump it when the shape changes incompatibly. */
    val version: Int = 1,
    /** Assets the project refers to, keyed by [AudioAsset.id]. */
    val assets: Map<String, AudioAsset> = emptyMap(),
    /** Operations in the order they were performed. */
    val operations: List<SavedAudioOperation> = emptyList(),
)
