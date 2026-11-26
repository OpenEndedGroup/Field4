import afield.math.autodiff.*
import afield.math.optimization.*

/*
Inverse design: which cut pattern of a square sheet opens into a circle?

This follows Choi, Dudte & Mahadevan, "Programming shape using kirigami tessellations",
Nature Materials 18, 999-1004 (2019), and the constraint set in Gary Choi's
inverse-kirigami-design code (Apache-2.0), re-derived here in Kotlin. The unknowns are the corners
of every tile in the OPENED sheet. Three families of constraints make that opened sheet come from
a real, closable cut pattern:

  - edge pairs: the two tile edges either side of a cut lie on top of each other when closed,
    so they must be the same length;
  - contractibility: the tile angles meeting at an interior node of the closed sheet sum to 2 pi
    (optionally pi along its outer edge, to force a rectangular closed sheet);
  - shape: the outermost corners of the opened sheet lie on the target curve.

Choi solves this with interior-point fmincon. Here it is two of Field's own solvers: L-BFGS on
the paper's regulariser (neighbouring tiles of the same kind should have similar edge lengths
and angles) plus a constraint penalty, then Levenberg-Marquardt to satisfy the constraints exactly.
*/
watchdog = 180 // the solve below takes tens of seconds

val n = 8
val radius = n / sqrt(2.0) // target circle, centred on the origin
// Choi's fix_contracted_boundary_shape. With this set the circle cannot be met exactly with the
// boundary points chosen here (the solver stalls about 0.07 off it), so it is off by default.
val squareClosedSheet = false

// --- 1. The standard pattern, opened flat (rotating squares at 45 degrees) ----------------------
// Tile k = (i, j) has corners 0..3 counter-clockwise from bottom-left, at grid nodes
// (i, j), (i+1, j), (i+1, j+1), (i, j+1). Corners joined by a hinge are one unknown point.
fun tile(i: Int, j: Int) = i + n * j
fun cornerNodes(i: Int, j: Int) = listOf(i to j, i + 1 to j, i + 1 to j + 1, i to j + 1)

val parent = IntArray(4 * n * n) { it }
fun root(a: Int): Int = if (parent[a] == a) a else root(parent[a]).also { parent[a] = it }
fun join(a: Int, b: Int) { parent[root(a)] = root(b) }

// Hinges follow Choi's square unit cell: at node (a, b) the meeting corners pair left-right when
// a + b is even, up-down when it is odd.
for (a in 1 until n) for (b in 0..n) for (j in listOf(b - 1, b)) {
    if (j < 0 || j >= n || (a + b) % 2 != 0) continue
    val left = cornerNodes(a - 1, j).indexOf(a to b)
    val right = cornerNodes(a, j).indexOf(a to b)
    join(4 * tile(a - 1, j) + left, 4 * tile(a, j) + right)
}
for (a in 0..n) for (b in 1 until n) for (i in listOf(a - 1, a)) {
    if (i < 0 || i >= n || (a + b) % 2 != 1) continue
    val below = cornerNodes(i, b - 1).indexOf(a to b)
    val above = cornerNodes(i, b).indexOf(a to b)
    join(4 * tile(i, b - 1) + below, 4 * tile(i, b) + above)
}
val ids = (0 until 4 * n * n).map { root(it) }.distinct().withIndex().associate { (k, r) -> r to k }
val point = IntArray(4 * n * n) { ids.getValue(root(it)) } // corner slot -> unknown point
val pointCount = ids.size

// Standard opened sheet: each tile turned 45 degrees (even tiles clockwise), centres sqrt(2) apart.
val standard = DoubleArray(2 * pointCount)
for (i in 0 until n) for (j in 0 until n) {
    val turn = if ((i + j) % 2 == 0) -PI / 4 else PI / 4
    cornerNodes(i, j).forEachIndexed { c, (a, b) ->
        val dx = a - (i + 0.5)
        val dy = b - (j + 0.5)
        val p = point[4 * tile(i, j) + c]
        standard[2 * p] = sqrt(2.0) * (i + 0.5 - n / 2.0) + dx * cos(turn) - dy * sin(turn)
        standard[2 * p + 1] = sqrt(2.0) * (j + 0.5 - n / 2.0) + dx * sin(turn) + dy * cos(turn)
    }
}

// The outermost points of the standard sheet are the ones held on the target curve.
val half = standard.maxOf { abs(it) }
val boundary = (0 until pointCount).filter {
    max(abs(standard[2 * it]), abs(standard[2 * it + 1])) > half - 1e-6
}

