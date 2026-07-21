var module = moduleHere { primitiveType = triangles }
var m = Mesh()
module += "grid" to m
renderer += "spinGlassDraw" to module

m.spins = { sgSpins }
m.jRight = { sgJRight }
m.jDown = { sgJDown }
m.L = { sgL.toUInt() }
m.aspect = { window.width / window.height.toFloat() }
m.showFrustration = { 0u }     // set to { 1u } and re-run to overlay frustrated plaquettes
m.set(3, 0) {}
println("draw launched")
