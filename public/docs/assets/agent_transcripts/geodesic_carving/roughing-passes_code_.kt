// A part modelled as a field: a ball fused to a bar, with a fillet.
val part = marchingCubes(
    sdfSmoothUnion(sdfSphere(0.7), sdfBox(vec(2.0, 0.35, 0.6)), k = 0.25),
    boundsAround(radius = 1.4),
    72,
)
val passes = part.roughingPasses(stepDown = 0.08)
println("${passes.size} passes, ${passes.sumOf { it.second.size }} contours")

// Colour each pass by its depth below the top, shallow pale to deep red.
val top = passes.first().first
val bottom = passes.last().first
val contours = passes.flatMap { (h, rings) ->
    val s = (top - h) / (top - bottom)
    rings.map { it.toFLine3(vec(1.0, 0.85 - 0.6 * s, 0.6 - 0.5 * s, 1.0), 1.4) }
}
val view = ThreeCameraSpec(yawDegrees = 30.0, pitchDegrees = 30.0)
three(height = 360) {
    columns(2)
    panel(title = "the part", camera = view)
    mesh(part, wireframe = false)
    panel(title = "${passes.size} roughing passes, coloured by depth", camera = view)
    lines(contours, id = "passes", panel = 1)
}
