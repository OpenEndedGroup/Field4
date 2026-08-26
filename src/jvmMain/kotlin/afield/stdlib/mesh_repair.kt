@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Why a mesh is not a solid, and what can be done about it — the paved road between "I loaded a file"
 * and `toSolid()`.
 *
 * [TriMesh.diagnose] answers *why*, in terms a person can act on: which loops are open and how big,
 * which edges have the wrong number of faces, which faces are duplicated, whether the winding is
 * consistent and whether it points out. [TriMesh.repairToSolid] then attempts the fixes in a
 * defensible order and hands back a [MeshRepairResult] that says **what it did and what it could
 * not do** — a repair that silently drops half your model is worse than one that refuses, so nothing
 * here returns a bare mesh.
 *
 * ```kotlin
 * val mesh = meshFromAnyFile("scan.glb")
 * println(mesh.diagnose().report())     // why it is not a solid, worst first
 * val fixed = mesh.repairToSolid()
 * println(fixed.report())               // what was done, and what was not
 * if (fixed.isSolid) display(fixed.mesh.toSolid() - drill)
 * ```
 *
 * # What actually blocks `toSolid`, measured rather than assumed
 *
 * Every severity below was established by feeding Manifold a mesh damaged in exactly one named way
 * (see `MeshDamage` in the tests) and asking whether it accepted the result. The answers are not the
 * ones folklore predicts, and three of them matter:
 *
 *  - **A mesh with several disjoint components is fine.** Manifold accepts it and reports the sum of
 *    the volumes. "Split into parts" is not a defect; it is [MeshDefectKind.MULTIPLE_COMPONENTS], and
 *    it is informational.
 *  - **Zero-area triangles are fine.** Manifold accepts them and the volume is unchanged.
 *  - **An inside-out mesh is fine, and that is the dangerous one.** Manifold accepts a consistently
 *    but inwardly wound closed surface and reports a **negative volume** — so the boolean you do next
 *    silently computes the wrong thing rather than throwing. That is
 *    [MeshDefectKind.INVERTED_ORIENTATION], and it is the reason this file reports severity
 *    [MeshDefectSeverity.SUSPICIOUS] at all instead of just a pass/fail.
 *
 * What Manifold genuinely rejects is a short list: a non-finite position, an edge with other than two
 * incident faces (which a duplicated face also produces, on all three of its edges), an open boundary,
 * and inconsistent winding.
 *
 * # What the existing repairs already do, and where they stop
 *
 * [TriMesh.repaired] — `weld → removeDegenerateTriangles → removeUnusedVertices → fixWinding` — is
 * not a straw man. Measured over twelve meshes that Manifold rejected, it already fixes **six**: every
 * unwelded-seam case (welding is what turns an STL's pile of triangles into a surface) and every
 * inconsistent-winding case. The six it cannot fix are exactly two families, and they are what this
 * file adds:
 *
 *  1. **Open boundaries** — nothing in the old pipeline fills a hole. [fillHoles] is the new code.
 *  2. **Non-manifold edges** — nothing removes a duplicated face or a dangling fin.
 *     [removeDuplicateFaces] and [removeNonManifoldFins] are the new code.
 *
 * So the accurate framing is that this file is the *last third* of a mesh repair pipeline, not the whole
 * of one, and [TriMesh.repairToSolid] calls the existing repairs for the first two thirds.
 */

// ==============================================================================================
// The vocabulary
// ==============================================================================================

/**
 * How much a defect matters — the distinction between "Manifold will refuse this" and "Manifold will
 * accept this and give you a wrong answer without saying so".
 *
 * The second category is the one worth having a word for. A mesh that is inside out is a perfectly
 * legal manifold with a negative volume: subtract it from something and you get the complement of what
 * you meant, with no exception anywhere.
 */
enum class MeshDefectSeverity {
    /** Manifold rejects the mesh outright. Fix these first; nothing else can proceed. */
    BLOCKING,

    /** Manifold accepts the mesh, but the result is probably not what was meant. */
    SUSPICIOUS,

    /** Worth knowing, not worth fixing — a fact about the mesh rather than a fault in it. */
    INFORMATIONAL,
}

/**
 * The kinds of thing that can be wrong with a mesh, **declared in the order worth fixing them**.
 *
 * The order is causal, not alphabetical, and that is the point: welding comes before counting holes
 * because an unwelded mesh reports every edge as a hole, and removing duplicated faces comes before
 * counting non-manifold edges because a duplicated face *is* three non-manifold edges. Reading the
 * diagnosis top to bottom and fixing as you go is meant to work.
 */
enum class MeshDefectKind {
    /** A position is `NaN` or infinite. Nothing downstream can run; usually a bad normal upstream. */
    NON_FINITE_POSITION,

    /** Vertices at the same place with different indices — the crack an STL or a per-cell march leaves. */
    UNWELDED_VERTICES,

    /** Triangles with no area: a repeated corner, or three collinear points. */
    DEGENERATE_FACE,

    /** Two or more triangles with the same three corners. Each one makes its three edges non-manifold. */
    DUPLICATE_FACE,

    /** An edge with three or more incident faces — the classic non-manifold defect. */
    NON_MANIFOLD_EDGE,

    /** A vertex whose faces do not form one fan: two cones meeting at a point, or a bowtie. */
    NON_MANIFOLD_VERTEX,

    /** An open hole: a ring of edges with only one face each. */
    OPEN_BOUNDARY,

    /** Boundary edges that no closed loop walk could reach — two holes meeting at a single vertex. */
    UNWALKABLE_BOUNDARY,

    /** Neighbouring faces disagree about which side is out. */
    INCONSISTENT_WINDING,

    /** Consistently wound, but *inward*: a closed component with negative signed volume. */
    INVERTED_ORIENTATION,

    /** More than one disjoint piece. Legal, and often intended. */
    MULTIPLE_COMPONENTS,

    /** Vertices no triangle references. Harmless, but they inflate every count. */
    UNREFERENCED_VERTEX,
}

/**
 * One thing that is wrong, with the evidence attached.
 *
 * @param kind which defect.
 * @param severity whether Manifold will refuse the mesh over this — see [MeshDefectSeverity].
 * @param count how many: faces, edges, vertices or loops, depending on [kind].
 * @param detail the specifics a person needs in order to go and look at it — sizes, indices, positions.
 * @param fix what to call, or why nothing can be called.
 */
class MeshDefect(
    val kind: MeshDefectKind,
    val severity: MeshDefectSeverity,
    val count: Int,
    val detail: String,
    val fix: String,
) {
    override fun toString(): String = "[$severity] $kind x$count — $detail  → $fix"
}

/**
 * One open boundary loop, measured.
 *
 * The two numbers past the vertex list are what decide whether a fill will look right. [planarity] is
 * the loop's worst deviation from its own best-fit plane divided by its diameter, so it is scale-free:
 * `0` is a flat hole, and anything past a few percent is a hole whose "obvious" flat cap is a lie.
 *
 * @param vertices the ring, in the direction the surface walks it.
 * @param perimeter total edge length around the loop.
 * @param diameter the largest distance between any two consecutive-ish samples — the loop's scale.
 * @param planarity worst out-of-plane distance over [diameter]; `0` when flat.
 * @param normal the loop's Newell normal, which is the plane a fill would use.
 */
class BoundaryLoopInfo(
    val vertices: List<Int>,
    val perimeter: Double,
    val diameter: Double,
    val planarity: Double,
    val normal: Vec3,
) {
    /** Number of edges around the hole. */
    val edgeCount: Int get() = vertices.size

    /** True when the loop is flat enough that a planar fill is trustworthy. */
    val isPlanar: Boolean get() = planarity <= 0.02

    /**
     * True when the ring visits every vertex once.
     *
     * A ring that repeats a vertex is a figure-eight: two holes meeting at a pinch point, walked as
     * one loop because the boundary half-edges happen to chain that way. Measured on a real case —
     * six faces deleted from an icosphere gives the ring `[1, 3, 2, 9, 3, 21]`. It is not a hole and
     * must not be capped as one; a fan over it necessarily produces a non-manifold edge.
     * [TriMesh.fillHoles] splits it at the repeats instead.
     */
    val isSimple: Boolean get() = vertices.toSet().size == vertices.size

    override fun toString(): String =
        "loop(%d edges, perimeter %.4g, planarity %.3f%s%s)".format(
            edgeCount, perimeter, planarity,
            if (isSimple) "" else " FIGURE-EIGHT", if (isPlanar) "" else " NON-PLANAR",
        )
}

/**
 * One connected piece of the mesh, and whether it is a solid in its own right.
 *
 * @param triangles the triangle indices in this piece.
 * @param vertexCount vertices this piece references.
 * @param isClosed true when every edge of the piece has two faces.
 * @param signedVolume this piece's volume — negative means inside out, and only meaningful if closed.
 * @param triangleShare this piece's fraction of the whole mesh's triangles.
 */
class MeshComponentInfo(
    val triangles: IntArray,
    val vertexCount: Int,
    val isClosed: Boolean,
    val signedVolume: Double,
    val triangleShare: Double,
) {
    override fun toString(): String =
        "component(%d tris, %.1f%%, %s, volume %+.4g)".format(
            triangles.size, triangleShare * 100.0, if (isClosed) "closed" else "open", signedVolume,
        )
}

// ==============================================================================================
// The diagnosis
// ==============================================================================================

/**
 * Everything wrong with one [TriMesh], with the evidence, ordered by what to fix first.
 *
 * Built from one [MeshTopology] plus a handful of extra passes, so it costs about what asking
 * [TriMesh.isWatertight] twice would. Hold on to it rather than rebuilding it.
 *
 * The headline is [defects], which is sorted by [MeshDefectSeverity] and then by [MeshDefectKind]'s
 * declaration order — so the first entry really is the one to fix first, and everything
 * [MeshDefectSeverity.BLOCKING] must be gone before Manifold will take the mesh.
 *
 * @param mesh the mesh being diagnosed.
 * @param weldTolerance the distance at which two vertices count as the same one, for
 *   [MeshDefectKind.UNWELDED_VERTICES]. Coincident-but-separate vertices are invisible to topology,
 *   so this is the one check that needs a number.
 */
class MeshDiagnosis(val mesh: TriMesh, val weldTolerance: Double = 1e-6) {

    /** The topology this diagnosis reads. Shared, so ask it your own questions rather than rebuilding. */
    val topology: MeshTopology = mesh.topology()

    /** Vertex indices whose position is `NaN` or infinite. */
    val nonFiniteVertices: List<Int> = (0 until mesh.vertexCount).filter {
        !mesh.x(it).isFinite() || !mesh.y(it).isFinite() || !mesh.z(it).isFinite()
    }

    /** Triangles with a repeated corner or no area. */
    val degenerateFaces: List<Int> = (0 until mesh.triangleCount).filter { t ->
        val a = mesh.ia(t); val b = mesh.ib(t); val c = mesh.ic(t)
        a == b || b == c || a == c || mesh.triangleArea(t) <= 1e-14
    }

    /**
     * Groups of two or more triangles sharing the same three corners, whatever their winding.
     *
     * Each group is a list of triangle indices. A pair with *opposite* windings is a zero-thickness
     * sheet — geometrically nothing, topologically a mess — and a pair with the *same* winding is a
     * plain duplicate, usually an exporter emitting a face twice.
     */
    val duplicateFaceGroups: List<List<Int>> = run {
        val byCorners = HashMap<Long, MutableList<Int>>()
        val out = mutableListOf<List<Int>>()
        for (t in 0 until mesh.triangleCount) {
            val s = intArrayOf(mesh.ia(t), mesh.ib(t), mesh.ic(t)).also { it.sort() }
            // Packing three ints into a long would overflow past ~2M vertices, so hash and verify.
            val key = s[0].toLong() * 73_856_093L xor s[1].toLong() * 19_349_663L xor s[2].toLong() * 83_492_791L
            byCorners.getOrPut(key) { mutableListOf() }.add(t)
        }
        for (bucket in byCorners.values) {
            if (bucket.size < 2) continue
            // Re-group within the hash bucket by the actual sorted corners, in case of a collision.
            val exact = HashMap<List<Int>, MutableList<Int>>()
            for (t in bucket) {
                val s = listOf(mesh.ia(t), mesh.ib(t), mesh.ic(t)).sorted()
                exact.getOrPut(s) { mutableListOf() }.add(t)
            }
            for (g in exact.values) if (g.size >= 2) out.add(g)
        }
        out
    }

    /** Edges with three or more incident faces, as `(u, v, faceCount)`. */
    val nonManifoldEdges: List<Triple<Int, Int, Int>> = topology.edges.indices
        .filter { topology.edgeTriangles[it].size > 2 }
        .map { Triple(topology.edges[it].first, topology.edges[it].second, topology.edgeTriangles[it].size) }

    /** Vertices whose incident faces do not form a single fan. */
    val nonManifoldVertices: List<Int> get() = topology.nonManifoldVertices

    /** Every open boundary loop, measured — see [BoundaryLoopInfo]. Largest first. */
    val boundaryLoops: List<BoundaryLoopInfo> =
        topology.boundaryLoops.map { measureLoop(mesh, it) }.sortedByDescending { it.edgeCount }

    /**
     * Boundary edges that [MeshTopology.boundaryLoops] could not walk into a closed ring.
     *
     * A vertex where two holes touch has two outgoing boundary edges, so the walk can take the wrong
     * one and never come home; that loop is abandoned. The count matters because those edges are holes
     * that [fillHoles] will not see, and a repair that reports "filled every loop" while these remain
     * is the exact overclaim this file exists to avoid.
     */
    val unwalkableBoundaryEdges: Int = run {
        val walked = topology.boundaryLoops.sumOf { it.size }
        (topology.boundaryEdges.size - walked).coerceAtLeast(0)
    }

    /** Interior edges whose two faces walk them the same way — the winding disagreements themselves. */
    val inconsistentEdges: List<Pair<Int, Int>> = topology.edges.indices.mapNotNull { e ->
        val ts = topology.edgeTriangles[e]
        if (ts.size != 2) return@mapNotNull null
        val (u, v) = topology.edges[e]
        if (senseOf(mesh, ts[0], u, v) == senseOf(mesh, ts[1], u, v)) topology.edges[e] else null
    }

    /** Each connected piece, measured — see [MeshComponentInfo]. Largest first. */
    val components: List<MeshComponentInfo> = run {
        val owner = IntArray(mesh.vertexCount) { -1 }
        topology.components.forEachIndexed { c, vs -> for (v in vs) owner[v] = c }
        val buckets = Array(topology.componentCount) { mutableListOf<Int>() }
        for (t in 0 until mesh.triangleCount) {
            val c = owner[mesh.ia(t)]
            if (c >= 0) buckets[c].add(t)
        }
        val total = mesh.triangleCount.coerceAtLeast(1).toDouble()
        buckets.mapIndexed { c, ts ->
            val closed = ts.all { t ->
                val a = mesh.ia(t); val b = mesh.ib(t); val cc = mesh.ic(t)
                listOf(a to b, b to cc, cc to a).all { (u, v) ->
                    u == v || topology.edgeOf(u, v).let { it >= 0 && topology.edgeTriangles[it].size == 2 }
                }
            }
            MeshComponentInfo(
                triangles = ts.toIntArray(),
                vertexCount = topology.components[c].size,
                isClosed = closed,
                signedVolume = partialSignedVolume(mesh, ts),
                triangleShare = ts.size / total,
            )
        }.sortedByDescending { it.triangles.size }
    }

    /** Closed components wound inward — accepted by Manifold, and a negative volume when they are. */
    val invertedComponents: List<MeshComponentInfo> =
        components.filter { it.isClosed && it.signedVolume < 0.0 }

    /** How many vertices [TriMesh.weld] at [weldTolerance] would merge away. */
    val weldableVertices: Int = if (mesh.vertexCount == 0) 0 else
        (mesh.vertexCount - mesh.weld(weldTolerance).vertexCount).coerceAtLeast(0)

    /** Vertices no triangle references. */
    val unreferencedVertices: Int = (0 until mesh.vertexCount).count { topology.vertexTriangles[it].isEmpty() }

    /**
     * Every defect found, **worst and most causal first**.
     *
     * Sorted by [MeshDefectSeverity] then [MeshDefectKind] declaration order. Everything
     * [MeshDefectSeverity.BLOCKING] here must go before Manifold will accept the mesh; see [blockers].
     */
    val defects: List<MeshDefect> = buildList {
        if (nonFiniteVertices.isNotEmpty()) add(MeshDefect(
            MeshDefectKind.NON_FINITE_POSITION, MeshDefectSeverity.BLOCKING, nonFiniteVertices.size,
            "vertices ${nonFiniteVertices.take(6)}${if (nonFiniteVertices.size > 6) " …" else ""} are NaN or infinite",
            "find the upstream NaN — a zero-length normal or a divide by zero in a generator. Nothing here can invent a position.",
        ))
        if (weldableVertices > 0) add(MeshDefect(
            MeshDefectKind.UNWELDED_VERTICES, MeshDefectSeverity.BLOCKING, weldableVertices,
            "$weldableVertices of ${mesh.vertexCount} vertices are duplicates within $weldTolerance; " +
                "until they are merged every seam reads as a hole",
            "TriMesh.weld($weldTolerance)",
        ))
        if (degenerateFaces.isNotEmpty()) add(MeshDefect(
            MeshDefectKind.DEGENERATE_FACE, MeshDefectSeverity.SUSPICIOUS, degenerateFaces.size,
            "${degenerateFaces.size} of ${mesh.triangleCount} triangles have no area " +
                "(Manifold tolerates these; they still break the Euler characteristic and curvature)",
            "TriMesh.removeDegenerateTriangles()",
        ))
        if (duplicateFaceGroups.isNotEmpty()) add(MeshDefect(
            MeshDefectKind.DUPLICATE_FACE, MeshDefectSeverity.BLOCKING, duplicateFaceGroups.sumOf { it.size - 1 },
            "${duplicateFaceGroups.size} groups of triangles share the same corners; each extra copy makes " +
                "its three edges non-manifold",
            "TriMesh.removeDuplicateFaces()",
        ))
        if (nonManifoldEdges.isNotEmpty()) add(MeshDefect(
            MeshDefectKind.NON_MANIFOLD_EDGE, MeshDefectSeverity.BLOCKING, nonManifoldEdges.size,
            "${nonManifoldEdges.size} edges have 3+ faces (worst: ${nonManifoldEdges.maxOf { it.third }})",
            "TriMesh.removeNonManifoldFins() removes dangling flaps; an edge where two closed sheets meet " +
                "needs a real decision and is not repaired automatically.",
        ))
        if (nonManifoldVertices.isNotEmpty()) add(MeshDefect(
            MeshDefectKind.NON_MANIFOLD_VERTEX, MeshDefectSeverity.SUSPICIOUS, nonManifoldVertices.size,
            "${nonManifoldVertices.size} vertices are pinch points (Manifold tolerates these on their own)",
            "no automatic repair — splitting a pinch point changes the shape, so it is a modelling decision.",
        ))
        // Reported on the boundary EDGE count, not the loop count, because the loop walk itself can
        // fail — see [unwalkableBoundaryEdges]. A mesh with holes whose rings could not be traced
        // still has holes, and a diagnosis that said "0 open loops" there would be actively misleading.
        if (topology.boundaryEdges.isNotEmpty()) add(MeshDefect(
            MeshDefectKind.OPEN_BOUNDARY, MeshDefectSeverity.BLOCKING, topology.boundaryEdges.size,
            "${topology.boundaryEdges.size} boundary edges in ${boundaryLoops.size} traced loop(s)" +
                (if (boundaryLoops.isEmpty()) "" else
                    ", edge counts ${boundaryLoops.take(8).map { it.edgeCount }}${if (boundaryLoops.size > 8) " …" else ""}") +
                (boundaryLoops.count { !it.isPlanar }.let { if (it == 0) "" else "; $it non-planar" }) +
                (boundaryLoops.count { !it.isSimple }.let { if (it == 0) "" else "; $it figure-eight" }),
            "TriMesh.fillHoles() — flat holes fill cleanly; a non-planar loop gets a flat cap over a wavy rim.",
        ))
        if (unwalkableBoundaryEdges > 0) add(MeshDefect(
            MeshDefectKind.UNWALKABLE_BOUNDARY, MeshDefectSeverity.BLOCKING, unwalkableBoundaryEdges,
            "$unwalkableBoundaryEdges boundary edges are in no closed ring, so fillHoles() cannot see them",
            "most often this is just inconsistent winding, which reverses some boundary half-edges and " +
                "breaks the walk — run TriMesh.fixWinding() and diagnose again. If it survives that, two " +
                "holes meet at one vertex and the pinch has to be split by hand.",
        ))
        if (inconsistentEdges.isNotEmpty()) add(MeshDefect(
            MeshDefectKind.INCONSISTENT_WINDING, MeshDefectSeverity.BLOCKING, inconsistentEdges.size,
            "${inconsistentEdges.size} interior edges have both faces walking them the same way",
            "TriMesh.fixWinding()",
        ))
        if (invertedComponents.isNotEmpty()) add(MeshDefect(
            MeshDefectKind.INVERTED_ORIENTATION, MeshDefectSeverity.SUSPICIOUS, invertedComponents.size,
            "${invertedComponents.size} closed components are wound inward (volume " +
                "${"%+.4g".format(invertedComponents.first().signedVolume)}). Manifold ACCEPTS this and " +
                "reports a negative volume, so the next boolean is silently wrong",
            "TriMesh.fixWinding() — its second stage flips a closed component whose volume is negative.",
        ))
        if (components.size > 1) add(MeshDefect(
            MeshDefectKind.MULTIPLE_COMPONENTS, MeshDefectSeverity.INFORMATIONAL, components.size,
            "${components.size} disjoint pieces, largest ${"%.1f".format(components.first().triangleShare * 100)}% " +
                "of the triangles. Manifold accepts this and sums the volumes",
            "nothing, unless you wanted one piece — then TriMesh.largestComponent().",
        ))
        if (unreferencedVertices > 0) add(MeshDefect(
            MeshDefectKind.UNREFERENCED_VERTEX, MeshDefectSeverity.INFORMATIONAL, unreferencedVertices,
            "$unreferencedVertices vertices are not used by any triangle",
            "TriMesh.removeUnusedVertices()",
        ))
    }.sortedWith(compareBy({ it.severity.ordinal }, { it.kind.ordinal }))

    /** Just the defects Manifold will refuse the mesh over. Empty means [TriMesh.toSolid] should work. */
    val blockers: List<MeshDefect> get() = defects.filter { it.severity == MeshDefectSeverity.BLOCKING }

    /** True when nothing [MeshDefectSeverity.BLOCKING] was found. */
    val isSolid: Boolean get() = blockers.isEmpty()

    /** One line: the counts, and the first blocker. */
    override fun toString(): String = buildString {
        append("MeshDiagnosis(${mesh.vertexCount}v ${mesh.triangleCount}t: ")
        append(if (isSolid) "solid" else "${blockers.size} blocking, ${defects.size} total; first: ${blockers.first().kind}")
        append(")")
    }

    /**
     * The whole diagnosis as readable text, worst first — the thing to `print` when a `toSolid()` threw.
     *
     * @param maxDefects stop after this many, so a pathological mesh does not fill the console.
     */
    fun report(maxDefects: Int = 20): String = buildString {
        appendLine("mesh: ${mesh.vertexCount} vertices, ${mesh.triangleCount} triangles, " +
            "chi=${topology.eulerCharacteristic}, ${components.size} component(s)")
        if (defects.isEmpty()) {
            appendLine("no defects — this is a closed, consistently wound, outward solid.")
            return@buildString
        }
        appendLine(if (isSolid) "no blocking defects; Manifold should accept this mesh."
            else "${blockers.size} blocking defect(s) — Manifold will refuse this mesh.")
        for ((i, d) in defects.take(maxDefects).withIndex()) {
            appendLine("${i + 1}. [${d.severity}] ${d.kind} x${d.count}")
            appendLine("     ${d.detail}")
            appendLine("     fix: ${d.fix}")
        }
        if (defects.size > maxDefects) appendLine("… and ${defects.size - maxDefects} more")
    }
}

/** Build (and do not cache) this mesh's [MeshDiagnosis]. See [MeshDiagnosis] for the vocabulary. */
fun TriMesh.diagnose(weldTolerance: Double = 1e-6): MeshDiagnosis = MeshDiagnosis(this, weldTolerance)

/** `+1` if triangle [t] walks `u -> v`, `-1` if `v -> u`, `0` if it does not use the edge. */
private fun senseOf(mesh: TriMesh, t: Int, u: Int, v: Int): Int {
    val a = mesh.ia(t); val b = mesh.ib(t); val c = mesh.ic(t)
    if ((a == u && b == v) || (b == u && c == v) || (c == u && a == v)) return 1
    if ((a == v && b == u) || (b == v && c == u) || (c == v && a == u)) return -1
    return 0
}

/** Signed volume of a subset of triangles — the per-component number [MeshComponentInfo] carries. */
private fun partialSignedVolume(mesh: TriMesh, triangles: List<Int>): Double {
    var sum = 0.0
    val p = mesh.positions
    for (t in triangles) {
        val a = mesh.ia(t) * 3; val b = mesh.ib(t) * 3; val c = mesh.ic(t) * 3
        val ax = p[a].toDouble(); val ay = p[a + 1].toDouble(); val az = p[a + 2].toDouble()
        val bx = p[b].toDouble(); val by = p[b + 1].toDouble(); val bz = p[b + 2].toDouble()
        val cx = p[c].toDouble(); val cy = p[c + 1].toDouble(); val cz = p[c + 2].toDouble()
        sum += (ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx)) / 6.0
    }
    return sum
}

