/*
strokes: every stroked line in the parent's FLineLayers, in one draw.

The vertex shader reads the stroke layer by buffer address and turns each segment (six
vertices) into a screen-space rectangle `thickness` pixels wide; the fragment shader cuts
a capsule out of it. Open them with the property switcher (`f1` or `ctrl-P`). Needs
"FLine layers" as its parent.
*/
val layers = flineLayers

var module = moduleHere {
    primitiveType = triangles
    depthWrite = false
}
var m = Mesh()
module += m named "strokes"
// Registered under the name every stroke box uses, so launching another one replaces
// this drawing rather than adding to it.
renderer += "fline strokes" to module

// Push constants, matched by name to StrokePC.
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

// Six vertices for every segment, i.e. for every vertex but the last. The shader drops
// the ones that would join two runs.
loop {
    m.resize(6 * maxOf(0, layers.strokes.count - 1))
}
