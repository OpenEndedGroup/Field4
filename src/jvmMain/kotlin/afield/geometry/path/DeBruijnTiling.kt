package afield.geometry.path

import afield.graphics.render.color
import afield.graphics.render.filled
import afield.graphics.render.stroked
import afield.graphics.render.thickness
import afield.math.linalg.Vector2d
import afield.math.linalg.Vector4f
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/*
 * Quasiperiodic rhombus tilings by de Bruijn's multigrid method (N. G. de Bruijn, "Algebraic theory
 * of Penrose's non-periodic tilings of the plane", 1981). The construction follows Aatish Bhatia's
 * Pattern Collider (https://github.com/aatishb/patterncollider, MIT licence), re-written here for
 * FLines: n families of parallel lines, every crossing of two lines becomes one rhombus whose edges
 * are perpendicular to those two lines, and each corner sits at the sum of the strip indices times
 * the family directions.
 */

/** One grid line of a de Bruijn multigrid: line [index] of direction [family]. */
data class DeBruijnLine(val family: Int, val index: Int)

/**
 * One rhombus of a [DeBruijnTiling].
 *
 * @param corners the four corners, in the tiling's drawing units, going round the rhombus.
 * @param type the angle class: the rhombus's acute angle is `type * PI / symmetry`. A 5-fold
 *   (Penrose) tiling has type 1 (thin, 36 degrees) and type 2 (thick, 72 degrees).
 * @param first the grid line of the lower-numbered family whose crossing made this tile.
 * @param second the other grid line of that crossing.
 * @param crossing where the two grid lines cross, in grid coordinates (before the tile map).
 */
class DeBruijnTile(
    val corners: List<Vector2d>,
    val type: Int,
    val first: DeBruijnLine,
    val second: DeBruijnLine,
    val crossing: Vector2d,
) {
    /** The mean of the four corners. */
    val center: Vector2d = Vector2d(corners.sumOf { it.x } / 4, corners.sumOf { it.y } / 4)

    /** The acute angle of this rhombus in degrees, for a tiling of [symmetry]-fold symmetry. */
    fun acuteDegrees(symmetry: Int) = type * 180.0 / symmetry

    /** True if [line] is one of the two grid lines that made this tile, i.e. the tile is on its ribbon. */
    fun on(line: DeBruijnLine) = first == line || second == line

    /** The outline as a closed FLine with no styling; [DeBruijnTiling.lines] adds colour. */
    fun outline(): FLine {
        val f = FLine().moveTo(corners[0].x, corners[0].y)
        for (i in 1 until 4) f.lineTo(corners[i].x, corners[i].y)
        return f.close()
    }

    /** The signed area of the rhombus (positive or negative depending on its winding). */
    fun signedArea(): Double {
        var a = 0.0
        for (i in 0 until 4) {
            val p = corners[i]
            val q = corners[(i + 1) % 4]
            a += p.x * q.y - q.x * p.y
        }
        return a / 2
    }
}

/**
 * A rhombus tiling made by [deBruijnTiling]: its [tiles], plus the grid that produced them.
 *
 * Tiles can be coloured by [DeBruijnTile.type], and the tiles a single grid line passes through
 * (its "ribbon") come from [ribbon].
 */
