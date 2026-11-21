# Field Document: buffer_paint

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Buffer paint — editing a running reaction-diffusion

<!-- field-box id="buffer_paint_gray_scott" index="0" -->

```edn [field-box id="buffer_paint_gray_scott" role="properties"]
{"id" "buffer_paint_gray_scott" "index" 0 "name" "Buffer paint — editing a running reaction-diffusion" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Buffer paint — editing a running reaction-diffusion" "_tombstone" false "frame" #afield/rect [80.0 80.0 380.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "buffer_paint_gray_scott_code_.kt" "lines" 80 "characters" 2934 "sha256" "19fa4a6d342f5bb06a5ca7089ec0397707240080c7475a8fa71462589a6db698"}]}
```

```kotlin [field-property box="buffer_paint_gray_scott" name="code" path="buffer_paint_gray_scott_code_.kt"]
import afield.media.image.*

val n = 160
val u = FloatArray(n * n) { 1f }
val v = FloatArray(n * n)
val un = FloatArray(n * n)
val vn = FloatArray(n * n)

// The canvas is a view onto v, the simulation's own state: no copy is made.
val canvas = bufferCanvas(v, width = n, wrapX = true, wrapY = true)
canvas.zoom = 2
canvas.map = ValueMap.Linear(0.0, 0.5) // v lives in 0..~0.45; said so in the caption
canvas.paint(80.0, 80.0, radius = 6.0, value = 0.5f, hardness = 1.0)

val feed = 0.037f
val kill = 0.06f
fun step() {
    for (y in 0 until n) for (x in 0 until n) {
        val i = y * n + x
        val l = (x + n - 1) % n + y * n
        val r = (x + 1) % n + y * n
        val t = x + ((y + n - 1) % n) * n
        val b = x + ((y + 1) % n) * n
        val lu = u[l] + u[r] + u[t] + u[b] - 4 * u[i]
        val lv = v[l] + v[r] + v[t] + v[b] - 4 * v[i]
        val uvv = u[i] * v[i] * v[i]
        un[i] = u[i] + 0.2f * lu - uvv + feed * (1 - u[i])
        vn[i] = v[i] + 0.1f * lv + uvv - (feed + kill) * v[i]
    }
    un.copyInto(u)
    vn.copyInto(v)
}

/*
Edits scheduled against the running simulation, by step number. Each one lands in
empty space (u = 1, v = 0) or on pattern the seed has already grown, and the
reaction takes it from there.
*/
val edits = mapOf(
    150 to
        ("stroke" to
            {
                canvas.stroke(
                    listOf(15.0 to 20.0, 60.0 to 35.0, 140.0 to 18.0),
                    radius = 3.0,
                    value = floatArrayOf(0.5f),
                )
            }),
    500 to ("clone" to { canvas.clone(CellRect(60, 60, 40, 40), 8, 112) }),
    850 to ("blur" to { canvas.blur(CellRect(60, 60, 40, 40), sigma = 4.0) }),
    1200 to ("motion blur" to { canvas.motionBlur(CellRect(0, 105, n, 50), length = 31, dx = 1.0, dy = 0.0) }),
    1550 to ("fill" to { canvas.fill(CellRect(104, 30, 12, 120), 0f) }),
)

// A small copy of the state just after each edit, viewed through its own BufferCanvas.
val strip = mutableListOf<String>()
frames {
    var s = 0
    var next = System.nanoTime()
    while (frame() && s < 2200) {
        if (System.nanoTime() < next) continue
        next += 30_000_000 // ~33 frames a second, 5 steps a frame
        repeat(5) {
            step()
            s++
            edits[s]?.let { (what, edit) ->
                edit()
                val after = bufferCanvas(v.copyOf(), width = n)
                after.map = canvas.map
                strip +=
                    "<div style='display:inline-block;margin:0 6px 6px 0'><div>step $s: $what</div>" +
                        "<img src='data:image/png;base64," +
                        java.util.Base64.getEncoder().encodeToString(after.png()) +
                        "' style='image-rendering:pixelated'/></div>"
                display("edits", strip.joinToString(""))
            }
        }
        display("state", canvas.toHTML() + "<pre>step $s</pre>")
    }
}
```

## 2. Buffer paint — paste and motion-blur in a delay line

<!-- field-box id="buffer_paint_delay_line" index="1" -->

```edn [field-box id="buffer_paint_delay_line" role="properties"]
{"id" "buffer_paint_delay_line" "index" 1 "name" "Buffer paint — paste and motion-blur in a delay line" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Buffer paint — paste and motion-blur in a delay line" "_tombstone" false "frame" #afield/rect [900.0 80.0 380.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "buffer_paint_delay_line_code_.kt" "lines" 51 "characters" 2260 "sha256" "e4afac46eb00cfda709868f58b386cbbd37facd12f30c10667471345dc336954"}]}
```

```kotlin [field-property box="buffer_paint_delay_line" name="code" path="buffer_paint_delay_line_code_.kt"]
import afield.media.image.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.*

val rate = 44100
val ring = FloatArray(rate / 2) // a half-second delay line
// A ring is one long row; viewed as rows of 210 samples it becomes a picture.
val row = 210

// Fill it with what a delay would hold: three decaying plucks.
for (k in 0 until 3) {
    val start = k * 5000
    for (i in 0 until 4000) ring[start + i] =
        (0.8 * exp(-i / 1500.0) * sin(i * 2 * PI * (220.0 * (k + 1)) / rate)).toFloat()
}
val before = ring.copyOf()

val view = bufferCanvas(ring, width = row, wrapX = false)
view.map = ValueMap.Signed(1.0)
view.zoom = 2

// The same storage as a single wrapped row, for edits along time.
val timeline = bufferCanvas(ring, width = ring.size, wrapX = true)
val pluck = timeline.copy(CellRect(0, 0, 4000, 1))
timeline.paste(pluck, 16000, 0, opacity = 0.7) // a fourth, quieter pluck
timeline.motionBlur(CellRect(5000, 0, 4000, 1), length = 61) // smear the second: a low-pass

fun rms(a: FloatArray, from: Int, len: Int) = sqrt((from until from + len).sumOf { a[it].toDouble() * a[it] } / len)
println("pluck 2 rms before %.4f after %.4f".format(rms(before, 5000, 4000), rms(ring, 5000, 4000)))
println("pasted region rms before %.4f after %.4f".format(rms(before, 16000, 4000), rms(ring, 16000, 4000)))

fun wav(samples: FloatArray): String {
    val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
    samples.forEach { pcm.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
    val out = ByteArrayOutputStream()
    val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    h.put("RIFF".toByteArray()).putInt(36 + pcm.capacity()).put("WAVEfmt ".toByteArray())
    h.putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
    h.put("data".toByteArray()).putInt(pcm.capacity())
    out.write(h.array())
    out.write(pcm.array())
    return "<audio controls src='data:audio/wav;base64," +
        Base64.getEncoder().encodeToString(out.toByteArray()) +
        "'></audio>"
}

display("ring", view.toHTML())
display("listen", "<p>before</p>" + wav(before) + "<p>after</p>" + wav(ring))
```

