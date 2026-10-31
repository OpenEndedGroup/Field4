/*
Loading a glTF model.

gltfModelHere reads a glTF file and returns a module whose shaders already match the file's vertex
layout, together with the meshes it found. Run this box beneath the renderer (`window`) box.
The vertex shader it writes (open "vertex" in the editor's property switcher) unpacks position,
normal, uv and colour from a flat float buffer by stride; the fragment shader shades by the
view-space normal.

The file is the Khronos toy car in the docs assets, read relative to the directory Field runs from.
*/
var (module, meshes) = gltfModelHere("public/docs/assets/fabricate_demo/ToyCar.glb")

var m = Mesh()
module += "car" to m
renderer += "boo" to module

// orthoness blends between perspective (0) and orthographic (1) projection.
val camera = Camera()
camera.p.orthoness = 0.3
camera.p.matchDepth = 2.0
camera.target = vec(0, 0.4, 0)
camera.at = vec(-2.2, 1.8, 2.9)

// Keyboard controls for the camera; R resets it.
KeyCamera(camera = camera)

// The file stands the car up and scales it to centimetres (a 0.0001 node scale); this model matrix
// keeps the rotation and makes it about four units long. meshes[model].meshes[primitive]: copy
// primitive 0 of model 0 into our mesh.
val model = Matrix4f().rotateX(Math.PI.toFloat() / 2).scale(0.01f)
meshes[0].meshes[0].applyToMesh(m, camera, model)
camera.p()

loop {
    frame()
}
