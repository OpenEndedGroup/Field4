val random = kotlin.random.Random(11)
val cloud = List(70) {
    vec(random.nextDouble(-1.6, 1.6), random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0))
}
val polytope = LpPolytope.hull(cloud)
val c = vec(1.0, 0.5, 0.3)
val far = polytope.vertices.indexOf(polytope.optimalVertex(vec(-1.0, -0.5, -0.3)))

val central = polytope.centralPath(c)
val pd = polytope.primalDual(c)
val walk = polytope.simplexWalk(c, startVertex = far)
val spiral = polytope.pdhg(c, iterations = 800, tau = 0.25, sigma = 0.25)

three(name = "routes", width = 760, height = 470) {
    mesh(polytope.toMesh(), wireframe = false, opacity = 0.1, cullBackFaces = false)
    lines(polytope.toFLine(vec(0.62, 0.70, 0.80, 0.5)))
    lines(
        central.toFLine(vec(1.0, 0.72, 0.32, 1.0), 2.4),
        pd.toFLine(vec(0.55, 0.9, 0.6, 1.0), 1.6),
        walk.toFLine(vec(0.6, 0.75, 1.0, 1.0), 3.0),
        spiral.trajectory.toFLine(vec(1.0, 0.55, 0.85, 0.8), 1.0),
    )
    camera(yaw = 20.0, pitch = 20.0)
    title("${polytope.vertices.size} hull vertices, ${polytope.constraintCount} faces")
    caption("orange: central path · green: primal-dual, ${pd.samples.size - 1} steps · blue: simplex, ${walk.samples.size - 1} pivots · pink: PDHG")
}
"every route ends at %s".format(polytope.optimalVertex(c))
