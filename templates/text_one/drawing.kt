import afield.graphics.scene.reading.*

fun source(box: Box, property: String) = checkNotNull(box.getPropertyRaw(property) as? String)
fun shaders() = DiagramShaderSet(
    source(strokeSource, "vertex"),
    source(strokeSource, "fragment"),
    source(pointSource, "vertex"),
    source(pointSource, "fragment"),
    source(textSource, "vertex"),
    source(textSource, "fragment"),
    source(blurSource, "vertex"),
    source(blurSource, "fragment"),
    source(compositeSource, "vertex"),
    source(compositeSource, "fragment"),
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
    button("Reload drawing, blur and compositor shaders") { down -> if (down) drawing.reload(shaders()) }
}
val grade = drawing.compositor.settings
controlGroup("blur cascade") {
    slider("Medium blur radius", initialValue = grade.mediumRadius.toDouble(), min = 0.0, max = 8.0) { grade.mediumRadius = it.toFloat() }
    slider("Broad blur radius", initialValue = grade.broadRadius.toDouble(), min = 0.0, max = 8.0) { grade.broadRadius = it.toFloat() }
}
controlGroup("green compositor") {
    slider("Blur mix", initialValue = grade.selection.z.toDouble(), min = 0.0, max = 1.0) { grade.selection.z = it.toFloat() }
    slider("Green selector gain", initialValue = grade.selection.x.toDouble(), min = 0.0, max = 8.0) { grade.selection.x = it.toFloat() }
    slider("Green selector offset", initialValue = grade.selection.y.toDouble(), min = -1.0, max = 1.0) { grade.selection.y = it.toFloat() }
    slider("Source green scale", initialValue = grade.selection.w.toDouble(), min = 0.0, max = 4.0) { grade.selection.w = it.toFloat() }
    slider("Selector sharp weight", initialValue = grade.selectorSource.x.toDouble(), min = 0.0, max = 1.0) { grade.selectorSource.x = it.toFloat() }
    slider("Selector medium weight", initialValue = grade.selectorSource.y.toDouble(), min = 0.0, max = 1.0) { grade.selectorSource.y = it.toFloat() }
    slider("Selector broad weight", initialValue = grade.selectorSource.z.toDouble(), min = 0.0, max = 1.0) { grade.selectorSource.z = it.toFloat() }
    slider("Green from red / blue", initialValue = grade.reconstruction.x.toDouble(), min = 0.0, max = 1.0) {
        grade.reconstruction.x = it.toFloat(); grade.reconstruction.y = 1f - it.toFloat()
    }
}
controlGroup("paint and colour") {
    slider("Additive to weighted paint", initialValue = grade.paint.z.toDouble(), min = 0.0, max = 1.0) { grade.paint.z = it.toFloat() }
    slider("Paint opacity threshold", initialValue = grade.paint.x.toDouble(), min = .001, max = 4.0) { grade.paint.x = it.toFloat() }
    slider("Paint knee width", initialValue = grade.paint.y.toDouble(), min = 0.0, max = 2.0) { grade.paint.y = it.toFloat() }
    slider("Exposure", initialValue = grade.reconstruction.z.toDouble(), min = 0.0, max = 4.0) { grade.reconstruction.z = it.toFloat() }
    slider("Saturation", initialValue = grade.saturation.x.toDouble(), min = 0.0, max = 3.0) { grade.saturation.x = it.toFloat() }
    slider("FXAA mix", initialValue = grade.fxaa.x.toDouble(), min = 0.0, max = 1.0) { grade.fxaa.x = it.toFloat() }
    colorPicker("Background", initialValue = grade.background) { grade.background.set(it) }
    colorPicker("Shadow tone (alpha = strength)", initialValue = grade.shadowTone) { grade.shadowTone.set(it) }
    colorPicker("Highlight tone (alpha = strength)", initialValue = grade.highlightTone) { grade.highlightTone.set(it) }
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
