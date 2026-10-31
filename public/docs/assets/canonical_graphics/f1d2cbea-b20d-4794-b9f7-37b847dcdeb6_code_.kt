// Textures from a glTF file.
//
// gltfModelWithTextureHere is gltfModelHere plus the file's images, bound as the sampler array
// `uTextures` that the fragment shader declares (open "fragment" in the property switcher).
// Run this box beneath the renderer (`window`) box. The path is relative to the directory Field runs
// from: clone the Khronos glTF-Sample-Models collection there, or point it at your own file.
var (module, meshes) = gltfModelWithTextureHere(
    "glTF-Sample-Models/2.0/TextureLinearInterpolationTest/glTF/TextureLinearInterpolationTest.gltf",
)

var m = Mesh()

module += "let's go" to m
renderer += "boo" to module

val camera = Camera()
camera.p.orthoness = 0.3
camera.p.matchDepth = 2.0

KeyCamera(camera = camera)
// Draw primitive 2 of model 0.
meshes[0].meshes[2].applyToMesh(m, camera)

camera.at = vec(0, 0, -10)

camera.p()

// The view matrix times the primitive's bind pose places it with the file's own transform; this is the
// hand-written form of what modelView(camera) computes.
loop {
    module.VertexData.modelView = camera.m() * meshes[0].meshes[2].bindPose.m
    module.VertexData.proj = camera.p()
    frame()
}
