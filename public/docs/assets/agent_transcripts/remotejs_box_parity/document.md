# Field Document: remotejs_box_parity

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 12 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole" "mainLanguage" "isKotlinJsRoot"]}
```

## 1. RemoteJS root -- children of this box run as Javascript in the head below

<!-- field-box id="rparity_root" index="0" -->

### Narrative

From `code`

This box stays Kotlin. It opens the browser head on the canvas; its children run there.

```edn [field-box id="rparity_root" role="properties"]
{"id" "rparity_root" "index" 0 "name" "RemoteJS root -- children of this box run as Javascript in the head below" "parents" ["root"] "children" ["rparity_print" "rparity_error" "rparity_controls" "rparity_late" "rparity_spin" "kparity_root"] "properties" {"children" #{#afield/box "rparity_print" #afield/box "rparity_error" #afield/box "rparity_controls" #afield/box "rparity_late" #afield/box "rparity_spin" #afield/box "kparity_root"} "parents" #{#afield/box "root"} "name" "RemoteJS root -- children of this box run as Javascript in the head below" "_tombstone" false "frame" #afield/rect [60.0 40.0 420.0 100.0] "mainLanguage" "remotejs" "isRemoteJSRoot" true} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rparity_root_code_.kt" "lines" 2 "characters" 141 "sha256" "90c1fb6dac8ee54fc658595fa8481b1ebcc4a2fac02932f96b334abb4864f115"}]}
```

```kotlin [field-property box="rparity_root" name="code" path="rparity_root_code_.kt"]
// This box stays Kotlin. It opens the browser head on the canvas; its children run there.
remoteAr(debug = true, width = 480, height = 270)
```

## 2. Kotlin → head root -- children of this box are Kotlin compiled to Javascript

<!-- field-box id="kparity_root" index="1" -->

### Narrative

From `code`

Nothing to run here. Boxes under this one are Kotlin, compiled to Javascript and sent
to the same browser head as the Javascript boxes on the left.

```edn [field-box id="kparity_root" role="properties"]
{"id" "kparity_root" "index" 1 "name" "Kotlin → head root -- children of this box are Kotlin compiled to Javascript" "parents" ["root" "rparity_root"] "children" ["kparity_print" "kparity_error" "kparity_controls" "kparity_late" "kparity_spin"] "properties" {"children" #{#afield/box "kparity_print" #afield/box "kparity_error" #afield/box "kparity_controls" #afield/box "kparity_late" #afield/box "kparity_spin"} "parents" #{#afield/box "root" #afield/box "rparity_root"} "name" "Kotlin → head root -- children of this box are Kotlin compiled to Javascript" "_tombstone" false "frame" #afield/rect [560.0 40.0 420.0 100.0] "mainLanguage" "kotlinjs" "isKotlinJsRoot" true} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kparity_root_code_.kt" "lines" 2 "characters" 154 "sha256" "f2ed1c80ed191de96b5ef7909f421540ec3ee223c8e497e841cddfb759f4c398"}]}
```

```kotlin [field-property box="kparity_root" name="code" path="kparity_root_code_.kt"]
// Nothing to run here. Boxes under this one are Kotlin, compiled to Javascript and sent
// to the same browser head as the Javascript boxes on the left.
```

## 3. Kotlin: an exception is marked on its line

<!-- field-box id="kparity_error" index="2" -->

### Narrative

From `code`

{{ remoteAR }}
The marker lands on line 5, the throw inside mean.

```edn [field-box id="kparity_error" role="properties"]
{"id" "kparity_error" "index" 2 "name" "Kotlin: an exception is marked on its line" "parents" ["root" "kparity_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "kparity_root"} "name" "Kotlin: an exception is marked on its line" "_tombstone" false "frame" #afield/rect [560.0 1140.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kparity_error_code_.kt" "lines" 8 "characters" 257 "sha256" "b4a5e9ab345d318ba81fb0fb8164926fd3234567e45fc9c937d33f64dff5d4ac"}]}
```

```kotlin [field-property box="kparity_error" name="code" path="kparity_error_code_.kt"]
// {{ remoteAR }}
// The marker lands on line 5, the throw inside mean.
fun mean(values: List<Double>): Double {
    if (values.isEmpty()) throw IllegalArgumentException("mean of nothing")
    return values.sum() / values.size
}

println(mean(emptyList()))
```

## 4. Kotlin: println and console.log beside their lines

<!-- field-box id="kparity_print" index="3" -->

### Narrative

From `code`

{{ remoteAR }}

```edn [field-box id="kparity_print" role="properties"]
{"id" "kparity_print" "index" 3 "name" "Kotlin: println and console.log beside their lines" "parents" ["root" "kparity_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "kparity_root"} "name" "Kotlin: println and console.log beside their lines" "_tombstone" false "frame" #afield/rect [560.0 640.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kparity_print_code_.kt" "lines" 3 "characters" 81 "sha256" "ae00a9d885e00552a96336926f8e5bf44e6aced95ccc31ee6fc4517e567ff5d7"}]}
```

```kotlin [field-property box="kparity_print" name="code" path="kparity_print_code_.kt"]
// {{ remoteAR }}
println("from println")
console.log("from console.log", 6 * 7)
```

## 5. output printed after the run finished

<!-- field-box id="rparity_late" index="4" -->

### Narrative

From `code`

The run ends at once; this line prints half a second later, beside line 3.

```edn [field-box id="rparity_late" role="properties"]
{"id" "rparity_late" "index" 4 "name" "output printed after the run finished" "parents" ["root" "rparity_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "rparity_root"} "name" "output printed after the run finished" "_tombstone" false "frame" #afield/rect [60.0 2340.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rparity_late_code_.kt" "lines" 5 "characters" 161 "sha256" "75eabac1717625d8f3b168ac98ba9dda741cc427bbe0095e7abc0904adc94f28"}]}
```

```kotlin [field-property box="rparity_late" name="code" path="rparity_late_code_.kt"]
// The run ends at once; this line prints half a second later, beside line 3.
setTimeout(() => {
  print("half a second later")
}, 500)
print("the run is over")
```

## 6. an error is marked where it happened

<!-- field-box id="rparity_error" index="5" -->

### Narrative

From `code`

The marker lands on line 4, inside mean, where reduce failed --
not on line 7, where mean was called.

```edn [field-box id="rparity_error" role="properties"]
{"id" "rparity_error" "index" 5 "name" "an error is marked where it happened" "parents" ["root" "rparity_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "rparity_root"} "name" "an error is marked where it happened" "_tombstone" false "frame" #afield/rect [60.0 1140.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rparity_error_code_.kt" "lines" 7 "characters" 200 "sha256" "585e89a9d84ed1b7d1d2d850e0cfbc0f39ef15f0198a3572f206a89352ff6591"}]}
```

```kotlin [field-property box="rparity_error" name="code" path="rparity_error_code_.kt"]
// The marker lands on line 4, inside mean, where reduce failed --
// not on line 7, where mean was called.
function mean(values) {
  return values.reduce((a, b) => a + b) / values.length
}

