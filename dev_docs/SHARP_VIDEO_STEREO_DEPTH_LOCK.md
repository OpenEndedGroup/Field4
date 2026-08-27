# Temporally locked SHARP splats for monocular-to-stereo video

Status: implementation plan, source-inspected on 2026-08-26, not yet executed.

## Goal

Given:

- an ordinary monocular video;
- a precomputed, temporally coherent relative-disparity sequence from
  [Video Depth Anything](https://github.com/DepthAnything/Video-Depth-Anything),
  stored as float16 NPZ;
- Apple's [SHARP](https://github.com/apple/ml-sharp) single-image Gaussian-splat
  predictor;

produce one SHARP Gaussian scene per source frame, but make the VDA sequence
authoritative for surface depth. Render a left and right view from the same
per-frame scene with fixed stereo geometry.

A metric VDA model is **not required**. Stereo constrains the ratio `B / Z`, not
baseline and depth independently. A stable relative inverse-depth/disparity
coordinate is therefore sufficient; the pipeline chooses one fixed gauge per
shot and expresses the virtual stereo baseline in that same gauge.

This deliberately does **not** attempt to build one persistent 4D scene. The
first target is narrower: accept a regenerated splat on every frame, while
eliminating depth-scale breathing and depth-edge jiggle well enough to make a
stable stereo pair.

The working pipeline is:

```text
source video ──┬───────────────────────────────> SHARP RGB/features ─────────────────────┐
               │                                                                        │
VDA float16 NPZ ──> promote to float32 ──> shot-wide disparity gauge/calibration ───────┤
                                                                                       v
                                                           depth-locked per-frame 3DGS
                                                                                        │
                                             fixed parallel left/right cameras <────────┘
                                                                                        │
                                                                        left + right video
```

## Why this is a small modification rather than a new model

SHARP already contains most of the intended interface:

- `RGBGaussianPredictor.forward()` accepts an optional `depth` tensor.
- `DepthAlignment` contains a learned network for aligning SHARP's monocular
  depth to supplied metric depth.
- `MultiLayerInitializer` constructs the base Gaussian positions and sizes from
  depth.
- `GaussianComposer` adds learned XYZ residuals and the remaining Gaussian
  attributes.

The stock CLI calls:

```python
gaussians_ndc = predictor(image_resized_pt, disparity_factor)
```

The smallest experiment changes that to:

```python
gaussians_ndc = predictor(
    image_resized_pt,
    disparity_factor,
    depth=depth_vda_resized,
)
```

The public CLI does not expose this input, but the checkpoint and source already
contain the alignment path. Relevant upstream files are:

- `src/sharp/models/predictor.py`
- `src/sharp/models/alignment.py`
- `src/sharp/models/initializer.py`
- `src/sharp/models/composer.py`
- `src/sharp/cli/predict.py`
- `src/sharp/cli/render.py`

## Reproducible starting revisions

These were the upstream `main` revisions inspected for this plan:

```text
apple/ml-sharp                              1eaa046834b81852261262b41b0919f5c1efdd2e
DepthAnything/Video-Depth-Anything          4f5ae23172ba60fd7bc11ef671cca678842c7072
TencentARC/StereoCrafter (reference only)   e30707728bd2f52cec2de8630098a7ae4f95d27c
```

Pin these commits for the first implementation. Reinspect the relevant files if
working from later upstream revisions.

## Linux/RTX 4090 setup

The implementation begins downstream of VDA. The existing movie-to-float16-NPZ
pipeline remains untouched. Only SHARP and the small NPZ/calibration wrapper are
needed on the Linux machine. If depth ever needs to be regenerated there, use a
separate VDA environment: at the inspected revisions, VDA pins PyTorch 2.1.1
while SHARP pins PyTorch 2.8.0 and `gsplat` 1.5.3.

Suggested layout:

```text
stereo-work/
  source/
    shot.mp4
  Video-Depth-Anything/
  ml-sharp/
  work/
    frames/
    depth/
    sharp-native/
    sharp-aligned/
    sharp-locked/
    stereo/
```

Before installing either project, verify:

```bash
nvidia-smi
nvcc --version
ffmpeg -version
```

SHARP prediction supports CPU, CUDA, and MPS, but its supplied `gsplat` video
renderer currently requires CUDA. `gsplat` may compile a CUDA extension, so the
machine needs a compatible CUDA toolkit, not merely a working NVIDIA driver.
Begin with each repository's own documented environment and pinned
requirements.

Licensing must be checked before the work is used beyond an experiment. In the
inspected revision, Apple's released SHARP model is licensed exclusively for
non-commercial research purposes. VDA model licenses vary by checkpoint; its
repository describes the Small model as Apache-2.0 and Base/Large as
CC-BY-NC-4.0.

## NPZ input contract

The SHARP-side pipeline consumes the precomputed NPZ and does not run VDA. The
preferred contract is:

```text
depths: float16 [frame, height, width]
```

Float16 is suitable for storage. Immediately promote each loaded frame, and any
statistics sampled from it, to float32 before fitting, inversion, interpolation,
filtering, or Gaussian construction:

```python
with np.load(depth_path) as data:
    disparity_f16 = data["depths"]
    disparity = disparity_f16.astype(np.float32)
```

Keep the relative values raw. Do not quantize them to an 8-bit visualization,
and do not normalize each frame independently.

Verify before proceeding:

1. RGB and depth have exactly the same frame count and order.
2. The dimensions and aspect ratio refer to the same processed source frames.
3. Foreground/background ordering is known. Larger raw relative-VDA values
   should normally behave like nearer inverse depth; confirm this on the actual
   clip rather than assuming it.
4. Cuts have been detected. Calibration and temporal state reset at every cut.

## Depth calibration

### Relative disparity is sufficient

Treat the raw output `q` as a stable inverse-depth coordinate. Fit one robust
affine conversion per shot:

```text
1 / Z = a q + b
Z     = 1 / max(a q + b, epsilon)
```

For a first experiment, obtain targets from SHARP's own native metric depth on
a sparse set of frames:

1. Run unmodified SHARP on roughly 16-32 frames spread across one shot.
2. Extract SHARP's first/surface depth layer before Gaussian composition.
3. Pair `1 / Z_sharp` with the corresponding VDA values.
4. Fit `a,b` jointly over all sampled frames with a robust loss.
5. Exclude sky, reflections, invalid pixels, extreme depth discontinuities, and
   image borders from the fit.
6. Apply the same `a,b` to every frame in that shot.

This uses SHARP only to choose the approximate metric scale. VDA remains the
source of temporal shape. Do not fit `a,b` independently on each frame.

The fit does not recover physically trustworthy metres, nor does it need to. It
places the VDA sequence in SHARP's familiar numerical range and defines a stable
unit system. The virtual camera baseline is subsequently chosen in that same
unit system. If the eventual delivery only needs a comfortable stereo effect,
the disparity budget on the target display is authoritative, not the nominal
baseline in metres.

Save the calibration beside the output:

```json
{
  "shot": "shot-000",
  "depthKind": "relative-inverse-depth",
  "a": 0.0,
  "b": 0.0,
  "epsilon": 0.0001,
  "focalLengthPx": 0.0,
  "sourceFrameRange": [0, 0]
}
```

## Stage A: use SHARP's existing alignment hook

This is the lowest-risk proof of concept because it follows a path present in
the released network.

Extend `predict_image()` to accept a depth array. Apply the same geometrical
preprocessing as the RGB input and create a tensor shaped `[B, 1, H, W]`:

```python
depth_pt = torch.from_numpy(depth_m.copy()).float().to(device)[None, None]
depth_resized_pt = F.interpolate(
    depth_pt,
    size=(internal_shape[1], internal_shape[0]),
    mode="bilinear",
    align_corners=True,
)

gaussians_ndc = predictor(
    image_resized_pt,
    disparity_factor,
    depth=depth_resized_pt,
)
```

The resize must match SHARP's stretched 1536 x 1536 internal image coordinates
exactly. Pass the positive pseudo-depth `Z` produced by the shot-wide mapping;
do not pass raw inverse depth to this argument.

The existing `DepthAlignment` module compares inverse source/target depths and
predicts a local multiplicative scale map. It may be sufficient. It does not,
however, mathematically force the final Gaussian Z positions to equal the VDA
surface.

## Stage B: make VDA depth authoritative

If Stage A still breathes or jiggles, bypass the learned alignment for the
depth used by the initializer while retaining SHARP's image and depth features.

Inside `RGBGaussianPredictor.forward()`:

```python
monodepth_output = self.monodepth_model(image)
monodepth_disparity = monodepth_output.disparity
disparity_factor = disparity_factor[:, None, None, None]
depth_sharp = disparity_factor / monodepth_disparity.clamp(min=1e-4, max=1e4)

if depth is not None:
    # Lock SHARP's first/surface layer exactly to VDA. Apply the same local
    # scale to its second layer so the learned behind-surface spacing survives.
    local_scale = depth / depth_sharp[:, 0:1].clamp(min=1e-4)
    depth_for_init = depth_sharp * local_scale
else:
    depth_for_init = depth_sharp

init_output = self.init_model(image, depth_for_init)
image_features = self.feature_model(
    init_output.feature_input,
    encodings=monodepth_output.output_features,
)
delta_values = self.prediction_head(image_features)
```

This choice matters: passing a one-channel depth map directly to SHARP's
two-layer initializer would duplicate the surface into both Gaussian layers.
Scaling both native layers by `depth / depth_sharp[:, 0:1]` pins the visible
surface while retaining SHARP's learned second layer for disoccluded material.

SHARP's composer subsequently adds an inverse-Z residual. Disable that residual
for a strict lock:

```python
delta_values[:, 2:3] = 0
```

The tensor layout is `[batch, gaussian_attribute, layer, height, width]`; mean
X, mean Y, and mean Z residuals occupy attributes 0, 1, and 2.

Do not immediately suppress every learned geometric attribute. Test in this
order:

1. lock base depth only;
2. lock base depth and zero the Z residual;
3. if silhouettes still shimmer, also zero or temporally filter X/Y residuals;
4. only then consider filtering scale, quaternion, or opacity.

Keep current-frame RGB/color rather than temporally averaging it blindly; color
averaging across moving image coordinates creates trails.

## Optional residual stabilization

VDA is already temporally trained, so add filtering only when measured or
visible residual jitter remains. Filter inverse depth or SHARP residual maps
along motion trajectories, not at fixed screen coordinates:

```text
q_stable(t) = mix(q_current(t), warp(q_stable(t-1), flow), history_weight)
```

Gate history with:

- forward/backward flow agreement;
- occlusion detection;
- depth-edge disagreement;
- scene-cut detection;
- a maximum history age.

A small temporal median along flow tracks is another useful offline baseline.
Never temporally average across an occlusion boundary merely because the pixels
are adjacent.

## Stereo camera construction

SHARP uses the OpenCV convention: X right, Y down, Z forward. Treat the source
camera as the midpoint and render parallel cameras at:

```text
C_left  = (-B / 2, 0, 0)
C_right = (+B / 2, 0, 0)
```

Both views must use the same focal length, image size, vertical principal point,
exposure, and per-frame Gaussian scene. Keep `B` fixed over the shot.

For a point at metric depth `Z`, the approximate disparity is:

```text
d_px = f_px * B / Z
```

For a chosen zero-parallax/convergence plane `Z0`, use off-axis principal-point
shifts so that:

```text
d_px = f_px * B * (1 / Z - 1 / Z0)
```

Do not toe the cameras inward; toe-in introduces keystone distortion and
vertical disparity. A post-render horizontal shift is equivalent for a first
prototype, provided the border crop is handled consistently.

When the depth gauge has been aligned to plausible SHARP metric depth, an
effective baseline around 0.02-0.04 in those units is a reasonable first probe.
More generally, solve for `B` so that a robust foreground percentile produces
about 1% of frame width in disparity. Increase only after inspecting the result
on the intended display. Nearby monocular scenes often do not contain enough
hidden content for a full human-interocular shift.

Use one fixed convergence plane per shot, or a very slowly varying editorial
track. Automatic per-frame convergence will look like the scene is pumping even
when the depth itself is stable.

## Rendering and output

Modify SHARP's renderer to accept two explicit camera poses rather than its demo
orbit trajectory. For each source frame:

1. predict one depth-locked `Gaussians3D`;
2. render the left camera;
3. render the right camera;
4. save lossless or high-bit-rate intermediate frames;
5. assemble separate-eye, full-SBS, or half-SBS delivery after validation.

Render both eyes from the same splat. This sacrifices the exact untouched source
frame, but it makes representation errors agree between the eyes and reduces
binocular rivalry. A useful diagnostic variant keeps the original as one eye
and renders only the other; it is not the preferred final pair if the SHARP
render differs visibly from the original.

Preserve the source frame rate and timestamps. Do not let frame extraction or
VDA's `target_fps` silently change cadence.

## First experiment

Use one continuous 5-10 second shot with foreground, midground, background, a
moving object, and a visible depth edge. Avoid cuts, transparent objects, heavy
motion blur, and a large camera translation for the first test.

Generate these variants from the same frames:

```text
A  native SHARP, native demo/original-pose render
B  SHARP with the existing depth-alignment hook
C  hard surface lock, SHARP Z residual retained
D  hard surface lock, SHARP Z residual zeroed
```

For B-D, render:

- source/original camera pose;
- left eye;
- right eye;
- red/cyan anaglyph;
- full side-by-side.

Do not begin with a feature-length video. The short comparison should determine
whether the existing hook is enough, whether the hard lock is needed, and which
remaining SHARP attributes visibly flicker.

## Acceptance checks

The short shot is successful when all of these are true:

1. **Depth order:** foreground disparity has the correct sign and is larger than
   background disparity.
2. **No scale breathing:** a tracked static surface does not move systematically
   toward/away from the camera over time.
3. **Stable convergence:** a tracked point on the chosen `Z0` plane remains near
   zero disparity.
4. **No vertical disparity:** left/right correspondences differ vertically by
   less than a small subpixel tolerance except at genuine occlusions.
5. **Temporal edges:** silhouettes do not chatter between depth layers.
6. **Binocular agreement:** faces, text, clothing patterns, and high-frequency
   texture are not independently invented in the two eyes.
7. **Honest holes:** disoccluded areas are either represented by SHARP's second
   layer, conservatively filled, or left visible as a failure. They are not
   hidden by collapsing the stereo baseline.
8. **Original-pose fidelity:** rendering the locked splat at the source camera
   remains acceptably close to the source frame.

Useful measurements include:

- temporal median and 95th-percentile inverse-depth change after flow warping;
- temporal change in disparity at flow-tracked points;
- vertical left/right matching error;
- foreground and background disparity percentiles;
- original-pose render PSNR/SSIM/LPIPS, interpreted alongside visual inspection.

## Expected failure modes

| Symptom | Likely cause | First response |
| --- | --- | --- |
| Entire scene pumps | Per-frame depth normalization, focal length, or convergence | Lock all three per shot and inspect saved calibration |
| VDA input has little effect | Learned alignment does not force final Z | Use Stage B and zero the Z residual |
| Foreground appears behind background | Relative depth direction is reversed | Inspect raw VDA values and invert the fitted ordering |
| Silhouette chatters but interiors are stable | Depth-edge or XY residual instability | Edge-aware flow stabilization; then suppress XY residuals |
| Stereo is stable but both eyes shimmer together | Per-frame opacity/scale/rotation changes | Motion-filter selected Gaussian residual maps |
| Two eyes disagree in texture | One eye is source RGB and one is synthesized | Render both eyes from the same splat |
| Large holes beside foreground objects | Baseline exceeds inferred hidden content | Reduce baseline; inspect second layer; then consider temporal/video inpainting |
| Depth is stable but stereo feels like a cardboard cutout | Depth lacks local relief or disparity was compressed too much | Inspect raw depth boundaries and disparity distribution before increasing baseline |
| Geometry bends at the edges | Wrong or varying focal length / lens distortion | Undistort first and use fixed calibrated intrinsics |
| CUDA render fails while prediction works | `gsplat` extension/toolkit mismatch | Verify the installed PyTorch CUDA build, `nvcc`, compiler, and gsplat build |

## What this does not solve

- A single frame cannot reveal every background surface exposed by the stereo
  baseline.
- Locking surface depth does not guarantee stable opacity, scale, rotation,
  color residuals, or hidden-layer geometry.
- Dynamic-object geometry is still inferred independently on every frame.
- A Gaussian representation can reproduce a wrong but internally consistent
  depth interpretation.
- Metric monocular depth is not camera calibration.

If missing-background quality is the limiting problem after the geometry is
stable, the next escalation is temporal stereo inpainting. StereoCrafter is a
useful reference architecture: depth-based forward splatting produces a warped
view and occlusion mask, followed by video inpainting. That is a later stage,
not a prerequisite for establishing the SHARP depth lock.

## Handoff checklist

Bring to the Linux machine:

- the original video, not a transcoded proxy;
- its frame rate, dimensions, and any known camera intrinsics/distortion;
- the existing raw float16 VDA NPZ sequence;
- the NPZ key name and the exact correspondence between source and depth frames;
- the shot/cut list;
- the intended output display or at least its delivery format;
- pinned clones of VDA and SHARP;
- enough storage for lossless intermediate eye frames;
- this plan and a record of every calibration/baseline/convergence value used.

The first implementation should finish with a small comparison grid and its
settings, not just a visually pleasing SBS movie. That evidence will tell us
whether the depth hook alone works or whether the strict Stage B lock is
necessary.
