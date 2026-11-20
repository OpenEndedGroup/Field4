var drawModule = moduleHere { primitiveType = triangles }
renderer += "outline-field-draw" to drawModule
val quad = fullscreenTriangle(drawModule)
drawModule += "quad" to quad

quad.state = { if (renderer.frameCounter % 2L == 0L) ofStateA else ofStateB }
quad.wall = { ofWall }
quad.w = { ofWidth }
quad.h = { ofHeight }
quad.zoom = { 2 }
println("drawing state and walls")
