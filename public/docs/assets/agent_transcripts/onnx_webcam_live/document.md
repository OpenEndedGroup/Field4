# Field Document: onnx_webcam_live

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. Webcam perception

<!-- field-box id="onnx_webcam" index="0" -->

```edn [field-box id="onnx_webcam" role="properties"]
{"id" "onnx_webcam" "index" 0 "name" "Webcam perception" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Webcam perception" "_tombstone" false "frame" #afield/rect [80.0 80.0 360.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "onnx_webcam_code_.kt" "lines" 114 "characters" 5308 "sha256" "390065f4d018fd10e8759f1c3db2db0d46dabcbcc9fc7d3049cb290d6433ff5e"}]}
```

```kotlin [field-property box="onnx_webcam" name="code" path="onnx_webcam_code_.kt"]
import afield.onnx.ClipImage
import afield.onnx.clipEncode
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

// Encoded once, here, into a val: the text tower is not in the per-frame path. First call in
// a session loads the 242MB encoder; after that each prompt is a few milliseconds.
val prompts = mapOf(
    "a person at a desk" to clipEncode("a person sitting at a desk"),
    "an empty room"      to clipEncode("an empty room"),
    "a close-up face"    to clipEncode("a close-up of a face"),
    "a hand up close"    to clipEncode("a hand held up to the camera")
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

    // `bar(max = 1.0)` so the bars read as "of the whole", not "of the best one this
    // frame" — a top-1 at 12% should look like 12%, not like a full bar.
    val top = Table(listOf("label", "confidence"))
        .rows(ranked.map { (label, p) -> listOf(label, p) })
        .role("confidence", CellRole.ACCENT)
        .bar("confidence", max = 1.0, width = 180)
        .format("confidence") { "%.1f%%".format((it as Number).toDouble() * 100) }

    markdown("main", """
        <img src="data:image/png;base64,$b64" style="width:100%;border-radius:3px"/>

        {muted:${w}x$h · depth ${depth.lastInferenceMs}ms, raw [${"%.2f".format(stats.rawMin)}, ${"%.2f".format(stats.rawMax)}] · brighter = nearer}

        ${top.h}
    """)
    println("depth ${depth.lastInferenceMs}ms  best=${ranked.first().first} ${"%.1f".format(ranked.first().second * 100)}%")
} finally {
    runCatching { grabber.stop() }
    runCatching { grabber.release() }
}
```

