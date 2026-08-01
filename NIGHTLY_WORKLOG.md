# morpho-circuit-search

Branch `nightly/2026-08-01/1-morpho-circuit-search`, based on `7abe8736`. Kind A.

## Plan

### What exists (read first)

Twelve hours ago `2a698dfc` landed MorphoHDL in Field: upstream's `parser.js` / `primitives.js` /
`compiler.js` on GraalJS and their 11 KB Barnes–Hut `main.wasm` on GraalWasm, byte-identical
(`src/jvmMain/resources/morpho/PROVENANCE.md`). Field supplies `MorphoRuntime` (the Graal driver),
`MorphoNetlist` (the SoA snapshot), `MorphoSim` (bit-parallel logic simulation — 64 samples per
machine word), `MorphoTiming` (morpho's own `updateTimes()`), `MorphoCircuit`, `MorphoFLines`,
`MorphoLayout3D`, and `MorphoExecution` (morpho as a box language).

Reading that code changed the plan in one important way: **almost the entire fitness function was
already there and was already honest.** `MorphoSim.evaluate` is a correctness oracle;
`MorphoNetlist.activeCount` / `lutCount` / `gateCount` and `MorphoTiming.depth` are a cost model.
Nothing needed reimplementing. What was missing was everything on the *generate* side.

### The representation decision

The genome is **MorphoHDL source text**, handed to upstream's `parse()` and `compileModule()` for
every fitness evaluation. Forced by the rule the subsystem is built around — the bridge is only
honest if it is their compiler producing the netlist — and it means anything found is a program a
person can paste into a morpho box.

`parser.js:23-188` fixes the grammar exactly: `Name = LUT(arity, value)` at top level; `@morpho` or
`@morpho(fallback=X)` then `def name(a, b, c):`; SSA lines `t0, t1 = OP(x, y)`; `return x, y`. A
generator writes that and never touches a data structure upstream owns.

### Pieces

1. `MorphoSpec.kt` — oracle + width family + sampling policy, cost model, evaluator.
2. `MorphoGenome.kt` — the program representation, the LUT palette, and a model of morpho's width
   inference.
3. `MorphoGenerator.kt` — sketches, random programs, mutation, crossover, repair.
4. `MorphoSearch.kt` — `random` (**the control arm**), `hillClimb`, `evolve`, `staged`.
5. `MorphoSearchFLines.kt` — convergence and Pareto figures as `FLine`s for `showPlaneGrid`.

### What was planned that changed

The plan said "search the parameter space of `tiny_morpho.py`, then go structural". On reading
`compiler.js` the parameter space turned out to be uninteresting — the standard library's cells take
no parameters beyond the signature, and `grow=`/`relax=` are Field's own animation knobs, not part
of the language. So the whole night went to the structural version, which is what the plan's own
step 2 wanted anyway.

The plan also assumed the headline would be a Pareto front over correct adders. It is not, because
no correct adder was found. The Pareto figure ships and is drawn from real data, and the real data
is two hand-written designs and nothing else — which is the honest picture and is in the demo.

---

## What was done

Six commits. `git log --oneline main..HEAD` reads as one pass each: the layer, the figures and
tests, the documentation, and the width-family finding.

### Files added

| file | lines | what |
| --- | --- | --- |
| `src/jvmMain/kotlin/afield/morpho/MorphoSpec.kt` | ~430 | `MorphoPortRole`, `MorphoSpec`, `MorphoCost`, `MorphoFitness`, `MorphoEvaluator` |
| `src/jvmMain/kotlin/afield/morpho/MorphoGenome.kt` | ~330 | `MorphoLut`, `MorphoPalette`, `MorphoStatement`, `MorphoCellGenome`, `MorphoGenome`, `MorphoWidthModel` |
| `src/jvmMain/kotlin/afield/morpho/MorphoGenerator.kt` | ~470 | `MorphoCellSketch`, `MorphoProgramGenerator` |
| `src/jvmMain/kotlin/afield/morpho/MorphoSearch.kt` | ~400 | `MorphoIndividual`, `MorphoSearchRun`, `MorphoSelection`, `MorphoSearch` |
| `src/jvmMain/kotlin/afield/morpho/MorphoProblems.kt` | ~300 | `MorphoProblem` and seven named problems |
| `src/jvmMain/kotlin/afield/morpho/MorphoSearchFLines.kt` | ~240 | the two vector figures |
| `src/jvmTest/kotlin/afield/morpho/MorphoSearchTest.kt` | ~400 | 15 tests |
| `src/jvmTest/kotlin/afield/morpho/MorphoSearchMeasurements.kt` | ~200 | 3 measurement runs |
| `dev_docs/morpho_circuit_search.md` | ~230 | design notes, results, what to try next |
| `dev_docs/agents/transcripts/morpho_circuit_search.yaml` | ~200 | the loadable demo |

### Files changed

* `src/jvmMain/kotlin/afield/morpho/MorphoGraal.kt` — added `MorphoRuntime.interruptGuest`, so the
  evaluator can interrupt the shared Graal context from a watchdog thread. A generated recursion
  that the width model wrongly believed terminates then costs one timeout rather than the process.
* `public/docs/morpho_examples.md` — a new section, "Searching for circuits nobody wrote", between
  "Using it" and the cost-as-a-template table. No new assets and no `diffstatic_runner` fences, so
  no docs build and no recapture.

### Why the design ended up as it did

Four things were learned by being wrong first, and each is commented at the place it bit:

1. **Termination has to be proved statically, over the signature graph.** A local "the arguments are
   narrower" rule is insufficient, because the body computes different widths at every level. The
   first version emitted `evolved(ONE, t0, ONE)` with `t0` as wide as `b` and recursed at a fixed
   signature forever. `MorphoWidthModel.terminates` now treats a signature revisited on the current
   path as a cycle.
2. **Introns are load-bearing.** Pruning the genome made an empty cell an absorbing state and hill
   climbing stopped dead. Genotype keeps dead statements; only `render()` prunes.
3. **Mutation must be able to change a LUT's arity**, or `And` → `Maj3` — the last step of a full
   adder — is not a single move.
4. **Budget is compiler invocations, not proposals.** Charging per proposal gives the control arm
   three times the real work.

---

## Results

Five seeds per cell, three for the 20 000-invocation rows. Source:
`./gradlew jvmTest --tests "afield.morpho.MorphoSearchMeasurements" --rerun -i`.

| problem | budget | random (control) | hill climb | evolution |
| --- | --- | --- | --- | --- |
| bitwise xor (harness canary) | 2 000 | 5/5 | 5/5 | 5/5 |
| one-bit full adder | 4 000 | **0/5** | 4/5 | **5/5** |
| or-reduce | 6 000 | see note | see note | see note |
| and-reduce | 6 000 | see note | see note | see note |
| recursive 8-bit adder | 20 000 | 0/3 | 0/3 | 0/3 |
| the same, both `SPLIT`s given | 20 000 | 0/3 | 0/3 | 0/3 |

(The or-reduce / and-reduce rows were re-measured after the default width family changed from 2/4/8
to 2/3/5 — see the finding below. At 2/4/8 they were 5/5 · 3/5 · 4/5 and 5/5 · 5/5 · 5/5. The
re-measured numbers are in `public/docs/morpho_examples.md` and `dev_docs/morpho_circuit_search.md`;
if those two documents and this table ever disagree, the documents are the ones kept current.)

**Three findings, in ascending order of how much they were expected.**

**1. Selection beats sampling, on exactly one problem.** The one-bit full adder: at 4 000
invocations from the same generator with the same seeds, random sampling found it 0/5 and evolution
5/5. Random plateaus at a bit-error rate of 0.125 — `sum = Xor3(a, b, c)`, `c_out = And(a, b)`, one
gate swap short. It is also the *only* row where selection helps: on or-reduce at 2/4/8 the control
was the most reliable driver of the three. Both are said in the documentation.

**2. A genuine recursion is found from nothing.** `or-reduce` (`y = x != 0`) cannot be done without
recursion — morpho broadcasts a LUT across a bus (`compiler.js:961-972`), so no combination of gates
narrows one, and the only route from N wires to one is to keep `SPLIT`ting. The designs found are
7 gates and depth 3 at eight bits, a balanced tree, and the same source text is verified at widths
nothing was selected on. `and-reduce` is the dual and behaves the same.

**3. The recursive adder is not found — and the near miss is specific.** Not by any driver, not with
lexicase selection over per-bit cases, not with a width curriculum, not with both `SPLIT`s given.
Random sampling and hill climbing stop at depth 1; evolution at 20 000 invocations reaches 16 gates
and depth 8, which is `ripple_adder`'s exact cost profile, and is still 17.9% wrong — the shape
without the wiring. Three measurements say why rather than shrugging: the target is provably in the
space (the hand-written skeleton scores exactly `ripple_adder`, asserted); 12.8% of random programs
on the guided problem already contain a self-call and the best of them beats the best combinational
candidate by 0.014; and a correct adder needs four statements that pay nothing until all four are
right, where or-reduce needs two and half of it already pays.

**4. The one nobody was looking for.** Selecting `or-reduce` on widths 2/4/8 produces 400 distinct
correct designs, of which **399 are wrong at widths 5, 7 and 9** — and all 400 are right at 3, 6,
12, 16, 32 and 48. For `and-reduce`, 400 of 400 are wrong at 5/7/9. `SPLIT` gives equal halves
exactly when the width is even; at 5, 7, 9 the halves are unequal and both wider than one bit, so a
LUT over both halves gets mismatched operands — and `materializeLut` takes the maximum width and
broadcasts only operands of length 1, so unequal wider operands are **neither rejected nor
broadcast**. The pins read past the end of the shorter bus and the circuit is silently wrong. The
search found undefined behaviour in the compiler and exploited it, invisibly to any even-width
check. Changing the family to 2/3/5 gives 400 of 400 correct everywhere; that is now
`MorphoProblems.ODD_FAMILY` and the default for both reduction problems.

This last one is worth reporting upstream: a width mismatch between LUT operands is currently a
silent miscompile, and it is the first mistake a person writing a divide-and-conquer cell would make.

---

## Verification

**Verified, observed directly:**

* `./gradlew jvmTest --tests "*Morpho*"` — **39 tests, 0 failures**: `MorphoSearchTest` 15 (new),
  `MorphoBridgeTest` 11, `MorphoLanguageTest` 9, `MorphoRegistrationTest` 4, plus the two
  measurement classes. Zero Kotlin warnings from the new files.
* `./gradlew compileKotlinJvm --rerun-tasks` — clean.
* **The demo transcript was replayed into a real Field through the backdoor and reported
  `applied=6 errors=0 ok=True`**, twice (once plain, once with Playwright attached), on a
  backdoor-owned server on port 9481. All three boxes executed; their printed output is in the
  run result.
* **The figures were taken to the pixels**: `screenshot-box` on all three boxes, written to
  `build/morpho-search-evidence/{control,recursion,adder}.png` and looked at. The convergence
  panel draws four best-so-far staircases with solve ticks; the cost panel draws exactly two cyan
  reference points (`ripple_adder` at depth 8 / 16 gates, `brent_kung_adder` at depth 7 / 46 gates)
  and no orange ones, which is the negative result as a picture; the recursion box draws the evolved
  or-reduce cell as three real `MorphoFLines` circuits at 4, 8 and 16 bits. Everything is vector —
  `FLine`s into `showPlaneGrid`, no raster anywhere, and `rasterizeFLinesFramed` is not referenced.
* Throughput measured, not guessed: ~1 ms per compiler invocation on the three-width adder problem,
  0.39 ms on or-reduce.

**Not verified:**

* **No docs build was run.** Deliberate — the disk was at 7–8 GiB free all night and a whole-site
  build is expensive. Every fence added to `public/docs/morpho_examples.md` is plain `diffstatic`,
  never `diffstatic_runner`, and no new asset was added, so nothing on that page needs recapturing.
  Somebody should still run the docs build once before this is considered finished.
* **The transcript is not materialized into the starting-point bundle.**
  `AgentTranscriptMaterializer` non-recursively scans `dev_docs/agents/transcripts/*.yaml` and its
  drift guard fails the build for a top-level yaml with no committed bundle under
  `public/docs/assets/agent_transcripts/`. **This will fail CI until somebody runs the materializer**
  (see the exact command below). It was left undone because the materializer wants a Field JVM and
  the night's budget ran out; it is a two-minute job.
* `MorphoSearch.staged` (the width curriculum) is implemented and exercised only by hand during
  development; it has no test and appears in no measurement.
* `MorphoProblems.adderFromScratch` (recursion and base case co-evolved) is implemented and was
  never given a serious budget.
* The adder problems still default to a 2/4/8 width family, which finding 4 says is the wrong shape.
  Nothing measured depends on it — they are unsolved at every family tried — but a future adder
  result must be re-checked at width 5.

### The one thing to do before merging

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --port 9477 \
  --command 'run-jvm-main afield.examples.AgentTranscriptMaterializer --arg '"$PWD"'/public/docs/assets/agent_transcripts'
```

then commit the resulting `public/docs/assets/agent_transcripts/morpho_circuit_search/`.

---

## Risks and unknowns

* **The materializer drift guard** (above) is a known, unfixed build break. Highest-priority item.
* **`MorphoWidthModel` is a model.** It mirrors `primitives.js` and `compiler.js`'s broadcast rule,
  but the self-call output shape is Field's assumption, not a transcription. When it is wrong the
  candidate simply fails, and the failure rate is reported (0.0–0.7% across every run measured), but
  it is the one place in the layer that could quietly disagree with upstream.
* **`interruptGuest` touches the shared Graal context.** It is called only from the evaluator's
  watchdog and only on timeout, and Graal documents a context as usable after an interrupt — but the
  same context serves every morpho box in a document, so a spurious interrupt would surface as an
  unrelated box failing. Not observed; no test forces it.
* **Search results are seeded but stochastic.** `MorphoSearchTest` asserts on specific seeds with
  thresholds set well inside the measured spread, and the whole test class takes about 45 s. If a
  Kotlin or Graal upgrade perturbs iteration order somewhere, the solved/unsolved assertions are the
  ones that would go red first, and they would be genuinely informative rather than flaky.
* **`the recursive adder is not solved at this budget` is asserted as a negative.** That is on
  purpose — the failure message says to rewrite the documentation — but it does mean a genuine
  improvement to the search will show up as a red test.
* Nothing here is evidence that a recursive adder is beyond search in general, only that it is
  beyond *these* searches at these budgets (20 000 invocations is 20 seconds).

## Recommendation

**Merge**, after running the materializer.

The layer is self-contained (one new package-mates group inside `afield.morpho`, one small addition
to `MorphoGraal.kt`, nothing else in the tree touched), it is tested, it is documented at two levels,
and it ships a demo that was actually replayed. The two results worth having are a clean control-arm
positive (selection finds a full adder where sampling does not) and a clean, specific negative (the
recursive adder is not found, the target is provably reachable, and here is the arithmetic of why).

The width-family finding is the part that will outlive the rest. It is a real bug in upstream's
compiler, found by a search that exploited it, and caught only because the fitness function scores a
design at more than one width. It is worth an issue on the morpho repository.

If it is *not* merged, the piece to keep is `MorphoSpec` + `MorphoEvaluator`: a width-family
correctness oracle over the real toolchain is useful with or without a search on top of it.
