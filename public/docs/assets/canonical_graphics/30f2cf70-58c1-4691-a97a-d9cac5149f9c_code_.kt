
var module = moduleHere()
var m = Mesh()

// and we connect these to together
module += "mainMesh" to m
renderer += module

val camera = Camera()
m.p =  camera.p
m.m =  camera.m

KeyCamera(camera=camera)


m.set(30, 10) {
    var base = 0
    5.alphas { z->
        pos.add(0,0,0+z*5.0, 1.0,0.1,z*5.0, 1,1,z*5.0)
        col.add(1,1,0,1,1,0,0,1,1,0,0,1)
        tri.add(base,base+1,base+2)
        base += 3
    }

    5.alphas { z->
        pos.add(3,3,0+z*5.0, 4.0,3.1,z*5.0, 4,4,z*5.0)
        col.add(1,1,0,1,1,0,0,1,1,0,0,1)
        tri.add(base,base+1,base+2)
        base += 3
    }
}
