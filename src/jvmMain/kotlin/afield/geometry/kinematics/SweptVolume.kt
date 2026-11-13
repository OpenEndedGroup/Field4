@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.kinematics

import afield.geometry.solid.manifold.Solid
import afield.math.linalg.Matrix4d
import afield.math.linalg.Matrix4dc
import afield.math.linalg.Vector3d
import afield.math.linalg.Vector3dc
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Swept volumes and clearance as a Minkowski offset.
 *
 * [sweptVolume] builds the region a [Solid] passes through as it moves: a union of convex hulls, one for each step
 * of the motion, each built so it *contains* everything the part touches during that step. [minkowskiClearance]
 * offsets those hulls by a clearance and intersects them with obstacles, so "keeps 12 mm through the whole motion" is
 * one call with a pass or fail and the places it fails.
 *
 * ```kotlin
 * val door = Solid.cube(900.0, 40.0, 2000.0)
 * val swing = RigidMotion.rotation(vec(0.0, 0.0, 0.0), vec(0.0, 0.0, 1.0), 0.0, 90.0)
 * val swept = sweptVolume(door, swing, maxStep = 5.0)
 * println("${swept.solid.volume} mm³, at most ${swept.maxOvershoot} mm beyond the true sweep")
 * val verdict = minkowskiClearance(door, swing, listOf(wall), clearance = 12.7)
 * println(if (verdict.passes) "keeps 12.7 mm" else "fails: ${verdict.worst}")
 * ```
 */

/**
 * One convex piece of a [SweptVolume]: what one point set of the part (a convex component, or one boundary
 * triangle of a non-convex one) sweeps between the motion values [from] and [to].
 */
class SweptPiece(
    /** The motion value at the start of the step. */
    val from: Double,
    /** The motion value at the end of the step. */
    val to: Double,
    /** The hull's points, flat `x, y, z` triples, world coordinates. */
    val points: DoubleArray,
    /** The convex hull of [points]. */
    val hull: Solid,
)

/**
 * The region a part passes through, as the union of convex [pieces].
 *
 * It is **conservative**: every point any pose of the part occupies is inside [solid]. It may reach beyond the true
 * sweep by at most [maxOvershoot] (in model units): for a slide of convex pieces it is exact, and for a rotation the
 * bound shrinks with the step. [AutoCloseable]: [close] releases the native solids.
 */
class SweptVolume(
    /** The motion swept. */
    val motion: RigidMotion,
    /** The union of every piece. */
    val solid: Solid,
    /** The convex pieces, step by step. */
    val pieces: List<SweptPiece>,
    /** How far [solid] can reach beyond the true swept region, at most. */
    val maxOvershoot: Double,
    /** The number of motion steps. */
    val stepCount: Int,
) : AutoCloseable {
    /** Release the native solids. */
    override fun close() {
        pieces.forEach { it.hull.close() }
        solid.close()
    }

    /** Volume, steps, pieces and the overshoot bound, for `println`. */
    override fun toString(): String =
        "SweptVolume(%.6g, $stepCount steps, ${pieces.size} pieces, overshoot <= %.4g)".format(solid.volume, maxOvershoot)
}

/** How [sweptVolume] covers each connected component of the part. */
enum class SweepShape {
    /** Hull convex components whole and sweep non-convex ones triangle by triangle (the default). */
    AUTO,

    /** Hull every component whole: fewest pieces, but recesses of a non-convex component are filled in. */
    HULLS,

    /** Sweep every boundary triangle: keeps every recess, at one hull per triangle per step. */
    TRIANGLES,
}

/**
 * The swept volume of [part] through [motion], as the union of convex hulls of successive poses.
 *
 * The motion is cut into equal steps no longer than [maxStep] (and no more than 90° for a rotation). Over one step a
 * point on a rotating part follows an arc that bulges outside the chord between its end positions, so the hull of
 * the two end poses alone would miss a sliver; each piece also hulls a third copy, at the middle angle and pushed out
 * from the axis by `1/cos(half-step)`, where the arc's end tangents meet, which puts every arc inside. The result
 * contains the true swept region and exceeds it by at most [SweptVolume.maxOvershoot]: `r · 2 sin(h/2)` for a
 * half-step angle `h` and farthest radius `r` (a first-order bound; in practice the excess is the much smaller
 * sagitta, `r (1 − cos h)`).
 *
 * A non-convex component is covered triangle by triangle ([SweepShape.AUTO]), together with its start and end
 * poses: each boundary triangle's sweep is convex-hulled, which keeps every recess of the part open (the idea of a
 * translation envelope built from forward-facing triangles, generalised to rotation).
 */
