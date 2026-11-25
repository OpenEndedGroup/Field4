# Field Document: reading_pointing

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 · Stereo context

<!-- field-box id="pick_context" index="0" -->

```edn [field-box id="pick_context" role="properties"]
{"id" "pick_context" "index" 0 "name" "1 · Stereo context" "parents" ["root"] "children" ["pick_scene"] "properties" {"children" #{#afield/box "pick_scene"} "parents" #{#afield/box "root"} "name" "1 · Stereo context" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pick_context_code_.kt" "lines" 28 "characters" 1340 "sha256" "65449670291ceeff7c8df8281a0f0d42797670d7de3aec28c5a85a85611e9010"}]}
```

```kotlin [field-property box="pick_context" name="code" path="pick_context_code_.kt"]
import afield.graphics.vulkan.util.Options

// Set BEFORE the first renderer in this Field JVM; switching modes needs a fresh JVM.
val headsetMode = false // true for OpenXR (WiVRn, CloudXR); false for the desktop stereo mirror
Options.enableVR = headsetMode
if (headsetMode) Options.validateOpenXR = false
prop<Window>(window) = Window("pointing at a reading diagram", initialDimensions = Rect(60.0, 60.0, 1280.0, 900.0))
prop<Renderer>(renderer) = Renderer(window)
val ownedRenderer = renderer
if (headsetMode) {
    renderer.openXRHmdTarget = OpenXRHmdTarget(renderer)
    repeat(8) { if (renderer.openXRHmdTarget?.hasEyeViews == false) renderer.render() }
}
prop<MultiviewStereoTarget>(stereo) = MultiviewStereoTarget(renderer, 1600, 1200, withDepth = false)
val ownedStereo = stereo
if (!headsetMode) {
    stereo.stereoCamera.base.at = vec(0f, 0f, 2.35f)
    stereo.stereoCamera.base.target = vec(0f, 0f, 0f)
}
stereo.layered.parameters.clearColor = vec(.003f, .005f, .010f, 1f)
prop<Boolean>(showStereoPair) = false
stereo.mirrorToScreen(name = "pointing-preview", stereoPair = { showStereoPair })
println("context ready: " + stereo.eyeWidth + " x " + stereo.eyeHeight + " per eye")
loop { frame() }
    .end {
        ownedRenderer.renderList.preTargets.remove("stereo-multiview", ownedStereo)
        ownedRenderer.cleanup()
    }
```

## 2. 2 · Annotated diagram

<!-- field-box id="pick_scene" index="1" -->

```edn [field-box id="pick_scene" role="properties"]
{"id" "pick_scene" "index" 1 "name" "2 · Annotated diagram" "parents" ["root" "pick_context"] "children" ["pick_drawing"] "properties" {"children" #{#afield/box "pick_drawing"} "parents" #{#afield/box "root" #afield/box "pick_context"} "name" "2 · Annotated diagram" "_tombstone" false "frame" #afield/rect [40.0 420.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pick_scene_code_.kt" "lines" 28 "characters" 1452 "sha256" "9b922b011552a93b885a8877d30218584af10ffe3518fb4f815aa289cb945ca7"}]}
```

```kotlin [field-property box="pick_scene" name="code" path="pick_scene_code_.kt"]
import afield.graphics.scene.reading.*

// A reading diagram whose words and marks carry what they stand for. Picking hands it back:
// properties on items, annotations on character ranges (innermost first).
val graph = readingDiagram {
    val top = point("top", 0, .16, pinned = true)
    val bottom = point("bottom", 0, -.12, pinned = true)
    val sum = text("sum", "1 + 3 + 5 + 7 = 4 × 4", top)
    sum.all.annotate("the whole identity")
    for (odd in listOf("1", "3", "5", "7")) sum.word(odd).annotate("the odd number " + odd)
    sum.span("4 × 4").annotate("the square it makes")
    val gloss = text("gloss", "odd numbers wrap a square", bottom)
    gloss.word("odd").annotate("each term is 2i + 1")
    gloss.word("square").annotate("n × n")
    val turn = arc("turn", sum.word("7").below.offset(y = -.03f), gloss.word("square").above.offset(y = .02f), rise = -.06f) {
        width = .0015f
        arrow = .012f
    }
    endpointDots(turn)
    dot("bead", turn.at(.5f)) { radius = .006f }
}
graph.path("turn").properties["says"] = "the last gnomon completes the square"
graph.dot("bead").properties["says"] = "halfway"
prop<ReadingDiagramPlayer>(sumPlayer) =
    ReadingDiagramPlayer.prepare(graph, Typefaces.named("Serif", 100), "field-slug".file, diagramScore(graph, 20.0) {})
sumPlayer.gravity = 0f
sumPlayer.wind = 0f
println("scene ready: " + graph.texts.size + " runs, " + graph.paths.size + " paths, " + graph.dots.size + " dots")
```

