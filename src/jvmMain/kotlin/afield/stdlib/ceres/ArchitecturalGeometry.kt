@file:afield.CompletionCategory("Graphics")

package afield.stdlib.ceres

import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector3fc
import java.util.*
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A named, typed DSL over the raw constraint vocabulary in `CeresGeometry.kt`, plus the interactive
 * machinery for solving it while a user drags. [geometryModel] opens a [GeometryModelScope] where
 * you declare [PointRef]s, [LineRef]s, [PlaneRef]s and [PanelRef]s (points can be created from
 * `Vector3fc` values or bound to live `Vector3f`s via [GeometryModelScope.bindPoint]) and then
 * state relations inside `constraints { }` — [ConstraintScope] supplies `length`, `parallelTo`,
 * `angleRadiansTo`, `planar`, `on`, `inside`, `lock`, `weak`, `mirror`, `dihedral` and the `ceq`
 * infix equality.
 *
 * [GeometryModel.openSession] (or [session]) compiles the program once and returns a
 * [GeometrySolveSession] holding a mutable point cloud, per-point prior weights and a reusable
 * solve report; [GeometrySolveSession.drag] / [DragHandle] / `pin` / `release` are the interaction
 * verbs, weighted by [InteractionPolicy].
 *
 * [liveGeometry] wraps a model *block* rather than a model: each [LiveGeometryProgram.step] re-runs
 * the block, reuses the existing session when point names, point count and program hash are all
 * unchanged, reads every bound point back in, solves, and writes the results out through the
 * bindings. [LiveGeometryProgram.polishLocked] does a separate hard-locked settle pass.
 *
 * Point arithmetic ([PointExpr], `a + b`, `0.5 * c`) creates synthetic "trace" points that are
 * recomputed from their expression before every solve. The default backend is `FfmCeresBackend`, so
 * these entry points need the native Ceres library present.
 */

class PointRef internal constructor(val id: PointId, val name: String?) {
    val index: Int get() = id.value

    override fun equals(other: Any?): Boolean =
        other is PointRef && id == other.id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = name ?: "p$index"
}

data class LineRef(val a: PointRef, val b: PointRef, val name: String? = null)

data class PlaneRef(val a: PointRef, val b: PointRef, val c: PointRef, val name: String? = null)

data class PanelRef(val a: PointRef, val b: PointRef, val c: PointRef, val d: PointRef, val name: String? = null) {
    val points: List<PointRef> get() = listOf(a, b, c, d)
}

class PointExpr internal constructor(
    internal val terms: Map<PointRef, Double>,
    internal val constant: Vec3 = Vec3(0.0, 0.0, 0.0),
) {
    operator fun plus(other: PointExpr): PointExpr {
        val next = terms.toMutableMap()
        other.terms.forEach { (point, weight) ->
            next[point] = (next[point] ?: 0.0) + weight
        }
        return PointExpr(next.cleanTerms(), constant + other.constant)
    }

    operator fun plus(other: PointRef): PointExpr = this + other.expr

    operator fun minus(other: PointExpr): PointExpr = this + (-other)

    operator fun minus(other: PointRef): PointExpr = this - other.expr

    operator fun unaryMinus(): PointExpr = this * -1.0

    operator fun times(scale: Number): PointExpr {
        val s = scale.toDouble()
        require(s.isFinite()) { "expression scale must be finite" }
        return PointExpr(terms.mapValues { it.value * s }.cleanTerms(), constant * s)
    }

    internal fun directPointOrNull(): PointRef? =
        terms.entries.singleOrNull()?.takeIf { it.value == 1.0 && constant.isZero() }?.key

    override fun toString(): String =
        buildString {
            if (terms.isEmpty() && constant.isZero()) append("0")
            terms.entries.forEachIndexed { index, (point, weight) ->
                if (index > 0) append(" + ")
                append(point)
                if (weight != 1.0) append("*").append(weight)
            }
            if (!constant.isZero()) {
                if (isNotEmpty()) append(" + ")
                append("Vec3(${constant.x}, ${constant.y}, ${constant.z})")
            }
        }
}

class DotExpr internal constructor(
    val a: LineRef,
    val b: LineRef,
    val normalized: Boolean = true,
)

class GeometryTrace internal constructor(
    val target: PointRef,
    val expression: PointExpr,
)

class PointBinding internal constructor(
    val point: PointRef,
    val reader: () -> Vec3,
    val writer: (Vec3) -> Unit,
    val dragTolerance: Double,
    val dragWeight: Double?,
    internal val sourceIdentity: Int? = null,
) {
    init {
        require(dragTolerance.isFinite() && dragTolerance >= 0.0) { "drag tolerance must be finite and non-negative" }
        dragWeight?.let {
            require(it.isFinite() && it >= 0.0) { "drag weight must be finite and non-negative" }
        }
    }
}

