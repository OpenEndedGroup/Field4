@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.mesh

import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.thickness
import afield.math.linalg.Vec2
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

    /** Lumped (barycentric) area per vertex, [TriMesh.barycentricVertexAreas]. */
    private val area = mesh.barycentricVertexAreas()

    /** Per edge, `(cot α + cot β) / 2`: the shared [MeshTopology.cotangentWeights]. */
    private val weight = topo.cotangentWeights(pos)

    private val heatSolver = LinearSolverFactory_DSCC.cholesky(FillReducing.NONE)
    private val poissonSolver = LinearSolverFactory_DSCC.cholesky(FillReducing.NONE)

    init {
        require(n > 0 && mesh.triangleCount > 0) { "HeatGeodesics needs a mesh with triangles" }
        var edgeSum = 0.0
        for (e in 0 until topo.edgeCount) {
            val (a, b) = topo.edges[e]
            edgeSum += sqrt(dist2(a, b))
        }
        val h = edgeSum / topo.edgeCount.coerceAtLeast(1)
        time = h * h * timeScale
        // An isolated vertex has no area and no edges; give it a little so both systems stay definite.
        val floor = h * h * 1e-6
        for (i in 0 until n) if (area[i] <= 0.0) area[i] = floor

        require(heatSolver.setA(assemble(time, 1.0))) { "heat system did not factor — is the mesh degenerate?" }
        // L alone is singular (constants are in its null space); a whisper of M pins the constant.
        require(poissonSolver.setA(assemble(1.0, 1e-8))) { "Poisson system did not factor — is the mesh degenerate?" }
    }

    /** `massScale · M + lScale · L`, as a sparse symmetric matrix: [MeshTopology.cotangentLaplacianMatrix]. */
    private fun assemble(lScale: Double, massScale: Double, fixed: Set<Int> = emptySet()): DMatrixSparseCSC =
        topo.cotangentLaplacianMatrix(weight, area, massScale, lScale, fixed)

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
        val cotK = cornerCotangent(pos, k, i, j) // angle at k, opposite edge ij
        val cotJ = cornerCotangent(pos, j, k, i) // angle at j, opposite edge ik
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

    private fun dist2(a: Int, b: Int): Double {
        val dx = pos[a * 3] - pos[b * 3]; val dy = pos[a * 3 + 1] - pos[b * 3 + 1]; val dz = pos[a * 3 + 2] - pos[b * 3 + 2]
        return dx * dx + dy * dy + dz * dz
    }
}

/**
 * A flat mesh filling [outline] (a closed polygon, `x` and `y` of each point mapped to the floor's
 * `x` and `z`, at `y = 0`), made of square cells of side [cell] with the boundary vertices snapped onto
 * the outline. For geodesics on a floor plan: unlike [polygonMesh], whose ear-clipped triangles run
 * from wall to wall, every triangle is about [cell] across, which is what the heat method needs.
 * Corners narrower than a cell are rounded off.
 */
