package afield.four

import afield.four.modules.DualQuaternionSkinning
import afield.four.modules.GltfSkinning
import afield.four.modules.GltfSupport
import afield.four.modules.GpuSkinning
import afield.four.modules.RiggedFixture
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Vector3f
import org.lwjgl.system.MemoryUtil.memFree
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CPU half of GPU dual-quaternion skinning, which needs no device: the packing
 * [GpuSkinning.packDualPalette] hands the shader, and one claim in `GpuSkinner.blendedPalette`'s KDoc.
 *
 * `GpuSkinningSmoke` grades the shader end to end, but it needs a GPU and an awake display, so it
 * does not run in `jvmTest`. What *can* be checked here is that the floats are in the order the
 * GLSL reads them: [reconstruct] rebuilds `[R·S | t]` from a packed joint exactly as
 * `afSkinBlendDual` does for a single influence of weight 1 (rotate the basis by `real`, translation
 * `2·(dual·conj(real)).xyz`, `S` by columns), and that must give back the matrix that was packed.
 * A transposed `S` or a sign slip in the translation fails this on the shear and on the translated
 * rotation; the identity would pass either way, so it is not the only case.
 */
class GpuDualPalettePackingTest {

    /** The GLSL's single-influence reconstruction, in Kotlin, term for term. Column-major 16 floats. */
    private fun reconstruct(f: FloatArray, o: Int): FloatArray {
        val qx = f[o]; val qy = f[o + 1]; val qz = f[o + 2]; val qw = f[o + 3]
        val dx = f[o + 4]; val dy = f[o + 5]; val dz = f[o + 6]; val dw = f[o + 7]
        fun rotate(vx: Float, vy: Float, vz: Float): FloatArray {
            val tx = 2f * (qy * vz - qz * vy)
            val ty = 2f * (qz * vx - qx * vz)
            val tz = 2f * (qx * vy - qy * vx)
            return floatArrayOf(
                vx + qw * tx + (qy * tz - qz * ty),
                vy + qw * ty + (qz * tx - qx * tz),
                vz + qw * tz + (qx * ty - qy * tx),
            )
        }
        val r = listOf(rotate(1f, 0f, 0f), rotate(0f, 1f, 0f), rotate(0f, 0f, 1f))
        val out = FloatArray(16)
        for (c in 0..2) {
            val s = floatArrayOf(f[o + 8 + c * 4], f[o + 9 + c * 4], f[o + 10 + c * 4])
            for (row in 0..2) out[c * 4 + row] = r[0][row] * s[0] + r[1][row] * s[1] + r[2][row] * s[2]
        }
        // t = 2 * (rw*dv - dw*rv + rv x dv)
        out[12] = 2f * (qw * dx - dw * qx + (qy * dz - qz * dy))
        out[13] = 2f * (qw * dy - dw * qy + (qz * dx - qx * dz))
        out[14] = 2f * (qw * dz - dw * qz + (qx * dy - qy * dx))
        out[15] = 1f
        return out
    }

    private val cases: List<Pair<String, Matrix4f>> = listOf(
        "identity" to Matrix4f(),
        "translated rotation" to Matrix4f().translate(Vector3f(1.5f, -2f, 0.25f))
            .mul(Matrix4f().rotate(2.1f, Vector3f(0.3f, 1f, -0.2f).normalize())),
        "180 about y" to Matrix4f().rotate(Math.PI.toFloat(), Vector3f(0f, 1f, 0f)),
        "non-uniform scale on a rotation" to Matrix4f().translate(Vector3f(0f, 3f, 0f))
            .mul(Matrix4f().rotate(0.7f, Vector3f(1f, 0f, 0f))).mul(Matrix4f().scale(1.5f, 1f, 0.7f)),
        "shear" to Matrix4f().set(
            1f, 0f, 0f, 0f,
            0.4f, 1f, 0f, 0f,
            0f, 0.25f, 1.2f, 0f,
            0.5f, 0f, -1f, 1f,
        ),
        "reflection" to Matrix4f().scale(1f, 1f, -1f),
    )