class GeometryModel internal constructor(
    val pointNames: List<String?>,
    private val initialValues: DoubleArray,
    val program: CeresProgram,
    internal val traces: List<GeometryTrace>,
    internal val bindings: List<PointBinding>,
) {
    val pointCount: Int = pointNames.size

    fun initialPointCloud(): PointCloud = PointCloud(initialValues.copyOf())

    fun mutablePoints(): MutablePointCloud = MutablePointCloud(initialValues.copyOf(), pointNames)

    fun openSession(
        backend: CeresSolverBackend = FfmCeresBackend(),
        baselinePrior: Double = 1e-6,
        metricCapacity: Int = 128,
        policy: InteractionPolicy = InteractionPolicy(
            preserveWeight = baselinePrior,
            releaseWeight = baselinePrior,
            metricCapacity = metricCapacity,
        ),
    ): GeometrySolveSession = GeometrySolveSession(this, backend, policy)

    internal fun withHardLocks(points: Iterable<PointRef>, weight: Double): GeometryModel {
        require(weight.isFinite() && weight >= 0.0) { "hard lock weight must be finite and non-negative" }
        return withHardLocks(points.associateWith { initialValue(it) }, weight)
    }

    internal fun withHardLocks(targets: Map<PointRef, Vec3>, weight: Double): GeometryModel {
        require(weight.isFinite() && weight >= 0.0) { "hard lock weight must be finite and non-negative" }
        val ids = targets.keys.map { it.id }.distinct()
        if (ids.isEmpty()) return this
        val lockedInitialValues = initialValues.copyOf()
        targets.forEach { (point, target) ->
            require(target.x.isFinite() && target.y.isFinite() && target.z.isFinite()) { "hard lock target must be finite" }
            val i = point.id.value * 3
            lockedInitialValues[i] = target.x
            lockedInitialValues[i + 1] = target.y
            lockedInitialValues[i + 2] = target.z
        }
        val locks = listOf(
            LockPosition(ids, AxisSet.X, weight),
            LockPosition(ids, AxisSet.Y, weight),
            LockPosition(ids, AxisSet.Z, weight),
        )
        return GeometryModel(
            pointNames = pointNames,
            initialValues = lockedInitialValues,
            // copy(), not a fresh CeresProgram: the loss and any free-variable declarations belong to
            // the program being locked, and rebuilding it from its constraints alone drops them.
            program = program.copy(constraints = program.constraints + locks),
            traces = traces,
            bindings = bindings,
        )
    }

    internal fun initialValue(point: PointRef): Vec3 {
        val i = point.id.value * 3
        require(i + 2 in initialValues.indices) { "point is not in this model: $point" }
        return Vec3(initialValues[i], initialValues[i + 1], initialValues[i + 2])
    }
}

class GeometryModelScope {
    private val pointNames = mutableListOf<String?>()
    private val pointValues = mutableListOf<Double>()
    private val constraints = mutableListOf<GeometryConstraint>()
    private val traces = mutableListOf<GeometryTrace>()
    private val bindings = mutableListOf<PointBinding>()
    private val vectorPointRefs = IdentityHashMap<Vector3fc, PointRef>()
    private val mutableVectorPointRefs = mutableMapOf<Int, PointRef>()
    private var syntheticPointCounter = 0
    private var defaultInteractivePriors = true
    private var defaultInteractivePriorWeight = 1e-6

    fun point(name: String, x: Double, y: Double, z: Double): PointRef = point(x, y, z, name)

    fun point(name: String, value: Vector3fc): PointRef = point(value, name)

    fun point(value: Vector3fc, name: String? = null): PointRef =
        vectorPointRefs.getOrPut(value) {
            point(value.x().toDouble(), value.y().toDouble(), value.z().toDouble(), name)
        }

    val Vector3fc.c: PointRef get() = point(this)

    val Vector3f.point: PointRef get() = bindPoint(value = this)

    fun point(
        name: String,
        reader: () -> Vec3,
        writer: (Vec3) -> Unit = {},
        dragTolerance: Double = 1e-5,
        dragWeight: Double? = null,
    ): PointRef = bindPoint(name, reader, writer, dragTolerance, dragWeight)

    fun bindPoint(
        name: String? = null,
        reader: () -> Vec3,
        writer: (Vec3) -> Unit = {},
        dragTolerance: Double = 1e-5,
        dragWeight: Double? = null,
        sourceIdentity: Int? = null,
    ): PointRef {
        val seed = reader()
        val ref = point(seed.x, seed.y, seed.z, name)
        bindings += PointBinding(ref, reader, writer, dragTolerance, dragWeight, sourceIdentity)
        return ref
    }