/** Perimeter, diameter, planarity and Newell normal for one boundary ring. */
private fun measureLoop(mesh: TriMesh, ring: List<Int>): BoundaryLoopInfo {
    var perimeter = 0.0
    var cx = 0.0; var cy = 0.0; var cz = 0.0
    for (i in ring.indices) {
        val a = ring[i]; val b = ring[(i + 1) % ring.size]
        val dx = (mesh.x(b) - mesh.x(a)).toDouble()
        val dy = (mesh.y(b) - mesh.y(a)).toDouble()
        val dz = (mesh.z(b) - mesh.z(a)).toDouble()
        perimeter += sqrt(dx * dx + dy * dy + dz * dz)
        cx += mesh.x(a); cy += mesh.y(a); cz += mesh.z(a)
    }
    val n = ring.size.toDouble()
    cx /= n; cy /= n; cz /= n

    // Newell's normal: robust for a non-planar ring in a way that "cross two edges" is not.
    var nx = 0.0; var ny = 0.0; var nz = 0.0
    for (i in ring.indices) {
        val a = ring[i]; val b = ring[(i + 1) % ring.size]
        val axv = mesh.x(a).toDouble(); val ayv = mesh.y(a).toDouble(); val azv = mesh.z(a).toDouble()
        val bxv = mesh.x(b).toDouble(); val byv = mesh.y(b).toDouble(); val bzv = mesh.z(b).toDouble()
        nx += (ayv - byv) * (azv + bzv)
        ny += (azv - bzv) * (axv + bxv)
        nz += (axv - bxv) * (ayv + byv)
    }
    val nl = sqrt(nx * nx + ny * ny + nz * nz)
    if (nl > 1e-30) { nx /= nl; ny /= nl; nz /= nl } else { nx = 0.0; ny = 0.0; nz = 1.0 }

    var worstOffPlane = 0.0
    var diameter = 0.0
    for (v in ring) {
        val dx = mesh.x(v) - cx; val dy = mesh.y(v) - cy; val dz = mesh.z(v) - cz
        worstOffPlane = maxOf(worstOffPlane, abs(dx * nx + dy * ny + dz * nz))
        diameter = maxOf(diameter, 2.0 * sqrt(dx * dx + dy * dy + dz * dz))
    }
    return BoundaryLoopInfo(
        vertices = ring,
        perimeter = perimeter,
        diameter = diameter,
        planarity = if (diameter > 1e-30) worstOffPlane / diameter else 0.0,
        normal = Vec3(nx.toFloat(), ny.toFloat(), nz.toFloat()),
    )
}

