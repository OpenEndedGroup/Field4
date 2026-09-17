package afield.four.targets

import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Matrix4fc
import afield.stdlib.linalg.Vector4f

/**
 * One render target's **frame history**: where the camera was last frame, and whether that answer is
 * usable.
 *
 * ### The gap this fills
 *
 * A pass can already read last frame's pixels. `FabricatePassSpec.persistent` gives it a ping-pong
 * target and `FabricatePassInput(..., previousFrame = true)` binds the other half of the pair, which
 * is everything a *simulation* needs: a stable fluid's velocity field is a function of position, and
 * position is where it already is. What that does not give you is **correspondence** — for a pixel on
 * screen now, which pixel was it then. Temporal antialiasing, motion blur, reprojection-based
 * denoise and temporal upscaling are all the same question asked of a camera that moved, and none of
 * them is expressible by reading last frame at the same UV.
 *
 * ### Why this is a type and not two matrices
 *
 * Because reprojection has two halves and only one of them is visible.
 *
 * "Where was this pixel last frame" is [reprojection] — a matrix multiply that is either right, or
 * produces an image so obviously smeared that nobody ships it. "*Is* there a last frame for this
 * pixel" is [available], and when that is wrong the image looks **entirely fine**: it blends against
 * a buffer that happens to hold a perfectly good picture of the wrong thing. A frame the renderer
 * dropped, a window resized between two captures, a camera that cut, a scene edit — every one of
 * those leaves history that reads cleanly and means nothing.
 *
 * So the validity rule is the product here, and it has teeth. [available] is false unless **all** of:
 *
 * - this is not the first capture (there is nothing behind frame zero);
 * - [frameId] is exactly one more than the last one captured — not merely *different* from it;
 * - the size is unchanged since the last capture;
 * - the epoch token is the **same object** as the last capture's;
 * - [invalidate] has not been called since.
 *
 * The `frameId + 1` clause is the one that is easy to get wrong and expensive to notice.
 * `standardRenderer.kt`'s private `FrameStableHistory` advances on `frame != lastFrameSeen`, so a
 * dropped frame leaves it holding a value two or more frames old and saying nothing about it — which
 * for its own consumer (a motion-blur streak) is a cosmetic error, and for an accumulating history
 * buffer is a permanent one.
 *
 * ### What a shader actually gets
 *
 * [reprojection] is `previous · inverse(current)`: it takes a **current clip position straight to
 * the previous clip position**, so a pass needs one matrix rather than three and one multiply rather
 * than two. That is not only convenience — it is what keeps this inside a push constant. A `mat4` is
 * 64 bytes and Vulkan's *guaranteed* `maxPushConstantsSize` is 128, so sending current, previous and
 * inverse separately (192 bytes, before the pass's own constants) is off the edge of the guarantee
 * on any device that only meets it. One composed matrix plus a `vec4` of [params] is 80 bytes and
 * fits everywhere. [current], [previous] and [inverse] are still kept, because the debug views and
 * any pass that wants to unproject to world space need them, and because [packed] carries all four
 * for a caller that would rather use a uniform buffer.
 *
 * When history is unavailable, [previous] is set equal to [current], so [reprojection] is the
 * identity. A shader that ignores [available] therefore degrades to a zero-motion read of the same
 * UV rather than to whatever happened to be in the matrix — wrong, but wrong in the boring direction.
 */
class FrameHistory {

    /** This frame's view-projection. */
    val current = Matrix4f()

    /** Last frame's view-projection, or a copy of [current] when [available] is false. */
    val previous = Matrix4f()

    /** `inverse(current)` — current clip to world, for a pass that unprojects. */
    val inverse = Matrix4f()

    /**
     * `previous · inverse(current)` — current clip position to previous clip position.
     *
     * The identity when [available] is false, by construction.
     */
    val reprojection = Matrix4f()

    /** True if [previous] describes the immediately preceding frame of the same target and scene. */
    var available = false
        private set

    /** The frame id of the last [capture], or [Long.MIN_VALUE] before the first one. */
    var frameId = Long.MIN_VALUE
        private set

    /** Width at the last [capture]. */
    var width = 0
        private set

    /** Height at the last [capture]. */
    var height = 0
        private set

    /** Alias for [frameId], matching the private prototype this was generalised from. */
    val serial: Long get() = frameId