    fun bindPoint(
        name: String? = null,
        value: Vector3f,
        dragTolerance: Double = 1e-5,
        dragWeight: Double? = null,
    ): PointRef {
        val identity = System.identityHashCode(value)
        mutableVectorPointRefs[identity]?.let { return it }
        val ref = bindPoint(
            name = name ?: "vector3f:$identity",
            reader = { value.vec3() },
            writer = { value.set(it.x.toFloat(), it.y.toFloat(), it.z.toFloat()) },
            dragTolerance = dragTolerance,
            dragWeight = dragWeight,
            sourceIdentity = identity,
        )
        mutableVectorPointRefs[identity] = ref
        vectorPointRefs[value] = ref
        return ref
    }

    fun point(x: Double, y: Double, z: Double, name: String? = null): PointRef {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "point coordinates must be finite" }
        val ref = PointRef(PointId(pointNames.size), name)
        pointNames += name
        pointValues += x
        pointValues += y
        pointValues += z
        return ref
    }

    fun line(a: PointRef, b: PointRef, name: String? = null): LineRef {
        require(a != b) { "line endpoints must be distinct" }
        return LineRef(a, b, name)
    }

    fun line(a: Vector3fc, b: Vector3fc, name: String? = null): LineRef =
        line(point(a), point(b), name)

    fun line(a: PointRef, b: Vector3fc, name: String? = null): LineRef =
        line(a, point(b), name)

    fun line(a: Vector3fc, b: PointRef, name: String? = null): LineRef =
        line(point(a), b, name)

    infix fun PointRef.lineTo(other: PointRef): LineRef = line(this, other)

    infix fun PointRef.lineTo(other: Vector3fc): LineRef = line(this, other)

    infix fun Vector3fc.lineTo(other: PointRef): LineRef = line(this, other)

    infix fun Vector3fc.lineTo(other: Vector3fc): LineRef = line(this, other)

    fun plane(a: PointRef, b: PointRef, c: PointRef, name: String? = null): PlaneRef {
        requireDistinctRefs(a, b, c)
        return PlaneRef(a, b, c, name)
    }

    fun plane(a: Vector3fc, b: Vector3fc, c: Vector3fc, name: String? = null): PlaneRef =
        plane(point(a), point(b), point(c), name)

    fun plane(a: PointRef, b: Vector3fc, c: Vector3fc, name: String? = null): PlaneRef =
        plane(a, point(b), point(c), name)

    fun plane(a: Vector3fc, b: PointRef, c: Vector3fc, name: String? = null): PlaneRef =
        plane(point(a), b, point(c), name)

    fun plane(a: Vector3fc, b: Vector3fc, c: PointRef, name: String? = null): PlaneRef =
        plane(point(a), point(b), c, name)

    fun panel(a: PointRef, b: PointRef, c: PointRef, d: PointRef, name: String? = null): PanelRef {
        requireDistinctRefs(a, b, c, d)
        return PanelRef(a, b, c, d, name)
    }

    fun panel(a: Vector3fc, b: Vector3fc, c: Vector3fc, d: Vector3fc, name: String? = null): PanelRef =
        panel(point(a), point(b), point(c), point(d), name)

    fun constraints(block: ConstraintScope.() -> Unit) {
        ConstraintScope(this).block()
    }

    fun defaultInteractionPriors(weight: Double = 1e-6) {
        require(weight.isFinite() && weight >= 0.0) { "default prior weight must be finite and non-negative" }
        defaultInteractivePriors = true
        defaultInteractivePriorWeight = weight
    }

    fun noDefaultInteractionPriors() {
        defaultInteractivePriors = false
    }

    internal fun add(constraint: GeometryConstraint) {
        constraints += constraint
    }

    internal fun positionOf(point: PointRef): Vec3 {
        val i = point.index * 3
        return Vec3(pointValues[i], pointValues[i + 1], pointValues[i + 2])
    }

    internal fun materialize(expr: PointExpr, name: String? = null): PointRef {
        expr.directPointOrNull()?.let { return it }
        val at = expr.terms.entries.fold(expr.constant) { acc, (point, weight) ->
            acc + positionOf(point) * weight
        }
        val ref = point(at.x, at.y, at.z, name ?: "_trace${syntheticPointCounter++}")
        traces += GeometryTrace(ref, expr)
        add(WeakPosition(listOf(ref.id), AxisSet.XYZ, 1e-6))
        return ref
    }

    fun build(): GeometryModel {
        val finalConstraints = constraints.toMutableList()
        val alreadyHasAllPointPrior = constraints.any {
            it is WeakPosition && it.axes == AxisSet.XYZ && PointId.ALL_POINTS in it.points
        }
        if (defaultInteractivePriors && !alreadyHasAllPointPrior) {
            finalConstraints += WeakPosition(listOf(PointId.ALL_POINTS), AxisSet.XYZ, defaultInteractivePriorWeight)
        }
        return GeometryModel(
            pointNames = pointNames.toList(),
            initialValues = pointValues.toDoubleArray(),
            program = CeresProgram(finalConstraints),
            traces = traces.toList(),
            bindings = bindings.toList(),
        )
    }
}

class ConstraintScope internal constructor(private val model: GeometryModelScope) {
    fun add(constraint: GeometryConstraint) {
        model.add(constraint)
    }

