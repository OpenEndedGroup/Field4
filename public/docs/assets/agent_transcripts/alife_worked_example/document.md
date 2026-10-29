# Field Document: alife_worked_example

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 20 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 2 — Four elementary rules, filling in — launch me

<!-- field-box id="alife_elementary_moving" index="0" -->

### Narrative

From `code`

LAUNCH this box rather than merely running it (undo stops it). The last expression is a
`Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
the box's output every displayed frame, then holds the final frame forever.
Compute the four space-time diagrams once, in full: 257 cells wide, 128 generations tall.

```edn [field-box id="alife_elementary_moving" role="properties"]
{"id" "alife_elementary_moving" "index" 0 "name" "2 — Four elementary rules, filling in — launch me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 — Four elementary rules, filling in — launch me" "_tombstone" false "frame" #afield/rect [60.0 960.0 430.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_elementary_moving_code_.kt" "lines" 32 "characters" 1389 "sha256" "f2b1e2492ea27df26b1e013ac70d847277abae0040889b9ab8932bd1aa29a290"}]}
```

```kotlin [field-property box="alife_elementary_moving" name="code" path="alife_elementary_moving_code_.kt"]
/*
LAUNCH this box rather than merely running it (undo stops it). The last expression is a
`Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
the box's output every displayed frame, then holds the final frame forever.
Compute the four space-time diagrams once, in full: 257 cells wide, 128 generations tall.
*/
val seedRow = IntArray(257) { if ((it * 7919) % 3 == 0) 1 else 0 }
val diagrams = [
    "rule 90" to elementaryCa(90, 257, 128).toImg(2),
    "rule 30" to elementaryCa(30, 257, 128).toImg(2),
    "rule 110" to elementaryCa(110, 257, 128, IntArray(257) { if (it % 13 == 0) 1 else 0 }).toImg(2),
    "rule 184" to elementaryCa(184, 257, 128, seedRow).toImg(2),
]

/*
128 generations over 128 displayed frames: ONE row of every diagram per frame. The upscale comes
AFTER the reveal, so `n` counts generations rather than pixels -- and `rowsRevealed` keeps the
full height, so montage frames each panel identically instead of rescaling a growing image.
*/
animateEvolution(
    Unit,
    totalSteps = 128,
    seconds = 6.0,
    framesTarget = 128,
    columns = 2,
    cellSide = 520,
    step = { it },
    panels = { _, n ->
        diagrams.map { (name, img) -> name + ", gen " + n to img.rowsRevealed(n).upscaleNearest(2) }
    },
)
```

## 2. 2 — Elementary CA against an independent oracle

<!-- field-box id="alife_elementary" index="1" -->

### Narrative

From `code`

Wolfram's elementary rules, and the sharpest evidence in the family: rule 90 from a single seed
IS Pascal's triangle mod 2, and `sierpinskiReference` computes that from Kummer's theorem
without running a cellular automaton at all. The equality is a cross-check between two
unrelated computations rather than a stepper agreeing with itself.

```edn [field-box id="alife_elementary" role="properties"]
{"id" "alife_elementary" "index" 1 "name" "2 — Elementary CA against an independent oracle" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 — Elementary CA against an independent oracle" "_tombstone" false "frame" #afield/rect [60.0 260.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_elementary_code_.kt" "lines" 52 "characters" 2172 "sha256" "c54125b51088b14dcf8794e4c4ea56c234d2c2aadc914dd6b2c67717a7fbde5a"}]}
```

```kotlin [field-property box="alife_elementary" name="code" path="alife_elementary_code_.kt"]
/*
Wolfram's elementary rules, and the sharpest evidence in the family: rule 90 from a single seed
IS Pascal's triangle mod 2, and `sierpinskiReference` computes that from Kummer's theorem
without running a cellular automaton at all. The equality is a cross-check between two
unrelated computations rather than a stepper agreeing with itself.
*/
val w = 257
val gens = 128
val rule90 = elementaryCa(90, w, gens)
val oracle = sierpinskiReference(w, gens)
var differing = 0
for (i in rule90.cells.indices) if (rule90.cells[i] != oracle.cells[i]) differing++
println("rule 90 vs Pascal mod 2: " + differing + " differing cells of " + rule90.cells.size)

// A second, independent oracle: row g of that diagram holds exactly 2^(popcount g) live cells.
val rows = (0 until gens).map { r -> (0 until w).count { rule90.cells[r * w + it] == 1 } }
println(
    "row populations are exactly 2^popcount(generation): " +
        (rows == (0 until gens).map { 1 shl Integer.bitCount(it) }),
)

// Rule 184 is the traffic rule: it conserves the number of 1s exactly, forever.
val seedRow = IntArray(257) { if ((it * 7919) % 3 == 0) 1 else 0 }
val traffic = elementaryCa(184, 257, 200, seedRow)
val pops = (0 until 200).map { r -> (0 until 257).count { traffic.cells[r * 257 + it] == 1 } }
println("rule 184 distinct row populations over 200 generations: " + pops.distinct())
println("rule 90 distinct row populations: " + rows.distinct().size + " values, so NOT conserved")

val col30 = elementaryCa(30, 601, 600).centerColumn()
println(
    "rule 30 centre column: " +
        col30.count { it == 1 } +
        "/600 ones, longest run " +
        longestRunLength(col30) +
        " (rule 90's is " +
        longestRunLength(rule90.centerColumn()) +
        ")",
)

showImg(
    montage(
        [
            "rule 90 = Pascal mod 2" to rule90.toImg(2),
            "rule 30" to elementaryCa(30, 257, 128).toImg(2),
            "rule 110 (universal)" to
                elementaryCa(110, 257, 128, IntArray(257) { if (it % 13 == 0) 1 else 0 }).toImg(2),
            "rule 184 (density-conserving)" to traffic.toImg(2),
        ],
        columns = 2,
        cellSide = 300,
    ),
)
```

## 3. 4 — Gray-Scott: regimes told apart by measurement

<!-- field-box id="alife_gray_scott" index="2" -->

### Narrative

From `code`

Gray-Scott reaction-diffusion. Two coupled fields, two parameters, and the whole zoo of
morphologies -- spots, worms, mazes, holes -- is a function of those two numbers.

The point of `morphology()` is that a regime claim should be a claim about numbers. Spots and a
labyrinth can have identical coverage, identical mean and identical variance; what separates
them is the SHAPE of their features (isoperimetric ratio P^2/4piA, ~1 for a disc) and whether
one connected structure dominates.

```edn [field-box id="alife_gray_scott" role="properties"]
{"id" "alife_gray_scott" "index" 2 "name" "4 — Gray-Scott: regimes told apart by measurement" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 — Gray-Scott: regimes told apart by measurement" "_tombstone" false "frame" #afield/rect [520.0 60.0 430.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_gray_scott_code_.kt" "lines" 45 "characters" 1916 "sha256" "f2209d2642b6eaa5d64b7cd348e0581635599d830f3a2394577054936af5f1c7"}]}
```

```kotlin [field-property box="alife_gray_scott" name="code" path="alife_gray_scott_code_.kt"]
/*
Gray-Scott reaction-diffusion. Two coupled fields, two parameters, and the whole zoo of
morphologies -- spots, worms, mazes, holes -- is a function of those two numbers.

The point of `morphology()` is that a regime claim should be a claim about numbers. Spots and a
labyrinth can have identical coverage, identical mean and identical variance; what separates
them is the SHAPE of their features (isoperimetric ratio P^2/4piA, ~1 for a disc) and whether
one connected structure dominates.
*/
val side = 128
val steps = 6000
val regimes: List<Pair<String, GrayScottParams>> = [
    "spots 0.030/0.062" to GRAY_SCOTT_SPOTS,
    "worms 0.037/0.060" to GRAY_SCOTT_WORMS,
    "maze 0.029/0.057" to GRAY_SCOTT_MAZE,
    "holes 0.039/0.058" to GRAY_SCOTT_HOLES,
]
val runs = regimes.map { (label, p) ->
    label to grayScottSeed(side, side, patches = 6, patchSize = 14, seed = 11L).stepTimes(p, steps)
}
for ((label, end) in runs) println(label + " -> " + end.v.morphology())

