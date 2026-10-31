// 1. hello triangle
//
// Triangles whose vertex positions are written from Kotlin into a GPU buffer every frame. The vertex shader
// reads that buffer through a buffer reference, so the mesh carries no vertex attributes at all.
// Needs the renderer window box as its parent.
//
// The `vertex` and `fragment` properties hold the shaders; open them with the editor's property switcher
// (`f1` or `ctrl-P`).

// A shader module belonging to this box, drawing filled triangles
var module = moduleHere { primitiveType = triangles }

// ... and a mesh, which runs the shader with some data
var m = Mesh()

// Connect the mesh to the module under the name "someMesh"
module += m named "someMesh"

// ... and connect the module to the renderer (under the name of this box)
renderer += module

// Now a buffer to hold the vertex positions
var vertexData = defaultBuffer {
    perFrame = true
    size = 3.vec4s
}
    .buffer

// `size = 3.vec4s` is only a starting size: the buffer grows when a write needs more room, as the loop below
// does (it writes up to 150 vec4s).
//
// `perFrame = true` gives one buffer per frame in flight, so the CPU can write the next frame's data while
// the GPU is still reading the current frame's, without waiting.

// Connect the buffer to the mesh. `m.vertexData` exists because Field has read the vertex shader and found
// its `vertexData` buffer reference; it has the matching type.
m.vertexData = { vertexData }

// Nothing has been written to the buffer yet. That is fine, because the mesh has no vertices until
// `m.resize` is called below.

// Triangles that move in circles
var t = 0.0
fun polar(t: Double, r: Double) = vec(Math.sin(t), Math.cos(t)) * r.f

loop {
    t += 0.01
    var num = 0

    var numVertToWrite = (Math.sin(t * 0.1) * 20 + 30).i // the triangle count changes over time

    vertexData.write {
        for (x in 0 until numVertToWrite) {
            val offset = vec(x * 0.02, 0, 0)
            put(vec(polar(t, 0.5), 0) + offset)
            put(vec(polar(t + 0.4, 0.4), 0) + offset)
            put(vec(polar(t + 0.9, 0.3), 0) + offset)

            num += 3
        }
    }

    // Tell the mesh how many vertices to draw. Every 3 make a triangle; what each vertex means is up to the
    // vertex shader.
    m.resize(num)

    // returning true keeps the loop running
    true
}

// `loop` is the last thing in this script, so its body runs once per animation frame while the box is
// launched (`alt-mouse-swipe` or `alt-up`).
