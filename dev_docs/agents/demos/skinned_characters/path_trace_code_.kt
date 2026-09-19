import afield.four.fabricated.GltfSkinPose
import afield.four.fabricated.PathTraceScene
import afield.four.fabricated.importGltf
import afield.four.modules.RiggedFixture
import afield.four.modules.skinnedGltfToTriMesh

// The line this whole branch was aimed at.
//
// PathTraceGltf.kt used to contain, verbatim:
//     require(mesh.mNumBones()==0) { "Skinned glTF meshes need skin deformation before path tracing" }
// It was the only mention of a bone in the entire JVM source tree -- the codebase naming a missing
// capability in its own refusal message. It now traces the DEFORMED mesh at a pose you choose.

val rig = RiggedFixture.locate()

val rest = PathTraceScene()
val restImport = rest.importGltf(rig)
val bent = PathTraceScene()
val bentImport = bent.importGltf(rig, pose = GltfSkinPose("bend", 1f))

println("rest pose   ${restImport.triangleCount} triangles, " +
    "x in [%.4f, %.4f]".format(restImport.boundsMin.x, restImport.boundsMax.x))
println("bend t=1    ${bentImport.triangleCount} triangles, " +
    "x in [%.4f, %.4f]".format(bentImport.boundsMin.x, bentImport.boundsMax.x))
println()
bentImport.warnings.forEach { println("warning: $it") }
println()

// The cross-check worth printing: two completely separate readers of the same deformation. The
// tracer walks Assimp and stages world-space triangles; the CPU bridge packs a 12-float vertex
// buffer and builds a TriMesh. They are two implementations of one formula, so agreeing is
// evidence and disagreeing would localise the bug.
val cpu = skinnedGltfToTriMesh(rig, clip = "bend", seconds = 1f).bounds()
println("CPU bridge  x in [%.4f, %.4f], y in [%.4f, %.4f]".format(cpu.min.x, cpu.max.x, cpu.min.y, cpu.max.y))
println("tracer      x in [%.4f, %.4f], y in [%.4f, %.4f]"
    .format(bentImport.boundsMin.x, bentImport.boundsMax.x, bentImport.boundsMin.y, bentImport.boundsMax.y))
println("max |difference| = %.7f".format(
    maxOf(
        kotlin.math.abs(cpu.min.x - bentImport.boundsMin.x), kotlin.math.abs(cpu.max.x - bentImport.boundsMax.x),
        kotlin.math.abs(cpu.min.y - bentImport.boundsMin.y), kotlin.math.abs(cpu.max.y - bentImport.boundsMax.y),
    )
))
println()
println("An unknown clip name fails the import rather than quietly tracing the rest pose -- try")
println("GltfSkinPose(\"walk\", 1f) above and read the message.")
