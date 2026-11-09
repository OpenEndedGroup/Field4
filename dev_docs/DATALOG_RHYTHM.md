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

## Not done

- No `#play` session command for a Datalog-language box; use `Box.datalog(...)` from Kotlin.
- No reading of the drawing layer / box graph in the demo (possible via `addBoxGraph`, untested here).
- No docs audio capture (audio fences need a registered `audio-renderer`); the transcript's box plays it.
