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
| [`PatPitchTranscriptionTest.kt`](../src/jvmTest/kotlin/afield/four/synth/PatPitchTranscriptionTest.kt) | 25 tests; the floors |

**Convention for the numbers below.** A figure in **bold** is asserted by a test in
`PatPitchTranscriptionTest`, so a change that breaks it fails the build. A figure marked *(observed)*
was measured once and is not asserted — it is evidence for a claim, not a ratchet, and it may drift.
Historical figures describing a bug that has since been fixed are marked *(before the fix)*.

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
detector found **17 onsets for 16 notes at recall 0.87** *(observed)*. No collapse.

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
**0.19 against 0.94 for the pitch detector alone** *(before the fix)*. Cause: `patDetectOnsetSamples` runs about 92 ms
late on sustained material, so its onsets were not competing versions of the same instant; they were
the previous note's onset arriving after the next note's and displacing a correct detection. The merge
now prefers the pitch-derived candidate, and *"merging never costs the pitch detector an onset"* is
asserted as an invariant across the whole corpus rather than as a threshold.

### 3. Segmentation took the previous note's tail

`patSegmentNotes` originally took the first voiced run inside a segment. The frames just after a
boundary are the ones whose window still straddles the note *before* it, so the first run is frequently
the previous note's tail at the previous note's pitch. The whole sequence came out shifted by one note
and `wide-leaps` reported a **1700-cent median error** *(before the fix)* on a detector whose per-note frequency estimates
were in fact accurate to a few cents. It now takes the longest *stable* run — consecutive voiced frames
within 0.4 semitone of each other — which is 0.6 cents on the same case.

Related, and the reason the fix is not just a threshold: **transition frames are unvoiced**. A window
straddling two pitches sums to something aperiodic, YIN rejects it, and `patPitchFlux` gates on both
frames being voiced. Only 7 flux peaks ≥ 0.5 semitone fire in the whole `bass-register` case *(observed)*. Note
changes are therefore detected as unvoiced-exit/voiced-entry pairs far more often than as flux peaks.
The criterion works; the mechanism is not the one it was designed around.

### 4. The strict scoring was unfair, in this branch's favour

