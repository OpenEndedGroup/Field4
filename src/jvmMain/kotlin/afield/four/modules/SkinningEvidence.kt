@file:Suppress("unused")

package afield.four.modules

import afield.stdlib.MeshView
import afield.stdlib.TriMesh
import afield.stdlib.TriMeshBuilder
import afield.stdlib.Vec3
import afield.stdlib.bounds
import afield.stdlib.renderPng
import java.io.File

/**
 * Regenerate the rigged fixture, measure the skinning, and write frames a person can look at.
 *
 * `./gradlew --no-daemon runSkinningEvidence`
 *
 * This exists because "it compiles" is not verification for anything visual. It writes
 * `dev_docs/agents/fixtures/skinning/bending_cylinder.gltf` from [RiggedFixture], loads it back
 * through the real Assimp path, prints every number the `dev_docs` page quotes **with its
 * population**, and renders the deformation at several times into
 * `dev_docs/agents/evidence/skinning/`.
 *
 * The camera is pinned across frames by merging a pair of tiny triangles at fixed corners into every
 * mesh before rendering: `MeshView.distance` is in multiples of the mesh's *own* bounding-sphere
 * radius and `VisionGeometryPreview` frames the mesh's own bounds, so without an anchor each frame
 * would re-frame itself and a sequence would show a cylinder that never appears to move.
 */
object SkinningEvidence {

    const val FIXTURE: String = RiggedFixture.COMMITTED_PATH
    const val EVIDENCE_DIR: String = "dev_docs/agents/evidence/skinning"

    /**
     * Two 2cm triangles at opposite corners of a fixed box, so every frame in a sequence is framed
     * identically. Invisible at the sizes here, and outside the cylinder so they never occlude it.
     */
    fun anchor(
        minX: Float = -3f, minY: Float = -0.5f, minZ: Float = -2f,
        maxX: Float = 1.5f, maxY: Float = 4.5f, maxZ: Float = 2f,
    ): TriMesh {
        val b = TriMeshBuilder()
        val s = 0.02f
        for ((x, y, z) in listOf(Triple(minX, minY, minZ), Triple(maxX, maxY, maxZ))) {
            val i = b.vertexCount
            b.vertex(x, y, z, normal = Vec3(0f, 1f, 0f))
            b.vertex(x + s, y, z, normal = Vec3(0f, 1f, 0f))
            b.vertex(x, y, z + s, normal = Vec3(0f, 1f, 0f))
            b.triangle(i, i + 1, i + 2)
        }
        return b.build()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val fixture = File(FIXTURE)
        fixture.parentFile?.mkdirs()
        fixture.writeText(RiggedFixture.bendingCylinderGltf())
        println("fixture: ${fixture.path} (${fixture.length()} bytes)")

        // ---------------------------------------------------------------- what Assimp read back
        val model = GltfSupport.loadGltfBasic(FIXTURE, GltfSupport.GltfLoadConfig(loadTextures = false))
        val instance = model.meshes.first { it.skin != null }
        val skin = instance.skin!!
        val sk = skin.skeleton
        println()
        println("=== loader ===")
        println("meshes in file            : ${model.meshes.size}")
        println("skinned mesh              : ${instance.name}, ${instance.buffers.vertexCount} vertices, " +
            "${instance.buffers.indexCount / 3} triangles")
        println("scene nodes               : ${sk.nodes.size} -> ${sk.nodes.map { it.name }}")
        println("node parents              : ${sk.nodes.map { it.parent }}")
        println("bones                     : ${sk.bones.size} -> ${sk.bones.map { "${it.name}@node${it.node}" }}")
        println("clips                     : ${sk.clips.map { "${it.name} ${"%.3f".format(it.durationSeconds)}s " +
            "${it.channels.size}ch ${it.channels.first().samples.size} samples" }}")
        for (b in sk.bones) println("inverseBind ${b.name.padEnd(10)}: ${b.inverseBind}")

