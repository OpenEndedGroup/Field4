package afield.four

/**
 * The row order a pixel source hands to an encoder.
 *
 * Not a property of the encoder, and not a property of the file format — a property of the
 * *readback*. Naming it is the whole point: "flip Y" on its own says what a line of code does and
 * not what it is for, which is how [BottomUp] came to be applied to two top-down sources.
 */
internal enum class RowOrder {
    /** Row 0 of the buffer is the top row of the picture. */
    TopDown,

    /** Row 0 of the buffer is the bottom row of the picture, GL `glReadPixels`-style. */
    BottomUp
}

/**
 * One readback→encode path, with the row order its source actually delivers and why.
 *
 * [file] and [symbol] are not decoration: [CaptureOrientationTest] uses them to read the real
 * source back and check that the flip written at the site agrees with [sourceRowOrder]. A spec
 * whose [symbol] no longer exists, or whose site carries the other flip, fails the build.
 */
internal data class CapturePathSpec(
    /** Stable short name, used in test failure messages. */
    val id: String,
    /** Repo-relative path of the file carrying the flip decision. */
    val file: String,
    /** The function whose body carries the flip decision. */
    val symbol: String,
    /** The row order the *source* of this path delivers to the encoder. */
    val sourceRowOrder: RowOrder,
    /**
     * The exact flip token the site must contain, given [sourceRowOrder]. `null` means the path
     * makes no orientation decision (it hands raw rows onward) and must contain no flip token.
     */
    val expectedToken: String?,
    /** Why the source delivers [sourceRowOrder]. The reason, not a restatement. */
    val why: String
)

/**
 * Every readback→encode path in `afield.four`, and the row order each one's source delivers.
 *
 * ## Why this exists as data
 *
 * The Y convention here is **per path, not universal**. Most of the paths below agree, but only
 * because they happen to share one Vulkan idiom — which is exactly the sort of coincidence that
 * invites a blanket rule, and a blanket flip applied to all of them would be a regression rather
 * than a simplification. What each path needs is a reason, which is what [CapturePathSpec.why]
 * holds.
 *
 * `62fd65d0a` fixed the swapchain path and, in its own commit message, asserted that the offscreen
 * `writeJpeg` path's "rows genuinely do arrive bottom-up". They do not: `writeJpeg`'s only caller
 * reads back with the same `vkCmdCopyImageToBuffer` call as the swapchain. The claim was wrong
 * because nothing made it checkable. That is what this table is for.
 *
 * ## The two facts every entry below rests on
 *
 * 1. **`vkCmdCopyImageToBuffer` delivers rows top-down.** Buffer addressing is spec-fixed as
 *    row-major from `imageOffset` with y increasing, so image row 0 lands at the lowest buffer
 *    address. Every readback in this tree passes `imageOffset(0,0,0)` and `bufferRowLength(0)`.
 * 2. **Every render path shares one viewport convention** — the negative-height Y-flip, at
 *    `Renderer.setViewportAndScissor`, `RenderTarget.setViewportAndScissor`,
 *    `RenderToTexture.setViewportAndScissorArray` and `ImGuiRenderToTexture`. So clip `y = +1`
 *    maps to image row 0 for the swapchain *and* for every offscreen target: a swapchain image
 *    and a render-to-texture image hold their rows in the identical order.
 *
 * Together those mean a render-target readback is top-down for the same reason the swapchain one
 * is, and `62fd65d0a`'s empirical check of the swapchain against the on-screen window carries over.
 *
 * ## Adding a path
 *
 * Declare it here. [CaptureOrientationTest] scans the `afield.four` tree for flip-bearing encode calls
 * and fails on any it cannot find in this list, so an undeclared path does not compile past CI.
 */
internal object CaptureOrientation {

    /** Token for "preserve the source's row order" at a [afield.util.PngEncoder] call. */
    const val PNG_PRESERVE = "flipY = false"

    /** Token for "invert the source's row order" at a [afield.util.PngEncoder] call. */
    const val PNG_INVERT = "flipY = true"

