package afield.examples.onnx

import afield.onnx.ClipImage
import afield.onnx.ClipText
import afield.onnx.ClipVector
import afield.onnx.DepthAnythingWorker
import afield.onnx.OnnxRuntime
import afield.util.PngEncoder
import org.bytedeco.javacv.OpenCVFrameConverter
import org.bytedeco.javacv.OpenCVFrameGrabber
import org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2RGBA
import org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGRA2RGBA
import org.bytedeco.opencv.global.opencv_imgproc.COLOR_GRAY2RGBA
import org.bytedeco.opencv.global.opencv_imgcodecs.imread
import org.bytedeco.opencv.global.opencv_imgproc.cvtColor
import org.bytedeco.opencv.opencv_core.Mat
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Depth and CLIP, on real webcam frames.
 *
 * Not a `FIELD_TEST`: it needs a camera and a human to point it at something, so it cannot run in the
 * smoke catalog. It exists to answer the one question the smokes cannot — *does this work on an actual
 * image* — and to make the answer visible, by writing a side-by-side PNG of the frame and its depth map.
 *
 * ```
 * run-jvm-main afield.examples.onnx.WebcamPerceptionDemo
 * ```
 *
 * Arguments, all optional and positional: output PNG path, camera index, frame count.
 *
 * There is no Vulkan here on purpose. `WebcamTexture` needs a `Renderer`, and the point of this demo is
 * the perception, not the presentation — the CPU frame buffer a camera produces is exactly what the
 * models want, which is the same observation that made
 * [afield.four.texture.video.WebcamTexture.withLatestFrame] worth adding.
 */
object WebcamPerceptionDemo {

    /** Prompts to classify each frame against. Deliberately a scene-level set, not object labels. */
    private val PROMPTS = listOf(
        "a person sitting at a desk",
        "an empty room",
        "a close-up of a face",
        "a computer screen",
        "a dark or blurry image"
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val outPath = Paths.get(args.getOrNull(0) ?: "/tmp/field-webcam-perception.png")
        val source = args.getOrNull(1) ?: "0"
        val frameCount = args.getOrNull(2)?.toIntOrNull() ?: 24

        // Encode the prompts before touching the camera, so a slow first model load is not blamed on
        // the grabber and the camera light is not on any longer than necessary.
        print("encoding ${PROMPTS.size} prompts… ")
        val startedText = System.nanoTime()
        val prompts: Map<String, ClipVector> = ClipText.encodeAll(PROMPTS)
        println("${(System.nanoTime() - startedText) / 1_000_000}ms")

        val cameraIndex = source.toIntOrNull()
        if (cameraIndex == null) {
            // A still image, for when there is no camera to be had — including the case that matters on
            // macOS, where the camera exists but this process has not been granted access to it.
            runOnStill(Paths.get(source), outPath, prompts)
            return
        }
        println("webcam perception: camera=$cameraIndex frames=$frameCount out=$outPath")
        val grabber = OpenCVFrameGrabber(cameraIndex)
        val converter = OpenCVFrameConverter.ToMat()
        try {
            grabber.start()
        } catch (t: Throwable) {
            System.err.println("could not open camera $cameraIndex: ${t.message ?: t.javaClass.name}")
            System.err.println(
                "On macOS the camera grant belongs to the *responsible app* — the outermost application " +
                    "in this process's ancestry, not the JVM and not necessarily a terminal. Grant Camera " +
                    "to the app named below in System Settings > Privacy & Security > Camera, then restart " +
                    "that app (a cached denial survives until it does)."
            )
            responsibleApp()?.let { System.err.println("  responsible app: $it") }
                ?: System.err.println("  (could not determine the responsible app from the process tree)")
            OnnxRuntime.closeAll()
            kotlin.system.exitProcess(2)
        }

        try {
            val camW = grabber.imageWidth
            val camH = grabber.imageHeight
            println("camera opened: ${camW}x$camH")

            val clipImage = ClipImage()
            val depth = DepthAnythingWorker(width = 266, height = 266)
            var latestRgba: ByteBuffer? = null
            var actualW = 0
            var actualH = 0
            var nonBlackFrames = 0

            try {
                repeat(frameCount) { frameIndex ->
                    val frame = grabber.grab()
                    if (frame == null) {
                        println("  frame $frameIndex: null")
                        return@repeat
                    }
                    val mat = converter.convert(frame) ?: return@repeat
                    val w = mat.cols()
                    val h = mat.rows()
                    if (w <= 0 || h <= 0) return@repeat

                    val rgba = toRgba(mat, w, h)
                    latestRgba = rgba
                    actualW = w
                    actualH = h

                    // A camera that is open but blocked hands back solid black; say so rather than
                    // reporting confident nonsense about an all-zero image.
                    val brightness = meanLuma(rgba, w, h)
                    if (brightness > 2.0) nonBlackFrames++

                    depth.submit(rgba, w, h)
                    val embedding = clipImage.encode(rgba, w, h)
                    val ranked = embedding.classify(prompts)
                    val depthReady = depth.consume()
                    val stats = depth.lastStats

                    // Only every few frames, so the log stays readable.
                    if (frameIndex % 4 == 0 || frameIndex == frameCount - 1) {
                        val best = ranked.first()
                        println(
                            "  frame %2d  luma=%5.1f  clip=%-28s %.3f  clip=%3dms  depth=%s"
                                .format(
                                    frameIndex, brightness, "\"${best.first}\"", best.second,
                                    clipImage.lastInferenceMs,
                                    if (stats == null) "pending" else
                                        "%3dms raw=[%.2f, %.2f]%s".format(
                                            depth.lastInferenceMs, stats.rawMin, stats.rawMax,
                                            if (depthReady) " new" else ""
                                        )
                                )
                        )
                    }
                }

                // Let the last submitted frame finish so the picture matches the final log line.
                val deadline = System.nanoTime() + 20_000_000_000L
                while (!depth.consume() && System.nanoTime() < deadline) Thread.sleep(20)

                println(
                    "frames with signal: $nonBlackFrames/$frameCount   " +
                        "depth inferred=${depth.framesInferred} dropped=${depth.framesDropped}"
                )
                if (nonBlackFrames == 0) {
                    System.err.println(
                        "every frame was black — the camera opened but delivered no image. " +
                            "That is what a macOS camera-permission denial looks like from Java."
                    )
                }

                val rgba = latestRgba
                if (rgba != null) {
                    val embedding = clipImage.encode(rgba, actualW, actualH)
                    report(embedding, prompts)

                    val png = montage(rgba, actualW, actualH, depth.depthFp16, depth.width, depth.height)
                    Files.createDirectories(outPath.toAbsolutePath().parent)
                    Files.write(outPath, png)
                    println()
                    println("wrote ${png.size / 1024}KB to $outPath")
                }
            } finally {
                depth.close()
            }
        } finally {
            runCatching { grabber.stop() }
            runCatching { grabber.release() }
            OnnxRuntime.closeAll()
        }
    }

