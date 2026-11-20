var drawModule = moduleHere { primitiveType = triangles }
renderer += "buffer-paint-draw" to drawModule
val quad = fullscreenTriangle(drawModule)
drawModule += "quad" to quad

// The half the simulation is writing this frame, by the same parity it uses.
quad.state = { if (renderer.frameCounter % 2L == 0L) bpStateA else bpStateB }
quad.w = { bpWidth }
quad.h = { bpHeight }
quad.zoom = { 1 }
println("drawing the buffer")
