# `patCondense` — structural mini-notation synthesis

Built on `nightly/2026-09-10/1-pattern-structure-synthesis`, on top of `c08b19003`.

`src/jvmMain/kotlin/afield/four/synth/PatCondense.kt` takes a rhythm — a boolean step mask, a
`PatPattern<String>`, a `PatTranscription`, or a mini-notation string — and returns the shortest
string in Field's own mini-notation whose parse renders to an **identical** event set, together with
the structure it found and the compression it achieved.

```
patCondense("x ~ ~ x ~ x ~ ~").notation          // x(3,8)
patCondense(kolakoskiMask(16)).notation          // x(k16)
patCondense("bd [hh hh] sd [cp cp]").notation    // bd hh*2 sd cp*2
pat("bd sd hh cp").rev().condense().notation     // cp hh sd bd
patCondense("a b c d e c a d").notation          // a b c d e c a d   (nothing to find)
```

## Why this and not something else

`PatTranscription.kt`'s file header says, in the repo's own words, that *"there is no mini-notation
printer anywhere in Field, in either direction"*, and then adds one: `patNotationOf`
(`PatTranscription.kt:407`). That printer emits a **flat** slot list — `slots.joinToString(" ")` over
`grid.steps` slots. It recovers a *grid*. It does not recover a *structure*, so the loop that file
draws (notate → render → analyse → revise) closed only at the flat level.

**Step-0 falsification, run before any code was written.** Population: 1311 `.kt` files under `src/`.

| predicate | hits | verdict |
|---|---|---|
| `fun [A-Za-z.<>]*[Nn]otation` | 48 | all `…Annotation…`, or another language's notation (`sorting_networks.kt`, `PetriSmokeTest`, `BooleanNetworksSmokeTest`). Only `patNotationOf` is mini-notation, and it is flat. |
| `description length\|minimum description\|MDL\|shortest (expression\|string\|notation)` | 18 | every one a `getCmdListsCount`-style substring in `ImGuiRenderToTexture.kt` / `Instance.kt`. None in a pattern file. |
| `fun .*(recognis\|recogniz\|infer)[A-Za-z]*\(` | 20 | FieldML type inference, docs-claim detection, render-proof assertions. Nothing over patterns. |

Premise confirmed.

## How it works

### 1. Reduce the target to slots

Render the target over `cycles` cycles (or find its own period, up to `PAT_CONDENSE_MAX_PERIOD = 8`,
by comparing `[0,P)` against `[P,2P)` **and** `[2P,3P)`). Then, per cycle:

- **Layer** the events so that no two in a layer overlap, which is what `,` means. Two partitions are
  produced and *both* are handed to the search: greedy first-fit by start time, and grouping by atom.
  Which reads shorter depends on the figure; the oracle picks.
- **Grid** each layer onto the coarsest step grid that holds it: the lcm of every boundary's
  denominator, divided through by the gcd of the boundaries themselves. Without that second step
  `bd sd` arrives as twelve slots whenever some other layer needed twelfths.
- A slot holds an atom (`bd`, `bd:2`), a rest (`~`), or a hold (`_`) continuing the slot before it —
  the same three tokens `patNotationOf` emits, which is what makes the two flat spellings the same
  string.

### 2. Search the grammar

A memoised range DP over each layer's slots, keeping `PAT_CONDENSE_BEAM = 4` candidates per span. The
recursion is over **spans a mini-notation split can actually produce**: a group divides its span into
equal parts, so only spans whose length divides the whole and whose start is aligned to it are
reachable. That is what keeps the search finite without a heuristic. An arbitrary substring DP would
propose `[bd sd] hh` for three equal steps, which the grammar cannot mean.

Candidate forms, taken from `MiniPat.Node` (`TidalPatterns.kt:1127`) rather than from a tutorial:

