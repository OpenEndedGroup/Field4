package afield.geometry.path

import afield.graphics.render.color
import afield.graphics.render.filled
import afield.graphics.render.stroked
import afield.graphics.render.thickness
import afield.math.linalg.Vector4f
import org.ejml.data.DMatrixRMaj
import org.ejml.dense.row.factory.DecompositionFactory_DDRM
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The spectrum of a rhombus tiling's vertex graph. Put one site on every vertex and let a particle
 * hop along the edges: the Hamiltonian is the graph's adjacency matrix A (the "vertex model" of
 * Kohmoto & Sutherland, Phys. Rev. Lett. 56, 2740, 1986). On a Penrose tiling a fraction
 * 81 - 50 tau ~ 0.0983 of all eigenstates sit exactly at E = 0 and are strictly localised: zero
 * outside a finite cluster of vertices (Arai, Tokihiro, Fujiwara & Kohmoto, Phys. Rev. B 38, 1621,
 * 1988, who prove it as a lower bound and conjecture it exact). TilingGraph counts those states
 * exactly, by rank over a prime field, and finds individual ones by growing a cluster around a
 * vertex until a state confined to it exists.
 */

/**
 * The vertices and edges of a tiling as a graph, ready for spectral work. Vertices are sorted by
 * (x, y), so a vertex's neighbours have nearby indices; that keeps elimination fill-in small.
 *
 * Build one with [DeBruijnTiling.vertexGraph] or [TilingGraph.squareLattice].
 *
 * @param x vertex x coordinates, in the tiling's drawing units.
 * @param y vertex y coordinates.
 * @param neighbours for each vertex, the indices of the vertices one edge away, ascending.
 * @param edgeLength the length of every edge (the tiling's `scale`).
 */
