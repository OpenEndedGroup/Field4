# Field Document: datalog_rhythm_rules

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 2 · why

<!-- field-box id="datalog_rhythm_why" index="0" -->

```edn [field-box id="datalog_rhythm_why" role="properties"]
{"id" "datalog_rhythm_why" "index" 0 "name" "2 · why" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · why" "_tombstone" false "frame" #afield/rect [60.0 390.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "datalog_rhythm_why_code_.kt" "lines" 11 "characters" 379 "sha256" "648021bf79c5f3ba323459b6159cbd98b21e561950dac9c846b93d5594ea6bde"}]}
```

```kotlin [field-property box="datalog_rhythm_why" name="code" path="datalog_rhythm_why_code_.kt"]
val r = datalog(
    """
    event(kick, 36, 100, T)  :- euc(T, 4, 16, 0).
    event(snare, 38, 90, T)  :- within(T, 16, 64), every(T, 8, 4).
    """,
).rhythm()
println(r.why("event(kick, 36, 100, 20)")?.render() ?: "no kick at 20")
println(
    "snare at step 4: " +
        (r.why("event(snare, 38, 90, 4)")?.render() ?: "did not play — within(T, 16, 64) starts at bar 1"),
)
```

## 2. 1 · rules

<!-- field-box id="datalog_rhythm_rules" index="1" -->

```edn [field-box id="datalog_rhythm_rules" role="properties"]
{"id" "datalog_rhythm_rules" "index" 1 "name" "1 · rules" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · rules" "_tombstone" false "frame" #afield/rect [60.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "datalog_rhythm_rules_code_.kt" "lines" 13 "characters" 522 "sha256" "802db30b7863ad4c12755c374993807579c9e4275ea4816266bc13bca33ddd00"}]}
```

```kotlin [field-property box="datalog_rhythm_rules" name="code" path="datalog_rhythm_rules_code_.kt"]
val song = datalog(
    """
    event(kick, 36, 100, T)  :- euc(T, 4, 16, 0).
    event(snare, 38, 90, T)  :- within(T, 16, 64), every(T, 8, 4).
    event(hh, 42, 45, T)     :- within(T, 32, 64), every(T, 2, 1).
    event(lead, N, 80, T)    :- euc(T, 5, 16, 2), D = (T div 3) mod 5, scale(48, minor_pent, D, 1, N).
    """,
)
val r = song.rhythm()
val text = (0L until 4L).joinToString("\n\n") { "bar $it\n" + r.grid(it) }
println(text)
display("grid", "<pre>$text</pre>")
showPatAudio(r.pattern(), cycles = 4, cps = 0.5)
```

