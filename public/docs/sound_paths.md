# How to make sound in Field

Want to play a rhythm, build a synthesizer, work with another sound application, or write audio
to a file? Field has several ways to make and route sound. Start with the task closest to yours,
then follow the links for the details.

---

## Which one to pick

* **"I have a rhythm and I want to hear it."** → `pat-stand-in`. Nothing to install; `patSynth` for
  the speakers, `patRenderStereo` for a file, and the same voice either way.
  [Patterns & Rhythm](pattern_language.md), then [Sound](sound.md).
* **"I want it to sound like actual drums."** → `dirt-samples`, if you have the Tidal quark, or
  after `./gradlew fetchDocsDirtSamples` for the few folders these docs use. [Sound](sound.md).
* **"I want to build a synth out of parts."** → `signal-graph`. Oscillators, filters, envelopes,
  Kotlin operators. [DoughDSP Sound Examples](dough_dsp_sound.md).
* **"I want to write the DSP itself, properly."** → `faust`. [Faust](faust.md).
* **"I want a pattern to play that DSP as an instrument, in chords."** → `faust-poly`.
  [Faust](faust.md).
* **"I want the rhythm to come from *somewhere* — a direction, a distance."** → `pat-spatial`,
  through headphones. [A pattern in space](pat_spatial.md).
* **"I want to patch it in Pure Data."** → `pure-data`. A patch inline in the box or a `.pd` file,
  once libpd is built. [Pure Data](pure_data.md).
* **"I want to write an orchestra and score."** → `csound`. Use Csound as a box language or call it
  from Kotlin; build libcsound once. [Csound](csound.md).
* **"I want to write music in Sonic Pi."** → `sonic-pi`. [Sonic Pi](sonic_pi.md).
* **"I want it in a file, or in a docs page."** → the `wav-file` sink. Offline, deterministic.
* **"I want a player in the box's output."** → the `box-output-player` sink.
* **"I want it out of the speakers, now."** → return `audio(signal)` from the box and the
  `box-audio` does the rest.

---

## The ten that make samples

| path | what it is | needs | offline? | shares `render`? | page |
| --- | --- | --- | --- | --- | --- |
| `pat-stand-in` | a pattern's events through a very small voice: one oscillator, one one-pole lowpass, one decay envelope, per event. `fun patRenderStereo` in `src/jvmMain/kotlin/afield/audio/synth/PatSoundDocs.kt` offline; `patSynth` live | nothing | both | **yes** | [Sound](sound.md) |
| `dirt-samples` | the same events as real Tidal one-shots. `fun patRenderStereoDirt` in `src/jvmMain/kotlin/afield/audio/synth/DirtRender.kt` offline; `DirtSamplePlayer` live | the Dirt-Samples quark, or the pinned subset `./gradlew fetchDocsDirtSamples` downloads | both | live half only | [Sound](sound.md) |
| `signal-graph` | Field's own DSP graph — `abstract class Signal` in `src/jvmMain/kotlin/afield/audio/synth/Signal.kt`, plus the oscillators, filters and the DoughDSP ports | nothing | both | **yes** | [DoughDSP](dough_dsp_sound.md) |
| `faust` | a Faust program compiled at runtime and dropped into that same graph. `class FaustNode` in `src/jvmMain/kotlin/afield/audio/synth/FaustDsp.kt` | nothing — libfaust is vendored as wasm | both | **yes** | [Faust](faust.md) |
| `faust-poly` | the same program played as an *instrument*: N instances of one compiled module, allocated to notes by a pattern, each note landing on the sample it was scheduled for. `class FaustVoiceBank` in `src/jvmMain/kotlin/afield/audio/synth/FaustPoly.kt` | nothing — as `faust` | both | **yes** | [Faust](faust.md) |
| `pure-data` | a Pure Data patch — inline text in the box, or a `.pd` file — in a libpd instance of its own, pulled a block at a time. `class PdNode` in `src/jvmMain/kotlin/afield/audio/pd/PdInstance.kt` | libpd, built once by `./gradlew buildLibpdNative` | both | **yes** | [Pure Data](pure_data.md) |
| `csound` | a Csound orchestra and score, as a box language or from Kotlin. `class CsoundNode` in `src/jvmMain/kotlin/afield/audio/csound/CsoundInstance.kt` pulls blocks live; `CsoundInstance.renderOffline` renders to a buffer | libcsound, built with `./gradlew buildCsoundNative` or installed separately | both | **yes** | [Csound](csound.md) |
| `dough-engine` | the whole dough/Strudel engine as a wasm module, with its own 128-frame block loop. `class DoughEngine` in `src/jvmMain/kotlin/afield/audio/synth/Dough.kt`, wrapped onto the shared `render` call by `DoughAudioSink` | the dough wasm build | both | **yes**, through `parkDough` | — |
| `pat-spatial` | a pattern **placed in space**: one dough engine per `spat` tag, its output taken as mono and aimed by Steam Audio's HRTF. `fun patSpatial` in `src/jvmMain/kotlin/afield/audio/synth/PatSpatial.kt` | dough, plus an uncommitted `libphonon` — `./gradlew fetchSteamAudioNatives` | both | **yes** | [A pattern in space](pat_spatial.md) |
| `sonic-pi` | a Ruby program handed to Sonic Pi's own scsynth over OSC. `class SonicPi` in `src/jvmMain/kotlin/afield/audio/synth/SonicPi.kt` | a local Sonic Pi v5 | realtime only | no | [Sonic Pi](sonic_pi.md) |

