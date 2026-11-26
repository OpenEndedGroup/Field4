import afield.math.autodiff.*
import afield.math.optimization.*

/*
Inverse design: which cut pattern of a square sheet opens into a circle?

This follows Choi, Dudte & Mahadevan, "Programming shape using kirigami tessellations",
Nature Materials 18, 999-1004 (2019), and the constraint set in Gary Choi's
inverse-kirigami-design code (Apache-2.0), re-derived here in Kotlin. The unknowns are the corners
of every tile in the OPENED sheet. These constraints make that opened sheet come from a real,
closable cut pattern whose closed outline is a square:

  - edge pairs: the two tile edges either side of a cut lie on top of each other when closed,
    so they must be the same length;
  - contractibility: the tile angles meeting at a node of the closed sheet sum to 2 pi inside,
    pi along its edge and pi / 2 at its corners, and the bottom edge is as long as the right one;
  - shape: the outermost corners of the opened sheet lie on the target curve;
  - no overlap: no cut closes below minOpening.

Choi solves this with interior-point fmincon from a conformal map of the standard pattern onto
the circle. Here it is Field's own Levenberg-Marquardt from the standard pattern squashed onto the
circle, with every tile corner kept between minCorner and pi - minCorner.
*/
watchdog = 300 // the first solve takes about half a minute; later runs come from the cache

val n = 8
val minOpening = 0.15 // radians
val minCorner = 0.4 // radians
// Choi's fix_contracted_boundary_shape = 1 with rectangular_ratio = 1: the closed sheet is square.
val squareClosedSheet = true

/*
--- 1. The standard pattern, opened flat (rotating squares at 45 degrees) ----------------------
Tile k = (i, j) has corners 0..3 counter-clockwise from bottom-left, at grid nodes
(i, j), (i+1, j), (i+1, j+1), (i, j+1). Corners joined by a hinge are one unknown point.
*/
fun tile(i: Int, j: Int) = i + n * j
fun cornerNodes(i: Int, j: Int) = listOf(i to j, i + 1 to j, i + 1 to j + 1, i to j + 1)

val parent = IntArray(4 * n * n) { it }
fun root(a: Int): Int = if (parent[a] == a) a else root(parent[a]).also { parent[a] = it }
fun join(a: Int, b: Int) {
    parent[root(a)] = root(b)
}

// Hinges follow Choi's square unit cell: at node (a, b) the meeting corners pair left-right when
// a + b is even, up-down when it is odd.
for (a in 1 until n) for (b in 0..n) for (j in listOf(b - 1, b)) {
    if (j < 0 || j >= n || (a + b) % 2 != 0) continue
    join(4 * tile(a - 1, j) + cornerNodes(a - 1, j).indexOf(a to b), 4 * tile(a, j) + cornerNodes(a, j).indexOf(a to b))
}
for (a in 0..n) for (b in 1 until n) for (i in listOf(a - 1, a)) {
    if (i < 0 || i >= n || (a + b) % 2 != 1) continue
    join(4 * tile(i, b - 1) + cornerNodes(i, b - 1).indexOf(a to b), 4 * tile(i, b) + cornerNodes(i, b).indexOf(a to b))
}
val ids = (0 until 4 * n * n).map { root(it) }.distinct().withIndex().associate { (k, r) -> r to k }
val point = IntArray(4 * n * n) { ids.getValue(root(it)) } // corner slot -> unknown point
val pointCount = ids.size
val tiles = (0 until n * n).map { k -> IntArray(4) { point[4 * k + it] } }

// Each tile turned 45 degrees (even tiles clockwise), centres sqrt(2) apart, centred on 0.
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

// The outermost points of the standard sheet (Choi's find_boundary_points) go on the target.
val half = standard.maxOf { abs(it) }
val boundary = (0 until pointCount).filter {
    max(abs(standard[2 * it]), abs(standard[2 * it + 1])) > half - 1e-6
}

/*
--- 2. The target, as a radius for every direction ---------------------------------------------
The target is Choi's shapes/circle.m, in units of the standard sheet's half-width: each quarter
is the parabola through three points, which is what his csape call gives for three points, so
his "circle" is four parabolic arcs, within 1% of round.
*/
val quarter = [[sqrt(0.5), -sqrt(0.5)], [1.0, 0.0], [sqrt(0.5), sqrt(0.5)]]
fun targetOutline(): List<DoubleArray> =
    (0 until 4).flatMap { q ->
        val a = q * PI / 2
        val pts = quarter.map { (x, y) ->
            doubleArrayOf(half * (x * cos(a) - y * sin(a)), half * (x * sin(a) + y * cos(a)))
        }
        val m = pts.size - 1
        (0 until 60).map { step ->
            val t = step / 60.0 * m // Lagrange polynomial through the points at t = 0, 1, .., m
            val w = (0..m).map { i -> (0..m).filter { it != i }.fold(1.0) { acc, j -> acc * (t - j) / (i - j) } }
            doubleArrayOf((0..m).sumOf { w[it] * pts[it][0] }, (0..m).sumOf { w[it] * pts[it][1] })
        }
    }
