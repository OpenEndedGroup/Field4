# Field Document: flow_lenia

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 6 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 2 — Rings meet a cell (SAMPLED mixing)

<!-- field-box id="flow_lenia_collision" index="0" -->

```edn [field-box id="flow_lenia_collision" role="properties"]
{"id" "flow_lenia_collision" "index" 0 "name" "2 — Rings meet a cell (SAMPLED mixing)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 — Rings meet a cell (SAMPLED mixing)" "_tombstone" false "frame" #afield/rect [60.0 240.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flow_lenia_collision_code_.kt" "lines" 23 "characters" 559 "sha256" "b5f23d8f5c5b1595399a94db3acefdf329fb8d73ce1420b5c8a4cdc1c132a32a"}]}
```

```kotlin [field-property box="flow_lenia_collision" name="code" path="flow_lenia_collision_code_.kt"]
val world = flowLeniaWorld(
    288,
    192,
    [FlowLeniaPresets.rings(), FlowLeniaPresets.cell()],
    positions = [38.0 to 32.0, 58.0 to 32.0],
    mixing = FlowLeniaMixing.SAMPLED,
    scale = 3,
)

animateEvolution(
    world,
    totalSteps = 1200,
    seconds = 9.0,
    columns = 2,
    cellSide = 480,
    step = { it.step() },
    panels = { w, n ->
        [
            ("step " + n) to w.speciesImg().resize(480, 320),
            ("rings %.0f, cell %.0f".format(w.speciesMass(0), w.speciesMass(1))) to w.massPlot(480, 320),
        ]
    },
)
```

## 2. 3 — The parameter field blending (MEAN mixing)

<!-- field-box id="flow_lenia_drift" index="1" -->

```edn [field-box id="flow_lenia_drift" role="properties"]
{"id" "flow_lenia_drift" "index" 1 "name" "3 — The parameter field blending (MEAN mixing)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 — The parameter field blending (MEAN mixing)" "_tombstone" false "frame" #afield/rect [560.0 240.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flow_lenia_drift_code_.kt" "lines" 23 "characters" 570 "sha256" "ca96a72686c97cfe4f5cec3a4f763146288e37d383e1879d7773d149d950e4ff"}]}
```

```kotlin [field-property box="flow_lenia_drift" name="code" path="flow_lenia_drift_code_.kt"]
val world = flowLeniaWorld(
    288,
    192,
    [FlowLeniaPresets.rings(), FlowLeniaPresets.cell()],
    positions = [38.0 to 32.0, 58.0 to 32.0],
    scale = 3,
)

animateEvolution(
    world,
    totalSteps = 1200,
    seconds = 9.0,
    columns = 2,
    cellSide = 480,
    step = { it.step() },
    panels = { w, n ->
        [
            ("step " + n) to w.speciesImg().resize(480, 320),
            ("hybrid %.0f of %.0f".format(w.mixedMass(), w.totalMass())) to
                w.weightImg(0).colormap("viridis", 0f, 0.4f).resize(480, 320),
        ]
    },
)
```

## 3. 1 — Flow Lenia: three species, total mass flat

<!-- field-box id="flow_lenia_species" index="2" -->

```edn [field-box id="flow_lenia_species" role="properties"]
{"id" "flow_lenia_species" "index" 2 "name" "1 — Flow Lenia: three species, total mass flat" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 — Flow Lenia: three species, total mass flat" "_tombstone" false "frame" #afield/rect [560.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flow_lenia_species_code_.kt" "lines" 21 "characters" 471 "sha256" "4d6a419e346efd120d90d4d782cb91730a380fa1622ff99fb750caf89912be12"}]}
```

```kotlin [field-property box="flow_lenia_species" name="code" path="flow_lenia_species_code_.kt"]
val world = flowLeniaWorld(
    288,
    288,
    [FlowLeniaPresets.wanderers(), FlowLeniaPresets.rings(), FlowLeniaPresets.cell()],
    scale = 3,
)

animateEvolution(
    world,
    totalSteps = 1200,
    seconds = 9.0,
    columns = 2,
    cellSide = 480,
    step = { it.step() },
    panels = { w, n ->
        [
            ("step " + n) to w.speciesImg().resize(480, 480),
            ("total mass " + w.totalMass().f3) to w.massPlot(480, 480),
        ]
    },
)
```

## 4. 0 — Flow Lenia: mass conserved over 200 steps

<!-- field-box id="flow_lenia_conservation" index="3" -->

```edn [field-box id="flow_lenia_conservation" role="properties"]
{"id" "flow_lenia_conservation" "index" 3 "name" "0 — Flow Lenia: mass conserved over 200 steps" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "0 — Flow Lenia: mass conserved over 200 steps" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flow_lenia_conservation_code_.kt" "lines" 14 "characters" 390 "sha256" "748be56c87e8b46b86bb764a6543cd9de4b568c83746f1de8dad53a365af4be2"}]}
```

