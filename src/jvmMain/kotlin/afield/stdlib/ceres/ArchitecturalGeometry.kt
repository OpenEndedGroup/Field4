@file:afield.CompletionCategory("Solvers")

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

/**
 * A handle on one point of a model — what the DSL passes around instead of an index.
 *
 * Equality is by [id] alone: the name is ignored, and so is which model the reference came from. Two models that both
 * declared a third point produce references that compare equal, so a [PointRef] used against the wrong session silently
 * addresses whatever point happens to sit at that index. They are only ever created by a [GeometryModelScope].
 */
class PointRef internal constructor(val id: PointId, val name: String?) {
    /** The raw index, for reaching the underlying [PointCloud] or [MutablePointCloud] directly. */
    val index: Int get() = id.value

    override fun equals(other: Any?): Boolean =
        other is PointRef && id == other.id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = name ?: "p$index"
}

/**
 * Two points named as a segment. Carries no constraint of its own — it is the receiver the length, angle and direction
 * verbs hang off, and the two endpoints stay ordinary free points.
 */
data class LineRef(val a: PointRef, val b: PointRef, val name: String? = null)

/**
 * Three points named as a plane, which therefore moves with them.
 *
 * The order is load-bearing: the normal is `unit(cross(b - a, c - a))`, and it decides the sign of every offset and
 * every half-space taken against this plane.
 */
data class PlaneRef(val a: PointRef, val b: PointRef, val c: PointRef, val name: String? = null)

/**
 * Four points named as a quadrilateral, in perimeter order — area triangulates on the `a`-`c` diagonal, so a transposed
 * pair describes a bow tie rather than a panel.
 */
data class PanelRef(val a: PointRef, val b: PointRef, val c: PointRef, val d: PointRef, val name: String? = null) {
    val points: List<PointRef> get() = listOf(a, b, c, d)
}

/**
 * An affine combination of points — `a + b`, `0.5 * (a + b)`, `a - b` — as an expression you can constrain with
 * [ConstraintScope.ceq].
 *
 * Terms collect by point and zero coefficients are dropped, so the same point mentioned twice becomes one term with the
 * weights summed. Note that nothing normalises the coefficients: `a + b` is the sum of two positions, not their
 * midpoint; write `0.5 * (a + b)` for that.
 *
 * How it is enforced is the important part. Constraining an expression materialises a synthetic point, which is
 * *recomputed* from the expression before each solve rather than tied to its inputs by a constraint. So the relation
 * holds between solves, not within one, and force applied to the derived point does not travel back to its terms.
 */
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

/**
 * A pending dot-product statement, waiting for [ConstraintScope.ceq] to give it a target. Normalised by default, so the
 * target is a cosine; [ConstraintScope.raw] makes it a raw dot product in length squared.
 */
class DotExpr internal constructor(
    val a: LineRef,
    val b: LineRef,
    val normalized: Boolean = true,
)

/**
 * A synthetic point and the [PointExpr] it is recomputed from before every solve.
 *
 * Created when an expression is constrained. It is a real free parameter during the solve, re-derived at the start of
 * the next one — which is why an expression relation is one-way within a single solve.
 */
class GeometryTrace internal constructor(
    val target: PointRef,
    val expression: PointExpr,
)

/**
 * The link between a model point and a value that lives outside the model: a reader, a writer, and the tolerance that
 * decides when a change in the read value counts as a drag.
 *
 * Created by [GeometryModelScope.bindPoint]. [dragTolerance] is the whole trick — a bound value that moves further than
 * this between steps is treated as user input and weighted heavily, so external edits become drags without anyone
 * saying so.
 */
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

/**
 * A finished, immutable model: the named points, their seed positions, the compiled-ready [CeresProgram], and the trace
 * expressions and external bindings that go with them.
 *
 * Describes a solve without being one. [openSession] is where it becomes a live problem; [initialPointCloud] and
 * [mutablePoints] hand out copies of the seed geometry, so a model can be solved many times over from the same start.
 *
 * The seed positions are not merely a starting guess — they are what [ConstraintScope.lock] locks to and what [Planar]
 * fits its plane variables from, so two models with the same constraints and different seeds are genuinely different
 * problems.
 */