val outline = targetOutline()
val polar =
    (outline.map { atan2(it[1], it[0]) to hypot(it[0], it[1]) }.sortedBy { it.first }).let { p ->
        listOf(p.last().first - 2 * PI to p.last().second) + p + listOf(p.first().first + 2 * PI to p.first().second)
    }
fun targetRadius(theta: AD): AD {
    val i = polar.indexOfLast { it.first <= theta.value }.coerceIn(0, polar.size - 2)
    val (t0, r0) = polar[i]
    val (t1, r1) = polar[i + 1]
    return (theta - t0) * ((r1 - r0) / (t1 - t0)) + r0
}

// --- 3. Constraint stencils ---------------------------------------------------------------------
// Edge pairs: tile edges either side of every interior cut, as (p, q, r, s) point indices.
val edgePairs = mutableListOf<IntArray>()
for (i in 0 until n) for (j in 0 until n) {
    val k = 4 * tile(i, j)
    if (i + 1 < n) {
        val r = 4 * tile(i + 1, j)
        edgePairs += intArrayOf(point[k + 1], point[k + 2], point[r + 0], point[r + 3])
    }
    if (j + 1 < n) {
        val r = 4 * tile(i, j + 1)
        edgePairs += intArrayOf(point[k + 3], point[k + 2], point[r + 0], point[r + 1])
    }
}

// Cut openings: at the hinge h of each cut, the angle from one side a round to the other b,
// ordered so that it is positive in the standard sheet.
val cutOpenings = edgePairs.map { (p, q, r, s) ->
    val (h, a, b) = if (p == r) Triple(p, q, s) else Triple(q, p, r)
    val cross =
        (standard[2 * a] - standard[2 * h]) * (standard[2 * b + 1] - standard[2 * h + 1]) -
            (standard[2 * a + 1] - standard[2 * h + 1]) * (standard[2 * b] - standard[2 * h])
    if (cross > 0) Triple(h, b, a) else Triple(h, a, b)
}

// Angle rings: the tile corners that meet at one node of the closed sheet, with their total.
val rings = mutableListOf<Pair<List<IntArray>, Double>>()
for (a in 0..n) for (b in 0..n) {
    val corners = mutableListOf<IntArray>()
    for (i in a - 1..a) for (j in b - 1..b) {
        if (i < 0 || j < 0 || i >= n || j >= n) continue
        val c = cornerNodes(i, j).indexOf(a to b)
        val k = 4 * tile(i, j)
        corners += intArrayOf(point[k + (c + 3) % 4], point[k + c], point[k + (c + 1) % 4])
    }
    rings += corners to corners.size * PI / 2 // 2 pi inside, pi on an edge, pi / 2 at a corner
}

// Closed-sheet outline edges, for Choi's square_boundary_constraint (bottom as long as right).
val bottomEdges = (0 until n).map { tiles[tile(it, 0)][0] to tiles[tile(it, 0)][1] }
val rightEdges = (0 until n).map { tiles[tile(n - 1, it)][1] to tiles[tile(n - 1, it)][2] }

// --- 4. Solve ------------------------------------------------------------------------------------
fun cornerAngle(x: List<AD>, prev: Int, at: Int, next: Int): AD {
    val ux = x[2 * next] - x[2 * at]
    val uy = x[2 * next + 1] - x[2 * at + 1]
    val vx = x[2 * prev] - x[2 * at]
    val vy = x[2 * prev + 1] - x[2 * at + 1]
    return atan2(ux * vy - uy * vx, ux * vx + uy * vy)
}
fun edgeLength(x: List<AD>, p: Int, q: Int) = sqrt((x[2 * q] - x[2 * p]).sq() + (x[2 * q + 1] - x[2 * p + 1]).sq())

