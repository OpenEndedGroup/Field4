@file:afield.runtime.completion.CompletionCategory("Physics")

package afield.simulation.physics

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Rigid plates joined by elastic hinges, relaxed to static equilibrium: the smallest model of a cut
 * sheet that can buckle out of its plane.
 *
 * ```kotlin
 * val plates = HingedPlates([doubleArrayOf(0.5, 0.5, 0.0), doubleArrayOf(1.5, 0.5, 0.0)])
 * plates.hinge(0, 1, at = doubleArrayOf(1.0, 0.0, 0.0), along = doubleArrayOf(0.0, 1.0, 0.0),
 *     stiffness = HingedPlates.Stiffness.ligament(width = 0.12, length = 0.08, thickness = 0.05))
 * plates.fixed[0] = true
 * plates.position[3] = 1.6 // pull plate 1 to the right
 * plates.relax()
 * ```
 *
 * Every plate has a position and a rotation (body to world, row-major 3 x 3). A hinge joins one
 * point of plate `a` to one point of plate `b` and holds four stiffnesses, in the hinge's own frame
 * at rest: `across` (the ligament's axis, from `a` towards `b`), `along` (in the sheet, along the
 * cut) and the sheet normal. Its energy is
 *
 * `½ stretch |pa − pb|² + ½ twist θ_across² + ½ fold θ_along² + ½ inPlane θ_normal²`,
 *
 * where `θ = log(Ra⁻¹ Rb rest⁻¹)` is the relative rotation of the two plates since rest. That is a
 * one-element Cosserat beam per hinge. [Stiffness.ligament] fills the four numbers in from beam
 * theory for a ligament of a given width, length and thickness, so a thin sheet's hinges fold and
 * twist easily and resist turning in the plane, which is what makes such a sheet buckle out of
 * plane when it is stretched.
 *
 * [relax] minimises the total energy over every plate not marked [fixed], by Levenberg–Marquardt on
 * the hinge residuals with a dense Cholesky solve: six unknowns a plate, so a few hundred plates
 * relax in milliseconds. The rotations are updated multiplicatively (`R ← exp(ω) R`), so there is no
 * singular angle parametrisation. [gradient] gives the force on every plate, fixed ones included,
 * which is how a reaction at a clamp is read.
 *
 * [SimdVerlet] cannot express this: its particles carry no orientation, so it has no twist, and
 * its constraint stiffness is a product with the iteration count, so it defines no force to read
 * a stress from.
 *
 * @param centres each plate's starting position, xyz.
 */
class HingedPlates(centres: List<DoubleArray>) {

    /** How many plates. */
    val count = centres.size

    /** Plate positions, xyz per plate. */
    val position = DoubleArray(3 * count).also { p -> centres.forEachIndexed { i, c -> for (k in 0 until 3) p[3 * i + k] = c[k] } }

    /** Plate rotations, body to world, nine row-major entries per plate; identity at the start. */
    val rotation = DoubleArray(9 * count).also { r -> for (i in 0 until count) for (k in 0 until 3) r[9 * i + 4 * k] = 1.0 }

    /** Plates [relax] does not move: clamps, or plates whose motion you prescribe. */
    val fixed = BooleanArray(count)

    /**
     * The four stiffnesses of one hinge.
     *
     * @param stretch translational stiffness holding the two anchor points together, force per length.
     * @param twist rotational stiffness about the ligament's axis, moment per radian.
     * @param fold rotational stiffness about the in-plane axis along the cut: out-of-plane bending.
     * @param inPlane rotational stiffness about the sheet normal: in-plane bending.
     */
    data class Stiffness(val stretch: Double, val twist: Double, val fold: Double, val inPlane: Double) {
        companion object {
            /**
             * Beam theory for a straight ligament [length] long (from plate to plate), [width] wide in
             * the sheet and [thickness] thick: `EA/L`, `GJ/L`, `E w t³ / 12L` and `E t w³ / 12L`,
             * with the torsion constant of a rectangle, `J = a b³ (1/3 − 0.21 (b/a)(1 − (b/a)⁴/12))`.
             */
            fun ligament(width: Double, length: Double, thickness: Double, youngs: Double = 1.0, poisson: Double = 0.3): Stiffness {
                val shear = youngs / (2 * (1 + poisson))
                val a = max(width, thickness)
                val b = minOf(width, thickness)
                val r = b / a
                val torsion = a * b * b * b * (1.0 / 3 - 0.21 * r * (1 - r * r * r * r / 12))
                return Stiffness(
                    stretch = youngs * width * thickness / length,
                    twist = shear * torsion / length,
                    fold = youngs * width * thickness * thickness * thickness / (12 * length),
                    inPlane = youngs * thickness * width * width * width / (12 * length),
                )
            }
        }
    }

