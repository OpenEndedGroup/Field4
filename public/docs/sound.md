# Sound: from a pattern to something you can hear

`pat("bd ~ sd ~")` is a schedule. It says *kick, rest, snare, rest* and it says exactly
when — but a schedule makes no sound. Something has to turn each event into samples.

Field has **three** things that turn a *pattern's* events into samples, offline, with no audio device
and no realtime engine, and they sound nothing alike. One is a tiny synthesiser that ships in the
source tree. The second is the genuine Tidal drum library, if you happen to have it. The third is a
**Faust program compiled at runtime and played polyphonically**: a pattern's notes become
`freq`/`gate`/`gain` writes on a bank of voices, scheduled to the sample. This page follows one
pattern all the way down the **first two**, and **every audio figure below says which of them
produced it.** The third has a page of its own,
[Faust: a box that is a signal processor](faust.md#a-pattern-plays-it).

Three counts the voices a *pattern* can drive. Seven things in Field make samples by one route or
another, and [Making a sound in Field: every path there is](sound_paths.md) maps all seven and helps
you pick.

A third of the audio published elsewhere in these docs is the first voice below, and the rest is
not. [What this means for the audio
elsewhere](#what-this-means-for-the-audio-elsewhere-in-these-docs) has the breakdown, measured
clip by clip.

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

The last two rows run the path backwards. `patTranscribe` takes a finite buffer and returns pitched
note events plus the mini-notation that reproduces them, so the output of analysis is the same kind
of object as the input to synthesis. On a notated scale it recovers 15 of 16 notes with a median
pitch error of 0.3 cents *(observed; the asserted floors are 14 notes and 3.0 cents)*; on three of
the corpus cases it returns *exactly* the notated integer sequence, and re-rendering that notation
and transcribing it again returns the same notes. It is monophonic and says so: a triad is reported
as `POLYPHONIC` and named as nothing rather than reported as its loudest note, while a sawtooth's
full harmonic stack is not refused, because timbre is not polyphony. One parameter dominates all
the thresholds: the register you declare. It sets the analysis window and therefore the shortest
note that can be resolved, and on identical audio the difference between declaring it and not is
onset recall 0.91 against 0.03. If you cannot declare one, call `patTranscribeAuto`, which
estimates it from a cheap wide first pass. Over the twelve cases where a register *can* be
declared, estimating it is about as good as being told (mean onset recall 0.716 estimated against
0.690 declared) and far better than leaving the wide default in place, which scores 0.538 *(all
three observed, not asserted; only their ordering is)*. It also refuses rather than transcribing
when the first pass says the material is not one line.

The loop closes all the way round. Feed the emitted notation back through the renderer and listen
again — notation → sound → notation → sound, every hop a real Field path — and **5 of 10
re-renderable corpus cases reproduce their own note sequence**. The other five are the cases whose
first hearing was already incomplete, so the fourth hop works as a validity check on the third
rather than as a separate accuracy number. Every case is also classified by what it *did* rather
than how it scored: 4 recovered exactly, 4 within tolerance, 5 refused with a reason, and **0
confidently wrong**. That last count is a statement about warnings rather than about correctness:
a wrong output carries a warning naming where it is wrong, and any case carrying a warning
classifies as refused. So the claim is narrower than nothing being wrong: nothing is wrong
*silently*. Act on that one, because a plausible parseable wrong answer gets believed where a
flagged one does not.

The floors behind these figures are asserted; the figures themselves are measurements. The mechanism
and the full measurement tables are in `dev_docs/pitch_transcription_roundtrip.md`, which also says
which figures are held against a floor and which are single observations; the runnable version is the
[pitch_transcription_roundtrip](agent_transcripts.md) starting point.

### What that looks like

Every panel below plots the notation you wrote as a **hollow outline** and what the detector heard back
out of the render as a **solid bar**, on one shared MIDI axis, so the exceptions are the only thing you
have to look at. Vertical position and left edge are measurements; bar *length* is not, because a
recovered note ends where its pitch stopped being stable, and that reaches into the previous note's
decay.

The clean case first: a stepped scale, wide leaps over three octaves, and a run detuned by 25/50/75/50
cents. The bars sit on the outlines, and in the third panel they sit off them by exactly the detuning.

![Three panels of notated-against-recovered piano rolls: a stepped scale at 15 of 16 notes recovered, wide leaps at 16 of 16 with the pitch order intact, and a detuned run at 8 of 8 whose bars sit slightly off their outlines](assets/pat_transcription/pat-transcription-roundtrip.svg)

Segmentation is harder, and the two available criteria fail in **opposite** directions rather than
one being better than the other. A flat-envelope Dough render has no energy transients to cut on
(energy recall 0.13) but clean pitch steps (0.86); a line that strikes each pitch
twice has the transients but no pitch change at half of them (energy 0.13, pitch 0.81, 0.88 merged):

![Two panels: a legato Dough render where the energy detector finds almost nothing while the pitch steps are clean, and a repeated-pitch line where the reverse holds](assets/pat_transcription/pat-transcription-opposite-failures.svg)

The figure behind the "0 confidently wrong" count shows what a **refusal** looks like. Three
simultaneous pitches gives 0 of 12 notes, a `POLYPHONIC` verdict and `pat("~")`: the outlines have no
bars in them at all, and that is the correct answer rather than a failure. The same audio at 31 ms per
note recovers 23 of 32 when it is *told* its register and 0 of 32 when it is not, because the analysis
window is then longer than the notes; the output is again `pat("~")` rather than a plausible parseable
wrong one:

![Three panels: a chord triad recovering zero of twelve notes and emitting pat tilde, a fast line recovering 23 of 32 when told its register, and the same audio recovering zero of 32 when not told, its whole span struck out by a declined band](assets/pat_transcription/pat-transcription-refusals-and-limits.svg)

Those three are **committed captures** from the `synth.pat.transcription` FIELD_TEST, which asserts
that every rectangle in each file corresponds to a reference note, a recovered note or a declined
span, that no coordinate is non-finite, and that the panels which are supposed to disagree do:

```diffstatic {data-lang=bash}
PAT_TRANSCRIPTION_DIR=/tmp/pat-transcription python3 tools/run_field_tests.py --id synth.pat.transcription
```

---

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

| token | what `patVoice` plays |
| --- | --- |
| `bd`, `kick` | 62 Hz sine |
| `sd`, `snare` | 190 Hz noise |
| `cp`, `clap` | 900 Hz noise |
| `hh`, `hat` | 6200 Hz noise |
| `oh` | 4200 Hz noise |
| anything else | `baseHz`, the voice's own default |

`showPatAudio(pattern)` renders through it and drops an audio player into the box's output.
Here is the beat:

```diffstatic_runner {data-lang=kotlin audio="assets/sound_render_path/beat-stand-in.wav" audio-spec="beat-stand-in" audio-renderer="afield.four.synth.SoundRenderPathDocs" embed=true hidden=true name="sound-doc-beat-stand-in"}
// showPatAudio(pat("bd ~ sd ~, hh*8") with gain(0.9))
// offline-rendered through patVoice by SoundRenderPathDocs — listen below
```

**Voice: the stand-in synth (`patVoice`).** No sample library was used.

That is not a drum machine. It is a sine and some filtered white noise, arranged in the shape
of a drum machine. It is nonetheless the **default**, for a good reason: it needs no assets. It
renders identically on a clean checkout, in CI, on a machine that has never heard of
SuperCollider, and it is deterministic, so a committed `.wav` regenerates byte-stably. A figure
that always works and sounds approximate beats a figure that sounds right on one laptop.

It is a *measurement* target before it is a musical one. `PatSoundDocs`' scenes verify pitch,
onset count, stereo decorrelation and swing ratios against the rendered audio, and a plain sine
is a much easier thing to measure a pitch in than a sampled kick.

---

## The real samples

`DirtRender` (`src/jvmMain/kotlin/afield/four/synth/DirtRender.kt`) is the other half.
`DirtSamples.kt` plays the genuine Tidal sample set **live** and only live: `playDirt` needs
`respondAhead`, a clock and an audio device, none of which exist in a docs build or a headless
box. `patRenderStereoDirt` mixes the same library straight into a buffer instead.

The same beat, the same ten events a cycle, the same `gain` / `pan` / `speed` annotations:

```diffstatic_runner {data-lang=kotlin audio="assets/sound_render_path/beat-dirt.wav" audio-spec="beat-dirt" audio-renderer="afield.four.synth.SoundRenderPathDocs" embed=true hidden=true name="sound-doc-beat-dirt"}
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

Dirt-Samples ships as a SuperCollider *quark*, so anybody who has ever run Tidal already has
it at one of those. That is why `dirtSampleRoot()` goes looking instead of asking you for a
path. The library is **not vendored**: roughly 400MB of third-party audio with mixed
per-folder licensing has no business inside this repository. The probe is not cached, so
setting `FIELD_DIRT_SAMPLES` and rendering in the same session gets you what you asked for.

---

## Two fallbacks, at different granularities

The two are easy to confuse and they mean very different things.

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

```diffstatic_runner {data-lang=kotlin audio="assets/sound_render_path/per-event-fallback.wav" audio-spec="per-event-fallback" audio-renderer="afield.four.synth.SoundRenderPathDocs" embed=true hidden=true name="sound-doc-per-event-fallback"}
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

## What this means for the audio elsewhere in these docs

The audit described at the bottom of this page sweeps every published clip and measures each one.
The corpus is forty-one clips across four pages, and it breaks down like this:

<div class="field-data-table" markdown>

| how many | family | where |
| --- | --- | --- |
| 31 | the stand-in synth (`patVoice`) | 29 on [Patterns & Rhythm](pattern_language.md), 1 here, 1 on [Sound paths](sound_paths.md) |
| 1 | real Dirt-Samples one-shots | here |
| 1 | real samples + the stand-in per unmatched event | here |
| 7 | a **DoughDSP primitive** on a real signal | 6 on [DoughDSP Sound Examples](dough_dsp_sound.md), 1 on [Sound paths](sound_paths.md) |
| 1 | a **Faust** program compiled at runtime | [Sound paths](sound_paths.md) |

</div>

That first row used to read 5, and this section used to open by saying the stand-in is *not* what
you are hearing on most of the site. It no longer can: twenty-six of those thirty-one are the
mini-notation cards on [Patterns & Rhythm](pattern_language.md), one clip per form of the notation,
and they arrived in a single commit. The stand-in is now most of the audio here by a wide margin.

The reason is the one given above, and adding twenty-six clips is the clearest case for it: a
primer that plays `bd*4` and `bd(3,8)` and `{bd hh cp}%8` needs a voice that renders identically
on a clean checkout with no sample library anywhere near it, and whose onsets can be *counted out
of the rendered audio* and checked against the schedule — which is what every one of those
twenty-six asserts.

The nine that never touch `patVoice` are still the interesting minority, and the largest of them
is standing in for nothing at all. `DoughDspSoundDocs` runs a plate reverb, a phaser, a resonant
lowpass and a soft clipper on real signals; a plate reverb ringing down *is* the thing that page is
about.

One more piece of audio ships with the site, and a sweep for "figures" misses it: the homepage
banner loop carries a 49.5-second soundtrack. No reader ever hears it — the player sets `muted` and
exposes no controls — so it is published bytes rather than published sound. It is also the only
audio here that no code can regenerate.

---

## Getting real drums in your own box

Install the quark (`Quarks.install("Dirt-Samples")` in SuperCollider, or clone
[tidalcycles/Dirt-Samples](https://github.com/tidalcycles/Dirt-Samples)) and then change one
call:

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

A loadable Field document with all of this side by side is committed at
`public/docs/assets/sound_render_path/`. Open `public/docs/assets` as the workspace and
`sound_render_path` as the document, then play box 1 and box 2 back to back.

---

## How the provenance claim is checked

Every sentence on this page in the form *"Voice: …"* is a machine-readable claim.

`SoundRenderPathDocs` declares a `Voice` per figure, and a build guard then reads *this file*, pairs each
audio fence with its figure, and requires:

* every fence names a figure the renderer knows, and every figure appears on the page once;
* the figure's declared label appears verbatim beside its fence — so the prose and the code
  cannot drift apart in silence;
* **the committed `.wav` is decoded and correlated against a stand-in render of the same
  pattern.** A figure claiming the stand-in must match it (measured 1.0000). A figure claiming
  samples must not (measured 0.0565 and 0.1588 for the sample-backed events of the two above);
  and a mixed figure's fallback events must be audibly present in it (measured 0.8074).

The correlation needs no sample library. A build machine with no 400MB third-party download still
catches the day a "real drums" figure becomes bursts of white noise; a reader would not, because the
caption underneath it would go on saying samples.

### And the same question, asked of every clip in the docs

`DocsAudioProvenance` widens all of that to the whole corpus, and runs on every build. It sweeps every
page for `audio=` fences, re-renders each asset through the *same* `renderWav(name, path)` contract the
docs build drives over the backdoor, and correlates the result against the committed bytes. Three
assertions:

* **nothing ships unclassified** — the fence count is pinned, so a new clip fails the build
  until somebody states which family it belongs to;
* **every committed clip still matches what its producer renders today** — all forty-one
  currently score 1.0000, bit-stable through peak normalisation and 16-bit quantisation. This is
  the assertion that catches a published figure whose producing code has changed underneath it.
  `56b124131` is the last time two of them had to be repaired by hand;
* **the list of audio that cannot be re-derived is pinned by key and by reason**, so it
  cannot become a place to put problems unnoticed. It has one entry, the banner loop.

The corpus audit prints its whole table:
`run_field_tests.py --id synth.docs.audio.provenance`.
