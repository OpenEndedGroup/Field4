// Two solids on a contact sheet, to turn while the canvas flies.
val shapes = listOf(
    "ring" to torusMesh(majorSegments = 24, minorSegments = 12),
    "tower" to boxMesh(0.5, 1.6, 0.5),
)
three(name = "flight", width = 760, height = 400) {
    columns(2)
    shapes.forEachIndexed { i, (label, shape) ->
        mesh(shape, label = label, panel = i, wireframe = false)
        panel(title = label, camera = ThreeCameraSpec(yawDegrees = 30.0, pitchDegrees = 20.0), bounds = shape.toThreeBounds())
    }
}