fun outlineMesh(outline: List<Vec2>, cell: Double): TriMesh {
    require(outline.size >= 3 && cell > 0.0) { "outlineMesh needs a polygon and a positive cell size" }
    val pts = if (outline.first() == outline.last()) outline.dropLast(1) else outline
    fun inside(x: Double, y: Double): Boolean {
        var c = false
        var j = pts.size - 1
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) c = !c
            j = i
        }
        return c
    }
    fun snap(x: Double, y: Double): Pair<Double, Double> {
        var best = Double.MAX_VALUE; var bx = x; var by = y
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[(i + 1) % pts.size]
            val ex = (b.x - a.x).toDouble(); val ey = (b.y - a.y).toDouble()
            val t = (((x - a.x) * ex + (y - a.y) * ey) / (ex * ex + ey * ey).coerceAtLeast(1e-18)).coerceIn(0.0, 1.0)
            val px = a.x + t * ex; val py = a.y + t * ey
            val d = (px - x) * (px - x) + (py - y) * (py - y)
            if (d < best) { best = d; bx = px; by = py }
        }
        return bx to by
    }
    val minX = pts.minOf { it.x }.toDouble(); val maxX = pts.maxOf { it.x }.toDouble()
    val minY = pts.minOf { it.y }.toDouble(); val maxY = pts.maxOf { it.y }.toDouble()
    val nx = kotlin.math.ceil((maxX - minX) / cell).toInt(); val ny = kotlin.math.ceil((maxY - minY) / cell).toInt()
    val keep = Array(nx) { i -> BooleanArray(ny) { j -> inside(minX + (i + 0.5) * cell, minY + (j + 0.5) * cell) } }
    val index = IntArray((nx + 1) * (ny + 1)) { -1 }
    val positions = ArrayList<Float>(); val indices = ArrayList<Int>()
    fun vertex(i: Int, j: Int): Int {
        val k = j * (nx + 1) + i
        if (index[k] >= 0) return index[k]
        var x = minX + i * cell; var y = minY + j * cell
        // A vertex next to a dropped (or missing) cell is on the boundary: move it onto the outline.
        val onEdge = (i - 1..i).any { a -> (j - 1..j).any { b -> a !in 0 until nx || b !in 0 until ny || !keep[a][b] } }
        if (onEdge) snap(x, y).let { (sx, sy) -> x = sx; y = sy }
        index[k] = positions.size / 3
        positions += x.toFloat(); positions += 0f; positions += y.toFloat()
        return index[k]
    }
    for (i in 0 until nx) for (j in 0 until ny) if (keep[i][j]) {
        val a = vertex(i, j); val b = vertex(i + 1, j); val c = vertex(i + 1, j + 1); val d = vertex(i, j + 1)
        indices += a; indices += c; indices += b
        indices += a; indices += d; indices += c
    }
    return TriMesh(positions.toFloatArray(), indices.toIntArray())
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
 * ring, and then walked once while being blended towards the next ring: the point a fraction `s`
 * of the way round the lap is paired with a point of ring `k + 1` and moved
 * `s` of the way to it, so at the start of a lap the tool is on ring `k` and at the end on ring
 * `k + 1`. The pairing is the nearest point searched only forwards along ring `k + 1`, which keeps
 * the laps from folding where consecutive rings change shape, as round the inside corner of an
 * L-shaped plan. So the path never jumps and
 * never retraces, and its spacing is the ring spacing — the "spiral from isolines" construction.
 *
 * Rings that are open, or a level with more than one ring, cannot be spiralled through; pass one
 * ring a level (the longest, say) and connect the rest separately.
 */
fun spiralFromRings(rings: List<List<Vec3>>): List<Vec3> {
    val oriented = orientedRings(rings)
    if (oriented.isEmpty()) return emptyList()
    return spiralThrough(oriented, oriented.first().first())
}

/**
 * A counterflow double spiral through concentric closed rings: the path spirals in on every other
 * ring, turns round in the middle in a smooth bend, and spirals back out along the rings in between,
 * so each lap out runs between two laps in. This is the pattern underfloor-heating pipe is laid
 * in (supply and return side by side keep the floor evenly warm), and a single tool pass that
 * starts and ends at the edge.
 *
 * [rings] are ordered from the outside in, as for [spiralFromRings]; their spacing is the
 * spacing between neighbouring passes, and each arm's own pitch is twice that. The inward arm
 * uses rings 0, 2, 4, … and the outward arm rings 1, 3, 5, …; the outward arm is started level
 * with the inward one so that both reach the middle at the same place, where a bend that is
 * tangent to both arms joins them. Both ends of the path lie on the outer two rings,
 * next to each other. Needs at least two rings.
 */
fun doubleSpiralFromRings(rings: List<List<Vec3>>): List<Vec3> {
    val (supply, back) = doubleSpiralArms(rings)
    val out = ArrayList<Vec3>(supply.size + back.size)
    out += supply
    for (p in back) if (out.isEmpty() || d2(out.last(), p) > 1e-14f) out += p
    return out
}

