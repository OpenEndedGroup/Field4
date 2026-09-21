# MARF field clocks: a silence, its measurement, and what replaced it

`MarfFieldClock` chooses the time coordinate a reader's field patterns are read in. This note is
about the third reading, `STEPS`, and about the refusal that sits beside it — but mostly it is about
the *measurement*, because the measurement is the reusable part. The published account of the layer
is `public/docs/marf.md`; this is the working note behind it.

---

## The silence, and how it was measured

`MarfFieldClock.STAGES` reads field `k` at cycle `k` (`Marf.kt`, `MarfFieldProbe.instant`), and
`marfLatch` probes `[k, k + 1/65536)` through `patSampleOver`, which returns "the event containing
the instant, else the first". A field pattern with more than one step inside a cycle therefore had
exactly one of them latched and the rest discarded. Nothing raised, warned or logged.

The evidence is unusually good, and it is worth saying what kind of evidence it is: **a measurement
of a real person failing, recorded by the tool they were failing in.** The untracked
`operations.yaml` under `dev_docs/agents/demos/marf_demo/` records, for 2026-09-21:

- 03:26:50 UTC — all four boxes of the demo document ran cleanly.
- 03:38:58 — `marf_readers` was dragged off the grid to `x = 1276.64`, isolating it.
- 03:38:58 → 03:42:10 — **thirty-eight edit-and-run cycles on that box alone, about five seconds
  apart**, walking its address pattern through ten spellings. Then the same experiment again on the
  second reader.

The method that turns that into a result: take the ten spellings *verbatim* out of the operations
log, build one five-slot bank, read it under each clock, and print the first eight
`MarfResolvedStage.address` values. Population 10 × 8. No paraphrase, no tidying — a spelling that
has been cleaned up on its way into a test is no longer evidence of anything.

| address | `CYCLES` | `STAGES`, as it was | `STEPS` |
| --- | --- | --- | --- |
| `<0 3>` | `03030303` | `03030303` | `03030303` |
| `<0 3> 1` | `03030303` | `03030303` | `01310131` |
| `1 <0 3>` | `11111111` | `11111111` | `10131013` |
| `<0 3>*2` | `00000000` | `00000000` | `03030303` |
| `<0 3>!2` | `03030303` | `03030303` | `00330033` |
| `<0 3>, 1` | `03030303` | `03030303` | *unreadable* |
| `<0 3> 1*8` | `03030303` | `03030303` | `01111111` |
| `<0 3> 1 2` | `03030303` | `03030303` | `01231201` |
| `<0 3> <0 4>` | `03030303` | `03030303` | `00340034` |
| `3 <0 3>` | `33333333` | `33333333` | `30333033` |

**Eight of the ten were byte-identical to something strictly simpler than what was typed**, and the
ten spellings between them produced three distinct sequences. Under `STEPS` the nine readable ones
produce eight.

The decisive line of the table is `<0 3>` against `<0 3> 1`: identical under both old readings, and
different under `STEPS`. Somebody typing the second and seeing the first has no way to tell whether
the layer disagreed with them or ignored them.

---

## The two mistakes, which are not the same mistake

The brief this work started from said "twenty-one consecutive attempts put more than one step inside
a cycle". Nine of them did. `"<0 3>, 1"` is a **stack**, not a subdivision: it has exactly one onset
per cycle, two events share that instant, and `patSampleOver` takes the first. A refusal predicated
on step count does not see it, and a per-stage step cursor does not fix it.

That distinction is load-bearing, so the refusal is two-sided and says two different things:

- **more than one step in a cycle**, under `STAGES`. Has a remedy, and the message names it:
  `fields = MarfFieldClock.STEPS` reads the sequence that was written.
- **more than one event on a step** — a stack. Has no remedy. A field latches to one value per
  stage; a stack is simultaneity and a field is a sequence. The message says so rather than
  suggesting `STEPS`, and `MarfTest` asserts the *absence* of the string `MarfFieldClock.STEPS`
  from that message. A message that names a fix which would not work is worse than one that names
  none.

`CYCLES` is exempt from both. A field is a control voltage there, every pattern means something, and
reading one layer of a stack at an instant is what the parameter algebra does everywhere else.

The precedent is one screen away in the same file and was already decided: `marfCurve` throws on an
unknown curve name, because "a typo in a curve pattern is silent otherwise, and the whole point of a
curve pattern is that you cannot hear which stage you misspelled." `curves()` validates at
construction for the same reason. `STAGES` was the larger instance of that argument and took the
other side of it.

---

## The step grid, and the one rule that is forced

`marfFieldSteps(pattern, cycle)` is the sorted distinct onsets inside `[cycle, cycle + 1)`, **or the
cycle's own start when there are none**.

