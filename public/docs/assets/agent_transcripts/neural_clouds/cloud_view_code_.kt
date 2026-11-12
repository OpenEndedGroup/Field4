// A fullscreen triangle; the fragment shader does all the work.
val module = moduleHere {
    depthTest = false
    depthWrite = false
    blend = false
}
module += "fullscreen" to fullscreenTriangle(module)
renderer += module
