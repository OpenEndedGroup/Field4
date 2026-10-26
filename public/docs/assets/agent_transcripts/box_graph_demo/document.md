# Field Document: box_graph_demo

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 7 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. Studio

<!-- field-box id="bgdemo_studio" index="0" -->

```edn [field-box id="bgdemo_studio" role="properties"]
{"id" "bgdemo_studio" "index" 0 "name" "Studio" "parents" ["root"] "children" ["bgdemo_sketch"] "properties" {"children" #{#afield/box "bgdemo_sketch"} "parents" #{#afield/box "root"} "name" "Studio" "_tombstone" false "frame" #afield/rect [900.0 40.0 200.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "bgdemo_studio_code_.kt" "lines" 2 "characters" 59 "sha256" "7d638a7f1cfffe9e5627881534f7aab348d776353c4a89fe5345df98f1b982f7"}]}
```

```kotlin [field-property box="bgdemo_studio" name="code" path="bgdemo_studio_code_.kt"]
val ink = java.awt.Color(200, 40, 40)
val title = "Spring"
```

## 2. Garden

<!-- field-box id="bgdemo_garden" index="1" -->

```edn [field-box id="bgdemo_garden" role="properties"]
{"id" "bgdemo_garden" "index" 1 "name" "Garden" "parents" ["root"] "children" ["bgdemo_left" "bgdemo_right" "bgdemo_sibling"] "properties" {"children" #{#afield/box "bgdemo_left" #afield/box "bgdemo_right" #afield/box "bgdemo_sibling"} "parents" #{#afield/box "root"} "name" "Garden" "_tombstone" false "frame" #afield/rect [200.0 40.0 200.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "bgdemo_garden_code_.kt" "lines" 1 "characters" 22 "sha256" "ba0e28bdddece0e79d4ff72314b395c45c146ef2b0085489795c3c49c9a931d9"}]}
```

```kotlin [field-property box="bgdemo_garden" name="code" path="bgdemo_garden_code_.kt"]
prop<Int>(petals) = 5
```

## 3. Right

<!-- field-box id="bgdemo_right" index="2" -->

```edn [field-box id="bgdemo_right" role="properties"]
{"id" "bgdemo_right" "index" 2 "name" "Right" "parents" ["root" "bgdemo_garden"] "children" ["bgdemo_bloom"] "properties" {"children" #{#afield/box "bgdemo_bloom"} "parents" #{#afield/box "root" #afield/box "bgdemo_garden"} "name" "Right" "_tombstone" false "frame" #afield/rect [360.0 180.0 200.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "bgdemo_right_code_.kt" "lines" 1 "characters" 11 "sha256" "8fc74f3378c116af5f7e711c7294e77ca7207c5619776132f0cf902364bc9908"}]}
```

```kotlin [field-property box="bgdemo_right" name="code" path="bgdemo_right_code_.kt"]
petals = 9
```

## 4. Left

<!-- field-box id="bgdemo_left" index="3" -->

```edn [field-box id="bgdemo_left" role="properties"]
{"id" "bgdemo_left" "index" 3 "name" "Left" "parents" ["root" "bgdemo_garden"] "children" ["bgdemo_bloom"] "properties" {"children" #{#afield/box "bgdemo_bloom"} "parents" #{#afield/box "root" #afield/box "bgdemo_garden"} "name" "Left" "_tombstone" false "frame" #afield/rect [40.0 180.0 200.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "bgdemo_left_code_.kt" "lines" 1 "characters" 11 "sha256" "9cd47e7ff60097fc261df91f1b54dd9f7c7251d59eb5403caafebf29b20ee3f3"}]}
```

```kotlin [field-property box="bgdemo_left" name="code" path="bgdemo_left_code_.kt"]
petals = 7
```

## 5. Bloom

<!-- field-box id="bgdemo_bloom" index="4" -->

```edn [field-box id="bgdemo_bloom" role="properties"]
{"id" "bgdemo_bloom" "index" 4 "name" "Bloom" "parents" ["root" "bgdemo_right" "bgdemo_left"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "bgdemo_right" #afield/box "bgdemo_left"} "name" "Bloom" "_tombstone" false "frame" #afield/rect [200.0 320.0 200.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "bgdemo_bloom_code_.kt" "lines" 1 "characters" 40 "sha256" "135d2464acd6cd3dec508d11ccd0eec7704dd7f0e90a0de94e92e1a40b7403da"}]}
```

```kotlin [field-property box="bgdemo_bloom" name="code" path="bgdemo_bloom_code_.kt"]
markdown("Bloom sees petals = $petals")
```

## 6. Sketch

<!-- field-box id="bgdemo_sketch" index="5" -->

```edn [field-box id="bgdemo_sketch" role="properties"]
{"id" "bgdemo_sketch" "index" 5 "name" "Sketch" "parents" ["root" "bgdemo_studio"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "bgdemo_studio"} "name" "Sketch" "_tombstone" false "frame" #afield/rect [900.0 200.0 200.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "bgdemo_sketch_code_.kt" "lines" 4 "characters" 250 "sha256" "2e11b03368baca6213a88cd0bbfe161f8a43044f96bf4321c409699f6b7ed899"}]}
```

```kotlin [field-property box="bgdemo_sketch" name="code" path="bgdemo_sketch_code_.kt"]
val ink = fromContext<java.awt.Color>()
val title = fromContextWithNameOrNull<String>("title")
val missing = fromContextWithNameOrNull<String>("subtitle")
markdown("ink = (${ink.red}, ${ink.green}, ${ink.blue}), title = $title, subtitle = $missing")
```

## 7. Sibling

<!-- field-box id="bgdemo_sibling" index="6" -->

```edn [field-box id="bgdemo_sibling" role="properties"]
{"id" "bgdemo_sibling" "index" 6 "name" "Sibling" "parents" ["root" "bgdemo_garden"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root" #afield/box "bgdemo_garden"} "name" "Sibling" "_tombstone" false "frame" #afield/rect [600.0 180.0 200.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "bgdemo_sibling_code_.kt" "lines" 1 "characters" 42 "sha256" "cb5eefcf91d7c57f5dedd62c23fa9cb9284e86fc45cf17a5cd09acbe123ee749"}]}
```

```kotlin [field-property box="bgdemo_sibling" name="code" path="bgdemo_sibling_code_.kt"]
markdown("Sibling sees petals = $petals")
```

