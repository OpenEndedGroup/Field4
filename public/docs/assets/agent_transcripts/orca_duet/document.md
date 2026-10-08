# Field Document: orca_duet

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_session" "fileManagerAccessHistory"]}
```

## 1. ORCA duet · run me

<!-- field-box id="orca_duet" index="0" -->

### Narrative

From `code`

Two ORCAs and Kotlin in between. Lead plays a tune in twos; Kotlin hands each note to
Echo, which plays the last eight back in threes, an octave up; every eighth note Echo
plays, Kotlin moves one note of Lead's tune a step up the scale. Click a grid to join in.

```edn [field-box id="orca_duet" role="properties"]
{"id" "orca_duet" "index" 0 "name" "ORCA duet · run me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "ORCA duet · run me" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "orca_duet_code_.kt" "lines" 57 "characters" 1944 "sha256" "0daf82df62530eb853254541aa2d7cef6ad820a988d98ef65055d4cce2df85a8"}]}
```

```kotlin [field-property box="orca_duet" name="code" path="orca_duet_code_.kt"]
// Two ORCAs and Kotlin in between. Lead plays a tune in twos; Kotlin hands each note to
// Echo, which plays the last eight back in threes, an octave up; every eighth note Echo
// plays, Kotlin moves one note of Lead's tune a step up the scale. Click a grid to join in.
val synth = PatSynthBank()
val lead = orca("lead", cols = 40, rows = 10, grid = """
    #.LEAD.#
    .
    .2C8
    D2.8TCDEGAGED
    .:04
    .
    D8
    .:12C
""")
val echo = orca("echo", cols = 40, rows = 10, grid = """
    #.ECHO.#
    .
    .3C8
    D3.8TCCCCCCCC
    .:25
""")
val up = mapOf('C' to 'D', 'D' to 'E', 'E' to 'G', 'G' to 'A', 'A' to 'C')
val heard = ArrayDeque("CCCCCCCC".toList())
var echoes = 0
var nudged = "none yet"

lead.onNote {
    if (it.channel == 0) { heard.addLast(it.note[0]); heard.removeFirst() }
    synth.play(it, pan = 0.35, wave = if (it.channel == 1) PatWave.SINE else PatWave.TRIANGLE)
}
echo.onNote {
    echoes++
    synth.play(it, pan = 0.7, wave = PatWave.SQUARE, gain = 0.35, cutoffHz = 2400.0)
}

displayOpen("between")
// The go-between. A box launches what it ends with: here the synth, and this loop beside it.
synth + frames {
    var seen = -1
    while (frame()) {
        if (lead.frame == seen) continue
        seen = lead.frame
        // Lead to Echo: Echo's track is always the last eight notes Lead played.
        heard.forEachIndexed { i, note -> echo[5 + i, 3] = note }
        // Echo to Lead: every eighth echo moves one note of Lead's tune up the scale.
        if (echoes >= 8) {
            echoes = 0
            val x = 5 + seen % 8
            val note = up[lead[x, 3]] ?: 'C'
            nudged = "lead[$x, 3] ${lead[x, 3]} → $note"
            lead[x, 3] = note
        }
        // The bass follows the tune: F under G and A, C under the rest.
        lead[4, 7] = if (lead[4, 4] in "GA") 'F' else 'C'
        markdown("between", "heard `${heard.joinToString("")}` · last nudge `$nudged`")
    }
}
```

