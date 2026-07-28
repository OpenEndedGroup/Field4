@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * What a [TriMesh] *is*, as opposed to where it is: adjacency, boundaries, components,
 * manifoldness, orientation, and the Euler characteristic those add up to — plus the repairs
 * ([TriMesh.weld], [TriMesh.fixWinding], [TriMesh.removeDegenerateTriangles]) that turn a mesh
 * which fails those checks into one that passes.
 *
 * [MeshTopology] is a **derived, cached** view: building one costs one pass over the triangles, and
 * everything after that is a lookup. Build it once and ask it many questions; the convenience
 * properties on [TriMesh] ([TriMesh.isWatertight] and friends) build a throwaway one each time,
 * which is fine interactively and wasteful in a loop.
 *
 * The interesting invariant, and the one the tests lean on: for a closed orientable surface,
 * `V - E + F = 2 - 2g`. So a cube, a sphere and an icosphere all report
 * [MeshTopology.eulerCharacteristic] `2` and [MeshTopology.genus] `0`, a torus reports `0` and `1`,
 * and a two-holed shape reports `-2` and `2`. That single number catches a startling range of
 * generator bugs — a duplicated seam, a missing cap, an unwelded march — because all of them change
 * it.
 *
 * ```kotlin
 * val t = torusMesh(48, 24)
 * val topo = t.topology()
 * println("${topo.eulerCharacteristic} χ, genus ${topo.genus}, watertight ${topo.isWatertight}")
 * // 0 χ, genus 1, watertight true
 * ```
 */

// ==============================================================================================
// MeshTopology
// ==============================================================================================

/**
 * Cached connectivity for one [TriMesh].
 *
 * Edges are undirected and de-duplicated: two triangles sharing corners `(a,b)` share one edge
 * here, however they wound it. That is what makes the Euler characteristic meaningful and what
 * makes [boundaryEdges] the edges with exactly one incident triangle.
 *
 * Nothing in here welds: two vertices at the same position but with different indices are two
 * vertices, and the topology will honestly report the resulting crack. Call [TriMesh.weld] first if
 * that is not what you meant.
 */
class MeshTopology(val mesh: TriMesh) {

    /** Undirected edges as packed `(lowIndex, highIndex)` pairs, in first-seen order. */
    val edges: List<Pair<Int, Int>>

    /** For each entry of [edges], the triangles incident on it. Size 1 = boundary, 2 = interior, >2 = non-manifold. */
    val edgeTriangles: List<IntArray>

    /** For each vertex, the triangles that use it. */
    val vertexTriangles: List<IntArray>

    private val edgeIndex = HashMap<Long, Int>()

    init {
        val n = mesh.vertexCount
        val stride = (n + 1).toLong()
        val incident = ArrayList<MutableList<Int>>()
        val edgeList = ArrayList<Pair<Int, Int>>()
        val perVertex = Array(n) { mutableListOf<Int>() }
        for (t in 0 until mesh.triangleCount) {
            val a = mesh.ia(t); val b = mesh.ib(t); val c = mesh.ic(t)
            perVertex[a].add(t)
            if (b != a) perVertex[b].add(t)
            if (c != a && c != b) perVertex[c].add(t)
            for ((u, v) in listOf(a to b, b to c, c to a)) {
                if (u == v) continue // a degenerate triangle contributes no edge here
                val lo = if (u < v) u else v
                val hi = if (u < v) v else u
                val key = lo.toLong() * stride + hi
                val e = edgeIndex.getOrPut(key) {
                    edgeList.add(lo to hi)
                    incident.add(mutableListOf())
                    edgeList.size - 1
                }
                incident[e].add(t)
            }
        }
        edges = edgeList
        edgeTriangles = incident.map { it.toIntArray() }
        vertexTriangles = perVertex.map { it.toIntArray() }
    }

    /** The index into [edges] of the edge joining [u] and [v], or `-1` if there is none. */
    fun edgeOf(u: Int, v: Int): Int {
        val stride = (mesh.vertexCount + 1).toLong()
        val lo = if (u < v) u else v
        val hi = if (u < v) v else u
        return edgeIndex[lo.toLong() * stride + hi] ?: -1
    }

