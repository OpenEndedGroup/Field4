var (module, meshes) = gltfModelWithTextureHere("/Users/marc/temp/glTF-Sample-Models/2.0/TextureLinearInterpolationTest/glTF/TextureLinearInterpolationTest.gltf")
//var (module, meshes) = gltfModelWithTextureHere("/Users/marc/Desktop/badTexture.gltf")


var m = Mesh()

module += "let's go" to m
renderer += "boo" to module

val camera = Camera()
camera.p.orthoness = 0.3
camera.p.matchDepth = 2.0

KeyCamera(camera=camera)
meshes[0].meshes[2].applyToMesh(m, camera)



camera.at = vec(0,0,-10)

camera.p()

loop {
    module.VertexData.modelView = camera.m() * meshes[0].meshes[2].bindPose.m
    module.VertexData.proj = camera.p()
    frame()
}
