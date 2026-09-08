@file:afield.CompletionCategory("Simulation")
package afield.tetgen

import afield.stdlib.TriMesh
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_DOUBLE
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.invoke.MethodHandle
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * A closed surface turned into tetrahedra, with its sharp edges intact.
 *
 * This is the discretisation half of the Kratos bridge for **body-fitted** work. The embedded
 * formulations (`thermal.stationary`, `flow.embedded`) never come here: they sample a level set
 * onto a fixed background grid and cut with it. Structural mechanics has no embedded element in
 * Kratos, so it needs a mesh whose boundary really is the part's surface — and, more to the point,
 * whose boundary reproduces the part's *edges*, because that is where stress concentrates.
 *
 * # Why not the level-set route
 *
 * `dev_docs/TETGEN.md` has the measurement in full. The short version, on the same body at the same
 * element size:
 *
 * | | MMG, level set | TetGen, this |
 * |---|---|---|
 * | time | 4669 ms | 223 ms |
 * | boundary error, curved surface | 0.55 h | 0.024 h |
 * | boundary error, a box's edges | 0.35–0.40 h, at every resolution | **exactly 0** |
 *
 * Sampling an SDF onto grid nodes destroys a sharp edge before the mesher ever sees it, and
 * refining only buys a smaller rounded radius. TetGen is handed the Manifold triangulation itself,
 * whose edges are exact, and constrains its output to it.
 *
 * # And why incremental meshing turned out not to matter
 *
 * A full re-tetrahedralization of a realistic body is ~0.2 s here. Against a Kratos structural
 * solve that is noise, so a geometry edit simply re-meshes. No part of this tries to update a mesh
 * in place.
 */
object Tetgen {

    /**
     * Tetrahedralize a closed triangle surface — typically `Solid.toTriMesh()`.
     *
     * @param surface must be closed and non-self-intersecting. Manifold guarantees both; an
     *   arbitrary [TriMesh] does not, and TetGen will say so rather than produce nonsense.
     * @param quality TetGen's radius-edge bound. 1.2 is the default here rather than TetGen's own
     *   1.414 because the measurement found it removes the last poor element for no measurable
     *   cost; below about 1.1 the refinement can fail to terminate.
     * @param targetEdgeLength the element size to aim for, in the surface's own units. Translated
     *   to TetGen's volume bound as the volume of a regular tetrahedron of that edge. `null` lets
     *   TetGen choose, which follows the surface's own triangle sizes.
     * @param preserveSurface forbid Steiner points on the boundary (TetGen's `-Y`). Helps a finely
     *   tessellated convex surface, and hurts a boolean result badly — 109 poor elements against 1
     *   on the measured body. Off unless you have measured your own case.
     */
    fun tetrahedralize(
        surface: TriMesh,
        quality: Double = 1.2,
        targetEdgeLength: Double? = null,
        preserveSurface: Boolean = false,
    ): TetMesh {
        require(!surface.isEmpty) { "Cannot tetrahedralize an empty surface" }
        require(quality > 1.0) { "quality must exceed 1.0, got $quality" }
        targetEdgeLength?.let { require(it > 0 && it.isFinite()) { "targetEdgeLength must be positive" } }
        // A regular tetrahedron of edge h has volume h^3/(6*sqrt 2); that is how "about this size"
        // is spelled to TetGen, which only takes a volume bound.
        val maxVolume = targetEdgeLength?.let { it * it * it / (6.0 * Math.sqrt(2.0)) } ?: 0.0
        return TetgenNative.tetrahedralize(surface, quality, maxVolume, preserveSurface)
    }
}

/**
 * A tetrahedral mesh: [positions] as xyz triples, [cells] as zero-based node index quadruples.
 *
 * The same flat layout the Kratos worker uses on the wire, so this crosses the protocol without
 * being reshaped.
 */
