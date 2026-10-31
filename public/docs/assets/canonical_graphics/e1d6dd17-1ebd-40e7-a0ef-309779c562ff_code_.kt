/*
Debugging: texture probes. Run this in a box beneath the renderer (`window`) box.

A field probe samples values out of a running shader and shows them in this box's output area. Here
the vertex shader (open `vertex` from the editor's property switcher) brings the probe in with
`{% property "fieldProbe" %}` and calls `fieldProbe(...)` with each vertex's post-projection position,
which is a direct check that the camera transform does what you expect.
The quads stack along z, so in the window they look like one; the probe readout is the point here.
*/

// The texture is bound by name: `bbc` matches the `sampler2D bbc` in the fragment shader.
// The bundled image makes the lesson runnable; change the path to try your own.
prop<LoadedTexture>(bbc) = LoadedTexture("public/docs/assets/afield_four_graphics/docs-checker-texture.png")
bbc.transferNow()

var module = moduleHere {}
var m = Mesh()

// create the probe and show its readback in the "probe" output channel
val probe = fieldProbeHere().display("probe")

module += "mainMesh" to m
renderer += "boo" to module

// the camera's projection and view matrices feed the shader's `p` and `m` push constants
val camera = Camera()
m.p = camera.p
m.m = camera.m

var kc by keyCamera(camera = camera)
val n = 100
// `n` textured quads (4 vertices, 2 triangles each), stacked one unit apart along z
m.set(4 * n, 2 * n) {
    n {
        val z = it + 0.0

        pos.add(
            0,
            0,
            0 + z,
            1.0,
            0.1,
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

        val b = it * 4
        tri.add(b + 0, b + 1, b + 2)
        tri.add(b + 0, b + 2, b + 3)
    }
}
