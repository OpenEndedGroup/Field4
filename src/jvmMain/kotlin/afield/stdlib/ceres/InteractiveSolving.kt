@file:afield.CompletionCategory("Solvers")

package afield.stdlib.ceres

/**
 * The mutable, reuse-oriented half of the Ceres solver API, for solving the same program over and
 * over at interactive rates. [MutablePointCloud], [MutablePriorWeights] and [MutableSolveReport]
 * are in-place counterparts to the immutable `PointCloud` / `DynamicPriorWeights` / `SolveMetrics`
 * in `CeresGeometry.kt`, so a drag loop can call [ReusableCompiledCeresSolver.solveInPlace] without
 * allocating a new point cloud or result per frame.
 *
 * [InteractionPolicy] collects the weights and iteration counts that distinguish the three phases
 * of a drag — a cheap `dragOptions` pass while the mouse is down, a longer `releaseOptions` settle
 * on release, and `pinnedWeight` for points held fixed — and precomputes the matching
 * `SolveOptions`.
 * [MutableSolveReport.top] names the highest-cost opcode groups from the last solve.
 *
 * All point and weight arrays are flat xyz triples; coordinates must be finite and weights finite
 * and non-negative, and a report's group capacity must be at least the metric capacity a solve
 * asks for.
 */

/**
 * The numbers that turn a constraint solve into a direct-manipulation gesture: how hard a dragged point is held, how
 * hard everything else resists, and how many iterations each phase of the gesture gets.
 *
 * A drag has three phases and they want different settings — a few iterations while the mouse moves so the frame
 * lands on time, many on release so the model settles, and a heavy prior on the point under the cursor throughout.
 * This collects the choice in one place and precomputes the matching [SolveOptions].
 *
 * Only the iteration counts and the metric capacity reach those option objects. The four weights are values for the
 * caller to apply to a [MutablePriorWeights], and nothing here checks that it did — this is a convention with a type,
 * not a mechanism. The precomputed options all stay within what the v1 wrapper can do, so
 * [ReusableCompiledCeresSolver.solveInPlace] accepts them.
 */
data class InteractionPolicy(
    /** What an untouched point's prior is worth — resistance to being moved by its neighbours. */
    val preserveWeight: Double = 1e-2,
    /** The point under the cursor: five orders of magnitude above [preserveWeight], so it goes where you put it. */
    val dragWeight: Double = 1e3,
    /** What the dragged point falls back to once the gesture ends; the same as [preserveWeight] by default. */
    val releaseWeight: Double = preserveWeight,
    /** For points the user has explicitly fixed — heavy enough to win against any geometry in the program. */
    val pinnedWeight: Double = 1e8,
    /** Iterations per frame while dragging. Small on purpose: a late frame is worse than an unsettled one. */
    val dragIterations: Int = 8,
    /** Iterations for the settle pass, where latency no longer matters. */
    val settleIterations: Int = 24,
    /** Length of the per-opcode cost array; 128 clears every opcode, unlike [SolveOptions]' default of 64. */
    val metricCapacity: Int = 128,
    val metricsDuringDrag: Boolean = false,
    val metricsOnRelease: Boolean = true,
) {
    init {
        requireWeightForInteractive(preserveWeight)
        requireWeightForInteractive(dragWeight)
        requireWeightForInteractive(releaseWeight)
        requireWeightForInteractive(pinnedWeight)
        require(dragIterations >= 1) { "dragIterations must be >= 1" }
        require(settleIterations >= 1) { "settleIterations must be >= 1" }
        require(metricCapacity >= 0) { "metricCapacity must be >= 0" }
    }

    /** The cheap per-frame solve: [dragIterations], metrics only if [metricsDuringDrag]. */
    val dragOptions: SolveOptions =
        SolveOptions(dragIterations, metricsDuringDrag, metricCapacity)

    /** The settle after the mouse goes up: [settleIterations], metrics per [metricsOnRelease]. */
    val releaseOptions: SolveOptions =
        SolveOptions(settleIterations, metricsOnRelease, metricCapacity)

    /** As [releaseOptions] but with metrics forced on — for the diagnostic pass, whatever the policy says. */
    val settleOptions: SolveOptions =
        SolveOptions(settleIterations, metrics = true, metricGroupCapacity = metricCapacity)
}

