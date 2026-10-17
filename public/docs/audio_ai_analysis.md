# Beat and pitch analysis

*To run the examples on this page, open the [audio_ai_analysis](agent_transcripts.md#audio_ai_analysis) starting point.*

Where the beats are and what note is sounding are separate questions here, and they arrive as
separate jobs. `analysis.beats` takes the first at several window lengths at once, since a tempo
figure means little apart from the window it was measured over. For the second, how much is
sounding at once decides: `analysis.pitch_mono` tracks one voice as a continuous F0 with a voicing
probability, and `analysis.pitch_poly` returns discrete note events and a MIDI file where there are
chords.

| Field operation | Implementation | What comes back |
| --- | --- | --- |
| `analysis.beats` | librosa beat tracking and multi-window tempograms | onset envelope, beat markers, local BPM and confidence at every requested scale |
| `analysis.pitch_mono` | librosa pYIN | monophonic F0, MIDI pitch, voiced flag and voiced probability |
| `analysis.pitch_poly` | Spotify Basic Pitch | note events, activation tensors, pitch bends and a MIDI artifact |

All three are durable, cached jobs on the same machinery as generation, and each asks for one
result rather than a family of candidates: a fixed window with fixed parameters has a single
answer, so there is nothing here to browse by seed. A request names an exact `AssetRegion` and the
recording on disk is never modified. Progress arrives on the WebSocket and in the embedded job
monitor, and the finished job points at a compressed NPZ asset instead of putting a large tensor
into Field's output JSON.

## The views

Every panel draws a preview the server has downsampled; the full-resolution arrays stay in the
NPZ asset. The beat view separates onset, tempo and confidence lanes; pYIN
shows pitch against a note grid with its voicing track; Basic Pitch combines a piano roll with
maximum activation and active-pitch count.
Hover, zoom and pan one panel and the others follow. A live board can join the source player on the
same shared timeline: playback drives every analysis cursor, hovering either surface highlights the
other, and clicking an analysis plot seeks the recording.

## Running all three from one box

The source waveform appears immediately. The monitor and three replace-in-place status
cards report each job while the worker runs, and a job becomes a panel on the analysis board only
once it has finished successfully.

```diffstatic_runner {data-lang=kotlin screenshot="assets/audio_ai_analysis/synchronized_analysis.png" capture="box" capture-profile="linux-nvidia" open-outputs="true" wait="4" wait-output="true" wait-output-channel="analysis" timeout="300" w="1040" h="40" padding="16" embed=true name="nancy-analysis" alt="Nancy from 18 to 42 seconds with a source waveform and synchronized beat, pYIN pitch and Basic Pitch note panels"}
:::fold imports and client
import java.util.concurrent.CompletableFuture

val client = FieldAudioClient()
:::endfold
val start = 18.0
val duration = 24.0
val timeSync = "nancy-analysis"
display("all jobs", fieldAudioJobMonitor(heightPixels = 330))

client.assetByFilename("*Nancy*").thenCompose { source ->
    val region = AssetRegion(source.id, start, duration)
    val canonical = source.representations.first { it.role == "canonical_project" }
    display("source", canonical.audioVisualizerHtml(
        label = "Nancy · 18–42 s",
        options = AudioVisualizerOptions(
            width = 1040, waveformHeight = 110,
            initialStartSeconds = start,
            initialDurationSeconds = duration,
            initialPositionSeconds = start,
            syncGroup = timeSync,
        ),
    ))

    :::fold submit and track the three analysis jobs
    val requests = [
        "beats" to multiScaleBeatAnalysis(region, requestedBy = "field:analysis-example"),
        "monophonic pitch" to monophonicPitchAnalysis(region, requestedBy = "field:analysis-example"),
        "polyphonic pitch" to polyphonicPitchAnalysis(region, requestedBy = "field:analysis-example"),
    ]
    val futures = requests.map { (label, request) ->
        client.submit(request).thenCompose { accepted ->
            display("$label job", fieldAudioJobStatusHtml(accepted))
            client.watch(accepted.jobId) { event ->
                display("$label job", fieldAudioJobStatusHtml(accepted, event))
            }
            client.awaitAnalysis(accepted)
        }
    }
    CompletableFuture.allOf(*futures.toTypedArray()).thenApply {
        futures.map { it.join() }
    }
    :::endfold
}.thenAccept { results ->
    displayClear("all jobs")
    for (label in ["beats", "monophonic pitch", "polyphonic pitch"]) displayClear("$label job")
    displayOpen("source")
    display("analysis", analysisDashboardHtml(
        results, width = 1040, panelHeight = 250,
        syncGroup = timeSync, timeOffsetSeconds = start,
    ))
    displayOpen("analysis")
}.exceptionally { error ->
    display("analysis error", error.cause?.message ?: error.message ?: error.toString())
    null
}
```

An unchanged request still goes through `submit` and `awaitAnalysis`, but the service resolves it to
the content-addressed cached result. Change the source range or an algorithm parameter to create a
new operation. `AnalysisResult.data` is the complete NPZ representation;
`AnalysisResult.artifacts` includes Basic Pitch MIDI where applicable; `client.download(...)`
copies either immutable asset when local numerical work needs the full resolution.

See also the [general audio visualizers](visualizers.md#beat-and-pitch-analysis-views), the
[gesture board](audio_ai_gesture_board.md) and [speech embeddings](audio_ai_speech_embeddings.md).
