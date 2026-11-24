@file:afield.runtime.completion.CompletionCategory("Graphics")

package afield.geometry.mesh

/*
 * Relaxing a path on a floor: repulsion between laps, a push off the walls, smoothing, and growth
 * into free space, the way differential growth fills a region with a curve.
 *
 * The starting path keeps its topology (repulsion and a cap on how far a point moves per step keep it
 * from passing through itself), so a double spiral relaxes into a better double spiral: laps evened
 * out to the spacing, gaps and bare patches grown into, corners rounded. Started from a short arc it
 * would grow into a labyrinth instead, which is why [relaxPath] is meant to start from
 * [fieldDoubleSpiral] or [doubleSpiralArms].
 */
import afield.math.linalg.Vec2
import afield.math.linalg.Vec3
import afield.math.linalg.vec
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The result of [relaxPath]: the relaxed [points], the index where the second arm begins
 * ([split], carried through every split and merge of segments; `points.size` if there was only one
 * arm), and the measurements a pipe layout is judged by.
 *
 * [closest] is the smallest distance between two points that are not near each other along the path
 * (should be close to the spacing), [tightestBend] the smallest radius of curvature, [crossings] the
 * number of pairs of segments that cross (should be 0), and [length] the path's length.
 */
class RelaxedPath internal constructor(
    val points: List<Vec3>,
    val split: Int,
    val closest: Double,
    val tightestBend: Double,
    val crossings: Int,
    val length: Double,
    val steps: Int,
) {
    /** The first arm, up to and including the split. */
    val first: List<Vec3> get() = points.subList(0, min(split + 1, points.size))

    /** The second arm, from the split on. */
    val second: List<Vec3> get() = points.subList(min(split, points.size), points.size)
}

/**
 * Signed distance from a point on the floor (its `x` and `z`) to the walls of [plan] (a closed
 * polygon, `x` and `y` of each point mapped to the floor's `x` and `z`, as for [outlineMesh]):
 * positive inside, negative outside. For [relaxPath]'s `wall`.
 */
fun outlineWallDistance(plan: List<Vec2>): (Vec3) -> Double {
    val pts = if (plan.first() == plan.last()) plan.dropLast(1) else plan
    return { p ->
        val x = p.x.toDouble(); val y = p.z.toDouble()
        var best = Double.MAX_VALUE
        var inside = false
        var j = pts.size - 1
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[j]
            if ((a.y > y) != (b.y > y) && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) inside = !inside
            val ex = (b.x - a.x).toDouble(); val ey = (b.y - a.y).toDouble()
            val t = (((x - a.x) * ex + (y - a.y) * ey) / (ex * ex + ey * ey).coerceAtLeast(1e-18)).coerceIn(0.0, 1.0)
            val dx = a.x + t * ex - x; val dy = a.y + t * ey - y
            best = min(best, dx * dx + dy * dy)
            j = i
        }
        if (inside) sqrt(best) else -sqrt(best)
    }
}

/**
 * Relax a path lying on a floor (the `x`–`z` plane) with its two ends fixed: laps closer than
 * [spacing] push each other apart, points nearer a wall than half a spacing are pushed off it, each
 * point is drawn a little towards the middle of its neighbours ([smoothing], which also rounds
 * corners), and where one side of the path has no pipe or wall within [growth] spacings the path
 * pushes out into it. Segments are split and merged as it moves so points stay about a fifth of a
 * spacing apart, which is what lets it grow.
 *
 * [first] and [second] are the two arms of a double spiral ([second] continues from the end of
 * [first]); pass an empty [second] for a single path. [wall] is the signed distance to the walls,
 * positive inside ([outlineWallDistance]). No point moves more than 3% of a spacing in a step,
 * so neighbouring laps cannot pass through each other; [RelaxedPath.crossings] reports whether that
 * held. [onStep], if given, is called every [every] steps with the step number and the current points.
 */