/**
 * A point cloud you edit in place: the same flat xyz layout as [PointCloud], with no copy on read or write.
 *
 * This exists so that a drag loop allocates nothing per frame — [ReusableCompiledCeresSolver.solveInPlace] reads the
 * points out of [values] and writes the answer back over them. That is also the cost: the input is gone after a
 * solve, so take a [snapshot] first if you want to undo, and remember that anything holding the same array sees the
 * change.
 *
 * Coordinates are checked finite on construction and on every [set], and every accessor bounds-checks its
 * [PointId] — including [PointId.ALL_POINTS], which is not a point and throws here. [names] is optional labelling
 * for tools and diagnostics; the solver never looks at it.
 */
class MutablePointCloud(
    val values: DoubleArray,
    val names: List<String?> = List(values.size / 3) { null },
) {
    init {
        require(values.size % 3 == 0) { "point cloud must be a flat xyz array" }
        require(names.size == values.size / 3) { "point name count must match point count" }
        require(values.all { it.isFinite() }) { "point coordinates must be finite" }
    }

    val pointCount: Int = values.size / 3

    fun x(point: PointId): Double = values[offset(point)]

    fun y(point: PointId): Double = values[offset(point) + 1]

    fun z(point: PointId): Double = values[offset(point) + 2]

    fun get(point: PointId): Vec3 {
        val i = offset(point)
        return Vec3(values[i], values[i + 1], values[i + 2])
    }

    fun set(point: PointId, x: Double, y: Double, z: Double) {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "point coordinates must be finite" }
        val i = offset(point)
        values[i] = x
        values[i + 1] = y
        values[i + 2] = z
    }

    fun set(point: PointId, value: Vec3) = set(point, value.x, value.y, value.z)

    /**
     * Copy one point's three coordinates into an arbitrary array. For handing a point to a vertex buffer or a
     * transform without allocating a [Vec3].
     *
     * The source point is bounds-checked; the destination is not, so a bad [offset] is an
     * `ArrayIndexOutOfBoundsException` at best.
     */
    fun copyPoint(from: PointId, into: DoubleArray, offset: Int) {
        val i = offset(from)
        into[offset] = values[i]
        into[offset + 1] = values[i + 1]
        into[offset + 2] = values[i + 2]
    }

    fun setPointFrom(point: PointId, source: DoubleArray, offset: Int) {
        set(point, source[offset], source[offset + 1], source[offset + 2])
    }

    /** Overwrite every point from an immutable cloud of the same size — how a saved state is restored. */
    fun copyFrom(points: PointCloud) {
        require(points.pointCount == pointCount) { "point count mismatch" }
        points.values.copyInto(values)
    }

    /** A detached copy, safe to keep while this one keeps being solved into. The undo point of a gesture. */
    fun snapshot(): PointCloud = PointCloud(values.copyOf())

    private fun offset(point: PointId): Int {
        val index = point.value
        require(index in 0 until pointCount) { "point index out of range: $index" }
        return index * 3
    }
}

/**
 * The in-place counterpart of [DynamicPriorWeights]: per-point, per-axis [WeakPosition] weights, rewritten between
 * frames without allocating.
 *
 * A gesture is a handful of writes to this — [fill] the baseline once, [set] the dragged point heavy, set it back on
 * release — and the compiled solver reads the array on every solve. Weights must be finite and non-negative; zero
 * releases that axis's prior entirely.
 *
 * The constructor is private, so start from [uniform] and never from a raw array of the wrong length. [asImmutable]
 * copies, so a snapshot does not alias.
 */
class MutablePriorWeights private constructor(val values: DoubleArray) {
    init {
        require(values.size % 3 == 0) { "prior weights must be a flat xyz array" }
        require(values.all { it.isFinite() && it >= 0.0 }) { "prior weights must be finite and non-negative" }
    }

    val pointCount: Int = values.size / 3

    /** One weight over every point and axis — the baseline a gesture then departs from. */
    fun fill(weight: Double) {
        requireWeightForInteractive(weight)
        values.fill(weight)
    }

    fun set(point: PointId, x: Double, y: Double, z: Double) {
        requireWeightForInteractive(x)
        requireWeightForInteractive(y)
        requireWeightForInteractive(z)
        val i = offset(point)
        values[i] = x
        values[i + 1] = y
        values[i + 2] = z
    }

    fun set(point: PointId, weight: Double) = set(point, weight, weight, weight)

    /** A detached copy for the allocating [CompiledCeresSolver.solve] path. */
    fun asImmutable(): DynamicPriorWeights = DynamicPriorWeights(values.copyOf())

    private fun offset(point: PointId): Int {
        val index = point.value
        require(index in 0 until pointCount) { "point index out of range: $index" }
        return index * 3
    }

