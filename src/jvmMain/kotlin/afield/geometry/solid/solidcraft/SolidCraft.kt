@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.solid.solidcraft

import afield.geometry.mesh.TriMesh
import afield.runtime.NativeArea
import afield.runtime.NativeCapability
import org.json.JSONObject
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.nio.file.Files
import java.nio.file.Path

/**
 * An optional bridge to the SolidCraft B-rep document engine, whose model stays in native Rust code.
 *
 * [SolidCraftLibrary] locates the native library and reports whether it is available. [SolidCraftDesign.open]
 * loads a design, [SolidCraftDesign.setParameter] edits it, and [SolidCraftDesign.mesh] copies a body out as a
 * [SolidCraftMesh] for display.
 */

/** Optional SolidCraft B-rep/document engine. Its model remains in Rust; Field copies display meshes. */
object SolidCraftLibrary : NativeCapability {
    override val name = "solidcraft"
    override val title = "SolidCraft parametric CAD"
    override val area = NativeArea.SOLIDS
    override val docsPage = "solidcraft.md"
    override val buildTask = "buildSolidCraftNative"
    override val install: Map<String, List<String>> = mapOf(
        "macos" to listOf("xcode-select --install", "curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh    # Rust 1.95 or newer", "./gradlew buildSolidCraftNative"),
        "linux" to listOf("sudo apt install build-essential git python3", "curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh    # Rust 1.95 or newer", "./gradlew buildSolidCraftNative"),
        "windows" to listOf("winget install -e --id Rustlang.Rustup    # and Visual Studio C++ Build Tools", "winget install -e --id Git.Git", ".\\gradlew buildSolidCraftNative"),
    )

    private val fileName: String get() = when {
        System.getProperty("os.name").contains("win", ignoreCase = true) -> "field_solidcraft_jvm.dll"
        System.getProperty("os.name").contains("mac", ignoreCase = true) -> "libfield_solidcraft_jvm.dylib"
        else -> "libfield_solidcraft_jvm.so"
    }

    val searchedPaths: List<Path> get() = listOfNotNull(
        System.getProperty("afield.solidcraft.library")?.takeIf(String::isNotBlank),
        System.getenv("AFIELD_SOLIDCRAFT_LIBRARY")?.takeIf(String::isNotBlank),
        Path.of("build", "native", "solidcraft", fileName).toString(),
    ).map(Path::of)

    val resolvedPath: Path? get() = searchedPaths.firstOrNull { Files.isRegularFile(it) }
    override val isAvailable: Boolean get() = resolvedPath != null
    override val location: String? get() = resolvedPath?.toString()
    override fun describe(): String = resolvedPath?.let { "SolidCraft bridge at $it" }
        ?: "SolidCraft bridge unavailable. Run ./gradlew buildSolidCraftNative or set AFIELD_SOLIDCRAFT_LIBRARY."
}

private object Native {
    private val path = SolidCraftLibrary.resolvedPath
        ?: throw IllegalStateException(SolidCraftLibrary.describe())
    private val lookup = SymbolLookup.libraryLookup(path.toAbsolutePath(), Arena.global())
    private val linker = Linker.nativeLinker()
    private fun method(name: String, descriptor: FunctionDescriptor): MethodHandle {
        val symbol = lookup.find("field_solidcraft_$name")
            .orElseThrow { IllegalStateException("SolidCraft bridge lacks $name at $path") }
        return linker.downcallHandle(symbol, descriptor)
    }

