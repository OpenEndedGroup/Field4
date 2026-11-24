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
{"id" "exposure_meters" "index" 3 "name" "Compare the values" "parents" ["root" "exposure_rule"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "exposure_rule"} "name" "Compare the values" "_tombstone" false "frame" #afield/rect [60.0 430.0 255.0 85.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "exposure_meters_code_.kt" "lines" 18 "characters" 601 "sha256" "8d9ef40de507a7c7738531b07f68dcb2aa53e5d766386934ff60dc64a9b41724"}]}
```

```kotlin [field-property box="exposure_meters" name="code" path="exposure_meters_code_.kt"]
prop<Double>(mappedOpacity)

val measured = lightLevel
val mapped = mappedOpacity
val track = 320

fun row(label: String, value: Double, color: String): String {
    val width = (track * value).toInt()
    return """
        <p>$label ${value.f3}</p>
        <div style="width:${track}px;background:#29303c">
            <div style="width:${width}px;height:12px;background:$color"></div>
        </div>""".trimIndent()
}

display("values", row("Light level", measured, "#c4d5e7") + row("Mapped opacity", mapped, "#8ed2b3"))
displayOpen("values")
println("Light ${measured.f3} · opacity ${mapped.f3}")
```

