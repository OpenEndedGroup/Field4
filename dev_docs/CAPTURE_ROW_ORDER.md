# Capture row order (the Y convention)

Which way up an image capture comes out, per path, and why.

The short version: **every readback in this repo delivers rows top-down, so no encode path should
flip.** But that is a derived result, not an axiom, and it has been got wrong in both directions —
so the reasoning is written out here, and the table is enforced by a test rather than trusted.

The machine-readable version of this table is
[`CaptureOrientation`](../src/jvmMain/kotlin/afield/four/CaptureOrientation.kt), and
`CaptureOrientationTest` fails the build if a path's code disagrees with its declaration, or if a
new flip-bearing path appears in `afield.four` without being declared.

## Why top-down, for all of them

Two facts, and neither is about a particular capture:

1. **`vkCmdCopyImageToBuffer` delivers rows top-down.** Its buffer addressing is spec-fixed as
   row-major from `imageOffset` with y increasing, so image row 0 lands at the lowest buffer
   address. Every readback in this tree passes `imageOffset(0,0,0)` and `bufferRowLength(0)`, so
   none of them varies this.
2. **Every render path shares one viewport convention** — the negative-height Y-flip, at
   `Renderer.setViewportAndScissor`, `RenderTarget.setViewportAndScissor`,
   `RenderToTexture.setViewportAndScissorArray` and `ImGuiRenderToTexture`. With
   `viewport.y = h, viewport.height = -h`, clip `y = +1` maps to image row 0. That is true for the
   swapchain **and** for every offscreen target, so a swapchain image and a render-to-texture image
   hold their rows in the identical order.

(1) and (2) together mean a render-target readback is top-down for exactly the reason the swapchain
one is. That matters because the swapchain case is the one with an empirical check behind it:
`62fd65d0a` compared it against the live on-screen window. Everything else inherits from that
comparison via (2), which is why (2) is worth stating rather than assuming.

**And (2) is itself guarded.** It is the load-bearing assumption, so
`CaptureOrientation.viewportConventionSites` declares all five `VkViewport` setups in
`afield.four` — `Renderer`, `RenderTarget`, `RenderToTexture`, `OpenXRHmdTarget`,
`ImGuiRenderToTexture` — and the test asserts each really does use a negative height, and fails on
any *undeclared* viewport setup in the package.

This matters more than it looks. A sixth render path added with an ordinary positive-height
viewport would silently falsify half the table while every flip in the tree still matched its
declaration: the table would be self-consistent and wrong, and the only symptom would be one
capture route coming out upside down again. Guarding the encode decisions without guarding their
premise would have left exactly the hole this document exists to close.

The bottom-up case is real but currently unused: a GL-style `glReadPixels` genuinely hands row 0 as
the bottom of the picture. `packRgbaToRgbBottomUp` is kept for it.

## The table

| Path | Symbol | Source rows | Decision | Status |
|---|---|---|---|---|
| Window capture, PNG | `Renderer.writeWindowCapturePng` | top-down | `flipY = false` | correct as of `62fd65d0a` |
| Window capture, JPEG | `Renderer.writeWindowCaptureJpeg` | top-down | `packRgbaToRgbTopDown` | correct as of `62fd65d0a` |
| Texture / RTT preview + 4 docs harnesses | `TexturePreviewCache.capture` | top-down | `flipY = false` | **was inverted, fixed** |
| `RenderToTexture.saveJpgAsync` | `GpuImageCapture.writeJpeg` | top-down | `packRgbaToRgbTopDown` | **was inverted, fixed** |
| JPEG write primitive | `GpuImageCapture.writeJpegRgb` | n/a | none — writes what it is given | correct |
| Virtual-texture thumbnail | `VirtualTextureSystem.writeThumbnailJpeg` | top-down (STB file decode, not a GPU readback) | none | correct |
| Caller-supplied framebuffer → temp PNG | `Window.saveFramebufferToTempFile` | caller's, defaulted to top-down | `flipY = false` | **KDoc was false, fixed**; no callers |
| Caller-supplied framebuffer → base64 | `Window.getFramebufferAsBase64` | caller's, defaulted to top-down | `flipY = false` | same; no callers |
| Rolling snapshot readback | `RollingCapture.copySnapshotToStaging` | top-down | none — hands the raw buffer on | no encoder, no consumers |

