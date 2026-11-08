# Field Document: particle_lenia

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 7 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 5 — Particle Lenia: a map of the growth curve

<!-- field-box id="particle_lenia_growth_map" index="0" -->

### Narrative

From `code`

The rotator's kernel (muK 4, sigmaK 0.6, wK 0.022) and repulsion (cRep 0.3) at 100 particles, seed 1,
with the growth curve's centre muG across and its width sigmaG up: 3000 steps to settle, 4000 measured.

```edn [field-box id="particle_lenia_growth_map" role="properties"]
{"id" "particle_lenia_growth_map" "index" 0 "name" "5 — Particle Lenia: a map of the growth curve" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 — Particle Lenia: a map of the growth curve" "_tombstone" false "frame" #afield/rect [560.0 260.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "particle_lenia_growth_map_code_.kt" "lines" 19 "characters" 873 "sha256" "b9eefd629d84ccbad3962b7d191eb3356b51da4d5cf07b454349191e61271b6c"}]}
```

```kotlin [field-property box="particle_lenia_growth_map" name="code" path="particle_lenia_growth_map_code_.kt"]
// The rotator's kernel (muK 4, sigmaK 0.6, wK 0.022) and repulsion (cRep 0.3) at 100 particles, seed 1,
// with the growth curve's centre muG across and its width sigmaG up: 3000 steps to settle, 4000 measured.
val muGs = [0.3, 0.4, 0.5, 0.6, 0.7, 0.8]
val sigmaGs = [0.06, 0.1, 0.15, 0.2]
val cells =
    leniaMotionMap(muGs, sigmaGs, settle = 3000, window = 4000) { muG, sigmaG ->
        ParticleLenia.random(
            100,
            side = 12.0 * Math.sqrt(0.5),
            seed = 1L,
            params = ParticleLeniaParams(sigmaK = 0.6, cRep = 0.3, muG = muG, sigmaG = sigmaG),
        )
    }
for (c in cells) println("muG %.2f  sigmaG %.2f  %s".format(c.x, c.y, c.window))
println(cells.groupingBy { it.motion }.eachCount())

showImg(
    montage(cells.map { "%.2f, %.2f".format(it.x, it.y) to it.tile(size = 160) }, columns = muGs.size, cellSide = 160),
)
```

## 2. 1 — Particle Lenia: 3000 steps, the energy field and a control

<!-- field-box id="particle_lenia_still" index="1" -->

```edn [field-box id="particle_lenia_still" role="properties"]
{"id" "particle_lenia_still" "index" 1 "name" "1 — Particle Lenia: 3000 steps, the energy field and a control" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 — Particle Lenia: 3000 steps, the energy field and a control" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "particle_lenia_still_code_.kt" "lines" 24 "characters" 889 "sha256" "9376101fa1b53636ad08adafb1a0be39625bb6c4e72fa15df6b07930041f996b"}]}
```

```kotlin [field-property box="particle_lenia_still" name="code" path="particle_lenia_still_code_.kt"]
val pl = ParticleLenia.random(200, side = 12.0, seed = 1L)
for (n in [0, 100, 500, 3000]) {
    pl.stepTimes(n - pl.steps)
    println(pl)
}
showImg(
    montage(
        [
            "particles over -E, step 3000" to pl.render(extent = 24.0, size = 360),
            "U, step 3000" to pl.fieldImg(extent = 24.0, size = 360, which = "U").colormap("magma", 0f, 1f),
        ],
        columns = 2,
        cellSide = 360,
    ),
)
// Control: the same seed with no kernel, so repulsion alone moves the particles.
val control = ParticleLenia.random(200, side = 12.0, seed = 1L, params = ParticleLeniaParams(wK = 0.0)).stepTimes(3000)
println("CONTROL, wK = 0: " + control)
showImg(
    energyPlotImg(pl.energyHistory, 720, 160),
    channel = "energy",
    caption =
        "mean particle energy, steps 0 to 3000: %.3f down to %.3f".format(pl.energyHistory.first(), pl.currentEnergy()),
)
```

