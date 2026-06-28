@file:afield.CompletionCategory("Graphics")

package afield.stdlib.ceres

data class InteractionPolicy(
    val preserveWeight: Double = 1e-2,
    val dragWeight: Double = 1e3,
    val releaseWeight: Double = preserveWeight,
    val pinnedWeight: Double = 1e8,
    val dragIterations: Int = 8,
    val settleIterations: Int = 24,
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

    val dragOptions: SolveOptions =
        SolveOptions(dragIterations, metricsDuringDrag, metricCapacity)

    val releaseOptions: SolveOptions =
        SolveOptions(settleIterations, metricsOnRelease, metricCapacity)

    val settleOptions: SolveOptions =
        SolveOptions(settleIterations, metrics = true, metricGroupCapacity = metricCapacity)
}

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

    fun copyPoint(from: PointId, into: DoubleArray, offset: Int) {
        val i = offset(from)
        into[offset] = values[i]
        into[offset + 1] = values[i + 1]
        into[offset + 2] = values[i + 2]
    }

    fun setPointFrom(point: PointId, source: DoubleArray, offset: Int) {
        set(point, source[offset], source[offset + 1], source[offset + 2])
    }

    fun copyFrom(points: PointCloud) {
        require(points.pointCount == pointCount) { "point count mismatch" }
        points.values.copyInto(values)
    }

    fun snapshot(): PointCloud = PointCloud(values.copyOf())

    private fun offset(point: PointId): Int {
        val index = point.value
        require(index in 0 until pointCount) { "point index out of range: $index" }
        return index * 3
    }
}

class MutablePriorWeights private constructor(val values: DoubleArray) {
    init {
        require(values.size % 3 == 0) { "prior weights must be a flat xyz array" }
        require(values.all { it.isFinite() && it >= 0.0 }) { "prior weights must be finite and non-negative" }
    }

    val pointCount: Int = values.size / 3

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

    fun asImmutable(): DynamicPriorWeights = DynamicPriorWeights(values.copyOf())

    private fun offset(point: PointId): Int {
        val index = point.value
        require(index in 0 until pointCount) { "point index out of range: $index" }
        return index * 3
    }

    companion object {
        fun uniform(pointCount: Int, weight: Double): MutablePriorWeights {
            require(pointCount >= 0) { "point count must be non-negative" }
            requireWeightForInteractive(weight)
            return MutablePriorWeights(DoubleArray(pointCount * 3) { weight })
        }
    }
}

class MutableSolveReport(groupCapacity: Int = 64) {
    init {
        require(groupCapacity >= 0) { "group capacity must be non-negative" }
    }

    var ok: Boolean = false
        internal set
    var totalCost: Double = Double.NaN
        internal set
    var groupCount: Int = 0
        internal set
    val groupCosts: DoubleArray = DoubleArray(groupCapacity)

    fun cost(opcode: CeresOpcode): Double =
        if (opcode.code in groupCosts.indices) groupCosts[opcode.code] else 0.0

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

interface ReusableCompiledCeresSolver : CompiledCeresSolver {
    val reusableMetricCapacity: Int

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
