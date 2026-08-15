@file:afield.CompletionCategory("Machine learning")

package afield.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.platform.Fp16Conversions
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Monocular depth, from any image, with no depth camera.
 *
 * [Depth Anything V2](https://depth-anything-v2.github.io) Small is the least demanding useful vision
 * model there is: one image in, one single-channel map out, no anchors, no non-maximum suppression,
 * no decode step and no state between frames. That makes it the right first ONNX capability — what it
 * exercises is the *plumbing*, and the plumbing is reusable for everything harder.
 *
 * ```kotlin
 * val depth = DepthAnything(width = 266, height = 266)
 * depth.infer(webcamRgbaBuffer, srcW = 640, srcH = 480)
 * // depth.depthFp16 now holds width*height fp16 values in [0,1]
 * ```
 *
 * ## The output is inverse relative depth, in arbitrary units
 *
 * This is the part that surprises people, so it is worth stating flatly. `predicted_depth` is not
 * metres and not normalised. Measured on a synthetic frame at 518x518 the raw range was
 * `[1.79, 3.27]`; on the same frame at 266x266 it was `[1.51, 2.45]`. The range moves with the
 * *content and the resolution*, and **larger means nearer**.
 *
 * So a usable texture needs a normalisation window, and choosing it per-frame from that frame's own
 * min and max makes the image flicker — a hand entering shot rescales the entire background. This
 * class instead tracks the window with an exponential moving average ([adaptation]) and normalises
 * against the smoothed window, which is the cheap version of the one-euro filter that anything
 * body-tracked eventually needs. [DepthStats] reports both the raw and the smoothed window so a box
 * can watch that decision rather than guess at it.
 *
 * ## Buffer identity is deliberate
 *
 * [depthFp16] and [depthRaw] are allocated once and refilled in place. That is not just to avoid
 * garbage: `afield.four.texture.StreamingTexture` captures its source buffer at *construction* and
 * re-reads it every frame, so the buffer has to keep the same identity for the texture to keep
 * working. Uploading to Vulkan is then:
 *
 * ```kotlin
 * val tex = StreamingTexture(
 *     depth.depthFp16, depth.width, depth.height,
 *     imageFormat = VK10.VK_FORMAT_R16_SFLOAT
 * )
 * ```
 *
 * fp16 is the output format because a single-channel depth map has nowhere near 16 bits of real
 * precision in it, and `R16_SFLOAT` halves the upload against `R32_SFLOAT` for no visible loss.
 *
 * ## Cost
 *
 * Inference is **not** frame-rate on a CPU execution provider: ≈388ms at 518x518 and ≈101ms at
 * 266x266 on a 10-core Apple Silicon machine, and see [OnnxRuntime] for why CoreML does not fix that.
 * Cost is roughly quadratic in resolution, which makes [width]/[height] the only lever that really
 * matters. Anything interactive should drive this through [DepthAnythingWorker] rather than calling
 * [infer] on the render thread.
 *
 * @param width model input width; forced to a multiple of 14 by [sizeFor] since the backbone is
 *   ViT-S/14 and the output is `14*floor(width/14)` wide
 * @param height model input height, likewise
 * @param model which weights to use; [FP32] is faster than [FP16] on CPU
 * @param provider execution provider to request
 * @param adaptation EMA rate for the normalisation window, per frame, in `(0,1]`; 1.0 means "use this
 *   frame's own range", small values mean "barely move"
 * @param invert map larger-is-nearer onto 1.0 rather than 0.0
 */
class DepthAnything(
    val width: Int = 266,
    val height: Int = 266,
    val model: OnnxModel = FP32,
    val provider: OnnxRuntime.OnnxProvider = OnnxRuntime.OnnxProvider.AUTO,
    var adaptation: Float = 0.1f,
    var invert: Boolean = false
) {
    init {
        require(width > 0 && height > 0) { "DepthAnything needs positive dimensions, got ${width}x$height" }
        require(width % 14 == 0 && height % 14 == 0) {
            "Depth Anything V2 is ViT-S/14: ${width}x$height must be multiples of 14 " +
                "(nearest is ${sizeFor(width, height, max(width, height))})"
        }
        require(adaptation > 0f && adaptation <= 1f) { "adaptation must be in (0,1], got $adaptation" }
    }

    private val loaded = model.session(provider)

    /** The provider actually in force, which may not be the one requested. */
    val activeProvider: String get() = loaded.provider

    private val inputName: String = loaded.inputNames.firstOrNull() ?: "pixel_values"
    private val outputName: String = loaded.outputNames.firstOrNull() ?: "predicted_depth"
    private val inputShape = longArrayOf(1, 3, height.toLong(), width.toLong())

    // Allocated once. Direct, because ORT silently copies a non-direct buffer into a fresh direct one
    // tied to the tensor's lifetime -- which would be a per-frame allocation of exactly the size we
    // were trying not to allocate.
    private val inputTensorData: FloatBuffer =
        ByteBuffer.allocateDirect(3 * width * height * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** The most recent depth map in the model's own units. Valid until the next [infer]. */
    val depthRaw: FloatBuffer =
        ByteBuffer.allocateDirect(width * height * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()

    /**
     * The most recent depth map as fp16 in `[0,1]`, ready to upload as `VK_FORMAT_R16_SFLOAT`.
     *
     * Stable identity across frames — see the class note. Little-endian, `width*height*2` bytes.
     */
    val depthFp16: ByteBuffer =
        ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.LITTLE_ENDIAN)

    // Resample taps, precomputed per source size. The webcam does not change size mid-stream, so
    // these are computed once and the per-frame inner loop is pure gathers.
    private var tapSrcW = -1
    private var tapSrcH = -1
    private lateinit var xLo: IntArray
    private lateinit var xHi: IntArray
    private lateinit var xFrac: FloatArray
    private lateinit var yLo: IntArray
    private lateinit var yHi: IntArray
    private lateinit var yFrac: FloatArray

    private var smoothedMin = Float.NaN
    private var smoothedMax = Float.NaN

    /** How many frames have been through [infer]. */
    var framesInferred: Long = 0L
        private set

    /** Wall time of the last [infer] call, in milliseconds, inference and conversion together. */
    var lastInferenceMs: Long = 0L
        private set

    /**
     * Run one frame.
     *
     * @param rgba tightly packed RGBA8, `srcW*srcH*4` bytes readable from position 0; the alpha
     *   channel is ignored. Not retained — safe to overwrite as soon as this returns.
     * @param srcW source width in pixels
     * @param srcH source height in pixels
     * @return the normalisation window this frame used, raw and smoothed
     */
    fun infer(rgba: ByteBuffer, srcW: Int, srcH: Int): DepthStats {
        require(srcW > 0 && srcH > 0) { "source must be non-empty, got ${srcW}x$srcH" }
        val needed = srcW * srcH * 4
        require(rgba.capacity() >= needed) {
            "RGBA buffer too small: need $needed bytes for ${srcW}x$srcH, have ${rgba.capacity()}"
        }

        val started = System.nanoTime()
        ensureTaps(srcW, srcH)
        preprocess(rgba, srcW)

        inputTensorData.rewind()
        val stats = OnnxTensor.createTensor(OnnxRuntime.env, inputTensorData, inputShape).use { input ->
            loaded.session.run(mapOf(inputName to input)).use { result ->
                val tensor = (result.get(outputName).orElse(null) ?: result.get(0)) as OnnxTensor
                // getFloatBuffer() is already a copy owned by us, but copying into our own stable
                // buffer keeps depthRaw's identity fixed and lets the Result close here.
                val out = tensor.floatBuffer
                // The graph declares its output as 14*floor(h/14) x 14*floor(w/14), which for the
                // multiple-of-14 sizes the constructor enforces is exactly height x width. Check
                // anyway: a shape surprise here is far easier to read than the BufferOverflowException
                // it would otherwise become.
                check(out.remaining() == width * height) {
                    "expected ${width * height} depth values for ${width}x$height, " +
                        "got ${out.remaining()} (output shape ${tensor.info.shape.joinToString("x")})"
                }
                depthRaw.clear()
                depthRaw.put(out)
                depthRaw.flip()
                normalize()
            }
        }
        lastInferenceMs = (System.nanoTime() - started) / 1_000_000
        framesInferred++
        return stats
    }

    /** Forget the normalisation window, so the next frame sets it from its own range. */
    fun resetAdaptation() {
        smoothedMin = Float.NaN
        smoothedMax = Float.NaN
    }

    private fun ensureTaps(srcW: Int, srcH: Int) {
        if (srcW == tapSrcW && srcH == tapSrcH) return
        xLo = IntArray(width); xHi = IntArray(width); xFrac = FloatArray(width)
        yLo = IntArray(height); yHi = IntArray(height); yFrac = FloatArray(height)
        buildTaps(srcW, width, xLo, xHi, xFrac)
        buildTaps(srcH, height, yLo, yHi, yFrac)
        tapSrcW = srcW
        tapSrcH = srcH
    }

    /**
     * Half-pixel-centred bilinear taps.
     *
     * The `+0.5 ... -0.5` is what makes the mapping exact when source and destination sizes agree:
     * `src == dst` gives `s == d`, `frac == 0`, i.e. a copy. Tests depend on that identity, and so
     * does anyone feeding an already-correctly-sized image.
     */
    private fun buildTaps(srcSize: Int, dstSize: Int, lo: IntArray, hi: IntArray, frac: FloatArray) {
        val scale = srcSize.toFloat() / dstSize.toFloat()
        for (d in 0 until dstSize) {
            val s = (d + 0.5f) * scale - 0.5f
            val base = floor(s.toDouble()).toInt()
            val f = s - base
            lo[d] = base.coerceIn(0, srcSize - 1)
            hi[d] = (base + 1).coerceIn(0, srcSize - 1)
            frac[d] = if (f < 0f) 0f else if (f > 1f) 1f else f
        }
    }

    /** RGBA8 to ImageNet-normalised planar fp32 NCHW, resampling on the way. */
    private fun preprocess(rgba: ByteBuffer, srcW: Int) {
        val plane = width * height
        for (c in 0 until 3) {
            val mean = IMAGENET_MEAN[c]
            val invStd = 1f / IMAGENET_STD[c]
            val channelBase = c * plane
            for (dy in 0 until height) {
                val row0 = yLo[dy] * srcW
                val row1 = yHi[dy] * srcW
                val fy = yFrac[dy]
                val outRow = channelBase + dy * width
                for (dx in 0 until width) {
                    val x0 = xLo[dx]
                    val x1 = xHi[dx]
                    val fx = xFrac[dx]
                    val p00 = (rgba.get(((row0 + x0) shl 2) + c).toInt() and 0xff).toFloat()
                    val p01 = (rgba.get(((row0 + x1) shl 2) + c).toInt() and 0xff).toFloat()
                    val p10 = (rgba.get(((row1 + x0) shl 2) + c).toInt() and 0xff).toFloat()
                    val p11 = (rgba.get(((row1 + x1) shl 2) + c).toInt() and 0xff).toFloat()
                    val top = p00 + (p01 - p00) * fx
                    val bottom = p10 + (p11 - p10) * fx
                    val v = (top + (bottom - top) * fy) * (1f / 255f)
                    inputTensorData.put(outRow + dx, (v - mean) * invStd)
                }
            }
        }
    }

    /** Find this frame's range, move the smoothed window towards it, and write fp16. */
    private fun normalize(): DepthStats {
        val count = width * height
        var rawMin = Float.POSITIVE_INFINITY
        var rawMax = Float.NEGATIVE_INFINITY
        for (i in 0 until count) {
            val v = depthRaw.get(i)
            if (v < rawMin) rawMin = v
            if (v > rawMax) rawMax = v
        }

        if (smoothedMin.isNaN() || smoothedMax.isNaN()) {
            smoothedMin = rawMin
            smoothedMax = rawMax
        } else {
            smoothedMin += (rawMin - smoothedMin) * adaptation
            smoothedMax += (rawMax - smoothedMax) * adaptation
        }

        // A constant frame -- a lens cap, or a solid colour -- gives a zero-width window. Emit mid
        // grey rather than dividing by zero and filling the texture with NaN, which would be a
        // genuinely confusing thing to see in a shader.
        val span = smoothedMax - smoothedMin
        val scale = if (abs(span) < 1e-6f) 0f else 1f / span

        depthFp16.clear()
        for (i in 0 until count) {
            val t = if (scale == 0f) 0.5f else (depthRaw.get(i) - smoothedMin) * scale
            val clamped = min(1f, max(0f, t))
            depthFp16.putShort(Fp16Conversions.floatToFp16(if (invert) 1f - clamped else clamped))
        }
        depthFp16.flip()

        return DepthStats(rawMin, rawMax, smoothedMin, smoothedMax)
    }

    companion object {
        /** ImageNet channel means from the model's own `preprocessor_config.json`. */
        val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)

        /** ImageNet channel standard deviations, likewise. */
        val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        /** The patch size of the ViT-S/14 backbone, and so the required size granularity. */
        const val PATCH = 14

        /** Full-precision weights: 99MB, and *faster* than [FP16] on a CPU provider. */
        val FP32 = OnnxModel(
            id = "depth-anything-v2-small",
            url = "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model.onnx",
            fileName = "depth-anything-v2-small/model.onnx",
            sha256 = "afb6a5c28f3b6bf1618c6e43f02073ef9dfdc70e937502d51603e57b0a1df10c",
            note = "fp32 weights, ~25M params. Fastest on CPU."
        )

        /**
         * Half-precision weights: 49MB.
         *
         * The tensors are still fp32 — only the stored weights are fp16 — so this saves download and
         * disk, not bandwidth at the ORT boundary, and it measured ≈19% *slower* on CPU because every
         * operator upcasts. Choose it for disk, never for speed.
         */
        val FP16 = OnnxModel(
            id = "depth-anything-v2-small-fp16",
            url = "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model_fp16.onnx",
            fileName = "depth-anything-v2-small/model_fp16.onnx",
            sha256 = "2df6223f206b5164e21f664ace61dabeb9bb6a49b8b5a3e00510b4807d0f5b04",
            note = "fp16 weights, half the download, ~19% slower on CPU."
        )

        /**
         * The nearest legal input size to a source image, preserving aspect ratio.
         *
         * Both returned dimensions are multiples of [PATCH] and at least [PATCH], with the longer
         * side at approximately [longestSide]. The model's dynamic axes accept any such size, which
         * is the cheapest performance dial available — halving it is roughly a quarter of the work.
         */
        fun sizeFor(srcW: Int, srcH: Int, longestSide: Int = 266): Pair<Int, Int> {
            require(srcW > 0 && srcH > 0) { "source must be non-empty, got ${srcW}x$srcH" }
            require(longestSide >= PATCH) { "longestSide must be at least $PATCH, got $longestSide" }
            val scale = longestSide.toFloat() / max(srcW, srcH).toFloat()
            fun snap(v: Int): Int =
                (((v * scale) / PATCH).roundToInt() * PATCH).coerceAtLeast(PATCH)
            return snap(srcW) to snap(srcH)
        }

        /** A [DepthAnything] sized for a given source image via [sizeFor]. */
        fun forSource(
            srcW: Int,
            srcH: Int,
            longestSide: Int = 266,
            model: OnnxModel = FP32,
            provider: OnnxRuntime.OnnxProvider = OnnxRuntime.OnnxProvider.AUTO
        ): DepthAnything {
            val (w, h) = sizeFor(srcW, srcH, longestSide)
            return DepthAnything(w, h, model, provider)
        }
    }
}

/**
 * The normalisation window one [DepthAnything.infer] used.
 *
 * @param rawMin smallest value this frame's model output actually contained
 * @param rawMax largest, likewise
 * @param windowMin low end of the smoothed window that was mapped to 0.0
 * @param windowMax high end, mapped to 1.0
 */
data class DepthStats(
    val rawMin: Float,
    val rawMax: Float,
    val windowMin: Float,
    val windowMax: Float
) {
    /** True while the smoothed window is still chasing the raw range. */
    val settled: Boolean get() = abs(rawMin - windowMin) < 1e-3f && abs(rawMax - windowMax) < 1e-3f
}
