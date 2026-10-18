package afield.geometry.topology.morsesmale

import afield.geometry.network.NativeGeometryCapability
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_FLOAT
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Where `field_morse_smale` is, and whether it is there. Field runs without it; [describe] says how
 * to build it.
 *
 * ```
 * MorseSmaleLibrary.describe()
 * ```
 */
object MorseSmaleLibrary : NativeGeometryCapability {
    override val name = "morseSmale"

    override val title = "Morse–Smale complexes"
    override val docsPage = "morse_smale.md"
    override val buildTask = "buildMorseSmaleNative"
    override val install: Map<String, List<String>> = mapOf(
        "macos" to listOf("xcode-select --install", "brew install cmake", "./gradlew buildMorseSmaleNative"),
        "linux" to listOf("sudo apt install build-essential cmake    # or dnf / pacman equivalents", "./gradlew buildMorseSmaleNative"),
        "windows" to listOf("winget install -e --id Kitware.CMake    # and Visual Studio C++ Build Tools", ".\\gradlew buildMorseSmaleNative"),
    )

    private val os = System.getProperty("os.name").lowercase()

    val fileName: String
        get() = when {
            os.contains("win") -> "field_morse_smale.dll"
            os.contains("mac") || os.contains("darwin") -> "libfield_morse_smale.dylib"
            else -> "libfield_morse_smale.so"
        }

    /** Every path [resolvedPath] tries, in order. */
    val searchedPaths: List<Path>
        get() = listOfNotNull(
            System.getProperty("afield.morsesmale.library")?.takeIf { it.isNotBlank() },
            System.getenv("AFIELD_MORSE_SMALE_LIBRARY")?.takeIf { it.isNotBlank() },
        ).map(Path::of) + listOf(
            Path.of("build", "field-morse-smale", "build", fileName).toAbsolutePath(),
            Path.of("build", "field-morse-smale", "build", "Release", fileName).toAbsolutePath(),
        )

    /** Not cached: a library built during a live session is picked up by the next box run. */
    val resolvedPath: Path? get() = searchedPaths.firstOrNull { it.exists() }

    override val isAvailable: Boolean get() = resolvedPath != null

    val HOW_TO_BUILD: String
        get() = "Build it with `./gradlew buildMorseSmaleNative` (needs cmake and a C++17 compiler, no other " +
            "dependencies), or point -Dafield.morsesmale.library / AFIELD_MORSE_SMALE_LIBRARY at a $fileName."

    override fun describe(): String = resolvedPath?.let {
        runCatching { "morseSmale: ${MorseSmaleNative.version()} at $it" }
            .getOrElse { e -> "morseSmale: found $it but cannot use it: ${e.message}" }
    } ?: "morseSmale: not built. $HOW_TO_BUILD"
}

/** The C ABI in `native/field-morse-smale/src/field_morse_smale.h`, bound with java.lang.foreign. */
internal object MorseSmaleNative {
    const val ABI_VERSION = 2
    private const val ERROR_CAPACITY = 512

    private val linker: Linker = Linker.nativeLinker()

    private val lookup: SymbolLookup by lazy {
        val path = MorseSmaleLibrary.resolvedPath ?: throw UnsatisfiedLinkError(
            "field_morse_smale is not built. ${MorseSmaleLibrary.HOW_TO_BUILD}\nLooked at: " +
                MorseSmaleLibrary.searchedPaths.joinToString(", "),
        )
        SymbolLookup.libraryLookup(path, Arena.global()).also { l ->
            val abi = linker.downcallHandle(
                l.find("fms_abi_version").orElseThrow { UnsatisfiedLinkError("$path has no fms_abi_version") },
                FunctionDescriptor.of(JAVA_INT),
            ).invokeExact() as Int
            if (abi != ABI_VERSION) throw UnsatisfiedLinkError(
                "$path speaks ABI $abi, Field expects $ABI_VERSION. Rebuild it with ./gradlew buildMorseSmaleNative.",
            )
        }
    }

    private fun handle(name: String, descriptor: FunctionDescriptor): MethodHandle = linker.downcallHandle(
        lookup.find(name).orElseThrow {
            UnsatisfiedLinkError("${MorseSmaleLibrary.fileName} has no symbol $name; rebuild it with ./gradlew buildMorseSmaleNative")
        },
        descriptor,
    )