// ==============================================================================================
// The repairs the old pipeline did not have
// ==============================================================================================

/**
 * Remove triangles that share all three corners with another triangle.
 *
 * A duplicated face is not a harmless extra: each copy adds itself to all three of its edges, so one
 * duplicate turns three perfectly good manifold edges into three non-manifold ones and Manifold
 * refuses the whole mesh. Exporters produce them by welding two parts that already touched, and no
 * amount of [TriMesh.weld] or [TriMesh.fixWinding] removes them — measured.
 *
 * Two cases, handled differently:
 *
 *  - **Same winding.** Plain redundancy. One copy is kept.
 *  - **Opposite winding, in equal numbers.** A zero-thickness sheet: two faces back to back enclosing
 *    nothing. Both are removed, because keeping either would leave a one-sided flap where there is no
 *    material. This is the only repair here that can *delete* surface you can see, so it is reported.
 *
 * A group with unequal numbers of each winding keeps a single face of the majority orientation.
 */
fun TriMesh.removeDuplicateFaces(): TriMesh {
    val groups = MeshDiagnosis(this).duplicateFaceGroups
    if (groups.isEmpty()) return this
    val drop = HashSet<Int>()
    for (g in groups) {
        val reference = intArrayOf(ia(g[0]), ib(g[0]), ic(g[0]))
        fun sameWinding(t: Int): Boolean {
            val a = ia(t); val b = ib(t); val c = ic(t)
            return (a == reference[0] && b == reference[1] && c == reference[2]) ||
                (a == reference[1] && b == reference[2] && c == reference[0]) ||
                (a == reference[2] && b == reference[0] && c == reference[1])
        }
        val same = g.filter { sameWinding(it) }
        val opposite = g.filter { !sameWinding(it) }
        val keep = when {
            same.size == opposite.size -> null            // cancels to nothing
            same.size > opposite.size -> same.first()
            else -> opposite.first()
        }
        for (t in g) if (t != keep) drop.add(t)
    }
    return filterTriangles { it !in drop }
}

