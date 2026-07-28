# Field Document: wfc_worked_example

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 7 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "fragment" "stackSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "ai4_auto_name" "ai4_auto_name_signature" "fileManagerAccessHistory"]}
```

## 1. 7 — Not pixels: WFC on a scalar field

<!-- field-box id="wfc_scalar_field" index="0" -->

### Narrative

From `code`

Section 6: the solver never sees a pixel. sliceIntoUniqueTiles2D and
sliceIntoOverlappingPatterns2D learn from a row-major FloatArray, so the same machinery
synthesises numbers -- and numbers in Field are geometry waiting to happen.

```edn [field-box id="wfc_scalar_field" role="properties"]
{"id" "wfc_scalar_field" "index" 0 "name" "7 — Not pixels: WFC on a scalar field" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "7 — Not pixels: WFC on a scalar field" "_tombstone" false "frame" #afield/rect [860.0 60.0 320.0 150.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "wfc_scalar_field_code_.kt" "lines" 92 "characters" 4513 "sha256" "f566135146d9cea93d3c447d3c72329a89a7c3531c8223f4fe8363cd4eba607d"}]}
```

```kotlin [field-property box="wfc_scalar_field" name="code" path="wfc_scalar_field_code_.kt"]
// Section 6: the solver never sees a pixel. sliceIntoUniqueTiles2D and
// sliceIntoOverlappingPatterns2D learn from a row-major FloatArray, so the same machinery
// synthesises numbers -- and numbers in Field are geometry waiting to happen.
fun bump(u: Double, v: Double, cx: Double, cy: Double, r: Double): Double {
    val dx = u - cx
    val dy = v - cy
    return exp(-(dx * dx + dy * dy) / (2.0 * r * r))
}

val sw = 26
val sh = 18
val levels = 6
val source = FloatArray(sw * sh)
for (cy in 0 until sh) for (cx in 0 until sw) {
    val u = cx / (sw - 1.0)
    val v = cy / (sh - 1.0)
    val e = (bump(u, v, 0.32, 0.42, 0.26) +
        0.85 * bump(u, v, 0.70, 0.60, 0.22) +
        0.50 * bump(u, v, 0.80, 0.24, 0.14)).coerceIn(0.0, 1.0)
    // Quantise: sliceIntoUniqueTiles2D dedupes on exact float equality, so a continuous field
    // would give one tile per distinct value instead of six.
    source[cx + cy * sw] = (Math.round(e * (levels - 1)).toDouble() / (levels - 1)).toFloat()
}

val tiles = source.sliceIntoUniqueTiles2D(sw, sh, tileWidth = 1, tileHeight = 1)
val patterns = source.sliceIntoOverlappingPatterns2D(sw, sh, 3, 3, wrapX = false, wrapY = false)

val outW = 40
val outH = 26

var lowestTile = 0
for (id in 0 until tiles.tileCount) {
    if (tiles.tiles[id][0] < tiles.tiles[lowestTile][0]) lowestTile = id
}

// The 1x1 model needs its border pinned or it settles on whichever level nucleates first.
val tiledSolver = WfcSolver(outW, outH, tiles.toRules(), seed = 5L)
tiledSolver.solveWithRetries(maxAttempts = 30) {
    for (x in 0 until outW) { forceTile(x, 0, lowestTile); forceTile(x, outH - 1, lowestTile) }
    for (y in 0 until outH) { forceTile(0, y, lowestTile); forceTile(outW - 1, y, lowestTile) }
}
// `throw`, not `error(...)`: a box has its own `error` in scope and it does not return Nothing.
val tiledGrid: IntArray = tiledSolver.resolvedTileGridOrNull()
    ?: throw IllegalStateException("1x1 model ended " + tiledSolver.status)
val tiledHeights: FloatArray = tiles.render(tiledGrid, outW, outH)

// The 3x3 model gets no pinning: pinning the whole border over-constrains it into a
// contradiction, and its windows already encode what a slope looks like.
val patternSolver = WfcSolver(outW, outH, patterns.toRules(), seed = 5L)
patternSolver.solveWithRetries(maxAttempts = 30)
val patternGrid: IntArray = patternSolver.resolvedTileGridOrNull()
    ?: throw IllegalStateException("3x3 model ended " + patternSolver.status)
val patternHeights: FloatArray = patterns.render(patternGrid, outW, outH)

// Mean absolute difference along rows vs down columns. The 1x1 result is isotropic speckle;
// the 3x3 result is smooth along rows and rough down them, i.e. horizontally banded.
fun roughness(values: FloatArray): String {
    var hs = 0.0
    var hn = 0
    var vs = 0.0
    var vn = 0
    for (y in 0 until outH) for (x in 0 until outW) {
        if (x + 1 < outW) { hs += abs(values[x + y * outW] - values[x + 1 + y * outW]); hn++ }
        if (y + 1 < outH) { vs += abs(values[x + y * outW] - values[x + (y + 1) * outW]); vn++ }
    }
    return "h=" + "%.3f".format(hs / hn) + " v=" + "%.3f".format(vs / vn)
}

println(tiles.tileCount.toString() + " levels, " + patterns.tileCount + " distinct 3x3 value patterns")
println("1x1 tiles:    mean " + "%.2f".format(tiledHeights.average()) + ", roughness " + roughness(tiledHeights))
println("3x3 patterns: mean " + "%.2f".format(patternHeights.average()) + ", roughness " + roughness(patternHeights))

// The payoff: iso-contours of the synthesised field are FLines, not a picture of them.
val smooth = floatsToImg(patternHeights, outW, outH).gaussianBlur(1.2)
val contours = ArrayList<afield.graphics.FLine>()
for (iso in listOf(0.15, 0.35, 0.55, 0.75)) {
    for (line in smooth.isoContourFLines(iso)) {
        line.color = vec(0.30 + iso * 0.65, 0.95 - iso * 0.45, 0.40 + iso * 0.45, 0.95)
        line.thickness = 1.6
        contours.add(line)
    }
}
println(contours.size.toString() + " iso-contour FLines across four levels")

showImgGrid(
    "source 26x18" to floatsToImg(source, sw, sh).colormap("viridis", lo = 0f, hi = 1f).upscaleNearest(20),
    "1x1 tiles, pinned" to floatsToImg(tiledHeights, outW, outH).colormap("viridis", lo = 0f, hi = 1f).upscaleNearest(13),
    "3x3 patterns" to floatsToImg(patternHeights, outW, outH).colormap("viridis", lo = 0f, hi = 1f).upscaleNearest(13),
    columns = 1,
    cellSide = 640
)
lines(contours, channel = "contours", w = 900.0, h = 560.0, fitToSize = true)
```

## 2. 2 — Simple-tiled, learned from an image

<!-- field-box id="wfc_simple_tiled" index="1" -->

### Narrative

From `code`

Section 2: learn tiles and adjacency from a source image -- and see what the learned
weights do NOT capture. Tile size 1x1, so a "tile" is just a colour and "learning the
rules" is literally "note which colours were seen touching".

```edn [field-box id="wfc_simple_tiled" role="properties"]
{"id" "wfc_simple_tiled" "index" 1 "name" "2 — Simple-tiled, learned from an image" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 — Simple-tiled, learned from an image" "_tombstone" false "frame" #afield/rect [60.0 260.0 300.0 150.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "wfc_simple_tiled_code_.kt" "lines" 84 "characters" 3379 "sha256" "080d5d2d9ea2b92fc9d510bb116ab3a243c7c6fa47f44a8669ea04a4550a0e19"}]}
```

```kotlin [field-property box="wfc_simple_tiled" name="code" path="wfc_simple_tiled_code_.kt"]
// Section 2: learn tiles and adjacency from a source image -- and see what the learned
// weights do NOT capture. Tile size 1x1, so a "tile" is just a colour and "learning the
// rules" is literally "note which colours were seen touching".
fun bump(u: Double, v: Double, cx: Double, cy: Double, r: Double): Double {
    val dx = u - cx
    val dy = v - cy
    return exp(-(dx * dx + dy * dy) / (2.0 * r * r))
}

val palette = listOf(
    Triple(0.08f, 0.20f, 0.31f),   // deep water
    Triple(0.18f, 0.48f, 0.66f),   // shallow water
    Triple(0.85f, 0.78f, 0.55f),   // sand
    Triple(0.31f, 0.55f, 0.24f),   // grass
    Triple(0.12f, 0.29f, 0.14f)    // forest
)

val sw = 26
val sh = 18
val bands = IntArray(sw * sh)
for (cy in 0 until sh) for (cx in 0 until sw) {
    val u = cx / (sw - 1.0)
    val v = cy / (sh - 1.0)
    val e = bump(u, v, 0.30, 0.40, 0.25) +
        0.90 * bump(u, v, 0.70, 0.62, 0.21) +
        0.55 * bump(u, v, 0.82, 0.22, 0.13) -
        0.62 * bump(u, v, 0.34, 0.44, 0.085)
    bands[cx + cy * sw] = if (e < 0.16) 0 else if (e < 0.32) 1 else if (e < 0.44) 2 else if (e < 0.74) 3 else 4
}
val source = Img.generateRGB(sw, sh) { x, y -> palette[bands[x + y * sw]] }

val tiles = source.toBufferedImage().sliceIntoUniqueTiles(1, 1)
val rules = tiles.toRules()

// Tile IDs are discovery order; sampleIds is row-major over the same grid, so one pass
// recovers which band each tile is.
val bandOfTile = IntArray(tiles.tileCount)
val tileOfBand = IntArray(palette.size)
for (i in bands.indices) {
    bandOfTile[tiles.sampleIds[i]] = bands[i]
    tileOfBand[bands[i]] = tiles.sampleIds[i]
}

val outW = 40
val outH = 26
fun solveWith(model: WfcRules): IntArray {
    val s = WfcSolver(outW, outH, model, seed = 12L)
    s.solveWithRetries(maxAttempts = 30)
    return s.resolvedTileGridOrNull()!!
}

val learned = solveWith(rules)

// rules.weights is a plain DoubleArray the solver reads live, so a learned model is editable.
// Boosting sand -- the only band that may touch both sides -- is what lets all five coexist.
val reweighted = tiles.toRules()
reweighted.weights[tileOfBand[0]] *= 2.0
reweighted.weights[tileOfBand[1]] *= 1.5
reweighted.weights[tileOfBand[2]] *= 10.0
val boosted = solveWith(reweighted)

val bandNames = listOf("deep", "shallow", "sand", "grass", "forest")

// Returns a String rather than printing: a named function that touches the script's
// implicit Box receiver (and println() in a box does) cannot be code-generated by K2 --
// "No mapping for symbol: VALUE_PARAMETER SCRIPT_IMPLICIT_RECEIVER". Keep helpers pure
// and do the printing at the top level, or declare the helper as `fun Box.foo()`.
fun histogram(cells: IntArray, bandOf: (Int) -> Int): String =
    palette.indices.joinToString("  ") { b ->
        bandNames[b] + " " + (cells.count { bandOf(it) == b } * 100 / cells.size) + "%"
    }

println("source   " + histogram(bands) { it })
println("learned  " + histogram(learned) { bandOfTile[it] })
println("boosted  " + histogram(boosted) { bandOfTile[it] })
println("the learned weights lose the water bands entirely -- this model is bistable")

showImgGrid(
    "source 26x18" to source.upscaleNearest(20),
    "learned weights" to tiles.toImg(learned, outW, outH).upscaleNearest(14),
    "sand boosted x10" to tiles.toImg(boosted, outW, outH).upscaleNearest(14),
    columns = 1,
    cellSide = 620
)
```

## 3. 5 — Neighborhoods: von Neumann vs Moore

<!-- field-box id="wfc_neighborhoods" index="2" -->

### Narrative

From `code`

Section 4: the neighborhood is a modelling decision, not a performance knob.
Diagonal stripes two cells wide: colour depends on (x + y) mod 4, so along the anti-diagonal
offset (1, -1) the colour never changes, while east and south change only half the time.

```edn [field-box id="wfc_neighborhoods" role="properties"]
{"id" "wfc_neighborhoods" "index" 2 "name" "5 — Neighborhoods: von Neumann vs Moore" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "5 — Neighborhoods: von Neumann vs Moore" "_tombstone" false "frame" #afield/rect [460.0 260.0 320.0 150.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "wfc_neighborhoods_code_.kt" "lines" 33 "characters" 1559 "sha256" "988cd00507085ce7e66d542dadef315f2a1dd0f3fcbd2fc4aaf3d7e52981ef0b"}]}
```

```kotlin [field-property box="wfc_neighborhoods" name="code" path="wfc_neighborhoods_code_.kt"]
// Section 4: the neighborhood is a modelling decision, not a performance knob.
// Diagonal stripes two cells wide: colour depends on (x + y) mod 4, so along the anti-diagonal
// offset (1, -1) the colour never changes, while east and south change only half the time.
val stripeA = Triple(0.17f, 0.25f, 0.45f)
val stripeB = Triple(0.91f, 0.71f, 0.29f)
val source = Img.generateRGB(24, 16) { x, y -> if ((x + y) % 4 < 2) stripeA else stripeB }
val tiles = source.toBufferedImage().sliceIntoUniqueTiles(1, 1)

fun solveStripes(neighborhood: WfcNeighborhood): Pair<Img, Int> {
    val rules = tiles.toRules(neighborhood = neighborhood)
    val solver = WfcSolver(40, 26, rules, seed = 5L)
    solver.solveWithRetries(maxAttempts = 30)
    val grid = solver.resolvedTileGridOrNull()!!
    var breaks = 0
    for (y in 1 until 26) for (x in 0 until 39) {
        if (grid[x + y * 40] != grid[(x + 1) + (y - 1) * 40]) breaks++
    }
    return tiles.toImg(grid, 40, 26) to breaks
}

val vn = solveStripes(WfcNeighborhood.VonNeumann)
val moore = solveStripes(WfcNeighborhood.Moore)

// von Neumann observes every cardinal pair and so learns nothing; Moore observes the one offset
// along which the colour is constant, and that alone pins the stripes exactly.
println("anti-diagonal breaks of 975 pairs -- von Neumann: " + vn.second + ", Moore: " + moore.second)
showImgGrid(
    "source 24x16" to source.upscaleNearest(12),
    "von Neumann (4)" to vn.first.upscaleNearest(7),
    "Moore (8)" to moore.first.upscaleNearest(7),
    columns = 3,
    cellSide = 340
)
```

## 4. 6 — Watching the collapse

<!-- field-box id="wfc_collapse" index="3" -->

### Narrative

From `code`

Section 5: step() is public and does exactly one WFC iteration, so the collapse is drawable.
waveToImg averages each cell's remaining possibilities -- undecided cells are a blur of what
they might become. possibilityCountImg is the remaining option count as a scalar field.

```edn [field-box id="wfc_collapse" role="properties"]
{"id" "wfc_collapse" "index" 3 "name" "6 — Watching the collapse" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "6 — Watching the collapse" "_tombstone" false "frame" #afield/rect [460.0 460.0 320.0 150.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "wfc_collapse_code_.kt" "lines" 63 "characters" 2571 "sha256" "b79c1afae17662ecb3af7eabf5c13e2628faad90fa02fe51b8009f1588e63d0b"}]}
```

```kotlin [field-property box="wfc_collapse" name="code" path="wfc_collapse_code_.kt"]
// Section 5: step() is public and does exactly one WFC iteration, so the collapse is drawable.
// waveToImg averages each cell's remaining possibilities -- undecided cells are a blur of what
// they might become. possibilityCountImg is the remaining option count as a scalar field.
fun bump(u: Double, v: Double, cx: Double, cy: Double, r: Double): Double {
    val dx = u - cx
    val dy = v - cy
    return exp(-(dx * dx + dy * dy) / (2.0 * r * r))
}
val palette = listOf(
    Triple(0.08f, 0.20f, 0.31f),
    Triple(0.18f, 0.48f, 0.66f),
    Triple(0.85f, 0.78f, 0.55f),
    Triple(0.31f, 0.55f, 0.24f),
    Triple(0.12f, 0.29f, 0.14f)
)
val bands = IntArray(26 * 18)
for (cy in 0 until 18) for (cx in 0 until 26) {
    val u = cx / 25.0
    val v = cy / 17.0
    val e = bump(u, v, 0.30, 0.40, 0.25) +
        0.90 * bump(u, v, 0.70, 0.62, 0.21) +
        0.55 * bump(u, v, 0.82, 0.22, 0.13) -
        0.62 * bump(u, v, 0.34, 0.44, 0.085)
    bands[cx + cy * 26] = if (e < 0.16) 0 else if (e < 0.32) 1 else if (e < 0.44) 2 else if (e < 0.74) 3 else 4
}
val source = Img.generateRGB(26, 18) { x, y -> palette[bands[x + y * 26]] }
val tiles = source.toBufferedImage().sliceIntoUniqueTiles(1, 1)
val rules = tiles.toRules()

// Section 2's boost, so the collapse has all five bands in it and is worth watching.
val tileOfBand = IntArray(palette.size)
for (i in bands.indices) tileOfBand[bands[i]] = tiles.sampleIds[i]
rules.weights[tileOfBand[0]] *= 2.0
rules.weights[tileOfBand[1]] *= 1.5
rules.weights[tileOfBand[2]] *= 10.0

val solver = WfcSolver(40, 28, rules, seed = 3L)

val waves = mutableListOf<Pair<String, Img>>()
val entropy = mutableListOf<Pair<String, Img>>()
fun sample() {
    val pct = (solver.resolvedFraction() * 100).toInt()
    waves.add(pct.toString() + "% decided" to tiles.waveToImg(solver).upscaleNearest(6))
    entropy.add(pct.toString() + "% decided" to
        solver.possibilityCountImg().colormap("magma", lo = 0f, hi = rules.tileCount.toFloat()).upscaleNearest(6))
}

sample()
var steps = 0
while (solver.step() == WfcStatus.Running) {
    steps++
    if (steps % 200 == 0) sample()
}
sample()

// A solved cell has exactly one possibility, so the last wave frame IS the finished output.
println(steps.toString() + " steps, " + waves.size + " sampled frames, ended " + solver.status)
println("magma is mapped with an explicit lo/hi so the colours mean the same thing in every frame")
display("main", vstack(
    montage(waves, columns = 4, cellSide = 260),
    montage(entropy, columns = 4, cellSide = 260),
    gap = 14
).toPngBytes())
```

## 5. 1 — The model, by hand

<!-- field-box id="wfc_by_hand" index="4" -->

### Narrative

From `code`

Section 1: the model, by hand. Three tile IDs and a table; nothing is learned.
Sand is the only tile allowed to touch both sea and grass, so a coastline is not
something the solver is told to draw -- it is what is left once a sea-grass edge is illegal.

```edn [field-box id="wfc_by_hand" role="properties"]
{"id" "wfc_by_hand" "index" 4 "name" "1 — The model, by hand" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 — The model, by hand" "_tombstone" false "frame" #afield/rect [60.0 60.0 300.0 150.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "wfc_by_hand_code_.kt" "lines" 57 "characters" 2117 "sha256" "94be24146dfa61e9352d0c0f8affe914605958bcfc717725b1c63bcf456c46ad"}]}
```

```kotlin [field-property box="wfc_by_hand" name="code" path="wfc_by_hand_code_.kt"]
// Section 1: the model, by hand. Three tile IDs and a table; nothing is learned.
// Sand is the only tile allowed to touch both sea and grass, so a coastline is not
// something the solver is told to draw -- it is what is left once a sea-grass edge is illegal.
val SEA = 0
val SAND = 1
val GRASS = 2

// allow(d, a, b) is directional: "b may sit east of a", and therefore "a may sit west of b".
// It does NOT say a may sit east of b, so an undirected "may touch" is both orders.
fun WfcRulesBuilder.mayTouch(a: Int, b: Int) {
    for (d in listOf(WFC_EAST, WFC_SOUTH)) {
        allow(d, a, b)
        allow(d, b, a)
    }
}

val rules = wfcRules(tileCount = 3) {
    weights(3.0, 1.0, 3.0)
    mayTouch(SEA, SEA)
    mayTouch(SEA, SAND)
    mayTouch(SAND, SAND)
    mayTouch(SAND, GRASS)
    mayTouch(GRASS, GRASS)
    // Deliberately absent: mayTouch(SEA, GRASS). That single omission is the whole model.
}

// Pin sea down the left edge and grass down the right, so the interface has to appear.
// reset() clears forced cells, so the pinning belongs in the prepare block where every
// retry re-applies it.
val solver = WfcSolver(width = 22, height = 14, rules = rules, seed = 12L)
val outcome = solver.solveWithRetries {
    for (row in 0 until 14) {
        forceTile(0, row, SEA)
        forceTile(21, row, GRASS)
    }
}
val grid = solver.resolvedTileGridOrNull()!!

val names = listOf("sea", "sand", "grass")
for (a in 0..2) {
    println("may sit east of " + names[a] + ": " +
        (0..2).filter { rules.allows(WFC_EAST, a, it) }.joinToString(", ") { names[it] })
}
println("solved in " + outcome.attempts + " attempt(s); symmetric=" + (rules.findAsymmetry() == null))
println("sea=" + grid.count { it == SEA } + " sand=" + grid.count { it == SAND } +
    " grass=" + grid.count { it == GRASS } + " of " + grid.size)

val palette = listOf(
    Triple(0.07f, 0.20f, 0.33f),
    Triple(0.85f, 0.78f, 0.55f),
    Triple(0.28f, 0.52f, 0.22f)
)
showImgGrid(
    "22x14 cells, seed 12" to Img.generateRGB(22, 14) { x, y -> palette[grid[x + y * 22]] }.upscaleNearest(20),
    columns = 1,
    cellSide = 560
)
```

## 6. 3 — Edge sockets: a tile set that connects

<!-- field-box id="wfc_circuit" index="5" -->

### Narrative

From `code`

Section 2b: the same simple-tiled solver, but the tiles carry art whose EDGES must line up.
Rules come from edge sockets rather than from a sample: b may sit east of a exactly when
a's east socket matches b's west socket. Every trace in the output therefore connects.

```edn [field-box id="wfc_circuit" role="properties"]
{"id" "wfc_circuit" "index" 5 "name" "3 — Edge sockets: a tile set that connects" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "3 — Edge sockets: a tile set that connects" "_tombstone" false "frame" #afield/rect [60.0 460.0 300.0 150.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "wfc_circuit_code_.kt" "lines" 53 "characters" 2607 "sha256" "cbb180801254378e591da21f1682bf177fef4d7b9b223584630c3262630df74b"}]}
```

```kotlin [field-property box="wfc_circuit" name="code" path="wfc_circuit_code_.kt"]
// Section 2b: the same simple-tiled solver, but the tiles carry art whose EDGES must line up.
// Rules come from edge sockets rather than from a sample: b may sit east of a exactly when
// a's east socket matches b's west socket. Every trace in the output therefore connects.
val sockets = listOf(
    intArrayOf(0, 0, 0, 0),   // blank
    intArrayOf(0, 1, 0, 1),   // horizontal
    intArrayOf(1, 0, 1, 0),   // vertical
    intArrayOf(1, 1, 0, 0),   // elbow N-E
    intArrayOf(0, 1, 1, 0),   // elbow E-S
    intArrayOf(0, 0, 1, 1),   // elbow S-W
    intArrayOf(1, 0, 0, 1),   // elbow W-N
    intArrayOf(1, 1, 1, 1)    // cross
)

val rules = wfcRules(tileCount = sockets.size) {
    weights(6.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0, 0.35)
    for (a in sockets.indices) for (b in sockets.indices) {
        if (sockets[a][WFC_EAST] == sockets[b][WFC_WEST]) allow(WFC_EAST, a, b)
        if (sockets[a][WFC_SOUTH] == sockets[b][WFC_NORTH]) allow(WFC_SOUTH, a, b)
    }
}

// A top-level fun, not one nested inside the Array(...) lambda below: a local fun declared
// inside a lambda in a Field box trips a K2 codegen assertion ("has no callableId").
val side = 11
val c = side / 2
val bg = 0xFF10241C.toInt()
val trace = 0xFF64E0A0.toInt()
val pad = 0xFFB8FFDA.toInt()

fun traceArt(s: IntArray): IntArray {
    val px = IntArray(side * side) { bg }
    if (s[WFC_NORTH] == 1) for (y in 0..c) { px[c + y * side] = trace; px[(c - 1) + y * side] = trace }
    if (s[WFC_SOUTH] == 1) for (y in c until side) { px[c + y * side] = trace; px[(c - 1) + y * side] = trace }
    if (s[WFC_WEST] == 1) for (x in 0..c) { px[x + c * side] = trace; px[x + (c - 1) * side] = trace }
    if (s[WFC_EAST] == 1) for (x in c until side) { px[x + c * side] = trace; px[x + (c - 1) * side] = trace }
    if (s.any { it == 1 }) for (y in (c - 1)..c) for (x in (c - 1)..c) px[x + y * side] = pad
    return px
}

// A PngTileSet can be assembled from hand-drawn art; sampleIds is just the atlas row,
// because nothing is being learned from it.
val tiles = PngTileSet(side, side, Array(sockets.size) { traceArt(sockets[it]) },
    IntArray(sockets.size) { it }, sockets.size, 1)

val solver = WfcSolver(34, 22, rules, seed = 20260728L)
val outcome = solver.solveWithRetries()
val grid = solver.resolvedTileGridOrNull()!!

println(outcome.attempts.toString() + " attempt(s); " + grid.count { it == 7 } + " crossings, " +
    grid.count { it == 0 } + " blank of " + grid.size + " cells")
display("tiles", tiles.sampleToImg().upscaleNearest(6).toPngBytes())
display("main", tiles.toImg(grid, 34, 22).upscaleNearest(2).toPngBytes())
```

## 7. 4 — Overlapping patterns + ground constraint

<!-- field-box id="wfc_overlapping" index="6" -->

### Narrative

From `code`

Section 3: the overlapping model. Every 3x3 window of the source becomes a "tile", and two
windows may be neighbours only if they agree on their overlap -- so each cell carries shape,
not just colour. Also the classic "ground" constraint, because WFC has no absolute position.

```edn [field-box id="wfc_overlapping" role="properties"]
{"id" "wfc_overlapping" "index" 6 "name" "4 — Overlapping patterns + ground constraint" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "4 — Overlapping patterns + ground constraint" "_tombstone" false "frame" #afield/rect [460.0 60.0 320.0 150.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "wfc_overlapping_code_.kt" "lines" 69 "characters" 2915 "sha256" "acabd1d2ab354a5cae7f5fd5fde85d4e5d858ed313cf64ff8d9bf6a97a4882ad"}]}
```

```kotlin [field-property box="wfc_overlapping" name="code" path="wfc_overlapping_code_.kt"]
// Section 3: the overlapping model. Every 3x3 window of the source becomes a "tile", and two
// windows may be neighbours only if they agree on their overlap -- so each cell carries shape,
// not just colour. Also the classic "ground" constraint, because WFC has no absolute position.
val SKY = Triple(0.75f, 0.86f, 0.94f)
val SOIL = Triple(0.42f, 0.29f, 0.16f)
val STEM = Triple(0.24f, 0.56f, 0.23f)
val BLOSSOM = Triple(0.88f, 0.34f, 0.42f)

val sw = 26
val sh = 18
val soilTop = 13
val flowers = listOf(3 to 4, 8 to 6, 13 to 3, 18 to 5, 22 to 4)   // column to stem height

val source = Img.generateRGB(sw, sh) { x, y ->
    val flower = flowers.firstOrNull { it.first == x }
    when {
        y >= soilTop -> SOIL
        flower != null && y == soilTop - flower.second - 1 -> BLOSSOM
        flower != null && y >= soilTop - flower.second -> STEM
        else -> SKY
    }
}

// wrapY = false: wrapping vertically would teach it that soil sits above sky, which is the one
// thing this source is trying to say is untrue.
val patterns = source.toBufferedImage().sliceIntoOverlappingPatterns(3, 3, wrapX = true, wrapY = false)
val rules = patterns.toRules()

// Named outW/outH: inside a lambda with its own receiver (the prepare block below) a bare `h`
// resolves to the Box's frame height rather than to a local. Likewise a bare `sky` is taken.
val outW = 60
val outH = 16

// Left alone the solver has no reason to put the soil anywhere: it produces a sky full of
// flowers whose stems run off the bottom edge with nothing to grow out of. Legal, and useless.
val free = WfcSolver(outW, outH, rules, seed = 9L)
free.solveWithRetries(maxAttempts = 30)
val freeOut = patterns.toImg(free.resolvedTileGridOrNull()!!, outW, outH)

// The all-soil and all-sky 3x3 patterns, found by looking for a flat window and testing its blue.
var groundPattern: Int = -1
var skyPattern: Int = -1
for (id in 0 until patterns.tileCount) {
    val px: IntArray = patterns.patterns[id]
    if (!px.all { v -> v == px[0] }) continue
    val blue: Int = px[0] and 0xff
    if (blue < 0x60) groundPattern = id
    if (blue > 0xC0) skyPattern = id
}

val solver = WfcSolver(outW, outH, rules, seed = 4L)
val outcome = solver.solveWithRetries(maxAttempts = 30) {
    for (x in 0 until outW) {
        forceTile(x, outH - 1, groundPattern)
        forceTile(x, 0, skyPattern)
    }
}
val out = patterns.toImg(solver.resolvedTileGridOrNull()!!, outW, outH)

println(patterns.tileCount.toString() + " distinct 3x3 patterns; ground=#" + groundPattern +
    " sky=#" + skyPattern + "; solved in " + outcome.attempts + " attempt(s)")
println("every blossom still sits on a stem: that is a 3x3-local fact, so the model enforces it")
showImgGrid(
    "source 26x18" to source.upscaleNearest(9),
    "no ground constraint" to freeOut.upscaleNearest(9),
    "ground + sky pinned" to out.upscaleNearest(9),
    columns = 1,
    cellSide = 620
)
```

