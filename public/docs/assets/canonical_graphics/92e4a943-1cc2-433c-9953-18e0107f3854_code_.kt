/*
Slang: texture. Run this in a box beneath the renderer (`window`) box.

This is the texturing lesson with its shaders written in Slang. They are in this box's
`vertex_fragment` property (open it from the editor's property switcher); when it is non-blank it is
used for both stages instead of the GLSL `vertex` and `fragment` properties. The Kotlin does not change:
resources and push constants are matched by their reflected names, whatever the shader language.
*/

// The texture is bound by name: `bbc` matches `Sampler2D bbc` in the Slang source.
// The bundled image makes the lesson runnable; change the path to try your own.
prop<LoadedTexture>(bbc) = LoadedTexture("public/docs/assets/afield_four_graphics/docs-checker-texture.png")
bbc.transferNow()

var module = moduleHere {}
var m = Mesh()

module += "mainMesh" to m
renderer += "boo" to module

// the camera's projection and view matrices feed the shader's `p` and `m` push constants
val camera = Camera()
// The quad runs from (0, 0) to (1, 1): aim at its centre, close enough that it fills the window.
camera.at = vec(0.5, 0.5, -0.8)
camera.target = vec(0.5, 0.5, 0)
m.p = camera.p
m.m = camera.m

var kc by keyCamera(camera = camera)
// one textured quad: 4 vertices, 2 triangles
m.set(4, 2) {
    val z = 0.0

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

    tri.add(0, 1, 2)
    tri.add(0, 2, 3)
}
