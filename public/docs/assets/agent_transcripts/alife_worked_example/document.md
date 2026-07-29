# Field Document: alife_worked_example

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 10 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. 10 — Bridges out: FLines, trees, and a watertight mesh

<!-- field-box id="alife_bridges" index="0" -->

### Narrative

From `code`

What makes this family Field's rather than a generic ALife library: the bridges out into the
things Field already draws, meshes and exports.

(a) A reaction-diffusion field -> iso-contour FLines, through the marching-squares extractor in
`contours.kt` that was already here.

```edn [field-box id="alife_bridges" role="properties"]
{"id" "alife_bridges" "index" 0 "name" "10 — Bridges out: FLines, trees, and a watertight mesh" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "10 — Bridges out: FLines, trees, and a watertight mesh" "_tombstone" false "frame" #afield/rect [1140.0 60.0 340.0 190.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_bridges_code_.kt" "lines" 41 "characters" 2407 "sha256" "fd6e32ebdac388818a69e627ae4657f678145cecbacb73331432f5cb44eb5629"}]}
```

```kotlin [field-property box="alife_bridges" name="code" path="alife_bridges_code_.kt"]
// What makes this family Field's rather than a generic ALife library: the bridges out into the
// things Field already draws, meshes and exports.
//
// (a) A reaction-diffusion field -> iso-contour FLines, through the marching-squares extractor in
//     `contours.kt` that was already here.
val rd = grayScottSeed(96, 96, patches = 5, patchSize = 12, seed = 4L).stepTimes(GRAY_SCOTT_SPOTS, 5000)
val contours = rd.v.isoContourFLines(0.25, cells = 96)
println("Gray-Scott -> " + contours.size + " iso-contour FLine(s) at v = 0.25")

// (b) Space colonization -> a tree of FLines. Grown into an ANNULUS, which no upward-with-noise
//     heuristic reaches, so the branching really is following the space. isTree() checks the thing
//     a picture cannot show: one root, n-1 edges, no cycles.
val ring = annulusAttractors(1400, 150.0, 140.0, 60.0, 120.0, seed = 3L)
val tree = spaceColonization(ring, 150.0, 285.0, influenceRadius = 30.0, killRadius = 7.0, stepSize = 5.0)
println(tree)
println("is a tree (one root, n-1 edges, acyclic): " + tree.isTree())
println("CONTROL, no attractors: " + spaceColonization(emptyList(), 150.0, 285.0).nodeCount + " nodes")

// (c) A 3-D life-like automaton -> a TriMesh, through `mesh_sdf.kt`'s marching cubes. The claim
//     that matters for a mesh is that it is WATERTIGHT -- a leaking mesher looks fine and produces
//     a model that cannot be printed, booleaned or subdivided. Note an EMPTY mesh is vacuously
//     watertight, so the triangle count is part of the claim.
val volume = IntVolume.randomBall(48, 48, 48, density = 0.55, ballFraction = 0.70, seed = 8L)
    .stepTimes(LIFE3D_CLOUDS, 6)
println(volume)
val mesh = volume.toMesh(resolution = 96, smoothRadius = 1)
println("marched: " + mesh.triangleCount + " triangles, watertight = " + mesh.isWatertight() +
    ", Euler characteristic = " + mesh.eulerCharacteristic())

showImg(
    montage(
        listOf(
            "Gray-Scott field" to rd.v.colormap("magma", 0f, 0.5f).upscaleNearest(3),
            "its iso-contours as FLines" to rasterizeFLines(contours, 300, 300),
            "space colonization tree" to rasterizeFLines(tree.toFLines(), 300, 300),
            "3-D CA: z slice" to volume.slice(24).toImg(2).upscaleNearest(5),
            "3-D CA: depth projection" to volume.depthProjection().colormap("viridis").upscaleNearest(5)
        ),
        columns = 3, cellSide = 280
    )
)
```

## 2. 8 — DLA: a fractal dimension, and two controls at 2

