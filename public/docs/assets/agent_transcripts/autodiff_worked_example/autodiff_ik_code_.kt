// Forward kinematics is a cumulative sum of joint angles and a running position, which is
// perfectly differentiable, so the inverse problem is just L-BFGS on |tip - target|^2.
val chain = IkChain(vec(120.0, 300.0), doubleArrayOf(90.0, 80.0, 70.0, 55.0))
val target = vec(330.0, 150.0)
val restAngles = DoubleArray(chain.linkCount)

val solved = solveIk(chain, target)
val smoothed = solveIk(chain, target, options = IkOptions(smoothness = 2.0))

// A ring *and* a cross, both wider than the chain's own joint dots, so "the tip landed on
// the target" is visible rather than hidden under the end effector.
fun marker(p: Vec2, r: Double) = fline {
    moveTo(p.x - r * 1.7, p.y); lineTo(p.x - r * 0.5, p.y)
    moveTo(p.x + r * 0.5, p.y); lineTo(p.x + r * 1.7, p.y)
    moveTo(p.x, p.y - r * 1.7); lineTo(p.x, p.y - r * 0.5)
    moveTo(p.x, p.y + r * 0.5); lineTo(p.x, p.y + r * 1.7)
    for (i in 0..40) {
        val a = i / 40.0 * Math.PI * 2.0
        val px = p.x + r * kotlin.math.cos(a)
        val py = p.y + r * kotlin.math.sin(a)
        if (i == 0) moveTo(px, py) else lineTo(px, py)
    }
    color = vec(1.0, 0.42, 0.3, 1.0)
    thickness = 2.0
}
fun pose(line: FLine, r: Double, g: Double, b: Double) = line.apply {
    color = vec(r, g, b, 1.0)
    thickness = 3.5
    pointed = true
    pointSize = 3.0
}

// One shared window, so the three panels are actually comparable.
val window = Rect(40.0, 60.0, 400.0, 320.0)
// 26 units across is wider than the chain's own joint dots at this window scale, so the
// tip lands visibly *inside* the reticle rather than swallowing it.
val cross = marker(target, 26.0)

showPlaneGrid(
    "start pose\nall four joint angles zero - the arm points along +x" to
        listOf(pose(chain.toFLine(restAngles), 0.45, 0.55, 0.72), cross),
    "solved\nreach error " + solved.reachError + " units, " + solved.run.iterations + " L-BFGS iterations" to
        listOf(pose(chain.toFLine(solved.angles), 0.42, 0.86, 0.58), cross),
    "solved, smoothness = 2.0\nstraighter, and it costs " + smoothed.reachError + " units of reach" to
        listOf(pose(chain.toFLine(smoothed.angles), 0.98, 0.86, 0.32), cross),
    channel = "1 poses",
    columns = 3,
    cellWidth = 260,
    window = window
)
showSignals(
    "reach loss" to solved.run.losses,
    channel = "2 loss",
    title = "|tip - target|^2 per L-BFGS iteration",
    xTitle = "iteration"
)

"solved joint angles (radians, relative link to link): " + solved.angles.toList() + "\n" +
"tip landed at (" + solved.endEffector.x + ", " + solved.endEffector.y + "), target (" +
target.x + ", " + target.y + "), error " + solved.reachError + "\n" +
"with smoothness = 2.0 the pose straightens and the reach error rises to " + smoothed.reachError +
" - the two terms genuinely compete, and reachError always tells the truth"
