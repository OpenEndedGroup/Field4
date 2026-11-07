import afield.graphics.scene.reading.*

fun source(box: Box, property: String) = checkNotNull(box.getPropertyRaw(property) as? String)
fun shaders() = DiagramShaderSet(
    source(strokeSource, "vertex"),
    source(strokeSource, "fragment"),
    source(pointSource, "vertex"),
    source(pointSource, "fragment"),
    source(textSource, "vertex"),
    source(textSource, "fragment"),
)
prop<StereoDiagramRenderer>(drawing) = StereoDiagramRenderer(renderer, stereo, poem, shaders())
val ownedDrawing = drawing
controlGroup("drawing") {
    button("Recenter poem") { down -> if (down) drawing.recenter() }
    checkbox("Gently face the reader", initialValue = true) { drawing.followReader = it }
    slider("Turning half-life (seconds)", initialValue = 2.0, min = .1, max = 10.0) {
        drawing.turnHalfLife = it.toFloat()
    }
    slider("Stroke width multiplier", initialValue = 1.0, min = .2, max = 4.0) {
        poem.examples.forEach { e -> e.player.strokeWidthScale = it.toFloat() }
    }
    slider("Point radius multiplier", initialValue = 1.0, min = .2, max = 4.0) {
        poem.examples.forEach { e -> e.player.dotRadiusScale = it.toFloat() }
    }
    checkbox("Show diagram dots", initialValue = true) { poem.examples.forEach { e -> e.player.showDots = it } }
    button("Reload the three shader pairs") { down -> if (down) drawing.reload(shaders()) }
}
var lastStats = 0L
loop {
    renderer.windows.forEach { it.pollEvents() }
    renderer.render()
    val now = System.nanoTime()
    if (now - lastStats > 1_000_000_000L) {
        lastStats = now
        display(
            "frame",
            "<div style='padding:12px;width:440px'>Frame ${drawing.frameCount} · ${poem.labels.size} text runs · ${poem.geometry.segments.size} stroke segments · ${poem.geometry.points.size} markers · ${poem.current.physicsSteps} fixed physics steps. One centreline upload and one text-pose update serve both eyes.</div>",
        )
    }
    frame()
}
    .end { ownedDrawing.close() }
