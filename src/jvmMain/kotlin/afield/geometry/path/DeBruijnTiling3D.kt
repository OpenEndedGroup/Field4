package afield.geometry.path

import afield.geometry.mesh.TriMesh
import afield.graphics.render.color
import afield.graphics.render.filled
import afield.graphics.render.stroked
import afield.graphics.render.thickness
import afield.math.linalg.Vector2d
import afield.math.linalg.Vector3d
import afield.math.linalg.Vector4f
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/*
 * De Bruijn's multigrid lifted to three dimensions: families of parallel planes instead of lines,
 * and every point where three planes of different families meet becomes one parallelepiped
 * (a rhombohedron when the directions are unit vectors). With the six 5-fold axes of the
 * icosahedron the cells are Ammann's two rhombohedra, prolate and oblate, and the result is the
 * three-dimensional Penrose tiling. The 3D grid ("generalized dual") method is J. E. S. Socolar and
 * P. J. Steinhardt, "Quasicrystals with arbitrary orientational symmetry", Phys. Rev. B 32, 5547
 * (1985); the same tiling by projection from six dimensions is P. Kramer and R. Neri, Acta Cryst.
 * A40, 580 (1984). The construction is the 2D one in DeBruijnTiling.kt with one more index.
 */

/** One plane of a 3D de Bruijn multigrid: plane [index] of direction [family]. */
data class DeBruijnPlane(val family: Int, val index: Int)

/**
 * The direction sets a [deBruijnTiling3D] can be built from, one direction per plane family.
 *
 * Each is a set of symmetry axes of a polyhedron, one vector per axis. The icosahedral sets are
 * quasiperiodic; the cubic ones have rational directions and repeat.
 */
enum class DeBruijnStar(val directions: List<Vector3d>) {
    /** The three 4-fold axes of a cube: the multigrid of a cubic lattice, whose cells are cubes. */
    CUBIC(listOf(Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), Vector3d(0.0, 0.0, 1.0))),

    /** The four 3-fold axes of a cube (its body diagonals). One rhombohedron, with 70.5 degree faces. */
    CUBIC_THREEFOLD(unit(listOf(Vector3d(1.0, 1.0, 1.0), Vector3d(1.0, -1.0, -1.0), Vector3d(-1.0, 1.0, -1.0), Vector3d(-1.0, -1.0, 1.0)))),

    /** The six 2-fold axes of a cube (its face diagonals). Some triples are coplanar and make no cell. */
    CUBIC_TWOFOLD(unit(listOf(Vector3d(1.0, 1.0, 0.0), Vector3d(1.0, -1.0, 0.0), Vector3d(1.0, 0.0, 1.0), Vector3d(1.0, 0.0, -1.0), Vector3d(0.0, 1.0, 1.0), Vector3d(0.0, 1.0, -1.0)))),

    /**
     * The six 5-fold axes of an icosahedron. Every pair meets at the same angle (cosine 1/sqrt 5),
     * and the cells are Ammann's prolate and oblate golden rhombohedra: the 3D Penrose tiling.
     */
    ICOSAHEDRAL(unit(listOf(
        Vector3d(0.0, 1.0, PHI), Vector3d(0.0, -1.0, PHI), Vector3d(1.0, PHI, 0.0),
        Vector3d(-1.0, PHI, 0.0), Vector3d(PHI, 0.0, 1.0), Vector3d(-PHI, 0.0, 1.0),
    ))),

    /** The ten 3-fold axes of an icosahedron (pairs of opposite dodecahedron vertices). */
    ICOSAHEDRAL_THREEFOLD(unit(
        listOf(Vector3d(1.0, 1.0, 1.0), Vector3d(1.0, 1.0, -1.0), Vector3d(1.0, -1.0, 1.0), Vector3d(-1.0, 1.0, 1.0)) +
            listOf(
                Vector3d(0.0, 1 / PHI, PHI), Vector3d(0.0, -1 / PHI, PHI), Vector3d(1 / PHI, PHI, 0.0),
                Vector3d(-1 / PHI, PHI, 0.0), Vector3d(PHI, 0.0, 1 / PHI), Vector3d(-PHI, 0.0, 1 / PHI),
            ),
    )),
}