    /** Number of distinct undirected edges. */
    val edgeCount: Int get() = edges.size

    /** Edges with exactly one incident triangle — the mesh's open border. */
    val boundaryEdges: List<Pair<Int, Int>> by lazy {
        edges.filterIndexed { i, _ -> edgeTriangles[i].size == 1 }
    }

    /** Edges shared by three or more triangles: the classic non-manifold defect. */
    val nonManifoldEdges: List<Pair<Int, Int>> by lazy {
        edges.filterIndexed { i, _ -> edgeTriangles[i].size > 2 }
    }

    /**
     * Vertices whose incident triangles do **not** form a single fan — two cones meeting at a point,
     * or a bowtie. Legal geometry, illegal for most mesh algorithms (subdivision, decimation and
     * curvature all assume a disc-like neighbourhood), so it is worth knowing about.
     *
     * Detected by walking triangle-to-triangle through shared edges within each vertex's own star
     * and checking that one walk reaches all of them.
     */
    val nonManifoldVertices: List<Int> by lazy {
        val out = mutableListOf<Int>()
        for (v in 0 until mesh.vertexCount) {
            val star = vertexTriangles[v]
            if (star.size < 2) continue
            val local = HashMap<Int, Int>(star.size * 2)
            star.forEachIndexed { i, t -> local[t] = i }
            val seen = BooleanArray(star.size)
            val stack = ArrayDeque<Int>()
            stack.addLast(0); seen[0] = true
            var reached = 1
            while (stack.isNotEmpty()) {
                val t = star[stack.removeLast()]
                // The two edges of t that touch v; a neighbour across one of them is in the same fan.
                val corners = intArrayOf(mesh.ia(t), mesh.ib(t), mesh.ic(t))
                for (c in corners) {
                    if (c == v) continue
                    val e = edgeOf(v, c)
                    if (e < 0) continue
                    for (nt in edgeTriangles[e]) {
                        val li = local[nt] ?: continue
                        if (!seen[li]) { seen[li] = true; reached++; stack.addLast(li) }
                    }
                }
            }
            if (reached != star.size) out.add(v)
        }
        out
    }

    /** True when every edge has one or two triangles and every vertex star is a single fan. */
    val isManifold: Boolean get() = nonManifoldEdges.isEmpty() && nonManifoldVertices.isEmpty()

    /** True when the mesh is manifold and has no boundary — a closed surface enclosing a volume. */
    val isWatertight: Boolean get() = isManifold && boundaryEdges.isEmpty()

    /**
     * True when neighbouring triangles agree about which side is out: every interior edge is
     * traversed in **opposite** directions by its two triangles.
     *
     * This is orientation *consistency*, not outward-ness. A sphere wound entirely inside-out passes
     * this and has a negative [TriMesh.signedVolume]; [TriMesh.fixWinding] fixes both.
     */
    val isConsistentlyOriented: Boolean by lazy {
        edges.indices.all { e ->
            val ts = edgeTriangles[e]
            if (ts.size != 2) return@all true
            val (u, v) = edges[e]
            directedSense(ts[0], u, v) != directedSense(ts[1], u, v)
        }
    }

    /** `+1` if triangle [t] walks `u -> v`, `-1` if it walks `v -> u`, `0` if it does not use the edge. */
    private fun directedSense(t: Int, u: Int, v: Int): Int {
        val a = mesh.ia(t); val b = mesh.ib(t); val c = mesh.ic(t)
        if ((a == u && b == v) || (b == u && c == v) || (c == u && a == v)) return 1
        if ((a == v && b == u) || (b == v && c == u) || (c == v && a == u)) return -1
        return 0
    }

