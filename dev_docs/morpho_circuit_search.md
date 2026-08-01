# Searching for MorphoHDL circuits

`afield.morpho` gained a generative / evolutionary search layer on 2026-08-01, twelve hours after
the MorphoHDL bridge itself landed (`2a698dfc`). This is what it does, what it found, and — the
longer half — what it did not find.

Regenerate every number below with:

```
./gradlew jvmTest --tests "afield.morpho.MorphoSearchMeasurements" --rerun -i
```

and the assertions that keep them honest with:

```
./gradlew jvmTest --tests "afield.morpho.MorphoSearchTest"
```

---

## The one design decision

**A genome is MorphoHDL source text.** Not a netlist, not an internal SSA object — text, in the
grammar `parser.js` accepts, handed to upstream's `parse()` and `compileModule()` for every single
fitness evaluation.

That is slower than the alternative and it is the only honest option. `src/jvmMain/resources/morpho/PROVENANCE.md`
opens by saying the bridge is only worth anything if it is *their* compiler producing the netlist;
a search that generated netlists directly would be optimising against Field's idea of what
MorphoHDL means, and would drift from the language the moment upstream changed anything. It also
has a practical payoff: everything the search finds is a program a person can read and paste into a
morpho box.

The five files:

| file | what is in it |
| --- | --- |
| `MorphoSpec.kt` | `MorphoSpec` (oracle + width family), `MorphoCost`, `MorphoFitness`, `MorphoEvaluator` |
| `MorphoGenome.kt` | `MorphoLut` / `MorphoPalette` / `MorphoStatement` / `MorphoCellGenome` / `MorphoGenome`, and `MorphoWidthModel` |
| `MorphoGenerator.kt` | `MorphoCellSketch` (what is fixed) and `MorphoProgramGenerator` (random programs, mutation, crossover, repair) |
| `MorphoSearch.kt` | `random` / `hillClimb` / `evolve` / `staged`, `MorphoSelection`, `MorphoSearchRun` |
| `MorphoProblems.kt` | seven named problems, so a box, a test and this page ask the same question |
| `MorphoSearchFLines.kt` | the two vector figures |

Nothing in the cost model is invented either. Cells, LUTs, gates and fan-out are read off
`MorphoNetlist`; depth is `MorphoTiming`, which is morpho's own `updateTimes()`.

## Width is the interesting part

A MorphoHDL cell does not declare bus widths. `ripple_adder` is eleven lines and it is a 4-bit
adder and a 64-bit adder, so **a design is a claim quantified over all N**. A `MorphoSpec` therefore
carries a *list* of widths, and `MorphoEvaluator` compiles and simulates a candidate once per width.
A candidate is `correct` only if every width is exact.

This is not decoration. `MorphoSearch.generalization` re-scores a found program at widths nothing
was selected on, and `MorphoSearchTest` uses it to check every winner at 16, 32 and 48 bits. It is
the same source text at a new signature — there is nothing to translate and nothing to get wrong.

The error a search *climbs* is the mean across widths; the verdict is the maximum. An earlier
version scored on the maximum alone and searched noticeably worse: a candidate that has learnt to
add two bits and not yet to carry across eight is progress, and a max-only score cannot see it.

## Four things that were learned the hard way

**Recursion has to be proved to terminate, statically.** MorphoHDL stops recursing by running out of
bits: `SPLIT` below width 2 returns `null` (`primitives.js:41`), the compiler discards the body
(`compiler.js:1053`) and the fallback answers. A generated body has no such courtesy, and the local
rule "the arguments are narrower than the inputs" is *not* sufficient, because the body computes
different widths at every level. The first version emitted `evolved(ONE, t0, ONE)` where `t0` is as
wide as `b`, which calls itself at the same signature forever. `MorphoWidthModel.terminates` walks
the signature graph instead and treats a signature revisited on the current path as a cycle — exact
with respect to the width model, which is all the generator has. Behind it, `MorphoRuntime.interruptGuest`
lets the evaluator interrupt the shared Graal context from a watchdog thread, so a candidate the
model got wrong costs one timeout rather than the process.

**Introns are load-bearing.** Pruning dead statements out of the *genome* made an empty cell an
absorbing state. Hill climbing reliably drifted to `return a, ZERO` — zero gates, 37.5% wrong — and
then every operator became a no-op: nothing to rewire, nothing to delete, and an inserted statement
was dead the instant it appeared so pruning removed it again. Genotype now keeps dead statements and
only `render()` prunes.

**Arity has to be mutable.** `swapOpcode` originally swapped a LUT for another of the same arity.
The full adder hill climbing kept reaching was `sum = Xor3(a, b, c)`, `c_out = And(a, b)` — two of
sixteen bits wrong, one `And` → `Maj3` from exact — and with arity frozen that swap did not exist.

**Budget is compiler invocations, not proposals.** Hill climbing re-proposes its own parent
constantly and random sampling almost never does, so charging per proposal hands the control arm
about three times the real work and calls it a fair fight.

