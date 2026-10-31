// 5c. some more templating: a push-constant block generated from a Kotlin class.
// Parent: window. PushConstants<T> reflects the class's members into a GLSL push_constant block;
// the fragment shader (open it with the editor's property switcher) splices that in with
// {{ pc.dec }}, calls loadRegs(), and reads member `a` through the generated accessor a().
class MyPC(
    var a: Vec4,
    var b: Vec4,
    var c: Vec4,
) : PushConstants<MyPC>()

var pc = MyPC(vec(1, 0, 0, 1), vec(1, 2, 3, 4), vec(1, 2, 3, 4))

// pc.glsl() emits the generated declaration; the template makes it available as {{ pc.dec }}.
shaderTemplate["pc"] = mapOf("dec" to Resolver { pc.glsl() })

pc.a.x = 0.3f
// A hand-written extra member, placed at the next free 16-byte-aligned offset after the generated ones.
pc.otherDeclarations = "layout(offset=${pc.nextFreeOffset(16)}) uint foo;"

// `//%-` is Field's execution break: the declarations above are in place before moduleHere reads the shader.
//%-
var module = moduleHere {
    primitiveType = triangle_strip
}

var m = Mesh()

module += m named "my mesh"
renderer += module

// Bind the whole Kotlin object to the push constants, and supply the extra member `foo`.
m.push.bindAll { pc }
m.foo = { 10u }

m.set(3, 3) {
    pos.add(0, 0, 0, 0.5, 0, 0, 0.5, 0.5, 0)
    tri.add(0, 1, 2)
}