Four of those ten are pattern voices: `pat-stand-in`, `dirt-samples`, `faust-poly` and
`pat-spatial`. The first two are what [Sound](sound.md) covers, with the fallback behaviour and the
provenance audit; `faust-poly` is on [Faust](faust.md), because what it needs explaining is the voice
allocator rather than the pattern; and `pat-spatial` has [a page of its
own](pat_spatial.md), because what it needs explaining is why a tag in a pattern is a *direction* and
why the synthesis is a thread away from the audio. Read whichever you have picked; this page only
helps you pick.

One of the ten is **realtime only**: `sonic-pi`, the only path where **no sample ever
enters this process**. Field sends text and receives log lines; the audio exists entirely inside
another program's synthesiser. You cannot filter, plot or record Sonic Pi's output the way you can
the others'. The way back in is `link-audio-in`, below.

## The five that carry samples out

| path | what it is | needs | page |
| --- | --- | --- | --- |
| `box-audio` | **the default.** Return `audio(signal)` from a box; one engine thread pulls blocks until the box stops. `internal interface Renderer` in `src/jvmMain/kotlin/afield/audio/synth/FieldAudioEngine.kt` | an output device | — |
| `wav-file` | a 16-bit PCM `.wav` on disk. `fun writeStereo16` in `src/jvmMain/kotlin/afield/audio/engine/WavFloatArrayWriter.kt` | nothing | [Sound](sound.md) |
| `box-output-player` | samples encoded to a WAV in memory, base64'd into a `data:` URI, shown as an `<audio>` element in the box's output. `fun renderSamplesHtml` in `src/jvmMain/kotlin/afield/ui/visualization/AudioVisualizer.kt` | nothing | [Visualizers](visualizers.md) |
| `spatial-openal` | a **second, independent** OpenAL context: 3D positional audio, optional HRTF, EFX reverb. `class SimpleSpatialAudioEngine` in `src/jvmMain/kotlin/afield/audio/engine/SimpleSpatialAudio.kt` | an output device | — |
| `link-audio-out` | the engine's output mirrored into Ableton Link Audio, tempo-locked, so another app on the network hears it. `fun enableAudioOutput` in `src/jvmMain/kotlin/afield/audio/synth/AbletonLinkAudio.kt` | Link peers | [Sonic Pi](sonic_pi.md) |