---

## Results

Five seeds per cell except the last two rows, which are three. "first correct" lists the invocation
at which each solving seed first produced an exactly-correct design.

| problem | widths | budget | random (control) | hill climb | evolution |
| --- | --- | --- | --- | --- | --- |
| bitwise xor (the harness canary) | 2/4/8 | 2000 | **5/5**, 6–30 | **5/5**, 3–26 | **5/5**, 7–23 |
| one-bit full adder | 1 | 4000 | **0/5** | **4/5**, 315–3846 | **5/5**, 165–1152 |
| or-reduce, `y = (x != 0)` | 2/3/5 | 6000 | **3/5**, 1617–4077 | **0/5** | **3/5**, 3987–4906 |
| and-reduce, `y = (x == ~0)` | 2/3/5 | 6000 | **3/5**, 826–5498 | **0/5** | **3/5**, 561–4975 |
| recursive 8-bit adder | 2/4/8 | 20000 | **0/3** | **0/3** | **0/3** |
| the same, both `SPLIT`s given | 2/4/8 | 20000 | **0/3** | **0/3** | **0/3** |

The two reduction rows are at the 2/3/5 family that the width finding below argues for. At the
2/4/8 family they read 5/5 · 3/5 · 4/5 and 5/5 · 5/5 · 5/5 — easier, and mostly with answers that
are not actually parametric.

Read it in three parts.

### Selection beats sampling, on exactly one of these problems

The one-bit full adder is the clean control result: at 4000 invocations from the same generator with
the same seeds, random sampling found it **zero times out of five** and evolution found it **five
times out of five**. Random sampling plateaus at a bit-error rate of 0.125, which is
`sum = Xor3(a, b, c)` with `c_out = And(a, b)`: correct on six of the eight truth-table rows and one
mutation short. Selection is what crosses that last step, and the figure in the demo shows the two
curves diverging.

It is worth saying plainly that this is the *only* problem here where selection helps. On the two
reductions random sampling matches evolution exactly (3/5 each) and hill climbing finds nothing at
all: it settles on a two-gate design that is 3% wrong and never leaves. On spaces that small,
sampling is a perfectly good algorithm and a greedy climber is a bad one, and a write-up that quoted
the full-adder row and not these would be cherry-picking.

### A real recursion, found from nothing

`or-reduce` is `y = (x != 0)`: an N-bit bus reduced to one bit. It is the positive structural result
because **it cannot be done without recursion in this vocabulary**. Morpho sizes a LUT cell to its
widest operand and broadcasts the narrow ones (`compiler.js:962–973`), so no gate and no combination
of gates turns eight wires into one; the only route down is to keep `SPLIT`ting. Every correct
answer is therefore genuinely divide-and-conquer.

The designs found come out at **four gates and depth 3** at five bits: `n - 1` two-input gates
arranged as a balanced tree, which is the minimum. `MorphoSearchTest` re-compiles the winner at 3,
16, 32 and 48 bits and requires it to be exact at all of them, and the demo draws it as a circuit at
4, 8 and 16.

`and-reduce` is the dual and behaves the same way, which is the point of having it: a result on one
truth table is a result about one truth table.

Choosing this target rather than parity was deliberate. Parity is the textbook deceptive function —
reducing half the bus scores exactly the 0.5 of a coin toss, so a half-built recursion is worth
nothing. Or-reducing half the bus is wrong only when the low half is zero and the high half is not,
about 6% of eight-bit vectors, so the partial answer is visibly better than nothing and there is a
gradient to climb.

### The result nobody was looking for: a power-of-two width family is not enough

`MorphoSearchMeasurements.generalisation` takes every distinct correct design a run produces and
re-compiles it at twelve widths. Selected on 2/4/8:

| width | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 12 | 16 | 32 | 48 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `or-reduce`, of 400 | 400 | 400 | 400 | **1** | 400 | **1** | 400 | **1** | 400 | 400 | 400 | 400 |
| `and-reduce`, of 400 | 400 | 400 | 400 | **0** | 400 | **0** | 400 | **0** | 400 | 400 | 400 | 400 |

One design in four hundred is genuinely parametric, and for the dual, none. The failure is not at
*large* widths, which is what a generalisation check normally looks for — 32 and 48 are fine. It is
at 5, 7 and 9.

`SPLIT` halves a bus as `floor(w/2), w - floor(w/2)` (`primitives.js:42`), so the halves are equal
exactly when the width is even. 5, 7 and 9 are the small widths where they are unequal *and* both
wider than one bit. The designs found are all some form of `Or(x0, x1)` over the two halves followed
by a recursion, so at those widths the LUT gets operands of two different widths — and
`materializeLut` (`compiler.js:962-973`) computes `w = max(...)` and broadcasts only operands of
length 1. Unequal wider operands are neither an error nor a broadcast; the pins read past the end of
the shorter bus. Silently wrong.