<!-- field-box id="alife_dla" index="1" -->

### Narrative

From `code`

Diffusion-limited aggregation: a seed, then particles released far away that random-walk until
they touch and stick. Nothing in the rule mentions branches.

This is the one system here with a quantitative number to hit, so it gets the sharpest evidence:
the mass-radius dimension. An Eden cluster (same neighbourhood, growth site chosen uniformly
instead of by a diffusing walker) and a uniform sprinkle both have dimension 2, so those two
controls show the estimator is reading the diffusion-limited part and not merely "growth".

```edn [field-box id="alife_dla" role="properties"]
{"id" "alife_dla" "index" 1 "name" "8 — DLA: a fractal dimension, and two controls at 2" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "8 — DLA: a fractal dimension, and two controls at 2" "_tombstone" false "frame" #afield/rect [780.0 260.0 320.0 170.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_dla_code_.kt" "lines" 31 "characters" 1755 "sha256" "124b7434eaf85f0b1aed60127605c180463eca9d9b3e8e7c9b0890bc3690c05c"}]}
```

```kotlin [field-property box="alife_dla" name="code" path="alife_dla_code_.kt"]
// Diffusion-limited aggregation: a seed, then particles released far away that random-walk until
// they touch and stick. Nothing in the rule mentions branches.
//
// This is the one system here with a quantitative number to hit, so it gets the sharpest evidence:
// the mass-radius dimension. An Eden cluster (same neighbourhood, growth site chosen uniformly
// instead of by a diffusing walker) and a uniform sprinkle both have dimension 2, so those two
// controls show the estimator is reading the diffusion-limited part and not merely "growth".
val side = 300
val dla = dlaCluster(side, side, particles = 6000, seed = 2L)
val fit = dla.fractalDimension(rMin = 5.0, rMaxFraction = 0.75)
println(dla)
println("DLA mass dimension " + "%.3f".format(fit.dimension) + " (r2 = " + "%.4f".format(fit.rSquared) + ")")

val eden = edenCluster(side, side, particles = dla.points.size, seed = 2L)
val edenFit = eden.fractalDimension(rMin = 5.0, rMaxFraction = 0.75)
println("CONTROL Eden dimension " + "%.3f".format(edenFit.dimension))
val sprinkle = sprinkleCluster(side, side, dla.points.size, dla.maxRadius, seed = 2L)
val sprinkleFit = massRadiusDimension(sprinkle.points, sprinkle.center,
    rMin = 5.0, rMax = sprinkle.maxRadius * 0.75, minCount = 60)
println("CONTROL uniform sprinkle dimension " + "%.3f".format(sprinkleFit.dimension) + " (exactly 2 by construction)")

showImg(
    montage(
        listOf(
            "DLA: D = " + "%.2f".format(fit.dimension) to dla.arrivalOrderImg().colormap("turbo"),
            "Eden: D = " + "%.2f".format(edenFit.dimension) to eden.arrivalOrderImg().colormap("turbo"),
            "sprinkle: D = " + "%.2f".format(sprinkleFit.dimension) to sprinkle.mask
        ),
        columns = 3, cellSide = 280
    )
)
```

## 3. 2 — Elementary CA against an independent oracle

<!-- field-box id="alife_elementary" index="2" -->

### Narrative

From `code`

Wolfram's elementary rules, and the sharpest evidence in the family: rule 90 from a single seed
IS Pascal's triangle mod 2, and `sierpinskiReference` computes that from Kummer's theorem
without running a cellular automaton at all. The equality is a cross-check between two
unrelated computations rather than a stepper agreeing with itself.

```edn [field-box id="alife_elementary" role="properties"]
{"id" "alife_elementary" "index" 2 "name" "2 — Elementary CA against an independent oracle" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 — Elementary CA against an independent oracle" "_tombstone" false "frame" #afield/rect [60.0 260.0 320.0 170.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_elementary_code_.kt" "lines" 39 "characters" 2095 "sha256" "71e40cc7b9c2917891c508227f48815a5c406adffcf58c4e12421be25fafe9f0"}]}
```

