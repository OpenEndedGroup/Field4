# Field Document: shader_study_region_checks

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. RectSL: protect the rest of the wall

<!-- field-box id="rectsl_region" index="0" -->

```edn [field-box id="rectsl_region" role="properties"]
{"id" "rectsl_region" "index" 0 "name" "RectSL: protect the rest of the wall" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "RectSL: protect the rest of the wall" "_tombstone" false "frame" #afield/rect [70.0 80.0 400.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "rectsl_region_code_.kt" "lines" 53 "characters" 1692 "sha256" "b47583dc50f12b922690e4492b7ab70e972db33cbafc4207cefd7e64e92854d1"}]}
```

```kotlin [field-property box="rectsl_region" name="code" path="rectsl_region_code_.kt"]
import afield.lang.rectsl.study.RectSLStudy
import afield.study.source.SourceStudy
import kotlin.io.path.createTempDirectory

val scene =
    """
    ->wall 16 9 meters
    ->duration 4
    ->background 0x161e2e

    "sun" rect
      -4 1 ->xy
      3 ->square
      0xeab962 solid ->fill
    ;

    "moon" rect
      4 -1 ->xy
      2 ->square
      0xabbceb solid ->fill
      '( 0=4  4=5 ) ->x
    ;""".trimIndent()
val moonFill = "0xabbceb"
val sunFill = "0xeab962"
val localEdit = scene.replace(moonFill, "0x93e5bb")
val spillEdit = localEdit.replace(sunFill, "0xdb756f")
val allowed = listOf(2.5, -2.5, 6.5, 0.5) // x0, y0, x1, y1 in metres
val times = listOf(0.0, 2.0, 3.5)

val folder = createTempDirectory("rectsl-region-").toFile()
try {
    var source = scene
    val study =
        RectSLStudy(folder, { source }) { expected, replacement ->
            if (source != expected) false
            else {
                source = replacement
                true
            }
        }
    val baseline = study.checkIn("Original wall").revision
    for ((label, candidate) in [("Moon only" to localEdit), ("Moon and sun" to spillEdit)]) {
        val revision = study.edit(study.currentId(), SourceStudy.hash(source), candidate, label).revision
        val report = study.compare(baseline, revision, seconds = times, region = allowed, size = 192)
        val held = report.facts["preservedOutsideRegion"] == true
        val verdict = if (held) "pass" else "changed outside the allowed rectangle"
        display(label, "<p><strong>$verdict</strong></p>" + report.h)
        displayOpen(label)
        println("$label: $verdict")
    }
} finally {
    folder.deleteRecursively()
}
```

## 2. StackSL: protect the left half

<!-- field-box id="stacksl_region" index="1" -->

```edn [field-box id="stacksl_region" role="properties"]
{"id" "stacksl_region" "index" 1 "name" "StackSL: protect the left half" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "StackSL: protect the left half" "_tombstone" false "frame" #afield/rect [980.0 80.0 385.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "stacksl_region_code_.kt" "lines" 34 "characters" 1387 "sha256" "5b91ca2f7aec15cf3d8f46fb1ba2a0fa53db62a67b7a3031d109b922cc9669a0"}]}
```

```kotlin [field-property box="stacksl_region" name="code" path="stacksl_region_code_.kt"]
import afield.lang.stacksl.study.StackSLStudy
import afield.study.source.SourceStudy
import kotlin.io.path.createTempDirectory

val program = "uv saturate"
val localEdit = "$program\nwhite 0.5 uv x step mix"
val spillEdit = "$program\nwhite 0.35 uv x step mix"
val allowed = listOf(0.5, 0.0, 1.0, 1.0) // x0, y0, x1, y1 in UV space
val times = listOf(0.0)

val folder = createTempDirectory("stacksl-region-").toFile()
try {
    var source = program
    val study =
        StackSLStudy(folder, { source }) { expected, replacement ->
            if (source != expected) false
            else {
                source = replacement
                true
            }
        }
    val baseline = study.checkIn("Original shader").revision
    for ((label, candidate) in [("Right half" to localEdit), ("Mask crosses the midpoint" to spillEdit)]) {
        val revision = study.edit(study.currentId(), SourceStudy.hash(source), candidate, label).revision
        val report = study.compare(baseline, revision, seconds = times, region = allowed, size = 128)
        val held = report.facts["preservedOutsideRegion"] == true
        val verdict = if (held) "pass" else "changed outside the allowed rectangle"
        display(label, "<p><strong>$verdict</strong></p>" + report.h)
        displayOpen(label)
        println("$label: $verdict")
    }
} finally {
    folder.deleteRecursively()
}
```