/**
 * Remove dangling flaps: triangles that hang off a non-manifold edge instead of being part of the
 * closed surface.
 *
 * The test is conservative — a triangle goes only if it has **both** a non-manifold edge
 * (three or more faces, so the surface does not need it to be closed there) **and** a boundary edge
 * (one face, so it is hanging free at the other end). That is what a fin, a stray internal wall, or a
 * modelling mistake looks like, and it is not what two properly closed sheets meeting along a seam
 * looks like. The second case is left alone, and [MeshDiagnosis] says so, because choosing which sheet
 * survives is a modelling decision and not a repair.
 *
 * Iterated to a fixed point, because removing one fin can expose the next one behind it.
 *
 * @param maxRounds give up after this many passes rather than spin on a pathological mesh.
 */
fun TriMesh.removeNonManifoldFins(maxRounds: Int = 16): TriMesh {
    var current = this
    for (round in 0 until maxRounds) {
        val topo = current.topology()
        if (topo.nonManifoldEdges.isEmpty()) return current
        val drop = HashSet<Int>()
        for (t in 0 until current.triangleCount) {
            var hasNonManifold = false
            var hasBoundary = false
            val a = current.ia(t); val b = current.ib(t); val c = current.ic(t)
            for ((u, v) in listOf(a to b, b to c, c to a)) {
                if (u == v) continue
                val e = topo.edgeOf(u, v)
                if (e < 0) continue
                when {
                    topo.edgeTriangles[e].size > 2 -> hasNonManifold = true
                    topo.edgeTriangles[e].size == 1 -> hasBoundary = true
                }
            }
            if (hasNonManifold && hasBoundary) drop.add(t)
        }
        if (drop.isEmpty()) return current
        current = current.filterTriangles { it !in drop }
    }
    return current
}

