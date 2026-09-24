@file:Suppress("unused")

package afield.four.modules

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * A rigged glTF, generated rather than downloaded.
 *
 * The repository contains no rigged model — `ToyCar.glb`, `quad.glb`, `transforms.gltf` and the rest of
 * `dev_docs/agents/fixtures/path_tracing` are all rigid — and a skinning implementation cannot be
 * tested without one. Rather than pull a third-party asset in, this writes one: a two-bone bending
 * cylinder, as glTF 2.0 text with its buffer inline as a base64 data URI, byte-for-byte reproducible
 * from this source. `afield.four.RiggedFixtureDriftTest` asserts the committed asset still matches.
 *
 * ### Why the numbers are the numbers
 *
 * Every constant here is chosen so that the **bind pose is exact in binary**, not merely close, which
 * is what lets [afield.four.GltfSkinningTest] assert bit-identity against the unskinned mesh and so
 * catch an inverse-bind-matrix mistake that a `1e-5` tolerance would wave through:
 *
 *  - the two joints' bind transforms are translations by `0` and `2`, so their inverse bind matrices
 *    are translations by `0` and `-2` — exactly representable, and their product with the joint's
 *    global bind transform is exactly the identity;
 *  - the animation's first rotation key is the identity quaternion `(0, 0, 0, 1)`, whose rotation
 *    matrix is computed as `1 - 2*(0+0)` and `2*(0 ± 0)` — exactly `1` and `0`;
 *  - the skin weights are drawn from `{0, 0.25, 0.5, 0.75, 1}`, all exact in binary and summing to
 *    exactly `1.0f`, so the blend `Σ wᵢ·I·p` is exactly `p` for any finite `p`.
 *
 * The mesh node is left at the identity transform. glTF 2.0 §3.7.3.2 says a skinned mesh node's own
 * transform is to be ignored, so a non-identity one would make "skinned at bind == unskinned" a
 * statement about which convention the comparison uses rather than about the skinning; leaving it at
 * the identity keeps the oracle about the thing under test.
 *
 * ### Shape
 *
 * An open tube along `+y`: radius `0.5`, height `4`, five rings at `y = 0, 1, 2, 3, 4`, twelve radial
 * segments — 60 vertices, 96 triangles, no caps (an open tube reads perfectly well under back-face
 * culling and the cap vertices would need duplicating to carry their own normals).
 *
 * ### Rig
 *
 * `armature` → `boneLower` (at the origin) → `boneUpper` (at `y = 2`). Skin weight to `boneUpper` is
 * `clamp((y - 1) / 2, 0, 1)`, so the bottom two rings are pure `boneLower`, the middle ring is an
 * even 50/50 blend, and the top two rings are pure `boneUpper`. The 50/50 ring is the one that makes
 * this a *skinning* test rather than a rigid-parenting test.
 *
 * ### Clips
 *
 *  - **`bend`** (0 → 2 s) rotates `boneUpper` about `+z` by `0° → 60° → 0°`. Only one joint moves, so
 *    the tip displacement has a closed form: the tip ring sits at `y = 4`, two units above the joint,
 *    and `|2·(−sin 60°, cos 60° − 1)| = 2`. That exact `2.0` is the floor the test asserts against,
 *    and a `+z` rotation tilts `+y` toward `−x`, which is the direction it asserts.
 *  - **`curl`** (0 → 2 s) rotates *both* joints, `boneLower` by `0° → 25° → 0°` about `+z` and
 *    `boneUpper` by `0° → 55° → 0°`. Nothing about it has a closed form worth writing down; it exists
 *    because a palette that forgets to compose down the parent chain still passes `bend` and fails
 *    this, and because a second clip is what a clip-blend has to have.
 */
object RiggedFixture {

    /** Radial segments around the tube. */
    const val SEGMENTS: Int = 12

    /** Rings along the tube, at `y = 0 .. RINGS - 1`. */
    const val RINGS: Int = 5

