# Field Document: orca_jam

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. ORCA jam · run me

<!-- field-box id="orca_jam" index="0" -->

### Narrative

From `code`

Run this, open its AI Chat with Cmd-4 and ask it to play. AI4 writes into the grid while the
music runs; rewinding the chat puts the board back. Channels: 0 lead, 1 bass, 2 hat, 3 square.

```edn [field-box id="orca_jam" role="properties"]
{"id" "orca_jam" "index" 0 "name" "ORCA jam · run me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "ORCA jam · run me" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "orca_jam_code_.kt" "lines" 28 "characters" 910 "sha256" "1364a109e7a129a4042399db0a3d07b2f43f0ebdbc2eb675fddabefda11ef9ad"}]}
```

```kotlin [field-property box="orca_jam" name="code" path="orca_jam_code_.kt"]
// Run this, open its AI Chat with Cmd-4 and ask it to play. AI4 writes into the grid while the
// music runs; rewinding the chat puts the board back. Channels: 0 lead, 1 bass, 2 hat, 3 square.
val synth = PatSynthBank(voices = 24)
val o = orca(
    rows = 16,
    grid =
        """
    #.JAM.#
    .
    .4C4
    D4.4TCEGE
    .:03
""",
)
o.onNote {
    when (it.channel) {
        0 -> synth.play(it, pan = 0.4, wave = PatWave.TRIANGLE)
        1 -> synth.play(it, pan = 0.5, wave = PatWave.SINE)
        2 -> synth.play(it, pan = 0.65, wave = PatWave.NOISE, gain = 0.3, cutoffHz = 9000.0)
        else -> synth.play(it, pan = 0.6, wave = PatWave.SQUARE, gain = 0.4, cutoffHz = 3000.0)
    }
}
o.exposeTools(
    about =
        "Channel 0 is a soft triangle lead, 1 a sine bass (octaves 2 and 3 suit it), " +
            "2 a noise hi-hat (any note works), 3 a bright square for a second melody.",
)
synth
```

