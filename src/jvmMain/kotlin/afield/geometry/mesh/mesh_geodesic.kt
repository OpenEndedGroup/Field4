@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.mesh

import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.thickness
import afield.math.linalg.Vec3
import afield.math.linalg.Vec4
import afield.math.linalg.vec
import org.ejml.data.DMatrixRMaj
import org.ejml.data.DMatrixSparseCSC
import org.ejml.data.DMatrixSparseTriplet
import org.ejml.ops.DConvertMatrixStruct
import org.ejml.sparse.FillReducing
import org.ejml.sparse.csc.factory.LinearSolverFactory_DSCC
import kotlin.math.sqrt

/**
 * Geodesic distance on a triangle mesh by the heat method, and the toolpaths that follow from it.
 *
 * Crane, Weischedel and Wardetzky, "Geodesics in Heat" (2013): let heat flow from the sources for a
 * short time `t`, keep only the *direction* it flows in, and find the function whose gradient has that
 * direction. Three steps, two of them linear solves with matrices that depend only on the mesh:
 *
 *  1. solve `(M + t L) u = δ`, where `M` is the lumped vertex area, `L` the cotangent Laplacian and
 *     `δ` is one at the sources;
 *  2. on every triangle, `X = −∇u / |∇u|`;
 *  3. solve `L φ = −∇·X` and shift `φ` so the sources sit at zero.
 *
 * Both matrices are factored once (sparse Cholesky from EJML, already a Field dependency), so a new
 * set of sources costs two back-substitutions. That is what makes it the right tool for carving:
 * the same stone, many distance fields.
 *
 * `t` is the mean edge length squared, times [timeScale]. Larger smooths the distance (rounder
 * isolines, worse accuracy near the sources); smaller is sharper and eventually noisy. A mesh with a
 * boundary uses natural (Neumann) conditions on both solves, which bends isolines to meet the border
 * at right angles; Crane et al. average Neumann and Dirichlet solutions to soften that, which this
 * does not do.
 *
 * Expects a welded, manifold mesh (`weld()` first if it came in face by face): unshared vertices
 * read as separate pieces, and heat does not cross between them.
 */
class HeatGeodesics(val mesh: TriMesh, timeScale: Double = 1.0) {

    /** The diffusion time actually used: mean edge length squared, times the `timeScale` given. */
    val time: Double

    private val n = mesh.vertexCount
    private val topo = mesh.topology()
    private val pos = DoubleArray(n * 3) { mesh.positions[it].toDouble() }

    /** Lumped (barycentric) area per vertex. */
    private val area = DoubleArray(n)

    /** Per edge, `(cot α + cot β) / 2`. */
    private val weight = DoubleArray(topo.edgeCount)

    private val heatSolver = LinearSolverFactory_DSCC.cholesky(FillReducing.NONE)
    private val poissonSolver = LinearSolverFactory_DSCC.cholesky(FillReducing.NONE)

    init {
        require(n > 0 && mesh.triangleCount > 0) { "HeatGeodesics needs a mesh with triangles" }
        var edgeSum = 0.0
        for (e in 0 until topo.edgeCount) {
            val (a, b) = topo.edges[e]
            edgeSum += sqrt(dist2(a, b))
            var cotSum = 0.0
            for (t in topo.edgeTriangles[e]) cotSum += cotOpposite(t, a, b)
            weight[e] = cotSum / 2.0
        }
        val h = edgeSum / topo.edgeCount.coerceAtLeast(1)
        time = h * h * timeScale
        for (t in 0 until mesh.triangleCount) {
            val third = triangleArea(t) / 3.0
            area[mesh.ia(t)] += third; area[mesh.ib(t)] += third; area[mesh.ic(t)] += third
        }
        // An isolated vertex has no area and no edges; give it a little so both systems stay definite.
        val floor = h * h * 1e-6
        for (i in 0 until n) if (area[i] <= 0.0) area[i] = floor

        require(heatSolver.setA(assemble(time, 1.0))) { "heat system did not factor — is the mesh degenerate?" }
        // L alone is singular (constants are in its null space); a whisper of M pins the constant.
        require(poissonSolver.setA(assemble(1.0, 1e-8))) { "Poisson system did not factor — is the mesh degenerate?" }
    }