`patDetectOnsetSamples` walks each coarse hit back to a level crossing, which assumes silence before
the attack — true for the percussive trains it was written for, false for melodic material where the
previous note's `decay · 4` tail is still at full level. Its onsets came out **+92 ms late, uniformly**.
Under a strict tally that reads as recall 0.06 and makes the energy detector look broken. Forgive the
constant offset and the same onsets score **recall 1.00, worst error 12.2 ms** *(observed)* — the
detector was fine and the tally was measuring a calibration offset.

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
  `warnings`. The emitted notation is what the detector will stand behind. (conjecture, 2026-08-25 — an
  absence in someone else's notation grammar; nothing in this tree can be pointed at to establish it.)

---

## The fourth hop: notation → sound → notation → **sound**

Everything above is three hops, and three hops is a weaker claim than it looks. "The recovered integers
equal the notated integers" compares a list against a list and never asks whether the emitted *string*
means what the list meant. A `pat("…")` that parses, renders, and sounds like something else passes it.

`patCloseLoop` closes the fourth: the transcription's own notation goes back through `patRenderStereo`
at the `cps` it reported, the second buffer is analysed by the same detector, and the two hearings are
compared. **5 of 10 re-renderable cases close the loop**, plus the Dough legato probe at 48 kHz.

The divergence is the finding, and it is a *relationship* rather than a list of names:

> Every case that closed the loop had named a self-consistent set — no wrong names and no invented
> notes. Every case that diverged had at least one.

So the fourth hop is a **validity check on the third**. An incomplete transcription re-renders to
something that is heard differently again; a complete one round-trips. That relationship is asserted,
so if it stops holding the finding gets restated rather than quietly rotting.

### It found a bug three hops cannot see

(not a limitation: a section title describing what the fourth hop added, not a claim that anything is
impossible — the three-hop comparison is measured and reported above.)

`saw-timbre` reproduced its pitch sequence perfectly and still diverged, with a 54 ms onset shift
*(before the fix)* against a detector whose own worst onset error on that case is 0.5 ms. The cause was
in the grid fit: the coarse sweep takes the **largest** step inside its tolerance, which systematically
biases the step long — 0.339 s against a true 0.333 s. That is 1.8%, harmless per note, and 42 ms of
accumulated drift by the end of a phrase. Note *names* are completely unaffected, which is exactly why
only a sound-to-sound comparison finds it. `refineStep` keeps the step count and minimises the residual;
the shift fell to **under 30 ms** and saw/square moved into `RECOVERED_EXACTLY`.

### Two ways I measured this wrong first

- **Unbounded pairing.** Pairing the two hearings by nearest onset with no bound reported a 386 ms
  "shift" and a "1100 cents" disagreement *(before the fix)* when the truth was one dropped note — the
  same off-by-one cascade that produced 1700 cents in segmentation earlier. Pairing is bounded to one
  step, unpaired notes are counted as unpaired, and cents is `NaN` when the sequence differs rather than
  publishing a number that compares unrelated notes.
- **A self-defeating tolerance.** Closure originally required the shift to sit inside
  `worstResidual · step + 2 · hop`. Fixing the grid cut the real shift 54 → 19 ms and cut the allowance
  46 → 6 ms, because the allowance was mostly made of the error being removed. Accuracy improved and the
  count of closing cases fell from 3 to 1. Closure is now sequence reproduction plus a half-step bound —
  derived from the grid, since past half a step a note quantises to a different step and the notation is
  no longer re-derivable. The quantisation figure is still computed and reported, as an observation.

The obvious suspect for the closure failures was the documented `sustainFraction` bias. It is **not** the
cause: re-rendering each case at its own original sustain gives the same result. Measured and discarded.

## Confidently wrong versus honestly refusing

A transcriber that emits a plausible, parseable, **wrong** `pat("…")` is worse than one that returns
`UNRESOLVED`, because the wrong answer gets believed and the refusal does not — and both land in the
same region of any F-measure. So every case is classified into exactly one bucket, and the last one has
an asserted ceiling.

| bucket | count | cases |
| --- | --- | --- |
| `RECOVERED_EXACTLY` | **4** | rests-punctuated, detuned-cents, saw-timbre, square-timbre |
| `RECOVERED_WITHIN` | **4** | detached-scale, repeated-pitch, bass-register, fast-line |
| `REFUSED` | **5** | legato-scale, wide-leaps, chord-triad, percussive-noise, fast-line-untold |
| `CONFIDENTLY_WRONG` | **0** | — |

Wrongness is judged in **cents**, not on note names, so `detuned-cents` is not punished for a 50-cent
rounding tie it cannot win.

**The count was 1, not 0.** `legato-scale` emitted `pat("60 62 65 _ 67 69 71 72 …")` — 64 and 65 heard as
one note, one name 65 cents out — carrying no warning of any kind, so a reader had nothing to go on. It
is 0 now because of a *specific* self-check rather than a blanket warning, which would have emptied the
bucket without improving anything:

- A note held across more than one step, in a grid whose other notes span exactly one, is where two
  notes were heard as one. The transcriber can see that in its own output with no ground truth.
- More onsets located than notes named means the notation is missing notes that were heard.

Neither tells the detector *which* name is wrong. Both tell the reader where to look, which is the whole
difference between being wrong and being confidently wrong.

## Can `minHz` have a defensible default? Yes.

Recall 0.03 → 0.91 on identical audio is a good measurement and a bad out-of-the-box experience: the
first thing anyone does is call the function without telling it anything, and they land on 0.03.

Two passes fix it, and they work for a specific reason — **a too-long window ruins segmentation but not
estimation**. On `fast-line-untold` the wide pass leaves 329 of 329 frames voiced with an F0 trace that
follows the tune correctly; the frames knew the pitch and only the note boundaries were lost. A wide
first pass is therefore a poor transcriber and a perfectly good histogram.

Mean onset recall over the corpus:

| register | mean onset recall |
| --- | --- |
| declared from the notation | **0.690** |
| estimated by `patTranscribeAuto` | **0.716** |
| wide default | **0.538** |

So `patTranscribeAuto` is the defensible default and is what a caller who cannot declare a register
should use. It is not free: `repeated-pitch` regresses 0.81 → 0.50 *(observed)*, because octave-error
debris in the first pass drags the estimated floor down to 65 Hz.

### The guard took three attempts, and the third is the interesting one

1. Gating on `patDiagnoseUnpitched`'s verdict refused five clean cases. That function exists to explain
   why nothing was named and *assumes* nothing was, so on nameable material it finds the two frames
   straddling a note boundary and calls them polyphonic.
2. Gating on the aperiodic **fraction** of loud frames separates the corpus by a wide margin — 0.86 for
   the triad, 1.00 for the noise train, 0.18 for the worst monophonic case *(observed)* — and still does
   not catch a chord.
3. Because a **4:5:6 triad is genuinely periodic two octaves below anything sounding**: 261/4 ≈ 329/5 ≈
   392/6 ≈ 65 Hz. A detector allowed to look that low is *correct* to report 65 Hz, and auto named five
   confident notes around MIDI 36. Nothing was wrong with the periodicity. The fundamental was one
   nobody played.

`patEnergyAtF0` is the discriminator: a played fundamental has energy in its own bin, a virtual one does
not. The control that makes it a real test rather than a distrust of low notes is `bass-register`, which
really does play MIDI 36 at that same 65 Hz and must **not** be refused. Both directions are asserted.

Two smaller things learned wiring it up, both in the KDoc: testing energy at the register *floor* refuses
every recording ever made, because the floor is a semitone below the lowest note by construction; and
passing "up to a second" as the analysis length centres the transform half a second past the frame it is
supposed to be about.

---

## Running it

```bash
# the floors (25 tests, ~75 s of CPU)
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