// --- 2. Constraint stencils ---------------------------------------------------------------------
// Edge pairs: tile edges either side of every interior cut, as (p, q, r, s) point indices.
val edgePairs = mutableListOf<IntArray>()
for (i in 0 until n) for (j in 0 until n) {
    val k = 4 * tile(i, j)
    if (i + 1 < n) { val r = 4 * tile(i + 1, j); edgePairs += intArrayOf(point[k + 1], point[k + 2], point[r + 0], point[r + 3]) }
    if (j + 1 < n) { val r = 4 * tile(i, j + 1); edgePairs += intArrayOf(point[k + 3], point[k + 2], point[r + 0], point[r + 1]) }
}

// Cut openings: at the hinge h of each cut, the angle from one side a round to the other b,
// ordered so that it is positive in the standard sheet.
val minOpening = 0.15 // radians
val cutOpenings = edgePairs.map { (p, q, r, s) ->
    val (h, a, b) = if (p == r) Triple(p, q, s) else Triple(q, p, r)
    val cross = (standard[2 * a] - standard[2 * h]) * (standard[2 * b + 1] - standard[2 * h + 1]) -
        (standard[2 * a + 1] - standard[2 * h + 1]) * (standard[2 * b] - standard[2 * h])
    if (cross > 0) Triple(h, b, a) else Triple(h, a, b)
}

// Angle rings: the tile corners that meet at one node of the closed sheet, with the angle they
// must add up to. Each corner is (previous, corner, next) point indices.
val rings = mutableListOf<Pair<List<IntArray>, Double>>()
for (a in 0..n) for (b in 0..n) {
    val corners = mutableListOf<IntArray>()
    for (i in a - 1..a) for (j in b - 1..b) {
        if (i < 0 || j < 0 || i >= n || j >= n) continue
        val c = cornerNodes(i, j).indexOf(a to b)
        val k = 4 * tile(i, j)
        corners += intArrayOf(point[k + (c + 3) % 4], point[k + c], point[k + (c + 1) % 4])
    }
    if (corners.size == 4) rings += corners to 2 * PI
    if (corners.size == 2 && squareClosedSheet) rings += corners to PI
}

// Regulariser pairs: tiles two apart along a row or column play the same role in the pattern.
val tiles = (0 until n * n).map { k -> IntArray(4) { point[4 * k + it] } }
val sameKind = mutableListOf<Pair<Int, Int>>()
for (i in 0 until n) for (j in 0 until n) {
    if (i + 2 < n) sameKind += tile(i, j) to tile(i + 2, j)
    if (j + 2 < n) sameKind += tile(i, j) to tile(i, j + 2)
}

// --- 3. Starting guess: the standard sheet squashed onto the disc ------------------------------
val start = DoubleArray(2 * pointCount)
for (p in 0 until pointCount) {
    val u = standard[2 * p] / half
    val v = standard[2 * p + 1] / half
    start[2 * p] = radius * u * sqrt(1 - v * v / 2)
    start[2 * p + 1] = radius * v * sqrt(1 - u * u / 2)
}

// --- 4. Solve ------------------------------------------------------------------------------------
fun cornerAngle(x: List<AD>, prev: Int, at: Int, next: Int): AD {
    val ux = x[2 * next] - x[2 * at]; val uy = x[2 * next + 1] - x[2 * at + 1]
    val vx = x[2 * prev] - x[2 * at]; val vy = x[2 * prev + 1] - x[2 * at + 1]
    return atan2(ux * vy - uy * vx, ux * vx + uy * vy)
}
fun edgeLength(x: List<AD>, p: Int, q: Int) = sqrt((x[2 * q] - x[2 * p]).sq() + (x[2 * q + 1] - x[2 * p + 1]).sq())

fun residuals(x: List<AD>): List<AD> {
    val out = mutableListOf<AD>()
    for (e in edgePairs) out += edgeLength(x, e[0], e[1]) - edgeLength(x, e[2], e[3])
    for ((corners, total) in rings) out += sum(corners.map { cornerAngle(x, it[0], it[1], it[2]) }) - total
    for (p in boundary) out += (x[2 * p].sq() + x[2 * p + 1].sq() - radius * radius) / radius
    // No overlap: every cut stays open by at least minOpening, the same way round as the standard
    // sheet. Zero once satisfied, so it only acts on a cut that tries to close.
    for ((h, a, b) in cutOpenings) out += relu(minOpening - cornerAngle(x, a, h, b))
    return out
}