    val Vector3fc.c: PointRef get() = model.point(this)

    val Vector3f.point: PointRef get() = model.bindPoint(value = this)

    fun point(value: Vector3fc, name: String? = null): PointRef = model.point(value, name)

    fun line(a: PointRef, b: PointRef, name: String? = null): LineRef = model.line(a, b, name)

    fun line(a: Vector3fc, b: Vector3fc, name: String? = null): LineRef = model.line(a, b, name)

    fun line(a: PointRef, b: Vector3fc, name: String? = null): LineRef = model.line(a, b, name)

    fun line(a: Vector3fc, b: PointRef, name: String? = null): LineRef = model.line(a, b, name)

    infix fun PointRef.lineTo(other: PointRef): LineRef = line(this, other)

    infix fun PointRef.lineTo(other: Vector3fc): LineRef = line(this, other)

    infix fun Vector3fc.lineTo(other: PointRef): LineRef = line(this, other)

    infix fun Vector3fc.lineTo(other: Vector3fc): LineRef = line(this, other)

    infix fun LineRef.dot(other: LineRef): DotExpr = DotExpr(this, other)

    fun DotExpr.raw(): DotExpr = DotExpr(a, b, normalized = false)

    operator fun PointRef.plus(other: PointRef): PointExpr = expr + other

    operator fun PointRef.plus(other: PointExpr): PointExpr = expr + other

    operator fun PointRef.minus(other: PointRef): PointExpr = expr - other

    operator fun PointRef.minus(other: PointExpr): PointExpr = expr - other

    operator fun PointRef.times(scale: Number): PointExpr = expr * scale

    operator fun Number.times(point: PointRef): PointExpr = point * this

    operator fun Number.times(expr: PointExpr): PointExpr = expr * this

    infix fun DotExpr.ceq(target: Number) {
        val t = target.toDouble()
        require(t.isFinite()) { "dot target must be finite" }
        add(VectorDot(a.a.id, a.b.id, b.a.id, b.b.id, t, normalized, 1.0))
    }

    infix fun PointRef.ceq(other: PointRef) {
        sameXYZ(this, other)
    }

    infix fun PointRef.ceq(other: Vector3fc) {
        this ceq other.c
    }

    infix fun Vector3fc.ceq(other: PointRef) {
        this.c ceq other
    }

    infix fun PointExpr.ceq(other: PointRef) {
        sameXYZ(model.materialize(this), other)
    }

    infix fun PointExpr.ceq(other: Vector3fc) {
        this ceq other.c
    }

    infix fun PointRef.ceq(other: PointExpr) {
        other ceq this
    }

    fun weakAll(weight: Double = 1e-6) {
        add(WeakPosition(listOf(PointId.ALL_POINTS), AxisSet.XYZ, weight))
    }

    fun noNegativeZAll(weight: Double = 1.0) {
        add(NoNegativeZ(listOf(PointId.ALL_POINTS), weight))
    }

    fun Iterable<PointRef>.planar(weight: Double = 1.0) {
        add(Planar(map { it.id }, weight))
    }

    fun PanelRef.planar(weight: Double = 1.0) {
        points.planar(weight)
    }

    fun PanelRef.area(target: Double, weight: Double = 1.0) {
        add(PanelArea(a.id, b.id, c.id, d.id, target, weight))
    }

    fun PointRef.on(plane: PlaneRef, offset: Double = 0.0, weight: Double = 1.0) {
        add(PointOnPlane(id, plane.a.id, plane.b.id, plane.c.id, offset, weight))
    }

    fun PointRef.inside(halfspace: PlaneRef, minSignedDistance: Double = 0.0, weight: Double = 1.0) {
        add(PointInHalfspace(id, halfspace.a.id, halfspace.b.id, halfspace.c.id, minSignedDistance, weight))
    }

    fun PointRef.lock(axes: AxisSet = AxisSet.XYZ, weight: Double = 1e6) {
        lock(listOf(this), axes, weight)
    }

    fun lock(points: Iterable<PointRef>, axes: AxisSet = AxisSet.XYZ, weight: Double = 1e6) {
        val ids = points.map { it.id }
        if (axes == AxisSet.XYZ) {
            add(LockPosition(ids, AxisSet.X, weight))
            add(LockPosition(ids, AxisSet.Y, weight))
            add(LockPosition(ids, AxisSet.Z, weight))
        } else {
            add(LockPosition(ids, axes, weight))
        }
    }

    fun PointRef.weak(axes: AxisSet = AxisSet.XYZ, weight: Double = 1e-6) {
        add(WeakPosition(listOf(id), axes, weight))
    }

    fun LineRef.length(distance: Double, weight: Double = 1.0) {
        add(DistanceEquals(a.id, b.id, distance, weight))
    }

    fun LineRef.minLength(distance: Double, weight: Double = 1.0) {
        add(DistanceAtLeast(a.id, b.id, distance, weight))
    }

