/*
Textures from a glTF file.

gltfModelWithTextureHere is gltfModelHere plus the file's images, bound as the sampler array
`uTextures` that the fragment shader declares (open "fragment" in the property switcher). The array
gets exactly as many images as the shader declares, so a file with more binds its first ones.
Run this box beneath the renderer (`window`) box.

The file is the Khronos toy car in the docs assets, read relative to the directory Field runs from.
*/
var (module, meshes) = gltfModelWithTextureHere("public/docs/assets/fabricate_demo/ToyCar.glb")

var m = Mesh()
module += "car" to m
renderer += "boo" to module

val camera = Camera()
camera.p.orthoness = 0.3
camera.p.matchDepth = 2.0
camera.target = vec(0, 0.4, 0)
camera.at = vec(-2.2, 1.8, 2.9)
KeyCamera(camera = camera)

// The same placement as the untextured lesson: the file's rotation, scaled to about four units long.
val model = Matrix4f().rotateX(Math.PI.toFloat() / 2).scale(0.01f)
meshes[0].meshes[0].applyToMesh(m, camera, model)
camera.p()

loop {
    frame()
}
