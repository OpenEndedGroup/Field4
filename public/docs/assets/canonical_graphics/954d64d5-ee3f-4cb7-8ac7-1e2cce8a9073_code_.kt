/*
2. hello keyboard camera

One triangle, built from ordinary vertex attributes, seen through a Camera you can move with the keyboard
while the renderer window has focus. Needs the renderer window box as its parent.

The `vertex` property holds the vertex shader (open it with the editor's property switcher, `f1` or
`ctrl-P`): it multiplies each position by the `p` and `m` push constants. The `fragment` shader paints
one flat color.
*/

// As usual, a shader module and a mesh for it
var module = moduleHere()
var m = Mesh()

// Connect the mesh to the module, and the module to the renderer
module += m named "mainMesh"
renderer += module

/*
A Camera is a convenient way of producing two matrices, `p` and `m`:

`p` is the projection matrix. It maps the 3D space of the world onto the screen following the rules of
perspective (things further away are proportionally smaller).

`m` is the model-view matrix. It accounts for the rotation and translation of the camera and the model.

The mesh's `m.p` and `m.m` match the vertex shader's push constants. They are given the camera's matrix
objects, which are read again every frame, so moving the camera needs no further code here.
*/
val camera = Camera()
m.p = camera.p
m.m = camera.m

// The triangle, written with vertex attributes: 3 vertices and 1 triangle
m.set(3, 1) {
    val z = 0.0
    pos.add(0, 0, 0 + z, 1.0, 0.1, z, 1, 1, z)
    tri.add(0, 1, 2)
}

// Keyboard control over the camera, read from the renderer window (R resets the camera). Because it is
// registered under this property's name, re-running the box replaces the controls instead of adding more.
var kc by keyCamera(camera = camera)