    fun LineRef.maxLength(distance: Double, weight: Double = 1.0) {
        add(DistanceAtMost(a.id, b.id, distance, weight))
    }

    fun LineRef.sameLengthAs(other: LineRef, weight: Double = 1.0) {
        add(EqualDistance(a.id, b.id, other.a.id, other.b.id, weight))
    }

    fun LineRef.sameVectorAs(other: LineRef, weight: Double = 1.0) {
        add(EqualEdgeVector(a.id, b.id, other.a.id, other.b.id, weight))
    }

    fun LineRef.parallelTo(other: LineRef, weight: Double = 1.0) {
        add(LineParallel(a.id, b.id, other.a.id, other.b.id, weight))
    }

    fun LineRef.angleCosTo(other: LineRef, targetCos: Double, weight: Double = 1.0) {
        add(LineAngle(a.id, b.id, other.a.id, other.b.id, targetCos, weight))
    }

    fun LineRef.angleRadiansTo(other: LineRef, radians: Double, weight: Double = 1.0) {
        require(radians.isFinite()) { "angle must be finite" }
        angleCosTo(other, cos(radians), weight)
    }

    fun LineRef.perpendicularTo(other: LineRef, weight: Double = 1.0) {
        angleCosTo(other, 0.0, weight)
    }

    fun LineRef.direction(direction: Vec3, weight: Double = 1.0) {
        add(LineDirection(a.id, b.id, direction, weight))
    }

    fun LineRef.toward(point: PointRef, weight: Double = 1.0) {
        add(LineTowardPoint(a.id, b.id, point.id, weight))
    }

    fun LineRef.vanishesAt(point: PointRef, weight: Double = 1.0) = toward(point, weight)

    fun PlaneRef.normal(direction: Vec3, targetCos: Double = 1.0, weight: Double = 1.0) {
        add(PlaneNormalDirection(a.id, b.id, c.id, direction, targetCos, weight))
    }

    fun dihedral(edge: LineRef, sideA: PointRef, sideB: PointRef, targetCos: Double, weight: Double = 1.0) {
        add(DihedralAngle(edge.a.id, edge.b.id, sideA.id, sideB.id, targetCos, weight))
    }

    fun mirror(point: PointRef, mirror: PointRef, across: PlaneRef, weight: Double = 1.0) {
        add(MirrorAcrossPlane(point.id, mirror.id, across.a.id, across.b.id, across.c.id, weight))
    }

    fun same(points: Iterable<PointRef>, axes: AxisSet, weight: Double = 1.0) {
        add(SameCoordinate(points.map { it.id }, axes, weight))
    }

    fun sameXYZ(a: PointRef, b: PointRef, weight: Double = 1.0) {
        add(SameCoordinate(listOf(a.id, b.id), AxisSet.X, weight))
        add(SameCoordinate(listOf(a.id, b.id), AxisSet.Y, weight))
        add(SameCoordinate(listOf(a.id, b.id), AxisSet.Z, weight))
    }
}

