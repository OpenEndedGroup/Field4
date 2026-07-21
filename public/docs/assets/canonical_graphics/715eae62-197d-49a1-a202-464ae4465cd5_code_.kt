prop<RenderToTexture>(bbc) = target

var module = moduleHere()
var mesh = Mesh()
module += "mainMesh" to mesh
renderer += "boo" to module

val camera = Camera()
mesh.p = camera.p
mesh.m = camera.m

var kc = KeyCamera(camera=camera)
mesh.set(4, 2) {
    val z = 0.0
    pos.add(-1,-1, z,
            1.0,-1.0,z, 
            1,1,z, 
            -1.0, 1, z)
    tc.add(0,0, 1,0, 1,1, 0,1)
    tri.add(0,1,2)
    tri.add(0,2,3)
}