fun relaxPath(
    first: List<Vec3>,
    second: List<Vec3> = emptyList(),
    spacing: Double,
    wall: (Vec3) -> Double,
    steps: Int = 2000,
    smoothing: Double = 0.02,
    stiffness: Double = 0.05,
    growth: Double = 1.5,
    every: Int = 20,
    onStep: ((Int, List<Vec3>) -> Unit)? = null,
): RelaxedPath {
    require(spacing > 0.0) { "spacing must be positive" }
    val y = (first.firstOrNull() ?: second.firstOrNull())?.y?.toDouble() ?: 0.0
    val xs = ArrayList<Double>(); val zs = ArrayList<Double>(); val arm = ArrayList<Int>()
    fun add(p: Vec3, a: Int) {
        if (xs.isNotEmpty() && abs(xs.last() - p.x) + abs(zs.last() - p.z) < 1e-9) return
        xs += p.x.toDouble(); zs += p.z.toDouble(); arm += a
    }
    first.forEach { add(it, 0) }
    second.forEach { add(it, 1) }
    require(xs.size >= 3) { "relaxPath needs a path of at least three points" }

    val h = spacing / 5.0
    fun wallAt(x: Double, z: Double) = wall(vec(x, y, z))

    fun resample() {
        var i = 0
        while (i < xs.size - 1) {
            val dx = xs[i + 1] - xs[i]; val dz = zs[i + 1] - zs[i]
            val d = sqrt(dx * dx + dz * dz)
            if (d > 1.5 * h) {
                xs.add(i + 1, xs[i] + dx / 2); zs.add(i + 1, zs[i] + dz / 2); arm.add(i + 1, arm[i + 1])
                continue
            }
            // Never merge away an end, and never the first point of the second arm.
            if (d < 0.5 * h && i + 1 < xs.size - 1 && arm[i + 1] == arm[i]) {
                xs.removeAt(i + 1); zs.removeAt(i + 1); arm.removeAt(i + 1)
                continue
            }
            i++
        }
    }

    // Points this close along the path are neighbours, not other laps: they never repel.
    val window = (2.0 * spacing / h).toInt() + 2
    fun buildHash(cell: Double): HashMap<Long, MutableList<Int>> {
        val grid = HashMap<Long, MutableList<Int>>()
        for (i in xs.indices) {
            val k = (floor(xs[i] / cell).toLong() shl 32) xor (floor(zs[i] / cell).toLong() and 0xffffffffL)
            grid.getOrPut(k) { ArrayList(8) } += i
        }
        return grid
    }
    fun near(grid: HashMap<Long, MutableList<Int>>, cell: Double, x: Double, z: Double, visit: (Int) -> Unit) {
        val cx = floor(x / cell).toLong(); val cz = floor(z / cell).toLong()
        for (a in cx - 1..cx + 1) for (b in cz - 1..cz + 1) grid[(a shl 32) xor (b and 0xffffffffL)]?.forEach(visit)
    }

    val maxMove = 0.03 * spacing
    var done = 0
    for (step in 1..steps) {
        resample()
        val n = xs.size
        val fx = DoubleArray(n); val fz = DoubleArray(n)
        val grow = DoubleArray(n); val gnx = DoubleArray(n); val gnz = DoubleArray(n)
        val reach = growth * spacing
        val cell = max(spacing, reach)
        // The bend: where the first arm hands over to the second. Only near it may the path grow.
        val bend = arm.indexOfFirst { it == 1 }
        val bendReach = (3 * spacing / h).toInt()
        val grid = buildHash(cell)
        for (i in 1 until n - 1) {
            // Tangent and the floor normal (tangent turned a quarter in x–z).
            var tx = xs[i + 1] - xs[i - 1]; var tz = zs[i + 1] - zs[i - 1]
            val tl = sqrt(tx * tx + tz * tz).coerceAtLeast(1e-12); tx /= tl; tz /= tl
            val nx = -tz; val nz = tx
            // Room on each side: the nearest lap roughly across from here, or the wall, counted at
            // twice its distance (a pipe should sit half a spacing from a wall and a whole spacing
            // from the next lap), capped at the reach.
            var left = reach; var right = reach
            near(grid, cell, xs[i], zs[i]) { j ->
                if (abs(j - i) <= window) return@near
                val dx = xs[j] - xs[i]; val dz = zs[j] - zs[i]
                val d = sqrt(dx * dx + dz * dz)
                if (d < 1e-12) return@near
                if (d < 0.8 * spacing) {
                    // Too close: push straight apart, whatever the angle.
                    val push = (0.8 * spacing - d) / spacing
                    fx[i] -= 2 * push * dx / d; fz[i] -= 2 * push * dz / d
                }
                val along = dx * nx + dz * nz
                if (abs(along) < 0.7 * d) return@near
                if (along > 0) left = min(left, along) else right = min(right, -along)
            }
            // The wall, measured exactly: its distance, and which side of the path it is on from the
            // gradient (stepping out along the normal to find it overestimates the room by a step).
            val wHere = wallAt(xs[i], zs[i])
            val e = 0.05 * spacing
            val gx = (wallAt(xs[i] + e, zs[i]) - wallAt(xs[i] - e, zs[i])) / (2 * e)
            val gz = (wallAt(xs[i], zs[i] + e) - wallAt(xs[i], zs[i] - e)) / (2 * e)
            val toward = -(gx * nx + gz * nz)
            if (abs(toward) > 0.3) {
                val wallRoom = 2 * wHere / abs(toward)
                if (toward > 0) left = min(left, wallRoom) else right = min(right, wallRoom)
            }
            // Move towards the roomier side: laps centre between their neighbours, and a lap with free
            // floor beside it grows into it.
            grow[i] = ((left - right) / spacing).coerceIn(-1.0, 1.0)
            gnx[i] = nx; gnz[i] = nz
            if (bend > 0 && abs(i - bend) < bendReach && left >= 0.95 * reach && right >= 0.95 * reach) {
                // The bend in the middle of a double spiral, with free floor on both sides: push it
                // outward, away from its centre of curvature, so it lengthens and the spiral winds
                // further in. Growth anywhere else, even where a gap happens to be wide, buckles the
                // laps into a labyrinth.
                val mx = (xs[i - 1] + xs[i + 1]) / 2 - xs[i]; val mz = (zs[i - 1] + zs[i + 1]) / 2 - zs[i]
                val ml = sqrt(mx * mx + mz * mz)
                if (ml > 1e-4 * h) { fx[i] -= 0.5 * mx / ml; fz[i] -= 0.5 * mz / ml }
            }
            if (wHere < 0.4 * spacing) {
                val gl = sqrt(gx * gx + gz * gz).coerceAtLeast(1e-12)
                val push = (0.4 * spacing - wHere) / spacing
                fx[i] += 2 * push * gx / gl; fz[i] += 2 * push * gz / gl
            }
            if (i >= 2 && i < n - 2) {
                // Bending stiffness: minus the fourth difference, which damps ripples a few points long
                // hard and gentle curves hardly at all.
                val bx = xs[i - 2] - 4 * xs[i - 1] + 6 * xs[i] - 4 * xs[i + 1] + xs[i + 2]
                val bz = zs[i - 2] - 4 * zs[i - 1] + 6 * zs[i] - 4 * zs[i + 1] + zs[i + 2]
                fx[i] -= stiffness * bx / h; fz[i] -= stiffness * bz / h
            }
            val mx = (xs[i - 1] + xs[i + 1]) / 2 - xs[i]; val mz = (zs[i - 1] + zs[i + 1]) / 2 - zs[i]
            fx[i] += smoothing * mx / h; fz[i] += smoothing * mz / h
        }
        // Averaged over a few neighbours, so the decision to grow does not flicker point by point.
        for (i in 1 until n - 1) {
            var g = 0.0; var c = 0
            for (j in max(1, i - 3)..min(n - 2, i + 3)) { g += grow[j]; c++ }
            g /= c
            fx[i] += 0.5 * g * gnx[i]; fz[i] += 0.5 * g * gnz[i]
        }
        for (i in 1 until n - 1) {
            var dx = fx[i] * 0.05 * spacing; var dz = fz[i] * 0.05 * spacing
            val d = sqrt(dx * dx + dz * dz)
            if (d > maxMove) { dx *= maxMove / d; dz *= maxMove / d }
            xs[i] += dx; zs[i] += dz
        }
        done = step
        if (onStep != null && step % every == 0) onStep(step, xs.indices.map { vec(xs[it], y, zs[it]) })
    }
    resample()

    val n = xs.size
    val grid = buildHash(spacing)
    var closest = Double.MAX_VALUE
    for (i in 0 until n) near(grid, spacing, xs[i], zs[i]) { j ->
        if (abs(j - i) <= window) return@near
        val dx = xs[i] - xs[j]; val dz = zs[i] - zs[j]
        closest = min(closest, sqrt(dx * dx + dz * dz))
    }
    if (closest == Double.MAX_VALUE) closest = spacing
    var tightest = Double.MAX_VALUE
    // Radius of the circle through points a fifth of a spacing either side.
    val k = 2
    for (i in k until n - k) {
        val ax = xs[i - k]; val az = zs[i - k]; val bx = xs[i]; val bz = zs[i]; val cx = xs[i + k]; val cz = zs[i + k]
        val ab = sqrt((bx - ax) * (bx - ax) + (bz - az) * (bz - az))
        val bc = sqrt((cx - bx) * (cx - bx) + (cz - bz) * (cz - bz))
        val ca = sqrt((ax - cx) * (ax - cx) + (az - cz) * (az - cz))
        val area2 = abs((bx - ax) * (cz - az) - (cx - ax) * (bz - az))
        if (area2 > 1e-14) tightest = min(tightest, ab * bc * ca / (2 * area2))
    }
    var crossings = 0
    for (i in 0 until n - 1) near(grid, spacing, xs[i], zs[i]) { j ->
        if (j <= i + 1 || j >= n - 1) return@near
        if (segmentsCross(xs[i], zs[i], xs[i + 1], zs[i + 1], xs[j], zs[j], xs[j + 1], zs[j + 1])) crossings++
    }
    var length = 0.0
    for (i in 1 until n) length += sqrt((xs[i] - xs[i - 1]).let { it * it } + (zs[i] - zs[i - 1]).let { it * it })
    val split = arm.indexOfFirst { it == 1 }.let { if (it < 0) n else max(0, it - 1) }
    return RelaxedPath(xs.indices.map { vec(xs[it], y, zs[it]) }, split, closest, tightest, crossings, length, done)
}

