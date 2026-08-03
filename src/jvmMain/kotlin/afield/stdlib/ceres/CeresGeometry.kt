@file:afield.CompletionCategory("Graphics")

package afield.stdlib.ceres

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * The constraint vocabulary for Field's Ceres point solver, and the encoding that ships it to a
 * backend. Geometry is a flat xyz [PointCloud] addressed by [PointId]; a solve is described by a
 * [CeresProgram] of [GeometryConstraint]s — [Planar], [Colinear], [DistanceEquals],
 * [Perpendicular], [LockPosition], [WeakPosition], [PointOnPlane], [DihedralAngle], [PanelArea] and
 * the rest — most conveniently assembled with [buildCeresProgram] / [CeresProgramBuilder].
 *
 * Every constraint serialises itself into one flat `DoubleArray` of opcodes ([CeresOpcode]) via
 * `encodeLegacyBytecode`; the actual solving happens behind [CeresSolverBackend] /
 * [CompiledCeresSolver] (see
 * `FfmCeresBackend.kt` for the native one). [CachedCeresSolvers] keys compiled solvers by point
 * count plus program hash so an unchanged program is compiled once.
 *
 * Constructors validate aggressively: coordinates and weights must be finite, weights non-negative,
 * point indices distinct where the opcode requires it, and the `-1`/[PointId.ALL_POINTS] sentinel
 * may only be used alone and only where a constraint allows it.
 */

@JvmInline
value class PointId(val value: Int) {
    init {
        require(value >= -1) { "point index must be >= -1" }
    }

    companion object {
        val ALL_POINTS = PointId(-1)
    }
}

data class Vec3(val x: Double, val y: Double, val z: Double)

enum class Axis {
    X,
    Y,
    Z,
}

enum class AxisSet {
    X,
    Y,
    Z,
    XY,
    XZ,
    YZ,
    XYZ,
}

enum class CeresOpcode(val code: Int, val metricName: String) {
    Planar(0, "PLANAR"),
    OnLine(1, "ON_LINE"),
    Distance(2, "DISTANCE"),
    SameX(3, "SAME_X"),
    SameY(4, "SAME_Y"),
    SameZ(5, "SAME_Z"),
    SameXY(6, "SAME_XY"),
    SameXZ(7, "SAME_XZ"),
    SameYZ(8, "SAME_YZ"),
    PerpendicularAtOrigin(9, "PERP"),
    LockX(20, "LOCK_X"),
    LockY(21, "LOCK_Y"),
    LockZ(22, "LOCK_Z"),
    LockXY(23, "LOCK_XY"),
    LockXZ(24, "LOCK_XZ"),
    LockYZ(25, "LOCK_YZ"),
    Prior3(30, "PRIOR_3"),
    PriorX(31, "PRIOR_X"),
    PriorY(32, "PRIOR_Y"),
    PriorZ(33, "PRIOR_Z"),
    MinDistance(34, "MIN_DISTANCE"),
    EqualDistance(35, "EQUAL_DISTANCE"),
    MaxDistance(36, "MAX_DISTANCE"),
    NoNegativeZ(37, "NO_Z_NEG"),

    // Reserved by the Kotlin interface for native/JNI implementations that go
    // beyond the current WASM bytecode.
    DotAtOrigin(40, "DOT_AT_ORIGIN"),
    VectorDot(41, "VECTOR_DOT"),
    PointOnPlane(42, "POINT_ON_PLANE"),
    LineParallel(43, "LINE_PARALLEL"),
    LineAngle(44, "LINE_ANGLE"),
    DihedralAngle(45, "DIHEDRAL_ANGLE"),
    EqualEdgeVector(46, "EQUAL_EDGE_VECTOR"),
    MirrorAcrossPlane(47, "MIRROR_ACROSS_PLANE"),
    PanelArea(48, "PANEL_AREA"),
    PointInHalfspace(49, "POINT_IN_HALFSPACE"),
    LineDirection(50, "LINE_DIRECTION"),
    PlaneNormalDirection(51, "PLANE_NORMAL_DIRECTION"),
    LineTowardPoint(52, "LINE_TOWARD_POINT"),

    // ABI v2: solver-directed pseudo-ops and constraints that couple every point.
    SetLoss(60, "SET_LOSS"),
    EdgeTangency(61, "EDGE_TANGENCY"),
    TangencyCentroid(62, "TANGENCY_CENTROID"),
    Centroid(63, "CENTROID"),
    RadiusAboutCentroid(64, "RADIUS_ABOUT_CENTROID"),
    PlanarHesse(65, "PLANAR_HESSE"),
    PlanarFit(66, "PLANAR_FIT"),
    GaugeRotation(67, "GAUGE_ROTATION"),
    Expr(68, "EXPR"),

    // ABI v3: a free variable, declared once at the head of the program and referenced by index.
    Variable(69, "VARIABLE");

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun metricNameFor(code: Int): String = byCode[code]?.metricName ?: "op$code"
    }
}

sealed interface GeometryConstraint {
    val weight: Double
    fun encodeLegacy(out: MutableList<Double>)
}

data class Planar(
    val points: List<PointId>,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requirePointList(points, 3, allowAll = false)
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.Planar)
        out.countedPoints(points)
        out += weight
    }
}

data class Colinear(
    val lineA: PointId,
    val lineB: PointId,
    val points: List<PointId>,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requirePoint(lineA, allowAll = false)
        requirePoint(lineB, allowAll = false)
        require(lineA != lineB) { "line endpoints must be distinct" }
        requirePointList(points, 1, allowAll = false)
        require(points.none { it == lineA || it == lineB }) {
            "colinear dependent points should not include line endpoints"
        }
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.OnLine)
        out += (points.size + 2).toDouble()
        out += lineA.value.toDouble()
        out += lineB.value.toDouble()
        points.forEach { out += it.value.toDouble() }
        out += weight
    }
}

data class DistanceEquals(
    val a: PointId,
    val b: PointId,
    val distance: Double,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requirePair(a, b)
        require(distance >= 0.0) { "distance must be >= 0" }
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.Distance)
        out += distance
        out += a.value.toDouble()
        out += b.value.toDouble()
        out += weight
    }
}

data class DistanceAtLeast(
    val a: PointId,
    val b: PointId,
    val minimum: Double,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requirePair(a, b)
        require(minimum >= 0.0) { "minimum distance must be >= 0" }
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.MinDistance)
        out += minimum
        out += a.value.toDouble()
        out += b.value.toDouble()
        out += weight
    }
}

data class DistanceAtMost(
    val a: PointId,
    val b: PointId,
    val maximum: Double,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requirePair(a, b)
        require(maximum >= 0.0) { "maximum distance must be >= 0" }
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.MaxDistance)
        out += maximum
        out += a.value.toDouble()
        out += b.value.toDouble()
        out += weight
    }
}

data class EqualDistance(
    val a: PointId,
    val b: PointId,
    val c: PointId,
    val d: PointId,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requirePair(a, b)
        requirePair(c, d)
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.EqualDistance)
        out += a.value.toDouble()
        out += b.value.toDouble()
        out += c.value.toDouble()
        out += d.value.toDouble()
        out += weight
    }
}

