# Loris: partial tracks and resynthesis

[Loris](https://github.com/kellyfitz/loris) turns a recording into evolving sinusoidal partials
with a noise-bandwidth envelope. Analyze a WAV, inspect its trajectories, change them, and render
them back to sound. This is an offline instrument: analysis and synthesis run in an optional
native worker, not on Field's audio thread. It needs no model weights or audio-model server.

Build the worker once with `./gradlew buildLorisNative` (setup below). In a Kotlin box, start with
the committed film-dialogue sample, or replace the path with your own WAV:

```diffstatic {data-lang=kotlin}
import afield.stdlib.loris.LorisClient
import afield.stdlib.loris.LorisOptions
import afield.stdlib.loris.LorisViewOptions
import afield.stdlib.loris.visualizationHtml
import afield.stdlib.path

val client = LorisClient()
val source = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
display("status", "Starting Loris…")
client.analyzeWavAsync(
    source,
    options = LorisOptions(resolutionHz = 35.0, windowWidthHz = 80.0),
    onProgress = { stage -> display("status", stage) },
).thenAccept { result ->
    display("partials and sound", result.visualizationHtml(
        LorisViewOptions(syncGroup = "my-partials")
    ))
    display("provenance", result.provenance)
}.exceptionally { error ->
    display("error", error.cause?.message ?: error.message ?: error.toString())
    null
}
```

`%docs` is FileManager's bundled documentation root, independent of your working directory.
For your own recording, use `"~/Downloads/recording.wav".path`, or `"recording.wav".path` for
the document's `files/` directory. See [Files and paths](files_and_paths.md).

The returned `LorisModel` retains the native, irregular breakpoint times. Each partial has an ID,
a label, and points with time in seconds, frequency in Hz, linear amplitude, phase in radians,
and bandwidth between 0 and 1. You can use these values directly in live code; the plot is not
the only way to get at them. The WAV convenience method explicitly reads the **left channel**.
For a different channel or your own mix, pass a mono `FloatArray` to `analyzeAsync(samples, rate)`.
No automatic peak normalization or resampling takes place.

The default plot spans **0 Hz to the source's Nyquist frequency** on an ERB axis; at 48 kHz,
that is 0–24 kHz, not a 4 kHz analysis cutoff. **Full band** and **80–4000 Hz detail** switch the
view without reanalysis. The axis menu offers ERB, linear Hz and cents/log. Zero is an axis origin,
not a claim to have tracked DC: the analyzer's default detection floor is 20 Hz. A cents/log view
cannot include zero and starts at a positive frequency instead.

## Listen alongside the tracks

This source is 13.5 seconds of *Night of the Living Dead* (1968), a public-domain film:
Johnny and Barbara speaking in the cemetery. The source is the same mono 48 kHz excerpt used in
[Subharmonic Synthesis](subharmonic_sound.md).

The capture below runs the real analyzer on that recording and plays its reconstruction. Source
and reconstruction players share a time window with the partial map. Playing either moves the
cursor; hovering shows native values; clicking a trajectory seeks both players. Zoom centers on
the visible playback head, wheel-zoom and drag-pan share the waveform's view, and **all time**
returns to the whole excerpt. The players do not automatically play together: audition one at a
time for A/B listening.

```diffstatic_runner {data-lang=kotlin screenshot="assets/loris/dialogue-partials.png" capture="box" open-outputs="true" requires="loris" wait="5" padding="14" timeout="180" w="1080" h="1100" embed=true name="loris-dialogue" alt="Real film dialogue source waveform, frequency-balanced Loris partial tracks across 0–24000 Hz on an ERB axis with local blue-to-amber bandwidth colors, full-band/detail controls, and reconstruction playback."}
:::static
import afield.stdlib.loris.LorisDocs
display("source, partials, reconstruction", LorisDocs.comparisonHtml())
```

Source:

<audio controls preload="metadata" src="assets/subharmonic_sound/notld-barbara-source.wav"></audio>

Loris reconstruction:

<audio controls preload="metadata" src="assets/loris/reconstruction.wav"></audio>

The map balances its drawing budget across frequency bands rather than letting loud, low
fundamentals occupy every slot. **Each segment's color** reflects its local native bandwidth:
blue is 0, amber is 1, with an explicit color key. This is not a whole-track average.
The map is not a spectrogram. **Bandwidth is not a
confidence score**, and a continuous track is not proof of the same source identity through a
crossing. Compare consonants, voices, hiss and attacks in the two recordings rather than
assuming the reconstruction preserved them all.

Drawing is bounded by `maxPartials` and `pointsPerPartial`, shown in the caption. These limits
only select native observations for display; the model and resynthesis retain every breakpoint.
`minimumHz`, `maximumHz` and `axis` change the view, not the analyzer's frequency resolution.
`selection = LorisTrackSelection.STRONGEST` restores amplitude/duration-ranked track selection;
the default is `BAND_BALANCED`. This film excerpt really has tracked observations above 4 kHz.

## Make a spectral whirlpool in one chunk

Keep the recording recognizable until six seconds, whirl its partial frequencies through
±18 semitones for three seconds, shutter their amplitudes at 12 Hz, and drive the center toward
noisy bandwidth. A squared-sine envelope brings all three gestures in and out together. Every
native breakpoint outside the selected window is left untouched.

This complete box starts from the WAV, analyzes it, changes the actual trajectories, waits for
resynthesis, and then plots **the edited model**, with its own playable sound. Phase is deliberately
retained; this is partial surgery, not a promise of transparent time/pitch processing. The
frequency cap below is an explicit guard against exceeding Nyquist, not a 4 kHz ceiling.

```diffstatic {data-lang=kotlin}
import afield.stdlib.loris.LorisClient
import afield.stdlib.loris.LorisOptions
import afield.stdlib.loris.LorisViewOptions
import afield.stdlib.loris.visualizationHtml
import afield.stdlib.path
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

val client = LorisClient()
val chunkStart = 6.0
val chunkDuration = 3.0
client.analyzeWavAsync(
    "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path,
    options = LorisOptions(resolutionHz = 35.0, windowWidthHz = 80.0,
        frequencyDriftHz = 50.0, amplitudeFloorDb = -55.0),
    onProgress = { display("status", it) },
).thenCompose { analyzed ->
    val model = analyzed.model
    val changed = model.copy(partials = model.partials.map { partial ->
        partial.copy(points = partial.points.map { point ->
            val u = (point.timeSeconds - chunkStart) / chunkDuration
            if (u <= 0.0 || u >= 1.0) point else {
                val envelope = sin(PI * u).pow(2)
                val octaves = 1.5 * envelope * sin(2 * PI * 4 * u)
                val shutter = .25 + .75 * (.5 + .5 * sin(2 * PI * 12 * chunkDuration * u))
                point.copy(
                    frequencyHz = (point.frequencyHz * 2.0.pow(octaves)).coerceAtMost(model.sampleRate * .49),
                    amplitude = point.amplitude * (1 - envelope + envelope * shutter),
                    bandwidth = point.bandwidth * (1 - envelope) + .85 * envelope,
                )
            }
        })
    })
    display("status", "Resynthesizing the whirlpool…")
    client.synthesizeAsync(changed)
}.thenAccept { edited ->
    display("edited tracks and sound", edited.visualizationHtml(
        LorisViewOptions(height = 400, syncGroup = "my-whirlpool")
    ))
}.exceptionally { error ->
    display("error", error.cause?.message ?: error.message ?: error.toString())
    null
}
```

Here is that exact gesture on the film excerpt. Compare seconds 6–9 against the reconstruction
above; the warm-colored noisy core and twisting tracks are data you can hear, not a drawn overlay.

```diffstatic_runner {data-lang=kotlin screenshot="assets/loris/spectral-whirlpool.png" capture="box" open-outputs="true" requires="loris" wait="5" padding="14" timeout="180" w="1080" h="900" embed=true name="loris-whirlpool" alt="Actual resynthesis of a localized spectral whirlpool: partial frequencies swirl through 6–9 seconds with amber noise bandwidth, plotted across the full native frequency band above its edited waveform and player."}
:::static
import afield.stdlib.loris.LorisDocs
display("edited tracks and sound", LorisDocs.whirlpoolHtml())
```

<audio controls preload="metadata" src="assets/loris/spectral-whirlpool.wav"></audio>

After `import afield.stdlib.loris.spectralWhirlpool`, the same example gesture is available as
`model.spectralWhirlpool(6.0, 3.0)`; the explicit code
above shows everything it does. Its [manifest](assets/loris/whirlpool-provenance.json) records the
parent analysis, chunk range, sweep depth, shutter rate, bandwidth target and synthesis request.

## Change the sound

This complete box analyzes the source, lowers every partial's frequency to three quarters,
removes the noise bandwidth, then renders and displays the changed model. Frequency and time
scaling retain stored breakpoint phases; listen for transient and phase-interpolation changes
rather than expecting a transparent pitch shifter.

```diffstatic {data-lang=kotlin}
import afield.stdlib.loris.LorisClient
import afield.stdlib.loris.LorisFrequencyAxis
import afield.stdlib.loris.LorisOptions
import afield.stdlib.loris.LorisViewOptions
import afield.stdlib.loris.visualizationHtml
import afield.stdlib.path

val client = LorisClient()
client.analyzeWavAsync(
    "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path,
    options = LorisOptions(resolutionHz = 35.0, windowWidthHz = 80.0,
        frequencyDriftHz = 50.0, amplitudeFloorDb = -55.0),
    onProgress = { display("status", it) },
).thenCompose { analyzed ->
    display("status", "Resynthesizing changed envelopes…")
    client.synthesizeAsync(analyzed.model.transformed(
        frequencyScale = 0.75, bandwidthScale = 0.0, gain = 0.8,
    ))
}.thenAccept { edited ->
    display("changed sound", edited.visualizationHtml(LorisViewOptions(
        axis = LorisFrequencyAxis.ERB,
        syncGroup = "changed-partials",
    )))
}.exceptionally { error ->
    display("error", error.cause?.message ?: error.message ?: error.toString())
    null
}
```

Here is that edit, with an ERB-spaced frequency axis:

All its segments should be blue: the edit explicitly sets every noise-bandwidth value to zero.

```diffstatic_runner {data-lang=kotlin screenshot="assets/loris/tonal-edit.png" capture="box" open-outputs="true" requires="loris" wait="5" padding="14" timeout="180" w="1080" h="720" embed=true name="loris-tonal-edit" alt="Loris partial map and waveform of the film dialogue lowered to 0.75 of its original partial frequencies, with noise bandwidth removed; frequency spacing is ERB."}
:::static
import afield.stdlib.loris.LorisDocs
display("changed partials and sound", LorisDocs.transformedHtml())
```

<audio controls preload="metadata" src="assets/loris/three-quarter-tonal.wav"></audio>

Other edits are ordinary data operations. Keep only tracks in a frequency band, delete short
partials, or replace a selected partial's breakpoint list. The IDs remain stable through
resynthesis. For example, after analyzing a source, this keeps only tracks whose mean frequency
is below 900 Hz:

```diffstatic {data-lang=kotlin}
val bassModel = analyzed.model.copy(partials = analyzed.model.partials.filter { partial ->
    partial.points.map { it.frequencyHz }.average() < 900.0
})
client.synthesizeAsync(bassModel).thenAccept { display("low partials", it.visualizationHtml()) }
```

`transformed(timeScale = 2.0)` stretches envelope times and doubles the frame extent;
`gain` changes linear amplitudes. Edits that exceed Nyquist or create invalid timestamps are
rejected. Reconstruction samples are never clipped. The combined player rejects peaks above
1 instead of silently clipping its PCM16 audition encoding: apply an explicit gain and keep
the unmodified model for later work.

## Time versus frequency resolution

These settings are independent:

| Control | Unit | What it changes |
| --- | --- | --- |
| `resolutionHz` | Hz | Separation between detected spectral peaks |
| `windowWidthHz` | Hz | Analysis window's main-lobe width; wider means a shorter window |
| `hopSeconds` | seconds | How often the analyzer examines the recording |
| `frequencyDriftHz` | Hz | Frequency movement allowed when building tracks |
| `amplitudeFloorDb` | dB | Detection floor |
| `minimumFrequencyHz` | Hz | Lower detection boundary |

A small hop cannot recover events blurred by a long window. Try a fine-time pass with
`windowWidthHz = 160.0`, and a fine-frequency pass with `windowWidthHz = 40.0`; retain them as
separate interpretations of the same source. A cents-spaced display does not turn Loris into a
constant-Q estimator. Use waveform and spectrogram views alongside it when judging attacks.

## A loadable Field study

Load [the Loris partial explorer transcript](offline.html?file=assets/agent_transcripts/loris_partial_explorer/metadata.edn)
for complete boxes: source playback, async analysis with status/error output, partial selection,
noise removal, a localized spectral whirlpool, resynthesis and an independent fine-time/fine-frequency comparison. Each box
submits its own analysis and waits for completion; it does not rely on a job secretly run earlier.

## Optional setup and caching

```diffstatic {data-lang=bash}
./gradlew buildLorisNative
./gradlew jvmTest --tests 'afield.stdlib.loris.LorisTest'
```

The build needs CMake 3.24+ and a C++17 compiler. It downloads a checksummed, pinned Loris source
archive once and uses the bundled FFT; no FFTW, Python binding, neural model or GPU is needed.
Linux has been tested with real analysis and resynthesis. macOS and Windows builds are provided
but have not yet been live-validated. `AFIELD_LORIS_EXECUTABLE` or the JVM property
`afield.loris.executable` selects another built worker.

Ordinary Field startup and tests do not build or download Loris. Documentation captures declare
`requires="loris"`, so an unavailable worker does not replace previous captures with empty ones.
The Kotlin wrapper runs offline subprocesses with a timeout and disk cache; these are not jobs
in the separate Python Audio AI server's monitor. Status is reported in the requesting Field box.

Cache identity includes exact source samples, sample rate, analyzer settings or edited
breakpoints, native executable/shared-library fingerprints and pinned upstream revision.
Unchanged requests reuse the validated binary result in `build/field-loris/cache/`. Closing a
document does not erase these entries. Provenance is available on every result, and the
[published example manifest](assets/loris/provenance.json) records the source hash, controls and
transformation used for the sounds above. Audio remains at its native source rate; place it on
a 48 kHz project through an explicitly recorded conversion when needed.
