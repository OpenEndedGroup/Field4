/*
An array of textures.

The fragment shader declares `uniform sampler2D ta[3]` (open "fragment" in the property switcher)
and shows one entry in each third of the quad: here the checkerboard, a gradient, then the
checkerboard again. The array published as `ta` must have exactly as many entries as the shader
declares, or the texture is refused at link time.
Run this box beneath the renderer (`window`) box.

The bundled textures make this lesson runnable; change these paths to try your own images.
*/
var bbc = LoadedTexture("public/docs/assets/afield_four_graphics/docs-checker-texture.png")
bbc.transferNow()

var bbc2 = LoadedTexture("public/docs/assets/rt-uv-gradient.png")
bbc2.transferNow()

// A sampler array is published as an Array<ImageView>, so take each texture's imageView.
var asArray: Array<ImageView> = arrayOf(bbc.imageView, bbc2.imageView, bbc.imageView)

prop<Array<ImageView>>(ta) = asArray

var module = moduleHere {}
var m = Mesh()

module += "mainMesh" to m
renderer += "boo" to module

val camera = Camera()
// The quad runs from (0, 0) to (1, 1): aim at its centre, close enough that it fills the window.
camera.at = vec(0.5, 0.5, -0.8)
camera.target = vec(0.5, 0.5, 0)
m.p = camera.p
m.m = camera.m

var kc by keyCamera(camera = camera)
// The same textured quad as the texturing lesson: positions and texture coordinates for four
// vertices, two triangles.
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
