# Field Document: steamaudio_screen_study

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. A screen between source and listener

<!-- field-box id="steam_screen" index="0" -->

```edn [field-box id="steam_screen" role="properties"]
{"id" "steam_screen" "index" 0 "name" "A screen between source and listener" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "A screen between source and listener" "_tombstone" false "frame" #afield/rect [80.0 80.0 370.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "steam_screen_code_.kt" "lines" 66 "characters" 2404 "sha256" "8d96fd272ce2986d1b4a9f3e54f9ece97f9d4ccde3f054383f026264d699b408"}]}
```

```kotlin [field-property box="steam_screen" name="code" path="steam_screen_code_.kt"]
import afield.audio.spatial.steamaudio.Material
import afield.examples.steamaudio.SteamAudioScreenStudy
import afield.ui.visualization.AudioVisualizerOptions
import afield.ui.visualization.LinesFraming
import afield.ui.visualization.displayAudioBytes
import java.util.Locale

val scene = SteamAudioScreenStudy
val listener = scene.listenerPosition
val source = scene.sourcePosition
val material = Material.WOOD
val metre = 80.0
val player = AudioVisualizerOptions(width = 500, waveformHeight = 65)

fun draw(offset: Float) {
    val take = scene.render(offset, material)
    val centre = (listener.x + source.x) / 2f + offset
    val z = scene.SCREEN_DEPTH * metre
    val transmission = take.transmission.joinToString(" / ") { "%.3f".format(Locale.ROOT, it) }
    val ray = fline {
        moveTo(listener.x * metre, listener.z * metre)
        lineTo(source.x * metre, source.z * metre)
        color = if (take.occlusion > 0.5f) vec(0.32, 0.82, 0.75, 1.0) else vec(0.93, 0.64, 0.30, 1.0)
        thickness = 2.0
    }
    val screen = fline {
        moveTo((centre - scene.SCREEN_HALF_WIDTH) * metre, z)
        lineTo((centre + scene.SCREEN_HALF_WIDTH) * metre, z)
        color = vec(0.68, 0.73, 0.84, 1.0)
        thickness = 7.0
    }
    val markers =
        [listener, source].map { point ->
            fline {
                circle(point.x * metre, point.z * metre, 7.0)
                filled = true
                color = vec(0.83, 0.90, 1.0, 1.0)
            }
        }
    lines(
        [ray, screen] + markers,
        w = 420.0,
        h = 245.0,
        fitToSize = true,
        framing = LinesFraming.Fixed(-220.0, -265.0, 575.0, 290.0),
    )
    val visibility = "%.2f".format(Locale.ROOT, take.occlusion)
    markdown("direct path", "**Occlusion $visibility** · transmission low / mid / high: $transmission", width = 500)
    displayAudioBytes(take.wav(), channel = "screen take", label = "Current screen position", options = player)
    println("Screen take ready: occlusion $visibility")
}

displayAudioBytes(
    scene.render(scene.CLEAR_OFFSET, material).wav(),
    channel = "clear reference",
    label = "Clear path",
    options = player,
)
slider("Screen offset (m)", min = -2.4, max = 2.4, step = 0.05, initialValue = 0.0) {
    draw(it.toFloat())
}
draw(0f)
displayOpen("controls")
displayOpen("lines")
displayClose("clear reference")
displayClose("screen take")
```

