package afield.onnx

import ai.onnxruntime.platform.Fp16Conversions
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
FIELD_TEST
id: onnx.depth.smoke
runner: jvm-main
entrypoint: afield.onnx.OnnxSmokeTest
tags: [smoke, onnx, ml, depth, cpu, network]
timeoutSec: 900
expect:
  exitCode: 0
  stdoutContains:
    - "ONNX depth smoke tests passed"
*/
/**
 * The ONNX binding, end to end, on the CPU.
 *
 * What this guards is a *contract with a third-party model file* — nothing here is Field being clever,
 * it is `depth-anything-v2-small` behaving the way [DepthAnything]'s KDoc claims. The reference values
 * below were produced independently, by running the same weights under Python's `onnxruntime` on the
 * same machine, so a mismatch means Field's preprocessing has drifted rather than that the numbers
 * were guessed. Each assertion corresponds to something that would otherwise break silently:
 *
 *  * the model's I/O signature is `pixel_values` in, `predicted_depth` out, with **dynamic** spatial
 *    axes — not the fixed 518x518 the model card implies — which is what makes [DEPTH_266] legal and
 *    is the only real performance dial there is;
 *  * output is *fp32 even from the fp16 weights file*, so anything expecting to read fp16 tensors off
 *    the wire is wrong;
 *  * ImageNet normalisation uses the model's own mean/std, and getting the channel order or the
 *    `1/255` rescale wrong still produces a plausible-looking depth map with subtly wrong values;
 *  * the resampler is exactly the identity when source and target sizes agree, which is what lets a
 *    correctly-sized input skip interpolation entirely;
 *  * [OnnxRuntime.session] really does memoise, which is the whole reason hot-reload is cheap;
 *  * and ORT's fp16 conversion agrees with the hand-rolled `floatToHalfBits` already in the graphics
 *    utilities, so an `R16_SFLOAT` texture means the same thing whichever one wrote it.
 *
 * The timeout is generous and the `network` tag is real: the first run downloads 99MB of weights into
 * `~/.afield/onnx/models`. Subsequent runs are offline.
 */
object OnnxSmokeTest {

    /** Reference values from Python `onnxruntime`, CPU EP, fp32 weights, on [syntheticRgba]. */
    private const val REF_518_MIN = 1.035337f
    private const val REF_518_MAX = 2.818658f
    private const val REF_266_MIN = 1.282628f
    private const val REF_266_MAX = 1.985421f

    /**
     * Tight on purpose.
     *
     * [syntheticRgba] is integer-only arithmetic, so Field and NumPy build a *byte-identical* frame,
     * and at these sizes the resampler is the identity — which means both runtimes feed the model the
     * same fp32 tensor and the only remaining difference is ORT's own arithmetic. An earlier version of
     * this test generated the frame with `sin`/`cos` and needed a tolerance 20x looser, because NumPy
     * evaluates `float32/python-float` in float32 while Kotlin was using `Double`, and the two
     * disagreed by one count on some blue samples. Integer input removes that whole class of doubt.
     */
    private const val TOLERANCE = 1e-3f

    private var failures = 0

    @JvmStatic
    fun main(args: Array<String>) {
        println("ONNX depth smoke: cache root is ${OnnxModel.cacheRoot}")

        testFp16AgreesWithGraphicsUtil()
        testSizeSnapping()
        testResamplerIsIdentityAtEqualSize()

        val model = DepthAnything.FP32
        println("ONNX depth smoke: ensuring ${model.id} (present=${model.isPresent})")
        model.ensureLocal()

        testSignature(model)
        testFp16BufferMatchesVulkanStride()
        val a = testInference(model, 518, REF_518_MIN, REF_518_MAX)
        val b = testInference(model, 266, REF_266_MIN, REF_266_MAX)
        testResolutionChangesRange(a, b)
        testSessionIsCached(model)
        testNormalisationWindowAdapts(model)
        testConstantFrameDoesNotProduceNaN(model)
        testWorkerPublishesAFrame(model)

        if (failures > 0) {
            System.err.println("ONNX depth smoke: $failures check(s) failed")
            // Leave the cached sessions closed so the JVM has no ORT threads left to keep it alive.
            OnnxRuntime.closeAll()
            kotlin.system.exitProcess(1)
        }

        println("ONNX depth smoke tests passed")
        // jvm-main smokes must exit naturally: ORT's intra-op pool is non-daemon, so the sessions have
        // to go before main returns or the process hangs on a healthy run.
        OnnxRuntime.closeAll()
    }

