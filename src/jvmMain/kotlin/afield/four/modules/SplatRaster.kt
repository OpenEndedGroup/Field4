@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.four.modules

import afield.four.fabricated.GaussianSplatRenderer
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import afield.util.PngEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A Gaussian splat cloud rasterized on the **CPU**, into pixels you can look at without a GPU.
 *
 * The reason to have this at all: until now the only way to see a splat in Field was to attach a
 * GPU and look at a window. `SplatFusedPackTest` and `SplatTransportTest` check *numbers* —
 * positions, covariances, play-head arithmetic — and every one of them would still pass if the
 * shader drew the cloud inside out. A headless test can now assert that a splat at a known world
 * position lands on a known pixel in a known colour, a docs build can capture a splat without a
 * graphics context, and a 126-frame capture can be looked at as a contact sheet
 * ([SplatContactSheet]) instead of one window at a time.
 *
 * ## This is a transcription, not a second renderer
 *
 * Everything below is a line-by-line port of [GaussianSplatRenderer.splatVertexShader] and
 * [GaussianSplatRenderer.splatFragmentShader]: the view-space covariance congruence, the projective
 * Jacobian, the 0.3 screen-space dilation, the eigen-decomposition into an oriented 3σ quad, the
 * `minScreenRadius`/`maxScreenRadius` clamps, the `colorGamma` decode and premultiplied Over. The
 * same [GaussianSplatRenderer.Config] numbers therefore mean the same things, and
 * `cloud.sizeScale = 2.5f` and `RasterConfig(sizeScale = 2.5f)` frame the cloud identically.
 *
 * Crucially it **consumes [GaussianSplatRenderer]'s own 16-float GPU layout** — the buffer
 * [GaussianSplatRenderer.packInto] writes — rather than decoding the compressed payload again. That
 * was a deliberate choice over the obvious alternative of decoding
 * [CompressedSplatPly.RawSplats] here. A second decoder would make "the CPU and the GPU are looking
 * at the same cloud" a claim about two decoders staying in step, which is exactly the kind of claim
 * that rots silently. There is one decoder; this reads its output. What is left to test is whether
 * this reads each of the sixteen slots for the purpose the shader reads it for, and `SplatRasterTest`
 * tests that directly, by perturbing one slot at a time and requiring the image to move.
 *
 * ## Where it deliberately differs from the GPU
 *
 * - **Accumulation precision.** A `B8G8R8A8_SRGB` framebuffer quantizes to eight bits per channel
 *   between fragments; this accumulates in `Float`. On a cloud hundreds of splats deep the CPU
 *   result is the *more* accurate of the two, by a fraction of a code value per splat.
 * - **No depth test.** Neither does the GPU path — `depthTest = false` at
 *   `GaussianSplatRenderer.kt:298` — so this is agreement, not divergence. Order is everything, which
 *   is what [DepthOrder] is about.
 * - **Coverage is by pixel centre.** The GPU rasterizes the 3σ quad with its own fill rules; this
 *   samples the quad at pixel centres. Along a quad edge the two can disagree by one pixel, at an
 *   alpha that the 3σ cutoff has already driven to `exp(-4.5) ≈ 0.011` of the splat's opacity.
 *
 * ## The Y convention, which is not a coin flip
 *
 * Field's Vulkan viewport is set with a **negative height** —
 * `.y(rect.y + rect.h).height(-rect.h)` at `Renderer.setViewportAndScissor`,
 * `Renderer.kt:1824-1829` — so NDC `+y` lands at the **top** of the image and the mapping is
 * `row = (1 - ndc.y) * 0.5 * height`. That is the GL convention, not raw Vulkan's, and getting it
 * backwards is the single easiest way to produce a plausible-looking wrong picture.
 *
 * This writes into an `IntArray` and encodes through [PngEncoder.encode], which takes row 0 as the
 * first scanline and has **no** `flipY` parameter to get wrong. There is no default being leaned on
 * here. `SplatRasterTest.checkOrientation` pins it anyway, with four corner-marked splats and an
 * assertion per corner, because a derivation from a viewport call is evidence and a corner-marked
 * image is proof.
 *
 * @see SplatContactSheet for a whole sequence at once.
 * @see SplatCloudEdit for reading, editing and writing clouds on the CPU.
 */
object SplatRaster {

    private const val STRIDE = GaussianSplatRenderer.SPLAT_FLOAT_STRIDE