    /**
     * Four matrices then four scalars, ready for a uniform buffer.
     *
     * Laid out by the `PACKED_*` offsets rather than by convention, because the one thing a caller
     * cannot debug from a picture is a layout that is off by one matrix.
     */
    val packed = FloatArray(PACKED_FLOATS)

    private var epoch: Any? = null
    private var reset = true

    /**
     * Drop the history at the next [capture].
     *
     * The escape hatch for everything the rule cannot see from a frame id and a size: a camera cut, a
     * scene edit, a material change, a lighting change — anything that makes last frame's pixels a
     * picture of something else. Cheap, so call it whenever in doubt; one call costs one frame of
     * accumulation and a missed call costs a ghost that persists.
     */
    fun invalidate() {
        reset = true
    }

    /**
     * Advance to [frameId], and report whether history is now [available].
     *
     * Capturing the frame it already stands at is a **no-op** that returns the same answer. That
     * matters: every pass in a chain that wants history calls this, and if the second call advanced,
     * the chain's later passes would reproject against its earlier passes rather than against last
     * frame, and the history would never be more than one *pass* old.
     *
     * @param frameId a monotonically increasing render-frame counter — `Renderer.frameCounter`, not a
     *   swapchain image index and not a simulation step.
     * @param viewProjection this frame's combined projection · view.
     * @param epoch an identity token for "the same scene and the same camera continuity". Compared
     *   with `===`, not `==`: its job is to be a token somebody swaps, not a value somebody compares.
     * @return [available], for a caller that would rather branch than re-read the field.
     */
    fun capture(frameId: Long, viewProjection: Matrix4fc, width: Int, height: Int, epoch: Any): Boolean {
        require(width > 0 && height > 0) { "frame history needs a positive size, got ${width}x$height" }
        if (frameId == this.frameId) return available

        available = !reset &&
                this.epoch === epoch &&
                frameId == this.frameId + 1 &&
                this.width == width &&
                this.height == height

        previous.set(if (available) current else viewProjection)
        current.set(viewProjection)
        inverse.set(current).invert()
        reprojection.set(previous).mul(inverse)

        for (i in 0 until 16) {
            packed[PACKED_CURRENT + i] = current[i]
            packed[PACKED_PREVIOUS + i] = previous[i]
            packed[PACKED_INVERSE + i] = inverse[i]
            packed[PACKED_REPROJECTION + i] = reprojection[i]
        }
        packed[PACKED_WIDTH] = width.toFloat()
        packed[PACKED_HEIGHT] = height.toFloat()
        packed[PACKED_AVAILABLE] = if (available) 1f else 0f
        packed[PACKED_RESERVED] = 0f

        this.width = width
        this.height = height
        this.epoch = epoch
        this.frameId = frameId
        reset = false
        return available
    }

    /**
     * `(width, height, available, 0)` — the companion `vec4` to [reprojection] in a push constant.
     *
     * Size travels with availability because a pass that reprojects needs both and neither is worth
     * its own constant: the size converts a clip position to a texel so a pass can widen its
     * rejection test to a neighbourhood, and `z` is the flag that says whether to bother.
     */
    fun params(): Vector4f = Vector4f(width.toFloat(), height.toFloat(), if (available) 1f else 0f, 0f)

    override fun toString(): String =
        "FrameHistory(frame=$frameId, ${width}x$height, available=$available)"

    companion object {

        /** Offset of [current] in [packed]. */
        const val PACKED_CURRENT = 0

        /** Offset of [previous] in [packed]. */
        const val PACKED_PREVIOUS = 16

        /** Offset of [inverse] in [packed]. */
        const val PACKED_INVERSE = 32

        /** Offset of [reprojection] in [packed]. */
        const val PACKED_REPROJECTION = 48

        /** Offset of the width scalar in [packed]. */
        const val PACKED_WIDTH = 64

        /** Offset of the height scalar in [packed]. */
        const val PACKED_HEIGHT = 65

        /** Offset of the availability flag in [packed], 1.0 or 0.0. */
        const val PACKED_AVAILABLE = 66

        /** Offset of the reserved scalar in [packed], which keeps the tail `vec4`-aligned. */
        const val PACKED_RESERVED = 67

        /** Floats in [packed]: four `mat4` then one `vec4`. */
        const val PACKED_FLOATS = 68
    }
}