So the search found undefined behaviour in the compiler and exploited it, and every check that only
looked at even widths agreed the answer was right.

Selecting on 2/3/5 instead: **400 of 400 correct at every width tried**. That is one line, it is now
`MorphoProblems.ODD_FAMILY` and the default for both reduction problems, and
`MorphoSearchTest.a power of two width family selects designs that are not parametric` pins both
halves of the observation.

Two things follow.

*For the search layer:* a width family must contain a width whose `SPLIT` is unequal on both sides.
2/3/5 is the smallest such family. The adder problems still default to 2/4/8, which is a known wart
— they are unsolved either way, so nothing measured depends on it, but a future adder result would
have to be re-checked at 5.

*For the bridge:* this is worth reporting upstream. A width mismatch between LUT operands is
currently a silent miscompile rather than a diagnostic, and it is exactly the mistake a person
writing a divide-and-conquer cell would make first.

### The recursive adder is not found, and that is the finding

Nobody finds a divide-and-conquer adder. Not random sampling, not hill climbing, not a population,
not with lexicase selection over per-bit cases, not with a width curriculum, and not with both
`SPLIT`s handed over as an unremovable prologue.

The failure is not uniform, and the shape of it matters. Random sampling and hill climbing stop on
**depth-1** circuits at bit-error rates of 0.25–0.27: broadcast logic, no recursion at all.
Evolution at 20 000 invocations reaches **16 gates, depth 8** — `ripple_adder`'s exact cost profile
— and is still **17.9% wrong**. It has found the shape and not the wiring: eight levels of something
chained across the width, carrying the wrong thing along the chain. That is closer than the
control gets and it is not an adder.

Three measurements say why the rest of the way is so far, rather than leaving it as a shrug.

*The target is in the space.* The ripple skeleton, written by hand in the generator's own grammar,
is five statements, compiles, and scores exactly `ripple_adder`: 16 gates and depth 8 at eight bits,
64 gates and depth 32 at thirty-two. `MorphoSearchTest.the recursive adder is reachable in the
genome grammar` asserts this, so the negative result is about search and not about reach.

*The recursion is being tried and is not paying.* Over 4000 random programs on the guided problem,
**511 (12.8%) contain a self-call in the compiled phenotype**. The best of those scores 0.2880; the
best purely combinational candidate scores 0.3017. An eighth of the population is recursing and it
buys 1.4% of the error.

On the *unguided* problem the density drops to 2.8% and the sign flips: the best recursive candidate
scores 0.3753 against the best combinational one's 0.3017. Handing over the two `SPLIT`s is what
turns recursion from actively harmful to marginally useful, and marginally useful is not enough.
(`MorphoSearchMeasurements.recursionDensity`.)

*The reason is the shape of the answer.* A correct adder needs both halves recursed, both carries
chained in the right order, and the two sums `CAT`ed back — four statements that are worth nothing
until all four are right and wired to the outputs. Or-reduce needs two, and half of it already pays.
That is the whole difference between the two results on this page.

The negative result is asserted, not just described: `the recursive adder is not solved at this
budget` fails if some later change solves it, and its failure message says to rewrite this section.
The loop's usual failure mode is prose outliving the result it described, and a red test is a
cheaper reminder than a reader.

### What that suggests next

Nothing here is evidence that the adder is unreachable by search in general, only that it is
unreachable by *these* searches at these budgets. The obvious next moves, in order of how much they
would teach:

1. **Reward structural novelty** rather than only error. Every driver here is a pure exploiter of a
   scalar (or per-case) fitness, and the plateau is exactly the situation novelty search exists for.
2. **Co-evolve the base case with the recursion** — `MorphoProblems.adderFromScratch` is written and
   was never given a serious budget.
3. **Semantic crossover.** Whole-cell crossover has one seam and the adder has none: both parents
   are one cell, so recombination degenerates to selection.
4. **A bigger budget.** 20000 invocations is 20 seconds. Nothing was tried at 10^7.

---

## Throughput, and why it is not the bottleneck

A compiler invocation on the recursive-adder problem — parse, compile at three widths, expand fully,
extract three netlists, three timings, and simulate all three — costs about **1 ms** once Graal is
warm. Roughly 1000 candidates a second, single-threaded, because a Graal `Context` is not safe for
concurrent access and `MorphoRuntime` funnels everything through one lock.

At that rate a 20000-invocation run is 20 seconds and a search can be run live inside a box while
the document is open, which is what the demo does. Compile throughput is not what is limiting these
results.

---

## Running it

The demo transcript is `dev_docs/agents/transcripts/morpho_circuit_search.yaml`: three boxes, no
`Window` and no `Renderer`, everything through `showPlaneGrid`.

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 \
  --command 'macro write morpho_circuit_search --file dev_docs/agents/transcripts/morpho_circuit_search.yaml' \
  --command 'macro run morpho_circuit_search'
```