// ==============================================================================================
// Hole filling
// ==============================================================================================

/** How one hole got filled — the difference between a fill you can trust and one you should look at. */
enum class HoleFillMethod {
    /** The loop had three edges; one triangle closed it exactly. Always correct. */
    SINGLE_TRIANGLE,

    /** Ear-clipped in the loop's own best-fit plane. Correct for any simple, roughly planar loop. */
    EAR_CLIP,

    /**
     * Ear clipping stalled, so the loop was fanned from its centroid. **Look at this one.** It closes
     * the mesh, which is what Manifold needs, but a concave or self-intersecting loop gets a cap that
     * passes through itself or through the model.
     */
    CENTROID_FAN,

    /** The loop was larger than the caller allowed. Nothing was added; the hole is still there. */
    SKIPPED_TOO_LARGE,
}

/**
 * What happened to one hole.
 *
 * @param loop the hole as it was measured before filling.
 * @param method how it was closed — see [HoleFillMethod].
 * @param trianglesAdded how many triangles the fill cost; `0` when the hole was skipped.
 * @param capAreaFraction this cap's area as a fraction of the repaired mesh's whole surface — see
 *   [isSuspect] for why a big one matters even when it is perfectly flat.
 * @param note the reason, when there is one worth reading.
 */
class HoleFillReport(
    val loop: BoundaryLoopInfo,
    val method: HoleFillMethod,
    val trianglesAdded: Int,
    val capAreaFraction: Double = 0.0,
    val note: String? = null,
) {
    /**
     * True when this fill is one to look at before trusting: fanned, non-planar, or skipped.
     *
     * # What this flag cannot tell you, demonstrated
     *
     * `isSuspect` catches fills whose *geometry* is doubtful. It does **not** catch a fill that is
     * geometrically spotless and simply the wrong shape, and no flag on this class can, because **no
     * local property of a hole distinguishes "this end should be a flat cap" from "the model
     * continued here."** Two measured cases settle it, and they point opposite ways:
     *
     * | case | rim | cap method | cap share of surface | result |
     * |---|---|---|---|---|
     * | uncapped cylinder | flat | clean ear clip | large (both lids) | volume error **0.00000** — exactly right |
     * | torus with its tube severed | flat | clean ear clip | small, 2.7% | volume error **4.2%**, and genus 1 becomes 0 |
     *
     * A size threshold was tried and removed: at any cut-off that flags the severed torus it also
     * flags the cylinder, whose caps are correct, and at 5% it flags the cylinder and *misses* the
     * torus entirely. The two are locally indistinguishable. [capAreaFraction] is still reported, as a
     * fact rather than a verdict — a cap that is a tenth of your model is worth a look whatever the
     * arithmetic says — but it does not set this flag.
     *
     * The only thing that catches the severed torus is comparison against the shape you meant, which
     * at run time you do not have. So the contract is: a clean `isSuspect == false` means the
     * cap's geometry is sound, **not** that the model is the one you started with.
     */
    val isSuspect: Boolean
        get() = method == HoleFillMethod.CENTROID_FAN ||
            method == HoleFillMethod.SKIPPED_TOO_LARGE ||
            !loop.isPlanar

    override fun toString(): String =
        "%s: %d edges -> %d triangles, %.1f%% of the surface%s".format(
            method, loop.edgeCount, trianglesAdded, capAreaFraction * 100, note?.let { " ($it)" } ?: "",
        )
}

/** The mesh with its holes closed, and the story of each one. */
class HoleFillResult(val mesh: TriMesh, val fills: List<HoleFillReport>) {
    /** Holes that were closed, whatever the method. */
    val filled: Int get() = fills.count { it.trianglesAdded > 0 }

    /** Holes still open because the caller's [TriMesh.fillHoles] limit refused them. */
    val skipped: Int get() = fills.count { it.trianglesAdded == 0 }

    /** Fills worth looking at before trusting — see [HoleFillReport.isSuspect]. */
    val suspect: List<HoleFillReport> get() = fills.filter { it.isSuspect }
}

