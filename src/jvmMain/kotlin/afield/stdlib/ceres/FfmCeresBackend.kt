@file:afield.CompletionCategory("Internal")

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

    /** Null on a v1 wrapper. Present means solver options and a solver summary are available. */
    private val solve2Handle: MethodHandle?

    /** Null on a v1 wrapper. Present means build errors are reportable instead of silent. */
    private val lastErrorHandle: MethodHandle?

    /** The `ken_ceres_jvm_abi_v*` string the loaded library reports. */
    val abiVersion: String

    override val supportedFeatures: Set<CeresFeature>

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

        abiVersion = (abiVersionHandle.invokeWithArguments() as MemorySegment)
            .reinterpret(64)
            .getString(0)
        require(abiVersion in SUPPORTED_ABIS) {
            "unsupported Ceres JVM ABI: $abiVersion (this build understands ${SUPPORTED_ABIS.joinToString()})"
        }

        // v2 is detected by symbol, not by trusting the version string: a half-built library that
        // reports v2 but is missing an entry point should fail here, not at the first solve.
        val v2 = abiVersion == ABI_V2
        solve2Handle = if (!v2) null else downcall(
            "ken_ceres_solver_solve2",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,   // handle
                ValueLayout.ADDRESS,   // points_in
                ValueLayout.JAVA_INT,  // num_points
                ValueLayout.ADDRESS,   // options
                ValueLayout.JAVA_INT,  // options_len
                ValueLayout.ADDRESS,   // prior_w_xyz
                ValueLayout.ADDRESS,   // points_out
                ValueLayout.ADDRESS,   // total_cost_out
                ValueLayout.ADDRESS,   // group_costs_out
                ValueLayout.JAVA_INT,  // group_costs_cap
                ValueLayout.ADDRESS,   // out_group_count
                ValueLayout.ADDRESS,   // summary_out
                ValueLayout.JAVA_INT,  // summary_cap
            ),
        )
        lastErrorHandle = if (!v2) null else downcall(
            "ken_ceres_solver_last_error",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        )

        supportedFeatures = buildSet {
            add(CeresFeature.LegacyWasmBytecode)
            add(CeresFeature.DotProduct)
            add(CeresFeature.VectorDotProduct)
            add(CeresFeature.NativeGeometry)
            if (v2) add(CeresFeature.AbiV2)
        }
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

            // A v1 wrapper stops at the first opcode it does not recognise and reports nothing, so
            // the problem it built silently omits every constraint after that point. v2 records
            // where it stopped; refuse the solver rather than hand back one that answers a
            // different question.
            lastErrorHandle?.let { errorHandle ->
                val message = (errorHandle.invokeWithArguments(handle) as MemorySegment)
                    .reinterpret(512)
                    .getString(0)
                if (message.isNotEmpty()) {
                    destroyHandle.invokeWithArguments(handle)
                    throw IllegalArgumentException("Ceres program was not fully built: $message")
                }
            }
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

                require(!(options.needsAbiV2 && solve2Handle == null)) {
                    "these SolveOptions need $ABI_V2 but the loaded library reports $abiVersion"
                }
                val wantSummary = options.summary && solve2Handle != null
                val summaryOut = if (wantSummary) {
                    arena.allocate(ValueLayout.JAVA_DOUBLE, CeresSolveSummary.SLOTS.toLong())
                } else {
                    MemorySegment.NULL
                }

                val ok = if (solve2Handle == null) {
                    solveHandle.invokeWithArguments(
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
                } else {
                    val encoded = options.encode()
                    solve2Handle.invokeWithArguments(
                        handle,
                        pointsIn,
                        pointCount,
                        arena.allocateFrom(ValueLayout.JAVA_DOUBLE, *encoded),
                        encoded.size,
                        priors,
                        pointsOut,
                        totalCost,
                        groups,
                        options.metricGroupCapacity,
                        groupCount,
                        summaryOut,
                        if (wantSummary) CeresSolveSummary.SLOTS else 0,
                    ) as Int
                }

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

                val summary = if (!wantSummary) null else {
                    CeresSolveSummary.decode(
                        DoubleArray(CeresSolveSummary.SLOTS) { i ->
                            summaryOut.getAtIndex(ValueLayout.JAVA_DOUBLE, i.toLong())
                        },
                    )
                }

                return SolveResult(
                    ok = ok != 0,
                    points = PointCloud(solved),
                    metrics = metrics,
                    summary = summary,
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
            // The allocation-free path deliberately stays on the v1 entry point: its whole purpose
            // is a steady per-frame solve with no arena churn, and the options that need v2 (a
            // different linear solver, threads, a summary) are setup-time choices, not per-frame
            // ones. Ask for them here and you get a clear refusal rather than silence.
            require(!options.needsAbiV2) {
                "solveInPlace runs the fixed-configuration solve; use solve() for these SolveOptions"
            }
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

private const val ABI_V1 = "ken_ceres_jvm_abi_v1"
private const val ABI_V2 = "ken_ceres_jvm_abi_v2"
private val SUPPORTED_ABIS = setOf(ABI_V1, ABI_V2)

object CeresNativeLibrary {
    val defaultPath: Path
        get() = System.getProperty("afield.ceres.library")
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?: System.getenv("AFIELD_CERES_LIBRARY")
                ?.takeIf { it.isNotBlank() }
                ?.let(Path::of)
            ?: Path.of("libs", "libken_ceres_jvm.dylib")
}