    /** The screen-space covariance dilation the shader adds to `c00` and `c11`. */
    const val SCREEN_DILATION = 0.3f

    /** How many σ the quad spans, matching `vGaussianCoord = corner * 3.0`. */
    const val QUAD_SIGMAS = 3f

    // ------------------------------------------------------------------------
    // View
    // ------------------------------------------------------------------------

    /**
     * Everything the shader gets about where the camera is: the two push-constant matrices and the
     * viewport size. Hand it the *same* matrices a [GaussianSplatRenderer.Cloud] would
     * (`camera.modelView()` and `camera.p.evaluateUniform()`) and this rasterizer and that cloud
     * frame the scene identically.
     *
     * @param modelView world to view, as `pc.modelView`. Column-major, `m<column><row>`.
     * @param proj view to clip, as `pc.proj`. Only `m00`, `m11` and the full transform are consulted;
     *   the depth range is immaterial because nothing here depth-tests.
     */
    data class View(
        val modelView: Matrix4f,
        val proj: Matrix4f,
        val width: Int,
        val height: Int
    ) {
        init {
            require(width > 0 && height > 0) { "viewport must be positive, got ${width}x$height" }
        }

        companion object {
            /**
             * A look-at view, built the way [afield.four.util.Camera] builds one so the framing
             * matches: `Matrix4f().lookAtField(eye, target, up)` and a symmetric perspective.
             *
             * For the depth-map captures this pipeline exists for, `up` is `(0, -1, 0)` — those
             * captures are `+z` forward and `+y` **down**, which the `splat_stream` document records
             * and which is easy to get wrong by inspection because the cloud is roughly symmetric.
             */
            fun lookAt(
                eye: Vector3f,
                target: Vector3f,
                up: Vector3f = Vector3f(0f, 1f, 0f),
                width: Int,
                height: Int,
                fovY: Float = Math.toRadians(50.0).toFloat(),
                zNear: Float = 0.01f,
                zFar: Float = 100f
            ): View = View(
                modelView = Matrix4f().lookAtField(eye, target, up),
                proj = Matrix4f().setPerspective(
                    fovY, width.toFloat() / height.toFloat(), zNear, zFar, zZeroToOne = true
                ),
                width = width,
                height = height
            )
        }
    }

    // ------------------------------------------------------------------------
    // Config
    // ------------------------------------------------------------------------

    /**
     * The rasterizer's knobs, named and defaulted to match [GaussianSplatRenderer.Config] and the
     * `var`s on a [GaussianSplatRenderer.Cloud], so a number that framed a cloud on the GPU frames
     * it the same way here.
     *
     * @param compose how a splat combines with what is already there. See [Compose.forBlend] to
     *   carry a GPU [GaussianSplatRenderer.Blend] across.
     * @param depthOrder see [DepthOrder]. The default sorts, which is always correct;
     *   [DepthOrder.BufferOrder] is the interesting one, because it makes this rasterizer able to
     *   *check* a `sortAlong` pack rather than paper over it.
     */
    data class RasterConfig(
        val sizeScale: Float = 1f,
        val alphaScale: Float = 1f,
        val minScreenRadius: Float = 0.75f,
        val maxScreenRadius: Float = 128f,
        val alphaCutoff: Float = 1f / 255f,
        val colorGamma: Float = 2.2f,
        val compose: Compose = Compose.Over,
        val depthOrder: DepthOrder = DepthOrder.BackToFront
    ) {
        companion object {
            /**
             * The raster knobs implied by a GPU [GaussianSplatRenderer.Config], for when a caller
             * already has one. `sizeScale`, `alphaScale` and friends live on the [Config] and are
             * copied; `alphaScale` is *also* a `var` on a live [GaussianSplatRenderer.Cloud], so
             * read it off the cloud if it has been moved since.
             */
            fun from(
                config: GaussianSplatRenderer.Config,
                depthOrder: DepthOrder = DepthOrder.BackToFront
            ): RasterConfig = RasterConfig(
                sizeScale = config.sizeScale,
                alphaScale = config.alphaScale,
                minScreenRadius = config.minScreenRadius,
                maxScreenRadius = config.maxScreenRadius,
                alphaCutoff = config.alphaCutoff,
                colorGamma = config.colorGamma,
                compose = Compose.forBlend(config.blend),
                depthOrder = depthOrder
            )
        }
    }