    /** Tube radius. */
    const val RADIUS: Float = 0.5f

    /** Where `boneUpper` sits, in the bind pose. */
    const val UPPER_JOINT_Y: Float = 2f

    /** Vertices in the generated mesh. */
    const val VERTEX_COUNT: Int = SEGMENTS * RINGS

    /** Triangles in the generated mesh. */
    const val TRIANGLE_COUNT: Int = SEGMENTS * (RINGS - 1) * 2

    /**
     * The weight this vertex gives to `boneUpper`; `1 - that` goes to `boneLower`.
     *
     * Quantised to quarters on purpose — see the class comment on why the bind pose has to be exact.
     */
    fun upperWeight(y: Float): Float = ((y - 1f) / 2f).coerceIn(0f, 1f)

    /** The generated glTF 2.0 document, as text. Deterministic: same bytes every call. */
    fun bendingCylinderGltf(): String {
        val positions = FloatArray(VERTEX_COUNT * 3)
        val normals = FloatArray(VERTEX_COUNT * 3)
        val joints = ByteArray(VERTEX_COUNT * 4)
        val weights = FloatArray(VERTEX_COUNT * 4)

        for (ring in 0 until RINGS) {
            val y = ring.toFloat()
            val upper = upperWeight(y)
            for (k in 0 until SEGMENTS) {
                val i = ring * SEGMENTS + k
                val theta = 2.0 * Math.PI * k / SEGMENTS
                val cx = Math.cos(theta).toFloat()
                val cz = Math.sin(theta).toFloat()
                positions[i * 3] = RADIUS * cx
                positions[i * 3 + 1] = y
                positions[i * 3 + 2] = RADIUS * cz
                normals[i * 3] = cx
                normals[i * 3 + 1] = 0f
                normals[i * 3 + 2] = cz
                // joint 0 = boneLower, joint 1 = boneUpper; slots 2 and 3 are unused, weight 0.
                joints[i * 4] = 0
                joints[i * 4 + 1] = 1
                weights[i * 4] = 1f - upper
                weights[i * 4 + 1] = upper
            }
        }

        val indices = ShortArray(TRIANGLE_COUNT * 3)
        var t = 0
        for (ring in 0 until RINGS - 1) {
            for (k in 0 until SEGMENTS) {
                val a = ring * SEGMENTS + k
                val b = ring * SEGMENTS + (k + 1) % SEGMENTS
                val c = (ring + 1) * SEGMENTS + k
                val d = (ring + 1) * SEGMENTS + (k + 1) % SEGMENTS
                // (a, c, d) and (a, d, b): both have face normal (tangent.z, 0, -tangent.x), which
                // points outward, so the tube survives back-face culling in the CPU rasterizer.
                indices[t++] = a.toShort(); indices[t++] = c.toShort(); indices[t++] = d.toShort()
                indices[t++] = a.toShort(); indices[t++] = d.toShort(); indices[t++] = b.toShort()
            }
        }

        // Inverse bind matrices, column-major as glTF requires. boneLower binds at the origin so its
        // inverse bind is the identity; boneUpper binds at y = 2 so its inverse bind translates by -2.
        val inverseBind = floatArrayOf(
            1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f,
            1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, -UPPER_JOINT_Y, 0f, 1f,
        )

        val times = floatArrayOf(0f, 1f, 2f)
        fun zQuat(degrees: Double): FloatArray {
            val half = Math.toRadians(degrees) / 2.0
            return floatArrayOf(0f, 0f, Math.sin(half).toFloat(), Math.cos(half).toFloat())
        }
        // Key 0 is the exact identity quaternion, which is what makes t = 0 the bind pose exactly.
        val bendUpper = zQuat(0.0) + zQuat(60.0) + zQuat(0.0)
        val curlLower = zQuat(0.0) + zQuat(25.0) + zQuat(0.0)
        val curlUpper = zQuat(0.0) + zQuat(55.0) + zQuat(0.0)

        // `twist` rotates about the BONE'S OWN AXIS, which is the one case linear blend skinning
        // cannot do. The bones run up +y, so this is a +y rotation, and 180 degrees is where it is
        // worst: at the joint ring the two influences are near 0.5/0.5, and averaging a matrix with
        // its own 180-degree rotation gives diag(0, 1, 0), which sends every vertex of that ring to
        // one point on the axis (SkinnedVolumeTest asserts it).
        // That is the "candy wrapper", and it is why `twist` exists rather than a third bend.
        fun yQuat(degrees: Double): FloatArray {
            val half = Math.toRadians(degrees) / 2.0
            return floatArrayOf(0f, Math.sin(half).toFloat(), 0f, Math.cos(half).toFloat())
        }
        val twistUpper = yQuat(0.0) + yQuat(180.0) + yQuat(0.0)

        val views = ArrayList<IntArray>() // (byteOffset, byteLength)
        val bytes = java.io.ByteArrayOutputStream()
        fun align() { while (bytes.size() % 4 != 0) bytes.write(0) }
        fun view(block: (ByteBuffer) -> Unit, length: Int): Int {
            align()
            val offset = bytes.size()
            val b = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
            block(b)
            bytes.write(b.array())
            views += intArrayOf(offset, length)
            return views.size - 1
        }
        fun floats(v: FloatArray) = view({ b -> v.forEach(b::putFloat) }, v.size * 4)
        fun shorts(v: ShortArray) = view({ b -> v.forEach(b::putShort) }, v.size * 2)
        fun raw(v: ByteArray) = view({ b -> b.put(v) }, v.size)

        val vPos = floats(positions)
        val vNrm = floats(normals)
        val vJnt = raw(joints)
        val vWgt = floats(weights)
        val vIdx = shorts(indices)
        val vIbm = floats(inverseBind)
        val vTime = floats(times)
        val vBendUpper = floats(bendUpper)
        val vCurlLower = floats(curlLower)
        val vCurlUpper = floats(curlUpper)
        val vTwistUpper = floats(twistUpper)

        val base64 = Base64.getEncoder().encodeToString(bytes.toByteArray())
        val bufferViews = views.joinToString(",\n   ") { (o, l) ->
            """{"buffer": 0, "byteOffset": $o, "byteLength": $l}"""
        }

        val minY = 0f
        val maxY = (RINGS - 1).toFloat()

        return """
        {
         "asset": {"version": "2.0", "generator": "afield.four.modules.RiggedFixture"},
         "scene": 0,
         "scenes": [{"nodes": [0, 1]}],
         "nodes": [
          {"name": "cylinder",  "mesh": 0, "skin": 0},
          {"name": "armature",  "children": [2]},
          {"name": "boneLower", "children": [3]},
          {"name": "boneUpper", "translation": [0.0, $UPPER_JOINT_Y, 0.0]}
         ],
         "skins": [
          {"name": "twoBone", "skeleton": 2, "joints": [2, 3], "inverseBindMatrices": 5}
         ],
         "meshes": [{"name": "cylinder", "primitives": [
           {"attributes": {"POSITION": 0, "NORMAL": 1, "JOINTS_0": 2, "WEIGHTS_0": 3},
            "indices": 4, "mode": 4}]}],
         "animations": [
          {"name": "bend",
           "samplers": [{"input": 6, "output": 7, "interpolation": "LINEAR"}],
           "channels": [{"sampler": 0, "target": {"node": 3, "path": "rotation"}}]},
          {"name": "curl",
           "samplers": [{"input": 6, "output": 8, "interpolation": "LINEAR"},
                        {"input": 6, "output": 9, "interpolation": "LINEAR"}],
           "channels": [{"sampler": 0, "target": {"node": 2, "path": "rotation"}},
                        {"sampler": 1, "target": {"node": 3, "path": "rotation"}}]},
          {"name": "twist",
           "samplers": [{"input": 6, "output": 10, "interpolation": "LINEAR"}],
           "channels": [{"sampler": 0, "target": {"node": 3, "path": "rotation"}}]}
         ],
         "accessors": [
          {"bufferView": $vPos, "componentType": 5126, "count": $VERTEX_COUNT, "type": "VEC3",
           "min": [${-RADIUS}, $minY, ${-RADIUS}], "max": [$RADIUS, $maxY, $RADIUS]},
          {"bufferView": $vNrm, "componentType": 5126, "count": $VERTEX_COUNT, "type": "VEC3"},
          {"bufferView": $vJnt, "componentType": 5121, "count": $VERTEX_COUNT, "type": "VEC4"},
          {"bufferView": $vWgt, "componentType": 5126, "count": $VERTEX_COUNT, "type": "VEC4"},
          {"bufferView": $vIdx, "componentType": 5123, "count": ${TRIANGLE_COUNT * 3}, "type": "SCALAR"},
          {"bufferView": $vIbm, "componentType": 5126, "count": 2, "type": "MAT4"},
          {"bufferView": $vTime, "componentType": 5126, "count": ${times.size}, "type": "SCALAR",
           "min": [${times.first()}], "max": [${times.last()}]},
          {"bufferView": $vBendUpper, "componentType": 5126, "count": ${times.size}, "type": "VEC4"},
          {"bufferView": $vCurlLower, "componentType": 5126, "count": ${times.size}, "type": "VEC4"},
          {"bufferView": $vCurlUpper, "componentType": 5126, "count": ${times.size}, "type": "VEC4"},
          {"bufferView": $vTwistUpper, "componentType": 5126, "count": ${times.size}, "type": "VEC4"}
         ],
         "bufferViews": [
          $bufferViews
         ],
         "buffers": [{"byteLength": ${bytes.size()},
                      "uri": "data:application/octet-stream;base64,$base64"}]
        }
        """.trimIndent() + "\n"
    }

