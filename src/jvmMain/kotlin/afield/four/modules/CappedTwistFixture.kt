@file:Suppress("unused")

package afield.four.modules

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * A closed, rigged tube for measuring what skinning does to **volume**.
 *
 * [RiggedFixture]'s bending cylinder is an open tube, and an open tube encloses nothing, so it
 * cannot answer "how much of the limb does a twist remove". This is the same rig with the two ends
 * capped, which makes the rest pose a closed, consistently wound surface that Manifold accepts as a
 * solid. [RiggedFixture] and its committed asset are left alone on purpose: other tests pin their
 * bytes.
 *
 * ### Why it is denser along the bone than the bending cylinder
 *
 * The bending cylinder has rings at `y = 0, 1, 2, 3, 4`, so exactly one ring (`y = 2`) is blended.
 * Under a 180° twist, dual quaternion skinning turns that ring 90° against each neighbour, and a
 * triangulated band between two 12-gons 90° apart loses about a third of its volume through the
 * *triangulation*: a straight chord between the rings passes within `r/√2` of the axis. A volume
 * measured on that mesh would charge the blend with a meshing artefact. Here the rings are a
 * quarter apart — 17 of them — so the blended zone `1 ≤ y ≤ 3` has nine rings, eight bands, and a
 * 180° twist spreads over them in steps small enough that the triangulation costs little.
 *
 * ### Shape and numbers
 *
 * Radius `0.5`, height `4`, [SEGMENTS] = 12, [RINGS] = 17 at `y = 0, 0.25, …, 4`, plus one centre
 * vertex on each cap: [VERTEX_COUNT] = 206, [TRIANGLE_COUNT] = 408. The caps share the end rings'
 * vertices rather than duplicating them, because a solid is a property of the indices: duplicated
 * cap vertices would leave the surface looking closed and sharing an edge nowhere.
 *
 * The rest volume has a closed form. The cross-section is a regular 12-gon of circumradius `r`,
 * area `½·n·r²·sin(2π/n) = 6 · 0.25 · 0.5 = 0.75`, and it is a straight prism, so the volume is
 * `0.75 × 4 = 3.0` exactly — [REST_VOLUME].
 *
 * ### Rig
 *
 * Identical to [RiggedFixture]: `armature → boneLower (origin) → boneUpper (y = 2)`, weight to
 * `boneUpper` = [RiggedFixture.upperWeight] = `clamp((y − 1)/2, 0, 1)`. At quarter-spaced rings that
 * gives weights in eighths, all exact in binary, so the bind pose is exact for the same reasons as
 * the bending cylinder's. Each cap centre takes its end ring's weights: all `boneLower` at the
 * bottom, all `boneUpper` at the top.
 *
 * ### Clip
 *
 * **`twist`** (0 → 1 s) rotates `boneUpper` about its own axis, `+y`, from 0° to 180°. The sampler
 * slerps, and slerp is linear in angle, so the twist at time `t` is `180°·t`: [secondsFor] converts.
 * 180° is where linear blend skinning is worst — at the 50/50 ring it averages the identity with its
 * own half-turn, `½·diag(1,1,1) + ½·diag(−1,1,−1) = diag(0,1,0)`, and that ring collapses to a point
 * on the axis.
 */
object CappedTwistFixture {

    /** Radial segments around the tube. */
    const val SEGMENTS: Int = 12

    /** Rings along the tube, at `y = 0, 0.25, … , 4`. */
    const val RINGS: Int = 17

    /** Distance between rings. */
    const val RING_SPACING: Float = 0.25f

    /** Tube radius, the same as [RiggedFixture.RADIUS]. */
    const val RADIUS: Float = 0.5f

    /** Tube height. */
    const val HEIGHT: Float = 4f

    /** Where `boneUpper` sits, in the bind pose. */
    const val UPPER_JOINT_Y: Float = 2f

    /** The twist, in degrees, at the end of the clip. */
    const val MAX_TWIST_DEGREES: Float = 180f