    /**
     * In what order the splats are composited.
     *
     * [BufferOrder] is not a lazy shortcut — it is what the GPU does under
     * [GaussianSplatRenderer.SortMode.None], which is the mode the streaming path uses because
     * `packInto(sortAlong = …)` has already written the frame farthest-first. Rasterizing in buffer
     * order is therefore how you find out whether that CPU sort was actually right, and rendering
     * the same frame both ways and differencing is a real test of it.
     */
    enum class DepthOrder {
        /** Sort farthest-first by view depth here. Always correct, and costs a sort. */
        BackToFront,

        /** Composite in the order the buffer holds. Matches [GaussianSplatRenderer.SortMode.None]. */
        BufferOrder
    }

    /**
     * The CPU equivalents of [GaussianSplatRenderer.Blend]'s five named companions, all on
     * premultiplied source `(c·a, a)` as the fragment shader emits it.
     */
    enum class Compose {
        /** `dst = src + dst·(1 - a)`. Correct back-to-front. */
        Over,

        /** `dst = src + dst`. Order-independent, saturates where splats pile up. */
        Additive,

        /** `dst = src + dst·(1 - src)`. Order-independent, cannot exceed one. */
        Screen,

        /** `dst = src·dst`. Order-independent, reads as absorption. */
        Multiply,

        /** `dst = max(src, dst)`. Order-independent and idempotent. */
        Max;

        companion object {
            /**
             * The [Compose] matching a GPU [GaussianSplatRenderer.Blend].
             *
             * Recognises the five named companions by value — `Blend` is a data class, so this is
             * exact — and refuses anything else rather than guessing. An arbitrary combination of
             * Vulkan blend factors has no CPU equivalent here, and silently rasterizing it as
             * [Over] would make this rasterizer's whole reason for existing (agreeing with the GPU)
             * a lie in precisely the case where a caller went out of their way to be unusual.
             */
            fun forBlend(blend: GaussianSplatRenderer.Blend): Compose = when (blend) {
                GaussianSplatRenderer.Blend.Over -> Over
                GaussianSplatRenderer.Blend.Additive -> Additive
                GaussianSplatRenderer.Blend.Screen -> Screen
                GaussianSplatRenderer.Blend.Multiply -> Multiply
                GaussianSplatRenderer.Blend.Max -> Max
                else -> error(
                    "no CPU equivalent for this Blend (srcColor=${blend.srcColor}, " +
                        "dstColor=${blend.dstColor}, colorBlendOp=${blend.colorBlendOp}); " +
                        "SplatRaster implements the five named Blend companions. Pass a Compose " +
                        "explicitly if you know which one you meant."
                )
            }
        }
    }

    // ------------------------------------------------------------------------
    // Output
    // ------------------------------------------------------------------------

    /**
     * A rasterized image: premultiplied **linear** RGBA in floats, four per pixel, row 0 at the top.
     *
     * Linear and premultiplied because that is what the framebuffer the GPU blends into holds; the
     * sRGB encode happens once, on the way to [toArgb].
     */
    class SplatImage(val width: Int, val height: Int) {
        val linear = FloatArray(width * height * 4)

        /** Composite [source] on top of this, treating both as premultiplied linear. */
        fun over(source: SplatImage, atX: Int, atY: Int) {
            for (y in 0 until source.height) {
                val destY = atY + y
                if (destY < 0 || destY >= height) continue
                for (x in 0 until source.width) {
                    val destX = atX + x
                    if (destX < 0 || destX >= width) continue
                    val s = (x + y * source.width) * 4
                    val d = (destX + destY * width) * 4
                    val a = source.linear[s + 3]
                    for (c in 0..3) linear[d + c] = source.linear[s + c] + linear[d + c] * (1f - a)
                }
            }
        }

        /** Set a rectangle to an opaque linear colour. Used for contact-sheet rules and gutters. */
        fun fill(atX: Int, atY: Int, w: Int, h: Int, r: Float, g: Float, b: Float, a: Float = 1f) {
            for (y in max(0, atY) until min(height, atY + h)) {
                for (x in max(0, atX) until min(width, atX + w)) {
                    val d = (x + y * width) * 4
                    linear[d] = r * a; linear[d + 1] = g * a; linear[d + 2] = b * a; linear[d + 3] = a
                }
            }
        }

