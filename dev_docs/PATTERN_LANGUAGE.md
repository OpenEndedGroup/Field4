# The `pat(...)` pattern language — design decisions

`afield.four.synth`'s pattern language is four files. This document is the *why*: the decisions that
are not obvious from the code, the two places it knowingly diverges from Tidal, three behaviours that
were measured rather than assumed, and one advertised feature that turned out to be broken.

| file | what lives there |
| --- | --- |
| `TidalPatterns.kt` | the event model (`PatQ`, `PatArc`, `PatEvent`, `PatPattern`), the mini-notation parser (`MiniPat`), source spans for editor highlighting, scheduling into seconds, and the original transform set |
| `PatSignals.kt` | continuous/signal patterns and the discretisers that turn them back into events |
| `PatAlgebra.kt` | how two patterns of different types meet, whose rhythm survives, and named parameters |
| `PatComposition.kt` | arrangement operators, scales, chords, and voice leading |

Verified by `PatSignalsTest`, `PatAlgebraTest`, `PatCompositionTest`, `MiniNotationTest` and
`PatContractTest` (97 tests), the `synth.pat.gallery` piano rolls and the `synth.pat.sound` `.wav`
renders — see [PAT_GALLERY.md](PAT_GALLERY.md).

---

## 1. A signal is not a new type

`PatPattern<A>` is an *event* stream: query an arc, get a list of things that happened in it. A
continuous signal is the degenerate case — **exactly one event per query, spanning the whole arc,
whose value is a function of time**:

```kotlin
fun signalOf(sample: (exact: PatQ, t: Double) -> Double): PatPattern<Double> = PatPattern { arc ->
    val mid = patArcMidpoint(arc)
    listOf(PatEvent(arc, sample(mid, mid.toDouble())))
}
```

No new type, no parallel combinator set: `sine().rev()`, `sine().every(4) { it.fast(2) }` and
`stack(pat("bd"), ...)` all already work, because a signal *is* a pattern.

### The sampling convention: exact rational arc midpoint

A signal queried with arc `a` is sampled at `(a.start + a.end) / 2`, computed in `PatQ` — the
conversion to `Double` happens only inside the signal function. Three reasons:

1. **It matches Tidal**, so a musician's ear-knowledge transfers.
2. **It is the only convention under which `segment(n)` samples each cell at its own centre**, which
   makes `sine().segment(4)` symmetric about the cycle and stable under `rev()`. Sampling at the
   start or the end shifts the values half a cell and the reversal no longer lines up.
   `PatSignalsTest.a segmented sine is symmetric under rev` is that property.
3. **It is total.** Every arc, however small, has a value. A signal can never be "missed" by a query
   the way a zero-width event could be.

The exactness is not decorative. `PatSignalsTest` asserts the midpoint of `3/7 .. 5/7` is exactly
`4/7` — a fraction with no binary representation. The one remaining approximation in the chain is
`PatQ.toDouble()`, which rounds to twelve decimal places, so a sampled value agrees with the
mathematical one to about `1e-11`. `rand()` sidesteps even that: it hashes the reduced numerator and
denominator of the rational instant, so it is granularity-free and identical on any machine.

### `segment(n)` is query-granularity independent

`segment(n)` does **not** just sample at the query arc's midpoint. It builds the `1/n` grid itself,
samples the source over each *whole* grid cell, and only then clips the emitted event to the query
arc. So the values you observe never depend on how the caller chopped up the arc — chop a cycle into
eight queries and every cell still reports the value at its own centre.
(`PatSignalsTest.segment is query-granularity independent`.)

`struct(structure, values)` is the general form: rhythm from a boolean pattern, values sampled per
structural event. `segment(n)` is `struct` against the `1/n` grid, specialised so the grid arithmetic
stays exact.

---

## 2. Structure choice, and the one deliberate divergence from Tidal

Three combinators; everything else in `PatAlgebra.kt` is built on them.