    /** Where the committed copy lives, relative to the repository root. */
    const val COMMITTED_PATH: String = "dev_docs/agents/fixtures/skinning/bending_cylinder.gltf"

    /**
     * An absolute path to the committed fixture, found by walking up from the working directory.
     *
     * A box does not get to choose the server's working directory, and a demo whose first line is a
     * relative path is a demo that works on the machine it was written on. This walks up to eight
     * levels looking for [COMMITTED_PATH], and if it finds nothing it *writes* the fixture to a
     * temporary file from the generator above — which costs nothing, because the generator is the
     * source of truth for that file anyway.
     */
    fun locate(): String {
        var dir: java.io.File? = java.io.File(".").absoluteFile
        repeat(8) {
            val candidate = java.io.File(dir, COMMITTED_PATH)
            if (candidate.isFile) return candidate.path
            dir = dir?.parentFile ?: return@repeat
        }
        val fallback = java.io.File(
            System.getProperty("java.io.tmpdir"), "afield-bending_cylinder.gltf",
        )
        if (!fallback.isFile) fallback.writeText(bendingCylinderGltf())
        return fallback.path
    }

    /** Rewrite the committed asset from this source. Run from the repository root. */
    @JvmStatic
    fun main(args: Array<String>) {
        val out = java.io.File(if (args.isNotEmpty()) args[0] else COMMITTED_PATH)
        out.parentFile?.mkdirs()
        out.writeText(bendingCylinderGltf())
        println("wrote ${out.path} (${out.length()} bytes)")
    }
}