private const val PHI = 1.618033988749895

private fun unit(v: List<Vector3d>) = v.map { val l = sqrt(it.x * it.x + it.y * it.y + it.z * it.z); Vector3d(it.x / l, it.y / l, it.z / l) }

/**
 * One cell of a [DeBruijnTiling3D]: the parallelepiped made where three planes cross.
 *
 * Its eight [corners] are indexed by bits: corner `i + 2j + 4k` is the base corner plus `i` steps
 * along [families]' first direction, `j` along the second and `k` along the third. Each corner's
 * integer lift (one integer per family, the vertex as a point of Z^n) is [lift]; two cells that
 * share a corner share its lift exactly, which is what [faceKey] and the tests use.
 *
 * @param shape the shape class, numbered 1, 2, ... in order of decreasing volume; two cells share a
 *   number exactly when they are congruent. For
 *   [DeBruijnStar.ICOSAHEDRAL], shape 1 is the prolate rhombohedron and shape 2 the oblate one.
 * @param planes the three planes whose crossing made this cell, lowest family first.
 * @param crossing where they cross, in grid coordinates (one unit = one plane spacing).
 */
class DeBruijnCell internal constructor(
    val shape: Int,
    val planes: List<DeBruijnPlane>,
    val crossing: Vector3d,
    private val base: IntArray,
    /** The 24 corner coordinates, xyz for corners 0..7, in drawing units. */
    private val xyz: DoubleArray,
    /** True when the three directions are right-handed, so the [faces] winding needs no flip. */
    internal val rightHanded: Boolean,
    /** The cell's volume in drawing units. */
    val volume: Double,
) {
    /** The three families whose planes crossed. */
    val families: List<Int> get() = planes.map { it.family }

    /** The eight corners, in drawing units; see the class comment for their order. */
    val corners: List<Vector3d> get() = List(8) { Vector3d(xyz[it * 3], xyz[it * 3 + 1], xyz[it * 3 + 2]) }

    /** The corners as 24 doubles (x, y, z per corner), the form `Solid.hullOfPointsD` takes. */
    fun cornerArray(): DoubleArray = xyz.copyOf()

    /** The mean of the eight corners. */
    val center: Vector3d = Vector3d(
        (0 until 8).sumOf { xyz[it * 3] } / 8, (0 until 8).sumOf { xyz[it * 3 + 1] } / 8, (0 until 8).sumOf { xyz[it * 3 + 2] } / 8,
    )

    /** Corner [corner]'s position in Z^n: the strip index of every family. */
    fun lift(corner: Int): IntArray = base.copyOf().also {
        if (corner and 1 != 0) it[planes[0].family]++
        if (corner and 2 != 0) it[planes[1].family]++
        if (corner and 4 != 0) it[planes[2].family]++
    }

    /** The six faces as quads of corner indices, wound counter-clockwise seen from outside. */
    fun faces(): List<IntArray> = if (rightHanded) FACES else FACES.map { it.reversedArray() }

    /** A key equal for the same face of two neighbouring cells: the four corner lifts, sorted. */
    fun faceKey(face: IntArray): List<List<Int>> = face.map { lift(it).toList() }.sortedWith(LIFT_ORDER)

    /** True if [plane] is one of the three planes that made this cell. */
    fun on(plane: DeBruijnPlane) = plane in planes

    /**
     * True if [p] lies strictly inside this cell, more than [margin] (as a fraction of each edge)
     * from every face.
     */
    fun contains(p: Vector3d, margin: Double = 1e-9): Boolean {
        val c = coordinates(p)
        return c.all { it > margin && it < 1 - margin }
    }

    /** [p] in the cell's own frame: the three edge-vector coefficients from corner 0. */
    private fun coordinates(p: Vector3d): DoubleArray {
        val o = doubleArrayOf(xyz[0], xyz[1], xyz[2])
        val a = DoubleArray(3) { xyz[3 + it] - o[it] }
        val b = DoubleArray(3) { xyz[6 + it] - o[it] }
        val c = DoubleArray(3) { xyz[12 + it] - o[it] }
        val d = doubleArrayOf(p.x - o[0], p.y - o[1], p.z - o[2])
        val det = det3(a, b, c)
        return doubleArrayOf(det3(d, b, c) / det, det3(a, d, c) / det, det3(a, b, d) / det)
    }

    internal fun x(corner: Int, axis: Int) = xyz[corner * 3 + axis]

    internal companion object {
        // Corner i + 2j + 4k; faces at i = 0, i = 1, j = 0, j = 1, k = 0, k = 1, outward for a
        // right-handed (positive determinant) triple of edge directions.
        val FACES = listOf(
            intArrayOf(0, 4, 6, 2), intArrayOf(1, 3, 7, 5),
            intArrayOf(0, 1, 5, 4), intArrayOf(2, 6, 7, 3),
            intArrayOf(0, 2, 3, 1), intArrayOf(4, 5, 7, 6),
        )

        /** The twelve edges as corner pairs. */
        val EDGES = listOf(0 to 1, 2 to 3, 4 to 5, 6 to 7, 0 to 2, 1 to 3, 4 to 6, 5 to 7, 0 to 4, 1 to 5, 2 to 6, 3 to 7)

        val LIFT_ORDER = Comparator<List<Int>> { a, b ->
            for (i in a.indices) if (a[i] != b[i]) return@Comparator a[i].compareTo(b[i])
            0
        }
    }
}