    private val versionString by lazy { handle("fms_version_string", FunctionDescriptor.of(ADDRESS)) }
    private val build by lazy {
        handle("fms_build", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT))
    }
    private val free by lazy { handle("fms_free", FunctionDescriptor.ofVoid(ADDRESS)) }
    private val timings by lazy { handle("fms_timings", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)) }
    private val criticalCount by lazy { handle("fms_critical_count", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
    private val criticalPoints by lazy {
        handle("fms_critical_points", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS))
    }
    private val cancellationCount by lazy { handle("fms_cancellation_count", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
    private val cancellations by lazy { handle("fms_cancellations", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS)) }
    private val pairCount by lazy { handle("fms_pair_count", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
    private val pairs by lazy { handle("fms_pairs", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS)) }
    private val simplify by lazy { handle("fms_simplify", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_DOUBLE, ADDRESS, JAVA_INT)) }
    private val freeView by lazy { handle("fms_free_view", FunctionDescriptor.ofVoid(ADDRESS)) }
    private val viewNodeCount by lazy { handle("fms_view_node_count", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
    private val viewNodes by lazy { handle("fms_view_nodes", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)) }
    private val viewArcCount by lazy { handle("fms_view_arc_count", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
    private val viewPathTotal by lazy { handle("fms_view_arc_path_total", FunctionDescriptor.of(JAVA_LONG, ADDRESS)) }
    private val viewArcs by lazy { handle("fms_view_arcs", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS)) }
    private val viewPaths by lazy { handle("fms_view_arc_paths", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)) }
    private val viewSegmentation by lazy { handle("fms_view_segmentation", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS)) }

    fun version(): String = (versionString.invokeExact() as MemorySegment).reinterpret(Long.MAX_VALUE).getString(0)

    /** Builds a complex; the returned address must be passed to [free] exactly once. */
    fun build(values: FloatArray, offset: Int, width: Int, height: Int, pixelStride: Int, rowStride: Int): MemorySegment =
        Arena.ofConfined().use { arena ->
            val data = arena.allocate(JAVA_FLOAT, (values.size - offset).toLong())
            MemorySegment.copy(values, offset, data, JAVA_FLOAT, 0, values.size - offset)
            val error = arena.allocate(JAVA_BYTE, ERROR_CAPACITY.toLong())
            val c = build.invokeExact(data, width, height, pixelStride, rowStride, error, ERROR_CAPACITY) as MemorySegment
            if (c.address() == 0L) throw IllegalArgumentException("Morse–Smale: " + error.getString(0))
            c
        }

    fun free(c: MemorySegment) { free.invokeExact(c) }

    class Base(
        val cell: IntArray, val type: IntArray, val value: FloatArray, val cancelledAt: DoubleArray, val xy: FloatArray,
        val cancelSaddle: IntArray, val cancelExtremum: IntArray, val cancelPersistence: DoubleArray,
        val pairBirth: IntArray, val pairDeath: IntArray, val pairDimension: IntArray, val pairPersistence: DoubleArray,
        val timings: DoubleArray,
    )

    fun read(c: MemorySegment): Base = Arena.ofConfined().use { a ->
        val n = criticalCount.invokeExact(c) as Int
        val cell = a.allocate(JAVA_INT, n.toLong()); val type = a.allocate(JAVA_INT, n.toLong())
        val value = a.allocate(JAVA_FLOAT, n.toLong()); val at = a.allocate(JAVA_DOUBLE, n.toLong())
        val xy = a.allocate(JAVA_FLOAT, 2L * n)
        criticalPoints.invokeExact(c, cell, type, value, at, xy)
        val k = cancellationCount.invokeExact(c) as Int
        val cs = a.allocate(JAVA_INT, k.toLong()); val ce = a.allocate(JAVA_INT, k.toLong()); val cp = a.allocate(JAVA_DOUBLE, k.toLong())
        cancellations.invokeExact(c, cs, ce, cp)
        val p = pairCount.invokeExact(c) as Int
        val pb = a.allocate(JAVA_INT, p.toLong()); val pd = a.allocate(JAVA_INT, p.toLong())
        val pk = a.allocate(JAVA_INT, p.toLong()); val pp = a.allocate(JAVA_DOUBLE, p.toLong())
        pairs.invokeExact(c, pb, pd, pk, pp)
        val t = a.allocate(JAVA_DOUBLE, 4)
        timings.invokeExact(c, t)
        Base(
            cell.toArray(JAVA_INT), type.toArray(JAVA_INT), value.toArray(JAVA_FLOAT), at.toArray(JAVA_DOUBLE), xy.toArray(JAVA_FLOAT),
            cs.toArray(JAVA_INT), ce.toArray(JAVA_INT), cp.toArray(JAVA_DOUBLE),
            pb.toArray(JAVA_INT), pd.toArray(JAVA_INT), pk.toArray(JAVA_INT), pp.toArray(JAVA_DOUBLE),
            t.toArray(JAVA_DOUBLE),
        )
    }

    class View(
        val nodes: IntArray, val arcSaddle: IntArray, val arcExtremum: IntArray, val arcKind: IntArray,
        val arcLength: IntArray, val paths: FloatArray, val basin: IntArray, val peak: IntArray,
    )

    /** Simplifies, copies the view out, and frees it before returning. */
    fun simplify(c: MemorySegment, threshold: Double, gridSize: Int): View = Arena.ofConfined().use { a ->
        val error = a.allocate(JAVA_BYTE, ERROR_CAPACITY.toLong())
        val v = simplify.invokeExact(c, threshold, error, ERROR_CAPACITY) as MemorySegment
        if (v.address() == 0L) throw IllegalArgumentException("Morse–Smale simplify: " + error.getString(0))
        try {
            val n = viewNodeCount.invokeExact(v) as Int
            val nodes = a.allocate(JAVA_INT, n.toLong())
            viewNodes.invokeExact(v, nodes)
            val m = viewArcCount.invokeExact(v) as Int
            val total = viewPathTotal.invokeExact(v) as Long
            val s = a.allocate(JAVA_INT, m.toLong()); val e = a.allocate(JAVA_INT, m.toLong())
            val k = a.allocate(JAVA_INT, m.toLong()); val l = a.allocate(JAVA_INT, m.toLong())
            viewArcs.invokeExact(v, s, e, k, l)
            val xy = a.allocate(JAVA_FLOAT, 2 * total)
            viewPaths.invokeExact(v, xy)
            val basin = a.allocate(JAVA_INT, gridSize.toLong()); val peak = a.allocate(JAVA_INT, gridSize.toLong())
            viewSegmentation.invokeExact(v, basin, peak)
            View(
                nodes.toArray(JAVA_INT), s.toArray(JAVA_INT), e.toArray(JAVA_INT), k.toArray(JAVA_INT), l.toArray(JAVA_INT),
                xy.toArray(JAVA_FLOAT), basin.toArray(JAVA_INT), peak.toArray(JAVA_INT),
            )
        } finally {
            freeView.invokeExact(v)
        }
    }
}