class TilingGraph(
    val x: DoubleArray,
    val y: DoubleArray,
    val neighbours: Array<IntArray>,
    val edgeLength: Double,
) {
    /** Number of vertices. */
    val vertexCount: Int get() = x.size

    /** Number of edges. */
    val edgeCount: Int get() = neighbours.sumOf { it.size } / 2

    /** The number of edges at vertex [v]. */
    fun degree(v: Int): Int = neighbours[v].size

    /** How many vertices have each degree, keyed by degree. */
    fun degreeCounts(): Map<Int, Int> = neighbours.groupingBy { it.size }.eachCount().toSortedMap()

    /** The vertex nearest (px, py). */
    fun nearest(px: Double, py: Double): Int = x.indices.minBy { hypot(x[it] - px, y[it] - py) }

    /** The mean vertex position, the patch's centre. */
    val centre: Pair<Double, Double> get() = x.average() to y.average()

    /**
     * The vertices within [radius] of [centre]: the patch's interior, away from its ragged rim.
     * Use it to measure bulk quantities that the rim would otherwise pollute.
     */
    fun interior(radius: Double): List<Int> {
        val (cx, cy) = centre
        return x.indices.filter { hypot(x[it] - cx, y[it] - cy) <= radius }
    }

    /** The largest distance of any vertex from [centre]. */
    val extent: Double get() {
        val (cx, cy) = centre
        return x.indices.maxOfOrNull { hypot(x[it] - cx, y[it] - cy) } ?: 0.0
    }

    /**
     * A two-colouring (0 or 1 per vertex) with every edge joining different colours, or null if the
     * graph has an odd cycle. Every rhombus tiling is two-colourable: a vertex `sum K_k e_k` takes
     * the parity of `sum K_k`.
     */
    fun twoColouring(): IntArray? {
        val c = IntArray(vertexCount) { -1 }
        val queue = IntArray(vertexCount)
        for (s in 0 until vertexCount) {
            if (c[s] >= 0) continue
            c[s] = 0
            var head = 0
            var tail = 0
            queue[tail++] = s
            while (head < tail) {
                val u = queue[head++]
                for (w in neighbours[u]) {
                    if (c[w] < 0) {
                        c[w] = 1 - c[u]
                        queue[tail++] = w
                    } else if (c[w] == c[u]) return null
                }
            }
        }
        return c
    }

    /**
     * The exact number of eigenstates of the adjacency matrix at E = 0, its nullity, counted with
     * no floating point at all: the rank of A is computed by Gaussian elimination modulo two large
     * primes, and the larger rank is kept (a rank mod p can only fall short of the true rank, and
     * only when p divides a minor). For a bipartite graph it is the rank of the half-size block B
     * joining the two colours, since A has rank 2·rank(B).
     */
    fun zeroModeCount(): Int {
        val colour = twoColouring()
        if (colour == null) {
            val rows = List(vertexCount) { neighbours[it] }
            return vertexCount - PRIMES.maxOf { rankModP(rows, it) }
        }
        // Columns: the colour-1 vertices, renumbered in index (x) order.
        val col = IntArray(vertexCount) { -1 }
        var nc = 0
        for (v in 0 until vertexCount) if (colour[v] == 1) col[v] = nc++
        val rows = (0 until vertexCount).filter { colour[it] == 0 }.map { v -> IntArray(neighbours[v].size) { col[neighbours[v][it]] } }
        return vertexCount - 2 * PRIMES.maxOf { rankModP(rows, it) }
    }

    /** [zeroModeCount] as a fraction of [vertexCount]. */
    fun zeroModeFraction(): Double = zeroModeCount().toDouble() / vertexCount

    /**
     * The number of vertices a maximum matching leaves unmatched ("monomers"), for a bipartite
     * graph. It is a lower bound on [zeroModeCount] (rank B can be no more than the matching size),
     * and on Penrose tilings the two agree in the limit: the monomer density of maximum dimer
     * coverings is also 81 - 50 tau (Flicker, Simon & Parameswaran, Phys. Rev. X 10, 011005, 2020).
     * Hopcroft–Karp; throws if the graph is not bipartite.
     */
    fun matchingDeficit(): Int {
        val colour = twoColouring() ?: throw IllegalStateException("graph is not bipartite")
        val left = (0 until vertexCount).filter { colour[it] == 0 }
        val matched = hopcroftKarp(left, neighbours, vertexCount)
        return vertexCount - 2 * matched
    }

    /**
     * Every eigenvalue of the adjacency matrix, ascending, and the eigenvectors if [vectors]: a
     * dense symmetric eigensolve (EJML), fine to a few thousand vertices.
     */
    @JvmOverloads
    fun spectrum(vectors: Boolean = true): GraphSpectrum {
        val n = vertexCount
        val a = DMatrixRMaj(n, n)
        for (v in 0 until n) for (w in neighbours[v]) a[v, w] = 1.0
        val (values, vecs) = symmetricEigen(a, vectors)
        return GraphSpectrum(this, values, vecs)
    }

    /**
     * A strictly localised E = 0 state that includes vertex [center], or null if none fits within
     * [maxHops] edges of it. Grows the cluster S of vertices within h hops, h = 1, 2, …, and looks
     * for amplitudes on S alone with (A·psi)(u) = 0 at every vertex u, the vertices just outside S
     * included; the first h with such a state at [center] wins. The returned vector has one entry
     * per vertex, zero off the cluster, scaled so its largest magnitude is 1.
     */
    @JvmOverloads
    fun confinedState(center: Int, maxHops: Int = 8, tolerance: Double = 1e-9): DoubleArray? {
        val dist = IntArray(vertexCount) { Int.MAX_VALUE }
        dist[center] = 0
        val order = ArrayList<Int>()
        order += center
        var head = 0
        while (head < order.size) {
            val u = order[head++]
            if (dist[u] > maxHops) continue
            for (w in neighbours[u]) if (dist[w] == Int.MAX_VALUE) {
                dist[w] = dist[u] + 1
                order += w
            }
        }
        for (h in 1..maxHops) {
            val s = order.filter { dist[it] <= h }
            val index = HashMap<Int, Int>()
            s.forEachIndexed { i, v -> index[v] = i }
            // M = A restricted to rows S ∪ ∂S, columns S; then (M^T M) psi = 0 is the condition.
            val rows = order.filter { dist[it] <= h + 1 }
            val m = s.size
            val mtm = DMatrixRMaj(m, m)
            for (u in rows) {
                val cols = neighbours[u].mapNotNull { index[it] }
                for (i in cols) for (j in cols) mtm[i, j] = mtm[i, j] + 1.0
            }
            val (values, vecs) = symmetricEigen(mtm, true)
            val nullBasis = values.indices.filter { values[it] < tolerance }.map { vecs!![it] }
            if (nullBasis.isEmpty()) continue
            // The state in the null space with the most weight on `center`: project e_center onto it.
            val psi = DoubleArray(m)
            val c = index.getValue(center)
            for (q in nullBasis) for (i in 0 until m) psi[i] += q[c] * q[i]
            if (abs(psi[c]) < 1e-6) continue
            val peak = psi.maxOf { abs(it) }
            val out = DoubleArray(vertexCount)
            s.forEachIndexed { i, v -> out[v] = psi[i] / peak }
            return out
        }
        return null
    }

    /** Every edge as one stroked FLine. */
    @JvmOverloads
    fun edgeLines(color: Vector4f = Vector4f(0.45f, 0.47f, 0.52f, 1f), thickness: Double = 0.8): FLine = FLine().also { f ->
        for (v in 0 until vertexCount) for (w in neighbours[v]) if (w > v) {
            f.moveTo(x[v], y[v])
            f.lineTo(x[w], y[w])
        }
        f.color = color
        f.stroked = true
        f.thickness = thickness
    }

    /**
     * One filled disc per vertex where [values] is non-zero, its area proportional to |value| and
     * its colour [positive] or [negative] by sign. Values are scaled so the largest disc has radius
     * [maxRadius] (in drawing units; default 0.42 of an edge). Draws a state, or any per-vertex
     * weight, on the tiling.
     */
    @JvmOverloads
    fun vertexDots(
        values: DoubleArray,
        maxRadius: Double = 0.42 * edgeLength,
        positive: Vector4f = Vector4f(1f, 0.55f, 0.15f, 1f),
        negative: Vector4f = Vector4f(0.25f, 0.6f, 1f, 1f),
        threshold: Double = 1e-6,
    ): List<FLine> {
        val peak = values.maxOfOrNull { abs(it) } ?: 0.0
        if (peak == 0.0) return emptyList()
        val pos = FLine()
        val neg = FLine()
        for (v in 0 until vertexCount) {
            val a = values[v] / peak
            if (abs(a) < threshold) continue
            val r = maxRadius * sqrt(abs(a))
            val f = if (a > 0) pos else neg
            f.moveTo(x[v] + r, y[v])
            for (k in 1..16) f.lineTo(x[v] + r * cos(2 * PI * k / 16), y[v] + r * sin(2 * PI * k / 16))
        }
        return listOf(pos.apply { color = positive; filled = true }, neg.apply { color = negative; filled = true })
    }

    companion object {
        private val PRIMES = longArrayOf(2147483647L, 1000000007L)

        /** The vertex graph of [tiling]: one vertex per distinct tile corner, one edge per distinct tile edge. */
        fun of(tiling: DeBruijnTiling): TilingGraph {
            require(tiling.tiles.isNotEmpty()) { "tiling has no tiles" }
            val c0 = tiling.tiles[0].corners
            val edge = hypot(c0[1].x - c0[0].x, c0[1].y - c0[0].y)
            val ids = HashMap<Pair<Long, Long>, Int>()
            val px = ArrayList<Double>()
            val py = ArrayList<Double>()
            fun id(p: afield.math.linalg.Vector2d): Int = ids.getOrPut(Math.round(p.x / edge * 1e5) to Math.round(p.y / edge * 1e5)) {
                px += p.x
                py += p.y
                px.size - 1
            }
            val edges = HashSet<Long>()
            for (t in tiling.tiles) for (i in 0 until 4) {
                val a = id(t.corners[i])
                val b = id(t.corners[(i + 1) % 4])
                edges += min(a, b).toLong() * 1_000_000_007L + max(a, b)
            }
            return build(px.toDoubleArray(), py.toDoubleArray(), edges.map { (it / 1_000_000_007L).toInt() to (it % 1_000_000_007L).toInt() }, edge)
        }

        /**
         * A periodic control: the square lattice's vertices within [radius] (in edges) of the
         * origin, with edge length [scale]. Its spectrum has no macroscopic E = 0 degeneracy.
         */
        @JvmOverloads
        fun squareLattice(radius: Double, scale: Double = 1.0): TilingGraph {
            val r = ceil(radius).toInt()
            val ids = HashMap<Pair<Int, Int>, Int>()
            val px = ArrayList<Double>()
            val py = ArrayList<Double>()
            for (i in -r..r) for (j in -r..r) if (hypot(i.toDouble(), j.toDouble()) <= radius) {
                ids[i to j] = px.size
                px += i * scale
                py += j * scale
            }
            val edges = ArrayList<Pair<Int, Int>>()
            for ((p, a) in ids) {
                ids[(p.first + 1) to p.second]?.let { edges += a to it }
                ids[p.first to (p.second + 1)]?.let { edges += a to it }
            }
            return build(px.toDoubleArray(), py.toDoubleArray(), edges, scale)
        }

        /** Sorts the vertices by (x, y) and assembles neighbour lists. */
        private fun build(px: DoubleArray, py: DoubleArray, edges: List<Pair<Int, Int>>, edge: Double): TilingGraph {
            val order = px.indices.sortedWith(compareBy({ Math.round(px[it] / edge * 1e5) }, { Math.round(py[it] / edge * 1e5) }))
            val rank = IntArray(px.size)
            order.forEachIndexed { i, v -> rank[v] = i }
            val adj = List(px.size) { ArrayList<Int>() }
            for ((a, b) in edges) if (a != b) {
                adj[rank[a]] += rank[b]
                adj[rank[b]] += rank[a]
            }
            return TilingGraph(
                DoubleArray(px.size) { px[order[it]] },
                DoubleArray(px.size) { py[order[it]] },
                Array(px.size) { adj[it].distinct().sorted().toIntArray() },
                edge,
            )
        }
    }
}

