# Field Document: fieldml_reactive_bridge

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole" "mainLanguage" "isFieldMLRoot"]}
```

## 1. Light level

<!-- field-box id="exposure_slider" index="0" -->

```edn [field-box id="exposure_slider" role="properties"]
{"id" "exposure_slider" "index" 0 "name" "Light level" "parents" ["root"] "children" ["exposure_rule"] "properties" {"children" #{#afield/box "exposure_rule"} "parents" #{#afield/box "root"} "name" "Light level" "_tombstone" false "frame" #afield/rect [60.0 60.0 255.0 70.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "exposure_slider_code_.kt" "lines" 3 "characters" 109 "sha256" "b0bc333e0a45f5a81872d61c8bd131bb2add986b43434cbc8f0a250c51b7b18d"}]}
```

```kotlin [field-property box="exposure_slider" name="code" path="exposure_slider_code_.kt"]
propDown<Double>(lightLevel)
syncToProperty("lightLevel", 0.5, min = 0.0, max = 1.0)
displayOpen("controls")
```

## 2. FieldML

<!-- field-box id="exposure_fieldml" index="1" -->

```edn [field-box id="exposure_fieldml" role="properties"]
{"id" "exposure_fieldml" "index" 1 "name" "FieldML" "parents" ["root"] "children" ["exposure_rule"] "properties" {"children" #{#afield/box "exposure_rule"} "parents" #{#afield/box "root"} "name" "FieldML" "_tombstone" false "frame" #afield/rect [390.0 60.0 180.0 70.0] "isFieldMLRoot" true "mainLanguage" "fieldml"} "blockProperties" []}
```

## 3. Map light to opacity

<!-- field-box id="exposure_rule" index="2" -->

```edn [field-box id="exposure_rule" role="properties"]
{"id" "exposure_rule" "index" 2 "name" "Map light to opacity" "parents" ["root" "exposure_slider" "exposure_fieldml"] "children" ["exposure_meters"] "properties" {"children" #{#afield/box "exposure_meters"} "parents" #{#afield/box "root" #afield/box "exposure_slider" #afield/box "exposure_fieldml"} "name" "Map light to opacity" "_tombstone" false "frame" #afield/rect [60.0 250.0 255.0 85.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "exposure_rule_code_.kt" "lines" 3 "characters" 132 "sha256" "3e9b3cdeb4b4605affc234595071504fa7b515cf04b3b115e5f16821f18faef7"}]}
```

```kotlin [field-property box="exposure_rule" name="code" path="exposure_rule_code_.kt"]
let measured = propDown "lightLevel" |> hostAsFloat
let mapped = 0.1 + 0.9 * measured * measured
setPropDown "mappedOpacity" mapped
```

## 4. Compare the values

<!-- field-box id="exposure_meters" index="3" -->

```edn [field-box id="exposure_meters" role="properties"]
{"id" "exposure_meters" "index" 3 "name" "Compare the values" "parents" ["root" "exposure_rule"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "exposure_rule"} "name" "Compare the values" "_tombstone" false "frame" #afield/rect [60.0 430.0 255.0 85.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "exposure_meters_code_.kt" "lines" 22 "characters" 678 "sha256" "c67b22875f30a32845fcc0230c3c17ee52a1654ea33c3a72edda38ca7768be3f"}]}
```

```kotlin [field-property box="exposure_meters" name="code" path="exposure_meters_code_.kt"]
prop<Double>(mappedOpacity)

val measured = lightLevel
val mapped = mappedOpacity
val track = 320.0
fun bar(row: Int, fraction: Double, ink: Vec4) = fline {
    rect(0.0, row * 30.0, track * fraction, 12.0)
    filled = true
    stroked = false
    color = ink
}
val background = colorHex("#29303c")
val bars = [
    bar(0, 1.0, background),
    bar(0, measured, colorHex("#c4d5e7")),
    bar(1, 1.0, background),
    bar(1, mapped, colorHex("#8ed2b3")),
]
display("values", drawToSVG(bars, w = track, h = 50.0))
markdown("readout", "Light level **${measured.f3}** · mapped opacity **${mapped.f3}**")
displayOpen("values")
println("Light ${measured.f3} · opacity ${mapped.f3}")
```