fun sweptVolume(
    part: Solid,
    motion: RigidMotion,
    maxStep: Double = defaultStep(motion),
    shape: SweepShape = SweepShape.AUTO,
): SweptVolume {
    val step = if (motion is Rotation) min(maxStep, 90.0) else maxStep
    val values = motion.stepsBy(step).map { it.value }
    val sets = pointSets(part, shape)
    val pieces = ArrayList<SweptPiece>()
    var overshoot = 0.0
    for (i in 0 until values.size - 1) {
        val a = values[i]; val b = values[i + 1]
        val poses = coveringPoses(motion, a, b)
        for (set in sets.sets) {
            val pts = DoubleArray(set.size * poses.size)
            for ((k, m) in poses.withIndex()) transformInto(m, set, pts, k * set.size)
            val hull = Solid.hullOfPointsD(pts)
            // A triangle sliding in its own plane sweeps nothing solid: its hull is flat, and empty.
            if (hull.status.isError || hull.isEmpty) hull.close() else pieces += SweptPiece(a, b, pts, hull)
        }
        overshoot = max(overshoot, overshootOf(motion, sets.all, a, b))
    }
    // A non-convex component swept by triangles needs its end poses too: the triangles cover only its surface's path.
    val ends = if (sets.wholeEnds.isEmpty()) emptyList() else listOf(values.first(), values.last()).flatMap { value ->
        sets.wholeEnds.map { it.transformedBy(motion.poseAt(value)) }
    }
    val solid = Solid.union(pieces.map { it.hull } + ends)
    ends.forEach { it.close() }
    return SweptVolume(motion, solid, pieces, overshoot, values.size - 1)
}

/**
 * The poses whose convex hull, applied to a convex point set, covers that set's every pose between [a] and [b]:
 * the two ends for a slide; the two ends and the tangent-point copy for a rotation.
 */
internal fun coveringPoses(motion: RigidMotion, a: Double, b: Double): List<Matrix4dc> = when (motion) {
    is Slide -> listOf(motion.poseAt(a), motion.poseAt(b))
    is Rotation -> {
        val h = Math.toRadians(abs(b - a)) / 2
        if (h == 0.0) listOf(motion.poseAt(a)) else {
            val mid = motion.poseAt((a + b) / 2)
            listOf(motion.poseAt(a), motion.poseAt(b), Matrix4d(mid).mul(radialScale(motion, 1.0 / cos(h))))
        }
    }
}

/** Scale distances from [r]'s axis by [s], leaving positions along the axis alone. */
private fun radialScale(r: Rotation, s: Double): Matrix4d {
    val n = sqrt(r.axis.x() * r.axis.x() + r.axis.y() * r.axis.y() + r.axis.z() * r.axis.z())
    val u = doubleArrayOf(r.axis.x() / n, r.axis.y() / n, r.axis.z() / n)
    // A = s I + (1 - s) u uᵀ, t = p - A p; column-major.
    val a = Array(3) { c -> DoubleArray(3) { row -> (if (row == c) s else 0.0) + (1 - s) * u[row] * u[c] } }
    val p = doubleArrayOf(r.point.x(), r.point.y(), r.point.z())
    val t = DoubleArray(3) { row -> p[row] - (a[0][row] * p[0] + a[1][row] * p[1] + a[2][row] * p[2]) }
    return Matrix4d(
        a[0][0], a[0][1], a[0][2], 0.0,
        a[1][0], a[1][1], a[1][2], 0.0,
        a[2][0], a[2][1], a[2][2], 0.0,
        t[0], t[1], t[2], 1.0,
    )
}

