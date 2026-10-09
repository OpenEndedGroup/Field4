# Gaussian process implementation and validation

The public tutorial is `public/docs/gaussian_processes.md`, dedicated to David J. C. MacKay.

## Model and ownership

The library is package `afield.math.gp` (`src/jvmMain/kotlin/afield/math/gp/`), imported into
every box by `intro` in `Boxes.kt`. The FLine adapters stay in `afield.graphics`.

- `GpKernel.kt`: `GpKernel`, `GpDifferentiableKernel` (∂k/∂b and ∂²k/∂a∂b), and `GpKernels`.
  Radial kernels (RBF, Matérn 3/2 and 5/2, rational quadratic) share one implementation in terms
  of g(s), g′(s), g″(s) with s the scaled squared distance; periodic, linear and constant have
  their own. Sums, products and scalings stay differentiable whatever their static type
  (`GpKernel.plus` checks at runtime). Matérn 1/2, `warp` and Smile kernels are not differentiable.
  `periodicMixture` / `octavePeriodic` put uncertainty on the period.
- `GaussianProcess.kt`: exact scalar regression on arbitrary-dimensional inputs. Training data
  and queries are linear functionals (`GpFunctional`: a value, or a partial derivative along an
  axis), so slopes can be observed (`GpDerivativeObservation`) and queried jointly with values
  (`derivative`, `gradient`, `derivativePosterior`, `posterior(List<GpFunctional>)`). A mean
  function's slope is a central difference. Priors, heteroscedastic noise, latent variance, full
  joint covariance, evidence, conditioning and leave-one-out as before.
- `GpJoint.kt`: the finite joint Gaussian, seeded coherent draws (`sample(seed, spread)` scales
  the departure from the mean), linear transforms, and the shared numerical kernels (training
  Cholesky with reported jitter, pivoted PSD factor, seed avalanche).
- `GpVectorProcess.kt`: separable multi-output covariance by lifting each (input, coordinate)
  to a scalar observation. The lifted kernel is differentiable along input axes when the base
  kernel is, which gives curve velocities and velocity observations.
- `GpPaths.kt`: exact conditional sample-path extension, and explicitly approximate paired
  Fourier-feature RBF models with Matheron-rule conditional function draws.
- `GpFit.kt`: portable bounded log-space pattern search, and `scanGpEvidence` (log evidence and
  grid posterior weights for one parameter, e.g. a period).
- `GpSmile.kt`: existing Smile kernels and bounded L-BFGS-B, evaluating Field's likelihood with
  bounded finite differences. Smile 5.0.1 is already a dependency; none was added.
- `GpFields.kt`: Vec2/Vec3 observation and prediction conveniences.
- `afield/graphics/FLineGaussianProcess.kt`: Vec3 curve observations (optionally with
  velocities), normalized arc-length FLine fitting, coherent sampled FLines, coordinate
  variances, covariance blocks, planar normal bands, and the curve geometry: `velocity`,
  `velocities`, `frames` (tangent, normal, binormal, speed and delta-method tangent-angle
  uncertainty) and `curvature` (Monte Carlo over joint velocity draws, differenced a small step
  either side; reports the curvature of the mean separately from the mean curvature of draws).

### Where it came from

PR #98 (`codex/gaussian-processes-mackay`) supplied the numerical core, paths, vector process,
fitting, Smile bridge, FLine adapters, tutorial and figures. A private, never-landed EJML GP in Marc's
local layer (package `gp`) supplied the ideas added on integration: analytic
derivative posteriors for RBF/periodic/linear kernels and their sums, curvature and Frenet
frames with uncertainty, delta-method tangent variance, period mixtures over an octave, evidence
over candidate periods, and scaled ("jittered") draws. Those were rewritten onto the PR's core
rather than ported: the personal code's mutable log-parameter kernels, Adam optimizer and
per-dimension diagonal variances were superseded by the PR's immutable kernels, bounded fitting
and full covariance. The derivative work is now general (any dimension, any differentiable
composition, products included, which the personal code did not support), and derivatives can
be *observed*, not only predicted.

Training uses Cholesky solves, not an explicit inverse. Numerical jitter is attempted only after
an unmodified factorization fails and is bounded/reported. The sampler uses a pivoted PSD factor
and exposes its residual variance; it does not add independent query noise. Exact duplicate
observations are checked, repeated query positions remain correlated, and all-noiseless singular
fits are checked against the numerically resolved support before accepting a jitter repair.

Arrays owned by models and cached factors are copied/defensively returned. Custom mean and kernel
closures must be pure and remain unchanged. Sample-path extension is explicitly single-threaded;
immutable feature paths have fixed coefficient storage.

The old FLine GP helper now delegates to the coherent joint implementation. The old Smile wrapper
no longer catches a failed fit and substitutes linear interpolation. It exposes posterior variance
and joint sampling too, accepts `maxIter = 0` for fixed hyperparameters, and skips division by zero
when normalization is requested on constant observations. Its legacy noise parameter remains a
variance in Smile's normalized units. New APIs consistently use noiseStd.

## Validation

In a full Field checkout (2026-10-09), after merging main:

```bash
./gradlew jvmTest --tests 'afield.math.gp.*' --tests afield.graphics.FLineGaussianProcessTest --tests afield.graphics.FLineGpGeometryTest
```

48 tests pass: the PR's 24 core, 4 Smile, 1 figure-data and 6 FLine integration tests (the last
six had never run against Field before), plus `GpDerivativeTest` (9) and `FLineGpGeometryTest`
(4). The derivative tests check every differentiable kernel and composition against central
differences of the covariance, the derivative posterior against differenced value posteriors
(means and covariances), analytic posteriors after slope observations, evidence scans and spread.
The geometry tests check a sampled circle's curvature (1/r) and frames, the delta-method tangent
angle against Monte Carlo over drawn velocities, velocity constraints and 3D frame orthonormality.

Curvature uncertainty is honest down to a floor. With 1e-3 position noise on a circle of radius
3 the curvature std is about 3.4e-3, independent of step from 1e-4 to 1e-2 and ten times larger
at ten times the noise. At 1e-4 noise the std starts to depend on step: the velocity variance is
then near the `GpNumerics` tolerances and the pivoted sampler truncates it.

Every tutorial snippet was run in a live Field (backdoor, port 9561, each with the snippets it
depends on prepended) with no compile or runtime errors, without any `import`.

The scientific figures are generated from production Kotlin output by GaussianProcessFiguresTest.
The Python plotting script only renders those numerical results. They are not Field UI captures.

To regenerate the committed scientific figures:

```bash
FIELD_GP_FIGURE_DATA="$PWD/build/gp-figure-data" ./gradlew jvmTest --tests afield.math.gp.GaussianProcessFiguresTest --rerun-tasks
python tools/gp/render_figures.py build/gp-figure-data public/docs/assets/gaussian_processes
```

The renderer needs numpy and matplotlib. Inspect the output images at documentation column width.
The explicit rerun matters: changing an environment variable alone need not invalidate Gradle's
up-to-date test task.

## Deliberate limits

Exact inference is dense; the vector model pays for all scalarized coordinates. Fourier features
remove the query-grid factorization but do not remove the dense training solve. They are an
approximate RBF model, never a hidden fallback. Hyperparameter fitting is a local point estimate,
not integration over hyperparameters. Values and first derivatives can be observed and queried; second
and higher derivatives are not analytic (curvature differences velocities), and general linear
functionals (integrals as observations), non-Gaussian likelihoods and sparse variational
training are outside this implementation. Normal bands and positional
ellipses are pointwise, not simultaneous credible regions for an entire curve.
