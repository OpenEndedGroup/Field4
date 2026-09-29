# Field Document: drawn_score

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "fileManagerAccessHistory"]}
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

Eight beats across, MIDI 84 at the top down to 48 at the bottom: every mark as an event.

```edn [field-box id="ds_read" role="properties"]
{"id" "ds_read" "index" 1 "name" "read" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "read" "_tombstone" false "frame" #afield/rect [20.0 500.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ds_read_code_.kt" "lines" 5 "characters" 345 "sha256" "63a8133de41b432953f949870e134bac21d57bceac56852b865b9b48639408d9"}]}
```

```kotlin [field-property box="ds_read" name="code" path="ds_read_code_.kt"]
// Eight beats across, MIDI 84 at the top down to 48 at the bottom: every mark as an event.
val score = notation.space("score", x = 0.0..8.0, y = 84.0..48.0)
println(score.events.joinToString("\n") { e ->
    "%s: beat %.3f to %.3f, MIDI %.2f to %.2f".format(e.mark.label ?: e.mark.kind.name.lowercase(), e.start, e.end, e.value, e.endValue)
})
```

## 3. pentatonic

<!-- field-box id="ds_pentatonic" index="2" -->

### Narrative

From `code`

The same drawing read another way: every note snapped to C minor pentatonic.

```edn [field-box id="ds_pentatonic" role="properties"]
{"id" "ds_pentatonic" "index" 2 "name" "pentatonic" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "pentatonic" "_tombstone" false "frame" #afield/rect [580.0 500.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ds_pentatonic_code_.kt" "lines" 16 "characters" 1097 "sha256" "8035b241813e604ceeb806db45f48a2a43387543b39b90dad57802ad52208ad6"}]}
```

```kotlin [field-property box="ds_pentatonic" name="code" path="ds_pentatonic_code_.kt"]
// The same drawing read another way: every note snapped to C minor pentatonic.
val notes = DrawnScoreDocs.interpret(notation.space("score", x = 0.0..8.0, y = 84.0..48.0))   // the loop in `play`

val minorPentatonic = setOf(0, 3, 5, 7, 10)                                // C Eb F G Bb
val inScale = (24..108).filter { it % 12 in minorPentatonic }
val snap = { midi: Double -> inScale.minBy { kotlin.math.abs(it - midi) }.toDouble() }
val tuned = mutableListOf<TimedNote>()
for (n in notes.map { it.copy(note = snap(it.note)) }) {
    val last = tuned.lastOrNull()
    if (last != null && last.note == n.note && last.end == n.start) tuned[tuned.size - 1] = last.copy(end = n.end)   // same pitch: hold it
    else tuned += n
}

val voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.004, sustain = 0.4)
showPatRollOf("read" to patTimed(notes, beats = 8.0), "C minor pentatonic" to patTimed(tuned, beats = 8.0), title = "one drawing, two readings", cycles = 1, pitched = true, channel = "roll")
showPatAudio(patTimed(tuned, beats = 8.0), cycles = 2, cps = 0.25, voice = voice, channel = "audio")
```

## 4. listen back

<!-- field-box id="ds_listen" index="3" -->

### Narrative

From `code`

Play both readings again, then take the sound apart: an onset per strike, a pitch per note.

```edn [field-box id="ds_listen" role="properties"]
{"id" "ds_listen" "index" 3 "name" "listen back" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "listen back" "_tombstone" false "frame" #afield/rect [860.0 500.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ds_listen_code_.kt" "lines" 11 "characters" 732 "sha256" "9efc2f7100d392b732675d4b553c3306d1fce95356f1c72d5390a08225db52c1"}]}
```

```kotlin [field-property box="ds_listen" name="code" path="ds_listen_code_.kt"]
// Play both readings again, then take the sound apart: an onset per strike, a pitch per note.
val notes = DrawnScoreDocs.interpret(notation.space("score", x = 0.0..8.0, y = 84.0..48.0))
val tuned = DrawnScoreDocs.pentatonic(notes)
val voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.004, sustain = 0.4)
val heard = listOf(notes, tuned).map { DrawnScoreDocs.listen(patRenderStereo(patTimed(it, beats = 8.0), cycles = 2, cps = 0.25, voice = voice), it) }
val trace = heard[0].trace()
showSignals(
    "drawn" to trace.map { it.third }, "heard" to trace.map { it.second },
    x = trace.map { it.first }, title = "pitch: drawn and heard", xTitle = "seconds", yTitle = "MIDI",
)
println(heard.joinToString("\n") { it.describe() })
```

## 5. play

<!-- field-box id="ds_play" index="4" -->

### Narrative

From `code`

What each mark means as music is decided here, and you can change it.

```edn [field-box id="ds_play" role="properties"]
{"id" "ds_play" "index" 4 "name" "play" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "play" "_tombstone" false "frame" #afield/rect [300.0 500.0 190.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ds_play_code_.kt" "lines" 25 "characters" 1612 "sha256" "0a52cce149e500c96d92a4ffdb787c19762b80510daa4b11739ce43ecd85c037"}]}
```

```kotlin [field-property box="ds_play" name="code" path="ds_play_code_.kt"]
// What each mark means as music is decided here, and you can change it.
val score = notation.space("score", x = 0.0..8.0, y = 84.0..48.0)   // eight beats; MIDI 84 at the top
val glideStep = 0.125                                                 // how finely a moving stroke is read

val notes = mutableListOf<TimedNote>()
for (e in score.events) {
    val kind = e.mark.kind
    if (kind == MarkType.TEXT || kind == MarkType.FRAME || kind == MarkType.ARROW) continue   // labels, not notes
    if (e.end <= e.start) continue                                                           // no width, no time
    val moving = (kind == MarkType.STROKE || kind == MarkType.LINE) && kotlin.math.abs(e.endValue - e.value) > 0.5
    if (!moving) {                                                  // a rectangle, or a level line: one note
        notes += TimedNote(e.start, e.end, e.value)
        continue
    }
    var t = e.start                                                 // a glissando: cut it every glideStep
    while (t < e.end - 1e-9) {
        val hit = score.vertical(t).firstOrNull { it.mark.id == e.mark.id && !it.isSpan }
        if (hit != null) notes += TimedNote(t, minOf(t + glideStep, e.end), hit.value, hit.pressure ?: 1.0)
        t += glideStep
    }
}

val voice = patVoice(wave = PatWave.TRIANGLE, decay = 0.004, sustain = 0.4)
showPatRollOf("read" to patTimed(notes, beats = 8.0), title = "the score, read", subtitle = "${notes.size} notes", cycles = 1, pitched = true, channel = "roll")
showPatAudio(patTimed(notes, beats = 8.0), cycles = 2, cps = 0.25, voice = voice, channel = "audio")
```

