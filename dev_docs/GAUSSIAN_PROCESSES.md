# Gaussian process implementation and validation

The public tutorial is `public/docs/gaussian_processes.md`.

## Model and ownership

- `GaussianProcess.kt`: exact scalar regression on arbitrary-dimensional DoubleArray inputs;
  composed covariance functions; priors, heteroscedastic noise, mean, latent variance, full joint
  covariance, seeded coherent draws, marginal likelihood, conditioning and leave-one-out diagnostics.
- `GaussianProcessVector.kt`: separable multi-output covariance, implemented by lifting each
  (input, coordinate) to a scalar observation. Full coordinate and cross-input covariance survive.
- `FLineGaussianProcess.kt`: Vec3 curve observations, normalized arc-length FLine fitting,
  coherent sampled FLines, coordinate variances, covariance blocks and planar normal bands.
- `GaussianProcessPaths.kt`: exact conditional sample-path extension, and explicitly approximate
  paired Fourier-feature RBF models with Matheron-rule conditional function draws.
- `GaussianProcessFit.kt`: portable bounded log-space pattern search.
- `GaussianProcessSmile.kt`: existing Smile kernels and bounded L-BFGS-B, evaluating Field's
  likelihood with bounded finite differences. Smile 5.0.1 is already a dependency; none was added.
- `GaussianProcessField.kt`: Vec2/Vec3 observation and prediction conveniences.

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

## Validation performed in this environment

A full authenticated Git clone was unavailable. Source files were read at main
`8068e9a420f228402b1e09cc3e231bc7238c3864` through the repository connector, and a focused standalone
Kotlin/JVM compilation was used. This is not a full Field build.

The standalone suite uses Kotlin 2.2.0, JDK 25, actual Smile 5.0.1 jars and the same OpenBLAS
0.3.26-1.5.10 Linux binary pinned in Field. It compiles the production numerical files and the
unchanged CompletionCategory declaration extracted from Field's util.kt. No numerical models,
Smile implementations or native kernels are mocked. All 29 tests pass (24 core, 4 Smile,
1 figure-data test).

Tests cover analytic regression/evidence, posterior moments including cross-covariance, 2D/3D
restrictions, duplicate/noisy/exact constraints, prior reversion, array ownership, indefinite
covariance rejection, rank-deficient sampling, leave-one-out against refits, linear transformations,
conditioning, composed kernels, bounded fitting, exact persistent paths, feature-model covariance
and constraints, correlated vector outputs and numerical support. Smile tests compare fixed-
hyperparameter means and the entire query covariance, check legacy constant-data normalization and
joint samples, and exercise the actual Smile bounded optimizer.

The scientific figures are generated from production Kotlin output by GaussianProcessFiguresTest.
The Python plotting script only renders those numerical results. They are not Field UI captures.

## Required full-checkout checks before landing

Run in a real Field checkout with its wrapper/toolchain and native setup:

```bash
./gradlew jvmTest --tests afield.stdlib.GaussianProcessTest --tests afield.stdlib.GaussianProcessSmileTest --tests afield.stdlib.GaussianProcessFiguresTest --tests afield.graphics.FLineGaussianProcessTest
```

This must also compile the FLine adapters and changed legacy helper against the actual Field
classes. The six FLine integration tests are supplied but were not executed in the focused
numerical harness. Signature-only compilation is not a substitute for this integration gate.
Exercise the tutorial's drawing/heatmap snippets in live Field and run the normal documentation
build; no live-box execution, Zensical build or generated-reference regeneration is claimed here.
Use the repository lander after these checks; do not merge main directly.

To regenerate the committed scientific figures:

```bash
FIELD_GP_FIGURE_DATA="$PWD/build/gp-figure-data" ./gradlew jvmTest --tests afield.stdlib.GaussianProcessFiguresTest --rerun-tasks
python tools/gp/render_figures.py build/gp-figure-data public/docs/assets/gaussian_processes
```

The renderer needs numpy and matplotlib. Inspect the output images at documentation column width.
The explicit rerun matters: changing an environment variable alone need not invalidate Gradle's
up-to-date test task.

## Deliberate limits

Exact inference is dense; the vector model pays for all scalarized coordinates. Fourier features
remove the query-grid factorization but do not remove the dense training solve. They are an
approximate RBF model, never a hidden fallback. Hyperparameter fitting is a local point estimate,
not integration over hyperparameters. Finite-difference/weighted query distributions are supported;
analytic derivative kernels, linear-functional training observations, non-Gaussian likelihoods
and sparse variational training are outside this implementation. Normal bands and positional
ellipses are pointwise, not simultaneous credible regions for an entire curve.