    /**
     * The outermost `.app` bundle in this process's ancestry — who macOS holds responsible for a TCC
     * request, and therefore who has to be granted camera access.
     *
     * Worth computing rather than guessing: a JVM launched from an agent session is a descendant of the
     * *agent's host application*, so telling someone to grant permission to "your terminal" sends them
     * to the wrong row in System Settings.
     */
    private fun responsibleApp(): String? {
        var handle: ProcessHandle? = ProcessHandle.current()
        var outermost: String? = null
        var hops = 0
        while (handle != null && hops < 32) {
            val command = handle.info().command().orElse(null)
            if (command != null && ".app/" in command) {
                outermost = command.substringBefore(".app/") + ".app"
            }
            handle = handle.parent().orElse(null)
            hops++
        }
        return outermost
    }

    /** The same analysis as the camera path, on one image decoded from disk. */
    private fun runOnStill(imagePath: Path, outPath: Path, prompts: Map<String, ClipVector>) {
        println("still-image perception: in=$imagePath out=$outPath")
        val mat = imread(imagePath.toAbsolutePath().toString())
        require(!mat.empty()) { "could not decode an image from $imagePath" }
        val w = mat.cols()
        val h = mat.rows()
        println("image: ${w}x$h, ${mat.channels()} channels")

        val rgba = toRgba(mat, w, h)
        mat.release()
        mat.close()

        // An all-zero buffer here means the decode or the RGBA conversion failed, not that the picture
        // is dark. Every number below would still be produced, and would still look plausible, so fail
        // instead of reporting confident results about nothing.
        val luma = meanLuma(rgba, w, h)
        check(luma > 0.0) { "decoded image is entirely black — RGBA conversion produced no pixels" }

        val clipImage = ClipImage()
        val depth = DepthAnythingWorker(width = 266, height = 266)
        try {
            depth.submit(rgba, w, h)
            val deadline = System.nanoTime() + 60_000_000_000L
            while (!depth.consume() && System.nanoTime() < deadline) Thread.sleep(20)
            depth.lastError?.let { error("depth failed: ${it.message}") }

            val embedding = clipImage.encode(rgba, w, h)
            println("luma=%.1f  clip=%dms  depth=%dms raw=[%.2f, %.2f]".format(
                luma, clipImage.lastInferenceMs, depth.lastInferenceMs,
                depth.lastStats?.rawMin ?: Float.NaN, depth.lastStats?.rawMax ?: Float.NaN
            ))
            report(embedding, prompts)

            val png = montage(rgba, w, h, depth.depthFp16, depth.width, depth.height)
            Files.createDirectories(outPath.toAbsolutePath().parent)
            Files.write(outPath, png)
            println()
            println("wrote ${png.size / 1024}KB to $outPath")
        } finally {
            depth.close()
            OnnxRuntime.closeAll()
        }
    }