        // ---------------------------------------------------------------- weights
        val infl = skin.influences
        println()
        println("=== weights (population: all ${infl.vertexCount} vertices of the skinned mesh) ===")
        println("worst |sum(w) - 1|        : ${infl.worstWeightSumError()}")
        println("vertices with no influence: ${infl.unweightedVertices()}")
        println("influences discarded by the 4-wide cap: ${infl.discardedInfluences} " +
            "(largest discarded weight ${infl.largestDiscardedWeight})")
        val distinct = (0 until infl.vertexCount)
            .map { v -> (0 until GltfSkinning.MAX_INFLUENCES).map { infl.weight[v * 4 + it] } }
            .distinct()
        println("distinct weight tuples    : ${distinct.size} -> $distinct")

        // ---------------------------------------------------------------- bind-pose identity
        val rigid = assimpToTriMesh(FIXTURE)
        val bind = skinnedGltfToTriMesh(FIXTURE, clip = "bend", seconds = 0f)
        println()
        println("=== bind pose (population: all ${rigid.vertexCount} vertices) ===")
        println("unskinned vertices        : ${rigid.vertexCount}")
        println("skinned-at-t0 vertices    : ${bind.vertexCount}")
        var exact = 0
        var worst = 0f
        for (v in 0 until minOf(rigid.vertexCount, bind.vertexCount)) {
            val a = rigid.position(v); val b = bind.position(v)
            if (a.x == b.x && a.y == b.y && a.z == b.z) exact++
            worst = maxOf(worst, maxOf(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y), kotlin.math.abs(a.z - b.z)))
        }
        println("bit-identical vertices    : $exact / ${rigid.vertexCount}")
        println("worst component difference: $worst")

        // ---------------------------------------------------------------- deformation
        println()
        println("=== deformation (clip 'bend') ===")
        for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val m = skinnedGltfToTriMesh(FIXTURE, clip = "bend", seconds = t)
            var maxD = 0f
            var tipX = 0f
            var tipCount = 0
            for (v in 0 until m.vertexCount) {
                val a = rigid.position(v); val b = m.position(v)
                val d = kotlin.math.sqrt(
                    (a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z)
                )
                if (d > maxD) maxD = d
                // The tip ring: y = 4 in the bind pose, weight 1 to boneUpper.
                if (kotlin.math.abs(a.y - 4f) < 1e-4f) { tipX += b.x; tipCount++ }
            }
            println("t=%.2f  max |displacement| = %.6f   mean tip x = %.6f  (over %d tip vertices of %d)"
                .format(t, maxD, tipX / tipCount, tipCount, m.vertexCount))
        }

        // ---------------------------------------------------------------- frames
        val out = File(EVIDENCE_DIR)
        out.mkdirs()
        println()
        println("=== frames ===")
        for ((name, spec) in frames()) {
            val mesh = spec()
            val png = (mesh + anchor()).renderPng(
                MeshView(yawDegrees = 0.0, pitchDegrees = 8.0, distance = 2.4, width = 560, height = 560),
            )
            val f = File(out, name)
            f.writeBytes(png)
            val b = mesh.bounds()
            println("${f.path}  ${png.size} bytes  x in [%.3f, %.3f]  y in [%.3f, %.3f]"
                .format(b.min.x, b.max.x, b.min.y, b.max.y))
        }
        println()
        println("done")
    }

    /** The frames written by [main], named so the sequence reads in sorted order. */
    fun frames(): List<Pair<String, () -> TriMesh>> = listOf(
        "bend_t000.png" to { skinnedGltfToTriMesh(FIXTURE, clip = "bend", seconds = 0f) },
        "bend_t050.png" to { skinnedGltfToTriMesh(FIXTURE, clip = "bend", seconds = 0.5f) },
        "bend_t100.png" to { skinnedGltfToTriMesh(FIXTURE, clip = "bend", seconds = 1f) },
        "curl_t100.png" to { skinnedGltfToTriMesh(FIXTURE, clip = "curl", seconds = 1f) },
        "blend_bend_curl.png" to {
            skinnedGltfToTriMesh(FIXTURE) { s ->
                s.blendedPalette(s.clip("bend"), 1f, s.clip("curl"), 1f, 0.5f)
            }
        },
    )
}