/**
 * The second, adversarial rig: a shearing plate and a vertex with more influences than fit.
 *
 * [RiggedFixture]'s bending cylinder is a *well-behaved* rig — rigid joints, two influences per
 * vertex, weights that are exact in binary — chosen so the bind-pose oracle can be bit-exact. Three
 * things it therefore cannot test at all, each of which is a place a skinning implementation
 * quietly differs from the one next to it:
 *
 *  - **A non-rigid joint.** Every joint in the cylinder is a rotation, and for a rotation the
 *    inverse transpose *is* the matrix, so the cylinder cannot tell the exact normal transform from
 *    the usual approximation anywhere except the blended band. Here `shearBone` animates its
 *    **scale** from `(1,1,1)` to `(1,2,1)`, which shears a tilted plate: measured against the
 *    plate's own face normal, the exact inverse transpose scores `1.0` and reusing the blended 3×3
 *    scores `0.8`. That `0.8` is a closed form — see [StressFixture.shearedNormal].
 *  - **More than four influences.** The cylinder has two per vertex, so the four-wide cap is idle
 *    and its discard counters are untested. Here three vertices carry **six** influences,
 *    `0.30 / 0.25 / 0.20 / 0.15 / 0.06 / 0.04`, spread over `JOINTS_0` **and** `JOINTS_1`. With the
 *    cap the triangle moves `+2.2222` in x; without it, `+2.54`. The gap is what the cap costs, and
 *    it is a number rather than a shrug.
 *  - **A skinned mesh node with its own transform.** glTF 2.0 §3.7.3.2 says it is *ignored*. The
 *    `plate` node here is translated `(0, 10, 0)`, so a skinning path that applies it lands the
 *    plate ten units up. Note this makes the fixture deliberately **not** bind-pose-identical to
 *    [assimpToTriMesh], which *does* apply it — that disagreement is the assertion, not a problem.
 */