## 3. 3 · Drawing

<!-- field-box id="pick_drawing" index="2" -->

```edn [field-box id="pick_drawing" role="properties"]
{"id" "pick_drawing" "index" 2 "name" "3 · Drawing" "parents" ["root" "pick_scene"] "children" ["pick_pointing"] "properties" {"children" #{#afield/box "pick_pointing"} "parents" #{#afield/box "root" #afield/box "pick_scene"} "name" "3 · Drawing" "_tombstone" false "frame" #afield/rect [40.0 800.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pick_drawing_code_.kt" "lines" 11 "characters" 308 "sha256" "c6cb19c73cb51210510725b26ee6bfc6b44668f353895db10fea43a025af3e1b"}]}
```

```kotlin [field-property box="pick_drawing" name="code" path="pick_drawing_code_.kt"]
import afield.graphics.scene.reading.*

prop<StereoDiagramRenderer>(drawing) = StereoDiagramRenderer(renderer, stereo, sumPlayer)
val ownedDrawing = drawing
println("drawing ready")
loop {
    renderer.windows.forEach { it.pollEvents() }
    renderer.render()
    frame()
}
    .end { ownedDrawing.close() }
```

## 4. 4 · Pointing

<!-- field-box id="pick_pointing" index="3" -->

```edn [field-box id="pick_pointing" role="properties"]
{"id" "pick_pointing" "index" 3 "name" "4 · Pointing" "parents" ["root" "pick_drawing"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "pick_drawing"} "name" "4 · Pointing" "_tombstone" false "frame" #afield/rect [640.0 800.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pick_pointing_code_.kt" "lines" 65 "characters" 2774 "sha256" "cf9e7f21ce2d76277ae12b7a742215201fcd2c38f16d6bad00f7f25eabaef610"}]}
```

```kotlin [field-property box="pick_pointing" name="code" path="pick_pointing_code_.kt"]
import afield.graphics.interaction.*
import afield.graphics.scene.reading.*
import afield.graphics.vulkan.util.WindowPointer

// Point at the reading space: the mouse over the desktop mirror, or the right controller in a headset.
// The hovered item stays bright and everything else dims; a click prints what was clicked.
val pointingDrawing = drawing
val pointingPlayer = sumPlayer
val pointingMouse = stereo.mirrorPointer(WindowPointer(window, "reading-pointer"), stereoPair = { showStereoPair })
val pointingController = stereo.pointer()
val pointing = DiagramPointerInteraction({ ray -> pointingDrawing.pick(ray, pointingPlayer) })
val pointingOverride = arrayOfNulls<Pointer>(1) // a fixed pointer for captures and tests; empty uses the device
val pointingLog = mutableListOf<String>()
val dimmed = HashMap<DiagramItem, Float>()
fun restoreDimmed() {
    for ((item, alpha) in dimmed) when (item) {
        is DiagramText -> item.color = Vector4f(item.color).apply { w = alpha }
        is DiagramPath -> item.color = Vector4f(item.color).apply { w = alpha }
        is DiagramDot -> item.color = Vector4f(item.color).apply { w = alpha }
        else -> {}
    }
    dimmed.clear()
}
fun dimAllBut(keep: DiagramItem) {
    val d = pointingPlayer.diagram
    for (item in d.texts + d.paths + d.dots) if (item !== keep) {
        val c = when (item) {
            is DiagramText -> item.color
            is DiagramPath -> item.color
            is DiagramDot -> item.color
            else -> null
        } ?: continue
        dimmed[item] = c.w
        val faint = Vector4f(c).apply { w *= .3f }
        when (item) {
            is DiagramText -> item.color = faint
            is DiagramPath -> item.color = faint
            is DiagramDot -> item.color = faint
            else -> {}
        }
    }
}
var pointingLit: DiagramItem? = null
val previousPointingUpdate = pointingDrawing.beforeUpdate
val pointingUpdate: () -> Unit = {
    previousPointingUpdate()
    val device = pointingOverride[0] ?: if (renderer.openXRHmdTarget != null) pointingController else pointingMouse
    pointing.update(device.sample())
    val over = pointing.hover?.item
    if (over !== pointingLit) {
        restoreDimmed()
        if (over != null) dimAllBut(over)
        pointingLit = over
        pointingLog += "hover " + (pointing.hover?.describe() ?: "nothing")
    }
    if (pointing.justReleased) pointingLog += "click " + (pointing.hover?.describe() ?: "nothing")
}
pointingDrawing.beforeUpdate = pointingUpdate
println("pointing ready: mouse over the mirror, or the right controller in a headset")

loop { frame() }
    .end {
        restoreDimmed()
        if (pointingDrawing.beforeUpdate === pointingUpdate) pointingDrawing.beforeUpdate = previousPointingUpdate
    }
```