    /** `massScale · M + lScale · L`, as a sparse symmetric matrix. */
    private fun assemble(lScale: Double, massScale: Double, fixed: Set<Int> = emptySet()): DMatrixSparseCSC {
        val trip = DMatrixSparseTriplet(n, n, n + 4 * topo.edgeCount)
        val diag = DoubleArray(n) { massScale * area[it] }
        for (e in 0 until topo.edgeCount) {
            val (a, b) = topo.edges[e]
            val w = lScale * weight[e]
            // A fixed (Dirichlet) vertex keeps only its identity row; its value is zero, so the column
            // drops out of every other row without changing the right-hand side.
            if (a !in fixed && b !in fixed) { trip.addItem(a, b, -w); trip.addItem(b, a, -w) }
            diag[a] += w; diag[b] += w
        }
        for (i in 0 until n) trip.addItem(i, i, if (i in fixed) 1.0 else diag[i])
        return DConvertMatrixStruct.convert(trip, null as DMatrixSparseCSC?)
    }

    /**
     * Geodesic distance from the nearest of [sources] (vertex indices) to every vertex, in the mesh's
     * own units. Sources are exactly zero.
     */
    fun distance(sources: Collection<Int>): DoubleArray = solve(sources, poissonSolver, emptySet())

    private fun solve(sources: Collection<Int>, poisson: org.ejml.interfaces.linsol.LinearSolverSparse<DMatrixSparseCSC, DMatrixRMaj>, fixed: Set<Int>): DoubleArray {
        require(sources.isNotEmpty()) { "distance needs at least one source vertex" }
        require(sources.all { it in 0 until n }) { "source vertices must be in 0 until $n" }

        val delta = DMatrixRMaj(n, 1)
        for (s in sources) delta[s, 0] = 1.0
        val u = DMatrixRMaj(n, 1)
        heatSolver.solve(delta, u)

        // Steps 2 and 3 together: accumulate the integrated divergence of X = -grad u / |grad u|.
        val div = DMatrixRMaj(n, 1)
        for (t in 0 until mesh.triangleCount) {
            val i = mesh.ia(t); val j = mesh.ib(t); val k = mesh.ic(t)
            val g = gradient(t, u[i, 0], u[j, 0], u[k, 0]) ?: continue
            val len = sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2])
            if (len < 1e-300) continue
            val x = doubleArrayOf(-g[0] / len, -g[1] / len, -g[2] / len)
            // div at vertex i = 1/2 (cot θ_k (e_ij · X) + cot θ_j (e_ik · X)), and around the triangle.
            addDivergence(div, i, j, k, x)
            addDivergence(div, j, k, i, x)
            addDivergence(div, k, i, j, x)
        }
        // L phi = -div with L positive semidefinite (Crane's Lc = -L).
        for (r in 0 until n) div[r, 0] = if (r in fixed) 0.0 else -div[r, 0]
        val phi = DMatrixRMaj(n, 1)
        poisson.solve(div, phi)

        var base = Double.POSITIVE_INFINITY
        for (s in sources) base = minOf(base, phi[s, 0])
        return DoubleArray(n) { (phi[it, 0] - base).coerceAtLeast(0.0) }.also { d -> sources.forEach { d[it] = 0.0 } }
    }

    /** Vararg convenience for [distance]. */
    fun distance(vararg sources: Int): DoubleArray = distance(sources.toList())

    /** Geodesic distance from the mesh's open boundary — the border of a relief, the rim of a shell. */
    fun distanceFromBoundary(): DoubleArray {
        val border = topo.boundaryLoops.flatten().toSet()
        require(border.isNotEmpty()) { "distanceFromBoundary needs an open mesh; this one is closed" }
        return solve(border, boundaryPoisson.value, border)
    }

    /**
     * The Poisson system with the boundary held at zero, built on first use. With every border vertex
     * a source, the free (Neumann) solve that [distance] uses overshoots: 15% at the centre of a flat
     * square. Pinning the sources to zero is exact there to within the mesh's resolution.
     */
    private val boundaryPoisson = lazy {
        val border = topo.boundaryLoops.flatten().toSet()
        LinearSolverFactory_DSCC.cholesky(FillReducing.NONE).also {
            require(it.setA(assemble(1.0, 0.0, border))) { "boundary Poisson system did not factor" }
        }
    }

    private fun addDivergence(div: DMatrixRMaj, i: Int, j: Int, k: Int, x: DoubleArray) {
        val e1x = pos[j * 3] - pos[i * 3]; val e1y = pos[j * 3 + 1] - pos[i * 3 + 1]; val e1z = pos[j * 3 + 2] - pos[i * 3 + 2]
        val e2x = pos[k * 3] - pos[i * 3]; val e2y = pos[k * 3 + 1] - pos[i * 3 + 1]; val e2z = pos[k * 3 + 2] - pos[i * 3 + 2]
        val cotK = cotAt(k, i, j) // angle at k, opposite edge ij
        val cotJ = cotAt(j, k, i) // angle at j, opposite edge ik
        div[i, 0] += 0.5 * (cotK * (e1x * x[0] + e1y * x[1] + e1z * x[2]) + cotJ * (e2x * x[0] + e2y * x[1] + e2z * x[2]))
    }

    /** The gradient of the linear interpolant of `(ui, uj, uk)` on triangle [t], or null if it is degenerate. */
    private fun gradient(t: Int, ui: Double, uj: Double, uk: Double): DoubleArray? {
        val i = mesh.ia(t); val j = mesh.ib(t); val k = mesh.ic(t)
        val nrm = normal(i, j, k)
        val a2 = sqrt(nrm[0] * nrm[0] + nrm[1] * nrm[1] + nrm[2] * nrm[2])
        if (a2 < 1e-300) return null
        val nx = nrm[0] / a2; val ny = nrm[1] / a2; val nz = nrm[2] / a2
        val g = DoubleArray(3)
        // grad u = 1/(2A) Σ u_i (N × e_i), e_i the edge opposite vertex i, counter-clockwise.
        fun add(u: Double, from: Int, to: Int) {
            val ex = pos[to * 3] - pos[from * 3]; val ey = pos[to * 3 + 1] - pos[from * 3 + 1]; val ez = pos[to * 3 + 2] - pos[from * 3 + 2]
            g[0] += u * (ny * ez - nz * ey); g[1] += u * (nz * ex - nx * ez); g[2] += u * (nx * ey - ny * ex)
        }
        add(ui, j, k); add(uj, k, i); add(uk, i, j)
        g[0] /= a2; g[1] /= a2; g[2] /= a2
        return g
    }

    private fun normal(i: Int, j: Int, k: Int): DoubleArray {
        val ax = pos[j * 3] - pos[i * 3]; val ay = pos[j * 3 + 1] - pos[i * 3 + 1]; val az = pos[j * 3 + 2] - pos[i * 3 + 2]
        val bx = pos[k * 3] - pos[i * 3]; val by = pos[k * 3 + 1] - pos[i * 3 + 1]; val bz = pos[k * 3 + 2] - pos[i * 3 + 2]
        return doubleArrayOf(ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx)
    }

    private fun triangleArea(t: Int): Double {
        val c = normal(mesh.ia(t), mesh.ib(t), mesh.ic(t))
        return 0.5 * sqrt(c[0] * c[0] + c[1] * c[1] + c[2] * c[2])
    }

    private fun dist2(a: Int, b: Int): Double {
        val dx = pos[a * 3] - pos[b * 3]; val dy = pos[a * 3 + 1] - pos[b * 3 + 1]; val dz = pos[a * 3 + 2] - pos[b * 3 + 2]
        return dx * dx + dy * dy + dz * dz
    }

    /** Cotangent of the angle at vertex [c] between edges to [a] and [b]. */
    private fun cotAt(c: Int, a: Int, b: Int): Double {
        val ux = pos[a * 3] - pos[c * 3]; val uy = pos[a * 3 + 1] - pos[c * 3 + 1]; val uz = pos[a * 3 + 2] - pos[c * 3 + 2]
        val vx = pos[b * 3] - pos[c * 3]; val vy = pos[b * 3 + 1] - pos[c * 3 + 1]; val vz = pos[b * 3 + 2] - pos[c * 3 + 2]
        val dot = ux * vx + uy * vy + uz * vz
        val cx = uy * vz - uz * vy; val cy = uz * vx - ux * vz; val cz = ux * vy - uy * vx
        val cross = sqrt(cx * cx + cy * cy + cz * cz)
        return if (cross < 1e-18) 0.0 else dot / cross
    }

    private fun cotOpposite(t: Int, a: Int, b: Int): Double {
        val ia = mesh.ia(t); val ib = mesh.ib(t); val ic = mesh.ic(t)
        val c = if (ia != a && ia != b) ia else if (ib != a && ib != b) ib else ic
        return cotAt(c, a, b)
    }
}

