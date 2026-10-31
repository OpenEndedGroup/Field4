/*
Debugging: printf from a shader. Run this in a box beneath the renderer (`window`) box.

The Kotlin is a cut-down hello triangle; the lesson is in the vertex shader (open `vertex` from the
editor's property switcher). It enables `GL_EXT_debug_printf` and calls `debugPrintfEXT` for vertex 0
only, so one line per draw reaches the log. Printing goes through the Vulkan validation layer
(`Options.validate`, on by default, or `Options.shaderDebugPrintf`).
*/

var module = moduleHere { primitiveType = triangles }

var m = Mesh()
module += m named "someMesh"
renderer += module

var vertexData = defaultBuffer {
    perFrame = true
    size = 3.vec4s
}
    .buffer

// `m.vertexData` is reflected from the `vertexData` buffer reference in the vertex shader's push constants
m.vertexData = { vertexData }
var t = 0.0

loop {
    t += 0.01
    var num = 0

    var numVertToWrite = 3

    // writes the same triangle three times, so the three copies draw on top of one another
    vertexData.write {
        for (x in 0 until numVertToWrite) {
            put(vec(0, 0, 0))
            put(vec(1, 0, 0))
            put(vec(0, 1, 0))
            num += 3
        }
    }

    // the number of vertices to draw
    m.resize(num)
    true
}