    /** Zero-shot ranking plus two semantic axes, the part that is genuinely CLIP-specific. */
    private fun report(embedding: ClipVector, prompts: Map<String, ClipVector>) {
        println()
        println("zero-shot over ${prompts.size} prompts:")
        embedding.classify(prompts).forEach { (label, p) ->
            println("  %-32s %6.2f%%".format(label, p * 100))
        }
        println()
        println("semantic axes on the same frame (projection onto a direction in meaning):")
        axis(prompts, "an empty room", "a person sitting at a desk").let {
            println("  empty-room → person-at-desk   %+.4f".format(embedding.project(it)))
        }
        axis(prompts, "a dark or blurry image", "a computer screen").let {
            println("  dark → screen                 %+.4f".format(embedding.project(it)))
        }
    }

    private fun axis(prompts: Map<String, ClipVector>, from: String, to: String): ClipVector =
        prompts.getValue(from) axisTo prompts.getValue(to)

    private fun toRgba(mat: Mat, w: Int, h: Int): ByteBuffer {
        val rgbaMat = Mat()
        when (mat.channels()) {
            4 -> cvtColor(mat, rgbaMat, COLOR_BGRA2RGBA)
            3 -> cvtColor(mat, rgbaMat, COLOR_BGR2RGBA)
            1 -> cvtColor(mat, rgbaMat, COLOR_GRAY2RGBA)
            else -> error("unsupported channel count ${mat.channels()}")
        }
        val bytes = w * h * 4
        check(rgbaMat.isContinuous) { "expected a continuous Mat, got step ${rgbaMat.step()}" }

        val pointer = rgbaMat.data() ?: error("converted Mat had no backing pixel buffer")
        // JavaCPP leaves the capacity of Mat.data() unset, so `asByteBuffer()` returns an EMPTY buffer
        // and any bounds-checked copy out of it silently moves zero bytes -- which reads downstream as
        // a black camera rather than as a bug. Wrap the raw address with the size computed here, the
        // way WebcamTexture.copyMatToBuffer already does.
        val src = MemoryUtil.memByteBuffer(pointer.address(), bytes)
        val out = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        out.put(src.duplicate())
        out.rewind()

        rgbaMat.release()
        rgbaMat.close()
        return out
    }

    private fun meanLuma(rgba: ByteBuffer, w: Int, h: Int): Double {
        var total = 0L
        var count = 0
        // Sample a grid rather than every pixel; this is only a "is there an image at all" check.
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val o = (y * w + x) shl 2
                val r = rgba.get(o).toInt() and 0xff
                val g = rgba.get(o + 1).toInt() and 0xff
                val b = rgba.get(o + 2).toInt() and 0xff
                total += (r * 299 + g * 587 + b * 114) / 1000
                count++
                x += 8
            }
            y += 8
        }
        return if (count == 0) 0.0 else total.toDouble() / count
    }

    /** Camera frame on the left, colour-mapped depth on the right, scaled to the same height. */
    private fun montage(
        rgba: ByteBuffer,
        camW: Int,
        camH: Int,
        depthFp16: ByteBuffer,
        depthW: Int,
        depthH: Int
    ): ByteArray {
        val outH = camH
        val depthOutW = (depthW.toDouble() / depthH * outH).roundToInt().coerceAtLeast(1)
        val outW = camW + depthOutW
        val pixels = IntArray(outW * outH)

        for (y in 0 until outH) {
            for (x in 0 until camW) {
                val o = (y * camW + x) shl 2
                val r = rgba.get(o).toInt() and 0xff
                val g = rgba.get(o + 1).toInt() and 0xff
                val b = rgba.get(o + 2).toInt() and 0xff
                pixels[y * outW + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val depthShorts = depthFp16.asShortBuffer()
        for (y in 0 until outH) {
            val sy = (y.toDouble() / outH * depthH).toInt().coerceIn(0, depthH - 1)
            for (x in 0 until depthOutW) {
                val sx = (x.toDouble() / depthOutW * depthW).toInt().coerceIn(0, depthW - 1)
                val t = ai.onnxruntime.platform.Fp16Conversions.fp16ToFloat(depthShorts.get(sy * depthW + sx))
                pixels[y * outW + camW + x] = ramp(t.coerceIn(0f, 1f))
            }
        }
        return PngEncoder.encode(pixels, outW, outH)
    }

    /** A compact dark-to-hot ramp; near is bright because the model's output is inverse depth. */
    private fun ramp(t: Float): Int {
        val r = (255 * (t * 1.6f).coerceIn(0f, 1f)).toInt()
        val g = (255 * ((t - 0.25f) * 1.6f).coerceIn(0f, 1f)).toInt()
        val b = (255 * ((t - 0.6f) * 2.2f).coerceIn(0f, 1f)).toInt()
        return (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }
}
