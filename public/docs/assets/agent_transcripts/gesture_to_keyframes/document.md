# Field Document: gesture_to_keyframes

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. gesture to keyframes

<!-- field-box id="gk_bake" index="0" -->

```edn [field-box id="gk_bake" role="properties"]
{"id" "gk_bake" "index" 0 "name" "gesture to keyframes" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "gesture to keyframes" "_tombstone" false "frame" #afield/rect [40.0 30.0 320.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gk_bake_code_.kt" "lines" 55 "characters" 1657 "sha256" "124a3018fa6a741ffebdc91904906afe7fe1b0fd0ef0dc29a3911376550cfbda"}]}
```

```kotlin [field-property box="gk_bake" name="code" path="gk_bake_code_.kt"]
var p = Vector2f(0f, 0f)
lateinit var pad: GestureHandle

// A figure eight drawn by a slightly unsteady hand: 240 samples over two seconds.
val rnd = java.util.Random(7)
val eight = 240 { i ->
    val a = i / 239.0 * 2 * Math.PI
    val wobble = { 0.008 * (rnd.nextDouble() - 0.5) }
    i / 239.0 * 2.0 to Vector2f((0.7 * Math.sin(a) + wobble()).toFloat(), (0.5 * Math.sin(2 * a) + wobble()).toFloat())
}

val x = keyframes("lanes", "x")
val y = keyframes("lanes", "y")

fun bake() {
    val (bx, by) = bakeGesture(pad, tolerance = 0.02, channel = "lanes")
    println("baked: ${pad.fit(bx, by)}")
}

// Takes the tremor out of the take: every sample as keys, smoothed over 40 ms, played back
// through the pad, then baked again, so both lanes still have their keys at the same times.
fun smoothTake() {
    val (sx, sy) = bakeGesture(pad, tolerance = 0.0, channel = "lanes")
    sx.smooth(0.04)
    sy.smooth(0.04)
    pad.setTake(sx, sy)
    bake()
}

controlGroup("gesture") {
    pad = gesture("take", initialTake = eight) { p = it }
    button("bake take") { if (it) bake() }
    button("smooth take") { if (it) smoothTake() }
    button("replay lanes") {
        if (it) {
            pad.setTake(x, y)
            println("pad now plays the lanes: ${pad.samples.size} samples")
        }
    }
}
displayOpen("gesture")

if (x.isEmpty() || y.isEmpty()) bake() else println("lanes kept: ${pad.fit(x, y)}")

val start = System.nanoTime()
loop {
    val d = pad.duration
    if (d > 0) {
        val t = ((System.nanoTime() - start) / 1e9) % d
        pad.at(t)?.let { p = it }
        pad.playhead(t)
        x.setTime(t)
        y.setTime(t)
    }
}
```