/** The bound on how far one step's hull reaches past the true sweep of [xyz]. */
internal fun overshootOf(motion: RigidMotion, xyz: DoubleArray, a: Double, b: Double): Double = when (motion) {
    is Slide -> 0.0
    is Rotation -> {
        val h = Math.toRadians(abs(b - a)) / 2
        val r = motion.radiusOf(xyz)
        r * max(2 * sin(h / 2), 1 / cos(h) - 1)
    }
}

/** The point sets a part is swept as, all its vertices, and the non-convex components whose end poses are kept. */
internal class PointSets(val sets: List<DoubleArray>, val all: DoubleArray, val wholeEnds: List<Solid>)

internal fun pointSets(part: Solid, shape: SweepShape): PointSets {
    val components = part.decompose().ifEmpty { listOf(part) }
    val sets = ArrayList<DoubleArray>()
    val ends = ArrayList<Solid>()
    val all = ArrayList<Double>()
    for (c in components) {
        val mesh = c.toPlanarMesh()
        mesh.positions.forEach { all += it }
        val convex = when (shape) {
            SweepShape.HULLS -> true
            SweepShape.TRIANGLES -> false
            SweepShape.AUTO -> c.hull().use { h -> h.volume - c.volume <= 1e-9 * max(h.volume, 1e-300) + 1e-12 }
        }
        if (convex) {
            sets += mesh.positions.copyOf()
        } else {
            for (t in 0 until mesh.triangleCount) {
                sets += DoubleArray(9) { k -> mesh.positions[mesh.indices[t * 3 + k / 3] * 3 + k % 3] }
            }
            ends += c.copy()
        }
    }
    if (components.size > 1 || components[0] !== part) components.forEach { if (it !== part) it.close() }
    return PointSets(sets, all.toDoubleArray(), ends)
}

internal fun transformInto(m: Matrix4dc, xyz: DoubleArray, out: DoubleArray, offset: Int) {
    var i = 0
    while (i < xyz.size) {
        val x = xyz[i]; val y = xyz[i + 1]; val z = xyz[i + 2]
        out[offset + i] = m.m00() * x + m.m10() * y + m.m20() * z + m.m30()
        out[offset + i + 1] = m.m01() * x + m.m11() * y + m.m21() * z + m.m31()
        out[offset + i + 2] = m.m02() * x + m.m12() * y + m.m22() * z + m.m32()
        i += 3
    }
}

internal fun Solid.transformedBy(m: Matrix4dc): Solid = transformed(
    m.m00(), m.m01(), m.m02(), m.m10(), m.m11(), m.m12(), m.m20(), m.m21(), m.m22(), m.m30(), m.m31(), m.m32(),
)

// ==============================================================================================================
// Clearance as a Minkowski offset
// ==============================================================================================================

/**
 * One obstacle that comes within the clearance: the motion range over which it does ([from] to [to]), the step
 * where the overlap is largest ([worstFrom] to [worstTo]), how big that overlap is, and where.
 */
class ClearanceHit(
    /** The obstacle's name. */
    val obstacle: String,
    /** Its position in the list given. */
    val obstacleIndex: Int,
    /** The motion value at the start of the first step that comes too close. */
    val from: Double,
    /** The motion value at the end of the last step that comes too close. */
    val to: Double,
    /** The start of the step with the largest overlap. */
    val worstFrom: Double,
    /** The end of the step with the largest overlap. */
    val worstTo: Double,
    /** The volume of the offset sweep inside the obstacle at that step: a measure of how badly, not a distance. */
    val overlapVolume: Double,
    /** The centre of that overlap's bounding box, world coordinates: where. */
    val where: Vector3dc,
    /** That overlap's bounds, `[minX, minY, minZ, maxX, maxY, maxZ]`. */
    val overlapBounds: DoubleArray,
) {
    /** The obstacle, the range and where, for `println`. */
    override fun toString(): String =
        "ClearanceHit($obstacle from %.4g to %.4g, worst %.4g..%.4g, %.4g in overlap at $where)".format(from, to, worstFrom, worstTo, overlapVolume)
}

/**
 * The verdict of [minkowskiClearance]: [passes] when no obstacle comes within [clearance] of the part anywhere along
 * the motion, and otherwise the [hits], [worst] (the largest overlap) first.
 */
