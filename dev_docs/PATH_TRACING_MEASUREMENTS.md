# Path tracing: what is checked, and what was measured

The [Linux/NVIDIA audit](RAY_TRACING_AUDIT.md) adds measured ray-query coverage, real ray-pipeline
captures, the shared-edge regression and reproduction commands. The M1 measurements below remain
historical observations on that hardware.

Displaced from `public/docs/path_tracing.md` on 2026-09-15. That page had three consecutive
sections — `## Verification and export`, `## Noise comparison`, `## Measured baseline` — running
from 81% to 91% of its length, between "Current limits" and "Reading a running tracer". A reader
who wants to path-trace something acts on two numbers out of all of it: roughly what a frame
costs, and roughly what `indirectClamp` costs in exchange for removing caustic specks. Those two
stayed on the page. Everything else is here.

Nothing in this note is re-derived; it is the same text, with the sections it was scattered across
collapsed into one place.

## What each check covers

`PathTraceTestMain` — scene/BVH packing, environment distributions, and shader
compilation/reflection for both backends.

`PathTraceGpuSmoke.run(renderer)` — GPU readback against known HDR colours, material placement,
image orientation, workgroup edge pixels, accumulation resets, and white-furnace
energy/convergence. It also compares backends when a ray-query device is available. The page keeps
the two-line recipe for running it from a launched renderer child.

glTF checks — embedded/external texture equivalence, UV orientation, transforms, colour spaces,
material channels, normal maps, alpha coverage.

Glass checks — slab transmission, Beer–Lambert thickness, both faces of a thin pane, a camera
inside glass, total internal reflection, and convergence at three frosted roughnesses.

Clamp checks — indirect luminance, colour preservation, accumulation reset, and preservation of
direct HDR lighting.

`PathTraceTemporalGpuSmoke.runFog(renderer)` — the exponential fog curve, clear distance,
background controls, fog-setting resets, and dense-to-clear camera motion with retained history.
Also premultiplied silhouette coverage with pixel jitter and depth of field, with temporal reuse
both enabled and disabled.

`PathTraceEmitterGpuSmoke.run(renderer, directory)` — emitter sampling: that it is inert with no
emitters, that its pdf matches the environment sampler in absolute terms, that its two arms compose
without double counting, and that it reduces RMSE by an asserted factor at an equal sample budget.
It writes the before/after captures on the way past.

Glass and white-furnace checks run with clamping disabled, or with exclusively direct lighting, so
that they test the sampler rather than the clamp.

## Noise, and what the clamp changes

A compute-backend check on an M1 Max rendered the same sunset material study at 320 × 180 and 1,024
samples per pixel. In the foreground floor patch (x 10–309, y 125–174), a strong speck means red
radiance more than 0.1 above the median of its eight neighbouring pixels:

| setting | strong specks | largest red excess | mean red radiance |
| --- | ---: | ---: | ---: |
| previous sampler | 20 | 1.288 | 0.27255 |
| filtered environment + visible-normal GGX, unclamped | 9 | 1.970 | 0.27236 |
| same sampler, `indirectClamp = 10f` | 0 | 0.044 | 0.27206 |

Sampling changes alone do not remove rare caustic outliers — the largest one in this particular
unclamped run was worse. The clamp addresses that tail. At 16,384 samples the patch's mean red
radiance went from 0.27244 unclamped to 0.27206 clamped, about −0.14%. That is a scene-specific
measurement and not a bound on clamp bias in other scenes.

The unit-environment frosted slab at roughness 0.6 and 2,048 samples had spatial variance 0.0000187
with visible-normal sampling against 0.000250 previously, its mean staying about 0.815.

## Frame cost

Before the transmission extension, on an Apple M1 Max using MoltenVK, the bundled opaque
three-sphere scene (6,626 triangles) at 640 × 360, one sample per frame and five bounces: **4.28 ms
median, 6.26 ms p95** for the GPU tracing module. GPU timestamps after 30 warmup samples, across
1,666 module samples, excluding display, presentation and unrelated application work.

The glass study (7,222 triangles) on the same machine at 640 × 360, one sample per frame and ten
bounces: **10.12 ms median, 10.89 ms p95** for GPU tracing across 2,189 samples after 30 warmup
samples, excluding presentation and other application work. That capture includes clear, absorbing,
frosted and thin glass, with traced caustics that stay visibly noisy under the sunset sun.

