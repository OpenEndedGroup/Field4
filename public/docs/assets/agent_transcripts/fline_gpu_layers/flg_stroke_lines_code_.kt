/*
stroke lines: the stroke layer as hardware lines, one pixel wide whatever the lines'
`thickness` (Apple's Vulkan drivers have no wide lines), two vertices per segment, in one
draw. Launch it to replace "strokes" with hairlines, or to see what the line rasteriser costs.
Needs "FLine layers" as its parent.
*/
val layers = flineLayers

var module = moduleHere {
    primitiveType = lines
    depthWrite = false
}
var m = Mesh()
module += m named "stroke lines"
// Registered under the name every stroke box uses, so launching another one replaces
// this drawing rather than adding to it.
renderer += "fline strokes" to module

// Push constants, matched by name to LinePC.
m.p = flineCamera.p
m.m = flineCamera.m
m.vertices = { layers.strokes.buffer }
m.count = { layers.strokes.count }

loop {
    m.resize(2 * maxOf(0, layers.strokes.count - 1))
}
