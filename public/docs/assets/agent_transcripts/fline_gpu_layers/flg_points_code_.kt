/*
points: a dot at every node of every pointed line in the parent's FLineLayers, six
vertices each, in one draw. Needs "FLine layers" as its parent.
*/
val layers = flineLayers

var module = moduleHere {
    primitiveType = triangles
    depthWrite = false
}
var m = Mesh()
module += m named "points"
renderer += "fline points" to module

// Push constants, matched by name to PointPC.
m.p = flineCamera.p
m.m = flineCamera.m
m.vertices = { layers.points.buffer }
m.count = { layers.points.count }
m.viewport = {
    val extent = renderer.swapChain.swapChainExtent
    vec(extent.width(), extent.height())
}
var sizeScale = 1.0f
m.sizeScale = { sizeScale }

loop {
    m.resize(6 * layers.points.count)
}