```kotlin [field-property box="alife_elementary" name="code" path="alife_elementary_code_.kt"]
// Wolfram's elementary rules, and the sharpest evidence in the family: rule 90 from a single seed
// IS Pascal's triangle mod 2, and `sierpinskiReference` computes that from Kummer's theorem
// without running a cellular automaton at all. The equality is a cross-check between two
// unrelated computations rather than a stepper agreeing with itself.
val w = 257
val gens = 128
val rule90 = elementaryCa(90, w, gens)
val oracle = sierpinskiReference(w, gens)
var differing = 0
for (i in rule90.cells.indices) if (rule90.cells[i] != oracle.cells[i]) differing++
println("rule 90 vs Pascal mod 2: " + differing + " differing cells of " + rule90.cells.size)

// A second, independent oracle: row g of that diagram holds exactly 2^(popcount g) live cells.
val rows = (0 until gens).map { r -> (0 until w).count { rule90.cells[r * w + it] == 1 } }
println("row populations are exactly 2^popcount(generation): " +
    (rows == (0 until gens).map { 1 shl Integer.bitCount(it) }))

// Rule 184 is the traffic rule: it conserves the number of 1s exactly, forever.
val seedRow = IntArray(257) { if ((it * 7919) % 3 == 0) 1 else 0 }
val traffic = elementaryCa(184, 257, 200, seedRow)
val pops = (0 until 200).map { r -> (0 until 257).count { traffic.cells[r * 257 + it] == 1 } }
println("rule 184 distinct row populations over 200 generations: " + pops.distinct())
println("rule 90 distinct row populations: " + rows.distinct().size + " values, so NOT conserved")

val col30 = elementaryCa(30, 601, 600).centerColumn()
println("rule 30 centre column: " + col30.count { it == 1 } + "/600 ones, longest run " +
    longestRunLength(col30) + " (rule 90's is " + longestRunLength(rule90.centerColumn()) + ")")

showImg(
    montage(
        listOf(
            "rule 90 = Pascal mod 2" to rule90.toImg(2),
            "rule 30" to elementaryCa(30, 257, 128).toImg(2),
            "rule 110 (universal)" to elementaryCa(110, 257, 128, IntArray(257) { if (it % 13 == 0) 1 else 0 }).toImg(2),
            "rule 184 (density-conserving)" to traffic.toImg(2)
        ),
        columns = 2, cellSide = 300
    )
)
```

## 4. 4 — Gray-Scott: regimes told apart by measurement

<!-- field-box id="alife_gray_scott" index="3" -->

### Narrative

From `code`

Gray-Scott reaction-diffusion. Two coupled fields, two parameters, and the whole zoo of
morphologies -- spots, worms, mazes, holes -- is a function of those two numbers.

The point of `morphology()` is that a regime claim should be a claim about numbers. Spots and a
labyrinth can have identical coverage, identical mean and identical variance; what separates
them is the SHAPE of their features (isoperimetric ratio P^2/4piA, ~1 for a disc) and whether
one connected structure dominates.

```edn [field-box id="alife_gray_scott" role="properties"]
{"id" "alife_gray_scott" "index" 3 "name" "4 — Gray-Scott: regimes told apart by measurement" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 — Gray-Scott: regimes told apart by measurement" "_tombstone" false "frame" #afield/rect [420.0 60.0 320.0 170.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_gray_scott_code_.kt" "lines" 33 "characters" 1886 "sha256" "6583dbe428bd301ef7dd5856ac1450b4ebdb29a23b2e68c3fa67dcf7d357be86"}]}
```

