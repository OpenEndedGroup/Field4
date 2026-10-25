# Field Document: sound_path_map

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 1 · The map

<!-- field-box id="sound_map_paths" index="0" -->

### Narrative

From `code`

The map, printed from the code rather than from a page: what makes samples, what carries
them out, what brings them in, and which of them meet the one `render` seam.

```edn [field-box id="sound_map_paths" role="properties"]
{"id" "sound_map_paths" "index" 0 "name" "1 · The map" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · The map" "_tombstone" false "frame" #afield/rect [60.0 60.0 520.0 150.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sound_map_paths_code_.kt" "lines" 24 "characters" 1074 "sha256" "e92f8906030c3338aaca8e5eda3946d09619b3332e84806e886e110273494f47"}]}
```

```kotlin [field-property box="sound_map_paths" name="code" path="sound_map_paths_code_.kt"]
// The map, printed from the code rather than from a page: what makes samples, what carries
// them out, what brings them in, and which of them meet the one `render` seam.
SoundPathMap.Role.values().forEach { role ->
    println("${role.label}:")
    SoundPathMap.PATHS.filter { it.role == role }
        .forEach { p ->
            println(
                "  %-20s %-19s %s  needs: %s"
                    .format(
                        p.id,
                        p.timing.label,
                        if (p.onSeam) "[on the seam]" else "[off the seam]",
                        p.needs,
                    ),
            )
            println("      ${p.what}")
            println("      ${p.file} — ${p.entryPoint}${if (p.page == null) "   (NO PAGE DOCUMENTS THIS)" else ""}")
        }
}
println()
println(
    "${SoundPathMap.PATHS.count { it.onSeam }} of ${SoundPathMap.PATHS.size} paths meet AudioPatchSpec.Renderer.render(outL, outR, numFrames, ctx)",
)
println("no published page mentions: ${SoundPathMap.PATHS.filter { it.page == null }.map { it.id }}")
```

## 2. 2 · One buffer, three paths

<!-- field-box id="sound_map_compare" index="1" -->

### Narrative

From `code`

One buffer, three paths. The dry beat is a real kick, snare and closed hat from Tidal's
Dirt-Samples, mixed offline by patRenderStereoDirt. Then it is lowpassed at 900 Hz twice:
by the Signal graph's own ChamberlinLowpass, and by a Faust program compiled at runtime.
Three players, a brightness plot, and the seam claim as a number.

The samples are a pinned, checksummed download of a few Dirt-Samples folders. Run
`./gradlew fetchDocsDirtSamples` once; without them this box stops and says so, rather
than playing a synth stand-in.

The first run pays 13-22s of Truffle warm-up for the Faust compile. The second does not.

```edn [field-box id="sound_map_compare" role="properties"]
{"id" "sound_map_compare" "index" 1 "name" "2 · One buffer, three paths" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · One buffer, three paths" "_tombstone" false "frame" #afield/rect [60.0 240.0 520.0 150.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sound_map_compare_code_.kt" "lines" 14 "characters" 677 "sha256" "c13a85b05bf55b31ff2c111ef88c57efbb78e2cbba0e297ae83e7c2038c1de3e"}]}
```

```kotlin [field-property box="sound_map_compare" name="code" path="sound_map_compare_code_.kt"]
/*
One buffer, three paths. The dry beat is a real kick, snare and closed hat from Tidal's
Dirt-Samples, mixed offline by patRenderStereoDirt. Then it is lowpassed at 900 Hz twice:
by the Signal graph's own ChamberlinLowpass, and by a Faust program compiled at runtime.
Three players, a brightness plot, and the seam claim as a number.

The samples are a pinned, checksummed download of a few Dirt-Samples folders. Run
`./gradlew fetchDocsDirtSamples` once; without them this box stops and says so, rather
than playing a synth stand-in.

The first run pays 13-22s of Truffle warm-up for the Faust compile. The second does not.
*/
showSoundPathComparison()
displayOpen("path0")
```

