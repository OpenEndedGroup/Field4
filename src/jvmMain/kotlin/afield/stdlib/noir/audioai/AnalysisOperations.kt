@file:afield.CompletionCategory("Audio AI")

package afield.stdlib.noir.audioai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Typed constructors for model-free musical analysis and learned speech representations.
 *
 * Each one is a [ModelOperation] factory and nothing more — it validates its arguments here in the
 * JVM, names the backend that serves it, and packs the parameters into the snake_case keys that
 * backend expects. Submitting it, waiting for it and drawing it are `FieldAudioClient`'s job.
 *
 * Every constructor asks for a single candidate, because an analysis of a fixed window with fixed parameters
 * has one answer; the seed family in `styleCandidates` has no counterpart here. All three also
 * produce an `.npz` of full-resolution arrays plus, for a drawing, a downsampled
 * [AnalysisPreview] — see `analysisVisualizationHtml`.
 *
 * Which to reach for: `multiScaleBeatAnalysis` when you want *when*, and at several time scales at
 * once, because a tempo estimate is only meaningful relative to a window length;
 * `monophonicPitchAnalysis` when you know there is one voice and want a continuous F0 with a voiced
 * probability; `polyphonicPitchAnalysis` when there are chords and you want discrete note events
 * (and MIDI) rather than a curve.
 */

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

/**
 * Short-time spectral shape for mel-transport, log-mel, and cepstral spaces.
 *
 * The worker retains one normalized mel distribution, one absolute log-mel vector and one MFCC
 * vector per frame. Field chooses temporal pooling, distance, and projection later without rerunning
 * this cached operation. The default 882-sample hop at 22.05 kHz is exactly 25 frames per second.
 */
