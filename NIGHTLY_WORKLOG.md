# Free variables and manifolds in the Ceres expression VM

Branch `nightly/2026-08-03/1-ceres-expression-free-variables`, based on `a2dc947b7`.
Kind A. Status: **done**.

## Plan

`dev_docs/CERES.md` ended its account of the `EXPR` register-machine VM with a named gap: an
expression could read *nothing but the point parameter blocks*, and so could not state the one
reformulation that has mattered most in this subsystem. `PLANAR_HESSE` beats `PLANAR` on `kkC`
(12 converging iterations at tangency `3.2e-08` against 201 non-converging at `6.8e-03`) precisely
because it introduces a plane as a **free variable** with its normal on a `SphereManifold`, and
`PLANAR_FIT` — same objective, no auxiliary variables — fails on `kkC` exactly as `relax()` does.
Well-conditioned auxiliary variables are not overhead; they are what makes the problem tractable.
The general mechanism could not express them.

The plan was to thread free variables end to end (DAG, hash-consing, compiler, operand encoding,
native validator, ABI v3), then prove it two ways: re-derive `PLANAR_HESSE` purely as a Kotlin
expression and reproduce the recorded `kkC` result, and solve something no opcode and no point-only
expression can state, checked against closed form. It was carried out as planned, with three
decisions taken against the real code (below) and one scope addition (bounds).

## What changed

**Kotlin — `src/jvmMain/kotlin/afield/stdlib/ceres/`**

- `CeresExpression.kt` (+~460 lines): `CeresManifold` (`NONE`/`SPHERE`/`QUATERNION`),
  `CeresVariableDecl` with factories and per-component bounds, `CeresVariableRef`,
  `CeresExprVariable` (with `scalar`, `vec()`, `rotate()`), the `ExprVar` leaf and `ExprOp.VAR`,
  compiler support and validation, `disassemble()` printing the variable blocks, plus
  `hessePlaneOf` / `symmetricEigen3` / `planarHesseExpression` — `PLANAR_HESSE` as an expression.
- `CeresGeometry.kt`: `CeresFeature.AbiV3`, `CeresOpcode.Variable`,
  `CeresProgramBuilder.variable`/`unitVariable`/`rotationVariable`, the variable-aware `expression`
  overload, `CeresProgram.variables` + encoding + cross-program validation,
  `CeresVariableValue`/`CeresVariableValues`, `SolveResult.variables`.
- `FfmCeresBackend.kt`: v3 detection by symbol, `ken_ceres_solver_variables` binding, readback, and a
  compile-time cross-check of the two independently computed variable layouts.
- `ArchitecturalGeometry.kt`: `GeometryModel.withHardLock` rebuilt its `CeresProgram` from the
  constraint list alone, silently dropping the loss (and now the variable declarations). It copies.

**Native — `native/ken-ceres/src/`** (`ken_ceres.cpp` +~230, `ken_ceres.h`)

`expr::VAR` in the templated evaluator (so `ceres::Jet` still flows and derivatives stay analytic),
`op::VARIABLE` with manifolds and bounds, one `operand_span` entry, build-time validation through the
existing `ken_ceres_solver_last_error` channel, `ken_ceres_solver_variables`, ABI → `_v3`.

**Polytope — `CeresRealization.kt`**: `PlaneFormulation.HESSE_EXPRESSION`.

**Tests / examples**

- `CeresExpressionVariablesSmoke.kt` (new, ~660 lines) + `smokeCeresExpressionVariables` task.
- `CeresExpressionCost.kt`: the `PLANAR_HESSE` opcode-vs-expression cost rows.
- `CeresExpressionSmoke.kt`: gate moved to `AbiV3`.
- `DemoTranscriptExecSmokeTest.kt`: the demo registered in `EXECUTE`.

**Demo** — `dev_docs/agents/transcripts/ceres_free_variables.yaml` and its materialized document
`public/docs/assets/agent_transcripts/ceres_free_variables/`.

**Docs** — `dev_docs/CERES.md`: the falsified paragraph deleted, a new section, both cost tables, the
kk-family agreement table, and four rewritten "still open" items.

**Unrelated fix** — `public/docs/assets/agent_transcripts/threebox/`: pre-existing drift, see below.