```kotlin [field-property box="alife_gray_scott" name="code" path="alife_gray_scott_code_.kt"]
// Gray-Scott reaction-diffusion. Two coupled fields, two parameters, and the whole zoo of
// morphologies -- spots, worms, mazes, holes -- is a function of those two numbers.
//
// The point of `morphology()` is that a regime claim should be a claim about numbers. Spots and a
// labyrinth can have identical coverage, identical mean and identical variance; what separates
// them is the SHAPE of their features (isoperimetric ratio P^2/4piA, ~1 for a disc) and whether
// one connected structure dominates.
val side = 128
val steps = 6000
val panels = ArrayList<Pair<String, Img>>()
for ((label, p) in listOf(
    "spots 0.030/0.062" to GRAY_SCOTT_SPOTS,
    "worms 0.037/0.060" to GRAY_SCOTT_WORMS,
    "maze 0.029/0.057" to GRAY_SCOTT_MAZE,
    "holes 0.039/0.058" to GRAY_SCOTT_HOLES
)) {
    val end = grayScottSeed(side, side, patches = 6, patchSize = 14, seed = 11L).stepTimes(p, steps)
    println(label + " -> " + end.v.morphology())
    panels.add(label to end.v.colormap("magma", 0f, 0.5f).upscaleNearest(2))
}

// Two controls. v = 0 is a fixed point of the equations, so an unseeded field never patterns;
// and equal diffusion coefficients destroy the Turing instability entirely, which is Turing's
// own argument restated as an experiment.
val unseeded = grayScottSeed(side, side, patches = 0).stepTimes(GRAY_SCOTT_WORMS, 500)
println("CONTROL no seed: max autocatalyst after 500 steps = " + unseeded.v.max())
val equalD = grayScottSeed(side, side, patches = 6, patchSize = 14, seed = 11L)
    .stepTimes(GrayScottParams(feed = 0.037, kill = 0.060, du = 0.08, dv = 0.08), steps)
println("CONTROL Du == Dv -> " + equalD.v.morphology())
panels.add("CONTROL no seed" to unseeded.v.colormap("magma", 0f, 0.5f).upscaleNearest(2))
panels.add("CONTROL Du == Dv" to equalD.v.colormap("magma", 0f, 0.5f).upscaleNearest(2))

showImg(montage(panels, columns = 3, cellSide = 260))
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
{"id" "alife_cyclic" "index" 4 "name" "3 — Cyclic CA: self-organisation, measured" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 — Cyclic CA: self-organisation, measured" "_tombstone" false "frame" #afield/rect [60.0 460.0 320.0 170.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_cyclic_code_.kt" "lines" 35 "characters" 1972 "sha256" "5b7789d919dd23ff9af71184d0cadf952302087c00ffa9d6155fd509be087060"}]}
```

```kotlin [field-property box="alife_cyclic" name="code" path="alife_cyclic_code_.kt"]
// A cyclic cellular automaton: a cell in state s advances to s+1 as soon as any neighbour is
// already in s+1. Nothing can skip, so the only durable structures are rotating bands.
//
// The measurement that makes "it self-organised" checkable: in an UNCORRELATED soup a cell can
// advance exactly when one of its 8 Moore neighbours happens to hold its successor state, which
// is 1 - (1 - 1/states)^8 -- a closed form. Once the field has organised, every cell is driven by
// a neighbour and the advance fraction pins at 1.0, which no uncorrelated field does.
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
println("correlation length: labyrinth " + "%.2f".format(settled.toImg(rule.states).correlationLength(maxLag = 30)) +
    ", excitable " + "%.2f".format(exSettled.toImg(ex.states).correlationLength(maxLag = 30)))

val palette = cyclicPalette(rule.states)
showImg(
    montage(
        listOf(
            "gen 0 (soup)" to soup.toImg(palette),
            "gen 40" to soup.stepTimes(rule, 40).toImg(palette),
            "gen 400" to settled.toImg(palette),
            "excitable, gen 200" to exSettled.toImg(cyclicPalette(ex.states))
        ),
        columns = 2, cellSide = 280
    )
)
```

## 6. 5 — Lenia: exact in its discrete limit

<!-- field-box id="alife_lenia" index="5" -->

### Narrative

From `code`

