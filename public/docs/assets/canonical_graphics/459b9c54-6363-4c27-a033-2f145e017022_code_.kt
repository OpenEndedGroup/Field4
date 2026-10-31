// Display a ping-pong target. Run this box as a child of `5. PingPongTT`, whose `target` it reads.
//
// A ping-pong target is two images, so its halves are handed out as HasImageView rather than as a
// RenderToTexture. thisImageView is the half most recently written; it is bound to the fragment
// shader's `sampler2D bbc` (open "fragment" in the property switcher).
prop<HasImageView>(bbc) = target.thisImageView

var module = moduleHere()
var mesh = Mesh()
module += "mainMesh" to mesh
renderer += "boo" to module

val camera = Camera()
mesh.p = camera.p
mesh.m = camera.m

var kc by keyCamera(camera = camera)
// A textured quad from (-1, -1) to (1, 1).
mesh.set(4, 2) {
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
