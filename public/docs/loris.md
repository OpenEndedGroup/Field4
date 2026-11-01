# Loris: partial tracks and resynthesis

[Loris](https://github.com/kellyfitz/loris) turns a recording into evolving sinusoidal partials
with a noise-bandwidth envelope. Analyze a WAV, inspect its trajectories, change them, and render
them back to sound. Analysis and synthesis run offline, in a small native program that Field
starts for you, so nothing here runs in real time.

Build the worker once with `./gradlew buildLorisNative` (setup below). In a Kotlin box, start with
the committed film-dialogue sample, or replace the path with your own WAV:

```diffstatic {data-lang=kotlin}
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await

val client = cached { LorisClient() }
val source = "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path
display("status", "Starting Loris…")
prop<LorisResult>(analyzed)
go {
    try {
        val result =
            client
                .analyzeWavAsync(
                    source,
                    options = LorisOptions(resolutionHz = 35.0, windowWidthHz = 80.0),
                    onProgress = { stage -> if (isActive) display("status", stage) },
                )
                .await()
        analyzed = result
        display(
            "partials and sound",
            result.visualizationHtml(
                LorisViewOptions(syncGroup = "my-partials"),
            ),
        )
        display("provenance", result.provenance)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        markdown("error", "Loris failed: ${(failure.message ?: failure.toString()).md}")
    }
}
```

`%docs` is the folder of documentation files that ships with Field. For your own recording, use `"~/Downloads/recording.wav".path`, or `"recording.wav".path` for
the document's `files/` directory. See [Files and paths](files_and_paths.md).

The box owns the coroutine: Stop or rerun cancels its current native future, and progress from
an ended run is ignored. The shared client and durable native cache are retained.

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
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await

val client = cached { LorisClient() }
val chunkStart = 6.0
val chunkDuration = 3.0
go {
    try {
        val analyzed =
            client
                .analyzeWavAsync(
                    "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path,
                    options = LorisOptions(
                        resolutionHz = 35.0,
                        windowWidthHz = 80.0,
                        frequencyDriftHz = 50.0,
                        amplitudeFloorDb = -55.0,
                    ),
                    onProgress = { if (isActive) display("status", it) },
                )
                .await()
        val model = analyzed.model
        val changed = model.copy(
            partials =
                model.partials.map { partial ->
                    partial.copy(
                        points =
                            partial.points.map { point ->
                                val u = (point.timeSeconds - chunkStart) / chunkDuration
                                if (u <= 0.0 || u >= 1.0) point
                                else {
                                    val envelope = sin(PI * u).pow(2)
                                    val octaves = 1.5 * envelope * sin(2 * PI * 4 * u)
                                    val shutter = .25 + .75 * (.5 + .5 * sin(2 * PI * 12 * chunkDuration * u))
                                    point.copy(
                                        frequencyHz = (point.frequencyHz * 2.0.pow(octaves)).coerceAtMost(
                                            model.sampleRate * .49,
                                        ),
                                        amplitude = point.amplitude * (1 - envelope + envelope * shutter),
                                        bandwidth = point.bandwidth * (1 - envelope) + .85 * envelope,
                                    )
                                }
                            },
                    )
                },
        )
        display("status", "Resynthesizing the whirlpool…")
        val edited = client.synthesizeAsync(changed).await()
        display(
            "edited tracks and sound",
            edited.visualizationHtml(
                LorisViewOptions(height = 400, syncGroup = "my-whirlpool"),
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        markdown("error", "Loris failed: ${(failure.message ?: failure.toString()).md}")
    }
}
```

Here is the gesture on the film excerpt. Compare seconds 6–9 with the reconstruction above: the
amber, noisy middle and the twisting tracks are what you hear.

```diffstatic_runner {data-lang=kotlin screenshot="assets/loris/spectral-whirlpool.png" capture="box" open-outputs="true" requires="loris" wait="5" padding="14" timeout="180" w="1080" h="900" embed=true name="loris-whirlpool" alt="Actual resynthesis of a localized spectral whirlpool: partial frequencies swirl through 6–9 seconds with amber noise bandwidth, plotted across the full native frequency band above its edited waveform and player."}
:::static
display("edited tracks and sound", LorisDocs.whirlpoolHtml())
```

<audio controls preload="metadata" src="assets/loris/spectral-whirlpool.wav"></audio>

The same example gesture is available in a box as
`model.spectralWhirlpool(6.0, 3.0)`. Its [manifest](assets/loris/whirlpool-provenance.json) records the
parent analysis, chunk range, sweep depth, shutter rate, bandwidth target and synthesis request.

## Change the sound

This box analyzes the source, lowers every partial's frequency to three quarters, removes the
noise bandwidth, then renders and displays the changed model. The breakpoints keep their stored
phases, so attacks change character more than a dedicated pitch shifter would make them.

```diffstatic {data-lang=kotlin}
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await

val client = cached { LorisClient() }
go {
    try {
        val analyzed =
            client
                .analyzeWavAsync(
                    "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path,
                    options = LorisOptions(
                        resolutionHz = 35.0,
                        windowWidthHz = 80.0,
                        frequencyDriftHz = 50.0,
                        amplitudeFloorDb = -55.0,
                    ),
                    onProgress = { if (isActive) display("status", it) },
                )
                .await()
        display("status", "Resynthesizing changed envelopes…")
        val edited =
            client
                .synthesizeAsync(
                    analyzed.model.transformed(
                        frequencyScale = 0.75,
                        bandwidthScale = 0.0,
                        gain = 0.8,
                    ),
                )
                .await()
        display(
            "changed sound",
            edited.visualizationHtml(
                LorisViewOptions(
                    axis = LorisFrequencyAxis.ERB,
                    syncGroup = "changed-partials",
                ),
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        markdown("error", "Loris failed: ${(failure.message ?: failure.toString()).md}")
    }
}
```

Here is that edit. Every segment is blue, because the edit set every bandwidth to zero:

```diffstatic_runner {data-lang=kotlin screenshot="assets/loris/tonal-edit.png" capture="box" open-outputs="true" requires="loris" wait="5" padding="14" timeout="180" w="1080" h="720" embed=true name="loris-tonal-edit" alt="Loris partial map and waveform of the film dialogue lowered to 0.75 of its original partial frequencies, with noise bandwidth removed; frequency spacing is ERB."}
:::static
display("changed partials and sound", LorisDocs.transformedHtml())
```

<audio controls preload="metadata" src="assets/loris/three-quarter-tonal.wav"></audio>

Other edits are ordinary data operations. Keep only tracks in a frequency band, delete short
partials, or replace a selected partial's breakpoint list. The IDs remain stable through
resynthesis. For example, after analyzing a source, this keeps only tracks whose mean frequency
is below 900 Hz:

```diffstatic {data-lang=kotlin}
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await

val bassModel = analyzed.model.copy(
    partials =
        analyzed.model.partials.filter { partial ->
            partial.points.map { it.frequencyHz }.average() < 900.0
        },
)
go {
    try {
        val it = client.synthesizeAsync(bassModel).await()
        display("low partials", it.visualizationHtml())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        markdown("error", "Loris failed: ${(failure.message ?: failure.toString()).md}")
    }
}
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

This box reads the dialogue with a short window, for fine time, and shows the reading with its resynthesis:

```diffstatic_runner {data-lang=kotlin screenshot="assets/loris/fine-time.png" capture="box" open-outputs="true" requires="loris" wait="20" padding="14" timeout="300" w="1080" h="1000" embed=true name="loris-fine-time" alt="A Loris partial map of the film dialogue, 0 to 5000 Hz on an ERB axis, read with windowWidthHz 160, above its reconstruction waveform and player. The tracks are short steep strokes that start and stop with each syllable, and the low harmonics are ragged."}
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await

val client = cached { LorisClient() }
val windowWidthHz = 160.0
go {
    try {
        val analyzed =
            client
                .analyzeWavAsync(
                    "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path,
                    options = LorisOptions(
                        resolutionHz = windowWidthHz / 2,
                        windowWidthHz = windowWidthHz,
                        hopSeconds = 0.005,
                        frequencyDriftHz = 50.0,
                        amplitudeFloorDb = -55.0,
                    ),
                )
                .await()
        display(
            "fine time · windowWidthHz = 160",
            analyzed.visualizationHtml(
                LorisViewOptions(height = 300, maximumHz = 5000.0, label = "fine time · windowWidthHz = 160"),
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        markdown("error", "Loris failed: ${(failure.message ?: failure.toString()).md}")
    }
}
```

The same box with `windowWidthHz = 40.0`, for fine frequency:

```diffstatic_runner {data-lang=kotlin screenshot="assets/loris/fine-frequency.png" capture="box" open-outputs="true" requires="loris" wait="20" padding="14" timeout="300" w="1080" h="1000" embed=true name="loris-fine-frequency" alt="The same dialogue read with windowWidthHz 40: the low harmonics run as long, closely spaced horizontal lines and the syllable onsets blur, above its reconstruction waveform and player."}
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await

val client = cached { LorisClient() }
val windowWidthHz = 40.0
go {
    try {
        val analyzed =
            client
                .analyzeWavAsync(
                    "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path,
                    options = LorisOptions(
                        resolutionHz = windowWidthHz / 2,
                        windowWidthHz = windowWidthHz,
                        hopSeconds = 0.005,
                        frequencyDriftHz = 50.0,
                        amplitudeFloorDb = -55.0,
                    ),
                )
                .await()
        display(
            "fine frequency · windowWidthHz = 40",
            analyzed.visualizationHtml(
                LorisViewOptions(height = 300, maximumHz = 5000.0, label = "fine frequency · windowWidthHz = 40"),
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        markdown("error", "Loris failed: ${(failure.message ?: failure.toString()).md}")
    }
}
```

The short window keeps the onsets of syllables as sharp starts and stops but leaves the low
harmonics ragged. The long window separates the low harmonics into long lines and smears the onsets.

## Label, align and morph two sounds

Loris's best-known trick is the morph: one sound turns into another with frequency, amplitude
and noise bandwidth moving on their own schedules. Before that can happen, both sounds need the
same numbering of their partials, and their important moments need to line up. Three calls do
this, and all three run Loris's own algorithms in the worker:

- `client.distill(model, reference)` labels each partial with the harmonic it sits nearest to,
  then joins the partials that share a label, so you are left with one partial per harmonic plus
  the unlabeled rest (label 0). `LorisReference.Estimated(minimumHz, maximumHz)` lets Loris
  estimate the fundamental itself; `LorisReference.constant(220.0)` or
  `LorisReference.Track(envelope)` gives it one you know. Pass `distill = false` to label without
  joining.
- `client.dilate(model, from, to)` warps time so each time in `from` lands on the matching time
  in `to`.
- `client.morph(a, b, frequency, amplitude, bandwidth)` morphs partials with the same label.
  Each argument is a `LorisEnvelope` from time in seconds to a weight, where 0 is `a` and 1 is
  `b`. Pass one envelope and all three follow it.

This box takes two two-second phrases from the film, labels each against its own estimated
fundamental, moves the loudest moment of the second onto the loudest moment of the first, and
morphs from the first to the second between 0.4 and 1.6 seconds:

```diffstatic {data-lang=kotlin}
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await

val client = cached { LorisClient() }
go {
    try {
        val analyzed =
            client
                .analyzeWavAsync(
                    "%docs/assets/subharmonic_sound/notld-barbara-source.wav".path,
                    options = LorisOptions(
                        resolutionHz = 35.0,
                        windowWidthHz = 80.0,
                        frequencyDriftHz = 50.0,
                        amplitudeFloorDb = -55.0,
                    ),
                    onProgress = { if (isActive) display("status", it) },
                )
                .await()
        val film = analyzed.model
        val a = client.distillAsync(film.crop(1.6, 3.6), LorisReference.Estimated(150.0, 450.0)).await().model
        val b = client.distillAsync(film.crop(11.5, 13.5), LorisReference.Estimated(60.0, 200.0)).await().model
        display("labels", "A ${a.labelCounts()}  B ${b.labelCounts()}")
        val end = a.durationSeconds
        val aligned =
            runInterruptible(Dispatchers.IO) {
                    client.dilate(
                        b,
                        [0.0, b.loudestTimeSeconds(), end],
                        [0.0, a.loudestTimeSeconds(), end],
                        durationSeconds = end,
                    )
                }
                .model
        val morphed =
            client.morphAsync(a.dropUnlabeled(), aligned.dropUnlabeled(), LorisEnvelope.ramp(0.4, 1.6)).await()
        display("morph", morphed.visualizationHtml(LorisViewOptions(maximumHz = 5000.0, syncGroup = "my-morph")))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        markdown("error", "Loris failed: ${(failure.message ?: failure.toString()).md}")
    }
}
```

The three plots below are that pipeline on the film. The long straight segments are where the distiller joined
pieces of one harmonic across a pause. Label 0 partials are left out of the morph here; keep them
and Loris crossfades them instead.

Phrase A, film seconds 1.6–3.6, distilled:

```diffstatic_runner {data-lang=kotlin screenshot="assets/loris/morph-a.png" capture="box" open-outputs="true" requires="loris" wait="5" padding="14" timeout="240" w="1080" h="900" embed=true name="loris-morph-a" alt="Loris partial map, 0–5000 Hz over two seconds, of phrase A distilled to one track per harmonic, with its reconstruction waveform and player."}
:::static
display("phrase A", LorisDocs.morphPanelHtml(0))
```

Phrase B, film seconds 11.5–13.5, distilled and dilated so its loudest moment meets A's:

```diffstatic_runner {data-lang=kotlin screenshot="assets/loris/morph-b.png" capture="box" open-outputs="true" requires="loris" wait="5" padding="14" timeout="240" w="1080" h="900" embed=true name="loris-morph-b" alt="Loris partial map of phrase B, distilled and time-dilated onto phrase A, with its reconstruction waveform and player."}
:::static
display("phrase B", LorisDocs.morphPanelHtml(1))
```

The morph, from A to B between 0.4 and 1.6 seconds:

```diffstatic_runner {data-lang=kotlin screenshot="assets/loris/morph-result.png" capture="box" open-outputs="true" requires="loris" wait="5" padding="14" timeout="240" w="1080" h="900" embed=true name="loris-morph-result" alt="Loris partial map of the morph of phrase A into phrase B, with its waveform and player."}
:::static
display("morph", LorisDocs.morphPanelHtml(2))
```

<audio controls preload="metadata" src="assets/loris/morph-a-to-b.wav"></audio>

The [morph manifest](assets/loris/morph-provenance.json) records both ranges, the label counts,
the dilation markers, the envelope and the gain applied before writing the file.

To keep the sources' frequencies while their amplitudes cross, give the morph separate envelopes:
`client.morph(a, b, frequency = LorisEnvelope.constant(0.0), amplitude = LorisEnvelope.ramp(0.0, 2.0))`.
An envelope can have as many breakpoints as you like,
`LorisEnvelope(listOf(0.0 to 0.0, 0.8 to 1.0, 1.2 to 0.2, 2.0 to 1.0))`, and holds its first and
last values outside them. `client.sift(model)` is Loris's Sieve: where same-label partials
overlap, it keeps the longest and unlabels the rest.

### Edits that stay in Kotlin

These change the breakpoints directly and return a new `LorisModel`; resynthesize it with
`client.synthesize(...)`:

| Call | What it does |
| --- | --- |
| `model.shiftPitch(cents)` | Transposes; also takes a `LorisEnvelope` of cents for a glide |
| `model.stretched(factor)` | Scales every time and the timeline |
| `model.tilt(dbPerOctave, pivotHz)` | Brightens (positive) or darkens (negative) around `pivotHz` |
| `model.keepLabels(1..8)`, `model.dropUnlabeled()` | Keeps partials by label, after `distill` |
| `model.minimumDuration(seconds)`, `model.minimumPeak(db)` | Drops short or quiet partials |
| `model.crop(start, end)` | Keeps a time range and moves it to zero |
| `model.labelCounts()` | How many partials carry each label |

Breakpoints that a transposition or tilt pushes above 0.49 × the sample rate are removed.

## A document to explore

The [`loris_partial_explorer`](agent_transcripts.md#loris_partial_explorer) starting point has a box for each step on this page: playing the source, analysis with its status, picking
partials, removing noise, the spectral whirlpool, resynthesis, a fine-time against
fine-frequency comparison, and the label, align and morph pipeline. Each box runs its own analysis, so you can start from any of them.

## Setup and caching

Loris is optional, and Field does not build it unless you ask. Build the worker with:

```diffstatic {data-lang=bash}
./gradlew buildLorisNative
./gradlew jvmTest --tests 'afield.audio.analysis.loris.LorisTest'
```

The build needs CMake 3.24+ and a C++17 compiler. It downloads a checksummed, pinned Loris source
archive once and uses the bundled FFT; no FFTW, Python binding, neural model or GPU is needed.
It has been built and run on Linux and macOS (Apple silicon); the Windows build is untested. `AFIELD_LORIS_EXECUTABLE`
or the JVM property `afield.loris.executable` selects another built worker.

Each request runs the worker with a timeout and reports its progress in the box that asked.
Results are cached in `build/field-loris/cache/` and survive closing the document; the cache is
keyed on the samples, the sample rate, the settings or edited breakpoints, and the worker build,
so any change runs the analysis again. Provenance is available on every result, and the
[published example manifest](assets/loris/provenance.json) records the source hash, controls and
transformation used for the sounds above. Audio remains at its native source rate; place it on
a 48 kHz project through an explicitly recorded conversion when needed.