data class SameCoordinate(
    val points: List<PointId>,
    val axes: AxisSet,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        require(axes != AxisSet.XYZ) { "use WeakPosition or locks for XYZ constraints" }
        requirePointList(points, 2, allowAll = false)
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(
            when (axes) {
                AxisSet.X -> CeresOpcode.SameX
                AxisSet.Y -> CeresOpcode.SameY
                AxisSet.Z -> CeresOpcode.SameZ
                AxisSet.XY -> CeresOpcode.SameXY
                AxisSet.XZ -> CeresOpcode.SameXZ
                AxisSet.YZ -> CeresOpcode.SameYZ
                AxisSet.XYZ -> error("XYZ is not a legacy same-coordinate opcode")
            }
        )
        out.countedPoints(points)
        out += weight
    }
}

data class Perpendicular(
    val origin: PointId,
    val a: PointId,
    val b: PointId,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requirePoint(origin, allowAll = false)
        requirePoint(a, allowAll = false)
        requirePoint(b, allowAll = false)
        require(origin != a && origin != b && a != b) { "perpendicular indices must be distinct" }
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.PerpendicularAtOrigin)
        out += origin.value.toDouble()
        out += a.value.toDouble()
        out += b.value.toDouble()
        out += weight
    }
}

data class LockPosition(
    val points: List<PointId>,
    val axes: AxisSet,
    override val weight: Double = 1e6,
) : GeometryConstraint {
    init {
        require(axes != AxisSet.XYZ) { "the current Ceres bytecode exposes axis and plane locks, not XYZ locks" }
        requirePointList(points, 1, allowAll = false)
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(
            when (axes) {
                AxisSet.X -> CeresOpcode.LockX
                AxisSet.Y -> CeresOpcode.LockY
                AxisSet.Z -> CeresOpcode.LockZ
                AxisSet.XY -> CeresOpcode.LockXY
                AxisSet.XZ -> CeresOpcode.LockXZ
                AxisSet.YZ -> CeresOpcode.LockYZ
                AxisSet.XYZ -> error("XYZ is not a legacy lock opcode")
            }
        )
        out.countedPoints(points)
        out += weight
    }
}

data class WeakPosition(
    val points: List<PointId>,
    val axes: AxisSet = AxisSet.XYZ,
    override val weight: Double = 1e-6,
) : GeometryConstraint {
    init {
        require(axes == AxisSet.X || axes == AxisSet.Y || axes == AxisSet.Z || axes == AxisSet.XYZ) {
            "weak priors currently support X, Y, Z, or XYZ"
        }
        requirePointList(points, 1, allowAll = axes == AxisSet.XYZ)
        requireAllPointSentinelIsAlone(points)
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(
            when (axes) {
                AxisSet.X -> CeresOpcode.PriorX
                AxisSet.Y -> CeresOpcode.PriorY
                AxisSet.Z -> CeresOpcode.PriorZ
                AxisSet.XYZ -> CeresOpcode.Prior3
                else -> error("weak priors support X, Y, Z, or XYZ")
            }
        )
        out.countedPoints(points)
        out += weight
    }
}

data class NoNegativeZ(
    val points: List<PointId>,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requirePointList(points, 1, allowAll = true)
        requireAllPointSentinelIsAlone(points)
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.NoNegativeZ)
        out.countedPoints(points)
        out += weight
    }
}

data class DotAtOrigin(
    val origin: PointId,
    val a: PointId,
    val b: PointId,
    val target: Double = 0.0,
    val normalized: Boolean = true,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requirePoint(origin, allowAll = false)
        requirePoint(a, allowAll = false)
        requirePoint(b, allowAll = false)
        require(origin != a && origin != b && a != b) { "dot-product indices must be distinct" }
        require(target.isFinite()) { "dot target must be finite" }
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        // A normalized dot with a zero target *is* perpendicularity, and the older, narrower
        // opcode says it in fewer operands.
        if (normalized && target == 0.0) {
            Perpendicular(origin, a, b, weight).encodeLegacy(out)
            return
        }
        out.op(CeresOpcode.DotAtOrigin)
        out += origin.value.toDouble()
        out += a.value.toDouble()
        out += b.value.toDouble()
        out += target
        out += if (normalized) 1.0 else 0.0
        out += weight
    }
}

data class VectorDot(
    val a0: PointId,
    val a1: PointId,
    val b0: PointId,
    val b1: PointId,
    val target: Double = 0.0,
    val normalized: Boolean = true,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requirePair(a0, a1)
        requirePair(b0, b1)
        require(target.isFinite()) { "dot target must be finite" }
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        when {
            a0 == b0 -> DotAtOrigin(a0, a1, b1, target, normalized, weight).encodeLegacy(out)
            a0 == b1 -> DotAtOrigin(a0, a1, b0, -target, normalized, weight).encodeLegacy(out)
            a1 == b0 -> DotAtOrigin(a1, a0, b1, -target, normalized, weight).encodeLegacy(out)
            a1 == b1 -> DotAtOrigin(a1, a0, b0, target, normalized, weight).encodeLegacy(out)
            else -> {
                out.op(CeresOpcode.VectorDot)
                out += a0.value.toDouble()
                out += a1.value.toDouble()
                out += b0.value.toDouble()
                out += b1.value.toDouble()
                out += target
                out += if (normalized) 1.0 else 0.0
                out += weight
            }
        }
    }
}

data class PointOnPlane(
    val point: PointId,
    val planeA: PointId,
    val planeB: PointId,
    val planeC: PointId,
    val offset: Double = 0.0,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requireDistinct(point, planeA, planeB, planeC)
        require(offset.isFinite()) { "plane offset must be finite" }
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.PointOnPlane)
        out.points(point, planeA, planeB, planeC)
        out += offset
        out += weight
    }
}

data class LineParallel(
    val a0: PointId,
    val a1: PointId,
    val b0: PointId,
    val b1: PointId,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requireDistinct(a0, a1, b0, b1)
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.LineParallel)
        out.points(a0, a1, b0, b1)
        out += weight
    }
}

data class LineAngle(
    val a0: PointId,
    val a1: PointId,
    val b0: PointId,
    val b1: PointId,
    val targetCos: Double,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requirePair(a0, a1)
        requirePair(b0, b1)
        requireCosine(targetCos, "line angle target")
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        when {
            a0 == b0 -> DotAtOrigin(a0, a1, b1, targetCos, normalized = true, weight).encodeLegacy(out)
            a0 == b1 -> DotAtOrigin(a0, a1, b0, -targetCos, normalized = true, weight).encodeLegacy(out)
            a1 == b0 -> DotAtOrigin(a1, a0, b1, -targetCos, normalized = true, weight).encodeLegacy(out)
            a1 == b1 -> DotAtOrigin(a1, a0, b0, targetCos, normalized = true, weight).encodeLegacy(out)
            else -> {
                out.op(CeresOpcode.LineAngle)
                out.points(a0, a1, b0, b1)
                out += targetCos
                out += weight
            }
        }
    }
}

data class DihedralAngle(
    val edgeA: PointId,
    val edgeB: PointId,
    val sideA: PointId,
    val sideB: PointId,
    val targetCos: Double,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requireDistinct(edgeA, edgeB, sideA, sideB)
        requireCosine(targetCos, "dihedral angle target")
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.DihedralAngle)
        out.points(edgeA, edgeB, sideA, sideB)
        out += targetCos
        out += weight
    }
}

