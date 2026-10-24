# Field Document: remotejs_box_parity

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 6 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole" "mainLanguage"]}
```

## 1. RemoteJS root -- children of this box run as Javascript in the head below

<!-- field-box id="rparity_root" index="0" -->

### Narrative

From `code`

This box stays Kotlin. It opens the browser head on the canvas; its children run there.

```edn [field-box id="rparity_root" role="properties"]
{"id" "rparity_root" "index" 0 "name" "RemoteJS root -- children of this box run as Javascript in the head below" "parents" ["root"] "children" ["rparity_print" "rparity_error" "rparity_controls" "rparity_late" "rparity_spin"] "properties" {"children" #{#afield/box "rparity_print" #afield/box "rparity_error" #afield/box "rparity_controls" #afield/box "rparity_late" #afield/box "rparity_spin"} "parents" #{#afield/box "root"} "name" "RemoteJS root -- children of this box run as Javascript in the head below" "_tombstone" false "frame" #afield/rect [60.0 40.0 420.0 100.0] "mainLanguage" "remotejs" "isRemoteJSRoot" true} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rparity_root_code_.kt" "lines" 2 "characters" 141 "sha256" "90c1fb6dac8ee54fc658595fa8481b1ebcc4a2fac02932f96b334abb4864f115"}]}
```

```kotlin [field-property box="rparity_root" name="code" path="rparity_root_code_.kt"]
// This box stays Kotlin. It opens the browser head on the canvas; its children run there.
remoteAr(debug = true, width = 480, height = 270)
```

## 2. an error is marked where it happened

<!-- field-box id="rparity_error" index="1" -->

### Narrative

From `code`

The marker lands on line 4, inside mean, where reduce failed --
not on line 7, where mean was called.

```edn [field-box id="rparity_error" role="properties"]
{"id" "rparity_error" "index" 1 "name" "an error is marked where it happened" "parents" ["root" "rparity_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "rparity_root"} "name" "an error is marked where it happened" "_tombstone" false "frame" #afield/rect [60.0 1140.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rparity_error_code_.kt" "lines" 7 "characters" 200 "sha256" "585e89a9d84ed1b7d1d2d850e0cfbc0f39ef15f0198a3572f206a89352ff6591"}]}
```

```kotlin [field-property box="rparity_error" name="code" path="rparity_error_code_.kt"]
// The marker lands on line 4, inside mean, where reduce failed --
// not on line 7, where mean was called.
function mean(values) {
  return values.reduce((a, b) => a + b) / values.length
}

mean([])
```

## 3. display, a slider and a checkbox

<!-- field-box id="rparity_controls" index="2" -->

### Narrative

From `code`

The same output channel and controls a Kotlin box draws, calling back into the head.

```edn [field-box id="rparity_controls" role="properties"]
{"id" "rparity_controls" "index" 2 "name" "display, a slider and a checkbox" "parents" ["root" "rparity_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "rparity_root"} "name" "display, a slider and a checkbox" "_tombstone" false "frame" #afield/rect [60.0 1640.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rparity_controls_code_.kt" "lines" 10 "characters" 415 "sha256" "3d72fd16db7962f67704c6f07ea859b9c633275dbbcd6e9b15db4d6c8d34b2fe"}]}
```

```kotlin [field-property box="rparity_controls" name="code" path="rparity_controls_code_.kt"]
// The same output channel and controls a Kotlin box draws, calling back into the head.
const state = {freq: 3.0, square: false}

function redraw() {
  display("main", `<b>${state.square ? "square" : "sine"}</b> at ${state.freq.toFixed(1)} Hz`)
}

slider("frequency", v => { state.freq = v; redraw() }, {min: 1, max: 10, step: 0.1, value: 3.0})
checkbox("square wave", v => { state.square = v; redraw() })
redraw()
```

## 4. Stop stops launch loops

<!-- field-box id="rparity_spin" index="3" -->

### Narrative

From `code`

Run this, then Stop it: the counter in the head freezes.

```edn [field-box id="rparity_spin" role="properties"]
{"id" "rparity_spin" "index" 3 "name" "Stop stops launch loops" "parents" ["root" "rparity_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "rparity_root"} "name" "Stop stops launch loops" "_tombstone" false "frame" #afield/rect [60.0 2840.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rparity_spin_code_.kt" "lines" 4 "characters" 146 "sha256" "56f51b07618940451e23fe656d172eb244d75c8f24ad3eab53316afd7eff0aaf"}]}
```

```kotlin [field-property box="rparity_spin" name="code" path="rparity_spin_code_.kt"]
// Run this, then Stop it: the counter in the head freezes.
window.spins = 0
launch(() => { window.spins++ }, "spinner", false)
print("spinning")
```

## 5. print and console.log beside their lines

<!-- field-box id="rparity_print" index="4" -->

### Narrative

From `code`

Both appear beside the line that called them; the last value is shown too.

```edn [field-box id="rparity_print" role="properties"]
{"id" "rparity_print" "index" 4 "name" "print and console.log beside their lines" "parents" ["root" "rparity_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "rparity_root"} "name" "print and console.log beside their lines" "_tombstone" false "frame" #afield/rect [60.0 640.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rparity_print_code_.kt" "lines" 5 "characters" 195 "sha256" "b0a153b8a12a75f709a40ada8bf12aa0e1e69ce22773070bd96c47c31229a5cb"}]}
```

```kotlin [field-property box="rparity_print" name="code" path="rparity_print_code_.kt"]
// Both appear beside the line that called them; the last value is shown too.
print("from print")
console.log("from console.log", {answer: 6 * 7})
const now = new Date().getFullYear()
now > 2000
```

## 6. output printed after the run finished

<!-- field-box id="rparity_late" index="5" -->

### Narrative

From `code`

The run ends at once; this line prints half a second later, beside line 3.

```edn [field-box id="rparity_late" role="properties"]
{"id" "rparity_late" "index" 5 "name" "output printed after the run finished" "parents" ["root" "rparity_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "rparity_root"} "name" "output printed after the run finished" "_tombstone" false "frame" #afield/rect [60.0 2340.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rparity_late_code_.kt" "lines" 5 "characters" 161 "sha256" "75eabac1717625d8f3b168ac98ba9dda741cc427bbe0095e7abc0904adc94f28"}]}
```

```kotlin [field-property box="rparity_late" name="code" path="rparity_late_code_.kt"]
// The run ends at once; this line prints half a second later, beside line 3.
setTimeout(() => {
  print("half a second later")
}, 500)
print("the run is over")
```

