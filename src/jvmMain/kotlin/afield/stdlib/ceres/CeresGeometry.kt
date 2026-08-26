@file:afield.CompletionCategory("Solvers")

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
 *
 * ## Two conventions worth knowing before you tune a weight
 *
 * **Weights are square-rooted.** Every native functor stores `s = sqrt(weight)` and multiplies its
 * residual by `s`, so Ceres minimises `weight * residual^2`. A residual weight of `k` in the usual
 * "residual = k * (quantity - target)" sense is a weight of `k^2` here. Weights are also only
 * comparable between constraints whose residuals share units — a length, a dimensionless cosine and
 * an area are three different scales.
 *
 * **Compiling captures a pose.** [CeresSolverBackend.compile] takes seed points, and several
 * constraints bake that pose in permanently: [LockPosition] pins to the seed coordinates,
 * [GaugeRotation] measures rotation relative to them, and [Planar] seeds each face's plane
 * variables from them. Points passed to a later `solve` move the answer, not those targets. The one
 * exception is [WeakPosition], whose target is refreshed from the input cloud on every solve — which
 * is what makes it a "stay near where you are" regulariser rather than a "return to the seed" one.
 */

/**
 * An index into a [PointCloud], counting points rather than doubles: point `k` occupies
 * `values[3k .. 3k+2]`.
 *
 * `-1` — [ALL_POINTS] — is a sentinel meaning "the whole cloud", accepted only by the constraints
 * that say so and only on its own. Nothing here or at compile time checks an index against the cloud
 * it will be used with: the native builder silently skips a residual naming a point it cannot
 * resolve, so an index past the end of the cloud costs you the constraint rather than an error.
 * [CeresExpressionResidual] is the exception and validates.
 */
@JvmInline
value class PointId(val value: Int) {
    init {
        require(value >= -1) { "point index must be >= -1" }
    }

    companion object {
        /**
         * "Every point in the cloud", resolved at compile time against whatever cloud the solver is built for.
         *
         * Accepted by [WeakPosition], [NoNegativeZ], [Centroid], [RadiusAboutCentroid] and [GaugeRotation]. It only
         * means anything on its own: the first two reject a list that mixes it with real indices, while the last three
         * accept one and then drop the sentinel, constraining just the points you named. The builder's `…All`
         * methods are the way to reach it without that ambiguity.
         */
        val ALL_POINTS = PointId(-1)
    }
}

/**
 * A bare xyz triple, used for both positions and directions.
 *
 * Which one it is decides what is legal: a position may be the origin ([Centroid]'s default target
 * is), a direction may not — [LineDirection] and [PlaneNormalDirection] reject a vector shorter than
 * `1e-10` and normalise the rest, so their magnitude never reaches the solver.
 */
data class Vec3(val x: Double, val y: Double, val z: Double)

/** Unused by this vocabulary; the constraints that take an axis take an [AxisSet]. */
enum class Axis {
    X,
    Y,
    Z,
}

/**
 * Which coordinates a lock, prior or same-coordinate constraint acts on.
 *
 * `XYZ` is the odd one out: only [WeakPosition] accepts it. [SameCoordinate] and [LockPosition]
 * reject it in their constructors, because the bytecode has single-axis and axis-pair opcodes and no
 * all-three one — say `XY` plus `Z` if you want all three, or reach for [WeakPosition]. `XY`, `XZ`
 * and `YZ` are in turn rejected by [WeakPosition].
 */
enum class AxisSet {
    X,
    Y,
    Z,
    XY,
    XZ,
    YZ,
    XYZ,
}

/**
 * The wire vocabulary: one entry per residual family the native wrapper knows how to build.
 *
 * [code] is an ABI number shared with the C++ side and cannot be renumbered without rebuilding the
 * dylib; the gaps and the bands (20s locks, 30s priors, 40s native geometry, 60s the solver-directed
 * and global families) are deliberate room to grow. [metricName] is the label [SolveMetrics.top] prints.
 *
 * The codes double as indices into the per-group cost array, so [SolveOptions.metricGroupCapacity]
 * has to exceed the highest code you care about — the default 64 silently reports nothing for
 * [RadiusAboutCentroid] and everything after it.
 *
 * A constraint class does not always encode to the opcode of the same name: [DotAtOrigin] degrades to
 * [PerpendicularAtOrigin] when it is asking for a plain right angle, and [VectorDot] and [LineAngle]
 * fold into [DotAtOrigin] when their two segments share an endpoint. Metrics follow the opcode
 * actually emitted, not the class you wrote.
 */
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

