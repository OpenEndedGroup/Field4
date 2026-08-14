# Hearing pitch, and writing it back down

Field could render notation to sound exactly, and could hear **rhythm** back
([`PatOnsetDetection.kt`](../src/jvmMain/kotlin/afield/four/synth/PatOnsetDetection.kt)). It could not
hear **pitch**, and it had no way to turn anything it heard back into something it can re-render:
there is no mini-notation printer anywhere in the repo, in either direction. `public/docs/audio_render_analysis.md`
draws the loop — notate, render, analyse, revise — and its last arrow, *"→ revise the generating
code"*, was drawn by hand.

This is that arrow, in Kotlin, offline. Nothing here talks to `services/field-audio-ai`, opens an
audio device, or needs a GPU.

| file | what it adds |
| --- | --- |
| [`PatPitchDetection.kt`](../src/jvmMain/kotlin/afield/four/synth/PatPitchDetection.kt) | frame-wise monophonic F0 (YIN), voiced/unvoiced, note segmentation in cents, a pitch-change onset criterion, and the refusals |
| [`PatTranscription.kt`](../src/jvmMain/kotlin/afield/four/synth/PatTranscription.kt) | step-grid fitting, `pat("…")` emission, and the scoring types |
| [`PatTranscriptionCorpus.kt`](../src/jvmMain/kotlin/afield/four/synth/PatTranscriptionCorpus.kt) | 13 notated cases scored against their own notation, plus the Dough legato probe |
| [`PatTranscriptionGallery.kt`](../src/jvmMain/kotlin/afield/four/synth/PatTranscriptionGallery.kt) | the notated-against-recovered SVG figures, and the `Box.showPatTranscription*` helpers |
| [`PatPitchTranscriptionTest.kt`](../src/jvmTest/kotlin/afield/four/synth/PatPitchTranscriptionTest.kt) | 17 tests; every number quoted below is asserted there or labelled an observation |

---

## The ground truth is free, and that is unusual

Transcription is normally bounded by annotation: a human listens to a recording, writes down what they
believe is in it, and the transcriber is scored against an opinion. Field authored the notation,
rendered it through its own synthesiser, and still has the notation. So the reference is not an
annotation of the audio — it **is the audio's cause**.

`PatTranscriptionCorpus.referenceOf` therefore *mirrors* `patRenderStereo`
(`PatSoundDocs.kt:785-797`) line for line rather than reimplementing the schedule: the same
`events(PatArc(ZERO, of(cycles)))`, the same sort, the same `arc.start · secondsPerCycle`, the same
`(startSec · sampleRate).toInt()` truncation, and deliberately no `hasOnset` filter because the
renderer has none either. A reference computed from what the schedule *ought* to be would measure this
file's understanding of the renderer instead of the detector.

## The one parameter that matters

YIN needs a frame at least twice the longest period it is asked to consider. So the **lowest pitch you
admit sets the analysis window**, and the window sets the shortest note you can resolve:

```
patPitchFrameSpan(sampleRate, minHz) = 3 · (sampleRate / minHz)
    minHz  65 Hz  → 2034 samples = 46.1 ms
    minHz 261 Hz  →  506 samples = 11.5 ms
```

`minHz`/`maxHz` are therefore the same *kind* of parameter as `patDetectOnsetSamples`'
`minGapSeconds`: the detector is told what it is allowed to resolve and buys sharpness with that
promise. This is not a tuning knob, and the corpus measures it rather than asserting it. The
`fast-line` / `fast-line-untold` pair is byte-identical audio — 31 ms notes — differing only in whether
the register comes from the notation or from the wide default:

| | onset recall | notes named | verdict |
| --- | --- | --- | --- |
| told (`minHz ≈ 247 Hz`, window 16 ms) | **0.91** | 23 of 32 | `PITCHED` |
| not told (`minHz = 65 Hz`, window 46 ms) | **0.03** | 0 of 32 | `UNRESOLVED` |

Both floors are asserted. `UNRESOLVED` is a distinct verdict from `UNPITCHED` precisely because the
remedy is different and knowable: 329 of 329 frames carried a pitch and no span held one long enough
to name, so the warning names declaring the register as the fix instead of reporting silence.

## What it measures, on the notation that generated it

Pitch error is reported as **median, p90 and worst** over a stated count. A single averaged figure
over a set containing one octave error is not a measurement of anything — it is a number between the
thing that worked and the thing that did not.

| case | notes | note-F | cents med / p90 / worst | onset med / worst |
| --- | --- | --- | --- | --- |
| `detached-scale` | 15/16 | 0.97 | 0.3 / 0.4 / 0.8 | 2.5 / 8.3 ms |
| `wide-leaps` | 16/16 | 0.88 | 0.6 / 2.5 / 3.4 | 2.1 / 14.0 ms |
| `rests-punctuated` | 8/8 | 1.00 | 0.0 / 0.1 / 0.1 | 0.3 / 0.3 ms |
| `saw-timbre` | 8/8 | 1.00 | 0.4 / 2.5 / 2.5 | 0.2 / 0.5 ms |
| `square-timbre` | 8/8 | 1.00 | 0.7 / 2.5 / 2.5 | 0.0 / 0.9 ms |
| `bass-register` | 12/16 | 0.86 | 0.8 / 4.6 / 4.7 | 4.4 / 16.2 ms |
| `fast-line` | 23/32 | 0.47 | 19.0 / 194.2 / 222.9 | 3.7 / 28.6 ms |