    val version = method("abi_version", FunctionDescriptor.of(JAVA_INT))
    val lastError = method("error", FunctionDescriptor.of(ADDRESS))
    val open = method("open", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG))
    val close = method("close", FunctionDescriptor.ofVoid(ADDRESS))
    val setParameter = method("set_parameter", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS))
    val summary = method("summary", FunctionDescriptor.of(ADDRESS, ADDRESS))
    val mesh = method("mesh", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, JAVA_DOUBLE))
    val meshClose = method("mesh_close", FunctionDescriptor.ofVoid(ADDRESS))
    val meshVertexCount = method("mesh_vertex_count", FunctionDescriptor.of(JAVA_LONG, ADDRESS))
    val meshTriangleCount = method("mesh_triangle_count", FunctionDescriptor.of(JAVA_LONG, ADDRESS))
    val meshPositions = method("mesh_positions", FunctionDescriptor.of(ADDRESS, ADDRESS))
    val meshTriangles = method("mesh_triangles", FunctionDescriptor.of(ADDRESS, ADDRESS))
    val meshFaceIds = method("mesh_face_ids", FunctionDescriptor.of(ADDRESS, ADDRESS))
    val export = method("export", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT))
    val bytesLen = method("bytes_len", FunctionDescriptor.of(JAVA_LONG, ADDRESS))
    val bytesPtr = method("bytes_ptr", FunctionDescriptor.of(ADDRESS, ADDRESS))
    val bytesClose = method("bytes_close", FunctionDescriptor.ofVoid(ADDRESS))

    init {
        val actual = version.invokeWithArguments() as Int
        require(actual == 1) { "SolidCraft bridge ABI $actual; Field needs 1 at $path" }
    }

    fun failure(): Nothing {
        val pointer = lastError.invokeWithArguments() as MemorySegment
        val message = if (pointer.address() == 0L) "unknown native error" else pointer.reinterpret(16_384).getString(0)
        throw IllegalStateException("SolidCraft: $message")
    }

    fun bytes(handle: MemorySegment): ByteArray {
        if (handle.address() == 0L) failure()
        try {
            val length = bytesLen.invokeWithArguments(handle) as Long
            require(length in 0..(256L shl 20)) { "SolidCraft returned an oversized buffer: $length" }
            if (length == 0L) return ByteArray(0)
            val pointer = bytesPtr.invokeWithArguments(handle) as MemorySegment
            require(pointer.address() != 0L) { "SolidCraft returned a null data pointer" }
            return pointer.reinterpret(length).toArray(JAVA_BYTE)
        } finally {
            bytesClose.invokeWithArguments(handle)
        }
    }
}

/** Double-precision SolidCraft tessellation plus B-rep face IDs, copied out of Rust memory. */
data class SolidCraftMesh(val positions: DoubleArray, val indices: IntArray, val faceIds: IntArray) {
    fun toTriMesh(): TriMesh = TriMesh(FloatArray(positions.size) { positions[it].toFloat() }, indices.copyOf())