class GeometrySolveSession internal constructor(
    model: GeometryModel,
    backend: CeresSolverBackend,
    val policy: InteractionPolicy,
) : AutoCloseable {
    var model: GeometryModel = model
        private set
    val points: MutablePointCloud = model.mutablePoints()
    val priors: MutablePriorWeights = MutablePriorWeights.uniform(model.pointCount, policy.preserveWeight)
    val report: MutableSolveReport = MutableSolveReport(policy.metricCapacity)
    val quickOptions: SolveOptions = policy.dragOptions
    val metricOptions: SolveOptions = policy.releaseOptions

    private val compiled: CompiledCeresSolver = backend.compile(model.initialPointCloud(), model.program)
    private val pinned = BooleanArray(model.pointCount)
    private val pinnedTargets = DoubleArray(model.pointCount * 3)
    private val pinnedWeights = DoubleArray(model.pointCount) { policy.pinnedWeight }

    fun solve(options: SolveOptions = quickOptions): Boolean =
        solvePrepared(options, activeDrag = null)

    internal fun solvePrepared(options: SolveOptions, activeDrag: PointId?): Boolean =
        solvePrepared(options, activeDrag?.let(::setOf) ?: emptySet())

    internal fun solvePrepared(options: SolveOptions, activeDrags: Set<PointId>): Boolean {
        applyTraces()
        applyPins(activeDrags)
        return solveCompiled(options)
    }

    private fun applyTraces() {
        model.traces.forEach { trace ->
            points.set(trace.target.id, trace.expression.evaluate(points))
        }
    }

    private fun solveCompiled(options: SolveOptions): Boolean =
        if (compiled is ReusableCompiledCeresSolver) {
            compiled.solveInPlace(points, priors, options, report)
        } else {
            val result = compiled.solve(points.snapshot(), priors.asImmutable(), options)
            points.copyFrom(result.points)
            report.ok = result.ok
            result.metrics?.let { metrics ->
                report.totalCost = metrics.totalCost ?: Double.NaN
                report.clearGroups()
                metrics.rawByOpcode.forEach { (opcode, cost) ->
                    if (opcode in report.groupCosts.indices) report.groupCosts[opcode] = cost
                }
                report.groupCount = metrics.rawByOpcode.size
            } ?: run {
                report.totalCost = Double.NaN
                report.clearGroups()
            }
            result.ok
        }

    fun solve(maxIterations: Int, metrics: Boolean = false): Boolean =
        solve(SolveOptions(maxIterations = maxIterations, metrics = metrics, metricGroupCapacity = report.groupCosts.size))

    fun set(point: PointRef, value: Vec3) {
        points.set(point.id, value.copy())
    }

    fun set(point: PointRef, x: Double, y: Double, z: Double) {
        points.set(point.id, x, y, z)
    }

    fun get(point: PointRef): Vec3 = points.get(point.id)

    fun drag(point: PointRef, policy: InteractionPolicy = this.policy): DragHandle =
        DragHandle(this, point, policy)

    fun drag(point: PointRef, target: Vec3, strength: Double = policy.dragWeight, options: SolveOptions = quickOptions): Boolean {
        points.set(point.id, target)
        priors.set(point.id, strength)
        return solvePrepared(options, activeDrag = point.id)
    }

    fun release(point: PointRef, weight: Double = policy.releaseWeight, options: SolveOptions = metricOptions): Boolean {
        priors.set(point.id, weight)
        return solve(options)
    }

    fun pin(point: PointRef, weight: Double = policy.pinnedWeight) {
        require(weight.isFinite() && weight >= 0.0) { "pin weight must be finite and non-negative" }
        pinned[point.index] = true
        pinnedWeights[point.index] = weight
        points.copyPoint(point.id, pinnedTargets, point.index * 3)
        priors.set(point.id, weight)
    }

    fun unpin(point: PointRef, weight: Double = policy.preserveWeight) {
        pinned[point.index] = false
        priors.set(point.id, weight)
    }

    fun isPinned(point: PointRef): Boolean = pinned[point.index]

    fun resetPriors(weight: Double = policy.preserveWeight) {
        priors.fill(weight)
        for (i in pinned.indices) {
            if (pinned[i]) priors.set(PointId(i), pinnedWeights[i])
        }
    }

    internal fun retargetCompatible(model: GeometryModel) {
        require(model.pointCount == this.model.pointCount) { "point count changed from ${this.model.pointCount} to ${model.pointCount}" }
        require(model.program.legacyHash() == this.model.program.legacyHash()) { "constraint program changed" }
        this.model = model
    }

    private fun applyPins(activeDrags: Set<PointId>) {
        for (i in pinned.indices) {
            if (!pinned[i]) continue
            val point = PointId(i)
            if (point in activeDrags) continue
            points.setPointFrom(point, pinnedTargets, i * 3)
            priors.set(point, pinnedWeights[i])
        }
    }

    override fun close() {
        compiled.close()
    }
}

class DragHandle internal constructor(
    private val session: GeometrySolveSession,
    val point: PointRef,
    val policy: InteractionPolicy,
) : AutoCloseable {
    private var active = true

    fun to(target: Vec3, solve: Boolean = true): Boolean {
        check(active) { "drag handle is already released" }
        session.points.set(point.id, target)
        session.priors.set(point.id, policy.dragWeight)
        return !solve || session.solvePrepared(policy.dragOptions, activeDrag = point.id)
    }

    fun to(x: Double, y: Double, z: Double, solve: Boolean = true): Boolean =
        to(Vec3(x, y, z), solve)

    fun settle(options: SolveOptions = policy.dragOptions): Boolean {
        check(active) { "drag handle is already released" }
        session.priors.set(point.id, policy.dragWeight)
        return session.solvePrepared(options, activeDrag = point.id)
    }

    fun release(options: SolveOptions = policy.releaseOptions): Boolean {
        if (!active) return session.report.ok
        active = false
        session.priors.set(point.id, policy.releaseWeight)
        return session.solve(options)
    }

    override fun close() {
        release()
    }
}

fun geometryModel(block: GeometryModelScope.() -> Unit): GeometryModel =
    GeometryModelScope().apply(block).build()

fun GeometryModel.session(
    backend: CeresSolverBackend = FfmCeresBackend(),
    baselinePrior: Double = 1e-6,
    metricCapacity: Int = 128,
    policy: InteractionPolicy = InteractionPolicy(
        preserveWeight = baselinePrior,
        releaseWeight = baselinePrior,
        metricCapacity = metricCapacity,
    ),
): GeometrySolveSession = openSession(backend, baselinePrior, metricCapacity, policy)

class LivePointMove internal constructor(
    val binding: PointBinding,
    val previous: Vec3,
    val read: Vec3,
    val distance: Double,
    val explicit: Boolean,
)