/** One cell's cross section in a [DeBruijnSlice]: a convex polygon of 3 to 6 sides. */
class DeBruijnSlicePiece(
    /** The cell this polygon cuts through. */
    val cell: DeBruijnCell,
    /** The polygon in the slice's (u, v) coordinates, counter-clockwise. */
    val polygon: List<Vector2d>,
) {
    /** The polygon as a closed FLine with no styling. */
    fun outline(): FLine {
        val f = FLine().moveTo(polygon[0].x, polygon[0].y)
        for (i in 1 until polygon.size) f.lineTo(polygon[i].x, polygon[i].y)
        return f.close()
    }

    /** The polygon's area. */
    fun area(): Double {
        var a = 0.0
        for (i in polygon.indices) {
            val p = polygon[i]
            val q = polygon[(i + 1) % polygon.size]
            a += p.x * q.y - q.x * p.y
        }
        return a / 2
    }
}

/**
 * A plane cut through a [DeBruijnTiling3D]: every cell the plane passes through, as a polygon in
 * the plane's own 2D coordinates. Each cell's polygon shares its edges with its neighbours', so a
 * slice is itself a tiling of the plane by triangles, quadrilaterals, pentagons and hexagons.
 */
class DeBruijnSlice(
    /** The unit normal of the cutting plane. */
    val normal: Vector3d,
    /** The plane is the set of points `p` with `p . normal = offset`, in drawing units. */
    val offset: Double,
    /** The slice's x axis in 3D (unit, perpendicular to [normal]). */
    val u: Vector3d,
    /** The slice's y axis in 3D (unit, perpendicular to [normal] and [u]). */
    val v: Vector3d,
    val pieces: List<DeBruijnSlicePiece>,
) {
    /** How many polygons have each number of sides, keyed by side count. */
    fun sideCounts(): Map<Int, Int> = pieces.groupingBy { it.polygon.size }.eachCount().toSortedMap()

    /** How many polygons come from each cell shape, keyed by [DeBruijnCell.shape]. */
    fun shapeCounts(): Map<Int, Int> = pieces.groupingBy { it.cell.shape }.eachCount().toSortedMap()

    /**
     * The slice as FLines, one per colour plus (when [fill] is on) one dark FLine of outlines,
     * like [DeBruijnTiling.lines]. Colour is by [DeBruijnCell.shape] (`palette[shape - 1]`, repeating),
     * or by side count with [bySides].
     */
    fun lines(
        palette: List<Vector4f> = DeBruijnTiling.defaultPalette,
        fill: Boolean = true,
        outlineThickness: Double = 1.0,
        bySides: Boolean = false,
    ): List<FLine> = pieces.groupBy { (if (bySides) it.polygon.size - 3 else it.cell.shape - 1).mod(palette.size) }
        .toSortedMap().map { (slot, group) ->
            FLine().apply {
                group.forEach { appendPolygon(it.polygon) }
                color = palette[slot]
                filled = fill
                stroked = !fill
                thickness = outlineThickness
            }
        } + if (fill) listOf(
        FLine().apply {
            pieces.forEach { appendPolygon(it.polygon) }
            color = Vector4f(0.08f, 0.08f, 0.1f, 1f)
            stroked = true
            thickness = outlineThickness
        },
    ) else emptyList()

    private fun FLine.appendPolygon(p: List<Vector2d>) {
        moveTo(p[0].x, p[0].y)
        for (i in 1 until p.size) lineTo(p[i].x, p[i].y)
        lineTo(p[0].x, p[0].y)
    }
}

