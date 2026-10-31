/*
Slang: hello triangle. Run this in a box beneath the renderer (`window`) box.

The Kotlin here is the same as "1. hello triangle"; what changes is the shader language. This box has a
`vertex_fragment` property holding one Slang source with both stages (`vsMain` and `fsMain`). When that
property is non-blank it wins over `vertex`, `fragment`, `vertex_s` and `fragment_s`, so the GLSL
properties this box also carries are not used. Open `vertex_fragment` from the editor's property
switcher (`f1` or `ctrl-P`) to read or edit it. Compiling it needs `slangc` on the machine.

Nothing below depends on the language: reflection reads the compiled shader's interface, so the mesh
accessors (`m.vertexData`) come out the same as they do for GLSL.
*/

// A shader module owned by this box that draws filled triangles
var module = moduleHere { primitiveType = triangles }

// ... and a mesh, which runs that module's shaders over some data
var m = Mesh()

// Connect the mesh to the module under the name "someMesh"
module += m named "someMesh"

// ... and connect the module to the renderer (under the name of this box)
renderer += module

/*
A buffer for the vertex positions.
- `size = 3.vec4s` is a starting size. The loop below writes up to 150 vec4s, and the buffer grows to
  fit what is written rather than failing.
- `perFrame = true` gives one Vulkan buffer per frame in flight, so the CPU can write this frame's
  data while the GPU is still reading an earlier frame's, without waiting.
*/
var vertexData = defaultBuffer {
    perFrame = true
    size = 3.vec4s
}
    .buffer

// Hand the buffer to the mesh. `m.vertexData` exists, with the right type, because Field reflected the
// shader and found the `vertexData` pointer in its push constants.
m.vertexData = { vertexData }

// Nothing has been written to the buffer yet. That is fine: the mesh draws nothing until it is given a
// vertex count with `m.resize` below.

// A small animation: a row of triangles turning in circles
var t = 0.0
fun polar(t: Double, r: Double) = vec(Math.sin(t), Math.cos(t)) * r.f

loop {
    t += 0.01
    var num = 0

    // the number of triangles changes over time: vertices can be added and removed while running
    var numVertToWrite = (Math.sin(t * 0.1) * 20 + 30).i

    vertexData.write {
        for (x in 0 until numVertToWrite) {
            val offset = vec(x * 0.02, 0, 0)
            put(vec(polar(t, 0.5), 0) + offset)
            put(vec(polar(t + 0.4, 0.4), 0) + offset)
            put(vec(polar(t + 0.9, 0.3), 0) + offset)

            num += 3
        }
    }

    // Tell the mesh how many vertices to draw. With `primitiveType = triangles` every three make a
    // triangle; where each vertex goes is up to the vertex shader.
    m.resize(num)

    // `loop` ignores this value and keeps running, once per frame, until the box is stopped
    true
}

// Because `loop` is the last expression in the box, its body runs once per animation frame when the box is
// launched (`alt-mouse-swipe` or `alt-up`).
