
var module = moduleHere(parameters {
    primitiveType = triangles
})

var m = Mesh()

module += "mainMesh" to m
renderer += "boo2" to module

m.aspectRatio = { window.width/window.height.toFloat() }
var partNum = 2_560_000

m.set(partNum*2*3, 0) {
    // done!
}