/**
 * One geometric condition, as a thing that can validate itself, name the backend feature it needs,
 * and serialise itself into the opcode stream.
 *
 * Every implementation is a `data class` that checks its arguments in an `init` block, so a
 * malformed constraint throws where it is written rather than at solve time. None of them is a hard
 * constraint: each contributes weighted least-squares residuals that the minimiser trades off against
 * every other, and a heavy weight ([LockPosition] defaults to `1e6`) is the nearest thing to "must".
 *
 * `weight` is square-rooted by the native functor, so the solver sees `weight * residual^2` — see the
 * conventions note on this file.
 */
sealed interface GeometryConstraint {
    val weight: Double

    /**
     * Append this constraint's opcodes and operands to the program being built.
     *
     * An implementation may emit a *different* opcode than its own name when a cheaper or
     * better-conditioned one says the same thing — the rewrites are documented on the classes that do
     * it. Nothing here validates against the cloud the program will be solved against; the operand
     * layout is the contract, and getting it wrong (only reachable via [RawProgramFragment]) desyncs
     * everything after it.
     */
    fun encodeLegacy(out: MutableList<Double>)
}

/**
 * All of [points] on one common plane, which the solver introduces as free variables: an origin `O`
 * and a unit normal `n` on a sphere manifold, seeded from a PCA fit of the seed cloud. One residual
 * per point, `(P - O) . n`, in length units.
 *
 * Prefer [PlanarHesse] for anything new. This form has an exact two-dimensional null space per face —
 * the residual sees `O` only through `O . n`, so sliding `O` within its own plane changes nothing —
 * held only by a `1e-6` stabiliser, and on a solid with more faces than vertices those null directions
 * outnumber the real parameters and Levenberg-Marquardt spends its budget crawling along them. It is
 * kept as its own opcode so that switching does not silently move existing architectural results.
 *
 * Two consequences of the auxiliary variables: Ceres' reported cost includes the two stabiliser
 * residuals per face that you never wrote, and the plane is seeded at compile time, so a solver reused
 * against a very different cloud starts each plane from the wrong place.
 */
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

/**
 * Every point in [points] on the infinite line through [lineA] and [lineB] — three residuals each,
 * `cross(unit(B - A), P - A)`, so the magnitude is the perpendicular distance from the line and does
 * not grow with the line's length.
 *
 * The line is defined by two of the cloud's own points, so it moves too: this is "these are colinear",
 * not "these lie on a fixed line". The endpoints must be distinct and must not appear in [points] —
 * a point that is its own reference contributes a residual that is identically zero, and Ceres will
 * not accept the same parameter block twice in one block anyway.
 */
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

/**
 * A fixed separation: one residual, `|a - b| - distance`, in length units. The workhorse of the
 * vocabulary and the one whose weight everything else is usually scaled against.
 *
 * [distance] may be zero, which welds the two points together; the native side adds `1e-20` under the
 * root so the derivative survives coincident points.
 */
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

/**
 * A one-sided floor: `max(0, minimum - |a - b|)`. Contributes *exactly* nothing while the points are
 * far enough apart, which is what makes it safe to sprinkle over a problem you have already tuned.
 *
 * The kink at the floor is not differentiable and Ceres will use a subgradient there — fine for a
 * barrier, which is all this is for. Its main use is guarding an objective that gets *cheaper* as
 * geometry degenerates: the canonical-form solve has exact zero-cost minima in which edges have
 * collapsed to nothing, and a floor at a small fraction of the mean edge length removes them without
 * touching the healthy cases.
 */
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

/**
 * A one-sided ceiling: `max(0, |a - b| - maximum)`, zero while the points are close enough. The mirror
 * of [DistanceAtLeast], with the same non-differentiable kink at the limit.
 */
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

/**
 * Two segments the same length, without saying what that length is: one residual,
 * `|a - b| - |c - d|`. The building block of "all these edges are equal" without committing to a scale.
 *
 * Keep the two pairs disjoint. This constraint checks only that each pair has distinct endpoints, so
 * `EqualDistance(0, 1, 0, 2)` is accepted here and reaches Ceres as a single residual block naming
 * point `0` twice, which Ceres does not permit. [VectorDot] and [LineAngle] rewrite that case;
 * [EqualEdgeVector] rejects it outright; this one does neither.
 */
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

/**
 * Points that agree on one or two coordinates — a shared height, a vertical alignment, a row.
 *
 * A star, not a chain: every point after the first is tied to `points[0]`, giving `points.size - 1`
 * residual blocks of one or two residuals each, in length units. Every residual therefore names
 * `points[0]`, which is where the whole group's gradient lands.
 *
 * [AxisSet.XYZ] is rejected — use [WeakPosition] or a pair of locks.
 */
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