/**
 * A space-filling by parallelepipeds made by [deBruijnTiling3D]: its [cells], plus the plane
 * directions and offsets that produced them.
 */
class DeBruijnTiling3D(
    /** One unit direction per plane family. */
    val directions: List<Vector3d>,
    /** One offset per family: plane `j` of family `k` is `x . directions[k] + offsets[k] = j`. */
    val offsets: List<Double>,
    val cells: List<DeBruijnCell>,
    /** The volume of each shape class, by [DeBruijnCell.shape] - 1, in drawing units. */
    val shapeVolumes: List<Double>,
) {
    /** The number of cells of each [DeBruijnCell.shape], keyed by shape. */
    fun shapeCounts(): Map<Int, Int> = cells.groupingBy { it.shape }.eachCount().toSortedMap()

    /** The total volume of [cells], in drawing units. */
    fun volume(): Double = cells.sumOf { it.volume }

    /** The cells made by crossings on [plane]: de Bruijn's ribbon, one dimension up (a layer). */
    fun layer(plane: DeBruijnPlane): List<DeBruijnCell> = cells.filter { it.on(plane) }

    /**
     * Each face shared by two cells, as their two indices into [cells]; the dual graph's edges.
     * A face on the boundary of the patch has one cell and is not listed.
     */
    fun sharedFaces(): List<Pair<Int, Int>> {
        val byFace = HashMap<List<List<Int>>, MutableList<Int>>()
        cells.forEachIndexed { i, c -> for (f in c.faces()) byFace.getOrPut(c.faceKey(f)) { mutableListOf() }.add(i) }
        return byFace.values.filter { it.size == 2 }.map { it[0] to it[1] }
    }

    /**
     * The cells cut by the plane `p . normal = offset` (in drawing units), as 2D polygons. The
     * slice's axes are [u] and its normal-perpendicular partner; by default [u] is the direction
     * perpendicular to [normal] nearest the x axis (or the y axis, if [normal] is along x).
     */
    fun slice(normal: Vector3d, offset: Double = 0.0, u: Vector3d? = null): DeBruijnSlice {
        val n = normalize(normal)
        val uu = normalize(reject(u ?: if (abs(n.x) < 0.9) Vector3d(1.0, 0.0, 0.0) else Vector3d(0.0, 1.0, 0.0), n))
        val vv = cross(n, uu)
        val pieces = ArrayList<DeBruijnSlicePiece>()
        for (cell in cells) {
            val d = DoubleArray(8) { cell.x(it, 0) * n.x + cell.x(it, 1) * n.y + cell.x(it, 2) * n.z - offset }
            if (d.all { it > 0 } || d.all { it < 0 }) continue
            val points = ArrayList<Vector2d>()
            for ((a0, b0) in DeBruijnCell.EDGES) {
                if ((d[a0] > 0) == (d[b0] > 0)) continue
                // Interpolate from the lower lift, so a neighbour sharing this edge gets the same bits.
                val (a, b) = if (DeBruijnCell.LIFT_ORDER.compare(cell.lift(a0).toList(), cell.lift(b0).toList()) <= 0) a0 to b0 else b0 to a0
                val t = d[a] / (d[a] - d[b])
                val px = cell.x(a, 0) + t * (cell.x(b, 0) - cell.x(a, 0))
                val py = cell.x(a, 1) + t * (cell.x(b, 1) - cell.x(a, 1))
                val pz = cell.x(a, 2) + t * (cell.x(b, 2) - cell.x(a, 2))
                points += Vector2d(px * uu.x + py * uu.y + pz * uu.z, px * vv.x + py * vv.y + pz * vv.z)
            }
            if (points.size < 3) continue
            val cx = points.sumOf { it.x } / points.size
            val cy = points.sumOf { it.y } / points.size
            pieces += DeBruijnSlicePiece(cell, points.sortedBy { atan2(it.y - cy, it.x - cx) })
        }
        return DeBruijnSlice(n, offset, uu, vv, pieces)
    }

    /**
     * The cells as a flat-shaded [TriMesh], each coloured by shape from [palette]. [shrink] below 1
     * scales every cell about its own centre, which opens gaps between them so the packing shows.
     * Pass [only] to draw a subset (a slab, a layer, the cells near the centre).
     */
    fun toTriMesh(
        only: List<DeBruijnCell> = cells,
        palette: List<Vector4f> = DeBruijnTiling.defaultPalette,
        shrink: Double = 1.0,
    ): TriMesh {
        val positions = FloatArray(only.size * 24 * 3)
        val normals = FloatArray(only.size * 24 * 3)
        val colors = FloatArray(only.size * 24 * 3)
        val indices = IntArray(only.size * 36)
        var v = 0
        var t = 0
        for (cell in only) {
            val c = palette[(cell.shape - 1).mod(palette.size)]
            val center = cell.center
            for (face in cell.faces()) {
                val p = face.map { k ->
                    doubleArrayOf(
                        center.x + shrink * (cell.x(k, 0) - center.x),
                        center.y + shrink * (cell.x(k, 1) - center.y),
                        center.z + shrink * (cell.x(k, 2) - center.z),
                    )
                }
                val e1 = DoubleArray(3) { p[1][it] - p[0][it] }
                val e2 = DoubleArray(3) { p[3][it] - p[0][it] }
                val nx = e1[1] * e2[2] - e1[2] * e2[1]
                val ny = e1[2] * e2[0] - e1[0] * e2[2]
                val nz = e1[0] * e2[1] - e1[1] * e2[0]
                val nl = sqrt(nx * nx + ny * ny + nz * nz)
                val first = v
                for (q in p) {
                    for (axis in 0 until 3) positions[v * 3 + axis] = q[axis].toFloat()
                    normals[v * 3] = (nx / nl).toFloat(); normals[v * 3 + 1] = (ny / nl).toFloat(); normals[v * 3 + 2] = (nz / nl).toFloat()
                    colors[v * 3] = c.x; colors[v * 3 + 1] = c.y; colors[v * 3 + 2] = c.z
                    v++
                }
                for (i in intArrayOf(0, 1, 2, 0, 2, 3)) indices[t++] = first + i
            }
        }
        return TriMesh(positions, indices, normals, null, colors)
    }

    /**
     * This tiling turned so that [up] points along +z: the same cells, the same lifts, with every
     * corner recomputed from rotated directions. Use it to lay a slab or a relief flat.
     */
    fun oriented(up: Vector3d): DeBruijnTiling3D {
        val z = normalize(up)
        val x = normalize(reject(if (abs(z.x) < 0.9) Vector3d(1.0, 0.0, 0.0) else Vector3d(0.0, 1.0, 0.0), z))
        val y = cross(z, x)
        fun rotate(p: Vector3d) = Vector3d(dot(p, x), dot(p, y), dot(p, z))
        val turned = directions.map(::rotate)
        val moved = cells.map { cell ->
            val xyz = DoubleArray(24)
            for (k in 0 until 8) {
                val p = rotate(Vector3d(cell.x(k, 0), cell.x(k, 1), cell.x(k, 2)))
                xyz[k * 3] = p.x; xyz[k * 3 + 1] = p.y; xyz[k * 3 + 2] = p.z
            }
            DeBruijnCell(cell.shape, cell.planes, cell.crossing, cell.lift(0), xyz, cell.rightHanded, cell.volume)
        }
        return DeBruijnTiling3D(turned, offsets, moved, shapeVolumes)
    }
}