/** The vertex graph of this tiling, for spectra: see [TilingGraph]. */
fun DeBruijnTiling.vertexGraph(): TilingGraph = TilingGraph.of(this)

/**
 * The eigenvalues [energies] (ascending) of a [TilingGraph]'s adjacency matrix and, when solved
 * for, the orthonormal eigenvectors: `vectors[i][v]` is state i's amplitude on vertex v.
 */
class GraphSpectrum(
    val graph: TilingGraph,
    val energies: DoubleArray,
    val vectors: Array<DoubleArray>?,
) {
    /** The indices of the states with |E| below [tolerance]. */
    @JvmOverloads
    fun zeroStates(tolerance: Double = 1e-8): List<Int> = energies.indices.filter { abs(energies[it]) < tolerance }

    /** How many states have |E| below [tolerance]: [TilingGraph.zeroModeCount], in floating point. */
    @JvmOverloads
    fun zeroCount(tolerance: Double = 1e-8): Int = zeroStates(tolerance).size

    /**
     * For each vertex, the weight of the E = 0 states on it: the diagonal of the projector onto
     * them, `sum_i psi_i(v)^2`. It does not depend on how the degenerate states are mixed, it sums
     * to [zeroCount], and it is exactly the share of a strike at v that rings at the E = 0 pitch
     * when the strike is heard at the same vertex.
     */
    @JvmOverloads
    fun zeroWeights(tolerance: Double = 1e-8): DoubleArray {
        val vecs = vectors ?: throw IllegalStateException("spectrum was solved without vectors")
        val w = DoubleArray(graph.vertexCount)
        for (i in zeroStates(tolerance)) for (v in w.indices) w[v] += vecs[i][v] * vecs[i][v]
        return w
    }

    /**
     * For each state, the share of its weight on [vertices] (`sum_{v in vertices} psi_i(v)^2`).
     * Pass [TilingGraph.interior] to tell bulk states from ones living on the patch's rim.
     */
    fun weightOn(vertices: Collection<Int>): DoubleArray {
        val vecs = vectors ?: throw IllegalStateException("spectrum was solved without vectors")
        return DoubleArray(energies.size) { i -> vertices.sumOf { vecs[i][it] * vecs[i][it] } }
    }

    /**
     * The integrated density of states N(E) as a stepped FLine: x is E mapped onto 0..[width]
     * between the lowest and highest eigenvalue, y is the fraction of states at or below E scaled to
     * 0..[height], drawn upward (y is negated, so it reads correctly in `lines(...)`). With [weights]
     * (one per state, such as [weightOn] the interior) each state counts by its weight instead of 1.
     * A vertical jump is a degenerate level; a flat stretch is a gap.
     */
    @JvmOverloads
    fun idosLine(width: Double = 480.0, height: Double = 260.0, weights: DoubleArray? = null): FLine {
        val lo = energies.first()
        val hi = energies.last()
        val total = weights?.sum() ?: energies.size.toDouble()
        val f = FLine().moveTo(0.0, 0.0)
        var acc = 0.0
        for (i in energies.indices) {
            val ex = (energies[i] - lo) / (hi - lo) * width
            f.lineTo(ex, -acc / total * height)
            acc += weights?.get(i) ?: 1.0
            f.lineTo(ex, -acc / total * height)
        }
        return f.apply { stroked = true }
    }

    /**
     * The largest gaps between consecutive eigenvalues, widest first, as (below, above) pairs: at
     * most [count] of them, each wider than [minWidth]. With [weights] only states whose weight is
     * at least [minWeight] count as levels, so rim states inside a bulk gap can be ignored.
     */
    @JvmOverloads
    fun gaps(count: Int = 4, minWidth: Double = 0.0, weights: DoubleArray? = null, minWeight: Double = 0.0): List<Pair<Double, Double>> {
        val levels = energies.indices.filter { weights == null || weights[it] >= minWeight }.map { energies[it] }
        return levels.zipWithNext().filter { it.second - it.first > minWidth }.sortedByDescending { it.second - it.first }.take(count)
    }
}

