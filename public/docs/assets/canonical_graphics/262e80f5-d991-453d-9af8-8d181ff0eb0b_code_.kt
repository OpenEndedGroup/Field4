var module = moduleHere {
    primitiveType = triangles
}

var m = Mesh()

module += "mainMesh" to m
renderer += "boo2" to module

m.inData = { outputPositions }

val N = 100000

m.aspectRatio = { window.width/window.height.toFloat() }
m.set(N*2*3, 0) {}

m
