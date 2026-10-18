package afield.geometry.curves.principal

import afield.geometry.network.NativeGeometryCapability
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Where `field_principal_curves` is, and whether it is there. Field runs without it; [describe] says how
 * to build it.
 *
 * ```
 * PrincipalCurvesLibrary.describe()
 * ```
 */
object PrincipalCurvesLibrary : NativeGeometryCapability {
    override val name = "principalCurves"

    private val os = System.getProperty("os.name").lowercase()

    val fileName: String
        get() = when {
            os.contains("win") -> "field_principal_curves.dll"
            os.contains("mac") || os.contains("darwin") -> "libfield_principal_curves.dylib"
            else -> "libfield_principal_curves.so"
        }

    /** Every path [resolvedPath] tries, in order. */
    val searchedPaths: List<Path>
        get() = listOfNotNull(
            System.getProperty("afield.principalcurves.library")?.takeIf { it.isNotBlank() },
            System.getenv("AFIELD_PRINCIPAL_CURVES_LIBRARY")?.takeIf { it.isNotBlank() },
        ).map(Path::of) + listOf(
            Path.of("build", "field-principal-curves", "build", fileName).toAbsolutePath(),
            Path.of("build", "field-principal-curves", "build", "Release", fileName).toAbsolutePath(),
        )

    /** Not cached: a library built during a live session is picked up by the next box run. */
    val resolvedPath: Path? get() = searchedPaths.firstOrNull { it.exists() }

    override val isAvailable: Boolean get() = resolvedPath != null

    val HOW_TO_BUILD: String
        get() = "Build it with `./gradlew buildPrincipalCurvesNative` (needs cmake and a C++17 compiler, no other " +
            "dependencies), or point -Dafield.principalcurves.library / AFIELD_PRINCIPAL_CURVES_LIBRARY at a $fileName."

    override fun describe(): String = resolvedPath?.let {
        runCatching { "principalCurves: ${PrincipalCurvesNative.version()} at $it" }
            .getOrElse { e -> "principalCurves: found $it but cannot use it: ${e.message}" }
    } ?: "principalCurves: not built. $HOW_TO_BUILD"
}

/** The C ABI in `native/field-principal-curves/src/field_principal_curves.h`, bound with java.lang.foreign. */
internal object PrincipalCurvesNative {
    const val ABI_VERSION = 2
    private const val ERROR_CAPACITY = 512
    const val BISECT = 1
    const val EXTEND = 2
    const val BRANCH = 4

    private val linker: Linker = Linker.nativeLinker()

    // fpc_params: four doubles, an int (padded), a double, two ints, a uint64
    private val PARAMS = MemoryLayout.structLayout(
        JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_INT, MemoryLayout.paddingLayout(4),
        JAVA_DOUBLE, JAVA_INT, JAVA_INT, JAVA_LONG,
    )

    private val lookup: SymbolLookup by lazy {
        val path = PrincipalCurvesLibrary.resolvedPath ?: throw UnsatisfiedLinkError(
            "field_principal_curves is not built. ${PrincipalCurvesLibrary.HOW_TO_BUILD}\nLooked at: " +
                PrincipalCurvesLibrary.searchedPaths.joinToString(", "),
        )
        SymbolLookup.libraryLookup(path, Arena.global()).also { l ->
            val abi = linker.downcallHandle(
                l.find("fpc_abi_version").orElseThrow { UnsatisfiedLinkError("$path has no fpc_abi_version") },
                FunctionDescriptor.of(JAVA_INT),
            ).invokeExact() as Int
            if (abi != ABI_VERSION) throw UnsatisfiedLinkError(
                "$path speaks ABI $abi, Field expects $ABI_VERSION. Rebuild it with ./gradlew buildPrincipalCurvesNative.",
            )
        }
    }

    private fun handle(name: String, descriptor: FunctionDescriptor): MethodHandle = linker.downcallHandle(
        lookup.find(name).orElseThrow {
            UnsatisfiedLinkError("${PrincipalCurvesLibrary.fileName} has no symbol $name; rebuild it with ./gradlew buildPrincipalCurvesNative")
        },
        descriptor,
    )

    private val E = arrayOf(ADDRESS, JAVA_INT)  // error, capacity
    private fun withError(vararg args: java.lang.foreign.MemoryLayout) = (args.toList() + E).toTypedArray()

