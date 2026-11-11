# Field Document: flow_lenia

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 4 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 2 — Rings meet a cell (SAMPLED mixing)

<!-- field-box id="flow_lenia_collision" index="0" -->

```edn [field-box id="flow_lenia_collision" role="properties"]
{"id" "flow_lenia_collision" "index" 0 "name" "2 — Rings meet a cell (SAMPLED mixing)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 — Rings meet a cell (SAMPLED mixing)" "_tombstone" false "frame" #afield/rect [60.0 240.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flow_lenia_collision_code_.kt" "lines" 22 "characters" 543 "sha256" "afcd0cd9759913f240a0990e4c8319fde04ad530d6cd25ea182fb4f74dabb2e4"}]}
```

```kotlin [field-property box="flow_lenia_collision" name="code" path="flow_lenia_collision_code_.kt"]
val world = flowLeniaWorld(
    96,
    64,
    [FlowLeniaPresets.rings(), FlowLeniaPresets.cell()],
    positions = [38.0 to 32.0, 58.0 to 32.0],
    mixing = FlowLeniaMixing.SAMPLED,
)

animateEvolution(
    world,
    totalSteps = 1200,
    seconds = 9.0,
    columns = 2,
    cellSide = 384,
    step = { it.step() },
    panels = { w, n ->
        [
            ("step " + n) to w.speciesImg().upscaleNearest(4),
            ("rings %.0f, cell %.0f".format(w.speciesMass(0), w.speciesMass(1))) to w.massPlot(384, 256),
        ]
    },
)
```

## 2. 3 — The parameter field blending (MEAN mixing)

<!-- field-box id="flow_lenia_drift" index="1" -->

```edn [field-box id="flow_lenia_drift" role="properties"]
{"id" "flow_lenia_drift" "index" 1 "name" "3 — The parameter field blending (MEAN mixing)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 — The parameter field blending (MEAN mixing)" "_tombstone" false "frame" #afield/rect [560.0 240.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flow_lenia_drift_code_.kt" "lines" 16 "characters" 520 "sha256" "be0d1866f3e44079e47aa6b2762c948376d2454fed56029f1e656509afa907be"}]}
```

```kotlin [field-property box="flow_lenia_drift" name="code" path="flow_lenia_drift_code_.kt"]
val world = flowLeniaWorld(96, 64, [FlowLeniaPresets.rings(), FlowLeniaPresets.cell()], positions = [38.0 to 32.0, 58.0 to 32.0])

animateEvolution(
    world,
    totalSteps = 1200,
    seconds = 9.0,
    columns = 2,
    cellSide = 384,
    step = { it.step() },
    panels = { w, n ->
        [
            ("step " + n) to w.speciesImg().upscaleNearest(4),
            ("hybrid %.0f of %.0f".format(w.mixedMass(), w.totalMass())) to w.weightImg(0).colormap("viridis", 0f, 0.4f).upscaleNearest(4),
        ]
    },
)
```

## 3. 1 — Flow Lenia: three species, total mass flat

<!-- field-box id="flow_lenia_species" index="2" -->

```edn [field-box id="flow_lenia_species" role="properties"]
{"id" "flow_lenia_species" "index" 2 "name" "1 — Flow Lenia: three species, total mass flat" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 — Flow Lenia: three species, total mass flat" "_tombstone" false "frame" #afield/rect [560.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flow_lenia_species_code_.kt" "lines" 16 "characters" 447 "sha256" "3f1d542bed4dc71635584cbccdf7fbb10ae6d0ff4855e60d93c7904fe54ea0a9"}]}
```

```kotlin [field-property box="flow_lenia_species" name="code" path="flow_lenia_species_code_.kt"]
val world = flowLeniaWorld(96, 96, [FlowLeniaPresets.wanderers(), FlowLeniaPresets.rings(), FlowLeniaPresets.cell()])

animateEvolution(
    world,
    totalSteps = 1200,
    seconds = 9.0,
    columns = 2,
    cellSide = 384,
    step = { it.step() },
    panels = { w, n ->
        [
            ("step " + n) to w.speciesImg().upscaleNearest(4),
            ("total mass %.6f".format(w.totalMass())) to w.massPlot(384, 384),
        ]
    },
)
```

## 4. 0 — Flow Lenia: mass conserved over 200 steps

<!-- field-box id="flow_lenia_conservation" index="3" -->

```edn [field-box id="flow_lenia_conservation" role="properties"]
{"id" "flow_lenia_conservation" "index" 3 "name" "0 — Flow Lenia: mass conserved over 200 steps" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "0 — Flow Lenia: mass conserved over 200 steps" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flow_lenia_conservation_code_.kt" "lines" 9 "characters" 371 "sha256" "a74621be876e6827e6abb89547c6976d65c2a89b1d37dfda2000e9f1e2392eed"}]}
```

```kotlin [field-property box="flow_lenia_conservation" name="code" path="flow_lenia_conservation_code_.kt"]
val world = flowLeniaWorld(64, 64, [FlowLeniaPresets.wanderers(), FlowLeniaPresets.rings(), FlowLeniaPresets.cell()], seedRadius = 10.0)
val m0 = world.totalMass()
var worst = 0.0
repeat(200) {
    world.step()
    worst = maxOf(worst, kotlin.math.abs(world.totalMass() - m0) / m0)
}
println(world)
println("worst relative change in total mass over 200 steps: " + worst)
```

