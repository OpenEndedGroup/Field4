/*
1b. hello triangle (UBO)

The same scene as "2. hello camera", but the camera matrices travel in a uniform buffer instead of push
constants. Usually push constants (or buffer references) are the better choice; this shows the
alternative. Needs the renderer window box as its parent.

Open the `vertex` property (editor property switcher, `f1` or `ctrl-P`): it declares a uniform block
named `view` holding `p` and `m`.
*/
var m = Mesh()
var module = moduleHere()

module += "mainMesh" to m
renderer += module

// A perspective camera that orbits the origin
val camera = Camera()

var tick = 0.0
var n = 0
loop {
    // For the first 100 frames, rebuild the mesh with one more triangle each frame: a mesh can be resized
    // while it is drawing.
    if (n < 100) {
        n++
        m.set(3 * n, n) {
            n {
                val z = 0.0 + it
                pos.add(0, 0, -1 + z, 1.0, 0.1, z, 1, 1, z)
                3 {
                    col.add(1, it / 3.0, 1 - it / 3.0, 1)
                }
                tri.add(0 + 3 * it, 1 + 3 * it, 2 + 3 * it)
            }
        }
    }

    camera.at = vec(-10 * Math.sin(tick), 0, 10 * Math.cos(tick))
    tick += 0.01

    // `module.view` exists because the vertex shader declares the uniform block `view`; copy the camera's
    // current matrices into it every frame
    module.view.m = camera.m()
    module.view.p = camera.p()
}
