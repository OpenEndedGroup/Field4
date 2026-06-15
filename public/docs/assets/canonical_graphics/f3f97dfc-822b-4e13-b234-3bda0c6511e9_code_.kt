var module = moduleHere(parameters {
    ssboResolver = {
        if (it.name=="InputBuffer")
            buffer.source!!
        else
            null
    }
    primitiveType = triangles
})

var m = Mesh()

module += "mainMesh" to m
renderer += "boo2" to module

m.aspectRatio = { window.width/window.height.toFloat() }
var partNum = 2560

m.set(partNum*2*3, 0) {
    // done!
}