/**
 * The two halves of [doubleSpiralFromRings]: the way in, ending with the U-bend, and the way back out
 * from the end of the bend. Draw them in two colours to see supply and return side by side.
 */
fun doubleSpiralArms(rings: List<List<Vec3>>): Pair<List<Vec3>, List<Vec3>> {
    val oriented = orientedRings(rings)
    if (oriented.size < 2) return spiralFromRings(rings) to emptyList()
    val inward = oriented.filterIndexed { i, _ -> i % 2 == 0 }
    val between = oriented.filterIndexed { i, _ -> i % 2 == 1 }
    // Neither arm goes round its innermost ring a last time: that loop would cross the other
    // arm's last lap, which is still on its way in.
    // Where the arms start on the outside sets where they meet in the middle (the laps keep in step).
    // Start at the outer ring's point nearest the middle of the innermost ring: on an elongated room
    // that is the middle of a long side, so the arms meet side by side on a straight stretch, running
    // opposite ways, rather than round the end of a long thin inner loop.
    val core = oriented.last()
    val middle = vec(core.map { it.x }.average().toFloat(), core.map { it.y }.average().toFloat(), core.map { it.z }.average().toFloat())
    val pathIn = spiralThrough(inward, inward.first()[nearestIndex(inward.first(), middle)], finalLap = false)
    // Start the second arm beside the first so the laps stay in step all the way in.
    val pathBack = spiralThrough(between, between.first()[nearestIndex(between.first(), pathIn.first())], finalLap = false)
    val onward = pathBack.asReversed()
    val supply = ArrayList<Vec3>(pathIn.size + 16)
    supply += pathIn
    backOff(supply, onward.first())
    supply += link(supply.toList(), onward)
    return supply to listOf(supply.last()) + onward
}

/**
 * Back [supply] off along itself while [onwardStart] lies ahead of its end, by up to about the gap
 * between them, so the bend that follows turns rather than dipping under to come back up.
 */
private fun backOff(supply: MutableList<Vec3>, onwardStart: Vec3) {
    val gap = sqrt(d2(supply.last(), onwardStart).toDouble())
    var removed = 0.0
    while (supply.size > 3 && removed < 1.5 * gap) {
        val a = supply.last()
        // The direction of travel over a little more than the gap, not the last few segments, which can be a sliver.
        val prev = supply.asReversed().firstOrNull { sqrt(d2(it, a).toDouble()) > 1.2 * gap } ?: break
        val tx = a.x - prev.x; val ty = a.y - prev.y; val tz = a.z - prev.z
        val hx = onwardStart.x - a.x; val hy = onwardStart.y - a.y; val hz = onwardStart.z - a.z
        val tl = sqrt((tx * tx + ty * ty + tz * tz).toDouble()); val hl = sqrt((hx * hx + hy * hy + hz * hz).toDouble())
        if (tl < 1e-12 || hl < 1e-12 || (tx * hx + ty * hy + tz * hz) / (tl * hl) < 0.5) break
        removed += sqrt(d2(a, supply[supply.size - 2]).toDouble())
        supply.removeAt(supply.size - 1)
    }
}

/**
 * The bend joining the end of [path] to the start of [onward]: a cubic Bezier that leaves along the
 * direction [path] arrives in and reaches [onward] already heading the way it leaves. When the two
 * arms lie side by side running opposite ways this is a U; when the return starts straight ahead
 * it is a smooth turn rather than a hook back on itself.
 */
