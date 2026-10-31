// A module is one draw pipeline: this box's `vertex` and `fragment` shaders plus the meshes it
// draws. `renderer` is inherited from the renderer parent box, and `+=` puts the module on screen.
renderer += moduleHere {
    primitiveType = triangle_strip

    // A mesh holds vertex buffers whose names come from the vertex shader's `in` attributes
    // (`pos`, `col`), plus the index buffer `tri`. Re-running the box replaces it by name.
    +Mesh("my mesh") {
        // Room for 3 vertices and 3 indices; inside, `pos`, `col` and `tri` append data.
        set(3, 3) {
            // x, y, z for each of the three corners, in clip space (-1..1)
            pos.add(0, 0, 0, 0.5, 0, 0, 0.5, 0.5, 0)

            // one RGBA colour per vertex, `it` running 0, 1, 2
            3 {
                col.add(0, it / 3.0, 1 - it / 3.0, 1)
            }
            tri.add(0, 1, 2)
        }
    }
}