    @Test
    fun `a packed joint reconstructs to the matrix that was packed`() {
        val palette = cases.map { it.second }
        val packed = FloatArray(palette.size * GpuSkinning.DUAL_FLOATS_PER_JOINT)
        GpuSkinning.packDualPalette(palette, packed)
        for ((j, case) in cases.withIndex()) {
            val back = reconstruct(packed, j * GpuSkinning.DUAL_FLOATS_PER_JOINT)
            var worst = 0f
            for (k in 0 until 16) worst = maxOf(worst, abs(back[k] - case.second[k]))
            println("  %-32s worst |reconstructed - packed| %.2e".format(case.first, worst))
            assertTrue(worst < 1e-5f, "${case.first}: reconstruction differs by $worst")
        }
    }

    @Test
    fun `the method word carries the residual bit exactly when a joint is non-rigid`() {
        val scratch = FloatArray(4 * GpuSkinning.DUAL_FLOATS_PER_JOINT)
        val rigid = listOf(cases[0].second, cases[1].second, cases[2].second)
        assertEquals(GpuSkinning.METHOD_DUAL_QUATERNION, GpuSkinning.packDualPalette(rigid, scratch))
        for (i in 3 until cases.size) {
            val word = GpuSkinning.packDualPalette(rigid + cases[i].second, scratch)
            assertEquals(
                GpuSkinning.METHOD_DUAL_QUATERNION or GpuSkinning.METHOD_RESIDUAL_BIT, word,
                "${cases[i].first} is non-rigid"
            )
        }
    }

    /**
     * `GpuSkinner.blendedPalette`'s KDoc says dual-quaternion skinning does **not** rescue a
     * matrix-lerped clip blend, because the lerp shrinks the joint before skinning sees it and
     * `split` then carries the shrink as a residual. This is that sentence as a measurement:
     * half of the `twist` clip at 180° lerped with the rest pose, dual-quaternion skinned, and the
     * tip ring — driven by the upper bone alone — has collapsed onto the axis all the same.
     */
    @Test
    fun `dual quaternion skinning does not undo a matrix-lerped clip blend`() {
        val model = GltfSupport.loadGltfBasic(RiggedFixture.locate(), GltfSupport.GltfLoadConfig(loadTextures = false))
        try {
            val instance = model.meshes.first { it.skin != null }
            val skin = instance.skin!!
            val sk = skin.skeleton
            val n = skin.influences.vertexCount
            val f = instance.buffers.vertexData.asFloatBuffer()
            val bind = FloatArray(n * 3) { f.get((it / 3) * GpuSkinning.VERTEX_STRIDE_FLOATS + it % 3) }
            val twist = sk.clip("twist")!!
            val blended = sk.blendedPalette(twist, twist.durationSeconds * 0.5f, null, 0f, 0.5f)

            val scratch = FloatArray(blended.size * GpuSkinning.DUAL_FLOATS_PER_JOINT)
            val word = GpuSkinning.packDualPalette(blended, scratch)
            val dq = GltfSkinning.skin(
                bind, null, skin.influences, blended,
                GltfSkinning.SkinningOptions(method = GltfSkinning.SkinningMethod.DUAL_QUATERNION),
            )
            val tipRadius = DualQuaternionSkinning.candyWrapperRadius(dq.position, 4f)
            val unblended = GltfSkinning.skin(
                bind, null, skin.influences, sk.palette(twist, twist.durationSeconds * 0.5f),
                GltfSkinning.SkinningOptions(method = GltfSkinning.SkinningMethod.DUAL_QUATERNION),
            )
            val unblendedTip = DualQuaternionSkinning.candyWrapperRadius(unblended.position, 4f)
            println(
                "  twist 180 deg lerped 50/50 with rest, DQ skinned: tip ring radius %.4f (rest %.1f); ".format(
                    tipRadius, RiggedFixture.RADIUS
                ) + "unblended twist, DQ skinned: %.4f; oracle nonRigidJoints ${dq.nonRigidJoints}".format(unblendedTip)
            )
            assertTrue(word and GpuSkinning.METHOD_RESIDUAL_BIT != 0, "the lerped palette must reach the residual path")
            assertTrue(dq.nonRigidJoints > 0, "the oracle must see the lerped joint as non-rigid")
            assertTrue(tipRadius < 0.01f * RiggedFixture.RADIUS, "the lerped blend collapses the tip ring: $tipRadius")
            assertTrue(abs(unblendedTip - RiggedFixture.RADIUS) < 1e-4f, "without the lerp, DQ keeps the ring: $unblendedTip")
        } finally {
            for (m in model.meshes) {
                memFree(m.buffers.vertexData)
                memFree(m.buffers.indexData)
                m.buffers.tangentData?.let { memFree(it) }
            }
        }
    }
}