data class EqualEdgeVector(
    val a: PointId,
    val b: PointId,
    val c: PointId,
    val d: PointId,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requireDistinct(a, b, c, d)
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.EqualEdgeVector)
        out.points(a, b, c, d)
        out += weight
    }
}

data class MirrorAcrossPlane(
    val point: PointId,
    val mirror: PointId,
    val planeA: PointId,
    val planeB: PointId,
    val planeC: PointId,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requireDistinct(point, mirror, planeA, planeB, planeC)
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.MirrorAcrossPlane)
        out.points(point, mirror, planeA, planeB, planeC)
        out += weight
    }
}

data class PanelArea(
    val a: PointId,
    val b: PointId,
    val c: PointId,
    val d: PointId,
    val targetArea: Double,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requireDistinct(a, b, c, d)
        require(targetArea.isFinite() && targetArea >= 0.0) { "target area must be finite and non-negative" }
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.PanelArea)
        out.points(a, b, c, d)
        out += targetArea
        out += weight
    }
}

data class PointInHalfspace(
    val point: PointId,
    val planeA: PointId,
    val planeB: PointId,
    val planeC: PointId,
    val minSignedDistance: Double = 0.0,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requireDistinct(point, planeA, planeB, planeC)
        require(minSignedDistance.isFinite()) { "minimum signed distance must be finite" }
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.PointInHalfspace)
        out.points(point, planeA, planeB, planeC)
        out += minSignedDistance
        out += weight
    }
}

data class LineDirection(
    val a: PointId,
    val b: PointId,
    val direction: Vec3,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requirePair(a, b)
        requireVector(direction, "line direction")
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.LineDirection)
        out.points(a, b)
        out.vector(direction)
        out += weight
    }
}

data class PlaneNormalDirection(
    val a: PointId,
    val b: PointId,
    val c: PointId,
    val normal: Vec3,
    val targetCos: Double = 1.0,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requireDistinct(a, b, c)
        requireVector(normal, "plane normal")
        requireCosine(targetCos, "plane normal target")
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.PlaneNormalDirection)
        out.points(a, b, c)
        out.vector(normal)
        out += targetCos
        out += weight
    }
}

data class LineTowardPoint(
    val a: PointId,
    val b: PointId,
    val target: PointId,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requireDistinct(a, b, target)
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.LineTowardPoint)
        out.points(a, b, target)
        out += weight
    }
}

// ==================================================================================================
// ABI v2: midsphere and whole-cloud constraints.
//
// The four below are what a *polyhedron realization* needs and the existing architectural vocabulary
// could not say. Three of them couple every listed point in one residual block, which is a genuine
// departure: the rest of this vocabulary is local. That is the honest shape of the constraint —
// "the mean of these points is here" is not decomposable — and Ceres handles it as one dynamic
// cost function rather than the caller faking it with a pile of pairwise terms.
// ==================================================================================================

/**
 * Distance from the origin to the infinite line through [a] and [b], driven to [radius]: every
 * edge tangent to one sphere about the origin. Local — two points per residual.
 */
data class EdgeTangency(
    val a: PointId,
    val b: PointId,
    val radius: Double,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requirePair(a, b)
        require(radius.isFinite() && radius > 0.0) { "tangency radius must be finite and positive" }
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.EdgeTangency)
        out.points(a, b)
        out += radius
        out += weight
    }
}

/**
 * The mean, over [edges], of each edge's nearest point to the origin, driven to the origin — three
 * residuals over every endpoint at once.
 *
 * Deliberately about the tangency points and not the vertex centroid: the canonical form centres on
 * the former, and mixing the two is the classic way to make the pair of conditions fight.
 */
data class TangencyCentroid(
    val edges: List<Pair<PointId, PointId>>,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        require(edges.isNotEmpty()) { "tangency centroid needs at least one edge" }
        edges.forEach { (a, b) -> requirePair(a, b) }
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.TangencyCentroid)
        out += edges.size.toDouble()
        edges.forEach { (a, b) ->
            out += a.value.toDouble()
            out += b.value.toDouble()
        }
        out += weight
    }
}

/**
 * The mean of [points] driven to [target]; three residuals. Pass [PointId.ALL_POINTS] alone for the
 * whole cloud.
 */
data class Centroid(
    val points: List<PointId>,
    val target: Vec3 = Vec3(0.0, 0.0, 0.0),
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requirePointList(points, 1, allowAll = true)
        requirePosition(target, "centroid target")
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.Centroid)
        if (points.size == 1 && points[0] == PointId.ALL_POINTS) out += -1.0 else out.countedPoints(points)
        out.vector(target)
        out += weight
    }
}

/**
 * Every point in [points] the same distance [radius] from the running mean of that same set — a
 * circumsphere whose centre is not a free variable. One residual per point, all in one block.
 *
 * Right for the Platonic and Archimedean families and exactly wrong for a deliberately irregular
 * object, which is why it is a constraint you opt into. Pass [PointId.ALL_POINTS] alone for the
 * whole cloud.
 */
data class RadiusAboutCentroid(
    val points: List<PointId>,
    val radius: Double,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requirePointList(points, 1, allowAll = true)
        require(radius.isFinite() && radius > 0.0) { "radius must be finite and positive" }
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.RadiusAboutCentroid)
        if (points.size == 1 && points[0] == PointId.ALL_POINTS) out += -1.0 else out.countedPoints(points)
        out += radius
        out += weight
    }
}

/**
 * A literal opcode stream, spliced into the program as-is.
 *
 * The escape hatch for an opcode the native wrapper understands but this Kotlin layer does not model
 * yet — the two sides ship separately, and without this a new native opcode is unusable until the
 * Kotlin catches up. It is also how the malformed-program path gets tested.
 *
 * Nothing validates the contents. Get the operand count wrong and the rest of the program is
 * garbage — but the wrapper reports that rather than silently truncating the stream, which is how
 * the native validator's own refusals get exercised from Kotlin.
 */
data class RawProgramFragment(
    val values: List<Double>,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        require(values.isNotEmpty()) { "a raw fragment needs at least an opcode" }
        require(values.all { it.isFinite() }) { "raw fragment values must be finite" }
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out += values
    }
}

/**
 * Face planarity with the plane in Hesse normal form: `{ x : x . n == d }`, `n` on a sphere manifold
 * and `d` a scalar.
 *
 * Prefer this to [Planar]. [Planar]'s residual is `(P - O) . n`, which depends on the plane origin
 * `O` only through `O . n` — so the two components of `O` in the plane are an *exact* null
 * direction, two per face, restrained by nothing but a `1e-6` prior. On a solid with more faces
 * than vertices that null space outnumbers the real parameters and a trust-region minimiser spends
 * its whole budget crawling along it. `(n, d)` is three effective parameters per face with no null
 * direction, and needs no stabilisers.
 */
data class PlanarHesse(
    val points: List<PointId>,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requirePointList(points, 3, allowAll = false)
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.PlanarHesse)
        out.countedPoints(points)
        out += weight
    }
}

