// Display the blur, the end of the blur chain (window -> 5. Bluring -> 5. kernel -> 5. draw blur).
//
// Draws a quad in the window textured with the kernel's output. A multi-layer target is sampled as a
// sampler2DArray; the fragment shader (open "fragment" in the property switcher) reads layer 1 of
// `back`, the layer the kernel's vertex shader writes to.

// The whole kernel target, bound to the fragment shader's `bbc`.
prop<RenderToTexture>(bbc) = b1
// imageViewArray holds one view per colour attachment; [1] is the second attachment, the
// channel-swapped copy of the blur.
val other = b1.imageViewArray[1]
prop<HasImageView>(back) = other

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
