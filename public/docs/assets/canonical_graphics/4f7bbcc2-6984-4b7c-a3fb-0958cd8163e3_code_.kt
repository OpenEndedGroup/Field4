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

window.width/window.height.toFloat()

m.aspectRatio = { window.width/window.height.toFloat() }
var partNum = 256

m.set(4*partNum, 2*partNum) {
    for (p in 0 until partNum) {
        pos.put(0,0,0)
        pos.put(1,0,0)
        pos.put(1,1,0)
        pos.put(0,1,0)
        val base = 4*p
        tri.put(base+0,base+1,base+2)
        tri.put(base+0,base+2,base+3)
    }
}