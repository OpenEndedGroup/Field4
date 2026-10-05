// this is almost as simple as drawing a triangle gets here. First a shader:
var module = moduleHere {
    primitiveType = triangle_strip
}

    // then a mesh
var m  = Mesh()

    // then some connection
module += m named "my mesh"
renderer += module

/*
This gets us everything we need to start rendering things. In particular, we get a vertex shader:

[[property vertex]]
*/

    // and a fragment shader:

/*
[[property fragment]]
*/

// you can edit these by switching to these properties in the editor `f1` or `ctrl-P`.

// and then 3 vertices and one triangle strip consisting of 3 indices
m.set(3, 3) {
    pos.add(0,0,0, 0.5,0,0, 0.5,0.5,0)

    // for a flourish, we have some color as well
    3 {
        col.add(0,it/3.0,1-it/3.0,1)
    }
    tri.add(0,1,2)
}
