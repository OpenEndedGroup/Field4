# Field Document: read_only_session

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. A saved{} counter

<!-- field-box id="a_saved_counter" index="0" -->

### Narrative

From `code`

`saved {}` is per-box persistence: assign to it and it is on disk, read it next
session and it is still there. The file is <document dir>/<box id>/runs.json.

Run this box a few times in a NORMAL session and the count climbs across restarts.
Run it in a READ-ONLY session and it climbs within the run and then forgets: the
write is refused, and `~/.afield/logs/save_reload.log` says so with a
`write-refused-read-only` line naming this property.

Before 2026-09-07 the read-only case wrote the file anyway — including on the very
first *read*, because with no file yet `saved {}` computes the default and writes it.

```edn [field-box id="a_saved_counter" role="properties"]
{"id" "a_saved_counter" "index" 0 "name" "A saved{} counter" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "A saved{} counter" "_tombstone" false "frame" #afield/rect [60.0 40.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "a_saved_counter_code_.kt" "lines" 14 "characters" 727 "sha256" "79c83eed9cbdc86858b30a73a42228e135a8df5ccd5288055ef6e47b6936abb3"}]}
```

```kotlin [field-property box="a_saved_counter" name="code" path="a_saved_counter_code_.kt"]
// `saved {}` is per-box persistence: assign to it and it is on disk, read it next
// session and it is still there. The file is <document dir>/<box id>/runs.json.
//
// Run this box a few times in a NORMAL session and the count climbs across restarts.
// Run it in a READ-ONLY session and it climbs within the run and then forgets: the
// write is refused, and `~/.afield/logs/save_reload.log` says so with a
// `write-refused-read-only` line naming this property.
//
// Before 2026-09-07 the read-only case wrote the file anyway — including on the very
// first *read*, because with no file yet `saved {}` computes the default and writes it.
var runs by saved { 0 }

runs = runs + 1
println("this box has run $runs time(s)")
```

## 2. How to see read-only mode

<!-- field-box id="how_to_see_read_only_mode" index="1" -->

### Narrative

From `code`

Read-only mode, end to end.

Open this document normally:      ./field.sh <workspace> -d read_only_session
Open it read-only:                ./field.sh <workspace> -d read_only_session --read-only

Read-only is also AFIELD_READ_ONLY=1, -Dafield.readOnly=true, or the agent
backdoor's `open --read-only` for a single session.

What to look for in a read-only session:

1. An amber pill at the top of the window: READ-ONLY, and in plain words that
nothing you change here will be saved.
2. Type in any box. The pill becomes a running count of the changes that are
going to be discarded. Cursor movement does NOT count -- Monaco's view state
is not part of the document.
3. Try to close or reload the window. The browser asks you to confirm, because
there is work to lose. Field has no explicit save, so this dialog is the only
moment a user would otherwise find out.
4. Drop a file onto the canvas. Refused, with a reason, instead of silently
copying it into a document that will not be written.

Nothing in this document needs a GPU, a network, or any setup.

```edn [field-box id="how_to_see_read_only_mode" role="properties"]
{"id" "how_to_see_read_only_mode" "index" 1 "name" "How to see read-only mode" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "How to see read-only mode" "_tombstone" false "frame" #afield/rect [60.0 1040.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "how_to_see_read_only_mode_code_.kt" "lines" 23 "characters" 1236 "sha256" "a067194446865b655ce75e21223b8d806cc2cc3af4b606f13afe45afc2344422"}]}
```

```kotlin [field-property box="how_to_see_read_only_mode" name="code" path="how_to_see_read_only_mode_code_.kt"]
// Read-only mode, end to end.
//
// Open this document normally:      ./field.sh <workspace> -d read_only_session
// Open it read-only:                ./field.sh <workspace> -d read_only_session --read-only
//
// Read-only is also AFIELD_READ_ONLY=1, -Dafield.readOnly=true, or the agent
// backdoor's `open --read-only` for a single session.
//
// What to look for in a read-only session:
//
//   1. An amber pill at the top of the window: READ-ONLY, and in plain words that
//      nothing you change here will be saved.
//   2. Type in any box. The pill becomes a running count of the changes that are
//      going to be discarded. Cursor movement does NOT count -- Monaco's view state
//      is not part of the document.
//   3. Try to close or reload the window. The browser asks you to confirm, because
//      there is work to lose. Field has no explicit save, so this dialog is the only
//      moment a user would otherwise find out.
//   4. Drop a file onto the canvas. Refused, with a reason, instead of silently
//      copying it into a document that will not be written.
//
// Nothing in this document needs a GPU, a network, or any setup.
println("open me with --read-only and read the pill at the top of the window")
```