`wide-leaps`, `saw-timbre` and `square-timbre` come back as **exactly** the notated integer sequence —
not close, the same integers in the same order — and re-rendering the recovered notation and
transcribing it again returns the same notes, which is the closure property that makes the notation a
representation of the audio rather than a description of it.

### Cents, not note names

`detuned-cents` renders notes 25, 50, 75 and 50 cents sharp. Its pitch error is **0.1 cents worst** and
its note-level F is **0.75**, and both are correct. Two of its eight notes sit exactly 50 cents from
equal temperament, where the right note name does not exist: `kotlin.math.round` breaks ties toward
even, so a reference of 62.5 rounds to 62 while a measurement of 62.5001 rounds to 63. A detector
accurate to a tenth of a cent is marked wrong by arithmetic. That case is in the corpus to keep this
argument concrete, and its 0.75 ceiling is asserted as *expected*.

---

## What failed, and what that changed

Four things in this branch were wrong in ways that looked like success. All four were caught by reading
the frame dump, not by reasoning about the code.

### 1. The premise about legato was false

The brief for this work held that `patVoice`'s `sustain = 1.0` default means a gapless melodic render
is one continuous tone an energy detector cannot segment "by construction". Measured: the energy
detector found **17 onsets for 16 notes at recall 0.87**. No collapse.

`patRenderStereo` adds **one voice per event** and gives each its own 3 ms attack ramp from zero
(`PatSoundDocs.kt:837`), so a "legato" Pat render still has one amplitude rise per note. The claimed
experiment cannot be run on that renderer at all.

It can be run on a single oscillator whose frequency steps with continuous phase — `patSteppedSine`
through `doughRenderMono`, at Dough's own 48 kHz, which also carries the whole analysis path across a
sample-rate boundary. There the RMS envelope really is flat:

| `legato-dough`, 8 notes, flat envelope | onset recall |
| --- | --- |
| energy criterion | **0.13** |
| pitch criterion | **0.86** |

That is the honest form of the comparison and it is strictly stronger than the assumed one, because
the audio genuinely has the property the argument needs.

### 2. So the two criteria are complementary in *both* directions

A pitch criterion cannot see a **note repeated at the same pitch** inside a continuous tone. There is
no pitch change, and by construction there never will be. That is exactly where an energy criterion is
strongest. `repeated-pitch` measures it, and `patDetectNoteOnsets` merges the two rather than
recommending one:

| `repeated-pitch` | strict onset recall |
| --- | --- |
| energy only | 0.13 |
| pitch only | 0.81 |
| merged | **0.88** |

The first version of the merge kept whichever candidate came first — the obvious rule — and scored
**0.19 against 0.94 for the pitch detector alone**. Cause: `patDetectOnsetSamples` runs about 92 ms
late on sustained material, so its onsets were not competing versions of the same instant; they were
the previous note's onset arriving after the next note's and displacing a correct detection. The merge
now prefers the pitch-derived candidate, and *"merging never costs the pitch detector an onset"* is
asserted as an invariant across the whole corpus rather than as a threshold.

### 3. Segmentation took the previous note's tail

`patSegmentNotes` originally took the first voiced run inside a segment. The frames just after a
boundary are the ones whose window still straddles the note *before* it, so the first run is frequently
the previous note's tail at the previous note's pitch. The whole sequence came out shifted by one note
and `wide-leaps` reported a **1700-cent median error** on a detector whose per-note frequency estimates
were in fact accurate to a few cents. It now takes the longest *stable* run — consecutive voiced frames
within 0.4 semitone of each other — which is 0.6 cents on the same case.

Related, and the reason the fix is not just a threshold: **transition frames are unvoiced**. A window
straddling two pitches sums to something aperiodic, YIN rejects it, and `patPitchFlux` gates on both
frames being voiced. Only 7 flux peaks ≥ 0.5 semitone fire in the whole `bass-register` case. Note
changes are therefore detected as unvoiced-exit/voiced-entry pairs far more often than as flux peaks.
The criterion works; the mechanism is not the one it was designed around.

### 4. The strict scoring was unfair, in this branch's favour

`patDetectOnsetSamples` walks each coarse hit back to a level crossing, which assumes silence before
the attack — true for the percussive trains it was written for, false for melodic material where the
previous note's `decay · 4` tail is still at full level. Its onsets came out **+92 ms late, uniformly**.
Under a strict tally that reads as recall 0.06 and makes the energy detector look broken. Forgive the
constant offset and the same onsets score **recall 1.00, worst error 12.2 ms** — the detector was fine
and the tally was measuring a calibration offset.

Both figures are now reported for every case, and comparisons between the criteria use the
lag-forgiven one, which is the number that could be used against this work. `refineAttack` in the new
file returns null rather than a wrong answer when it finds its search window already loud.

