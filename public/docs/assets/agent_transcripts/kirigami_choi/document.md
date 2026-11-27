# Field Document: kirigami_choi

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["contents" "frame" "name" "code" "code_language" "box_kind" "textFrame" "textRunAnchor" "textRunSources" "textFrameReview" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "parentHint" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "watchdog" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_panel" "ai4_continuation" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "ai4_garden" "fileManagerAccessHistory" "kitRole"]}
```

## 1. 2 - Inverse design, a square that opens into a circle

<!-- field-box id="kirigami_inverse_circle" index="0" -->

```edn [field-box id="kirigami_inverse_circle" role="properties"]
{"id" "kirigami_inverse_circle" "index" 0 "name" "2 - Inverse design, a square that opens into a circle" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "2 - Inverse design, a square that opens into a circle" "_tombstone" false "frame" #afield/rect [60.0 1000.0 460.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kirigami_inverse_circle_code_.kt" "lines" 124 "characters" 5224 "sha256" "bfebc8091541b0f8fb95fd04d35ef561653d8a8b663932951f8afc55ef959369"}]}
```

```kotlin [field-property box="kirigami_inverse_circle" name="code" path="kirigami_inverse_circle_code_.kt"]
import afield.geometry.conformal.KirigamiInverseDesign
import afield.math.autodiff.*

/*
Inverse design: which cut pattern of a square sheet opens into a circle?

This follows Choi, Dudte & Mahadevan, "Programming shape using kirigami tessellations",
Nature Materials 18, 999-1004 (2019), and the constraint set in Gary Choi's
inverse-kirigami-design code (Apache-2.0), re-derived in Kotlin as KirigamiInverseDesign. The
unknowns are the corners of every tile in the OPENED sheet. The constraints make that opened sheet
come from a real, closable cut pattern whose closed outline is a square:

  - edge pairs: the two tile edges either side of a cut have the same length;
  - contractibility: the tile angles meeting at a node of the closed sheet sum to 2 pi inside,
    pi along its edge and pi / 2 at its corners, and the bottom edge is as long as the right one;
  - shape: the outermost corners of the opened sheet lie on the target curve;
  - no overlap: no cut closes below minOpening, no tile corner outside [minCorner, pi - minCorner].

Choi solves this with interior-point fmincon from a conformal map of the standard pattern onto
the circle; KirigamiInverseDesign uses Field's Levenberg-Marquardt from the standard pattern
squashed onto the circle.
*/
watchdog = 300 // the first solve takes about half a minute; later runs come from the cache

val n = 8
val steps = 40
val problem = KirigamiInverseDesign(n, minOpening = 0.15, minCorner = 0.4)
val tiles = problem.tiles
val outline = problem.circleOutline()

// Solving is the slow part, so keep the result; changing n or the limits solves again.
val result = cached<List<DoubleArray>>(n, problem.minOpening, problem.minCorner, steps) {
    display("progress", "solving the design")
    val design = problem.solve(outline)
    val frames =
        problem.deploy(design, steps) { display("progress", "solving the deployment: frame $it of $steps") }
    listOf(design.opened, doubleArrayOf(design.residual, problem.rigidCheck(design.closed))) + frames
}
val solved = result[0]
val worst = result[1][0]
val rigidOpening = result[1][1]
val frames = result.drop(2)
display("progress", "")

val shapeOf = problem.tileShape(ADTape().variables(solved)).map { it.value }
val strain = frames.maxOf { f ->
    problem
        .tileShape(ADTape().variables(f))
        .mapIndexed { i, v -> abs(v.value - shapeOf[i]) / if (i % 2 == 0) shapeOf[i] else 1.0 }
        .max()
}
println(
    "largest constraint residual: %.1e (%d cuts, %d target points)"
        .format(worst, problem.cuts.size, problem.boundary.size),
)
println(
    "rigid tiles and hinges, asked to open the cuts by 0.05 rad, open them by %.1e: the closed sheet is locked"
        .format(rigidOpening),
)
println("hinges are shared points in every frame; largest tile strain on the way: %.1f%%".format(100 * strain))

// --- 7. Draw -------------------------------------------------------------------------------------
// +y is up in every view here: `lines` draws y down the page, so it gets y negated.
fun drawFrame(f: DoubleArray, flip: Boolean = false): List<FLine> =
    (0 until n * n).map { k ->
        val (i, j) = k % n to k / n
        val sy = if (flip) -1.0 else 1.0
        FLine().apply {
            val t = tiles[k]
            moveTo(f[2 * t[0]], sy * f[2 * t[0] + 1])
            for (c in [1, 2, 3, 0]) lineTo(f[2 * t[c]], sy * f[2 * t[c] + 1])
            filled = true
            stroked = true
            thickness = 0.6
            color = if ((i + j) % 2 == 0) vec(0.93, 0.62, 0.30, 0.9) else vec(0.30, 0.62, 0.86, 0.9)
        }
    }
fun targetLine(flip: Boolean = false) = FLine().apply {
    val sy = if (flip) -1.0 else 1.0
    outline.forEachIndexed { i, p -> if (i == 0) moveTo(p[0], sy * p[1]) else lineTo(p[0], sy * p[1]) }
    lineTo(outline[0][0], sy * outline[0][1])
    color = vec(0.80, 0.10, 0.12, 0.9)
    thickness = 1.5
}

val reach = (frames.flatMap { it.asList() } + outline.flatMap { it.asList() }).maxOf { abs(it) } * 1.05
val window = LinesFraming.fixed(-reach, -reach, 2 * reach, 2 * reach)
fun show(open: Double) {
    lines(
        drawFrame(frames[(open * steps).roundToInt()], flip = true) + targetLine(flip = true),
        framing = window,
        w = 520.0,
        h = 520.0,
    )
}

showPlaneGrid(
    "cut pattern (closed)" to drawFrame(frames.first()),
    "halfway\ntile strain up to ${(100 * strain).f1}%" to drawFrame(frames[steps / 2]),
    "opened, on the target\nconstraint residual ${"%.0e".format(worst)}" to drawFrame(frames.last()) + targetLine(),
    channel = "stages",
    columns = 3,
    cellWidth = 260,
    yUp = true,
)

// Drag the slider to hold the sheet at one opening; launch the box to watch it open and close.
var heldUntil = 0L
slider("open", min = 0.0, max = 1.0, step = 1.0 / steps, initialValue = 1.0) {
    heldUntil = System.currentTimeMillis() + 5000
    show(it)
}
show(1.0)

var drawn = 0L
loop {
    val now = System.currentTimeMillis()
    if (now > heldUntil && now - drawn > 60) {
        drawn = now
        // closed -> open -> closed every 9 s, resting a moment at each end
        val wave = 0.5 - 0.5 * cos(2 * PI * (now % 9000) / 9000.0)
        show((wave * 1.2 - 0.1).coerceIn(0.0, 1.0))
    }
}
```

## 2. 1 - Rotating squares, opened by a slider

<!-- field-box id="kirigami_rotating_squares" index="1" -->

### Narrative

From `code`

Rotating-squares kirigami: an n x n sheet of unit squares, cut along the grid lines but left joined
at one corner on every cut edge. Each square stays rigid; opening the sheet turns neighbouring
squares in opposite directions by the same angle, the one-degree-of-freedom mechanism behind
Choi, Dudte & Mahadevan, "Programming shape using kirigami tessellations" (Nat. Mater. 2019).

The squares are placed by walking the hinges, not by a formula: square (0,0) is fixed, every other
square is turned by +-angle and slid until it meets an already-placed neighbour at their shared
hinge. The caption reports the largest gap left at the hinges the walk did not use, which is how
you can see that the mechanism really closes.

```edn [field-box id="kirigami_rotating_squares" role="properties"]
{"id" "kirigami_rotating_squares" "index" 1 "name" "1 - Rotating squares, opened by a slider" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "1 - Rotating squares, opened by a slider" "_tombstone" false "frame" #afield/rect [60.0 60.0 420.0 120.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "kirigami_rotating_squares_code_.kt" "lines" 115 "characters" 5202 "sha256" "ced38c35fb79e7b6b518aa13c506dd34a570e281132e7ea22fb8047b2ba8c449"}]}
```

```kotlin [field-property box="kirigami_rotating_squares" name="code" path="kirigami_rotating_squares_code_.kt"]
/*
Rotating-squares kirigami: an n x n sheet of unit squares, cut along the grid lines but left joined
at one corner on every cut edge. Each square stays rigid; opening the sheet turns neighbouring
squares in opposite directions by the same angle, the one-degree-of-freedom mechanism behind
Choi, Dudte & Mahadevan, "Programming shape using kirigami tessellations" (Nat. Mater. 2019).

The squares are placed by walking the hinges, not by a formula: square (0,0) is fixed, every other
square is turned by +-angle and slid until it meets an already-placed neighbour at their shared
hinge. The caption reports the largest gap left at the hinges the walk did not use, which is how
you can see that the mechanism really closes.
*/
val n = 8

// Hinges follow the unit cell of Choi's inverse-kirigami-design code (Apache-2.0): at grid node
// (a, b) the four meeting corners pair left-right when a + b is even and up-down when it is odd.
fun hingeBetweenColumns(i: Int, j: Int) = if ((i + 1 + j) % 2 == 0) j else j + 1 // node row, at x = i + 1
fun hingeBetweenRows(i: Int, j: Int) = if ((i + j + 1) % 2 == 1) i else i + 1 // node column, at y = j + 1

// Corners of square (i, j), counter-clockwise from bottom-left, as grid nodes.
fun cornerNodes(i: Int, j: Int) = listOf(i to j, i + 1 to j, i + 1 to j + 1, i to j + 1)

fun deploy(angle: Double): Pair<Array<Array<List<DoubleArray>>>, Double> {
    val placed = Array(n) { arrayOfNulls<List<DoubleArray>>(n) }

    // Square (i, j) turned about its own centre by -angle (even squares) or +angle (odd).
    fun turned(i: Int, j: Int) =
        cornerNodes(i, j).map { (a, b) ->
            val turn = if ((i + j) % 2 == 0) -angle else angle
            val dx = a - (i + 0.5)
            val dy = b - (j + 0.5)
            doubleArrayOf(dx * cos(turn) - dy * sin(turn), dx * sin(turn) + dy * cos(turn))
        }

    // Slide the turned square so that its corner at `node` lands on `target`.
    fun placeAt(i: Int, j: Int, node: Pair<Int, Int>, target: DoubleArray): List<DoubleArray> {
        val corners = turned(i, j)
        val c = corners[cornerNodes(i, j).indexOf(node)]
        return corners.map { doubleArrayOf(it[0] - c[0] + target[0], it[1] - c[1] + target[1]) }
    }

    fun corner(i: Int, j: Int, node: Pair<Int, Int>) = placed[i][j]!![cornerNodes(i, j).indexOf(node)]

    placed[0][0] = turned(0, 0)
    for (j in 0 until n) for (i in 0 until n) {
        if (i == 0 && j == 0) continue
        placed[i][j] = if (j == 0) {
            val node = i to hingeBetweenColumns(i - 1, j)
            placeAt(i, j, node, corner(i - 1, j, node))
        } else {
                val node = hingeBetweenRows(i, j - 1) to j
                placeAt(i, j, node, corner(i, j - 1, node))
            }
    }

    // The left-right hinges above the first row were never used to place anything: check them.
    var gap = 0.0
    for (j in 1 until n) for (i in 0 until n - 1) {
        val node = i + 1 to hingeBetweenColumns(i, j)
        val p = corner(i, j, node)
        val q = corner(i + 1, j, node)
        gap = max(gap, hypot(p[0] - q[0], p[1] - q[1]))
    }
    return Array(n) { i -> Array(n) { j -> placed[i][j]!! } } to gap
}

// +y is up in both views: `lines` draws y down the page, so it gets y negated (flip).
fun sheet(angle: Double, flip: Boolean = false): Pair<List<FLine>, Double> {
    val (squares, gap) = deploy(angle)
    val sy = if (flip) -1.0 else 1.0
    val lines = mutableListOf<FLine>()
    for (i in 0 until n) for (j in 0 until n) {
        val c = squares[i][j]
        lines +=
            FLine().apply {
                moveTo(c[0][0], sy * c[0][1])
                for (k in 1..3) lineTo(c[k][0], sy * c[k][1])
                lineTo(c[0][0], sy * c[0][1])
                filled = true
                stroked = true
                thickness = 0.6
                color = if ((i + j) % 2 == 0) vec(0.93, 0.62, 0.30, 0.9) else vec(0.30, 0.62, 0.86, 0.9)
            }
    }
    return lines to gap
}

// One fixed window that holds the sheet at every opening, so the drawing does not jump about.
val reach = (0..90 step 5).flatMap { d -> sheet(Math.toRadians(d.toDouble()), flip = true).first }.flatMap { it.nodes }
val (x0, x1) = reach.minOf { it.to.x.toDouble() } to reach.maxOf { it.to.x.toDouble() }
val (y0, y1) = reach.minOf { it.to.y.toDouble() } to reach.maxOf { it.to.y.toDouble() }
val side = max(x1 - x0, y1 - y0) + 0.4
val window = LinesFraming.fixed((x0 + x1 - side) / 2, (y0 + y1 - side) / 2, side, side)

fun show(degrees: Double) {
    val (lines, gap) = sheet(Math.toRadians(degrees), flip = true)
    lines(lines, framing = window, w = 520.0, h = 520.0)
    display("about", "opening angle ${degrees.f1} deg, largest hinge gap ${"%.1e".format(gap)}")
}

slider("opening angle (deg)", min = 0.0, max = 90.0, step = 0.5, initialValue = 30.0) { show(it) }
show(30.0)

val stages = listOf(0.0, 20.0, 45.0)
showPlaneGrid(
    *stages
        .map { d ->
            val (lines, gap) = sheet(Math.toRadians(d))
            "${d.toInt()} deg\nhinge gap ${"%.0e".format(gap)}" to lines
        }
        .toTypedArray(),
    channel = "stages",
    columns = 3,
    cellWidth = 240,
    yUp = true,
)
```

