# Field Document: macro_recorder_stamp

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. steps

<!-- field-box id="mr_steps" index="0" -->

### Narrative

From `code`

Step through "stamp" one operation at a time, as the panel's Step button does.

```edn [field-box id="mr_steps" role="properties"]
{"id" "mr_steps" "index" 0 "name" "steps" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "steps" "_tombstone" false "frame" #afield/rect [80.0 640.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mr_steps_code_.kt" "lines" 11 "characters" 505 "sha256" "29e1d0702656c8c4012e125dac863f262b4297e1929e78422e36319c12f9ab5e"}]}
```

```kotlin [field-property box="mr_steps" name="code" path="mr_steps_code_.kt"]
// Step through "stamp" one operation at a time, as the panel's Step button does.
val document = thisBox.inside.filename
(0 until 3).forEach { step ->
    val run = OperationTranscript.runMacro(
        document,
        "stamp",
        replay = OperationTranscript.MacroReplayOptions(step = step),
    )
    println("step ${step + 1}: ${run.message}" + run.errors.joinToString("") { " " + it })
}
println("the original pair is wired: ${thisBox.inside.boxes["stamp_source"]?.children?.map { it.name }}")
```

## 2. stamp

<!-- field-box id="mr_stamp" index="1" -->

### Narrative

From `code`

A macro is YAML. This one is what the recorder writes when you make a box with code,
make another, and connect them with `g`.

```edn [field-box id="mr_stamp" role="properties"]
{"id" "mr_stamp" "index" 1 "name" "stamp" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "stamp" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mr_stamp_code_.kt" "lines" 24 "characters" 759 "sha256" "3ad0a4e2e74cf73cec5036a02b947410fe49913c7f0fc36d387705c66c084336"}]}
```

```kotlin [field-property box="mr_stamp" name="code" path="mr_stamp_code_.kt"]
// A macro is YAML. This one is what the recorder writes when you make a box with code,
// make another, and connect them with `g`.
val stamp =
    """
    name: stamp
    operations:
      - create:
          box: stamp_source
          name: Source
          frame: { x: 520.0, y: 80.0, w: 300.0, h: 100.0 }
          properties:
            code: |
              println("source")
      - create:
          box: stamp_sink
          name: Sink
          frame: { x: 880.0, y: 80.0, w: 300.0, h: 100.0 }
      - connect:
          parent: stamp_source
          child: stamp_sink
    """
        .trimIndent()
val saved = OperationTranscript.writeMacro(thisBox.inside.filename, "stamp", stamp)
println("${saved.message} ${saved.operationCount} operations")
```

## 3. copies

<!-- field-box id="mr_copies" index="2" -->

### Narrative

From `code`

Replay "stamp" as a copy three times, each 140 lower: the boxes it creates get new
identities on every run, so each run makes another connected pair.

```edn [field-box id="mr_copies" role="properties"]
{"id" "mr_copies" "index" 2 "name" "copies" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "copies" "_tombstone" false "frame" #afield/rect [80.0 360.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "mr_copies_code_.kt" "lines" 15 "characters" 670 "sha256" "4333a7a4d0d7b0eb56069e741fc85332ab40b51a9500362eac134cf2ea47b8dc"}]}
```

```kotlin [field-property box="mr_copies" name="code" path="mr_copies_code_.kt"]
// Replay "stamp" as a copy three times, each 140 lower: the boxes it creates get new
// identities on every run, so each run makes another connected pair.
val document = thisBox.inside.filename
(1..3).forEach { i ->
    val run = OperationTranscript.runMacro(
        document,
        "stamp",
        replay = OperationTranscript.MacroReplayOptions(asCopy = true, offsetY = 140.0 * i),
    )
    println(run.message + run.errors.joinToString("") { " " + it })
}
val sources = thisBox.inside.boxes.values.filter { it.name == "Source" }
println(
    "${sources.size} Source boxes; Sinks wired to each: ${sources.map { s -> s.children.count { it.name == "Sink" } }}",
)
```

