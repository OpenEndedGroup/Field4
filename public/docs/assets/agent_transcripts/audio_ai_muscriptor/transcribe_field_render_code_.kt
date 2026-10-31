import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await

val client = cached { FieldAudioClient() }
val sourcePattern = "*sonic-pi-study*" // replace with an asset you may transcribe
// Filename matches can refer to different recordings; retain the resolved content ID.
val sync = "muscriptor-live"
val midiDirectory = Path.of(System.getProperty("java.io.tmpdir"), "field-audio-renders")
display("job monitor", fieldAudioJobMonitor(heightPixels = 330))

go {
    try {
        val source = client.assetByFilename(sourcePattern).await()
        display("source identity", source.id)
        val audio =
            source.representations.firstOrNull { it.role == "canonical_project" }
                ?: source.representations.first { it.mediaType in setOf("audio/wav", "audio/x-wav") }
        val duration = requireNotNull(audio.durationSeconds) { "source audio has no duration" }
        val region = AssetRegion(source.id, startSeconds = 0.0, durationSeconds = duration)
        display(
            "source",
            audio.audioVisualizerHtml(
                label = "$sourcePattern · ${duration.f2} seconds",
                options = AudioVisualizerOptions(
                    width = 1100,
                    waveformHeight = 145,
                    initialDurationSeconds = minOf(20.0, duration),
                    syncGroup = sync,
                ),
            ),
        )
        val result =
            client
                .trackAnalysis(
                    musicTranscriptionAnalysis(
                        region,
                        backend = MuscriptorBackend.SMALL,
                        tempoDetection = MuscriptorTempoDetection.BEST_EFFORT,
                        requestedBy = "field:audio-ai-muscriptor/live",
                    ),
                    onAccepted = { display("transcription job", fieldAudioJobStatusHtml(it)) },
                    onProgress = { job, event -> display("transcription job", fieldAudioJobStatusHtml(job, event)) },
                    onCompleted = { display("transcription job", fieldAudioJobStatusHtml(it)) },
                    onError = {}, // Report failure once through the surrounding coroutine.
                )
                .await()
        display("score", analysisVisualizationHtml(result, width = 1100, height = 420, syncGroup = sync))
        val transcription = client.musicTranscription(result).await()
        val byInstrument = transcription.notes.groupingBy { it.instrument }.eachCount()
        display("typed notes", "${transcription.notes.size} notes · $byInstrument")
        display(
            "interpretation",
            "Instrument labels are predictions. Compare pitches and onsets with the source; a completed job is not an accuracy guarantee.",
        )
        val downloads = mutableListOf<Path>()
        downloads.add(
            client
                .download(result.musicTranscriptionMidi(), midiDirectory.resolve("muscriptor-performance.mid"))
                .await(),
        )
        result.musicTranscriptionQuantizedMidiOrNull()?.let { midi ->
            downloads.add(client.download(midi, midiDirectory.resolve("muscriptor-quantized.mid")).await())
        }
        display("MIDI", downloads.joinToString("<br>") { "saved $it" })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        markdown("MuScriptor error", "Operation failed: ${(failure.message ?: failure.toString()).md}")
    }
}