| combinator | what survives |
| --- | --- |
| `appBoth` | every pair of intersecting events, each output arc the *intersection*. The only one that can create events. |
| `appLeft` | **exactly the left's events**, one output per input, each taking a right value |
| `appRight` | the mirror image |

`appLeft`'s rule is: **query the right pattern over the left event's own arc, and take the right event
containing the left arc's start.** That single rule makes discrete and continuous parameters work
through one implementation:

- a **discrete** right pattern latches its value at note **onset**, which is what a synth voice does
  anyway — parameters are read when the envelope is triggered;
- a **continuous** right pattern answers the left event's whole arc with one event whose value is
  that note's midpoint, so `gain(sine())` gives one gain per note, taken at the middle of the note.

**Tidal instead fragments the left event**, one fragment per overlapping right event, because a Tidal
event carries both a `whole` and a `part`. `PatEvent` has a single arc, so fragmenting here would
silently multiply notes: ask for four kicks with a sixteen-step filter sweep and you would get
sixty-four kicks. Cardinality preservation is the more useful invariant for a composition language,
and it is asserted directly:

```
appLeft preserves note count where appBoth multiplies it
  pat("bd*4")                                     ->  4 events
  pat("bd*4").appBoth(saw().segment(16))          -> 16 events   (fragmentation, on purpose)
  pat("bd*4") with cutoff(saw().segment(16))      ->  4 events, arc for arc identical to the kicks
```

The cost: because `appLeft` never fragments, a **sub-cycle** query samples the clipped fragment's
start rather than the note's true onset. Every scheduling path in the language (`eventSequence`,
`scheduled`, `peek`) queries whole cycles, so this only bites if you call `events(...)` with a partial
arc by hand.

### Operator spellings, and why three families could not keep Tidal's

Kotlin backtick identifiers cannot contain `/`, `<` or `>` — the compiler rejects them outright with
`Name contains illegal characters`. So:

| Tidal | here | note |
| --- | --- | --- |
| `\|+\| \|+ +\|` and the same for `-` `*` `%` | unchanged | legal, and they compile |
| `\|/\| \|/ /\|` | `` `\|over\|` `` `` `\|over` `` `` `over\|` `` (+ `divBoth` / `divLeft` / `divRight`) | `/` is illegal in an identifier |
| `\|>` `>\|` `\|<` `<\|` | `` `\|R` `` `` `R\|` `` `` `\|L` `` `` `L\|` `` | `<` and `>` are illegal; the bar keeps its Tidal meaning and the arrow becomes an `L` or an `R` |
| `#` | `` `#` `` | legal, and it is the operator you reach for most |

Every operator also has a prose alias (`addBoth`, `mulLeft`, `valuesFrom`, `rhythmFrom`, …). The file
carries `@file:Suppress("DANGEROUS_CHARACTERS")`: Kotlin warns that `|` in a name can cause problems
on Windows, which is true of *file* names and irrelevant to a JVM method name. Without the
suppression the operator family adds nineteen warnings to every build.

### Named parameters ride on `annotations`

`PatEvent` already carried `annotations: Map<String, String>`. Parameters use it — no parallel
substrate — which is why they survive `rev`, `fast`, `every`, `stack`, scheduling and the
source-span bookkeeping without any of those knowing parameters exist.

```kotlin
val voice = pat("bd sd hh sd") with gain(sine().range(0.3, 1.0)) with pan(saw()) with speed(2)
voice.peek(4).forEach { println(it.value to it.num("gain")) }
```

Numeric parameters round-trip through text at **nine decimal places** (`patFormatNumber`): far finer
than any audible parameter and short enough that an event prints readably in a box output. Read them
back with `num(key)` / `int(key)`.

The well-known keys are `gain pan speed cutoff resonance room shape n note sustain`, plus
`begin`/`end` written by `chop` and `striateAcross`. `patRenderStereo` in `PatSoundDocs.kt` is the
reference consumer.

