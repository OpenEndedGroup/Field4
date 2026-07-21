var module = moduleHere { primitiveType = triangles }
var m = Mesh()
module += "mesh" to m
renderer += "draw" to module
val N = 100000
m.inData = { outputPositions }
m.aspectRatio = { window.width/window.height.toFloat() }
m.set(N*2*3, 0) {}
println("draw launched")