/** Heat-method geodesic distance from [sources] (vertex indices). Build a [HeatGeodesics] to reuse the factorisation. */
fun TriMesh.geodesicDistance(vararg sources: Int, timeScale: Double = 1.0): DoubleArray =
    HeatGeodesics(this, timeScale).distance(sources.toList())

/** Heat-method geodesic distance from this mesh's open boundary. */
fun TriMesh.geodesicDistanceFromBoundary(timeScale: Double = 1.0): DoubleArray =
    HeatGeodesics(this, timeScale).distanceFromBoundary()

/** Straight-line distance from vertex [source] to every vertex — the comparison a geodesic is measured against. */
fun TriMesh.euclideanDistance(source: Int): DoubleArray {
    val sx = x(source); val sy = y(source); val sz = z(source)
    return DoubleArray(vertexCount) {
        val dx = (x(it) - sx).toDouble(); val dy = (y(it) - sy).toDouble(); val dz = (z(it) - sz).toDouble()
        sqrt(dx * dx + dy * dy + dz * dz)
    }
}

/** The vertex closest to ([px], [py], [pz]). */
fun TriMesh.nearestVertex(px: Number, py: Number, pz: Number): Int {
    val qx = px.toDouble(); val qy = py.toDouble(); val qz = pz.toDouble()
    var best = 0; var bestD = Double.POSITIVE_INFINITY
    for (i in 0 until vertexCount) {
        val dx = x(i) - qx; val dy = y(i) - qy; val dz = z(i) - qz
        val d = dx * dx + dy * dy + dz * dz
        if (d < bestD) { bestD = d; best = i }
    }
    return best
}