class DeBruijnTiling(
    val symmetry: Int,
    val offsets: List<Double>,
    val directions: List<Vector2d>,
    val tiles: List<DeBruijnTile>,
) {
    /** How many acute-angle classes this symmetry has: `symmetry / 2`. */
    val typeCount: Int get() = symmetry / 2

    /** The tiles made by crossings on [line]: a chain of rhombi sharing parallel edges. */
    fun ribbon(line: DeBruijnLine): List<DeBruijnTile> = tiles.filter { it.on(line) }

    /** The tiles made by crossings on line [index] of [family]. */
    fun ribbon(family: Int, index: Int) = ribbon(DeBruijnLine(family, index))

    /** The number of tiles of each [DeBruijnTile.type], keyed by type. */
    fun typeCounts(): Map<Int, Int> = tiles.groupingBy { it.type }.eachCount().toSortedMap()

    /**
     * Each tile's neighbours across a shared edge, by index into [tiles] (the dual graph).
     * Boundary tiles have fewer than four.
     */
    fun adjacency(): List<List<Int>> {
        val byEdge = HashMap<Pair<Long, Long>, MutableList<Int>>()
        tiles.forEachIndexed { t, tile ->
            for (i in 0 until 4) byEdge.getOrPut(edgeKey(tile.corners[i], tile.corners[(i + 1) % 4])) { mutableListOf() }.add(t)
        }
        val out = List(tiles.size) { mutableListOf<Int>() }
        for (shared in byEdge.values) if (shared.size == 2) {
            out[shared[0]].add(shared[1])
            out[shared[1]].add(shared[0])
        }
        return out
    }

    /**
     * Filled, outlined FLines, one per tile, coloured by [palette] (indexed by `type - 1`, repeating).
     * Pass [highlight] to draw one grid line's ribbon in [highlightColor] instead.
     * Set [fill] to false for plain outlines suitable for a pen plotter.
     */
    fun lines(
        palette: List<Vector4f> = defaultPalette,
        fill: Boolean = true,
        outlineThickness: Double = 1.0,
        highlight: DeBruijnLine? = null,
        highlightColor: Vector4f = Vector4f(1f, 0.35f, 0.2f, 1f),
    ): List<FLine> = tiles.map { tile ->
        tile.outline().apply {
            color = if (highlight != null && tile.on(highlight)) highlightColor else palette[(tile.type - 1).mod(palette.size)]
            filled = fill
            stroked = !fill
            thickness = outlineThickness
        }
    } + if (fill) tiles.map { tile ->
        tile.outline().apply {
            color = Vector4f(0.08f, 0.08f, 0.1f, 1f)
            stroked = true
            thickness = outlineThickness
        }
    } else emptyList()

    companion object {
        /** A palette of six colours, light to dark, for tile types 1..6. */
        val defaultPalette: List<Vector4f> = listOf(
            Vector4f(0.98f, 0.82f, 0.36f, 1f),
            Vector4f(0.30f, 0.62f, 0.86f, 1f),
            Vector4f(0.56f, 0.80f, 0.52f, 1f),
            Vector4f(0.86f, 0.48f, 0.62f, 1f),
            Vector4f(0.62f, 0.52f, 0.86f, 1f),
            Vector4f(0.94f, 0.62f, 0.34f, 1f),
        )

        internal fun edgeKey(a: Vector2d, b: Vector2d): Pair<Long, Long> {
            val ka = pointKey(a)
            val kb = pointKey(b)
            return if (ka <= kb) ka to kb else kb to ka
        }

        private fun pointKey(p: Vector2d): Long =
            (Math.round(p.x * 1e4) shl 32) xor (Math.round(p.y * 1e4) and 0xffffffffL)
    }
}

/**
 * Builds a quasiperiodic rhombus tiling by de Bruijn's multigrid method.
 *
 * [symmetry] families of parallel lines are laid down, family `k` perpendicular to the unit vector
 * `e_k` (at angle `2 PI k / symmetry` for odd [symmetry], `PI k / symmetry` for even). Line `j` of
 * family `k` is the set of points `x` with `x . e_k + offsets[k] = j`. Every crossing of two lines
 * becomes one rhombus with edges along the two families' `e` vectors, placed at
 * `sum_k K_k e_k`, where `K_k` is the strip of family `k` the crossing lies in. The result
 * covers the plane edge to edge with no gaps or overlaps.
 *
 * - `symmetry = 5` with offsets summing to zero is a Penrose rhomb tiling (thin 36, thick 72 degree).
 * - `symmetry = 8` is Ammann-Beenker (squares and 45 degree rhombi).
 * - [disorder] in `0.0 ..< 1.0` moves each grid line by up to `disorder / 2` at random (seeded by
 *   [seed]); the tiling stays valid but loses its long-range order.
 *
 * Only crossings within [radius] of the grid origin (in grid units, one unit = one line spacing)
 * become tiles. [scale] is the rhombus edge length in drawing units, and the tiling is centred at
 * ([centerX], [centerY]).
 *
 * The method and its parameters follow Aatish Bhatia's Pattern Collider
 * (https://github.com/aatishb/patterncollider, MIT) and N. G. de Bruijn (1981).
 *
 * ```kotlin
 * val penrose = deBruijnTiling(symmetry = 5, radius = 6.0, scale = 20.0)
 * lines(penrose.lines(), w = 600.0, h = 600.0, fitToSize = true)
 * ```
 */
