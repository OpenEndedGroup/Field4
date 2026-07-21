var module = moduleHere { primitiveType = triangles }

var m = Mesh()
module += m named "someMesh"
renderer += module

var vertexData = defaultBuffer { 
    perFrame = true 
    size = 3.vec4s
}.buffer

m.vertexData = { vertexData } 
var t = 0.0

loop {
    t += 0.01
    var num = 0

    var numVertToWrite = 3 

    vertexData.write {
        for(x in 0 until numVertToWrite) {
            put(vec(0,0,0))
            put(vec(1,0,0))
            put(vec(0,1,0))
            num += 3
        }
    }

    m.resize(num)
    true
}
