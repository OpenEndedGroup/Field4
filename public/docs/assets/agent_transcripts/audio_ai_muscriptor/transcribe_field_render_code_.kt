import afield.stdlib.AudioVisualizerOptions
import afield.stdlib.noir.audioai.*
import java.nio.file.Path

val client = FieldAudioClient()
val sourcePattern = "*sonic-pi-study*" // replace with an asset you may transcribe
// Filename matches can refer to different recordings; retain the resolved content ID.
val sync = "muscriptor-live"
val midiDirectory = Path.of(System.getProperty("java.io.tmpdir"), "field-audio-renders")
display("job monitor", fieldAudioJobMonitor(heightPixels = 330))

client.assetByFilename(sourcePattern).thenCompose { source ->
    display("source identity", source.id)
    val audio = source.representations.firstOrNull { it.role == "canonical_project" }
        ?: source.representations.first { it.mediaType in setOf("audio/wav", "audio/x-wav") }
    val duration = requireNotNull(audio.durationSeconds) { "source audio has no duration" }
    val region = AssetRegion(source.id, startSeconds = 0.0, durationSeconds = duration)
    display("source", audio.audioVisualizerHtml(
        label = "$sourcePattern · ${"%.2f".format(duration)} seconds",
        options = AudioVisualizerOptions(
            width = 1100, waveformHeight = 145,
            initialDurationSeconds = minOf(20.0, duration), syncGroup = sync,
        ),
    ))
    client.submit(musicTranscriptionAnalysis(
        region,
        backend = MuscriptorBackend.SMALL,
        tempoDetection = MuscriptorTempoDetection.BEST_EFFORT,
        requestedBy = "field:audio-ai-muscriptor/live",
    )).thenCompose { accepted ->
        display("transcription job", fieldAudioJobStatusHtml(accepted))
        client.watch(
            accepted.jobId,
            onEvent = { event -> display("transcription job", fieldAudioJobStatusHtml(accepted, event)) },
            onError = { error -> display("transcription stream", "progress stream: ${error.message}") },
        )
        client.awaitAnalysis(accepted).thenApply { result ->
            client.inspect(accepted.jobId).thenAccept { job ->
                display("transcription job", fieldAudioJobStatusHtml(job))
            }
            result
        }
    }
}.thenAccept { result ->
    display("score", analysisVisualizationHtml(
        result, width = 1100, height = 420, syncGroup = sync,
    ))
    client.musicTranscription(result).thenAccept { transcription ->
        val byInstrument = transcription.notes.groupingBy { it.instrument }.eachCount()
        display("typed notes", "${transcription.notes.size} notes · $byInstrument")
        display("interpretation", "Instrument labels are predictions. Compare pitches and onsets with the source; a completed job is not an accuracy guarantee.")
    }
    val downloads = mutableListOf(
        client.download(result.musicTranscriptionMidi(), midiDirectory.resolve("muscriptor-performance.mid"))
    )
    result.musicTranscriptionQuantizedMidiOrNull()?.let { midi ->
        downloads += client.download(midi, midiDirectory.resolve("muscriptor-quantized.mid"))
    }
    java.util.concurrent.CompletableFuture.allOf(*downloads.toTypedArray()).thenRun {
        display("MIDI", downloads.joinToString("<br>") { "saved ${it.join()}" })
    }
}.exceptionally { error ->
    val message = error.cause?.message ?: error.message ?: error.toString()
    display("MuScriptor error", """<pre style="color:#f1aaaa;background:#28191b;padding:12px;white-space:pre-wrap">$message</pre>""")
    null
}