---

## 3. Voice leading — the piece Tidal has no answer for

`chords("c'maj7 a'min7 d'min7 g'dom7")` spelled from each chord's own root makes the hand leap.
`voiceLead(center, spread)` chooses each chord's inversion and octave to minimise the total semitone
movement of the voices from the previous chord — an exhaustive search over every rotation (each
rotated-off note lifted an octave) at every octave transposition that keeps the voicing inside
`center ± spread`, scored by `patVoicingDistance`.

Measured live by `PatCompositionTest` and printed by both the roll and the `.wav` scene:

```
'c'maj7 a'min7 d'min7 g'dom7' as written moves 83.0 semitones
                             voice-led it moves  9.0 semitones
```

with **every chord's pitch-class set unchanged** — the harmony is identical, only the voicing moves.
The test asserts that as well as the reduction, so a "smaller number" cannot be bought by changing
the chords.

Chords are led in scheduled order within each cycle, so the pattern stays a pure function of the
queried cycle and is still safe to query out of order or in parallel.

---

## 4. Three behaviours that were measured, not assumed — and one that was fixed

Writing the tests and the gallery turned up four places where the pre-existing code does something
other than its name suggests. The first two are left alone — callers could reasonably depend on them —
and are now documented and pinned by a test, with the genuinely missing operator added alongside where
one was hiding behind a name. The third was a plain defect and is fixed.

**`bjorklund(3, 8)` is `x..x.x..`**, a rotation of the canonical `x..x..x.`. Just as Euclidean (gaps
3, 2, 3); the gallery asserts the gaps rather than the exact slots.

**`striate(n)` slices each event in place**, despite the Tidal name — it agrees with `chop(n)` on
values and never interleaves. Tidal's actual `striate`, which plays the whole pattern `n` times per
cycle with a different `begin`/`end` slice each pass, is added as **`striateAcross(n)`**.
`pat("a b").chop(2)` reads `a a b b`; `pat("a b").striateAcross(2)` reads `a b a b`.

**`slow(n)`, `fast(n)`, `timeScale`, `density` and mini-notation's `/n` were anchored per cycle, and
silently dropped material.** `pat("a b c d").slow(2)` gave `a b` in **every** cycle — `c d` was never
heard at all, on any cycle, ever. `slow(4)` gave `a` forever. `[a b c d]/2` the same. `fast(n)` was
`cat` of *n* copies, so `pat("<a b>").fast(2)` replayed cycle 0 twice and read `a a` instead of `a b`.
And `chunk(n, f)`, built on both, applied its transform to the same quarter forever rather than
walking through them.

This was the deepest defect on the branch and it is fixed, not documented: all five are now defined in
terms of one absolute-time primitive, `stretch(by: PatQ)`, which queries the source over `arc / by` and
maps the result back by `× by`. `pat("a b c d").slow(2)` now reads `a b`, then `c d`, then `a b`. For a
cycle-invariant pattern `fast(n)` is bit-identical to the old `cat`-of-copies, so nothing that worked
before moves. `fastBy` / `slowBy` take an exact `PatQ`, and `fastBy(f).slowBy(f)` is asserted to be the
identity for factors 2, 3, 3/2 and 5/4.

The visible consequence in the contract sweep: `slow`, `timeScale` and `density` had to be re-marked
as per-cycle-*varying*, which is the sweep saying out loud that they now span cycles.

One thing that genuinely is a per-cycle operator and stays one: `cat`. `cat(a, b).slow(2)` is still not
"two cycles of `a` then two of `b`" — `cat` packs its arguments into one cycle by design. `whenCycle` is
the operator for that, and both before/after `.wav` scenes use it.

