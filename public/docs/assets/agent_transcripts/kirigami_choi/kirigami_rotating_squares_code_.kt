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
