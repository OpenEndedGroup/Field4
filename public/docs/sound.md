# Sound: from a pattern to something you can hear

`pat("bd ~ sd ~")` is a schedule. It says *kick, rest, snare, rest* and it says exactly
when — but a schedule makes no sound. Something has to turn each event into samples.

This page follows one pattern through the two voices that render it without an audio device: a
small built-in synthesiser, and the Tidal drum library if you have it installed. Every audio figure
below says which of them produced it.

Other pages cover the other voices. A pattern can play a
[Faust program](faust.md#a-pattern-plays-it) as a polyphonic instrument, or be placed in space with
[`patSpatial`](pat_spatial.md), which places each sound with Steam Audio.
[How to make sound in Field](sound_paths.md) lists every way Field makes sound and helps you pick
one.

---

## The path

| stage | what it is | where |
| --- | --- | --- |
| `pat("bd ~ sd ~")` | mini-notation parsed into a `PatPattern<String>` | [Patterns & Rhythm](pattern_language.md) |
| `.events(arc)` | the schedule, as values with rational spans | `PatPattern` |
| `showPatRoll` | those events drawn | `PatPianoRollGallery.kt` |
| `patVoice` → `patRenderStereo` | **the stand-in**: one oscillator per event | `PatSoundDocs.kt` |
| `DirtSampleLibrary` → `patRenderStereoDirt` | **real samples**, mixed into a buffer | `DirtRender.kt` |
| `WavFloatArrayWriter` | a 16-bit PCM WAV, or bytes into a box's output | `WavFloatArrayWriter.kt` |
| `patTranscribe` | **back the other way**: the buffer heard as pitched notes, and a `pat("…")` string that re-renders | `PatTranscription.kt` |
| `patTranscribeAuto` | the same, with the register estimated instead of declared | `PatTranscription.kt` |
| `showPatTranscriptionOf` | notated against recovered, overlaid on one MIDI axis | `PatTranscriptionGallery.kt` |

The pattern language itself is documented on [its own page](pattern_language.md) and this one
does not restate it. All that carries over here is that a pattern is a *function from a query to
a list of events*, and that both voices below consume exactly the same list.


## The events, before anything makes a sound

One kick-and-snare and eight hats, stacked. Two cycles.

```diffstatic_runner {data-lang=kotlin screenshot="assets/sound_render_path/the-beat-roll.png" wait="2" capture="box" open-outputs="true" name="sound-doc-beat-roll"}
:::static
// The pattern the two A/B figures below render. Nothing here is audio yet — these are
// the events, and both voices are handed exactly this list.
showPatRollOf(
    "bd ~ sd ~" to pat("bd ~ sd ~"),
    "hh*8" to pat("hh*8"),
    title = "pat(\"bd ~ sd ~, hh*8\")",
    subtitle = "the schedule both voices are given",
    cycles = 2,
)
:::fold open the output pane
displayOpen("main")
:::endfold
```

![Piano roll of a kick-and-snare lane over an eight-hat lane, two cycles](assets/sound_render_path/the-beat-roll.png)

Ten events a cycle — the two `~` are rests and produce nothing at all — at exact rational
positions. Nothing so far has an opinion about what a kick drum sounds like.

---

## The stand-in voice

`patVoice` is that opinion, and it is a small one: **one oscillator, one
one-pole lowpass, one exponential-decay envelope**, per event. Pitch comes from the event's
value when it parses as a MIDI number, and otherwise from a drum map with five entries in it:

<div class="field-reference" data-code-lang="kotlin" markdown>

| Pattern example | What the stand-in plays |
| --- | --- |
| `pat("bd ~ kick ~")` | Both names select a 62 Hz sine voice. This is a deterministic kick stand-in, not a recording from a sample library. |
| `pat("~ sd ~ snare")` | Both names select the 190 Hz noise voice. |
| `pat("cp ~ clap ~")` | Both names select the 900 Hz noise voice. The higher noise setting distinguishes claps from the snare stand-in. |
| `pat("hh*8")` | `hh` and `hat` select the 6200 Hz noise voice. |
| `pat("~ oh ~ oh")` | Selects the 4200 Hz noise voice. It gives the open-hat token a different sound from `hh`. |
| `pat("custom ~")` | An unrecognised token uses the voice’s `baseHz`. Use Dirt rendering when a token is meant to name an actual sample. |

</div>

`showPatAudio(pattern)` renders through it and drops an audio player into the box's output.
Here is the beat:

```diffstatic_runner {data-lang=kotlin audio="assets/sound_render_path/beat-stand-in.wav" audio-spec="beat-stand-in" audio-renderer="afield.audio.synth.SoundRenderPathDocs" embed=true hidden=true name="sound-doc-beat-stand-in"}
// showPatAudio(pat("bd ~ sd ~, hh*8") with gain(0.9))
// offline-rendered through patVoice by SoundRenderPathDocs — listen below
```

**Voice: the stand-in synth (`patVoice`).** No sample library was used.

That is not a drum machine. It is a sine and some filtered white noise, arranged in the shape
of a drum machine. It is the default because it needs nothing installed and sounds the same on
every machine, every time.

---

## The real samples

`DirtRender` (`src/jvmMain/kotlin/afield/audio/synth/DirtRender.kt`) is the other half.
`DirtSamples.kt` plays the genuine Tidal sample set **live** and only live: `playDirt` needs
`respondAhead`, a clock and an audio device, none of which exist in a docs build or a headless
box. `patRenderStereoDirt` mixes the same library straight into a buffer instead.

The same beat, the same ten events a cycle, the same `gain` / `pan` / `speed` annotations:

```diffstatic_runner {data-lang=kotlin audio="assets/sound_render_path/beat-dirt.wav" audio-spec="beat-dirt" audio-renderer="afield.audio.synth.SoundRenderPathDocs" embed=true hidden=true name="sound-doc-beat-dirt"}
// showDirtAudio(pat("bd ~ sd ~, hh*8") with gain(0.9))
// offline-rendered from the Dirt-Samples quark by SoundRenderPathDocs — listen below
```

**Voice: real Dirt-Samples one-shots, every token matched.**

Two details do most of the work in making that sound like drums rather than like a sampler
being triggered:

* **Hits are one-shots.** A sample plays to its natural end rather than being gated by the
  length of the arc that triggered it. That is what SuperDirt does, and it is the reason a
  snare sounds like a snare instead of a click: an eighth-note arc is far shorter than the
  snare's tail.
* **Only events with an onset trigger.** A fragment carried over from a previous query arc is
  a continuation, not a new hit; re-triggering it would flam.

### How the library is found

`DirtRender.dirtSampleRoot()` probes, in order:

1. `$FIELD_DIRT_SAMPLES`, if set and non-blank — the documented override;
2. `~/Library/Application Support/SuperCollider/downloaded-quarks/Dirt-Samples` (macOS);
3. `~/.local/share/SuperCollider/downloaded-quarks/Dirt-Samples` (Linux);
4. `~/Dirt-Samples`.

Dirt-Samples ships as a SuperCollider *quark*, so if you have run Tidal you already have it in
one of those places. Field does not include the library itself. Setting `FIELD_DIRT_SAMPLES`
takes effect on the next render, with no restart.

---

## Two fallbacks, at different granularities

**No library on this machine: the whole render falls back.** `patRenderStereoDirt` returns
the `patRenderStereo` render exactly, the same samples rather than an approximation, and
`DirtRenderResult.usedSamples` is `false`. Nothing breaks and no figure fails to generate;
the caller is simply told, and can say so.

**Library present, one token missing from it: only that event falls back.** A pattern of
MIDI note numbers, or one naming a drum Dirt has never heard of, must not go silent just
because the library loaded successfully. The unrecognised events are voiced by `patVoice` and
mixed on top of the sampled ones, and `DirtRenderResult.unmatched` names them.

`pat("bd zzzznotadrum bd hh")` is the second case: `bd` and `hh` are in every
Dirt install and `zzzznotadrum` is in none. You can hear where the join falls: three sampled
drums and one square-wave blip, in one bar.

```diffstatic_runner {data-lang=kotlin audio="assets/sound_render_path/per-event-fallback.wav" audio-spec="per-event-fallback" audio-renderer="afield.audio.synth.SoundRenderPathDocs" embed=true hidden=true name="sound-doc-per-event-fallback"}
// showDirtAudio(pat("bd zzzznotadrum bd hh") with gain(0.9),
//               voice = patVoice(wave = PatWave.SQUARE, baseHz = 330.0, ...))
// offline-rendered by SoundRenderPathDocs — listen below
```

**Voice: real Dirt-Samples where the library had the token, the stand-in synth per event where it did not.**

`describe()` names exactly which, in one line a demo box can print:

```diffstatic {data-lang=text}
Dirt samples: bd hh — synth voice for zzzznotadrum
```

and for the whole-render fallback, the same call names the override rather than failing
silently:

```diffstatic {data-lang=text}
synth voice (no Dirt-Samples found; set FIELD_DIRT_SAMPLES to use real samples)
```

---

## What you hear on the other pages

The mini-notation cards on [Patterns & Rhythm](pattern_language.md) play the Dirt-Samples drums.
Some of the longer clips there, and on a few other pages, use the stand-in synth: its noise-burst
hats stay distinct where a sampled hat would blur, and its pitched lines have a clean tone.

Other pages use other voices. The effects on [DoughDSP Sound Examples](dough_dsp_sound.md) run on real
signals, and [Faust](faust.md#what-it-sounds-like-at-eight-voices-and-at-one) plays its own
instruments. [Subharmonic Synthesis](subharmonic_sound.md) plays a new tone under three real
sources: a synth bassline, Dirt-Samples drums and the dialogue of a 1968 film. The clips on
[A pattern in space](pat_spatial.md) are the mono source before spatialisation and one binaural
render through Steam Audio. The drums on [How to make sound in Field](sound_paths.md#one-buffer-three-paths) and
[Boolean network music examples](boolean_network_music_examples.md) are real Tidal samples.

---

## Getting real drums in your own box

Install the quark (`Quarks.install("Dirt-Samples")` in SuperCollider, or clone
[tidalcycles/Dirt-Samples](https://github.com/tidalcycles/Dirt-Samples)) and then change one
call. Without either, `./gradlew fetchDocsDirtSamples` downloads just the folders these docs use
(`arpy`, `bd`, `click`, `cp`, `hh`, `perc`, `sd`, `sn`, about 6 MB), and the library is found
there too, after any full install:

```diffstatic {data-lang=kotlin}
// the stand-in
showPatAudio(pat("bd ~ sd ~, hh*8") with gain(0.9))

// the real thing — same shape, same annotations
val played = showDirtAudio(pat("bd ~ sd ~, hh*8") with gain(0.9))
println(played.describe())          // say which voice you actually got
```

Both put a player in the box's output. `showPatAudio` hands back the rendered
`PatSoundDocs.Stereo` so you can plot or measure it as well as listen to it;
`showDirtAudio` hands back a `DirtRenderResult`, which carries the same audio in `.audio`
plus `usedSamples`, `matched` and `unmatched`. Print `describe()` and the box tells you
which voice it found rather than leaving you to guess from the sound.

If you want to be sure, force it:

```diffstatic {data-lang=bash}
FIELD_DIRT_SAMPLES=/path/to/Dirt-Samples ./field.sh
```

The [`sound_render_path`](agent_transcripts.md#sound_render_path) starting point has all of this
side by side. Open it from the welcome screen, then play box 1 and box 2 back to back.

---

## From audio back to a pattern

The last rows of [the path](#the-path) go the other way. `patTranscribe` takes a finite buffer and returns pitched
note events plus the mini-notation that reproduces them, so the output of analysis is the same kind
of object as the input to synthesis, and you can feed it straight back to the renderer.

It is monophonic and says so: a triad is reported as `POLYPHONIC` and named as nothing rather than
reported as its loudest note, while a sawtooth's full harmonic stack is not refused, because timbre
is not polyphony.

One parameter dominates all the thresholds: **the register you declare.** It sets the analysis
window and therefore the shortest note that can be resolved, and on identical audio the difference
between declaring it and not is onset recall 0.91 against 0.03. If you cannot declare one, call
`patTranscribeAuto`, which estimates it from a cheap wide first pass; estimating is about as good
as being told, and much better than leaving the wide default in place. It refuses rather than
transcribing when the first pass says the material is not one line.

It does not promise a correct answer, but it never gives a wrong one silently: a wrong output
carries a warning that says where it is wrong. Check the warnings before you trust the result.

To try it, open the [`pitch_transcription_roundtrip`](agent_transcripts.md#pitch_transcription_roundtrip) starting point.

### What that looks like

Every panel below plots the notation you wrote as a **hollow outline** and what the detector heard back
out of the render as a **solid bar**, on one shared MIDI axis, so the exceptions are the only thing you
have to look at. Vertical position and left edge are measurements; bar *length* is not, because a
recovered note ends where its pitch stopped being stable, and that reaches into the previous note's
decay.

The clean case first: a stepped scale, wide leaps over three octaves, and a run detuned by 25/50/75/50
cents. The bars sit on the outlines, and in the third panel they sit off them by exactly the detuning.

![Three panels of notated-against-recovered piano rolls: a stepped scale at 15 of 16 notes recovered, wide leaps at 16 of 16 with the pitch order intact, and a detuned run at 8 of 8 whose bars sit slightly off their outlines](assets/pat_transcription/pat-transcription-roundtrip.svg)

Splitting audio into notes is harder. The two ways of finding where a note starts fail on
opposite material. A flat-envelope Dough render has no energy transients to cut on
(energy recall 0.13) but clean pitch steps (0.86); a line that strikes each pitch
twice has transients at every note but no pitch change at half of them. Field's pitch detector
still finds most of those at the dip where the note is struck again (energy 0.00, pitch 0.88, 0.88
merged), and merging the two never loses a note start the pitch detector found:

![Two panels: a legato Dough render where the energy detector finds almost nothing while the pitch steps are clean, and a repeated-pitch line where the reverse holds](assets/pat_transcription/pat-transcription-opposite-failures.svg)

A **refusal** looks like this. Three simultaneous pitches gives 0 of 12 notes, a `POLYPHONIC`
verdict and `pat("~")`: the outlines have no bars in them at all, which is the correct answer. The same audio at 31 ms per
note recovers 23 of 32 when it is *told* its register and 0 of 32 when it is not, because the analysis
window is then longer than the notes; the output is again `pat("~")` rather than a plausible parseable
wrong one:

![Three panels: a chord triad recovering zero of twelve notes and emitting pat tilde, a fast line recovering 23 of 32 when told its register, and the same audio recovering zero of 32 when not told, its whole span struck out by a declined band](assets/pat_transcription/pat-transcription-refusals-and-limits.svg)

??? aside "Agents: regenerating the transcription figures"
    The three figures above come from the `synth.pat.transcription` smoke test:

    ```diffstatic {data-lang=bash}
    PAT_TRANSCRIPTION_DIR=/tmp/pat-transcription python3 tools/run_field_tests.py --id synth.pat.transcription
    ```


<a id="what-a-voice-caption-promises"></a>

??? aside "Agents: where the \"Voice:\" captions come from"
    The *"Voice: …"* captions on this page are written by the docs build, not by hand. A clip
    captioned as the stand-in has to match a fresh synth render, and one captioned as real samples
    has to *not* match one. That shows an asset did not come from the synth; it does not show it
    came from the Tidal library specifically. `run_field_tests.py --id synth.docs.audio.provenance`
    prints the table; the method and figures are in `dev_docs/DOCS_AUDIO_PROVENANCE_AGREEMENT.md`.