/** Dense symmetric eigensolve: eigenvalues ascending and, if asked, eigenvectors in the same order. */
internal fun symmetricEigen(a: DMatrixRMaj, vectors: Boolean): Pair<DoubleArray, Array<DoubleArray>?> {
    val n = a.numRows
    val eig = DecompositionFactory_DDRM.eig(n, vectors, true)
    require(eig.decompose(a.copy())) { "eigendecomposition failed" }
    val values = DoubleArray(n) { eig.getEigenvalue(it).real }
    val order = values.indices.sortedBy { values[it] }
    val vecs = if (vectors) Array(n) { i -> eig.getEigenVector(order[i])!!.data.copyOf() } else null
    return DoubleArray(n) { values[order[it]] } to vecs
}

/**
 * The rank, modulo the prime [p], of the 0/1 matrix whose row r has ones in columns [rows]`[r]`.
 * Rows are reduced in order against an echelon basis keyed by leading column; with rows and
 * columns in spatial order the reduced rows stay short.
 */
internal fun rankModP(rows: List<IntArray>, p: Long): Int {
    val pivotCols = HashMap<Int, IntArray>()
    val pivotVals = HashMap<Int, LongArray>()
    var rank = 0
    var bufC = IntArray(64)
    var bufV = LongArray(64)
    for (r in rows) {
        var cols = r.sortedArray()
        var vals = LongArray(cols.size) { 1L }
        while (cols.isNotEmpty()) {
            val lead = cols[0]
            val pc = pivotCols[lead]
            if (pc == null) {
                val inv = modPow(vals[0], p - 2, p)
                for (i in vals.indices) vals[i] = vals[i] * inv % p
                pivotCols[lead] = cols
                pivotVals[lead] = vals
                rank++
                break
            }
            val pv = pivotVals.getValue(lead)
            val f = vals[0]
            // cols/vals -= f * pivot; the leading entries cancel.
            val need = cols.size + pc.size
            if (bufC.size < need) {
                bufC = IntArray(need * 2)
                bufV = LongArray(need * 2)
            }
            var i = 1
            var j = 1
            var k = 0
            while (i < cols.size || j < pc.size) {
                val ci = if (i < cols.size) cols[i] else Int.MAX_VALUE
                val cj = if (j < pc.size) pc[j] else Int.MAX_VALUE
                val c: Int
                val v: Long
                if (ci < cj) {
                    c = ci; v = vals[i]; i++
                } else if (cj < ci) {
                    c = cj; v = (p - f * pv[j] % p) % p; j++
                } else {
                    c = ci; v = ((vals[i] - f * pv[j] % p) % p + p) % p; i++; j++
                }
                if (v != 0L) {
                    bufC[k] = c
                    bufV[k] = v
                    k++
                }
            }
            cols = bufC.copyOf(k)
            vals = bufV.copyOf(k)
        }
    }
    return rank
}

