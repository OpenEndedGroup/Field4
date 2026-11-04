# Field Document: alife_search_comparison

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 11 — search vs random: Life-like rules

<!-- field-box id="compare_life" index="0" -->

```edn [field-box id="compare_life" role="properties"]
{"id" "compare_life" "index" 0 "name" "11 — search vs random: Life-like rules" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "11 — search vs random: Life-like rules" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "compare_life_code_.kt" "lines" 10 "characters" 393 "sha256" "0bee0788b9c8188ac152e22901f777d2815fcb28d5dc891423aa603ac65e8d5b"}]}
```

```kotlin [field-property box="compare_life" name="code" path="compare_life_code_.kt"]
val soups = [randomSoup(48, 48, density = 0.35, seed = 101L), randomSoup(48, 48, density = 0.12, seed = 202L)]
val c = compareSearch(
    LifeRuleGenome(radius = 2),
    caEdgeOfChaosObjective(soups, warmup = 20, scored = 30),
    budget = 120,
    seeds = [1L, 2L, 3L, 4L],
    label = "life rule, edge of chaos",
)
println(c.table() + c.budgetTable([30, 60, 120]))
showImg(c.plot(360, 260))
```

## 2. 11 — search vs random: Lenia solitons

<!-- field-box id="compare_lenia" index="1" -->

```edn [field-box id="compare_lenia" role="properties"]
{"id" "compare_lenia" "index" 1 "name" "11 — search vs random: Lenia solitons" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "11 — search vs random: Lenia solitons" "_tombstone" false "frame" #afield/rect [980.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "compare_lenia_code_.kt" "lines" 9 "characters" 294 "sha256" "a1a2df1dbc69e4a6d83d90dc40d07d7611745ffaa704860b99889ebb36ce60e3"}]}
```

```kotlin [field-property box="compare_lenia" name="code" path="compare_lenia_code_.kt"]
val c = compareSearch(
    LeniaGenome(radius = 6, dt = 0.1, shells = 3),
    leniaSolitonObjective(width = 48, height = 48, steps = 90, initialSeed = 5L, driftScale = 8.0),
    budget = 60,
    seeds = [1L, 2L, 3L],
    label = "lenia, soliton",
)
println(c.table())
showImg(c.plot(360, 260))
```

## 3. 11 — search vs random: L-systems

<!-- field-box id="compare_lsystem" index="2" -->

```edn [field-box id="compare_lsystem" role="properties"]
{"id" "compare_lsystem" "index" 2 "name" "11 — search vs random: L-systems" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "11 — search vs random: L-systems" "_tombstone" false "frame" #afield/rect [520.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "compare_lsystem_code_.kt" "lines" 9 "characters" 242 "sha256" "0fa599add7e5ab50a8b85e47bed3ec3072c718733ad0ae9483a3a816980793de"}]}
```

```kotlin [field-property box="compare_lsystem" name="code" path="compare_lsystem_code_.kt"]
val c = compareSearch(
    LSystemGenome(),
    lSystemDimensionObjective(),
    budget = 120,
    seeds = [1L, 2L, 3L, 4L],
    label = "l-system, dimension 1.7",
)
println(c.table() + c.budgetTable([30, 60, 120]))
showImg(c.plot(360, 260))
```

