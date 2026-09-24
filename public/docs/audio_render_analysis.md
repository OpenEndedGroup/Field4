# Render Field music, then analyse it

Write a Pat pattern in a box, render twelve cycles of it, and there is a finite WAV file on disk
before you have heard it play. That file is all Field Audio ever sees of the music. Pat, Dirt,
Dough and Faust render faster than real time and Sonic Pi records a real-time performance, but
every one of them hands back the same `RenderedAudioFile`, and
`FieldAudioClient.analyzeRenderedAudio(...)` takes it from there. It imports the immutable bytes,
submits cached analysis jobs, follows progress and returns durable results.

The loop:

```diffstatic {data-lang=text}
Field code (Pat / Dirt / Dough / Faust / Sonic Pi)
    → finite WAV + Field-owned render provenance
    → content-addressed Field Audio asset
    → beat, pYIN and Basic Pitch jobs
    → synchronized waveform and analysis views
    → revise the generating code
```

The project model stays in Field. `RenderedAudioFile.provenance` stays with the Field operation
and the source code stays in its box. The server asset id names only the WAV bytes, which
means two programs that genuinely render identical audio share the asset and its cached analyses.

## Rendering a Pat pattern and analysing it

`renderDirtWav` may be replaced by any renderer in the table below; the import, job and
visualization half is unchanged.

```diffstatic {data-lang=kotlin}
import afield.four.synth.*
import afield.stdlib.noir.audioai.*
import java.nio.file.Path

val client = FieldAudioClient()
display("all jobs", fieldAudioJobMonitor(heightPixels = 330))

val music = (pat("{60 64 67 71, bd ~ sd ~, hh*8}")
    with gain(sine().range(0.22, 0.72))
    with pan("0.2 0.8"))

val render = renderDirtWav(
    Path.of(System.getProperty("java.io.tmpdir"), "field-audio-renders", "pat-study.wav"),
    music, cycles = 12, cps = 1.6,
    provenance = mapOf("field_box" to "pat-study"),
)

client.analyzeRenderedAudio(
    render,
    requests = { region ->
        standardAudioAnalyses(region, requestedBy = "field:pat-study")
    },
    onAccepted = { named, accepted ->
        display("${named.label} job", fieldAudioJobStatusHtml(accepted))
    },
    onProgress = { named, accepted, event ->
        display("${named.label} job", fieldAudioJobStatusHtml(accepted, event))
    },
).thenAccept { completed ->
    display("analysis", completed.visualizationHtml(panelHeight = 230))
}.exceptionally { error ->
    display("analysis error", error.cause?.message ?: error.message ?: error.toString())
    null
}
```

The final view behaves as one instrument: audio playback moves every analysis cursor; hovering a
pitch or beat lane moves the waveform cursor; clicking any lane seeks the player; zoom and pan
share the same time window.

## Renderers

<div class="field-data-table field-call-table" data-code-lang="kotlin" markdown>

| Source in Field | Finite-file entry point | Execution |
| --- | --- | --- |
| Pat's deterministic voice | `renderPatWav(path, pattern, cycles, cps)` | Renders offline and returns the completed file synchronously. `cycles` sets the pattern span; `cps` sets cycles per second. |
| Pat through Dirt samples | `renderDirtWav(path, pattern, cycles, cps)` | Renders offline using the available Dirt samples and returns synchronously. The render provenance records which samples matched. |
| Dough mono signal | `renderDoughWav(path, signal, seconds)` | Evaluates a mono signal for the requested duration and returns the completed WAV synchronously. Use it for a finite study of a continuous signal. |
| Dough stereo node | `renderDoughStereoWav(path, node, seconds)` | Renders a stereo node offline for the requested duration. Left and right channels remain separate in the returned file. |
| compiled Faust program | `renderFaustWav(path, program, seconds)` | Renders an already compiled program offline and returns synchronously. Reuse the compiled program when varying render parameters. |
| Faust source text | `renderFaustWavAsync(path, name, source, seconds)` | Compiles and renders in the background, returning a future for the completed file. Chain analysis with `thenCompose` to avoid blocking on compiler warm-up. |
| Sonic Pi source text | `recordSonicPiWav(sonicPi(), path, source, seconds)` | Records a real-time performance and returns a future. Completion waits for recording to stop and the WAV to stabilize. |
| any existing Field WAV | `RenderedAudioFile.existing(path, generator, provenance)` | Wraps an existing WAV with its generator name and provenance. It performs no synthesis; use it to send an earlier render through the same analysis pipeline. |

</div>

All writers accept explicit paths and never silently choose a project location. The transcript uses
the system temporary directory because the uploaded asset is durable and content-addressed; use a
path beside a Field project when retaining the local WAV is part of the composition.

## Choosing analyses and regions

`standardAudioAnalyses(region)` requests multi-scale beats, monophonic pYIN and polyphonic Basic
Pitch. Disable irrelevant legs to make an experiment cheaper:

```diffstatic {data-lang=kotlin}
client.analyzeRenderedAudio(
    render,
    startSeconds = 4.0,
    durationSeconds = 12.0,
    requests = { region -> standardAudioAnalyses(
        region,
        requestedBy = "field:rhythm-only",
        includeMonophonicPitch = false,
        includePolyphonicPitch = false,
    ) },
)
```

For a custom fan-out, return `NamedAudioAnalysis(label, operation)` values from `requests`. Accepted
jobs are exposed immediately, WebSocket progress is optional, and `RenderedAudioAnalysis.analyses`
retains each label, `JobAccepted` record and final `AnalysisResult`. Identical bytes plus identical
operation parameters resolve through the existing cache.

## Asynchronous renderers

Faust source compilation and Sonic Pi capture return `CompletableFuture<RenderedAudioFile>`, so they
compose directly into analysis without blocking Field:

```diffstatic {data-lang=kotlin}
renderFaustWavAsync(path, "voice", faustSource, seconds = 18.0)
    .thenCompose { render -> client.analyzeRenderedAudio(render) }
    .thenAccept { result -> display("analysis", result.visualizationHtml()) }
```

Sonic Pi's helper starts the recorder, evaluates the Ruby program, captures for the requested time,
stops its jobs, waits for the saved file to stabilize, and only then imports it. It does not close
the shared Sonic Pi daemon.

Load the `audio_render_analysis` operation transcript for complete Pat/Dirt, Dough, Faust and Sonic
Pi boxes plus the durable job monitor. Continue with [beat and pitch analysis](audio_ai_analysis.md),
[the general audio visualizer](visualizers.md#recorded-audio-waveform-player-playhead-and-navigation),
or [every sound path in Field](sound_paths.md).
