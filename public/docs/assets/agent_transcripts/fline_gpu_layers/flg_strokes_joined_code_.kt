/*
strokes, joined: the stroke layer as in "strokes", but the fragment shader antialiases each
run analytically as one shape and draws each of its pixels once, so translucent strokes
keep an even tone through their joins and along dense curves. It reads the stroke layer in
both shaders; NEIGHBOURS in the fragment shader sets how far along a run it looks.
Launch it to replace "strokes". Needs "FLine layers" as its parent.
*/
val layers = flineLayers

var module = moduleHere {
    primitiveType = triangles
    depthWrite = false
}
var m = Mesh()
module += m named "strokes, joined"
// Registered under the name every stroke box uses, so launching another one replaces
// this drawing rather than adding to it.
renderer += "fline strokes" to module

// Push constants, matched by name to StrokePC (declared in both shaders).
m.p = flineCamera.p
m.m = flineCamera.m
m.vertices = { layers.strokes.buffer }
m.count = { layers.strokes.count }
m.viewport = {
    val extent = renderer.swapChain.swapChainExtent
    vec(extent.width(), extent.height())
}
var widthScale = 1.0f
m.widthScale = { widthScale }

loop {
    m.resize(6 * maxOf(0, layers.strokes.count - 1))
}
