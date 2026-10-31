/*
3b. draw particles

Draws the particles computed by "3b. compute particles" as one small ring each, reading the positions
directly from that box's GPU buffer. Needs "3b. compute particles" as its parent (which itself sits
beneath the renderer window box).

The `vertex` property builds a square (two triangles, six vertices) per particle from the buffer; the
`fragment` property cuts a soft-edged ring out of each square. Open them with the editor's property
switcher (`f1` or `ctrl-P`).
*/
var module = moduleHere {
    primitiveType = triangles
}

var m = Mesh()

module += "mainMesh" to m
renderer += "boo2" to module

// `outputPositions` is inherited from the compute parent, which published it with `prop`
m.inData = { outputPositions }

// Inherit the count from the same parent that owns the buffers.
val N = particleCount

// keeps the particles round whatever the window's shape
m.aspectRatio = { window.width / window.height.toFloat() }
// 6 vertices per particle and no index buffer (0 triangles means non-indexed drawing)
m.set(N * 2 * 3, 0) {}

m
