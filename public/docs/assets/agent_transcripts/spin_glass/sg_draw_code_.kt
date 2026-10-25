var module = moduleHere { primitiveType = triangles }
val m = fullscreenTriangle(module)
module += "grid" to m
renderer += "spinGlassDraw" to module

m.spins = { sgSpins }
m.jRight = { sgJRight }
m.jDown = { sgJDown }
m.L = { sgL }
m.aspect = { window.width / window.height.toFloat() }
// set to { 1 } and re-run to overlay frustrated plaquettes
m.showFrustration = { 0 }
println("draw launched")
