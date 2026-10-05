var module = moduleHere { primitiveType = triangles }
var m = Mesh()
module += "grid" to m
renderer += "spinGlassDraw" to module

m.spins = { sgSpins }
m.jRight = { sgJRight }
m.jDown = { sgJDown }
m.L = { sgL.toUInt() }
m.aspect = { window.width / window.height.toFloat() }
// set to { 1u } and re-run to overlay frustrated plaquettes
m.showFrustration = { 0u }
// three vertices, no index buffer: the vertex shader makes the fullscreen triangle
m.set(3, 0) {}
println("draw launched")