    /**
     * The open borders, each as a closed ring of vertex indices.
     *
     * Walked along the **directed** boundary half-edges, so each loop comes back in a consistent
     * direction around the hole. A cylinder with no caps yields two loops; a closed solid yields
     * none. A vertex where two holes touch defeats the walk, and that loop is dropped rather than
     * looped forever.
     */
    val boundaryLoops: List<List<Int>> by lazy {
        // Each boundary edge belongs to one triangle; take the direction that triangle walks it.
        val next = HashMap<Int, MutableList<Int>>()
        for (e in edges.indices) {
            val ts = edgeTriangles[e]
            if (ts.size != 1) continue
            val (u, v) = edges[e]
            val sense = directedSense(ts[0], u, v)
            val from = if (sense >= 0) u else v
            val to = if (sense >= 0) v else u
            next.getOrPut(from) { mutableListOf() }.add(to)
        }
        val loops = mutableListOf<List<Int>>()
        val remaining = HashMap(next.mapValues { it.value.toMutableList() })
        for (start in next.keys.sorted()) {
            while (remaining[start]?.isNotEmpty() == true) {
                val loop = mutableListOf(start)
                var current = remaining[start]!!.removeAt(0)
                var guard = 0
                while (current != start && guard++ <= edges.size) {
                    loop.add(current)
                    val outs = remaining[current]
                    if (outs == null || outs.isEmpty()) { loop.clear(); break }
                    current = outs.removeAt(0)
                }
                if (loop.size >= 3) loops.add(loop)
            }
        }
        loops
    }

    /** Connected components as vertex-index lists, joined through shared triangles. */
    val components: List<List<Int>> by lazy {
        val parent = IntArray(mesh.vertexCount) { it }
        fun find(a: Int): Int { var r = a; while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }; return r }
        fun union(a: Int, b: Int) { val ra = find(a); val rb = find(b); if (ra != rb) parent[ra] = rb }
        for (t in 0 until mesh.triangleCount) {
            union(mesh.ia(t), mesh.ib(t)); union(mesh.ib(t), mesh.ic(t))
        }
        val groups = LinkedHashMap<Int, MutableList<Int>>()
        // Only vertices a triangle actually uses; a stray unreferenced vertex is not a component.
        for (v in 0 until mesh.vertexCount) {
            if (vertexTriangles[v].isEmpty()) continue
            groups.getOrPut(find(v)) { mutableListOf() }.add(v)
        }
        groups.values.toList()
    }

    /** Number of connected components (ignoring vertices no triangle references). */
    val componentCount: Int get() = components.size

    /**
     * `V - E + F` over the vertices triangles actually use.
     *
     * `2` for any closed genus-0 solid — cube, sphere, icosphere, extruded convex polygon with caps.
     * `0` for a torus. `1` for a single disc (a plane grid). Unreferenced vertices are excluded so a
     * mesh with a dangling vertex still reports the characteristic of the surface you can see.
     */
    val eulerCharacteristic: Int
        get() {
            val usedVertices = (0 until mesh.vertexCount).count { vertexTriangles[it].isNotEmpty() }
            return usedVertices - edgeCount + mesh.triangleCount
        }

    /**
     * Genus from `χ = 2c - 2g - b` — the number of handles, `1` for a torus, `2` for a two-holed
     * solid — where `c` is [componentCount] and `b` the number of [boundaryLoops].
     *
     * Only meaningful for an orientable manifold surface; `null` when [isManifold] is false, or when
     * the arithmetic does not come out to a whole number of handles (which is itself a sign the mesh
     * is not the surface you think it is).
     */
    val genus: Int?
        get() {
            if (!isManifold) return null
            val twiceG = 2 * componentCount - eulerCharacteristic - boundaryLoops.size
            return if (twiceG >= 0 && twiceG % 2 == 0) twiceG / 2 else null
        }

    /** The neighbours of [v] — every vertex sharing an edge with it, in no particular order. */
    fun neighbors(v: Int): IntArray {
        val out = LinkedHashSet<Int>()
        for (t in vertexTriangles[v]) {
            val a = mesh.ia(t); val b = mesh.ib(t); val c = mesh.ic(t)
            if (a == v) { out.add(b); out.add(c) }
            if (b == v) { out.add(a); out.add(c) }
            if (c == v) { out.add(a); out.add(b) }
        }
        out.remove(v)
        return out.toIntArray()
    }

    /** True when [v] sits on an open boundary. */
    fun isBoundaryVertex(v: Int): Boolean =
        edges.indices.any { e -> edgeTriangles[e].size == 1 && (edges[e].first == v || edges[e].second == v) }

    /** Every vertex that sits on an open boundary. */
    val boundaryVertices: Set<Int> by lazy {
        val s = LinkedHashSet<Int>()
        for (e in edges.indices) {
            if (edgeTriangles[e].size == 1) { s.add(edges[e].first); s.add(edges[e].second) }
        }
        s
    }

    /** One line of the facts most worth printing: counts, χ, genus, and what is broken. */
    override fun toString(): String = buildString {
        append("MeshTopology(V=${mesh.vertexCount} E=$edgeCount F=${mesh.triangleCount}")
        append(" χ=$eulerCharacteristic")
        append(" components=$componentCount")
        append(" boundaryLoops=${boundaryLoops.size}")
        genus?.let { append(" genus=$it") }
        if (!isManifold) append(" NON-MANIFOLD(${nonManifoldEdges.size} edges, ${nonManifoldVertices.size} vertices)")
        if (!isConsistentlyOriented) append(" INCONSISTENT-WINDING")
        append(")")
    }
}

