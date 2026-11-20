/*
Exact triangle coverage in a fragment shader. The window is cut into fat pixels; the left half
samples each fat pixel at its centre, the right half integrates each triangle over it exactly
with #include "coverage/triangle.glsl". The triangles and the compositing are in this box's
fragment property.
*/
val pass = moduleHere {
    depthTest = false
    depthWrite = false
    blend = false
}
val quad = fullscreenTriangle(pass)
pass += "quad" to quad
renderer += "exact-coverage" to pass

var pixelSize = 0.0
var sigma = 0.0
quad.viewport = { vec(window.width.toFloat(), window.height.toFloat(), pixelSize, sigma) }

controlGroup("Coverage") {
    slider("Fat pixel size (0 = fit the scene)", min = 0.0, max = 64.0, initialValue = pixelSize) { pixelSize = it }
    slider("Window sharpness sigma", min = 0.0, max = 4.0, initialValue = sigma) { sigma = it }
}
displayOpen("Coverage")
println("exact coverage on the GPU: left point-sampled, right exact")