## 3. 3 — Particle Lenia: a creature that turns and travels

<!-- field-box id="particle_lenia_rotator" index="2" -->

### Narrative

From `code`

The rotator rule: a narrower kernel ring and weaker repulsion than the article's defaults.

```edn [field-box id="particle_lenia_rotator" role="properties"]
{"id" "particle_lenia_rotator" "index" 2 "name" "3 — Particle Lenia: a creature that turns and travels" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 — Particle Lenia: a creature that turns and travels" "_tombstone" false "frame" #afield/rect [1060.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "particle_lenia_rotator_code_.kt" "lines" 20 "characters" 729 "sha256" "8ed99118d6326ceb5b85bd503dc31e6d2618da13d9d6748930c312ac8a15911a"}]}
```

```kotlin [field-property box="particle_lenia_rotator" name="code" path="particle_lenia_rotator_code_.kt"]
// The rotator rule: a narrower kernel ring and weaker repulsion than the article's defaults.
val rule = ParticleLeniaParams(sigmaK = 0.6, cRep = 0.3)
val track = mutableListOf<Pair<Double, Double>>()

animateEvolution(
    ParticleLenia.random(100, side = 12.0 * Math.sqrt(0.5), seed = 1L, params = rule),
    totalSteps = 12000,
    seconds = 7.0,
    cellSide = 400,
    step = { pl ->
        pl.step()
        if (pl.steps % 50 == 0) track += pl.centroid()
        pl
    },
    panels = { pl, n ->
        val img = pl.render(extent = 24.0, size = 300, cx = 0.0, cy = 0.0)
        // the centroid's path so far, as small dots
        ["step %d".format(n) to splatDots(img, track, 24.0, 0.0, 0.0, dotRadius = 1.0)]
    },
)
```

## 4. 4 — Particle Lenia: the demo's zoo, measured and classified

<!-- field-box id="particle_lenia_zoo" index="3" -->

### Narrative

From `code`

Nine creatures from the demo's zoo, each from the demo's starting positions under its own rule:
2000 steps to settle, then a measured window of 4000.

```edn [field-box id="particle_lenia_zoo" role="properties"]
{"id" "particle_lenia_zoo" "index" 3 "name" "4 — Particle Lenia: the demo's zoo, measured and classified" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 — Particle Lenia: the demo's zoo, measured and classified" "_tombstone" false "frame" #afield/rect [60.0 260.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "particle_lenia_zoo_code_.kt" "lines" 20 "characters" 1053 "sha256" "26d9eae0f1bc2554166d1c7ff9bce2ceec20093f1575e99123696c37f8109372"}]}
```

```kotlin [field-property box="particle_lenia_zoo" name="code" path="particle_lenia_zoo_code_.kt"]
// Nine creatures from the demo's zoo, each from the demo's starting positions under its own rule:
// 2000 steps to settle, then a measured window of 4000.
val tiles = mutableListOf<Pair<String, Img>>()
for (c in ParticleLeniaZoo.creatures) {
    val pl = c.lenia().stepTimes(2000)
    val w = pl.measure(4000)
    println("%-14s %3d  %s".format(c.name, c.count, w))
    tiles +=
        "%s: %s".format(c.name, w.motion.name.lowercase()) to
            LeniaMapCell(0.0, 0.0, pl, w).tile(extent = 16.0, size = 240)
}

// The gliders again under the demo's integrator, which carries half of each step into the next.
for (name in ["Iguanmousauru", "Agilienster", "Tarbosaurolop", "Coelopasaurop", "Sauropeltad"]) {
    val euler = ParticleLeniaZoo[name].lenia().stepTimes(2000).measure(4000)
    val demo = ParticleLeniaZoo[name].lenia().also { it.inertia = 0.5 }.stepTimes(2000).measure(4000)
    println("%-14s speed %.4f (Euler)  %.4f (demo's integrator)".format(name, euler.speed, demo.speed))
}

showImg(montage(tiles, columns = 3, cellSide = 240))
```

