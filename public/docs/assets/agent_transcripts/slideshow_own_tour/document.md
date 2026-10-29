# Field Document: slideshow_own_tour

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. tour

<!-- field-box id="sot_tour" index="0" -->

```edn [field-box id="sot_tour" role="properties"]
{"id" "sot_tour" "index" 0 "name" "tour" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "tour" "_tombstone" false "frame" #afield/rect [60.0 140.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sot_tour_code_.kt" "lines" 15 "characters" 456 "sha256" "bfa9d1c91014129f8632b10546a13bf5c37e86e808ddf545801f486b2a56a5c4"}]}
```

```kotlin [field-property box="sot_tour" name="code" path="sot_tour_code_.kt"]
val show = slideShow {
    step("Data") {
        markdownNote("## `numbers` makes the data\n[[next]]")
        selectBoxByName("numbers")
    }
    step("The work") {
        markdownNote("## Line 3 squares each number\n[[back]] [[next]]")
        selectBoxByName("squares", line = 3, highlight = 3)
    }
    step("Done") {
        markdownNote("## Run `squares` to see the result\n[[home:Start again]] [[finish]]")
    }
}

runBlocking { show.start() }
```

## 2. squares

<!-- field-box id="sot_squares" index="1" -->

### Narrative

From `code`

Squares of the numbers 1 to 10.

```edn [field-box id="sot_squares" role="properties"]
{"id" "sot_squares" "index" 1 "name" "squares" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "squares" "_tombstone" false "frame" #afield/rect [60.0 420.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sot_squares_code_.kt" "lines" 4 "characters" 121 "sha256" "7287b9c10ffb51d366e3ed0ca9b7e2dd8c40001c7003b6304f2a252b7f7f19c3"}]}
```

```kotlin [field-property box="sot_squares" name="code" path="sot_squares_code_.kt"]
// Squares of the numbers 1 to 10.
val numbers = (1..10).toList()
val squares = numbers.map { it * it }
println(squares)
```

## 3. numbers

<!-- field-box id="sot_numbers" index="2" -->

### Narrative

From `code`

Ten numbers for squares to use.

```edn [field-box id="sot_numbers" role="properties"]
{"id" "sot_numbers" "index" 2 "name" "numbers" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "numbers" "_tombstone" false "frame" #afield/rect [60.0 300.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sot_numbers_code_.kt" "lines" 3 "characters" 83 "sha256" "15499b73372165adfd132edd147d917cca851e385f50f2de191562ae82c85b97"}]}
```

```kotlin [field-property box="sot_numbers" name="code" path="sot_numbers_code_.kt"]
// Ten numbers for squares to use.
val numbers = (1..10).toList()
println(numbers)
```