fun residuals(x: List<AD>): List<AD> {
    val out = mutableListOf<AD>()
    for (e in edgePairs) out += edgeLength(x, e[0], e[1]) - edgeLength(x, e[2], e[3])
    for ((corners, total) in rings) out += sum(corners.map { cornerAngle(x, it[0], it[1], it[2]) }) - total
    if (squareClosedSheet)
        out +=
            sum(bottomEdges.map { (p, q) -> edgeLength(x, p, q) }) -
                sum(rightEdges.map { (p, q) -> edgeLength(x, p, q) })
    for (p in boundary) {
        out += sqrt(x[2 * p].sq() + x[2 * p + 1].sq()) - targetRadius(atan2(x[2 * p + 1], x[2 * p]))
    }
    // Zero once satisfied, so it only acts on a cut that tries to close.
    for ((h, a, b) in cutOpenings) out += relu(minOpening - cornerAngle(x, a, h, b))
    // Keep every tile reasonably shaped: no corner sharper than minCorner or flatter than pi - minCorner.
    for (t in tiles) for (c in 0 until 4) {
        val angle = cornerAngle(x, t[(c + 3) % 4], t[c], t[(c + 1) % 4])
        out += relu(minCorner - angle) + relu(angle - (PI - minCorner))
    }
    return out
}

fun solveDesign(): DoubleArray {
    // Start from the standard sheet squashed onto the target (the role of Choi's conformal map).
    val x0 = DoubleArray(2 * pointCount)
    val tape = ADTape()
    for (p in 0 until pointCount) {
        val u = standard[2 * p] / half
        val v = standard[2 * p + 1] / half
        val sx = u * sqrt(1 - v * v / 2)
        val sy = v * sqrt(1 - u * u / 2)
        val r = targetRadius(tape.variable(atan2(sy, sx))).value / half
        x0[2 * p] = half * r * sx
        x0[2 * p + 1] = half * r * sy
    }
    display("progress", "solving the design")
    val fit = leastSquaresAD(x0, LeastSquaresOptions(maxIterations = 200)) { residuals(it) }
    println("Levenberg-Marquardt: ${fit.iterations} iterations, stopped on ${fit.reason}")
    return fit.x
}

/*
--- 5. Close it again: the cut pattern ---------------------------------------------------------
Tiles are rigid, so the closed sheet is the opened one with each tile turned and moved until the
two sides of every cut lie on top of each other. Walk along the first row and then up every
column, placing each tile against one already placed; return every point's closed position.
*/
fun closedSheet(solved: DoubleArray): DoubleArray {
    val pose = DoubleArray(3 * n * n) // turn, shift x, shift y per tile
    fun corner(k: Int, c: Int) = doubleArrayOf(solved[2 * tiles[k][c]], solved[2 * tiles[k][c] + 1])
    fun moved(k: Int, c: Int): DoubleArray {
        val p = corner(k, c)
        val t = pose[3 * k]
        return doubleArrayOf(
            cos(t) * p[0] - sin(t) * p[1] + pose[3 * k + 1],
            sin(t) * p[0] + cos(t) * p[1] + pose[3 * k + 2],
        )
    }
    // Place tile b so that its corners cb0, cb1 land on placed tile a's corners ca0, ca1.
    fun glue(a: Int, ca0: Int, ca1: Int, b: Int, cb0: Int, cb1: Int) {
        val p0 = moved(a, ca0)
        val p1 = moved(a, ca1)
        val q0 = corner(b, cb0)
        val q1 = corner(b, cb1)
        pose[3 * b] = atan2(p1[1] - p0[1], p1[0] - p0[0]) - atan2(q1[1] - q0[1], q1[0] - q0[0])
        pose[3 * b + 1] = 0.0
        pose[3 * b + 2] = 0.0
        val r = moved(b, cb0)
        pose[3 * b + 1] = p0[0] - r[0]
        pose[3 * b + 2] = p0[1] - r[1]
    }
    for (i in 1 until n) glue(tile(i - 1, 0), 1, 2, tile(i, 0), 0, 3)
    for (i in 0 until n) for (j in 1 until n) glue(tile(i, j - 1), 3, 2, tile(i, j), 0, 1)

    // Every corner slot of a point should now agree; take the average, levelled and centred.
    val sum = DoubleArray(2 * pointCount)
    val count = IntArray(pointCount)
    for (slot in 0 until 4 * n * n) {
        val m = moved(slot / 4, slot % 4)
        val p = point[slot]
        sum[2 * p] += m[0]
        sum[2 * p + 1] += m[1]
        count[p]++
    }
    val closed = DoubleArray(2 * pointCount) { sum[it] / count[it / 2] }
    val (b0, b1) = bottomEdges.first().first to bottomEdges.last().second
    val level = -atan2(closed[2 * b1 + 1] - closed[2 * b0 + 1], closed[2 * b1] - closed[2 * b0])
    val cx = (0 until pointCount).sumOf { closed[2 * it] } / pointCount
    val cy = (0 until pointCount).sumOf { closed[2 * it + 1] } / pointCount
    for (p in 0 until pointCount) {
        val (x, y) = closed[2 * p] - cx to closed[2 * p + 1] - cy
        closed[2 * p] = cos(level) * x - sin(level) * y
        closed[2 * p + 1] = sin(level) * x + cos(level) * y
    }
    return closed
}