/**
 * Close every open boundary loop with a triangulated cap.
 *
 * This is the step nothing in Field had, and it is what turns a scan or a badly exported CAD part
 * into something Manifold will accept. Each loop from [MeshTopology.boundaryLoops] is first split into
 * the simple rings it is really made of ([decomposeRing] — a traced ring can be a figure-eight through
 * a pinch vertex, and capping *that* as one polygon creates a non-manifold edge). Then, per ring:
 *
 *  1. the ring is reversed, because a cap must walk each boundary edge in the **opposite** direction
 *     to the surface triangle already on it — that is what makes the closed surface consistently
 *     wound, and getting it backwards produces a mesh that is closed and inside out along the patch;
 *  2. the ring's [Newell normal](https://en.wikipedia.org/wiki/Newell%27s_algorithm) gives a plane —
 *     Newell rather than "cross the first two edges", because a real hole is never quite flat and two
 *     nearly-parallel edges give a normal that is mostly noise;
 *  3. the ring is projected into that plane and ear-clipped;
 *  4. if ear clipping stalls, a centroid fan closes it anyway and the report says so.
 *
 * # When this produces a bad fill, stated plainly
 *
 * The simple algorithm has exactly two failure modes, and both are reported rather than hidden:
 *
 *  - **A non-planar loop.** The cap is a flat-ish disc stretched over a wavy rim, so it can dip inside
 *    the model or bulge out of it. [BoundaryLoopInfo.planarity] measures this before the fill happens
 *    and [BoundaryLoopInfo.isPlanar] is the threshold; anything past a couple of percent of the loop's
 *    own diameter is flagged [HoleFillReport.isSuspect]. The mesh will still be a valid solid — the
 *    volume will just be wrong. Fixing it properly needs advancing-front or Poisson filling, which is
 *    a different and much larger piece of work.
 *  - **A self-intersecting loop**, which is what makes ear clipping stall. The centroid fan then
 *    guarantees closure at the cost of a cap that passes through itself.
 *
 * There is also a case it cannot see at all: boundary edges that no ring walk can reach, because two
 * holes meet at a single vertex. Those are counted by [MeshDiagnosis.unwalkableBoundaryEdges] and this
 * function will leave them open — the mesh comes back still not a solid, and the diagnosis says why.
 *
 * @param maxEdges refuse a loop bigger than this rather than cap something the size of the model. The
 *   default is generous; lower it when you would rather see the hole than a wrong cap.
 */
fun TriMesh.fillHoles(maxEdges: Int = 10_000): HoleFillResult {
    val loops = topology().boundaryLoops
    if (loops.isEmpty()) return HoleFillResult(this, emptyList())

    // Each pending fill records where its triangles land, so the cap's share of the finished
    // surface can be measured once the mesh exists — see HoleFillReport.capAreaFraction.
    class Pending(
        val info: BoundaryLoopInfo,
        val method: HoleFillMethod,
        val firstTriangle: Int,
        val triangleCount: Int,
        val note: String?,
    )

    val pending = mutableListOf<Pending>()
    val extraPositions = mutableListOf<Float>()
    val extraIndices = mutableListOf<Int>()
    var nextVertex = vertexCount

    // A traced ring may be a figure-eight through a pinch vertex; each simple piece is a real hole.
    for (ring in loops.flatMap { decomposeRing(it) }) {
        val info = measureLoop(this, ring)
        val firstTriangle = triangleCount + extraIndices.size / 3
        if (ring.size > maxEdges) {
            pending.add(Pending(info, HoleFillMethod.SKIPPED_TOO_LARGE, firstTriangle, 0,
                "${ring.size} edges is past the maxEdges=$maxEdges limit"))
            continue
        }
        // A cap must traverse each boundary edge the other way round from the surface triangle on it.
        val fill = ring.reversed()
        if (fill.size == 3) {
            extraIndices.add(fill[0]); extraIndices.add(fill[1]); extraIndices.add(fill[2])
            pending.add(Pending(info, HoleFillMethod.SINGLE_TRIANGLE, firstTriangle, 1, null))
            continue
        }
        val projected = projectRing(this, fill)
        val clipped = if (projected == null) null else earClip(projected)
        if (clipped != null) {
            for (k in clipped.indices step 3) {
                extraIndices.add(fill[clipped[k]]); extraIndices.add(fill[clipped[k + 1]]); extraIndices.add(fill[clipped[k + 2]])
            }
            pending.add(Pending(info, HoleFillMethod.EAR_CLIP, firstTriangle, clipped.size / 3,
                if (info.isPlanar) null else "loop is non-planar (%.1f%% of its diameter) — the cap is flat and the model is not"
                    .format(info.planarity * 100)))
        } else {
            var cx = 0f; var cy = 0f; var cz = 0f
            for (v in fill) { cx += x(v); cy += y(v); cz += z(v) }
            cx /= fill.size; cy /= fill.size; cz /= fill.size
            val centre = nextVertex++
            extraPositions.add(cx); extraPositions.add(cy); extraPositions.add(cz)
            for (i in fill.indices) {
                extraIndices.add(fill[i]); extraIndices.add(fill[(i + 1) % fill.size]); extraIndices.add(centre)
            }
            pending.add(Pending(info, HoleFillMethod.CENTROID_FAN, firstTriangle, fill.size,
                "ear clipping stalled — the loop is not simple in its own plane, so this cap may self-intersect"))
        }
    }

    if (extraIndices.isEmpty()) {
        return HoleFillResult(this, pending.map { HoleFillReport(it.info, it.method, 0, 0.0, it.note) })
    }
    val pos = positions.copyOf(positions.size + extraPositions.size)
    for (i in extraPositions.indices) pos[positions.size + i] = extraPositions[i]
    val idx = indices.copyOf(indices.size + extraIndices.size)
    for (i in extraIndices.indices) idx[indices.size + i] = extraIndices[i]
    // Attributes are dropped: a cap has no sensible uv or colour, and a half-populated array is worse
    // than none. Call withVertexNormals() after if the result is going to a renderer.
    val filled = TriMesh(pos, idx)

    val totalArea = filled.surfaceArea()
    val reports = pending.map { p ->
        var capArea = 0.0
        for (t in p.firstTriangle until p.firstTriangle + p.triangleCount) capArea += filled.triangleArea(t)
        val fraction = if (totalArea > 0.0) capArea / totalArea else 0.0
        // Information, not a verdict: see HoleFillReport.isSuspect for why size cannot decide this.
        val note = p.note ?: if (fraction > 0.05)
            ("this cap is %.1f%% of the finished surface — its geometry is clean, but that much of the " +
                "model is being invented rather than restored").format(fraction * 100) else null
        HoleFillReport(p.info, p.method, p.triangleCount, fraction, note)
    }
    return HoleFillResult(filled, reports)
}

/**
 * Split a traced boundary ring into the simple rings it is really made of.
 *
 * A ring that repeats a vertex is a figure-eight: two holes that touch at a pinch point, chained into
 * one walk by [MeshTopology.boundaryLoops] because at that vertex there were two boundary edges to
 * leave by. Capping it as a single polygon is not merely inaccurate — the fan crosses itself and
 * produces a **non-manifold edge**, so the "repaired" mesh is worse than the broken one. Measured:
 * deleting six faces from an icosphere gives `[1, 3, 2, 9, 3, 21]`, and fanning it added an edge with
 * four incident faces.
 *
 * The split is the classic stack decomposition: walk the ring, and whenever a vertex is met that is
 * already on the stack, pop everything back to it and emit that as a sub-ring. Every original boundary
 * edge lands in exactly one sub-ring, in its original direction, so the caps still close the surface
 * exactly once and no cap shares an *edge* with another. They do share the pinch **vertex**, which
 * leaves a non-manifold vertex behind — and that is fine, because Manifold accepts those (measured;
 * see the file overview).
 */
private fun decomposeRing(ring: List<Int>): List<List<Int>> {
    if (ring.toSet().size == ring.size) return listOf(ring)
    val out = mutableListOf<List<Int>>()
    val stack = mutableListOf<Int>()
    val positionOf = HashMap<Int, Int>()
    for (v in ring) {
        val seen = positionOf[v]
        if (seen != null) {
            val sub = stack.subList(seen, stack.size).toList()
            for (k in seen until stack.size) positionOf.remove(stack[k])
            while (stack.size > seen) stack.removeAt(stack.size - 1)
            if (sub.size >= 3) out.add(sub)
        }
        positionOf[v] = stack.size
        stack.add(v)
    }
    if (stack.size >= 3) out.add(stack.toList())
    return out
}