/**
 * Face planarity against the face's own Newell best-fit plane, computed inside the residual — no
 * auxiliary plane variables at all.
 *
 * Each corner's residual depends on every corner of that face, which makes the Jacobian denser
 * per row than [PlanarHesse]'s, but the problem carries only the points. This is exactly what
 * [afield.stdlib.polytope.FacePlanarityConstraint] measures, so it is the choice that makes a Ceres
 * solve and a Kotlin solve the *same* objective rather than two equivalent ones.
 *
 * A face naming the same vertex twice is skipped: the fit would need one parameter block twice,
 * which Ceres forbids, and such a face is degenerate anyway.
 */
data class PlanarFit(
    val points: List<PointId>,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requirePointList(points, 3, allowAll = false)
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.PlanarFit)
        out.countedPoints(points)
        out += weight
    }
}

/**
 * Remove the infinitesimal-rotation null space: three residuals holding `sum(reference_i x p_i)` at
 * zero, where the reference pose is the one the problem was compiled with.
 *
 * A set of constraints that pins translation and scale but says nothing about orientation has a
 * three-dimensional continuum of equally good answers, and a minimiser will wander along it —
 * spending iterations, and making the answer depend on the path taken to it. Removing the freedom
 * is cheaper than damping it. Pass [PointId.ALL_POINTS] alone for the whole cloud.
 */
data class GaugeRotation(
    val points: List<PointId>,
    override val weight: Double = 1.0,
) : GeometryConstraint {
    init {
        requirePointList(points, 1, allowAll = true)
        requireWeight(weight)
    }

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.GaugeRotation)
        if (points.size == 1 && points[0] == PointId.ALL_POINTS) out += -1.0 else out.countedPoints(points)
        out += weight
    }
}

class CeresProgramBuilder {
    private val constraints = mutableListOf<GeometryConstraint>()
    private val variables = mutableListOf<CeresVariableDecl>()

    fun add(constraint: GeometryConstraint): CeresProgramBuilder = apply {
        constraints += constraint
    }

    /**
     * Declare a free variable: an unknown Ceres solves for that is not a point.
     *
     * Program-scoped, so the returned [CeresVariableRef] can be named by as many [expression]s as you
     * like and they will all see the same block. That is the difference between "every face has its
     * own plane" and "these fifty observations all see one camera".
     *
     * ```kotlin
     * val normal = variable(CeresVariableDecl.unitVector("n", seed))   // on a sphere manifold
     * val offset = variable(CeresVariableDecl.scalar("d", seed dot n))
     * expression(face, listOf(normal, offset)) { corners, (n, d) ->
     *     corners.map { (it dot n.vec()) - d.scalar }               // PLANAR_HESSE, in Kotlin
     * }
     * ```
     */
    fun variable(declaration: CeresVariableDecl): CeresVariableRef {
        variables += declaration
        return CeresVariableRef(variables.size - 1, declaration)
    }

    /** A free scalar. */
    fun variable(name: String, initial: Double): CeresVariableRef =
        variable(CeresVariableDecl.scalar(name, initial))

    /** A free position or offset — three components, no manifold. */
    fun variable(name: String, initial: Vec3): CeresVariableRef =
        variable(CeresVariableDecl.vector(name, initial))

    /** A free direction, held on the unit sphere. */
    fun unitVariable(name: String, initial: Vec3): CeresVariableRef =
        variable(CeresVariableDecl.unitVector(name, initial))

    /** A free rotation, held on the quaternion manifold. Starts at the identity. */
    fun rotationVariable(name: String): CeresVariableRef =
        variable(CeresVariableDecl.rotation(name))

    fun planar(vararg points: Int, weight: Double = 1.0) = add(Planar(points.ids(), weight))

    fun colinear(lineA: Int, lineB: Int, vararg points: Int, weight: Double = 1.0) =
        add(Colinear(lineA.id(), lineB.id(), points.ids(), weight))

    fun colinear(points: List<Int>, weight: Double = 1.0): CeresProgramBuilder {
        require(points.size >= 3) { "colinear(points) needs at least three points" }
        return add(Colinear(points[0].id(), points[1].id(), points.drop(2).ids(), weight))
    }

    fun distance(a: Int, b: Int, distance: Double, weight: Double = 1.0) =
        add(DistanceEquals(a.id(), b.id(), distance, weight))

    fun minDistance(a: Int, b: Int, minimum: Double, weight: Double = 1.0) =
        add(DistanceAtLeast(a.id(), b.id(), minimum, weight))

    fun maxDistance(a: Int, b: Int, maximum: Double, weight: Double = 1.0) =
        add(DistanceAtMost(a.id(), b.id(), maximum, weight))

    fun equalDistance(a: Int, b: Int, c: Int, d: Int, weight: Double = 1.0) =
        add(EqualDistance(a.id(), b.id(), c.id(), d.id(), weight))

    fun same(points: List<Int>, axes: AxisSet, weight: Double = 1.0) =
        add(SameCoordinate(points.ids(), axes, weight))

    fun perpendicular(origin: Int, a: Int, b: Int, weight: Double = 1.0) =
        add(Perpendicular(origin.id(), a.id(), b.id(), weight))

    fun dotAtOrigin(
        origin: Int,
        a: Int,
        b: Int,
        target: Double = 0.0,
        normalized: Boolean = true,
        weight: Double = 1.0,
    ) = add(DotAtOrigin(origin.id(), a.id(), b.id(), target, normalized, weight))

    fun vectorDot(
        a0: Int,
        a1: Int,
        b0: Int,
        b1: Int,
        target: Double = 0.0,
        normalized: Boolean = true,
        weight: Double = 1.0,
    ) = add(VectorDot(a0.id(), a1.id(), b0.id(), b1.id(), target, normalized, weight))

    fun pointOnPlane(point: Int, planeA: Int, planeB: Int, planeC: Int, offset: Double = 0.0, weight: Double = 1.0) =
        add(PointOnPlane(point.id(), planeA.id(), planeB.id(), planeC.id(), offset, weight))

    fun lineParallel(a0: Int, a1: Int, b0: Int, b1: Int, weight: Double = 1.0) =
        add(LineParallel(a0.id(), a1.id(), b0.id(), b1.id(), weight))

    fun lineAngle(a0: Int, a1: Int, b0: Int, b1: Int, targetCos: Double, weight: Double = 1.0) =
        add(LineAngle(a0.id(), a1.id(), b0.id(), b1.id(), targetCos, weight))

    fun dihedralAngle(edgeA: Int, edgeB: Int, sideA: Int, sideB: Int, targetCos: Double, weight: Double = 1.0) =
        add(DihedralAngle(edgeA.id(), edgeB.id(), sideA.id(), sideB.id(), targetCos, weight))

    fun equalEdgeVector(a: Int, b: Int, c: Int, d: Int, weight: Double = 1.0) =
        add(EqualEdgeVector(a.id(), b.id(), c.id(), d.id(), weight))

    fun mirrorAcrossPlane(point: Int, mirror: Int, planeA: Int, planeB: Int, planeC: Int, weight: Double = 1.0) =
        add(MirrorAcrossPlane(point.id(), mirror.id(), planeA.id(), planeB.id(), planeC.id(), weight))

    fun panelArea(a: Int, b: Int, c: Int, d: Int, targetArea: Double, weight: Double = 1.0) =
        add(PanelArea(a.id(), b.id(), c.id(), d.id(), targetArea, weight))