/**
 * A right angle at [origin] between the rays to [a] and to [b]: one residual, the dot product of the
 * two *normalised* directions, so it is a dimensionless cosine and its weight is not on the same scale
 * as a distance constraint's.
 *
 * Because the directions are normalised, the constraint says nothing about the two lengths, and it is
 * satisfied equally by 90 degrees and 270 — there is no orientation here, only perpendicularity.
 */
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

/**
 * Hold points at the coordinates they had **when the solver was compiled** — not the ones you pass to
 * `solve`. One residual per locked axis per point, in length units, at a default weight of `1e6`.
 *
 * That target is captured once, from the seed cloud handed to [CeresSolverBackend.compile], and never
 * refreshed. It is what makes this a genuine anchor rather than a smoothing term, and it is also the
 * trap: reuse a cached solver against a cloud that has moved and the locks pull back towards the old
 * pose. For "wherever this point is now, keep it there", use [WeakPosition] with a large weight.
 *
 * Still a residual, not a hard constraint — a heavy enough conflicting constraint will move a locked
 * point. [AxisSet.XYZ] is rejected; the bytecode has axis and axis-pair locks only.
 */
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

/**
 * A prior pulling each point towards **the position it had on entry to this solve**, refreshed every
 * call. The regulariser that makes an underdetermined problem have one answer instead of a family of
 * them, and at the default `1e-6` it is cheap enough to leave on everywhere.
 *
 * The live target is the whole point: dragging a vertex and re-solving leaves every other point held
 * where it was, so the model deforms locally instead of snapping to some remembered pose — the exact
 * opposite of [LockPosition]. The same machinery carries the interactive story: pass
 * [DynamicPriorWeights] to a solve and it *replaces* these weights per point per axis, so one
 * dragged point can be pinned hard while the rest stay loose without recompiling the program.
 *
 * Only `X`, `Y`, `Z` and `XYZ` are accepted, and only `XYZ` may use [PointId.ALL_POINTS]. The weights
 * live in one array per point, so a second prior on the same point overwrites the first's weight, and
 * mixing a single-axis prior with an `XYZ` one on the same point leaves that axis penalised twice.
 */
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

/**
 * Keep points out of negative `z`: a hinge, `max(0, -z)`, costing nothing at or above the ground plane.
 *
 * The plane is `z = 0` exactly and is not configurable — this is the architectural "nothing below the
 * ground" rule, not a general half-space test. [PointInHalfspace] takes an arbitrary plane; a
 * [CeresProgramBuilder.expression] with [barrierBelow] takes an arbitrary axis. Pass
 * [PointId.ALL_POINTS] alone for the whole cloud.
 */
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

/**
 * A prescribed angle at [origin]: one residual, `dir(a) . dir(b) - target`, i.e. [Perpendicular]
 * generalised to any cosine.
 *
 * With [normalized] true (the default) [target] is the cosine of the angle and the residual is
 * dimensionless; with it false the residual is a raw dot product in length squared, which also
 * constrains the two lengths and needs a correspondingly different weight.
 *
 * Two arguments-dependent behaviours: a normalised zero target is exactly [Perpendicular], so it
 * re-encodes as `PERP` — cheaper to solve, but its cost is reported under `PERP`. And a cosine
 * cannot distinguish `+angle` from
 * `-angle`; nothing here fixes a handedness.
 */
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

/**
 * An angle between two segments that need not touch: one residual, `dir(a0 -> a1) . dir(b0 -> b1) -
 * target`. The four-point form of [DotAtOrigin], and the same normalised/raw distinction applies.
 *
 * When the two segments *do* share an endpoint this re-encodes as [DotAtOrigin] about that shared
 * point, negating the target where the shared point is the far end of one segment so the sign still
 * means what you wrote. Not an optimisation: Ceres refuses a residual block that names the same
 * parameter block twice, so the four-block form is not available in that case.
 */
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

/**
 * [point] at a signed distance [offset] from the plane through [planeA], [planeB] and [planeC] — one
 * residual in length units, zero by default meaning "on the plane".
 *
 * The normal is `unit(cross(B - A, C - A))`, so the sign of a non-zero [offset] follows the winding of
 * the three plane points; swap two of them and the point moves to the other side. The plane is made of
 * cloud points and so is itself free to move, and it is only ever a plane through three points: a
 * fourth coplanar point needs [Planar] (or [PlanarHesse]), not three of these.
 */
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

/**
 * Two segments parallel: three residuals, the cross product of the two unit directions, so the
 * magnitude is the sine of the angle between them and does not depend on either length.
 *
 * Antiparallel counts as parallel — a cross product cannot tell the two apart. Use [EqualEdgeVector]
 * when you mean "same direction and same length", or [LineDirection] to pin a direction outright. All
 * four points must be distinct, so a shared endpoint needs [VectorDot] instead.
 */
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

