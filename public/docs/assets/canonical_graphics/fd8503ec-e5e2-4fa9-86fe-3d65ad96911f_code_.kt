// 5. lygia: using the Lygia shader library through #include.
// Parent: window. The fragment shader (open it with the editor's property switcher) includes
// Lygia's gerstnerWave and fbm functions and builds a procedural pattern from the texture coordinates.
registerLygiaIncludeRoot()
// Registers Lygia's source tree as an include root, so `#include "lygia/..."` resolves.

var module = moduleHere {}
var m = Mesh()

module += "mainMesh" to m
renderer += "boo" to module

val camera = Camera()
m.p = camera.p
m.m = camera.m

var kc by keyCamera(camera = camera)
m.set(4, 2) {
    // A unit quad from (0, 0) to (1, 1) with matching texture coordinates.
    val z = 0.0

    pos.add(
        0,
        0,
        0 + z,
        1.0,
        0,
        z,
        1,
        1,
        z,
        0,
        1,
        z,
    )

    tc.add(
        0,
        0,
        1,
        0,
        1,
        1,
        0,
        1,
    )

    tri.add(0, 1, 2)
    tri.add(0, 2, 3)
}
