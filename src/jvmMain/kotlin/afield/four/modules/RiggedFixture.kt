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
                        {"sampler": 1, "target": {"node": 3, "path": "rotation"}}]}
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
          {"bufferView": $vCurlUpper, "componentType": 5126, "count": ${times.size}, "type": "VEC4"}
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

    /** Rewrite the committed asset from this source. Run from the repository root. */
    @JvmStatic
    fun main(args: Array<String>) {
        val out = java.io.File(if (args.isNotEmpty()) args[0] else COMMITTED_PATH)
        out.parentFile?.mkdirs()
        out.writeText(bendingCylinderGltf())
        println("wrote ${out.path} (${out.length()} bytes)")
    }
}