class MinkowskiClearance(
    /** The clearance asked for. */
    val clearance: Double,
    /** Every obstacle the offset sweep meets, worst first. */
    val hits: List<ClearanceHit>,
    /** How far the offset sweep can reach beyond `clearance` around the true sweep: the verdict's resolution. */
    val maxOvershoot: Double,
    /** The number of motion steps. */
    val stepCount: Int,
) {
    /** True when nothing comes within the clearance. */
    val passes: Boolean get() = hits.isEmpty()

    /** The hit with the largest overlap, or `null` when it passes. */
    val worst: ClearanceHit? get() = hits.firstOrNull()

    /** Pass or fail and the worst place, for `println`. */
    override fun toString(): String =
        if (passes) "MinkowskiClearance(keeps $clearance, resolution %.4g)".format(maxOvershoot)
        else "MinkowskiClearance(fails $clearance: ${hits.size} obstacle(s), worst $worst)"
}

/**
 * "Does [part] keep [clearance] from every obstacle through the whole [motion]?", answered with Manifold booleans.
 *
 * Each convex piece of the swept volume ([sweptVolume]) is offset by [clearance] — the hull of its points plus a
 * polyhedral sphere, scaled so the sphere's faces, not just its vertices, lie at least [clearance] out, so the offset
 * contains the true one — and intersected with every obstacle whose box it reaches. Any overlap of more than
 * [minOverlapVolume] fails, and is reported with the steps that hit and where.
 *
 * It is conservative: a pass means every pose keeps at least [clearance] (from every obstacle that reaches the part's
 * surface: one lying wholly inside the part is not seen). A fail means some pose comes within
 * `clearance + maxOvershoot`; for the exact gap there, run [sweptClearance] over the hit's steps.
 *
 * @param names a label per obstacle for the report; defaults to `"obstacle i"`.
 * @param sphereSegments the offset sphere's resolution; more is a tighter offset and slower hulls.
 */
fun minkowskiClearance(
    part: Solid,
    motion: RigidMotion,
    obstacles: List<Solid>,
    clearance: Double,
    maxStep: Double = defaultStep(motion),
    names: List<String> = obstacles.indices.map { "obstacle $it" },
    shape: SweepShape = SweepShape.AUTO,
    sphereSegments: Int = 16,
    minOverlapVolume: Double = 1e-9,
): MinkowskiClearance {
    require(clearance >= 0.0 && clearance.isFinite()) { "clearance must be finite and non-negative, got $clearance" }
    require(names.size == obstacles.size) { "one name per obstacle" }
    val step = if (motion is Rotation) min(maxStep, 90.0) else maxStep
    val values = motion.stepsBy(step).map { it.value }
    val sets = pointSets(part, shape)
    val (ball, ballOvershoot) = offsetBall(clearance, sphereSegments)
    val obstacleBounds = obstacles.map { o -> o.bounds.let { doubleArrayOf(it.min.x.toDouble(), it.min.y.toDouble(), it.min.z.toDouble(), it.max.x.toDouble(), it.max.y.toDouble(), it.max.z.toDouble()) } }
    class Overlap(val from: Double, val to: Double, val volume: Double, val bounds: DoubleArray)
    val found = HashMap<Int, MutableList<Overlap>>()
    var overshoot = 0.0
    // The offset triangle sweeps already cover everything within the clearance of a non-convex component's surface,
    // at every pose; its end poses add only its own interior, so they are not needed here.
    sets.wholeEnds.forEach { it.close() }
    for (i in 0 until values.size - 1) {
        val a = values[i]; val b = values[i + 1]
        val poses = coveringPoses(motion, a, b)
        overshoot = max(overshoot, overshootOf(motion, sets.all, a, b))
        for (set in sets.sets) {
            val pts = DoubleArray(set.size * poses.size)
            for ((k, m) in poses.withIndex()) transformInto(m, set, pts, k * set.size)
            val offsetPts = minkowskiPoints(pts, ball)
            val box = boundsOf(offsetPts)
            val near = obstacleBounds.indices.filter { boxesTouch(box, obstacleBounds[it]) }
            if (near.isEmpty()) continue
            Solid.hullOfPointsD(offsetPts).use { hull ->
                for (o in near) (hull and obstacles[o]).use { cut ->
                    val v = cut.volume
                    if (v > minOverlapVolume) {
                        val bb = cut.bounds
                        found.getOrPut(o) { ArrayList() } += Overlap(a, b, v, doubleArrayOf(bb.min.x.toDouble(), bb.min.y.toDouble(), bb.min.z.toDouble(), bb.max.x.toDouble(), bb.max.y.toDouble(), bb.max.z.toDouble()))
                    }
                }
            }
        }
    }
    val hits = found.map { (o, list) ->
        val w = list.maxBy { it.volume }
        ClearanceHit(
            names[o], o, list.minOf { min(it.from, it.to) }, list.maxOf { max(it.from, it.to) }, w.from, w.to, w.volume,
            Vector3d((w.bounds[0] + w.bounds[3]) / 2, (w.bounds[1] + w.bounds[4]) / 2, (w.bounds[2] + w.bounds[5]) / 2), w.bounds,
        )
    }.sortedByDescending { it.overlapVolume }
    return MinkowskiClearance(clearance, hits, overshoot + ballOvershoot, values.size - 1)
}

