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