/**
 * A prescribed angle between two segments: one residual, the dot product of the two unit directions
 * minus [targetCos]. Always normalised, so the residual is dimensionless — this is [VectorDot]'s
 * normalised case with a name that says what it is for.
 *
 * [targetCos] is a cosine, checked to lie in `[-1, 1]`, and a cosine has no sign of rotation: `+60` and
 * `-60` degrees are the same constraint. As with [VectorDot], two segments sharing an endpoint
 * re-encode through [DotAtOrigin], target negated where required, because Ceres will not take a
 * repeated parameter block.
 */
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

/**
 * The fold angle across an edge: one residual, the dot product of the unit normals of triangles
 * `(edgeA, edgeB, sideA)` and `(edgeA, edgeB, sideB)`, minus [targetCos].
 *
 * The sign convention is worth getting right, because both extremes look plausible. That dot product is
 * the cosine of the dihedral angle measured between the two half-planes hinged on the edge, so
 * `targetCos = -1` is flat (180 degrees, the two faces coplanar and pointing away from each other) and
 * `+1` is folded shut. A flat panel joint is `-1`, not `+1`.
 *
 * Dimensionless, and unsigned in the same way every cosine here is: it cannot distinguish a valley fold
 * from a mountain fold. All four points must be distinct.
 */
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

/**
 * One segment a translate of another: three residuals, `(b - a) - (d - c)`, in length units.
 *
 * Strictly stronger than [EqualDistance] plus [LineParallel] — it fixes length, direction *and* sense
 * in one block, which is what a repeated module or a mirrored bay usually means. All four points must be
 * distinct.
 */
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

/**
 * [point] and [mirror] reflections of each other in the plane through [planeA], [planeB], [planeC].
 *
 * Four residuals stating the two halves of a reflection: the midpoint lies on the plane (one, in length
 * units) and the segment between them is parallel to the plane normal (three, the cross product of the
 * unit normal with the segment, so in length units too).
 *
 * Symmetric — it does not decide which side either point is on, so a pair that has both collapsed onto
 * the plane satisfies it. All five points must be distinct.
 */
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

/**
 * A quadrilateral panel of a prescribed area: one residual, the summed areas of triangles `abc` and
 * `acd` minus [targetArea].
 *
 * The split is along the `a`-`c` diagonal, and it is the corner *order* that decides what "the panel" is
 * — pass them around the perimeter, or you will be constraining a bow tie. Because the panel is
 * triangulated rather than fitted, a non-planar quad still has a well-defined area here, just one that
 * depends on which diagonal was chosen; pair this with [Planar] if you want it flat.
 *
 * The residual is an area, so its weight lives on a different scale from every length constraint in this
 * file by a factor of length squared. [targetArea] may be zero, which collapses the panel.
 */
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

/**
 * Keep [point] on one side of a plane: a hinge, `max(0, minSignedDistance - signed distance)`, costing
 * nothing once the point is far enough over. A clearance or a keep-out zone, and free while respected.
 *
 * Which side is "in" follows the winding of [planeA], [planeB], [planeC] through
 * `unit(cross(B - A, C - A))`; a positive [minSignedDistance] demands that much clearance on the normal
 * side. Non-differentiable at the boundary, like the other barriers here. All four points must be
 * distinct.
 */
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

/**
 * A segment aligned with a world direction — vertical columns, level beams. Three residuals, the cross
 * product of the segment's unit direction with the normalised [direction], so a sine and dimensionless.
 *
 * One of the few constraints that reaches outside the cloud, which makes it the way to pin an
 * orientation the geometry cannot express about itself. [direction] is normalised, so its length is
 * irrelevant, and a vector shorter than `1e-10` is rejected. Antiparallel satisfies it: a cross product
 * has no sense of direction. Says nothing about length or position.
 */
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

/**
 * A face turned to face a given way: one residual, the dot product of `unit(cross(b - a, c - a))` with
 * the normalised [normal], minus [targetCos].
 *
 * Unlike [LineDirection] this one *is* signed — the default `targetCos = 1.0` means "same way", `-1.0`
 * means "flipped", and the sign is decided by the winding of `a`, `b`, `c`. Intermediate values give a
 * fixed tilt, unsigned about the axis. Dimensionless. [normal] is normalised and must be longer than
 * `1e-10`; the three points must be distinct.
 */
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