    companion object {
        /** The only way to make one: [pointCount] points, every axis at [weight]. */
        fun uniform(pointCount: Int, weight: Double): MutablePriorWeights {
            require(pointCount >= 0) { "point count must be non-negative" }
            requireWeightForInteractive(weight)
            return MutablePriorWeights(DoubleArray(pointCount * 3) { weight })
        }
    }
}

/**
 * A reusable, caller-owned place for a solve to put its diagnostics: the [SolveMetrics] of the in-place path, without
 * the allocation.
 *
 * Pass the same instance to every [ReusableCompiledCeresSolver.solveInPlace] and read it after; each solve overwrites
 * it. With `metrics = false` the solve leaves [totalCost] as NaN and zeroes the groups, so a stale reading is never
 * mistaken for a fresh one.
 *
 * [groupCosts] is indexed by [CeresOpcode.code], not packed, so [groupCapacity] must exceed the highest opcode you
 * care about — the default 64 has no room for [CeresOpcode.RadiusAboutCentroid] or anything above it, and the solve
 * refuses outright if the capacity is below what the [SolveOptions] asked for. As with [SolveMetrics], each group
 * writes into its opcode's slot, so with several constraints of one kind the figure is the last group's cost rather
 * than the total.
 */
class MutableSolveReport(groupCapacity: Int = 64) {
    init {
        require(groupCapacity >= 0) { "group capacity must be non-negative" }
    }

    /** Whether the last solve produced a usable answer. Weaker than convergence; see [SolveResult.ok]. */
    var ok: Boolean = false
        internal set

    /** Final cost of the last solve, or NaN when that solve was not asked for metrics. */
    var totalCost: Double = Double.NaN
        internal set

    /** How many group slots the last solve filled. */
    var groupCount: Int = 0
        internal set

    /** Costs indexed by [CeresOpcode.code], zero-filled where nothing was reported. Live; the next solve overwrites. */
    val groupCosts: DoubleArray = DoubleArray(groupCapacity)

    /**
     * The last solve's cost for one opcode. Returns `0.0` for an opcode beyond the report's capacity, which is
     * indistinguishable from an opcode that genuinely cost nothing.
     */
    fun cost(opcode: CeresOpcode): Double =
        if (opcode.code in groupCosts.indices) groupCosts[opcode.code] else 0.0

    /**
     * The [limit] costliest opcode groups, largest first, as `metricName to cost` — the "what is still fighting"
     * readout. Zero and NaN entries are dropped, so it can be shorter than asked.
     */
    fun top(limit: Int = 5): List<Pair<String, Double>> =
        groupCosts
            .withIndex()
            .filter { it.value != 0.0 && !it.value.isNaN() }
            .sortedByDescending { it.value }
            .take(kotlin.math.max(0, limit))
            .map { CeresOpcode.metricNameFor(it.index) to it.value }

    internal fun clearGroups() {
        groupCosts.fill(0.0)
        groupCount = 0
    }
}

/**
 * A [CompiledCeresSolver] that can also solve without allocating: same compiled problem, a per-frame path that reuses
 * its native buffers.
 *
 * This is the interface a drag loop holds. Implementing it is a promise about allocation, not about capability — the
 * in-place path deliberately runs the wrapper's fixed configuration, so anything in [SolveOptions] that needs the
 * configurable solve (a different linear solver, threads, a summary, a changed tolerance) is refused rather than
 * silently ignored. Those are setup-time choices; make them at compile time and solve through [solve] when you need
 * them.
 */
interface ReusableCompiledCeresSolver : CompiledCeresSolver {
    /** The largest metric-group array this solver has buffers for. A solve asking for more is refused. */
    val reusableMetricCapacity: Int

    /**
     * Solve in place: [points] is read for the starting positions and overwritten with the answer, and nothing is
     * allocated on the way. Returns whether the result is usable — the same weak guarantee as [SolveResult.ok].
     *
     * [dynamicPriorWeights] is read live, so a gesture changes weights rather than programs. [report], if given, is
     * filled with this solve's diagnostics and must have room for [SolveOptions.metricGroupCapacity] groups. The input
     * positions are gone afterwards; snapshot first if you need them.
     */
    fun solveInPlace(
        points: MutablePointCloud,
        dynamicPriorWeights: MutablePriorWeights? = null,
        options: SolveOptions = SolveOptions(),
        report: MutableSolveReport? = null,
    ): Boolean
}

private fun requireWeightForInteractive(weight: Double) {
    require(weight.isFinite() && weight >= 0.0) { "weight must be finite and non-negative" }
}
