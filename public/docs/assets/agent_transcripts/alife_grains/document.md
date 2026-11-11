# Field Document: alife_grains

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 2 — Evolving toward a statistical profile

<!-- field-box id="grains_evolve" index="0" -->

### Narrative

From `code`

Fitness is a weighted mean of trapezoids over the set's statistics. The sliders move
three of them and the time scale; releasing one re-runs the search from the same seed.
The duration target stretches with the time scale, so the same grammars win, slower or faster.

```edn [field-box id="grains_evolve" role="properties"]
{"id" "grains_evolve" "index" 0 "name" "2 — Evolving toward a statistical profile" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 — Evolving toward a statistical profile" "_tombstone" false "frame" #afield/rect [60.0 200.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "grains_evolve_code_.kt" "lines" 62 "characters" 2294 "sha256" "ed7d6ee34434e820130792251292738438cb154d669dcd6f37f4876354052e5a"}]}
```

```kotlin [field-property box="grains_evolve" name="code" path="grains_evolve_code_.kt"]
/*
Fitness is a weighted mean of trapezoids over the set's statistics. The sliders move
three of them and the time scale; releasing one re-runs the search from the same seed.
The duration target stretches with the time scale, so the same grammars win, slower or faster.
*/
var medianPitch = 62.0
var countCentre = 80.0
var polyWeight = 1.0
var timeScale = 1.0

fun terms() = [
    GrainFitnessTerm(
        GrainStat.COUNT,
        Trapezoid(countCentre * 0.15, countCentre * 0.5, countCentre * 1.5, countCentre * 5),
    ),
    GrainFitnessTerm(
        GrainStat.MEDIAN_PITCH,
        Trapezoid(medianPitch - 14, medianPitch - 4, medianPitch + 4, medianPitch + 16),
    ),
    GrainFitnessTerm(GrainStat.PITCH_SPREAD, Trapezoid(1.0, 4.0, 8.0, 16.0)),
    GrainFitnessTerm(
        GrainStat.DURATION,
        Trapezoid(4.0 * timeScale, 12.0 * timeScale, 30.0 * timeScale, 60.0 * timeScale),
    ),
    GrainFitnessTerm(GrainStat.POLYPHONY, Trapezoid(1.0, 1.8, 3.5, 8.0), weight = polyWeight),
    GrainFitnessTerm(GrainStat.MODE_SHARE, Trapezoid(0.0, 0.0, 0.3, 0.6)),
]

fun search() {
    val run = PopulationSearch.evolve(
        grainLSystemGenome(),
        grainSetObjective(terms(), turtle = GrainTurtle(timeScale = timeScale)),
        budget = 300,
        seed = 7L,
        crossoverRate = 0.3,
        immigrantRate = 0.1,
        label = "grains",
    )
    val best = decodeGrains(run.best!!.genome, 3, GrainTurtle(timeScale = timeScale))
    display("main", run.summary() + "<br>" + grainRule(run.best!!.genome) + "<br>" + GrainSetStats(best))
    showImg(fitnessTraceImg(run.trace), channel = "trace", caption = "best-so-far fitness against evaluations, 0 to 1")
    showImg(grainScoreImg(best, 640, 220), channel = "score", caption = "the best set")
    display("audio", renderGrains(best).wav())
}

slider("median pitch", 40.0, 84.0, 1.0, medianPitch, channel = "pitch target") {
    medianPitch = it
    search()
}
slider("grain count", 10.0, 300.0, 5.0, countCentre, channel = "count target") {
    countCentre = it
    search()
}
slider("polyphony weight", 0.0, 4.0, 0.1, polyWeight, channel = "polyphony weight") {
    polyWeight = it
    search()
}
slider("time scale", 0.125, 2.0, 0.025, timeScale, channel = "time scale") {
    timeScale = it
    search()
}
search()
```

## 2. 3 — Interactive evolution: you choose the parents

<!-- field-box id="grains_breed" index="1" -->

### Narrative

From `code`

Listen to the six children, type the indices of the ones you like (e.g. "1 4"),
press return: they survive and the rest are their crossovers, each mutated once.

```edn [field-box id="grains_breed" role="properties"]
{"id" "grains_breed" "index" 1 "name" "3 — Interactive evolution: you choose the parents" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 — Interactive evolution: you choose the parents" "_tombstone" false "frame" #afield/rect [60.0 340.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "grains_breed_code_.kt" "lines" 23 "characters" 876 "sha256" "466082eb72b03366755e09b067ca4b2f568ada40a7a5764936438d9c03326c00"}]}
```

```kotlin [field-property box="grains_breed" name="code" path="grains_breed_code_.kt"]
// Listen to the six children, type the indices of the ones you like (e.g. "1 4"),
// press return: they survive and the rest are their crossovers, each mutated once.
val breeder = GrainBreeder(seed = 1L)

fun show() {
    displayClearAll()
    val scores = breeder.scores()
    val listing =
        breeder.population.withIndex().joinToString("<br>") { (i, g) ->
            "%d: %s &nbsp; trapezoid fitness %.2f".format(i, grainRule(g), scores[i])
        }
    display("main", "generation ${breeder.generation}<br>" + listing)
    for ((i, g) in breeder.population.withIndex()) display("child $i", renderGrains(decodeGrains(g, 3)).wav())
    stringInput("parents (e.g. 1 4)") { s ->
        val picks = s.split(" ", ",").mapNotNull { it.trim().toIntOrNull() }
        if (picks.isNotEmpty()) {
            breeder.breed(picks)
            show()
        }
    }
}

show()
```

## 3. 1 — An L-system read as grains

<!-- field-box id="grains_decode" index="2" -->

### Narrative

From `code`

G plays a grain and moves on in time, f rests, + and - step the pitch,
[ and ] save and restore time and pitch, so each bracket is a new voice.

```edn [field-box id="grains_decode" role="properties"]
{"id" "grains_decode" "index" 2 "name" "1 — An L-system read as grains" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 — An L-system read as grains" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "grains_decode_code_.kt" "lines" 8 "characters" 492 "sha256" "8187a82358e1e82627976ee77a66ee204be9e5878d0c3a42abb9bdf81cde3b5f"}]}
```

```kotlin [field-property box="grains_decode" name="code" path="grains_decode_code_.kt"]
// G plays a grain and moves on in time, f rests, + and - step the pitch,
// [ and ] save and restore time and pitch, so each bracket is a new voice.
val system = lSystem("G", 'G' to "G[+G-G]fG")
for ((i, s) in system.derivations(2).withIndex()) println("gen $i: $s")
val grains = decodeGrains(system, 3)
println(GrainSetStats(grains))
showImg(grainScoreImg(grains, 640, 220), caption = "time across, pitch up; gold trunk, blue and red branches")
display("audio", renderGrains(grains).wav())
```

