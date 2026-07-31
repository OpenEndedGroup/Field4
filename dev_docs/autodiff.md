# Automatic differentiation in Field

Before 2026-07-31 there was no AD anywhere in this repository. Every gradient was numerical:
`finiteDifferenceGradient` in `src/jvmMain/kotlin/afield/stdlib/FunctionFit.kt` (used by
`fitGradient`) and `finiteDifferenceJacobian` in
`src/jvmMain/kotlin/afield/stdlib/LeastSquares.kt`. That is a real capability gap — "differentiable
programming" is on the standing Themes list — and this is the layer that closes it.

Five files, in dependency order:

| file | what it is |
| --- | --- |
| `afield/stdlib/autodiff.kt` | reverse mode: `ADTape`, `AD`, the op vocabulary, `grad` / `gradAt` / `valueAndGrad` / `jacobian` / `hessian` |
| `afield/stdlib/autodiff_forward.kt` | second-order forward mode: `Dual2`, `taylor2`, `secondDerivative`, `directionalDerivative`, `gradForward` |
| `afield/stdlib/autodiff_optimize.kt` | `minimize` over SGD+momentum, Adam and line-searched L-BFGS |
| `afield/stdlib/autodiff_fn.kt` | the Field bridge: differentiating the `Fn` graph, `FnAdMetric`, `exactFitObjective` |
| `afield/stdlib/autodiff_geometry.kt` | `ADVec2` / `ADVec3`, planar inverse kinematics, differentiable circle packing |

The loadable demo is `dev_docs/agents/transcripts/autodiff_worked_example.yaml` — five boxes,
replayable with `field_run_transcript` or the backdoor's `macro write` / `macro run`. Committed
captures of every box's real output are in `public/docs/assets/autodiff/`.

## Reverse mode

`AD` is a `Double` that remembers how it was made. Arithmetic on it appends to an `ADTape` — four
parallel primitive arrays holding, per node, the value plus at most two `(parent, ∂self/∂parent)`
pairs — and the backward sweep walks that array once in reverse accumulating adjoints. Nothing is
allocated per node beyond the `AD` handle, and n-ary reductions (`sum`, `dot`) decompose into binary
chains so the two-parent limit costs nothing.

```kotlin
val g = grad { p -> (p[0] - 3.0).sq() + 2.0 * (p[1] + 1.0).sq() }
g(doubleArrayOf(0.0, 0.0))                       // [-6.0, 4.0], exact

val (v, d) = valueAndDerivative(0.4) { x -> exp(-x * x) * sin(6.0 * x) }
val j = jacobian(doubleArrayOf(0.5, 1.1)) { p -> listOf(p[0] * p[1], sin(p[0]) + p[1].sq()) }
```

`grad` returns a *function*, which is what lets it be handed straight to `minimize`. There is no
scope object and no thread-local: each `AD` carries its tape, and mixing two tapes throws.

The op vocabulary is deliberately broad enough that ordinary code does not have to be rewritten:
`+ - * /` (with `Double` and `Int` on either side), `exp ln log10 log2 sqrt cbrt sin cos tan asin
acos atan sinh cosh tanh abs relu sigmoid softplus floor ceil round sign pow max min clamp atan2
hypot reciprocal sq`, and the reductions `sum mean product dot sumOfSquares norm maxOf minOf
logSumExp softmax`.

## The kink convention, and why it is not arbitrary

`abs`, `min`, `max` and `relu` are not differentiable at their kink, so a convention has to be
picked. **This library splits the derivative evenly at an exact tie**: `min(a, b)` with `a == b`
sends 0.5 to each argument, so `relu'(0) = 0.5` and, because `abs(x) = max(x, -x)`, `abs'(0) = 0`.

