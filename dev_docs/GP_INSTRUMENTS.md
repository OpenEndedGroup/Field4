# GP instruments: Bayesian optimisation and preference learning

Two classes on top of `afield.math.gp` (so imported into every box), added on the nightly branch
`nightly/2026-10-10/1-gp-instruments`. User page: `public/docs/gaussian_process_optimization.md`.
Demo: `dev_docs/agents/transcripts/gp_instruments.yaml` (saved document
`dev_docs/agents/demos/gp_instruments/`). Tests: `src/jvmTest/kotlin/afield/math/gp/GpOptimizerTest.kt`.

## `GpOptimizer` (`GpOptimizer.kt`)

- Named bounded parameters (`GpRange`); internally the unit cube. Outputs standardised (mean/sd of
  everything observed) on every refit.
- Surrogate: `GaussianProcess.fit` with `GpKernels.matern(2.5, perAxisLengthScales)` and a scalar
  noise std, both fitted by the existing `fitGpHyperparameters` pattern search (bounds: length scale
  0.03..3 unit-cube units, noise 1e-3..0.7 standardised), warm-started from the previous fit.
  `refitEvery` skips refits for long runs.
- Initial design: a seeded jittered Latin hypercube of `initialPoints` (default max(4, d + 2)).
- Acquisition: `ExpectedImprovement(xi)` (default), `UpperConfidenceBound(beta)`,
  `ProbabilityOfImprovement(xi)`, in standardised units.
- Acquisition maximisation: `candidates` uniform random points plus 8 Gaussian jitters (sd 0.05) of
  each of the 4 best observations; the top `refineStarts` refined by projected gradient ascent with
  an adaptive step. **The gradient is a central difference**, not analytic: EI needs dσ/dx and the
  library exposes only the mean gradient (`GaussianProcess.gradient`). Adding a posterior-variance
  gradient to `GaussianProcess` would let this go analytic; it is the obvious next step.
- Batch (`suggest(count)`): kriging believer — fantasy observation at the posterior mean with the
  hyperparameters held, so later members move away. Duplicates of an observation are replaced by
  the next-best candidate.
- Determinism: one `gpRandom(seed)` stream; same seed + same observations = same suggestions
  (pinned by `isDeterministicForASeed`).

## `GpPreference` (`GpPreference.kt`)

The full probit preference GP of Chu & Ghahramani (2005, "Preference learning with Gaussian
processes"), not the pairwise-to-score shortcut.

- Latent utility f at the distinct compared points, prior RBF(`lengthScale`) with variance 1 on the
  unit cube; likelihood Π Φ((f_w − f_l)/(√2 σ)), σ = `noise`.
- Laplace: Newton on Ψ(f) = Σ log Φ(z) − ½ fᵀK⁻¹f, update f ← K (I + W K)⁻¹ (W f + ∇log L), with
  step halving if Ψ does not increase. W is the (pairwise, singular) negative Hessian of the log
  likelihood. Predictive: mean k*ᵀ K⁻¹ f̂, variance k** − k*ᵀ W (I + K W)⁻¹ k*, which never inverts W.
  Dense LU with partial pivoting for the non-symmetric I + WK; `gpFactorTraining` Cholesky for K⁻¹.
- `log Φ` and φ/Φ have asymptotic branches below z = −8 (contradictory answers with small σ push
  z there); continuity at the switch is tested.
- `nextPair()`: the favourite (max posterior mean among shown points) against the argmax of
  mean + `exploration` · std over `candidates` random points and 32 jitters of the favourite.
- `lengthScale`/`noise` are fixed — with tens of binary answers the Laplace evidence hardly
  constrains them. An evidence-based choice is future work.
- Cost: O(n³) per answer in the number of distinct shown points; fine to a few hundred.

## Box wiring

The demo's box 2 uses `controlGroup { button(...) }` callbacks that call `choose`, `nextPair` and
redraw with `lines(...)`. No new UI code was needed. Box state lives in plain `var`s (callbacks run
one at a time), and is lost on re-run, which restarts the questioning — deliberate for a demo.