    fun pointInHalfspace(
        point: Int,
        planeA: Int,
        planeB: Int,
        planeC: Int,
        minSignedDistance: Double = 0.0,
        weight: Double = 1.0,
    ) = add(PointInHalfspace(point.id(), planeA.id(), planeB.id(), planeC.id(), minSignedDistance, weight))

    fun lineDirection(a: Int, b: Int, direction: Vec3, weight: Double = 1.0) =
        add(LineDirection(a.id(), b.id(), direction, weight))

    fun planeNormalDirection(
        a: Int,
        b: Int,
        c: Int,
        normal: Vec3,
        targetCos: Double = 1.0,
        weight: Double = 1.0,
    ) = add(PlaneNormalDirection(a.id(), b.id(), c.id(), normal, targetCos, weight))

    fun lineTowardPoint(a: Int, b: Int, target: Int, weight: Double = 1.0) =
        add(LineTowardPoint(a.id(), b.id(), target.id(), weight))

    fun lock(points: List<Int>, axes: AxisSet, weight: Double = 1e6) =
        add(LockPosition(points.ids(), axes, weight))

    fun weak(points: List<Int>, axes: AxisSet = AxisSet.XYZ, weight: Double = 1e-6) =
        add(WeakPosition(points.ids(), axes, weight))

    fun weakAll(weight: Double = 1e-6) =
        add(WeakPosition(listOf(PointId.ALL_POINTS), AxisSet.XYZ, weight))

    fun noNegativeZ(points: List<Int>, weight: Double = 1.0) =
        add(NoNegativeZ(points.ids(), weight))

    fun noNegativeZAll(weight: Double = 1.0) =
        add(NoNegativeZ(listOf(PointId.ALL_POINTS), weight))

    fun edgeTangency(a: Int, b: Int, radius: Double, weight: Double = 1.0) =
        add(EdgeTangency(a.id(), b.id(), radius, weight))

    fun tangencyCentroid(edges: List<Pair<Int, Int>>, weight: Double = 1.0) =
        add(TangencyCentroid(edges.map { (a, b) -> a.id() to b.id() }, weight))

    fun centroid(points: List<Int>, target: Vec3 = Vec3(0.0, 0.0, 0.0), weight: Double = 1.0) =
        add(Centroid(points.ids(), target, weight))

    fun centroidAll(target: Vec3 = Vec3(0.0, 0.0, 0.0), weight: Double = 1.0) =
        add(Centroid(listOf(PointId.ALL_POINTS), target, weight))

    fun radiusAboutCentroid(points: List<Int>, radius: Double, weight: Double = 1.0) =
        add(RadiusAboutCentroid(points.ids(), radius, weight))

    fun radiusAboutCentroidAll(radius: Double, weight: Double = 1.0) =
        add(RadiusAboutCentroid(listOf(PointId.ALL_POINTS), radius, weight))

    fun planarHesse(points: List<Int>, weight: Double = 1.0) = add(PlanarHesse(points.ids(), weight))

    fun planarFit(points: List<Int>, weight: Double = 1.0) = add(PlanarFit(points.ids(), weight))

    fun gaugeRotationAll(weight: Double = 1.0) = add(GaugeRotation(listOf(PointId.ALL_POINTS), weight))

    /**
     * A residual you write here, in Kotlin, differentiated exactly by Ceres — no native rebuild.
     *
     * [build] receives one [CeresExprVec] per point in [points], in order, and returns one
     * expression per residual. See [CeresExpr] for the vocabulary and its caveats.
     *
     * ```kotlin
     * // a spring between two points, at rest length 2.5
     * expression(a, b) { (pa, pb) -> listOf(pa.distanceTo(pb) - 2.5.c) }
     *
     * // a one-sided floor on the same distance, contributing nothing above it
     * expression(a, b) { (pa, pb) -> listOf(barrierBelow(pa.distanceTo(pb), 0.1.c)) }
     * ```
     */
    fun expression(
        points: List<Int>,
        weight: Double = 1.0,
        build: (List<CeresExprVec>) -> List<CeresExpr>,
    ): CeresProgramBuilder = add(
        CeresExpressionResidual(points.ids(), build(boundPoints(points.size)), weight),
    )

    /** As [expression], for the common case of naming the points inline. */
    fun expression(
        vararg points: Int,
        weight: Double = 1.0,
        build: (List<CeresExprVec>) -> List<CeresExpr>,
    ): CeresProgramBuilder = expression(points.toList(), weight, build)

    /**
     * A residual over [points] *and* [variables] — unknowns Ceres solves for that are not points.
     *
     * [build] receives one [CeresExprVec] per point and one [CeresExprVariable] per variable, both in
     * the order given, and returns one expression per residual. Declare the variables first with
     * [variable]; several expressions may name the same one.
     *
     * ```kotlin
     * // fit a sphere to every point: free centre, free radius
     * val centre = variable("centre", Vec3(0.0, 0.0, 0.0))
     * val radius = variable("radius", 1.0)
     * indices.forEach { i ->
     *     expression(listOf(i), listOf(centre, radius)) { (p), (c, r) ->
     *         listOf(p.distanceTo(c.vec()) - r.scalar)
     *     }
     * }
     * ```
     */
    fun expression(
        points: List<Int>,
        variables: List<CeresVariableRef>,
        weight: Double = 1.0,
        build: (List<CeresExprVec>, List<CeresExprVariable>) -> List<CeresExpr>,
    ): CeresProgramBuilder {
        variables.forEachIndexed { at, ref ->
            require(ref.index < this.variables.size && this.variables[ref.index] == ref.declaration) {
                "variable '${ref.name}' (position $at) was not declared by this program builder"
            }
        }
        val boundVariables = variables.mapIndexed { block, ref -> CeresExprVariable(block, ref.declaration) }
        return add(
            CeresExpressionResidual(
                points.ids(),
                build(boundPoints(points.size), boundVariables),
                weight,
                variables,
            ),
        )
    }

    private fun boundPoints(count: Int): List<CeresExprVec> = (0 until count).map { block ->
        CeresExprVec(ExprParam(block, 0), ExprParam(block, 1), ExprParam(block, 2))
    }

    fun raw(vararg values: Double) = add(RawProgramFragment(values.toList()))

    fun build(): CeresProgram = CeresProgram(constraints.toList(), variables = variables.toList())

    fun build(loss: RobustLoss): CeresProgram =
        CeresProgram(constraints.toList(), loss, variables.toList())
}

/**
 * The loss the native solver wraps every residual in.
 *
 * The wrapper used to hardcode `Huber(1e-3)`. That is a good default for interactive constraint
 * editing — one badly-placed point should not drag the whole model — and the wrong one for a
 * least-squares problem whose residuals are *meant* to reach zero together, because a robust loss
 * caps the influence of the largest residual and so quietly changes the objective you wrote down.
 * [HUBER] therefore remains the default for compatibility, and [NONE] is what a realization solve
 * wants.
 */
data class RobustLoss(val kind: Kind = Kind.HUBER, val scale: Double = 1e-3) {
    enum class Kind(val code: Int) {
        NONE(0),
        HUBER(1),
        SOFT_L_ONE(2),
        CAUCHY(3),
    }

    init {
        require(scale.isFinite() && scale > 0.0) { "loss scale must be finite and positive" }
    }