/** Build (and do not cache) this mesh's [MeshTopology]. Hold on to the result if you will ask twice. */
fun TriMesh.topology(): MeshTopology = MeshTopology(this)

/** True when this mesh is a closed manifold surface — see [MeshTopology.isWatertight]. */
fun TriMesh.isWatertight(): Boolean = topology().isWatertight

/** True when this mesh is a manifold surface — see [MeshTopology.isManifold]. */
fun TriMesh.isManifold(): Boolean = topology().isManifold

/** `V - E + F` — see [MeshTopology.eulerCharacteristic]. */
fun TriMesh.eulerCharacteristic(): Int = topology().eulerCharacteristic

/** Number of handles, or `null` when the mesh is not an orientable manifold — see [MeshTopology.genus]. */
fun TriMesh.genus(): Int? = topology().genus

/** Each open border as an [afield.graphics.FLine]-ready ring of vertex indices. */
fun TriMesh.boundaryLoops(): List<List<Int>> = topology().boundaryLoops

// ==============================================================================================
// Repairs
// ==============================================================================================

/**
 * Merge vertices closer together than [tolerance] and renumber the triangles onto the survivors.
 *
 * This is the operation that turns "a pile of triangles" into "a mesh": an STL file, a marching
 * pass done per-cell, or a `TriMesh.ofTriangles` list all have three unshared vertices per triangle,
 * so their [MeshTopology] reports every edge as a boundary and every solid as full of holes. Weld
 * them and the same geometry becomes watertight.
 *
 * Implemented by snapping to a grid of side [tolerance] and checking the 27 neighbouring cells, so
 * it is O(n) rather than O(n²) and two points either side of a cell wall still merge. Attributes are
 * taken from the **first** vertex of each merged group (averaging normals across a genuine crease
 * would round it off).
 *
 * @param tolerance absolute distance. `0` snaps only exactly-equal positions.
 */