/*
Two controls. v = 0 is a fixed point of the equations, so an unseeded field never patterns;
and equal diffusion coefficients destroy the Turing instability entirely, which is Turing's
own argument restated as an experiment.
*/
val unseeded = grayScottSeed(side, side, patches = 0).stepTimes(GRAY_SCOTT_WORMS, 500)
println("CONTROL no seed: max autocatalyst after 500 steps = " + unseeded.v.max())
val equalD =
    grayScottSeed(side, side, patches = 6, patchSize = 14, seed = 11L)
        .stepTimes(GrayScottParams(feed = 0.037, kill = 0.060, du = 0.08, dv = 0.08), steps)
println("CONTROL Du == Dv -> " + equalD.v.morphology())

val controls: List<Pair<String, GrayScottState>> = [
    "CONTROL no seed" to unseeded,
    "CONTROL Du == Dv" to equalD,
]
showImg(
    montage(
        (runs + controls).map { (label, s) -> label to s.v.colormap("magma", 0f, 0.5f).upscaleNearest(2) },
        columns = 3,
        cellSide = 260,
    ),
)
```

## 4. 6 — Boids: the control arm, running — launch me

<!-- field-box id="alife_boids_moving" index="3" -->

### Narrative

From `code`

LAUNCH this box rather than merely running it (undo stops it). The last expression is a
`Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
the box's output every displayed frame, then holds the final frame forever.
One pixel per domain unit, with an 8-unit motion streak behind each agent -- long enough to join
up at nine units of travel per frame. `order()` reads the velocity array, so neither choice
touches what is measured; the number in each caption is computed fresh every frame.

```edn [field-box id="alife_boids_moving" role="properties"]
{"id" "alife_boids_moving" "index" 3 "name" "6 — Boids: the control arm, running — launch me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "6 — Boids: the control arm, running — launch me" "_tombstone" false "frame" #afield/rect [520.0 1160.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_boids_moving_code_.kt" "lines" 35 "characters" 1570 "sha256" "dd42bad6f5cba198041a7005a0685619b709e56f8a6a48cc9ab809f831511539"}]}
```

```kotlin [field-property box="alife_boids_moving" name="code" path="alife_boids_moving_code_.kt"]
/*
LAUNCH this box rather than merely running it (undo stops it). The last expression is a
`Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
the box's output every displayed frame, then holds the final frame forever.
One pixel per domain unit, with an 8-unit motion streak behind each agent -- long enough to join
up at nine units of travel per frame. `order()` reads the velocity array, so neither choice
touches what is measured; the number in each caption is computed fresh every frame.
*/
fun flocks(a: BoidFlock, b: BoidFlock, n: Int) = [
    "flock " + n + ": order " + "%.2f".format(a.order()) to a.toImg(1.0, tail = 8.0),
    "no align " + n + ": " + "%.2f".format(b.order()) to b.toImg(1.0, tail = 8.0),
]

/*
The same initial state, twice, stepped together -- so the ONLY difference between the two panels
is the alignment weight. 900 steps over 140 displayed frames: six or seven steps per frame, and
no ramp -- a flock's rate of change is roughly constant, which is exactly when linear is right.
*/
val params = BoidParams()
val blind = params.copy(alignmentWeight = 0.0)
val start = BoidFlock.random(400, 260.0, 260.0, speed = 1.4, seed = 4L)
animateEvolution(
    start.copy() to start.copy(),
    totalSteps = 900,
    seconds = 7.0,
    columns = 2,
    cellSide = 300,
    step = { (a, b) ->
        a.step(params)
        b.step(blind)
        a to b
    },
    panels = { (a, b), n -> flocks(a, b, n) },
)
```

## 5. 3 — Cyclic CA: self-organisation, measured

<!-- field-box id="alife_cyclic" index="4" -->

### Narrative

From `code`

A cyclic cellular automaton: a cell in state s advances to s+1 as soon as any neighbour is
already in s+1. Nothing can skip, so the only durable structures are rotating bands.

The measurement that makes "it self-organised" checkable: in an UNCORRELATED soup a cell can
advance exactly when one of its 8 Moore neighbours happens to hold its successor state, which
is 1 - (1 - 1/states)^8 -- a closed form. Once the field has organised, every cell is driven by
a neighbour and the advance fraction pins at 1.0, which no uncorrelated field does.

```edn [field-box id="alife_cyclic" role="properties"]
{"id" "alife_cyclic" "index" 4 "name" "3 — Cyclic CA: self-organisation, measured" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 — Cyclic CA: self-organisation, measured" "_tombstone" false "frame" #afield/rect [60.0 460.0 390.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_cyclic_code_.kt" "lines" 42 "characters" 1990 "sha256" "1cd8a12826c0b9158e2f94980bd52f8b9a14940499e6306e228e4e8e2db02fe9"}]}
```

```kotlin [field-property box="alife_cyclic" name="code" path="alife_cyclic_code_.kt"]
/*
A cyclic cellular automaton: a cell in state s advances to s+1 as soon as any neighbour is
already in s+1. Nothing can skip, so the only durable structures are rotating bands.

The measurement that makes "it self-organised" checkable: in an UNCORRELATED soup a cell can
advance exactly when one of its 8 Moore neighbours happens to hold its successor state, which
is 1 - (1 - 1/states)^8 -- a closed form. Once the field has organised, every cell is driven by
a neighbour and the advance fraction pins at 1.0, which no uncorrelated field does.
*/
val rule = CYCLIC_LABYRINTH
val soup = IntGrid.random(200, 200, states = rule.states, seed = 7L)
val predicted = 1.0 - Math.pow(1.0 - 1.0 / rule.states, 8.0)
val early = cyclicAdvanceFraction(soup, soup.step(rule))
val settled = soup.stepTimes(rule, 400)
val late = cyclicAdvanceFraction(settled, settled.step(rule))
println("soup advance fraction " + "%.4f".format(early) + ", closed form " + "%.4f".format(predicted))
println("after 400 generations " + "%.4f".format(late) + " of cells advance every step")

// The excitable corner of the parameter space (wider neighbourhood, higher threshold) is a
// visibly different phase -- spiral cores -- and the difference is measurable as a length scale.
val ex = CYCLIC_EXCITABLE
val exSettled = IntGrid.random(200, 200, states = ex.states, seed = 7L).stepTimes(ex, 200)
println(
    "correlation length: labyrinth " +
        "%.2f".format(settled.toImg(rule.states).correlationLength(maxLag = 30)) +
        ", excitable " +
        "%.2f".format(exSettled.toImg(ex.states).correlationLength(maxLag = 30)),
)