Relatedly, `off(by)` *is* built on `delay`, which is genuinely cycle-spanning, so an echo whose arc
crosses a cycle boundary wraps into the next cycle. `pat("a b c d").off(1, 8) { it }` yields **nine**
events in a cycle, not eight: four originals, four echoes, and the previous cycle's clipped tail.

---

## 5. Mini-notation: what was already there, and what is new

Already present before this work, and **not** reimplemented: `{a b c}%4` and `{bd sd, hh hh hh}`
polymeter, `!` replicate, `@` elongate, `[a|b]` per-cycle random alternative, `(p,s,r)` Euclidean
suffixes, `<a b>` per-cycle alternation, `*` / `/` density, `,` stacking, `{{ kotlin }}` inline
expressions, and `ply` under the name `stutter(n, gap)`.

Added:

- **`.` groups**: `bd sd . hh hh hh` is `[bd sd] [hh hh hh]` — two halves, not five fifths.
- **`_` extends the previous step**: `bd _ _ sd` is `bd@3 sd`.
- **A bare `!` repeats it**: `bd ! !` is `bd bd bd`. This one needed no parser addition — `parseTerm`'s
  postfix loop already skips whitespace before an operator, so it saw each `!`. What it needed was for
  repeats to *accumulate* rather than nest: `Repeat(Repeat(bd, 2), 2)` is four copies where Tidal
  gives three.
- **`?` degrade-with-probability**, `bd*8?` (a half) or `bd*8?0.35`. Deterministic per event and per
  cycle; the same text always gives the same groove.
- **`:n` sample index as a parameter.** `pat("bd:3")` still has the atom value `bd:3` — nothing
  existing moves — but the event now also carries `n = 3`, so `event.num("n")` works and the
  parameter algebra can see it. `dropSampleIndex()` gives the bare name. A parenthesised payload
  (`g:(0,0.12)`, which `PatFunctions.kt` relies on) is deliberately *not* a sample index.
- **`'` is a legal atom character**, so chord roots parse: `chords("c'maj7 <a'min7 f'maj7>")`.

All three markers are markers **only when they stand alone** — followed by whitespace, the end of the
string, or a closing/separating character. That is the compatibility guarantee, and it is what keeps
`0.5`, `1.25`, `my_atom`, `bd!2` and `g:(0,0.12)` parsing exactly as they did: their `.`, `_` and `!`
are inside a token, so `readAtom` consumes them and `parseSeq` never sees them. `MiniNotationTest`
asserts each shorthand is arc-for-arc and value-for-value its bracketed equivalent, and re-checks
eleven pre-existing forms for their original event counts.

### Polymeter was silent after one bar

The largest single find of this work, and it was found by *looking at a figure*: the piano-roll
gallery queries two whole cycles in one call, and both polymeter lanes were empty after the first.

`MiniPat.compilePolyLayer` builds **absolute** windows — repetition *r* of a three-slot layer against
an eight-pulse grid sits at `[3r/8, (3r+1)/8)`, which leaves `[0, 1)` as soon as `r >= 3` — and handed
them to `within`, which reads its window as a *local* arc inside one cycle (`base + window.start`).
Every window from cycle 1 on therefore failed to intersect, and:

```
pat("{bd hh 808:4}%8")            cycle 0: 8 events   cycle 1: 0   cycle 2: 0
pat("{bd sd stab, cp arpy cr}")   cycle 0: 6 events   cycle 1: 0   cycle 2: 0
```

An advertised feature — it is in `TidalPatterns.kt`'s own file header — that played one bar and then
stopped, on the cycle-by-cycle queries the scheduler makes as well as on a multi-cycle one.

The fix is a new `patFocus(window, cycleIndex, pattern)`: the absolute-time counterpart of `within`.
Cycle 0 is byte-identical (repetition 0 maps to child cycle 0, exactly what `within` did), and the
sequence now phases against the grid as it should — `{bd hh 808:4}%8` starts on `bd`, then `808:4`,
then `hh`, because after eight pulses a three-item sequence has advanced by `8 mod 3 = 2`. Passing the
repetition index down as the child's own cycle also un-freezes per-cycle constructs inside a layer, so
`{<a b> c}%4` alternates instead of playing `a` forever. Four tests pin it, including that cycle 0 is
unchanged.