## Decisions worth defending

1. **One `EXPR` opcode with a changed encoding, gated on ABI v3 — not a second opcode.** `EXPR`'s
   operand run now carries a variable-reference count unconditionally, so a v2 wrapper reading a v3
   stream would take that count as the residual count and desync — the silent-misparse mode the v2
   work removed. Gating it in Kotlin gives a clear refusal; two encodings kept in sync forever is the
   worse trade. **Verified**: with the committed v1 dylib, `smokeCeresExpressionVariables` refuses
   with `needs the v3 wrapper: ./gradlew buildCeresNative …` rather than misbehaving.
2. **Variables are declared at *program* scope, referenced by index from `EXPR`.** "Each face has its
   own plane" and "these fifty observations see one camera" are both wanted, and the second is not
   expressible if a variable belongs to one residual block. This is what makes the free-frame proof
   possible at all.
3. **A residual may name no points.** A fit has no point unknowns. Requiring one would have forced
   such a residual to name a point it never reads — a Jacobian column of exact zeros for a parameter
   that provably cannot affect the answer, the defect this layer refuses everywhere else.
4. **Bounds only on `CeresManifold.NONE`, and a feasible seed required.** A bound constrains an
   ambient coordinate; a manifold's `Plus` steps in the tangent space and can walk through it. An
   infeasible start is a `CHECK` failure inside Ceres, which takes the process down.
5. **The demo defaults to `tI`, not the headline `kkC`.** `kkC`'s canonical form is a stellation, so
   its face planes sit inside the spikes and the drawn normals are occluded — established by looking
   at the screenshot, not assumed. The comment says so and says how to switch.

## Verification

Everything below was **observed**, against a dylib built by `./gradlew buildCeresNative` (Homebrew
Ceres 2.2.0) and run with `-PceresLibrary=build/ken-ceres/libken_ceres_jvm.dylib`.

| | result |
|---|---|
| `buildCeresNative` | succeeds, ~10 s incremental |
| `compileKotlinJvm`, `compileTestKotlinJvm` | clean |
| `smokeCeresExpressionVariables` | PASS |
| `smokeCeresExpression` | PASS — every recorded number reproduced to the digit through the changed encoding |
| `ceresExpressionCost` | PASS, both tables |
| `smokeCeresCanonical` | PASS — "8 solids reach a genuine canonical form", numbers unchanged |
| `smokeCeresAbiV2`, `smokeCeres`, `ceresPlaneFormulationStudy` | PASS |
| `examples.demo-transcript.exec` (canonical runner) | PASS |
| `examples.agent-transcripts.materialize` (drift guard) | PASS |
| v1-dylib gating | refuses with an actionable message |

**`PLANAR_HESSE` opcode vs Kotlin expression**, same plan, same seed:

| solid | iterations | max coordinate difference |
|---|---|---|
| `kC` | 7 / 7 | 5.3e-15 |
| `kD` | 15 / 15 | 1.3e-14 |
| `tI` | 6 / 6 | 1.1e-15 |
| `kkC` | **12 / 12** | **1.6e-15** |
| `kkD` | 10 / 10 | 1.2e-15 |

The recorded `kkC` target survives exactly: 12 iterations, convergence, tangency spread `2.682e-08`.

**Closed-form fits, no point unknowns**: circumsphere centre off by `1.6e-16` and radius exactly;
total-least-squares plane normal off by `3.6e-09` from the smallest eigenvector with
`|n| - 1 = -1.1e-16` and no residual asking it to stay unit; a free rigid frame recovering its
generating rotation to `3.7e-15` and translation to `1.9e-14`. Bounds: a sphere fitted to coplanar
points (no finite unconstrained optimum) converges with the radius exactly on its ceiling.

**Cost**: ×1.3–2.1, improving with size, at identical iteration counts.

**Malformed input**: 19 Kotlin rejections and 10 native ones, each reporting its own message through
the existing error channel.

**Demo**: replayed on a live Field (port 9601) against the v3 dylib — `applied=6 errors=0`, printing
`64 free variables (32 planes)`, 6 iterations each, opcode and expression `8.9e-16` apart, every
normal unit to `6.7e-16`; and the sphere fit exact to `1.25e-16`. Both boxes screenshotted and the
images inspected. The Field JVM was stopped and port 9601 verified free.