val palette = cyclicPalette(rule.states)
showImg(
    montage(
        [
            "gen 0 (soup)" to soup.toImg(palette),
            "gen 40" to soup.stepTimes(rule, 40).toImg(palette),
            "gen 400" to settled.toImg(palette),
            "excitable, gen 200" to exSettled.toImg(cyclicPalette(ex.states)),
        ],
        columns = 2,
        cellSide = 280,
    ),
)
```

## 6. 5 — Lenia: exact in its discrete limit, and Orbium across mu

<!-- field-box id="alife_lenia" index="5" -->

```edn [field-box id="alife_lenia" role="properties"]
{"id" "alife_lenia" "index" 5 "name" "5 — Lenia: exact in its discrete limit, and Orbium across mu" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 — Lenia: exact in its discrete limit, and Orbium across mu" "_tombstone" false "frame" #afield/rect [520.0 260.0 370.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_lenia_code_.kt" "lines" 54 "characters" 2118 "sha256" "29425a474ad3381bd7e9beba2614b8e05111069387a9e47e6ad9e5ac3090e206"}]}
```

```kotlin [field-property box="alife_lenia" name="code" path="alife_lenia_code_.kt"]
val discrete = leniaFromLifeRule(LIFE)
println("discrete-limit kernel: " + discrete.taps.size + " taps summing to " + discrete.taps.weight.sum())

var grid = LifePatterns.glider.centeredOn(32, 32)
var field = grid.toImg(2)
var worst = 0.0
for (gen in 1..20) {
    grid = grid.step(LIFE)
    field = field.leniaTimes(discrete, 1)
    worst = maxOf(worst, fieldMaxAbsDiff(field, grid.toImg(2)))
}
println("20 generations, float Lenia vs integer Life: worst per-cell difference " + worst)
println(
    "the glider the float stepper carried moved by " +
        translationBetween(LifePatterns.glider.centeredOn(32, 32), field.toIntGrid(0.5f)),
)

// Control: move the growth window by one neighbour count and nothing else.
println(
    "CONTROL, growth window at B4/S234: difference " +
        fieldMaxAbsDiff(
            LifePatterns.glider.centeredOn(32, 32).toImg(2).leniaTimes(leniaFromLifeRule(lifeRule("B4/S234")), 20),
            grid.toImg(2),
        ),
)

// The continuous regime: Orbium, from Bert Chan's catalogue: its cells shuffled, then only mu moved.
val orbium = LeniaPresets.orbium()
for (seed in 1L..6L) {
    println(
        "shuffled cells, seed " + seed + ": " + leniaRun(orbium.shuffled(seed).seed(64, 64), orbium.rule, steps = 300),
    )
}
for (mu in [0.11, 0.12, 0.13, 0.16, 0.17, 0.18]) {
    val rule = leniaRuleFromCatalogue(radius = 13, t = 10.0, mu = mu, sigma = 0.015)
    println("mu = " + mu + ": " + leniaRun(orbium.seed(64, 64), rule, steps = 300))
}

val moments = [0, 5, 10, 20, 250]
val panels =
    [0.12, 0.15, 0.18].flatMap { mu ->
        val rule = leniaRuleFromCatalogue(radius = 13, t = 10.0, mu = mu, sigma = 0.015)
        var current = orbium.seed(64, 64)
        var done = 0
        moments.map { step ->
            current = current.leniaTimes(rule, step - done)
            done = step
            (if (step == 0) "mu " + mu else "step " + step) to current.colormap("inferno", 0f, 1f).upscaleNearest(3)
        }
    }