fun spectralEmbeddingAnalysis(
    source: AssetRegion,
    hopLength: Int = 882,
    nFft: Int = 2048,
    nMels: Int = 64,
    nMfcc: Int = 20,
    fminHz: Double = 30.0,
    fmaxHz: Double? = null,
    backend: String? = "analysis.librosa",
    requestedBy: String? = null,
    forceRegenerate: Boolean = false,
): ModelOperation {
    require(hopLength > 0 && nFft > 0) { "hopLength and nFft must be positive" }
    require(nMels >= 4) { "nMels must be at least 4" }
    require(nMfcc in 1..nMels) { "nMfcc must be between 1 and nMels" }
    require(fminHz >= 0.0) { "fminHz must be non-negative" }
    require(fmaxHz == null || fmaxHz > fminHz) { "fmaxHz must be greater than fminHz" }
    return ModelOperation(
        operation = "analysis.embed_spectral",
        inputs = listOf(source),
        parameters = buildJsonObject {
            put("hop_length", hopLength)
            put("n_fft", nFft)
            put("n_mels", nMels)
            put("n_mfcc", nMfcc)
            put("fmin_hz", fminHz)
            fmaxHz?.let { put("fmax_hz", it) }
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

/** Instrument groups learned by MuScriptor 0.3.0 and accepted as hard transcription constraints. */
enum class MuscriptorInstrument(val id: String) {
    ACOUSTIC_PIANO("acoustic_piano"),
    ELECTRIC_PIANO("electric_piano"),
    CHROMATIC_PERCUSSION("chromatic_percussion"),
    ORGAN("organ"),
    ACOUSTIC_GUITAR("acoustic_guitar"),
    CLEAN_ELECTRIC_GUITAR("clean_electric_guitar"),
    DISTORTED_ELECTRIC_GUITAR("distorted_electric_guitar"),
    ACOUSTIC_BASS("acoustic_bass"),
    ELECTRIC_BASS("electric_bass"),
    VIOLIN("violin"),
    VIOLA("viola"),
    CELLO("cello"),
    CONTRABASS("contrabass"),
    ORCHESTRAL_HARP("orchestral_harp"),
    TIMPANI("timpani"),
    STRING_ENSEMBLE("string_ensemble"),
    SYNTH_STRINGS("synth_strings"),
    VOICE("voice"),
    ORCHESTRA_HIT("orchestra_hit"),
    TRUMPET("trumpet"),
    TROMBONE("trombone"),
    TUBA("tuba"),
    FRENCH_HORN("french_horn"),
    BRASS_SECTION("brass_section"),
    SOPRANO_AND_ALTO_SAX("soprano_and_alto_sax"),
    TENOR_SAX("tenor_sax"),
    BARITONE_SAX("baritone_sax"),
    OBOE("oboe"),
    ENGLISH_HORN("english_horn"),
    BASSOON("bassoon"),
    CLARINET("clarinet"),
    FLUTES("flutes"),
    SYNTH_LEAD("synth_lead"),
    SYNTH_PAD("synth_pad"),
    DRUMS("drums"),
}

/** Revision-pinned MuScriptor model exposed behind the model-neutral music-transcription operation. */
enum class MuscriptorBackend(val modelId: String) {
    /** Roughly 100M parameters; the practical first choice and viable on lower-resource machines. */
    SMALL("analysis.muscriptor.small"),

    /** Roughly 300M parameters; upstream's default quality/footprint compromise. */
    MEDIUM("analysis.muscriptor.medium"),

    /** Roughly 1.4B parameters; highest accuracy and the largest GPU/latency cost. */
    LARGE("analysis.muscriptor.large"),
}

/** What MuScriptor should do when deriving MIDI tempo and meter from the selected audio. */
enum class MuscriptorTempoDetection {
    /** Fail the job when a stable beat grid cannot be recovered. */
    REQUIRED,

    /** Try, but retain unquantized notes and placeholder MIDI timing when detection fails. */
    BEST_EFFORT,

    /** Do not run the separate beat tracker. */
    OFF,
}

/**
 * Multi-instrument note transcription with immutable MIDI and structured-note artifacts.
 *
 * MuScriptor consumes mono 16 kHz audio internally in five-second chunks. Its note vocabulary
 * carries onset, offset, MIDI pitch, and one of its instrument groups, but not velocity. Supplying
 * [instruments] is a hard constraint and can stabilize attribution when the expected ensemble is
 * known. Prelude forcing preserves held notes across chunk boundaries and therefore requires an
 * effective batch size of one.
 */
fun musicTranscriptionAnalysis(
    source: AssetRegion,
    backend: MuscriptorBackend = MuscriptorBackend.SMALL,
    instruments: List<MuscriptorInstrument> = emptyList(),
    useSampling: Boolean = false,
    temperature: Double = 1.0,
    cfgCoefficient: Double = 1.0,
    beamSize: Int = 1,
    preludeForcing: Boolean = true,
    batchSize: Int? = null,
    strictEos: Boolean = false,
    tempoDetection: MuscriptorTempoDetection = MuscriptorTempoDetection.BEST_EFFORT,
    includeQuantizedMidi: Boolean = true,
    requestedBy: String? = null,
    forceRegenerate: Boolean = false,
): ModelOperation {
    require(temperature.isFinite() && temperature > 0.0) { "temperature must be positive and finite" }
    require(cfgCoefficient.isFinite() && cfgCoefficient > 0.0) {
        "cfgCoefficient must be positive and finite"
    }
    require(beamSize >= 1) { "beamSize must be at least one" }
    require(batchSize == null || batchSize >= 1) { "batchSize must be positive when supplied" }
    require(!preludeForcing || batchSize == null || batchSize == 1) {
        "batchSize greater than one requires preludeForcing=false"
    }
    return ModelOperation(
        operation = "analysis.transcribe_music",
        inputs = listOf(source),
        parameters = buildJsonObject {
            putJsonArray("instruments") { instruments.forEach { add(JsonPrimitive(it.id)) } }
            put("use_sampling", useSampling)
            put("temperature", temperature)
            put("cfg_coef", cfgCoefficient)
            put("beam_size", beamSize)
            put("prelude_forcing", preludeForcing)
            batchSize?.let { put("batch_size", it) }
            put("strict_eos", strictEos)
            when (tempoDetection) {
                MuscriptorTempoDetection.REQUIRED -> put("detect_tempo", true)
                MuscriptorTempoDetection.BEST_EFFORT -> put("detect_tempo", "best-effort")
                MuscriptorTempoDetection.OFF -> put("detect_tempo", false)
            }
            put("include_quantized_midi", includeQuantizedMidi)
        },
        candidateCount = 1,
        backendPreference = backend.modelId,
        requestedBy = requestedBy,
        forceRegenerate = forceRegenerate,
    )
}

/** The performance-timed MIDI emitted by [musicTranscriptionAnalysis]. */
fun AnalysisResult.musicTranscriptionMidi(): AssetRepresentation =
    artifacts.firstOrNull { it.role == "music_transcription_midi" }
        ?: error("analysis result has no MuScriptor MIDI artifact")

/** Beat-quantized MIDI, present only when requested and a usable subdivision was detected. */
fun AnalysisResult.musicTranscriptionQuantizedMidiOrNull(): AssetRepresentation? =
    artifacts.firstOrNull { it.role == "music_transcription_quantized_midi" }

/** Full onset/offset/pitch/instrument event document in the versioned binary interchange format. */
fun AnalysisResult.musicTranscriptionMessagePack(): AssetRepresentation =
    artifacts.firstOrNull { it.role == "music_transcription_messagepack" }
        ?: error("analysis result has no MuScriptor MessagePack artifact")

/** The two revision-pinned learned speech spaces exposed behind one model-neutral operation. */
enum class SpeechEmbeddingBackend(val modelId: String) {
    /** Prosody, speaker affect, vocal texture, and other emotion-oriented acoustic content. */
    EMOTION2VEC("analysis.emotion2vec"),

    /** General self-supervised speech representation: voice, timbre, and increasingly phonetic content by layer. */
    WAVLM_BASE_PLUS("analysis.wavlm_base_plus"),
}

/**
 * A 50 Hz learned speech trajectory, explicit voice activity, novelty, and a reproducible four-axis MDS map.
 *
 * [wavlmLayer] is used only by WavLM. Earlier layers are more acoustic; middle layers are a useful
 * voice/timbre/phonetic compromise; later layers increasingly reflect speech-unit structure. Full
 * frame embeddings remain in the result NPZ while the four MDS coordinates form the interactive view.
 */
fun speechEmbeddingAnalysis(
    source: AssetRegion,
    backend: SpeechEmbeddingBackend,
    wavlmLayer: Int = 6,
    chunkSeconds: Double = 20.0,
    contextSeconds: Double = 1.0,
    mdsDimensions: Int = 4,
    mdsLandmarks: Int = 384,
    mdsSmoothingMillis: Double = 80.0,
    requestedBy: String? = null,
    forceRegenerate: Boolean = false,
): ModelOperation {
    require(wavlmLayer in -13..12) { "wavlmLayer must select one of WavLM's 13 hidden states" }
    require(chunkSeconds in 2.0..120.0) { "chunkSeconds must be between 2 and 120" }
    require(contextSeconds >= 0.0 && contextSeconds < chunkSeconds / 2.0) {
        "contextSeconds must be non-negative and less than half chunkSeconds"
    }
    require(mdsDimensions in 4..8) { "mdsDimensions must be between 4 and 8" }
    require(mdsLandmarks >= mdsDimensions + 2) {
        "mdsLandmarks must leave room for every requested dimension"
    }
    require(mdsSmoothingMillis in 0.0..10_000.0) {
        "mdsSmoothingMillis must be between 0 and 10000"
    }
    return ModelOperation(
        operation = "analysis.embed_speech",
        inputs = listOf(source),
        parameters = buildJsonObject {
            put("wavlm_layer", wavlmLayer)
            put("chunk_seconds", chunkSeconds)
            put("context_seconds", contextSeconds)
            put("mds_dimensions", mdsDimensions)
            put("mds_landmarks", mdsLandmarks)
            put("mds_smoothing_ms", mdsSmoothingMillis)
        },
        candidateCount = 1,
        backendPreference = backend.modelId,
        requestedBy = requestedBy,
        forceRegenerate = forceRegenerate,
    )
}

/** Emotion/prosody-oriented speech embedding convenience constructor. */
fun emotion2VecEmbeddingAnalysis(
    source: AssetRegion,
    chunkSeconds: Double = 20.0,
    contextSeconds: Double = 1.0,
    mdsLandmarks: Int = 384,
    mdsSmoothingMillis: Double = 80.0,
    requestedBy: String? = null,
    forceRegenerate: Boolean = false,
): ModelOperation = speechEmbeddingAnalysis(
    source = source,
    backend = SpeechEmbeddingBackend.EMOTION2VEC,
    chunkSeconds = chunkSeconds,
    contextSeconds = contextSeconds,
    mdsLandmarks = mdsLandmarks,
    mdsSmoothingMillis = mdsSmoothingMillis,
    requestedBy = requestedBy,
    forceRegenerate = forceRegenerate,
)

/** WavLM Base+ speech embedding convenience constructor. */
fun wavlmBasePlusEmbeddingAnalysis(
    source: AssetRegion,
    layer: Int = 6,
    chunkSeconds: Double = 20.0,
    contextSeconds: Double = 1.0,
    mdsLandmarks: Int = 384,
    mdsSmoothingMillis: Double = 80.0,
    requestedBy: String? = null,
    forceRegenerate: Boolean = false,
): ModelOperation = speechEmbeddingAnalysis(
    source = source,
    backend = SpeechEmbeddingBackend.WAVLM_BASE_PLUS,
    wavlmLayer = layer,
    chunkSeconds = chunkSeconds,
    contextSeconds = contextSeconds,
    mdsLandmarks = mdsLandmarks,
    mdsSmoothingMillis = mdsSmoothingMillis,
    requestedBy = requestedBy,
    forceRegenerate = forceRegenerate,
)
