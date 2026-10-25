package afield.geometry.vectorize.polyvector

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
import java.lang.foreign.ValueLayout.JAVA_FLOAT
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Where `field_polyvector` is, and whether it is there. Field runs without it; [describe] says how
 * to build it.
 *
 * ```
 * PolyvectorLibrary.describe()
 * ```
 */
object PolyvectorLibrary : NativeGeometryCapability {
    override val name = "polyvector"

    override val title = "Line-drawing vectorization"
    override val docsPage = "polyvector.md"
    override val buildTask = "buildPolyvectorNative"
    override val install: Map<String, List<String>> = mapOf(
        "macos" to listOf("xcode-select --install", "brew install cmake opencv boost eigen", "./gradlew buildPolyvectorNative"),
        "linux" to listOf(
            "sudo apt install build-essential cmake libopencv-dev libboost-dev libeigen3-dev",
            "# Fedora: sudo dnf install gcc-c++ cmake opencv-devel boost-devel eigen3-devel",
            "# Arch: sudo pacman -S base-devel cmake opencv boost eigen",
            "./gradlew buildPolyvectorNative",
        ),
        "windows" to emptyList(),
    )

    private val os = System.getProperty("os.name").lowercase()

    val fileName: String
        get() = when {
            os.contains("win") -> "field_polyvector.dll"
            os.contains("mac") || os.contains("darwin") -> "libfield_polyvector.dylib"
            else -> "libfield_polyvector.so"
        }

    /** Every path [resolvedPath] tries, in order. */
    val searchedPaths: List<Path>
        get() = listOfNotNull(
            System.getProperty("afield.polyvector.library")?.takeIf { it.isNotBlank() },
            System.getenv("AFIELD_POLYVECTOR_LIBRARY")?.takeIf { it.isNotBlank() },
        ).map(Path::of) + listOf(
            Path.of("build", "field-polyvector", "build", fileName).toAbsolutePath(),
            Path.of("build", "field-polyvector", "build", "Release", fileName).toAbsolutePath(),
        )

    /** Not cached: a library built during a live session is picked up by the next box run. */
    val resolvedPath: Path? get() = searchedPaths.firstOrNull { it.exists() }

    override val isAvailable: Boolean get() = resolvedPath != null

    val HOW_TO_BUILD: String
        get() = "Build it with `./gradlew buildPolyvectorNative` (needs cmake and a C++17 compiler, no other " +
            "dependencies), or point -Dafield.polyvector.library / AFIELD_POLYVECTOR_LIBRARY at a $fileName."

    override fun describe(): String = resolvedPath?.let {
        runCatching { "polyvector: ${PolyvectorNative.version()} at $it" }
            .getOrElse { e -> "polyvector: found $it but cannot use it: ${e.message}" }
    } ?: "polyvector: not built. $HOW_TO_BUILD"
}

/** The C ABI in `native/field-polyvector/src/field_polyvector.h`, bound with java.lang.foreign. */
internal object PolyvectorNative {
    const val ABI_VERSION = 1
    private const val ERROR_CAPACITY = 512

    private val linker: Linker = Linker.nativeLinker()

    // fpv_field_params: three doubles and an int; fpv_trace_params: double, int (padded), double, int
    private val FIELD = MemoryLayout.structLayout(JAVA_DOUBLE, JAVA_DOUBLE, JAVA_DOUBLE, JAVA_INT, MemoryLayout.paddingLayout(4))
    private val TRACE = MemoryLayout.structLayout(JAVA_DOUBLE, JAVA_INT, MemoryLayout.paddingLayout(4), JAVA_DOUBLE, JAVA_INT, MemoryLayout.paddingLayout(4))

    private val lookup: SymbolLookup by lazy {
        val path = PolyvectorLibrary.resolvedPath ?: throw UnsatisfiedLinkError(
            "field_polyvector is not built. ${PolyvectorLibrary.HOW_TO_BUILD}\nLooked at: " +
                PolyvectorLibrary.searchedPaths.joinToString(", "),
        )
        SymbolLookup.libraryLookup(path, Arena.global()).also { l ->
            val abi = linker.downcallHandle(
                l.find("fpv_abi_version").orElseThrow { UnsatisfiedLinkError("$path has no fpv_abi_version") },
                FunctionDescriptor.of(JAVA_INT),
            ).invokeExact() as Int
            if (abi != ABI_VERSION) throw UnsatisfiedLinkError(
                "$path speaks ABI $abi, Field expects $ABI_VERSION. Rebuild it with ./gradlew buildPolyvectorNative.",
            )
        }
    }

    private fun handle(name: String, descriptor: FunctionDescriptor): MethodHandle = linker.downcallHandle(
        lookup.find(name).orElseThrow {
            UnsatisfiedLinkError("${PolyvectorLibrary.fileName} has no symbol $name; rebuild it with ./gradlew buildPolyvectorNative")
        },
        descriptor,
    )

