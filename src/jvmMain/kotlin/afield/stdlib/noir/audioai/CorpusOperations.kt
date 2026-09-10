@file:afield.CompletionCategory("Audio AI")

package afield.stdlib.noir.audioai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.abs

/**
 * Multi-scale corpus operations: segment a source into overlapping windows, embed each window
 * with grouped audio descriptors, and retrieve neighbours by weighted distance.
 *
 * The three operations form a pipeline that runs server-side. [segmentCorpus] enumerates
 * deterministic time windows at several scales without computing any descriptors. [embedCorpus]
 * computes and persists the five grouped descriptor families (harmony, pitch, texture, rhythm,
 * dynamics) for every window. [findCorpusNeighbours] retrieves the closest embedded windows
 * to a query using [CorpusDistanceWeights], which controls how much each group contributes to
 * the distance. All three produce a [ModelOperation] that [FieldAudioClient.submit] executes.
 */

/** Explicit weights for the five interpretable descriptor groups used by corpus retrieval. */
data class CorpusDistanceWeights(
    val harmony: Double = 1.0,
    val pitch: Double = 0.7,
    val texture: Double = 1.0,
    val rhythm: Double = 0.8,
    val dynamics: Double = 0.45,
) {
    init {
        require(listOf(harmony, pitch, texture, rhythm, dynamics).all { it.isFinite() && it >= 0.0 }) {
            "corpus distance weights must be finite and non-negative"
        }
        require(harmony + pitch + texture + rhythm + dynamics > 0.0) {
            "at least one corpus distance weight must be positive"
        }
    }
}

private fun corpusWindowOperation(
    operation: String,
    source: AssetRegion,
    scalesSeconds: List<Double>,
    overlap: Double,
    hopLength: Int,
    nFft: Int,
    backend: String?,
    requestedBy: String?,
    forceRegenerate: Boolean,
): ModelOperation {
    require(scalesSeconds.isNotEmpty() && scalesSeconds.all { it.isFinite() && it > 0.0 }) {
        "scalesSeconds must contain positive finite values"
    }
    require(overlap >= 0.0 && overlap < 1.0) { "overlap must be in [0, 1)" }
    require(hopLength > 0 && nFft > 0) { "hopLength and nFft must be positive" }
    return ModelOperation(
        operation = operation,
        inputs = listOf(source),
        parameters = buildJsonObject {
            putJsonArray("scales_seconds") { scalesSeconds.forEach { add(JsonPrimitive(it)) } }
            put("overlap", overlap)
            put("hop_length", hopLength)
            put("n_fft", nFft)
        },
        candidateCount = 1,
        backendPreference = backend,
        requestedBy = requestedBy,
        forceRegenerate = forceRegenerate,
    )
}

/** Enumerate deterministic source-adjacent windows without computing descriptors. */
fun segmentCorpus(
    source: AssetRegion,
    scalesSeconds: List<Double> = listOf(1.5, 3.0, 6.0, 12.0),
    overlap: Double = 0.5,
    backend: String? = "analysis.librosa",
    requestedBy: String? = null,
    forceRegenerate: Boolean = false,
): ModelOperation = corpusWindowOperation(
    "analysis.segment", source, scalesSeconds, overlap, 512, 2048,
    backend, requestedBy, forceRegenerate,
)

/** Compute and persist grouped descriptors for every multi-scale source window. */
fun embedCorpus(
    source: AssetRegion,
    scalesSeconds: List<Double> = listOf(1.5, 3.0, 6.0, 12.0),
    overlap: Double = 0.5,
    hopLength: Int = 512,
    nFft: Int = 2048,
    backend: String? = "analysis.librosa",
    requestedBy: String? = null,
    forceRegenerate: Boolean = false,
): ModelOperation = corpusWindowOperation(
    "analysis.embed", source, scalesSeconds, overlap, hopLength, nFft,
    backend, requestedBy, forceRegenerate,
)

/** Retrieve indexed windows by an explicit weighted descriptor distance. */
fun findCorpusNeighbours(
    source: AssetRegion,
    scaleSeconds: Double = source.durationSeconds,
    limit: Int = 12,
    weights: CorpusDistanceWeights = CorpusDistanceWeights(),
    includeSameAsset: Boolean = true,
    hopLength: Int = 512,
    nFft: Int = 2048,
    backend: String? = "analysis.librosa",
    requestedBy: String? = null,
    forceRegenerate: Boolean = false,
): ModelOperation {
    require(scaleSeconds > 0.0 && scaleSeconds.isFinite()) { "scaleSeconds must be positive" }
    require(abs(scaleSeconds - source.durationSeconds) < 1e-6) {
        "the query duration must equal scaleSeconds"
    }
    require(limit in 1..64) { "limit must be between 1 and 64" }
    require(hopLength > 0 && nFft > 0) { "hopLength and nFft must be positive" }
    return ModelOperation(
        operation = "analysis.find_neighbours",
        inputs = listOf(source),
        parameters = buildJsonObject {
            put("scale_seconds", scaleSeconds)
            put("limit", limit)
            put("include_same_asset", includeSameAsset)
            put("hop_length", hopLength)
            put("n_fft", nFft)
            putJsonObject("weights") {
                put("harmony", weights.harmony)
                put("pitch", weights.pitch)
                put("texture", weights.texture)
                put("rhythm", weights.rhythm)
                put("dynamics", weights.dynamics)
            }
        },
        candidateCount = 1,
        backendPreference = backend,
        requestedBy = requestedBy,
        forceRegenerate = forceRegenerate,
    )
}
