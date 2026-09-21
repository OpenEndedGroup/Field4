import afield.four.modules.GltfSkinning
import afield.four.modules.GltfSupport
import afield.four.modules.RiggedFixture
import afield.four.modules.assimpToTriMesh
import afield.four.modules.skinnedGltfToTriMesh
import afield.stdlib.MeshView
import afield.stdlib.showMeshGrid

// Field could load a glTF and it could sample per-NODE animation. It could not skin.
//
// One line in the whole of src/jvmMain + src/commonMain mentioned a bone, and it was
// PathTraceGltf.kt refusing to render one: "Skinned glTF meshes need skin deformation before path
// tracing". This box loads a rigged mesh, prints what the loader now reads out of it, and draws
// four poses of it. Box 2 is the one you drag.

val rig = RiggedFixture.locate()
val model = GltfSupport.loadGltfBasic(rig, GltfSupport.GltfLoadConfig(loadTextures = false))
val skin = model.meshes.firstNotNullOf { it.skin }
val sk = skin.skeleton

println("rig  $rig")
println("mesh ${model.meshes.first().buffers.vertexCount} vertices, " +
    "${model.meshes.first().buffers.indexCount / 3} triangles")
println()
println("nodes   ${sk.nodes.map { it.name }}")
println("parents ${sk.nodes.map { it.parent }}   (-1 is the scene root)")
println("bones   ${sk.bones.map { "${it.name} -> node ${it.node}" }}")
println("clips   ${sk.clips.map { "${it.name} (${it.durationSeconds}s, ${it.channels.size} channels)" }}")
println()
println("The skeleton indexes NODES, not bones. Assimp hands over a bone's name and its inverse")
println("bind matrix and no parent -- the hierarchy is in the node tree, a bone's parent chain")
println("routinely runs through nodes that are NOT bones (here: 'armature'), and the skeleton root")
println("is not the mesh's node. Indexing nodes makes all three fall out instead of needing cases.")
println()

// Weights, over a stated population. A skinning bug that shows up as a mesh collapsing toward the
// origin is almost always weights that do not sum to 1, so it is worth being able to see the number.
val infl = skin.influences
println("weights over all ${infl.vertexCount} vertices: worst |sum(w) - 1| = ${infl.worstWeightSumError()}, " +
    "${infl.unweightedVertices()} unweighted, ${infl.discardedInfluences} influences past the " +
    "${GltfSkinning.MAX_INFLUENCES}-wide cap")

// The assertion worth making about a bind pose is EXACTNESS, not closeness: an inverse-bind mistake
// lands a fraction of a percent off, which any tolerance you would reach for waves through.
val rest = assimpToTriMesh(rig)
val atZero = skinnedGltfToTriMesh(rig, clip = "bend", seconds = 0f)
val identical = (0 until rest.vertexCount).count { v ->
    val a = rest.position(v); val b = atZero.position(v)
    a.x == b.x && a.y == b.y && a.z == b.z
}
println("bind pose: $identical of ${rest.vertexCount} vertices bit-identical to the unskinned mesh")

val view = MeshView(yawDegrees = 0.0, pitchDegrees = 8.0, distance = 2.6)
showMeshGrid(
    "bind, t = 0" to atZero,
    "bend, t = 0.5" to skinnedGltfToTriMesh(rig, clip = "bend", seconds = 0.5f),
    "bend, t = 1" to skinnedGltfToTriMesh(rig, clip = "bend", seconds = 1f),
    "curl, t = 1" to skinnedGltfToTriMesh(rig, clip = "curl", seconds = 1f),
    columns = 4, cellWidth = 300, view = view,
)
