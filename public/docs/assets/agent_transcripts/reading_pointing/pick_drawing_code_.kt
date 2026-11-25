import afield.graphics.scene.reading.*

prop<StereoDiagramRenderer>(drawing) = StereoDiagramRenderer(renderer, stereo, sumPlayer)
val ownedDrawing = drawing
println("drawing ready")
loop {
    renderer.windows.forEach { it.pollEvents() }
    renderer.render()
    frame()
}.end { ownedDrawing.close() }