    /**
     * One hinge between plates [a] and [b].
     *
     * @param a the first plate.
     * @param b the second plate.
     * @param anchorA the joined point in [a]'s body frame.
     * @param anchorB the joined point in [b]'s body frame.
     * @param axes the hinge frame in [a]'s body frame: rows `across`, `along`, `normal`.
     * @param stiffness the four stiffnesses.
     * @param rest the relative rotation `Ra⁻¹ Rb` at which the hinge holds no moment, row-major.
     */
    class Hinge(
        val a: Int,
        val b: Int,
        val anchorA: DoubleArray,
        val anchorB: DoubleArray,
        val axes: DoubleArray,
        val stiffness: Stiffness,
        val rest: DoubleArray,
    ) {
        internal val weights = doubleArrayOf(
            sqrt(stiffness.stretch), sqrt(stiffness.stretch), sqrt(stiffness.stretch),
            sqrt(stiffness.twist), sqrt(stiffness.fold), sqrt(stiffness.inPlane),
        )
    }

    /** Every hinge, in the order [hinge] added them. */
    val hinges = mutableListOf<Hinge>()

    /**
     * Joins plates [a] and [b] at world point [at], with the cut running along [along] and the sheet
     * normal [normal]; `across` is `along × normal`, flipped to point from [a] towards [b]. The
     * hinge is at rest in the plates' current poses, turned by [kink] (a rotation vector in the hinge
     * frame, radians), which is how a small imperfection is cut into an otherwise flat sheet.
     */
    fun hinge(
        a: Int,
        b: Int,
        at: DoubleArray,
        along: DoubleArray,
        stiffness: Stiffness,
        normal: DoubleArray = doubleArrayOf(0.0, 0.0, 1.0),
        kink: DoubleArray = DoubleArray(3),
    ): Hinge {
        val e2 = normalised(along)
        val e3 = normalised(normal)
        var e1 = cross(e2, e3)
        val ab = DoubleArray(3) { position[3 * b + it] - position[3 * a + it] }
        if (dot(e1, ab) < 0) e1 = DoubleArray(3) { -e1[it] }
        val ra = rotation.copyOfRange(9 * a, 9 * a + 9)
        val rb = rotation.copyOfRange(9 * b, 9 * b + 9)
        // The frame and the anchors live in each plate's body frame.
        val axesWorld = e1 + e2 + e3
        val axes = DoubleArray(9)
        for (row in 0 until 3) {
            val local = transposeTimes(ra, axesWorld.copyOfRange(3 * row, 3 * row + 3))
            for (k in 0 until 3) axes[3 * row + k] = local[k]
        }
        fun anchor(r: DoubleArray, plate: Int) = transposeTimes(r, DoubleArray(3) { at[it] - position[3 * plate + it] })
        val kinkBody = DoubleArray(3) { k -> (0 until 3).sumOf { row -> kink[row] * axes[3 * row + k] } }
        val rest = compose(compose(transposeOf(ra), rb), exp(kinkBody))
        return Hinge(a, b, anchor(ra, a), anchor(rb, b), axes, stiffness, rest).also { hinges += it }
    }

    /** The six weighted residuals of [h] with plate poses taken from [pa], [ra], [pb], [rb]. */
    private fun residual(h: Hinge, pa: DoubleArray, ra: DoubleArray, pb: DoubleArray, rb: DoubleArray, out: DoubleArray) {
        val wa = rotate(ra, h.anchorA)
        val wb = rotate(rb, h.anchorB)
        for (k in 0 until 3) out[k] = h.weights[k] * (pa[k] + wa[k] - pb[k] - wb[k])
        val theta = log(compose(compose(transposeOf(ra), rb), transposeOf(h.rest)))
        for (row in 0 until 3) {
            out[3 + row] = h.weights[3 + row] * (h.axes[3 * row] * theta[0] + h.axes[3 * row + 1] * theta[1] + h.axes[3 * row + 2] * theta[2])
        }
    }

    private fun poseOf(plate: Int) = position.copyOfRange(3 * plate, 3 * plate + 3) to rotation.copyOfRange(9 * plate, 9 * plate + 9)

    private fun nudged(plate: Int, d: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val (p, r) = poseOf(plate)
        return DoubleArray(3) { p[it] + d[it] } to compose(exp(d.copyOfRange(3, 6)), r)
    }

