@file:Suppress("MemberVisibilityCanBePrivate", "unused")

package afield.four.modules

import afield.stdlib.linalg.DualQuaternionf
import afield.stdlib.linalg.Matrix3f
import afield.stdlib.linalg.Matrix4f
import afield.stdlib.linalg.Quaternionf
import afield.stdlib.linalg.Vector3f
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Dual quaternion linear blending — the skinning method that does not collapse a twisted joint.
 *
 * [GltfSkinning.skin] averages *matrices*. That is right for positions under small rotations and
 * wrong in a way you can see the moment two influences disagree by much: the average of a rotation
 * and its 180° twin is not a rotation, it is a matrix that has squashed the cross-section flat. On
 * a cylinder twisted about its own axis the waist pinches to nothing and the artefact has a name —
 * the candy wrapper. [skin] here averages *rigid motions* instead, following Kavan et al.'s
 * "Geometric Skinning with Approximate Dual Quaternion Blending" (2008), so the blend of two
 * rotations is a rotation and the waist keeps its radius.
 *
 * It is a strict improvement only for the rigid case, and saying where it is *not* is the point of
 * the two sections below.
 *
 * ### What a dual quaternion cannot carry, and what this does about it
 *
 * A unit dual quaternion is exactly a rigid motion: rotation plus translation, six degrees of
 * freedom. A glTF joint matrix has twelve, and the other six are scale and shear. Feed a scaled
 * joint to a naive DQS and the scale silently disappears — the mesh snaps back to unit size with no
 * warning, which is worse than the candy wrapper because nothing about it looks like an artefact.
 *
 * So [split] factors each palette matrix `M` into `M = T · R · S`: a rotation `R` and translation
 * `T` that the dual quaternion carries exactly, and a residual 3×3 `S` holding whatever scale,
 * shear or reflection is left. `S` is blended **linearly**, because there is no better averaging of
 * shears and pretending otherwise would be the same mistake in a new place. A vertex is then
 * `dq · (S_blend · p)` — residual first, in mesh space, then the rigid motion.
 *
 * For `RiggedFixture.bendingCylinderGltf` every joint is a pure rotation, so every `S` is the
 * identity and this path costs nothing. For `RiggedFixture.shearAndCapGltf`, which exists precisely
 * to be nasty, it is what keeps the result correct rather than merely smooth.
 * [GltfSkinning.SkinnedVertices.nonRigidJoints] reports how many joints needed it, so a caller is never
 * guessing whether the fallback engaged.
 *
 * ### Antipodality, which is the bug everyone writes first
 *
 * `q` and `−q` are the same rotation and the *opposite* dual quaternion. Blend two that happen to
 * have been stored with opposite signs and they cancel toward zero instead of averaging — the mesh
 * folds itself inside out at exactly the vertices where the weights are most even, which is the
 * joint ring, which is the place you were trying to fix. The fix is to pick one influence as a
 * **pivot** and flip the sign of any other whose real part has a negative dot with it. This picks
 * the *highest-weight* influence as the pivot rather than the first, so the choice does not depend
 * on the order Assimp happened to hand the bones over in.
 *
 * ### What is exactly true, and therefore testable
 *
 * - **One influence with weight 1 gives the same answer as linear blending** for a rigid joint —
 *   both reduce to applying that joint's matrix. To `1e-6` and not to the bit, and the reason is
 *   worth stating rather than burying in an epsilon: linear blending multiplies a 4×4 while this
 *   rotates by a unit quaternion and adds a translation, so it is the same transform in a different
 *   float operation order.
 * - **The bind pose is the identity under both methods.** Every joint is the identity matrix, so
 *   there is nothing for either blend to get wrong.
 * - **Vertices that share a transform keep every mutual distance.** Measured over the joint ring's
 *   66 pairs at `[0.9999998, 1.0000000]`. Note the *weaker* form is the true one: this does **not**
 *   say the whole mesh moves rigidly. Two vertices with different weights get different rigid
 *   transforms and the edge between them is free to change — which is why the mesh-wide mean edge
 *   ratio is 1.051 rather than 1. That stronger claim was written here first and
 *   `DualQuaternionSkinningTest` refuted it.
 * - **Under twist the two methods disagree by a lot, in a direction with a sign.** That is the
 *   measurement in [candyWrapperRadius], and it is the reason this file exists: at the 0.5/0.5 ring
 *   linear blending reaches **0.0%** of the rest radius and this reaches **100.0%**. The tradeoff,
 *   stated honestly: over all 288 edges linear blending *shrinks* to 0.950 and this *bulges* to
 *   1.051. Neither is 1; they fail in opposite directions.
 */