    private val versionString by lazy { handle("fpc_version_string", FunctionDescriptor.of(ADDRESS)) }
    private val create by lazy { handle("fpc_create", FunctionDescriptor.of(ADDRESS, *withError(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS))) }
    private val setPoints by lazy { handle("fpc_set_points", FunctionDescriptor.of(JAVA_INT, *withError(ADDRESS, ADDRESS, ADDRESS, JAVA_INT))) }
    private val free by lazy { handle("fpc_free", FunctionDescriptor.ofVoid(ADDRESS)) }
    private val setParams by lazy { handle("fpc_set_params", FunctionDescriptor.of(JAVA_INT, *withError(ADDRESS, ADDRESS))) }
    private val setGraph by lazy { handle("fpc_set_graph", FunctionDescriptor.of(JAVA_INT, *withError(ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS))) }
    private val initComponents by lazy { handle("fpc_init_components", FunctionDescriptor.of(JAVA_INT, *withError(ADDRESS, JAVA_INT, JAVA_INT))) }
    private val initTree by lazy { handle("fpc_init_tree", FunctionDescriptor.of(JAVA_INT, *withError(ADDRESS, JAVA_INT))) }
    private val setNode by lazy { handle("fpc_set_node", FunctionDescriptor.of(JAVA_INT, *withError(ADDRESS, JAVA_INT, ADDRESS, JAVA_INT))) }
    private val splitEdge by lazy { handle("fpc_split_edge", FunctionDescriptor.of(JAVA_INT, *withError(ADDRESS, JAVA_INT, JAVA_DOUBLE))) }
    private val fit by lazy { handle("fpc_fit", FunctionDescriptor.of(JAVA_INT, *withError(ADDRESS, JAVA_INT))) }
    private val grow by lazy { handle("fpc_grow", FunctionDescriptor.of(JAVA_INT, *withError(ADDRESS, JAVA_INT, JAVA_INT))) }
    private val nodeCount by lazy { handle("fpc_node_count", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
    private val edgeCount by lazy { handle("fpc_edge_count", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
    private val nodes by lazy { handle("fpc_nodes", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)) }
    private val edges by lazy { handle("fpc_edges", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)) }
    private val energy by lazy { handle("fpc_energy", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)) }
    private val pointCount by lazy { handle("fpc_point_count", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
    private val assignments by lazy { handle("fpc_assignments", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS)) }
    private val nodeStats by lazy {
        handle("fpc_node_stats", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
    }

    fun version(): String = (versionString.invokeExact() as MemorySegment).reinterpret(Long.MAX_VALUE).getString(0)

    private fun params(a: Arena, p: ElasticParameters): MemorySegment = a.allocate(PARAMS).also { s ->
        s.set(JAVA_DOUBLE, 0, p.lambda); s.set(JAVA_DOUBLE, 8, p.mu); s.set(JAVA_DOUBLE, 16, p.trimmingRadius)
        s.set(JAVA_DOUBLE, 24, p.branchPenalty); s.set(JAVA_INT, 32, p.maxIterations); s.set(JAVA_DOUBLE, 40, p.eps)
        s.set(JAVA_INT, 48, p.candidateSample); s.set(JAVA_INT, 52, p.threads); s.set(JAVA_LONG, 56, p.seed)
    }

    private inline fun <T> call(what: String, body: (Arena, MemorySegment) -> T): T = Arena.ofConfined().use { a ->
        val error = a.allocate(JAVA_BYTE, ERROR_CAPACITY.toLong())
        body(a, error)
    }

    private fun check(status: Int, what: String, error: MemorySegment): Int {
        if (status < 0) throw IllegalArgumentException("principal curves $what: " + error.getString(0))
        return status
    }

    fun create(dimension: Int, points: DoubleArray, weights: DoubleArray?, p: ElasticParameters): MemorySegment = call("create") { a, e ->
        val n = points.size / dimension
        val pts = a.allocateFrom(JAVA_DOUBLE, *points)
        val w = weights?.let { a.allocateFrom(JAVA_DOUBLE, *it) } ?: MemorySegment.NULL
        val f = create.invokeExact(dimension, pts, w, n, params(a, p), e, ERROR_CAPACITY) as MemorySegment
        if (f.address() == 0L) throw IllegalArgumentException("principal curves: " + e.getString(0))
        f
    }

    fun free(f: MemorySegment) { free.invokeExact(f) }

    fun setPoints(f: MemorySegment, dimension: Int, points: DoubleArray, weights: DoubleArray?) = call("points") { a, e ->
        val w = weights?.let { a.allocateFrom(JAVA_DOUBLE, *it) } ?: MemorySegment.NULL
        check(setPoints.invokeExact(f, a.allocateFrom(JAVA_DOUBLE, *points), w, points.size / dimension, e, ERROR_CAPACITY) as Int, "points", e)
    }

    fun setParams(f: MemorySegment, p: ElasticParameters) = call("parameters") { a, e ->
        check(setParams.invokeExact(f, params(a, p), e, ERROR_CAPACITY) as Int, "parameters", e)
    }

    fun setGraph(f: MemorySegment, nodePositions: DoubleArray, k: Int, edgeList: IntArray) = call("graph") { a, e ->
        check(setGraph.invokeExact(f, k, a.allocateFrom(JAVA_DOUBLE, *nodePositions), edgeList.size / 2,
            if (edgeList.isEmpty()) MemorySegment.NULL else a.allocateFrom(JAVA_INT, *edgeList), e, ERROR_CAPACITY) as Int, "graph", e)
    }

    fun initComponents(f: MemorySegment, components: Int, nodesEach: Int) = call("init") { _, e ->
        check(initComponents.invokeExact(f, components, nodesEach, e, ERROR_CAPACITY) as Int, "initialisation", e)
    }

    fun initTree(f: MemorySegment, k: Int) = call("init") { _, e ->
        check(initTree.invokeExact(f, k, e, ERROR_CAPACITY) as Int, "initialisation", e)
    }

    fun setNode(f: MemorySegment, node: Int, position: DoubleArray?, fixed: Boolean) = call("node") { a, e ->
        check(setNode.invokeExact(f, node, position?.let { a.allocateFrom(JAVA_DOUBLE, *it) } ?: MemorySegment.NULL,
            if (fixed) 1 else 0, e, ERROR_CAPACITY) as Int, "node edit", e)
    }

    fun splitEdge(f: MemorySegment, edge: Int, t: Double): Int = call("split") { _, e ->
        check(splitEdge.invokeExact(f, edge, t, e, ERROR_CAPACITY) as Int, "split", e)
    }

    fun fit(f: MemorySegment, iterations: Int): Int = call("fit") { _, e ->
        check(fit.invokeExact(f, iterations, e, ERROR_CAPACITY) as Int, "fit", e)
    }

    fun grow(f: MemorySegment, target: Int, grammar: Int): Int = call("grow") { _, e ->
        check(grow.invokeExact(f, target, grammar, e, ERROR_CAPACITY) as Int, "grow", e)
    }

    class GraphState(val positions: DoubleArray, val fixed: IntArray, val edges: IntArray)

    fun graph(f: MemorySegment, dimension: Int): GraphState = Arena.ofConfined().use { a ->
        val k = nodeCount.invokeExact(f) as Int
        val m = edgeCount.invokeExact(f) as Int
        val pos = a.allocate(JAVA_DOUBLE, (k.toLong() * dimension).coerceAtLeast(1))
        val fx = a.allocate(JAVA_INT, k.toLong().coerceAtLeast(1))
        val ed = a.allocate(JAVA_INT, (2L * m).coerceAtLeast(1))
        nodes.invokeExact(f, pos, fx)
        edges.invokeExact(f, ed)
        GraphState(
            pos.asSlice(0, 8L * k * dimension).toArray(JAVA_DOUBLE),
            fx.asSlice(0, 4L * k).toArray(JAVA_INT),
            ed.asSlice(0, 8L * m).toArray(JAVA_INT),
        )
    }

    fun energy(f: MemorySegment): DoubleArray = Arena.ofConfined().use { a ->
        val out = a.allocate(JAVA_DOUBLE, 5)
        energy.invokeExact(f, out)
        out.toArray(JAVA_DOUBLE)
    }

    class Assign(val node: IntArray, val edge: IntArray, val t: DoubleArray, val distance: DoubleArray)

    fun assignments(f: MemorySegment): Assign = Arena.ofConfined().use { a ->
        val n = (pointCount.invokeExact(f) as Int).toLong()
        val nd = a.allocate(JAVA_INT, n); val ed = a.allocate(JAVA_INT, n)
        val t = a.allocate(JAVA_DOUBLE, n); val d = a.allocate(JAVA_DOUBLE, n)
        assignments.invokeExact(f, nd, ed, t, d)
        Assign(nd.toArray(JAVA_INT), ed.toArray(JAVA_INT), t.toArray(JAVA_DOUBLE), d.toArray(JAVA_DOUBLE))
    }

    class Stats(
        val support: DoubleArray, val nEff: DoubleArray, val residual: DoubleArray, val spread: DoubleArray,
        val standardError: DoubleArray, val tangent: DoubleArray, val basis: DoubleArray,
    )

    fun nodeStats(f: MemorySegment, dimension: Int): Stats = Arena.ofConfined().use { a ->
        val k = (nodeCount.invokeExact(f) as Int).toLong().coerceAtLeast(1)
        val spreadWidth = if (dimension == 2) 1 else 3
        val su = a.allocate(JAVA_DOUBLE, k); val ne = a.allocate(JAVA_DOUBLE, k); val re = a.allocate(JAVA_DOUBLE, k)
        val sp = a.allocate(JAVA_DOUBLE, k * spreadWidth); val se = a.allocate(JAVA_DOUBLE, k)
        val ta = a.allocate(JAVA_DOUBLE, k * dimension); val ba = a.allocate(JAVA_DOUBLE, k * 6)
        nodeStats.invokeExact(f, su, ne, re, sp, se, ta, ba)
        Stats(su.toArray(JAVA_DOUBLE), ne.toArray(JAVA_DOUBLE), re.toArray(JAVA_DOUBLE), sp.toArray(JAVA_DOUBLE),
            se.toArray(JAVA_DOUBLE), ta.toArray(JAVA_DOUBLE), if (dimension == 3) ba.toArray(JAVA_DOUBLE) else DoubleArray(0))
    }
}
