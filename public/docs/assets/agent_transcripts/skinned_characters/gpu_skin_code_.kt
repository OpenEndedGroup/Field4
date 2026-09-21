import afield.four.modules.CpuSkinner
import afield.four.modules.RiggedFixture
import afield.stdlib.linalg.Matrix4f

// The same deformation, on the GPU, through Field's OWN glTF module and its OWN bundled shaders.
//
// No shader change and no joint-matrix buffer: CpuSkinner rewrites the position and normal fields
// of the packed 12-float vertex buffer that applyToMesh already uploads. At the measured 17.7 M
// vertices/s that is 2.8 ms for a 50,000-vertex character.
//
// EDIT `poseTime` AND RE-RUN. 0 is a straight tube; 1 is a 60-degree bend with the tip at
// x = -1.982. The line it prints is the tip's x and the mesh's top y, so the pose is a number as
// well as a picture.
val poseTime = 1.0f

// This box needs a renderer in scope. Under `field_backdoor.py --graphics-context` that is the
// agent's context box, which sets `renderer` as a raw property but does not declare it with the
// `prop<Renderer>(renderer)` macro a hand-built renderer box uses -- so the bare name does not
// resolve and fromContext() finds it by type instead. In a document with your own renderer box as
// the parent, `renderer` resolves directly and this line is redundant but harmless.
val renderer: Renderer = fromContext()

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

// ----------------------------------------------------------------------------------------------
// Why this poses ONCE rather than animating, and what was measured
//
// An animating version is three lines:
//
//     var n = 0
//     loop { skinner.pose("bend", (n % 120) / 60f)
//            inst.applyToMesh(m, camera, Matrix4f(), reuseBuffers = true); n++; frame() }
//
// and it runs. What it does NOT survive is the agent screenshot path: `native-screenshot` times
// out after 15 s against it, so no agent can photograph the result, and a demo nobody has looked
// at is a claim. Bisected on 2026-09-19, one variable at a time, against the same scene:
//
//   nothing in the loop                                        capture OK
//   skinner.pose() every frame, 118 fps, no upload             capture OK
//   getPropertyRaw() every frame                               capture TIMES OUT
//   inst.applyToMesh() every frame, reuseBuffers = true        capture TIMES OUT
//
// So the deformation is innocent -- posing 60 vertices every frame costs nothing and photographs
// fine. It is the per-frame re-upload (and, separately, a per-frame box-property read) that the
// direct window capture cannot get past. Left as a named finding rather than a fix, because
// diagnosing the renderer's capture path is a different job from skinning.