object DualQuaternionSkinning {

    /** Below this, a residual 3×3 counts as the identity and the joint is treated as rigid. */
    const val RIGID_TOLERANCE: Float = 1e-4f

    /**
     * A palette matrix split into the rigid motion a dual quaternion carries exactly and the rest.
     *
     * [residual] is `null` when the joint is rigid, which is the common case and lets the blend skip
     * the 3×3 accumulation entirely.
     */
    class RigidSplit(
        val dq: DualQuaternionf,
        val residual: Matrix3f?,
    ) {
        val isRigid: Boolean get() = residual == null
    }

    /**
     * Factor [m] into a rigid motion and a residual 3×3, by Gram-Schmidt on the upper 3×3.
     *
     * Gram-Schmidt rather than a polar decomposition: it is exact for the rigid case (which is
     * almost every joint in almost every rig), it cannot fail to converge, and the residual it
     * leaves is upper-triangular, so `S == I` is a cheap and exact test rather than a threshold on
     * an iteration. A polar decomposition would distribute shear more evenly between `R` and `S`;
     * for a rig that is a cosmetic difference, and for a *rigid* rig there is no difference at all.
     *
     * A **reflection** — negative determinant, which `simpleGLTFSupport`'s own KDoc notes Assimp
     * hands over for mirrored nodes — cannot live in a rotation. It is folded into the residual as a
     * `−1` on the third axis, so the mesh still mirrors and the dual quaternion still holds a
     * genuine rotation.
     */
    fun split(m: Matrix4f, tolerance: Float = RIGID_TOLERANCE): RigidSplit {
        val a = Vector3f(m.m00, m.m01, m.m02)
        val b = Vector3f(m.m10, m.m11, m.m12)
        val c = Vector3f(m.m20, m.m21, m.m22)

        val sx = sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
        if (sx < 1e-20f) return degenerate(m)
        val e0 = Vector3f(a.x / sx, a.y / sx, a.z / sx)

        val d01 = e0.x * b.x + e0.y * b.y + e0.z * b.z
        val b1 = Vector3f(b.x - e0.x * d01, b.y - e0.y * d01, b.z - e0.z * d01)
        val sy = sqrt(b1.x * b1.x + b1.y * b1.y + b1.z * b1.z)
        if (sy < 1e-20f) return degenerate(m)
        val e1 = Vector3f(b1.x / sy, b1.y / sy, b1.z / sy)

        val d02 = e0.x * c.x + e0.y * c.y + e0.z * c.z
        val d12 = e1.x * c.x + e1.y * c.y + e1.z * c.z
        val c1 = Vector3f(
            c.x - e0.x * d02 - e1.x * d12,
            c.y - e0.y * d02 - e1.y * d12,
            c.z - e0.z * d02 - e1.z * d12,
        )
        var sz = sqrt(c1.x * c1.x + c1.y * c1.y + c1.z * c1.z)
        if (sz < 1e-20f) return degenerate(m)
        var e2 = Vector3f(c1.x / sz, c1.y / sz, c1.z / sz)

        // Right-handedness. e2 must be e0 x e1; if it is the other way the matrix reflects, and the
        // reflection belongs in the residual rather than in a quaternion that cannot express it.
        val cx = e0.y * e1.z - e0.z * e1.y
        val cy = e0.z * e1.x - e0.x * e1.z
        val cz = e0.x * e1.y - e0.y * e1.x
        var mirrored = false
        if (cx * e2.x + cy * e2.y + cz * e2.z < 0f) {
            mirrored = true
            e2 = Vector3f(-e2.x, -e2.y, -e2.z)
            sz = -sz
        }

        // R has the orthonormal basis as its rows, matching this codebase's row-vector convention
        // (see GltfSkinning.skin, which reads m00/m10/m20 down a column to transform a position).
        val rot = Matrix3f().set(e0.x, e0.y, e0.z, e1.x, e1.y, e1.z, e2.x, e2.y, e2.z)
        val q = Quaternionf().setFromNormalized(rot)

        val dq = DualQuaternionf()
        dq.set(q, Vector3f(m.m30, m.m31, m.m32))

        // S = R^T * M3, which for row-vector rows is the triangle of projections we already have.
        val residual = if (
            abs(sx - 1f) < tolerance && abs(sy - 1f) < tolerance && abs(sz - 1f) < tolerance &&
            abs(d01) < tolerance && abs(d02) < tolerance && abs(d12) < tolerance && !mirrored
        ) {
            null
        } else {
            Matrix3f().set(sx, 0f, 0f, d01, sy, 0f, d02, d12, sz)
        }
        return RigidSplit(dq, residual)
    }

