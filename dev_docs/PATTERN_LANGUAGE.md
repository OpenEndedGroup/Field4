# The `pat(...)` pattern language — design decisions

`afield.four.synth`'s pattern language is four files. This document is the *why*: the decisions that
are not obvious from the code, the two places it knowingly diverges from Tidal, and three behaviours
that were measured rather than assumed.

| file | what lives there |
| --- | --- |
| `TidalPatterns.kt` | the event model (`PatQ`, `PatArc`, `PatEvent`, `PatPattern`), the mini-notation parser (`MiniPat`), source spans for editor highlighting, scheduling into seconds, and the original transform set |
| `PatSignals.kt` | continuous/signal patterns and the discretisers that turn them back into events |
| `PatAlgebra.kt` | how two patterns of different types meet, whose rhythm survives, and named parameters |
| `PatComposition.kt` | arrangement operators, scales, chords, and voice leading |

Verified by `PatSignalsTest`, `PatAlgebraTest`, `PatCompositionTest` (58 tests), the
`synth.pat.gallery` piano rolls and the `synth.pat.sound` `.wav` renders — see
[PAT_GALLERY.md](PAT_GALLERY.md).

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

## 4. Three behaviours that were measured, not assumed

Writing the tests and the gallery turned up three places where the pre-existing code does something
other than its name suggests. None of them is changed — callers depend on them — but all three are
now documented and pinned by a test, and where a genuinely missing operator was hiding behind a name
it has been added alongside.

**`bjorklund(3, 8)` is `x..x.x..`**, a rotation of the canonical `x..x..x.`. Just as Euclidean (gaps
3, 2, 3); the gallery asserts the gaps rather than the exact slots.

**`striate(n)` slices each event in place**, despite the Tidal name — it agrees with `chop(n)` on
values and never interleaves. Tidal's actual `striate`, which plays the whole pattern `n` times per
cycle with a different `begin`/`end` slice each pass, is added as **`striateAcross(n)`**.
`pat("a b").chop(2)` reads `a a b b`; `pat("a b").striateAcross(2)` reads `a b a b`.

**`slow(n)` and `timeScale(ratio)` are anchored per cycle**, not cycle-spanning. `cat(a, b).slow(2)`
is *not* "two cycles of `a` then two of `b`" — it doubles the density instead. `whenCycle` is the
right operator for that, and both before/after `.wav` scenes use it. (`fast(n).slow(n)` is still the
identity on a cycle-invariant pattern, which is what `PatSignalsTest` asserts.)

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

- **`?` degrade-with-probability**, `bd*8?` (a half) or `bd*8?0.35`. Deterministic per event and per
  cycle; the same text always gives the same groove.
- **`:n` sample index as a parameter.** `pat("bd:3")` still has the atom value `bd:3` — nothing
  existing moves — but the event now also carries `n = 3`, so `event.num("n")` works and the
  parameter algebra can see it. `dropSampleIndex()` gives the bare name. A parenthesised payload
  (`g:(0,0.12)`, which `PatFunctions.kt` relies on) is deliberately *not* a sample index.
- **`'` is a legal atom character**, so chord roots parse: `chords("c'maj7 <a'min7 f'maj7>")`.

`PatSourceSpan` bookkeeping is preserved throughout — the editor highlighting in `Marker.patHighlight`
still points back into the pattern text, and `with(...)` adds the parameter pattern's spans to the
event's `additionalSpans` so a highlighted event can show where its parameter came from too.

---

## 6. The surface, at a glance

**Signals** (`PatSignals.kt`) — `sine cosine saw isaw tri square envL envLR rand irand perlin sig
signalOf constant run grid` · `segment discretise struct bools quantise rounded range rangex bipolar
nums texts patArcMidpoint patFormatNumber`

**Algebra** (`PatAlgebra.kt`) — `appBoth appLeft appRight patSampleOver` · `` `|+|` `|+` `+|` `` and
`- * %` · `` `|over|` `|over` `over|` `` · `` `#` `|R` `R|` `|L` `L|` `` · prose aliases · `PatParam
param paramOf with without` · `gain pan speed cutoff resonance room shape n note sustain` ·
`num int`

**Composition** (`PatComposition.kt`) — `off superimpose layer jux juxBy iter iterBack linger ply rot
shuffle randcat wrandcat choose wchoose chop striateAcross` · `PAT_SCALES patScaleSteps
patScaleDegree scale` · `PAT_CHORDS patChordSteps patNoteNumber chords chordsOf patParseChord chord
notesOf arpeggiate` · `voiceLead patLeadVoicing patVoicingDistance patTotalVoiceMovement`

**Rendering** (`PatSoundDocs.kt`) — `PatWave patVoice patRenderStereo patMidiToHz`

> The public reference under `public/docs/reference/` is generated and is now stale with respect to
> these declarations. It was deliberately not regenerated here (a different change owns those files);
> run `./gradlew generateCodebaseReference` when the tree is quiet.
