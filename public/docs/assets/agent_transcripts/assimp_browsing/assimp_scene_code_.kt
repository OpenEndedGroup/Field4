import afield.graphics.scene.GltfSupport

// Assimp's view of a glTF file: nodes, meshes, materials, animations
val scene = GltfSupport.loadModel("dev_docs/agents/fixtures/path_tracing/transforms.gltf")!!
column("columns", scene, width = 1300)
tree("tree", scene, width = 700)
displayOpen("columns")
displayOpen("tree")