/**
 * [target] on the infinite line through [a] and [b] — three residuals, the same `cross(unit(b - a),
 * target - a)` the native side uses for [Colinear], reported under its own metric group.
 *
 * The name oversells it: there is no ray and no ordering. "Toward" means the line points at [target], and
 * a target behind [a], or beyond [b], satisfies it exactly as well as one between them. All three points
 * must be distinct.
 */
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
 *
 * The sphere is centred on the origin, not on a free variable, so this only says what you mean in a frame
 * where the solid is already centred — that is [TangencyCentroid]'s job, and the two are meant to be used
 * together.
 *
 * It has one genuinely dangerous failure mode. The measurement is to the *line*, whose direction becomes
 * arbitrary as the two endpoints converge, so a collapsed edge can place its tangency point anywhere on
 * the sphere and satisfies its own constraint for free. Collapsing edges therefore *removes* cost, and a
 * midsphere objective built from these has a family of exact zero-cost minima in which part of the solid
 * has folded up — reached, in practice, on the `kk` Conway family. A [DistanceAtLeast] floor at a small
 * fraction of the mean edge length removes them and is bit-identical on healthy input.
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
 * About the tangency points and not the vertex centroid: the canonical form centres on
 * the former, and mixing the two is the classic way to make the pair of conditions fight.
 *
 * One residual block over every distinct endpoint — the endpoint list is deduplicated, since Ceres will
 * not accept a parameter block twice in one block — so the Jacobian row is dense in the points and this
 * is the constraint that stops the problem being sparse. That is the true shape of a mean, and it is
 * three residuals however many edges you pass.
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
 *
 * The mean is over *distinct* points: the list is sorted and deduplicated on the way in, so naming a point
 * twice does not weight it twice. Fixes translation and nothing else — three residuals cannot, and are not
 * trying to, hold a shape.
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
 *
 * The list is sorted and deduplicated first, so the residual count is the number of *distinct* points and
 * their order is by ascending index — worth knowing only if you are reading raw residuals.
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

/**
 * A fluent front end for the constraint classes, taking plain `Int` point indices instead of [PointId]s.
 *
 * Every method is a thin wrapper that constructs the corresponding constraint, so the argument checks and
 * the surprises documented there apply unchanged — this saves the ceremony, not the reading. Reach for
 * [buildCeresProgram] rather than instantiating it.
 *
 * Because the indices are ordinary `Int`s the `-1` whole-cloud sentinel would be easy to pass by accident;
 * it is instead reached explicitly through the `…All` methods ([weakAll], [centroidAll],
 * [radiusAboutCentroidAll], [noNegativeZAll], [gaugeRotationAll]). [build] snapshots the constraints, so a
 * builder may be extended and built again, and the resulting [CeresProgram]s are independent.
 */
class CeresProgramBuilder {
    private val constraints = mutableListOf<GeometryConstraint>()
    private val variables = mutableListOf<CeresVariableDecl>()

    /** Append an already-constructed constraint — the escape hatch for one this class has no method for. */
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

    /**
     * Splice literal opcodes into the program. See [RawProgramFragment] — unvalidated, and a miscounted
     * operand list corrupts everything after it.
     */
    fun raw(vararg values: Double) = add(RawProgramFragment(values.toList()))

    /** Snapshot the constraints so far, with the wrapper's historic `Huber(1e-3)` loss. */
    fun build(): CeresProgram = CeresProgram(constraints.toList(), variables = variables.toList())

    /**
     * Snapshot the constraints so far under an explicit loss. [RobustLoss.NONE] is what a problem whose
     * residuals are meant to reach zero together wants.
     */
    fun build(loss: RobustLoss): CeresProgram =
        CeresProgram(constraints.toList(), loss, variables.toList())
}

/**
 * The loss the native solver wraps every residual in.
 *
 * The wrapper used to hardcode `Huber(1e-3)`. That is a good default for interactive constraint
 * editing — one badly-placed point should not drag the whole model — and the wrong one for a
 * least-squares problem whose residuals are *meant* to reach zero together, because a robust loss
 * caps the influence of the largest residual and so changes the objective you wrote down.
 * [HUBER] therefore remains the default for compatibility, and [NONE] is what a realization solve
 * wants.
 */
data class RobustLoss(val kind: Kind = Kind.HUBER, val scale: Double = 1e-3) {
    /**
     * Which of Ceres' loss functions to wrap residuals in, in increasing order of how hard they discount a large
     * residual: `NONE` (plain least squares), `HUBER`, `SOFT_L_ONE`, `CAUCHY`.
     *
     * [scale] is where each stops behaving quadratically, so it is in the *units of the residual* — and this
     * vocabulary's residuals are variously lengths, cosines and areas. A scale chosen for one family is meaningless
     * for another, which is the strongest argument for `NONE` on a mixed program.
     */
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

/**
 * A whole solve, described independently of any point data: an ordered list of constraints plus the loss
 * they are wrapped in.
 *
 * Not tied to a cloud. The same program compiles against any cloud of the right size, which is
 * what lets [CachedCeresSolvers] key a compiled solver on point count plus program hash and what lets a drag
 * loop re-solve without rebuilding anything. Constraint order is preserved and is the order the native side
 * builds residual blocks in; it does not otherwise affect the answer.
 */
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