`PatSourceSpan` bookkeeping is preserved throughout — the editor highlighting in `Marker.patHighlight`
still points back into the pattern text, and `with(...)` adds the parameter pattern's spans to the
event's `additionalSpans` so a highlighted event can show where its parameter came from too.

---

## 5b. The contract sweep

The polymeter bug was not a polymeter bug so much as an instance of a *class*: an operator that
answers cycle 0 and then stops. Nothing in the language would have caught it, because every other
test — and the scheduler itself — only ever asks for one cycle at a time and only ever looks at the
first.

`PatContractTest` closes that class. It holds one expression per operator across all four files
(currently 106 entries, guarded against shrinking) and asserts five properties of `PatPattern` itself:

| property | what it catches |
| --- | --- |
| **liveness** — every pattern makes an event in each of the first six cycles | the whole "works once, then silent" family |
| **cycle-invariance** — a pattern with no per-cycle construct repeats exactly | drift, frozen alternation, off-by-one tile arithmetic |
| **query-range additivity** — `[0,N)` in one call equals N single-cycle calls | events lost or duplicated at a query boundary |
| **purity** and **query-order independence** | accidental state between queries |
| **containment and well-formedness** | events escaping the queried arc; empty or inverted arcs, at whole-cycle, sub-cycle, straddling and negative arcs |

Two exemption lists keep it honest, and both are themselves asserted:

- `mayBeSilent()` is exactly `{silence, mini rest, unDegradeBy}`, each with a stated reason. Adding to
  it is the obvious way to defeat the liveness property, so the membership is pinned by an assertion.
- `varying()` entries are exempt from cycle-invariance, and a separate test requires each of them to
  *actually* differ across six cycles — an unearned exemption fails the build. That test immediately
  found seven entries I had marked as varying which are in fact cycle-invariant, including `slow`,
  `zoom` and `within`, all for the documented tile-anchoring reason.

And the sweep was **checked for sensitivity** rather than assumed: reinstating the old `within` call
in `compilePolyLayer` makes it fail with

```
patterns that fall silent (the polymeter-bug family):
  mini polymeter:       events per cycle = [6, 0, 0, 0, 0, 0]
  mini polymeter %:     events per cycle = [8, 0, 0, 0, 0, 0]
  mini poly alternate:  events per cycle = [4, 0, 0, 0, 0, 0]
```

A first draft of the sweep did *not* catch it: query-range additivity holds when a bug is silent
everywhere, because the multi-cycle query and the single-cycle queries agree on producing nothing.
Liveness is the property that has teeth here, which is worth knowing before adding the next one.

---

## 6. The pattern-of-patterns primitive, groove, and probability per event

Three additions that are worth calling out because each one is a *kind* of operator the surface did
not have, not just another combinator.

**`squeezeJoin` / `squeeze`.** The existing `join` unwraps a `PatPattern<PatPattern<A>>` without
rescaling: it asks each inner pattern what it is doing *at* the outer event's position, so an inner
triplet keeps its own grid and is merely cut at the outer boundary. `squeezeJoin` **compresses** each
inner pattern's whole cycle *into* the outer event's arc. That is what mini-notation's `[a b]` nesting
does inside a string, now available over Kotlin values:

```kotlin
pat("a b").squeeze { if (it == "a") pat("1 2 3") else pat("4 5") }   // 1 2 3 4 5
```

`PatCompositionTest` asserts both — `squeeze` gives five events on a 1/6-and-1/4 grid, `join` on the
same input gives four with a split at 1/2 — so the difference is measured rather than asserted from
the docs.

