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
        // ---------------------------------------------------------------- the adversarial fixture
        val stress = java.io.File(StressFixture.COMMITTED_PATH)
        stress.parentFile?.mkdirs()
        stress.writeText(StressFixture.shearAndCapGltf())
        println()
        println("=== stress fixture: ${stress.path} (${stress.length()} bytes) ===")
        val sModel = GltfSupport.loadGltfBasic(StressFixture.COMMITTED_PATH, GltfSupport.GltfLoadConfig(loadTextures = false))
        val sSkin = sModel.meshes.firstNotNullOf { it.skin }
        println("nodes  ${sSkin.skeleton.nodes.map { it.name }}")
        println("bones  ${sSkin.skeleton.bones.map { it.name }}")
        val si = sSkin.influences
        println("population: all ${si.vertexCount} vertices")
        println("influences discarded by the 4-wide cap: ${si.discardedInfluences}, " +
            "largest discarded weight ${si.largestDiscardedWeight}")
        println("worst |sum(w) - 1| after the cap and renormalisation: ${si.worstWeightSumError()}")
        for (v in 0 until si.vertexCount) {
            println("  vertex $v joints ${(0..3).map { si.jointIndex[v * 4 + it] }} " +
                "weights ${(0..3).map { si.weight[v * 4 + it] }}")
        }

        val sRest = posedGltfToTriMesh(StressFixture.COMMITTED_PATH) { it.palette(null, 0f) }
        val sShear = skinnedGltfToTriMesh(StressFixture.COMMITTED_PATH, clip = "shear", seconds = 1f)
        val sApprox = skinnedGltfToTriMesh(
            StressFixture.COMMITTED_PATH, clip = "shear", seconds = 1f,
            options = GltfSkinning.SkinningOptions(exactNormalMatrix = false),
        )
        val sRigid = assimpToTriMesh(StressFixture.COMMITTED_PATH)
        println()
        println("plate node is translated (0, 10, 0), which glTF says to IGNORE for a skinned mesh:")
        println("  skinned at rest, plate y in [%.4f, %.4f]".format(
            (0..3).minOf { sRest.position(it).y }, (0..3).maxOf { sRest.position(it).y }))
        println("  assimpToTriMesh (rigid path, applies it) y in [%.4f, %.4f]".format(
            (0 until sRigid.vertexCount).minOf { sRigid.position(it).y },
            (0 until sRigid.vertexCount).maxOf { sRigid.position(it).y }))
        println()
        val face = sShear.faceNormal(0)
        fun dot(a: Vec3, b: Vec3) = a.x * b.x + a.y * b.y + a.z * b.z
        println("sheared plate face normal        ${face}  (predicted ${StressFixture.shearedNormal.toList()})")
        println("exact inverse-transpose vertex normal dot face = %.6f".format(dot(sShear.normal(0)!!, face)))
        println("plain blended-3x3 vertex normal  dot face = %.6f  (predicted 0.8)".format(dot(sApprox.normal(0)!!, face)))
        println()
        val dx = sShear.position(4).x - sRest.position(4).x
        println("six-influence triangle x displacement = %.6f".format(dx))
        println("  capped prediction   %.6f".format(StressFixture.CAPPED_DISPLACEMENT))
        println("  uncapped prediction %.6f".format(StressFixture.UNCAPPED_DISPLACEMENT))

        // Two more frames: the shear is a thing you can see, not only a dot product.
        val stressAnchor = anchor(minX = -1.6f, minY = -0.4f, minZ = -1.4f, maxX = 6.6f, maxY = 3.4f, maxZ = 1.4f)
        val stressView = MeshView(yawDegrees = 16.0, pitchDegrees = 14.0, distance = 2.1, width = 700, height = 420)
        for ((name, mesh) in listOf("shear_rest.png" to sRest, "shear_t100.png" to sShear)) {
            val f = File(out, name)
            f.writeBytes((mesh + stressAnchor).renderPng(stressView))
            println("${f.path}  ${f.length()} bytes")
        }

        // ---------------------------------------------------------------- throughput
        // Not a benchmark -- one JVM, one warm-up, wall clock -- but a number is better than a
        // shrug when someone asks whether CPU skinning is fast enough to drive from a slider.
        run {
            val big = GltfSupport.loadGltfBasic(FIXTURE, GltfSupport.GltfLoadConfig(loadTextures = false))
            val inst = big.meshes.first { it.skin != null }
            val sk2 = inst.skin!!
            val n = sk2.influences.vertexCount
            val pos = FloatArray(n * 3); val nrm = FloatArray(n * 3)
            val fb = inst.buffers.vertexData.asFloatBuffer()
            for (v in 0 until n) {
                for (k in 0..2) { pos[v * 3 + k] = fb.get(v * 12 + k); nrm[v * 3 + k] = fb.get(v * 12 + 3 + k) }
            }
            val pal = sk2.skeleton.palette(sk2.skeleton.clip("bend"), 1f)
            repeat(2000) { GltfSkinning.skin(pos, nrm, sk2.influences, pal) }
            val reps = 20000
            val t0 = System.nanoTime()
            repeat(reps) { GltfSkinning.skin(pos, nrm, sk2.influences, pal) }
            val ns = (System.nanoTime() - t0).toDouble()
            println()
            println("=== throughput (one JVM, 2000 warm-up + $reps timed calls of $n vertices) ===")
            println("%.1f ns per vertex, %.2f M vertices/s, exact inverse-transpose normals on"
                .format(ns / (reps.toDouble() * n), reps.toDouble() * n / ns * 1000.0))
            val t1 = System.nanoTime()
            val plain = SkinningOptionsPlain
            repeat(reps) { GltfSkinning.skin(pos, nrm, sk2.influences, pal, plain) }
            val ns1 = (System.nanoTime() - t1).toDouble()
            println("%.1f ns per vertex, %.2f M vertices/s, with exactNormalMatrix = false"
                .format(ns1 / (reps.toDouble() * n), reps.toDouble() * n / ns1 * 1000.0))
        }

        println()
        println("done")
    }

    private val SkinningOptionsPlain = GltfSkinning.SkinningOptions(exactNormalMatrix = false)

    /** The frames written by [main], named so the sequence reads in sorted order. */
    fun frames(): List<Pair<String, () -> TriMesh>> = listOf(
        "bend_t000.png" to { skinnedGltfToTriMesh(FIXTURE, clip = "bend", seconds = 0f) },
        "bend_t050.png" to { skinnedGltfToTriMesh(FIXTURE, clip = "bend", seconds = 0.5f) },
        "bend_t100.png" to { skinnedGltfToTriMesh(FIXTURE, clip = "bend", seconds = 1f) },
        "curl_t100.png" to { skinnedGltfToTriMesh(FIXTURE, clip = "curl", seconds = 1f) },
        "blend_bend_curl.png" to {
            posedGltfToTriMesh(FIXTURE) { s ->
                s.blendedPalette(s.clip("bend"), 1f, s.clip("curl"), 1f, 0.5f)
            }
        },
    )
}
