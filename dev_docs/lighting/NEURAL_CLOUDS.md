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
- GPU parity (2026-11-12, M1 Max / KosmicKrisp): `four.neural-cloud-parity.smoke` (compute shader, CPU pixel
  centres + ign seeds) gives GPU-vs-CPU network relMAE 8.4e-7 / RMSE 7.0e-7 over 4089 px; a x1.5-scaled network
  gives 0.32. Writes `build/neural_clouds/gpu_parity.{png,txt}`.
- Network every Nth lit step (`networkEvery`, `cloudRenderEvery`), vs 48-path reference: N=1 0.0652, 2 0.0656,
  4 0.0658, 8 0.0640. Wall-clock frame at 1280x800: 21.4 / 23.9 / 21.7 / 17.1 ms (empty frame 9.1 ms), so the
  timings are noisy and only N=8 is clearly faster. No GPU timestamp queries are used.
- linux-nvidia (a0, RTX 4090): not measured. The smoke failed with "Unable to initialize GLFW" over ssh, with and
  without DISPLAY=:0 (probably no XAUTHORITY over ssh).
- Not done: live controls (sun/density/network on-off) in `cloud_view`; needs the scene constants as push constants.
- Sky is a two-colour gradient, not `afield.graphics.sky.Atmosphere`; a sun-direction input would let one net cover a moving sun.
- `#include "cloud/network"` is registered by a *parent* box (`cloudHere`); a box's shaders compile before its code runs.