There is a second trap here worth naming. `patMatchOnsets`' recall denominator is *the schedule up to
the last observed onset* (`PatOnsetDetection.kt:171`) — exactly right for its own job, comparing a
recording that may have been cut short, and wrong for a detector that stopped finding things. Under it,
a detector that reported 1 onset out of 32 and gave up scores **recall 1.00**, which is what
`fast-line-untold` did before the two measures were separated. `patTallyOnsets` exists for that reason
and documents it.

## The refusals

A monophonic detector's useful output on a chord is a stated inability, not the loudest note.

- `chord-triad` names **nothing**, emits `pat("~")`, and reports `POLYPHONIC`. The refusal is not
  silent: `patTranscribe` warns whenever it named no notes at all.
- `percussive-noise` reports `UNPITCHED` with 0 of 674 frames voiced.
- The two are told apart by **spectral sparsity**: 3 discrete peaks for the triad against 365 for the
  noise train. Two orders of magnitude, so the threshold between them is not delicate — and that gap
  is asserted as a ratio rather than as a pair of constants.

Two honest weakenings of the original design:

- **The voicing gate does nearly all the work, not the harmonic residual.** Three sines in a
  non-harmonic ratio are not periodic at any single lag, so YIN rejects a triad frame by frame without
  ever consulting a spectrum — 71 of 915 frames voiced. `patHarmonicResidual` was designed to be the
  chord detector and is in practice mostly a *straddle* detector.
- Consequently its flag is called `notOneNote`, not `polyphonicSuspected`. A high residual has two
  causes the spectrum cannot separate: more than one pitch really is sounding, or the pitch estimate
  straddles two notes and is a harmonic of neither. On `bass-register` it fires on the second. The
  action is the same either way — do not name this span — so the flag is useful and its name now claims
  only what is known.

**Timbre is not polyphony**, and that control is what makes the refusal worth having: `saw-timbre` and
`square-timbre` are full harmonic stacks on one note, are *not* refused, and score note-F 1.00 with
zero octave errors.

## What is not recoverable

A step grid is recoverable from onsets. A **cycle length is not**. Eight notes a beat apart are equally
well "eight steps in one cycle at 0.5 cps" and "four steps per cycle over two cycles at 1 cps", because
the two render to identical samples. `patTranscribe` recovers the grid honestly and then makes the one
arbitrary choice it must — one cycle, however many steps it found — and reports the `cps` that makes
that choice sound right. The notation is faithful to the audio, not to the authoring decision that
produced it.

Also not measured, and marked as such in the code:

- **Note *ends*.** A recovered note ends where its pitch stopped being stable, which reaches into the
  previous note's decay. `PatTranscription.sustainFraction` is offered for re-rendering, is biased
  high, says so, and is asserted nowhere. The figure's subtitle says outright that bar *length* is not
  a measurement.
- **A rest and a refusal are different, and mini-notation cannot say so.** There is no token for "this
  sounded and I will not name it", so a declined span appears as `~` and is named explicitly in
  `warnings`. The emitted notation is what the detector will stand behind.

---

## Running it

```bash
# the floors (17 tests, ~20 s of CPU)
./gradlew jvmTest --tests 'afield.four.synth.PatPitchTranscriptionTest'

# the figures, as a pass/fail smoke
python3 tools/run_field_tests.py --id synth.pat.transcription --port 9631

# regenerate the committed figures under dev_docs/assets/pat_transcription/
python3 tools/field_backdoor.py --port 9631 run-jvm-main \
  afield.four.synth.PatTranscriptionGallery --arg "$PWD/dev_docs/assets/pat_transcription"
```

Note that `field_backdoor.py` refuses to reuse a server whose sources changed after it started, and
reports that refusal **only** to `.field-backdoor/logs/*.jsonl` — stdout and stderr are empty and the
exit code is 1. If a backdoor call goes silent, read the newest session log before anything else.

## The loadable version

`dev_docs/agents/transcripts/pitch_transcription_roundtrip.yaml`, published to
`public/docs/assets/agent_transcripts/pitch_transcription_roundtrip/metadata.edn`. Five boxes, replayed
end to end by `DemoTranscriptExecSmokeTest`. **Box 4 is the one to edit**: change `minHz` from
`patMidiToHz(59.0)` to `PAT_PITCH_MIN_HZ` and watch recall fall from 0.91 to 0.03 on unchanged audio,
with the verdict turning `UNRESOLVED`.

## Figures

Committed under `dev_docs/assets/pat_transcription/`. Hollow outline is the notation Field authored;
solid bar is what the detector heard; one shared MIDI axis, so agreement reads as a filled outline and
the exceptions are what you see.

- `pat-transcription-roundtrip.svg` — the clean case, the exact round trip, the detuned case.
- `pat-transcription-opposite-failures.svg` — each criterion failing where the other works.
- `pat-transcription-refusals-and-limits.svg` — the triad with nothing in its outlines, and the
  resolution floor from both sides. The `fast-line-untold` panel is the most useful picture here: the
  F0 trace follows the tune perfectly while nothing is named, which is what `UNRESOLVED` means and is
  not visible in any score table.
