@file:afield.CompletionCategory("Audio AI")

package afield.stdlib.noir.audioai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Multi-scale local tempo trajectories plus a discrete beat grid. */
fun multiScaleBeatAnalysis(
    source: AssetRegion,
    scalesSeconds: List<Double> = listOf(2.0, 4.0, 8.0, 16.0),
    hopLength: Int = 256,
    minBpm: Double = 30.0,
    maxBpm: Double = 300.0,
    backend: String? = "analysis.librosa",
    requestedBy: String? = null,
    forceRegenerate: Boolean = false,
): ModelOperation {
    require(scalesSeconds.isNotEmpty() && scalesSeconds.all { it > 0.0 }) {
        "scalesSeconds must contain positive values"
    }
    require(hopLength > 0) { "hopLength must be positive" }
    require(minBpm > 0.0 && maxBpm > minBpm) { "BPM bounds are invalid" }
    return ModelOperation(
        operation = "analysis.beats",
        inputs = listOf(source),
        parameters = buildJsonObject {
            putJsonArray("scales_seconds") { scalesSeconds.forEach { add(JsonPrimitive(it)) } }
            put("hop_length", hopLength)
            put("min_bpm", minBpm)
            put("max_bpm", maxBpm)
        },
        candidateCount = 1,
        backendPreference = backend,
        requestedBy = requestedBy,
        forceRegenerate = forceRegenerate,
    )
}

/** Frame-wise monophonic F0 and voiced probability from probabilistic YIN. */
fun monophonicPitchAnalysis(
    source: AssetRegion,
    fminHz: Double = 55.0,
    fmaxHz: Double = 1760.0,
    frameLength: Int = 2048,
    hopLength: Int = 256,
    resolution: Double = 0.1,
    backend: String? = "analysis.librosa",
    requestedBy: String? = null,
    forceRegenerate: Boolean = false,
): ModelOperation {
    require(fminHz > 0.0 && fmaxHz > fminHz) { "pitch bounds are invalid" }
    require(frameLength > 0 && hopLength > 0) { "frame and hop lengths must be positive" }
    require(resolution > 0.0) { "resolution must be positive" }
    return ModelOperation(
        operation = "analysis.pitch_mono",
        inputs = listOf(source),
        parameters = buildJsonObject {
            put("fmin_hz", fminHz)
            put("fmax_hz", fmaxHz)
            put("frame_length", frameLength)
            put("hop_length", hopLength)
            put("resolution", resolution)
        },
        candidateCount = 1,
        backendPreference = backend,
        requestedBy = requestedBy,
        forceRegenerate = forceRegenerate,
    )
}

/** Note events, activation tensors, pitch bends, and MIDI from Spotify Basic Pitch. */
fun polyphonicPitchAnalysis(
    source: AssetRegion,
    onsetThreshold: Double = 0.5,
    frameThreshold: Double = 0.3,
    minimumNoteLengthMillis: Double = 127.7,
    minimumFrequencyHz: Double? = null,
    maximumFrequencyHz: Double? = null,
    multiplePitchBends: Boolean = false,
    melodiaTrick: Boolean = true,
    backend: String? = "analysis.basic_pitch",
    requestedBy: String? = null,
    forceRegenerate: Boolean = false,
): ModelOperation {
    require(onsetThreshold in 0.0..1.0 && frameThreshold in 0.0..1.0) {
        "activation thresholds must be between zero and one"
    }
    require(minimumNoteLengthMillis >= 0.0) { "minimum note length must be non-negative" }
    require(minimumFrequencyHz == null || minimumFrequencyHz > 0.0) {
        "minimum frequency must be positive"
    }
    require(maximumFrequencyHz == null || maximumFrequencyHz > 0.0) {
        "maximum frequency must be positive"
    }
    return ModelOperation(
        operation = "analysis.pitch_poly",
        inputs = listOf(source),
        parameters = buildJsonObject {
            put("onset_threshold", onsetThreshold)
            put("frame_threshold", frameThreshold)
            put("minimum_note_length_ms", minimumNoteLengthMillis)
            minimumFrequencyHz?.let { put("minimum_frequency_hz", it) }
            maximumFrequencyHz?.let { put("maximum_frequency_hz", it) }
            put("multiple_pitch_bends", multiplePitchBends)
            put("melodia_trick", melodiaTrick)
        },
        candidateCount = 1,
        backendPreference = backend,
        requestedBy = requestedBy,
        forceRegenerate = forceRegenerate,
    )
}