## 5. 7 — Particle Lenia: the evolved glider

<!-- field-box id="particle_lenia_glider" index="4" -->

### Narrative

From `code`

Evolution's best rule, in full as the search box prints it, from two of the held-out seeds. Each panel is
framed on its own creature, with the path its centre has travelled.

```edn [field-box id="particle_lenia_glider" role="properties"]
{"id" "particle_lenia_glider" "index" 4 "name" "7 — Particle Lenia: the evolved glider" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "7 — Particle Lenia: the evolved glider" "_tombstone" false "frame" #afield/rect [1560.0 260.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "particle_lenia_glider_code_.kt" "lines" 41 "characters" 1406 "sha256" "7c90c43bc5caa39b259aa08d2ba57acda5c7ca1435104ce6cc48aed0518ae9e0"}]}
```

```kotlin [field-property box="particle_lenia_glider" name="code" path="particle_lenia_glider_code_.kt"]
// Evolution's best rule, in full as the search box prints it, from two of the held-out seeds. Each panel is
// framed on its own creature, with the path its centre has travelled.
val rule = ParticleLeniaParams(
    muK = 4.366173229773033,
    sigmaK = 0.6772483357354411,
    wK = 0.030367731514477567,
    muG = 0.17729432366437387,
    sigmaG = 0.1030105021620745,
    cRep = 1.1856752478847217,
)
val tracks = [mutableListOf<Pair<Double, Double>>(), mutableListOf<Pair<Double, Double>>()]

animateEvolution(
    [3L, 4L].map { ParticleLenia.random(40, side = 12.0 * Math.sqrt(0.2), seed = it, params = rule) },
    totalSteps = 8000,
    seconds = 7.0,
    columns = 2,
    cellSide = 360,
    step = { pls ->
        pls.forEachIndexed { i, pl ->
            pl.step()
            if (pl.steps % 40 == 0) tracks[i] += pl.centroid()
        }
        pls
    },
    panels = { pls, n ->
        pls.mapIndexed { i, pl ->
            val (cx, cy) = pl.centroid()
            val (x0, y0) = tracks[i].firstOrNull() ?: (cx to cy)
            "seed %d, step %d, moved %.0f".format(i + 3, n, Math.hypot(cx - x0, cy - y0)) to
                splatDots(
                    pl.render(extent = 30.0, size = 360, dotRadius = 2.0),
                    tracks[i],
                    30.0,
                    cx,
                    cy,
                    dotRadius = 1.0,
                )
        }
    },
)
```

## 6. 2 — Particle Lenia, animated

<!-- field-box id="particle_lenia_movie" index="5" -->

### Narrative

From `code`

200 particles from a random square, 3000 steps, drawn over the energy field.

```edn [field-box id="particle_lenia_movie" role="properties"]
{"id" "particle_lenia_movie" "index" 5 "name" "2 — Particle Lenia, animated" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 — Particle Lenia, animated" "_tombstone" false "frame" #afield/rect [560.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "particle_lenia_movie_code_.kt" "lines" 14 "characters" 437 "sha256" "dc67ee1402da637d70ed016841dcac75a89ed470229b0500af31846350f72dbf"}]}
```

```kotlin [field-property box="particle_lenia_movie" name="code" path="particle_lenia_movie_code_.kt"]
// 200 particles from a random square, 3000 steps, drawn over the energy field.
animateEvolution(
    ParticleLenia.random(200, side = 12.0, seed = 1L),
    totalSteps = 3000,
    seconds = 7.0,
    cellSide = 400,
    step = { it.step() },
    panels = { pl, n ->
        [
            "step %d   mean E %.3f".format(n, pl.currentEnergy()) to
                pl.render(extent = 24.0, size = 300, cx = 0.0, cy = 0.0),
        ]
    },
)
```