class LiveGeometryStep internal constructor(
    val ok: Boolean,
    val rebuilt: Boolean,
    val moved: List<LivePointMove>,
    val pointCount: Int,
    val programHash: UInt,
    val report: MutableSolveReport,
)

class LiveGeometryProgram(
    private val backend: CeresSolverBackend = FfmCeresBackend(),
    private val policy: InteractionPolicy = InteractionPolicy(),
    private val block: GeometryModelScope.() -> Unit,
) : AutoCloseable {
    private var session: GeometrySolveSession? = null
    private var signature: LiveGeometrySignature? = null
    private val lastExplicitDragTargets = mutableMapOf<PointId, Vec3>()
    private val lastExplicitVectorTargets = mutableMapOf<Int, Vec3>()

    val currentSession: GeometrySolveSession?
        get() = session

    fun step(
        options: SolveOptions? = null,
        dragged: Iterable<PointRef> = emptyList(),
        draggedVectors: Iterable<Vector3f> = emptyList(),
    ): LiveGeometryStep {
        val model = geometryModel(block)
        val nextSignature = LiveGeometrySignature(model)
        val existing = session
        val rebuilt: Boolean
        val active = if (existing != null && signature == nextSignature) {
            existing.retargetCompatible(model)
            rebuilt = false
            existing
        } else {
            existing?.close()
            rebuilt = true
            model.openSession(backend = backend, policy = policy)
        }
        session = active
        signature = nextSignature

        active.resetPriors(policy.preserveWeight)
        val explicitPointIds = dragged.mapTo(linkedSetOf()) { it.id }
        val explicitVectorIdentities = draggedVectors.mapTo(linkedSetOf()) { System.identityHashCode(it) }
        explicitPointIds.forEach { active.priors.set(it, policy.dragWeight) }
        val moved = readBindings(model, active, explicitPointIds, explicitVectorIdentities)
        rememberExplicitDragTargets(moved)
        val solveOptions = options ?: if (moved.isEmpty()) policy.releaseOptions else policy.dragOptions.withMetrics()
        val activeDrags = moved.mapTo(explicitPointIds) { it.binding.point.id }
        val ok = active.solvePrepared(solveOptions, activeDrags)
        writeBindings(model, active)
        return LiveGeometryStep(
            ok = ok,
            rebuilt = rebuilt,
            moved = moved,
            pointCount = model.pointCount,
            programHash = model.program.legacyHash(),
            report = active.report,
        )
    }

    fun step(dragged: PointRef, vararg moreDragged: PointRef, options: SolveOptions? = null): LiveGeometryStep =
        step(options = options, dragged = sequenceOf(dragged, *moreDragged).asIterable())

    fun step(dragged: Vector3f, vararg moreDragged: Vector3f, options: SolveOptions? = null): LiveGeometryStep =
        step(options = options, draggedVectors = sequenceOf(dragged, *moreDragged).asIterable())

    fun polishLocked(
        locked: Iterable<PointRef> = emptyList(),
        lockedVectors: Iterable<Vector3f> = emptyList(),
        weight: Double = policy.pinnedWeight,
        options: SolveOptions = policy.settleOptions,
    ): LiveGeometryStep {
        val model = geometryModel(block)
        val lockedVectorIdentities = lockedVectors.mapTo(linkedSetOf()) { System.identityHashCode(it) }
        val lockTargets = linkedMapOf<PointRef, Vec3>()
        locked.forEach { point ->
            lockTargets[point] = lastExplicitDragTargets[point.id] ?: model.initialValue(point)
        }
        model.bindings.forEach { binding ->
            val identity = binding.sourceIdentity
            if (identity in lockedVectorIdentities) {
                lockTargets[binding.point] = binding.reader()
            }
        }
        val lockedModel = model.withHardLocks(lockTargets, weight)
        lockedModel.openSession(backend = backend, policy = policy).use { polish ->
            polish.resetPriors(policy.preserveWeight)
            val moved = readBindings(lockedModel, polish, emptySet(), emptySet(), detectImplicitMoves = false)
            val ok = polishRepeated(polish, options)
            writeBindings(lockedModel, polish)
            return LiveGeometryStep(
                ok = ok,
                rebuilt = true,
                moved = moved,
                pointCount = lockedModel.pointCount,
                programHash = lockedModel.program.legacyHash(),
                report = polish.report,
            )
        }
    }

    private fun polishRepeated(session: GeometrySolveSession, options: SolveOptions): Boolean {
        val passOptions = options.copy(maxIterations = 1)
        var ok = false
        var previousCost = Double.POSITIVE_INFINITY
        repeat(options.maxIterations) {
            ok = session.solve(passOptions)
            if (options.metrics) {
                val cost = session.report.totalCost
                if (hasConverged(previousCost, cost)) return ok
                previousCost = cost
            }
        }
        return ok
    }

    private fun hasConverged(previous: Double, current: Double): Boolean {
        if (!previous.isFinite() || !current.isFinite()) return false
        val improvement = previous - current
        return improvement >= 0.0 && improvement <= max(1e-12, abs(previous) * 1e-9)
    }

    fun polishLocked(locked: PointRef, vararg moreLocked: PointRef, weight: Double = policy.pinnedWeight, options: SolveOptions = policy.settleOptions): LiveGeometryStep =
        polishLocked(locked = sequenceOf(locked, *moreLocked).asIterable(), weight = weight, options = options)

    fun polishLocked(locked: Vector3f, vararg moreLocked: Vector3f, weight: Double = policy.pinnedWeight, options: SolveOptions = policy.settleOptions): LiveGeometryStep =
        polishLocked(lockedVectors = sequenceOf(locked, *moreLocked).asIterable(), weight = weight, options = options)

    private fun readBindings(
        model: GeometryModel,
        session: GeometrySolveSession,
        explicitPointIds: Set<PointId>,
        explicitVectorIdentities: Set<Int>,
        detectImplicitMoves: Boolean = true,
    ): List<LivePointMove> {
        val moved = mutableListOf<LivePointMove>()
        model.bindings.forEach { binding ->
            val read = binding.reader()
            require(read.x.isFinite() && read.y.isFinite() && read.z.isFinite()) { "point reader returned non-finite coordinates" }
            val previous = session.get(binding.point)
            val distance = distance(previous, read)
            session.set(binding.point, read.copy())
            val explicit = binding.point.id in explicitPointIds || binding.sourceIdentity in explicitVectorIdentities
            if (explicit || (detectImplicitMoves && distance > binding.dragTolerance)) {
                session.priors.set(binding.point.id, binding.dragWeight ?: policy.dragWeight)
                moved += LivePointMove(binding, previous, read, distance, explicit)
            }
        }
        return moved
    }

    private fun writeBindings(model: GeometryModel, session: GeometrySolveSession) {
        model.bindings.forEach { binding ->
            binding.writer(session.get(binding.point))
        }
    }

    private fun rememberExplicitDragTargets(moved: List<LivePointMove>) {
        moved.forEach { move ->
            if (!move.explicit) return@forEach
            lastExplicitDragTargets[move.binding.point.id] = move.read
            move.binding.sourceIdentity?.let { lastExplicitVectorTargets[it] = move.read }
        }
    }

    override fun close() {
        session?.close()
        session = null
        signature = null
    }

    private data class LiveGeometrySignature(
        val pointNames: List<String?>,
        val pointCount: Int,
        val programHash: UInt,
    ) {
        constructor(model: GeometryModel) : this(
            pointNames = model.pointNames,
            pointCount = model.pointCount,
            programHash = model.program.legacyHash(),
        )
    }
}