Both describe that scene on that machine.

## Energy and transmission agreement

The GPU white-furnace check measured mean radiance 0.9985 under unit white illumination;
central-patch variance fell from 0.00625 to 0.00000224 as accumulation went from about 8 to 1,024
samples. Hardware ray-query execution was unavailable on that Mac — both shader variants compiled,
and the GPU smoke compares their outputs automatically when a ray-query device is present.

GPU slab checks: white-environment radiance 1.00005 for clear glass. Red transmission through an
index-matched absorbing slab was 0.50007 at unit thickness and 0.25012 at double thickness.
Thin-pane measurements agreed from either face. At roughness 0.35 the frosted slab's mean was
0.97649 and spatial variance fell from 0.00640 at about eight samples to 0.0000177 at 2,048. At
roughness 0.6 the mean fell to 0.81528 — the single-scattering approximation loses appreciable
energy at high roughness, which is the one number here a user may need, and the page states it
where roughness is introduced. Total internal reflection produced no leaked radiance.

## Emitter sampling: that it converges to the same image

Displaced from the page on 2026-09-16. The page keeps the RMSE ratio, which is the number that
tells a reader to leave `emitterSampling` on; this is the unbiasedness half.

Unbiasedness is checked against something other than itself. A sphere inside a uniformly emissive
cube, at one bounce, is the identical integral as the same sphere under a constant unit environment
map -- which the tracer solves through the separate, already-checked environment path. At one bounce
neither run applies a multiple-importance weight, so what is under test is exactly the new pdf and
its area-to-solid-angle Jacobian.

| Check | Whole image | Worst of 24 tiles | Per-pixel p95 |
| --- | ---: | ---: | ---: |
| Emissive enclosure against a constant environment, 1 bounce | 0.00001 | 0.00011 | 0.00206 |
| Six differently coloured faces, on against off, 2 bounces | 0.00004 | 0.00054 | 0.00360 |
| Two small lamps, on against off, 3 bounces, 65,536 samples | 0.00070 | 0.01071 | 0.13316 |

The first two are over all 6,912 pixels; the third uses the 6,746 lit ones. The white-furnace mean
under unit radiance from every direction came out 0.99811 through emissive geometry and 0.99810
through the environment map -- the same number by two unrelated routes.

The third row's larger residual is the lamp box's own reference noise rather than bias: it shrinks
5.49x when the reference budget grows 4x, and the two references differ by 0.00359 RMS against a
predicted standard error of 0.00848 for the off reference at that budget.

The RMSE comparison the page keeps is over the 6,746 lit pixels of a 96 x 72 render -- luminance
between 0.004 and 6, which excludes the black background and the directly visible lamps -- at an
equal 257 samples per pixel, against a 65,536-sample reference: 0.13534 off against 0.00389 on.
`PathTraceEmitterGpuSmoke` asserts a floor of 28x against the measured 34.8x.

## Temporal reuse: the retained-weight regression

Displaced from the page on 2026-09-16.

In a 48 x 32 constant-environment diffuse-plane scene, after warming up for 512 samples, a small
translation with a 16-sample cap retained a mean weight of approximately 16 in the measured surface
patch. A separate densely tessellated plane checks that jitter crossing triangle seams preserves
useful history.

These tests include interpolation of history, so the noise reduction they report is not a count of
independent samples and not a general speedup. The page keeps that caveat, since a reader can
otherwise take the regression as a performance claim.

## Replacing the integrator: what survives the swap

Displaced from the page on 2026-09-16.

On an M1 Max, swapping the 640 x 360 material study to direct-light-only changed 155,099 of its
230,400 pixels and dropped mean linear radiance from 0.27579 to 0.26030, while `camera.at`,
`camera.target` and `camera.up` stayed bitwise identical. The page keeps the qualitative
guarantee -- scene, environment, accumulation buffers, camera and tuning properties all survive.

## Open

- **None of the figures in this note is asserted at its printed value.** The smokes assert floors
  and ratios (`mediumSpread > 0.5`, `fogSpread / fogMean < 1e-4`, the 28x RMSE floor); everything
  quoted to five decimals here is a last observation on one machine. Do not cite them back onto a
  page as contract.
