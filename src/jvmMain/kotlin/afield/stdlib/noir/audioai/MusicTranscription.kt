@file:afield.CompletionCategory("Audio AI")

package afield.stdlib.noir.audioai

import org.msgpack.core.MessagePack
import org.msgpack.value.Value
import org.msgpack.value.ValueType
import java.util.concurrent.CompletableFuture

/**
 * Typed decoder for the versioned note document a multi-instrument transcription job leaves behind.
 *
 * `musicTranscriptionAnalysis` in `AnalysisOperations.kt` builds the request, and three accessors
 * beside it locate what came back: performance MIDI, optional beat-quantized MIDI, and the
 * MessagePack event document. This file reads the last of those.
 * `decodeMusicTranscriptionMessagePack` turns its bytes into a [MusicTranscription], and
 * `FieldAudioClient.musicTranscription` is the one-call path from a finished [AnalysisResult].
 *
 * MessagePack rather than JSON keeps the note times binary, so an onset reaches the JVM as the
 * float64 the worker wrote. The decoder rejects a payload whose `format` is not
 * `field.audio.music-transcription.v1`, rejects trailing data after the root map, and range-checks
 * every note: non-negative onset, offset at or after onset, MIDI pitch inside 0..127. A malformed
 * artifact fails at the decode rather than at the first drawing that reads it.
 *
 * Most of the document is optional. `MusicTranscription.beatGrid` is null when the artifact carries
 * no `beat_grid` or a nil one, and inside a grid `beatsPerBar`, `onsetDelaySeconds` and
 * `beatSubdivision` are each independently absent. The four provenance fields come from the
 * artifact's `metadata` map and are null when the worker recorded nothing there. `instrument` is a
 * string naming one of the worker's own instrument groups, which is a model's hypothesis about a
 * mixed recording and not a reading of what played it. See
 * [Multi-instrument transcription with MuScriptor](../audio_ai_muscriptor.md) for how far that has
 * been checked against a known source.
 */

/** One source-aligned note recovered by a multi-instrument transcription worker. */
data class MusicTranscriptionNote(
    val startSeconds: Double,
    val endSeconds: Double,
    val midi: Int,
    val instrument: String,
)

/** Tempo, meter, and the measured model-onset correction retained with a transcription. */
data class MusicTranscriptionBeatGrid(
    val bpm: Double,
    val beatsPerBar: Int?,
    val firstDownbeatSeconds: Double,
    val onsetDelaySeconds: Double?,
    val beatSubdivision: Int?,
)

/** Typed contents of the versioned MuScriptor MessagePack artifact. */
data class MusicTranscription(
    val notes: List<MusicTranscriptionNote>,
    val beatGrid: MusicTranscriptionBeatGrid?,
    val backend: String?,
    val modelId: String?,
    val modelRevision: String?,
    val checkpointSha256: String?,
)

private fun Value.namedMap(context: String): Map<String, Value> {
    require(valueType == ValueType.MAP) { "$context must be a MessagePack map" }
    return asMapValue().map().entries.associate { entry ->
        require(entry.key.valueType == ValueType.STRING) { "$context contains a non-string key" }
        entry.key.asStringValue().asString() to entry.value
    }
}

private fun Value.number(context: String): Double = when (valueType) {
    ValueType.INTEGER -> asIntegerValue().toLong().toDouble()
    ValueType.FLOAT -> asFloatValue().toDouble()
    else -> error("$context must be numeric")
}.also { require(it.isFinite()) { "$context must be finite" } }

private fun Value.stringOrNull(context: String): String? = when (valueType) {
    ValueType.NIL -> null
    ValueType.STRING -> asStringValue().asString()
    else -> error("$context must be a string or nil")
}

private fun Map<String, Value>.optionalString(name: String): String? =
    get(name)?.stringOrNull(name)

/** Decode the complete source-aligned note document without routing numerical data through JSON. */
fun decodeMusicTranscriptionMessagePack(bytes: ByteArray): MusicTranscription {
    require(bytes.isNotEmpty()) { "music transcription MessagePack is empty" }
    val root = MessagePack.newDefaultUnpacker(bytes).use { unpacker ->
        val value = unpacker.unpackValue()
        require(!unpacker.hasNext()) { "music transcription MessagePack has trailing data" }
        value.namedMap("music transcription")
    }
    val format = root["format"]?.stringOrNull("format")
    require(format == "field.audio.music-transcription.v1") {
        "unsupported music transcription MessagePack format: $format"
    }
    val noteValues = requireNotNull(root["notes"]) { "music transcription has no notes" }
    require(noteValues.valueType == ValueType.ARRAY) { "music transcription notes must be an array" }
    val notes = noteValues.asArrayValue().list().mapIndexed { index, value ->
        val note = value.namedMap("note $index")
        val start = requireNotNull(note["start_seconds"]) { "note $index has no onset" }
            .number("note $index onset")
        val end = requireNotNull(note["end_seconds"]) { "note $index has no offset" }
            .number("note $index offset")
        val midiValue = requireNotNull(note["midi"]) { "note $index has no MIDI pitch" }
        require(midiValue.valueType == ValueType.INTEGER) { "note $index MIDI pitch must be an integer" }
        val midi = midiValue.asIntegerValue().toInt()
        val instrument = requireNotNull(note["instrument"]?.stringOrNull("note $index instrument")) {
            "note $index has no instrument"
        }
        require(start >= 0.0 && end >= start) { "note $index has an invalid time range" }
        require(midi in 0..127) { "note $index MIDI pitch is outside 0..127" }
        MusicTranscriptionNote(start, end, midi, instrument)
    }
    val beatGrid = root["beat_grid"]?.let { value ->
        if (value.valueType == ValueType.NIL) null else {
            val grid = value.namedMap("beat grid")
            val bpm = requireNotNull(grid["bpm"]) { "beat grid has no bpm" }.number("bpm")
            val firstDownbeat = requireNotNull(grid["first_downbeat"]) {
                "beat grid has no first downbeat"
            }.number("first_downbeat")
            require(bpm > 0.0) { "beat grid bpm must be positive" }
            MusicTranscriptionBeatGrid(
                bpm = bpm,
                beatsPerBar = grid["beats_per_bar"]?.let {
                    if (it.valueType == ValueType.NIL) null else it.asIntegerValue().toInt()
                },
                firstDownbeatSeconds = firstDownbeat,
                onsetDelaySeconds = grid["onset_delay"]?.let {
                    if (it.valueType == ValueType.NIL) null else it.number("onset_delay")
                },
                beatSubdivision = grid["beat_subdivision"]?.let {
                    if (it.valueType == ValueType.NIL) null else it.asIntegerValue().toInt()
                },
            )
        }
    }
    val metadata = root["metadata"]?.let {
        if (it.valueType == ValueType.NIL) emptyMap() else it.namedMap("metadata")
    } ?: emptyMap()
    return MusicTranscription(
        notes = notes,
        beatGrid = beatGrid,
        backend = metadata.optionalString("backend"),
        modelId = metadata.optionalString("model_id"),
        modelRevision = metadata.optionalString("model_revision"),
        checkpointSha256 = metadata.optionalString("checkpoint_sha256"),
    )
}

/** Download and decode the complete typed note document retained by [musicTranscriptionAnalysis]. */
fun FieldAudioClient.musicTranscription(result: AnalysisResult): CompletableFuture<MusicTranscription> =
    downloadBytes(result.musicTranscriptionMessagePack()).thenApply(::decodeMusicTranscriptionMessagePack)