object StressFixture {

    /** Where the committed copy lives, relative to the repository root. */
    const val COMMITTED_PATH: String = "dev_docs/agents/fixtures/skinning/shear_and_cap.gltf"

    /** The plate's bind-pose normal: the plane is spanned by `+x` and `(0, 1, -1)/√2`. */
    val BIND_NORMAL: FloatArray = floatArrayOf(0f, 0.70710678f, 0.70710678f)

    /**
     * The plate's normal after `shearBone` reaches scale `(1, 2, 1)`, by hand.
     *
     * The plate's tangents are `u = (1,0,0)` and `v = (0, √2/2, -√2/2)`. Scaling `y` by 2 leaves
     * `u` alone and takes `v` to `(0, √2, -√2/2)`, so the true normal is
     * `u × v' = (0, √2/2, √2)`, normalised `(0, 0.4472136, 0.8944272)`.
     *
     * The inverse transpose of `diag(1,2,1)` is `diag(1, 0.5, 1)`, which takes the bind normal to
     * `(0, √2/4, √2/2)` — the same direction. The *plain* matrix takes it to `(0, √2, √2/2)`,
     * normalised `(0, 0.8944272, 0.4472136)`, whose dot with the truth is exactly
     * `0.4472136·0.8944272 × 2 = 0.8`.
     */
    val shearedNormal: FloatArray = floatArrayOf(0f, 0.4472136f, 0.8944272f)

    /** What the six-influence triangle's x displacement is with the four-wide cap: `2.0 / 0.9`. */
    const val CAPPED_DISPLACEMENT: Float = 2.2222222f

    /** And without it: `0.30·1 + 0.25·2 + 0.20·3 + 0.15·4 + 0.06·5 + 0.04·6`. */
    const val UNCAPPED_DISPLACEMENT: Float = 2.54f

    /** The six weights, largest first, as the file writes them. */
    val SIX_WEIGHTS: FloatArray = floatArrayOf(0.30f, 0.25f, 0.20f, 0.15f, 0.06f, 0.04f)

