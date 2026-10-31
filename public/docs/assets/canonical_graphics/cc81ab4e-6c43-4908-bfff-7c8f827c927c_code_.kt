// 0. traditional triangle
//
// About the smallest drawing there is: a shader module, a mesh with three vertices, one triangle.
// Needs the renderer window box as its parent.
//
// The shaders live on this box too: open the `vertex` and `fragment` properties with the editor's property
// switcher (`f1` or `ctrl-P`). The vertex shader passes `pos` straight through as a clip-space position and
// hands `col` on to the fragment shader, which tints it.

// First a shader module belonging to this box, drawing triangle strips
var module = moduleHere {
    primitiveType = triangle_strip
}

// then a mesh, which holds the vertex data the shaders read
var m = Mesh()

// Connect the mesh to the module, and the module to the renderer
module += m named "my mesh"
renderer += module

// Fill the mesh: 3 vertices and 3 indices. `pos` and `col` are the vertex shader's inputs; `tri` is the
// index list
m.set(3, 3) {
    pos.add(0, 0, 0, 0.5, 0, 0, 0.5, 0.5, 0)

    // one color per vertex, interpolated across the triangle
    3 {
        col.add(0, it / 3.0, 1 - it / 3.0, 1)
    }
    tri.add(0, 1, 2)
}