### Not verified

- Nothing was run on any platform but this macOS/aarch64 machine, or against a non-Homebrew Ceres.
- The demo's *v3* branch was exercised on a live Field but not through an automated test; CI takes
  the v1 fallback branch (correctly — the committed dylib is v1), so the v3 branch of those two box
  bodies is compiled by `examples.demo-transcript.exec` and run only by hand.
- No full `./gradlew check`, and no docs build. Only the Ceres tasks and the two transcript
  FIELD_TESTs were run.
- `SolveResult.variables` is empty on the `solveInPlace` path by design; that path was not exercised
  with an expression program.
- The kk-family agreement was measured with the default `CeresRelaxationOptions`; other option
  combinations were not swept.

## Risks and unknowns

- **Highest risk: `CeresFeature.AbiV3` on *every* expression residual.** Any existing caller of
  `expression(...)` now needs a locally built wrapper. In-tree that is only the Ceres smokes, and it
  is deliberate and gated (see decision 1) — but it is a real behaviour change for anyone who had a
  v2 dylib lying around.
- `kkT`/`kkO`/`kkI` land in *different places* under the two formulations (not even congruent). I
  believe this is the objective's own shallow minimum, and the argument is recorded in the smoke and
  the docs: the two agree to `1e-15` on every case that converges in a dozen iterations. It is an
  argument from evidence, not a proof.
- The variable readback layout is computed twice, once per side, and cross-checked — but the check is
  skipped when a `RawProgramFragment` is present, since that can legitimately declare a variable this
  layer cannot see.
- `hessePlaneOf` uses a Jacobi eigensolver written here rather than Eigen's. It agrees to `1e-15` on
  every case tried (that is what the agreement table measures), but it is a second implementation.
- Free variables are warm-started across solves on one compiled solver, exactly as `PLANAR`'s planes
  always have been. Documented, not changed — but it means a cached solver run twice is not a
  repeat of the same solve.

## Pre-existing failure found on the way

`examples.agent-transcripts.materialize` was **already red on `main`**: commit `072093bc4` ("Make docs
video capture deterministic") edited `dev_docs/agents/transcripts/threebox.yaml` without regenerating
its committed starting point. Establishing this was a measurement, not an inference — the committed
bundle was restored (guard FAILs) and the regeneration put back (guard PASSes). The regenerated
`threebox` files are in this branch. **If this branch is rejected, that fix should be salvaged.**

## Demo artifact

**`public/docs/assets/agent_transcripts/ceres_free_variables/metadata.edn`** — open this to see it
(it also appears on the Field welcome screen as a starting point). Or replay
`dev_docs/agents/transcripts/ceres_free_variables.yaml`. Run `./gradlew buildCeresNative` first, or
the boxes will tell you to.

Two boxes. The first realizes a Conway solid in canonical form with each face's plane written in
Kotlin as a free unit normal on a sphere manifold plus a free scalar offset, and draws the solid
*together with the free variables themselves* — one orange stem per face, from the point of its plane
nearest the origin along the normal Ceres found, both endpoints derived from `n` and `d` alone. It
also solves the same plan through the C++ opcode and prints the difference. Click a face to read its
plane. The second fits a sphere to fixed data with a free centre and a free radius — a residual that
names no point at all — against the closed-form circumcentre.

## Recommendation

**Merge.** The change does what the documentation said was the most valuable extension available, and
it is proved rather than asserted: against the wrapper's own functors on the whole `kk` family
including the standing `kkC` counter-example, and against three closed forms on problems the previous
vocabulary could not state. Existing behaviour is bit-reproducible through the new encoding, the ABI
gating is intact and verified in both directions, the malformed-input surface grew rather than
shrank, and the subsystem now has the first Field document in which a person can feel a solve.

Two things a reviewer should decide rather than inherit: whether `PLANAR_HESSE` should now be
*deleted* in favour of the Kotlin expression that matches it to `1e-15` at ×1.4 (still-open item 6,
and the same question item 2 asks about `PLANAR`), and whether requiring `AbiV3` for every expression
residual is the gating they want. Both are recorded in `dev_docs/CERES.md`.
