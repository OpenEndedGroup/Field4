@file:afield.CompletionCategory("Graphics")

package afield.solidcraft

import afield.stdlib.TriMesh
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

/** Optional SolidCraft B-rep/document engine. Its model remains in Rust; Field copies display meshes. */
object SolidCraftLibrary {
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
    val isAvailable: Boolean get() = resolvedPath != null
    fun describe(): String = resolvedPath?.let { "SolidCraft bridge at $it" }
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
}

/** A saved SolidCraft design with an incremental evaluator. Close it when finished. */
class SolidCraftDesign private constructor(private var handle: MemorySegment) : AutoCloseable {
    private fun current(): MemorySegment {
        check(handle.address() != 0L) { "SolidCraft design is closed" }
        return handle
    }

    @Synchronized fun summary(): JSONObject = JSONObject(String(Native.bytes(Native.summary.invokeWithArguments(current()) as MemorySegment), Charsets.UTF_8))

    /** An invalid expression or failed feature leaves the previous design and mesh intact. */
    @Synchronized fun setParameter(name: String, expression: String) {
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