        /**
         * sRGB-encoded ARGB, one int per pixel, row 0 first — the layout [PngEncoder.encode] wants.
         *
         * @param background composited *under* the image as an opaque sRGB colour, or null to keep
         *   the alpha channel and hand back straight (un-premultiplied) RGBA.
         */
        fun toArgb(background: Int? = 0xff000000.toInt()): IntArray {
            val out = IntArray(width * height)
            val backR = if (background == null) 0f else srgbToLinear(((background shr 16) and 255) / 255f)
            val backG = if (background == null) 0f else srgbToLinear(((background shr 8) and 255) / 255f)
            val backB = if (background == null) 0f else srgbToLinear((background and 255) / 255f)
            for (pixel in 0 until width * height) {
                val at = pixel * 4
                val a = linear[at + 3].coerceIn(0f, 1f)
                var r = linear[at]
                var g = linear[at + 1]
                var b = linear[at + 2]
                val alphaOut: Int
                if (background == null) {
                    // Un-premultiply. Below the point where eight bits can tell the difference the
                    // colour is unrecoverable anyway, so leave it black rather than divide by dust.
                    if (a > 1f / 512f) { r /= a; g /= a; b /= a } else { r = 0f; g = 0f; b = 0f }
                    alphaOut = (a * 255f + 0.5f).toInt().coerceIn(0, 255)
                } else {
                    r += backR * (1f - a); g += backG * (1f - a); b += backB * (1f - a)
                    alphaOut = 255
                }
                out[pixel] = (alphaOut shl 24) or
                    (encode8(r) shl 16) or (encode8(g) shl 8) or encode8(b)
            }
            return out
        }

        /** PNG bytes, via [PngEncoder]. See [toArgb] for [background]. */
        fun toPng(background: Int? = 0xff000000.toInt()): ByteArray =
            PngEncoder.encode(toArgb(background), width, height)

        /**
         * What is actually in the image, so a caller can tell "dark content" from "the capture
         * failed and wrote black".
         *
         * That distinction is the whole point of this existing. A 3DGS capture of a dim scene is
         * *supposed* to come out dark — the `splat_stream` document notes this shot's albedo averages
         * 0.207 — so "the PNG looks nearly black" is not by itself a bug report. [coverage] and
         * [distinctColors] separate the cases: a failed raster has coverage 0, a dark one does not.
         */
        fun stats(): ImageStats {
            var sum = 0.0
            var peak = 0f
            var covered = 0
            val seen = HashSet<Int>()
            val argb = toArgb(null)
            for (pixel in argb) {
                val a = (pixel ushr 24) and 255
                if (a > 0) {
                    covered++
                    if (seen.size < 4096) seen.add(pixel and 0xffffff)
                }
                // Rec.709 over the sRGB code values, which is what a person judging the PNG sees.
                val luminance = (
                    0.2126f * ((pixel shr 16) and 255) +
                        0.7152f * ((pixel shr 8) and 255) +
                        0.0722f * (pixel and 255)
                    ) / 255f * (a / 255f)
                sum += luminance
                if (luminance > peak) peak = luminance
            }
            return ImageStats(
                width = width,
                height = height,
                meanLuminance = (sum / argb.size).toFloat(),
                peakLuminance = peak,
                coverage = covered.toFloat() / argb.size,
                distinctColors = seen.size
            )
        }
    }

    /** See [SplatImage.stats]. */
    data class ImageStats(
        val width: Int,
        val height: Int,
        val meanLuminance: Float,
        val peakLuminance: Float,
        val coverage: Float,
        /** Distinct RGB triples among covered pixels, saturating at 4096. */
        val distinctColors: Int
    ) {
        /**
         * True when nothing landed. Deliberately *not* "the mean luminance is low" — dark content is
         * not a failure, and conflating the two is how a black capture gets waved through.
         */
        val blank: Boolean get() = coverage <= 0f

        override fun toString(): String =
            "${width}x$height coverage %.1f%% mean %.4f peak %.4f %d colours".format(
                coverage * 100, meanLuminance, peakLuminance, distinctColors
            )
    }