    companion object {
        /** Plain least squares: no loss at all. */
        val NONE = RobustLoss(Kind.NONE)

        /** The wrapper's historic default. */
        val DEFAULT = RobustLoss(Kind.HUBER, 1e-3)
    }
}

data class CeresProgram(
    val constraints: List<GeometryConstraint>,
    val loss: RobustLoss = RobustLoss.DEFAULT,
    /**
     * Free variables, in declaration order — the order the native side registers them and the order
     * [SolveResult.variables] reports their solved values in.
     */
    val variables: List<CeresVariableDecl> = emptyList(),
) {
    /** Every free-variable component, summed — the size of the array a solve reports back. */
    val variableComponentCount: Int = variables.sumOf { it.dimension }

    init {
        // An expression can only reference a variable by index, so every index a constraint names has
        // to exist. `CeresProgramBuilder` cannot get this wrong; a hand-assembled program can.
        constraints.filterIsInstance<CeresExpressionResidual>().forEach { residual ->
            residual.variables.forEach { ref ->
                require(ref.index < variables.size && variables[ref.index] == ref.declaration) {
                    "expression names variable '${ref.name}' at index ${ref.index}, which this " +
                        "program does not declare"
                }
            }
        }
        val referenced = constraints.filterIsInstance<CeresExpressionResidual>()
            .flatMapTo(mutableSetOf()) { residual -> residual.variables.map { it.index } }
        variables.indices.forEach { index ->
            require(index in referenced) {
                "free variable '${variables[index].name}' is declared but no expression reads it; " +
                    "Ceres would be handed a parameter block nothing can move"
            }
        }
    }

    fun encodeLegacyBytecode(): DoubleArray {
        val out = mutableListOf<Double>()
        if (loss != RobustLoss.DEFAULT) {
            out.op(CeresOpcode.SetLoss)
            out += loss.kind.code.toDouble()
            out += loss.scale
        }
        // Declarations come first, and in order: an EXPR references them by index, and both sides
        // count them in the order they appear.
        variables.forEach { declaration ->
            out.op(CeresOpcode.Variable)
            out += declaration.dimension.toDouble()
            out += declaration.manifold.code.toDouble()
            declaration.initial.forEach { out += it }
            val bounded = declaration.bounds.keys.sorted()
            out += bounded.size.toDouble()
            bounded.forEach { component ->
                val (lower, upper) = requireNotNull(declaration.boundOf(component))
                out += component.toDouble()
                out += lower
                out += upper
            }
        }
        constraints.forEach { it.encodeLegacy(out) }
        return out.toDoubleArray()
    }

    fun legacyHash(): UInt = fnv1a64BitDoubles(encodeLegacyBytecode())
}

data class PointCloud(val values: DoubleArray) {
    init {
        require(values.size % 3 == 0) { "point cloud must be a flat xyz array" }
        require(values.all { it.isFinite() }) { "point coordinates must be finite" }
    }

    val pointCount: Int = values.size / 3

    operator fun get(index: Int): Vec3 {
        require(index in 0 until pointCount) { "point index out of range" }
        val i = index * 3
        return Vec3(values[i], values[i + 1], values[i + 2])
    }

    fun withPoint(index: Int, value: Vec3): PointCloud {
        require(index in 0 until pointCount) { "point index out of range" }
        val next = values.copyOf()
        val i = index * 3
        next[i] = value.x
        next[i + 1] = value.y
        next[i + 2] = value.z
        return PointCloud(next)
    }

    fun copyValues(): DoubleArray = values.copyOf()

    companion object {
        fun of(vararg points: Vec3): PointCloud {
            val values = DoubleArray(points.size * 3)
            points.forEachIndexed { index, point ->
                values[index * 3] = point.x
                values[index * 3 + 1] = point.y
                values[index * 3 + 2] = point.z
            }
            return PointCloud(values)
        }
    }
}

data class DynamicPriorWeights(val values: DoubleArray) {
    init {
        require(values.size % 3 == 0) { "prior weights must be a flat xyz array" }
        require(values.all { it.isFinite() && it >= 0.0 }) { "prior weights must be finite and non-negative" }
    }

    val pointCount: Int = values.size / 3

    fun copyValues(): DoubleArray = values.copyOf()

    companion object {
        fun uniform(pointCount: Int, weight: Double): DynamicPriorWeights {
            require(pointCount >= 0) { "point count must be non-negative" }
            requireWeight(weight)
            return DynamicPriorWeights(DoubleArray(pointCount * 3) { weight })
        }

        fun dragging(
            pointCount: Int,
            draggedPoint: Int,
            baseline: Double = 1e-6,
            dragged: Double = 1e3,
        ): DynamicPriorWeights {
            val values = uniform(pointCount, baseline).values
            require(draggedPoint in 0 until pointCount) { "dragged point out of range" }
            requireWeight(dragged)
            values[draggedPoint * 3] = dragged
            values[draggedPoint * 3 + 1] = dragged
            values[draggedPoint * 3 + 2] = dragged
            return DynamicPriorWeights(values)
        }
    }
}

/**
 * Which linear solver Ceres uses inside each trust-region step.
 *
 * This is the single biggest lever on a large problem and it used to be hardcoded to [DENSE_QR].
 * Point-cloud constraint problems are structurally sparse — a per-edge or per-face residual touches
 * two or a handful of the `3N` parameters — so [SPARSE_NORMAL_CHOLESKY] is usually the right answer
 * above a hundred or so points, while [DENSE_QR] stays the most numerically forgiving for small
 * ones.
 *
 * The sparse options need a Ceres built with a sparse backend. The committed `libs/` artifact is
 * not; one built by `./gradlew buildCeresNative` against a system Ceres is. Ceres falls back to a
 * dense solver and says so in [CeresSolveSummary.linearSolverUsed] rather than failing, so check
 * that field if a sparse choice does not speed things up.
 */
enum class CeresLinearSolver(val code: Int) {
    DENSE_QR(0),
    DENSE_NORMAL_CHOLESKY(1),
    SPARSE_NORMAL_CHOLESKY(2),
    CGNR(3),
    DENSE_SCHUR(4),
    ITERATIVE_SCHUR(5),
}

/** Why the minimizer stopped. Mirrors `ceres::TerminationType`. */
enum class CeresTermination {
    CONVERGENCE,
    NO_CONVERGENCE,
    FAILURE,
    USER_SUCCESS,
    USER_FAILURE,
    UNKNOWN,
    ;

    companion object {
        fun ofCode(code: Int): CeresTermination = entries.getOrElse(code) { UNKNOWN }
    }
}

