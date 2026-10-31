// Blur kernel, the middle of the blur chain (window -> 5. Bluring -> 5. kernel -> 5. draw blur).
//
// Samples the parent's checkerboard target and writes a horizontal Gaussian blur into a second
// target, `b1`, which the child box `5. draw blur` displays. Most of the lesson is in the shaders
// (open "vertex" and "fragment" in the property switcher): the vertex shader computes the Gaussian
// weights once per vertex, and the fragment shader takes seven symmetric taps and writes two
// colour attachments at once.

// The parent's `target`, republished under the name the fragment shader samples (`sampler2D b0`).
prop<RenderToTexture>(b0) = target

// Two colour attachments (one per fragment-shader output), each with two layers and a mip chain.
prop<RenderToTexture>(b1) = RenderToTexture {
    useDepth = false
    mipLevels = 10
    colorLayerCount = 2
    colorAttachments = 2
}

renderer.renderList += "b1" to b1

var module = moduleHere {
    primitiveType = triangle_strip
    depth = 0
    +b1
}

var m = Mesh()
module += "mainMesh" to m
b1 += "boo" to module

// radius and sigma are the vertex shader's push constants; edit them live to change the blur.
m.radius = { 10.0f }
m.sigma = { 10.0f }
// A fullscreen quad with no vertex data: four vertices drawn as a triangle strip, positions made in
// the vertex shader from gl_VertexIndex.
m.resize(4)