mean([])
```

## 7. display, a slider and a checkbox

<!-- field-box id="rparity_controls" index="6" -->

### Narrative

From `code`

The same output channel and controls a Kotlin box draws, calling back into the head.

```edn [field-box id="rparity_controls" role="properties"]
{"id" "rparity_controls" "index" 6 "name" "display, a slider and a checkbox" "parents" ["root" "rparity_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "rparity_root"} "name" "display, a slider and a checkbox" "_tombstone" false "frame" #afield/rect [60.0 1640.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rparity_controls_code_.kt" "lines" 10 "characters" 415 "sha256" "3d72fd16db7962f67704c6f07ea859b9c633275dbbcd6e9b15db4d6c8d34b2fe"}]}
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

## 8. Stop stops launch loops

<!-- field-box id="rparity_spin" index="7" -->

### Narrative

From `code`

Run this, then Stop it: the counter in the head freezes.

```edn [field-box id="rparity_spin" role="properties"]
{"id" "rparity_spin" "index" 7 "name" "Stop stops launch loops" "parents" ["root" "rparity_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "rparity_root"} "name" "Stop stops launch loops" "_tombstone" false "frame" #afield/rect [60.0 2840.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rparity_spin_code_.kt" "lines" 4 "characters" 146 "sha256" "56f51b07618940451e23fe656d172eb244d75c8f24ad3eab53316afd7eff0aaf"}]}
```

```kotlin [field-property box="rparity_spin" name="code" path="rparity_spin_code_.kt"]
// Run this, then Stop it: the counter in the head freezes.
window.spins = 0
launch(() => { window.spins++ }, "spinner", false)
print("spinning")
```

