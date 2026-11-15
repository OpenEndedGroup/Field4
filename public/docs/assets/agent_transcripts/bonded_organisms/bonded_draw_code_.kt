// Cells as discs and bonds as thin quads, read straight from the GPU buffers.
// 30 vertices per cell: 6 for its disc, 6 for each of its four bond slots.
var module = moduleHere { primitiveType = triangles }
var m = Mesh()
module += "mainMesh" to m
renderer += "bondedDraw" to module

m.cells = { bondedCells }
m.bonds = { bondedBonds }
m.latest = { bondedClock.latestHalf }
m.capacity = { bonded.capacity }
m.viewX = { 0f }
m.viewY = { 0f }
m.viewHalfHeight = { 19f }
m.aspect = { window.width / window.height.toFloat() }
m.set(bonded.count * 30, 0) {}
m