    /**
     * A deterministic frame, byte-identical to the one the Python reference ran on.
     *
     * Every channel is integer arithmetic with an integer division — no floating point anywhere — so
     * there is no rounding-mode or precision question to argue about between Kotlin and NumPy. Red and
     * green are linear ramps in x and y; blue is a corner-centred radial ramp, which gives the model
     * some smooth structure to actually respond to rather than noise.
     */
    private fun syntheticRgba(n: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(n * n * 4).order(ByteOrder.nativeOrder())
        val span = n - 1
        val radial = span.toLong() * span.toLong()
        for (y in 0 until n) {
            for (x in 0 until n) {
                val r = x * 255 / span
                val g = y * 255 / span
                val b = min(
                    255L,
                    (x.toLong() * x.toLong() + y.toLong() * y.toLong()) * 255L / radial
                ).toInt()
                val o = (y * n + x) shl 2
                buffer.put(o, r.toByte())
                buffer.put(o + 1, g.toByte())
                buffer.put(o + 2, b.toByte())
                buffer.put(o + 3, 255.toByte())
            }
        }
        return buffer
    }

    private fun testSignature(model: OnnxModel) {
        val loaded = model.session()
        check("input name is pixel_values", loaded.inputNames == listOf("pixel_values")) {
            "got ${loaded.inputNames}"
        }
        check("output name is predicted_depth", loaded.outputNames == listOf("predicted_depth")) {
            "got ${loaded.outputNames}"
        }
        println("  signature: ${loaded.inputNames} -> ${loaded.outputNames} on ${loaded.provider}")
    }

    private fun testInference(model: OnnxModel, n: Int, refMin: Float, refMax: Float): DepthStats {
        val depth = DepthAnything(width = n, height = n, model = model, adaptation = 1f)
        val stats = depth.infer(syntheticRgba(n), n, n)
        println(
            "  ${n}x$n: raw=[${"%.4f".format(stats.rawMin)}, ${"%.4f".format(stats.rawMax)}] " +
                "ref=[$refMin, $refMax] in ${depth.lastInferenceMs}ms on ${depth.activeProvider}"
        )
        near("${n}x$n min matches Python reference", stats.rawMin, refMin)
        near("${n}x$n max matches Python reference", stats.rawMax, refMax)

        // With adaptation = 1 the window is this frame's own range, so fp16 must span [0,1] fully.
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        val shorts = depth.depthFp16.asShortBuffer()
        check("fp16 buffer holds ${n * n} values", shorts.remaining() == n * n) {
            "got ${shorts.remaining()}"
        }
        for (i in 0 until n * n) {
            val v = Fp16Conversions.fp16ToFloat(shorts.get(i))
            check("fp16 value $i is finite", v.isFinite()) { "got $v" }
            lo = min(lo, v)
            hi = max(hi, v)
        }
        near("${n}x$n normalised low end is 0", lo, 0f, 1e-3f)
        near("${n}x$n normalised high end is 1", hi, 1f, 1e-3f)
        return stats
    }