Lenia is a CONTINUOUS cellular automaton: one field in 0..1, one convolution kernel, one growth
mapping, and A <- clamp(A + dt * G(K * A)).

The claim worth making about an implementation of it is a cross-check. With a uniform Moore ring
kernel, dt = 1 and a three-valued step growth mapping, Lenia reduces EXACTLY to an
outer-totalistic B/S rule -- so this float convolution and the integer Life stepper in
`alife_ca.kt` must agree bit for bit. `leniaFromLifeRule` builds that limit.

```edn [field-box id="alife_lenia" role="properties"]
{"id" "alife_lenia" "index" 5 "name" "5 — Lenia: exact in its discrete limit" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 — Lenia: exact in its discrete limit" "_tombstone" false "frame" #afield/rect [420.0 260.0 320.0 170.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_lenia_code_.kt" "lines" 43 "characters" 2553 "sha256" "ebbd1091a8346d01a4055ce716b09c9e5acc3b68893bc4a02874d6aa84d79979"}]}
```

```kotlin [field-property box="alife_lenia" name="code" path="alife_lenia_code_.kt"]
// Lenia is a CONTINUOUS cellular automaton: one field in 0..1, one convolution kernel, one growth
// mapping, and A <- clamp(A + dt * G(K * A)).
//
// The claim worth making about an implementation of it is a cross-check. With a uniform Moore ring
// kernel, dt = 1 and a three-valued step growth mapping, Lenia reduces EXACTLY to an
// outer-totalistic B/S rule -- so this float convolution and the integer Life stepper in
// `alife_ca.kt` must agree bit for bit. `leniaFromLifeRule` builds that limit.
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
println("the glider the float stepper carried moved by " +
    translationBetween(LifePatterns.glider.centeredOn(32, 32), field.toIntGrid(0.5f)))

// Control: move the growth window by one neighbour count and nothing else.
println("CONTROL, growth window at B4/S234: difference " +
    fieldMaxAbsDiff(LifePatterns.glider.centeredOn(32, 32).toImg(2).leniaTimes(leniaFromLifeRule(lifeRule("B4/S234")), 20), grid.toImg(2)))

// The continuous regime, reported as a measured phase trichotomy rather than as a named creature:
// this file ships no published Lenia initial pattern, so it claims none.
val panels = ArrayList<Pair<String, Img>>()
panels.add("discrete limit == Life" to field.upscaleNearest(8))
panels.add("ring kernel R=8" to LeniaPresets.ring(0.15, radius = 8).kernelImg().normalized().upscaleNearest(10))
for (mu in listOf(0.06, 0.14)) {
    val r = LeniaPresets.ring(mu, sigma = 0.017, radius = 8, dt = 0.12)
    val run = leniaRun(leniaSeed(96, 96, r, blobRadii = 1.6, seed = 9L), r, steps = 240)
    println("mu = " + mu + " -> " + run)
    panels.add("mu=" + mu + " " + run.phase.name.lowercase() to run.field.colormap("inferno", 0f, 1f).upscaleNearest(3))
}
val wide = LeniaPresets.ring(0.60, sigma = 0.60, radius = 8, dt = 0.12)
val sat = leniaRun(leniaSeed(96, 96, wide, blobRadii = 1.6, seed = 9L), wide, steps = 240)
println("a growth bell positive over the whole unit interval -> " + sat)
panels.add("wide sigma: saturation" to sat.field.colormap("inferno", 0f, 1f).upscaleNearest(3))

showImg(montage(panels, columns = 3, cellSide = 280))
```

## 7. 9 — L-systems: exact combinatorial identities

<!-- field-box id="alife_lsystem" index="6" -->

### Narrative

From `code`

L-systems are the one generative system in this family whose output can be checked exactly and
combinatorially rather than statistically -- so they carry the sharpest evidence here.