    /** Residuals and their 6 x 12 Jacobian with respect to increments of plate a then plate b. */
    private fun linearise(h: Hinge, r0: DoubleArray, jac: Array<DoubleArray>) {
        val (pa, ra) = poseOf(h.a)
        val (pb, rb) = poseOf(h.b)
        residual(h, pa, ra, pb, rb, r0)
        val step = 1e-7
        val plus = DoubleArray(6)
        val minus = DoubleArray(6)
        for (c in 0 until 12) {
            val d = DoubleArray(6)
            d[c % 6] = step
            if (c < 6) {
                val (p1, q1) = nudged(h.a, d)
                residual(h, p1, q1, pb, rb, plus)
                d[c % 6] = -step
                val (p2, q2) = nudged(h.a, d)
                residual(h, p2, q2, pb, rb, minus)
            } else {
                val (p1, q1) = nudged(h.b, d)
                residual(h, pa, ra, p1, q1, plus)
                d[c % 6] = -step
                val (p2, q2) = nudged(h.b, d)
                residual(h, pa, ra, p2, q2, minus)
            }
            for (row in 0 until 6) jac[row][c] = (plus[row] - minus[row]) / (2 * step)
        }
    }

    /** The total elastic energy. */
    fun energy(): Double {
        val r = DoubleArray(6)
        return hinges.sumOf { h ->
            val (pa, ra) = poseOf(h.a)
            val (pb, rb) = poseOf(h.b)
            residual(h, pa, ra, pb, rb, r)
            0.5 * r.sumOf { it * it }
        }
    }

    /**
     * The energy's gradient for every plate, fixed ones included: six numbers a plate, force then
     * moment, each the negative of what the hinges push with. On a clamped plate it is the reaction
     * the clamp has to supply.
     */
    fun gradient(): DoubleArray {
        val g = DoubleArray(6 * count)
        val r = DoubleArray(6)
        val jac = Array(6) { DoubleArray(12) }
        for (h in hinges) {
            linearise(h, r, jac)
            for (c in 0 until 12) {
                val plate = if (c < 6) h.a else h.b
                g[6 * plate + c % 6] += (0 until 6).sumOf { jac[it][c] * r[it] }
            }
        }
        return g
    }

    /**
     * What [relax] did.
     *
     * @param iterations accepted and rejected steps taken.
     * @param energy the energy it stopped at.
     * @param gradient the largest gradient component left on a free plate.
     */
    data class Relaxed(val iterations: Int, val energy: Double, val gradient: Double)

    /** Moves every free plate to the nearest energy minimum: Levenberg–Marquardt, stopping when a step changes no coordinate by more than [tolerance]. */
    fun relax(maxIterations: Int = 60, tolerance: Double = 1e-11): Relaxed {
        val free = (0 until count).filter { !fixed[it] }
        val slot = IntArray(count) { -1 }
        free.forEachIndexed { k, plate -> slot[plate] = 6 * k }
        val m = 6 * free.size
        var damping = 1e-6
        var energy = energy()
        var iterations = 0
        val r = DoubleArray(6)
        val jac = Array(6) { DoubleArray(12) }
        var worst = 0.0
        while (iterations < maxIterations && m > 0) {
            iterations++
            val normal = Array(m) { DoubleArray(m) }
            val g = DoubleArray(m)
            for (h in hinges) {
                linearise(h, r, jac)
                val blocks = listOf(0 to slot[h.a], 6 to slot[h.b]).filter { it.second >= 0 }
                for ((ca, oa) in blocks) {
                    for (i in 0 until 6) {
                        g[oa + i] += (0 until 6).sumOf { jac[it][ca + i] * r[it] }
                        for ((cb, ob) in blocks) for (j in 0 until 6) {
                            normal[oa + i][ob + j] += (0 until 6).sumOf { jac[it][ca + i] * jac[it][cb + j] }
                        }
                    }
                }
            }
            worst = g.maxOf { abs(it) }
            var accepted = false
            while (!accepted && damping < 1e12) {
                val a = Array(m) { i -> normal[i].copyOf().also { it[i] += damping * max(normal[i][i], 1e-12) + 1e-14 } }
                val step = cholesky(a, DoubleArray(m) { -g[it] }) ?: run {
                    damping *= 10
                    null
                } ?: continue
                val savedP = position.copyOf()
                val savedR = rotation.copyOf()
                for (plate in free) {
                    val (p, q) = nudged(plate, step.copyOfRange(slot[plate], slot[plate] + 6))
                    for (k in 0 until 3) position[3 * plate + k] = p[k]
                    for (k in 0 until 9) rotation[9 * plate + k] = q[k]
                }
                val candidate = energy()
                if (candidate <= energy) {
                    accepted = true
                    energy = candidate
                    damping = max(damping * 0.3, 1e-12)
                    if (step.maxOf { abs(it) } < tolerance) return Relaxed(iterations, energy, worst)
                } else {
                    position.indices.forEach { position[it] = savedP[it] }
                    rotation.indices.forEach { rotation[it] = savedR[it] }
                    damping *= 10
                }
            }
            if (!accepted) break
        }
        return Relaxed(iterations, energy, worst)
    }