/**
 * A ring's vertices in its own best-fit plane, as `(u, v)` pairs, oriented so that a counter-clockwise
 * ring has positive signed area. `null` when the ring is degenerate enough to have no plane.
 */
private fun projectRing(mesh: TriMesh, ring: List<Int>): List<Vec2>? {
    val info = measureLoop(mesh, ring)
    val n = info.normal
    if (n.x == 0f && n.y == 0f && n.z == 0f) return null
    // Any axis not parallel to n, so the cross product below is well conditioned.
    val seed = if (abs(n.x) < 0.9f) Vec3(1f, 0f, 0f) else Vec3(0f, 1f, 0f)
    val u = Vec3(seed).cross(n).normalize()
    // v = n x u makes u x v = n, so a ring that is counter-clockwise about n projects to positive area.
    val v = Vec3(n).cross(u).normalize()
    if (!u.x.isFinite() || !v.x.isFinite()) return null
    var cx = 0f; var cy = 0f; var cz = 0f
    for (i in ring) { cx += mesh.x(i); cy += mesh.y(i); cz += mesh.z(i) }
    cx /= ring.size; cy /= ring.size; cz /= ring.size
    val out = ring.map { i ->
        val dx = mesh.x(i) - cx; val dy = mesh.y(i) - cy; val dz = mesh.z(i) - cz
        Vec2(dx * u.x + dy * u.y + dz * u.z, dx * v.x + dy * v.y + dz * v.z)
    }
    // A negative area here means the Newell normal and the projection disagree, which only happens on a
    // ring so non-planar that neither is meaningful. Refuse, and let the caller fan it and say so.
    return if (polygonSignedArea(out) > 0.0) out else null
}

/**
 * Ear-clip a counter-clockwise simple polygon, returning index triples **into [polygon]**, or `null`
 * when it stalls.
 *
 * Distinct from [triangulatePolygon]: that one silently falls back to a fan when it cannot find an
 * ear, which is the right call for a modelling primitive and exactly the wrong one here. A repair
 * pipeline has to be able to say "this hole did not triangulate cleanly", so this variant returns
 * `null` instead of producing something wrong.
 */
private fun earClip(polygon: List<Vec2>): IntArray? {
    val order = polygon.indices.toMutableList()
    fun p(i: Int) = polygon[order[i]]
    fun cross(o: Vec2, a: Vec2, b: Vec2): Double =
        (a.x - o.x).toDouble() * (b.y - o.y) - (a.y - o.y).toDouble() * (b.x - o.x)
    fun inside(a: Vec2, b: Vec2, c: Vec2, q: Vec2): Boolean {
        val d1 = cross(a, b, q); val d2 = cross(b, c, q); val d3 = cross(c, a, q)
        return (d1 >= 0 && d2 >= 0 && d3 >= 0) || (d1 <= 0 && d2 <= 0 && d3 <= 0)
    }
    val out = ArrayList<Int>((polygon.size - 2) * 3)
    var guard = polygon.size * polygon.size + 16
    while (order.size > 3 && guard-- > 0) {
        var clipped = false
        for (i in order.indices) {
            val prev = (i + order.size - 1) % order.size
            val next = (i + 1) % order.size
            val a = p(prev); val b = p(i); val c = p(next)
            if (cross(a, b, c) <= 0.0) continue
            var blocked = false
            for (k in order.indices) {
                if (k == prev || k == i || k == next) continue
                if (inside(a, b, c, p(k))) { blocked = true; break }
            }
            if (blocked) continue
            out.add(order[prev]); out.add(order[i]); out.add(order[next])
            order.removeAt(i)
            clipped = true
            break
        }
        if (!clipped) return null // not simple in this plane — the caller must fan it and report that
    }
    if (order.size != 3) return null
    out.add(order[0]); out.add(order[1]); out.add(order[2])
    return out.toIntArray()
}

// ==============================================================================================
// The pipeline
// ==============================================================================================

/**
 * One thing a repair pipeline did, and how much of it.
 *
 * @param name the operation, as the reader would call it.
 * @param changed how many vertices or triangles it touched. `0` means it ran and found nothing to do.
 * @param detail the specifics.
 */
class RepairStep(val name: String, val changed: Int, val detail: String) {
    override fun toString(): String = "$name: $detail"
}

/**
 * What [TriMesh.repairToSolid] did, what it could not do, and the mesh that came out.
 *
 * The reason this is a result object rather than a returned mesh is that a repair pipeline that
 * returns only a mesh cannot be audited. Every number a person needs in order to decide whether to
 * trust the output is here: which steps changed something, how many holes were filled and by which
 * method, and — the important one — [unfixed], the blocking defects that are **still there**.
 *
 * @param mesh the repaired mesh. Not necessarily a solid; check [isSolid].
 * @param original the mesh as it came in.
 * @param steps every operation attempted, in order.
 * @param before the diagnosis of [original].
 * @param after the diagnosis of [mesh].
 * @param holeFills one entry per hole the pipeline met — see [HoleFillReport].
 */
class MeshRepairResult(
    val mesh: TriMesh,
    val original: TriMesh,
    val steps: List<RepairStep>,
    val before: MeshDiagnosis,
    val after: MeshDiagnosis,
    val holeFills: List<HoleFillReport>,
) {
    /** True when nothing blocking remains — [mesh] should now survive `toSolid()`. */
    val isSolid: Boolean get() = after.isSolid

    /**
     * The blocking defects still present, each carrying its own explanation of why nothing was done.
     *
     * An empty list here is the *claim*; it is only believable alongside the fact that the pipeline
     * reports the ones it failed on rather than dropping them. See [MeshDiagnosis.blockers].
     */
    val unfixed: List<MeshDefect> get() = after.blockers

    /** Defects Manifold will accept but which are probably still wrong — see [MeshDefectSeverity.SUSPICIOUS]. */
    val remainingWarnings: List<MeshDefect>
        get() = after.defects.filter { it.severity == MeshDefectSeverity.SUSPICIOUS }

    /** Fills worth looking at with your eyes before trusting the volume. */
    val suspectFills: List<HoleFillReport> get() = holeFills.filter { it.isSuspect }

    override fun toString(): String =
        "MeshRepairResult(${if (isSolid) "solid" else "${unfixed.size} unfixed"}, " +
            "${original.triangleCount}t -> ${mesh.triangleCount}t, ${steps.count { it.changed > 0 }} steps did something)"

    /** The whole story as readable text: what changed, what was filled, and what is still wrong. */
    fun report(): String = buildString {
        appendLine("repair: ${original.vertexCount}v ${original.triangleCount}t -> ${mesh.vertexCount}v ${mesh.triangleCount}t")
        appendLine("  before: ${before.blockers.size} blocking, ${before.defects.size} defects total")
        for (s in steps) appendLine("  - $s")
        if (holeFills.isNotEmpty()) {
            val byMethod = holeFills.groupingBy { it.method }.eachCount()
            appendLine("  holes: ${holeFills.size} loops — $byMethod")
            for (f in suspectFills.take(8)) appendLine("      suspect $f")
            if (suspectFills.size > 8) appendLine("      … and ${suspectFills.size - 8} more suspect fills")
        }
        if (isSolid) {
            appendLine("  RESULT: a valid solid.")
        } else {
            appendLine("  RESULT: NOT a solid — ${unfixed.size} blocking defect(s) could not be fixed:")
            for (d in unfixed) {
                appendLine("      ${d.kind} x${d.count}: ${d.detail}")
                appendLine("        why not: ${d.fix}")
            }
        }
        for (w in remainingWarnings) appendLine("  warning: ${w.kind} x${w.count} — ${w.detail}")
    }
}

