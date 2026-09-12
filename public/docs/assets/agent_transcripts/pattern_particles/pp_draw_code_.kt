var module = moduleHere {
    primitiveType = triangles
}

var quads = Mesh()
module += "quads" to quads
renderer += "pattern-particles-draw" to module

val N = 24000
quads.inData = { outputPositions }
quads.aspectRatio = { window.width / window.height.toFloat() }
quads.set(N * 2 * 3, 0) {}

// Printed from this box rather than from the compute box, because a docs capture waits on
// the output of the box it is capturing.
println(
    "$N particles drawn in four rings; bands now " +
        bandEnergy.joinToString(", ") { "%.3f".format(it) },
)

quads