    /** The generated glTF 2.0 document, as text. Deterministic: same bytes every call. */
    fun shearAndCapGltf(): String {
        val h = 0.70710678f
        // Four corners of a plate centred at (0, 2, 0), spanned by u = (1,0,0) and v = (0, h, -h).
        val quad = arrayOf(
            floatArrayOf(-1f, 2f - h, h), floatArrayOf(1f, 2f - h, h),
            floatArrayOf(1f, 2f + h, -h), floatArrayOf(-1f, 2f + h, -h),
        )
        // A separate triangle, far enough away to read independently, carrying six influences.
        val tri = arrayOf(
            floatArrayOf(3f, 0f, 0f), floatArrayOf(4f, 0f, 0f), floatArrayOf(3f, 1f, 0f),
        )
        val positions = FloatArray(21)
        val normals = FloatArray(21)
        for (i in 0..3) {
            for (k in 0..2) { positions[i * 3 + k] = quad[i][k]; normals[i * 3 + k] = BIND_NORMAL[k] }
        }
        for (i in 0..2) {
            for (k in 0..2) positions[(4 + i) * 3 + k] = tri[i][k]
            normals[(4 + i) * 3 + 2] = 1f
        }

        val joints0 = ByteArray(28)
        val joints1 = ByteArray(28)
        val weights0 = FloatArray(28)
        val weights1 = FloatArray(28)
        for (i in 0..3) weights0[i * 4] = 1f            // the plate: all of it on bone 0, shearBone
        for (i in 4..6) {
            joints0[i * 4] = 1; joints0[i * 4 + 1] = 2; joints0[i * 4 + 2] = 3; joints0[i * 4 + 3] = 4
            joints1[i * 4] = 5; joints1[i * 4 + 1] = 6
            for (k in 0..3) weights0[i * 4 + k] = SIX_WEIGHTS[k]
            weights1[i * 4] = SIX_WEIGHTS[4]; weights1[i * 4 + 1] = SIX_WEIGHTS[5]
        }

        val indices = shortArrayOf(0, 1, 2, 0, 2, 3, 4, 5, 6)

        // shearBone binds at (0, 2, 0); the six cap bones bind at the origin, so their inverse binds
        // are the identity and their animated translation IS their joint matrix.
        val inverseBind = FloatArray(7 * 16)
        for (b in 0 until 7) {
            inverseBind[b * 16] = 1f; inverseBind[b * 16 + 5] = 1f
            inverseBind[b * 16 + 10] = 1f; inverseBind[b * 16 + 15] = 1f
        }
        inverseBind[13] = -2f  // column 3, row 1 of bone 0

        val times = floatArrayOf(0f, 1f)
        val shearScale = floatArrayOf(1f, 1f, 1f, 1f, 2f, 1f)
        val capTranslations = (1..6).map { floatArrayOf(0f, 0f, 0f, it.toFloat(), 0f, 0f) }

        val views = ArrayList<IntArray>()
        val bytes = java.io.ByteArrayOutputStream()
        fun view(block: (java.nio.ByteBuffer) -> Unit, length: Int): Int {
            while (bytes.size() % 4 != 0) bytes.write(0)
            val offset = bytes.size()
            val b = java.nio.ByteBuffer.allocate(length).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            block(b)
            bytes.write(b.array())
            views += intArrayOf(offset, length)
            return views.size - 1
        }
        fun floats(v: FloatArray) = view({ b -> v.forEach(b::putFloat) }, v.size * 4)
        fun shorts(v: ShortArray) = view({ b -> v.forEach(b::putShort) }, v.size * 2)
        fun raw(v: ByteArray) = view({ b -> b.put(v) }, v.size)

        val vPos = floats(positions); val vNrm = floats(normals)
        val vJ0 = raw(joints0); val vW0 = floats(weights0)
        val vJ1 = raw(joints1); val vW1 = floats(weights1)
        val vIdx = shorts(indices); val vIbm = floats(inverseBind)
        val vTime = floats(times); val vShear = floats(shearScale)
        val vCap = capTranslations.map { floats(it) }

        val base64 = java.util.Base64.getEncoder().encodeToString(bytes.toByteArray())
        val bufferViews = views.joinToString(",\n   ") { (o, l) ->
            """{"buffer": 0, "byteOffset": $o, "byteLength": $l}"""
        }
        val capNodes = (1..6).joinToString(", ") { """{"name": "capBone$it"}""" }
        val capSamplers = vCap.mapIndexed { i, acc ->
            """{"input": $vTime, "output": $acc, "interpolation": "LINEAR"}"""
        }.joinToString(",\n                        ")
        val capChannels = (1..6).joinToString(",\n                       ") {
            """{"sampler": $it, "target": {"node": ${2 + it}, "path": "translation"}}"""
        }

        return """
        {
         "asset": {"version": "2.0", "generator": "afield.four.modules.StressFixture"},
         "scene": 0,
         "scenes": [{"nodes": [0, 1]}],
         "nodes": [
          {"name": "plate", "mesh": 0, "skin": 0, "translation": [0.0, 10.0, 0.0]},
          {"name": "rig", "children": [2, 3, 4, 5, 6, 7, 8]},
          {"name": "shearBone", "translation": [0.0, 2.0, 0.0]},
          $capNodes
         ],
         "skins": [
          {"name": "stress", "skeleton": 1, "joints": [2, 3, 4, 5, 6, 7, 8], "inverseBindMatrices": $vIbm}
         ],
         "meshes": [{"name": "plate", "primitives": [
           {"attributes": {"POSITION": $vPos, "NORMAL": $vNrm,
                           "JOINTS_0": $vJ0, "WEIGHTS_0": $vW0,
                           "JOINTS_1": $vJ1, "WEIGHTS_1": $vW1},
            "indices": $vIdx, "mode": 4}]}],
         "animations": [
          {"name": "shear",
           "samplers": [{"input": $vTime, "output": $vShear, "interpolation": "LINEAR"},
                        $capSamplers],
           "channels": [{"sampler": 0, "target": {"node": 2, "path": "scale"}},
                       $capChannels]}
         ],
         "accessors": [
          {"bufferView": $vPos, "componentType": 5126, "count": 7, "type": "VEC3",
           "min": [-1.0, ${2f - h}, ${-h}], "max": [4.0, ${2f + h}, $h]},
          {"bufferView": $vNrm, "componentType": 5126, "count": 7, "type": "VEC3"},
          {"bufferView": $vJ0, "componentType": 5121, "count": 7, "type": "VEC4"},
          {"bufferView": $vW0, "componentType": 5126, "count": 7, "type": "VEC4"},
          {"bufferView": $vJ1, "componentType": 5121, "count": 7, "type": "VEC4"},
          {"bufferView": $vW1, "componentType": 5126, "count": 7, "type": "VEC4"},
          {"bufferView": $vIdx, "componentType": 5123, "count": ${indices.size}, "type": "SCALAR"},
          {"bufferView": $vIbm, "componentType": 5126, "count": 7, "type": "MAT4"},
          {"bufferView": $vTime, "componentType": 5126, "count": 2, "type": "SCALAR",
           "min": [0.0], "max": [1.0]},
          {"bufferView": $vShear, "componentType": 5126, "count": 2, "type": "VEC3"},
          ${vCap.joinToString(",\n          ") { """{"bufferView": $it, "componentType": 5126, "count": 2, "type": "VEC3"}""" }}
         ],
         "bufferViews": [
          $bufferViews
         ],
         "buffers": [{"byteLength": ${bytes.size()},
                      "uri": "data:application/octet-stream;base64,$base64"}]
        }
        """.trimIndent() + "\n"
    }

    /** An absolute path to the committed fixture; see [RiggedFixture.locate]. */
    fun locate(): String {
        var dir: java.io.File? = java.io.File(".").absoluteFile
        repeat(8) {
            val candidate = java.io.File(dir, COMMITTED_PATH)
            if (candidate.isFile) return candidate.path
            dir = dir?.parentFile ?: return@repeat
        }
        val fallback = java.io.File(System.getProperty("java.io.tmpdir"), "afield-shear_and_cap.gltf")
        if (!fallback.isFile) fallback.writeText(shearAndCapGltf())
        return fallback.path
    }
}