??? aside "Where the clips on this site come from"

    Every audio figure in these docs comes out of the `wav-file` sink, so they regenerate on a build
    machine with no sound card. `pat-spatial`'s binaural clip does too, but only where Steam Audio's
    `libphonon` has been fetched; elsewhere the committed file is kept.

## Spatial audio with OpenAL, and rendering with no device

Field has two ways to place a sound in space: OpenAL, which is this section, and Steam Audio, whose
HRTF is what [A pattern in space](pat_spatial.md) uses. With `spatial-openal` you position sources
from code, it opens its own OpenAL context, and it has an offline loopback mode, so a machine with no
sound card can render and measure it. It has no clip on this site.

`spatial-openal` defaults to one shared engine. Two constructor arguments change that, and both
exist so that spatial audio can happen somewhere other than the speakers of the machine you are
sitting at.

`SimpleSpatialAudioEngine(threadLocalContext = true)` keeps its OpenAL context on the thread that
initializes it, so a box can have 3D audio of its own while another thread's ordinary output keeps
playing. Use the engine and its sources on that thread and destroy it there; destroying it restores
whichever thread-local context was current before, and nested contexts must close in reverse
creation order. The mode needs the OpenAL extension `ALC_EXT_thread_local_context` and fails to initialize
without it.

`loopbackSampleRate = 48000` renders instead of playing. Initialize as usual, request HRTF, then
call `renderSamples(output, frames)` with a reusable direct stereo `ShortBuffer`. That call, and
nothing else, advances playback, so the output is deterministic, and it needs the bundled OpenAL
Soft library rather than an output device. A test or a docs build can use it.

<div class="field-reference" data-code-lang="kotlin" markdown>

| member | what it is for |
| --- | --- |
| `hrtfEnabled`, `hrtfStatus` | Report whether the device actually enabled HRTF and the resulting status. Inspect these after creation; requesting HRTF does not guarantee availability. |
| `sourceCount`, `bufferCount` | Count live sources and buffers owned by the engine. Compare them before and after stopping a sound to check resource cleanup. |
| the scalar `setListener` overload | Updates listener position and orientation from scalar components. Use it in frequent tracking updates to avoid allocating vectors on every call. |

</div>

## The four that bring samples in

