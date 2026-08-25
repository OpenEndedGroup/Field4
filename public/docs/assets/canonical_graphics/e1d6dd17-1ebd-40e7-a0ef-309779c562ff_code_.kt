prop<LoadedTexture>(bbc) = LoadedTexture("/Users/marc/Desktop/bbc.jpg")
bbc.transferNow()

var module = moduleHere {}
var m = Mesh()

val probe = fieldProbeHere().display("probe")

module += "mainMesh" to m
renderer += "boo" to module

val camera = Camera()
m.p = camera.p 
m.m = camera.m 

var kc by keyCamera(camera=camera)
val n = 100
m.set(4*n, 2*n) {
    n {
        val z = it+0.0 

        pos.add(0,0,0+z, 
                1.0,0.1,z, 
                1,1,z,
            0,1,z)

        tc.add(0,0,
            1,0,
            1,1,
            0,1)

        val b = it*4
        tri.add(b+0,b+1,b+2)
        tri.add(b+0,b+2,b+3)
    }
}
