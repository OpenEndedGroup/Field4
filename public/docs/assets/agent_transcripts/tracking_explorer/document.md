# Field Document: tracking_explorer

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 - Peaks followed through time

<!-- field-box id="tracking_live" index="0" -->

```edn [field-box id="tracking_live" role="properties"]
{"id" "tracking_live" "index" 0 "name" "1 - Peaks followed through time" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - Peaks followed through time" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tracking_live_code_.kt" "lines" 27 "characters" 1594 "sha256" "2e1796e4730e50e4feb94791e33e11f69dbfe3aca5ed95e9b3836be66b62426a"}]}
```

```kotlin [field-property box="tracking_live" name="code" path="tracking_live_code_.kt"]
import afield.geometry.topology.morsesmale.*
import afield.geometry.topology.tracking.*

// Launch this one (Alt-R). Six hills circle; every frame's maxima are matched to the last frame's, so
// each hill keeps its track and its colour for as long as it stays a separate peak.
val hills = (0 until 6).map { i -> Triple(0.4 + 0.25 * i, 30.0 + 9.0 * i, 1.0 - 0.1 * i) }
fun field(t: Double) = Img.generate(200, 150) { x, y ->
    hills.withIndex().sumOf { (i, h) ->
        val (speed, radius, amplitude) = h
        val cx = 100 + radius * cos(speed * t + i); val cy = 75 + radius * 0.7 * sin(speed * t * 1.3 + i)
        amplitude * exp(-((x - cx).pow(2) + (y - cy).pow(2)) / 120.0)
    }.toFloat()
}
val tracker = FeatureTracker(MatchingWeights(birthDistance = 10.0, maxDistance = 20.0))

loop {
    val f = field(frameNumber / 40.0)
    val ms = MorseSmale.compute(f, persistence = 0.05)
    tracker.add(frameNumber.toDouble(), ms.features(minPersistence = 0.05, types = setOf(CriticalType.MAXIMUM)))
    // the last 120 frames of each living track
    val trails = tracker.alive.map { t ->
        FLine().also { line -> t.samples.takeLast(120).forEachIndexed { k, (_, s) -> if (k == 0) line.moveTo(s.x, s.y) else line.lineTo(s.x, s.y) }
            line.properties["color"] = FeatureTracker.colourOf(t.id); line.properties["thickness"] = 2.0 }
    }
    showImgGrid("frame $frameNumber: ${tracker.alive.size} peaks, ${tracker.tracks.size} tracks so far" to
        f.normalized().colormap("viridis").drawFLines(trails + tracker.aliveFLine(9.0), scale = 3.0), columns = 1, cellSide = 600)
}
```

## 2. 2 - Features followed through scale

<!-- field-box id="tracking_scale" index="1" -->

```edn [field-box id="tracking_scale" role="properties"]
{"id" "tracking_scale" "index" 1 "name" "2 - Features followed through scale" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - Features followed through scale" "_tombstone" false "frame" #afield/rect [560.0 60.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "tracking_scale_code_.kt" "lines" 22 "characters" 1448 "sha256" "85a0cfcb7cfe448a830fa2118e90fa1afe5058e80799a10093640c290e2bd475"}]}
```

```kotlin [field-property box="tracking_scale" name="code" path="tracking_scale_code_.kt"]
import afield.geometry.topology.morsesmale.*
import afield.geometry.topology.tracking.*

// The leaf print's dark spots (minima of its brightness), followed as the image is blurred more and
// more. A feature that survives a large blur is large; one that dies at the first is fine detail.
val print = imgFromFile("public/docs/assets/vision/fline-viewer.jpg", 3).crop(78, 262, 128, 236).scaleBy(2.0)
val sigmas = [1.0, 2.0, 3.0, 4.0, 6.0, 8.0, 11.0, 16.0]
val space = ScaleSpace.track(print.luma(), sigmas, persistence = 0.02, maxDistance = 14.0, types = setOf(CriticalType.MINIMUM))

// Each feature that survives to σ 3 or more, as a circle where it starts with radius twice the largest
// σ it reached (a blob of scale σ is about 2σ across), and its path through scale.
val lasting = space.tracker.tracks.filter { space.scaleOf(it) >= 3.0 }
val circles = lasting.map { t ->
    val (x, y) = t.samples.first().second.let { it.x to it.y }
    FLine().circle(x, y, 2 * space.scaleOf(t)).also { it.properties["color"] = FeatureTracker.colourOf(t.id); it.properties["thickness"] = 1.5 }
}
val dark = Img(print.width, print.height, 3, FloatArray(print.width * print.height * 3))
showImgGrid(
    "photograph" to print,
    "by scale" to print.overlay(dark, 0.55).drawFLines(circles + lasting.map { t -> t.toFLine().also { it.properties["color"] = FeatureTracker.colourOf(t.id); it.properties["thickness"] = 1.5 } }),
    columns = 2, cellSide = 320,
)
```