private fun modPow(base: Long, exp: Long, p: Long): Long {
    var r = 1L
    var b = base % p
    var e = exp
    while (e > 0) {
        if (e and 1L == 1L) r = r * b % p
        b = b * b % p
        e = e shr 1
    }
    return r
}

/** Size of a maximum matching between [left] and the rest, by Hopcroft–Karp with an explicit stack. */
private fun hopcroftKarp(left: List<Int>, adj: Array<IntArray>, n: Int): Int {
    val inf = Int.MAX_VALUE
    val matchOf = IntArray(n) { -1 }
    val dist = IntArray(n) { inf }
    val iter = IntArray(n)
    val queue = IntArray(n)
    var matched = 0
    while (true) {
        // BFS from the free left vertices, layering by alternating paths.
        var head = 0
        var tail = 0
        for (u in left) {
            if (matchOf[u] < 0) {
                dist[u] = 0
                queue[tail++] = u
            } else dist[u] = inf
        }
        var found = false
        while (head < tail) {
            val u = queue[head++]
            for (v in adj[u]) {
                val w = matchOf[v]
                if (w < 0) found = true else if (dist[w] == inf) {
                    dist[w] = dist[u] + 1
                    queue[tail++] = w
                }
            }
        }
        if (!found) return matched
        for (u in left) iter[u] = 0
        val stack = IntArray(n)
        val path = IntArray(n)
        for (root in left) {
            if (matchOf[root] >= 0) continue
            var sp = 0
            stack[sp++] = root
            while (sp > 0) {
                val u = stack[sp - 1]
                if (iter[u] == adj[u].size) {
                    dist[u] = inf
                    sp--
                    continue
                }
                val v = adj[u][iter[u]++]
                val w = matchOf[v]
                if (w < 0) {
                    // Augment along the stack: stack[i] takes path[i] (path[sp-1] = v).
                    path[sp - 1] = v
                    for (i in 0 until sp) {
                        matchOf[stack[i]] = path[i]
                        matchOf[path[i]] = stack[i]
                    }
                    matched++
                    break
                }
                if (dist[w] == dist[u] + 1) {
                    path[sp - 1] = v
                    stack[sp++] = w
                }
            }
        }
    }
}