/**
 * Try to turn this mesh into something [afield.manifold.toSolid] will accept, and report what it could not fix.
 *
 * # The order, and why it is that order
 *
 * Each step exists because the next one would give a wrong answer without it. This is not a taste
 * judgement; it is the causal chain, and it is why a "just call them all" pipeline in the wrong order
 * fails on real meshes:
 *
 *  1. **Weld.** Nothing else can run first. An unwelded mesh reports every shared edge as two boundary
 *     edges, so hole counting, component counting and winding propagation are all nonsense until the
 *     duplicated vertices are merged. Measured: welding alone fixes four of the twelve broken meshes
 *     in the test table.
 *  2. **Drop degenerate triangles.** A zero-area face contributes edges that confuse the boundary walk
 *     without contributing surface.
 *  3. **Drop duplicate faces.** A duplicated face makes three edges non-manifold, and a non-manifold
 *     edge stops the winding flood-fill from crossing it.
 *  4. **Remove dangling fins.** Same reason, different cause.
 *  5. **Drop unused vertices.** Cosmetic, but it makes the counts in the report mean something.
 *  6. **Fix winding.** Must come *before* filling, because a cap's winding is chosen to oppose the
 *     boundary loop's direction, and that direction is only meaningful once the surface around it
 *     agrees with itself.
 *  7. **Fill holes.** See [fillHoles] for what it does and does not do well.
 *  8. **Fix winding again.** Not redundant: [TriMesh.fixWinding]'s second stage only flips a component
 *     that is *closed*, because "outward" is undefined for an open sheet. Before step 7 every holed
 *     component was open and therefore skipped; after it they are closed and can finally be pointed
 *     the right way. This is the step that stops a repaired mesh from coming back inside out with a
 *     negative volume.
 *
 * # What it will not do
 *
 * It will not invent a position for a `NaN`, split a pinch vertex, choose between two closed sheets
 * that meet along an edge, or reach a boundary edge that no loop walk can get to. All four come back
 * in [MeshRepairResult.unfixed] with the reason attached, and the result's [MeshRepairResult.isSolid]
 * is `false`. That is deliberate: a pipeline that reports total success is either lying or untested.
 *
 * @param weldTolerance passed to [TriMesh.weld]. `1e-6` suits a model normalised to about unit size;
 *   scale it with the model, or use [TriMesh.meanEdgeLength] over a thousand.
 * @param fillHoles close open boundary loops. Turn off to see the holes rather than a cap over them.
 * @param maxHoleEdges refuse to cap a loop bigger than this — see [TriMesh.fillHoles].
 * @param keepLargestComponentOnly throw away every piece but the biggest. Off by default, because
 *   several disjoint pieces is legal and Manifold sums their volumes — measured, not assumed.
 */
fun TriMesh.repairToSolid(
    weldTolerance: Double = 1e-6,
    fillHoles: Boolean = true,
    maxHoleEdges: Int = 10_000,
    keepLargestComponentOnly: Boolean = false,
): MeshRepairResult {
    val before = MeshDiagnosis(this, weldTolerance)
    val steps = mutableListOf<RepairStep>()
    var m = this

    // A NaN position is refused rather than repaired, and the distinction is deliberate. The pipeline
    // *could* limp on: removeDegenerateTriangles drops the affected faces (a NaN area fails every
    // comparison), fillHoles caps the gap, and a valid-looking solid comes out. But that solid is
    // missing whatever the broken triangles described, with no way to tell how much, so the only
    // honest answer is to stop and name the upstream bug.
    if (before.nonFiniteVertices.isNotEmpty()) {
        steps.add(RepairStep(
            "refused", 0,
            "${before.nonFiniteVertices.size} vertices are NaN or infinite — stopping. Repairing around " +
                "them would silently delete the triangles that use them and cap the gap.",
        ))
        return MeshRepairResult(this, this, steps, before, before, emptyList())
    }

    fun step(name: String, detail: (TriMesh, TriMesh) -> Pair<Int, String>, op: (TriMesh) -> TriMesh) {
        val next = op(m)
        val (changed, text) = detail(m, next)
        steps.add(RepairStep(name, changed, text))
        m = next
    }

    step("weld", { a, b -> (a.vertexCount - b.vertexCount) to "merged ${a.vertexCount - b.vertexCount} duplicate vertices" }) {
        it.weld(weldTolerance)
    }
    step("removeDegenerateTriangles", { a, b -> (a.triangleCount - b.triangleCount) to "dropped ${a.triangleCount - b.triangleCount} zero-area triangles" }) {
        it.removeDegenerateTriangles()
    }
    step("removeDuplicateFaces", { a, b -> (a.triangleCount - b.triangleCount) to "dropped ${a.triangleCount - b.triangleCount} duplicated faces" }) {
        it.removeDuplicateFaces()
    }
    step("removeNonManifoldFins", { a, b -> (a.triangleCount - b.triangleCount) to "dropped ${a.triangleCount - b.triangleCount} dangling triangles" }) {
        it.removeNonManifoldFins()
    }
    step("removeUnusedVertices", { a, b -> (a.vertexCount - b.vertexCount) to "dropped ${a.vertexCount - b.vertexCount} unreferenced vertices" }) {
        it.removeUnusedVertices()
    }
    step("fixWinding", { a, b -> countReorientations(a, b) to "reoriented ${countReorientations(a, b)} faces" }) {
        it.fixWinding()
    }

    var fills = emptyList<HoleFillReport>()
    if (fillHoles) {
        val result = m.fillHoles(maxHoleEdges)
        fills = result.fills
        val added = result.mesh.triangleCount - m.triangleCount
        steps.add(RepairStep(
            "fillHoles", result.filled,
            "closed ${result.filled} of ${result.fills.size} holes with $added triangles" +
                (if (result.skipped > 0) ", skipped ${result.skipped} as too large" else "") +
                (if (result.suspect.isNotEmpty()) ", ${result.suspect.size} need looking at" else ""),
        ))
        m = result.mesh
        // Only now is every component closed, so only now can fixWinding decide which way is out.
        step("fixWinding (after fill)", { a, b -> countReorientations(a, b) to "reoriented ${countReorientations(a, b)} faces once the caps made the components closed" }) {
            it.fixWinding()
        }
    }

    if (keepLargestComponentOnly) {
        step("largestComponent", { a, b -> (a.triangleCount - b.triangleCount) to "dropped ${a.triangleCount - b.triangleCount} triangles in smaller pieces" }) {
            it.largestComponent()
        }
    }

    return MeshRepairResult(m, this, steps, before, MeshDiagnosis(m, weldTolerance), fills)
}

/**
 * How many triangles changed orientation between two meshes with the same index buffer length.
 *
 * [TriMesh.fixWinding] does not report what it flipped, and "reoriented N faces" is exactly the number
 * a person reading a repair log wants, so it is recovered by comparison. Returns `0` when the two
 * meshes are not comparable triangle for triangle.
 */
private fun countReorientations(a: TriMesh, b: TriMesh): Int {
    if (a.triangleCount != b.triangleCount) return 0
    var n = 0
    for (t in 0 until a.triangleCount) {
        if (a.ia(t) != b.ia(t) || a.ib(t) != b.ib(t) || a.ic(t) != b.ic(t)) n++
    }
    return n
}