    /**
     * The body's edges as the tessellation shows them: every run of triangle sides where two
     * B-rep faces meet, one [SolidCraftEdge] per connected run. Pass their [SolidCraftEdge.pick]
     * points to a chamfer or fillet to take every edge of a part:
     *
     * ```kotlin
     * val edges = part.mesh().edges()
     * chamfer("0.5", *edges.map { it.pick }.toTypedArray())
     * ```
     *
     * A curved edge comes back as the chords the mesh drew it with, so its points are within the
     * mesh's tolerance of the true edge, which is as near as a pick needs.
     */
    fun edges(): List<SolidCraftEdge> {
        // Triangles of different faces repeat their shared corners: weld by position first.
        var extent = 0.0
        for (axis in 0 until 3) {
            var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
            for (i in axis until positions.size step 3) { lo = minOf(lo, positions[i]); hi = maxOf(hi, positions[i]) }
            extent = maxOf(extent, hi - lo)
        }
        val grid = maxOf(extent * 1e-9, 1e-12)
        val welded = HashMap<Triple<Long, Long, Long>, Int>()
        val corners = ArrayList<DoubleArray>()
        val vertexOf = IntArray(positions.size / 3) { v ->
            val key = Triple(Math.round(positions[3 * v] / grid), Math.round(positions[3 * v + 1] / grid), Math.round(positions[3 * v + 2] / grid))
            welded.getOrPut(key) { corners += doubleArrayOf(positions[3 * v], positions[3 * v + 1], positions[3 * v + 2]); corners.size - 1 }
        }
        // Each triangle side, by its two welded corners, with the faces on either side of it.
        val sides = HashMap<Long, MutableSet<Int>>()
        val trianglesOn = HashMap<Long, MutableList<Int>>()
        fun key(a: Int, b: Int) = minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong()
        for (t in 0 until indices.size / 3) for (k in 0 until 3) {
            val a = vertexOf[indices[3 * t + k]]; val b = vertexOf[indices[3 * t + (k + 1) % 3]]
            if (a == b) continue
            sides.getOrPut(key(a, b)) { HashSet(2) } += faceIds[t]
            trianglesOn.getOrPut(key(a, b)) { ArrayList(2) } += t
        }
        // Convex where the second face falls away behind the first one's plane.
        fun convex(a: Int, b: Int): Boolean {
            val (t, u) = trianglesOn.getValue(key(a, b)).let { it[0] to it[it.size - 1] }
            fun corner(t: Int, k: Int) = corners[vertexOf[indices[3 * t + k]]]
            val (p0, p1, p2) = Triple(corner(t, 0), corner(t, 1), corner(t, 2))
            val e1 = DoubleArray(3) { p1[it] - p0[it] }; val e2 = DoubleArray(3) { p2[it] - p0[it] }
            val n = doubleArrayOf(e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0])
            val far = (0 until 3).map { vertexOf[indices[3 * u + it]] }.first { it != a && it != b }
            return (0 until 3).sumOf { n[it] * (corners[far][it] - corners[a][it]) } < 0
        }
        // The sides between two faces, grouped by that pair, then walked into connected runs.
        val byPair = HashMap<Pair<Int, Int>, MutableList<Pair<Int, Int>>>()
        for ((key, faces) in sides) {
            if (faces.size != 2) continue
            val (f, g) = faces.sorted()
            byPair.getOrPut(f to g) { ArrayList() } += (key ushr 32).toInt() to (key and 0xffffffffL).toInt()
        }
        val edges = ArrayList<SolidCraftEdge>()
        for ((pair, segments) in byPair) {
            val next = HashMap<Int, MutableList<Int>>()
            for ((a, b) in segments) { next.getOrPut(a) { ArrayList(2) } += b; next.getOrPut(b) { ArrayList(2) } += a }
            val used = HashSet<Long>()
            fun take(a: Int, b: Int) = used.add(minOf(a, b).toLong() shl 32 or maxOf(a, b).toLong())
            // Open runs start at an end; whatever is left afterwards is closed loops.
            val starts = next.keys.sortedBy { next.getValue(it).size != 1 }
            for (start in starts) {
                for (first in next.getValue(start)) {
                    if (!take(start, first)) continue
                    val run = arrayListOf(start, first)
                    while (true) {
                        val here = run.last()
                        val onward = next.getValue(here).firstOrNull { take(here, it) } ?: break
                        run += onward
                    }
                    val middle = (run.size - 2) / 2
                    edges += SolidCraftEdge(pair, run.map { corners[it] }, convex(run[middle], run[middle + 1]))
                }
            }
        }
        return edges
    }
}

/**
 * One edge of a SolidCraft body: the two B-rep faces it separates, its points in order, and
 * whether it is [convex] (an outside corner, which a chamfer cuts away) or concave (an inside one,
 * which a chamfer fills).
 */
class SolidCraftEdge internal constructor(val faces: Pair<Int, Int>, val points: List<DoubleArray>, val convex: Boolean) {
    /** Whether it is a loop, such as a hole's rim, rather than a run between two ends. */
    val closed: Boolean get() = points.size > 2 && points.first().contentEquals(points.last())

    /** A point on the edge for picking it: the middle of its middle segment. */
    val pick: DoubleArray get() {
        val i = (points.size - 2) / 2
        return DoubleArray(3) { (points[i][it] + points[i + 1][it]) / 2 }
    }

    /** Its length along the mesh. */
    val length: Double get() = points.zipWithNext().sumOf { (a, b) -> kotlin.math.sqrt((0 until 3).sumOf { (a[it] - b[it]) * (a[it] - b[it]) }) }
}

