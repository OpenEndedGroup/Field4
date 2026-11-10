# Datalog rhythm rules (DlRhythm)

Source: `src/jvmMain/kotlin/afield/data/query/datalog/DatalogRhythm.kt`; tests
`DatalogRhythmTest`; user docs `public/docs/datalog.md#rules-that-decide-when-notes-play`;
demo `dev_docs/agents/transcripts/datalog_rhythm_rules.yaml`. Idea from Dogalog
(github.com/danja/dogalog, MIT).

## Decisions

- **Bottom-up per bar, not top-down per step.** Each cycle (16 steps by default) is solved as its
  own fixpoint with `lookbehind` steps of context, memoised per cycle, keyed on the program text.
  That satisfies Pat's contract (any arc, any order, same answer) and is the scheduler's lookahead
  question. Cost: rules cannot see further back than the lookbehind.
- **Time predicates are materialised base facts**, not engine builtins. `euc/every/within/scale/chord`
  rows are generated only for the constant parameter tuples the program writes; a variable
  parameter throws `DlException`. No change to `DatalogEngine` was needed, and `#why` provenance
  works unchanged (the time facts show as `[fact]` leaves).
- `euc` reuses Pat's `bjorklund` with the same rotate-right as `euclid(K, N, R)`; a test asserts
  agreement. Arity is 4 (`euc(T, K, N, R)`), not Dogalog's 5.
- Chords are relations (one row per note) because Datalog has no lists.
- Output is `PatPattern<String>`: value = instrument; `note` annotation only for non-drum names
  (else a `kick` would be pitched), `gain = velocity / 100`, `datalog` = the fact.

## Live, and from the drawing (2026-11-10)

Source `DatalogRhythmNotation.kt`; tests `DatalogRhythmLiveTest`; docs
`datalog.md#rules-that-listen-to-the-drawing`; demo `dev_docs/agents/transcripts/datalog_rhythm_drawn.yaml`.

- **Playing live is the existing route**: return `patSynth(r.pattern(), cps)` from a launched box.
  `PatSynthFeeder` queries the pattern on its own daemon thread ~0.4 s ahead and hands frame-stamped
  notes to the `PatSynthBank` the audio thread owns. Nothing new runs on the Runloop, and no DSP
  state is touched outside the audio thread. (Forth `tick-steps` uses a hand-rolled ticker from box
  code instead; `patSynth` is the better fit for a `PatPattern`.)
- **The memo key compared fact *counts***, so a moved fact with the same row count served a stale
  bar. It now hashes row values (program facts and live facts).
- **`rules(source)`** builds a fresh `Datalog` and swaps a `@Volatile` reference; mutating
  `program` in place from box code races the feeder thread (`ArrayList` of rules). It validates by
  solving bar 0 on the caller's thread first, so a bad edit throws and the old rules keep playing.
- **`facts(pred) { rows }`** is a relation read on every query (the feeder thread). Reading the
  notation scene there is a read of an immutable `NotationScene` from `NotationStore`'s
  `ConcurrentHashMap` cache — reads only. Live rows shadow a program relation of the same name.
- **Drawing → facts**: `NotationSpace.rhythmHits(voices, steps)` gives `hit(P, Voice, Mark)` with `P`
  the step in the bar (join to time with `pos(T, P)`), lane from the start height, `Mark` = label or
  id. `rhythmSpans` gives `span(Voice, A, B, Mark)` for marks at least a step wide (gates).
  `DlRhythm.notationHits(space, voices)` wires both. `why()` shows `hit(4, snare, "n4.1") [fact]`.
- Observed live (port 9731): a launched box played bars 0–1 from seven marks, box 1 redrew four marks,
  and the same feeder's bars 2–3 were the new grid, with no restart (probe in NIGHTLY_WORKLOG).

## Not done

- No `#play` session command for a Datalog-language box; use `Box.datalog(...)` from Kotlin.
- A mark spans one bar; there is no multi-bar drawn score (would need absolute steps instead of `pos`).
- `facts { }` lambdas are polled every bar query; an expensive reader should cache itself.
- No docs audio capture (audio fences need a registered `audio-renderer`); the transcript's box plays it.
