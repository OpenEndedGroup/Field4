# Making a sound in Field: every path there is

You want to hear something. There is more than one way, and this page is the complete map of them.

**Six things in Field make samples. Five things carry samples out of the process. Four bring them
in.** Those numbers are held by a guard rather than by memory: `SoundPathMap.SOURCE_COUNT` is `6` and
[the build fails](#the-map-is-derived-not-remembered) if `PATHS` disagrees.

??? aside "Why the count is six and not two"
    [Sound: from a pattern to something you can hear](sound.md) opened by saying Field had "two" things
    that turn a pattern's events into samples. That was true of *patterns* and was never true of *sound*,
    and Faust arriving made it visibly false — which is why the number is now derived rather than
    remembered.

---

## Thirty seconds

* **"I have a rhythm and I want to hear it."** → `pat-stand-in`. Nothing to install, works headless.
  [Patterns & Rhythm](pattern_language.md), then [Sound](sound.md).
* **"I want it to sound like actual drums."** → `dirt-samples`, if you have the Tidal quark.
  [Sound](sound.md).
* **"I want to build a synth out of parts."** → `signal-graph`. Oscillators, filters, envelopes,
  Kotlin operators. [DoughDSP Sound Examples](dough_dsp_sound.md).
* **"I want to write the DSP itself, properly."** → `faust`. [Faust](faust.md).
* **"I want to write music, not DSP."** → `sonic-pi`. [Sonic Pi](sonic_pi.md).
* **"I want it in a file, or in a docs page."** → the `wav-file` sink. Offline, deterministic.
* **"I want a player in the box's output."** → the `box-output-player` sink.
* **"I want it out of the speakers, now."** → return `audio(signal)` from the box and the
  `audio-patch-seam` does the rest.

---

## The six that make samples

| path | what it is | needs | offline? | on the seam? | page |
| --- | --- | --- | --- | --- | --- |
| `pat-stand-in` | a pattern's events through a deliberately tiny voice: one oscillator, one one-pole lowpass, one decay envelope, per event. `fun patRenderStereo` in `src/jvmMain/kotlin/afield/four/synth/PatSoundDocs.kt` | nothing | offline only | no | [Sound](sound.md) |
| `dirt-samples` | the same events as real Tidal one-shots. `fun patRenderStereoDirt` in `src/jvmMain/kotlin/afield/four/synth/DirtRender.kt` offline; `DirtSamplePlayer` live | the Dirt-Samples quark | both | live half only | [Sound](sound.md) |
| `signal-graph` | Field's own DSP graph — `abstract class Signal` in `src/jvmMain/kotlin/afield/four/synth/Signal.kt`, plus the oscillators, filters and the DoughDSP ports | nothing | both | **yes** | [DoughDSP](dough_dsp_sound.md) |
| `faust` | a Faust program compiled at runtime and dropped into that same graph. `class FaustNode` in `src/jvmMain/kotlin/afield/four/synth/FaustDsp.kt` | nothing — libfaust is vendored as wasm | both | **yes** | [Faust](faust.md) |
| `dough-engine` | the whole dough/Strudel engine as a wasm module, with its own 128-frame block loop. `class DoughEngine` in `src/jvmMain/kotlin/afield/four/synth/Dough.kt` | the dough wasm build | both | no | — |
| `sonic-pi` | a Ruby program handed to Sonic Pi's own scsynth over OSC. `class SonicPi` in `src/jvmMain/kotlin/afield/four/synth/SonicPi.kt` | a local Sonic Pi v5 | realtime only | no | [Sonic Pi](sonic_pi.md) |

Two of those six are pattern voices, and those two are what [Sound](sound.md) is about — in depth,
with the fallback behaviour and the provenance audit. It remains the page to read once you have
picked. This one only helps you pick.

`sonic-pi` is the odd one and worth naming as such: it is the only path where **no sample ever
enters this process**. Field sends text and receives log lines; the audio exists entirely inside
another program's synthesiser. Everything you can do to samples in the other five — filter them,
plot them, write them to a file — you cannot do to those. The way back in is `link-audio-in`, below.

## The five that carry samples out

| path | what it is | needs | page |
| --- | --- | --- | --- |
| `audio-patch-seam` | **the default.** Return `audio(signal)` from a box; one engine thread pulls blocks until the box stops. `internal interface Renderer` in `src/jvmMain/kotlin/afield/four/synth/FieldAudioEngine.kt` | an output device | — |
| `wav-file` | a 16-bit PCM `.wav` on disk. `fun writeStereo16` in `src/jvmMain/kotlin/afield/four/audio/WavFloatArrayWriter.kt` | nothing | [Sound](sound.md) |
| `box-output-player` | samples encoded to a WAV in memory, base64'd into a `data:` URI, shown as an `<audio>` element in the box's output. `fun renderSamplesHtml` in `src/jvmMain/kotlin/afield/stdlib/AudioVisualizer.kt` | nothing | [Visualizers](visualizers.md) |
| `spatial-openal` | a **second, independent** OpenAL context: 3D positional audio, optional HRTF, EFX reverb. `class SimpleSpatialAudioEngine` in `src/jvmMain/kotlin/afield/four/audio/SimpleSpatialAudio.kt` | an output device | — |
| `link-audio-out` | the engine's output mirrored into Ableton Link Audio, tempo-locked, so another app on the network hears it. `fun enableAudioOutput` in `src/jvmMain/kotlin/afield/four/synth/AbletonLinkAudio.kt` | Link peers | [Sonic Pi](sonic_pi.md) |

Every audio figure in these docs — all fifteen of them — is the `wav-file` sink. That is the whole
reason they regenerate on a build machine with no sound card, and it is what makes the corpus
auditable at all.

## The four that bring samples in

| path | what it is | needs | page |
| --- | --- | --- | --- |
| `device-input` | OpenAL capture as a `Signal`, so a microphone is just another node. `fun stereoInput()` in `src/jvmMain/kotlin/afield/four/synth/FieldAudioEngine.kt` | an input device + OS permission | — |
| `link-audio-in` | another application's output as a `Signal`. `fun stereoInput(channelName…)` in `src/jvmMain/kotlin/afield/four/synth/AbletonLinkAudio.kt` | Link peers | [Sonic Pi](sonic_pi.md) |
| `video-audio` | a file decoded by FFmpeg and played through a **third** OpenAL context, kept in step by its own playback clock. `class AudioDecoder` in `src/jvmMain/kotlin/afield/four/texture/video/AudioDecoder.kt` | the bundled FFmpeg | `dev_docs/AUDIO_OPTIONS.md`, in the repo |
| `wav-file-in` | a `.wav` off disk as `FloatArray`s — the counterpart of `wav-file`. `WavFloatArrayLoader` in `src/jvmMain/kotlin/afield/four/audio/WavFloatArrayLoader.kt` | nothing | — |

Link Audio is how `sonic-pi` and Field reach each other without a virtual cable — but be precise
about which direction is *known* to work. **Field → Sonic Pi is measured**: Field publishes a channel
through `enableAudioOutput` and Sonic Pi v5's `link_audio` subscribes to it; a probe puts a 440 Hz tone in
one end and looks for it at the other. See
[Sonic Pi](sonic_pi.md), including the Link visibility toggle that defaults to Off and gives you
silence with nothing to explain it. **Sonic Pi → Field** is supported by both APIs — Sonic Pi has
`link_audio_publish_set!` and Field has `linkAudioIn(channelName)` — and nothing in this tree
exercises it, so it is listed as a path and not as a measurement.

---

## One seam, and seven of fifteen paths on it

There is exactly one signature everything realtime meets at:

```diffstatic {data-lang=kotlin}
fun render(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext)
```

`AudioPatchSpec.Renderer` declares it. `StereoRenderable` declares it, character for character. So
does every node in the graph, `DirtSamplePlayer`, and `FaustNode`. Seven of the fifteen paths above
sit on it, and being on it is the single most useful fact about a path: **a path on the seam
composes with every other path on the seam for free, and a path off it composes with nothing.**

The clearest evidence is Faust. Embedding a whole third-party compiler was a lot of work, and
*none* of it was plumbing — `class FaustNode` is 23 lines that fill an input buffer and call
`dsp.render`. It reached the speakers, the engine's visualisers, the Link output and the box
lifecycle on the day it landed, because it landed on the seam.

The three paths off it are off it for three different, legible reasons, and the reasons are worth
more than the fact:

* **`pat-stand-in`** is a free function that returns a whole buffer. A pattern has to be *queried*
  over an arc, so there is no "next 128 frames" to ask it for without a scheduler — which is what
  `DirtSamplePlayer` is, and why the sampled half of `dirt-samples` is on the seam and the offline
  half is not.
* **`dough-engine`** is a wasm module with its own fixed 128-frame block size and its own event
  queue. It could be wrapped, and has not been.
* **`sonic-pi`** is a different process.

The seam is also the least-documented thing in Field's audio stack, which is the finding this page
was written out of rather than a complaint: **no published page mentions `AudioPatchSpec` at all**,
and it is what every box that makes a noise actually returns. Five of the fifteen paths above have
no page — `audio-patch-seam`, `dough-engine`, `spatial-openal`, `device-input`, `wav-file-in` — and
the em-dashes in the tables are honest rather than decorative. `afield.four.synth` is also absent
from the [generated codebase reference](reference/index.md), whose source roots are `afield/stdlib`
and `afield/visualization` only, so the audio stack is the least-surfaced major subsystem Field has.

---

## One buffer, three paths

The map claims the paths are different. Here they are being different, on **bit-identical input**.

One buffer: `pat("bd ~ sd ~, hh*8")` at 2 cps for four cycles, rendered through `patVoice` with the
voice's own filter wide open. Then a 900 Hz lowpass, three times, from three different paths.

```diffstatic_runner {data-lang=kotlin audio="assets/sound_path_map/same-input-dry.wav" audio-spec="same-input-dry" audio-renderer="afield.four.synth.SoundPathMap" embed=true hidden=true name="sound-path-map-dry"}
// SoundPathMap.dryBeat()
// patRenderStereo(pat("bd ~ sd ~, hh*8") with gain(0.9), 4, 2.0)
```

**Path: `pat-stand-in`, unfiltered.** This exact buffer is the input to both figures below.

```diffstatic_runner {data-lang=kotlin audio="assets/sound_path_map/same-input-signal-graph.wav" audio-spec="same-input-signal-graph" audio-renderer="afield.four.synth.SoundPathMap" embed=true hidden=true name="sound-path-map-signal-graph"}
// SoundPathMap.throughSignalGraph(SoundPathMap.dryBeat())
// BufferSignal(dry) through ChamberlinLowpass(cutoff = Param(900f))
```

**Path: `signal-graph`.** The buffer above, through `ChamberlinLowpass` at 900 Hz.

```diffstatic_runner {data-lang=kotlin audio="assets/sound_path_map/same-input-faust.wav" audio-spec="same-input-faust" audio-renderer="afield.four.synth.SoundPathMap" embed=true hidden=true name="sound-path-map-faust"}
// SoundPathMap.throughFaust(SoundPathMap.dryBeat())
// process = fi.lowpass(1, 900), fi.lowpass(1, 900);  — compiled at runtime, no audio device
```

**Path: `faust`.** The same buffer, through `fi.lowpass(1, 900)`, compiled at runtime.

The hats go and the kick stays, twice, differently — and the *difference* between the two is the
honest part of the figure. `ChamberlinLowpass` is a two-pole state-variable filter, 12 dB per
octave. `fi.lowpass(1, 900)` is first-order, 6 dB per octave. Ask both for 900 Hz and the steeper one
takes far more of the hats away, which is exactly what the spectra show and what the numbers say:

| figure | spectral centroid | vs dry | rolloff |
| --- | --- | --- | --- |
| `same-input-dry` | 10914 Hz | — | none; the hats are 6.2 kHz noise |
| `same-input-signal-graph` | 2255 Hz | 0.21× | ~12 dB/oct above the knee |
| `same-input-faust` | 3900 Hz | 0.36× | ~6 dB/oct |

Their waveform similarity to each other is **0.9584** — high, because they are lowpasses of one
buffer; not 1.0, because they are not the same lowpass. A figure in which they matched exactly would
mean one of them was not running. Every number here is printed by
`run_field_tests.py --id synth.sound.path.map`, which is where to re-measure them.

### What the three clips do not tell you: loudness

**All three players above are peak-normalized to 0.9 of full scale, so they compare timbre and not
level.** That is `fun writeStereo16` being called with `normalizePeak = 0.9f`, and it means every
committed clip has the identical peak sample value of 29490. Listen to them back to back and they
will seem to arrive at a matched volume; that is the writer's doing, not the paths'.

The level the filters actually produced is in the pre-normalization buffers, and it is not matched at
all: the dry beat peaks at **2.005** — well past full scale, because ten voices a cycle sum — and the
two filtered renders at **0.934** and **1.069**. A 12 dB/octave filter removes more energy than a
6 dB/octave one, and that shows up as level before it is scaled away.

**The centroids in the table are measured on those pre-normalization buffers**, not on the committed
`.wav`s: `brightnessHz(audio.mono)` runs on the rendered buffer and `writeStereo16` is called
afterwards. It happens not to matter — peak normalization is a single scalar multiply, so the
spectral centroid, the rolloff slopes and the 0.9584 similarity are all invariant under it — but
"the number and the file were computed at different points" is worth saying out loud rather than
leaving a reader to assume.

One small thing that fell out of decoding the committed bytes, and it is the abstract 0.9584 made
concrete: the two filtered clips reach their maximum at frames **25778** and **25777** — one sample
apart — while the dry beat peaks at 22632. Two lowpasses asked for the same 900 Hz, agreeing on
which kick is loudest and disagreeing by exactly one sample about when.

### The seam claim, as a number

That comparison shows the paths differ. The stronger claim on this page is that they are *driven
identically*, and a lowpass cannot show it. A gain can. The same buffer, multiplied by 0.5 by the
graph (`Signal * 0.5f`) and by Faust (`process = _,_ : *(0.5), *(0.5);`), over 44100 samples:

**worst per-sample disagreement 0.000e+00** — bit-equal. Two entirely separate implementations, one
seam, no adapter in between. `SoundPathMap.SEAM_AGREEMENT_CEILING` pins it at `1e-6`.

---

## The map is derived, not remembered

A hand-maintained list of render paths is exactly the artifact that rots, so this one is checked
against the code in `check`, in the shape [`DocsAudioProvenance`](sound.md#and-the-same-question-asked-of-every-clip-in-the-docs)
already established: declare, re-derive from the tree, fail on disagreement.

`SoundPathMap` declares the paths, and the `synth.sound.path.map` smoke then asserts five things:

1. **The counts.** `SOURCE_COUNT = 6`, `SINK_COUNT = 5`, `INPUT_COUNT = 4`, `SEAM_COUNT = 7`. The
   last one is the interesting ratchet: if a later change adds a path *beside* the seam rather than
   *on* it, `PATHS` grows while `SEAM_COUNT` does not, and that is a finding rather than a number to
   edit.
2. **Undeclared paths, derived from markers.** Four literals in the tree can only mean "a new way in
   or out of this process": `alcOpenDevice(` (three files, three genuinely separate OpenAL
   contexts), `alcCaptureOpenDevice(` (one), `fun renderWav(name: String, outPath: String)` (four —
   the docs-audio producer contract) and `WavFloatArrayWriter.encode` (five). Every file carrying
   one is attributed to a declared path, and a new hit names itself in the failure.
3. **Stale declarations.** The same check in reverse: a declared file that no longer carries its
   marker is a citation that has rotted, and fails the same way.
4. **Every citation on this page resolves.** Each path declares an entry-point *symbol*, not a line
   number, and the guard greps the file for it. So a rename fails the build instead of leaving a
   dead symbol in the tables above.
5. **This page names every path.** Both file and symbol, verbatim, for all fifteen. An omitted row
   fails.

The comparison figures are checked too, and not by eye: both filters must take the centroid below
0.6× the dry buffer's, the two must still correlate above 0.5 with each other, and the gain
agreement must stay under `1e-6`. `DocsAudioProvenance` separately re-renders all three committed
`.wav`s through the same `renderWav` contract the docs build uses and correlates them against the
bytes on disk, so a figure the code has stopped producing fails as well.

### A forward note

A Faust path that is polyphonic and pattern-driven is in flight and is not in this map, because it
did not exist when the map was written. When it lands: add one `SoundPath` to `SoundPathMap.PATHS`
with `onSeam = true`, bump `SOURCE_COUNT` to `7`, and name its entry point in the first table above.
The guard's failure message says the same thing, so the merge resolves itself.

---

## A box that plays the comparison

A loadable Field document is committed at `public/docs/assets/sound_path_map/`. Open
`public/docs/assets` as the workspace and `sound_path_map` as the document, then run the boxes top
to bottom: the first prints the map, the second plays the three renders back to back and plots
their short-time brightness so you can see the filters as well as hear them.
