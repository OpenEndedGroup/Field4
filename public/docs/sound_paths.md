# Making a sound in Field: every path there is

You want to hear something. There is more than one way, and this page maps all of them.

**Eight things in Field make samples. Five things carry samples out of the process. Four bring them
in.** A guard holds those numbers to the code: `SoundPathMap.SOURCE_COUNT` is `8`, and a path that
arrives in the tree and not in the tables below
[fails the build](#where-the-list-comes-from).

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
* **"I want the rhythm to come from *somewhere* — a direction, a distance."** → `pat-spatial`,
  through headphones. [A pattern in space](pat_spatial.md).
* **"I want to write music, not DSP."** → `sonic-pi`. [Sonic Pi](sonic_pi.md).
* **"I want it in a file, or in a docs page."** → the `wav-file` sink. Offline, deterministic.
* **"I want a player in the box's output."** → the `box-output-player` sink.
* **"I want it out of the speakers, now."** → return `audio(signal)` from the box and the
  `audio-patch-seam` does the rest.

---

## The eight that make samples

| path | what it is | needs | offline? | on the seam? | page |
| --- | --- | --- | --- | --- | --- |
| `pat-stand-in` | a pattern's events through a very small voice: one oscillator, one one-pole lowpass, one decay envelope, per event. `fun patRenderStereo` in `src/jvmMain/kotlin/afield/four/synth/PatSoundDocs.kt` offline; `patSynth` live | nothing | both | **yes** | [Sound](sound.md) |
| `dirt-samples` | the same events as real Tidal one-shots. `fun patRenderStereoDirt` in `src/jvmMain/kotlin/afield/four/synth/DirtRender.kt` offline; `DirtSamplePlayer` live | the Dirt-Samples quark | both | live half only | [Sound](sound.md) |
| `signal-graph` | Field's own DSP graph — `abstract class Signal` in `src/jvmMain/kotlin/afield/four/synth/Signal.kt`, plus the oscillators, filters and the DoughDSP ports | nothing | both | **yes** | [DoughDSP](dough_dsp_sound.md) |
| `faust` | a Faust program compiled at runtime and dropped into that same graph. `class FaustNode` in `src/jvmMain/kotlin/afield/four/synth/FaustDsp.kt` | nothing — libfaust is vendored as wasm | both | **yes** | [Faust](faust.md) |
| `faust-poly` | the same program played as an *instrument*: N instances of one compiled module, allocated to notes by a pattern, each note landing on the sample it was scheduled for. `class FaustVoiceBank` in `src/jvmMain/kotlin/afield/four/synth/FaustPoly.kt` | nothing — as `faust` | both | **yes** | [Faust](faust.md) |
| `dough-engine` | the whole dough/Strudel engine as a wasm module, with its own 128-frame block loop. `class DoughEngine` in `src/jvmMain/kotlin/afield/four/synth/Dough.kt`, wrapped onto the seam by `DoughAudioSink` | the dough wasm build | both | **yes**, through `parkDough` | — |
| `pat-spatial` | a pattern **placed in space**: one dough engine per `spat` tag, its output taken as mono and aimed by Steam Audio's HRTF. `fun patSpatial` in `src/jvmMain/kotlin/afield/four/synth/PatSpatial.kt` | dough, plus an uncommitted `libphonon` — `./gradlew fetchSteamAudioNatives` | realtime only | **yes** | [A pattern in space](pat_spatial.md) |
| `sonic-pi` | a Ruby program handed to Sonic Pi's own scsynth over OSC. `class SonicPi` in `src/jvmMain/kotlin/afield/four/synth/SonicPi.kt` | a local Sonic Pi v5 | realtime only | no | [Sonic Pi](sonic_pi.md) |

Four of those eight are pattern voices: `pat-stand-in`, `dirt-samples`, `faust-poly` and
`pat-spatial`. The first two are what [Sound](sound.md) covers, with the fallback behaviour and the
provenance audit; `faust-poly` is on [Faust](faust.md), because what it needs explaining is the voice
allocator rather than the pattern; and `pat-spatial` has [a page of its
own](pat_spatial.md), because what it needs explaining is why a tag in a pattern is a *direction* and
why the synthesis is a thread away from the audio. Read whichever you have picked; this page only
helps you pick.

Two of the eight are **realtime only**, and for unrelated reasons. `pat-spatial` cannot render into a
buffer because its synthesis runs on its own clock, ahead of whoever consumes it — its own page gives
[the three reasons](pat_spatial.md#why-there-is-no-clip-of-the-output), and it is the
only path in this table whose published clips are of its *input* rather than of what it sounds like.

`sonic-pi` is the other, and it is the more extreme case: it is the only path where **no sample ever
enters this process**. Field sends text and receives log lines; the audio exists entirely inside
another program's synthesiser. Everything you can do to samples in the other seven — filter them,
plot them, write them to a file — you cannot do to those. The way back in is `link-audio-in`, below.

## The five that carry samples out

| path | what it is | needs | page |
| --- | --- | --- | --- |
| `audio-patch-seam` | **the default.** Return `audio(signal)` from a box; one engine thread pulls blocks until the box stops. `internal interface Renderer` in `src/jvmMain/kotlin/afield/four/synth/FieldAudioEngine.kt` | an output device | — |
| `wav-file` | a 16-bit PCM `.wav` on disk. `fun writeStereo16` in `src/jvmMain/kotlin/afield/four/audio/WavFloatArrayWriter.kt` | nothing | [Sound](sound.md) |
| `box-output-player` | samples encoded to a WAV in memory, base64'd into a `data:` URI, shown as an `<audio>` element in the box's output. `fun renderSamplesHtml` in `src/jvmMain/kotlin/afield/stdlib/AudioVisualizer.kt` | nothing | [Visualizers](visualizers.md) |
| `spatial-openal` | a **second, independent** OpenAL context: 3D positional audio, optional HRTF, EFX reverb. `class SimpleSpatialAudioEngine` in `src/jvmMain/kotlin/afield/four/audio/SimpleSpatialAudio.kt` | an output device | — |
| `link-audio-out` | the engine's output mirrored into Ableton Link Audio, tempo-locked, so another app on the network hears it. `fun enableAudioOutput` in `src/jvmMain/kotlin/afield/four/synth/AbletonLinkAudio.kt` | Link peers | [Sonic Pi](sonic_pi.md) |

Every audio figure in these docs, all fifty-one of them, comes out of the `wav-file` sink, which is
why they regenerate on a build machine with no sound card. That is also what bounds what
`pat-spatial` can publish: two of those fifty-one are the *mono source* its dough engine produces,
and none of them is its spatialised output, because this sink cannot be pointed at a path that only
runs in realtime. [Its page says why](pat_spatial.md#why-there-is-no-clip-of-the-output).

## Spatial audio: thread-local contexts, and rendering with no device

**Field has two spatial audio paths and they are not alternatives.** `spatial-openal` — this section
— is a sink: you position sources from code, it opens its own OpenAL context, and it has an offline
loopback mode, so a headless machine can render and measure it. `pat-spatial` is a *source*: the
position is a tag in a pattern, the HRTF is Steam Audio's rather than OpenAL's, and it reaches the
speakers through the ordinary seam rather than through a context of its own. The trade runs the other
way on the one axis that matters for these docs: `spatial-openal` can be driven with no device at
all, by the loopback renderer described below, and `pat-spatial` cannot be driven faster than wall
clock by anything — which is why it is the one path here whose own page has to
[explain an absent figure](pat_spatial.md#why-there-is-no-clip-of-the-output) rather than publish
one. Neither of them has a clip on this site; only one of them *could*.

`spatial-openal` defaults to one shared engine. Two constructor arguments change that, and both
exist so that spatial audio can happen somewhere other than the speakers of the machine you are
sitting at.

`SimpleSpatialAudioEngine(threadLocalContext = true)` keeps its OpenAL context on the thread that
initializes it, so a box can have 3D audio of its own while another thread's ordinary output keeps
playing. Use the engine and its sources on that thread and destroy it there; destroying it restores
whichever thread-local context was current before, and nested contexts must close in reverse
creation order. The mode needs `ALC_EXT_thread_local_context` and fails initialization where the
extension is absent.

`loopbackSampleRate = 48000` renders instead of playing. Initialize as usual, request HRTF, then
call `renderSamples(output, frames)` with a reusable direct stereo `ShortBuffer`. That call, and
nothing else, advances playback — so the output is deterministic, and it needs the bundled OpenAL
Soft library rather than an output device. A test or a docs build can use it.

| member | what it is for |
| --- | --- |
| `hrtfEnabled`, `hrtfStatus` | what the device actually did. Asking for HRTF is not proof of getting it |
| `sourceCount`, `bufferCount` | live object counts, for checking that an owner cleaned up after itself |
| the scalar `setListener` overload | position and orientation without allocating a vector per update |

The `audio.spatial.loopback` smoke drives both modes through the real mixer;
`dev_docs/SOUND_PATH_MAP_AGREEMENT.md` lists what it asserts and to what tolerance.

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

## One seam, and eleven of seventeen paths on it

There is exactly one signature everything realtime meets at:

```diffstatic {data-lang=kotlin}
fun render(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext)
```

`AudioPatchSpec.Renderer` declares it. `StereoRenderable` declares it, character for character. So
does every node in the graph, `DirtSamplePlayer`, `FaustNode`, `FaustPolyNode`, `DoughAudioSink`,
`PatSynthBank` and `PatSpatialBank`. Eleven of the seventeen paths above sit on it. **A path on the
seam composes with every other path on the seam; a path off it composes with nothing.**

Faust is the clearest case of what that buys, twice over. `class FaustNode` is 23 lines that fill an
input buffer and call `dsp.render`. `class FaustPolyNode` is five lines, and behind them is an event
scheduler, a voice allocator and N WebAssembly instances in one GraalVM context — none of which the
seam has to know about. Being on it is what gets both of them the speakers, the engine's visualisers,
the Link output and the box lifecycle, with no adapter written for any of them.

The two paths off the seam are off it for two different reasons:

`sonic-pi` is the one path off the seam, and no wrapper reaches it: it is a different process, and
no sample ever enters this one.

Two of the seventeen used to be off it. `dough-engine` was described here as something that "could be
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

`pat-spatial` is the newest arrival on the seam, and it settles a question the column leaves open:
being on the seam and being able to render offline are **independent**. It is the first path that can
fill a block when asked and cannot do it faster than wall clock — the seam asks for a block, not for
a buffer, and a render thread feeding a ring satisfies that with an `arraycopy`. It also arrived the
way `faust-poly` did, opening no device and encoding no WAV, and was reported by the same
`override fun generateStereo(` marker rather than by anyone reading the diff.

`AudioPatchSpec` is what every box that makes a noise returns. Five of the seventeen paths above have
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

??? aside "Do not compare the three clips by loudness"

    All three players above are peak-normalized to 0.9 of full scale, so they compare timbre and
    not level. That is `fun writeStereo16` being called with `normalizePeak = 0.9f`. Listen to
    them back to back and they will seem to arrive at a matched volume; that is the writer's
    doing, not the paths'. The levels the filters actually produced, and the rest of the
    comparison's arithmetic, are in `dev_docs/SOUND_PATH_MAP_AGREEMENT.md`.

A lowpass shows that the paths differ. Feed the same buffer a plain gain instead — 0.5 by the
graph, 0.5 by Faust — and the two are bit-equal, which is the stronger claim: two separate
implementations on one seam, with no adapter in between.

---

## Where the list comes from

Everything on the seam is here, and not because somebody keeps the page up to date. If a table
above names a path, that path exists, in that file, under that name: the `synth.sound.path.map`
smoke re-derives the list from the tree on every `check`, and cites each entry point by *symbol*,
so a rename fails the build rather than leaving a dead citation.

Off the seam the derivation is blind, and both of the shapes it cannot find are listed above
anyway, because somebody declared them: a player in another process, like `sonic-pi`, and a
whole-buffer free function, like `patRenderStereo`. A third of either kind could be added to Field
tomorrow and this page would not notice — though the seam marker has now caught an undeclared
source twice, `FaustPoly.kt` and then `pat-spatial`, so the blind spot is narrower than it was. The
marker literals, the assertions and the tolerances are in `dev_docs/SOUND_PATH_MAP_AGREEMENT.md`.

---

## A box that plays the comparison

A loadable Field document is committed at `public/docs/assets/sound_path_map/`. Open
`public/docs/assets` as the workspace and `sound_path_map` as the document, then run the boxes top
to bottom: the first prints the map, the second plays the three renders back to back and plots
their short-time brightness so you can see the filters as well as hear them.
