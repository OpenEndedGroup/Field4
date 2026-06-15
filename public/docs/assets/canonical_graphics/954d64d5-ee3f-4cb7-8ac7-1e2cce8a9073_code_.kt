/** this is a little example that draws a triangle (using more conventional vertex attributes and adds a keyboard controlled camera

as usual we need a shader module and a mesh for the module:
*/
var module = moduleHere()
var m = Mesh()

/*
This gets us everything we need to start rendering things. In particular, we get a vertex shader:

[[property vertex]]
*/

    // and a fragment shader:

/*
[[property fragment]]
*/

// and we connect these to together
module += m named "mainMesh"
renderer += module

/** now we make a camera. A Camera is really just a convient way of setting two matricies, `p` and `m`:

* `p` is the _projection matrix_ and it converts from the 3d space of the world to the 2d space of the screen in ways that follow the laws of perspective (in particular, things further away are proprotionally smaller)

* `m` is the _model_view` matrix_ and this accounts for the rotation and translation of the camera _and_ the model

*/
val camera = Camera()
m.p =  camera.p
m.m =  camera.m

/**
here's our triangle, written using vertex attributes. `3` vertices and `1` triangle
*/
m.set(3, 1) {
    val z = 0.0
    pos.add(0,0,0+z, 1.0,0.1,z, 1,1,z)
    tri.add(0,1,2)
}


// and here's our handy keyboard control over the camera.
var kc = KeyCamera(camera=camera)
