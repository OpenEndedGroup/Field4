/*
6. RTT- draw rtt: shows the streamed target from `6. RTT - sending` on a quad in the window.
Parent chain: window -> 6. RTT - sending. The fragment shader (open it with the editor's property
switcher) declares `sampler2D bbc`; publishing the target under the name `bbc` binds it there.
*/
prop<RenderToTexture>(bbc) = _target

var module = moduleHere()
var mesh = Mesh()
module += "mainMesh" to mesh
renderer += "boo" to module

val camera = Camera()
mesh.p = camera.p
mesh.m = camera.m

// Focus the renderer window to move the camera with the keyboard.
var kc by keyCamera(camera = camera)
mesh.set(4, 2) {
    // A 2x2 quad at z = 0: 4 vertices with texture coordinates, 2 triangles.
    val z = 0.0
    pos.add(
        -1,
        -1,
        z,
        1.0,
        -1.0,
        z,
        1,
        1,
        z,
        -1.0,
        1,
        z,
    )
    tc.add(0, 0, 1, 0, 1, 1, 0, 1)
    tri.add(0, 1, 2)
    tri.add(0, 2, 3)
}
