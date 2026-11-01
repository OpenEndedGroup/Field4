/*
Texturing a quad.

The fragment shader declares `uniform sampler2D bbc` (open "fragment" in the property switcher).
Publishing a LoadedTexture property with the same name is what binds it: resources are matched to
shader declarations by name. Run this box beneath the renderer (`window`) box.

The bundled texture makes this lesson runnable; change the path to try your own image.
*/
prop<LoadedTexture>(bbc) = LoadedTexture("public/docs/assets/afield_four_graphics/docs-checker-texture.png")
// Upload the image to the GPU now, before the first draw needs it.
bbc.transferNow()

var module = moduleHere {}
var m = Mesh()

module += "mainMesh" to m
renderer += "boo" to module

// p and m are the vertex shader's push-constant matrices, supplied by the camera.
val camera = Camera()
// The quad runs from (0, 0) to (1, 1): aim at its centre, close enough that it fills the window.
camera.at = vec(0.5, 0.5, -0.8)
camera.target = vec(0.5, 0.5, 0)
m.p = camera.p
m.m = camera.m

var kc by keyCamera(camera = camera)
// Four vertices, two triangles. `pos` and `tc` are the vertex shader's two attributes: an xyz
// position and a texture coordinate per vertex.
m.set(4, 2) {
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
