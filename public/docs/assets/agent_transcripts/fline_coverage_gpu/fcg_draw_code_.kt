var drawModule = moduleHere { primitiveType = triangles }
renderer += "fline-coverage-draw" to drawModule
val quad = fullscreenTriangle(drawModule)
drawModule += "quad" to quad

quad.mask = { fcMask }
quad.w = { fcWidth }
quad.h = { fcHeight }
// as many window pixels per cell as fit both copies side by side
quad.zoom = { maxOf(1, minOf(window.width / (2 * fcWidth + 1), window.height / fcHeight)) }
println("drawing the mask")