That fallback is forced, not chosen, and the measurement is why:

```
GRID  <0 3>       -> [[0], [1], [2], [3]]                       1 onset/cycle
GRID  <0 3> 1     -> [[0, 1/2], [1, 3/2], [2, 5/2], [3, 7/2]]   2 onsets/cycle
GRID  slow3       -> [[0], [], [], [3], [], []]                 1, then two EMPTY cycles
GRID  saw.slow8   -> [[0 analog], [1 analog], ...]              no onsets at all
GRID  euclid(5,8) -> [[0, 1/4, 3/8, 5/8, 3/4], ...]             5 onsets/cycle
```

A `slow(3)` field has onsets only every third cycle, and the published meaning of `slow(3)` on an
address is "hold this entry for three stages". One step per empty cycle is exactly what preserves
that. A continuous field has no onsets at all — `signalOf` answers any query with one analog event
spanning it — so it lands on the same fallback and contributes one step per cycle, which is the
`STAGES` reading unchanged.

So the framing that makes `STEPS` easy to hold in the head: **`STEPS` subdivides each cycle of
`STAGES`.** Cycle `c` contributes as many stages as it has onsets, and at least one. Consequences:

- `STEPS` and `STAGES` agree **exactly** on every pattern with at most one onset per cycle. That is
  every `<…>`, every `slow(n)` of one, and every signal — and therefore every `STAGES` call site in
  this repository, all of which were checked rather than assumed.
- Each field walks its own grid. An address with two steps a cycle and a duration with one still
  advance one entry each per stage. Measured: address `"<0 3> 1"` with duration `"<1 2>"` gives
  `(0,1) (1,2) (3,1) (1,2)`, stage 4 starting at exactly `PatQ.of(6)`.
- `slow`/`fast` become **inert on a sub-cycle field's sequence** under `STEPS`, because slowing a
  two-step cycle into two one-step cycles yields the same step list. They are *not* inert on a
  whole-cycle field, where they create empty cycles and therefore holds. This is the least tidy
  corner of the design and it is stated rather than hidden.
- A stack still adds no steps, which is why it needed the second refusal rather than a mode.

---

## Traps, all from tonight

- **`internal` is not visible to a box.** `marfFieldSteps` is `internal`, so `jvmMain` and `jvmTest`
  can call it and box code compiled by the K2 REPL cannot. Anything a demo box needs has to be
  public.
- **A finite probe is a finite claim.** `marfCheckFieldClock` inspects cycles 0..15. A pattern whose
  first misbehaving cycle is the seventeenth passes. Sixteen covers every period this layer's own
  idioms produce; the number is named in the constant's KDoc rather than left as a magic 16.
- **The step cursor is the only mutable state a reader has.** A reader asks for indices `0, 1, 2, …`
  and restarts at `0` when its timeline is dropped, so a cursor that rewinds on a backwards ask is
  exact — but it has to be *tested* backwards, because "forwards only" is the order every natural
  test uses. `MarfTest` asks 24 instants forwards, then the same 24 in reverse, then again after a
  `reset()`.
- **A guard that re-renders every figure still shipped a false snippet.** `MarfDocsGuardTest`
  re-rendered every committed SVG and pinned figure-to-page correspondence, and two published
  listings still went out with `fields = MarfFieldClock.STAGES` deleted while the code that drew
  the figures kept it — because every check it had stopped at the fence. The structural fix is to
  make the snippet and the code one object: a `Figure` now carries its published snippet,
  `MarfReader` carries its `fieldClock`, and the guard compares them. The corrections were the
  small part.
- **A guard written after the fact must be shown to fail on the defect it was written for.** The
  negative control runs all three new checks against the two listings exactly as they shipped in
  `bc71ed216` and asserts each fails — five reader calls, five defects, no clock named anywhere —
  then that the corrected snippet passes.
- **Regenerating the reference is not optional.** Any KDoc edit under a `SOURCE_ROOTS` path makes
  `public/docs/reference/patterns.md`, `reference/index.md` and `dev_docs/kdoc_worklist.md` stale,
  and the pre-commit hook runs `checkCodebaseReference` and refuses the commit. `./gradlew
  generateCodebaseReference` takes about 70 s; budget two minutes per commit that touches a `.kt`.

---

## Where it is

| | |
| --- | --- |
| The clocks, the grid, the refusal | `src/jvmMain/kotlin/afield/four/synth/Marf.kt` |
| The assertions, against the night's ten spellings | `src/jvmTest/kotlin/afield/four/synth/MarfTest.kt` |
| The snippet ↔ figure guard, and its negative control | `src/jvmTest/kotlin/afield/four/synth/MarfDocsGuardTest.kt` |
| The published account | `public/docs/marf.md` |
| A replayable scene where the refusal fires | `dev_docs/agents/artifacts/marf_field_clocks/` |