    /**
     * Flatten the whole program to the `DoubleArray` the native wrapper parses — opcodes, operands and
     * weights in one stream, with a leading `SET_LOSS` when the loss is not the default.
     *
     * Not cached: each call re-encodes, and [CeresExpressionResidual] re-compiles its expression DAG. Solve
     * through [SolveRequest] or [CachedCeresSolvers] and it happens once.
     */
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

    /**
     * The cache key: an FNV-1a hash of the encoded bytecode, so two programs that encode identically are the
     * same compiled solver.
     *
     * 32 bits, and used as an identity by [CachedCeresSolvers] — a collision would silently reuse the wrong
     * compiled problem. Encodes the program each time it is called.
     */
    fun legacyHash(): UInt = fnv1a64BitDoubles(encodeLegacyBytecode())
}

/**
 * The geometry: a flat `[x, y, z, x, y, z, …]` array, immutable in intent, validated finite on construction.
 *
 * The array is *not* copied on the way in, so this is only immutable if you stop writing to whatever you
 * passed; [copyValues] and [withPoint] hand back copies, and `data class` equality compares the array
 * *reference* rather than its contents. For a drag loop that would rather mutate in place than allocate a
 * cloud per frame, see [MutablePointCloud].
 */
data class PointCloud(val values: DoubleArray) {
    init {
        require(values.size % 3 == 0) { "point cloud must be a flat xyz array" }
        require(values.all { it.isFinite() }) { "point coordinates must be finite" }
    }

    /** Points, not doubles: `values.size / 3`. */
    val pointCount: Int = values.size / 3

    /** Bounds-checked; throws rather than returning a garbage triple. Takes a point index, not an offset. */
    operator fun get(index: Int): Vec3 {
        require(index in 0 until pointCount) { "point index out of range" }
        val i = index * 3
        return Vec3(values[i], values[i + 1], values[i + 2])
    }

    /** A copy with one point moved. Copies the whole array — fine for an edit, wasteful in a per-frame loop. */
    fun withPoint(index: Int, value: Vec3): PointCloud {
        require(index in 0 until pointCount) { "point index out of range" }
        val next = values.copyOf()
        val i = index * 3
        next[i] = value.x
        next[i + 1] = value.y
        next[i + 2] = value.z
        return PointCloud(next)
    }

    /** A defensive copy of the backing array — the safe way to hand these coordinates to anything else. */
    fun copyValues(): DoubleArray = values.copyOf()