fun regulariser(x: List<AD>): AD = sum(sameKind.map { (a, b) ->
    val ta = tiles[a]; val tb = tiles[b]
    sum((0 until 4).map { c ->
        (edgeLength(x, ta[c], ta[(c + 1) % 4]) - edgeLength(x, tb[c], tb[(c + 1) % 4])).sq() +
            (cornerAngle(x, ta[(c + 3) % 4], ta[c], ta[(c + 1) % 4]) -
                cornerAngle(x, tb[(c + 3) % 4], tb[c], tb[(c + 1) % 4])).sq()
    })
}) / sameKind.size.toDouble()

// First shape the pattern: regulariser plus a penalty on the constraints, by L-BFGS.
// Then make it exact: Levenberg-Marquardt on the constraints alone, from that start.
var solved = lbfgs(start, iterations = 400) { x -> regulariser(x) + 30.0 * sumOfSquares(residuals(x)) }.x
val exact = leastSquaresAD(solved, LeastSquaresOptions(maxIterations = 200)) { x -> residuals(x) }
solved = exact.x
println("Levenberg-Marquardt: ${exact.iterations} iterations, stopped on ${exact.reason}")
val finalResiduals = residuals(ADTape().variables(solved)).map { abs(it.value) }
val worst = finalResiduals.max()
val e = edgePairs.size; val r = rings.size
println("worst residual - edge pairs %.1e, angle rings %.1e, circle %.1e".format(
    finalResiduals.take(e).max(), finalResiduals.drop(e).take(r).max(), finalResiduals.drop(e + r).max()))
println("largest constraint residual: %.2e over %d edge pairs, %d angle rings, %d boundary points"
    .format(worst, edgePairs.size, rings.size, boundary.size))

// --- 5. Close it again: the cut pattern ---------------------------------------------------------
// Tiles are rigid, so the closed sheet is the opened one with each tile turned and moved until the
// two sides of every cut lie on top of each other. turn[k], shift[k] map tile k's opened corners
// to its closed ones, walking along the first row and then up every column.
fun corner(k: Int, c: Int) = doubleArrayOf(solved[2 * tiles[k][c]], solved[2 * tiles[k][c] + 1])
val turn = DoubleArray(n * n)
val shift = Array(n * n) { DoubleArray(2) }
fun moved(k: Int, c: Int): DoubleArray {
    val p = corner(k, c)
    return doubleArrayOf(cos(turn[k]) * p[0] - sin(turn[k]) * p[1] + shift[k][0],
        sin(turn[k]) * p[0] + cos(turn[k]) * p[1] + shift[k][1])
}
// Place tile `b` so that its corners cb0, cb1 land on placed tile `a`'s corners ca0, ca1.
fun glue(a: Int, ca0: Int, ca1: Int, b: Int, cb0: Int, cb1: Int) {
    val p0 = moved(a, ca0); val p1 = moved(a, ca1)
    val q0 = corner(b, cb0); val q1 = corner(b, cb1)
    turn[b] = atan2(p1[1] - p0[1], p1[0] - p0[0]) - atan2(q1[1] - q0[1], q1[0] - q0[0])
    turn[b] = atan2(sin(turn[b]), cos(turn[b]))
    shift[b][0] = 0.0; shift[b][1] = 0.0
    val r = moved(b, cb0)
    shift[b][0] = p0[0] - r[0]; shift[b][1] = p0[1] - r[1]
}
for (i in 1 until n) glue(tile(i - 1, 0), 1, 2, tile(i, 0), 0, 3)
for (i in 0 until n) for (j in 1 until n) glue(tile(i, j - 1), 3, 2, tile(i, j), 0, 1)

// Square the closed sheet up: turn everything so tile (0,0)'s bottom edge is horizontal.
val level = -atan2(moved(0, 1)[1] - moved(0, 0)[1], moved(0, 1)[0] - moved(0, 0)[0])
for (k in 0 until n * n) {
    turn[k] += level
    val (sx, sy) = shift[k][0] to shift[k][1]
    shift[k][0] = cos(level) * sx - sin(level) * sy
    shift[k][1] = sin(level) * sx + cos(level) * sy
}

// How well the closed sheet closes: the cut sides the walk did not glue should meet anyway.
var closeGap = 0.0
for (i in 0 until n - 1) for (j in 1 until n) {
    val a = tile(i, j); val b = tile(i + 1, j)
    closeGap = max(closeGap, hypot(moved(a, 1)[0] - moved(b, 0)[0], moved(a, 1)[1] - moved(b, 0)[1]))
    closeGap = max(closeGap, hypot(moved(a, 2)[0] - moved(b, 3)[0], moved(a, 2)[1] - moved(b, 3)[1]))
}
println("largest gap between cut sides in the closed sheet: %.2e".format(closeGap))

