package afield.stdlib.noir.audioai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AssetRegion(
    @SerialName("asset_id") val assetId: String,
    @SerialName("start_seconds") val startSeconds: Double,
    @SerialName("duration_seconds") val durationSeconds: Double,
)

@Serializable
data class AssetRepresentation(
    val id: String,
    @SerialName("asset_id") val assetId: String,
    val role: String,
    @SerialName("media_type") val mediaType: String,
    @SerialName("sample_rate") val sampleRate: Int? = null,
    val channels: Int? = null,
    @SerialName("frame_count") val frameCount: Long? = null,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
    @SerialName("native_sample_rate") val nativeSampleRate: Int? = null,
    @SerialName("resampling_method") val resamplingMethod: String? = null,
    @SerialName("binary_url") val binaryUrl: String,
    val metadata: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class AudioAsset(
    val id: String,
    @SerialName("byte_size") val byteSize: Long,
    @SerialName("created_at") val createdAt: String,
    val representations: List<AssetRepresentation>,
    val metadata: JsonObject = JsonObject(emptyMap()),
) {
    val durationSeconds: Double? get() = representations.firstNotNullOfOrNull { it.durationSeconds }
    val sampleRate: Int? get() = representations.firstNotNullOfOrNull { it.sampleRate }
    val channels: Int? get() = representations.firstNotNullOfOrNull { it.channels }
}

@Serializable
data class WaveformSummary(
    @SerialName("representation_id") val representationId: String,
    @SerialName("start_seconds") val startSeconds: Double,
    @SerialName("duration_seconds") val durationSeconds: Double,
    @SerialName("sample_rate") val sampleRate: Int,
    val channels: Int,
    @SerialName("point_count") val pointCount: Int,
    val minimum: List<List<Float>>,
    val maximum: List<List<Float>>,
)

@Serializable
enum class GestureDelivery {
    @SerialName("direct") DIRECT,
    @SerialName("compiled") COMPILED,
    @SerialName("evaluative") EVALUATIVE,
    @SerialName("transformative") TRANSFORMATIVE,
}

@Serializable
enum class GestureCurveKind {
    @SerialName("density") DENSITY,
    @SerialName("activity") ACTIVITY,
    @SerialName("register") REGISTER,
    @SerialName("brightness") BRIGHTNESS,
}

@Serializable
data class GesturePoint(
    @SerialName("time_seconds") val timeSeconds: Double,
    val value: Double,
)

@Serializable
data class GestureCurve(
    val name: String,
    val kind: GestureCurveKind,
    val points: List<GesturePoint>,
    val weight: Double = 1.0,
    val delivery: GestureDelivery = GestureDelivery.EVALUATIVE,
)

@Serializable
data class GestureEvent(
    @SerialName("time_seconds") val timeSeconds: Double,
    val label: String,
    val strength: Double = 1.0,
    val weight: Double = 1.0,
    val delivery: GestureDelivery = GestureDelivery.EVALUATIVE,
)

@Serializable
data class GesturePromptRegion(
    @SerialName("start_seconds") val startSeconds: Double,
    @SerialName("duration_seconds") val durationSeconds: Double,
    val text: String,
    val weight: Double = 1.0,
    val delivery: GestureDelivery = GestureDelivery.COMPILED,
)

@Serializable
data class GestureBoard(
    @SerialName("duration_seconds") val durationSeconds: Double,
    @SerialName("base_prompt") val basePrompt: String = "",
    @SerialName("prompt_regions") val promptRegions: List<GesturePromptRegion> = emptyList(),
    val events: List<GestureEvent> = emptyList(),
    val curves: List<GestureCurve> = emptyList(),
    val metadata: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class GestureBinding(
    val name: String,
    val kind: String,
    val delivery: GestureDelivery,
    val target: String,
    val explanation: String,
)

@Serializable
data class GestureCompileRequest(
    val board: GestureBoard,
    val operation: String = "generate.style",
    val parameters: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class GestureCompilation(
    val board: GestureBoard,
    val operation: String,
    @SerialName("effective_prompt") val effectivePrompt: String,
    val parameters: JsonObject,
    val bindings: List<GestureBinding>,
)

@Serializable
data class GestureEvaluation(
    @SerialName("overall_score") val overallScore: Double,
    @SerialName("lane_scores") val laneScores: Map<String, Double> = emptyMap(),
    @SerialName("feature_summary") val featureSummary: Map<String, Double> = emptyMap(),
)

@Serializable
data class ModelOperation(
    val operation: String,
    val inputs: List<AssetRegion> = emptyList(),
    val parameters: JsonObject = JsonObject(emptyMap()),
    val seeds: List<Long> = emptyList(),
    @SerialName("candidate_count") val candidateCount: Int = 8,
    @SerialName("backend_preference") val backendPreference: String? = null,
    @SerialName("requested_by") val requestedBy: String? = null,
    @SerialName("gesture_board") val gestureBoard: GestureBoard? = null,
    @SerialName("force_regenerate") val forceRegenerate: Boolean = false,
)

@Serializable
enum class JobState {
    @SerialName("queued") QUEUED,
    @SerialName("loading_model") LOADING_MODEL,
    @SerialName("preparing_audio") PREPARING_AUDIO,
    @SerialName("encoding_source") ENCODING_SOURCE,
    @SerialName("analysing") ANALYSING,
    @SerialName("decoding") DECODING,
    @SerialName("generating") GENERATING,
    @SerialName("evaluating_gestures") EVALUATING_GESTURES,
    @SerialName("writing_assets") WRITING_ASSETS,
    @SerialName("making_proxies") MAKING_PROXIES,
    @SerialName("complete") COMPLETE,
    @SerialName("cancel_requested") CANCEL_REQUESTED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("cancel_unsupported") CANCEL_UNSUPPORTED,
    @SerialName("completed_before_cancellation") COMPLETED_BEFORE_CANCELLATION,
    @SerialName("failed") FAILED;

    val terminal: Boolean get() = this in setOf(COMPLETE, CANCELLED, COMPLETED_BEFORE_CANCELLATION, FAILED)
}

@Serializable
data class JobAccepted(
    @SerialName("job_id") val jobId: String,
    val state: JobState,
    @SerialName("request_hash") val requestHash: String,
    @SerialName("resolved_backend") val resolvedBackend: String,
    @SerialName("cache_hit") val cacheHit: Boolean = false,
)

@Serializable
data class StructuredError(
    val code: String,
    val message: String,
    val retryable: Boolean = false,
    val details: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class JobRecord(
    val id: String,
    @SerialName("operation_id") val operationId: String,
    @SerialName("request_hash") val requestHash: String,
    val state: JobState,
    @SerialName("resolved_backend") val resolvedBackend: String,
    val progress: Double,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val error: StructuredError? = null,
    @SerialName("candidate_set_id") val candidateSetId: String? = null,
    @SerialName("codec_result_id") val codecResultId: String? = null,
    @SerialName("analysis_result_id") val analysisResultId: String? = null,
    @SerialName("cache_hit") val cacheHit: Boolean = false,
)

@Serializable
data class JobSummary(
    val id: String,
    @SerialName("operation_id") val operationId: String,
    @SerialName("request_hash") val requestHash: String,
    val state: JobState,
    @SerialName("resolved_backend") val resolvedBackend: String,
    val progress: Double,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    val error: StructuredError? = null,
    @SerialName("candidate_set_id") val candidateSetId: String? = null,
    @SerialName("codec_result_id") val codecResultId: String? = null,
    @SerialName("analysis_result_id") val analysisResultId: String? = null,
    @SerialName("cache_hit") val cacheHit: Boolean = false,
    val operation: String,
    @SerialName("requested_by") val requestedBy: String? = null,
)

@Serializable
data class Candidate(
    val asset: AudioAsset,
    @SerialName("operation_id") val operationId: String,
    val index: Int,
    val seed: Long,
    @SerialName("native_representation_id") val nativeRepresentationId: String,
    @SerialName("proxy_representation_id") val proxyRepresentationId: String,
    @SerialName("splice_preview_representation_id") val splicePreviewRepresentationId: String? = null,
    @SerialName("native_representation") val nativeRepresentation: AssetRepresentation? = null,
    @SerialName("proxy_representation") val proxyRepresentation: AssetRepresentation? = null,
    @SerialName("splice_preview_representation") val splicePreviewRepresentation: AssetRepresentation? = null,
    val score: Double? = null,
    @SerialName("gesture_evaluation") val gestureEvaluation: GestureEvaluation? = null,
    val rating: Double? = null,
    val selected: Boolean = false,
    val annotation: String? = null,
)

@Serializable
data class CandidateSet(
    val id: String,
    @SerialName("operation_id") val operationId: String,
    @SerialName("request_hash") val requestHash: String,
    val candidates: List<Candidate>,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class ReconstructionMetrics(
    val rmse: Double,
    @SerialName("snr_db") val snrDb: Double? = null,
    @SerialName("spectral_convergence") val spectralConvergence: Double? = null,
)

@Serializable
data class CodecReconstructionResult(
    val id: String,
    @SerialName("operation_id") val operationId: String,
    @SerialName("request_hash") val requestHash: String,
    val source: AssetRegion,
    @SerialName("model_input") val modelInput: AssetRepresentation,
    val tokens: AssetRepresentation,
    @SerialName("reconstruction_native") val reconstructionNative: AssetRepresentation,
    @SerialName("reconstruction_proxy") val reconstructionProxy: AssetRepresentation,
    val metrics: ReconstructionMetrics,
    val codec: JsonObject,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class AnalysisResult(
    val id: String,
    @SerialName("operation_id") val operationId: String,
    @SerialName("request_hash") val requestHash: String,
    val source: AssetRegion,
    val data: AssetRepresentation,
    val artifacts: List<AssetRepresentation> = emptyList(),
    val summary: JsonObject = JsonObject(emptyMap()),
    val analysis: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class AnalysisSeries(
    val name: String,
    val label: String,
    val unit: String? = null,
    @SerialName("times_seconds") val timesSeconds: List<Double?>,
    val values: List<Double?>,
    val metadata: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class AnalysisNote(
    @SerialName("start_seconds") val startSeconds: Double,
    @SerialName("end_seconds") val endSeconds: Double,
    val midi: Int,
    val amplitude: Double,
)

@Serializable
data class AnalysisPreview(
    val operation: String,
    @SerialName("duration_seconds") val durationSeconds: Double,
    val series: List<AnalysisSeries>,
    @SerialName("beat_times_seconds") val beatTimesSeconds: List<Double> = emptyList(),
    val notes: List<AnalysisNote> = emptyList(),
    val summary: JsonObject = JsonObject(emptyMap()),
    val analysis: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class ProgressEvent(
    val sequence: Long,
    @SerialName("job_id") val jobId: String,
    val state: JobState,
    val progress: Double,
    val message: String? = null,
    @SerialName("candidate_index") val candidateIndex: Int? = null,
    val at: String,
)

@Serializable
data class ModelCapability(
    @SerialName("model_id") val modelId: String,
    val revision: String,
    val operations: List<String>,
    @SerialName("native_sample_rates") val nativeSampleRates: List<Int>,
    val channels: List<String>,
    @SerialName("max_duration_seconds") val maxDurationSeconds: Double? = null,
    @SerialName("supports_seed") val supportsSeed: Boolean,
    @SerialName("supports_cancellation") val supportsCancellation: Boolean,
    val conditioning: List<String>,
    val available: Boolean,
    @SerialName("unavailable_reason") val unavailableReason: String? = null,
)

@Serializable
data class CandidateJudgement(
    val rating: Double? = null,
    val selected: Boolean = false,
    val annotation: String? = null,
)

@Serializable
data class SavedAudioOperation(
    val request: ModelOperation,
    val accepted: JobAccepted,
    val job: JobRecord? = null,
    @SerialName("candidate_set") val candidateSet: CandidateSet? = null,
    @SerialName("codec_result") val codecResult: CodecReconstructionResult? = null,
    @SerialName("analysis_result") val analysisResult: AnalysisResult? = null,
    val judgements: Map<Int, CandidateJudgement> = emptyMap(),
)

@Serializable
data class FieldAudioProjectState(
    val version: Int = 1,
    val assets: Map<String, AudioAsset> = emptyMap(),
    val operations: List<SavedAudioOperation> = emptyList(),
)
