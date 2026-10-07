# Loris: partial tracks and resynthesis

[Loris](https://github.com/kellyfitz/loris) turns a recording into evolving sinusoidal partials
with a noise-bandwidth envelope. Analyze a WAV, inspect its trajectories, change them, and render
them back to sound. Analysis and synthesis run offline, in a small native program that Field
starts for you, so nothing here runs in real time.

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

`%docs` is the folder of documentation files that ships with Field. For your own recording, use `"~/Downloads/recording.wav".path`, or `"recording.wav".path` for
the document's `files/` directory. See [Files and paths](files_and_paths.md).

The returned `LorisModel` retains the native, irregular breakpoint times. Each partial has an ID,
a label, and points with time in seconds, frequency in Hz, linear amplitude, phase in radians,
and bandwidth between 0 and 1 (how noisy the partial is), and all of it is ordinary data your code
can read. Analyzing a WAV reads its left channel. For a different channel or your own mix, pass a
mono `FloatArray` to `analyzeAsync(samples, rate)`. The samples are analyzed as they are, with no
normalization or resampling.

The plot runs from 0 Hz up to half the sample rate (24 kHz for a 48 kHz file) on an ERB axis, a
scale that spaces frequencies roughly as the ear does. The Full band and 80–4000 Hz detail buttons
change the view without analyzing again, and the axis menu also offers linear Hz and cents. The
analyzer itself ignores anything below 20 Hz by default. A cents axis cannot reach zero, so it
starts at a positive frequency.

## Listen alongside the tracks

This source is 13.5 seconds of *Night of the Living Dead* (1968), a public-domain film:
Johnny and Barbara speaking in the cemetery. The source is the same mono 48 kHz excerpt used in
[Subharmonic Synthesis](subharmonic_sound.md).

The plot below shows the analysis of that recording, with players for the source and for its
reconstruction. Playing either one moves the cursor across the partial map, hovering a track shows
its values, and clicking a track moves both players to that time. The wheel zooms around the
playhead, dragging pans, and all time returns to the whole excerpt. The two players do not play
together, so you can switch between them to compare.

```diffstatic_runner {data-lang=kotlin screenshot="assets/loris/dialogue-partials.png" capture="box" open-outputs="true" requires="loris" wait="5" padding="14" timeout="180" w="1080" h="1100" embed=true name="loris-dialogue" alt="Real film dialogue source waveform, frequency-balanced Loris partial tracks across 0–24000 Hz on an ERB axis with local blue-to-amber bandwidth colors, full-band/detail controls, and reconstruction playback."}
:::static
import afield.stdlib.loris.LorisDocs
display("source, partials, reconstruction", LorisDocs.comparisonHtml())
```

Source:

<audio controls preload="metadata" src="assets/subharmonic_sound/notld-barbara-source.wav"></audio>

Loris reconstruction:

<audio controls preload="metadata" src="assets/loris/reconstruction.wav"></audio>

The map shares the tracks it draws across frequency bands, so loud low fundamentals do not crowd
out everything above them. Each segment is coloured by its bandwidth at that moment, from blue (a
pure tone) to amber (noise). When two tracks cross, one continuous line may still have swapped
between voices. Listen to the consonants, the hiss and the attacks in both recordings to hear what
the reconstruction kept.

Drawing is bounded by `maxPartials` and `pointsPerPartial`, shown in the caption. These limits
only select native observations for display; the model and resynthesis retain every breakpoint.
`minimumHz`, `maximumHz` and `axis` change the view, not the analyzer's frequency resolution.
`selection = LorisTrackSelection.STRONGEST` draws the loudest and longest tracks instead of the
default `BAND_BALANCED`.

## Make a spectral whirlpool in one chunk

Keep the recording recognizable until six seconds, whirl its partial frequencies through
±18 semitones for three seconds, shutter their amplitudes at 12 Hz, and drive the center toward
noisy bandwidth. A squared-sine envelope brings all three gestures in and out together, and
nothing outside that window changes.

This box analyzes the WAV, edits the partials, waits for the resynthesis, and plots the edited
model with a player for its sound. Each breakpoint keeps its stored phase, so expect some smearing
of attacks. The frequency cap in the code keeps every partial below half the sample rate.

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

Here is the gesture on the film excerpt. Compare seconds 6–9 with the reconstruction above: the
amber, noisy middle and the twisting tracks are what you hear.

```diffstatic_runner {data-lang=kotlin screenshot="assets/loris/spectral-whirlpool.png" capture="box" open-outputs="true" requires="loris" wait="5" padding="14" timeout="180" w="1080" h="900" embed=true name="loris-whirlpool" alt="Actual resynthesis of a localized spectral whirlpool: partial frequencies swirl through 6–9 seconds with amber noise bandwidth, plotted across the full native frequency band above its edited waveform and player."}
:::static
import afield.stdlib.loris.LorisDocs
display("edited tracks and sound", LorisDocs.whirlpoolHtml())
```

<audio controls preload="metadata" src="assets/loris/spectral-whirlpool.wav"></audio>

After `import afield.stdlib.loris.spectralWhirlpool`, the same example gesture is available as
`model.spectralWhirlpool(6.0, 3.0)`. Its [manifest](assets/loris/whirlpool-provenance.json) records the
parent analysis, chunk range, sweep depth, shutter rate, bandwidth target and synthesis request.

## Change the sound

This box analyzes the source, lowers every partial's frequency to three quarters, removes the
noise bandwidth, then renders and displays the changed model. The breakpoints keep their stored
phases, so attacks change character more than a dedicated pitch shifter would make them.

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

Here is that edit. Every segment is blue, because the edit set every bandwidth to zero:

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
rejected. Field never clips a reconstruction: if a peak goes above 1, the player refuses it, so
apply a `gain` and keep the unmodified model for later.

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
separate readings of the same source. When judging attacks, look at a waveform or spectrogram as
well.

## A document to explore

[The Loris partial explorer](offline.html?file=assets/agent_transcripts/loris_partial_explorer/metadata.edn)
has a box for each step on this page: playing the source, analysis with its status, picking
partials, removing noise, the spectral whirlpool, resynthesis, and a fine-time against
fine-frequency comparison. Each box runs its own analysis, so you can start from any of them.

## Setup and caching

Loris is optional, and Field does not build it unless you ask. Build the worker with:

```diffstatic {data-lang=bash}
./gradlew buildLorisNative
./gradlew jvmTest --tests 'afield.stdlib.loris.LorisTest'
```

The build needs CMake 3.24+ and a C++17 compiler. It downloads a checksummed, pinned Loris source
archive once and uses the bundled FFT; no FFTW, Python binding, neural model or GPU is needed.
It has been run on Linux; the macOS and Windows builds are untested. `AFIELD_LORIS_EXECUTABLE`
or the JVM property `afield.loris.executable` selects another built worker.

Each request runs the worker with a timeout and reports its progress in the box that asked.
Results are cached in `build/field-loris/cache/` and survive closing the document; the cache is
keyed on the samples, the sample rate, the settings or edited breakpoints, and the worker build,
so any change runs the analysis again. Provenance is available on every result, and the
[published example manifest](assets/loris/provenance.json) records the source hash, controls and
transformation used for the sounds above. Audio remains at its native source rate; place it on
a 48 kHz project through an explicitly recorded conversion when needed.
