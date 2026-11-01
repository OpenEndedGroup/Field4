# Field Document: orca_hello

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 1 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. ORCA · run me

<!-- field-box id="orca_hello" index="0" -->

### Narrative

From `code`

Click the grid to type into ORCA. Space plays and pauses; Cmd-G shows every operator.

```edn [field-box id="orca_hello" role="properties"]
{"id" "orca_hello" "index" 0 "name" "ORCA · run me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "ORCA · run me" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 60.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "orca_hello_code_.kt" "lines" 24 "characters" 510 "sha256" "0347041d84a9773908cc2c7c32ae5c08ff4f11a084c1880166a2f40c54b3d662"}]}
```

```kotlin [field-property box="orca_hello" name="code" path="orca_hello_code_.kt"]
// Click the grid to type into ORCA. Space plays and pauses; Cmd-G shows every operator.
val synth = PatSynthBank()
val o = orca(
    rows = 12,
    grid =
        """
    #.ORCA.IN.FIELD.#
    .
    .2C8
    D2.8TCDEGAGED
    .:04
    .
    D8
    .:12C
""",
)
o.onNote {
    synth.play(it)
    markdown("heard", "`${it.note}${it.octave}` on channel ${it.channel}, MIDI ${it.midi}")
}
displayOpen("heard")
// Ask AI4 (Cmd-4) to change the tune: it can read, write and command this ORCA.
o.exposeTools()
synth
```