    /**
     * What the rasterizer did, per splat. Every splat is in exactly one of the counted categories, and
     * [check] enforces that, so a category quietly appearing that nothing counts cannot hide.
     *
     * [nonFinite] is separated from the culls on purpose. A NaN position fails
     * `depth <= 0.0001` — `NaN <= x` is false — so it sails past the near-plane cull and then
     * silently contributes nothing when its pixel bounds come out empty. Counting it as "culled"
     * would let a cloud that is NaN throughout report a clean render of nothing. It is counted, and
     * `SplatRasterTest` poisons a cloud and requires the count to notice.
     */
    data class RasterStats(
        val considered: Int,
        val drawn: Int,
        val culledBehind: Int,
        val culledTransparent: Int,
        val culledOffscreen: Int,
        val nonFinite: Int,
        val fragments: Long
    ) {
        /** Every splat accounted for exactly once. */
        fun check() {
            val sum = drawn + culledBehind + culledTransparent + culledOffscreen + nonFinite
            require(sum == considered) {
                "RasterStats does not account for every splat: $considered considered but " +
                    "drawn=$drawn behind=$culledBehind transparent=$culledTransparent " +
                    "offscreen=$culledOffscreen nonFinite=$nonFinite sums to $sum"
            }
        }

        override fun toString(): String =
            "$drawn/$considered splats drawn ($fragments fragments; behind $culledBehind, " +
                "transparent $culledTransparent, offscreen $culledOffscreen, non-finite $nonFinite)"
    }

    /** An image and the account of how it was made. */
    data class Rasterized(val image: SplatImage, val stats: RasterStats)

    // ------------------------------------------------------------------------
    // The rasterizer
    // ------------------------------------------------------------------------