private fun link(path: List<Vec3>, onward: List<Vec3>, steps: Int = 16): List<Vec3> {
    val a = path.last(); val b = onward.first()
    val gap = sqrt(d2(a, b).toDouble()).toFloat()
    if (gap < 1e-7f) return emptyList()
    fun direction(points: List<Vec3>, from: Vec3): Vec3? {
        // Over a little more than the gap, so the kink where a lap snaps onto the next ring does not set the tangent.
        val far = points.firstOrNull { sqrt(d2(it, from).toDouble()) > 1.2 * gap } ?: points.lastOrNull { d2(it, from) > 1e-14f } ?: return null
        val dx = from.x - far.x; val dy = from.y - far.y; val dz = from.z - far.z
        val l = sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat()
        return vec(dx / l, dy / l, dz / l)
    }
    val tIn = direction(path.asReversed(), a) ?: return emptyList()
    val tOut = direction(onward, b)?.let { vec(-it.x, -it.y, -it.z) } ?: return emptyList()
    val h = 0.75f * gap
    val p1 = vec(a.x + tIn.x * h, a.y + tIn.y * h, a.z + tIn.z * h)
    val p2 = vec(b.x - tOut.x * h, b.y - tOut.y * h, b.z - tOut.z * h)
    return (1 until steps).map { i ->
        val t = i.toFloat() / steps; val u = 1 - t
        val c0 = u * u * u; val c1 = 3 * u * u * t; val c2 = 3 * u * t * t; val c3 = t * t * t
        vec(c0 * a.x + c1 * p1.x + c2 * p2.x + c3 * b.x, c0 * a.y + c1 * p1.y + c2 * p2.y + c3 * b.y, c0 * a.z + c1 * p1.z + c2 * p2.z + c3 * b.z)
    }
}

private fun d2(a: Vec3, b: Vec3): Float { val dx = a.x - b.x; val dy = a.y - b.y; val dz = a.z - b.z; return dx * dx + dy * dy + dz * dz }

private fun nearestIndex(r: List<Vec3>, p: Vec3) = r.indices.minByOrNull { d2(r[it], p) } ?: 0

/** Closed rings without their repeated end point, all turned the same way round as the first. */
private fun orientedRings(rings: List<List<Vec3>>): List<List<Vec3>> {
    val closed = rings.filter { it.size > 3 }.map { r -> if (r.first() == r.last()) r.dropLast(1) else r }
    if (closed.isEmpty()) return emptyList()
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
    return closed.map { r ->
        val a = areaVector(r)
        if (a[0] * ref[0] + a[1] * ref[1] + a[2] * ref[2] < 0) r.reversed() else r
    }
}

/**
 * Walk [oriented] rings from [start], blending each lap towards the next ring (see [spiralFromRings]).
 * With [finalLap] the walk ends with one plain lap of the last ring; without, it ends where the last
 * blended lap reaches that ring.
 */