### Not in the table on purpose

`ImGuiRenderToTexture.flipYWhenBlittingToScreen` (`Rect(0.0, 1.0, 1.0, -1.0)` as source UVs, read
by the blit fragment shader) is a **texture-sampling** convention on a drawn quad, not a row order
in a buffer. It is the same word on a different axis of the problem and is correctly independent of
everything above. It is a *display-time* flip, which is why an ImGui RTT sampled directly reads
opposite to what the screen shows.

`flipY` in `afield.visualization.heatmap` and `afield.stdlib.imaging` is a user-facing orientation
*option* on a CPU image — which row of a matrix you want at the bottom of a spectrogram. Unrelated,
and deliberately outside the scanner's roots.

## How this went wrong, twice

Worth reading before changing any of it, because both failures were failures of *reasoning from
neighbouring comments* rather than of arithmetic.

**First**, `captureWindowToFile` flipped a top-down readback, so every window PNG and JPEG came out
upside down while the window looked right. `62fd65d0a` fixed it.

**Second**, that same commit asserted in its message that `packRgbaToRgbBottomUp` was "still used by
the offscreen `writeJpeg` path, whose rows genuinely do arrive bottom-up". It is not: `writeJpeg`'s
only caller reads back with the same `vkCmdCopyImageToBuffer` call as the swapchain. So the fix
landed on the path that was already the reference and left both render-target paths inverted — and
`TexturePreviewCache` is the pixel path for the StackSL, RectSL, standard-renderer and ray-tracing
docs harnesses, so the docs site carried Vulkan figures of two opposite handednesses: upright from
the `native-screenshot` route, inverted from every gallery harness.

Meanwhile `Window.saveFramebufferToTempFile`'s KDoc stated the pre-fix convention as a general fact
and told callers *not* to correct it — "if the result looks upside-down you are likely
double-flipping upstream; do not flip again here" — which points the reader at the wrong file. It
had no callers, so it was pure trap.

The common cause is that the convention was per-path and unwritten, so each site was decided by
whatever comment was nearest. Hence
[`CaptureOrientation`](../src/jvmMain/kotlin/afield/four/CaptureOrientation.kt): the row order is
now data, with the reason attached, and adding a path without declaring it fails the build.

## How the flip was measured

Not from the comments — from committed artifacts, which is the only reason the second bug was
findable at all.

**`public/docs/assets/stacksl/stacksl-uv-coordinates.png`**, whose program is `uv saturate` and
whose vertex shader sets `fragUV = pos`, `gl_Position = pos*2-1`, so `uv.y = (clip.y+1)/2` and
`uv.y = 0` belongs at the image bottom. Sampled corners put `uv.y ≈ 0.06` at the **top** and
`≈ 0.94` at the bottom.

**`public/docs/assets/rectsl/rectsl-easing-ladder.png`** at `stillTime: 1.0`, part-way up a rising
keyframe segment (`'( 0 -3  2 3  4 -3 )`). Under any monotone easing, `ease-in` must sit lower in
world y than `ease-out` at the same phase of a rising segment. Colour centroids put ease-in 64 px
*above* centre and ease-out 63 px *below*. X measured correct, so it was a pure Y flip.

That second oracle is the useful pattern to reuse: an asymmetry the *source* fixes, rather than a
judgement about what a picture ought to look like.

## Regenerating the affected assets

Fixing `TexturePreviewCache` inverts the correct output relative to every committed asset produced
through it — the StackSL, RectSL, standard-renderer and ray-tracing galleries, stills and mp4s
alike. Those are **not** regenerated by the fix and are stale until a docs build re-renders them.
