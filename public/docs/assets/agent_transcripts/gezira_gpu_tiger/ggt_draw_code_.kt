var drawModule = moduleHere { primitiveType = triangles }
renderer += "gezira-tiger-draw" to drawModule
val quad = fullscreenTriangle(drawModule)
drawModule += "quad" to quad

quad.image = { tigerImage }
quad.n = { tigerSize }
quad.win = { window.width }
println("drawing the tiger")
