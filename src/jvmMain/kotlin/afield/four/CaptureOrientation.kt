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
     * Paths that are *not* in [paths] because they are not readback→encode paths, kept here so
     * that the next person to grep `flipY` in `afield.four` does not have to re-derive it:
     *
     * - `ImGuiRenderToTexture.flipYWhenBlittingToScreen` is a texture-sampling convention on a
     *   blitted quad (`Rect(0.0, 1.0, 1.0, -1.0)` as source UVs), not a row order in a buffer.
     *   It is a *different* axis of the same word and correctly independent of this table.
     */
    val deliberatelyExcluded: List<String> = listOf(
        "ImGuiRenderToTexture.flipYWhenBlittingToScreen"
    )
}
