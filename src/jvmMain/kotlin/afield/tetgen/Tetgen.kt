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

/** Entry point; use [tetrahedralize] to convert a closed [afield.stdlib.TriMesh] surface into a [TetMesh]. */
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
     * @param order 1 for 4-node linear tetrahedra, 2 for 10-node quadratic ones. See [TetMesh] for
     *   what that buys and what it costs; for structural bending it is the difference between a
     *   deflection that is 76% of the truth and one that is within a percent of it. Order 2 is
     *   [TetMesh.quadratic] applied to the same tetrahedralization, not a different mesh.
     */
    fun tetrahedralize(
        surface: TriMesh,
        quality: Double = 1.2,
        targetEdgeLength: Double? = null,
        preserveSurface: Boolean = false,
        order: Int = 1,
    ): TetMesh {
        require(!surface.isEmpty) { "Cannot tetrahedralize an empty surface" }
        require(quality > 1.0) { "quality must exceed 1.0, got $quality" }
        require(order == 1 || order == 2) { "order must be 1 (4-node) or 2 (10-node), got $order" }
        targetEdgeLength?.let { require(it > 0 && it.isFinite()) { "targetEdgeLength must be positive" } }
        // A regular tetrahedron of edge h has volume h^3/(6*sqrt 2); that is how "about this size"
        // is spelled to TetGen, which only takes a volume bound.
        val maxVolume = targetEdgeLength?.let { it * it * it / (6.0 * Math.sqrt(2.0)) } ?: 0.0
        val linear = TetgenNative.tetrahedralize(surface, quality, maxVolume, preserveSurface)
        return if (order == 2) linear.quadratic() else linear
    }
}

/**
 * A tetrahedral mesh: [positions] as xyz triples, [cells] as groups of [nodesPerCell] zero-based
 * node indices.
 *
 * The same flat layout the Kratos worker uses on the wire, so this crosses the protocol without
 * being reshaped.
 *
 * # Linear and quadratic
 *
 * [nodesPerCell] is 4 or 10. The *shape* is identical either way — a 10-node tetrahedron's six
 * extra nodes sit at the exact midpoints of its straight edges, so the mesh occupies the same
 * region and its boundary is the same faceted surface. What changes is the interpolation: a 4-node
 * tetrahedron has constant strain, which makes it far too stiff in bending. `dev_docs/TETGEN.md`
 * has the beam measurement.
 *
 * The extra nodes follow Kratos's `Tetrahedra3D10` order, which is also VTK's: node 4 bisects edge
 * (0,1), then (1,2), (0,2), (0,3), (1,3), (2,3).
 *
 * Anything that only wants the shape — volume, the boundary surface, a picture — reads the first
 * four indices of each cell and ignores the rest; [corners] is that view.
 */
class TetMesh(val positions: DoubleArray, val cells: IntArray, val nodesPerCell: Int = 4) {
    init {
        require(positions.size % 3 == 0) { "positions must be xyz triples, got ${positions.size}" }
        require(nodesPerCell == 4 || nodesPerCell == 10) {
            "a tetrahedron has 4 nodes or 10, not $nodesPerCell"
        }
        require(cells.size % nodesPerCell == 0) {
            "cells must be groups of $nodesPerCell, got ${cells.size}"
        }
        val n = positions.size / 3
        require(cells.all { it in 0 until n }) { "a cell references a node outside the mesh" }
    }

    val nodeCount: Int get() = positions.size / 3
    val cellCount: Int get() = cells.size / nodesPerCell

    /** True for 10-node tetrahedra. */
    val isQuadratic: Boolean get() = nodesPerCell == 10

    /**
     * The same mesh seen as 4-node tetrahedra, dropping any midside nodes from the connectivity.
     *
     * The node array is untouched, so a quadratic mesh's midside nodes stay in [positions] and are
     * simply unreferenced. Returns `this` when there was nothing to drop.
     */
    fun corners(): TetMesh {
        if (!isQuadratic) return this
        val linear = IntArray(4 * cellCount) { cells[nodesPerCell * (it / 4) + it % 4] }
        return TetMesh(positions, linear, 4)
    }

