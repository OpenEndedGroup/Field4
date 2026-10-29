# Field Document: keyframe_editor

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. shape

<!-- field-box id="kf_shape" index="0" -->

### Narrative

From `code`

Run this box: one key of each type. Right-click a key to change its type.

```edn [field-box id="kf_shape" role="properties"]
{"id" "kf_shape" "index" 0 "name" "shape" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "shape" "_tombstone" false "frame" #afield/rect [760.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kf_shape_code_.kt" "lines" 12 "characters" 386 "sha256" "569c9328eb0a22cc27e81b9075c07b9645206882399b0787388eeee5b8047cd3"}]}
```

```kotlin [field-property box="kf_shape" name="code" path="kf_shape_code_.kt"]
// Run this box: one key of each type. Right-click a key to change its type.
val shape = keyframes(
    name = "shape",
    keys = [
        Keyframe(0.0, 0.1, "linear"),
        Keyframe(1.0, 0.8, "hold"),
        Keyframe(2.0, 0.3, "bezierFlat"),
        Keyframe(3.0, 0.9, "bezierContinuous"),
        Keyframe(4.0, 0.5, "bezierFree"),
        Keyframe(5.0, 0.2, "linear"),
    ],
)
```

## 2. smoothed

<!-- field-box id="kf_smoothed" index="1" -->

### Narrative

From `code`

Run this box, then edit the curve: the dashed overlay is a smoothed copy of it, and
onChange redraws it after every edit.

```edn [field-box id="kf_smoothed" role="properties"]
{"id" "kf_smoothed" "index" 1 "name" "smoothed" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "smoothed" "_tombstone" false "frame" #afield/rect [40.0 520.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kf_smoothed_code_.kt" "lines" 16 "characters" 505 "sha256" "2c39b054b76fe8abb9afbdfe44bd1f0e61317cf40aa1ce538fee5049ca698ccb"}]}
```

```kotlin [field-property box="kf_smoothed" name="code" path="kf_smoothed_code_.kt"]
// Run this box, then edit the curve: the dashed overlay is a smoothed copy of it, and
// onChange redraws it after every edit.
val wobble = keyframes(
    name = "wobble",
    defaults = [
        vec(0.0, 0.2),
        vec(0.5, 0.9),
        vec(1.0, 0.1),
        vec(1.5, 0.8),
        vec(2.0, 0.3),
    ],
)

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
{"id" "kf_brightness" "index" 2 "name" "brightness" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "brightness" "_tombstone" false "frame" #afield/rect [40.0 40.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kf_brightness_code_.kt" "lines" 14 "characters" 574 "sha256" "223d9a1fe286927b6ca831c6c2395a0be273ba4d18fa91e999b1d1214611b96b"}]}
```

```kotlin [field-property box="kf_brightness" name="code" path="kf_brightness_code_.kt"]
// Launch this box to play the curve: the playhead sweeps it every four seconds and the
// meter shows its value. Edit the curve while it plays; your edits are saved with the box.
val brightness = keyframes(name = "brightness", defaults = [vec(0.0, 0.1), vec(1.0, 0.9), vec(2.5, 0.4), vec(4.0, 0.1)])

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

## 4. custom types

<!-- field-box id="kf_custom" index="3" -->

### Narrative

From `code`

Run this box: three key types written in Kotlin. Each returns the curve's value across a
segment; u runs 0 to 1, lerp(f) is "f of the way from this key's value to the next".
Right-click a key and they are in the menu, after the five built in.

```edn [field-box id="kf_custom" role="properties"]
{"id" "kf_custom" "index" 3 "name" "custom types" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "custom types" "_tombstone" false "frame" #afield/rect [760.0 520.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kf_custom_code_.kt" "lines" 23 "characters" 852 "sha256" "6daced37acdb69f3c5e870968fa0ec2a16c14b638c00187dd75f7fc8cb8f79cb"}]}
```

```kotlin [field-property box="kf_custom" name="code" path="kf_custom_code_.kt"]
/*
Run this box: three key types written in Kotlin. Each returns the curve's value across a
segment; u runs 0 to 1, lerp(f) is "f of the way from this key's value to the next".
Right-click a key and they are in the menu, after the five built in.
*/
interpolant("overshoot") { lerp(1 + 2.7 * (u - 1).pow(3) + 1.7 * (u - 1).pow(2)) }
interpolant("wobble") { lerp(u) + 0.08 * sin(u * 6 * PI) * (1 - u) }
// Deterministic chaos: the logistic map, seeded by where we are, and pinned to both keys.
interpolant("chaos") {
    var x = 0.1 + 0.8 * u
    repeat(30) { x = 3.97 * x * (1 - x) }
    lerp(u) + 0.25 * sin(PI * u) * (x - 0.5)
}

val custom = keyframes(
    name = "custom",
    keys = [
        Keyframe(0.0, 0.1, "overshoot"),
        Keyframe(1.5, 0.8, "wobble"),
        Keyframe(3.0, 0.3, "chaos"),
        Keyframe(4.5, 0.6, "linear"),
    ],
)
```