    private val versionString by lazy { handle("fpv_version_string", FunctionDescriptor.of(ADDRESS)) }
    private val field by lazy { handle("fpv_field", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT)) }
    private val free by lazy { handle("fpv_free", FunctionDescriptor.ofVoid(ADDRESS)) }
    private val trace by lazy { handle("fpv_trace", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT)) }
    private val maps by lazy { handle("fpv_field_maps", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS)) }
    private val componentCount by lazy { handle("fpv_component_count", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
    private val singularityCount by lazy { handle("fpv_singularity_count", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }
    private val singularities by lazy { handle("fpv_singularities", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)) }
    private val curveCount by lazy { handle("fpv_curve_count", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT)) }
    private val pointTotal by lazy { handle("fpv_point_total", FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT)) }
    private val curves by lazy { handle("fpv_curves", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, ADDRESS)) }
    private val timings by lazy { handle("fpv_timings", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS)) }
    private val dropped by lazy { handle("fpv_dropped_points", FunctionDescriptor.of(JAVA_INT, ADDRESS)) }

    fun version(): String = (versionString.invokeExact() as MemorySegment).reinterpret(Long.MAX_VALUE).getString(0)

    fun field(evidence: FloatArray, width: Int, height: Int, p: FrameFieldParameters): MemorySegment = Arena.ofConfined().use { a ->
        val params = a.allocate(FIELD).also { s ->
            s.set(JAVA_DOUBLE, 0, p.inkThreshold); s.set(JAVA_DOUBLE, 8, p.regularizer)
            s.set(JAVA_DOUBLE, 16, p.smoothness); s.set(JAVA_INT, 24, p.repairPasses)
        }
        val error = a.allocate(JAVA_BYTE, ERROR_CAPACITY.toLong())
        val r = field.invokeExact(a.allocateFrom(JAVA_FLOAT, *evidence), width, height, params, error, ERROR_CAPACITY) as MemorySegment
        if (r.address() == 0L) throw IllegalArgumentException("polyvector field: " + error.getString(0))
        r
    }

    fun free(r: MemorySegment) { free.invokeExact(r) }

    fun trace(r: MemorySegment, p: TraceParameters): Int = Arena.ofConfined().use { a ->
        val params = a.allocate(TRACE).also { s ->
            s.set(JAVA_DOUBLE, 0, p.pruneRatio); s.set(JAVA_INT, 8, p.maxLoopPixels)
            s.set(JAVA_DOUBLE, 16, p.simplifyTolerance); s.set(JAVA_INT, 24, if (p.smooth) 1 else 0)
        }
        val error = a.allocate(JAVA_BYTE, ERROR_CAPACITY.toLong())
        val n = trace.invokeExact(r, params, error, ERROR_CAPACITY) as Int
        if (n < 0) throw IllegalArgumentException("polyvector trace: " + error.getString(0))
        n
    }

    class Maps(val mask: ByteArray, val weight: FloatArray, val directions: FloatArray, val singularities: FloatArray, val components: Int, val fieldMs: Double)

    fun maps(r: MemorySegment, width: Int, height: Int): Maps = Arena.ofConfined().use { a ->
        val n = width.toLong() * height
        val m = a.allocate(JAVA_BYTE, n); val w = a.allocate(JAVA_FLOAT, n); val d = a.allocate(JAVA_FLOAT, 4 * n)
        maps.invokeExact(r, m, w, d)
        val s = singularityCount.invokeExact(r) as Int
        val sxy = a.allocate(JAVA_FLOAT, (2L * s).coerceAtLeast(1))
        singularities.invokeExact(r, sxy)
        val t = a.allocate(JAVA_DOUBLE, 2)
        timings.invokeExact(r, t)
        Maps(m.toArray(JAVA_BYTE), w.toArray(JAVA_FLOAT), d.toArray(JAVA_FLOAT), sxy.asSlice(0, 8L * s).toArray(JAVA_FLOAT),
            componentCount.invokeExact(r) as Int, t.getAtIndex(JAVA_DOUBLE, 0))
    }

    /** Polylines of [kind] (0 = streamlines, 1 = final curves), each as x, y floats. */
    fun polylines(r: MemorySegment, kind: Int): List<FloatArray> = Arena.ofConfined().use { a ->
        val n = curveCount.invokeExact(r, kind) as Int
        val total = pointTotal.invokeExact(r, kind) as Long
        val lengths = a.allocate(JAVA_INT, n.toLong().coerceAtLeast(1))
        val xy = a.allocate(JAVA_FLOAT, (2 * total).coerceAtLeast(1))
        curves.invokeExact(r, kind, lengths, xy)
        var o = 0L
        List(n) { i ->
            val k = lengths.getAtIndex(JAVA_INT, i.toLong())
            xy.asSlice(4 * o, 8L * k).toArray(JAVA_FLOAT).also { o += 2L * k }
        }
    }

    fun traceMs(r: MemorySegment): Double = Arena.ofConfined().use { a ->
        val t = a.allocate(JAVA_DOUBLE, 2); timings.invokeExact(r, t); t.getAtIndex(JAVA_DOUBLE, 1)
    }

    fun droppedPoints(r: MemorySegment): Int = dropped.invokeExact(r) as Int
}
