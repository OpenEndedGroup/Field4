# Making a sound in Field: every path there is

You want to hear something. There is more than one way, and this page maps all of them.

**Seven things in Field make samples. Five things carry samples out of the process. Four bring them
in.** A guard holds those numbers to the code: `SoundPathMap.SOURCE_COUNT` is `7` and
[the build fails](#how-the-map-is-checked) if `PATHS` disagrees.

---

## Thirty seconds

* **"I have a rhythm and I want to hear it."** → `pat-stand-in`. Nothing to install; `patSynth` for
  the speakers, `patRenderStereo` for a file, and the same voice either way.
  [Patterns & Rhythm](pattern_language.md), then [Sound](sound.md).
* **"I want it to sound like actual drums."** → `dirt-samples`, if you have the Tidal quark.
  [Sound](sound.md).
* **"I want to build a synth out of parts."** → `signal-graph`. Oscillators, filters, envelopes,
  Kotlin operators. [DoughDSP Sound Examples](dough_dsp_sound.md).
* **"I want to write the DSP itself, properly."** → `faust`. [Faust](faust.md).
* **"I want a pattern to play that DSP as an instrument, in chords."** → `faust-poly`.
  [Faust](faust.md).
* **"I want to write music, not DSP."** → `sonic-pi`. [Sonic Pi](sonic_pi.md).
* **"I want it in a file, or in a docs page."** → the `wav-file` sink. Offline, deterministic.
* **"I want a player in the box's output."** → the `box-output-player` sink.
* **"I want it out of the speakers, now."** → return `audio(signal)` from the box and the
  `audio-patch-seam` does the rest.

---

## The seven that make samples

| path | what it is | needs | offline? | on the seam? | page |
| --- | --- | --- | --- | --- | --- |
| `pat-stand-in` | a pattern's events through a very small voice: one oscillator, one one-pole lowpass, one decay envelope, per event. `fun patRenderStereo` in `src/jvmMain/kotlin/afield/four/synth/PatSoundDocs.kt` offline; `patSynth` live | nothing | both | **yes** | [Sound](sound.md) |
| `dirt-samples` | the same events as real Tidal one-shots. `fun patRenderStereoDirt` in `src/jvmMain/kotlin/afield/four/synth/DirtRender.kt` offline; `DirtSamplePlayer` live | the Dirt-Samples quark | both | live half only | [Sound](sound.md) |
| `signal-graph` | Field's own DSP graph — `abstract class Signal` in `src/jvmMain/kotlin/afield/four/synth/Signal.kt`, plus the oscillators, filters and the DoughDSP ports | nothing | both | **yes** | [DoughDSP](dough_dsp_sound.md) |
| `faust` | a Faust program compiled at runtime and dropped into that same graph. `class FaustNode` in `src/jvmMain/kotlin/afield/four/synth/FaustDsp.kt` | nothing — libfaust is vendored as wasm | both | **yes** | [Faust](faust.md) |
| `faust-poly` | the same program played as an *instrument*: N instances of one compiled module, allocated to notes by a pattern, each note landing on the sample it was scheduled for. `class FaustVoiceBank` in `src/jvmMain/kotlin/afield/four/synth/FaustPoly.kt` | nothing — as `faust` | both | **yes** | [Faust](faust.md) |
| `dough-engine` | the whole dough/Strudel engine as a wasm module, with its own 128-frame block loop. `class DoughEngine` in `src/jvmMain/kotlin/afield/four/synth/Dough.kt`, wrapped onto the seam by `DoughAudioSink` | the dough wasm build | both | **yes**, through `parkDough` | — |
| `sonic-pi` | a Ruby program handed to Sonic Pi's own scsynth over OSC. `class SonicPi` in `src/jvmMain/kotlin/afield/four/synth/SonicPi.kt` | a local Sonic Pi v5 | realtime only | no | [Sonic Pi](sonic_pi.md) |

Three of those seven are pattern voices: `pat-stand-in`, `dirt-samples` and `faust-poly`. The first
two are what [Sound](sound.md) covers, with the fallback behaviour and the provenance audit; the
third is on [Faust](faust.md), because what it needs explaining is the voice allocator rather than
the pattern. Read whichever you have picked; this page only helps you pick.

`sonic-pi` is the exception: it is the only path where **no sample ever enters this process**.
Field sends text and receives log lines; the audio exists entirely inside another program's
synthesiser. Everything you can do to samples in the other six — filter them, plot them, write
them to a file — you cannot do to those. The way back in is `link-audio-in`, below.

## The five that carry samples out

| path | what it is | needs | page |
| --- | --- | --- | --- |
| `audio-patch-seam` | **the default.** Return `audio(signal)` from a box; one engine thread pulls blocks until the box stops. `internal interface Renderer` in `src/jvmMain/kotlin/afield/four/synth/FieldAudioEngine.kt` | an output device | — |
| `wav-file` | a 16-bit PCM `.wav` on disk. `fun writeStereo16` in `src/jvmMain/kotlin/afield/four/audio/WavFloatArrayWriter.kt` | nothing | [Sound](sound.md) |
| `box-output-player` | samples encoded to a WAV in memory, base64'd into a `data:` URI, shown as an `<audio>` element in the box's output. `fun renderSamplesHtml` in `src/jvmMain/kotlin/afield/stdlib/AudioVisualizer.kt` | nothing | [Visualizers](visualizers.md) |
| `spatial-openal` | a **second, independent** OpenAL context: 3D positional audio, optional HRTF, EFX reverb. `class SimpleSpatialAudioEngine` in `src/jvmMain/kotlin/afield/four/audio/SimpleSpatialAudio.kt` | an output device | — |
| `link-audio-out` | the engine's output mirrored into Ableton Link Audio, tempo-locked, so another app on the network hears it. `fun enableAudioOutput` in `src/jvmMain/kotlin/afield/four/synth/AbletonLinkAudio.kt` | Link peers | [Sonic Pi](sonic_pi.md) |

Every audio figure in these docs, all seventeen of them, comes out of the `wav-file` sink, which is
why they regenerate on a build machine with no sound card.

## Isolated spatial audio and offline checks

`SimpleSpatialAudioEngine(threadLocalContext = true)` keeps an OpenAL context on the
thread that initializes it. Use its engine and sources on that thread, and destroy
it there. This mode requires `ALC_EXT_thread_local_context`; it fails initialization
when the extension is absent. It preserves another thread's ordinary audio output
and restores a previous thread-local context when destroyed. Close nested contexts
in reverse creation order. The existing shared engine keeps its default behaviour.

For deterministic headphone rendering without opening speakers, add
`loopbackSampleRate = 48000`. Initialize normally, request HRTF, then call
`renderSamples(output, frames)` with a reusable direct stereo `ShortBuffer`. Only
that call advances playback. Inspect `hrtfEnabled` and `hrtfStatus` for the actual
result; a requested mode is not proof that the device enabled it. `sourceCount` and
`bufferCount` help verify owner cleanup. A scalar `setListener` overload avoids
allocating temporary vectors for frequent position/orientation updates.

The `audio.spatial.loopback` smoke (`SpatialAudioLoopbackSmoke`) renders through the
real OpenAL mixer: it checks left/right energy exchanging when the head rotates,
Doppler under listener motion, source/buffer cleanup, and restoration of both the
OpenAL context and LWJGL capabilities after nested use. These checks require the
bundled OpenAL Soft library, but no physical output device. They do not establish
how an individual listener perceives an HRTF through their headphones.

## The four that bring samples in

| path | what it is | needs | page |
| --- | --- | --- | --- |
| `device-input` | OpenAL capture as a `Signal`, so a microphone is just another node. `fun stereoInput()` in `src/jvmMain/kotlin/afield/four/synth/FieldAudioEngine.kt` | an input device + OS permission | — |
| `link-audio-in` | another application's output as a `Signal`. `fun stereoInput(channelName…)` in `src/jvmMain/kotlin/afield/four/synth/AbletonLinkAudio.kt` | Link peers | [Sonic Pi](sonic_pi.md) |
| `video-audio` | a file decoded by FFmpeg and played through a **third** OpenAL context, kept in step by its own playback clock. `class AudioDecoder` in `src/jvmMain/kotlin/afield/four/texture/video/AudioDecoder.kt` | the bundled FFmpeg | `dev_docs/AUDIO_OPTIONS.md`, in the repo |
| `wav-file-in` | a `.wav` off disk as `FloatArray`s — the counterpart of `wav-file`. `WavFloatArrayLoader` in `src/jvmMain/kotlin/afield/four/audio/WavFloatArrayLoader.kt` | nothing | — |

Link Audio is how `sonic-pi` and Field reach each other without a virtual cable. The two directions
are not equally well tested. **Field → Sonic Pi** is: Field publishes a channel through
`enableAudioOutput`, Sonic Pi v5's `link_audio` subscribes to it, and a probe puts a 440 Hz tone in
one end and looks for it at the other. See [Sonic Pi](sonic_pi.md), including the Link visibility
toggle that defaults to Off and gives you silence with nothing to explain it. **Sonic Pi → Field**
has an API on both sides, `link_audio_publish_set!` and `linkAudioIn(channelName)`, but nothing in
this tree exercises it.

---

## One seam, and ten of sixteen paths on it

There is exactly one signature everything realtime meets at:

```diffstatic {data-lang=kotlin}
fun render(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext)
```

`AudioPatchSpec.Renderer` declares it. `StereoRenderable` declares it, character for character. So
does every node in the graph, `DirtSamplePlayer`, `FaustNode`, `FaustPolyNode`, `DoughAudioSink` and
`PatSynthBank`. Ten of the sixteen paths above sit on it. **A path on the seam composes with every
other path on the seam; a path off it composes with nothing.**

Faust is the clearest case of what that buys, twice over. `class FaustNode` is 23 lines that fill an
input buffer and call `dsp.render`. `class FaustPolyNode` is five lines, and behind them is an event
scheduler, a voice allocator and N WebAssembly instances in one GraalVM context — none of which the
seam has to know about. Being on it is what gets both of them the speakers, the engine's visualisers,
the Link output and the box lifecycle, with no adapter written for any of them.

The two paths off the seam are off it for two different reasons:

`sonic-pi` is the one path off the seam, and no wrapper reaches it: it is a different process, and
no sample ever enters this one.

Two of the sixteen used to be off it. `dough-engine` was described here as something that "could be
wrapped, and has not been", and it is wrapped: `class DoughAudioSink` is a `StereoNode` in the same
file as `DoughEngine`, and `parkDough` registers `audio(sink)` with the engine. Nothing noticed for a
month, because the guard's markers were all device calls and WAV encodes and a node on the seam
contains neither; the marker that now catches this, `override fun generateStereo(`, reported it and
`faust-poly` together on its first run. (`DoughAudioSink` renders in whole 128-frame blocks and
copies out only what was asked for, so it drops samples at any engine buffer size that is not a
multiple of 128. The default is 512.)

`pat-stand-in` was the other, and getting a pattern onto the seam is worth reading as the general
case, because a pattern has to be *queried* over an arc and there is no "next 128 frames" to ask one
for. The answer is a scheduler in two halves: a thread that queries a cycle at a time and turns
events into notes stamped with the absolute frame they start on, and an audio-thread voice bank whose
only remaining job is an integer comparison. `DirtSamplePlayer` and `FaustPatternFeeder` divide the
work the same way, and being early costs nothing because what crosses between the halves is frames.
See [A pattern that moves particles](chain_pattern_particles.md).

`AudioPatchSpec` is what every box that makes a noise returns. Five of the sixteen paths above have
no page of their own — `audio-patch-seam`, `dough-engine`, `spatial-openal`, `device-input`,
`wav-file-in` — and the em-dash in those table cells means absent, not merely unlisted. For those
five, this map and [`afield.four.synth` in the generated reference](reference/synthesis.md) are what
there is.

---

## One buffer, three paths

Here are three of the paths on **bit-identical input**.

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

The hats go and the kick stays, twice, differently. `ChamberlinLowpass` is a two-pole state-variable
filter, 12 dB per octave; `fi.lowpass(1, 900)` is first-order, 6 dB per octave. Ask both for 900 Hz
and the steeper one takes more of the hats away:

| figure | spectral centroid | vs dry | rolloff |
| --- | --- | --- | --- |
| `same-input-dry` | 10914 Hz | — | none; the hats are 6.2 kHz noise |
| `same-input-signal-graph` | 2255 Hz | 0.21× | ~12 dB/oct above the knee |
| `same-input-faust` | 3900 Hz | 0.36× | ~6 dB/oct |

Their waveform similarity to each other is **0.9584** — high, because they are lowpasses of one
buffer; not 1.0, because they are not the same lowpass. Every number here is printed by
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
afterwards. Peak normalization is a single scalar multiply, so the spectral centroid, the rolloff
slopes and the 0.9584 similarity are the same either way.

The committed bytes make that similarity concrete. The two filtered clips reach their maximum at
frames **25778** and **25777**, one sample apart, while the dry beat peaks at 22632.

### The seam claim, as a number

A lowpass shows the paths differ. It cannot show the stronger claim, that they are driven
identically; a gain can. The same buffer, multiplied by 0.5 by the graph (`Signal * 0.5f`) and by
Faust (`process = _,_ : *(0.5), *(0.5);`), over 44100 samples:

**worst per-sample disagreement 0.000e+00** — bit-equal. Two entirely separate implementations, one
seam, no adapter in between. `SoundPathMap.SEAM_AGREEMENT_CEILING` pins it at `1e-6`.

---

## How the map is checked

A hand-maintained list of render paths is exactly the artifact that rots, so this one is checked
against the code in `check`, in the shape [`DocsAudioProvenance`](sound.md#and-the-same-question-asked-of-every-clip-in-the-docs)
uses: declare, re-derive from the tree, fail on disagreement.

`SoundPathMap` declares the paths, and the `synth.sound.path.map` smoke then asserts five things:

1. **The counts.** `SOURCE_COUNT = 7`, `SINK_COUNT = 5`, `INPUT_COUNT = 4`, `SEAM_COUNT = 9`. If a
   change adds a path beside the seam instead of on it, `PATHS` grows while `SEAM_COUNT` does not.
   Read that failure before editing the number.
2. **Undeclared paths, derived from markers.** Five literals in the tree can only mean "a new way in
   or out of this process": `alcOpenDevice(` (three files, three genuinely separate OpenAL
   contexts), `alcCaptureOpenDevice(` (one), `fun renderWav(name: String, outPath: String)` (five —
   the docs-audio producer contract), `WavFloatArrayWriter.encode` (six) and
   `override fun generateStereo(` (eight — every node on the seam). Every file carrying one is
   attributed to a declared path, and a new hit names itself in the failure.

    The fifth was added on 2026-09-12, because the first four missed a whole sample source. A
    pattern-driven Faust instrument opens no device and writes no WAV, so `FaustPoly.kt` sat in the
    tree carrying none of these literals: the counts above stayed at six and seven, every check
    stayed green, and this page under-reported what Field can do. The seam is the one thing a new
    realtime source cannot avoid, which is why the marker that closes the hole greps for the seam.
    It still cannot see a source that is off the seam entirely — another `sonic-pi`, another
    whole-buffer free function — and `SoundPathMap`'s own KDoc says so.
3. **Stale declarations.** The same check in reverse: a declared file that no longer carries its
   marker is a citation that has rotted, and fails the same way.
4. **Every citation on this page resolves.** Each path declares an entry-point *symbol*, not a line
   number, and the guard greps the file for it. So a rename fails the build instead of leaving a
   dead symbol in the tables above.
5. **This page names every path.** Both file and symbol, verbatim, for all sixteen. An omitted row
   fails.

The comparison figures are checked too: both filters must take the centroid below 0.6× the dry
buffer's, the two must still correlate above 0.5 with each other, and the gain
agreement must stay under `1e-6`. `DocsAudioProvenance` separately re-renders all three committed
`.wav`s through the same `renderWav` contract the docs build uses and correlates them against the
bytes on disk, so a figure the code has stopped producing fails as well.

---

## A box that plays the comparison

A loadable Field document is committed at `public/docs/assets/sound_path_map/`. Open
`public/docs/assets` as the workspace and `sound_path_map` as the document, then run the boxes top
to bottom: the first prints the map, the second plays the three renders back to back and plots
their short-time brightness so you can see the filters as well as hear them.
