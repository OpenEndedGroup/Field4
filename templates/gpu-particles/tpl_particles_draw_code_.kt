// Draws the particles the box above publishes as `particlePositions`. Launch it after that box.
var module = moduleHere { primitiveType = triangles }
var m = Mesh()
module += "mesh" to m
renderer += "particles_draw_" + id.take(8) to module

val N = 100000
m.inData = { particlePositions }
m.aspectRatio = { window.width / window.height.toFloat() }
m.set(N * 2 * 3, 0) {}