/** What the minimizer actually did. Null when [SolveOptions.summary] is false. */
data class CeresSolveSummary(
    val initialCost: Double,
    val finalCost: Double,
    val successfulSteps: Int,
    val unsuccessfulSteps: Int,
    val iterations: Int,
    val termination: CeresTermination,
    val seconds: Double,
    val residualCount: Int,
    val effectiveParameterCount: Int,
    val parameterBlockCount: Int,
    val residualBlockCount: Int,
    /**
     * What Ceres used — not necessarily what you asked for, if the build lacks that backend. Null
     * when Ceres picked something this ABI has no name for.
     */
    val linearSolverUsed: CeresLinearSolver?,
) {
    val converged: Boolean get() = termination == CeresTermination.CONVERGENCE

    override fun toString(): String = buildString {
        append(termination.name.lowercase())
        append(" in ").append(iterations).append(" iter")
        append(" (").append(successfulSteps).append(" ok, ").append(unsuccessfulSteps).append(" rejected)")
        append(", cost ").append("%.4g".format(initialCost)).append(" -> ").append("%.4g".format(finalCost))
        append(", ").append("%.1f".format(seconds * 1000)).append(" ms")
        linearSolverUsed?.let { append(", ").append(it.name) }
    }

    companion object {
        internal const val SLOTS = 12

        internal fun decode(slots: DoubleArray): CeresSolveSummary {
            fun at(i: Int) = if (i < slots.size) slots[i] else 0.0
            val solverCode = at(11).toInt()
            return CeresSolveSummary(
                initialCost = at(0),
                finalCost = at(1),
                successfulSteps = at(2).toInt(),
                unsuccessfulSteps = at(3).toInt(),
                iterations = at(4).toInt(),
                termination = CeresTermination.ofCode(at(5).toInt()),
                seconds = at(6),
                residualCount = at(7).toInt(),
                effectiveParameterCount = at(8).toInt(),
                parameterBlockCount = at(9).toInt(),
                residualBlockCount = at(10).toInt(),
                linearSolverUsed = CeresLinearSolver.entries.firstOrNull { it.code == solverCode },
            )
        }
    }
}

data class SolveOptions(
    val maxIterations: Int = 8,
    val metrics: Boolean = false,
    val metricGroupCapacity: Int = 64,
    /**
     * Ceres falls back to a dense solve, silently, when the loaded wrapper was built without the
     * sparse backend an option asks for — [CeresSolveSummary.linearSolverUsed] is what tells you.
     */
    val linearSolver: CeresLinearSolver = CeresLinearSolver.DENSE_QR,
    /** Ceres parallelises residual and Jacobian evaluation. */
    val threads: Int = 1,
    val functionTolerance: Double = 1e-6,
    val gradientTolerance: Double = 1e-10,
    val parameterTolerance: Double = 1e-8,
    /** Let the trust region accept a worse step occasionally; helps on narrow valleys. */
    val nonmonotonicSteps: Boolean = false,
    /** Fill [SolveResult.summary]. */
    val summary: Boolean = false,
    /** Ceres' own per-iteration table, on stdout. Debugging only. */
    val progressToStdout: Boolean = false,
) {
    init {
        require(maxIterations >= 1) { "maxIterations must be >= 1" }
        require(metricGroupCapacity >= 0) { "metricGroupCapacity must be >= 0" }
        require(threads >= 1) { "threads must be >= 1" }
        require(functionTolerance > 0.0 && functionTolerance.isFinite()) { "functionTolerance must be positive" }
        require(gradientTolerance > 0.0 && gradientTolerance.isFinite()) { "gradientTolerance must be positive" }
        require(parameterTolerance > 0.0 && parameterTolerance.isFinite()) { "parameterTolerance must be positive" }
    }

    /**
     * True when this request needs the configurable solve entry point rather than the
     * fixed-configuration one — which is what [ReusableCompiledCeresSolver.solveInPlace] refuses.
     */
    internal val needsConfigurableSolve: Boolean
        get() = linearSolver != CeresLinearSolver.DENSE_QR ||
            threads != 1 ||
            summary ||
            progressToStdout ||
            functionTolerance != 1e-6 ||
            gradientTolerance != 1e-10 ||
            parameterTolerance != 1e-8 ||
            nonmonotonicSteps

    internal fun encode(): DoubleArray = doubleArrayOf(
        maxIterations.toDouble(),
        linearSolver.code.toDouble(),
        threads.toDouble(),
        functionTolerance,
        gradientTolerance,
        parameterTolerance,
        if (nonmonotonicSteps) 1.0 else 0.0,
        if (progressToStdout) 1.0 else 0.0,
    )
}

data class SolveRequest(
    val points: PointCloud,
    val program: CeresProgram,
    val dynamicPriorWeights: DynamicPriorWeights? = null,
    val options: SolveOptions = SolveOptions(),
) {
    init {
        dynamicPriorWeights?.let {
            require(it.pointCount == points.pointCount) { "dynamic prior point count must match points" }
        }
    }

    val legacyBytecode: DoubleArray by lazy { program.encodeLegacyBytecode() }
    val legacyHash: UInt by lazy { fnv1a64BitDoubles(legacyBytecode) }
}

data class SolveMetrics(
    val totalCost: Double?,
    val byOpcode: Map<CeresOpcode, Double>,
    val rawByOpcode: Map<Int, Double>,
) {
    fun top(limit: Int = 5): List<Pair<String, Double>> =
        rawByOpcode.entries
            .filter { it.value != 0.0 && !it.value.isNaN() }
            .sortedByDescending { it.value }
            .take(max(0, limit))
            .map { CeresOpcode.metricNameFor(it.key) to it.value }
}

/**
 * What a free variable was solved to.
 *
 * The values live in the compiled solver, not in the [PointCloud], so they have to be read back
 * separately — see [CeresVariableValues].
 */
data class CeresVariableValue(
    val name: String,
    val manifold: CeresManifold,
    val values: DoubleArray,
) {
    val scalar: Double get() = values.single()

    /** The first three components, when the variable is a direction or a position. */
    fun vec3(): Vec3 {
        require(values.size >= 3) { "variable '$name' has ${values.size} component(s), not three" }
        return Vec3(values[0], values[1], values[2])
    }

    override fun toString(): String =
        "$name${if (manifold == CeresManifold.NONE) "" else "/${manifold.name.lowercase()}"}" +
            values.joinToString(", ", prefix = " [", postfix = "]") { "%.9g".format(it) }

    override fun equals(other: Any?): Boolean =
        other is CeresVariableValue && name == other.name && manifold == other.manifold &&
            values.contentEquals(other.values)

    override fun hashCode(): Int = (name.hashCode() * 31 + manifold.hashCode()) * 31 + values.contentHashCode()
}

/**
 * The solved values of a program's free variables.
 *
 * [blocks] is empty when the flat array the native side reported cannot be cut up according to the
 * program's own declarations — which happens only if a [RawProgramFragment] smuggled in a declaration
 * this layer cannot see. [flat] is always exactly what the solver holds, so nothing is hidden.
 */
data class CeresVariableValues(
    val flat: DoubleArray,
    val blocks: List<CeresVariableValue>,
) {
    val size: Int get() = blocks.size

    operator fun get(index: Int): CeresVariableValue = blocks[index]

    /** Every block with this name, in declaration order — names need not be unique. */
    fun all(name: String): List<CeresVariableValue> = blocks.filter { it.name == name }

    /** The first block with this name. */
    fun first(name: String): CeresVariableValue =
        blocks.firstOrNull { it.name == name }
            ?: error("no free variable named '$name'; this program declares ${blocks.map { it.name }}")

    fun scalar(name: String): Double = first(name).scalar

    fun vec3(name: String): Vec3 = first(name).vec3()

    override fun equals(other: Any?): Boolean =
        other is CeresVariableValues && flat.contentEquals(other.flat) && blocks == other.blocks

    override fun hashCode(): Int = flat.contentHashCode() * 31 + blocks.hashCode()

    companion object {
        val EMPTY = CeresVariableValues(DoubleArray(0), emptyList())

        /** Cut a flat native readback into the blocks [program] declared. */
        internal fun of(program: CeresProgram, flat: DoubleArray): CeresVariableValues {
            if (flat.size != program.variableComponentCount) {
                return CeresVariableValues(flat, emptyList())
            }
            var at = 0
            val blocks = program.variables.map { declaration ->
                val values = flat.copyOfRange(at, at + declaration.dimension)
                at += declaration.dimension
                CeresVariableValue(declaration.name, declaration.manifold, values)
            }
            return CeresVariableValues(flat, blocks)
        }
    }
}

