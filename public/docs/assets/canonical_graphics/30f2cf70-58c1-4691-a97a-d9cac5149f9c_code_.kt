/*
5. included: a vertex shader that pulls its declarations in from another property.
Parent: window. Open the vertex property in the editor's property switcher: the line
{% property "vertex_included" %} is replaced, before compilation, by the GLSL held in the
`vertex_included` property (attributes `pos` and `col`, and the push constants `pc`).
*/
var module = moduleHere()
var m = Mesh()

// Connect the mesh to the module, and the module to the renderer.
module += "mainMesh" to m
renderer += module

val camera = Camera()
m.p = camera.p
m.m = camera.m

KeyCamera(camera = camera)

// Ten triangles (30 vertices): two rows of five, spread out along z.
m.set(30, 10) {
    var base = 0
    5.alphas { z ->
        pos.add(0, 0, 0 + z * 5.0, 1.0, 0.1, z * 5.0, 1, 1, z * 5.0)
        col.add(1, 1, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1)
        tri.add(base, base + 1, base + 2)
        base += 3
    }

    5.alphas { z ->
        pos.add(3, 3, 0 + z * 5.0, 4.0, 3.1, z * 5.0, 4, 4, z * 5.0)
        col.add(1, 1, 0, 1, 1, 0, 0, 1, 1, 0, 0, 1)
        tri.add(base, base + 1, base + 2)
        base += 3
    }
}