The reason to prefer that over PyTorch's `relu'(0) = 0` is that the even split is *exactly what a
two-point central difference reports at a kink*:

```
relu:  (relu(h) - relu(-h)) / 2h = (h - 0) / 2h = 0.5
abs:   (|h| - |-h|) / 2h         = (h - h) / 2h = 0
```

So the analytic rule and the finite-difference oracle agree **at the kink too**, which is what lets
`AutoDiffGradientCheckTest` check there at all instead of skipping it. It also matters downstream:
`packCircles` is built entirely out of `relu(...)²` hinges, and at the instant two discs touch the
even-split rule gives half the derivative a one-sided rule would, so the packing settles rather than
chattering across the contact.

## Forward mode and exact Hessians

`Dual2` is a truncated Taylor number `(v, v', v'')` carried along one direction. The op vocabulary
mirrors `autodiff.kt` name for name, so the same expression body evaluates either way — which is how
the demo plots `f`, `f'` and `f''` from a single sweep.

One `Dual2` pass seeded along `v` yields `vᵀHv` exactly. `hessian` runs it along each axis for the
diagonal and along each `eᵢ+eⱼ` for the off-diagonals by polarisation, so the Hessian is **exact**,
not a finite difference of an exact gradient. Cost is `n(n+1)/2` passes; that is the price of not
having a nestable tape.

## Optimizers

`minimize(initial, options, objective)` over three descents, all deterministic and all recording a
start-inclusive loss trajectory that plots straight into `showSignals`:

- **L-BFGS** — Nocedal two-loop recursion, γ-scaled initial inverse Hessian, backtracking Armijo line
  search, and a curvature-pair skip (`s·y ≤ 0`) rather than admitting an indefinite model. The right
  default for a smooth objective. On Rosenbrock from `(-1.2, 1.0)` it reaches `|x - 1| < 1e-6` and
  value `< 1e-12`, stopping on a tolerance rather than the iteration cap.
- **Adam** — the robust choice for a kinked, noisy or badly scaled landscape.
- **SGD + momentum** (optionally Nesterov) — kept partly because a visibly *settling* trajectory is
  sometimes the artistically wanted thing rather than the solver-shaped one.

`minimizeWithGradient` is the seam for a gradient that does not come from `AD` — a hand-derived one,
or the exact `Fn`-graph gradient below.

## The Field bridge: differentiating the `Fn` graph

`Functions.Fn` is a closure over `Double`, so it cannot be differentiated by overloading its
arithmetic. But every `Fn` the stdlib builds also carries an inspectable `FnGraphNode`, and
`FnAutoDiff` re-interprets *that structure* in `AD` arithmetic. `x` is itself tracked, which is what
makes `shift` — an x-domain transform with a possibly θ-dependent offset — compose correctly instead
of needing a special case.

θ-indices are assigned by exactly the traversal `mapGraphNumbers` uses (post-order DFS over
`node.children` in order, firing on any node carrying `numbers["value"]`), so index *i* here is the
same leaf as index *i* in `extractParameters()`.

`FnAutoDiff.differentiableOps` is a deliberate **subset**: `const param identity name over add
subtract multiply divide sin cos scaleY offsetY shift gaussian`. Not covered — and honestly reported
by `unsupportedOps` — are the piecewise oscillators (`saw`, `triangle`, `square`, `pulseTrain`),
everything table- or data-backed (`piecewise`, `timeline`, `rbf`, the interpolants), the seeded noise
family, and the clipping/folding transforms. Every name in the set is in `OpRegistry.knownOps`, and a
test asserts it.

### The honesty guard

A second interpreter's real danger is not that it crashes but that it silently computes the
derivative of a *slightly different function* from the one `eval` computes, and hands you a confident
wrong gradient. So the exact path never trusts itself. `exactFitObjective` returns **null** — not a
gradient — unless three things hold:

1. every op is in `differentiableOps`;
2. every loss sample lies inside the seed's own domain, so the loss's `evalSafe(outside = NaN)` and
   the interpreter's unguarded evaluation cannot disagree about which samples count;
3. `FnAutoDiff.worstValueDisagreement` against `Fn.eval` on that grid is within `1e-9`.

`exactFitRefusal` says which one failed, in prose.

### Opting in

```kotlin
with(Functions) {
    val target = cos(2) * sin(1.5, amplitude = 0.8, offset = 0.2)
    val seed = cos(1.9) * sin(1.3, amplitude = 1.0)

    fitGradient(target, seed, iterations = 400)                                   // finite differences
    fitGradient(target, seed, iterations = 400, gradient = FnGradientMode.EXACT)   // one sweep per step
}
```

`FnGradientMode.FINITE_DIFFERENCE` is the default and the historic path. `EXACT` throws with the
diagnostic if it cannot apply; `AUTO` falls back silently and `FnFitRun.usedExactGradient` reports
which happened. `fitBest` and `fitHybrid` forward the mode.

Because `FnMetric` takes three `DoubleArray`s and returns a `Double`, it has no derivative to
recover, so the exact path needs `FnAdMetric` — the same distances written over `AD`
(`rmse mae logCosh mse`). `FnAdMetric.of(metric)` matches by **identity** against the `FnMetric`
singletons, deliberately: `FnMetric.lp(2.0)` is numerically `rmse` but a fresh instance, and it is
refused with a message telling you to pass `adMetric` rather than being handed the gradient of
something subtly else.

`leastSquares` gained an optional `jacobian`, and `leastSquaresAD` derives an exact one from
residuals written once in `AD` arithmetic:

```kotlin
val fit = leastSquaresAD(doubleArrayOf(1.0, -1.0, 0.0)) { p ->
    xs.indices.map { i -> p[0] * exp(p[1] * xs[i]) + p[2] - ys[i] }
}
```

### Proving the default did not move

Both defaults are asserted **byte-identically** against golden raw bit patterns captured by running
exactly those two calls at base commit `f7d1601b`, before either file was touched — 41 loss values
and 4 θ entries from `fitGradient`, plus 3 parameters, the cost, the iteration count and the reason
string from `leastSquares`. See `defaultFitGradientPathIsByteIdenticalToTheHistoricOne` and
`defaultLeastSquaresPathIsByteIdenticalToTheHistoricOne` in
`src/jvmTest/kotlin/afield/stdlib/AutoDiffFnBridgeTest.kt`. They are bits, not tolerances: a
reordered operation turns them red.

Measured on `sin(1.5, amplitude = 0.8, offset = 0.2)` fitted from `sin(1.0)`, 400 Adam steps at
`lr = 0.05`: exact reaches RMSE 0.00379 in 401 loss evaluations, finite differences 0.00998 in 3601.
On the demo's harder eight-parameter `cos(2) * sin(1.5, …)` target from `cos(1.9) * sin(1.3, …)`, both
start at 0.3605 and exact ends at 0.00959 in 801 evaluations against 0.01420 in 6801.

## Geometry

`ADVec2` / `ADVec3` are immutable pairs and triples of `AD`, not a parallel numeric tower: they
convert to and from Field's own generated float-backed `Vector2f` / `Vector3f` via `.value` and
`Vec2.onTape(tape)`, and every operation is expressed in the same scalar ops as the rest of the
layer.

`solveIk` is planar inverse kinematics on relative joint angles. Forward kinematics is a cumulative
angle sum and a running position, so it differentiates cleanly; L-BFGS on the exact gradient puts the
tip on the target to under `1e-3` units in tens of iterations, and `IkChain.toFLine(angles)` hands
the pose to `showPlane` / `lines`.

`IkOptions.smoothness` defaults to `0.0`. It was `0.02` while the tests were being written, and the
default changed because the curvature penalty and the reach term **genuinely compete** — it is a
trade, not a free regulariser. Measured on the four-link chain in the demo at `smoothness = 0.02`: a
target 216 units out still reaches to under `1e-3`, but a target 5 units from the anchor settles 2.6
units short, because reaching there needs exactly the tight folds the penalty forbids.
`IkSolution.reachError` always reports the truth.

`packCircles` descends `Σᵢ<ⱼ relu(rᵢ+rⱼ - dᵢⱼ)² + wall·Σ relu(penetration)² + pull·Σ|cᵢ - centre|²`.
On the demo's twelve discs the worst pairwise overlap falls from 48.3 units to 0.354. That residual is
not solver failure: the centre pull is a competing term and 0.354 is where it balances the overlap
hinge. Set `pullWeight = 0.0` and it goes to zero, along with the compactness.

## Verification

| test | what it pins |
| --- | --- |
| `AutoDiffGradientCheckTest` | 28 unary ops, 9 binary ops (both partials), 8 reductions, mixed `Double`/`Int` operator forms, deep compositions, `jacobian`, `hessian`, forward-vs-reverse agreement — every one against a finite-difference oracle at **≥ 3 distinct inputs**. A fourth-order five-point stencil away from kinks, a two-point central difference at them. `everyDeclaredOpIsCovered` fails if an op is added without an oracle check. |
| `AutoDiffOptimizeTest` | problems with known exact minima, so "converged" means arrived at the right answer; determinism; seeded jitter; Armijo's monotone-descent guarantee |
| `AutoDiffFnBridgeTest` | the byte-identical goldens; the interpreter reproducing `Fn.eval` to `< 1e-12` on 10 graph shapes; the exact θ-gradient against a central difference at 3 θ points per graph, for both a raw sum objective and the real RMSE loss; every refusal path; `leastSquaresAD` |
| `AutoDiffGeometryTest` | 16 `ADVec2` and 13 `ADVec3` expressions differentiated in every component against an oracle at 4/3 points; the IK Jacobian against an oracle; IK reaching 4 targets; the smoothness trade asserted in *both* directions; packing convergence and reproducibility |
| `examples.demo-transcript.exec` | the loadable demo really runs, all five boxes, no diagnostics |

The negative control is worth recording: perturbing `tanh`'s derivative by one part in `1e4` turns
three of `AutoDiffGradientCheckTest`'s tests red, so the tolerances bite.

## What is *not* covered

- **Third and higher derivatives.** `hessian` is exact; a third order would need a `Dual3` or a
  nestable tape and neither is here.
- **Mutation.** `AD` is immutable. Rebinding a `var` is fine and does the right thing; there is no
  in-place accumulation into a tracked buffer.
- **Control flow** is differentiated *as taken*. `if (x > 0.0)` records only the branch that ran,
  which is the correct derivative of the piecewise function everywhere except at the switch-over.
  Comparisons on `AD` return plain `Boolean` and carry no derivative.
- **Piecewise-constant ops** (`floor`, `round`, `sign`, `quantize`) have derivative 0 by definition,
  and at their jumps no finite-difference oracle exists to check against.
- **Tape memory.** The tape grows with the number of *operations executed*, not with `n`. A loss over
  100k samples builds a 100k-node tape per evaluation. There is no checkpointing or chunking.
- **Most of the `Fn` op vocabulary.** Fourteen ops of a much larger registry; see the subset above.
  Anything else falls back to finite differences.
- **GPU.** Everything here is CPU `Double`. Nothing is differentiated through a shader.
