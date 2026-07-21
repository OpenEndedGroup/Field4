// we can defined this first
shaderTemplate["col"] = mapOf("fixedColor" to Resolver { "vec4(1,0.75,0,1)" } )


// then there's an exection break here
//%- 
var module = moduleHere {
    primitiveType = triangle_strip
}

var m  = Mesh()

module += m named "my mesh"
renderer += module

m.set(3, 3) {
    pos.add(0,0,0, 0.5,0,0, 0.5,0.5,0)
    tri.add(0,1,2)
}
