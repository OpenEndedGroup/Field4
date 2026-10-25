val polygon = LpPolygon([vec(-2.0, -1.0), vec(1.5, -1.5), vec(3.0, 1.0), vec(0.5, 2.5), vec(-2.5, 1.5)])
val c = vec(1.0, 0.4)
val weights = doubleArrayOf(1.0, 1.0, 1.0, 1.0, 1.0)

val central = polygon.centralPath(c, weights)
val fan = polygon.centralFan(angles = 48, samples = 80, weights = weights)
val pd = polygon.primalDual(c)
val spiral = polygon.pdhg(c, iterations = 600, tau = 0.3, sigma = 0.3)
val ellipsoid = polygon.ellipsoid(c, iterations = 30)
val walk = polygon.simplexWalk(c, startVertex = 4)
val cuts = polygon.cuttingPlanes(c, iterations = 12)

val outline = polygon.toFLine()
val answer = pointCloudFLine([polygon.optimalVertex(c)], vec(1.0, 0.4, 0.4, 1.0), 2.0)
val dots = { t: LpTrajectory -> pointCloudFLine(t.positions, vec(1.0, 1.0, 1.0, 0.9), 1.0) }

showPlaneGrid(
    "centralPath\nμ %.0e → %.0e, ${central.samples.size} samples"
        .format(central.samples.first().scale, central.last.scale) to [outline, central.toFLine(), answer],
    "centralFan\n${fan.size} directions" to [outline] + fan.toFLines(),
    "primalDual\n${pd.samples.size - 1} Newton steps" to
        [outline, pd.toFLine(vec(0.55, 0.9, 0.6, 0.95)), dots(pd), answer],
    "pdhg, tau = sigma = 0.3\n${spiral.trajectory.samples.count { it.violation > 0.0 }} of ${spiral.trajectory.samples.size} iterates outside" to
        [outline, spiral.trajectory.toFLine(vec(1.0, 0.55, 0.85, 0.85), 1.0), answer],
    "ellipsoid\n${ellipsoid.ellipses.size} ellipses, each ${(kotlin.math.sqrt(ellipsoid.ellipses[1].determinant / ellipsoid.ellipses[0].determinant)).f2} of the last's area" to
        [outline] +
            ellipsoid.ellipses.take(16).map { it.toFLine() } +
            ellipsoid.trajectory.toFLine(vec(1.0, 1.0, 1.0, 0.6), 0.8),
    "simplexWalk\n${walk.samples.size - 1} pivots" to
        [outline, walk.toFLine(vec(0.6, 0.75, 1.0, 0.95), 2.4), dots(walk), answer],
    "cuttingPlanes (analytic centres)\narea %.2f → %.1e"
        .format(cuts.trajectory.samples.first().scale, cuts.trajectory.last.scale) to
        [outline] +
            cuts.regions.map { r -> polylineFrom(r, closed = true).also { it.color = vec(0.6, 0.8, 1.0, 0.4) } } +
            cuts.trajectory.toFLine(),
    columns = 4,
    yUp = true,
    cellWidth = 260,
    cellHeight = 230,
)

"every route ends at ${polygon.optimalVertex(c)}"