class GeometryModel internal constructor(
    val pointNames: List<String?>,
    private val initialValues: DoubleArray,
    val program: CeresProgram,
    internal val traces: List<GeometryTrace>,
    internal val bindings: List<PointBinding>,
) {
    val pointCount: Int = pointNames.size

    /** A copy of the declared positions. Copies, so the model's own seed cannot be edited through it. */
    fun initialPointCloud(): PointCloud = PointCloud(initialValues.copyOf())

    /** A fresh mutable cloud at the declared positions, carrying the point names. What a session solves into. */
    fun mutablePoints(): MutablePointCloud = MutablePointCloud(initialValues.copyOf(), pointNames)

    /**
     * Compile the program and open a live [GeometrySolveSession] over it — the transition from description to solving.
     *
     * Compiling is the expensive step and happens once here, so hold the session for as long as the model is unchanged.
     * It owns native memory and must be closed.
     *
     * Note the parameter shape: [baselinePrior] and [metricCapacity] are only read while constructing the *default*
     * [policy], so passing an explicit policy silently ignores both. Set them on the policy instead.
     */
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

/**
 * The declaration half of the DSL: everything you can say inside a [geometryModel] block before, and around, the
 * `constraints { }` that states the relations.
 *
 * Points come three ways and the difference matters. [point] with literal coordinates is a plain free variable; [point]
 * with a `Vector3fc` reads its value *once* and is memoised by object identity, so passing the same vector twice gives
 * you the same point and two equal-but-distinct vectors give you two; [bindPoint] takes a reader and a writer, and
 * those are called on every [LiveGeometryProgram] step, which is how a model tracks values that live outside it.
 *
 * [line], [plane] and [panel] are not constraints — they are named tuples of points, so the same three points can be a
 * plane here and a triangle there at no cost. Constraint order within `constraints { }` does not matter; point
 * declaration order does, because it fixes the ids.
 */
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

    /**
     * A point seeded from a `Vector3fc`, memoised by *object identity*: the same vector always yields the same
     * [PointRef], two vectors that merely hold equal coordinates yield two points.
     *
     * The value is read once, at declaration. The vector is not watched and not written back — for that, declare it
     * with [bindPoint] or the [Vector3f.point] property.
     */
    fun point(value: Vector3fc, name: String? = null): PointRef =
        vectorPointRefs.getOrPut(value) {
            point(value.x().toDouble(), value.y().toDouble(), value.z().toDouble(), name)
        }

    /** `v.c` — the terse form of [point] for a read-only vector, memoised by identity in the same way. */
    val Vector3fc.c: PointRef get() = point(this)

    /**
     * `v.point` — a point bound to a mutable `Vector3f`, read *and written back* on every solve.
     *
     * Memoised by identity, so the same vector is always the same point. The generated name embeds the vector's
     * identity hash, which means a [LiveGeometryProgram] whose block allocates fresh `Vector3f`s every step sees a
     * different model each time and rebuilds its session; hoist the vectors out of the block.
     */
    val Vector3f.point: PointRef get() = bindPoint(value = this)

    /** A named bound point: as [bindPoint], for when the name is not optional. */
    fun point(
        name: String,
        reader: () -> Vec3,
        writer: (Vec3) -> Unit = {},
        dragTolerance: Double = 1e-5,
        dragWeight: Double? = null,
    ): PointRef = bindPoint(name, reader, writer, dragTolerance, dragWeight)

    /**
     * A point whose value lives outside the model: [reader] supplies it, [writer] receives the solved result.
     *
     * The bridge between a constraint model and whatever else holds the geometry. [LiveGeometryProgram] calls [reader]
     * at the start of every step and [writer] at the end, so an external edit becomes an input and the solve becomes an
     * output. [reader] is also called here, once, for the seed — and must return finite coordinates every time or the
     * step throws.
     *
     * A point whose read value has moved by more than [dragTolerance] since the last step is treated as dragged, and
     * gets [dragWeight] (or the policy's) rather than the resting prior. That is how "the user moved this one" is
     * inferred without being told; set [dragTolerance] high to opt a point out. The default [writer] discards, which
     * makes a read-only input.
     */
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

    /**
     * Bind a mutable `Vector3f` directly: the solver reads it and writes the answer back into it, in place.
     *
     * The common case, and the reason [Vector3f.point] exists. Memoised by identity hash, so the same vector is one
     * point however often it is mentioned. Coordinates round-trip through `Float`, so a solved position is stored to
     * about seven significant digits — enough for geometry, not enough to compare a bound point for exact equality with
     * the solved cloud.
     */
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

    /**
     * A free point at a literal position. The seed matters: it is where the solve starts from, where
     * [ConstraintScope.lock] will lock to, and — for a non-convex problem — which of several answers you get.
     *
     * [name] is for diagnostics and for [LiveGeometryProgram]'s reuse check; two points may share one.
     */
    fun point(x: Double, y: Double, z: Double, name: String? = null): PointRef {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "point coordinates must be finite" }
        val ref = PointRef(PointId(pointNames.size), name)
        pointNames += name
        pointValues += x
        pointValues += y
        pointValues += z
        return ref
    }

    /**
     * Name a segment. Declares nothing about it — a [LineRef] is a handle you then hang [ConstraintScope.length],
     * [ConstraintScope.parallelTo] and the rest on.
     */
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

    /**
     * Name a plane by three of the model's own points, so it moves with them.
     *
     * The order fixes the normal, `unit(cross(b - a, c - a))`, and therefore the sign of every offset and half-space
     * taken against it. Points must be distinct; nothing checks that they are not collinear, which would leave the
     * normal arbitrary.
     */
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

    /**
     * Name a quadrilateral. Pass the corners *around the perimeter* — [ConstraintScope.area] triangulates on the
     * `a`-`c` diagonal, so a transposed pair silently constrains a bow tie.
     */
    fun panel(a: PointRef, b: PointRef, c: PointRef, d: PointRef, name: String? = null): PanelRef {
        requireDistinctRefs(a, b, c, d)
        return PanelRef(a, b, c, d, name)
    }

    fun panel(a: Vector3fc, b: Vector3fc, c: Vector3fc, d: Vector3fc, name: String? = null): PanelRef =
        panel(point(a), point(b), point(c), point(d), name)

    /**
     * State the relations. Runs immediately, appending to this model; may be called more than once, and constraints
     * accumulate across calls.
     *
     * [ConstraintScope] can also declare points, so a relation may introduce the geometry it needs inline.
     */
    fun constraints(block: ConstraintScope.() -> Unit) {
        ConstraintScope(this).block()
    }

    /**
     * Set the weight of the whole-cloud prior that [build] appends. Raise it to make the model stiffer against being
     * dragged; the default `1e-6` is meant to be invisible except where the geometry leaves a point free.
     */
    fun defaultInteractionPriors(weight: Double = 1e-6) {
        require(weight.isFinite() && weight >= 0.0) { "default prior weight must be finite and non-negative" }
        defaultInteractivePriors = true
        defaultInteractivePriorWeight = weight
    }

    /**
     * Suppress the automatic whole-cloud prior, leaving the model constrained by nothing but what you wrote.
     *
     * For a fully determined problem, or one that supplies its own regularisation. Without it, any direction the
     * constraints do not pin is a direction the minimiser is free to wander in, and the answer becomes dependent on the
     * path taken to it.
     */
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

    /**
     * Freeze the declarations into a [GeometryModel], appending the whole-cloud weak prior unless one was written by
     * hand or [noDefaultInteractionPriors] was called.
     *
     * [geometryModel] calls this for you. Calling it twice gives two independent models over the same declarations.
     */
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

