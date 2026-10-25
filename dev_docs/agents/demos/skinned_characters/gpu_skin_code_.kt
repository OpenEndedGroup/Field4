/*
Draw a CPU-skinned pose with Field's glTF module and bundled shaders.
CpuSkinner rewrites the packed position/normal data that applyToMesh uploads.

EDIT `poseTime` AND RE-RUN. 0 is a straight tube; 1 is a 60-degree bend with the tip at
x = -1.982. The line it prints is the tip's x and the mesh's top y, so the pose is a number as
well as a picture.
*/
val poseTime = 1.0f

// Launch the connected renderer parent first.

var (module, models) = gltfModelHere(RiggedFixture.locate())

var m = Mesh()
module += "skinned" to m
renderer += "skinned characters" to module

val camera = Camera()
camera.p.orthoness = 0.3
camera.p.matchDepth = 2.0
camera.target = vec(0, 2, 0)
camera.at = vec(2, 3, -8)
camera.p()

val inst = models[0].meshes[0]
val skinner = CpuSkinner(inst)
val out = skinner.pose("bend", poseTime)

// model = identity, NOT the default: a skinned mesh is already in scene space, because glTF 2.0
// 3.7.3.2 says a skinned mesh node's own transform is ignored. The default would apply it twice.
inst.applyToMesh(m, camera, Matrix4f(), reuseBuffers = true)

val minX = (0 until out.vertexCount).minOf { out.position[it * 3] }
val maxY = (0 until out.vertexCount).maxOf { out.position[it * 3 + 1] }
println("gpu skinned at t = %.2f -> tip x %.4f, top y %.4f".format(poseTime, minX, maxY))

loop { frame() }

// Pose and upload once; edit poseTime and rerun to inspect a different bend.