**Groove: `swingBy` / `swing` / `pressBy` / `press`.** `swingBy(amount, n)` delays every second
subdivision of a `2n` grid by `amount` of one subdivision, in exact rational time — so a swung pattern
is still exactly quantised, just not straight. `swing(4)` is the triplet feel (`1/3` of an eighth
late, i.e. `1/24` of a cycle). Events pushed past the end of the cycle are clipped, not wrapped:
swing is a groove applied within the bar. `pressBy(r)` squeezes each event into the later `1 - r` of
its own arc, which turns four-on-the-floor into an off-beat without touching the grid.

**Probability per event, and the name collision that made it necessary.** `sometimesBy` in
`TidalPatterns.kt` chooses per **cycle** — the whole cycle is transformed or none of it is — which is
Tidal's `someCyclesBy`, not Tidal's `sometimesBy`. Rather than change behaviour callers may depend on,
`someCyclesBy` is added as an accurate alias and the genuinely per-event version is
`sometimesEventBy(p) { … }`, built on a new `unDegradeBy` that keeps exactly the events `degradeBy`
drops. That only works if the two share one hash, so `eventKey` was widened from `private` to
`internal` rather than reimplemented — two independent copies of "the same hash" would silently
overlap or leave gaps, and the test asserts the two halves partition the events exactly, at three
probabilities across eight cycles.

Also in this batch: `ncat` (weighted `cat`, exact rational shares), `pick` (index a list with a
numeric pattern), `euclidInv` / `euclidFull` (the off-beats of a Euclidean figure), and `toScale` (an
arbitrary scale from a list of semitones).

Three names deliberately *not* added, because the surface already has them:
`inside(n, f)` is the existing `chunk(n, f)`; `ply(n)` is `stutter(n, PatQ.ZERO)` (kept as an alias
because `ply` is the name the Tidal docs use); and `someCyclesBy` is `sometimesBy`.

---

## 7. The surface, at a glance

**Time** (`TidalPatterns.kt`) — `stretch fastBy slowBy fast slow timeScale density zoom delay rev
palindrome patFocus within cat stack`

**Signals** (`PatSignals.kt`) — `sine cosine saw isaw tri square envL envLR rand irand perlin sig
signalOf constant run grid` · `segment discretise struct bools quantise rounded range rangex bipolar
nums texts patArcMidpoint patFormatNumber`

**Algebra** (`PatAlgebra.kt`) — `appBoth appLeft appRight patSampleOver` · `` `|+|` `|+` `+|` `` and
`- * %` · `` `|over|` `|over` `over|` `` · `` `#` `|R` `R|` `|L` `L|` `` · prose aliases · `PatParam
param paramOf with without` · `gain pan speed cutoff resonance room shape n note sustain` ·
`num int`

**Composition** (`PatComposition.kt`) — `off superimpose layer jux juxBy iter iterBack linger ply rot
shuffle randcat wrandcat choose wchoose chop striateAcross` · `squeezeJoin squeeze pick swingBy swing
pressBy press ncat unDegradeBy sometimesEventBy sometimesEvent someCyclesBy euclidInv euclidFull` ·
`PAT_SCALES patScaleSteps patScaleDegree scale toScale` · `PAT_CHORDS patChordSteps patNoteNumber
chords chordsOf patParseChord chord notesOf arpeggiate` · `voiceLead patLeadVoicing
patVoicingDistance patTotalVoiceMovement`

**Rendering** (`PatSoundDocs.kt`) — `PatWave patVoice patRenderStereo patMidiToHz`

> The generated reference under `public/docs/reference/` is **not** affected: checked, and
> `StdlibReferenceGenerator.SOURCE_ROOTS` covers only `afield/stdlib` and `afield/visualization`, so
> `afield/four/synth` has never been in its scope. `./gradlew --no-daemon check` — which runs
> `checkCodebaseReference` and `checkKDocAttachment` — is green on this branch.
