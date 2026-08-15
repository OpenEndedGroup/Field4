import afield.onnx.ClipImage
import afield.onnx.DepthAnything
import afield.util.PngEncoder
import org.bytedeco.javacv.OpenCVFrameConverter
import org.bytedeco.javacv.OpenCVFrameGrabber
import org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGR2RGBA
import org.bytedeco.opencv.global.opencv_imgproc.COLOR_BGRA2RGBA
import org.bytedeco.opencv.global.opencv_imgproc.COLOR_GRAY2RGBA
import org.bytedeco.opencv.global.opencv_imgproc.cvtColor
import org.bytedeco.opencv.opencv_core.Mat
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Baked at macro-expansion time: each clip("...") becomes a 512-float constant in this
// box's attic (look at the attic after the first run). The 242MB text encoder loads once,
// ever — never again on a re-run, and never in a future session.
val prompts = mapOf(
    "a person at a desk" to clip("a person sitting at a desk"),
    "an empty room"      to clip("an empty room"),
    "a close-up face"    to clip("a close-up of a face"),
    "a hand up close"    to clip("a hand held up to the camera")
)

val grabber = OpenCVFrameGrabber(0)
grabber.start()
try {
    val conv = OpenCVFrameConverter.ToMat()
    // Discard the first few frames: auto-exposure and white balance need a moment, and
    // frame 0 is often black even on a camera that is working perfectly.
    var mat: Mat? = null
    repeat(8) { grabber.grab()?.let { f -> mat = conv.convert(f) } }
    val m = requireNotNull(mat) {
        "camera delivered no frame — on macOS check Privacy & Security > Camera for the app " +
            "that launched Field, and restart it (a cached denial survives until you do)"
    }
    val w = m.cols()
    val h = m.rows()

    val rgbaMat = Mat()
    when (m.channels()) {
        4 -> cvtColor(m, rgbaMat, COLOR_BGRA2RGBA)
        3 -> cvtColor(m, rgbaMat, COLOR_BGR2RGBA)
        else -> cvtColor(m, rgbaMat, COLOR_GRAY2RGBA)
    }
    val bytes = w * h * 4
    // JavaCPP leaves Mat.data()'s capacity unset, so asByteBuffer() hands back an EMPTY
    // buffer and a bounds-checked copy silently moves nothing -- which looks exactly like a
    // black camera. Wrap the raw address instead, as WebcamTexture.copyMatToBuffer does.
    val src = MemoryUtil.memByteBuffer(rgbaMat.data().address(), bytes)
    val rgba = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    rgba.put(src.duplicate())
    rgba.rewind()
    rgbaMat.release()

    // adaptation = 1f: normalise against this frame's own range, since each press is a
    // fresh start rather than a continuous stream.
    val depth = DepthAnything(width = 266, height = 266, adaptation = 1f)
    val stats = depth.infer(rgba, w, h)
    val embedding = ClipImage().encode(rgba, w, h)
    val ranked = embedding.classify(prompts)

    // Camera frame beside its depth map, both scaled to 360 tall.
    val outH = 360
    val camOutW = (w.toDouble() / h * outH).toInt()
    val depthOutW = outH
    val outW = camOutW + depthOutW
    val px = IntArray(outW * outH)
    for (y in 0 until outH) {
        val sy = (y.toDouble() / outH * h).toInt().coerceIn(0, h - 1)
        for (x in 0 until camOutW) {
            val sx = (x.toDouble() / camOutW * w).toInt().coerceIn(0, w - 1)
            val o = (sy * w + sx) shl 2
            val r = rgba.get(o).toInt() and 0xff
            val g = rgba.get(o + 1).toInt() and 0xff
            val b = rgba.get(o + 2).toInt() and 0xff
            px[y * outW + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    val d = depth.depthFp16.asShortBuffer()
    for (y in 0 until outH) {
        val sy = (y.toDouble() / outH * depth.height).toInt().coerceIn(0, depth.height - 1)
        for (x in 0 until depthOutW) {
            val sx = (x.toDouble() / depthOutW * depth.width).toInt().coerceIn(0, depth.width - 1)
            val t = ai.onnxruntime.platform.Fp16Conversions
                .fp16ToFloat(d.get(sy * depth.width + sx)).coerceIn(0f, 1f)
            val r = (255 * (t * 1.6f).coerceIn(0f, 1f)).toInt()
            val g = (255 * ((t - 0.25f) * 1.6f).coerceIn(0f, 1f)).toInt()
            val b = (255 * ((t - 0.6f) * 2.2f).coerceIn(0f, 1f)).toInt()
            px[y * outW + camOutW + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
    val b64 = java.util.Base64.getEncoder().encodeToString(PngEncoder.encode(px, outW, outH))

    val rows = ranked.joinToString("") { (label, p) ->
        val pct = (p * 100).toInt()
        "<tr><td style='padding:2px 10px 2px 0'>$label</td>" +
            "<td style='width:180px'><div style='height:9px;background:#7fd1ff;width:${pct}%'></div></td>" +
            "<td style='padding-left:8px;color:#9fb6c9'>${"%.1f".format(p * 100)}%</td></tr>"
    }
    display("main", """
        <div style="font-family:inherit;color:#dfe7ee">
          <img src="data:image/png;base64,$b64" style="width:100%;border-radius:3px"/>
          <div style="margin:8px 0 4px;color:#8fa6b8;font-size:12px">
            ${w}x$h &middot; depth ${depth.lastInferenceMs}ms, raw [${"%.2f".format(stats.rawMin)}, ${"%.2f".format(stats.rawMax)}]
            &middot; brighter = nearer
          </div>
          <table style="border-collapse:collapse;font-size:13px">$rows</table>
        </div>
    """.trimIndent())
    println("depth ${depth.lastInferenceMs}ms  best=${ranked.first().first} ${"%.1f".format(ranked.first().second * 100)}%")
} finally {
    runCatching { grabber.stop() }
    runCatching { grabber.release() }
}
