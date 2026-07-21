var module = moduleHere {
    primitiveType = triangle_strip
}
var m = Mesh()
module += "mainMesh" to m


m.blue = {Math.random().toFloat()}

var positions = buffer {
    size = 5.vec2s
    addressable()
    storage()
    hosted()
}.buffer

m.ref = { 
    positions
} 

m.set(4, 4) {
    pos.put(0,0,0, 1,0,0, 1,1,0, 0,1,0)
    4 {
        col.put(0,it/3.0,1-it/3.0,1)
    }
    tri.put(0,1,2, 3)
}

renderer += "boo2" to module

loop {
    positions.mapAndWrite { 
        put(vec(0,0.25))
        put(vec(0.33,0.5))
        put(vec(0.66,1))
        put(vec(1,0))
        put(vec(0.5,0.5))
    }
    frame()
}