showImg(
    montage(panels, columns = 5, cellSide = 192),
    caption = "rows: mu = 0.12, 0.15, 0.18; columns: steps 0, 5, 10, 20 and 250",
)
```

## 7. 9 — L-systems: exact combinatorial identities

<!-- field-box id="alife_lsystem" index="6" -->

### Narrative

From `code`

L-systems are the one generative system in this family whose output can be checked exactly and
combinatorially rather than statistically -- so they carry the sharpest evidence here.

```edn [field-box id="alife_lsystem" role="properties"]
{"id" "alife_lsystem" "index" 6 "name" "9 — L-systems: exact combinatorial identities" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "9 — L-systems: exact combinatorial identities" "_tombstone" false "frame" #afield/rect [980.0 460.0 410.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_lsystem_code_.kt" "lines" 60 "characters" 2923 "sha256" "fa37ef2b826d0da33b37f312bbedc37b7d3718829ce12e2eb881d6de5bc8894c"}]}
```

```kotlin [field-property box="alife_lsystem" name="code" path="alife_lsystem_code_.kt"]
// L-systems are the one generative system in this family whose output can be checked exactly and
// combinatorially rather than statistically -- so they carry the sharpest evidence here.
println("algae derivation lengths (Fibonacci): " + LSYSTEM_ALGAE.derivations(9).map { it.length })
println(
    "Koch snowflake forward symbols (3 * 4^n): " +
        (0..5).map { gen -> LSYSTEM_KOCH_SNOWFLAKE.derive(gen).count { it == 'F' } },
)

/*
And the turtle: the path CLOSES, its drawn length quadruples exactly at constant step size, and
its enclosed area matches the closed form 9^n * A0 * (1 + 3/5(1 - (4/9)^n)). FLine stores
single-precision coordinates, so "exact" here means to float precision.
*/
val step = 1.0
val tp = TurtleParams(angle = Math.PI / 3.0, step = step)
val gens = (0..5).map { drawLSystem(LSYSTEM_KOCH_SNOWFLAKE, it, tp) }
println("worst closure residual: " + gens.map { turtleClosureError(it, step) }.max() + " of a step")
val lens = gens.map { flineTotalLength(it.lines) }
println("length ratio per generation: " + (1..5).map { "%.6f".format(lens[it] / lens[it - 1]) })
println(
    "area, relative error against the closed form: " +
        (0..5).map { gen ->
            val a0 = Math.sqrt(3.0) / 4.0 * step * step
            val predicted =
                Math.pow(9.0, gen.toDouble()) * a0 * (1.0 + 0.6 * (1.0 - Math.pow(4.0 / 9.0, gen.toDouble())))
            "%.1e".format(Math.abs(Math.abs(flineSignedArea(gens[gen].lines)) - predicted) / predicted)
        },
)
// Control: one wrong turn and none of that holds.
println(
    "CONTROL, turn angle perturbed by 0.01 rad: open by " +
        "%.1f"
            .format(
                turtleClosureError(drawLSystem(LSYSTEM_KOCH_SNOWFLAKE, 4, tp.copy(angle = Math.PI / 3.0 + 0.01)), step),
            ) +
        " steps",
)

// Stochastic productions vary the TOPOLOGY, not merely the coordinates.
val plants = (1L..3L).map {
    drawLSystem(LSYSTEM_STOCHASTIC_PLANT, 5, TurtleParams(angle = Math.toRadians(24.0), step = 4.0), seed = it)
}
println("three seeds of the stochastic plant give branch counts " + plants.map { it.lines.size })

// The arrowhead curve draws on A and B, not F -- with the default alphabet it renders nothing.
val arrow = drawLSystem(LSYSTEM_SIERPINSKI_ARROWHEAD, 7, TurtleParams(angle = Math.PI / 3.0, drawSymbols = "AB"))
println("arrowhead, generation 7: " + arrow.drawnSegments + " drawn segments (3^7 = 2187)")

showImg(
    montage(
        [
            "Koch snowflake gen 4" to rasterizeFLines(gens[4].lines, 300, 300),
            "arrowhead gen 7" to rasterizeFLines(arrow.lines, 300, 300),
            "dragon gen 12" to
                rasterizeFLines(drawLSystem(LSYSTEM_DRAGON, 12, TurtleParams(angle = Math.PI / 2.0)).lines, 300, 300),
        ] + plants.mapIndexed { i, pl -> "plant seed " + (i + 1) to rasterizeFLines(pl.lines, 240, 300) },
        columns = 3,
        cellSide = 280,
    ),
)
```

## 8. 7 — Physarum: the network finding itself — launch me

<!-- field-box id="alife_physarum_moving" index="7" -->

### Narrative

From `code`

LAUNCH this box rather than merely running it (undo stops it). The last expression is a
`Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
the box's output every displayed frame, then holds the final frame forever.
Both panels on ONE derived range, not on each other's maxima: a cell an agent occupies every
step accumulates `deposit` and keeps `decay` of what it had, so it converges to
deposit / (1 - decay) = 62.5. The first cut of this clip guessed 30 and clipped the network to
flat cream. The caption's number is `topShare(0.05)`, recomputed every frame.

```edn [field-box id="alife_physarum_moving" role="properties"]
{"id" "alife_physarum_moving" "index" 7 "name" "7 — Physarum: the network finding itself — launch me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "7 — Physarum: the network finding itself — launch me" "_tombstone" false "frame" #afield/rect [980.0 760.0 450.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_physarum_moving_code_.kt" "lines" 34 "characters" 1834 "sha256" "568a0507e85531290321a18f538f6936814fa978ad75ca4898e724a08ecce90f"}]}
```

```kotlin [field-property box="alife_physarum_moving" name="code" path="alife_physarum_moving_code_.kt"]
/*
LAUNCH this box rather than merely running it (undo stops it). The last expression is a
`Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
the box's output every displayed frame, then holds the final frame forever.
Both panels on ONE derived range, not on each other's maxima: a cell an agent occupies every
step accumulates `deposit` and keeps `decay` of what it had, so it converges to
deposit / (1 - decay) = 62.5. The first cut of this clip guessed 30 and clipped the network to
flat cream. The caption's number is `topShare(0.05)`, recomputed every frame.
*/
val p = PhysarumParams()
val ceiling = (p.deposit / (1.0 - p.decay)).toFloat()
fun trails(a: PhysarumSwarm, b: PhysarumSwarm, n: Int) = [
    "sensing " + n + "  " + "%.3f".format(a.trail.topShare(0.05)) to a.trail.colormap("magma", 0f, ceiling),
    "blind " + n + "  " + "%.3f".format(b.trail.topShare(0.05)) to b.trail.colormap("magma", 0f, ceiling),
]

/*
Sensing and blind, from the same seed, stepped together: 400 steps over 140 displayed frames,
so three deposit-sense-move rounds per frame. `step` takes its rng explicitly, so each
population gets its own stream -- exactly what `stepTimes(p, 400, seed = 6L)` does internally,
which is why these panels land on the same numbers as the figure above.
*/
val rngA = kotlin.random.Random(6L)
val rngB = kotlin.random.Random(6L)
animateEvolution(
    PhysarumSwarm.disc(256, 256, 6000, 0.55, seed = 6L) to PhysarumSwarm.disc(256, 256, 6000, 0.55, seed = 6L),
    totalSteps = 400,
    seconds = 7.0,
    columns = 2,
    cellSide = 300,
    step = { (a, b) -> a.step(p, rngA) to b.step(p.copy(sensing = false), rngB) },
    panels = { (a, b), n -> trails(a, b, n) },
)
```

## 9. 8 — DLA: the order the branches arrived in — launch me

<!-- field-box id="alife_dla_moving" index="8" -->

### Narrative

From `code`

LAUNCH this box rather than merely running it (undo stops it). The last expression is a
`Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
the box's output every displayed frame, then holds the final frame forever.
Grow the cluster once, then replay `Aggregate.points` -- which IS the order the particles stuck
in -- at a uniform rate over seven seconds.

```edn [field-box id="alife_dla_moving" role="properties"]
{"id" "alife_dla_moving" "index" 8 "name" "8 — DLA: the order the branches arrived in — launch me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "8 — DLA: the order the branches arrived in — launch me" "_tombstone" false "frame" #afield/rect [980.0 960.0 470.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_dla_moving_code_.kt" "lines" 60 "characters" 2555 "sha256" "f05fdcd0eaae6c29649cb5a9d656d5a1e33fa78a329426c50c275e5c5a0c4965"}]}
```

```kotlin [field-property box="alife_dla_moving" name="code" path="alife_dla_moving_code_.kt"]
/*
LAUNCH this box rather than merely running it (undo stops it). The last expression is a
`Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
the box's output every displayed frame, then holds the final frame forever.
Grow the cluster once, then replay `Aggregate.points` -- which IS the order the particles stuck
in -- at a uniform rate over seven seconds.
*/
val dla = dlaCluster(300, 300, particles = 6000, seed = 2L)

/*
The crop is the colour-range discipline in geometry: taken ONCE, from the finished cluster, never
from the frame -- a crop that tracked the growing prefix would zoom out as the cluster grew and
the screening this clip is about would be the one thing you could not see.

Per AXIS rather than a square around maxRadius: a DLA cluster is anisotropic, and maxRadius is set
by its single longest arm, so a square crop leaves a third of the panel permanently empty.
*/
val pad = 2
val x0 = (dla.points.minOf { it.x }.toInt() - pad).coerceAtLeast(0)
val y0 = (dla.points.minOf { it.y }.toInt() - pad).coerceAtLeast(0)
val cw = (dla.points.maxOf { it.x }.toInt() + pad + 1 - x0).coerceAtMost(300 - x0)
val ch = (dla.points.maxOf { it.y }.toInt() + pad + 1 - y0).coerceAtMost(300 - y0)

/*
`arrivalPrefixImg` fixes the colour ramp to the FULL cluster's particle count, so a branch keeps
the colour it arrived with; `arrivalOrderImg` normalises by its own count and would re-map the
newest particle to the top of turbo on every frame.
*/
fun cluster(dla: Aggregate, n: Int) = [
    "particles " + n + " of " + dla.points.size to
        dla.arrivalPrefixImg(n).crop(x0, y0, cw, ch).colormap("turbo", 0f, 1f).upscaleNearest(3),
]

/*
Why the crop: a cluster is a fixed amount of ink, and how legible its growth is depends entirely on
how much of the frame that ink occupies. Note the particle count in the first line -- `particles`
is a ceiling, not a promise, and this cluster stopped well short of the 6000 asked for.
*/
println(dla)
println(
    "cropped to " +
        cw +
        " x " +
        ch +
        " of 300 x 300, so " +
        "%.1f".format(100.0 * dla.points.size / (cw * ch)) +
        "% of the panel is ever lit, against " +
        "%.1f".format(100.0 * dla.points.size / 90000.0) +
        "% uncropped",
)

animateEvolution(
    Unit,
    totalSteps = dla.points.size,
    seconds = 7.0,
    cellSide = 660,
    step = { it },
    panels = { _, n -> cluster(dla, n) },
)
```

## 10. 1 — Conway's Life against known objects

<!-- field-box id="alife_life" index="9" -->

### Narrative

From `code`

Conway's Life. Every claim here is about an object that predates this code, stated as an
integer-grid equality rather than as an observation about a picture.

```edn [field-box id="alife_life" role="properties"]
{"id" "alife_life" "index" 9 "name" "1 — Conway's Life against known objects" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 — Conway's Life against known objects" "_tombstone" false "frame" #afield/rect [60.0 60.0 370.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_life_code_.kt" "lines" 49 "characters" 2127 "sha256" "539ff33a77da18bf50bde2330699e04c63081c453bfd9f3d0ff8b028d8a8bf1a"}]}
```

```kotlin [field-property box="alife_life" name="code" path="alife_life_code_.kt"]
// Conway's Life. Every claim here is about an object that predates this code, stated as an
// integer-grid equality rather than as an observation about a picture.
val glider = LifePatterns.glider.centeredOn(40, 40)
println("glider, 4 generations: rigid translation = " + translationBetween(glider, glider.stepTimes(LIFE, 4)))
println("glider, 8 generations: rigid translation = " + translationBetween(glider, glider.stepTimes(LIFE, 8)))

// The control: one birth count away and the same seed is not a spaceship at all.
println(
    "same seed under B34/S23, 4 generations: " + translationBetween(glider, glider.stepTimes(lifeRule("B34/S23"), 4)),
)

val block = LifePatterns.block.centeredOn(20, 20)
println("block is bit-identical after one step: " + block.step(LIFE).sameCellsAs(block))
println("blinker's detected period: " + LifePatterns.blinker.centeredOn(20, 20).runUntilCycle(LIFE, 20).period)
println("toad's detected period: " + LifePatterns.toad.centeredOn(20, 20).runUntilCycle(LIFE, 20).period)

/*
The unbounded R-pentomino reaches a peak of 319 cells at generation 821 and settles to
116 cells from generation 1103, including six gliders (30 live cells) still in flight.
A 620-wide board leaves room for their travel. The measurements below report population,
peak and final left clearance; final bounds cannot rule out earlier edge contact.
*/
var g = LifePatterns.rPentomino.centeredOn(620, 620, wrap = false)
var peak = 0
var peakAt = 0
for (gen in 1..1103) {
    g = g.step(LIFE)
    val pop = g.population()
    if (pop > peak) {
        peak = pop
        peakAt = gen
    }
}
val b = g.liveBounds()!!
println("R-pentomino peak population " + peak + " at generation " + peakAt)
println(
    "population at generation 1103 = " + g.population() + ", live cells " + b[0] + " clear of the board edge",
)

showImg(
    montage(
        (0..4).map {
            "gen " + it to LifePatterns.glider.centeredOn(16, 16).stepTimes(LIFE, it).toImg(2).upscaleNearest(12)
        },
        columns = 5,
        cellSide = 200,
    ),
    caption = "one glider, five generations: the same shape displaced by (1, 1)",
)
```

## 11. 7 — Physarum: a network the code never mentions

<!-- field-box id="alife_physarum" index="10" -->

### Narrative

From `code`

Jones' Physarum model: agents deposit into a trail field, sense it three ways ahead, turn toward
the strongest, move. The transport network is nowhere in the code -- it is the fixed point of
that loop.

The measurement: `topShare(0.05)` is the fraction of all deposit held by the brightest 5% of
pixels. A flat field gives 0.05. A network concentrates material into filaments and gives much
more. The control is the identical population with sensing switched off -- same count, same
seed, same deposit, same decay, random turns.

```edn [field-box id="alife_physarum" role="properties"]
{"id" "alife_physarum" "index" 10 "name" "7 — Physarum: a network the code never mentions" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "7 — Physarum: a network the code never mentions" "_tombstone" false "frame" #afield/rect [980.0 60.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_physarum_code_.kt" "lines" 35 "characters" 1416 "sha256" "4c6517907e12f28ed3e1ad03a9531bef795dc623cede0a9e423ded62cc0f76f5"}]}
```

```kotlin [field-property box="alife_physarum" name="code" path="alife_physarum_code_.kt"]
/*
Jones' Physarum model: agents deposit into a trail field, sense it three ways ahead, turn toward
the strongest, move. The transport network is nowhere in the code -- it is the fixed point of
that loop.

The measurement: `topShare(0.05)` is the fraction of all deposit held by the brightest 5% of
pixels. A flat field gives 0.05. A network concentrates material into filaments and gives much
more. The control is the identical population with sensing switched off -- same count, same
seed, same deposit, same decay, random turns.
*/
val side = 256
val p = PhysarumParams()
val sensing = PhysarumSwarm.disc(side, side, 6000, 0.55, seed = 6L).stepTimes(p, 400, seed = 6L)
val blind = PhysarumSwarm.disc(side, side, 6000, 0.55, seed = 6L).stepTimes(p.copy(sensing = false), 400, seed = 6L)

println(
    "top-5% deposit share: sensing " +
        "%.3f".format(sensing.trail.topShare(0.05)) +
        ", CONTROL sensing off " +
        "%.3f".format(blind.trail.topShare(0.05)) +
        " (a flat field gives 0.05)",
)
println("sensing morphology: " + sensing.trail.normalized().morphology())
println("blind   morphology: " + blind.trail.normalized().morphology())

showImg(
    montage(
        [
            "sensing" to sensing.trail.normalized().colormap("magma"),
            "CONTROL: sensing off" to blind.trail.normalized().colormap("magma"),
        ],
        columns = 2,
        cellSide = 340,
    ),
)
```

## 12. 3 — Cyclic CA: the two phases, running — launch me

<!-- field-box id="alife_cyclic_moving" index="11" -->

### Narrative

From `code`

LAUNCH this box rather than merely running it (undo stops it). The last expression is a
`Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
the box's output every displayed frame, then holds the final frame forever.
One palette per rule, FIXED -- it is a function of the rule's state count, never of the frame,
which is what makes generation 0 and generation 400 the same picture of the same thing. Three
pixels to a cell, nearest-neighbour, so the bands are countable and the encoder sees flat blocks.

```edn [field-box id="alife_cyclic_moving" role="properties"]
{"id" "alife_cyclic_moving" "index" 11 "name" "3 — Cyclic CA: the two phases, running — launch me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 — Cyclic CA: the two phases, running — launch me" "_tombstone" false "frame" #afield/rect [60.0 1160.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_cyclic_moving_code_.kt" "lines" 30 "characters" 1502 "sha256" "5e3b85d2860c526799e74b67cd47264f5abba00baf27158cf702d02385da78d1"}]}
```

```kotlin [field-property box="alife_cyclic_moving" name="code" path="alife_cyclic_moving_code_.kt"]
/*
LAUNCH this box rather than merely running it (undo stops it). The last expression is a
`Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
the box's output every displayed frame, then holds the final frame forever.
One palette per rule, FIXED -- it is a function of the rule's state count, never of the frame,
which is what makes generation 0 and generation 400 the same picture of the same thing. Three
pixels to a cell, nearest-neighbour, so the bands are countable and the encoder sees flat blocks.
*/
val pal = cyclicPalette(CYCLIC_LABYRINTH.states)
val exPal = cyclicPalette(CYCLIC_EXCITABLE.states)
fun soups(a: IntGrid, b: IntGrid, n: Int) = [
    "labyrinth, gen " + n to a.toImg(pal).upscaleNearest(3),
    "excitable, gen " + n to b.toImg(exPal).upscaleNearest(3),
]

// Two cyclic rules from the same kind of soup, stepped together so the schedule is shared:
// 400 generations over 130 displayed frames, so a little over THREE generations per frame.
val rule = CYCLIC_LABYRINTH
val ex = CYCLIC_EXCITABLE
animateEvolution(
    IntGrid.random(96, 96, states = rule.states, seed = 7L) to IntGrid.random(96, 96, states = ex.states, seed = 7L),
    totalSteps = 400,
    seconds = 6.5,
    framesTarget = 130,
    columns = 2,
    cellSide = 288,
    step = { (a, b) -> a.step(rule) to b.step(ex) },
    panels = { (a, b), n -> soups(a, b, n) },
)
```

## 13. 9 — L-systems: the turtle, walking — launch me

<!-- field-box id="alife_lsystem_moving" index="12" -->

### Narrative

From `code`

LAUNCH this box rather than merely running it (undo stops it). The last expression is a
`Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
the box's output every displayed frame, then holds the final frame forever.
`rasterizeFLinesFramed` takes its framing from the FINISHED curve. Plain `rasterizeFLines` fits
the raster to whatever it is handed, so a growing prefix rescales every frame: the curve appears
to stay the same size while the world shrinks around it, and the growth becomes invisible.

```edn [field-box id="alife_lsystem_moving" role="properties"]
{"id" "alife_lsystem_moving" "index" 12 "name" "9 — L-systems: the turtle, walking — launch me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "9 — L-systems: the turtle, walking — launch me" "_tombstone" false "frame" #afield/rect [980.0 1160.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_lsystem_moving_code_.kt" "lines" 34 "characters" 1699 "sha256" "be9c7c8bad293a54f620d4f8709ad8adb1e0102cb6807953521894d1c85247b4"}]}
```

```kotlin [field-property box="alife_lsystem_moving" name="code" path="alife_lsystem_moving_code_.kt"]
/*
LAUNCH this box rather than merely running it (undo stops it). The last expression is a
`Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
the box's output every displayed frame, then holds the final frame forever.
`rasterizeFLinesFramed` takes its framing from the FINISHED curve. Plain `rasterizeFLines` fits
the raster to whatever it is handed, so a growing prefix rescales every frame: the curve appears
to stay the same size while the world shrinks around it, and the growth becomes invisible.
*/
fun drawn(koch: List<FLine>, plant: List<FLine>, f: Double): List<Pair<String, Img>> {
    val pct = (f * 100).toInt()
    return [
        "Koch gen 4: " + pct + "% drawn" to rasterizeFLinesFramed(flinePrefix(koch, f), koch, 420, 420),
        "plant gen 5: " + pct + "% drawn" to rasterizeFLinesFramed(flinePrefix(plant, f), plant, 420, 420),
    ]
}

/*
Animated by DRAWING, not by cutting between derivation depths: an L-system's turtle walks in an
order, and `flinePrefix` truncates the emitted FLines at a fraction of their segment count in
exactly that order. 240 ticks over 140 displayed frames.
*/
val koch = drawLSystem(LSYSTEM_KOCH_SNOWFLAKE, 4, TurtleParams(angle = Math.PI / 3.0, step = 1.0))
val plant = drawLSystem(LSYSTEM_STOCHASTIC_PLANT, 5, TurtleParams(angle = Math.toRadians(24.0), step = 4.0), seed = 1L)
val ticks = 240
animateEvolution(
    Unit,
    totalSteps = ticks,
    seconds = 7.0,
    columns = 2,
    cellSide = 420,
    step = { it },
    panels = { _, n -> drawn(koch.lines, plant.lines, n.toDouble() / ticks) },
)
```

## 14. 10 — Bridges out: FLines, trees, and a watertight mesh

<!-- field-box id="alife_bridges" index="13" -->

### Narrative

From `code`

What makes this family Field's rather than a generic ALife library: the bridges out into the
things Field already draws, meshes and exports.

(a) A reaction-diffusion field -> iso-contour FLines, through the marching-squares extractor in
`contours.kt` that was already here.

```edn [field-box id="alife_bridges" role="properties"]
{"id" "alife_bridges" "index" 13 "name" "10 — Bridges out: FLines, trees, and a watertight mesh" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "10 — Bridges out: FLines, trees, and a watertight mesh" "_tombstone" false "frame" #afield/rect [1440.0 60.0 470.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_bridges_code_.kt" "lines" 54 "characters" 2437 "sha256" "273998a12442a020944329f4551e2a8f1636c09d3b3fe7fb4677cdfb492d73b0"}]}
```

```kotlin [field-property box="alife_bridges" name="code" path="alife_bridges_code_.kt"]
/*
What makes this family Field's rather than a generic ALife library: the bridges out into the
things Field already draws, meshes and exports.

(a) A reaction-diffusion field -> iso-contour FLines, through the marching-squares extractor in
    `contours.kt` that was already here.
*/
val rd = grayScottSeed(96, 96, patches = 5, patchSize = 12, seed = 4L).stepTimes(GRAY_SCOTT_SPOTS, 5000)
val contours = rd.v.isoContourFLines(0.25, cells = 96)
println("Gray-Scott -> " + contours.size + " iso-contour FLine(s) at v = 0.25")

/*
(b) Space colonization -> a tree of FLines. Grown into an ANNULUS, which no upward-with-noise
    heuristic reaches, so the branching really is following the space. isTree() checks the thing
    a picture cannot show: one root, n-1 edges, no cycles.
*/
val ring = annulusAttractors(1400, 150.0, 140.0, 60.0, 120.0, seed = 3L)
val tree = spaceColonization(ring, 150.0, 285.0, influenceRadius = 30.0, killRadius = 7.0, stepSize = 5.0)
println(tree)
println("is a tree (one root, n-1 edges, acyclic): " + tree.isTree())
println("CONTROL, no attractors: " + spaceColonization(emptyList(), 150.0, 285.0).nodeCount + " nodes")

/*
(c) A 3-D life-like automaton -> a TriMesh, through `mesh_sdf.kt`'s marching cubes. The claim
    that matters for a mesh is that it is WATERTIGHT -- a leaking mesher looks fine and produces
    a model that cannot be printed, booleaned or subdivided. Note an EMPTY mesh is vacuously
    watertight, so the triangle count is part of the claim.
*/
val volume =
    IntVolume.randomBall(48, 48, 48, density = 0.55, ballFraction = 0.70, seed = 8L).stepTimes(LIFE3D_CLOUDS, 6)
println(volume)
val mesh = volume.toMesh(resolution = 96, smoothRadius = 1)
println(
    "marched: " +
        mesh.triangleCount +
        " triangles, watertight = " +
        mesh.isWatertight() +
        ", Euler characteristic = " +
        mesh.eulerCharacteristic(),
)

showImg(
    montage(
        [
            "Gray-Scott field" to rd.v.colormap("magma", 0f, 0.5f).upscaleNearest(3),
            "its iso-contours as FLines" to rasterizeFLines(contours, 300, 300),
            "space colonization tree" to rasterizeFLines(tree.toFLines(), 300, 300),
            "3-D CA: z slice" to volume.slice(24).toImg(2).upscaleNearest(5),
            "3-D CA: depth projection" to volume.depthProjection().colormap("viridis").upscaleNearest(5),
        ],
        columns = 3,
        cellSide = 280,
    ),
)
```

## 15. 5 — Lenia: Orbium gliding, beside the same cells at mu 0.12 — launch me

<!-- field-box id="alife_lenia_moving" index="14" -->

### Narrative

From `code`

LAUNCH this box rather than merely running it (undo stops it). The last expression is a
Frames, and a Frames that is only built never runs.

Orbium, a Lenia lifeform from Bert Chan's catalogue, gliding beside the same cells with the
growth curve's centre mu moved from 0.15 to 0.12, where they burst and fill the torus.

```edn [field-box id="alife_lenia_moving" role="properties"]
{"id" "alife_lenia_moving" "index" 14 "name" "5 — Lenia: Orbium gliding, beside the same cells at mu 0.12 — launch me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 — Lenia: Orbium gliding, beside the same cells at mu 0.12 — launch me" "_tombstone" false "frame" #afield/rect [520.0 960.0 440.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_lenia_moving_code_.kt" "lines" 27 "characters" 1126 "sha256" "5b2fd6b5a1c9295316c57b1d288b742058ea9e17e0fa7187ddee803adc5e0dcb"}]}
```

```kotlin [field-property box="alife_lenia_moving" name="code" path="alife_lenia_moving_code_.kt"]
/*
LAUNCH this box rather than merely running it (undo stops it). The last expression is a
Frames, and a Frames that is only built never runs.

Orbium, a Lenia lifeform from Bert Chan's catalogue, gliding beside the same cells with the
growth curve's centre mu moved from 0.15 to 0.12, where they burst and fill the torus.
*/
val orbium = LeniaPresets.orbium()

fun fields(a: Img, b: Img, n: Int) = [
    "Orbium, step %d: mass %.4f".format(n, a.mean()) to a.colormap("inferno", 0f, 1f).upscaleNearest(6),
    "mu 0.12, step %d: mass %.4f".format(n, b.mean()) to b.colormap("inferno", 0f, 1f).upscaleNearest(6),
]

// The same cells, with the growth curve centred at 0.12 instead of 0.15.
val lowMu = leniaRuleFromCatalogue(radius = 13, t = 10.0, mu = 0.12, sigma = 0.015)

// At Orbium's 0.62 cells per step, 200 steps is about two laps of the 64-cell torus.
animateEvolution(
    orbium.seed(64, 64) to orbium.seed(64, 64),
    totalSteps = 200,
    seconds = 7.0,
    columns = 2,
    cellSide = 384,
    step = { (a, b) -> a.leniaStep(orbium.rule) to b.leniaStep(lowMu) },
    panels = { (a, b), n -> fields(a, b, n) },
)
```

## 16. 8 — DLA: a fractal dimension, and two controls at 2

<!-- field-box id="alife_dla" index="15" -->

### Narrative

From `code`

Diffusion-limited aggregation: a seed, then particles released far away that random-walk until
they touch and stick. Nothing in the rule mentions branches.

This is the one system here with a quantitative number to hit, so it gets the sharpest evidence:
the mass-radius dimension. An Eden cluster (same neighbourhood, growth site chosen uniformly
instead of by a diffusing walker) and a uniform sprinkle both have dimension 2, so those two
controls show the estimator is reading the diffusion-limited part and not merely "growth".

```edn [field-box id="alife_dla" role="properties"]
{"id" "alife_dla" "index" 15 "name" "8 — DLA: a fractal dimension, and two controls at 2" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "8 — DLA: a fractal dimension, and two controls at 2" "_tombstone" false "frame" #afield/rect [980.0 260.0 450.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_dla_code_.kt" "lines" 39 "characters" 1765 "sha256" "6892728985baf4a4ef821528c1c70617d02861f4b823757c3267ec4c593685e6"}]}
```

```kotlin [field-property box="alife_dla" name="code" path="alife_dla_code_.kt"]
/*
Diffusion-limited aggregation: a seed, then particles released far away that random-walk until
they touch and stick. Nothing in the rule mentions branches.

This is the one system here with a quantitative number to hit, so it gets the sharpest evidence:
the mass-radius dimension. An Eden cluster (same neighbourhood, growth site chosen uniformly
instead of by a diffusing walker) and a uniform sprinkle both have dimension 2, so those two
controls show the estimator is reading the diffusion-limited part and not merely "growth".
*/
val side = 300
val dla = dlaCluster(side, side, particles = 6000, seed = 2L)
val fit = dla.fractalDimension(rMin = 5.0, rMaxFraction = 0.75)
println(dla)
println("DLA mass dimension " + "%.3f".format(fit.dimension) + " (r2 = " + "%.4f".format(fit.rSquared) + ")")

val eden = edenCluster(side, side, particles = dla.points.size, seed = 2L)
val edenFit = eden.fractalDimension(rMin = 5.0, rMaxFraction = 0.75)
println("CONTROL Eden dimension " + "%.3f".format(edenFit.dimension))
val sprinkle = sprinkleCluster(side, side, dla.points.size, dla.maxRadius, seed = 2L)
val sprinkleFit = massRadiusDimension(
    sprinkle.points,
    sprinkle.center,
    rMin = 5.0,
    rMax = sprinkle.maxRadius * 0.75,
    minCount = 60,
)
println("CONTROL uniform sprinkle dimension " + "%.3f".format(sprinkleFit.dimension) + " (exactly 2 by construction)")

showImg(
    montage(
        [
            "DLA: D = " + "%.2f".format(fit.dimension) to dla.arrivalOrderImg().colormap("turbo"),
            "Eden: D = " + "%.2f".format(edenFit.dimension) to eden.arrivalOrderImg().colormap("turbo"),
            "sprinkle: D = " + "%.2f".format(sprinkleFit.dimension) to sprinkle.mask,
        ],
        columns = 3,
        cellSide = 280,
    ),
)
```

## 17. 1 — Conway's Life, moving — launch me

<!-- field-box id="alife_life_moving" index="16" -->

### Narrative

From `code`

LAUNCH this box rather than merely running it (undo stops it). The last expression is a
`Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
the box's output every displayed frame, then holds the final frame forever.
Both boards are integer-upscaled to the same 384 pixels, so a cell is a block you can count:
twenty-four pixels for the glider's 16-cell torus, six for the R-pentomino's 64-cell walled
board. Small boards on purpose -- see the note above this clip, which measures why.

```edn [field-box id="alife_life_moving" role="properties"]
{"id" "alife_life_moving" "index" 16 "name" "1 — Conway's Life, moving — launch me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 — Conway's Life, moving — launch me" "_tombstone" false "frame" #afield/rect [60.0 760.0 360.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_life_moving_code_.kt" "lines" 29 "characters" 1415 "sha256" "f2fed5387bde004315454bbc6eb9c060df631a0f43ae3c51e80ecabc9033e475"}]}
```

```kotlin [field-property box="alife_life_moving" name="code" path="alife_life_moving_code_.kt"]
/*
LAUNCH this box rather than merely running it (undo stops it). The last expression is a
`Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
the box's output every displayed frame, then holds the final frame forever.
Both boards are integer-upscaled to the same 384 pixels, so a cell is a block you can count:
twenty-four pixels for the glider's 16-cell torus, six for the R-pentomino's 64-cell walled
board. Small boards on purpose -- see the note above this clip, which measures why.
*/
fun boards(g: IntGrid, r: IntGrid, n: Int) = [
    "glider, gen " + n to g.toImg(2).upscaleNearest(24),
    "R-pent " + n + ": " + r.population() + " live" to r.toImg(2).upscaleNearest(6),
]

/*
400 generations over 200 displayed frames: TWO generations per frame. Slower than every other
clip on this page on purpose -- the glider's period is FOUR generations, and a spaceship stepped
nine generations at a time is a strobe rather than a spaceship.
*/
animateEvolution(
    LifePatterns.glider.centeredOn(16, 16) to LifePatterns.rPentomino.centeredOn(64, 64, wrap = false),
    totalSteps = 400,
    seconds = 7.0,
    framesTarget = 200,
    columns = 2,
    cellSide = 384,
    step = { (g, r) -> g.step(LIFE) to r.step(LIFE) },
    panels = { (g, r), n -> boards(g, r, n) },
)
```

## 18. 4 — Gray-Scott: 6000 steps, ramped — launch me

<!-- field-box id="alife_gray_scott_moving" index="17" -->

### Narrative

From `code`

LAUNCH this box rather than merely running it (undo stops it). The last expression is a
`Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
the box's output every displayed frame, then holds the final frame forever.
The autocatalyst field on a FIXED 0..0.5 magma range. Auto-ranging per frame is what makes an
animation of a growing pattern appear to stand still, and it is the single easiest way to turn
a reaction-diffusion clip into a lie.

```edn [field-box id="alife_gray_scott_moving" role="properties"]
{"id" "alife_gray_scott_moving" "index" 17 "name" "4 — Gray-Scott: 6000 steps, ramped — launch me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 — Gray-Scott: 6000 steps, ramped — launch me" "_tombstone" false "frame" #afield/rect [520.0 760.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_gray_scott_moving_code_.kt" "lines" 27 "characters" 1344 "sha256" "fbe1a46bb85e77c158462faa8f035eaa8cff132743c0239e75195f826871192f"}]}
```

```kotlin [field-property box="alife_gray_scott_moving" name="code" path="alife_gray_scott_moving_code_.kt"]
/*
LAUNCH this box rather than merely running it (undo stops it). The last expression is a
`Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
the box's output every displayed frame, then holds the final frame forever.
The autocatalyst field on a FIXED 0..0.5 magma range. Auto-ranging per frame is what makes an
animation of a growing pattern appear to stand still, and it is the single easiest way to turn
a reaction-diffusion clip into a lie.
*/
fun view(s: GrayScottState, n: Int) =
    ["maze - step " + n + " of 6000" to s.v.colormap("magma", 0f, 0.5f).upscaleNearest(3)]

/*
The `maze` preset, the same 6000 steps the figure above measures, over 140 displayed frames --
but NOT 43 steps per frame. `ramp = 40` makes the last displayed frame advance forty times as
many steps as the first, which is the schedule a log-time process needs: the mid-point of the
clip is step 819 rather than step 3000. This is the number to change if you disagree.
*/
animateEvolution(
    grayScottSeed(128, 128, patches = 6, patchSize = 14, seed = 11L),
    totalSteps = 6000,
    seconds = 7.0,
    ramp = 40.0,
    cellSide = 420,
    step = { it.step(GRAY_SCOTT_MAZE) },
    panels = { s, n -> view(s, n) },
)
```

## 19. 6 — Boids: order above the random baseline

<!-- field-box id="alife_boids" index="18" -->

### Narrative

From `code`

Reynolds' boids on a torus. A flock is not "dots that move together"; it is a population whose
alignment order parameter is far above what independent headings would give, AND whose
nearest-neighbour distribution has a floor. Both are measured against the same initial state run
with one weight zeroed -- so the difference is attributable to that weight and nothing else.

```edn [field-box id="alife_boids" role="properties"]
{"id" "alife_boids" "index" 18 "name" "6 — Boids: order above the random baseline" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "6 — Boids: order above the random baseline" "_tombstone" false "frame" #afield/rect [520.0 460.0 390.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_boids_code_.kt" "lines" 45 "characters" 1813 "sha256" "5ecc84841a03ef51632d251a2586a27444072e369be6a495b69b0f4f6487c5db"}]}
```

```kotlin [field-property box="alife_boids" name="code" path="alife_boids_code_.kt"]
/*
Reynolds' boids on a torus. A flock is not "dots that move together"; it is a population whose
alignment order parameter is far above what independent headings would give, AND whose
nearest-neighbour distribution has a floor. Both are measured against the same initial state run
with one weight zeroed -- so the difference is attributable to that weight and nothing else.
*/
val n = 400
val start = BoidFlock.random(n, 260.0, 260.0, speed = 1.4, seed = 4L)
val params = BoidParams()
println("random-heading baseline for " + n + " agents: " + "%.3f".format(randomPolarizationBaseline(n)))
println("initial polarization: " + "%.3f".format(start.order()))

val flock = start.copy()
flock.stepTimes(params, 900)
val noAlign = start.copy()
noAlign.stepTimes(params.copy(alignmentWeight = 0.0), 900)
val noSep = start.copy()
noSep.stepTimes(params.copy(separationWeight = 0.0), 900)

println(
    "flock polarization " +
        "%.3f".format(flock.order()) +
        ", CONTROL with no alignment " +
        "%.3f".format(noAlign.order()),
)
println(
    "5th-percentile nearest neighbour: flock " +
        "%.2f".format(quantileOf(flock.neighborDistances(), 0.05)) +
        ", CONTROL with no separation " +
        "%.2f".format(quantileOf(noSep.neighborDistances(), 0.05)),
)
println("mean speed " + "%.2f".format(flock.meanSpeed()) + " of a " + params.maxSpeed + " cap")

showImg(
    montage(
        [
            "gen 0: order " + "%.2f".format(start.order()) to start.toImg(1.4, tail = 5.0),
            "900 steps: " + "%.2f".format(flock.order()) to flock.toImg(1.4, tail = 5.0),
            "no alignment: " + "%.2f".format(noAlign.order()) to noAlign.toImg(1.4, tail = 5.0),
            "no separation" to noSep.toImg(1.4, tail = 5.0),
        ],
        columns = 2,
        cellSide = 300,
    ),
)
```

## 20. 10 — A field and its contours, live — launch me

<!-- field-box id="alife_bridges_moving" index="19" -->

### Narrative

From `code`

LAUNCH this box rather than merely running it (undo stops it). The last expression is a
`Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
the box's output every displayed frame, then holds the final frame forever.
The contour panel is framed by the FIELD's extent -- two invisible single-node FLines at the
corners of the 96x96 sample grid, in the contour extractor's own pixel coordinates. Framed by
the contours' own bounds instead, the early frames (two loops in one corner) would be drawn
zoomed right in and the whole figure would zoom out as the pattern spread.

```edn [field-box id="alife_bridges_moving" role="properties"]
{"id" "alife_bridges_moving" "index" 19 "name" "10 — A field and its contours, live — launch me" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "10 — A field and its contours, live — launch me" "_tombstone" false "frame" #afield/rect [1440.0 760.0 420.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_bridges_moving_code_.kt" "lines" 33 "characters" 1628 "sha256" "7d7e9875f0787c196358706b227f02bbbf53e9a7afbfba9af5fcf45901875b26"}]}
```

```kotlin [field-property box="alife_bridges_moving" name="code" path="alife_bridges_moving_code_.kt"]
/*
LAUNCH this box rather than merely running it (undo stops it). The last expression is a
`Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
the box's output every displayed frame, then holds the final frame forever.
The contour panel is framed by the FIELD's extent -- two invisible single-node FLines at the
corners of the 96x96 sample grid, in the contour extractor's own pixel coordinates. Framed by
the contours' own bounds instead, the early frames (two loops in one corner) would be drawn
zoomed right in and the whole figure would zoom out as the pattern spread.
*/
val fieldFrame = [FLine().moveTo(0, 0), FLine().moveTo(95, 95)]
fun bridge(s: GrayScottState, n: Int): List<Pair<String, Img>> {
    val loops = s.v.isoContourFLines(0.25, cells = 96)
    return [
        "v field, step " + n to s.v.colormap("magma", 0f, 0.5f).upscaleNearest(4),
        "iso v=0.25: " + loops.size + " loops" to rasterizeFLinesFramed(loops, fieldFrame, 384, 384),
    ]
}

/*
A reaction-diffusion field and its iso-contour FLines, re-extracted afresh every frame -- so the
geometry tracks the field instead of being a snapshot of it. 5000 steps over 140 displayed
frames: 36 reaction-diffusion steps AND one marching-squares pass per frame.
*/
animateEvolution(
    grayScottSeed(96, 96, patches = 5, patchSize = 12, seed = 4L),
    totalSteps = 5000,
    seconds = 7.0,
    columns = 2,
    cellSide = 384,
    step = { it.step(GRAY_SCOTT_SPOTS) },
    panels = { s, n -> bridge(s, n) },
)
```

