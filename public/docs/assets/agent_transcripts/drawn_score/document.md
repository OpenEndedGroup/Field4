# Field Document: drawn_score

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
```

## 1. score

<!-- field-box id="ds_score" index="0" -->

### Narrative

From `code`

The score, drawn from code into the document's drawing: a frame named "score", a piano
roll, a glissando and a held note. Running it again replaces these marks; anything you
draw by hand in the frame is left alone and is read with them.

```edn [field-box id="ds_score" role="properties"]
{"id" "ds_score" "index" 0 "name" "score" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "score" "_tombstone" false "frame" #afield/rect [20.0 40.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ds_score_code_.kt" "lines" 5 "characters" 317 "sha256" "3a2744048d18217c4dcbba09c99d57dd930ad2e46aab655a20b7b7cf7f07fd0f"}]}
```

```kotlin [field-property box="ds_score" name="code" path="ds_score_code_.kt"]
// The score, drawn from code into the document's drawing: a frame named "score", a piano
// roll, a glissando and a held note. Running it again replaces these marks; anything you
// draw by hand in the frame is left alone and is read with them.
notation { drawnScore() }
println("${notation.mine.size} marks drawn")
```

## 2. read

<!-- field-box id="ds_read" index="1" -->

### Narrative

From `code`

Eight beats across, MIDI 84 at the top down to 48 at the bottom.

```edn [field-box id="ds_read" role="properties"]
{"id" "ds_read" "index" 1 "name" "read" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "read" "_tombstone" false "frame" #afield/rect [20.0 500.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ds_read_code_.kt" "lines" 5 "characters" 315 "sha256" "8384025746b4338de13a3d4c0a14c7ffd1d15e18d4f14b1715bbe51516035e6c"}]}
```

```kotlin [field-property box="ds_read" name="code" path="ds_read_code_.kt"]
// Eight beats across, MIDI 84 at the top down to 48 at the bottom.
val score = notation.space("score", x = 0.0..8.0, y = 84.0..48.0)
println(score.notes().joinToString("\n") { n ->
    "beat %.3f to %.3f: MIDI %.2f (%s)".format(n.start, n.end, n.pitch, n.event.mark.label ?: n.event.mark.kind.name.lowercase())
})
```

## 3. listen back

<!-- field-box id="ds_listen" index="2" -->

### Narrative

From `code`

Render the reading again, then take the sound apart: an onset per strike, a pitch per note.

```edn [field-box id="ds_listen" role="properties"]
{"id" "ds_listen" "index" 2 "name" "listen back" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "listen back" "_tombstone" false "frame" #afield/rect [580.0 500.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ds_listen_code_.kt" "lines" 12 "characters" 731 "sha256" "58aa15b90fcf61403f42230cbe6420ff63af9b160aa44835309749ad36d87545"}]}
```

```kotlin [field-property box="ds_listen" name="code" path="ds_listen_code_.kt"]
// Render the reading again, then take the sound apart: an onset per strike, a pitch per note.
val score = notation.space("score", x = 0.0..8.0, y = 84.0..48.0)
val notes = score.notes()
val audio = patRenderStereo(score.toPattern(), cycles = 2, cps = 0.25, voice = DrawnScoreDocs.VOICE)
val heard = DrawnScoreDocs.listen(audio, notes)
val trace = heard.trace()
showSignals(
    "drawn" to trace.map { it.third }, "heard" to trace.map { it.second },
    x = trace.map { it.first }, title = "pitch: drawn and heard", xTitle = "seconds", yTitle = "MIDI",
)
println("${heard.onsetsDetected} onsets for ${heard.scheduled} notes, worst %.1f ms; worst pitch error %.3f semitones"
    .format(heard.maxOnsetErrorMs, heard.maxPitchError))
```

## 4. play

<!-- field-box id="ds_play" index="3" -->

### Narrative

From `code`

The whole score is one cycle, so 0.25 cycles a second is 120 beats a minute.

```edn [field-box id="ds_play" role="properties"]
{"id" "ds_play" "index" 3 "name" "play" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "play" "_tombstone" false "frame" #afield/rect [300.0 500.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ds_play_code_.kt" "lines" 3 "characters" 251 "sha256" "aca6dd3ccb4adccfb838eaa383c0a3c9d93d476652d22d226b91fb47a7a36389"}]}
```

```kotlin [field-property box="ds_play" name="code" path="ds_play_code_.kt"]
// The whole score is one cycle, so 0.25 cycles a second is 120 beats a minute.
val score = notation.space("score", x = 0.0..8.0, y = 84.0..48.0)
showPatAudio(score.toPattern(), cycles = 2, cps = 0.25, voice = DrawnScoreDocs.VOICE, channel = "audio")
```