| form | when it is proposed |
|---|---|
| the atom, `~` | a span of one slot; a span of all rests |
| the atom, again | a span that is one event held across all of it — `hh _ _` as a unit *is* `hh` |
| `x*n` | the span is `n` copies of its first `L/n` slots |
| `x!n` | adjacent equal items after an equal split |
| `x@n` | an item followed by `n-1` holds |
| `[ … ]` | any equal split into `m` parts, for every divisor `m` |
| `x(p,s)` / `x(p,s,r)` | the span is one atom on a `bjorklund(p, s)` mask, rotated |
| `x(kN)` / `x(gN)` and offsets | the span is one atom on a `kolakoskiMask` |
| `a, b` | between layers |
| `< … >` | between cycles, when they differ |

Two parser facts the search has to respect, both now in the KDoc because both cost candidates:

- **`@` and `!` re-weight the *parent* sequence.** `pat("x a@2 y")` is four slots and
  `pat("x [a@2] y")` is three. A token carrying either at top level is not atomic and must be
  bracketed before it can be used as a sequence item.
- **`_!2` does not parse.** `!` is in `STANDALONE_FOLLOWERS`, so the parser takes the `_` as a hold
  marker and then fails on a bare `!`. Hold tokens are never run-length folded.

### 3. The oracle is `pat(...)`

Every candidate is re-parsed through the entry point a user's box calls, re-rendered, and compared
event for event — part, whole, value and annotations, relative to each cycle's start; source spans
deliberately excluded, because `bd(3,8)` and `bd ~ ~ bd ~ bd ~ ~` cite different substrings of
different strings and sound identical.

There is **no second evaluator**. A hand-written one would agree with itself forever while drifting
from the language, and this project has that mistake recorded twice. The proposers above are allowed
to be wrong, because being wrong only costs a rejected candidate. The flat spelling is always in the
candidate set, so the search can never lose to it — asserted over the corpus, not assumed.

### 4. The bounds, and why each

| constant | value | why |
|---|---|---|
| `PAT_CONDENSE_BEAM` | 4 | candidates kept per span. The product across a split would otherwise explode; variant *t* is taken from every part at once, so the combination stays linear. |
| `PAT_CONDENSE_NODES` | 20 000 | spans expanded before the DP falls back to flat. Measured: `kolakoskiMask(64)` uses **69**. |
| `PAT_CONDENSE_MAX_SLOTS` | 256 | finest grid a cycle may need. Past it the reduction refuses. |
| `PAT_CONDENSE_ORACLE_CALLS` | 96 | parses + renders before the flat floor is taken. Measured: most targets use **1**. |
| `PAT_CONDENSE_MAX_PERIOD` | 8 | cycles searched for a period. |

`PatCondensation.limits` reports which were used and whether any was hit, so a caller can tell "there
is nothing shorter" from "the search stopped looking".

## Measurements

All from `src/jvmTest/kotlin/afield/four/synth/PatCondenseTest.kt`, 2026-09-10, every floor asserted
in the file rather than printed.

**Round trip over the corpus that already existed.** The corpus is the set of distinct `pat("…")`
literals in `PatContractTest.kt` and `SonicPiPatternsSmokeTest.kt`, scanned out of those files at test
time rather than copied: **81** distinct literals (123 occurrences in the first, 116 in the second).

- **78 of 81** condense exactly, each re-rendered independently of the `exact` flag.
- The 3 refusals are `[a|b|c] d`, `a*8?0.4` and `[bd*4|hh*2|cp]` — all randomised per cycle, all
  refused with a reason rather than fitted to their first bars.
- No condensation is longer than its own flat spelling. Worst ratio **1.000**.

**Compression.** Against the flat spelling, over the 78 exact: median **1.000**, best **0.127**
(`a(k16) b`). The median is 1.000 because more than half of these literals are two or three atoms and
already minimal; the number that says anything about the search is over the **35 of 78** it shortens
at all, where the median is **0.429**. Against the string a person wrote, the median is 1.000 and the
worst is 1.000 — the search never lengthens the corpus.

**The counterfactual, in two parts, because the obvious one is false.**

A 16-step *boolean* mask always has structure to find: for run-length replication to be no help it
must have no two adjacent equal cells, and over two symbols that forces strict alternation, which is
`[x ~]*8`. Measured, **0 of 400** random masks (seed 20260910, one step in three on) came back as
their own flat spelling. Every one of them re-renders exactly, so nothing was invented; a sparse mask
really does contain runs.