```edn [field-box id="alife_lsystem" role="properties"]
{"id" "alife_lsystem" "index" 6 "name" "9 — L-systems: exact combinatorial identities" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "9 — L-systems: exact combinatorial identities" "_tombstone" false "frame" #afield/rect [780.0 460.0 320.0 170.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_lsystem_code_.kt" "lines" 41 "characters" 2754 "sha256" "b7165575f2c874c473adb6ceaa0aa045b6056278dbba28b7c7da9732913aff29"}]}
```

```kotlin [field-property box="alife_lsystem" name="code" path="alife_lsystem_code_.kt"]
// L-systems are the one generative system in this family whose output can be checked exactly and
// combinatorially rather than statistically -- so they carry the sharpest evidence here.
println("algae derivation lengths (Fibonacci): " + LSYSTEM_ALGAE.derivations(9).map { it.length })
println("Koch snowflake forward symbols (3 * 4^n): " +
    (0..5).map { gen -> LSYSTEM_KOCH_SNOWFLAKE.derive(gen).count { it == 'F' } })

// And the turtle: the path CLOSES, its drawn length quadruples exactly at constant step size, and
// its enclosed area matches the closed form 9^n * A0 * (1 + 3/5(1 - (4/9)^n)). FLine stores
// single-precision coordinates, so "exact" here means to float precision.
val step = 1.0
val tp = TurtleParams(angle = Math.PI / 3.0, step = step)
val gens = (0..5).map { drawLSystem(LSYSTEM_KOCH_SNOWFLAKE, it, tp) }
println("worst closure residual: " + gens.map { turtleClosureError(it, step) }.max() + " of a step")
val lens = gens.map { flineTotalLength(it.lines) }
println("length ratio per generation: " + (1..5).map { "%.6f".format(lens[it] / lens[it - 1]) })
println("area, relative error against the closed form: " + (0..5).map { gen ->
    val a0 = Math.sqrt(3.0) / 4.0 * step * step
    val predicted = Math.pow(9.0, gen.toDouble()) * a0 * (1.0 + 0.6 * (1.0 - Math.pow(4.0 / 9.0, gen.toDouble())))
    "%.1e".format(Math.abs(Math.abs(flineSignedArea(gens[gen].lines)) - predicted) / predicted)
})
// Control: one wrong turn and none of that holds.
println("CONTROL, turn angle perturbed by 0.01 rad: open by " +
    "%.1f".format(turtleClosureError(drawLSystem(LSYSTEM_KOCH_SNOWFLAKE, 4, tp.copy(angle = Math.PI / 3.0 + 0.01)), step)) + " steps")

// Stochastic productions vary the TOPOLOGY, not merely the coordinates.
val plants = (1L..3L).map { drawLSystem(LSYSTEM_STOCHASTIC_PLANT, 5, TurtleParams(angle = Math.toRadians(24.0), step = 4.0), seed = it) }
println("three seeds of the stochastic plant give branch counts " + plants.map { it.lines.size })

// The arrowhead curve draws on A and B, not F -- with the default alphabet it renders nothing.
val arrow = drawLSystem(LSYSTEM_SIERPINSKI_ARROWHEAD, 7, TurtleParams(angle = Math.PI / 3.0, drawSymbols = "AB"))
println("arrowhead, generation 7: " + arrow.drawnSegments + " drawn segments (3^7 = 2187)")

showImg(
    montage(
        listOf("Koch snowflake gen 4" to rasterizeFLines(gens[4].lines, 300, 300),
            "arrowhead gen 7" to rasterizeFLines(arrow.lines, 300, 300),
            "dragon gen 12" to rasterizeFLines(drawLSystem(LSYSTEM_DRAGON, 12, TurtleParams(angle = Math.PI / 2.0)).lines, 300, 300)) +
            plants.mapIndexed { i, pl -> "plant seed " + (i + 1) to rasterizeFLines(pl.lines, 240, 300) },
        columns = 3, cellSide = 280
    )
)
```

## 8. 1 — Conway's Life against known objects

<!-- field-box id="alife_life" index="7" -->

### Narrative