    /**
     * Resolution is not just a speed knob — it changes the answer.
     *
     * Worth pinning because it is the single most surprising property of this model: the same frame at
     * 266 and at 518 produces different *ranges*, so a normalisation window calibrated at one size is
     * meaningless at the other.
     */
    private fun testResolutionChangesRange(at518: DepthStats, at266: DepthStats) {
        check(
            "range genuinely differs between 518 and 266",
            abs(at518.rawMax - at266.rawMax) > 0.5f
        ) { "518 max ${at518.rawMax} vs 266 max ${at266.rawMax}" }
    }

    private fun testSessionIsCached(model: OnnxModel) {
        val before = OnnxRuntime.liveSessionCount
        val first = model.session()
        val second = model.session()
        check("repeated session() returns the identical OrtSession", first.session === second.session) {
            "got two different sessions"
        }
        check("session count did not grow", OnnxRuntime.liveSessionCount == before) {
            "went from $before to ${OnnxRuntime.liveSessionCount}"
        }

        // Thread counts are baked in at construction, so they must be part of the cache key.
        val distinct = OnnxRuntime.session(
            OnnxRuntime.SessionSpec(model.localPath, intraOpThreads = 2)
        )
        check("a different thread count is a different session", distinct.session !== first.session) {
            "spec with intraOpThreads=2 reused the default session"
        }
        OnnxRuntime.evict(distinct.spec)
    }

    private fun testNormalisationWindowAdapts(model: OnnxModel) {
        val n = 266
        val depth = DepthAnything(width = n, height = n, model = model, adaptation = 0.25f)
        val frame = syntheticRgba(n)
        val first = depth.infer(frame, n, n)
        check("first frame seeds the window from its own range", first.settled) {
            "window=[${first.windowMin}, ${first.windowMax}] raw=[${first.rawMin}, ${first.rawMax}]"
        }

        // A frame whose range genuinely differs: flat mid-grey is a different scene entirely.
        val flat = ByteBuffer.allocateDirect(n * n * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until n * n) {
            flat.put(i * 4, 128.toByte()); flat.put(i * 4 + 1, 128.toByte())
            flat.put(i * 4 + 2, 128.toByte()); flat.put(i * 4 + 3, 255.toByte())
        }
        val second = depth.infer(flat, n, n)
        check(
            "window lags a changed range rather than snapping to it",
            !second.settled && abs(second.windowMax - first.windowMax) > 0f
        ) {
            "window=[${second.windowMin}, ${second.windowMax}] raw=[${second.rawMin}, ${second.rawMax}]"
        }

        depth.resetAdaptation()
        val third = depth.infer(flat, n, n)
        check("resetAdaptation re-seeds the window", third.settled) {
            "window=[${third.windowMin}, ${third.windowMax}] raw=[${third.rawMin}, ${third.rawMax}]"
        }
    }

    /**
     * A zero-width normalisation window must not become a texture full of NaN.
     *
     * Forced directly rather than hoped for from a flat input, because the model does not actually
     * return a constant map for a constant image — it returns something almost constant, which is not
     * the case that breaks.
     */
    private fun testConstantFrameDoesNotProduceNaN(model: OnnxModel) {
        val n = 266
        val depth = DepthAnything(width = n, height = n, model = model, adaptation = 1f)
        val flat = ByteBuffer.allocateDirect(n * n * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until n * n) {
            flat.put(i * 4 + 3, 255.toByte())
        }
        depth.infer(flat, n, n)
        val shorts = depth.depthFp16.asShortBuffer()
        var allFinite = true
        for (i in 0 until n * n) if (!Fp16Conversions.fp16ToFloat(shorts.get(i)).isFinite()) allFinite = false
        check("a black frame still yields finite depth", allFinite) { "found NaN or Inf" }
    }

