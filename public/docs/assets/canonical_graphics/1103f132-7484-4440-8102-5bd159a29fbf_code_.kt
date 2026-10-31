/*
5b. some templating: Kotlin supplies GLSL source that is spliced into a shader before it compiles.
Parent: window. The fragment shader (open it with the editor's property switcher) writes
{{ col.fixedColor }}, which is replaced by the string the Resolver below returns.
A Resolver returns GLSL source text, not a value: changing it recompiles the shader.
*/
shaderTemplate["col"] = mapOf("fixedColor" to Resolver { "vec4(1,0.75,0,1)" })

// `//%-` is Field's execution break. The template is registered above it, so it exists before
// moduleHere below reads the shader.
//%-
var module = moduleHere {
    primitiveType = triangle_strip
}

var m = Mesh()

module += m named "my mesh"
renderer += module

// One triangle. The vertex shader uses positions directly, with no camera.
m.set(3, 3) {
    pos.add(0, 0, 0, 0.5, 0, 0, 0.5, 0.5, 0)
    tri.add(0, 1, 2)
}