    /**
     * Rasterize [count] splats held in [packed] in [GaussianSplatRenderer]'s 16-float GPU layout —
     * the buffer [GaussianSplatRenderer.packInto] fills.
     *
     * @param packed absolutely indexed and read-only; the position is not disturbed.
     */
    fun rasterize(
        packed: FloatBuffer,
        count: Int,
        view: View,
        config: RasterConfig = RasterConfig(),
        into: SplatImage = SplatImage(view.width, view.height)
    ): Rasterized {
        require(count >= 0) { "count must be non-negative, got $count" }
        require(packed.capacity() >= count.toLong() * STRIDE) {
            "buffer holds ${packed.capacity() / STRIDE} splats, asked to draw $count"
        }
        require(into.width == view.width && into.height == view.height) {
            "image is ${into.width}x${into.height} but the view is ${view.width}x${view.height}"
        }

        val width = view.width
        val height = view.height
        val mv = view.modelView

        // The view-space depth of a splat is the third row of modelView applied to its position.
        val vz0 = mv.m02; val vz1 = mv.m12; val vz2 = mv.m22; val vz3 = mv.m32

        val order: IntArray? = when (config.depthOrder) {
            DepthOrder.BufferOrder -> null
            DepthOrder.BackToFront -> {
                // Sort on the key, not on boxed indices: a frame is hundreds of thousands of splats
                // and this runs per contact-sheet tile. The float depth goes in the high 32 bits with
                // its sign flipped into an order-preserving unsigned form, the index in the low 32.
                val keys = LongArray(count)
                for (splat in 0 until count) {
                    val at = splat * STRIDE
                    val depth = -(
                        vz0 * packed.get(at) + vz1 * packed.get(at + 1) +
                            vz2 * packed.get(at + 2) + vz3
                        )
                    keys[splat] = (orderableBits(depth).toLong() shl 32) or splat.toLong()
                }
                java.util.Arrays.sort(keys)
                // Ascending by depth is nearest-first; Over wants the far ones down first.
                IntArray(count) { (keys[count - 1 - it] and 0xffffffffL).toInt() }
            }
        }

        var drawn = 0
        var behind = 0
        var transparent = 0
        var offscreen = 0
        var nonFinite = 0
        var fragments = 0L

        val fx = abs(view.proj.m00) * width * 0.5f
        val fy = abs(view.proj.m11) * height * 0.5f
        val minLambda = config.minScreenRadius * config.minScreenRadius
        val invGamma = config.colorGamma

        for (slot in 0 until count) {
            val splat = order?.get(slot) ?: slot
            val at = splat * STRIDE

            val px = packed.get(at)
            val py = packed.get(at + 1)
            val pz = packed.get(at + 2)
            val opacity = packed.get(at + 3)

            // Everything the shader reads about this splat, checked once. A NaN anywhere in the
            // sixteen produces a NaN somewhere downstream that a comparison would swallow.
            if (!px.isFinite() || !py.isFinite() || !pz.isFinite() || !opacity.isFinite() ||
                !packed.get(at + 4).isFinite() || !packed.get(at + 5).isFinite() ||
                !packed.get(at + 6).isFinite() ||
                !packed.get(at + 8).isFinite() || !packed.get(at + 9).isFinite() ||
                !packed.get(at + 10).isFinite() || !packed.get(at + 11).isFinite() ||
                !packed.get(at + 12).isFinite() || !packed.get(at + 13).isFinite()
            ) {
                nonFinite++
                continue
            }

            // hideVertex() on a zero-opacity splat, as the shader does before anything else.
            if (opacity <= 0f) { transparent++; continue }

            // view = modelView * vec4(position, 1)
            val viewX = mv.m00 * px + mv.m10 * py + mv.m20 * pz + mv.m30
            val viewY = mv.m01 * px + mv.m11 * py + mv.m21 * pz + mv.m31
            val viewZ = vz0 * px + vz1 * py + vz2 * pz + vz3
            val depth = -viewZ
            if (depth <= 0.0001f) { behind++; continue }

            // covView = mat3(modelView) * covObject * transpose(mat3(modelView)). Matrix4f is
            // column-major with m<column><row>, so the linear part's row r, column c is m<c><r>.
            val c00o = packed.get(at + 8); val c01o = packed.get(at + 9); val c02o = packed.get(at + 10)
            val c11o = packed.get(at + 11); val c12o = packed.get(at + 12); val c22o = packed.get(at + 13)

            // M · C, one row of M at a time.
            val a00 = mv.m00 * c00o + mv.m10 * c01o + mv.m20 * c02o
            val a01 = mv.m00 * c01o + mv.m10 * c11o + mv.m20 * c12o
            val a02 = mv.m00 * c02o + mv.m10 * c12o + mv.m20 * c22o
            val a10 = mv.m01 * c00o + mv.m11 * c01o + mv.m21 * c02o
            val a11 = mv.m01 * c01o + mv.m11 * c11o + mv.m21 * c12o
            val a12 = mv.m01 * c02o + mv.m11 * c12o + mv.m21 * c22o
            val a20 = vz0 * c00o + vz1 * c01o + vz2 * c02o
            val a21 = vz0 * c01o + vz1 * c11o + vz2 * c12o
            val a22 = vz0 * c02o + vz1 * c12o + vz2 * c22o
            // (M · C) · Mᵀ, keeping only the six the Jacobian below touches.
            val v00 = a00 * mv.m00 + a01 * mv.m10 + a02 * mv.m20
            val v01 = a00 * mv.m01 + a01 * mv.m11 + a02 * mv.m21
            val v02 = a00 * vz0 + a01 * vz1 + a02 * vz2
            val v11 = a10 * mv.m01 + a11 * mv.m11 + a12 * mv.m21
            val v12 = a10 * vz0 + a11 * vz1 + a12 * vz2
            val v22 = a20 * vz0 + a21 * vz1 + a22 * vz2

            // The projective Jacobian, as jx and jy in the shader.
            val invDepth = 1f / depth
            val invDepth2 = invDepth * invDepth
            val jx0 = fx * invDepth
            val jx2 = fx * viewX * invDepth2
            val jy1 = fy * invDepth
            val jy2 = fy * viewY * invDepth2

            // covView · jx and covView · jy, then dotted back.
            val cx0 = v00 * jx0 + v02 * jx2
            val cx1 = v01 * jx0 + v12 * jx2
            val cx2 = v02 * jx0 + v22 * jx2
            val cy0 = v01 * jy1 + v02 * jy2
            val cy1 = v11 * jy1 + v12 * jy2
            val cy2 = v12 * jy1 + v22 * jy2
            val c00 = jx0 * cx0 + jx2 * cx2 + SCREEN_DILATION
            val c01 = jx0 * cy0 + jx2 * cy2
            val c11 = jy1 * cy1 + jy2 * cy2 + SCREEN_DILATION

            val mid = 0.5f * (c00 + c11)
            val radius = sqrt(
                (0.5f * (c00 - c11)) * (0.5f * (c00 - c11)) + c01 * c01
            )
            val lambda1 = max(mid + radius, minLambda)
            val lambda2 = max(mid - radius, minLambda)

            var axis1x: Float
            var axis1y: Float
            if (abs(c01) > 1e-5f) {
                val ax = lambda1 - c11
                val ay = c01
                val length = sqrt(ax * ax + ay * ay)
                axis1x = ax / length; axis1y = ay / length
            } else if (c00 >= c11) {
                axis1x = 1f; axis1y = 0f
            } else {
                axis1x = 0f; axis1y = 1f
            }
            val axis2x = -axis1y
            val axis2y = axis1x

            var sigma1 = sqrt(lambda1) * config.sizeScale
            var sigma2 = sqrt(lambda2) * config.sizeScale
            val maxRadius = max(sigma1, sigma2) * QUAD_SIGMAS
            if (maxRadius > config.maxScreenRadius) {
                val shrink = config.maxScreenRadius / maxRadius
                sigma1 *= shrink
                sigma2 *= shrink
            }
            if (sigma1 <= 0f || sigma2 <= 0f || !sigma1.isFinite() || !sigma2.isFinite()) {
                // Degenerate on screen: the GPU would draw a zero-area quad. Not "non-finite data" —
                // the data was fine and the projection collapsed it — so it is an offscreen cull.
                offscreen++
                continue
            }

            // clip = proj * view, then NDC, then pixels. The screen space here is y-**up**, matching
            // the shader's own pixelOffset space; the flip to row order happens once, below.
            val clipW = view.proj.m03 * viewX + view.proj.m13 * viewY +
                view.proj.m23 * viewZ + view.proj.m33
            if (abs(clipW) < 1e-12f) { offscreen++; continue }
            val clipX = view.proj.m00 * viewX + view.proj.m10 * viewY +
                view.proj.m20 * viewZ + view.proj.m30
            val clipY = view.proj.m01 * viewX + view.proj.m11 * viewY +
                view.proj.m21 * viewZ + view.proj.m31
            val centreX = (clipX / clipW * 0.5f + 0.5f) * width
            val centreY = (clipY / clipW * 0.5f + 0.5f) * height

            // The quad is an oriented rectangle 3σ on a side, so its axis-aligned bound is the sum
            // of the two axes' absolute contributions.
            val halfW = QUAD_SIGMAS * (abs(sigma1 * axis1x) + abs(sigma2 * axis2x))
            val halfH = QUAD_SIGMAS * (abs(sigma1 * axis1y) + abs(sigma2 * axis2y))

            val colLow = max(0, ceil(centreX - halfW - 0.5f).toInt())
            val colHigh = min(width - 1, floor(centreX + halfW - 0.5f).toInt())
            // Row r's centre sits at y-up height - r - 0.5; see the Y-convention note in the KDoc.
            val rowLow = max(0, ceil(height - 0.5f - centreY - halfH).toInt())
            val rowHigh = min(height - 1, floor(height - 0.5f - centreY + halfH).toInt())
            if (colLow > colHigh || rowLow > rowHigh) { offscreen++; continue }

            val red = packed.get(at + 4).coerceAtLeast(0f).pow(invGamma)
            val green = packed.get(at + 5).coerceAtLeast(0f).pow(invGamma)
            val blue = packed.get(at + 6).coerceAtLeast(0f).pow(invGamma)
            val peakAlpha = opacity * config.alphaScale

            val invSigma1 = 1f / sigma1
            val invSigma2 = 1f / sigma2
            var touched = 0

            for (row in rowLow..rowHigh) {
                val sampleY = height - row - 0.5f
                val dy = sampleY - centreY
                val rowBase = row * width
                for (col in colLow..colHigh) {
                    val dx = col + 0.5f - centreX
                    // The Gaussian coordinate the shader interpolates across the quad: the offset
                    // resolved onto the two axes and divided by their sigmas.
                    val u = (dx * axis1x + dy * axis1y) * invSigma1
                    val v = (dx * axis2x + dy * axis2y) * invSigma2
                    // Outside the quad the GPU rasterizes nothing at all, so neither do we. The box
                    // test, not a radius test: the quad is a rectangle in (u, v).
                    if (abs(u) > QUAD_SIGMAS || abs(v) > QUAD_SIGMAS) continue
                    val alpha = (peakAlpha * exp(-0.5f * (u * u + v * v))).coerceIn(0f, 1f)
                    if (alpha < config.alphaCutoff) continue

                    val d = (rowBase + col) * 4
                    composite(into.linear, d, red * alpha, green * alpha, blue * alpha, alpha, config.compose)
                    touched++
                }
            }
            if (touched == 0) {
                // Its bounds overlapped the image but every sample fell outside the quad or under the
                // alpha cutoff. Sub-pixel, in other words, which is a legitimate GPU outcome too.
                offscreen++
            } else {
                drawn++
                fragments += touched
            }
        }

        val stats = RasterStats(
            considered = count,
            drawn = drawn,
            culledBehind = behind,
            culledTransparent = transparent,
            culledOffscreen = offscreen,
            nonFinite = nonFinite,
            fragments = fragments
        )
        stats.check()
        return Rasterized(into, stats)
    }