/**
 * The verbs: what you can say about the points, lines, planes and panels a [GeometryModelScope] has declared.
 *
 * Every method here is a named front end for one of the constraints in `CeresGeometry.kt` — `length` is
 * [DistanceEquals], `on` is [PointOnPlane], `dihedral` is [DihedralAngle] — so the units, the sign conventions and the
 * documented traps of the underlying constraint all still apply. What this layer adds is names, receiver syntax, and a
 * few conveniences the raw vocabulary cannot express: [lock] with [AxisSet.XYZ] expands to three locks, [sameXYZ] to
 * three same-coordinate constraints, and [angleRadiansTo] takes the cosine for you.
 *
 * Weights default to `1.0` and are only comparable between constraints whose residuals share units. Nothing here checks
 * for redundancy or contradiction: two conflicting statements are not an error, they are a least-squares compromise
 * weighted by whatever you gave them.
 */
class ConstraintScope internal constructor(private val model: GeometryModelScope) {
    /** Add a raw [GeometryConstraint] — the escape hatch to anything this scope has no verb for. */
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

    /** Begin a dot-product statement: `(a lineTo b) dot (c lineTo d) ceq 0.5`. Normalised, so the target is a cosine. */
    infix fun LineRef.dot(other: LineRef): DotExpr = DotExpr(this, other)

    /**
     * Make a [dot] statement unnormalised, so the target is a raw dot product in length squared and the lengths are
     * constrained too. Rarely what you want; [angleCosTo] is the angle-only form.
     */
    fun DotExpr.raw(): DotExpr = DotExpr(a, b, normalized = false)

    operator fun PointRef.plus(other: PointRef): PointExpr = expr + other

    operator fun PointRef.plus(other: PointExpr): PointExpr = expr + other

    operator fun PointRef.minus(other: PointRef): PointExpr = expr - other

    operator fun PointRef.minus(other: PointExpr): PointExpr = expr - other

    operator fun PointRef.times(scale: Number): PointExpr = expr * scale

    operator fun Number.times(point: PointRef): PointExpr = point * this

    operator fun Number.times(expr: PointExpr): PointExpr = expr * this

    /** Drive a [dot] statement to a value. Weight is fixed at `1.0`; use [angleCosTo] if you need to set it. */
    infix fun DotExpr.ceq(target: Number) {
        val t = target.toDouble()
        require(t.isFinite()) { "dot target must be finite" }
        add(VectorDot(a.a.id, a.b.id, b.a.id, b.b.id, t, normalized, 1.0))
    }

    /**
     * Two points coincident, as three same-coordinate constraints at weight `1.0`.
     *
     * A soft weld, not an identity: the two remain separate parameters and a heavier constraint elsewhere can pull them
     * apart. If you want one point, declare one point.
     */
    infix fun PointRef.ceq(other: PointRef) {
        sameXYZ(this, other)
    }

