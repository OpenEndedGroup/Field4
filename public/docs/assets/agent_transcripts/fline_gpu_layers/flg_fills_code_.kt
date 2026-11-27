/*
fills: every filled line in the parent's FLineLayers, as fans in one draw. A line's
`fillHint` picks how much tessellation it gets (once per distinct content): HOLES, the
full tessellator; NO_HOLES, each subpath alone; STAR, a fan about each subpath's centroid
with no tessellator. Every stored vertex is one triangle slot, so the draw is three
vertices per stored vertex. Needs "FLine layers" as its parent.
*/
val layers = flineLayers

var module = moduleHere {
    primitiveType = triangles
    depthWrite = false
}
var m = Mesh()
module += m named "fills"
renderer += "fline fills" to module

// Push constants, matched by name to FillPC.
m.p = flineCamera.p
m.m = flineCamera.m
m.vertices = { layers.fills.buffer }
m.count = { layers.fills.count }

loop {
    m.resize(3 * layers.fills.count)
}