fun TriMesh.weld(tolerance: Double = 1e-6): TriMesh {
    if (vertexCount == 0) return this
    val tol = tolerance.coerceAtLeast(0.0)
    val cell = if (tol <= 0.0) 0.0 else tol
    val tol2 = (tol * tol).toFloat()

    // grid -> representative vertex indices in that cell
    val grid = HashMap<Long, MutableList<Int>>()
    val remap = IntArray(vertexCount) { -1 }
    val keptOriginal = ArrayList<Int>()

    fun key(ix: Long, iy: Long, iz: Long): Long =
        ix * 73_856_093L xor iy * 19_349_663L xor iz * 83_492_791L

    for (v in 0 until vertexCount) {
        val px = x(v); val py = y(v); val pz = z(v)
        var found = -1
        if (cell <= 0.0) {
            val k = key(px.toRawBits().toLong(), py.toRawBits().toLong(), pz.toRawBits().toLong())
            grid[k]?.firstOrNull { o -> x(o) == px && y(o) == py && z(o) == pz }?.let { found = it }
            if (found < 0) {
                grid.getOrPut(k) { mutableListOf() }.add(v)
            }
        } else {
            val cx = floor(px / cell).roundToLong()
            val cy = floor(py / cell).roundToLong()
            val cz = floor(pz / cell).roundToLong()
            outer@ for (dx in -1L..1L) for (dy in -1L..1L) for (dz in -1L..1L) {
                val bucket = grid[key(cx + dx, cy + dy, cz + dz)] ?: continue
                for (o in bucket) {
                    val ddx = x(o) - px; val ddy = y(o) - py; val ddz = z(o) - pz
                    if (ddx * ddx + ddy * ddy + ddz * ddz <= tol2) { found = o; break@outer }
                }
            }
            if (found < 0) grid.getOrPut(key(cx, cy, cz)) { mutableListOf() }.add(v)
        }
        if (found >= 0) {
            remap[v] = remap[found]
        } else {
            remap[v] = keptOriginal.size
            keptOriginal.add(v)
        }
    }

    val n = keptOriginal.size
    if (n == vertexCount) {
        // Nothing merged; still renumbering would be a pointless copy.
        return this
    }
    val pos = FloatArray(n * 3)
    val nor = if (normals != null) FloatArray(n * 3) else null
    val tex = if (uvs != null) FloatArray(n * 2) else null
    val col = if (colors != null) FloatArray(n * 3) else null
    keptOriginal.forEachIndexed { d, src ->
        for (k in 0..2) {
            pos[d * 3 + k] = positions[src * 3 + k]
            nor?.set(d * 3 + k, normals!![src * 3 + k])
            col?.set(d * 3 + k, colors!![src * 3 + k])
        }
        if (tex != null) { tex[d * 2] = uvs!![src * 2]; tex[d * 2 + 1] = uvs[src * 2 + 1] }
    }
    val idx = IntArray(indices.size) { remap[indices[it]] }
    return TriMesh(pos, idx, nor, tex, col)
}

/**
 * Drop triangles with no area — a repeated index, or three collinear corners — and any vertex that
 * leaves unreferenced.
 *
 * A UV sphere legitimately has `2 * segments` of these where its quad lattice collapses at the
 * poles, and they cost a renderer nothing, but they break the Euler characteristic and confuse
 * curvature, so the analytic paths want them gone.
 *
 * @param areaEpsilon triangles with area at or below this are removed.
 */
fun TriMesh.removeDegenerateTriangles(areaEpsilon: Double = 1e-14): TriMesh {
    val keep = (0 until triangleCount).filter { t ->
        val a = ia(t); val b = ib(t); val c = ic(t)
        a != b && b != c && a != c && triangleArea(t) > areaEpsilon
    }.toHashSet()
    if (keep.size == triangleCount) return this
    return filterTriangles { it in keep }
}

/** Drop vertices no triangle references, renumbering the rest. */
fun TriMesh.removeUnusedVertices(): TriMesh = filterTriangles { true }

/**
 * Make the winding consistent across the whole mesh, then make it point **outward**.
 *
 * Two stages, because they are two different problems:
 *
 *  1. *Consistency* — flood-fill triangle to triangle across shared edges, flipping any neighbour
 *     that traverses the shared edge the same way as its parent (two triangles agreeing about an
 *     edge's direction means they disagree about which side is out). Runs per connected component.
 *  2. *Outwardness* — with the component consistent, its [TriMesh.signedVolume] is positive if the
 *     normals point out and negative if in, so a negative volume flips the whole component. Skipped
 *     for a component with an open boundary, where "out" is not defined by the mesh alone.
 *
 * Normals are dropped when anything was flipped, since a stale one would now disagree with its
 * triangle; follow with [withVertexNormals].
 */
