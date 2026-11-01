# Field Document: braitenberg_where_next

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 5 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 8 — moving lights (launch it)

<!-- field-box id="moving_lights" index="0" -->

```edn [field-box id="moving_lights" role="properties"]
{"id" "moving_lights" "index" 0 "name" "8 — moving lights (launch it)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "8 — moving lights (launch it)" "_tombstone" false "frame" #afield/rect [1440.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "moving_lights_code_.kt" "lines" 31 "characters" 1145 "sha256" "e3f441cf62de145c8bd6acbd2653ce08ce22a2a7f39e59fd71dd25466798d6a2"}]}
```

```kotlin [field-property box="moving_lights" name="code" path="moving_lights_code_.kt"]
val still = [Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0)]
val moving = still.mapIndexed { k, l -> l.copy(vx = 0.1 * cos(k * 1.7), vy = 0.1 * sin(k * 1.7)) }
val p = VehicleParams(neuron = NeuronParams(bias = -1.0))

val trained = VehicleWorld.random(80, still, seed = 5L)
trained.stepTimes(p.copy(plasticity = PlasticityParams(learningRate = 3.0)), 15000)
val learned = trained.withLights(moving, seed = 11L)
val lovers = VehicleWorld.random(80, still, wiring = VehicleWorld.LOVER, seed = 5L).withLights(moving, seed = 11L)
val canvasA = VehicleCanvas(learned, scale = 1.2, fade = 0.98f)
val canvasB = VehicleCanvas(lovers, scale = 1.2, fade = 0.98f)

animateEvolution(
    Unit,
    totalSteps = 3000,
    seconds = 12.0,
    framesTarget = 240,
    columns = 2,
    cellSide = 300,
    step = {
        learned.step(p)
        lovers.step(p)
        canvasA.record()
        canvasB.record()
    },
    panels = { _, n ->
        [
            "learned " + n + ": " + learned.meanLight().f2 to canvasA.image(),
            "lovers " + n + ": " + lovers.meanLight().f2 to canvasB.image(),
        ]
    },
)
```

## 2. 5 — BUMP vehicles circle at a set distance

<!-- field-box id="orbits" index="1" -->

```edn [field-box id="orbits" role="properties"]
{"id" "orbits" "index" 1 "name" "5 — BUMP vehicles circle at a set distance" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 — BUMP vehicles circle at a set distance" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "orbits_code_.kt" "lines" 22 "characters" 1008 "sha256" "2be19d959f0fb5a752df1500faa725dc91b21ae7f4bef16a2691c3c04195a301"}]}
```

```kotlin [field-property box="orbits" name="code" path="orbits_code_.kt"]
val lights = [Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0)]
val panels = mutableListOf<Pair<String, Img>>()
for (d in [8.0, 12.0, 16.0, 20.0]) {
    val p = VehicleParams(
        neuron = NeuronParams(activation = Activation.BUMP, gain = 4.0, tauMembrane = 1.0, bias = orbitBias(d)),
    )
    val world = VehicleWorld.random(60, lights, wiring = VehicleWorld.AGGRESSOR, seed = 3L)
    val canvas = VehicleCanvas(world, scale = 1.2, fade = 0.99f)
    world.stepTimes(p, 6000)
    val radii = OrbitRadii()
    repeat(2000) {
        world.step(p)
        radii.observe(world)
        canvas.record()
    }
    println(
        "tuned ${d.f2}: median ${radii.median().f2}, middle half ${radii.quantile(0.25).f2} to ${radii.quantile(0.75).f2}",
    )
    panels += ("tuned " + d.f2) to canvas.image()
    panels += ("median " + radii.median().f2) to radii.toImg(maxDistance = 60.0, mark = d, width = 300, height = 300)
}
showImg(montage(panels, columns = 4, cellSide = 240))
```

## 3. 7 — learning and homeostasis together

<!-- field-box id="combined" index="2" -->

```edn [field-box id="combined" role="properties"]
{"id" "combined" "index" 2 "name" "7 — learning and homeostasis together" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "7 — learning and homeostasis together" "_tombstone" false "frame" #afield/rect [980.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "combined_code_.kt" "lines" 35 "characters" 1144 "sha256" "725459fd71de0b18c64f4ceaa1ca8272c0b8dd00f4b71202359da24aef1c1413"}]}
```