---

## `AUTO`, and why the default is not one of the three readings

Added 2026-09-21, hours after the three clocks landed, on Marc's call: *"STEPS should clearly be the
default — it's what, honestly, I thought I was getting yesterday."* The instinct was right and the
blanket flip was not, and the gap between those two is the useful part of this section.

### Flipping the default to `STEPS` breaks three tested guarantees

Measured by doing it. `fields: MarfFieldClock = MarfFieldClock.STEPS` on the three entry points
failed **7 of 56** tests in the package, and three of the failures are semantics rather than pinned
expectations:

| guarantee | `CYCLES` | `STEPS`, before this change |
| --- | --- | --- |
| `MarfTest:276` a continuous address glides | rises stage by stage | **frozen** — all eight samples `2.2888185e-5` |
| `MarfTest:236` a rest holds the field it interrupts | `[0.0, 0.0, 0.8, 0.8]` | `[0.0, 0.8, 0.0, 0.8]` |
| `MarfTest:200` a thinned address holds, not snaps | `[0,0,4,4,4,2,3,3]` | `[0,4,4,2,3,0,4,4]` |

The second and third are one cause: the step grid was **onsets**, and a rest has no onset, so the
rests vanished and the sequence compressed. `marfFieldSteps` now takes each event's **start and its
end**, because the end is where a rest begins and a rest is a step. With that, `"0 ~ .8 ~"` reads
`[0, 0, .8, .8]` under `STEPS` as it always did under `CYCLES` — the two clocks agreeing about how
many stages a pattern *has* is not a nicety, it is the difference between a refinement and a rival.
`struct(euclid(5, 8), …)` on an address is only meaningful because its three rests hold.

### The first one has no fix inside `STEPS`, and that is why `AUTO` exists

A signal has no steps to walk. `signalOf` answers *any* query with a single analog event spanning it
(`src/jvmMain/kotlin/afield/four/synth/PatSignals.kt:73#signalOf`), so there is no structure in it
for a step grid to find, whatever the grid is built from — which is why widening the grid to event
boundaries fixed the rests and did nothing for this. Walking it therefore samples once per cycle,
and a cycle-periodic signal sampled once per cycle is a constant: `saw()` as an address stops
moving, measured as eight identical samples in
`src/jvmTest/kotlin/afield/four/synth/MarfTest.kt:276#a continuous address glides through the bank instead of stepping`.

So the clock has to be *chosen* rather than repaired, and `marfResolveClock` chooses it from the
leading field: discrete events → `STEPS`, none → `CYCLES`.

### The trap that cost the most: `hasOnset` is true for analog events

The first `AUTO` predicate asked `events.any { it.hasOnset }` and sent **every** signal to `STEPS`,
i.e. it implemented exactly the freeze it existed to prevent. `PatEvent.hasOnset` is
`whole == null || whole.start == arc.start` (`TidalPatterns.kt:404`), and its own KDoc says why:
*"Analog events (`whole == null`) are always onsets: there is no earlier fragment they could be the
tail of."* The field that separates a step from a voltage is **`whole`**, not `hasOnset` —
`signalOf` builds its event with `whole = null` (`PatSignals.kt:75`) and mini-notation gives every
step a whole. `marfFieldIsStepped` tests `whole != null`.

A predicate that reads correctly in English and inverts the thing you wanted is worth the extra
minute of reading the field it is built on.

### What moved, and what did not

One figure of seven re-rendered: `address-transforms`, and the diff is a single polyline. Its blue
`"0 1 2 3"` lane had a 16-sample plateau at the head under `CYCLES` — the initial latch — and under
`STEPS` it advances immediately. Its `verify` block is unchanged and still passes, pinning the
reversal at `[3,2,1,0]` then `[0,1,2,3]` and the euclidean rests at `[0,0,4,4,4,2,3,3]`. The lanes
and the published snippet now both name `fields = MarfFieldClock.STEPS`, because that figure's
comment used to argue that the *absence* of a `fields` argument was its point, and the absence
stopped meaning anything the moment `AUTO` became the default.

`continuous-address` and `discrete-to-continuous` did not change, which is the design working:
their lanes are `saw()` and `sine()`, and `AUTO` sends them to `CYCLES`.

Nor did the `marf_demo` document, checked reader by reader: every reader that matters names its
clock explicitly, and the only two that omit it — `g` in box 1 and `azR` in box 4 — are both
`saw().slow(8)`, so they resolve to `CYCLES` and read as they did.