fun TriMesh.fixWinding(): TriMesh {
    if (triangleCount == 0) return this
    val topo = topology()
    val idx = indices.copyOf()
    var flippedAny = false

    fun senseIn(triangle: Int, u: Int, v: Int): Int {
        val a = idx[triangle * 3]; val b = idx[triangle * 3 + 1]; val c = idx[triangle * 3 + 2]
        if ((a == u && b == v) || (b == u && c == v) || (c == u && a == v)) return 1
        if ((a == v && b == u) || (b == v && c == u) || (c == v && a == u)) return -1
        return 0
    }
    fun flip(triangle: Int) {
        val tmp = idx[triangle * 3 + 1]
        idx[triangle * 3 + 1] = idx[triangle * 3 + 2]
        idx[triangle * 3 + 2] = tmp
        flippedAny = true
    }

    val visited = BooleanArray(triangleCount)
    val componentTriangles = mutableListOf<MutableList<Int>>()
    for (seed in 0 until triangleCount) {
        if (visited[seed]) continue
        val group = mutableListOf<Int>()
        val stack = ArrayDeque<Int>()
        stack.addLast(seed); visited[seed] = true
        while (stack.isNotEmpty()) {
            val t = stack.removeLast()
            group.add(t)
            val a = idx[t * 3]; val b = idx[t * 3 + 1]; val c = idx[t * 3 + 2]
            for ((u, v) in listOf(a to b, b to c, c to a)) {
                if (u == v) continue
                val e = topo.edgeOf(u, v)
                if (e < 0) continue
                for (nt in topo.edgeTriangles[e]) {
                    if (nt == t || visited[nt]) continue
                    // Neighbours of a consistently oriented pair traverse the shared edge oppositely.
                    if (senseIn(nt, u, v) == senseIn(t, u, v)) flip(nt)
                    visited[nt] = true
                    stack.addLast(nt)
                }
            }
        }
        componentTriangles.add(group)
    }

    // Stage 2, per component: a closed consistent component with negative volume is inside-out.
    val consistent = TriMesh(positions, idx)
    for (group in componentTriangles) {
        val closed = group.all { t ->
            val a = idx[t * 3]; val b = idx[t * 3 + 1]; val c = idx[t * 3 + 2]
            listOf(a to b, b to c, c to a).all { (u, v) ->
                u == v || topo.edgeOf(u, v).let { it >= 0 && topo.edgeTriangles[it].size == 2 }
            }
        }
        if (!closed) continue
        if (signedVolumeOf(consistent, group) < 0.0) {
            for (t in group) flip(t)
        }
    }

    if (!flippedAny) return this
    return TriMesh(positions, idx, null, uvs, colors)
}

/** Signed volume contribution of just [triangles] — used by [fixWinding] before the mesh is trustworthy. */
private fun signedVolumeOf(mesh: TriMesh, triangles: List<Int>): Double {
    var sum = 0.0
    for (t in triangles) {
        val a = mesh.ia(t) * 3; val b = mesh.ib(t) * 3; val c = mesh.ic(t) * 3
        val p = mesh.positions
        val ax = p[a].toDouble(); val ay = p[a + 1].toDouble(); val az = p[a + 2].toDouble()
        val bx = p[b].toDouble(); val by = p[b + 1].toDouble(); val bz = p[b + 2].toDouble()
        val cx = p[c].toDouble(); val cy = p[c + 1].toDouble(); val cz = p[c + 2].toDouble()
        sum += (ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx)) / 6.0
    }
    return sum
}

/**
 * Weld, drop degenerate triangles, drop unused vertices and fix the winding — the one call to make
 * before trusting a mesh that came from a file, a scanner, or a per-cell generator.
 *
 * @param tolerance passed to [weld].
 */
fun TriMesh.repaired(tolerance: Double = 1e-6): TriMesh =
    weld(tolerance).removeDegenerateTriangles().removeUnusedVertices().fixWinding()

/**
 * The largest connected component, by triangle count — the usual way to throw away the specks a
 * noisy iso-surface leaves around the shape you wanted.
 */
fun TriMesh.largestComponent(): TriMesh {
    val topo = topology()
    if (topo.componentCount <= 1) return this
    val owner = IntArray(vertexCount) { -1 }
    topo.components.forEachIndexed { c, vs -> for (v in vs) owner[v] = c }
    val counts = IntArray(topo.componentCount)
    for (t in 0 until triangleCount) {
        val c = owner[ia(t)]
        if (c >= 0) counts[c]++
    }
    val best = counts.indices.maxByOrNull { counts[it] } ?: return this
    return filterTriangles { owner[ia(it)] == best }
}

/** True when [a] and [b] describe the same positions and triangles to within [tolerance]. */
internal fun sameGeometry(a: TriMesh, b: TriMesh, tolerance: Double = 1e-6): Boolean {
    if (a.vertexCount != b.vertexCount || a.triangleCount != b.triangleCount) return false
    for (i in a.positions.indices) if (abs(a.positions[i] - b.positions[i]) > tolerance) return false
    return a.indices.contentEquals(b.indices)
}
