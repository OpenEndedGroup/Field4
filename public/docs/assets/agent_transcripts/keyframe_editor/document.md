# Field Document: keyframe_editor

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_session" "fileManagerAccessHistory"]}
```

## 1. shape

<!-- field-box id="kf_shape" index="0" -->

### Narrative

From `code`

Run this box: one key of each type. Right-click a key to change its type.

```edn [field-box id="kf_shape" role="properties"]
{"id" "kf_shape" "index" 0 "name" "shape" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "shape" "_tombstone" false "frame" #afield/rect [760.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kf_shape_code_.kt" "lines" 9 "characters" 353 "sha256" "53c6da346ab8bc5beedf6d04835b92d8a0bd959ac011e4c6eb1cd69021eaad76"}]}
```

```kotlin [field-property box="kf_shape" name="code" path="kf_shape_code_.kt"]
// Run this box: one key of each type. Right-click a key to change its type.
val shape = keyframes(name = "shape", keys = listOf(
    Keyframe(0.0, 0.1, "linear"),
    Keyframe(1.0, 0.8, "hold"),
    Keyframe(2.0, 0.3, "bezierFlat"),
    Keyframe(3.0, 0.9, "bezierContinuous"),
    Keyframe(4.0, 0.5, "bezierFree"),
    Keyframe(5.0, 0.2, "linear"),
))
```

## 2. smoothed

<!-- field-box id="kf_smoothed" index="1" -->

### Narrative

From `code`

Run this box, then edit the curve: the dashed overlay is a smoothed copy of it, and
onChange redraws it after every edit.

```edn [field-box id="kf_smoothed" role="properties"]
{"id" "kf_smoothed" "index" 1 "name" "smoothed" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "smoothed" "_tombstone" false "frame" #afield/rect [40.0 520.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kf_smoothed_code_.kt" "lines" 9 "characters" 459 "sha256" "be5ecd466155eb189b00be904a27ba3b87f98cb30813ce510c59b2d6cabc35f4"}]}
```

```kotlin [field-property box="kf_smoothed" name="code" path="kf_smoothed_code_.kt"]
// Run this box, then edit the curve: the dashed overlay is a smoothed copy of it, and
// onChange redraws it after every edit.
val wobble = keyframes(name = "wobble", defaults = listOf(
    vec(0.0, 0.2), vec(0.5, 0.9), vec(1.0, 0.1), vec(1.5, 0.8), vec(2.0, 0.3)
))

val smooth = { keys: List<Keyframe> -> with(Functions) { gaussianSmooth(fromKeyframeEditor(keys), sigma = 0.15) } }
wobble.setOverlay(smooth(wobble.getKeyframes()))
wobble.onChange = smooth
```

## 3. brightness

<!-- field-box id="kf_brightness" index="2" -->

### Narrative

From `code`

Launch this box to play the curve: the playhead sweeps it every four seconds and the
meter shows its value. Edit the curve while it plays; your edits are saved with the box.

```edn [field-box id="kf_brightness" role="properties"]
{"id" "kf_brightness" "index" 2 "name" "brightness" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "brightness" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kf_brightness_code_.kt" "lines" 14 "characters" 580 "sha256" "9491ffbc9ebc337b6eb81e129aed6c38248a3690a1097485a15a3af332c5c4f3"}]}
```

```kotlin [field-property box="kf_brightness" name="code" path="kf_brightness_code_.kt"]
// Launch this box to play the curve: the playhead sweeps it every four seconds and the
// meter shows its value. Edit the curve while it plays; your edits are saved with the box.
val brightness = keyframes(name = "brightness", defaults = listOf(vec(0.0, 0.1), vec(1.0, 0.9), vec(2.5, 0.4), vec(4.0, 0.1)))

lateinit var level: MeterHandle
controlGroup("level") { level = meter("brightness") }
displayOpen("level")

val start = System.nanoTime()
loop {
    val t = ((System.nanoTime() - start) / 1e9) % 4.0
    brightness.setTime(t)
    level.set(brightness.evaluateValueAt(t))
}
```