/**
 * A polyhedral sphere's vertices, scaled so every face lies at least [radius] from the centre (so the polyhedron
 * contains the true ball), and how far its vertices reach beyond [radius].
 */
internal fun offsetBall(radius: Double, segments: Int): Pair<DoubleArray, Double> {
    if (radius == 0.0) return doubleArrayOf(0.0, 0.0, 0.0) to 0.0
    val mesh = Solid.sphere(1.0, segments).use { it.toPlanarMesh() }
    val p = mesh.positions
    var inradius = Double.POSITIVE_INFINITY
    for (t in 0 until mesh.triangleCount) {
        val a = mesh.indices[t * 3] * 3; val b = mesh.indices[t * 3 + 1] * 3; val c = mesh.indices[t * 3 + 2] * 3
        val ux = p[b] - p[a]; val uy = p[b + 1] - p[a + 1]; val uz = p[b + 2] - p[a + 2]
        val vx = p[c] - p[a]; val vy = p[c + 1] - p[a + 1]; val vz = p[c + 2] - p[a + 2]
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        val n = sqrt(nx * nx + ny * ny + nz * nz)
        if (n < 1e-15) continue
        inradius = min(inradius, abs(nx * p[a] + ny * p[a + 1] + nz * p[a + 2]) / n)
    }
    val scale = radius / inradius
    var reach = 0.0
    val out = DoubleArray(p.size) { p[it] * scale }
    for (i in 0 until out.size / 3) reach = max(reach, sqrt(out[i * 3] * out[i * 3] + out[i * 3 + 1] * out[i * 3 + 1] + out[i * 3 + 2] * out[i * 3 + 2]))
    return out to (reach - radius)
}

/** Every point of [pts] plus every point of [ball]: the vertex set whose hull is the hull of [pts] offset by the ball. */
internal fun minkowskiPoints(pts: DoubleArray, ball: DoubleArray): DoubleArray {
    val n = pts.size / 3; val m = ball.size / 3
    val out = DoubleArray(n * m * 3)
    var o = 0
    for (i in 0 until n) for (j in 0 until m) {
        out[o++] = pts[i * 3] + ball[j * 3]; out[o++] = pts[i * 3 + 1] + ball[j * 3 + 1]; out[o++] = pts[i * 3 + 2] + ball[j * 3 + 2]
    }
    return out
}

private fun boundsOf(xyz: DoubleArray): DoubleArray {
    val b = doubleArrayOf(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY)
    var i = 0
    while (i < xyz.size) {
        for (k in 0..2) { b[k] = min(b[k], xyz[i + k]); b[3 + k] = max(b[3 + k], xyz[i + k]) }
        i += 3
    }
    return b
}

private fun boxesTouch(a: DoubleArray, b: DoubleArray): Boolean =
    a[0] <= b[3] && b[0] <= a[3] && a[1] <= b[4] && b[1] <= a[4] && a[2] <= b[5] && b[2] <= a[5]
