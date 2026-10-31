// 2. hello camera
//
// A stack of triangles seen through a moving Camera. The camera's projection and view matrices reach the
// vertex shader as push constants. Needs the renderer window box as its parent.
//
// Open the `vertex` property (editor property switcher, `f1` or `ctrl-P`) to see the `p` and `m` push
// constants and how they transform each position. The `fragment` shader paints every triangle one flat
// color.
var m = Mesh()
var module = moduleHere()

module += "mainMesh" to m
renderer += "boo" to module

// A perspective camera, placed off to one side looking at the origin

val camera = Camera()
camera.at = vec(-10, 2, 0)

// The vertex shader declares push constants `p` and `m`, so the mesh has matching `m.p` and `m.m`.
// They are given the camera's matrix objects rather than a snapshot, so they are read again every frame.
m.p = camera.p
m.m = camera.m

var tick = 0.0
loop {
    // orbit the camera around the origin
    camera.at = vec(-10 * Math.sin(tick), 0, 10 * Math.cos(tick))
    if (tick < 3) {
        // For the first few seconds, rebuild the mesh with 1, 2, ... 100 triangles, one more each frame.
        // `m.set(numVert, numTriangle)` sizes the mesh; `n { }` repeats its body n times with `it` as the index.
        for (n in 1..100) {
            camera.at = vec(-10 * Math.sin(tick), 0, 10 * Math.cos(tick))
            tick += 0.01
            m.set(3 * n, 1 * n) {
                n {
                    val z = 0.0 + it
                    pos.add(0, 0, -1 + z, 1.0, 0.1, z, 1, 1, z)
                    3 {
                        col.add(0, it / 3.0, 1 - it / 3.0, 1)
                    }
                    tri.add(0 + 3 * it, 1 + 3 * it, 2 + 3 * it)
                }
            }
            // wait for the next frame inside this loop body
            frame()
        }
    } else {
        tick += 0.01
        frame()
    }
    true
}