/**
 * Builds a three-dimensional quasiperiodic tiling by de Bruijn's multigrid method.
 *
 * One family of evenly spaced parallel planes is laid down per direction of [star] (or of
 * [directions], which overrides it): plane `j` of family `k` is the set of points `x` with
 * `x . e_k + offsets[k] = j`. Every point where three planes of different families meet becomes
 * one parallelepiped with edges along those three families' directions, placed at `sum_k K_k e_k`
 * where `K_k` is the slab of family `k` the crossing lies in. The cells fill space face to face
 * with no gaps or overlaps.
 *
 * - [DeBruijnStar.ICOSAHEDRAL] (the default) is the 3D Penrose tiling: Ammann's prolate and
 *   oblate golden rhombohedra, about 1.618 prolate for every oblate.
 * - [DeBruijnStar.CUBIC] gives cubes; the other cubic stars give periodic rhombohedral packings.
 *
 * Only crossings within [radius] of the grid origin (in plane spacings) become cells. Pass
 * [planes] to keep only those plane indices in every family instead: `planes = 0..0` (six planes,
 * twenty crossings) gives the twenty rhombohedra that fill a rhombic triacontahedron, and
 * `0 until m` fills one m times the size. [scale] is the edge length in drawing units; with
 * [recenter] the mean cell centre is moved to ([centerX], [centerY], [centerZ]).
 *
 * Where four planes meet at one point no tiling exists; [onDegenerate] nudges the offsets and
 * retries (the default) or throws [DeBruijnDegenerateException].
 *
 * The 3D grid method is Socolar and Steinhardt's "generalized dual method" (Phys. Rev. B 32, 5547,
 * 1985); the 3D Penrose tiling itself was found by projection by Kramer and Neri (1984).
 *
 * ```kotlin
 * val penrose3 = deBruijnTiling3D(radius = 3.0, scale = 10.0)
 * showMesh(penrose3.toTriMesh(shrink = 0.9))
 * ```
 */
