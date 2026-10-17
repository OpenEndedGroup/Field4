@file:afield.runtime.completion.CompletionCategory("Audio AI")

package afield.audio.ai

import afield.app.protocol.Rect
import afield.app.server.SessionInformation
import afield.app.server.createAgentBox
import afield.audio.engine.WavFloatArrayLoader
import afield.audio.engine.WavFloatData
import afield.audio.synth.AudioSample
import afield.audio.synth.Sampler
import afield.document.graph.Box
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Locale

/**
 * One window of a corpus recording, in memory: what a neighbour the [corpusMicroscopeHtml] found
 * becomes when it is placed as a box of its own.
 *
 * [sampler] plays [sample] once from its start; return `audio(clip.sampler())` from a box to hear it.
 */
class CorpusClip(
    /** The recording the window is cut from. */
    val assetId: String,
    /** Where the window starts in that recording, in seconds. */
    val startSeconds: Double,
    /** Where it ends, in seconds. */
    val endSeconds: Double,
    /** The window's samples, the recording's left channel. */
    val sample: AudioSample,
) {
    /** A [Sampler] over the window, already triggered, so it plays as soon as it is launched. */
    fun sampler(): Sampler = Sampler(sample).also { it.trigger() }

    override fun toString(): String =
        "CorpusClip($assetId, ${"%.2f".format(Locale.US, startSeconds)}–${"%.2f".format(Locale.US, endSeconds)} s)"

    companion object {
        /** The window [startSeconds]..[endSeconds] of an already decoded recording. */
        fun of(assetId: String, wav: WavFloatData, startSeconds: Double, endSeconds: Double): CorpusClip {
            require(endSeconds > startSeconds) { "a clip ends after it starts: $startSeconds..$endSeconds" }
            val from = (startSeconds * wav.sampleRate).toInt().coerceIn(0, wav.samples.size)
            val to = (endSeconds * wav.sampleRate).toInt().coerceIn(from, wav.samples.size)
            return CorpusClip(assetId, startSeconds, endSeconds,
                AudioSample(wav.samples.copyOfRange(from, to), wav.sampleRate.toFloat()))
        }
    }
}

/**
 * The window [startSeconds]..[endSeconds] of the recording [assetId], fetched from the Field audio
 * service. The recording's WAV is cached under `~/.afield/cache/field-audio`, so placing several windows of
 * one recording downloads it once. This blocks until the bytes are here.
 *
 * ```kotlin
 * val clip = corpusClip("sha256:d8ae…", startSeconds = 21.0, endSeconds = 27.0)
 * audio(clip.sampler())
 * ```
 */
fun corpusClip(
    assetId: String,
    startSeconds: Double,
    endSeconds: Double,
    client: FieldAudioClient = FieldAudioClient(),
): CorpusClip {
    val asset = client.describe(assetId).join()
    val wav = asset.representations.firstOrNull { it.role == "canonical_project" && it.mediaType in WAV_TYPES }
        ?: asset.representations.firstOrNull { it.mediaType in WAV_TYPES }
        ?: error("$assetId has no WAV representation")
    val cached = File(System.getProperty("user.home"), ".afield/cache/field-audio/${wav.id}.wav")
    if (!cached.isFile) client.download(wav, cached.toPath()).join()
    return CorpusClip.of(assetId, WavFloatArrayLoader.loadWavToFloatArray(cached.path), startSeconds, endSeconds)
}

private val WAV_TYPES = setOf("audio/wav", "audio/x-wav")

private fun seconds(value: Double) = String.format(Locale.US, "%.2f", value)

/** The name a placed neighbour's box gets: its recording and window. */
fun corpusNeighbourName(neighbour: CorpusNeighbour): String {
    val segment = neighbour.segment
    val source = neighbour.audio.metadata["original_filename"]?.jsonPrimitive?.contentOrNull?.substringBeforeLast('.')
        ?: segment.assetId.removePrefix("sha256:").take(12)
    return "$source · ${seconds(segment.startSeconds)}–${seconds(segment.startSeconds + segment.durationSeconds)} s"
}

/** The `code` a placed neighbour's box gets: ordinary box code that plays the window. */
fun corpusNeighbourCode(neighbour: CorpusNeighbour): String {
    val segment = neighbour.segment
    return """
        // Placed from the corpus microscope: ${corpusNeighbourName(neighbour)}.
        val clip = corpusClip(
            "${segment.assetId}",
            startSeconds = ${segment.startSeconds},
            endSeconds = ${segment.startSeconds + segment.durationSeconds},
        )
        audio(clip.sampler())
    """.trimIndent() + "\n"
}

/**
 * Make the box for [neighbour] beside [beside]: named after the window, its code [corpusNeighbourCode].
 * [index] stacks the boxes of several placed neighbours below one another.
 */
fun placeCorpusNeighbour(info: SessionInformation, beside: Box, neighbour: CorpusNeighbour, index: Int = 0): Box {
    val frame = beside.frame
    return createAgentBox(
        info, corpusNeighbourName(neighbour),
        Rect(frame.x + frame.w + 40.0, frame.y + index * 70.0, 360.0, 50.0),
        mapOf("code" to JsonPrimitive(corpusNeighbourCode(neighbour))),
    )
}

/** [placeCorpusNeighbour] beside this box, in its own document: what a card's **place** button does. */
fun Box.placeCorpusNeighbour(neighbour: CorpusNeighbour, index: Int = 0): Box {
    val info = afield.study.source.BoxStudies.documentOf(this)
        ?: throw IllegalStateException("placeCorpusNeighbour needs this box to be in an open document")
    return placeCorpusNeighbour(info, this, neighbour, index)
}
