# Field Document: stroke_playheads

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. meet

<!-- field-box id="sp_meet" index="0" -->

### Narrative

From `code`

Each named stroke is a track; the tracks change only where two playheads meet.

```edn [field-box id="sp_meet" role="properties"]
{"id" "sp_meet" "index" 0 "name" "meet" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "meet" "_tombstone" false "frame" #afield/rect [20.0 500.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sp_meet_code_.kt" "lines" 9 "characters" 598 "sha256" "9d15c589e0d1d40cca2e29e53f01221740a22be7f8b7d9db0f205e13e73084c8"}]}
```

```kotlin [field-property box="sp_meet" name="code" path="sp_meet_code_.kt"]
// Each named stroke is a track; the tracks change only where two playheads meet.
val strokes = notation.strokes.filter { it.name != null }.associate { it.name!! to it.points }
val tracks = StrokePlayheadsDocs.tracks(strokes) // the three tracks and their settings
val run = StrokePlayheads(tracks, MeetingRule.Crossing(windowBeats = 1.0), seed = 4).run(beats = 32.0)
notation("meetings") {
    stroke = "#f08c00"
    for (m in run.meetings) ellipse(m.where.x - 14.0, m.where.y - 14.0, 28.0, 28.0)
}
println(run.meetings.joinToString("\n") { "beat %.2f: %s meets %s".format(it.beat, it.a, it.b) })
```

## 2. listen back

<!-- field-box id="sp_listen" index="1" -->

### Narrative

From `code`

Each track rendered alone, its onsets and pitches found; the wave's heard transposition.

```edn [field-box id="sp_listen" role="properties"]
{"id" "sp_listen" "index" 1 "name" "listen back" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "listen back" "_tombstone" false "frame" #afield/rect [580.0 500.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sp_listen_code_.kt" "lines" 13 "characters" 652 "sha256" "fcdee329c2eac93b4ac3dd87351e55853ae2a64a480a248337a5f897a94211d7"}]}
```

```kotlin [field-property box="sp_listen" name="code" path="sp_listen_code_.kt"]
// Each track rendered alone, its onsets and pitches found; the wave's heard transposition.
val run = StrokePlayheadsDocs.run(StrokePlayheadsDocs.readStrokes(thisBox))
val heard = StrokePlayheadsDocs.listen(run)
val shift = StrokePlayheadsDocs.heardTranspositions(run, heard.first { it.track == "wave" })
showSignals(
    "heard transposition" to shift,
    x = run.notes.filter { it.track == "wave" }.map { it.start },
    marks = run.meetings.map { "%s/%s".format(it.a, it.b) to it.beat },
    title = "wave: transposition heard, meetings marked",
    xTitle = "beats",
    yTitle = "semitones",
)
println(heard.joinToString("\n") { it.describe() })
```

## 3. play

<!-- field-box id="sp_play" index="2" -->

### Narrative

From `code`

The notes as a pattern: a piano roll per track, and all three in an audio player.

```edn [field-box id="sp_play" role="properties"]
{"id" "sp_play" "index" 2 "name" "play" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "play" "_tombstone" false "frame" #afield/rect [300.0 500.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sp_play_code_.kt" "lines" 14 "characters" 735 "sha256" "bb0c953a7593a81c6d65d164125aa418a716e7a9aaa30c6703077dafcde0d26d"}]}
```

```kotlin [field-property box="sp_play" name="code" path="sp_play_code_.kt"]
// The notes as a pattern: a piano roll per track, and all three in an audio player.
val run = StrokePlayheadsDocs.run(StrokePlayheadsDocs.readStrokes(thisBox))
val timed = run.timedNotes()
val rolls =
    ["wave", "rise", "arc"].map { name ->
        name to
            patTimed(
                run.notes.filter { it.track == name }.map { TimedNote(it.start, it.end, it.note, it.gain) },
                beats = 32.0,
            )
    }
showPatRollOf(*rolls.toTypedArray(), title = "three playheads", cycles = 1, pitched = true, channel = "roll")
val voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.004, sustain = 0.4)
showPatAudio(patTimed(timed, beats = 32.0), cycles = 1, cps = 1.0 / 16.0, voice = voice, channel = "audio")
```

## 4. strokes

<!-- field-box id="sp_strokes" index="3" -->

### Narrative

From `code`

Three crossing strokes in a frame named "anima": wave, rise and arc.

```edn [field-box id="sp_strokes" role="properties"]
{"id" "sp_strokes" "index" 3 "name" "strokes" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "strokes" "_tombstone" false "frame" #afield/rect [20.0 40.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sp_strokes_code_.kt" "lines" 3 "characters" 150 "sha256" "89ac0117698371668bd5dc5455bcac9b9462a2bd8e29afffda61bc6f2c1b419c"}]}
```

```kotlin [field-property box="sp_strokes" name="code" path="sp_strokes_code_.kt"]
// Three crossing strokes in a frame named "anima": wave, rise and arc.
notation { threeStrokes() }
println("${notation.strokes.size} strokes drawn")
```