class TetMesh(val positions: DoubleArray, val cells: IntArray) {
    init {
        require(positions.size % 3 == 0) { "positions must be xyz triples, got ${positions.size}" }
        require(cells.size % 4 == 0) { "cells must be tetrahedra, got ${cells.size}" }
        val n = positions.size / 3
        require(cells.all { it in 0 until n }) { "a cell references a node outside the mesh" }
    }

    val nodeCount: Int get() = positions.size / 3
    val cellCount: Int get() = cells.size / 4

    /** Signed volume of every tetrahedron; all positive for a consistently oriented mesh. */
    fun volumes(): DoubleArray = DoubleArray(cellCount) { t ->
        fun at(k: Int, c: Int) = positions[3 * cells[4 * t + k] + c]
        val ax = at(1, 0) - at(0, 0); val ay = at(1, 1) - at(0, 1); val az = at(1, 2) - at(0, 2)
        val bx = at(2, 0) - at(0, 0); val by = at(2, 1) - at(0, 1); val bz = at(2, 2) - at(0, 2)
        val cx = at(3, 0) - at(0, 0); val cy = at(3, 1) - at(0, 1); val cz = at(3, 2) - at(0, 2)
        (ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx)) / 6.0
    }

    /** Total volume — worth asserting against `Solid.volume`, which is exact. */
    fun volume(): Double = volumes().sumOf { Math.abs(it) }

    override fun toString(): String = "TetMesh($nodeCount nodes, $cellCount tetrahedra)"
}

/** Thrown when TetGen refuses a surface, carrying its own explanation. */
class TetgenException(message: String) : RuntimeException(message)

/**
 * Where `libfield_tetgen_jvm` is, and whether it is there — the same shape as
 * [afield.manifold.ManifoldLibrary], so "is this installed?" is answerable from a box.
 *
 * Unlike Manifold there is no package manager to suggest: TetGen has no Homebrew formula and no
 * distribution package worth linking, which is why `tools/build_tetgen_native.py` exists.
 */
object TetgenLibrary {

    private val isWindows: Boolean get() = System.getProperty("os.name").lowercase().contains("win")
    private val isMac: Boolean
        get() = System.getProperty("os.name").lowercase().let { it.contains("mac") || it.contains("darwin") }

    val fileName: String
        get() = when {
            isWindows -> "field_tetgen_jvm.dll"
            isMac -> "libfield_tetgen_jvm.dylib"
            else -> "libfield_tetgen_jvm.so"
        }

    /** Every path [resolvedPath] tries, in order — quoted in the "not built" error. */
    val searchedPaths: List<Path>
        get() = listOfNotNull(
            System.getProperty("afield.tetgen.library")?.takeIf { it.isNotBlank() },
            System.getenv("AFIELD_TETGEN_LIBRARY")?.takeIf { it.isNotBlank() },
        ).map(Path::of) + listOf(
            Path.of("build", "field-tetgen", "build", fileName).toAbsolutePath(),
            Path.of("build", "field-tetgen", "build", "Release", fileName).toAbsolutePath(),
        )

    /** Not cached: building it during a live session should be picked up by the next box run. */
    val resolvedPath: Path? get() = searchedPaths.firstOrNull { it.exists() }

    val isAvailable: Boolean get() = resolvedPath != null

    val HOW_TO_BUILD: String
        get() = "Build it with `python3 tools/build_tetgen_native.py` (needs cmake and the network " +
            "once), or point -Dafield.tetgen.library at a $fileName you built."

    /** One line for a box or a smoke test to print. */
    fun describe(): String = resolvedPath?.let { "TetGen wrapper at $it (${TetgenNative.version()})" }
        ?: "TetGen wrapper not built. $HOW_TO_BUILD"
}

internal object TetgenNative {

    private val linker: Linker = Linker.nativeLinker()