fun deBruijnTiling3D(
    star: DeBruijnStar = DeBruijnStar.ICOSAHEDRAL,
    offsets: List<Double>? = null,
    radius: Double = 3.0,
    planes: IntRange? = null,
    scale: Double = 10.0,
    directions: List<Vector3d>? = null,
    centerX: Double = 0.0,
    centerY: Double = 0.0,
    centerZ: Double = 0.0,
    onDegenerate: DeBruijnDegeneracy = DeBruijnDegeneracy.NUDGE,
    recenter: Boolean = true,
): DeBruijnTiling3D {
    val e = unit(directions ?: star.directions)
    val requested = offsets ?: defaultDeBruijnOffsets(e.size)
    var attempt = 0
    while (true) {
        val tried = if (attempt == 0) requested else requested.mapIndexed { k, g -> g + 1e-6 * attempt * (k + 1) * (if (k % 2 == 0) 1 else -1) }
        try {
            return buildDeBruijnTiling3D(e, tried, radius, planes, scale, doubleArrayOf(centerX, centerY, centerZ), recenter)
        } catch (d: DeBruijnDegenerateException) {
            if (onDegenerate == DeBruijnDegeneracy.ERROR || attempt >= 8) throw d
            attempt++
        }
    }
}

private fun buildDeBruijnTiling3D(
    e: List<Vector3d>,
    gamma: List<Double>,
    radius: Double,
    planes: IntRange?,
    scale: Double,
    center: DoubleArray,
    recenter: Boolean,
): DeBruijnTiling3D {
    val n = e.size
    require(n >= 3) { "need at least three plane families, got $n" }
    require(gamma.size == n) { "need $n offsets, got ${gamma.size}" }
    require(planes == null || !planes.isEmpty()) { "planes is empty" }
    val range = ceil(radius).toInt() + 2
    val indices = planes ?: -range..range
    val limit = if (planes == null) radius else Double.POSITIVE_INFINITY

    fun position(k: Int, j: Int) = j - gamma[k]

    // The slab of t in family k: one less than the lowest plane index above t, within the planes kept.
    fun slab(k: Int, t: Double): Int {
        val j = floor(t + gamma[k]).toInt()
        return j.coerceIn(indices.first - 1, indices.last)
    }

    val e3 = e.map { doubleArrayOf(it.x, it.y, it.z) }
    // Shape classes, largest volume first. A parallelepiped of unit edges is fixed, up to
    // congruence, by the three |cos| between its edges and the sign of their product (flipping one
    // edge flips two cosines); equal volumes need not mean equal shapes (cube face diagonals).
    val triples = ArrayList<IntArray>()
    for (r in 0 until n) for (s in r + 1 until n) for (t in s + 1 until n)
        if (abs(det3(e3[r], e3[s], e3[t])) > 1e-9) triples += intArrayOf(r, s, t)
    fun cos(a: Int, b: Int) = e3[a][0] * e3[b][0] + e3[a][1] * e3[b][1] + e3[a][2] * e3[b][2]
    fun shapeKey(tri: IntArray): List<Double> {
        val c = listOf(cos(tri[0], tri[1]), cos(tri[0], tri[2]), cos(tri[1], tri[2]))
        val product = c[0] * c[1] * c[2]
        val sign = if (abs(product) < 1e-9) 0.0 else Math.signum(product)
        return listOf(abs(det3(e3[tri[0]], e3[tri[1]], e3[tri[2]]))) + c.map { abs(it) }.sorted() + sign
    }
    fun same(a: List<Double>, b: List<Double>) = a.indices.all { abs(a[it] - b[it]) < 1e-9 }
    val keys = triples.map(::shapeKey)
    val classes = ArrayList<List<Double>>()
    val order = Comparator<List<Double>> { a, b ->
        for (i in a.indices) if (abs(a[i] - b[i]) >= 1e-9) return@Comparator if (i == 0) b[i].compareTo(a[i]) else a[i].compareTo(b[i])
        0
    }
    for (key in keys.sortedWith(order)) if (classes.none { same(it, key) }) classes += key

    data class Raw(val shape: Int, val planes: List<DeBruijnPlane>, val crossing: DoubleArray, val base: IntArray, val triple: IntArray, val right: Boolean)

    val raw = ArrayList<Raw>()
    val k = IntArray(n)
    for ((ti, tri) in triples.withIndex()) {
        val (r, s, t) = Triple(tri[0], tri[1], tri[2])
        val det = det3(e3[r], e3[s], e3[t])
        val shape = classes.indexOfFirst { same(it, keys[ti]) } + 1
        for (a in indices) for (b in indices) for (c in indices) {
            // Solve x . e_r = pa, x . e_s = pb, x . e_t = pc by Cramer's rule on the transposed system.
            val rhs = doubleArrayOf(position(r, a), position(s, b), position(t, c))
            val rows = arrayOf(e3[r], e3[s], e3[t])
            val x = DoubleArray(3) { col ->
                val m = Array(3) { row -> DoubleArray(3) { if (it == col) rhs[row] else rows[row][it] } }
                det3(m[0], m[1], m[2]) / det
            }
            if (sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2]) > limit) continue
            for (q in 0 until n) {
                if (q == r || q == s || q == t) continue
                val tq = x[0] * e3[q][0] + x[1] * e3[q][1] + x[2] * e3[q][2]
                // A fourth plane through this crossing, among the planes kept: no tiling here.
                val near = Math.round(tq + gamma[q]).toInt()
                if (near in indices && abs(position(q, near) - tq) < 1e-9)
                    throw DeBruijnDegenerateException(listOf(DeBruijnLine(r, a), DeBruijnLine(s, b), DeBruijnLine(t, c), DeBruijnLine(q, near)))
                k[q] = slab(q, tq)
            }
            k[r] = a - 1
            k[s] = b - 1
            k[t] = c - 1
            raw += Raw(shape, listOf(DeBruijnPlane(r, a), DeBruijnPlane(s, b), DeBruijnPlane(t, c)), x, k.copyOf(), tri, det > 0)
        }
    }

    // Corner positions straight from the integer lifts, summed in family order, so two cells that
    // share a vertex compute it to the same bits. Recentring subtracts one shift from every corner.
    fun place(lift: IntArray, axis: Int): Double {
        var p = 0.0
        for (q in 0 until n) p += lift[q] * e3[q][axis]
        return scale * p
    }

    val corners = raw.map { cell ->
        DoubleArray(24) { i ->
            val corner = i / 3
            val lift = cell.base.copyOf()
            if (corner and 1 != 0) lift[cell.triple[0]]++
            if (corner and 2 != 0) lift[cell.triple[1]]++
            if (corner and 4 != 0) lift[cell.triple[2]]++
            place(lift, i % 3)
        }
    }
    val shift = DoubleArray(3) { axis ->
        if (recenter && corners.isNotEmpty()) corners.sumOf { c -> (0 until 8).sumOf { c[it * 3 + axis] } / 8 } / corners.size - center[axis]
        else -center[axis]
    }
    val cube = scale * scale * scale
    val cells = raw.mapIndexed { i, cell ->
        val xyz = DoubleArray(24) { corners[i][it] - shift[it % 3] }
        DeBruijnCell(cell.shape, cell.planes, Vector3d(cell.crossing[0], cell.crossing[1], cell.crossing[2]), cell.base, xyz, cell.right, classes[cell.shape - 1][0] * cube)
    }
    return DeBruijnTiling3D(e, gamma, cells, classes.map { it[0] * cube })
}

private fun det3(a: DoubleArray, b: DoubleArray, c: DoubleArray) =
    a[0] * (b[1] * c[2] - b[2] * c[1]) - a[1] * (b[0] * c[2] - b[2] * c[0]) + a[2] * (b[0] * c[1] - b[1] * c[0])

private fun dot(a: Vector3d, b: Vector3d) = a.x * b.x + a.y * b.y + a.z * b.z

private fun cross(a: Vector3d, b: Vector3d) = Vector3d(a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x)

private fun normalize(a: Vector3d): Vector3d {
    val l = sqrt(dot(a, a))
    return Vector3d(a.x / l, a.y / l, a.z / l)
}

private fun reject(a: Vector3d, n: Vector3d): Vector3d {
    val d = dot(a, n)
    return Vector3d(a.x - d * n.x, a.y - d * n.y, a.z - d * n.z)
}