    /** Ring vertices plus the two cap centres. */
    const val VERTEX_COUNT: Int = SEGMENTS * RINGS + 2

    /** Side triangles plus two twelve-triangle cap fans. */
    const val TRIANGLE_COUNT: Int = SEGMENTS * (RINGS - 1) * 2 + 2 * SEGMENTS

    /** The prism's volume, in closed form: 12-gon area `0.75` times height `4`. See the class comment. */
    const val REST_VOLUME: Double = 3.0

    /** The clip time at which `boneUpper` has turned [degrees] about its own axis. */
    fun secondsFor(degrees: Float): Float = degrees / MAX_TWIST_DEGREES

    /** The generated glTF 2.0 document, as text. Deterministic: same bytes every call. */
    fun cappedTwistGltf(): String {
        val positions = FloatArray(VERTEX_COUNT * 3)
        val normals = FloatArray(VERTEX_COUNT * 3)
        val joints = ByteArray(VERTEX_COUNT * 4)
        val weights = FloatArray(VERTEX_COUNT * 4)

        fun put(i: Int, x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, upper: Float) {
            positions[i * 3] = x; positions[i * 3 + 1] = y; positions[i * 3 + 2] = z
            normals[i * 3] = nx; normals[i * 3 + 1] = ny; normals[i * 3 + 2] = nz
            // joint 0 = boneLower, joint 1 = boneUpper; slots 2 and 3 unused.
            joints[i * 4] = 0; joints[i * 4 + 1] = 1
            weights[i * 4] = 1f - upper; weights[i * 4 + 1] = upper
        }

        for (ring in 0 until RINGS) {
            val y = ring * RING_SPACING
            val upper = RiggedFixture.upperWeight(y)
            for (k in 0 until SEGMENTS) {
                val theta = 2.0 * Math.PI * k / SEGMENTS
                val cx = Math.cos(theta).toFloat()
                val cz = Math.sin(theta).toFloat()
                put(ring * SEGMENTS + k, RADIUS * cx, y, RADIUS * cz, cx, 0f, cz, upper)
            }
        }
        val bottom = SEGMENTS * RINGS
        val top = bottom + 1
        put(bottom, 0f, 0f, 0f, 0f, -1f, 0f, RiggedFixture.upperWeight(0f))
        put(top, 0f, HEIGHT, 0f, 0f, 1f, 0f, RiggedFixture.upperWeight(HEIGHT))

        val indices = ShortArray(TRIANGLE_COUNT * 3)
        var t = 0
        fun tri(a: Int, b: Int, c: Int) {
            indices[t++] = a.toShort(); indices[t++] = b.toShort(); indices[t++] = c.toShort()
        }
        for (ring in 0 until RINGS - 1) {
            for (k in 0 until SEGMENTS) {
                val a = ring * SEGMENTS + k
                val b = ring * SEGMENTS + (k + 1) % SEGMENTS
                val c = (ring + 1) * SEGMENTS + k
                val d = (ring + 1) * SEGMENTS + (k + 1) % SEGMENTS
                // The same outward winding as RiggedFixture's side wall.
                tri(a, c, d); tri(a, d, b)
            }
        }
        val last = (RINGS - 1) * SEGMENTS
        for (k in 0 until SEGMENTS) {
            val k1 = (k + 1) % SEGMENTS
            // (centre, P_k, P_k+1) has normal P_k × P_k+1, whose y is sin(θ_k − θ_k+1) < 0: outward
            // for the bottom cap. The top cap is the same fan wound the other way.
            tri(bottom, k, k1)
            tri(top, last + k1, last + k)
        }

        val inverseBind = floatArrayOf(
            1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f,
            1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, -UPPER_JOINT_Y, 0f, 1f,
        )

        val times = floatArrayOf(0f, 1f)
        fun yQuat(degrees: Double): FloatArray {
            val half = Math.toRadians(degrees) / 2.0
            return floatArrayOf(0f, Math.sin(half).toFloat(), 0f, Math.cos(half).toFloat())
        }
        // Key 0 is the exact identity quaternion; key 1 is the half-turn about +y.
        val twistUpper = yQuat(0.0) + yQuat(MAX_TWIST_DEGREES.toDouble())

        val views = ArrayList<IntArray>()
        val bytes = java.io.ByteArrayOutputStream()
        fun view(block: (ByteBuffer) -> Unit, length: Int): Int {
            while (bytes.size() % 4 != 0) bytes.write(0)
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
        val vTwist = floats(twistUpper)

        val base64 = Base64.getEncoder().encodeToString(bytes.toByteArray())
        val bufferViews = views.joinToString(",\n   ") { (o, l) ->
            """{"buffer": 0, "byteOffset": $o, "byteLength": $l}"""
        }

        return """
        {
         "asset": {"version": "2.0", "generator": "afield.four.modules.CappedTwistFixture"},
         "scene": 0,
         "scenes": [{"nodes": [0, 1]}],
         "nodes": [
          {"name": "tube",      "mesh": 0, "skin": 0},
          {"name": "armature",  "children": [2]},
          {"name": "boneLower", "children": [3]},
          {"name": "boneUpper", "translation": [0.0, $UPPER_JOINT_Y, 0.0]}
         ],
         "skins": [
          {"name": "twoBone", "skeleton": 2, "joints": [2, 3], "inverseBindMatrices": $vIbm}
         ],
         "meshes": [{"name": "tube", "primitives": [
           {"attributes": {"POSITION": $vPos, "NORMAL": $vNrm, "JOINTS_0": $vJnt, "WEIGHTS_0": $vWgt},
            "indices": $vIdx, "mode": 4}]}],
         "animations": [
          {"name": "twist",
           "samplers": [{"input": $vTime, "output": $vTwist, "interpolation": "LINEAR"}],
           "channels": [{"sampler": 0, "target": {"node": 3, "path": "rotation"}}]}
         ],
         "accessors": [
          {"bufferView": $vPos, "componentType": 5126, "count": $VERTEX_COUNT, "type": "VEC3",
           "min": [${-RADIUS}, 0.0, ${-RADIUS}], "max": [$RADIUS, $HEIGHT, $RADIUS]},
          {"bufferView": $vNrm, "componentType": 5126, "count": $VERTEX_COUNT, "type": "VEC3"},
          {"bufferView": $vJnt, "componentType": 5121, "count": $VERTEX_COUNT, "type": "VEC4"},
          {"bufferView": $vWgt, "componentType": 5126, "count": $VERTEX_COUNT, "type": "VEC4"},
          {"bufferView": $vIdx, "componentType": 5123, "count": ${TRIANGLE_COUNT * 3}, "type": "SCALAR"},
          {"bufferView": $vIbm, "componentType": 5126, "count": 2, "type": "MAT4"},
          {"bufferView": $vTime, "componentType": 5126, "count": ${times.size}, "type": "SCALAR",
           "min": [${times.first()}], "max": [${times.last()}]},
          {"bufferView": $vTwist, "componentType": 5126, "count": ${times.size}, "type": "VEC4"}
         ],
         "bufferViews": [
          $bufferViews
         ],
         "buffers": [{"byteLength": ${bytes.size()},
                      "uri": "data:application/octet-stream;base64,$base64"}]
        }
        """.trimIndent() + "\n"
    }

    /**
     * An absolute path to the fixture, written from [cappedTwistGltf] into the temporary directory.
     *
     * Nothing is committed for this one: the generator is the source of truth, and the file name
     * carries a hash of the generated text, so a changed generator can never be answered by a stale
     * copy a previous build left behind.
     */
    fun locate(): String {
        val text = cappedTwistGltf()
        val hash = Integer.toHexString(text.hashCode())
        val file = java.io.File(System.getProperty("java.io.tmpdir"), "afield-capped_twist-$hash.gltf")
        if (!file.isFile || file.readText() != text) file.writeText(text)
        return file.path
    }
}
