# Field Document: splat_sequence_streaming

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. Splat Sequence Streaming

<!-- field-box id="splat_sequence_streaming" index="0" -->

```edn [field-box id="splat_sequence_streaming" role="properties"]
{"id" "splat_sequence_streaming" "index" 0 "name" "Splat Sequence Streaming" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Splat Sequence Streaming" "_tombstone" false "frame" #afield/rect [60.0 60.0 320.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "splat_sequence_streaming_code_.kt" "lines" 134 "characters" 5897 "sha256" "4d3e6bcc0feff56aecab08750bc90478f8db8e5fce1b22d724559468a81d00b0"}]}
```

```kotlin [field-property box="splat_sequence_streaming" name="code" path="splat_sequence_streaming_code_.kt"]
import afield.four.Renderer
import afield.four.Target
import afield.four.fabricated.GaussianSplatRenderer
import afield.four.fabricated.GaussianSplatRenderer.Blend
import afield.four.fabricated.GaussianSplatRenderer.SortMode
import afield.four.fabricated.GaussianSplatRenderer.SplatSource
import afield.four.fabricated.SplatSequence
import afield.four.fabricated.SplatTransport
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import java.io.File

// ---- knobs: edit and re-run this box ------------------------------------
// No committed sample: point this at your own frames. To make some, take a .ply per
// frame and run PlayCanvas splat-transform over each:
//     splat-transform frame_0001.ply frame_0001.compressed.ply
val sequenceDir = File(System.getProperty("user.home"), "Downloads/splat-sequence")
val slots = 15              // sources shown at once, one per direction around you
val fps = 12.0              // source frame rate, independent of the display rate
val sortEachFrame = true    // false + an order-independent blend == no sort at all
val blend = Blend.Over      // Over / Additive / Screen / Multiply / Max
val splatSize = 2.5f        // these splats are ~2 mm across, so they need help
val alphaBoost = 1.6f
val arcDegrees = 200.0      // spread the slots over this much of the circle
val cameraBack = 2.6f
val cameraUp = 1.1f
// 2.2 is sRGB-to-linear and is CORRECT: SwapChain prefers B8G8R8A8_SRGB, so the target
// encodes back on write. Lowering it double-encodes -- brighter than the data, which is a
// legitimate artistic move but not a fix for a dark scene.
val colorGamma = 2.2f
// -----------------------------------------------------------------------

val renderer: Renderer = fromContext()

if (!sequenceDir.isDirectory) {
    println("no sequence at $sequenceDir")
    println("Point `sequenceDir` above at a directory of .compressed.ply frames.")
    println("PlayCanvas splat-transform writes them: splat-transform in.ply out.compressed.ply")
    println("Any 3DGS .ply will do as a source; a single frame repeated also works.")
} else {

val sequence = SplatSequence.fromDirectory(sequenceDir, cacheFrames = 160)
val capacity = sequence.peakWindowSplats(slots)
println("sequence ${sequence.frameCount} frames; capacity $capacity splats")
println("  (the true peak over any $slots consecutive frames, not the $slots largest anywhere)")

// Each capture is a depth map from a camera at the origin looking down +z, so its content
// already sits ~1-1.6 in front of its own origin. A ring around the viewer is therefore
// fifteen rotations about Y and no translation: every panel lands at its natural distance.
fun yRotation(radians: Double): Matrix4f {
    val c = Math.cos(radians).toFloat()
    val s = Math.sin(radians).toFloat()
    // Matrix4f is column-major, m<column><row>: each group of four is a COLUMN.
    return Matrix4f().set(
        c, 0f, -s, 0f,
        0f, 1f, 0f, 0f,
        s, 0f, c, 0f,
        0f, 0f, 0f, 1f
    )
}
val arc = Math.toRadians(arcDegrees)
val ring = (0 until slots).map { i ->
    yRotation(-arc / 2.0 + arc * i / (slots - 1).coerceAtLeast(1))
}

val cloud = GaussianSplatRenderer.streaming(
    capacity = capacity,
    renderer = renderer,
    config = GaussianSplatRenderer.Config(
        sortMode = SortMode.None,   // packInto writes back-to-front instead
        blend = blend,
        sizeScale = splatSize,
        colorGamma = colorGamma
    ),
    name = "splatStream"
)
// Depth-map convention: +z forward, +y DOWN, hence up = (0, -1, 0).
cloud.camera.at = Vector3f(0f, -cameraUp, -cameraBack)
cloud.camera.target = Vector3f(0f, 0f, 1.2f)
cloud.camera.up = Vector3f(0f, -1f, 0f)
cloud.alphaScale = alphaBoost
cloud.install()

val scratch = GaussianSplatRenderer.PackScratch()

// ---- the play head. Select a line and run it as a fragment while this plays ----------
// transport.playing = false          pause
// transport.normalized = 0.42        scrub to 42% of the shot
// transport.seek(60.0)               scrub to frame 60
// transport.scrubBy(-1.0)            step back one frame
// transport.speed = -0.25            slow reverse
// transport.loop = false             clamp at the ends instead of wrapping
val transport = SplatTransport(sequence.frameCount, fps = fps)
// -------------------------------------------------------------------------------------

var packedMs = 0.0
var decodes = 0
var displayFrames = 0

renderer.renderList.preTargets["__splat_feed__"] = Target { _, _ ->
    displayFrames++
    // False on the display frames where the resolved frame did not move -- six in seven at
    // 12 fps into 90 Hz -- and false for a scrub that stays inside one frame. Each false is
    // fifteen files not re-decoded to write identical bytes.
    if (!transport.advance(System.nanoTime())) return@Target
    val at = transport.frame
    val sources = (0 until slots).map { slot ->
        SplatSource(sequence.raw(at + slot), ring[slot])
    }
    val t0 = System.nanoTime()
    cloud.reupload(
        sources,
        sortAlong = if (sortEachFrame) Vector3f(0f, 0f, 1f) else null,
        scratch = scratch
    )
    packedMs = (System.nanoTime() - t0) / 1e6
    decodes++
}

var reportedAt = System.nanoTime()
renderer.renderList.preTargets["__splat_report__"] = Target { _, _ ->
    val now = System.nanoTime()
    if (now - reportedAt < 1_000_000_000L) return@Target
    reportedAt = now
    println("frame ${transport.frame}/${sequence.frameCount}  " +
            "$decodes decode(s) in $displayFrames display frame(s)  " +
            "last pack %.1f ms  %s".format(packedMs, sequence.stats))
    decodes = 0; displayFrames = 0
}

println("streaming ${sequence.frameCount} frames at $fps fps, sort=$sortEachFrame")
println("blend=${if (blend.orderIndependent) "order-independent, the sort is optional" else "Over, needs the back-to-front order"}")

}
```