    /** Token for "preserve the source's row order" at a JPEG pack call. */
    const val JPEG_PRESERVE = "packRgbaToRgbTopDown"

    /** Token for "invert the source's row order" at a JPEG pack call. */
    const val JPEG_INVERT = "packRgbaToRgbBottomUp"

    /** A raw JPEG write. Makes no orientation decision of its own; declared so new ones surface. */
    const val JPEG_WRITE = "stbi_write_jpg"

    /**
     * Source roots the completeness scan covers. Deliberately only `afield.four`: `flipY` also
     * appears in `afield.visualization.heatmap` and `afield.stdlib.imaging`, where it is a
     * user-facing *orientation option* on a CPU image and has nothing to do with a GPU readback.
     * Widening the scan to those would make it assert a convention they do not share.
     */
    val scannedRoots: List<String> = listOf("src/jvmMain/kotlin/afield/four")

    val paths: List<CapturePathSpec> = listOf(
        CapturePathSpec(
            id = "window-png",
            file = "src/jvmMain/kotlin/afield/four/Renderer.kt",
            symbol = "writeWindowCapturePng",
            sourceRowOrder = RowOrder.TopDown,
            expectedToken = PNG_PRESERVE,
            why = "Swapchain image read back with vkCmdCopyImageToBuffer. Fixed and checked " +
                    "against the on-screen window by 62fd65d0a."
        ),
        CapturePathSpec(
            id = "window-jpeg",
            file = "src/jvmMain/kotlin/afield/four/Renderer.kt",
            symbol = "writeWindowCaptureJpeg",
            sourceRowOrder = RowOrder.TopDown,
            expectedToken = JPEG_PRESERVE,
            why = "Same swapchain readback as window-png, encoded as JPEG instead."
        ),
        CapturePathSpec(
            id = "texture-preview",
            file = "src/jvmMain/kotlin/afield/four/FourVisualizers.kt",
            symbol = "capture",
            sourceRowOrder = RowOrder.TopDown,
            expectedToken = PNG_PRESERVE,
            why = "vkCmdCopyImageToBuffer from an arbitrary render target, optionally via " +
                    "vkCmdBlitImage whose srcOffsets(0) and dstOffsets(0) are both (0,0,0), so the " +
                    "downsample does not invert Y either. Shares the negative-height viewport with " +
                    "the swapchain, so top-down for the same reason. This is the pixel path for the " +
                    "StackSL, RectSL, standard-renderer and ray-tracing docs harnesses, so it and " +
                    "window-png must agree or the docs site carries figures of both handednesses."
        ),
        CapturePathSpec(
            id = "render-to-texture-jpeg",
            file = "src/jvmMain/kotlin/afield/four/GpuImageCapture.kt",
            symbol = "writeJpeg",
            sourceRowOrder = RowOrder.TopDown,
            expectedToken = JPEG_PRESERVE,
            why = "Only caller is RenderToTexture.completeFileSave, whose readback at " +
                    "RenderToTexture.recordPendingFileSaves is the same vkCmdCopyImageToBuffer " +
                    "call as the swapchain path. Reached from user code through " +
                    "RenderToTexture.saveJpgAsync."
        ),
        CapturePathSpec(
            id = "jpeg-write-primitive",
            file = "src/jvmMain/kotlin/afield/four/GpuImageCapture.kt",
            symbol = "writeJpegRgb",
            sourceRowOrder = RowOrder.TopDown,
            expectedToken = JPEG_WRITE,
            why = "The shared stbi_write_jpg call. It makes no orientation decision — it writes " +
                    "whatever the packer handed it, and stbi_flip_vertically_on_write is never " +
                    "called anywhere in this repo. Declared so that a *new* JPEG writer added " +
                    "elsewhere in afield.four has to come here and say which way its rows run."
        ),
        CapturePathSpec(
            id = "virtual-texture-thumbnail",
            file = "src/jvmMain/kotlin/afield/four/texture/VirtualTextureSystem.kt",
            symbol = "writeThumbnailJpeg",
            sourceRowOrder = RowOrder.TopDown,
            expectedToken = JPEG_WRITE,
            why = "Not a GPU readback at all: the rows come from STB decoding an image file, " +
                    "which yields top-down, and go straight to stbi_write_jpg unflipped. Correct " +
                    "as-is, and listed so it is not mistaken for an unaudited capture path."
        ),
        CapturePathSpec(
            id = "window-framebuffer-tempfile",
            file = "src/jvmMain/kotlin/afield/four/util/Window.kt",
            symbol = "saveFramebufferToTempFile",
            sourceRowOrder = RowOrder.TopDown,
            expectedToken = PNG_PRESERVE,
            why = "Takes pixels from the caller, so it has no readback of its own to reason " +
                    "about. It has no callers at all in this repo; the default matches the " +
                    "in-tree Vulkan readbacks so that the first caller to arrive is right by " +
                    "default, and the parameter lets a genuinely bottom-up source say so."
        ),
        CapturePathSpec(
            id = "window-framebuffer-base64",
            file = "src/jvmMain/kotlin/afield/four/util/Window.kt",
            symbol = "getFramebufferAsBase64",
            sourceRowOrder = RowOrder.TopDown,
            expectedToken = PNG_PRESERVE,
            why = "Base64 twin of window-framebuffer-tempfile, same reasoning, also with no " +
                    "callers in this repo."
        ),
        CapturePathSpec(
            id = "rolling-capture",
            file = "src/jvmMain/kotlin/afield/four/targets/RollingCapture.kt",
            symbol = "copySnapshotToStaging",
            sourceRowOrder = RowOrder.TopDown,
            expectedToken = null,
            why = "vkCmdCopyImageToBuffer like the others, so top-down, but it encodes nothing " +
                    "— it hands the raw mapped ByteBuffer to an onFrame callback and makes no " +
                    "orientation decision. Listed so that the day it grows an encoder, the row " +
                    "order it is handing over is already written down."
        )
    )