From `code`

Conway's Life. Every claim here is about an object that predates this code, stated as an
integer-grid equality rather than as an observation about a picture.

```edn [field-box id="alife_life" role="properties"]
{"id" "alife_life" "index" 7 "name" "1 — Conway's Life against known objects" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 — Conway's Life against known objects" "_tombstone" false "frame" #afield/rect [60.0 60.0 320.0 170.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_life_code_.kt" "lines" 39 "characters" 2092 "sha256" "4fead2903de276187c0176416a5a4fa3ac460afb355930e1068df2fc09306924"}]}
```

```kotlin [field-property box="alife_life" name="code" path="alife_life_code_.kt"]
// Conway's Life. Every claim here is about an object that predates this code, stated as an
// integer-grid equality rather than as an observation about a picture.
val glider = LifePatterns.glider.centeredOn(40, 40)
println("glider, 4 generations: rigid translation = " + translationBetween(glider, glider.stepTimes(LIFE, 4)))
println("glider, 8 generations: rigid translation = " + translationBetween(glider, glider.stepTimes(LIFE, 8)))

// The control: one birth count away and the same seed is not a spaceship at all.
println("same seed under B34/S23, 4 generations: " +
    translationBetween(glider, glider.stepTimes(lifeRule("B34/S23"), 4)))

val block = LifePatterns.block.centeredOn(20, 20)
println("block is bit-identical after one step: " + block.step(LIFE).sameCellsAs(block))
println("blinker's detected period: " + LifePatterns.blinker.centeredOn(20, 20).runUntilCycle(LIFE, 20).period)
println("toad's detected period: " + LifePatterns.toad.centeredOn(20, 20).runUntilCycle(LIFE, 20).period)

// The R-pentomino. The textbook figures -- peak 319 at generation 821, then 116 cells from
// generation 1103 -- are facts about an UNBOUNDED board: six of those 116 cells are gliders still
// in flight. So it needs room. 620 wide gives every glider more than the 1103/4 cells it can
// travel, and liveBounds() confirms nothing reached an edge rather than assuming it.
var g = LifePatterns.rPentomino.centeredOn(620, 620, wrap = false)
var peak = 0
var peakAt = 0
for (gen in 1..1103) {
    g = g.step(LIFE)
    val pop = g.population()
    if (pop > peak) { peak = pop; peakAt = gen }
}
val b = g.liveBounds()!!
println("R-pentomino peak population " + peak + " at generation " + peakAt)
println("population at generation 1103 = " + g.population() +
    ", live cells " + b[0] + " clear of the board edge")

showImg(
    montage(
        (0..4).map { "gen " + it to LifePatterns.glider.centeredOn(16, 16).stepTimes(LIFE, it).toImg(2).upscaleNearest(12) },
        columns = 5, cellSide = 200
    ),
    caption = "one glider, five generations: the same shape displaced by (1, 1)"
)
```

## 9. 6 — Boids: order above the random baseline

<!-- field-box id="alife_boids" index="8" -->

### Narrative

From `code`

Reynolds' boids on a torus. A flock is not "dots that move together"; it is a population whose
alignment order parameter is far above what independent headings would give, AND whose
nearest-neighbour distribution has a floor. Both are measured against the same initial state run
with one weight zeroed -- so the difference is attributable to that weight and nothing else.

```edn [field-box id="alife_boids" role="properties"]
{"id" "alife_boids" "index" 8 "name" "6 — Boids: order above the random baseline" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "6 — Boids: order above the random baseline" "_tombstone" false "frame" #afield/rect [420.0 460.0 320.0 170.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_boids_code_.kt" "lines" 31 "characters" 1763 "sha256" "4b35a2b651255fecf3edb6650edce36da6b94ab7e24c52ee27368351b2fea90c"}]}
```