/** Vertex [axis] coordinates (0 = x, 1 = y, 2 = z) as a scalar field, for [isolines] that slice by height. */
fun TriMesh.coordinateField(axis: Int = 1): DoubleArray {
    require(axis in 0..2) { "axis must be 0, 1 or 2, got $axis" }
    return DoubleArray(vertexCount) { positions[it * 3 + axis].toDouble() }
}

/**
 * The curves where the piecewise-linear field [values] (one per vertex) crosses [level], as 3D
 * polylines on the surface. A closed curve repeats its first point at the end.
 *
 * Each triangle the level crosses contributes one segment; segments that share a mesh edge are
 * chained, so a ring around the surface comes back as one polyline, not hundreds of pieces. A vertex
 * exactly on the level is nudged up by a hair so no segment degenerates.
 */
fun TriMesh.isolines(values: DoubleArray, level: Double): List<List<Vec3>> {
    require(values.size == vertexCount) { "isolines needs one value per vertex (${vertexCount}), got ${values.size}" }
    val eps = 1e-12 * (1.0 + kotlin.math.abs(level))
    fun v(i: Int) = if (values[i] == level) level + eps else values[i]
    fun key(a: Int, b: Int) = if (a < b) a.toLong() * vertexCount + b else b.toLong() * vertexCount + a
    val point = HashMap<Long, Vec3>()
    fun crossing(a: Int, b: Int): Long {
        val k = key(a, b)
        point.getOrPut(k) {
            val s = ((level - v(a)) / (v(b) - v(a))).toFloat()
            vec(x(a) + s * (x(b) - x(a)), y(a) + s * (y(b) - y(a)), z(a) + s * (z(b) - z(a)))
        }
        return k
    }
    // Each crossed triangle joins two crossed edges.
    val links = HashMap<Long, MutableList<Long>>()
    for (t in 0 until triangleCount) {
        val c = intArrayOf(ia(t), ib(t), ic(t))
        val hit = ArrayList<Long>(2)
        for (e in 0..2) {
            val a = c[e]; val b = c[(e + 1) % 3]
            if ((v(a) < level) != (v(b) < level)) hit += crossing(a, b)
        }
        if (hit.size == 2) {
            links.getOrPut(hit[0]) { ArrayList(2) } += hit[1]
            links.getOrPut(hit[1]) { ArrayList(2) } += hit[0]
        }
    }
    val used = HashSet<Long>()
    val out = ArrayList<List<Vec3>>()
    fun walk(start: Long): List<Long> {
        val chain = arrayListOf(start); used += start
        var cur = start
        while (true) {
            val next = links[cur]?.firstOrNull { it !in used } ?: break
            chain += next; used += next; cur = next
        }
        return chain
    }
    // Open chains first, from an end (a node with one link), then the closed rings that remain.
    for ((k, nb) in links) if (nb.size == 1 && k !in used) out += walk(k).map { point.getValue(it) }
    for (k in links.keys) if (k !in used) {
        val chain = walk(k)
        val closes = links[chain.last()]?.contains(chain.first()) == true && chain.size > 2
        out += (if (closes) chain + chain.first() else chain).map { point.getValue(it) }
    }
    return out
}