    /**
     * The *other* axis of the same word: how a shader turns a clip position into a **texture
     * coordinate**. Not a row order in a buffer, so not in [paths] — but it shares [paths]'
     * premise, and getting it wrong produces the identical symptom, so it is written down here
     * rather than left for the next person to re-derive.
     *
     * The rule follows from the negative-height viewport alone. Clip `y = +1` is image row 0, and
     * sampler `v = 0` is image row 0, so a pass sampling a render target must use
     * `v = 0.5 - clip.y * 0.5` — **texel-aligned**, v running *opposite* to clip y. The GL-style
     * `v = clip.y * 0.5 + 0.5` reads the row it is writing to `v = 0` from `v = 1`: one vertical
     * mirror, per pass.
     *
     * Per pass is what makes it nastier than a row order. A single wrong flip in an encoder is
     * uniformly wrong and therefore findable; a wrong flip in a *chained* fullscreen pass makes the
     * output orientation depend on the **parity** of the chain, so the same renderer emits both
     * handednesses depending on how many post effects a scene happens to add. The standard
     * renderer's built-in chain — lighting, transparency copy, motion blur, FXAA — carried four
     * such mirrors and cancelled; `fabricate-post-stack-vignette.png` added one
     * `addShaderEffect` pass and was committed upside down relative to the other eleven figures on
     * its page, and `ImGuiRenderToTexture` cancelled `blitVertexShader`'s mirror locally with a
     * `Rect(0.0, 1.0, 1.0, -1.0)` source crop. Three sites, three different local repairs, no
     * shared statement of the rule.
     *
     * Sites now texel-aligned, all with the reason at the site:
     * - `StandardRenderer.fullscreenVertex` — the whole deferred chain plus every post effect.
     * - `RenderToTexture.blitVertexShader` and `segmentedBlitVertexShader` — RTT to swapchain.
     * - `RayTracingDocsHarness.RT_IMAGE_BLIT_VERTEX` — storage image to swapchain.
     * - `StandardRenderer.aoFragment`'s `screenUvFromNdc`, and `lightingFragment`'s inverse of it.
     *
     * Deliberately **not** texel-aligned, because it samples nothing:
     * `StackSLGalleryHarness.FULLSCREEN_VERTEX` emits GL-style `fragUV = pos` and feeds it to a
     * StackSL program, whose `uv.y = 0` means the bottom of the picture. The convention belongs to
     * the use, not to the fullscreen triangle — which is the distinction the three repairs above
     * were each groping at separately.
     */
    val samplingConventionNote: String =
        "clip y = +1 is image row 0, so a pass sampling a render target uses v = 0.5 - clip.y * 0.5"

