# Field Document: eagle_flow_explorer

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 - Two frames of reference

<!-- field-box id="eagle_flow_live" index="0" -->

```edn [field-box id="eagle_flow_live" role="properties"]
{"id" "eagle_flow_live" "index" 0 "name" "1 - Two frames of reference" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - Two frames of reference" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "eagle_flow_live_code_.kt" "lines" 44 "characters" 1842 "sha256" "fb2d004cf879e16653eba58f819264eea6cba32a7578562b9372fa019d779561"}]}
```

```kotlin [field-property box="eagle_flow_live" name="code" path="eagle_flow_live_code_.kt"]
import afield.geometry.topology.vectorfield.*

// The eagle's flow (one grid of 4-pixel cells per frame) and the film it was tracked from.
val flow = loadParticleFlow("public/docs/assets/vector_field_topology/eagle.flow.gz")
val film = videoFrames("public/docs/assets/vector_field_topology/eagle.mp4", width = 480).toList()
val cameras = CameraMotion.entries
var frame = 270
var camera = CameraMotion.KEEP
var minSpeed = 0.0

// Streamlines, separatrices and critical points of one frame's flow, over the darkened film.
fun show() {
    val topology =
        VectorFieldTopology.compute(flow.at(frame, window = 1, camera = camera), minStrength = 0.02, maxLength = 300.0)
    val lines =
        topology.streamlines(spacing = 1.5, length = 14.0, minSpeed = minSpeed) +
            topology.toFLines() +
            topology.criticalPointsFLine(6.0)
    val picture = film[frame]
    val dark = Img(picture.width, picture.height, 3, FloatArray(picture.width * picture.height * 3))
    showImgGrid(
        "frame $frame, camera ${camera.name.lowercase()}" to
            picture.overlay(dark, 0.55).drawFLines(lines.map { it * flow.cell.toDouble() }, scale = 2.0),
        columns = 1,
        cellSide = 960,
    )
}
// camera: 0 keeps the camera's motion, 1 removes its median shift, 2 its fitted affine motion.
// min speed: draw streamlines only where the flow is faster than this, in pixels per frame.
controlGroup("eagle") {
    slider("frame", min = 0.0, max = flow.frameCount - 1.0, step = 1.0, initialValue = 270.0) {
        frame = it.toInt()
        show()
    }
    slider("camera", min = 0.0, max = 2.0, step = 1.0, initialValue = 0.0) {
        camera = cameras[it.toInt()]
        show()
    }
    slider("min speed", min = 0.0, max = 3.0, step = 0.1, initialValue = 0.0) {
        minSpeed = it
        show()
    }
}
show()
```

## 2. 2 - The whole clip as a movie

<!-- field-box id="eagle_flow_movie" index="1" -->

```edn [field-box id="eagle_flow_movie" role="properties"]
{"id" "eagle_flow_movie" "index" 1 "name" "2 - The whole clip as a movie" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - The whole clip as a movie" "_tombstone" false "frame" #afield/rect [560.0 60.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "eagle_flow_movie_code_.kt" "lines" 54 "characters" 1838 "sha256" "ea04c39e27f5f6a4a3a943a5006f660a79b0841685e7456af300f8bd044aeb45"}]}
```

```kotlin [field-property box="eagle_flow_movie" name="code" path="eagle_flow_movie_code_.kt"]
import afield.geometry.topology.vectorfield.*
import afield.tools.docs.FFMPEG
import java.io.File

// Launch this one: every frame drawn in the raw look, encoded at the film's 18 frames per second.
val flow = loadParticleFlow("public/docs/assets/vector_field_topology/eagle.flow.gz")
val out = File("build/eagle_flow/eagle_flow_raw.mp4")

suspend fun render() {
    val pngs = File(out.parentFile, "frames").apply {
        deleteRecursively()
        mkdirs()
    }
    for ((frame, picture) in
        videoFrames("public/docs/assets/vector_field_topology/eagle.mp4", width = 480).withIndex()) {
        if (frame >= flow.frameCount) break
        val topology = VectorFieldTopology.compute(
            flow.at(frame, window = 1, camera = CameraMotion.KEEP),
            minStrength = 0.02,
            maxLength = 300.0,
        )
        val lines =
            topology.streamlines(spacing = 1.5, length = 14.0) + topology.toFLines() + topology.criticalPointsFLine(6.0)
        val dark = Img(picture.width, picture.height, 3, FloatArray(picture.width * picture.height * 3))
        picture
            .overlay(dark, 0.55)
            .drawFLines(lines.map { it * flow.cell.toDouble() }, scale = 2.0)
            .savePng(File(pngs, "%03d.png".format(frame)).path)
        if (frame % 30 == 0) println("frame $frame of ${flow.frameCount}")
    }
    val encode = ProcessBuilder(
        FFMPEG,
        "-v",
        "error",
        "-y",
        "-framerate",
        "18",
        "-i",
        File(pngs, "%03d.png").path,
        "-c:v",
        "libx264",
        "-preset",
        "slow",
        "-crf",
        "28",
        "-pix_fmt",
        "yuv420p",
        out.path,
    ).inheritIO()
        .start()
    check(encode.waitFor() == 0) { "ffmpeg failed" }
    println("wrote ${out.absolutePath}")
}
start(::render)
```