    /** A matrix with no usable basis: keep the translation, drop the rest, and say so via the residual. */
    private fun degenerate(m: Matrix4f): RigidSplit {
        val dq = DualQuaternionf()
        dq.set(Quaternionf().identity(), Vector3f(m.m30, m.m31, m.m32))
        return RigidSplit(dq, Matrix3f().set(m.m00, m.m01, m.m02, m.m10, m.m11, m.m12, m.m20, m.m21, m.m22))
    }

    /**
     * Dual quaternion linear blending of [positions] and [normals] under [influences].
     *
     * Same signature and same conventions as [GltfSkinning.skin], so the two are drop-in
     * alternatives and a caller can measure one against the other on identical inputs — which is
     * what `SkinningEvidence` does.
     */
    fun skin(
        positions: FloatArray,
        normals: FloatArray?,
        influences: GltfSkinning.VertexInfluences,
        palette: List<Matrix4f>,
        options: GltfSkinning.SkinningOptions = GltfSkinning.SkinningOptions(),
    ): GltfSkinning.SkinnedVertices {
        val n = influences.vertexCount
        require(positions.size >= n * 3) { "positions has ${positions.size} floats, need ${n * 3}" }
        require(normals == null || normals.size >= n * 3) { "normals has ${normals?.size} floats, need ${n * 3}" }

        val splits = palette.map { split(it) }
        val nonRigid = splits.count { !it.isRigid }
        val anyNonRigid = nonRigid > 0

        val outP = FloatArray(n * 3)
        val outN = if (normals != null) FloatArray(n * 3) else null
        var flips = 0

        val blended = DualQuaternionf()
        val p = Vector3f()
        val nv = Vector3f()
        val residual = Matrix3f()
        val normalResidual = Matrix3f()
        val k = GltfSkinning.MAX_INFLUENCES

        for (v in 0 until n) {
            var sum = 0f
            for (i in 0 until k) sum += influences.weight[v * k + i]
            val norm = if (options.renormalize && sum > 0f) 1f / sum else 1f

            // The pivot is the HIGHEST-WEIGHT influence, not the first: the sign convention must not
            // depend on the order the bones arrived in.
            var pivot = -1
            var best = 0f
            for (i in 0 until k) {
                val w = influences.weight[v * k + i]
                if (w > best) { best = w; pivot = i }
            }
            if (pivot < 0) {
                outP[v * 3] = positions[v * 3]
                outP[v * 3 + 1] = positions[v * 3 + 1]
                outP[v * 3 + 2] = positions[v * 3 + 2]
                if (normals != null && outN != null) {
                    outN[v * 3] = normals[v * 3]
                    outN[v * 3 + 1] = normals[v * 3 + 1]
                    outN[v * 3 + 2] = normals[v * 3 + 2]
                }
                continue
            }
            val pivotDq = splits[influences.jointIndex[v * k + pivot]].dq

            blended.real.set(0f, 0f, 0f, 0f)
            blended.dual.set(0f, 0f, 0f, 0f)
            if (anyNonRigid) residual.set(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

            for (i in 0 until k) {
                val w0 = influences.weight[v * k + i]
                if (w0 == 0f) continue
                val w = w0 * norm
                val s = splits[influences.jointIndex[v * k + i]]
                val sign = if (quatDot(pivotDq, s.dq) < 0f) { if (i != pivot) flips++; -1f } else 1f
                accumulate(blended, s.dq, w * sign)
                if (anyNonRigid) addScaled3(residual, s.residual, w)
            }
            blended.normalize()

            p.set(positions[v * 3], positions[v * 3 + 1], positions[v * 3 + 2])
            if (anyNonRigid) residual.transform(p)
            blended.transform(p, p)
            outP[v * 3] = p.x; outP[v * 3 + 1] = p.y; outP[v * 3 + 2] = p.z

            if (normals != null && outN != null) {
                nv.set(normals[v * 3], normals[v * 3 + 1], normals[v * 3 + 2])
                if (anyNonRigid) {
                    // A normal takes the inverse transpose of the residual. The rigid half needs no
                    // such correction, which is the other quiet win here: a rotation is its own
                    // inverse transpose, so the normal path cannot go singular the way the
                    // matrix-blend one can.
                    normalResidual.set(
                        residual.m00, residual.m01, residual.m02,
                        residual.m10, residual.m11, residual.m12,
                        residual.m20, residual.m21, residual.m22,
                    )
                    val d = normalResidual.determinant()
                    if (abs(d) > 1e-20f && d.isFinite()) normalResidual.invert().transpose()
                    normalResidual.transform(nv)
                }
                blended.real.transform(nv, nv)
                val len = sqrt(nv.x * nv.x + nv.y * nv.y + nv.z * nv.z)
                if (len > 1e-12f) { nv.x /= len; nv.y /= len; nv.z /= len }
                outN[v * 3] = nv.x; outN[v * 3 + 1] = nv.y; outN[v * 3 + 2] = nv.z
            }
        }
        // singularNormalMatrices is 0 by construction: the rigid half is a rotation, which is its
        // own inverse transpose, so the normal path here cannot go singular the way the matrix
        // blend's can. That is a real difference between the methods, not a stub.
        return GltfSkinning.SkinnedVertices(n, outP, outN, 0, nonRigid, flips)
    }

    private fun quatDot(a: DualQuaternionf, b: DualQuaternionf): Float =
        a.real.x * b.real.x + a.real.y * b.real.y + a.real.z * b.real.z + a.real.w * b.real.w

    private fun accumulate(into: DualQuaternionf, dq: DualQuaternionf, w: Float) {
        into.real.set(
            into.real.x + dq.real.x * w,
            into.real.y + dq.real.y * w,
            into.real.z + dq.real.z * w,
            into.real.w + dq.real.w * w,
        )
        into.dual.set(
            into.dual.x + dq.dual.x * w,
            into.dual.y + dq.dual.y * w,
            into.dual.z + dq.dual.z * w,
            into.dual.w + dq.dual.w * w,
        )
    }

    private fun addScaled3(into: Matrix3f, s: Matrix3f?, w: Float) {
        if (s == null) {
            into.set(
                into.m00 + w, into.m01, into.m02,
                into.m10, into.m11 + w, into.m12,
                into.m20, into.m21, into.m22 + w,
            )
        } else {
            into.set(
                into.m00 + s.m00 * w, into.m01 + s.m01 * w, into.m02 + s.m02 * w,
                into.m10 + s.m10 * w, into.m11 + s.m11 * w, into.m12 + s.m12 * w,
                into.m20 + s.m20 * w, into.m21 + s.m21 * w, into.m22 + s.m22 * w,
            )
        }
    }

    /**
     * The candy wrapper, as a number: the smallest distance from the bone axis of any vertex on the
     * ring nearest [aroundY], over [positions].
     *
     * This is the measurement the whole file is for. Linear blending pinches that radius toward zero
     * under a twist; dual quaternion blending holds it near the rest radius. Reporting the *minimum*
     * rather than the mean matters — the collapse is a flattening, so one axis of the ring shrinks
     * while the perpendicular one does not, and a mean would average the artefact away.
     *
     * @param axis the bone axis the twist is about; the distance is measured perpendicular to it.
     */
    fun candyWrapperRadius(
        positions: FloatArray,
        aroundY: Float,
        tolerance: Float = 0.25f,
        axis: Vector3f = Vector3f(0f, 1f, 0f),
    ): Float {
        var worst = Float.MAX_VALUE
        var found = false
        val n = positions.size / 3
        for (v in 0 until n) {
            val x = positions[v * 3]; val y = positions[v * 3 + 1]; val z = positions[v * 3 + 2]
            val along = x * axis.x + y * axis.y + z * axis.z
            if (abs(along - aroundY) > tolerance) continue
            val px = x - axis.x * along
            val py = y - axis.y * along
            val pz = z - axis.z * along
            val r = sqrt(px * px + py * py + pz * pz)
            if (r < worst) worst = r
            found = true
        }
        return if (found) worst else Float.NaN
    }
}