    private val lookup: SymbolLookup by lazy {
        val path = TetgenLibrary.resolvedPath
            ?: throw UnsatisfiedLinkError(
                "TetGen wrapper is not built. ${TetgenLibrary.HOW_TO_BUILD}\n" +
                    "Looked at: " + TetgenLibrary.searchedPaths.joinToString(", "),
            )
        SymbolLookup.libraryLookup(path, Arena.global())
    }

    private fun handle(name: String, descriptor: FunctionDescriptor): MethodHandle {
        val symbol = lookup.find(name).orElseThrow {
            UnsatisfiedLinkError("$fileNameForError has no symbol $name; rebuild it with " +
                "tools/build_tetgen_native.py")
        }
        return linker.downcallHandle(symbol, descriptor)
    }

    private val fileNameForError get() = TetgenLibrary.fileName

    private val tetrahedralizeHandle: MethodHandle by lazy {
        handle("field_tetgen_tetrahedralize", FunctionDescriptor.of(
            JAVA_INT,
            JAVA_INT, ADDRESS,            // pointCount, points
            JAVA_INT, ADDRESS,            // triangleCount, triangles
            JAVA_DOUBLE, JAVA_DOUBLE, JAVA_INT,   // quality, maxVolume, preserveSurface
            ADDRESS, ADDRESS, ADDRESS, ADDRESS,   // nodeCountOut, nodesOut, cellCountOut, cellsOut
            ADDRESS, JAVA_INT))                   // message, messageCapacity
    }

    private val freeHandle: MethodHandle by lazy {
        handle("field_tetgen_free", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS))
    }

    private val versionHandle: MethodHandle by lazy {
        handle("field_tetgen_version", FunctionDescriptor.of(ADDRESS))
    }

    fun version(): String = (versionHandle.invokeExact() as MemorySegment)
        .reinterpret(Long.MAX_VALUE).getString(0)

    fun tetrahedralize(surface: TriMesh, quality: Double, maxVolume: Double,
                       preserveSurface: Boolean): TetMesh = Arena.ofConfined().use { arena ->
        val pointCount = surface.vertexCount
        val triangleCount = surface.indices.size / 3
        val points = arena.allocate(JAVA_DOUBLE, (3L * pointCount))
        for (i in 0 until 3 * pointCount) points.setAtIndex(JAVA_DOUBLE, i.toLong(), surface.positions[i].toDouble())
        val triangles = arena.allocateFrom(JAVA_INT, *surface.indices)

        val nodeCount = arena.allocate(JAVA_INT)
        val cellCount = arena.allocate(JAVA_INT)
        val nodes = arena.allocate(ADDRESS)
        val cells = arena.allocate(ADDRESS)
        val message = arena.allocate(JAVA_BYTE, MESSAGE_CAPACITY.toLong())

        val status = tetrahedralizeHandle.invokeExact(
            pointCount, points, triangleCount, triangles,
            quality, maxVolume, if (preserveSurface) 1 else 0,
            nodeCount, nodes, cellCount, cells,
            message, MESSAGE_CAPACITY) as Int

        val nodesPointer = nodes.get(ADDRESS, 0)
        val cellsPointer = cells.get(ADDRESS, 0)
        if (status != 0) {
            throw TetgenException("TetGen failed (status $status): " + message.getString(0))
        }
        try {
            val n = nodeCount.get(JAVA_INT, 0)
            val m = cellCount.get(JAVA_INT, 0)
            check(n > 0 && m > 0) { "TetGen reported success with an empty mesh" }
            val positions = nodesPointer.reinterpret(3L * n * Double.SIZE_BYTES)
                .toArray(JAVA_DOUBLE)
            val connectivity = cellsPointer.reinterpret(4L * m * Int.SIZE_BYTES).toArray(JAVA_INT)
            return TetMesh(positions, connectivity)
        } finally {
            freeHandle.invokeExact(nodesPointer, cellsPointer)
        }
    }

    private const val MESSAGE_CAPACITY = 512
}