```kotlin [field-property box="combined" name="code" path="combined_code_.kt"]
val lights = [Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0)]
val base = VehicleParams(neuron = NeuronParams(bias = -1.0))

fun arm(name: String, adaptation: Double, learning: Double): Pair<String, Img> {
    val p = base.copy(
        neuron = base.neuron.copy(adaptationRate = adaptation),
        plasticity = PlasticityParams(learningRate = learning),
    )
    val world = VehicleWorld.random(80, lights, seed = 5L)
    val canvas = VehicleCanvas(world, scale = 1.2, fade = 0.998f)
    world.stepTimes(p, 15000)
    val visits = LightVisits(world)
    var light = 0.0
    repeat(5000) {
        world.step(p)
        visits.observe(world)
        canvas.record()
        light += world.meanLight() / 5000
    }
    println(name + ": light " + light.f3 + ", lights visited " + visits.mean().f2)
    return (name + ": " + light.f2) to canvas.image()
}

showImg(
    montage(
        [
            arm("neither", 0.0, 0.0),
            arm("homeostasis", 0.002, 0.0),
            arm("learning", 0.0, 3.0),
            arm("both", 0.002, 3.0),
        ],
        columns = 4,
        cellSide = 240,
    ),
)
```

## 4. 6 — a price on speed

<!-- field-box id="speed_cost" index="3" -->

```edn [field-box id="speed_cost" role="properties"]
{"id" "speed_cost" "index" 3 "name" "6 — a price on speed" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "6 — a price on speed" "_tombstone" false "frame" #afield/rect [520.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "speed_cost_code_.kt" "lines" 24 "characters" 935 "sha256" "d644122081fdf3ed4dceee58a49d56e12336f65d70649d7a8e2227616acc649d"}]}
```

```kotlin [field-property box="speed_cost" name="code" path="speed_cost_code_.kt"]
val lights = [Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0)]
val panels = mutableListOf<Pair<String, Img>>()
for (cost in [0.0, 0.2, 0.5, 1.0]) {
    val p = VehicleParams(
        neuron = NeuronParams(bias = -1.0),
        plasticity = PlasticityParams(learningRate = 3.0, speedCost = cost),
    )
    val world = VehicleWorld.random(80, lights, seed = 5L)
    val canvas = VehicleCanvas(world, scale = 1.2)
    world.stepTimes(p, 15000)
    var light = 0.0
    var speed = 0.0
    repeat(2000) {
        world.step(p)
        canvas.record()
        light += world.meanLight() / 2000
        speed += world.meanSpeed() / 2000
    }
    println(
        "speedCost ${cost.f2}: light ${light.f3}, speed ${speed.f3}, summed weight ${(world.w.sum() / world.count).f2}",
    )
    panels += ("cost " + cost.f2 + ": " + speed.f2) to canvas.image()
}
showImg(montage(panels, columns = 4, cellSide = 240))
```

## 5. 9 — searching for a wiring

<!-- field-box id="evolved_wiring" index="4" -->

```edn [field-box id="evolved_wiring" role="properties"]
{"id" "evolved_wiring" "index" 4 "name" "9 — searching for a wiring" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "9 — searching for a wiring" "_tombstone" false "frame" #afield/rect [1900.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "evolved_wiring_code_.kt" "lines" 23 "characters" 1105 "sha256" "127ba9f3c3e1422de61fa4440009434441e784f61ffadd46c2abb7828a5bf9bc"}]}
```

```kotlin [field-property box="evolved_wiring" name="code" path="evolved_wiring_code_.kt"]
val lights = [Light(60.0, 60.0), Light(180.0, 70.0), Light(120.0, 180.0), Light(200.0, 200.0)]
val ops = VehicleWiringGenome()
val objective = vehicleForageObjective(lights)

val control = PopulationSearch.random(ops, objective, 300, seed = 13L, label = "random")
val evolved =
    PopulationSearch.evolve(ops, objective, 300, seed = 13L, mu = 6, lambda = 12, crossoverRate = 0.3, label = "evolve")
println(control.summary())
println(evolved.summary())
println("lover " + objective.evaluate(VehicleWorld.LOVER).fitness.f3)
val best = evolved.best!!.genome
println("evolved wiring " + best.joinToString { it.f2 })

fun trails(name: String, wiring: DoubleArray): Pair<String, Img> {
    val world = VehicleWorld.random(30, lights, wiring = wiring, seed = 1L)
    val canvas = VehicleCanvas(world, scale = 1.2)
    repeat(3000) {
        world.step(VehicleParams(neuron = NeuronParams(bias = -1.0)))
        canvas.record()
    }
    return (name + ": " + world.meanLight().f2) to canvas.image()
}
showImg(montage([trails("lover", VehicleWorld.LOVER), trails("evolved", best)], columns = 2, cellSide = 300))
```

