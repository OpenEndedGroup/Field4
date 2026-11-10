# Field Document: datalog_rhythm_drawn

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 3 · why

<!-- field-box id="datalog_rhythm_why_drawn" index="0" -->

```edn [field-box id="datalog_rhythm_why_drawn" role="properties"]
{"id" "datalog_rhythm_why_drawn" "index" 0 "name" "3 · why" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 · why" "_tombstone" false "frame" #afield/rect [60.0 380.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "datalog_rhythm_why_drawn_code_.kt" "lines" 5 "characters" 309 "sha256" "161fc919cf2fd1d861605e1160a66305e6289e1529958288e414c6342eeadd6d"}]}
```

```kotlin [field-property box="datalog_rhythm_why_drawn" name="code" path="datalog_rhythm_why_drawn_code_.kt"]
val drums = notation.space("drums", x = 0.0..16.0, y = 0.0..3.0)
val r =
    datalog("event(V, 36, 100, T) :- hit(P, V, _), pos(T, P).").rhythm().notationHits(drums, ["kick", "snare", "hh"])
println(r.grid(0))
println(r.why("event(snare, 36, 100, 4)")?.render() ?: "no snare at step 4 — nothing drawn there")
```

## 2. 1 · draw the bar

<!-- field-box id="datalog_rhythm_draw" index="1" -->

### Narrative

From `code`

(step, lane): lane 0 is kick, 1 snare, 2 hh. Edit and run again to redraw.

```edn [field-box id="datalog_rhythm_draw" role="properties"]
{"id" "datalog_rhythm_draw" "index" 1 "name" "1 · draw the bar" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · draw the bar" "_tombstone" false "frame" #afield/rect [60.0 60.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "datalog_rhythm_draw_code_.kt" "lines" 7 "characters" 362 "sha256" "019cc207a9d8349be08d400524688cde059778104ad06be605ec944eef4260e8"}]}
```

```kotlin [field-property box="datalog_rhythm_draw" name="code" path="datalog_rhythm_draw_code_.kt"]
// (step, lane): lane 0 is kick, 1 snare, 2 hh. Edit and run again to redraw.
val notes = [0 to 0, 6 to 0, 10 to 0, 4 to 1, 12 to 1, 2 to 2, 14 to 2]
notation {
    rect(400, 60, 480, 90, name = "drums")
    for ((step, lane) in notes) rect(400 + step * 30 + 4, 60 + lane * 30 + 8, 14, 14, name = "n$step.$lane")
}
println("drew ${notes.size} marks in 'drums'")
```

## 3. 2 · play it — launch me

<!-- field-box id="datalog_rhythm_play" index="2" -->

```edn [field-box id="datalog_rhythm_play" role="properties"]
{"id" "datalog_rhythm_play" "index" 2 "name" "2 · play it — launch me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · play it — launch me" "_tombstone" false "frame" #afield/rect [60.0 220.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "datalog_rhythm_play_code_.kt" "lines" 10 "characters" 354 "sha256" "de9dd79628cdcefd37fb0312d2277ca00d7073444f9bad8edf4e7f8c6f39fd07"}]}
```

```kotlin [field-property box="datalog_rhythm_play" name="code" path="datalog_rhythm_play_code_.kt"]
val drums = notation.space("drums", x = 0.0..16.0, y = 0.0..3.0)
val r = datalog(
    """
    event(V, 36, 100, T) :- hit(P, V, _), pos(T, P).
    event(hh, 42, 40, T) :- every(T, 2, 1), pos(T, P), span(hh, A, B, _), P >= A, P < B.
    """,
).rhythm()
    .notationHits(drums, ["kick", "snare", "hh"])
println(r.grid(0))
patSynth(r.pattern(), cps = 0.5)
```