```kotlin [field-property box="alife_boids" name="code" path="alife_boids_code_.kt"]
// Reynolds' boids on a torus. A flock is not "dots that move together"; it is a population whose
// alignment order parameter is far above what independent headings would give, AND whose
// nearest-neighbour distribution has a floor. Both are measured against the same initial state run
// with one weight zeroed -- so the difference is attributable to that weight and nothing else.
val n = 400
val start = BoidFlock.random(n, 260.0, 260.0, speed = 1.4, seed = 4L)
val params = BoidParams()
println("random-heading baseline for " + n + " agents: " + "%.3f".format(randomPolarizationBaseline(n)))
println("initial polarization: " + "%.3f".format(start.order()))

val flock = start.copy(); flock.stepTimes(params, 900)
val noAlign = start.copy(); noAlign.stepTimes(params.copy(alignmentWeight = 0.0), 900)
val noSep = start.copy(); noSep.stepTimes(params.copy(separationWeight = 0.0), 900)

println("flock polarization " + "%.3f".format(flock.order()) +
    ", CONTROL with no alignment " + "%.3f".format(noAlign.order()))
println("5th-percentile nearest neighbour: flock " + "%.2f".format(quantileOf(flock.neighborDistances(), 0.05)) +
    ", CONTROL with no separation " + "%.2f".format(quantileOf(noSep.neighborDistances(), 0.05)))
println("mean speed " + "%.2f".format(flock.meanSpeed()) + " of a " + params.maxSpeed + " cap")

showImg(
    montage(
        listOf(
            "gen 0: order " + "%.2f".format(start.order()) to start.toImg(1.4, tail = 5.0),
            "900 steps: " + "%.2f".format(flock.order()) to flock.toImg(1.4, tail = 5.0),
            "no alignment: " + "%.2f".format(noAlign.order()) to noAlign.toImg(1.4, tail = 5.0),
            "no separation" to noSep.toImg(1.4, tail = 5.0)
        ),
        columns = 2, cellSide = 300
    )
)
```

## 10. 7 — Physarum: a network the code never mentions

<!-- field-box id="alife_physarum" index="9" -->

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
{"id" "alife_physarum" "index" 9 "name" "7 — Physarum: a network the code never mentions" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "7 — Physarum: a network the code never mentions" "_tombstone" false "frame" #afield/rect [780.0 60.0 320.0 170.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "alife_physarum_code_.kt" "lines" 28 "characters" 1398 "sha256" "bef69a82693e988cb9ffa290624d0d8dad485825076a394723d41b4ced3d83e4"}]}
```

```kotlin [field-property box="alife_physarum" name="code" path="alife_physarum_code_.kt"]
// Jones' Physarum model: agents deposit into a trail field, sense it three ways ahead, turn toward
// the strongest, move. The transport network is nowhere in the code -- it is the fixed point of
// that loop.
//
// The measurement: `topShare(0.05)` is the fraction of all deposit held by the brightest 5% of
// pixels. A flat field gives 0.05. A network concentrates material into filaments and gives much
// more. The control is the identical population with sensing switched off -- same count, same
// seed, same deposit, same decay, random turns.
val side = 256
val p = PhysarumParams()
val sensing = PhysarumSwarm.disc(side, side, 6000, 0.55, seed = 6L).stepTimes(p, 400, seed = 6L)
val blind = PhysarumSwarm.disc(side, side, 6000, 0.55, seed = 6L)
    .stepTimes(p.copy(sensing = false), 400, seed = 6L)

println("top-5% deposit share: sensing " + "%.3f".format(sensing.trail.topShare(0.05)) +
    ", CONTROL sensing off " + "%.3f".format(blind.trail.topShare(0.05)) + " (a flat field gives 0.05)")
println("sensing morphology: " + sensing.trail.normalized().morphology())
println("blind   morphology: " + blind.trail.normalized().morphology())

showImg(
    montage(
        listOf(
            "sensing" to sensing.trail.normalized().colormap("magma"),
            "CONTROL: sensing off" to blind.trail.normalized().colormap("magma")
        ),
        columns = 2, cellSide = 340
    )
)
```