    infix fun PointRef.ceq(other: Vector3fc) {
        this ceq other.c
    }

    infix fun Vector3fc.ceq(other: PointRef) {
        this.c ceq other
    }

    /**
     * Tie a point to an affine combination of others — `(a + b) * 0.5 ceq mid` for a midpoint.
     *
     * The combination is *materialised* as a synthetic point, recomputed from its expression immediately before every
     * solve and then constrained to [other]. The consequence is that the coupling is one-way within a solve: moving
     * `mid` pulls on the synthetic point, but not back through it onto `a` and `b`, which only catch up at the start of
     * the next solve. Good for derived geometry, wrong for a relation you expect to push both ways.
     */
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

    /**
     * All these points coplanar, on a plane the solver introduces as free variables ([Planar]).
     *
     * Needs at least three points, and is only meaningful past three. Adds auxiliary plane variables to the problem, so
     * the reported cost includes residuals you never wrote; on a model with many faces that formulation has a known
     * null space — see [Planar].
     */
    fun Iterable<PointRef>.planar(weight: Double = 1.0) {
        add(Planar(map { it.id }, weight))
    }

    /** A flat panel: [planar] over its four corners. */
    fun PanelRef.planar(weight: Double = 1.0) {
        points.planar(weight)
    }

    /**
     * A panel of a prescribed area, triangulated on the `a`-`c` diagonal ([PanelArea]).
     *
     * The residual is an area, so its weight is on a different scale from every length constraint in the model by a
     * factor of length squared.
     */
    fun PanelRef.area(target: Double, weight: Double = 1.0) {
        add(PanelArea(a.id, b.id, c.id, d.id, target, weight))
    }

    /**
     * This point on that plane, or [offset] away from it along the normal ([PointOnPlane]).
     *
     * The sign of [offset] follows the plane's point order. The plane is made of model points and moves too, so this is
     * a relation, not a fixture.
     */
    fun PointRef.on(plane: PlaneRef, offset: Double = 0.0, weight: Double = 1.0) {
        add(PointOnPlane(id, plane.a.id, plane.b.id, plane.c.id, offset, weight))
    }

    /**
     * This point on the normal side of that plane, with [minSignedDistance] of clearance ([PointInHalfspace]).
     *
     * A barrier: costs exactly nothing once satisfied, so it can be added to a tuned model without moving it. Which
     * side counts as inside follows the plane's point order.
     */
    fun PointRef.inside(halfspace: PlaneRef, minSignedDistance: Double = 0.0, weight: Double = 1.0) {
        add(PointInHalfspace(id, halfspace.a.id, halfspace.b.id, halfspace.c.id, minSignedDistance, weight))
    }

    /** Hold this point where it was declared. See [lock] for what "hold" and "where" actually mean. */
    fun PointRef.lock(axes: AxisSet = AxisSet.XYZ, weight: Double = 1e6) {
        lock(listOf(this), axes, weight)
    }

    /**
     * Hold points at the coordinates they were *declared* with, on the given axes, at a heavy default weight.
     *
     * [AxisSet.XYZ] is expanded into three separate [LockPosition] constraints, because the bytecode has no all-axes
     * lock. Still soft: heavy enough to win in practice, not a hard constraint, and the target is the seed position
     * rather than wherever the point has since been dragged to. For "keep it where it is now", pin it on the session
     * instead.
     */
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

    /**
     * A light preference for this point staying put, refreshed from its current position on every solve
     * ([WeakPosition]).
     *
     * Unlike [lock], the target follows the point, so this resists motion rather than remembering a place. The model
     * already has a whole-cloud version of this; use it to make one point stiffer than the rest.
     */
    fun PointRef.weak(axes: AxisSet = AxisSet.XYZ, weight: Double = 1e-6) {
        add(WeakPosition(listOf(id), axes, weight))
    }

    /** A fixed length ([DistanceEquals]) — the constraint everything else's weight is usually scaled against. */
    fun LineRef.length(distance: Double, weight: Double = 1.0) {
        add(DistanceEquals(a.id, b.id, distance, weight))
    }

    /** A minimum length ([DistanceAtLeast]): a barrier, costing exactly nothing while it is respected. */
    fun LineRef.minLength(distance: Double, weight: Double = 1.0) {
        add(DistanceAtLeast(a.id, b.id, distance, weight))
    }

    /** A maximum length ([DistanceAtMost]): the mirror barrier, free while short enough. */
    fun LineRef.maxLength(distance: Double, weight: Double = 1.0) {
        add(DistanceAtMost(a.id, b.id, distance, weight))
    }

    /**
     * Two segments the same length, without saying what ([EqualDistance]).
     *
     * The two segments must not share a point — see [EqualDistance], which does not check for it and cannot be encoded
     * that way. Use [sameVectorAs] if you also mean parallel.
     */
    fun LineRef.sameLengthAs(other: LineRef, weight: Double = 1.0) {
        add(EqualDistance(a.id, b.id, other.a.id, other.b.id, weight))
    }

