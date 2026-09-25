# Optical flow

Optical flow turns two images into a motion field you can use in another shader. It follows visible image patterns, so it can work on video, rendered geometry, or a changing procedural texture. It does not know which patterns belong to a real moving object.

![Consecutive deforming images, measured motion, hardware cost, and feedback advection](assets/optical_flow/advection.png)

## Open the experiment

Open **Starting points → optical_flow**. The top row shows the two input images. Below them are a color wheel and arrows for motion, a grayscale strip labelled **COST**, and a feedback image carried by that motion.

**Consecutive-frame wiggle** compares two frames of the same deforming image, 1/30 second apart. There is no added global translation: arrows should bend across the image and reverse as the source wiggles. Their lengths are normalized for readability; color intensity represents speed, with full intensity at 3 input pixels per pair.

Turn off the wiggle to run the separate **known translation** check: exactly 12 pixels right and 8 pixels up. That setting should produce a mostly uniform up-right field. **Advection gain**, **Feedback retention**, and **Reject cost above** control how the measured field moves the feedback image. Reset clears feedback and the engine's temporal hints.

![Optical flow controls](assets/optical_flow/controls.png)

<video controls muted loop playsinline preload="metadata" src="assets/optical_flow/advection.mp4" width="1920" height="1080" style="max-width:100%;height:auto;aspect-ratio:16/9"></video>

The wiggle is checked against the known local deformation, not just against a global shift. Across 85,696 interior samples at four animation phases, median endpoint error is **0.15 input pixels** and p95 is **0.35 pixels**. Measured vectors occupy all four direction quadrants. The identical-frame check stays below 0.25 pixel everywhere. These checks would fail if the output remained the old constant up-right field.

??? aside "Why the earlier clip mostly pointed up and right"

    The earlier pair added a fixed (+12, −8) displacement to a much smaller temporal wiggle. The measured field followed that dominant offset correctly, but the clip was a poor demonstration of local deformation. The revised default removes that offset and advances by exactly the pair interval; the feedback image now follows consecutive source frames.

The **COST** strip displays the hardware's matching cost at those same image coordinates. Its **0 100+** scale runs from black (low cost) to white (100 or above). It occupies the rightmost 15% of the flow panel; it is not a compressed cost map of the whole image. The **Reject cost above** control uses this same signal when deciding whether to carry the previous image or take the fresh source at that pixel.

The field can be useful even when the correspondence is imperfect. Repetition, newly revealed regions, lighting changes, and abrupt procedural edits all make it less reliable. The cost strip exposes some of that uncertainty; hardware cost is not a calibrated probability or confidence score.

## What goes in, and what comes out

`OpticalFlow(renderer, firstImage, secondImage, grid = 4)` creates a reusable operation. Its two image arguments have type **`HasImageView`**, Field's interface for a GPU image view. **A `RenderToTexture` implements that interface**, so two RTTs can be passed directly:

```diffstatic
// firstTarget and secondTarget are already-rendered RTTs of the same size.
val flow = OpticalFlow(renderer, firstTarget, secondTarget, grid = 4)

// After the input producers have submitted, between Field frames:
flow.evaluate(reset = cameraCut)

// Publish the result for ordinary texture lookup by name.
prop<RenderToTexture>(motion) = flow.output
```

| Argument or result | Meaning |
| --- | --- |
| `renderer` | Owns the Vulkan device and submission resources; both inputs must belong to that device. |
| `firstImage` | Earlier image. An RTT passed directly supplies its first color attachment. |
| `secondImage` | Later image, with the same width and height. |
| `grid = 4` | One flow texel per 4 × 4 input pixels. A 960 × 540 pair produces a 240 × 135 field. Supported grids are checked on the device. |
| `quality` | Optional `OpticalFlow.Quality.FAST`, `MEDIUM`, or `SLOW` (the default). |
| `flow.output` | An owned **`RenderToTexture`**, containing a sampled RGBA32F motion texture. |

For two attachments of one RTT, select them explicitly:

```diffstatic
val flow = OpticalFlow(renderer,
    pair.imageViewArray[0], pair.imageViewArray[1], grid = 4)
```

The inputs are borrowed: keep them alive while the operation uses them. `evaluate()` reads their current contents; it neither renders them nor keeps a previous input frame for you. Recreate the operation when dimensions, grid, quality, or input resources change. Call `flow.close()` when finished, after removing consumers of its output.

