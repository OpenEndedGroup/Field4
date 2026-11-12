# Neural multiple scattering for clouds — method, numbers, traps

Source idea: Charles Grassi, https://charlesgrassi.dev/blog/neural-volumetric-clouds (no licence stated;
reimplemented from the description, no code copied). Code: `src/jvmMain/kotlin/afield/graphics/volume/NeuralClouds.kt`;
test `NeuralCloudsTest`; demo `dev_docs/agents/transcripts/neural_clouds.yaml`; docs section in `public/docs/path_tracing.md`.

## Method
- Reference: CPU delta-tracking path tracer over a closed-form density (soft spheres eroded by sines, so GLSL matches
  exactly). Target = in-scatter at a point toward the eye, *excluding* direct sun (the raymarcher adds that
  analytically): phase-sampled directions, sun NEE + ratio tracking at every collision, sky on escape, RR after 4 bounces.
- `PathTracingRenderer.mediumDensity` was not usable: uniform density only, no per-sample targets.
- Features (10): height, density, exp(-tau) toward sun over 0.25 / 1.0 / full, up, eye, down; cos(theta); scaled HG.
  Each tau is a fixed 16-step midpoint march, identical in Kotlin and GLSL.
- Net: `VectorMLP` 10-16-16-3 tanh (499 params), RMSProp, lr 3e-3 -> 1e-4 over 8000 steps of 64; targets / (0.5 * max).
- Baseline: least-squares a*exp(-tau_up) + b*exp(-tau_sun) per channel.

## Numbers (held-out camera, 200x125, 4532 cloud px, 48-path reference)
single 0.68 rel MAE / 0.44 RMSE; fitted ambient 0.31 / 0.21; network 0.068 / 0.053. 12k samples, final loss 0.124 (scaled).

## Not done / traps
- GPU network image is checked by eye only (it matches the CPU network render in look); there is no numeric
  GPU-vs-CPU parity test. `MlpWeights.evaluate` is the fp32 oracle the GLSL accumulates like, so one is easy to add.
- No frame-time measurement. Per pixel: 48 steps x 6 marches x 16 density evals + the net.
- Sky is a two-colour gradient, not `afield.graphics.sky.Atmosphere`; a sun-direction input would let one net cover a moving sun.
- `#include "cloud/network"` is registered by a *parent* box (`cloudHere`); a box's shaders compile before its code runs.