    /**
     * One segment a translate of another — same length, same direction, same sense ([EqualEdgeVector]). Strictly
     * stronger than [sameLengthAs] plus [parallelTo]; the shape a repeated bay or module usually wants.
     */
    fun LineRef.sameVectorAs(other: LineRef, weight: Double = 1.0) {
        add(EqualEdgeVector(a.id, b.id, other.a.id, other.b.id, weight))
    }

    /** Parallel, in either sense — antiparallel satisfies it too ([LineParallel]). Endpoints must all be distinct. */
    fun LineRef.parallelTo(other: LineRef, weight: Double = 1.0) {
        add(LineParallel(a.id, b.id, other.a.id, other.b.id, weight))
    }

    /** A prescribed angle given as its cosine ([LineAngle]). Unsigned: it cannot tell `+t` from `-t`. */
    fun LineRef.angleCosTo(other: LineRef, targetCos: Double, weight: Double = 1.0) {
        add(LineAngle(a.id, b.id, other.a.id, other.b.id, targetCos, weight))
    }

    /**
     * A prescribed angle in radians, converted to a cosine here. The readable form of [angleCosTo] — and, being a
     * cosine underneath, equally unsigned and equally blind to a full turn.
     */
    fun LineRef.angleRadiansTo(other: LineRef, radians: Double, weight: Double = 1.0) {
        require(radians.isFinite()) { "angle must be finite" }
        angleCosTo(other, cos(radians), weight)
    }

    /** A right angle: [angleCosTo] at zero. Says nothing about either length. */
    fun LineRef.perpendicularTo(other: LineRef, weight: Double = 1.0) {
        angleCosTo(other, 0.0, weight)
    }

    /**
     * Align a segment with a world direction — vertical, level, on a grid axis ([LineDirection]).
     *
     * One of the few statements that reaches outside the model. The vector's length is irrelevant, it must not be zero,
     * and the reversed direction satisfies it equally.
     */
    fun LineRef.direction(direction: Vec3, weight: Double = 1.0) {
        add(LineDirection(a.id, b.id, direction, weight))
    }

    /**
     * Point this segment's line at another point ([LineTowardPoint]) — radial members, a set of lines converging.
     *
     * There is no ray: a target behind the segment satisfies it exactly as well as one in front.
     */
    fun LineRef.toward(point: PointRef, weight: Double = 1.0) {
        add(LineTowardPoint(a.id, b.id, point.id, weight))
    }

    /** [toward], named for the perspective case — a family of segments sharing one vanishing point. */
    fun LineRef.vanishesAt(point: PointRef, weight: Double = 1.0) = toward(point, weight)

    /**
     * Turn a plane to face a given direction ([PlaneNormalDirection]).
     *
     * Signed, unlike [direction]: `targetCos = 1.0` means the plane's own normal — fixed by its point order — points
     * the same way, `-1.0` means it faces the other way.
     */
    fun PlaneRef.normal(direction: Vec3, targetCos: Double = 1.0, weight: Double = 1.0) {
        add(PlaneNormalDirection(a.id, b.id, c.id, direction, targetCos, weight))
    }

    /**
     * A fold angle across an edge, given as its cosine ([DihedralAngle]).
     *
     * `-1.0` is flat and `+1.0` is folded shut, which is the opposite of most people's first guess — a coplanar panel
     * joint is `-1.0`. All four points must be distinct.
     */
    fun dihedral(edge: LineRef, sideA: PointRef, sideB: PointRef, targetCos: Double, weight: Double = 1.0) {
        add(DihedralAngle(edge.a.id, edge.b.id, sideA.id, sideB.id, targetCos, weight))
    }

    /**
     * Two points reflections of each other across a plane ([MirrorAcrossPlane]) — the symmetry statement of the DSL.
     *
     * Symmetric and side-agnostic: a pair that has collapsed onto the plane satisfies it. All five points must be
     * distinct, so a point cannot be its own mirror.
     */
    fun mirror(point: PointRef, mirror: PointRef, across: PlaneRef, weight: Double = 1.0) {
        add(MirrorAcrossPlane(point.id, mirror.id, across.a.id, across.b.id, across.c.id, weight))
    }

    /**
     * Points sharing one or two coordinates — a level row, a shared height ([SameCoordinate]).
     *
     * Every point is tied to the first in the list, not to its neighbour, and [AxisSet.XYZ] is rejected here: use
     * [sameXYZ] or [ceq].
     */
    fun same(points: Iterable<PointRef>, axes: AxisSet, weight: Double = 1.0) {
        add(SameCoordinate(points.map { it.id }, axes, weight))
    }