/**
 * Evenly spaced [isolines] of [values]: levels `spacing, 2·spacing, …` below the field's maximum,
 * offset by [phase] (a fraction of [spacing]). The outer list is by level, nearest the sources first.
 */
fun TriMesh.evenIsolines(values: DoubleArray, spacing: Double, phase: Double = 0.5): List<List<List<Vec3>>> {
    require(spacing > 0.0) { "spacing must be positive, got $spacing" }
    val top = values.maxOrNull() ?: return emptyList()
    val out = ArrayList<List<List<Vec3>>>()
    var level = spacing * phase
    while (level < top) {
        out += isolines(values, level)
        level += spacing
    }
    return out
}

/**
 * Join concentric closed rings into one continuous spiral, as a carving or engraving toolpath.
 *
 * [rings] are ordered from one end to the other (the output of an [evenIsolines], one ring
 * a level). Each ring is re-started at the point nearest where the path is, oriented like the first
 * ring, and then walked once while being blended, by arc length, towards the next ring: at the start
 * of a lap the tool is on ring `k`, at the end it is on ring `k + 1`. So the path never jumps and
 * never retraces, and its spacing is the ring spacing — the "spiral from isolines" construction.
 *
 * Rings that are open, or a level with more than one ring, cannot be spiralled through; pass one
 * ring a level (the longest, say) and connect the rest separately.
 */
