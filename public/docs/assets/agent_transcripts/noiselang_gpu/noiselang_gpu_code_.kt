/*
Distributions drawn in a fragment shader: three panels, a ring, the ring given x + y > 0.5, and
a Gaussian process through five readings. The graphs are in the box above; the fragment
property includes them.
*/
val shader = moduleHere {
    depthTest = false
    depthWrite = false
    blend = false
}
val quad = fullscreenTriangle(shader)
shader += "quad" to quad
renderer += "noiselang-gpu" to shader
quad.viewport = { vec(window.width.toFloat(), window.height.toFloat(), 0f, 0f) }
