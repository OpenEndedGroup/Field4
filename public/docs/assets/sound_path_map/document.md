# Field Document: sound_path_map

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory" "isClojureRoot" "isForthRoot" "isFieldMLRoot" "isMorphoRoot" "isNanoRoot" "nanoLaunchDelayMs" "nanoMemoryInit" "isKotlinJsRoot" "mainLanguage" "spreadsheet"]}
```

## 1. root

<!-- field-box id="root" index="0" -->

```edn [field-box id="root" role="properties"]
{"id" "root" "index" 0 "name" "root" "parents" ["metaroot"] "children" ["sound_map_paths" "sound_map_compare"] "properties" {"children" #{#afield/box "sound_map_paths" #afield/box "sound_map_compare"} "parents" #{#afield/box "metaroot"} "name" "root" "nested" #{}} "blockProperties" []}
```

## 2. 1 · The map

<!-- field-box id="sound_map_paths" index="1" -->

### Narrative

From `code`

The map, printed from the code rather than from a page. Seven ways to make samples, five ways
out, four ways in — and 9 of the 16 on the one seam that matters.

```edn [field-box id="sound_map_paths" role="properties"]
{"id" "sound_map_paths" "index" 1 "name" "1 · The map" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 · The map" "_tombstone" false "frame" #afield/rect [60.0 60.0 520.0 150.0] "nested" #{} "preamble" "" "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sound_map_paths_code_.kt" "lines" 14 "characters" 878 "sha256" "57d03177d65f9848c31b17b000bbba97e2e5afb4202dcc8fb99e648d36d85273"}]}
```

```kotlin [field-property box="sound_map_paths" name="code" path="sound_map_paths_code_.kt"]
// The map, printed from the code rather than from a page. Seven ways to make samples, five ways
// out, four ways in — and 9 of the 16 on the one seam that matters.
SoundPathMap.Role.values().forEach { role ->
    println("${role.label}:")
    SoundPathMap.PATHS.filter { it.role == role }.forEach { p ->
        println("  %-20s %-19s %s  needs: %s".format(
            p.id, p.timing.label, if (p.onSeam) "[on the seam]" else "[off the seam]", p.needs))
        println("      ${p.what}")
        println("      ${p.file} — ${p.entryPoint}${if (p.page == null) "   (NO PAGE DOCUMENTS THIS)" else ""}")
    }
}
println()
println("${SoundPathMap.PATHS.count { it.onSeam }} of ${SoundPathMap.PATHS.size} paths meet AudioPatchSpec.Renderer.render(outL, outR, numFrames, ctx)")
println("no published page mentions: ${SoundPathMap.PATHS.filter { it.page == null }.map { it.id }}")
```

## 3. 2 · One buffer, three paths

<!-- field-box id="sound_map_compare" index="2" -->

### Narrative

From `code`

One buffer, three paths. The dry beat is rendered once by patVoice and then lowpassed at
900 Hz twice — by the Signal graph's own ChamberlinLowpass, and by a Faust program compiled
at runtime. Three players, a brightness plot, and the seam claim as a number.

The first run pays 13-22s of Truffle warm-up for the Faust compile. The second does not.

```edn [field-box id="sound_map_compare" role="properties"]
{"id" "sound_map_compare" "index" 2 "name" "2 · One buffer, three paths" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 · One buffer, three paths" "_tombstone" false "frame" #afield/rect [60.0 240.0 520.0 150.0] "nested" #{} "preamble" "" "autorun" false} "blockProperties" [{"name" "code" "language" "kotlin" "path" "sound_map_compare_code_.kt" "lines" 7 "characters" 410 "sha256" "11ca9c3a450959a396ddaec318cbfc7c2e8a6b03d33487fa75fbdc76493160b1"}]}
```

```kotlin [field-property box="sound_map_compare" name="code" path="sound_map_compare_code_.kt"]
// One buffer, three paths. The dry beat is rendered once by patVoice and then lowpassed at
// 900 Hz twice — by the Signal graph's own ChamberlinLowpass, and by a Faust program compiled
// at runtime. Three players, a brightness plot, and the seam claim as a number.
//
// The first run pays 13-22s of Truffle warm-up for the Faust compile. The second does not.
showSoundPathComparison()
displayOpen("path0")
```

