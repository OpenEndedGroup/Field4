# A Solid you can differentiate — a document you can open

Open `metadata.edn` in Field (**File ▸ Open**, or `./field.sh <path to this folder>/metadata.edn`)
and run the three boxes. No Manifold, no GPU, no window: marching cubes and the mesh rasteriser are
both pure Kotlin, so everything here runs on any machine that can run Field.

`afield.solidsl` writes a 3D solid down as a **value**, and had two readings of it —
`toScalarField()` (a distance field, marchable) and `exactSolid()` (Manifold's exact CSG). Both are
*evaluations*. Neither changes what you can ask the notation for. This document is the third
reading, the one `afield.rectsl.RectSLFn` argued for in 2D: **a shape you can take the derivative
of is a shape you can solve.**

| box | what it does | what to notice |
| --- | --- | --- |
| **1 — a solid, and its exact gradient** | `numericSlots()`, `parameterize { … }`, `gradientAt(…)`, then `checkGradient` against central differences *on the spot* | `segments` is **absent** from the slot list — an integer facet count has no derivative, so it is not offerable. And the last probe is the ball's own centre: the **parameter** gradient there is exact (`3/3 usable`), the **position** gradient is `NaN` and the check reports `0/3 usable` rather than pretending |
| **2 — inverse design** | a target sampled from a known solid, three named parameters seeded 42%, 45% and 112% wrong, and a gradient descent | the loss falls twenty orders of magnitude in fourteen iterations; the three parameters come back to ~1e-10; the **seed and fitted renders are in the same box**, so the counterfactual is not a remembered number; and the fitted render is pixel-identical to the target's |
| **3 — what refuses, and the one that does not** | the seven parameterisation refusals, then a parameter whose gradient is exactly `0.0` | the refusals carry *reasons*, not `null`s. Then the failure mode of inverse design: a swallowed child under a hard `Union` is **dead** — a pointwise `min` gives the whole derivative to whichever child is nearest — and it presents as a *converged* optimisation. Under `smoothUnion` it is alive |

Box 3 is the one that pays for the whole idea. The two nodes the *exact* lowering refuses outright
(`SmoothUnion`, `SmoothDifference`) are the two this reading is **best** at, because a smooth blend
is C¹ by construction and a hard boolean is not. If a shape is going to be fitted, those are the
operators to write it with.

`build.yaml` beside this file is the `OperationTranscript` that built the document. Replay it into a
fresh Field with:

```bash
uv run --project . --group backdoor python tools/field_backdoor.py --json --scratch \
  --command 'macro write solidsl_inverse --file dev_docs/agents/demos/solidsl_inverse/build.yaml' \
  --command 'macro run solidsl_inverse'
```

The loose `*_code_.kt` files are what Field loads; `metadata.edn` carries portable inline copies of
the same text, and `./gradlew checkAgentDemos` fails if the two ever disagree.

The measurements behind the claims are in `src/jvmTest/kotlin/afield/solidsl/SolidSLGradientTest.kt`
— the gradient against central differences over a census of every construct in the algebra, and
against `toScalarField`, which shares no code with it — and in `SolidSLInverseRenderProofTest.kt`,
which is the same fit in pixels. The written-up version is
[`public/docs/solid_notation.md`](../../../../public/docs/solid_notation.md).