/**
 * The largest bare circle on a floor: the greatest distance from any sample point (a grid of
 * [cell] over the floor, inside the walls) to the nearest point of [path] or to a wall. How far a
 * layout is from covering the floor evenly; a perfect one at spacing `s` scores about `s / 2`.
 */
fun largestBareCircle(path: List<Vec3>, wall: (Vec3) -> Double, minX: Double, maxX: Double, minZ: Double, maxZ: Double, cell: Double): Double {
    val y = path.firstOrNull()?.y ?: 0f
    val hashCell = 4 * cell
    val grid = HashMap<Long, MutableList<Vec3>>()
    for (p in path) grid.getOrPut((floor(p.x / hashCell).toLong() shl 32) xor (floor(p.z / hashCell).toLong() and 0xffffffffL)) { ArrayList() } += p
    var worst = 0.0
    var x = minX + cell / 2
    while (x < maxX) {
        var z = minZ + cell / 2
        while (z < maxZ) {
            val w = wall(vec(x, y, z))
            if (w > 0) {
                var best = w
                val cx = floor(x / hashCell).toLong(); val cz = floor(z / hashCell).toLong()
                var ring = 0
                // Widen the search ring until it is beyond the best distance found.
                while ((ring - 1) * hashCell < best && ring < 64) {
                    for (a in cx - ring..cx + ring) for (b in cz - ring..cz + ring) {
                        if (maxOf(abs(a - cx), abs(b - cz)) != ring.toLong()) continue
                        grid[(a shl 32) xor (b and 0xffffffffL)]?.forEach { p ->
                            val dx = p.x - x; val dz = p.z - z
                            best = min(best, sqrt(dx * dx + dz * dz))
                        }
                    }
                    ring++
                }
                worst = max(worst, best)
            }
            z += cell
        }
        x += cell
    }
    return worst
}

private fun segmentsCross(ax: Double, az: Double, bx: Double, bz: Double, cx: Double, cz: Double, dx: Double, dz: Double): Boolean {
    fun orient(px: Double, pz: Double, qx: Double, qz: Double, rx: Double, rz: Double) = (qx - px) * (rz - pz) - (qz - pz) * (rx - px)
    val o1 = orient(ax, az, bx, bz, cx, cz); val o2 = orient(ax, az, bx, bz, dx, dz)
    val o3 = orient(cx, cz, dx, dz, ax, az); val o4 = orient(cx, cz, dx, dz, bx, bz)
    return o1 * o2 < 0 && o3 * o4 < 0
}