// --- 6. Deploy: rigid tiles, hinges held, every tile's turn eased from closed to open ----------
// Unknowns per tile: turn, shift x, shift y (closed -> 1 - opened). Hinges are the corner slots
// that share a point; each frame starts from the last.
val hinges = (0 until 4 * n * n).groupBy { point[it] }.values.filter { it.size > 1 }
    .flatMap { slots -> slots.zipWithNext() }
fun pose(x: List<AD>, slot: Int): Pair<AD, AD> {
    val k = slot / 4; val p = corner(k, slot % 4)
    val t = x[3 * k]
    return (cos(t) * p[0] - sin(t) * p[1] + x[3 * k + 1]) to (sin(t) * p[0] + cos(t) * p[1] + x[3 * k + 2])
}
val steps = 24
var poseNow = DoubleArray(3 * n * n) { if (it % 3 == 0) turn[it / 3] else shift[it / 3][it % 3 - 1] }
val frames = mutableListOf<DoubleArray>()
var frameGap = 0.0
for (s in 0..steps) {
    val open = s / steps.toDouble()
    poseNow = leastSquaresAD(poseNow, LeastSquaresOptions(maxIterations = 60)) { x ->
        val held = hinges.flatMap { (a, b) ->
            val (ax, ay) = pose(x, a); val (bx, by) = pose(x, b)
            listOf(ax - bx, ay - by)
        }
        // Turns are driven; positions follow the hinges, with only the middle tile pinned.
        val eased = (0 until n * n).map { k -> (x[3 * k] - turn[k] * (1 - open)) * 0.1 }
        val middle = tile(n / 2, n / 2)
        val pinned = listOf(x[3 * middle + 1] - shift[middle][0] * (1 - open),
            x[3 * middle + 2] - shift[middle][1] * (1 - open))
        held + eased + pinned
    }.x
    frames += poseNow.copyOf()
    val tape = ADTape().variables(poseNow)
    frameGap = max(frameGap, hinges.maxOf { (a, b) ->
        val (ax, ay) = pose(tape, a); val (bx, by) = pose(tape, b)
        hypot(ax.value - bx.value, ay.value - by.value)
    })
}
println("largest hinge gap over %d deployment frames: %.2e".format(steps + 1, frameGap))

// --- 7. Draw -------------------------------------------------------------------------------------
fun drawFrame(f: DoubleArray): List<FLine> {
    val tape = ADTape().variables(f)
    return (0 until n * n).map { k ->
        val (i, j) = k % n to k / n
        FLine().apply {
            val corners = (0 until 4).map { c -> pose(tape, 4 * k + c) }
            moveTo(corners[0].first.value, corners[0].second.value)
            for (c in listOf(1, 2, 3, 0)) lineTo(corners[c].first.value, corners[c].second.value)
            filled = true
            stroked = true
            thickness = 0.6
            color = if ((i + j) % 2 == 0) vec(0.93, 0.62, 0.30, 0.9) else vec(0.30, 0.62, 0.86, 0.9)
        }
    }
}
fun targetCircle() = FLine().apply {
    for (s in 0..128) {
        val a = 2 * PI * s / 128
        if (s == 0) moveTo(radius * cos(a), radius * sin(a)) else lineTo(radius * cos(a), radius * sin(a))
    }
    color = vec(0.80, 0.10, 0.12, 0.9)
    thickness = 1.5
}

val all = frames.flatMap { drawFrame(it) }
val lo = all.minOf { l -> l.nodes.minOf { min(it.to.x, it.to.y).toDouble() } } - 0.5
val hi = all.maxOf { l -> l.nodes.maxOf { max(it.to.x, it.to.y).toDouble() } } + 0.5
val window = LinesFraming.fixed(lo, lo, hi - lo, hi - lo)

fun show(open: Double) {
    val f = frames[(open * steps).roundToInt()]
    lines(drawFrame(f) + targetCircle(), framing = window, w = 520.0, h = 520.0)
}
slider("open", min = 0.0, max = 1.0, step = 1.0 / steps, initialValue = 1.0) { show(it) }
show(1.0)

showPlaneGrid(
    "cut pattern (closed)\ncut-side gap ${"%.0e".format(closeGap)}" to drawFrame(frames.first()),
    "halfway" to drawFrame(frames[steps / 2]),
    "opened, on the target circle\nconstraint residual ${"%.0e".format(worst)}" to drawFrame(frames.last()) + targetCircle(),
    channel = "stages",
    columns = 3,
    cellWidth = 260,
    yUp = true,
)