## 9. print and console.log beside their lines

<!-- field-box id="rparity_print" index="8" -->

### Narrative

From `code`

Both appear beside the line that called them; the last value is shown too.

```edn [field-box id="rparity_print" role="properties"]
{"id" "rparity_print" "index" 8 "name" "print and console.log beside their lines" "parents" ["root" "rparity_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "rparity_root"} "name" "print and console.log beside their lines" "_tombstone" false "frame" #afield/rect [60.0 640.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rparity_print_code_.kt" "lines" 5 "characters" 195 "sha256" "b0a153b8a12a75f709a40ada8bf12aa0e1e69ce22773070bd96c47c31229a5cb"}]}
```

```kotlin [field-property box="rparity_print" name="code" path="rparity_print_code_.kt"]
// Both appear beside the line that called them; the last value is shown too.
print("from print")
console.log("from console.log", {answer: 6 * 7})
const now = new Date().getFullYear()
now > 2000
```

## 10. Kotlin: display, a slider and a checkbox

<!-- field-box id="kparity_controls" index="9" -->

### Narrative

From `code`

{{ remoteAR }}

```edn [field-box id="kparity_controls" role="properties"]
{"id" "kparity_controls" "index" 9 "name" "Kotlin: display, a slider and a checkbox" "parents" ["root" "kparity_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "kparity_root"} "name" "Kotlin: display, a slider and a checkbox" "_tombstone" false "frame" #afield/rect [560.0 1640.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kparity_controls_code_.kt" "lines" 17 "characters" 346 "sha256" "d83268b6462963567f7521e00765aafcb1a3ac2cabb88c3ad5072062a1a02adb"}]}
```

```kotlin [field-property box="kparity_controls" name="code" path="kparity_controls_code_.kt"]
// {{ remoteAR }}
var freq = 3.0
var square = false

fun redraw() {
    display("main", "<b>${if (square) "square" else "sine"}</b> at ${freq.asDynamic().toFixed(1)} Hz")
}

slider("frequency", min = 1.0, max = 10.0, step = 0.1, initialValue = 3.0) {
    freq = it
    redraw()
}
checkbox("square wave") {
    square = it
    redraw()
}
redraw()
```

## 11. Kotlin: output printed after the run finished

<!-- field-box id="kparity_late" index="10" -->

### Narrative

From `code`

{{ remoteAR }}
The run ends at once; line 4 prints half a second later.

```edn [field-box id="kparity_late" role="properties"]
{"id" "kparity_late" "index" 10 "name" "Kotlin: output printed after the run finished" "parents" ["root" "kparity_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "kparity_root"} "name" "Kotlin: output printed after the run finished" "_tombstone" false "frame" #afield/rect [560.0 2340.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kparity_late_code_.kt" "lines" 9 "characters" 180 "sha256" "60291d2f9b6734c19728c0a3637b14e03d3270004dc480a526fb64843286daee"}]}
```

```kotlin [field-property box="kparity_late" name="code" path="kparity_late_code_.kt"]
// {{ remoteAR }}
// The run ends at once; line 4 prints half a second later.
setTimeout(
    {
        println("half a second later")
    },
    500,
)
println("the run is over")
```

## 12. Kotlin: Launch, then Stop, a frame loop

<!-- field-box id="kparity_spin" index="11" -->

### Narrative

From `code`

{{ remoteAR }}
Launch this, then Stop it: the frame count stops changing.

```edn [field-box id="kparity_spin" role="properties"]
{"id" "kparity_spin" "index" 11 "name" "Kotlin: Launch, then Stop, a frame loop" "parents" ["root" "kparity_root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "kparity_root"} "name" "Kotlin: Launch, then Stop, a frame loop" "_tombstone" false "frame" #afield/rect [560.0 2840.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kparity_spin_code_.kt" "lines" 7 "characters" 208 "sha256" "240bb5020a96664052e6bfcd104a571b11bc7f40fc858778aaa8299acfd36055"}]}
```

```kotlin [field-property box="kparity_spin" name="code" path="kparity_spin_code_.kt"]
// {{ remoteAR }}
// Launch this, then Stop it: the frame count stops changing.
onStart {
    onFrame { frame ->
        if (frame.frameNumber % 30 == 0) display("main", "frame ${frame.frameNumber}")
    }
}
```

