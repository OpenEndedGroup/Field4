# Field Document: braitenberg_brain_search

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 10 — 8 genes vs 344 genes, moving lamps

<!-- field-box id="brain_sizes" index="0" -->

```edn [field-box id="brain_sizes" role="properties"]
{"id" "brain_sizes" "index" 0 "name" "10 — 8 genes vs 344 genes, moving lamps" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "10 — 8 genes vs 344 genes, moving lamps" "_tombstone" false "frame" #afield/rect [520.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "brain_sizes_code_.kt" "lines" 13 "characters" 294 "sha256" "24ea0aeed715a77e3bfd8520848e4183d616a7384604d93c52db95e879958ffb"}]}
```

```kotlin [field-property box="brain_sizes" name="code" path="brain_sizes_code_.kt"]
val task = VehicleBrainTask.track()
for (h in [0, 16]) {
    val c = compareVehicleSearch(
        task,
        hidden = h,
        budget = 300,
        seeds = [1L, 2L, 3L, 4L],
        mu = 12,
        lambda = 24,
        immigrantRate = 0.25,
    )
    println(c.table() + c.perSeed())
}
```

## 2. 10 — evolution vs random sampling, orbit task

<!-- field-box id="brain_search" index="1" -->

```edn [field-box id="brain_search" role="properties"]
{"id" "brain_search" "index" 1 "name" "10 — evolution vs random sampling, orbit task" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "10 — evolution vs random sampling, orbit task" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "brain_search_code_.kt" "lines" 25 "characters" 914 "sha256" "584a564f34a6f40158e420fe59c5836f9618ce5fb381d1ebe1e9036cd6b5a126"}]}
```

```kotlin [field-property box="brain_search" name="code" path="brain_search_code_.kt"]
val task = VehicleBrainTask.orbit(radius = 20.0)
val seeds = [1L, 2L, 3L, 4L, 5L, 6L]
val narrow = compareVehicleSearch(task, hidden = 4, budget = 300, seeds = seeds)
val wide =
    compareVehicleSearch(task, hidden = 4, budget = 300, seeds = seeds, mu = 12, lambda = 24, immigrantRate = 0.25)
println(narrow.table() + narrow.perSeed())
println(wide.table())

fun trails(name: String, genes: DoubleArray): Pair<String, Img> {
    val world = task.world(genes, hidden = 4, seed = 7L, vehicles = 12)
    world.stepTimes(1000)
    return name to world.trails(600)
}
showImg(
    montage(
        [
            "mu 6, lambda 12" to narrow.plot(),
            "mu 12 + immigrants" to wide.plot(),
            trails("best random", wide.champion(VehicleSearchDriver.RANDOM)),
            trails("best evolved", wide.champion(VehicleSearchDriver.EVOLVE)),
        ],
        columns = 2,
        cellSide = 300,
    ),
)
```