    /** Two points coincident, as three single-axis [SameCoordinate] constraints. What [ceq] is written in terms of. */
    fun sameXYZ(a: PointRef, b: PointRef, weight: Double = 1.0) {
        add(SameCoordinate(listOf(a.id, b.id), AxisSet.X, weight))
        add(SameCoordinate(listOf(a.id, b.id), AxisSet.Y, weight))
        add(SameCoordinate(listOf(a.id, b.id), AxisSet.Z, weight))
    }
}

/**
 * A compiled model plus the mutable state a gesture needs: the live [points], the per-point [priors], the reusable
 * [report], and the pins.
 *
 * Solve as often as you like — the native problem is built once and every [solve] reuses it, allocating nothing on the
 * fast path. The cost is that [points] *is* the state: each solve overwrites it, so snapshot before anything you might
 * want to undo.
 *
 * Two things happen before every solve, whether you asked or not. Trace points ([PointExpr]) are recomputed from their
 * expressions, and pinned points are moved back to their pinned targets — unless that point is the one currently being
 * dragged. So a [set] on a pinned point does not survive a solve.
 *
 * Owns native memory; close it, or open it with `use`.
 */
class GeometrySolveSession internal constructor(
    model: GeometryModel,
    backend: CeresSolverBackend,
    val policy: InteractionPolicy,
) : AutoCloseable {
    var model: GeometryModel = model
        private set
    /** The live geometry, overwritten by every solve. Read it for results; [snapshot] it before you need an undo. */
    val points: MutablePointCloud = model.mutablePoints()

    /** The live prior weights — this is where a drag, a pin and a release are expressed. */
    val priors: MutablePriorWeights = MutablePriorWeights.uniform(model.pointCount, policy.preserveWeight)

    /** Filled by every solve that was asked for metrics; reused, so read it before the next one. */
    val report: MutableSolveReport = MutableSolveReport(policy.metricCapacity)

    /** The cheap per-frame options from the policy. The default for [solve]. */
    val quickOptions: SolveOptions = policy.dragOptions

    /** The longer settle-with-diagnostics options from the policy. The default for [release]. */
    val metricOptions: SolveOptions = policy.releaseOptions

    private val compiled: CompiledCeresSolver = backend.compile(model.initialPointCloud(), model.program)
    private val pinned = BooleanArray(model.pointCount)
    private val pinnedTargets = DoubleArray(model.pointCount * 3)
    private val pinnedWeights = DoubleArray(model.pointCount) { policy.pinnedWeight }

    /**
     * Re-solve from the current state, in place. Returns whether the answer is usable — a weak claim, not convergence.
     *
     * Recomputes trace points and re-imposes pins first. With the default [quickOptions] this is a few iterations,
     * meant to be called every frame rather than to finish.
     */
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

    /**
     * Solve for a given number of iterations, sizing the metric request to this session's report. The convenient form
     * when you want more effort than the policy's default and nothing else changed.
     */
    fun solve(maxIterations: Int, metrics: Boolean = false): Boolean =
        solve(SolveOptions(maxIterations = maxIterations, metrics = metrics, metricGroupCapacity = report.groupCosts.size))

    /**
     * Move a point directly, without touching its prior.
     *
     * A starting position, not a constraint: the next solve is free to move it straight back unless its prior says
     * otherwise — and if the point is pinned, the pin is re-imposed and this write is lost. [drag] is the verb that
     * means "and hold it there".
     */
    fun set(point: PointRef, value: Vec3) {
        points.set(point.id, value.copy())
    }

    fun set(point: PointRef, x: Double, y: Double, z: Double) {
        points.set(point.id, x, y, z)
    }

    /** The point's current solved position. */
    fun get(point: PointRef): Vec3 = points.get(point.id)

    /**
     * Begin a gesture: a [DragHandle] that holds this point heavily until released.
     *
     * The stateful form, for a mouse-down to mouse-up sequence. Close it — or `use` it — and the point is released and
     * the model settles; forget to, and it stays weighted for every subsequent solve.
     */
    fun drag(point: PointRef, policy: InteractionPolicy = this.policy): DragHandle =
        DragHandle(this, point, policy)

    /**
     * Move a point to [target] with a heavy prior and re-solve in one call — the stateless form of a drag frame.
     *
     * Leaves the prior heavy afterwards, so a gesture built from these must end with [release]. A pinned point is not
     * fought over here: the drag counts as active, so the pin is not re-imposed for this solve.
     */
    fun drag(point: PointRef, target: Vec3, strength: Double = policy.dragWeight, options: SolveOptions = quickOptions): Boolean {
        points.set(point.id, target)
        priors.set(point.id, strength)
        return solvePrepared(options, activeDrag = point.id)
    }

    /**
     * End a drag: drop the point's prior back to resting and run the longer settle.
     *
     * The point keeps wherever the solve leaves it — releasing is not undoing. Note that this does *not* clear a pin;
     * the next solve will re-impose one if there is one.
     */
    fun release(point: PointRef, weight: Double = policy.releaseWeight, options: SolveOptions = metricOptions): Boolean {
        priors.set(point.id, weight)
        return solve(options)
    }

    /**
     * Fix a point where it is now, and keep putting it back there before every solve.
     *
     * Stronger than a heavy prior, because the position is re-asserted rather than merely preferred: the current
     * position is captured as the pin target, and every subsequent solve restores it first. The exception is a point
     * being actively dragged, which wins for that solve. [set] on a pinned point does not stick; [unpin] first.
     */
    fun pin(point: PointRef, weight: Double = policy.pinnedWeight) {
        require(weight.isFinite() && weight >= 0.0) { "pin weight must be finite and non-negative" }
        pinned[point.index] = true
        pinnedWeights[point.index] = weight
        points.copyPoint(point.id, pinnedTargets, point.index * 3)
        priors.set(point.id, weight)
    }

    /** Release a pin and return the point to a resting prior. Leaves it where it is; it is simply free again. */
    fun unpin(point: PointRef, weight: Double = policy.preserveWeight) {
        pinned[point.index] = false
        priors.set(point.id, weight)
    }

    /** Whether [pin] is currently holding this point. */
    fun isPinned(point: PointRef): Boolean = pinned[point.index]

    /**
     * Return every prior to [weight], then restore the pinned points' own weights — the "forget the last gesture" call.
     *
     * Pins survive; drags do not. [LiveGeometryProgram] calls this at the start of every step, which is why an implicit
     * drag has to be re-detected each time rather than persisting.
     */
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

/**
 * One gesture on one point: `to` while it moves, `release` when it stops.
 *
 * A handle exists so the heavy prior has an owner. It is [AutoCloseable] and closing releases, so `use` is the shape
 * that cannot leave a point weighted; call [to] or [settle] after releasing and it throws rather than quietly
 * reweighting.
 *
 * Nothing stops two handles existing on the same point at once, and they would fight over its prior.
 */
class DragHandle internal constructor(
    private val session: GeometrySolveSession,
    val point: PointRef,
    val policy: InteractionPolicy,
) : AutoCloseable {
    private var active = true

    /**
     * Move the dragged point and, unless [solve] is false, re-solve at drag effort. The per-frame call of a gesture.
     *
     * Returns the solve's usability flag — or `true` when [solve] is false, since nothing was attempted.
     */
    fun to(target: Vec3, solve: Boolean = true): Boolean {
        check(active) { "drag handle is already released" }
        session.points.set(point.id, target)
        session.priors.set(point.id, policy.dragWeight)
        return !solve || session.solvePrepared(policy.dragOptions, activeDrag = point.id)
    }

    fun to(x: Double, y: Double, z: Double, solve: Boolean = true): Boolean =
        to(Vec3(x, y, z), solve)

    /**
     * Re-solve without moving the point — more iterations at the same target, while still holding the drag.
     *
     * Defaults to drag effort, so pass [InteractionPolicy.settleOptions] if you actually want the long pass. The point
     * stays held afterwards; this is not a release.
     */
    fun settle(options: SolveOptions = policy.dragOptions): Boolean {
        check(active) { "drag handle is already released" }
        session.priors.set(point.id, policy.dragWeight)
        return session.solvePrepared(options, activeDrag = point.id)
    }

    /**
     * End the gesture: drop the prior to resting and run the settle. Idempotent — a second call reports the last result
     * without solving again.
     */
    fun release(options: SolveOptions = policy.releaseOptions): Boolean {
        if (!active) return session.report.ok
        active = false
        session.priors.set(point.id, policy.releaseWeight)
        return session.solve(options)
    }

    /** Releases, which means closing a handle *solves*. Intentional: `use` should not leave a point weighted. */
    override fun close() {
        release()
    }
}

/**
 * The way in: declare points and the relations between them, get back an immutable [GeometryModel] that can be solved
 * and re-solved.
 *
 * ```kotlin
 * val model = geometryModel {
 *     val a = point("a", 0.0, 0.0, 0.0)
 *     val b = point("b", 1.0, 0.2, 0.0)
 *     constraints {
 *         (a lineTo b).length(1.0)
 *         a.lock()
 *     }
 * }
 * ```
 *
 * The block runs once, immediately. Point ids are assigned in declaration order, so the model's identity — and the
 * session cache in [LiveGeometryProgram] — depends on the order you declare things in, not just on what you declare.
 *
 * Unless the block says [GeometryModelScope.noDefaultInteractionPriors], a weak whole-cloud [WeakPosition] is appended,
 * which is what stops an underdetermined model from wandering. Solving needs the native Ceres library, since
 * [GeometryModel.openSession] defaults to the FFM backend.
 */
fun geometryModel(block: GeometryModelScope.() -> Unit): GeometryModel =
    GeometryModelScope().apply(block).build()

/**
 * [GeometryModel.openSession] as an extension, for the reading order `model.session { }`. Same parameters and the same
 * caveat: [baselinePrior] and [metricCapacity] are ignored if you pass an explicit [policy].
 */
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

/**
 * One bound point that changed between steps: where it was, what its reader now says, and whether the caller said so
 * ([explicit]) or the move was inferred from exceeding the binding's drag tolerance.
 */
class LivePointMove internal constructor(
    val binding: PointBinding,
    val previous: Vec3,
    val read: Vec3,
    val distance: Double,
    val explicit: Boolean,
)

/**
 * What one [LiveGeometryProgram.step] did: whether the solve was usable, whether the session had to be rebuilt, which
 * bound points moved, and the model identity that was solved.
 *
 * [report] is the session's live report, not a copy — read it before the next step overwrites it. [rebuilt] is the
 * field to watch when a live-coded sketch feels like it is forgetting things: a rebuild throws away pins and session
 * state.
 */
class LiveGeometryStep internal constructor(
    val ok: Boolean,
    val rebuilt: Boolean,
    val moved: List<LivePointMove>,
    val pointCount: Int,
    val programHash: UInt,
    val report: MutableSolveReport,
)

/**
 * A model block held across frames: re-declares, reuses the compiled session where it can, reads the bound values in,
 * solves, writes them back.
 *
 * The unit of use is [step]. Everything a live-coded constraint sketch needs is there — the model may change between
 * steps, the geometry may be moved from outside, and the solve happens in place — at the price of two things worth
 * knowing. The block runs on every step, so keep it cheap and free of allocation that changes the model's identity; and
 * the session is reused only when point names, point count and program hash all match, with a rebuild dropping pins and
 * the session's accumulated state.
 *
 * [polishLocked] is the separate, non-interactive pass: a throwaway session with hard locks on the points you name,
 * iterated until the cost stops improving. Owns native memory through its session; close it.
 */
class LiveGeometryProgram(
    private val backend: CeresSolverBackend = FfmCeresBackend(),
    private val policy: InteractionPolicy = InteractionPolicy(),
    private val block: GeometryModelScope.() -> Unit,
) : AutoCloseable {
    private var session: GeometrySolveSession? = null
    private var signature: LiveGeometrySignature? = null
    private val lastExplicitDragTargets = mutableMapOf<PointId, Vec3>()
    private val lastExplicitVectorTargets = mutableMapOf<Int, Vec3>()

    /** The live session, or null before the first [step]. Replaced whenever the model changes shape. */
    val currentSession: GeometrySolveSession?
        get() = session

    /**
     * One frame: re-run the block, reuse or rebuild the session, read every bound point, solve, and write the results
     * back through the bindings.
     *
     * A point may be declared dragged — by [PointRef] in [dragged], by object in [draggedVectors] — or be *inferred*
     * dragged, because its bound value moved by more than its tolerance since the last step. Either way it gets the
     * drag weight for this step only; priors are reset at the top of every step, so a drag has to be re-asserted each
     * frame.
     *
     * With [options] null, effort is chosen from whether anything moved: drag options (with metrics forced on) if so,
     * the longer release options if not — so a still frame settles by itself. The returned [LiveGeometryStep] says
     * whether the session was rebuilt and what moved, and carries the session's live report.
     */
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

    /**
     * A settle pass with some points nailed down: build a throwaway model with hard locks on [locked], solve it to
     * convergence, and write the result out through the bindings.
     *
     * What "nailed down" means differs by argument. A [PointRef] is locked to wherever the user last explicitly dragged
     * it, or to its declared position if never dragged; a `Vector3f` is locked to whatever it currently reads. The pass
     * runs single-iteration solves up to [SolveOptions.maxIterations] and stops early once the cost stops improving —
     * but only when [SolveOptions.metrics] is on, since that is what the improvement is read from. With metrics off it
     * always runs the full count.
     *
     * The locked session is separate and closed on the way out, so the live session and its pins are untouched. Nothing
     * here is a genuinely hard constraint: "locked" is a very heavy prior.
     */
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

/**
 * Wrap a model *block* rather than a model, so the declarations can be re-run — the live-coding entry point.
 *
 * The block is evaluated on every [LiveGeometryProgram.step], which is what lets an editor change the constraints while
 * the thing is being dragged. Hold the returned program across frames: it keeps the compiled session and reuses it
 * whenever the point names, point count and program hash all match, and rebuilds silently when they do not.
 *
 * Written for a block that closes over external `Vector3f`s declared with [GeometryModelScope.bindPoint], which the
 * solve reads from and writes back to. Allocate those outside the block — a fresh one each step changes the model's
 * identity and throws the session away, along with its pins.
 */
fun liveGeometry(
    backend: CeresSolverBackend = FfmCeresBackend(),
    policy: InteractionPolicy = InteractionPolicy(),
    block: GeometryModelScope.() -> Unit,
): LiveGeometryProgram =
    LiveGeometryProgram(backend, policy, block)

/** A point as a one-term [PointExpr], so arithmetic can start from it. `a + b` and `0.5 * a` go through this. */
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