```kotlin [field-property box="flow_lenia_conservation" name="code" path="flow_lenia_conservation_code_.kt"]
val world = flowLeniaWorld(
    64,
    64,
    [FlowLeniaPresets.wanderers(), FlowLeniaPresets.rings(), FlowLeniaPresets.cell()],
    seedRadius = 10.0,
)
val m0 = world.totalMass()
var worst = 0.0
repeat(200) {
    world.step()
    worst = maxOf(worst, kotlin.math.abs(world.totalMass() - m0) / m0)
}
println(world)
println("worst relative change in total mass over 200 steps: " + worst)
```

## 5. 5 — Mutation inside the world, against a neutral control

<!-- field-box id="flow_lenia_mutation" index="4" -->

```edn [field-box id="flow_lenia_mutation" role="properties"]
{"id" "flow_lenia_mutation" "index" 4 "name" "5 — Mutation inside the world, against a neutral control" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 — Mutation inside the world, against a neutral control" "_tombstone" false "frame" #afield/rect [560.0 420.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flow_lenia_mutation_code_.kt" "lines" 26 "characters" 985 "sha256" "3a64632225491e70afa468654f284b204026045fc4365689b3da42be965be496"}]}
```

```kotlin [field-property box="flow_lenia_mutation" name="code" path="flow_lenia_mutation_code_.kt"]
val species = [FlowLeniaPresets.wanderers(), FlowLeniaPresets.rings(), FlowLeniaPresets.cell()]
val neutral = flowLeniaWorld(192, 192, species, mixing = FlowLeniaMixing.SAMPLED, scale = 2)
neutral.mutation = FlowLeniaMutation(rate = 0.05, radius = 12.0, scale = 0.0)
val mutating = flowLeniaWorld(192, 192, species, mixing = FlowLeniaMixing.SAMPLED, scale = 2)
mutating.mutation = FlowLeniaMutation(rate = 0.05, radius = 12.0, scale = 0.3)

fun masses(w: FlowLeniaWorld) = w.speciesMasses().joinToString(" ") { it.f0 }

animateEvolution(
    neutral to mutating,
    totalSteps = 2400,
    seconds = 9.0,
    columns = 2,
    cellSide = 384,
    step = { (a, b) ->
        a.step()
        b.step()
        a to b
    },
    panels = { (a, b), n ->
        [
            ("neutral " + masses(a)) to vstack(a.lineageImg().resize(384, 384), a.massPlot(384, 128)),
            ("size 0.3 " + masses(b)) to vstack(b.lineageImg().resize(384, 384), b.massPlot(384, 128)),
        ]
    },
)
```

## 6. 4 — A crescent that travels, at three times the resolution

<!-- field-box id="flow_lenia_crescent" index="5" -->

### Narrative

From `code`

Noise seed 2 folds into the crescent; grow it to three times the resolution once it has formed.

```edn [field-box id="flow_lenia_crescent" role="properties"]
{"id" "flow_lenia_crescent" "index" 5 "name" "4 — A crescent that travels, at three times the resolution" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 — A crescent that travels, at three times the resolution" "_tombstone" false "frame" #afield/rect [60.0 420.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "flow_lenia_crescent_code_.kt" "lines" 21 "characters" 822 "sha256" "25f2f51a0d6a46cc04ea32f40b81ee39393bcaa7ce61ae1a865611dc3a2799a0"}]}
```

```kotlin [field-property box="flow_lenia_crescent" name="code" path="flow_lenia_crescent_code_.kt"]
// Noise seed 2 folds into the crescent; grow it to three times the resolution once it has formed.
val world =
    flowLeniaWorld(64, 64, [FlowLeniaPresets.crescent()], seedRadius = 6.0, randomSeed = 2L).steps(200).scaledCopy(3)
val trail = FloatArray(world.width * world.height)

animateEvolution(
    world,
    totalSteps = 3000,
    seconds = 9.0,
    columns = 2,
    cellSide = 480,
    step = { it.step() },
    panels = { w, n ->
        val b = w.bodies().first()
        for (c in trail.indices) trail[c] = maxOf(0.97f * trail[c], w.matter[c].toFloat())
        [
            ("step %d: mass %.1f at (%.0f, %.0f)".format(n, b.mass, b.x, b.y)) to w.speciesImg().resize(480, 480),
            "the path" to Img(w.width, w.height, 1, trail.copyOf()).colormap("magma", 0f, 1.2f).resize(480, 480),
        ]
    },
)
```

