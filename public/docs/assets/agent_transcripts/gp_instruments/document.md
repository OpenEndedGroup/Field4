# Field Document: gp_instruments

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 3 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 2 - A or B? (you choose)

<!-- field-box id="gp_duel" index="0" -->

### Narrative

From `code`

A rosette with four dials: petals, depth, curl and hue. Say which of two you
like better, and the model proposes the next two.

```edn [field-box id="gp_duel" role="properties"]
{"id" "gp_duel" "index" 0 "name" "2 - A or B? (you choose)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - A or B? (you choose)" "_tombstone" false "frame" #afield/rect [800.0 40.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gp_duel_code_.kt" "lines" 42 "characters" 1555 "sha256" "e08432cb1f08051cf60454f9cc9a320201c6535e4a493e0272b8b86c96f318f7"}]}
```

```kotlin [field-property box="gp_duel" name="code" path="gp_duel_code_.kt"]
// A rosette with four dials: petals, depth, curl and hue. Say which of two you
// like better, and the model proposes the next two.
fun rosette(p: Map<String, Double>, dx: Double) =
    FLine().also { f ->
        val petals = p["petals"]!!.roundToInt()
        for (i in 0..600) {
            val t = 2 * PI * i / 600
            val r = 1 + p["depth"]!! * cos(petals * t) + 0.15 * p["depth"]!! * cos(2 * petals * t + 3 * p["curl"]!!)
            val x = dx + 90 * r * cos(t + p["curl"]!! * sin(petals * t) * 0.3)
            val y = -90 * r * sin(t + p["curl"]!! * sin(petals * t) * 0.3)
            if (i == 0) f.moveTo(x, y) else f.lineTo(x, y)
        }
        f.color = okhsl(p["hue"]!!, 0.8, 0.55, 1.0)
        f.thickness = 2.0
    }

val taste = GpPreference("petals" to 3.0..9.0, "depth" to 0.1..0.8, "curl" to 0.0..1.0, "hue" to 0.0..360.0)
var pair = taste.nextPair()

fun show() {
    lines(rosette(pair.first, 0.0), rosette(pair.second, 300.0), w = 600.0, h = 280.0, fitToSize = true)
    val fav = taste.best
    display(
        "status",
        "<div>%d answers. A (left) vs B (right).%s</div>"
            .format(
                taste.comparisons.size,
                if (fav == null) "" else " Favourite so far: " + fav.mapValues { "%.2f".format(it.value) },
            ),
    )
}
fun answer(aWins: Boolean) {
    taste.choose(pair, aWins)
    pair = taste.nextPair()
    show()
}

controlGroup {
    button("A is better") { down -> if (down) answer(true) }
    button("B is better") { down -> if (down) answer(false) }
}
show()
```

## 2. 3 - A or B? (a simulated judge)

<!-- field-box id="gp_judge" index="1" -->

### Narrative

From `code`

The same loop with the human replaced by a judge whose taste we know: it likes dark-blue
five-petalled rosettes, deep and barely curled. Twenty-four answers, and we draw the
model's favourite after every six, then - past a dividing line - the judge's ideal, in its
own colour, so shape and colour can both be compared.

```edn [field-box id="gp_judge" role="properties"]
{"id" "gp_judge" "index" 1 "name" "3 - A or B? (a simulated judge)" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 - A or B? (a simulated judge)" "_tombstone" false "frame" #afield/rect [1540.0 40.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gp_judge_code_.kt" "lines" 82 "characters" 3583 "sha256" "ddb73ee602a087d5cf6b4a417fd7a942a48be151c23191fd43ea6df5436f4e19"}]}
```

