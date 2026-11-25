# Field Document: shared_kotlin_garden

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Garden settings

<!-- field-box id="garden_settings" index="0" -->

```edn [field-box id="garden_settings" role="properties"]
{"id" "garden_settings" "index" 0 "name" "Garden settings" "parents" ["root"] "children" ["garden_view"] "properties" {"children" #{#afield/box "garden_view"} "parents" #{#afield/box "root"} "name" "Garden settings" "_tombstone" false "frame" #afield/rect [370.0 40.0 260.0 90.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "garden_settings_code_.kt" "lines" 1 "characters" 58 "sha256" "8156e642dfc8608190ab6d63ade2db0acafe7478a3f5978170197670880a35f7"}]}
```

```kotlin [field-property box="garden_settings" name="code" path="garden_settings_code_.kt"]
prop<Flower>(flower) = Flower(petals = 5, color = "pink")
```

## 2. Garden types

<!-- field-box id="garden_types" index="1" -->

```edn [field-box id="garden_types" role="properties"]
{"id" "garden_types" "index" 1 "name" "Garden types" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "Garden types" "_tombstone" false "frame" #afield/rect [40.0 40.0 260.0 90.0] "box_kind" "shared-kotlin"} "blockProperties" [{"name" "code" "language" "kotlin" "path" "garden_types_code_.kt" "lines" 1 "characters" 54 "sha256" "2f5af0df9e1aa5e0410a967067cf8c09c45b5de248d3a48f4f47b18d4b0e7f49"}]}
```

```kotlin [field-property box="garden_types" name="code" path="garden_types_code_.kt"]
data class Flower(val petals: Int, val color: String)
```

## 3. Garden view

<!-- field-box id="garden_view" index="2" -->

```edn [field-box id="garden_view" role="properties"]
{"id" "garden_view" "index" 2 "name" "Garden view" "parents" ["root" "garden_settings"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "garden_settings"} "name" "Garden view" "_tombstone" false "frame" #afield/rect [370.0 210.0 260.0 90.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "garden_view_code_.kt" "lines" 2 "characters" 118 "sha256" "9e4260791716caed1a9592e82b273688a19e79c4d516b4591100493dbfabc1cb"}]}
```

```kotlin [field-property box="garden_view" name="code" path="garden_view_code_.kt"]
check(flower.petals == 5 && flower.color == "pink")
markdown("A ${flower.color} flower with ${flower.petals} petals")
```

