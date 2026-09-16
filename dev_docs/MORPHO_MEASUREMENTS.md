# MorphoHDL: the size and timing census

The numbers `morpho_examples.md` quotes about how big a circuit is and how long it takes to compile,
grow and relax, with the run they came from.

Source: `src/jvmTest/kotlin/afield/morpho/MorphoMeasurements.kt`. **Nothing here is asserted.** That
class prints and returns; the gate counts have a guard elsewhere (see `dev_docs/MORPHO_AGREEMENT.md`)
but the cell, net, expansion and timing columns do not, and the timings are one machine's.

Regenerate with:

```bash
./gradlew jvmMeasurements --tests "afield.morpho.MorphoMeasurements" --rerun -i
```

`jvmMeasurements`, not `jvmTest`: `build.gradle.kts` registers it precisely to hold `**/*Measurements`
out of the deterministic check task, so `jvmTest --tests "...MorphoMeasurements"` matches nothing.

## The census

| design | cells | gates | nets | depth | expansions | compile+grow | 200 relax steps | z / xy |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `ripple_adder(8,8,1)` | 42 | 16 | 82 | 8 | 36 | 1563 ms\* | 588 ms | 0.76 |
| `ripple_adder(32,32,1)` | 162 | 64 | 386 | 32 | 156 | 142 ms | 1225 ms | 0.92 |
| `brent_kung_adder(32,32,1)` | 288 | 190 | 608 | 11 | 157 | 46 ms | 634 ms | 1.09 |
| `wallace_multiplier(16,16)` | 936 | 872 | 3711 | 17 | 886 | 175 ms | 161 ms | 1.33 |
| `wallace_multiplier(16,16)` unopt | 1150 | 1086 | 3711 | 18 | 1014 | 61 ms | 252 ms | 1.06 |
| `right_shifter(32,5,1)` | 230 | 160 | 392 | 5 | 227 | 15 ms | 17 ms | 0.73 |
| `triangle(32)` | 1025 | 992 | 1058 | 62 | 993 | 34 ms | 946 ms | 1.10 |
| `medusa(36,8)` | 4252 | 4144 | 16726 | 63 | 5491 | 269 ms | 1557 ms | 0.91 |

\* The first row is Graal warming up: it includes JIT-compiling morpho's compiler. Every later row
is the steady state, which is the number that matters for live coding.

The `z / xy` column is z extent over the larger of the x and y extents — `MorphoLayout.flatness()`.
It is on the page because the shipped upstream driver pins z to zero and Field's does not; a value
near 1 means the layout genuinely occupies three dimensions.

## The line-count census

Displaced from `public/docs/morpho_examples.md`'s `### What it cost` on 2026-09-15. The page keeps
the conclusion drawn from these — registration proper is sixty lines and those sixty were a copy,
so what a foreign language costs a host is the data boundary, the decision about what running the
language means, and reading the other implementation carefully enough not to disagree with it by
accident. Line counts include KDoc, which in this codebase is a substantial fraction of every file.

The plumbing, step by step, against the six steps the page walks through:

| step | file | lines |
| --- | --- | --- |
| 1. host the foreign runtime | `MorphoGraal.kt` | 273 |
| 1. guest-side drivers | `morpho-field-driver.mjs`, `morpho-field-layout.mjs` | 446 |
| 2. the netlist crossing the boundary | `MorphoNetlist.kt` | 171 |
| 3–4. registration, and what a box does | `MorphoInterface.kt` | 426 |
| 5. editor grammar | `morpho.js` | 105 |
| 6. tests | `MorphoBridgeTest`, `MorphoLanguageTest`, `MorphoRegistrationTest` | 628 |
| 6. measurement run | `MorphoMeasurements.kt` | 78 |

And what morpho left to its host, which is this language's bill rather than the template's:

| what Field supplies | file | lines |
| --- | --- | --- |
| logic simulation | `MorphoSim.kt` | 192 |
| 3D force layout | `MorphoLayout3D.kt` | 111 |
| grow-and-draw handle | `MorphoCircuit.kt` | 121 |
| drawing | `MorphoFLines.kt` | 317 |

Of 2,868 lines, registration proper is sixty, the boundary in steps 1 and 2 is 890, and checking it
in step 6 is 706. The search layer is a separate ~2,400 lines on top of all of this, and needed
nothing from the bridge that was not already there.

## Simulation throughput

All 131 072 vectors of the exhaustive 8-bit adder check evaluate in **17.8 ms**, which is 2 048
passes of 64 samples. Each net holds a `Long` whose bit *s* is that net's value for sample *s*, so
the sample axis *is* the machine word and a LUT evaluation is a few `and`/`or`/`inv`. This mirrors
what `tiny_morpho.py`'s `CircuitRunner` does with numpy, using a register instead of an array.

`MorphoSim.LANES` is the divisor; the printed line reports passes and lanes rather than hard-coding
64.

## Depth scaling

Printed by the same class as `MEASURE-DEPTH`, and reproduced on the page because it is the reason
both adder families exist.

| width | `ripple_adder` | `brent_kung_adder` |
| --- | --- | --- |
| 4 | 4 | 5 |
| 8 | 8 | 7 |
| 16 | 16 | 9 |
| 32 | 32 | 11 |
| 64 | 64 | 13 |