    /** Plate [plate]'s normal: its rotation applied to body `z`. */
    fun normal(plate: Int) = doubleArrayOf(rotation[9 * plate + 2], rotation[9 * plate + 5], rotation[9 * plate + 8])

    /** A body-frame point of plate [plate] in world coordinates. */
    fun world(plate: Int, body: DoubleArray): DoubleArray {
        val w = rotate(rotation.copyOfRange(9 * plate, 9 * plate + 9), body)
        return DoubleArray(3) { position[3 * plate + it] + w[it] }
    }

    companion object {
        private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

        private fun cross(a: DoubleArray, b: DoubleArray) =
            doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

        private fun normalised(a: DoubleArray): DoubleArray {
            val l = sqrt(dot(a, a))
            return DoubleArray(3) { a[it] / l }
        }

        private fun rotate(m: DoubleArray, v: DoubleArray) =
            DoubleArray(3) { m[3 * it] * v[0] + m[3 * it + 1] * v[1] + m[3 * it + 2] * v[2] }

        private fun transposeTimes(m: DoubleArray, v: DoubleArray) =
            DoubleArray(3) { m[it] * v[0] + m[3 + it] * v[1] + m[6 + it] * v[2] }

        private fun transposeOf(m: DoubleArray) = DoubleArray(9) { m[3 * (it % 3) + it / 3] }

        private fun compose(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { k ->
            val i = k / 3
            val j = k % 3
            a[3 * i] * b[j] + a[3 * i + 1] * b[3 + j] + a[3 * i + 2] * b[6 + j]
        }

        /** Rodrigues: the rotation matrix of rotation vector [w]. */
        fun exp(w: DoubleArray): DoubleArray {
            val angle = sqrt(dot(w, w))
            val (s, c) = if (angle < 1e-8) (1.0 - angle * angle / 6) to (0.5 - angle * angle / 24) else (sin(angle) / angle) to ((1 - cos(angle)) / (angle * angle))
            val (x, y, z) = Triple(w[0], w[1], w[2])
            return doubleArrayOf(
                1 - c * (y * y + z * z), -s * z + c * x * y, s * y + c * x * z,
                s * z + c * x * y, 1 - c * (x * x + z * z), -s * x + c * y * z,
                -s * y + c * x * z, s * x + c * y * z, 1 - c * (x * x + y * y),
            )
        }

        /** The rotation vector of rotation matrix [r], for turns below π. */
        fun log(r: DoubleArray): DoubleArray {
            val v = doubleArrayOf((r[7] - r[5]) / 2, (r[2] - r[6]) / 2, (r[3] - r[1]) / 2)
            val s = sqrt(dot(v, v))
            val c = (r[0] + r[4] + r[8] - 1) / 2
            if (s < 1e-12) return v
            val angle = atan2(s, c)
            return DoubleArray(3) { v[it] * angle / s }
        }

        /** Solves the symmetric positive definite system [a] x = [b] in place; null if [a] is not positive definite. */
        private fun cholesky(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val n = b.size
            for (j in 0 until n) {
                val row = a[j]
                var d = row[j]
                for (k in 0 until j) d -= row[k] * row[k]
                if (d <= 0 || !d.isFinite()) return null
                d = sqrt(d)
                row[j] = d
                for (i in j + 1 until n) {
                    val other = a[i]
                    var s = other[j]
                    for (k in 0 until j) s -= other[k] * row[k]
                    other[j] = s / d
                }
            }
            for (i in 0 until n) {
                var s = b[i]
                for (k in 0 until i) s -= a[i][k] * b[k]
                b[i] = s / a[i][i]
            }
            for (i in n - 1 downTo 0) {
                var s = b[i]
                for (k in i + 1 until n) s -= a[k][i] * b[k]
                b[i] = s / a[i][i]
            }
            return b
        }
    }
}