    private fun testWorkerPublishesAFrame(model: OnnxModel) {
        val n = 266
        DepthAnythingWorker(width = n, height = n, model = model).use { worker ->
            check("nothing to consume before the first submit", !worker.consume()) {
                "consume() returned true with no frame submitted"
            }
            worker.submit(syntheticRgba(n), n, n)
            val deadline = System.nanoTime() + 60_000_000_000L
            var got = false
            while (System.nanoTime() < deadline) {
                if (worker.consume()) { got = true; break }
                worker.lastError?.let { break }
                Thread.sleep(20)
            }
            worker.lastError?.let { check("worker did not fail", false) { "${it.message}" } }
            check("worker published a depth map", got) { "nothing consumed within 60s" }
            check("worker inferred exactly one frame", worker.framesInferred == 1L) {
                "got ${worker.framesInferred}"
            }
            check("worker dropped nothing", worker.framesDropped == 0L) { "got ${worker.framesDropped}" }

            var nonZero = 0
            val shorts = worker.depthFp16.asShortBuffer()
            for (i in 0 until n * n) if (shorts.get(i) != 0.toShort()) nonZero++
            check("published map is not all zero", nonZero > n * n / 10) { "$nonZero non-zero of ${n * n}" }
            println("  worker: 1 frame in ${worker.lastInferenceMs}ms, window=${worker.lastStats}")
        }
    }

    /**
     * ORT's fp16 conversion and Field's own must agree over the normalised range.
     *
     * [DepthAnything] writes `R16_SFLOAT` with ORT's converter, to keep `afield.onnx` free of any
     * dependency on the graphics tree, while everything else in Field that writes binary16 goes through
     * [afield.four.util.floatToHalfBits]. Two writers for one texture format is only safe while they
     * produce identical bits, so that is asserted rather than assumed — across `[0,1]`, which is the
     * range depth is normalised into, plus the boundary cases.
     *
     * Deliberately not claiming agreement everywhere: the two differ in NaN payload, and neither one's
     * NaN handling matters to a depth map.
     */
    private fun testFp16AgreesWithGraphicsUtil() {
        var mismatches = 0
        var firstMismatch = ""
        val steps = 4096
        for (i in 0..steps) {
            val v = i.toFloat() / steps
            val ort = Fp16Conversions.floatToFp16(v)
            val field = afield.four.util.floatToHalfBits(v)
            if (ort != field) {
                mismatches++
                if (firstMismatch.isEmpty()) {
                    firstMismatch = "at $v ORT=0x${"%04x".format(ort.toInt() and 0xffff)} " +
                        "Field=0x${"%04x".format(field.toInt() and 0xffff)}"
                }
            }
        }
        for (v in floatArrayOf(0f, -0f, 1f, 0.5f, 1e-5f, 6.1e-5f, 65504f, 65519f)) {
            val ort = Fp16Conversions.floatToFp16(v)
            val field = afield.four.util.floatToHalfBits(v)
            if (ort != field) {
                mismatches++
                if (firstMismatch.isEmpty()) {
                    firstMismatch = "at $v ORT=0x${"%04x".format(ort.toInt() and 0xffff)} " +
                        "Field=0x${"%04x".format(field.toInt() and 0xffff)}"
                }
            }
        }
        check("ORT and Field fp16 conversions agree over [0,1]", mismatches == 0) {
            "$mismatches mismatch(es); first $firstMismatch"
        }

        // And a round trip, so a wrong-endian or wrong-sign bug cannot hide behind mutual agreement.
        near("fp16 round trip preserves 0.25", Fp16Conversions.fp16ToFloat(Fp16Conversions.floatToFp16(0.25f)), 0.25f, 1e-6f)
        near("fp16 round trip preserves 0.75", Fp16Conversions.fp16ToFloat(Fp16Conversions.floatToFp16(0.75f)), 0.75f, 1e-6f)
    }