    /**
     * Rasterize compressed splat payloads directly: [GaussianSplatRenderer.packInto] into a scratch
     * buffer, then [rasterize] over it. This is the whole pipeline the GPU runs, minus the GPU.
     *
     * @param sortAlong passed to `packInto`. Leave it null under [DepthOrder.BackToFront], which
     *   sorts here anyway; set it *with* [DepthOrder.BufferOrder] to see what the packed order looks
     *   like, which is what the streaming path actually draws.
     */
    fun rasterize(
        sources: List<GaussianSplatRenderer.SplatSource>,
        view: View,
        config: RasterConfig = RasterConfig(),
        packConfig: GaussianSplatRenderer.Config = GaussianSplatRenderer.Config(),
        sortAlong: Vector3f? = null,
        into: SplatImage = SplatImage(view.width, view.height)
    ): Rasterized {
        val total = sources.sumOf { it.raw.count }
        val packed = allocatePacked(total)
        GaussianSplatRenderer.packInto(sources, packed, packConfig, sortAlong)
        return rasterize(packed, total, view, config, into)
    }

    /** A direct [FloatBuffer] sized for [splats] in the 16-float layout. */
    fun allocatePacked(splats: Int): FloatBuffer =
        ByteBuffer.allocateDirect(splats * STRIDE * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    // ------------------------------------------------------------------------
    // Small numerics
    // ------------------------------------------------------------------------

    private fun composite(
        dest: FloatArray, at: Int,
        srcR: Float, srcG: Float, srcB: Float, srcA: Float,
        compose: Compose
    ) {
        when (compose) {
            Compose.Over -> {
                val keep = 1f - srcA
                dest[at] = srcR + dest[at] * keep
                dest[at + 1] = srcG + dest[at + 1] * keep
                dest[at + 2] = srcB + dest[at + 2] * keep
                dest[at + 3] = srcA + dest[at + 3] * keep
            }
            Compose.Additive -> {
                dest[at] += srcR; dest[at + 1] += srcG; dest[at + 2] += srcB; dest[at + 3] += srcA
            }
            Compose.Screen -> {
                dest[at] = srcR + dest[at] * (1f - srcR)
                dest[at + 1] = srcG + dest[at + 1] * (1f - srcG)
                dest[at + 2] = srcB + dest[at + 2] * (1f - srcB)
                dest[at + 3] = srcA + dest[at + 3] * (1f - srcA)
            }
            Compose.Multiply -> {
                dest[at] *= srcR; dest[at + 1] *= srcG; dest[at + 2] *= srcB; dest[at + 3] *= srcA
            }
            Compose.Max -> {
                dest[at] = max(dest[at], srcR)
                dest[at + 1] = max(dest[at + 1], srcG)
                dest[at + 2] = max(dest[at + 2], srcB)
                dest[at + 3] = max(dest[at + 3], srcA)
            }
        }
    }

    /**
     * A float's bits rearranged so that unsigned integer order matches float order — the standard
     * trick: flip the sign bit for positives, invert everything for negatives. NaN sorts to one end
     * rather than breaking the comparator, which matters because a poisoned cloud must still get as
     * far as the per-splat finiteness check that counts it.
     */
    private fun orderableBits(value: Float): Int {
        val bits = value.toRawBits()
        return if (bits < 0) bits.inv() else bits xor Int.MIN_VALUE
    }

    /** The sRGB OETF, which is what a `_SRGB` framebuffer applies on write. */
    fun linearToSrgb(value: Float): Float {
        val v = value.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) 12.92f * v else 1.055f * v.pow(1f / 2.4f) - 0.055f
    }

    /** The sRGB EOTF, the inverse of [linearToSrgb]. */
    fun srgbToLinear(value: Float): Float {
        val v = value.coerceIn(0f, 1f)
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun encode8(linear: Float): Int =
        (linearToSrgb(linear) * 255f + 0.5f).toInt().coerceIn(0, 255)
}