    /**
     * Every place `afield.four` sets up a `VkViewport`.
     *
     * This list is not about encoding at all — it is the **premise** every `TopDown` row above
     * rests on, and it is here because it was the one load-bearing assumption with nothing
     * checking it.
     *
     * Only the swapchain capture has ever been compared against a real on-screen window
     * (`62fd65d0a`). Every other path inherits that comparison through a syllogism: if a render
     * target is rendered through *the same* viewport convention as the swapchain, then its image
     * holds rows the same way round, so the same readback yields the same order. Today all five
     * sites below use the negative-height Y-flip (`viewport.y = y + h`, `viewport.height = -h`),
     * which maps clip `y = +1` to image row 0, so the syllogism holds.
     *
     * A sixth render path added with an ordinary positive-height viewport would silently falsify
     * half of [paths] while every flip in the tree still matched its declaration — the table would
     * be self-consistent and wrong. `CaptureOrientationTest` therefore checks these too, and fails
     * on any undeclared `VkViewport` setup in `afield.four`.
     */
    val viewportConventionSites: List<ViewportConventionSite> = listOf(
        ViewportConventionSite(
            file = "src/jvmMain/kotlin/afield/four/Renderer.kt",
            symbol = "setViewportAndScissor",
            why = "The swapchain path — the one case checked against a live window, and so the " +
                    "reference every other row in the table is derived from."
        ),
        ViewportConventionSite(
            file = "src/jvmMain/kotlin/afield/four/targets/RenderTarget.kt",
            symbol = "setViewportAndScissor",
            why = "Base render target. Matches the swapchain, which is what makes a render-target " +
                    "readback top-down."
        ),
        ViewportConventionSite(
            file = "src/jvmMain/kotlin/afield/four/targets/RenderToTexture.kt",
            symbol = "setViewportAndScissorArray",
            why = "Offscreen targets, including every docs-gallery capture and " +
                    "RenderToTexture.saveJpgAsync. The two paths this branch fixed both read images " +
                    "rendered here."
        ),
        ViewportConventionSite(
            file = "src/jvmMain/kotlin/afield/four/targets/OpenXRHmdTarget.kt",
            symbol = "setViewportAndScissor",
            why = "XR eye targets. Not currently captured to a file, but it shares the convention, " +
                    "so a capture added here would be top-down like the rest."
        ),
        ViewportConventionSite(
            file = "src/jvmMain/kotlin/afield/four/imgui/ImGuiRenderToTexture.kt",
            symbol = "renderAdditional",
            why = "ImGui's own RTT. Spells the flip inline rather than through a Rect, but is the " +
                    "same convention — which is why an ImGui RTT read back directly is top-down " +
                    "like any other. It used to carry a flipYWhenBlittingToScreen flag as well; " +
                    "that was a display-time sampling flip cancelling a mirror in " +
                    "blitVertexShader, and both are gone. See samplingConventionNote."
        )
    )
}

/**
 * One `VkViewport` setup, declared so that the row-order table's premise is checkable.
 *
 * [CaptureOrientationTest] requires the function named by [symbol] to contain a `VkViewport`
 * whose height is negative.
 */
internal data class ViewportConventionSite(
    /** Repo-relative path of the file setting up the viewport. */
    val file: String,
    /** The function that sets it up. */
    val symbol: String,
    /** What depends on this site sharing the convention. */
    val why: String
)
