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

## Numbers (held-out camera, 200x125, 4089 cloud px, 48-path reference, jittered marches)
single 0.65 rel MAE / 0.42 RMSE; fitted ambient 0.32 / 0.23; network 0.065 / 0.051. 12k samples, final loss 0.112.
(Before jitter + lifting the cloud: 0.68 / 0.31 / 0.068 on 4532 px.)

## Banding (fixed 2026-11-12)
Fixed 48 view steps and 16 midpoint sun steps with the same offset for every pixel drew arcs centred
on the camera. Now: view step 0.04 world units (max 160), per-pixel interleaved-gradient-noise start
offset; a second IGN offset for every optical-depth march; Kotlin `ign` and GLSL `cloudIgn` match;
training features use random offsets. The cloud was lifted 1 unit (bounds y 1..3.2) so it no longer
overlaps the below-horizon ground colour, which is the sky function's lower hemisphere at infinity.

## Not done / traps
- GPU network image is checked by eye only (it matches the CPU network render in look); there is no numeric
  GPU-vs-CPU parity test. `MlpWeights.evaluate` is the fp32 oracle the GLSL accumulates like, so one is easy to add.
- No frame-time measurement. Per pixel: 48 steps x 6 marches x 16 density evals + the net.
- Sky is a two-colour gradient, not `afield.graphics.sky.Atmosphere`; a sun-direction input would let one net cover a moving sun.
- `#include "cloud/network"` is registered by a *parent* box (`cloudHere`); a box's shaders compile before its code runs.