## 7. 6 — Particle Lenia: searching for a glider at 40 particles

<!-- field-box id="particle_lenia_glider_search" index="6" -->

```edn [field-box id="particle_lenia_glider_search" role="properties"]
{"id" "particle_lenia_glider_search" "index" 6 "name" "6 — Particle Lenia: searching for a glider at 40 particles" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "6 — Particle Lenia: searching for a glider at 40 particles" "_tombstone" false "frame" #afield/rect [1060.0 260.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "particle_lenia_glider_search_code_.kt" "lines" 81 "characters" 3160 "sha256" "375f6d570bc3e78a111e97c7d43612bf92b16821d3e63d01af47a74e996bc09d"}]}
```

```kotlin [field-property box="particle_lenia_glider_search" name="code" path="particle_lenia_glider_search_code_.kt"]
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
A rule whose 40 particles pull themselves into a glider from a random square. The genome is five numbers
(muK, sigmaK, muG, sigmaG, cRep) with wK normalised. A rule scores speed times straightness over two windows
of 1500 steps after 3000 to settle, the worse of seeds 1 and 2, and zero if its creature splits or its spread
changes by more than 2%. Random sampling at the same budget and seed is the control.
*/
val genome = ParticleLeniaRuleGenome()
val objective = particleLeniaGlideObjective(genome, count = 40, seeds = [1L, 2L])

go {
    // three or four minutes of simulation, so it runs off the main loop
    val (runs, checks) = withContext(Dispatchers.Default) {
        val runs = [
            PopulationSearch.random(genome, objective, 300, seed = 5L, label = "random sampling"),
            PopulationSearch.evolve(
                genome,
                objective,
                300,
                seed = 5L,
                mu = 6,
                lambda = 12,
                crossoverRate = 0.3,
                immigrantRate = 0.2,
                label = "evolution",
            ),
        ]
        // each arm's best rule from five seeds the search never saw: two windows of 4000 after 2000 to settle
        val checks = runs.map { run ->
            (3L..7L)
                .toList()
                .parallelStream()
                .map { seed ->
                    val pl = ParticleLenia.random(
                        40,
                        side = 12.0 * Math.sqrt(0.2),
                        seed = seed,
                        params = genome.params(run.best!!.genome),
                    )
                    pl.stepTimes(2000)
                    seed to [pl.measure(4000), pl.measure(4000)]
                }
                .toList()
        }
        runs to checks
    }
    val table = StringBuilder(
        "| | best fitness | muK | sigmaK | muG | sigmaG | cRep |\n| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n",
    )
    for (run in runs) {
        val p = genome.params(run.best!!.genome)
        table.append(
            "| %s | %.4f | %.3f | %.3f | %.3f | %.3f | %.3f |\n"
                .format(run.label, run.bestFitness, p.muK, p.sigmaK, p.muG, p.sigmaG, p.cRep),
        )
    }
    table.append("\n| best of | seed | steps 2000–6000 | steps 6000–10000 | heading drift | spread |\n")
    table.append("| --- | ---: | --- | --- | ---: | ---: |\n")
    for ((run, rows) in runs.zip(checks)) for ((seed, w) in rows) {
        table.append(
            "| %s | %d | %s %.4f | %s %.4f | %.3f | %.2f |\n"
                .format(
                    run.label,
                    seed,
                    w[0].motion.name.lowercase(),
                    w[0].speed,
                    w[1].motion.name.lowercase(),
                    w[1].speed,
                    w[1].headingDrift,
                    w[1].spreadEnd,
                ),
        )
    }
    markdown(table.toString())
    for (run in runs) {
        println(run.summary())
        println("  " + genome.params(run.best!!.genome))
    }
}
```