private fun spiralThrough(oriented: List<List<Vec3>>, start: Vec3, finalLap: Boolean = true): List<Vec3> {
    val out = ArrayList<Vec3>()
    var at = start
    for (k in oriented.indices) {
        val ring = oriented[k].let { r -> val s = nearestIndex(r, at); r.drop(s) + r.take(s) + r[s] }
        val next = oriented.getOrNull(k + 1)
        // The last blended lap already ends on the last ring; going round it again is optional.
        if (next == null && !finalLap && k > 0) break
        val lengths = DoubleArray(ring.size)
        for (i in 1 until ring.size) lengths[i] = lengths[i - 1] + sqrt(d2(ring[i], ring[i - 1]).toDouble())
        val total = lengths.last().coerceAtLeast(1e-12)
        // The next ring, started level with this lap's start. Each point of this lap is paired with
        // the nearest point of the next ring found by searching only forwards from the last pairing,
        // so the pairing never runs backwards (plain nearest point folds back on itself where the
        // rings change shape) and never skips ahead to a far side of the ring (plain arc length drifts
        // where part of the ring shrinks faster than the rest, as one wing of an L-shaped plan does).
        val target = next?.let { r -> val s = nearestIndex(r, ring.first()); r.drop(s) + r.take(s) + r[s] }
        val window = target?.let { maxOf(4, it.size / 4) } ?: 0
        var cursor = 0
        for (i in ring.indices) {
            val p = ring[i]
            if (target == null) { if (out.isEmpty() || d2(out.last(), p) > 1e-14f) out += p; continue }
            if (i == ring.lastIndex) cursor = target.lastIndex
            else {
                var best = cursor; var bestD = d2(target[cursor], p)
                for (j in cursor + 1..minOf(cursor + window, target.lastIndex)) {
                    val d = d2(target[j], p)
                    if (d < bestD) { bestD = d; best = j }
                }
                cursor = best
            }
            val q = target[cursor]
            val s = (lengths[i] / total).toFloat()
            val blended = vec(p.x + s * (q.x - p.x), p.y + s * (q.y - p.y), p.z + s * (q.z - p.z))
            // Where one lap ends and the next begins the point repeats; a zero-length segment draws nothing useful.
            if (out.isEmpty() || d2(out.last(), blended) > 1e-14f) out += blended
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

/**
 * A harmonic winding angle about the point of a disc-like surface farthest from its edge, on a copy
 * of the mesh with a small hole left round that point and cut open from the hole to the edge.
 *
 * [mesh] is the cut copy: the vertices along the cut are doubled, and the triangles on one side use
 * the copies. [angle] runs from 0 to 1 once round, jumping by exactly 1 across the cut; it is the
 * harmonic function with that jump (cotangent weights, natural boundary condition), so it spreads the
 * turn evenly round any outline, the way the angle of a conformal map does. [distance] is the field it
 * was built from, carried onto the cut copy, and [centre] is the vertex it winds about (an index into
 * [mesh]).
 *
 * The point is [fieldSpiral]: the contours of `distance / pitch + angle` are one curve that winds
 * inward a pitch per turn, and two contours of one function never cross.
 */
class WindingAngle internal constructor(
    val mesh: TriMesh,
    val angle: DoubleArray,
    val distance: DoubleArray,
    val centre: Int,
    /** Where the cut meets the edge: the two ends of a [fieldSpiral] or [fieldDoubleSpiral] start here. */
    val port: Vec3,
)

/**
 * The [WindingAngle] of this mesh about the vertex where [distance] (usually
 * [geodesicDistanceFromBoundary]) is largest. The cut follows steepest descent of [distance] from
 * there to the edge, and triangles within [hole] of the largest distance are left out (see the
 * class). [port], if given, is a point on (or near) the edge where the cut should come out: the
 * spiral's two ends start there. A cut aimed past an inside corner comes out where it first meets the
 * edge. Expects a welded mesh with one boundary loop.
 */
fun TriMesh.windingAngle(
    distance: DoubleArray,
    hole: Double = 0.08 * (distance.maxOrNull() ?: 0.0),
    port: Vec3? = null,
): WindingAngle {
    require(distance.size == vertexCount) { "windingAngle needs one distance per vertex" }
    val n = vertexCount
    // An angle has no value at the point it winds about, and asked to be harmonic right up to that
    // point it puts the whole turn in a sliver beside the cut. Leaving out a small hole there makes
    // the surface a ring, where the angle spreads evenly.
    val dMax = distance.maxOrNull() ?: 0.0
    val kept = BooleanArray(triangleCount) { t -> maxOf(distance[ia(t)], distance[ib(t)], distance[ic(t)]) <= dMax - hole }
    val nb = Array(n) { HashSet<Int>() }
    val edgeFaces = HashMap<Long, Int>()
    fun key(a: Int, b: Int) = if (a < b) a.toLong() * n + b else b.toLong() * n + a
    for (t in 0 until triangleCount) {
        val c = intArrayOf(ia(t), ib(t), ic(t))
        for (e in 0..2) {
            val a = c[e]; val b = c[(e + 1) % 3]
            nb[a] += b; nb[b] += a
            edgeFaces.merge(key(a, b), 1, Int::plus)
        }
    }
    val onBoundary = BooleanArray(n)
    for ((k, count) in edgeFaces) if (count == 1) { onBoundary[(k / n).toInt()] = true; onBoundary[(k % n).toInt()] = true }
    require(onBoundary.any { it }) { "windingAngle needs a surface with an edge" }

    val centre = distance.indices.maxBy { distance[it] }
    // The cut runs downhill from the centre: on [distance] to the nearest edge, or, given a [port],
    // on the geodesic distance from the edge vertex nearest it, so the cut (and with it where a
    // spiral's ends meet the edge) lands at the port.
    fun descend(f: DoubleArray): List<Int>? {
        val path = arrayListOf(centre)
        while (!onBoundary[path.last()]) {
            val here = path.last()
            val next = nb[here].minBy { f[it] }
            if (f[next] >= f[here]) return null
            path += next
        }
        return path
    }
    val cut = if (port == null) {
        descend(distance) ?: error("distance has a pit; the cut cannot reach the edge")
    } else {
        val target = (0 until n).filter { onBoundary[it] }.minBy { i ->
            val dx = x(i) - port.x; val dy = y(i) - port.y; val dz = z(i) - port.z
            dx * dx + dy * dy + dz * dz
        }
        val toPort = geodesicDistance(target)
        // Downhill on the distance to the port alone hugs any inside corner on the way and stops at
        // the first edge vertex it touches; taking off half the distance to the nearest wall keeps
        // it in the open until it reaches the port. Plain distance to the port if that finds a pit.
        descend(DoubleArray(n) { toPort[it] - 0.5 * distance[it] }) ?: descend(toPort) ?: error("the cut cannot reach the edge")
    }
    val onCut = HashMap<Int, Int>()
    cut.forEachIndexed { i, v -> onCut[v] = i }

    fun p(i: Int) = doubleArrayOf(x(i).toDouble(), y(i).toDouble(), z(i).toDouble())
    fun sub(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    // Which triangles lie on the left of the cut (seen from the surface's normal side) and take the copies.
    val left = BooleanArray(triangleCount)
    val used = BooleanArray(n)
    for (t in 0 until triangleCount) if (kept[t]) { used[ia(t)] = true; used[ib(t)] = true; used[ic(t)] = true }
    for (t in 0 until triangleCount) {
        if (!kept[t]) continue
        val c = intArrayOf(ia(t), ib(t), ic(t))
        val touching = c.filter { it in onCut }
        if (touching.isEmpty()) continue
        val normal = cross(sub(p(c[1]), p(c[0])), sub(p(c[2]), p(c[0])))
        val mid = doubleArrayOf((p(c[0])[0] + p(c[1])[0] + p(c[2])[0]) / 3, (p(c[0])[1] + p(c[1])[1] + p(c[2])[1]) / 3, (p(c[0])[2] + p(c[1])[2] + p(c[2])[2]) / 3)
        // Side of the nearest cut segment the triangle's centre is on.
        var best = Double.MAX_VALUE; var side = 0.0
        for (v in touching) {
            val i = onCut.getValue(v)
            for (j in listOf(i - 1, i)) {
                if (j < 0 || j + 1 >= cut.size) continue
                val a = p(cut[j]); val b = p(cut[j + 1])
                val ab = sub(b, a); val am = sub(mid, a)
                val s = (dot(am, ab) / dot(ab, ab)).coerceIn(0.0, 1.0)
                val foot = doubleArrayOf(a[0] + s * ab[0], a[1] + s * ab[1], a[2] + s * ab[2])
                val d = dot(sub(mid, foot), sub(mid, foot))
                if (d < best) { best = d; side = dot(normal, cross(ab, am)) }
            }
        }
        left[t] = side > 0
    }
    // Every cut vertex on the kept surface is doubled, from the hole's edge out to the outline.
    val copyOf = HashMap<Int, Int>()
    var next = n
    for (v in cut) if (used[v]) copyOf[v] = next++
    val jump = IntArray(next)
    for ((_, c) in copyOf) jump[c] = 1

    val keptTriangles = (0 until triangleCount).filter { kept[it] }
    val cutIndices = IntArray(keptTriangles.size * 3) { k ->
        val t = keptTriangles[k / 3]
        val v = indices[3 * t + k % 3]
        if (left[t] && v in copyOf) copyOf.getValue(v) else v
    }
    val cutPositions = FloatArray(next * 3)
    System.arraycopy(positions, 0, cutPositions, 0, n * 3)
    for ((v, c) in copyOf) for (d in 0..2) cutPositions[3 * c + d] = positions[3 * v + d]
    val cutMesh = TriMesh(cutPositions, cutIndices)
    val originalOf = IntArray(next) { if (it < n) it else -1 }
    for ((v, c) in copyOf) originalOf[c] = v

    // Minimise sum w_ij ((th_i + J_i) - (th_j + J_j))^2 over the original vertices th, with J the jump
    // carried by each corner: L th = b. Vertex 0 is pinned (the angle is defined up to a constant).
    val wide = widen(positions)
    val trip = DMatrixSparseTriplet(n, n, 7 * n)
    val diag = DoubleArray(n)
    val off = HashMap<Long, Double>()
    val rhs = DMatrixRMaj(n, 1)
    for (t in keptTriangles.indices) {
        val c = intArrayOf(cutIndices[3 * t], cutIndices[3 * t + 1], cutIndices[3 * t + 2])
        for (e in 0..2) {
            val i = c[e]; val j = c[(e + 1) % 3]; val k = c[(e + 2) % 3]
            val a = originalOf[i]; val b = originalOf[j]
            // This triangle's half of the edge's cotangent weight; summed over triangles it is [MeshTopology.cotangentWeights] on the kept surface.
            val w = 0.5 * cornerCotangent(wide, originalOf[k], a, b)
            if (a == b) continue
            diag[a] += w; diag[b] += w
            // Each interior edge comes up once per triangle; the triplet form does not sum repeats.
            off.merge(if (a < b) a.toLong() * n + b else b.toLong() * n + a, w, Double::plus)
            val dj = (jump[i] - jump[j]).toDouble()
            rhs[a, 0] -= w * dj; rhs[b, 0] += w * dj
        }
    }
    for ((k, w) in off) { val a = (k / n).toInt(); val b = (k % n).toInt(); trip.addItem(a, b, -w); trip.addItem(b, a, -w) }
    // Vertices inside the hole have no triangles left; an identity row keeps the system definite.
    for (i in 0 until n) if (!used[i]) diag[i] = 1.0
    val pin = (0 until n).first { used[it] }
    for (i in 0 until n) trip.addItem(i, i, diag[i] + if (i == pin) 1e6 * (diag[i] + 1.0) else 0.0)
    rhs[pin, 0] = 0.0
    val solver = LinearSolverFactory_DSCC.cholesky(FillReducing.NONE)
    require(solver.setA(DConvertMatrixStruct.convert(trip, null as DMatrixSparseCSC?))) { "winding-angle system did not factor" }
    val th = DMatrixRMaj(n, 1)
    solver.solve(rhs, th)

    val angle = DoubleArray(next) { th[originalOf[it], 0] + jump[it] }
    val lo = (0 until next).filter { it >= n || used[it] }.minOf { angle[it] }
    for (i in angle.indices) angle[i] -= lo
    return WindingAngle(cutMesh, angle, DoubleArray(next) { distance[originalOf[it]] }, centre, vec(x(cut.last()), y(cut.last()), z(cut.last())))
}

/**
 * A spiral toolpath traced as one contour of a single field, `distance / pitch + angle`, on the
 * [WindingAngle]'s cut mesh: each contour runs once round from one side of the cut to the other,
 * where it meets the next contour a [pitch] further in, and the pieces join into one curve from the
 * edge to the middle. Because they are contours of one function, no two laps can cross, whatever the
 * outline does, where [spiralFromRings], which blends between separate rings, folds where one wing of
 * a plan runs out before the rest. [phase] (0..1) shifts the laps by a fraction of a pitch.
 */
fun WindingAngle.fieldSpiral(pitch: Double, phase: Double = 0.5): List<Vec3> {
    require(pitch > 0.0) { "pitch must be positive" }
    val g = DoubleArray(angle.size) { distance[it] / pitch + angle[it] }
    val lo = kotlin.math.floor(g.min()).toInt(); val hi = kotlin.math.ceil(g.max()).toInt()
    // Only whole laps: a lap runs from distance (k + phase - 1) · pitch on one side of the cut to
    // (k + phase) · pitch on the other, so keep those that start inside the walls and end short of the hole.
    val inner = (0 until mesh.triangleCount).maxOf { t -> minOf(distance[mesh.ia(t)], distance[mesh.ib(t)], distance[mesh.ic(t)]) }
    // Every piece of every whole lap's contour. Usually one piece a lap, but where the cut is not
    // monotone in distance a contour meets the cut more than once and breaks into pieces.
    val pieces = ArrayList<List<Vec3>>()
    for (k in lo..hi) {
        if ((k + phase - 1) * pitch < 0.0) continue
        if ((k + phase) * pitch > inner) continue
        pieces += mesh.isolines(g, k + phase).filter { it.size >= 2 }
    }
    if (pieces.isEmpty()) return emptyList()
    // Chain them end to end: where a piece ends on one side of the cut, the angle there is one turn
    // more than on the other side, so the piece that carries on starts at exactly that point. Start
    // from the piece end nearest where the cut meets the edge, the port.
    val tol = (pitch * pitch * 1e-6).toFloat()
    val used = BooleanArray(pieces.size)
    val first = pieces.indices.minBy { i -> minOf(d2(pieces[i].first(), port), d2(pieces[i].last(), port)) }
    used[first] = true
    val out = ArrayList<Vec3>()
    out += pieces[first].let { if (d2(it.first(), port) <= d2(it.last(), port)) it else it.reversed() }
    while (true) {
        val end = out.last()
        val next = pieces.indices.firstOrNull { !used[it] && (d2(pieces[it].first(), end) < tol || d2(pieces[it].last(), end) < tol) } ?: break
        used[next] = true
        val piece = if (d2(pieces[next].first(), end) < tol) pieces[next] else pieces[next].reversed()
        for (q in piece) if (d2(out.last(), q) > 1e-14f) out += q
    }
    return out
}

private fun WindingAngle.fieldAt(p: Vec3): Double {
    var best = Float.MAX_VALUE; var at = 0
    for (i in 0 until mesh.vertexCount) {
        val dx = mesh.x(i) - p.x; val dy = mesh.y(i) - p.y; val dz = mesh.z(i) - p.z
        val d = dx * dx + dy * dy + dz * dz
        if (d < best) { best = d; at = i }
    }
    return distance[at]
}

/**
 * The counterflow double spiral as two contours of the field of [fieldSpiral]: supply on the laps at
 * phase 0.25 and return on those at 0.75 of a pitch of `2 · spacing`, so every return lap lies
 * between two supply laps a [spacing] from each, joined in the middle by a U-bend. Returns supply
 * (ending with the bend) and return, the way [doubleSpiralArms] does, and like it never crosses —
 * but here on any disc-like outline, an L-shaped room included.
 */
fun WindingAngle.fieldDoubleSpiral(spacing: Double): Pair<List<Vec3>, List<Vec3>> {
    val supply = ArrayList(fieldSpiral(2 * spacing, 0.25))
    val back = fieldSpiral(2 * spacing, 0.75)
    if (supply.size < 2 || back.size < 2) return supply to back
    val onward = back.asReversed()
    backOff(supply, onward.first())
    supply += link(supply.toList(), onward)
    return supply to listOf(supply.last()) + onward
}