data class SolveResult(
    val ok: Boolean,
    val points: PointCloud,
    val metrics: SolveMetrics? = null,
    val summary: CeresSolveSummary? = null,
    /** The solved free variables, in declaration order. Empty when the program declared none. */
    val variables: CeresVariableValues = CeresVariableValues.EMPTY,
)

interface CompiledCeresSolver : AutoCloseable {
    val pointCount: Int
    val programHash: UInt

    fun solve(
        points: PointCloud,
        dynamicPriorWeights: DynamicPriorWeights? = null,
        options: SolveOptions = SolveOptions(),
    ): SolveResult

    override fun close() = Unit
}

interface CeresSolverBackend {
    fun compile(seedPoints: PointCloud, program: CeresProgram): CompiledCeresSolver
}

class CachedCeresSolvers(
    private val backend: CeresSolverBackend,
    private val maxEntries: Int = 32,
) : AutoCloseable {
    private val cache = object : LinkedHashMap<Key, CompiledCeresSolver>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, CompiledCeresSolver>): Boolean {
            val evict = size > maxEntries
            if (evict) eldest.value.close()
            return evict
        }
    }

    fun solve(request: SolveRequest): SolveResult {
        val solver = getOrCompile(request.points, request.program)
        return solver.solve(request.points, request.dynamicPriorWeights, request.options)
    }

    fun getOrCompile(seedPoints: PointCloud, program: CeresProgram): CompiledCeresSolver {
        val pointCount = seedPoints.pointCount
        require(pointCount >= 0) { "point count must be non-negative" }
        val key = Key(pointCount, program.legacyHash())
        return cache.getOrPut(key) { backend.compile(seedPoints, program) }
    }

    override fun close() {
        cache.values.forEach { it.close() }
        cache.clear()
    }

    private data class Key(val pointCount: Int, val hash: UInt)
}

fun buildCeresProgram(block: CeresProgramBuilder.() -> Unit): CeresProgram =
    CeresProgramBuilder().apply(block).build()

/** As [buildCeresProgram], choosing the loss the native solver wraps every residual in. */
fun buildCeresProgram(loss: RobustLoss, block: CeresProgramBuilder.() -> Unit): CeresProgram =
    CeresProgramBuilder().apply(block).build(loss)

fun metricMap(groupCosts: DoubleArray): SolveMetrics =
    SolveMetrics(
        totalCost = null,
        byOpcode = groupCosts
            .withIndex()
            .mapNotNull { (index, cost) ->
                CeresOpcode.entries.firstOrNull { it.code == index }?.let { opcode -> opcode to cost }
            }
            .toMap(),
        rawByOpcode = groupCosts.withIndex().associate { (index, cost) -> index to cost },
    )

fun fnv1a64BitDoubles(values: DoubleArray): UInt {
    val bytes = ByteBuffer
        .allocate(values.size * Double.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
    values.forEach { bytes.putDouble(it) }

    var hash = 2166136261u
    val data = bytes.array()
    for (byte in data) {
        hash = hash xor byte.toUByte().toUInt()
        hash *= 16777619u
    }
    hash = hash xor values.size.toUInt()
    return hash
}

private fun Int.id(): PointId = PointId(this)

private fun IntArray.ids(): List<PointId> = map { it.id() }

private fun List<Int>.ids(): List<PointId> = map { it.id() }

internal fun MutableList<Double>.op(opcode: CeresOpcode) {
    this += opcode.code.toDouble()
}

private fun MutableList<Double>.countedPoints(points: List<PointId>) {
    this += points.size.toDouble()
    points.forEach { this += it.value.toDouble() }
}

private fun MutableList<Double>.points(a: PointId, b: PointId) {
    this += a.value.toDouble()
    this += b.value.toDouble()
}

private fun MutableList<Double>.points(a: PointId, b: PointId, c: PointId) {
    points(a, b)
    this += c.value.toDouble()
}

private fun MutableList<Double>.points(a: PointId, b: PointId, c: PointId, d: PointId) {
    points(a, b, c)
    this += d.value.toDouble()
}

private fun MutableList<Double>.points(a: PointId, b: PointId, c: PointId, d: PointId, e: PointId) {
    points(a, b, c, d)
    this += e.value.toDouble()
}

private fun MutableList<Double>.vector(vector: Vec3) {
    this += vector.x
    this += vector.y
    this += vector.z
}

private fun requirePoint(point: PointId, allowAll: Boolean) {
    require(allowAll || point != PointId.ALL_POINTS) { "-1/all-points is not valid here" }
}

private fun requirePair(a: PointId, b: PointId) {
    requirePoint(a, allowAll = false)
    requirePoint(b, allowAll = false)
    require(a != b) { "point pair endpoints must be distinct" }
}

internal fun requirePointList(points: List<PointId>, min: Int, allowAll: Boolean) {
    require(points.size >= min) { "need at least $min point(s)" }
    points.forEach { requirePoint(it, allowAll) }
}

private fun requireDistinct(points: List<PointId>) {
    points.forEach { requirePoint(it, allowAll = false) }
    require(points.toSet().size == points.size) { "point indices must be distinct" }
}

private fun requireDistinct(a: PointId, b: PointId) = requireDistinct(listOf(a, b))

private fun requireDistinct(a: PointId, b: PointId, c: PointId) = requireDistinct(listOf(a, b, c))

private fun requireDistinct(a: PointId, b: PointId, c: PointId, d: PointId) = requireDistinct(listOf(a, b, c, d))

private fun requireDistinct(a: PointId, b: PointId, c: PointId, d: PointId, e: PointId) =
    requireDistinct(listOf(a, b, c, d, e))

private fun requireAllPointSentinelIsAlone(points: List<PointId>) {
    require(points.size == 1 || PointId.ALL_POINTS !in points) { "use -1/all-points alone, not mixed" }
}

internal fun requireWeight(weight: Double) {
    require(weight.isFinite() && weight >= 0.0) { "weight must be finite and non-negative" }
}

private fun requireCosine(value: Double, label: String) {
    require(value.isFinite() && value in -1.0..1.0) { "$label must be a finite cosine in [-1, 1]" }
}

/** A position, where the origin is a perfectly good value — unlike [requireVector]'s directions. */
private fun requirePosition(point: Vec3, label: String) {
    require(point.x.isFinite() && point.y.isFinite() && point.z.isFinite()) { "$label must be finite" }
}

private fun requireVector(vector: Vec3, label: String) {
    require(vector.x.isFinite() && vector.y.isFinite() && vector.z.isFinite()) { "$label must be finite" }
    require(vector.x * vector.x + vector.y * vector.y + vector.z * vector.z > 1e-20) {
        "$label must be non-zero"
    }
}
