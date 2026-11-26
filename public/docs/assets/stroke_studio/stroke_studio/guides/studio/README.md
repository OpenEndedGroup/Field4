# Stroke studio

Open `stroke_studio` in Field. Run **Stroke types**, launch **Stroke renderer**, then launch **Stroke studio**. Run **Read the layers** to read the root quadtree cell back from the GPU.

## Make a brush

Open the **StackSL** property on **Stroke studio**. The definitions each take one `vec4` and return one `vec4`. The comments above them specify every lane. Field recompiles an edit on the running renderer and reports which pipelines changed in **Brush program**. A failed edit keeps the previous working program. The named GLSL files in `files/shaders` hold the surrounding passes. Use **Reload GLSL** or **R** for those.

The controls are grouped into **Scene and views**, **Brush form**, **Tracing and pigment**, and **Final composite**. The initial program has a fast attack, a long release, and unit brush gains. **Width**, **Stroke weight**, **Stroke scale**, and **World displacement** affect ribbons that are already alive. Weight multiplies signed pigment and coverage together. Scale grows the ribbon around the middle of its ordered knots. World displacement scatters each complete ribbon along a stable 3D direction. Tracking still updates the original world-space anchors.

**Attack seconds**, **Release seconds**, **Length**, **Pigment fraction**, and the variance controls apply when new strokes are born. **Interrupted release seconds** applies when a stroke loses its surface, stretches, or sees a large color change. Press **C** to clear the pool and watch a new population grow. Controls use the defaults in **Stroke types** when you launch **Stroke studio**.

## Choose what to follow

- **Velocity integration = 0** uses the tangent of the mixed paint/G-buffer structure tensor. Near the frontier, existing paint can steer weak tangents via **Paint-front steering**. Reliable projected velocity chooses the initial sign.
- **Velocity integration = 1** samples the velocity vector at each midpoint. The second branch integrates backward, so one coherent trajectory runs across the seed. Weak or invalid velocity lets the edge tangent continue. An abrupt velocity reversal terminates that branch.
- Intermediate values blend the two at each integration step. Both routes still seed from a silhouette or beside existing paint. An accepted path must stay on its surface, sustain four integration steps, and cover a minimum span. Use **C** when comparing routes so both start from the same empty canvas.

The default **guideVelocity** reads the projected G-buffer velocity. Replace it with the rotating field in [StackSL recipes](../stacksl/README.md) to see the path steering independently of the objects' true motion. Existing marks continue to follow the G-buffer's world-space displacement.

## Use the luminance quadtree

Field's `FabricatePassLibrary.summaryPyramid` runs over the mixed edge-analysis image. Its 128×128 leaves reduce to one cell using the existing pooled-variance reducer. Each of the eight levels has value, spatial moments and extent in separate RGBA32F textures. Luminance supplies both the value and the mass for the weighted statistics. The signed paint buffer is still available separately.

- **Variance targeting** at zero keeps the usual birth probability. Turning it up favors candidate cells with higher luminance standard deviation. It operates after the edge/frontier test and does not turn scattered flat regions into independent birth sites.
- **Variance thickness** at zero keeps the usual width. Positive values favor thick ribbons in high-variance cells. Negative values reverse the mapping.
- **Variance reference (luma σ)** sets the response scale. The mapping is `1 - exp(-sqrt(variance) / reference)`. The studio reads depth 5, which partitions the image into 32×32 cells. Change `summaryDepth` at the top of **Stroke studio** to use a different level and rerun it.
- The **regionalStyle** StackSL hook can sample value, extent and moments directly. It can also use the supplied variance interest or direction strength. Its return can modulate birth probability, thickness and step length independently.

The recipe's moment and extent planes remain available for orientation experiments. For orientation, divide the spatial moments by the value plane's weight, then subtract the outer product of the centroid from the second-moment matrix. The resulting covariance's major eigenvector gives the cell's mass direction in UV space. Apply the image aspect before using that direction as a pixel-space heading.

## Mix light and paint

The final composite uses the `dust_six` pattern of a sharp render and two separable Gaussian blurs. The medium blur's green channel chooses between sharp, medium and broad. **Green selector gain** moves that choice and **Green blur mix** sets its contribution. **Green reconstruction** can replace some green with half red plus half a second channel. Set **Green reconstruction G to B** to zero for `(R+G)/2` or one for the red/blue reconstruction used in `dust_six`.

**Light to paint** crossfades additive light with an order-independent, coverage-weighted color. Accumulated alpha determines where the crossover takes effect. **Paint crossover alpha** sets its onset. In the weighted branch, RGB is the signed RGB sum divided by accumulated alpha, multiplied by `1 - exp(-alpha)`. This makes dense overlap behave more like a mixture without sorting strokes by depth. At Light to paint zero, the blend is fully additive.

The final composite is downstream of feedback. Edge tracing and pigment error continue to read the raw signed paint and analysis images. This keeps blur, green reconstruction and the light-to-paint crossover available as display controls without changing the stroke population. **compositeTone** receives the signed RGB and accumulated alpha before the canvas and gamut mapping.

## Resolution and geometry

The authored configuration uses a **1792×1120** analysis grid and **3840×2400** paint and composite targets. Paint geometry expands at the paint resolution. The edge-input pass resolves signed RGB and coverage down to the analysis grid, where blur, tensor, velocity, statistics and birth all operate. Analysis step sizes and width are scaled from the original 896-pixel reference grid. The two sizes must share an aspect ratio. Edit **StrokeResolution** in **Stroke types** and start a fresh renderer to change allocation-time dimensions. The native preview size is set independently by the window dimensions in **Stroke renderer**.

The task shader checks 32 slots per group and compacts active stroke IDs. A mesh workgroup emits exactly two vertices per used knot and two triangles per segment. The fixed per-workgroup limits are 18 vertices and 16 triangles for nine knots. Rasterization uses additive RGBA16F blending with depth testing disabled. The editable path shader places ribbons in world space.

This document uses a single desktop camera. The 4K-wide paint size is per-view sizing for this renderer. A stereo implementation would share the stroke pool and run the paint/composite passes for each eye with its own projection. It also needs an explicit policy for which camera produces edge and velocity analysis.

## Views and keys

| Key | Function |
| --- | --- |
| 1 | Final composite |
| 2 | Lit G-buffer |
| 3 | Edge direction and strength |
| 4 | Signed residual |
| 5 | Stroke phases |
| 6 | Optical flow |
| 7 | Signed correction strokes |
| 8 | Scene attachments and velocity |
| 9 | Paint, analysis, direction, and signed color error |
| View 10 | Quadtree mean, range, standard deviation, and orientation |
| View 11 | Sharp paint, two Gaussian targets, and composite |
| C | Clear strokes |
| Space / O | Toggle object motion / camera orbit |
| Left / Right | Step the camera |
| R | Reload GLSL and recompile the current StackSL |

[StackSL inputs and examples](../stacksl/README.md) · [Ambient resources and frame order](../resources/README.md)
