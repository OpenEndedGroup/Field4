package afield.stdlib.ceres

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

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

enum class CeresFeature {
    LegacyWasmBytecode,
    DotProduct,
    VectorDotProduct,
    NativeGeometry,
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
    LineTowardPoint(52, "LINE_TOWARD_POINT");

    companion object {
        private val byCode = entries.associateBy { it.code }

        fun metricNameFor(code: Int): String = byCode[code]?.metricName ?: "op$code"
    }
}

sealed interface GeometryConstraint {
    val weight: Double
    val requiredFeature: CeresFeature
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

    override val requiredFeature = CeresFeature.LegacyWasmBytecode

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

    override val requiredFeature = CeresFeature.LegacyWasmBytecode

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

    override val requiredFeature = CeresFeature.LegacyWasmBytecode

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

    override val requiredFeature = CeresFeature.LegacyWasmBytecode

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

    override val requiredFeature = CeresFeature.LegacyWasmBytecode

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

    override val requiredFeature = CeresFeature.LegacyWasmBytecode

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

    override val requiredFeature = CeresFeature.LegacyWasmBytecode

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

    override val requiredFeature = CeresFeature.LegacyWasmBytecode

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

    override val requiredFeature = CeresFeature.LegacyWasmBytecode

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

    override val requiredFeature = CeresFeature.LegacyWasmBytecode

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

    override val requiredFeature = CeresFeature.LegacyWasmBytecode

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

    override val requiredFeature =
        if (normalized && target == 0.0) CeresFeature.LegacyWasmBytecode else CeresFeature.DotProduct

    override fun encodeLegacy(out: MutableList<Double>) {
        if (requiredFeature == CeresFeature.LegacyWasmBytecode) {
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

    override val requiredFeature = CeresFeature.VectorDotProduct

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

    override val requiredFeature = CeresFeature.NativeGeometry

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

    override val requiredFeature = CeresFeature.NativeGeometry

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

    override val requiredFeature = CeresFeature.NativeGeometry

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

    override val requiredFeature = CeresFeature.NativeGeometry

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

    override val requiredFeature = CeresFeature.NativeGeometry

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

    override val requiredFeature = CeresFeature.NativeGeometry

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

    override val requiredFeature = CeresFeature.NativeGeometry

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

    override val requiredFeature = CeresFeature.NativeGeometry

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

    override val requiredFeature = CeresFeature.NativeGeometry

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

    override val requiredFeature = CeresFeature.NativeGeometry

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

    override val requiredFeature = CeresFeature.NativeGeometry

    override fun encodeLegacy(out: MutableList<Double>) {
        out.op(CeresOpcode.LineTowardPoint)
        out.points(a, b, target)
        out += weight
    }
}

class CeresProgramBuilder {
    private val constraints = mutableListOf<GeometryConstraint>()

    fun add(constraint: GeometryConstraint): CeresProgramBuilder = apply {
        constraints += constraint
    }

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

    fun build(): CeresProgram = CeresProgram(constraints.toList())
}

data class CeresProgram(val constraints: List<GeometryConstraint>) {
    val requiredFeatures: Set<CeresFeature> = constraints.mapTo(linkedSetOf()) { it.requiredFeature }

    fun encodeLegacyBytecode(): DoubleArray {
        val out = mutableListOf<Double>()
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

data class SolveOptions(
    val maxIterations: Int = 8,
    val metrics: Boolean = false,
    val metricGroupCapacity: Int = 64,
) {
    init {
        require(maxIterations >= 1) { "maxIterations must be >= 1" }
        require(metricGroupCapacity >= 0) { "metricGroupCapacity must be >= 0" }
    }
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

data class SolveResult(
    val ok: Boolean,
    val points: PointCloud,
    val metrics: SolveMetrics? = null,
)

interface CompiledCeresSolver : AutoCloseable {
    val pointCount: Int
    val programHash: UInt
    val supportedFeatures: Set<CeresFeature>

    fun solve(
        points: PointCloud,
        dynamicPriorWeights: DynamicPriorWeights? = null,
        options: SolveOptions = SolveOptions(),
    ): SolveResult

    override fun close() = Unit
}

interface CeresSolverBackend {
    val supportedFeatures: Set<CeresFeature>

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
        require(backend.supportedFeatures.containsAll(program.requiredFeatures)) {
            "backend lacks required features: ${program.requiredFeatures - backend.supportedFeatures}"
        }
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

private fun MutableList<Double>.op(opcode: CeresOpcode) {
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

private fun requirePointList(points: List<PointId>, min: Int, allowAll: Boolean) {
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

private fun requireWeight(weight: Double) {
    require(weight.isFinite() && weight >= 0.0) { "weight must be finite and non-negative" }
}

private fun requireCosine(value: Double, label: String) {
    require(value.isFinite() && value in -1.0..1.0) { "$label must be a finite cosine in [-1, 1]" }
}

private fun requireVector(vector: Vec3, label: String) {
    require(vector.x.isFinite() && vector.y.isFinite() && vector.z.isFinite()) { "$label must be finite" }
    require(vector.x * vector.x + vector.y * vector.y + vector.z * vector.z > 1e-20) {
        "$label must be non-zero"
    }
}