| path | what it is | needs | page |
| --- | --- | --- | --- |
| `device-input` | OpenAL capture as a `Signal`, so a microphone is just another node. `fun stereoInput()` in `src/jvmMain/kotlin/afield/audio/synth/FieldAudioEngine.kt` | an input device + OS permission | — |
| `link-audio-in` | another application's output as a `Signal`. `fun stereoInput(channelName…)` in `src/jvmMain/kotlin/afield/audio/synth/AbletonLinkAudio.kt` | Link peers | [Sonic Pi](sonic_pi.md) |
| `video-audio` | a file decoded by FFmpeg and played through a **third** OpenAL context, kept in step by its own playback clock. `class AudioDecoder` in `src/jvmMain/kotlin/afield/graphics/texture/video/AudioDecoder.kt` | the bundled FFmpeg | [`dev_docs/AUDIO_OPTIONS.md`](https://github.com/OpenEndedGroup/Field4/blob/main/dev_docs/AUDIO_OPTIONS.md) |
| `wav-file-in` | a `.wav` off disk as `FloatArray`s — the counterpart of `wav-file`. `WavFloatArrayLoader` in `src/jvmMain/kotlin/afield/audio/engine/WavFloatArrayLoader.kt` | nothing | — |

Link Audio is how `sonic-pi` and Field reach each other without a virtual cable. The two directions
are not equally well tested. **Field → Sonic Pi** is: Field publishes a channel through
`enableAudioOutput`, Sonic Pi v5's `link_audio` subscribes to it, and a probe puts a 440 Hz tone in
one end and looks for it at the other. See [Sonic Pi](sonic_pi.md), including the Link visibility
toggle that defaults to Off and gives you silence with nothing to explain it. **Sonic Pi → Field**
has an API on both sides, `link_audio_publish_set!` and `linkAudioIn(channelName)`, but is untested.

---

## The shared `render` call

Everything realtime is driven through one signature:

```diffstatic {data-lang=kotlin}
fun render(outL: FloatArray, outR: FloatArray, numFrames: Int, ctx: SynthContext)
```

`AudioPatchSpec.Renderer` declares it, and so does `StereoRenderable`. So does every node in the
graph: `DirtSamplePlayer`, `FaustNode`, `FaustPolyNode`, `DoughAudioSink`, `PatSynthBank`,
`PatSpatialBank`, `PdNode` and `CsoundNode`. Every path marked "yes" in the table above shares it, and any of them
can be plugged into any other.

Faust shows what that buys. `class FaustNode` only fills an input buffer and calls `dsp.render`.
`class FaustPolyNode` is a few lines more, and behind them is an event scheduler, a voice allocator
and N WebAssembly instances in one GraalVM context, none of which the engine has to know about.
Because both share `render`, both get the speakers, the engine's visualisers, the Link output and
the box lifecycle with no adapter.

Of the ten paths that make samples, `sonic-pi` is the only one that does not share `render`, and no
wrapper can make it: it is a different process, and no sample ever enters this one.

`dough-engine` reaches `render` through a wrapper: `class DoughAudioSink` is a `StereoNode` in the
same file as `DoughEngine`, and `parkDough` registers `audio(sink)` with the engine.

Getting a pattern onto `render` is the general case, because a pattern has to be *queried* over an
arc and there is no "next 128 frames" to ask one for. The answer is a scheduler in two halves: a
thread that queries a cycle at a time and turns events into notes stamped with the absolute frame
they start on, and an audio-thread voice bank whose only remaining job is an integer comparison.
`DirtSamplePlayer` and `FaustPatternFeeder` divide the work the same way, and being early costs nothing because what crosses between the halves is frames.
See [A pattern that moves particles](chain_pattern_particles.md).

`pat-spatial` shares `render` too. Its `render` copies a block out of a ring that a render thread
fills, and with no device pulling, that thread renders as fast as it can, so the path renders
offline as well. Like `faust-poly`, it opens no device and encodes no WAV.

`AudioPatchSpec` is what every box that makes a noise returns. The paths with "—" in the page
column (`box-audio`, `dough-engine`, `spatial-openal`, `device-input`, `wav-file-in`) have no
page of their own; this page and [`afield.audio.synth` in the generated reference](reference/synthesis.md)
are their documentation.

---

## One buffer, three paths

Here are three of the paths on **bit-identical input**.

One buffer: `pat("bd ~ sd ~, hh*8")` at 2 cps for four cycles, two seconds, played from real Tidal
one-shots by `patRenderStereoDirt`: a kick, a hard snare and a closed hi-hat out of
[tidalcycles/Dirt-Samples](https://github.com/tidalcycles/Dirt-Samples). Then a 900 Hz lowpass from
two different paths.

```diffstatic_runner {data-lang=kotlin audio="assets/sound_path_map/same-input-dry.wav" audio-spec="same-input-dry" audio-renderer="afield.audio.synth.SoundPathMap" requires="dirt-samples" embed=true hidden=true name="sound-path-map-dry"}
// SoundPathMap.dryBeat()
// patRenderStereoDirt(pat("bd ~ sd ~, hh*8") with gain(0.9), 4, 2.0, DirtRender.requirePinnedLibrary(), strict = true)
```

**Path: `dirt-samples`, unfiltered.** This exact buffer is the input to both figures below.

```diffstatic_runner {data-lang=kotlin audio="assets/sound_path_map/same-input-signal-graph.wav" audio-spec="same-input-signal-graph" audio-renderer="afield.audio.synth.SoundPathMap" requires="dirt-samples" embed=true hidden=true name="sound-path-map-signal-graph"}
// SoundPathMap.throughSignalGraph(SoundPathMap.dryBeat())
// BufferSignal(dry) through ChamberlinLowpass(cutoff = Param(900f))
```

**Path: `signal-graph`.** The buffer above, through `ChamberlinLowpass` at 900 Hz.

```diffstatic_runner {data-lang=kotlin audio="assets/sound_path_map/same-input-faust.wav" audio-spec="same-input-faust" audio-renderer="afield.audio.synth.SoundPathMap" requires="dirt-samples" embed=true hidden=true name="sound-path-map-faust"}
// SoundPathMap.throughFaust(SoundPathMap.dryBeat())
// process = fi.lowpass(1, 900), fi.lowpass(1, 900);  — compiled at runtime, no audio device
```

**Path: `faust`.** The same buffer, through `fi.lowpass(1, 900)`, compiled at runtime.

Through either filter the kick comes out nearly whole, while the closed hat loses its tick and the
snare its crack, until both are dull thuds riding on the kick. The two filters do this differently.
`ChamberlinLowpass` is a two-pole state-variable filter, 12 dB per octave; `fi.lowpass(1, 900)` is first-order, 6 dB per octave. Ask
both for 900 Hz and the steeper one leaves less of the hat behind:

| figure | spectral centroid | vs dry | rolloff |
| --- | --- | --- | --- |
| `same-input-dry` | 2856 Hz | — | none; the brightness is the hat and the snare |
| `same-input-signal-graph` | 652 Hz | 0.23× | ~12 dB/oct above the knee |
| `same-input-faust` | 783 Hz | 0.27× | ~6 dB/oct |

Their waveform similarity to each other is **0.9899**: high, because they are lowpasses of one
buffer, and short of 1.0 because they are not the same lowpass. Most of what either one leaves is
the kick, which neither filter touches much, so they agree more closely than they would on a
brighter input.

??? aside "Do not compare the three clips by loudness"

    All three players above are peak-normalized to 0.9 of full scale, so they compare timbre and
    not level. That is `fun writeStereo16` being called with `normalizePeak = 0.9f`. Listen to
    them back to back and they will seem to arrive at a matched volume; that is the writer's
    doing, not the paths'. The levels the filters actually produced, and the numbers in the table
    above, are printed by `run_field_tests.py --id synth.sound.path.map`.

A lowpass shows that the paths differ. Feed the same buffer a plain gain of 0.5 instead, once by
the graph and once by Faust, and the two outputs are bit-equal: two separate implementations behind
one `render` call, with no adapter in between.

---

??? aside "Agents: how the tables stay current"

    The `synth.sound.path.map` smoke re-derives the list of paths that share `render` from the tree
    on every `check`, and cites each entry point by symbol, so a rename fails the build. It cannot
    find a player in another process (like `sonic-pi`) or a whole-buffer free function (like
    `patRenderStereo`); those are declared by hand in `SoundPathMap.kt`, and the marker literals and
    tolerances are in
    [`dev_docs/SOUND_PATH_MAP_AGREEMENT.md`](https://github.com/OpenEndedGroup/Field4/blob/main/dev_docs/SOUND_PATH_MAP_AGREEMENT.md).

---

## A box that plays the comparison

Open the [`sound_path_map`](agent_transcripts.md#sound_path_map) starting point from the welcome
screen. It has two boxes: *The map*
prints this page's tables from `SoundPathMap.PATHS`, and *One buffer, three paths* plays the three
renders above back to back and plots their short-time brightness, so you can see the filters as well
as hear them. The second box needs the Dirt-Samples folders once, from `./gradlew fetchDocsDirtSamples`;
without them it stops and names that command. Or [browse it in the offline
viewer](offline.html?file=assets/agent_transcripts/sound_path_map/metadata.edn).