```kotlin [field-property box="gp_judge" name="code" path="gp_judge_code_.kt"]
/*
The same loop with the human replaced by a judge whose taste we know: it likes dark-blue
five-petalled rosettes, deep and barely curled. Twenty-four answers, and we draw the
model's favourite after every six, then - past a dividing line - the judge's ideal, in its
own colour, so shape and colour can both be compared.
*/
fun rosette(p: Map<String, Double>, dx: Double) =
    FLine().also { f ->
        val petals = p["petals"]!!.roundToInt()
        for (i in 0..600) {
            val t = 2 * PI * i / 600
            val r = 1 + p["depth"]!! * cos(petals * t) + 0.15 * p["depth"]!! * cos(2 * petals * t + 3 * p["curl"]!!)
            val x = dx + 90 * r * cos(t + p["curl"]!! * sin(petals * t) * 0.3)
            val y = -90 * r * sin(t + p["curl"]!! * sin(petals * t) * 0.3)
            if (i == 0) f.moveTo(x, y) else f.lineTo(x, y)
        }
        f.color = okhsl(p["hue"]!!, 0.8, 0.55, 1.0)
        f.thickness = 2.0
    }
val ideal = mapOf("petals" to 5.0, "depth" to 0.6, "curl" to 0.1, "hue" to 250.0)
fun utility(p: Map<String, Double>) =
    -((p["petals"]!! - 5.0) / 6).pow(2) -
        ((p["depth"]!! - 0.6) / 0.7).pow(2) -
        (p["curl"]!! - 0.1).pow(2) -
        ((p["hue"]!! - 250.0) / 360).pow(2)

val taste = GpPreference("petals" to 3.0..9.0, "depth" to 0.1..0.8, "curl" to 0.0..1.0, "hue" to 0.0..360.0)
val snapshots = mutableListOf<Map<String, Double>>()
repeat(24) { round ->
    val pair = taste.nextPair()
    taste.choose(pair, aWins = utility(pair.first) > utility(pair.second))
    if ((round + 1) % 6 == 0) snapshots += taste.best!!
}
val divider = 380.0 * snapshots.size - 130.0
val separator = FLine().moveTo(divider, -170.0).lineTo(divider, 170.0).also {
    it.color = vec(0.7, 0.7, 0.7, 1.0)
    it.thickness = 1.5
}
lines(
    snapshots.mapIndexed { i, p -> rosette(p, 380.0 * i) } + separator + rosette(ideal, 380.0 * snapshots.size + 120.0),
    w = 1000.0,
    h = 200.0,
    fitToSize = true,
)

// What the model believes about colour: utility relative to its favourite as the hue sweeps,
// with the other dials held at the favourite's. Answers only fix differences, hence relative.
val best = taste.best!!
val hues = (0..72).map { it * 5.0 }
val u = hues.map { taste.utilityRelativeTo(best, best + ("hue" to it)) }
val chart =
    Chart(title = "utility vs hue, relative to the favourite", xLabel = "hue", yLabel = "u - u(favourite)")
        .line("+2 sd", hues, u.map { it.mean + 2 * it.std }, color = vec(1.0, 0.55, 0.2, 0.5))
        .line("-2 sd", hues, u.map { it.mean - 2 * it.std }, color = vec(1.0, 0.55, 0.2, 0.5))
        .line("mean", hues, u.map { it.mean }, color = vec(1.0, 0.55, 0.2, 1.0), thickness = 3.0)
        .vrule(250.0, "judge's ideal hue")
chart.width = 600.0
chart.height = 280.0
showChart(chart, channel = "utility")
snapshots.forEachIndexed { i, p ->
    println(
        "after %2d answers: %s  utility %.3f".format(6 * (i + 1), p.mapValues { "%.2f".format(it.value) }, utility(p)),
    )
}
println(
    "ideal utility 0; a random rosette averages %.3f"
        .format(
            (0 until 200)
                .map {
                    java.util.Random(it.toLong()).let { r ->
                        mapOf(
                            "petals" to 3 + 6 * r.nextDouble(),
                            "depth" to 0.1 + 0.7 * r.nextDouble(),
                            "curl" to r.nextDouble(),
                            "hue" to 360 * r.nextDouble(),
                        )
                    }
                }
                .map { utility(it) }
                .average(),
        ),
)
```

## 3. 1 - Tune a curve to a target

<!-- field-box id="gp_tune" index="2" -->

### Narrative

From `code`

A closed curve with three dials: how far it wobbles, where the wobble starts, and how much
it is squashed sideways. One setting of the dials is the target; pretend we cannot see it.

```edn [field-box id="gp_tune" role="properties"]
{"id" "gp_tune" "index" 2 "name" "1 - Tune a curve to a target" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - Tune a curve to a target" "_tombstone" false "frame" #afield/rect [60.0 40.0 340.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "gp_tune_code_.kt" "lines" 53 "characters" 2095 "sha256" "2727479d983aecf6918c933cfaf20c2bf0548bd854e82ebc8fc5430b89f4e042"}]}
```

```kotlin [field-property box="gp_tune" name="code" path="gp_tune_code_.kt"]
// A closed curve with three dials: how far it wobbles, where the wobble starts, and how much
// it is squashed sideways. One setting of the dials is the target; pretend we cannot see it.
fun curve(p: Map<String, Double>) =
    (0..300).map { i ->
        val t = 2 * PI * i / 300
        val r = 1 + p["wobble"]!! * cos(5 * t + p["phase"]!!)
        (p["squash"]!! * r * cos(t)) to (r * sin(t))
    }
val target = curve(mapOf("wobble" to 0.32, "phase" to 1.1, "squash" to 0.8))
fun misfit(p: Map<String, Double>) =
    curve(p).zip(target).sumOf { (a, b) ->
        (a.first - b.first).pow(2) + (a.second - b.second).pow(2)
    } / target.size

// Each step fits a Gaussian process to every try so far and evaluates where it expects
// the biggest improvement. Thirty tries, minimising.
val opt = GpOptimizer("wobble" to 0.0..0.6, "phase" to 0.0..2 * PI, "squash" to 0.4..1.4, minimize = true)
repeat(30) { opt.step { misfit(it) } }

fun draw(pts: List<Pair<Double, Double>>, dx: Double, colour: Vec4, width: Double) =
    FLine().also { f ->
        pts.forEachIndexed { i, (x, y) ->
            if (i == 0) f.moveTo(dx + 100 * x, -100 * y) else f.lineTo(dx + 100 * x, -100 * y)
        }
        f.color = colour
        f.thickness = width
    }
val grey = vec(0.6, 0.6, 0.6, 1.0)
lines(
    [draw(target, 0.0, grey, 6.0)] +
        opt.history.take(5).map { draw(curve(it.parameters), 0.0, vec(0.2, 0.4, 0.9, 0.35), 1.0) } +
        draw(target, 320.0, grey, 6.0) +
        draw(curve(opt.best!!.parameters), 320.0, vec(0.9, 0.2, 0.2, 1.0), 2.0),
    w = 640.0,
    h = 300.0,
    fitToSize = true,
)
showChart(
    "best misfit so far" to opt.bestSoFar.map { log10(it) },
    channel = "convergence",
    title = "log10 misfit after each try",
    xLabel = "try",
    yLabel = "log10 misfit",
)

println(
    "best after 30 tries: misfit %.2e at %s"
        .format(
            opt.best!!.value,
            opt.best!!.parameters.mapValues { "%.3f".format(it.value) },
        ),
)
println("first five tries (blue, left): " + opt.history.take(5).joinToString { "%.3f".format(it.value) })
```