/*
--- 6. Deploy ----------------------------------------------------------------------------------
Each frame is a sheet in which every hinge is exactly one shared point, so hinges never gap.
The cuts are opened a step at a time (each is eased from shut towards its opening in the design)
while every tile is held as close to its true shape as it will go. Choi's patterns are rigid at
the two ends but generally not in between: the printed strain is how far a tile must give.
*/
val steps = 40

fun tileShape(x: List<AD>): List<AD> = tiles.flatMap { t ->
    (0 until 4).flatMap { c ->
        listOf(edgeLength(x, t[c], t[(c + 1) % 4]), cornerAngle(x, t[(c + 3) % 4], t[c], t[(c + 1) % 4]))
    }
}
fun meanOpening(x: List<AD>): AD =
    sum(cutOpenings.map { (h, a, b) -> cornerAngle(x, a, h, b) }) / cutOpenings.size.toDouble()

fun deploy(solved: DoubleArray, closed: DoubleArray): List<DoubleArray> {
    val shapeOf = tileShape(ADTape().variables(solved)).map { it.value }
    val openOf = cutOpenings.map { (h, a, b) -> cornerAngle(ADTape().variables(solved), a, h, b).value }
    val frames = mutableListOf(closed.copyOf())
    var x = closed.copyOf()
    for (s in 1..steps) {
        display("progress", "solving the deployment: frame $s of $steps")
        val open = s / steps.toDouble()
        x =
            if (s == steps) solved.copyOf()
            else
                leastSquaresAD(x, LeastSquaresOptions(maxIterations = 30)) { p ->
                    val shape = tileShape(p).mapIndexed { i, v -> v - shapeOf[i] }
                    // Pin the sheet's centre so it does not drift, and drive the opening.
                    val cx = sum((0 until pointCount).map { p[2 * it] }) / pointCount.toDouble()
                    val cy = sum((0 until pointCount).map { p[2 * it + 1] }) / pointCount.toDouble()
                    val eased = cutOpenings.mapIndexed { k, (h, a, b) ->
                        (cornerAngle(p, a, h, b) - openOf[k] * open) * 0.3
                    }
                    shape + eased + listOf(cx, cy)
                }.x
        frames += x.copyOf()
    }
    return frames
}

// Is there a rigid path at all? Hold every tile exactly and every hinge exactly, and try to open
// the cuts a little from closed: if the sheet is locked, the cuts stay shut.
fun rigidCheck(closed: DoubleArray): Double {
    val shapeOf = tileShape(ADTape().variables(closed)).map { it.value }
    val nudged =
        leastSquaresAD(closed, LeastSquaresOptions(maxIterations = 50)) { p ->
            tileShape(p).mapIndexed { i, v -> (v - shapeOf[i]) * 1e3 } + listOf(meanOpening(p) - 0.05)
        }.x
    return meanOpening(ADTape().variables(nudged)).value
}

// Solving is the slow part, so keep the result; changing n or the limits solves again.
val result = cached<List<DoubleArray>>(n, minOpening, minCorner, steps) {
    val solved = solveDesign()
    val closed = closedSheet(solved)
    listOf(solved, doubleArrayOf(rigidCheck(closed))) + deploy(solved, closed)
}
val solved = result[0]
val rigidOpening = result[1][0]
val frames = result.drop(2)
display("progress", "")

val worst = residuals(ADTape().variables(solved)).maxOf { abs(it.value) }
val shapeOf = tileShape(ADTape().variables(solved)).map { it.value }
val strain = frames.maxOf { f ->
    tileShape(ADTape().variables(f))
        .mapIndexed { i, v -> abs(v.value - shapeOf[i]) / if (i % 2 == 0) shapeOf[i] else 1.0 }
        .max()
}
println(
    "largest constraint residual: %.1e (%d edge pairs, %d angle rings, %d target points, %d cuts)"
        .format(worst, edgePairs.size, rings.size, boundary.size, cutOpenings.size),
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
