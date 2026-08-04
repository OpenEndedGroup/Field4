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

    /** The options-and-summary solve. `solveHandle` is the fixed-configuration one `solveInPlace` uses. */
    private val solve2Handle: MethodHandle

    /** Why a program failed to build, instead of a silent refusal. */
    private val lastErrorHandle: MethodHandle

    /** Reads an expression's free variables back after a solve. */
    private val variablesHandle: MethodHandle

    /** The `ken_ceres_jvm_abi_v*` string the loaded library reports. */
    val abiVersion: String

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
        require(abiVersion == ABI_V3) {
            "this Ceres wrapper is $abiVersion; Field needs $ABI_V3. " +
            "Build one with ./gradlew buildCeresRedistributable"
        }

        // Every entry point is looked up eagerly rather than on first use: a library that reports
        // the right version but is missing a symbol should fail here, not mid-solve.
        solve2Handle = downcall(
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
        lastErrorHandle = downcall(
            "ken_ceres_solver_last_error",
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
        )
        variablesHandle = downcall(
            "ken_ceres_solver_variables",
            FunctionDescriptor.of(
                ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS,   // handle
                ValueLayout.ADDRESS,   // out
                ValueLayout.JAVA_INT,  // cap
            ),
        )
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
        private val program: CeresProgram,
    ) : ReusableCompiledCeresSolver {
        override val pointCount: Int = seedPoints.pointCount
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
            lastErrorHandle.let { errorHandle ->
                val message = (errorHandle.invokeWithArguments(handle) as MemorySegment)
                    .reinterpret(512)
                    .getString(0)
                if (message.isNotEmpty()) {
                    destroyHandle.invokeWithArguments(handle)
                    throw IllegalArgumentException("Ceres program was not fully built: $message")
                }
            }

            // Two independent computations of the free-variable layout — this side walks the
            // declarations, the native side counts the parameter blocks it actually registered — and
            // they have to agree, or every solved value read back would be attributed to the wrong
            // name. Skipped when a RawProgramFragment is present, since that can legitimately declare
            // a variable this layer cannot see.
            if (program.constraints.none { it is RawProgramFragment }) {
                val nativeCount = variablesHandle.invokeWithArguments(handle, MemorySegment.NULL, 0) as Int
                if (nativeCount != program.variableComponentCount) {
                    destroyHandle.invokeWithArguments(handle)
                    throw IllegalStateException(
                        "free-variable layout disagrees: this program declares " +
                            "${program.variableComponentCount} component(s), the compiled problem holds " +
                            "$nativeCount",
                    )
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

                val wantSummary = options.summary
                val summaryOut = if (wantSummary) {
                    arena.allocate(ValueLayout.JAVA_DOUBLE, CeresSolveSummary.SLOTS.toLong())
                } else {
                    MemorySegment.NULL
                }

                val encoded = options.encode()
                val ok = solve2Handle.invokeWithArguments(
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
                    variables = readVariables(arena),
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
            // The allocation-free path deliberately stays on the fixed-configuration entry point:
            // its whole purpose is a steady per-frame solve with no arena churn, and the options
            // that need the configurable one (a different linear solver, threads, a summary) are
            // setup-time choices, not per-frame ones. Ask for them here and you get a clear refusal.
            require(!options.needsConfigurableSolve) {
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

        /**
         * The solved free variables. They live in the compiled problem rather than in the point
         * array, so they are a separate read — which also means they are warm-started across solves,
         * exactly as `PLANAR`'s planes have always been.
         */
        private fun readVariables(arena: Arena): CeresVariableValues {
            val count = variablesHandle.invokeWithArguments(handle, MemorySegment.NULL, 0) as Int
            if (count <= 0) return CeresVariableValues.EMPTY
            val out = arena.allocate(ValueLayout.JAVA_DOUBLE, count.toLong())
            val written = variablesHandle.invokeWithArguments(handle, out, count) as Int
            val flat = DoubleArray(minOf(count, written)) { i ->
                out.getAtIndex(ValueLayout.JAVA_DOUBLE, i.toLong())
            }
            return CeresVariableValues.of(program, flat)
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

private const val ABI_V3 = "ken_ceres_jvm_abi_v3"

/**
 * Where to find the native wrapper.
 *
 * Nothing is committed: the solver is an optional extra, and a self-contained build is 8 MB that most
 * of Field never touches. So the default is wherever `buildCeresRedistributable` puts its output, and
 * a fresh clone has no library until someone runs that once. `afield.ceres.library` or
 * `AFIELD_CERES_LIBRARY` override it — that is also how you point at the faster, Homebrew-linked
 * `buildCeresNative` build while iterating on the wrapper.
 */
object CeresNativeLibrary {
    private val isWindows: Boolean
        get() = System.getProperty("os.name").lowercase().contains("win")

    /**
     * The three names `tools/build_ceres_native.py` can produce, chosen the same way it chooses.
     *
     * The platform is read at call time rather than baked in, because this is also how a
     * cross-built artifact gets named on a machine that is not the one that built it.
     */
    private val libraryFileName: String
        get() {
            val os = System.getProperty("os.name").lowercase()
            return when {
                os.contains("win") -> "ken_ceres_jvm.dll"
                os.contains("mac") || os.contains("darwin") -> "libken_ceres_jvm.dylib"
                else -> "libken_ceres_jvm.so"
            }
        }

    val defaultPath: Path
        get() = System.getProperty("afield.ceres.library")
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
            ?: System.getenv("AFIELD_CERES_LIBRARY")
                ?.takeIf { it.isNotBlank() }
                ?.let(Path::of)
            ?: Path.of("build", "ken-ceres-redistributable", libraryFileName)

    /** What to tell someone whose [defaultPath] is not there, in the shell they are actually in. */
    val HOW_TO_BUILD: String
        get() = if (isWindows) "gradlew.bat buildCeresRedistributable" else "./gradlew buildCeresRedistributable"
}