fun deBruijnTiling(
    symmetry: Int = 5,
    offsets: List<Double>? = null,
    radius: Double = 6.0,
    disorder: Double = 0.0,
    seed: Int = 1,
    scale: Double = 20.0,
    centerX: Double = 0.0,
    centerY: Double = 0.0,
): DeBruijnTiling {
    require(symmetry >= 3) { "symmetry must be at least 3, was $symmetry" }
    require(disorder in 0.0..0.99) { "disorder must be in 0.0..0.99, was $disorder" }
    val n = symmetry
    val gamma = offsets ?: defaultDeBruijnOffsets(n)
    require(gamma.size == n) { "need $n offsets, got ${gamma.size}" }

    val step = if (n % 2 == 1) 2 * PI / n else PI / n
    val e = List(n) { Vector2d(cos(step * it), sin(step * it)) }

    // Grid lines considered: enough that every crossing within radius is found.
    val range = ceil(radius).toInt() + 2
    val random = Random(seed)
    val jitter = Array(n) { DoubleArray(2 * range + 1) { if (disorder > 0) disorder * (random.nextDouble() - 0.5) else 0.0 } }

    // Position (along e_k) of line j of family k.
    fun position(k: Int, j: Int): Double {
        val base = j - gamma[k]
        val i = j + range
        return if (i in 0..2 * range) base + jitter[k][i] else base
    }

    // The strip index of t along family k: the greatest j whose line lies below t.
    fun strip(k: Int, t: Double): Int {
        var j = floor(t + gamma[k]).toInt() + 2
        while (position(k, j) >= t) j--
        return j
    }

    val tiles = ArrayList<DeBruijnTile>()
    val k = IntArray(n)
    for (r in 0 until n) for (s in r + 1 until n) {
        val er = e[r]
        val es = e[s]
        val det = er.x * es.y - er.y * es.x
        if (abs(det) < 1e-9) continue
        val cosAngle = abs(er.x * es.x + er.y * es.y).coerceAtMost(1.0)
        val type = (acos(cosAngle) / (PI / n)).roundToInt()
        for (a in -range..range) for (b in -range..range) {
            val pa = position(r, a)
            val pb = position(s, b)
            // Solve x . e_r = pa, x . e_s = pb.
            val x = (pa * es.y - pb * er.y) / det
            val y = (er.x * pb - es.x * pa) / det
            if (hypot(x, y) > radius) continue
            for (q in 0 until n) k[q] = if (q == r || q == s) 0 else strip(q, x * e[q].x + y * e[q].y)
            var bx = 0.0
            var by = 0.0
            for (q in 0 until n) if (q != r && q != s) {
                bx += k[q] * e[q].x
                by += k[q] * e[q].y
            }
            val corners = listOf(a - 1 to b - 1, a to b - 1, a to b, a - 1 to b).map { (ka, kb) ->
                Vector2d(
                    centerX + scale * (bx + ka * er.x + kb * es.x),
                    centerY + scale * (by + ka * er.y + kb * es.y),
                )
            }
            tiles.add(DeBruijnTile(corners, type, DeBruijnLine(r, a), DeBruijnLine(s, b), Vector2d(x, y)))
        }
    }

    // Recentre so the tiling's mean tile centre lands on (centerX, centerY).
    if (tiles.isNotEmpty()) {
        val mx = tiles.sumOf { it.center.x } / tiles.size - centerX
        val my = tiles.sumOf { it.center.y } / tiles.size - centerY
        val moved = tiles.map { t -> DeBruijnTile(t.corners.map { Vector2d(it.x - mx, it.y - my) }, t.type, t.first, t.second, t.crossing) }
        return DeBruijnTiling(n, gamma, e, moved)
    }
    return DeBruijnTiling(n, gamma, e, tiles)
}

/**
 * Generic offsets for a [symmetry]-fold multigrid, chosen so that no three grid lines meet at a
 * point. They sum to zero, which for 5-fold makes the result a true Penrose tiling. A 3-fold grid
 * with offsets summing to an integer always has triple points, so for `symmetry = 3` they sum to
 * 0.3 instead.
 */
fun defaultDeBruijnOffsets(symmetry: Int): List<Double> {
    // A fixed hash rather than an arithmetic sequence: evenly stepped offsets are rationally
    // related and put three lines through one point (7-fold did, with golden-ratio steps).
    val raw = List(symmetry) { (sin((it + 1) * 12.9898 + symmetry * 78.233) * 43758.5453).mod(1.0) }
    val mean = raw.average()
    val shift = if (symmetry == 3) 0.1 else 0.0
    return raw.map { it - mean + shift }
}
