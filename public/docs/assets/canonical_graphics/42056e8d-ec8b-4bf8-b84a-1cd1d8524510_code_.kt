import afield.four.util.*

class MyPC(
    var a : Vec4,
    var b : Vec4,
    var c : Vec4
) : PushConstants<MyPC>()

var pc = MyPC(vec(1,0,0,1),vec(1,2,3,4),vec(1,2,3,4))

shaderTemplate["pc"] = mapOf("dec" to Resolver { pc.glsl() })

pc.a.x=0.3f
pc.otherDeclarations = "layout(offset=${pc.nextFreeOffset(16)}) uint foo;"

// then there's an exection break here
//%-
var module = moduleHere {
    primitiveType = triangle_strip
}

var m  = Mesh()

module += m named "my mesh"
renderer += module

m.push.bindAll { pc }
m.foo = { 10u }

m.set(3, 3) {
    pos.add(0,0,0, 0.5,0,0, 0.5,0.5,0)
    tri.add(0,1,2)
}