/** A saved SolidCraft design with an incremental evaluator. Close it when finished. */
class SolidCraftDesign private constructor(private var handle: MemorySegment) : AutoCloseable {
    private fun current(): MemorySegment {
        check(handle.address() != 0L) { "SolidCraft design is closed" }
        return handle
    }

    @Synchronized fun summary(): JSONObject = JSONObject(String(Native.bytes(Native.summary.invokeWithArguments(current()) as MemorySegment), Charsets.UTF_8))

    /** An invalid expression or failed feature leaves the previous design and mesh intact. */
    @Synchronized
    fun setParameter(name: String, expression: String) {
        Arena.ofConfined().use { arena ->
            val ok = Native.setParameter.invokeWithArguments(current(), arena.allocateFrom(name), arena.allocateFrom(expression)) as Int
            if (ok != 1) Native.failure()
        }
    }

    @Synchronized fun mesh(bodyIndex: Int = 0, tolerance: Double = 0.05): SolidCraftMesh {
        require(bodyIndex >= 0)
        val mesh = Native.mesh.invokeWithArguments(current(), bodyIndex.toLong(), tolerance) as MemorySegment
        if (mesh.address() == 0L) Native.failure()
        try {
            val vertices = Native.meshVertexCount.invokeWithArguments(mesh) as Long
            val triangles = Native.meshTriangleCount.invokeWithArguments(mesh) as Long
            require(vertices in 1..10_000_000 && triangles in 1..20_000_000) {
                "SolidCraft mesh too large: $vertices vertices, $triangles triangles"
            }
            val pos = Native.meshPositions.invokeWithArguments(mesh) as MemorySegment
            val tri = Native.meshTriangles.invokeWithArguments(mesh) as MemorySegment
            val faces = Native.meshFaceIds.invokeWithArguments(mesh) as MemorySegment
            val p = pos.reinterpret(vertices * 3 * 8)
            val t = tri.reinterpret(triangles * 3 * 4)
            val f = faces.reinterpret(triangles * 4)
            return SolidCraftMesh(
                DoubleArray((vertices * 3).toInt()) { p.getAtIndex(JAVA_DOUBLE, it.toLong()) },
                IntArray((triangles * 3).toInt()) { t.getAtIndex(JAVA_INT, it.toLong()) },
                IntArray(triangles.toInt()) { f.getAtIndex(JAVA_INT, it.toLong()) },
            )
        } finally {
            Native.meshClose.invokeWithArguments(mesh)
        }
    }

    @Synchronized fun exportStep(): ByteArray = Native.bytes(Native.export.invokeWithArguments(current(), 1) as MemorySegment)
    @Synchronized fun exportStl(): ByteArray = Native.bytes(Native.export.invokeWithArguments(current(), 2) as MemorySegment)
    @Synchronized fun saveDesign(): ByteArray = Native.bytes(Native.export.invokeWithArguments(current(), 3) as MemorySegment)

    @Synchronized override fun close() {
        if (handle.address() != 0L) {
            Native.close.invokeWithArguments(handle)
            handle = MemorySegment.NULL
        }
    }

    companion object {
        /** The small box, fillet and hole design shipped with Field's SolidCraft starting point. */
        fun openBundledExample(): SolidCraftDesign {
            val path = "/afield/solidcraft/m0-box-fillet-hole.solidcraft"
            val stream = SolidCraftDesign::class.java.getResourceAsStream(path)
                ?: throw IllegalStateException("Missing bundled SolidCraft example: $path")
            return stream.use { open(it.readBytes()) }
        }

        fun open(bytes: ByteArray): SolidCraftDesign {
            require(bytes.isNotEmpty()) { "empty SolidCraft design" }
            Arena.ofConfined().use { arena ->
                val input = arena.allocate(bytes.size.toLong())
                input.copyFrom(MemorySegment.ofArray(bytes))
                val handle = Native.open.invokeWithArguments(input, bytes.size.toLong()) as MemorySegment
                if (handle.address() == 0L) Native.failure()
                return SolidCraftDesign(handle)
            }
        }

        fun open(path: Path): SolidCraftDesign = open(Files.readAllBytes(path))
    }
}
