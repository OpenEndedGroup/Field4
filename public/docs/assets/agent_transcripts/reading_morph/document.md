# Field Document: reading_morph

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 · Stereo context

<!-- field-box id="pick_context" index="0" -->

```edn [field-box id="pick_context" role="properties"]
{"id" "pick_context" "index" 0 "name" "1 · Stereo context" "parents" ["root"] "children" ["morph_scene"] "properties" {"children" #{#afield/box "morph_scene"} "parents" #{#afield/box "root"} "name" "1 · Stereo context" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pick_context_code_.kt" "lines" 28 "characters" 1331 "sha256" "5da29bfc9e480bcf38102ea2ebdfbaef4a5a635f2663da39acd1ae04423d6e4e"}]}
```

```kotlin [field-property box="pick_context" name="code" path="pick_context_code_.kt"]
import afield.graphics.vulkan.util.Options

// Set BEFORE the first renderer in this Field JVM; switching modes needs a fresh JVM.
val headsetMode = false // true for OpenXR (WiVRn, CloudXR); false for the desktop stereo mirror
Options.enableVR = headsetMode
if (headsetMode) Options.validateOpenXR = false
prop<Window>(window) = Window("morphing text by key", initialDimensions = Rect(60.0, 60.0, 1280.0, 900.0))
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

## 2. 2 · Three lines, morphed by key

<!-- field-box id="morph_scene" index="1" -->

```edn [field-box id="morph_scene" role="properties"]
{"id" "morph_scene" "index" 1 "name" "2 · Three lines, morphed by key" "parents" ["root" "pick_context"] "children" ["pick_drawing"] "properties" {"children" #{#afield/box "pick_drawing"} "parents" #{#afield/box "root" #afield/box "pick_context"} "name" "2 · Three lines, morphed by key" "_tombstone" false "frame" #afield/rect [40.0 420.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "morph_scene_code_.kt" "lines" 74 "characters" 2711 "sha256" "2055d982325cad7498599d664fc74d965647932c35c97d485f47a07b91635019"}]}
```

```kotlin [field-property box="morph_scene" name="code" path="morph_scene_code_.kt"]
import afield.graphics.scene.reading.*

// Each line is cut into keyed pieces. A key on both sides glides; a key on one side fades where it stands.
// Keys are yours: here they name the terms of an expression, so the second `a` is a new key.
fun keyed(text: String, vararg parts: Pair<String, String>): List<DiagramKeyedSpan> {
    var from = 0
    return parts.map { (s, k) ->
        val i = text.indexOf(s, from)
        from = i + s.length
        DiagramKeyedSpan(i, i + s.length, k)
    }
}
val one = "a × (b + c)"
val two = "a × b + a × c"
val three = "a × b + c × a"
val graph = readingDiagram {
    val here = point("here", 0, .02, pinned = true)
    val caption = point("caption", 0, -.12, pinned = true)
    textMorph(
        "distribute",
        one,
        keyed(one, "a" to "a", "×" to "times", "b" to "b", "+" to "plus", "c" to "c"),
        two,
        keyed(two, "a" to "a", "×" to "times", "b" to "b", "+" to "plus", "a" to "a2", "×" to "times2", "c" to "c"),
        here.anchor,
    ) {
        scale = 1.4f
    }
    textMorph(
        "commute",
        two,
        keyed(two, "a" to "a", "×" to "times", "b" to "b", "+" to "plus", "a" to "a2", "×" to "times2", "c" to "c"),
        three,
        keyed(three, "a" to "a", "×" to "times", "b" to "b", "+" to "plus", "c" to "c", "×" to "times2", "a" to "a2"),
        here.anchor,
    ) {
        scale = 1.4f
    }
    text("why-1", "distribute: the brackets go, a second a arrives", caption) {
        scale = .5f
        color = Vector4f(1f, 1f, 1f, .6f)
    }
    text("why-2", "commute: c and a change places", caption) {
        scale = .5f
        color = Vector4f(1f, 1f, 1f, .6f)
    }
}
val distribute = graph.textMorph("distribute")
val commute = graph.textMorph("commute")
val score = diagramScore(graph, 12.0) {
    fadeIn(distribute.group, at = 0.0, seconds = 1.0)
    fadeIn(graph.text("why-1"), at = .5, seconds = 1.0)
    morph(distribute, at = 1.5, seconds = 2.0)
    fadeOut(distribute.group, at = 5.0, seconds = .01)
    fadeOut(graph.text("why-1"), at = 4.5, seconds = .8)
    fadeIn(commute.group, at = 4.99, seconds = .01)
    fadeIn(graph.text("why-2"), at = 5.0, seconds = 1.0)
    morph(commute, at = 6.0, seconds = 2.0)
    fadeOut(commute.group, at = 10.5, seconds = 1.0)
    fadeOut(graph.text("why-2"), at = 10.5, seconds = 1.0)
}
println(
    "distribute: " +
        distribute.matched +
        " matched, " +
        distribute.pieces.size +
        " pieces; commute: " +
        commute.matched +
        " matched",
)
prop<ReadingDiagramPlayer>(sumPlayer) =
    ReadingDiagramPlayer.prepare(graph, Typefaces.named("Serif", 100), "field-slug".file, score)
sumPlayer.gravity = 0f
sumPlayer.wind = 0f
```

## 3. 3 · Drawing

<!-- field-box id="pick_drawing" index="2" -->

```edn [field-box id="pick_drawing" role="properties"]
{"id" "pick_drawing" "index" 2 "name" "3 · Drawing" "parents" ["root" "morph_scene"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "morph_scene"} "name" "3 · Drawing" "_tombstone" false "frame" #afield/rect [40.0 800.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "pick_drawing_code_.kt" "lines" 11 "characters" 308 "sha256" "c6cb19c73cb51210510725b26ee6bfc6b44668f353895db10fea43a025af3e1b"}]}
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

