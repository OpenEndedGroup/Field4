# Field Document: slideshow_guide

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. tour

<!-- field-box id="sg_tour" index="0" -->

### Narrative

From `code`

A four-step guide to this document. Run it, then follow the links in the note.

```edn [field-box id="sg_tour" role="properties"]
{"id" "sg_tour" "index" 0 "name" "tour" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "tour" "_tombstone" false "frame" #afield/rect [60.0 140.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sg_tour_code_.kt" "lines" 68 "characters" 1987 "sha256" "0ec35f0c1e6c28dd4a2bf5cc1cdc335b70b299da9533f7830ca40749a63fea56"}]}
```

```kotlin [field-property box="sg_tour" name="code" path="sg_tour_code_.kt"]
// A four-step guide to this document. Run it, then follow the links in the note.
val guide = thisBox
val numbersBox = inside.boxes.values.first { it.name == "numbers" }
val squaresBox = inside.boxes.values.first { it.name == "squares" }
var visits = 0
var stars = 0
//%-

val show = slideShow {
    step("Welcome") {
        markdownNote(
            """
            ## A tour of this document
            This guide has four steps. Each one selects the box it is about.

            [[next:Start →]]
            """.trimIndent(),
            animated = true
        )
        selectBox(guide)
    }
    step("numbers") {
        onEnter {
            visits += 1
            guide.properties["visits"] = visits
        }
        markdownNote(
            """
            ## `numbers` makes a list
            The green line points at it. Visits to this step: **{{visits}}**.

            [[back]] [[next]]
            """.trimIndent()
        )
        selectBox(numbersBox)
        annotate(id = "numbers", targetBox = numbersBox, color = "rgba(128, 255, 164, 0.8)", dashed = false)
    }
    step("squares") {
        markdownNote(
            """
            ## Line 3 does the work
            `squares` maps each number to its square.

            [[back]] [[next]]
            """.trimIndent()
        )
        selectBox(squaresBox, property = "code", line = 3, highlight = 3)
        annotateToLine(id = "line3", targetBox = squaresBox, line = 3, color = "rgba(255, 180, 100, 0.8)", dashed = true)
    }
    step("Your turn") {
        markdownNote(
            """
            ## Your turn
            [Give this guide a star](#custom:star). Stars so far: **{{stars}}**.

            [[home:Start again]] [[finish]]
            """.trimIndent()
        )
        linkCallback("star") {
            stars += 1
            here.properties["stars"] = stars
            show.goToStep(show.currentStepIndex)
        }
        selectBox(guide)
    }
}

runBlocking { show.start() }
```

## 2. numbers

<!-- field-box id="sg_numbers" index="1" -->

### Narrative

From `code`

Ten numbers for squares to use.

```edn [field-box id="sg_numbers" role="properties"]
{"id" "sg_numbers" "index" 1 "name" "numbers" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "numbers" "_tombstone" false "frame" #afield/rect [60.0 320.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sg_numbers_code_.kt" "lines" 3 "characters" 83 "sha256" "15499b73372165adfd132edd147d917cca851e385f50f2de191562ae82c85b97"}]}
```

```kotlin [field-property box="sg_numbers" name="code" path="sg_numbers_code_.kt"]
// Ten numbers for squares to use.
val numbers = (1..10).toList()
println(numbers)
```

## 3. squares

<!-- field-box id="sg_squares" index="2" -->

### Narrative

From `code`

Squares of the numbers 1 to 10.

```edn [field-box id="sg_squares" role="properties"]
{"id" "sg_squares" "index" 2 "name" "squares" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "squares" "_tombstone" false "frame" #afield/rect [60.0 440.0 150.0 50.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sg_squares_code_.kt" "lines" 4 "characters" 121 "sha256" "7287b9c10ffb51d366e3ed0ca9b7e2dd8c40001c7003b6304f2a252b7f7f19c3"}]}
```

```kotlin [field-property box="sg_squares" name="code" path="sg_squares_code_.kt"]
// Squares of the numbers 1 to 10.
val numbers = (1..10).toList()
val squares = numbers.map { it * it }
println(squares)
```