    /**
     * The same tetrahedra promoted to 10 nodes, with one new node at the midpoint of every distinct
     * edge. Returns `this` if it is already quadratic.
     *
     * # Why this is here and not in TetGen
     *
     * TetGen has a `-o2` for exactly this, and it cannot be used from the library. TetGen 1.6.0's
     * `highorder()` stores each element's six extra nodes in a `highordertable` and then
     * `delete[]`s that table before returning, leaving every tetrahedron holding a dangling
     * pointer. Driven from the command line it usually survives; driven through `tetgenio` it does
     * not, because `outelements()` allocates the output node array first and the allocator hands
     * back the block just freed — so TetGen reads its own coordinates as pointers. The crash is a
     * SIGSEGV on an address that is a `double` bit pattern from the input body.
     *
     * `tools/tetgen_o2_use_after_free.py` reproduces that from scratch and pins the cause: the same
     * cube built twice from the same TetGen source, dying on SIGSEGV as shipped and succeeding with
     * that one `delete[]` commented out.
     *
     * Doing it here is not a workaround so much as a simplification. `highorder()` places each node
     * at the exact arithmetic midpoint of a straight edge, which is what this does; and building
     * the table on this side means the six midside nodes are emitted in Kratos's `Tetrahedra3D10`
     * order by construction, instead of being recovered from TetGen's internal edge numbering.
     */
    fun quadratic(): TetMesh {
        if (isQuadratic) return this
        // Sorted node pair -> the index of the node bisecting it. One entry per distinct edge, so
        // adjacent tetrahedra share a midside node and the mesh stays conforming.
        val midpoints = HashMap<Long, Int>(cellCount * 8)
        val extra = ArrayList<Double>(cellCount * 9)
        fun midpointOf(a: Int, b: Int): Int {
            val key = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()
            return midpoints.getOrPut(key) {
                for (c in 0 until 3) extra.add(0.5 * (positions[3 * a + c] + positions[3 * b + c]))
                nodeCount + extra.size / 3 - 1
            }
        }
        val promoted = IntArray(10 * cellCount)
        for (t in 0 until cellCount) {
            val corner = IntArray(4) { cells[4 * t + it] }
            for (k in 0 until 4) promoted[10 * t + k] = corner[k]
            for ((k, edge) in QUADRATIC_EDGES.withIndex()) {
                promoted[10 * t + 4 + k] = midpointOf(corner[edge[0]], corner[edge[1]])
            }
        }
        val grown = positions.copyOf(positions.size + extra.size)
        for (i in extra.indices) grown[positions.size + i] = extra[i]
        return TetMesh(grown, promoted, 10)
    }

    /** Signed volume of every tetrahedron; all positive for a consistently oriented mesh. */
    fun volumes(): DoubleArray = DoubleArray(cellCount) { t ->
        // Corners only. With straight edges the midside nodes carry no extra shape, so this is the
        // exact volume of a quadratic element too.
        fun at(k: Int, c: Int) = positions[3 * cells[nodesPerCell * t + k] + c]
        val ax = at(1, 0) - at(0, 0); val ay = at(1, 1) - at(0, 1); val az = at(1, 2) - at(0, 2)
        val bx = at(2, 0) - at(0, 0); val by = at(2, 1) - at(0, 1); val bz = at(2, 2) - at(0, 2)
        val cx = at(3, 0) - at(0, 0); val cy = at(3, 1) - at(0, 1); val cz = at(3, 2) - at(0, 2)
        (ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx)) / 6.0
    }

    /** Total volume — worth asserting against `Solid.volume`, which is exact. */
    fun volume(): Double = volumes().sumOf { Math.abs(it) }

    override fun toString(): String =
        "TetMesh($nodeCount nodes, $cellCount ${if (isQuadratic) "quadratic " else ""}tetrahedra)"

    companion object {
        /**
         * The corner pairs bisected by nodes 4..9 of a 10-node tetrahedron, in the order Kratos's
         * `Tetrahedra3D10` and VTK's `VTK_QUADRATIC_TETRA` both number them.
         */
        val QUADRATIC_EDGES: List<IntArray> = listOf(
            intArrayOf(0, 1), intArrayOf(1, 2), intArrayOf(0, 2),
            intArrayOf(0, 3), intArrayOf(1, 3), intArrayOf(2, 3))
    }
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
