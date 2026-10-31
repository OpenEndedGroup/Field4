/*
Loading a glTF model.

gltfModelHere reads a glTF file and returns a module whose shaders already match the file's vertex
layout, together with the meshes it found. Run this box beneath the renderer (`window`) box.
The vertex shader (open "vertex" in the editor's property switcher) unpacks position, normal, uv and
colour from a flat float buffer by stride; the fragment shader shades by the view-space normal.

The path is relative to the directory Field runs from: clone the Khronos glTF-Sample-Models
collection there, or point it at your own file.
*/
var (module, meshes) = gltfModelHere("glTF-Sample-Models/2.0/Box/glTF/Box.gltf")

var m = Mesh()

module += "let's go" to m
renderer += "boo" to module

// orthoness blends between perspective (0) and orthographic (1) projection.
val camera = Camera()
camera.p.orthoness = 0.3
camera.p.matchDepth = 2.0

// Keyboard controls for the camera; R resets it.
KeyCamera(camera = camera)
// meshes[model].meshes[primitive]: copy primitive 0 of model 0 into our mesh.
meshes[0].meshes[0].applyToMesh(m, camera)

camera.at = vec(0, 0, -10)

camera.p()

// VertexData is the shader's uniform block, assigned member by member each frame. modelView(camera) is
// view * the primitive's own node transform, so the model sits where the file places it.
loop {
    module.VertexData.modelView = meshes[0].meshes[0].modelView(camera)
    module.VertexData.proj = camera.p()
    frame()
}