fun spiralFromRings(rings: List<List<Vec3>>): List<Vec3> {
    val closed = rings.filter { it.size > 3 }.map { r -> if (r.first() == r.last()) r.dropLast(1) else r }
    if (closed.isEmpty()) return emptyList()
    fun d2(a: Vec3, b: Vec3): Float { val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z; return dx * dx + dy * dy + dz * dz }
    fun nearest(r: List<Vec3>, p: Vec3) = r.indices.minByOrNull { d2(r[it], p) } ?: 0
    // Orientation: the sign of the ring's area vector projected on the first ring's.
    fun areaVector(r: List<Vec3>): DoubleArray {
        val a = DoubleArray(3)
        for (i in r.indices) {
            val p = r[i]; val q = r[(i + 1) % r.size]
            a[0] += (p.y * q.z - p.z * q.y).toDouble(); a[1] += (p.z * q.x - p.x * q.z).toDouble(); a[2] += (p.x * q.y - p.y * q.x).toDouble()
        }
        return a
    }
    val ref = areaVector(closed.first())
    val oriented = closed.map { r ->
        val a = areaVector(r)
        if (a[0] * ref[0] + a[1] * ref[1] + a[2] * ref[2] < 0) r.reversed() else r
    }
    val out = ArrayList<Vec3>()
    var at = oriented.first().first()
    for (k in oriented.indices) {
        val ring = oriented[k].let { r -> val s = nearest(r, at); r.drop(s) + r.take(s) + r[s] }
        val next = oriented.getOrNull(k + 1)
        val lengths = DoubleArray(ring.size)
        for (i in 1 until ring.size) lengths[i] = lengths[i - 1] + sqrt(d2(ring[i], ring[i - 1]).toDouble())
        val total = lengths.last().coerceAtLeast(1e-12)
        for (i in ring.indices) {
            val p = ring[i]
            if (next == null) { out += p; continue }
            val q = next[nearest(next, p)]
            val s = (lengths[i] / total).toFloat()
            out += vec(p.x + s * (q.x - p.x), p.y + s * (q.y - p.y), p.z + s * (q.z - p.z))
        }
        at = out.last()
    }
    return out
}

/**
 * Isoplanar roughing passes: the mesh sliced by planes perpendicular to [axis] every [stepDown],
 * from the top down. Each pass is the list of closed contours at that height — where a flat-bottomed
 * tool would follow the outline of the part, one layer at a time, before a finish pass follows the
 * surface itself.
 */
fun TriMesh.roughingPasses(stepDown: Double, axis: Int = 1): List<Pair<Double, List<List<Vec3>>>> {
    require(stepDown > 0.0) { "stepDown must be positive, got $stepDown" }
    val h = coordinateField(axis)
    val lo = h.minOrNull() ?: return emptyList()
    val hi = h.maxOrNull() ?: return emptyList()
    val out = ArrayList<Pair<Double, List<List<Vec3>>>>()
    var level = hi - stepDown * 0.5
    while (level > lo) {
        val rings = isolines(h, level)
        if (rings.isNotEmpty()) out += level to rings
        level -= stepDown
    }
    return out
}

/** Colour every vertex by [values] mapped through a dark-to-warm ramp, from [min] (default the smallest) to [max]. */
fun TriMesh.colouredBy(values: DoubleArray, min: Double = values.minOrNull() ?: 0.0, max: Double = values.maxOrNull() ?: 1.0, bands: Int = 0): TriMesh {
    require(values.size == vertexCount) { "colouredBy needs one value per vertex" }
    val span = (max - min).takeIf { it > 0 } ?: 1.0
    val c = FloatArray(vertexCount * 3)
    for (i in 0 until vertexCount) {
        val s = ((values[i] - min) / span).coerceIn(0.0, 1.0)
        // Optional banding: alternate light and dark stripes, so equal-distance rings read at a glance.
        val stripe = if (bands > 0 && ((s * bands).toInt() % 2 == 1)) 0.82 else 1.0
        val r = (0.18 + 0.82 * s); val g = (0.25 + 0.55 * s * s); val b = (0.55 - 0.4 * s)
        c[i * 3] = (r * stripe).toFloat(); c[i * 3 + 1] = (g * stripe).toFloat(); c[i * 3 + 2] = (b * stripe).toFloat()
    }
    return withColors(c)
}

/** A 3D polyline as an [FLine], for `three { lines(...) }`. */
fun List<Vec3>.toFLine3(color: Vec4 = vec(1.0, 1.0, 1.0, 1.0), thickness: Double = 1.5): FLine {
    val line = FLine()
    forEachIndexed { i, p -> if (i == 0) line.moveTo(p.x, p.y, p.z) else line.lineTo(p.x, p.y, p.z) }
    line.color = color
    line.thickness = thickness
    return line
}
