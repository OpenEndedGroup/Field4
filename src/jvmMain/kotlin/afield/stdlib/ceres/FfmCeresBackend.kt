package afield.stdlib.ceres

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Path
import kotlin.io.path.absolute

class FfmCeresBackend(
    libraryPath: Path = CeresNativeLibrary.defaultPath,
    private val reusableMetricCapacity: Int = 128,
) : CeresSolverBackend {
    private val linker: Linker = Linker.nativeLinker()
    private val lookup: SymbolLookup
    private val createHandle: MethodHandle
    private val destroyHandle: MethodHandle
    private val solveHandle: MethodHandle
    private val abiVersionHandle: MethodHandle

    override val supportedFeatures: Set<CeresFeature> = setOf(
        CeresFeature.LegacyWasmBytecode,
        CeresFeature.DotProduct,
        CeresFeature.VectorDotProduct,
        CeresFeature.NativeGeometry,
    )

    init {
        require(reusableMetricCapacity >= 0) { "reusable metric capacity must be non-negative" }
        lookup = SymbolLookup.libraryLookup(libraryPath.absolute(), Arena.global())
        abiVersionHandle = downcall(
            "ken_ceres_jvm_abi_version",
            FunctionDescriptor.of(ValueLayout.ADDRESS),
        )
        createHandle = downcall(
            "ken_ceres_solver_create",
            FunctionDescriptor.of(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
            ),
        )
        destroyHandle = downcall(
            "ken_ceres_solver_destroy",
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
        )
        solveHandle = downcall(
            "ken_ceres_solver_solve",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,
            ),
        )

        val version = (abiVersionHandle.invokeWithArguments() as MemorySegment)
            .reinterpret(64)
            .getString(0)
        require(version == "ken_ceres_jvm_abi_v1") { "unsupported Ceres JVM ABI: $version" }
    }

    override fun compile(seedPoints: PointCloud, program: CeresProgram): CompiledCeresSolver =
        FfmCompiledCeresSolver(seedPoints, program)

    private fun downcall(name: String, descriptor: FunctionDescriptor): MethodHandle {
        val symbol = lookup.find(name).orElseThrow {
            UnsatisfiedLinkError("missing native symbol $name")
        }
        return linker.downcallHandle(symbol, descriptor)
    }

    private inner class FfmCompiledCeresSolver(
        seedPoints: PointCloud,
        program: CeresProgram,
    ) : ReusableCompiledCeresSolver {
        override val pointCount: Int = seedPoints.pointCount
        override val supportedFeatures: Set<CeresFeature> = this@FfmCeresBackend.supportedFeatures
        override val programHash: UInt = program.legacyHash()
        override val reusableMetricCapacity: Int = this@FfmCeresBackend.reusableMetricCapacity

        private val handle: MemorySegment
        private val solveArena: Arena = Arena.ofConfined()
        private val pointValueCount: Int = pointCount * 3
        private val reusablePointsIn: MemorySegment = solveArena.allocate(ValueLayout.JAVA_DOUBLE, pointValueCount.toLong())
        private val reusablePointsOut: MemorySegment = solveArena.allocate(ValueLayout.JAVA_DOUBLE, pointValueCount.toLong())
        private val reusablePriors: MemorySegment = solveArena.allocate(ValueLayout.JAVA_DOUBLE, pointValueCount.toLong())
        private val reusableTotalCost: MemorySegment = solveArena.allocate(ValueLayout.JAVA_DOUBLE)
        private val reusableGroups: MemorySegment =
            if (reusableMetricCapacity > 0) solveArena.allocate(ValueLayout.JAVA_DOUBLE, reusableMetricCapacity.toLong()) else MemorySegment.NULL
        private val reusableGroupCount: MemorySegment = solveArena.allocate(ValueLayout.JAVA_INT)
        private var closed = false

        init {
            require(pointCount >= 0) { "point count must be non-negative" }
            require(supportedFeatures.containsAll(program.requiredFeatures)) {
                "FFM backend lacks required features: ${program.requiredFeatures - supportedFeatures}"
            }

            val bytecode = program.encodeLegacyBytecode()
            Arena.ofConfined().use { arena ->
                val seedPointSegment = arena.allocateFrom(ValueLayout.JAVA_DOUBLE, *seedPoints.copyValues())
                val programSegment = arena.allocateFrom(ValueLayout.JAVA_DOUBLE, *bytecode)
                handle = createHandle.invokeWithArguments(
                    seedPointSegment,
                    pointCount,
                    programSegment,
                    bytecode.size,
                    programHash.toInt(),
                ) as MemorySegment
            }
            check(handle != MemorySegment.NULL) { "native Ceres solver creation failed" }
        }

        override fun solve(
            points: PointCloud,
            dynamicPriorWeights: DynamicPriorWeights?,
            options: SolveOptions,
        ): SolveResult {
            check(!closed) { "compiled Ceres solver is closed" }
            require(points.pointCount == pointCount) { "point count changed from $pointCount to ${points.pointCount}" }
            dynamicPriorWeights?.let {
                require(it.pointCount == pointCount) { "dynamic prior point count must match points" }
            }

            Arena.ofConfined().use { arena ->
                val pointValues = points.copyValues()
                val pointsIn = arena.allocateFrom(ValueLayout.JAVA_DOUBLE, *pointValues)
                val pointsOut = arena.allocate(ValueLayout.JAVA_DOUBLE, pointValues.size.toLong())
                val priors = dynamicPriorWeights?.let {
                    arena.allocateFrom(ValueLayout.JAVA_DOUBLE, *it.copyValues())
                } ?: MemorySegment.NULL
                val totalCost = if (options.metrics) arena.allocate(ValueLayout.JAVA_DOUBLE) else MemorySegment.NULL
                val groups = if (options.metrics && options.metricGroupCapacity > 0) {
                    arena.allocate(ValueLayout.JAVA_DOUBLE, options.metricGroupCapacity.toLong())
                } else {
                    MemorySegment.NULL
                }
                val groupCount = if (options.metrics) arena.allocate(ValueLayout.JAVA_INT) else MemorySegment.NULL

                val ok = solveHandle.invokeWithArguments(
                    handle,
                    pointsIn,
                    pointCount,
                    options.maxIterations,
                    priors,
                    pointsOut,
                    totalCost,
                    groups,
                    options.metricGroupCapacity,
                    groupCount,
                ) as Int

                val solved = DoubleArray(pointValues.size) { i ->
                    pointsOut.getAtIndex(ValueLayout.JAVA_DOUBLE, i.toLong())
                }
                val metrics = if (options.metrics) {
                    val count = if (groupCount == MemorySegment.NULL) 0 else {
                        groupCount.get(ValueLayout.JAVA_INT, 0)
                    }
                    val raw = if (groups == MemorySegment.NULL || count <= 0) {
                        emptyMap()
                    } else {
                        (0 until count).associateWith { i ->
                            groups.getAtIndex(ValueLayout.JAVA_DOUBLE, i.toLong())
                        }
                    }
                    SolveMetrics(
                        totalCost = if (totalCost == MemorySegment.NULL) null else totalCost.get(ValueLayout.JAVA_DOUBLE, 0),
                        byOpcode = raw.mapNotNull { (opcode, cost) ->
                            CeresOpcode.entries.firstOrNull { it.code == opcode }?.let { it to cost }
                        }.toMap(),
                        rawByOpcode = raw,
                    )
                } else {
                    null
                }

                return SolveResult(
                    ok = ok != 0,
                    points = PointCloud(solved),
                    metrics = metrics,
                )
            }
        }

        override fun solveInPlace(
            points: MutablePointCloud,
            dynamicPriorWeights: MutablePriorWeights?,
            options: SolveOptions,
            report: MutableSolveReport?,
        ): Boolean {
            check(!closed) { "compiled Ceres solver is closed" }
            require(points.pointCount == pointCount) { "point count changed from $pointCount to ${points.pointCount}" }
            require(!options.metrics || options.metricGroupCapacity <= reusableMetricCapacity) {
                "requested ${options.metricGroupCapacity} metric groups, but reusable capacity is $reusableMetricCapacity"
            }
            dynamicPriorWeights?.let {
                require(it.pointCount == pointCount) { "dynamic prior point count must match points" }
            }
            report?.let {
                require(!options.metrics || it.groupCosts.size >= options.metricGroupCapacity) {
                    "report group capacity is smaller than requested metric capacity"
                }
            }

            putDoubles(reusablePointsIn, points.values)
            val priorSegment = if (dynamicPriorWeights == null) {
                MemorySegment.NULL
            } else {
                putDoubles(reusablePriors, dynamicPriorWeights.values)
                reusablePriors
            }
            if (options.metrics && reusableMetricCapacity > 0) {
                clearDoubles(reusableGroups, options.metricGroupCapacity)
            }

            val ok = solveHandle.invokeWithArguments(
                handle,
                reusablePointsIn,
                pointCount,
                options.maxIterations,
                priorSegment,
                reusablePointsOut,
                if (options.metrics) reusableTotalCost else MemorySegment.NULL,
                if (options.metrics && options.metricGroupCapacity > 0) reusableGroups else MemorySegment.NULL,
                options.metricGroupCapacity,
                if (options.metrics) reusableGroupCount else MemorySegment.NULL,
            ) as Int

            getDoubles(reusablePointsOut, points.values)

            report?.let {
                it.ok = ok != 0
                if (options.metrics) {
                    it.totalCost = reusableTotalCost.get(ValueLayout.JAVA_DOUBLE, 0)
                    val count = reusableGroupCount.get(ValueLayout.JAVA_INT, 0).coerceAtMost(options.metricGroupCapacity)
                    it.groupCount = count
                    val limit = minOf(count, it.groupCosts.size)
                    for (i in 0 until limit) {
                        it.groupCosts[i] = reusableGroups.getAtIndex(ValueLayout.JAVA_DOUBLE, i.toLong())
                    }
                    for (i in limit until it.groupCosts.size) {
                        it.groupCosts[i] = 0.0
                    }
                } else {
                    it.totalCost = Double.NaN
                    it.clearGroups()
                }
            }

            return ok != 0
        }

        override fun close() {
            if (!closed) {
                destroyHandle.invokeWithArguments(handle)
                solveArena.close()
                closed = true
            }
        }

        private fun putDoubles(segment: MemorySegment, values: DoubleArray) {
            for (i in values.indices) {
                segment.setAtIndex(ValueLayout.JAVA_DOUBLE, i.toLong(), values[i])
            }
        }

        private fun getDoubles(segment: MemorySegment, values: DoubleArray) {
            for (i in values.indices) {
                values[i] = segment.getAtIndex(ValueLayout.JAVA_DOUBLE, i.toLong())
            }
        }

        private fun clearDoubles(segment: MemorySegment, count: Int) {
            if (segment == MemorySegment.NULL) return
            for (i in 0 until count) {
                segment.setAtIndex(ValueLayout.JAVA_DOUBLE, i.toLong(), 0.0)
            }
        }
    }
}

object CeresNativeLibrary {
    val defaultPath: Path
        get() = System.getProperty("afield.ceres.library")
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?: System.getenv("AFIELD_CERES_LIBRARY")
                ?.takeIf { it.isNotBlank() }
                ?.let(Path::of)
            ?: Path.of("lib", "libken_ceres_jvm.dylib")
}