fun liveGeometry(
    backend: CeresSolverBackend = FfmCeresBackend(),
    policy: InteractionPolicy = InteractionPolicy(),
    block: GeometryModelScope.() -> Unit,
): LiveGeometryProgram =
    LiveGeometryProgram(backend, policy, block)

val PointRef.expr: PointExpr get() = PointExpr(mapOf(this to 1.0))

operator fun PointRef.plus(other: PointRef): PointExpr = expr + other

operator fun PointRef.plus(other: PointExpr): PointExpr = expr + other

operator fun PointRef.minus(other: PointRef): PointExpr = expr - other

operator fun PointRef.minus(other: PointExpr): PointExpr = expr - other

operator fun PointRef.times(scale: Number): PointExpr = expr * scale

operator fun Number.times(point: PointRef): PointExpr = point * this

operator fun Number.times(expr: PointExpr): PointExpr = expr * this

private fun requireDistinctRefs(vararg refs: PointRef) {
    require(refs.map { it.id }.toSet().size == refs.size) { "point references must be distinct" }
}

private fun Map<PointRef, Double>.cleanTerms(): Map<PointRef, Double> =
    filterValues { it != 0.0 }

private operator fun Vec3.plus(other: Vec3): Vec3 =
    Vec3(x + other.x, y + other.y, z + other.z)

private operator fun Vec3.times(scale: Double): Vec3 =
    Vec3(x * scale, y * scale, z * scale)

private fun Vec3.isZero(): Boolean =
    x == 0.0 && y == 0.0 && z == 0.0

private fun PointExpr.evaluate(points: MutablePointCloud): Vec3 =
    terms.entries.fold(constant) { acc, (point, weight) ->
        acc + points.get(point.id) * weight
    }

private fun Vector3fc.vec3(): Vec3 =
    Vec3(x().toDouble(), y().toDouble(), z().toDouble())

private fun SolveOptions.withMetrics(): SolveOptions =
    if (metrics) this else copy(metrics = true)

private fun distance(a: Vec3, b: Vec3): Double {
    val dx = a.x - b.x
    val dy = a.y - b.y
    val dz = a.z - b.z
    return sqrt(dx * dx + dy * dy + dz * dz)
}
