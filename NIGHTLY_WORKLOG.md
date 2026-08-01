# morpho-circuit-search

Branch `nightly/2026-08-01/1-morpho-circuit-search`, based on `7abe8736`.

## Plan

### What exists (read first)

Twelve hours ago `2a698dfc` landed MorphoHDL in Field: upstream's `parser.js` / `primitives.js` /
`compiler.js` on GraalJS and their 11 KB Barnes–Hut `main.wasm` on GraalWasm, byte-identical
(`src/jvmMain/resources/morpho/PROVENANCE.md`). Field supplies `MorphoRuntime` (the Graal driver),
`MorphoNetlist` (the SoA snapshot), `MorphoSim` (bit-parallel logic simulation — 64 samples per
machine word), `MorphoTiming` (morpho's own `updateTimes()`), `MorphoCircuit`, `MorphoFLines`,
`MorphoLayout3D`, and `MorphoExecution` (morpho as a box language).

Reading that code changes the plan in one important way: **almost the entire fitness function is
already there and is honest.** `MorphoSim.evaluate` is the correctness oracle,
`MorphoNetlist.activeCount` / `lutCount` / `gateCount` and `MorphoTiming.depth` are the cost model.
Nothing needs reimplementing. What is missing is everything on the *generate* side.

### The representation decision

The genome is **MorphoHDL source text**. Not a netlist, not an SSA object we compile ourselves —
text, handed to upstream's `parse()` and `compileModule()`. That is forced by the house rule the
whole subsystem is built around: the bridge is only honest if it is *their* compiler producing the
netlist. It also means every individual the search finds can be pasted straight into a morpho box.

`parser.js` (lines 23–188) fixes the grammar exactly:

* tokens are `IDENT`, `NUM` (`0b…`/`0x…`/decimal), `@decorator`, and the operator set
  ``[()\[\],:=\-]`` — line 25;
* a top-level statement is `Name = LUT(arity, value)` — line 165;
* a cell is `@morpho` / `@morpho(fallback=X)` then `def name(args):` then SSA lines
  `t0, t1 = Op(a, b)` then `return …` — lines 134–177;
* slices `x[1:]`, `x[:-1]`, `x[0]` are `SLICE`/`INDEX` SSA — lines 119–126.

So a generator writes text in that grammar and never touches a data structure upstream owns.

### Two search problems, both parametric

**A — the base cell.** A straight-line combinational cell at fixed 1-bit width, e.g.
`full_adder(a,b,c) -> (sum, c_out)`. Search over: statement count, which LUT from a palette, which
wires feed it. Exhaustive correctness (8 vectors). Cheap, and it validates the whole pipeline.

**B — the recursion.** The interesting one, and the one that is specific to *this* language.
MorphoHDL cells are width-agnostic: a design is a claim quantified over all N. So the genome is a
recursive cell body over `SPLIT` / `CAT` / self-call / LUT application, and **fitness is correctness
at several widths at once** (2, 4, 8, 16). That satisfies the house rule about universally
quantified claims by construction rather than by afterthought: an individual that is correct at
width 4 and wrong at width 16 is not an adder and the fitness function knows it.

### Pieces

1. `MorphoSpec.kt` — a functional oracle plus a width family plus sampling policy (exhaustive when
   the input space is small, seeded-random when not). Correctness reported as *fraction of output
   bits agreeing*, not pass/fail, so hill-climbing has a gradient to climb.
2. `MorphoCost.kt` — cells / LUTs / gates / depth / max fan-out, all read off the real netlist and
   morpho's own timing.
3. `MorphoGenome.kt` — the program representation and its `render(): String`. Point mutations:
   change a LUT, rewire an argument, insert a statement, delete a statement, change an output.
4. `MorphoSearch.kt` — three drivers sharing one evaluator: `randomSearch` (**the control arm**),
   `hillClimb`, and `evolve` (μ+λ with a Pareto archive over error × gates × depth). Every run
   records a history so convergence is plottable.
5. `MorphoSearchFLines.kt` — vector figures: convergence curves and the Pareto front, as `FLine`s
   for `showPlaneGrid`. No rasters.

### What gets claimed, and how it gets checked

* Evolution beats random search at equal evaluation budget — or it does not, and that gets printed.
  The control arm runs in the same test with the same generator and the same budget.
* A found design's correctness is asserted at ≥3 widths.
* Stdlib designs (`ripple_adder`, `brent_kung_adder`) are plotted on the same Pareto axes as the
  found ones, so "the search found something good" is measured against human-designed circuits
  rather than against nothing.

### Demo artifact

An operation transcript under `dev_docs/agents/transcripts/`, replayed through the real engine with
`applied=N errors=0` observed before it is claimed.

---

(Execution notes below are appended as the night goes on.)