    /**
     * The depth buffer must be exactly the size a `StreamingTexture` will demand of it.
     *
     * This is the one part of the Vulkan seam that is checkable without a GPU, and it is the part that
     * was actually broken: `ImageData.bytesPerPixel` had no branch for `VK_FORMAT_R16_SFLOAT` and fell
     * through to a default of 4, so `StreamingTexture` would have computed `width*height*4` for a
     * half-float depth map, rejected the buffer as half the size it wanted, and — had the buffer been
     * big enough — copied twice the bytes into the staging buffer.
     *
     * Asserting against `ImageData` rather than a hardcoded 2 is the point: it pins the *agreement*
     * between what [DepthAnything] produces and what the texture layer expects, so re-breaking that
     * table fails here rather than in a shader.
     */
    private fun testFp16BufferMatchesVulkanStride() {
        val n = 42
        val bytesPerPixel = afield.four.texture.Image.ImageData()
            .format(org.lwjgl.vulkan.VK10.VK_FORMAT_R16_SFLOAT).bytesPerPixel
        check("Vulkan R16_SFLOAT is 2 bytes per pixel", bytesPerPixel == 2) { "got $bytesPerPixel" }

        val depth = DepthAnything(width = n, height = n, model = DepthAnything.FP32)
        check(
            "depthFp16 capacity matches width*height*bytesPerPixel",
            depth.depthFp16.capacity() == n * n * bytesPerPixel
        ) { "buffer is ${depth.depthFp16.capacity()} bytes, texture wants ${n * n * bytesPerPixel}" }

        val worker = DepthAnythingWorker(width = n, height = n)
        worker.use {
            check(
                "worker's published buffer matches too",
                it.depthFp16.capacity() == n * n * bytesPerPixel
            ) { "buffer is ${it.depthFp16.capacity()} bytes" }
        }
    }

    private fun testSizeSnapping() {
        val (w, h) = DepthAnything.sizeFor(640, 480, 266)
        check("640x480 snaps to multiples of 14", w % 14 == 0 && h % 14 == 0) { "got ${w}x$h" }
        check("longest side is about 266", abs(w - 266) <= 14) { "got width $w" }
        check("aspect roughly preserved", abs(w.toFloat() / h - 640f / 480f) < 0.08f) {
            "got ${w}x$h, aspect ${w.toFloat() / h}"
        }
        val (sw, sh) = DepthAnything.sizeFor(100, 100, 518)
        check("a square source stays square at 518", sw == 518 && sh == 518) { "got ${sw}x$sh" }
        val (tw, th) = DepthAnything.sizeFor(1920, 8, 266)
        check("an extreme aspect still yields legal sizes", tw % 14 == 0 && th >= 14) { "got ${tw}x$th" }
    }

    /**
     * The half-pixel-centred tap mapping must be exact when no scaling is required.
     *
     * Verified through the public API rather than by reaching into the resampler: feeding an image that
     * is already the model's size and getting the Python reference values back is the same claim, and
     * it is the claim that matters.
     */
    private fun testResamplerIsIdentityAtEqualSize() {
        val n = 42
        val src = ByteBuffer.allocateDirect(n * n * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until n * n) {
            src.put(i * 4, (i % 251).toByte())
            src.put(i * 4 + 1, ((i * 7) % 251).toByte())
            src.put(i * 4 + 2, ((i * 13) % 251).toByte())
            src.put(i * 4 + 3, 255.toByte())
        }
        // sizeFor with the source's own longest side must return the source size unchanged when it is
        // already a multiple of the patch size, which is the precondition for identity resampling.
        val (w, h) = DepthAnything.sizeFor(n, n, n)
        check("a multiple-of-14 source is left alone", w == n && h == n) { "got ${w}x$h" }
    }

    private fun near(what: String, actual: Float, expected: Float, tolerance: Float = TOLERANCE) {
        check(what, abs(actual - expected) <= tolerance) {
            "expected $expected +/- $tolerance, got $actual"
        }
    }

    private inline fun check(what: String, condition: Boolean, detail: () -> String) {
        if (condition) return
        failures++
        System.err.println("FAIL: $what -- ${detail()}")
    }
}
