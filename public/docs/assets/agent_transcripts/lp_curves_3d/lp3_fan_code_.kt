val random = kotlin.random.Random(11)
val cloud = List(70) {
    vec(random.nextDouble(-1.6, 1.6), random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0))
}
val polytope = LpPolytope.hull(cloud)
val directions = 120
val fan = polytope.centralFan(directions = directions, samples = 80)

three(name = "fan", width = 700, height = 470) {
    mesh(polytope.toMesh(), wireframe = false, opacity = 0.12, cullBackFaces = false)
    lines(polytope.toFLine())
    lines(fan.toFLines(alpha = 0.85, thickness = 1.2))
    camera(yaw = 32.0, pitch = 24.0)
    title("${fan.size} central paths over a ${polytope.constraintCount}-face hull")
    turntable(degreesPerSecond = 20.0)
}
"${fan.size} paths"