    companion object {
        /** Build from triples rather than a flat array; the readable way to write a seed cloud by hand. */
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

/**
 * Per-point, per-axis override of the [WeakPosition] prior weights, supplied at solve time rather than baked
 * into the program.
 *
 * This is what makes interactive editing work without recompiling: the native side reads these weights
 * through a pointer on every solve, so "pin the point under the cursor hard, keep everything else loose"
 * is a change of numbers, not a change of problem. Flat xyz like a [PointCloud], must match the cloud's point
 * count, and every entry must be finite and non-negative — zero means that axis is unconstrained by its prior.
 *
 * Only points that a [WeakPosition] already named have a prior residual to weight; a weight here does not
 * create one. See [DynamicPriorWeights.dragging] for the usual shape, and [MutablePriorWeights] for the
 * allocation-free version.
 */
data class DynamicPriorWeights(val values: DoubleArray) {
    init {
        require(values.size % 3 == 0) { "prior weights must be a flat xyz array" }
        require(values.all { it.isFinite() && it >= 0.0 }) { "prior weights must be finite and non-negative" }
    }

    val pointCount: Int = values.size / 3

    fun copyValues(): DoubleArray = values.copyOf()

    companion object {
        /** One weight everywhere — the baseline a drag is a departure from. */
        fun uniform(pointCount: Int, weight: Double): DynamicPriorWeights {
            require(pointCount >= 0) { "point count must be non-negative" }
            requireWeight(weight)
            return DynamicPriorWeights(DoubleArray(pointCount * 3) { weight })
        }

        /**
         * The interaction shape: everything at [baseline], the dragged point at [dragged], nine orders of
         * magnitude apart by default. The dragged point effectively goes where you put it and the rest of the
         * model follows through the geometry.
         */
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
 * The sparse options need a Ceres built with a sparse backend, which both
 * `./gradlew buildCeresNative` and `./gradlew buildCeresRedistributable` give you. Ceres falls back to a
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
    /** Includes residuals the caller never wrote — [Planar]'s two stabilisers per face, for instance. */
    val residualCount: Int,
    /**
     * Parameters after manifolds are taken into account, so a plane normal on a sphere counts 2, not 3. Compare it
     * with `3 * pointCount` to see how much of the problem is auxiliary variables rather than geometry.
     */
    val effectiveParameterCount: Int,
    val parameterBlockCount: Int,
    val residualBlockCount: Int,
    /**
     * What Ceres used — not necessarily what you asked for, if the build lacks that backend. Null
     * when Ceres picked something this ABI has no name for.
     */
    val linearSolverUsed: CeresLinearSolver?,
) {
    /**
     * Ceres hit one of its tolerances, rather than running out of iterations. Strictly stronger than
     * [SolveResult.ok], which only says the answer is usable.
     */
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

/**
 * What to ask of the minimiser for one solve. Per-call, not baked into the compiled solver, so a drag can be
 * cheap and the settle on release expensive.
 *
 * The defaults are tuned for interaction, not for convergence: eight iterations, dense QR, one thread, no
 * metrics and no summary. A batch solve that is supposed to *finish* wants far more iterations, probably
 * [CeresLinearSolver.SPARSE_NORMAL_CHOLESKY], and `summary = true` so that it can tell whether it did.
 *
 * Most of the interesting fields need the wrapper's configurable solve entry point rather than its
 * fixed-configuration one; [needsConfigurableSolve] is how that is decided, and asking for one of them on the
 * allocation-free path is a clear refusal rather than a silent downgrade. The three tolerances are Ceres' own
 * defaults — changing any of them, even to the same value by a different route, trips that requirement.
 */
data class SolveOptions(
    /** Trust-region iterations, not a time budget. The default of 8 is an interactive number. */
    val maxIterations: Int = 8,
    /** Fill [SolveResult.metrics] with per-opcode costs. Costs an extra problem evaluation per group. */
    val metrics: Boolean = false,
    /** Length of the per-opcode cost array; opcodes numbered at or above it are not reported at all. */
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

/**
 * One solve, bundled: geometry, program, optional prior weights and options. The unit [CachedCeresSolvers.solve]
 * takes.
 *
 * The bytecode and its hash are computed lazily and then held, so building a request per frame around an
 * unchanged program still only encodes once per request — but a *new* request each frame re-encodes. Hold the
 * compiled solver instead if that shows up.
 */
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

/**
 * Where the remaining cost is, broken down by opcode — the "which constraint family is fighting" readout.
 *
 * Keyed by opcode, and that is the catch: each residual group writes into the slot for its opcode, so with many
 * constraints of the same kind the reported figure is the *last group's* cost, not the sum. Treat it as a hint
 * about which family to look at rather than an accounting of total cost. Costs are post-loss, a group whose
 * evaluation failed reports NaN, and opcodes numbered at or above [SolveOptions.metricGroupCapacity] are absent
 * entirely.
 *
 * [byOpcode] holds the entries that map to a known [CeresOpcode]; [rawByOpcode] is everything, indexed by code,
 * which is how an opcode this Kotlin build does not know about still shows up.
 */
data class SolveMetrics(
    val totalCost: Double?,
    val byOpcode: Map<CeresOpcode, Double>,
    val rawByOpcode: Map<Int, Double>,
) {
    /**
     * The [limit] costliest groups, largest first, as `metricName to cost`. Zero and NaN entries are dropped, so
     * the result can be shorter than [limit], and empty when no group has any cost left to report.
     */
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

/**
 * The answer: always a full cloud, plus whatever diagnostics were asked for.
 *
 * [ok] is Ceres' "solution is usable", which is a much weaker claim than converged — a solve that ran out of
 * iterations is `ok` and still wrong. [points] carries the best positions found either way, so a caller that
 * ignores [ok] gets a plausible answer rather than nothing. For whether it actually converged, ask for a
 * [summary] and read [CeresSolveSummary.converged].
 */
data class SolveResult(
    val ok: Boolean,
    val points: PointCloud,
    val metrics: SolveMetrics? = null,
    val summary: CeresSolveSummary? = null,
    /** The solved free variables, in declaration order. Empty when the program declared none. */
    val variables: CeresVariableValues = CeresVariableValues.EMPTY,
)

/**
 * A [CeresProgram] built into a native `ceres::Problem`, ready to be solved repeatedly against changing point
 * data.
 *
 * Compiling is the expensive half and solving is the cheap one; the whole shape of this API is about doing the
 * first once. The cost of that is a solver locked to one point count and to the seed pose baked in by
 * [LockPosition], [GaugeRotation] and [Planar].
 *
 * Holds native memory, so it must be closed — the default no-op [close] is for in-Kotlin implementations only,
 * and the FFM one really does free. Solving after closing throws.
 */
interface CompiledCeresSolver : AutoCloseable {
    /** The point count this solver was compiled for; a cloud of any other size is refused. */
    val pointCount: Int

    /** [CeresProgram.legacyHash] of the program built into it — the identity a cache keys on. */
    val programHash: UInt

    /**
     * Solve for new positions, returning a fresh cloud and leaving the input untouched.
     *
     * [dynamicPriorWeights] overrides the [WeakPosition] weights for this call only; [options] chooses effort and
     * diagnostics.
     */
    fun solve(
        points: PointCloud,
        dynamicPriorWeights: DynamicPriorWeights? = null,
        options: SolveOptions = SolveOptions(),
    ): SolveResult

    override fun close() = Unit
}

/**
 * Something that can turn a program plus a seed cloud into a [CompiledCeresSolver] — the seam the native library
 * sits behind.
 *
 * One implementation matters in practice (`FfmCeresBackend`, over the `ken_ceres` C ABI); the interface exists so
 * that feature gating, caching and the tests are written against a capability set rather than against a dylib.
 */
interface CeresSolverBackend {
    /**
     * Build the problem. [seedPoints] is not just a starting guess — it is the pose [LockPosition] locks to,
     * [GaugeRotation] measures against, and [Planar] fits its plane variables from.
     */
    fun compile(seedPoints: PointCloud, program: CeresProgram): CompiledCeresSolver
}

/**
 * An LRU of compiled solvers keyed by point count plus program hash, so re-solving an unchanged program compiles
 * once.
 *
 * The thing to know is what the key leaves out: the seed cloud. A cache hit returns a solver compiled from
 * whatever cloud arrived *first*, so with [LockPosition], [GaugeRotation] or [Planar] in the program — all of
 * which capture that pose — the same program against a moved cloud silently reuses the old targets. Evict, or
 * hold your own solver, when that matters.
 *
 * Not thread-safe: the backing map is access-ordered, so even reads mutate it. Eviction closes the solver it drops
 * and so does [close], which is what releases the native problems.
 */
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

    /** Compile if necessary, then solve. The one call a caller who does not want to manage solvers needs. */
    fun solve(request: SolveRequest): SolveResult {
        val solver = getOrCompile(request.points, request.program)
        return solver.solve(request.points, request.dynamicPriorWeights, request.options)
    }

    /**
     * The cached solver for this program and point count, compiling one on a miss.
     *
     * Throws if the backend cannot run the program. [seedPoints] is used only
     * on a miss — on a hit it is ignored entirely, including the pose it carries.
     */
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

/**
 * The way in. Describe a solve in a [CeresProgramBuilder] block and get an immutable [CeresProgram] back.
 *
 * ```kotlin
 * val program = buildCeresProgram {
 *     distance(0, 1, 1.0)
 *     planarHesse(listOf(0, 1, 2, 3))
 *     weakAll()
 * }
 * ```
 *
 * The loss defaults to the wrapper's historic `Huber(1e-3)`, which caps the influence of the largest residual —
 * see the overload taking a [RobustLoss] if your residuals are meant to reach zero together.
 */
fun buildCeresProgram(block: CeresProgramBuilder.() -> Unit): CeresProgram =
    CeresProgramBuilder().apply(block).build()

/** As [buildCeresProgram], choosing the loss the native solver wraps every residual in. */
fun buildCeresProgram(loss: RobustLoss, block: CeresProgramBuilder.() -> Unit): CeresProgram =
    CeresProgramBuilder().apply(block).build(loss)

/**
 * Wrap a raw per-opcode cost array — the shape the native side fills — as a [SolveMetrics].
 *
 * The array is indexed *by opcode code*, not packed, so index 2 is [CeresOpcode.Distance] whether or not the
 * program contained one. [SolveMetrics.totalCost] is left null: this sees only the breakdown.
 */
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

/**
 * FNV-1a over the raw bytes of a `DoubleArray`, folding the length in at the end. The program identity behind
 * [CeresProgram.legacyHash] and the solver cache.
 *
 * Native byte order, so the value is not portable between machines — fine for an in-process cache key, not
 * something to persist. Bitwise, so `0.0` and `-0.0` hash differently and a NaN payload is significant. 32 bits,
 * which is small enough that a collision is a real if unlikely event, and a collision here means reusing the wrong
 * compiled problem.
 */
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
