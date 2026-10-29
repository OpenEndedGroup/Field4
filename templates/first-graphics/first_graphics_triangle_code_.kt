// A module connects this box's vertex and fragment shaders to its meshes.
// Re-launching replaces the module under this box's name.
renderer += moduleHere {
    primitiveType = triangle_strip
    +Mesh("triangle") {
        // Three clip-space positions (-1..1), three RGBA colours and one triangle.
        set(3, 3) {
            pos.add(0, 0, 0, 0.5, 0, 0, 0.5, 0.5, 0)
            3 {
                col.add(0, it / 3.0, 1 - it / 3.0, 1)
            }
            tri.add(0, 1, 2)
        }
    }
}

/*
 Switch to the shaders with F1 or Ctrl+P:
 [[property vertex]]
 [[property fragment]]
 */