Inputs are opaque linear RGB images of equal size. A GPU pass applies the same tone map and gamma conversion to each before making the engine's BGRA8 inputs. The engine's signed fixed-point vectors are unpacked on the GPU. None of the images cross the CPU boundary during evaluation.

## Sample the motion as a texture

**`flow` is the operation; `flow.output` is the texture.** Publishing the latter as `prop<RenderToTexture>(motion)` lets a `moduleHere()` in this box or a child resolve `sampler2D motion` by name, just like a color RTT. A fragment or compute shader can read it with:

```diffstatic
layout(set=0,binding=0) uniform sampler2D motion;

// In your shader, at normalized image coordinates uv:
vec4 field = texture(motion, uv);
vec2 displacementPixels = field.xy;
float matchingCost = field.z;
```

XY is **first-image → second-image displacement in input pixels**: X points right and Y points down. Z is raw matching cost, where lower is better; W is 1. The vectors remain in input pixels even though the texture has fewer texels. Divide by the **input image size**, not the motion-texture size, to obtain a UV displacement. This is numerical data: do not apply color grading or gamma conversion to the motion field.

The current adapter submits to a dedicated optical-flow queue and waits for a final fence before returning. Call `evaluate()` **between frames, after the producers have submitted**, rather than inside a `Target.render` callback that is already recording a frame. Its output is ready for the following Field rendering. It does not yet overlap several image pairs; account for the wait in your frame budget.

## Keep the previous frame with ping-pong

Open **Starting points → optical_flow_pingpong** for the complete editable example. The parent box renders one `moduleHere()` into a `PingPongRenderToTexture`; its child samples the previous image, current image, and motion texture. The four panels show **previous / current** above **motion / matching cost**.

![One editable shader, retained previous/current frames, and the motion texture sampled by another module](assets/optical_flow/pingpong.png)

```diffstatic
prop<PingPongRenderToTexture>(history) = PingPongRenderToTexture(renderer) {
    width = 960; height = 540; useDepth = false
    format = VK_FORMAT_R16G16B16A16_SFLOAT
}

val flow = OpticalFlow(renderer,
    history.previousImageView, history.latestImageView, grid = 4)
prop<RenderToTexture>(motion) = flow.output
```

Those two image-view providers follow the ring as it rotates; keep the providers themselves, rather than caching their `.imageView` handles. After each source render, `latestImageView` is the new completed frame and `previousImageView` is the one before it. No manual swap is needed.

The example's frame loop is:

```diffstatic
// renderSource() is a helper defined in the example: it records history.render(),
// submits that command buffer, and waits for the source to finish.
if (reset) renderSource()   // Seed both slots with the same time on startup/reset.
renderSource()
flow.evaluate(reset = reset || !animate)
frame()                    // The child now samples the completed motion texture.
```

Edit the parent's **fragment** to change the source; edit the child's **fragment** to use the motion. **Animate source** pauses time: both slots receive the paused frame, and motion should settle near zero. Repeated paused pairs disable temporal hints on every evaluation. **Reset the pair** seeds both slots and disables hints for that pair. A successful source shader edit does the same, so unrelated old and new patterns are not treated as consecutive animation frames. When relaunching the parent, relaunch the child too so its texture bindings follow the new allocation.

This source also publishes `expectedMotion`, its analytical displacement in input pixels. The live check compares the measured interior field against it through movement, pause, reset, and relaunch. The repeated circles still admit occasional wrong matches: bright patches in the motion panel coincide with higher matching cost. Inspect that cost before using a vector for feedback; the example deliberately shows the raw result.

## Measured on this machine

On the RTX 4090, the 960 × 540 translation test has **zero median error** and **1/32 pixel p95 error**, measured away from the image borders. At a 4 × 4 grid, median GPU costs are 0.039 ms for conversion, 0.523 ms for optical flow, and 0.083 ms for unpacking. The complete call, including submissions and the final fence, takes 1.20 ms median / 1.56 ms p95. These are 100 warmed measurements using SLOW quality; the adapter owns about 10.6 MB of visible allocations. Driver-private session memory is not included.

The [measurements](assets/optical_flow/measurements.json) retain the distribution summaries and numerical check. The implementation uses `VK_NV_optical_flow` through the existing LWJGL bindings, with no additional native library. Feature, format, grid, and queue checks decide availability; a missing extension does not affect other Field workflows.

Use this as an image-processing primitive. Engine motion vectors for [DLAA/SR](neural_reconstruction.md) or [RR](ray_reconstruction.md) have a different contract and should not silently be replaced with optical flow.