So the sharper claim is asserted instead. Over those 400, **6** read as a single whole-span figure,
and each was recomputed here from `bjorklund` / `kolakoskiMask` and compared against the mask:
**0 wrong**. Their median compression is **0.645**, against **0.127** for a real Kolakoski figure — a
separation with a floor asserted on it, because if noise compressed as well as structure the search
would be finding figures in nothing.

The counterfactual that *does* hold needs a larger alphabet. **200 of 200** step sequences over five
atoms, eight steps, with no two adjacent alike and no equal-width block repeated at any divisor, come
back byte-identical to what went in.

**The audio loop, closed structurally.** Render `60 ~ ~ 60 ~ 60 ~ ~` to 88 200 samples at 44.1 kHz,
discard the pattern, run `patTranscribe` over the samples, condense what it heard: `60(3,8)`. Same
path, `60 ~ ~ 60 60 ~ 60 ~` → `60(k8)`. `PatTranscription.kt`'s last arrow used to land on a slot
list.

## What it cannot find, and why

Pinned in `PatCondenseTest.the four things the search cannot find are the four things it says it
cannot find`, each against a *live pattern* so the source string is not available as a fallback.
Whichever of these gets built next should turn one of those assertions red.

1. **Alternation nested inside a sequence.** `< … >` is proposed only at the top level, one whole
   cycle per alternative, so `bd <sd cp>` costs `<[bd sd] [bd cp]>` — 17 characters against 10.
   Fixing it means searching for a per-slot alternation across the cycle beam, which is a second
   dimension on the DP.
2. **Polymeter is never proposed.** `{a b c}%8` phases over three cycles and comes back as those three
   cycles written out: 54 characters against 9. A polymeter recogniser would have to notice that the
   same short sequence is being read at a different rate, which the slot model erases.
3. **A rhythm figure over anything but a single atom.** `[bd cp](3,8)` puts a two-atom group on the
   Euclidean onsets; the recogniser requires one repeated voice and will not look at it. This is the
   cheapest of the four to fix: propose `unit(block)(p,s)` wherever the true steps all hold the same
   *sub-expression* rather than the same token.
4. **The Kolakoski alphabet.** `kolakoskiMask(16, a = 2, b = 3)` has no spelling — the notation has
   `k` and `g` over `{1,2}` and nothing else — so that figure decomposes into pieces. This one is a
   grammar gap, not a search gap.

Four more, not pinned because they are properties of the model rather than of the search:

5. **Randomised targets are refused**, not approximated: `?`, `|`, `shuffle`, `randcat`. Two
   comparison windows are used rather than one, because a coin-flip agrees with itself across a
   single comparison often enough to matter.
6. **Events crossing a bar line are refused.** `pat("a b c").slow(2)` has no bar to spell itself in.
7. **Parameters no atom can write down are refused.** `jux` writes `pan` onto every event and `chop`
   writes `begin`/`end`; the only parameter mini-notation carries is the `:n` sample index. The flat
   spelling is already lossy for those, so the search says so rather than returning a rhythm that
   sounds different.
8. **Grouped unequal weights are not proposed.** `[a b]@5 [c d]@3` is reachable only as
   `a@5 b@5 c@3 d@3`, which happens to be the same length. A wider split rule would need weights in
   the DP state.

**And the beam is a beam.** The result is the shortest string *found*, not a proof that no shorter
string exists. `PatCondensation.bounded` is the flag that says a bound was actually hit; on everything
measured above it is false.

## Running it

```bash
# the tests
./gradlew jvmTest --tests 'afield.four.synth.PatCondenseTest'

# the figure (roll 11 of the piano-roll gallery)
python3 tools/run_field_tests.py --id synth.pat.gallery --port <p>
python3 tools/field_backdoor.py --port <p> run-jvm-main \
    afield.four.synth.PatPianoRollGallery --arg "$PWD/dev_docs/assets/pat_gallery"

# the demo
./field.sh dev_docs/agents/demos/pattern_condense --read-only
```

Reader-facing prose is the "The shortest way to write a rhythm down" section of
`public/docs/pattern_language.md`. The gallery entry is in
[`PAT_GALLERY.md`](./PAT_GALLERY.md).
