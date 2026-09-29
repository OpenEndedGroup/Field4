# Chain: a drawn score, heard

Status (2026-09-29, nightly `3-chain-drawn-score`): page, bridge, harness, demo and smoke landed on
the branch; not merged.

Public page: `public/docs/chain_drawn_score.md`. Starting point:
`dev_docs/agents/transcripts/drawn_score.yaml` (EXECUTE bucket).

## The route

```text
notation { drawnScore() }                         // marks in the root box's `drawing`
  -> notation.space("score", 0..8, 84..48)        // NotationSpace: beats x MIDI
  -> space.notes() / space.toPattern()            // NotationPatterns.kt (new): the bridge
  -> patRenderStereo(..., DrawnScoreDocs.VOICE)   // offline stand-in voice
  -> DrawnScoreDocs.listen(audio, notes)          // onsetPeaks + YIN, against the drawing
```

* `src/jvmMain/kotlin/afield/four/synth/NotationPatterns.kt` — `NotationSpace.notes(glideStep,
  levelTolerance)` and `toPattern(...)`. Rectangles/ellipses/diamonds: one note, centre pitch. Level
  stroke/line (ends within 0.5 semitone): one held note. Moving stroke/line: cut with `vertical(t)`
  every `glideStep` beats (a glissando becomes a run of short notes; the voice holds one pitch per
  note). Texts, frames, arrows skipped; zero-width marks (a vertical playhead) skipped. The whole x
  range is one cycle, on a 960-ticks-per-beat rational grid. Pitches are not rounded. Pen pressure
  becomes `gain` where a stroke recorded one. Code-drawn strokes record none —
  `Notation.kt:512#"simulatePressure" to JsonPrimitive(true)` writes an empty `pressures` array — so
  this chain's headless score does not exercise pressure->velocity.
* `src/jvmMain/kotlin/afield/four/synth/DrawnScoreDocs.kt` — the score (`NotationBuilder.drawnScore()`,
  shared by box code and renderer), `headless { }` (an ephemeral `SessionInformation`, restoring the
  thread's `thisSessionInfo`), `renderWav("drawn-score", path)`, `listen(...)`, and the
  `synth.drawn.score` FIELD_TEST.

## Verified

* `synth.drawn.score` PASS: 24 notes read (7 rectangles, 16 glissando steps, 1 held); two cycles,
  48 notes; 48 onsets detected without being told where, all within 30 ms (worst 10.9 ms, the
  detector's 12 ms window leading the strike); 48 notes voiced, worst pitch error 0.003 semitones.
* The published `.wav` (rendered by the docs runner inside Field via `renderWav`) is byte-identical to
  the one the smoke measured.
* The page's second figure re-reads the score from the live canvas and checks its notes equal the
  headless renderer's (`true` in the captured table).
* `DrawnScoreTest` (6 tests), `NotationTest` (19), `NotationIntersectionTest` (9) green.
* All four demo boxes run one at a time through the backdoor on a scratch document.

## Found

1. **Hand-named frames were invisible to `named()` / `space(name)`.** Excalidraw keeps a frame's title
   in the element's `name` field; `Mark.label` never read it. Fixed in `Notation.kt` (one line).
2. **A space defined by a named rectangle counted itself as an event.** Only `FRAME` was excluded.
   Fixed in `NotationGeometry.kt`: any non-linear mark whose bounds equal the region.
3. **`PatSoundDocs.onsetPeaks` KDoc was wrong** about re-arming: it re-arms against the level of the
   *triggering* window (low on the attack), not the note's peak. With 62 ms notes whose gaps fell to
   ~5% of peak it missed 16 strikes in cycle one and none in cycle two (hop alignment). KDoc
   corrected; behaviour left alone because six other harnesses count with it. `DrawnScoreDocs.VOICE`
   is detached (sustain 0.4, decay 4 ms) so steps are separated by true silence.

## Open

* Pressure -> velocity is untested here, because code-written strokes carry no pressure (see
  above); a hand-drawn pen stroke would carry it. A `pressures =` parameter on `NotationBuilder.stroke` would make it
  testable; left for the notation owner.
* A second voice (polyphony) would need a polyphonic pitch check; YIN is monophonic.
* A continuous glissando (one gliding oscillator) needs a signal-graph voice (`FnSignal` path, as
  `chain_fn_sound_roundtrip`), not `patVoice`.
