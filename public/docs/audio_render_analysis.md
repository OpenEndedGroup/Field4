# Render Field music, then analyse it

Field's sound-producing systems and Field Audio meet at one plain boundary: a finite WAV file.
Pat, Dirt, Dough and Faust can render faster than real time; Sonic Pi can record a real-time
performance. Each returns a `RenderedAudioFile`, and the same
`FieldAudioClient.analyzeRenderedAudio(...)` call then imports its immutable bytes, submits cached
analysis jobs, follows progress and returns durable results.

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

## The complete live-coding path

This Pat example shows every boundary. `renderDirtWav` may be replaced by any renderer in the
table below; the import, job and visualization half is unchanged.

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

| Source in Field | Finite-file entry point | Execution |
| --- | --- | --- |
| Pat's deterministic voice | `renderPatWav(path, pattern, cycles, cps)` | offline, synchronous |
| Pat through Dirt samples | `renderDirtWav(path, pattern, cycles, cps)` | offline, synchronous; reports matched samples |
| Dough mono signal | `renderDoughWav(path, signal, seconds)` | offline, synchronous |
| Dough stereo node | `renderDoughStereoWav(path, node, seconds)` | offline, synchronous; preserves L/R |
| compiled Faust program | `renderFaustWav(path, program, seconds)` | offline, synchronous |
| Faust source text | `renderFaustWavAsync(path, name, source, seconds)` | background future; avoids blocking on compiler warm-up |
| Sonic Pi source text | `recordSonicPiWav(sonicPi(), path, source, seconds)` | background future; real-time recording |
| any existing Field WAV | `RenderedAudioFile.existing(path, generator, provenance)` | no rendering assumption |

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
