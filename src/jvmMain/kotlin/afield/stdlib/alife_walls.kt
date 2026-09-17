@file:afield.CompletionCategory("ALife")

package afield.stdlib

import afield.graphics.FLine
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Walls for `alife_script.kt`: obstacles, swept circle collision, and radius-aware routing around
 * them.
 *
 * See `alife_script.kt` for the scripting layer this completes, and `alife.kt` for the family
 * overview.
 *
 * ### Why this exists
 *
 * [ScriptWorld] shipped as a bare rectangle. The only spatial thing an agent could say was
 * [BehaviourSteering.avoidEdges], which pushes away from the four sides of the arena; everything
 * else a scripted agent could steer around was another agent. That rules out the whole category of
 * behaviour that is *about* the environment — a corridor, a room with a door, a barrier you have to
 * go round rather than through — and it rules it out silently, because an agent pressing into a
 * wall that does not exist just flies on.
 *
 * ### Three representations
 *
 * **Obstacles are a flat soup of line segments** ([ScriptObstacle], [ObstacleField]) and not
 * polygons-as-solids. The only predicate collision needs is "is this swept circle crossing a wall",
 * which a segment answers directly; making obstacles *solid* would mean a point-in-polygon test, a
 * winding rule, and an answer for what two overlapping solids mean. A soup accepts an open wall, a
 * concave room, a fence with a gap in it and two shapes that cross, all without a special case. The
 * price is stated rather than hidden: there is no "inside", so nothing stops an agent that *starts*
 * inside a closed shape from staying there — [ScriptWorld.spawn] pushes it out to the nearest
 * surface instead of teleporting it somewhere arbitrary.
 *
 * **Collision is swept**, not discrete ([ObstacleField.sweep]). A discrete test asks whether the
 * agent's new position overlaps a wall, which is false for any agent whose per-step displacement is
 * larger than the wall is thick — and a wall here is a segment, so it is *infinitely* thin and a
 * discrete test can miss it at any speed at all. The swept test solves for the first fraction of the
 * step at which the moving circle touches, so a projectile at 600 units/s stops at the wall it would
 * otherwise have crossed in one tick. `AlifeWallsGallery`'s tunnelling figure runs both.
 *
 * **Navigation is a uniform occupancy grid inflated by the agent's radius, searched with A\*, then
 * string-pulled against the exact segment test** ([NavGrid]). The obvious alternative is a
 * visibility graph, which is exact and gives shorter paths from a much smaller search. It was
 * rejected on *construction*, not on search: a visibility graph over a segment soup needs its
 * corners offset outward by the agent radius, and "the offset corner" is not defined where two
 * segments cross, nearly touch, or are collinear — which is precisely the geometry that appears
 * when a user draws a wall twice or butts two rectangles together. A grid has no corners to reason
 * about: it is built by one distance query per cell per segment, radius-awareness is a single
 * inflation number, and the one thing it gets wrong — paths that staircase along cell centres — is
 * removed afterwards by [NavGrid.stringPull], which uses the same exact swept test the physics does.
 * A coarse grid can still *over*-block a narrow gap; [NavGrid.coarse] says when that happened rather
 * than leaving the caller to wonder why there was no route.
 *
 * ### Determinism
 *
 * The same property the rest of `alife_script.kt` depends on, and the same discipline. Segments
 * are held in insertion order in flat [DoubleArray]s. A*'s open set is ordered by `(f, cellIndex)`,
 * a **total** order, so two cells with equal estimated cost expand in a fixed order rather than in
 * whichever order a heap happened to leave them. Neighbours are visited through one fixed
 * eight-element offset table. The nav-grid cache is a [LinkedHashMap] keyed by the quantised
 * inflation radius. The per-step route budget is consumed in agent-id order, because drives are
 * evaluated in agent-id order. Nothing here reads a clock or a hash set.
 */

// ==============================================================================================
// Obstacles
// ==============================================================================================

/**
 * What an agent does when its swept path meets a wall.
 *
 * @property SLIDE keep the component of the motion along the wall and drop the component into it —
 *   the default, and the only one of the three that lets an agent get anywhere while touching
 *   something. An agent that slides along a barrier and round its end has not been told to do that
 *   by any steering verb; it is what "keep going, minus the impossible part" looks like.
 * @property BOUNCE reflect, scaling the normal component by [ScriptActor.restitution]. For anything
 *   thrown.
 * @property STOP stop dead at the contact. For anything that should be *caught* by a wall rather
 *   than deflected by it.
 */
enum class WallResponse { SLIDE, BOUNCE, STOP }

/**
 * A wall: a run of points, open (a polyline) or closed (a polygon outline).
 *
 * Not solid — see the file KDoc. A closed obstacle is exactly its outline, so "inside" is a region
 * an agent cannot *enter* rather than a region it cannot *be*.
 *
 * Build these with [ScriptWorld.addWall], [ScriptWorld.addRect], [ScriptWorld.addPolygon],
 * [ScriptWorld.addPolyline] and [ScriptWorld.addDisc] rather than directly; the world has to see
 * them to index them.
 *
 * @property name shows up in [ScriptWorld.obstacles] and in a figure's legend; not required to be
 *   unique, because two walls called "barrier" is a reasonable thing for a script to build in a loop
 * @property points `x0, y0, x1, y1, …`, copied on the way in
 * @property closed whether a final segment joins the last point back to the first
 */
class ScriptObstacle(val name: String, points: DoubleArray, val closed: Boolean) {
    /** `x0, y0, x1, y1, …`. A defensive copy; mutating what you passed in cannot move a wall. */
    val points: DoubleArray = points.copyOf()

    init {
        require(points.size >= 4 && points.size % 2 == 0) {
            "an obstacle needs at least two points as x,y pairs, got ${points.size} doubles"
        }
        for (v in points) require(v.isFinite()) { "obstacle '$name' has a non-finite coordinate" }
    }

    /** How many points the outline has. */
    val vertexCount: Int get() = points.size / 2

    /** How many segments it contributes: one fewer than the vertices, or the same if [closed]. */
    val segmentCount: Int get() = if (closed) vertexCount else vertexCount - 1

    /** The outline as an [FLine], for drawing the world the agents are actually in. */
    fun outline(z: Number = 0): FLine = FLine().also { l ->
        l.moveTo(points[0], points[1], z)
        for (i in 1 until vertexCount) l.lineTo(points[i * 2], points[i * 2 + 1], z)
        if (closed) l.lineTo(points[0], points[1], z)
    }

    override fun toString(): String =
        "ScriptObstacle('$name', $vertexCount points, ${if (closed) "closed" else "open"})"
}

/**
 * One swept contact: how far along the displacement it happened, and which way the wall faces there.
 *
 * @property t the fraction of the requested displacement travelled before touching, in `0..1`. 0
 *   means the agent was already in contact at the start of the step.
 * @property nx x of the unit surface normal, pointing back toward the side the agent came from
 * @property ny y of the same normal
 * @property obstacle which wall
 * @property segment which of that wall's segments, counting from 0
 */
class WallHit internal constructor(
    val t: Double,
    val nx: Double,
    val ny: Double,
    val obstacle: ScriptObstacle,
    val segment: Int
) {
    override fun toString(): String =
        "WallHit(t=%.4f n=(%.3f, %.3f) '%s'#%d)".format(t, nx, ny, obstacle.name, segment)
}

/**
 * Every wall in a world, indexed as one flat array of segments — the thing the physics and the
 * navigation both ask.
 *
 * Flat `DoubleArray`s rather than a list of objects, and rebuilt lazily rather than appended to,
 * because [sweep] is called once per agent per step (more, with a slide) and [NavGrid] calls the
 * distance query once per cell per segment. Insertion order is preserved, which is what makes
 * "the earliest hit, ties to the first-added wall" a reproducible sentence.
 */
class ObstacleField {
    private val walls = ArrayList<ScriptObstacle>()
    private var packed = DoubleArray(0)
    private var owner = IntArray(0)
    private var localIndex = IntArray(0)
    private var dirty = true

    /** The walls, in the order they were added. */
    val obstacles: List<ScriptObstacle> get() = walls

    /** How many segments all the walls come to. */
    val segmentCount: Int get() { rebuild(); return owner.size }

    /** True when there is nothing to collide with — the fast path the integrator takes. */
    val isEmpty: Boolean get() = walls.isEmpty()

    /** Add [o]. Invalidates the packed index and any [NavGrid] built from this field. */
    fun add(o: ScriptObstacle) {
        walls.add(o)
        dirty = true
    }

    /**
     * The packed segments, `ax, ay, bx, by` per segment, in insertion order.
     *
     * Handed out live rather than copied, because the callers are per-step loops. Read it; writing
     * to it moves walls without telling the navigation cache, which is the one way to make this
     * class lie.
     */
    fun segments(): DoubleArray { rebuild(); return packed }

    /** The obstacle that owns segment [i] of [segments]. */
    fun ownerOf(i: Int): ScriptObstacle { rebuild(); return walls[owner[i]] }

    private fun rebuild() {
        if (!dirty) return
        var n = 0
        for (w in walls) n += w.segmentCount
        val p = DoubleArray(n * 4)
        val ow = IntArray(n)
        val li = IntArray(n)
        var k = 0
        for ((wi, w) in walls.withIndex()) {
            val v = w.vertexCount
            for (s in 0 until w.segmentCount) {
                val a = s
                val b = (s + 1) % v
                p[k * 4] = w.points[a * 2]; p[k * 4 + 1] = w.points[a * 2 + 1]
                p[k * 4 + 2] = w.points[b * 2]; p[k * 4 + 3] = w.points[b * 2 + 1]
                ow[k] = wi; li[k] = s
                k++
            }
        }
        packed = p; owner = ow; localIndex = li
        dirty = false
    }

    /**
     * The first wall a circle of [radius] at ([px], [py]) touches while moving by ([dx], [dy]), or
     * `null` if it touches none.
     *
     * Solved, not stepped. For each segment the earliest `t` is the smaller of two roots: the one
     * where the circle centre reaches the offset line `±radius` from the segment's own line (and
     * lands within the segment's extent), and the ones where it reaches an endpoint's `radius`
     * circle. That is the whole of moving-circle-versus-capsule, and it is exact — there is no
     * substep size to be too coarse.
     *
     * A circle that is *already* overlapping a wall at `t = 0` is not reported here: the roots it
     * would produce are behind the start of the step, and a hit at a negative `t` is a depenetration
     * problem rather than a collision. [pushOut] is that.
     *
     * Ties — two walls hit at the identical `t` — go to the earlier-added segment.
     */
    fun sweep(px: Double, py: Double, dx: Double, dy: Double, radius: Double): WallHit? {
        rebuild()
        if (owner.isEmpty()) return null
        if (dx == 0.0 && dy == 0.0) return null
        var bestT = Double.MAX_VALUE
        var bestNx = 0.0
        var bestNy = 0.0
        var bestSeg = -1
        val scratch = DoubleArray(3)
        for (i in owner.indices) {
            val ax = packed[i * 4]; val ay = packed[i * 4 + 1]
            val bx = packed[i * 4 + 2]; val by = packed[i * 4 + 3]
            if (sweepSegment(px, py, dx, dy, radius, ax, ay, bx, by, scratch)) {
                if (scratch[0] < bestT) {
                    bestT = scratch[0]; bestNx = scratch[1]; bestNy = scratch[2]; bestSeg = i
                }
            }
        }
        if (bestSeg < 0) return null
        return WallHit(bestT, bestNx, bestNy, walls[owner[bestSeg]], localIndex[bestSeg])
    }

    /**
     * The nearest wall point to ([px], [py]) within [range], written into [out] as
     * `distance, wallX, wallY, normalX, normalY` — the normal pointing from the wall toward the
     * query. Returns false and leaves [out] alone when nothing is in range.
     *
     * Writes into a caller-owned array rather than returning an object because the two steering
     * verbs that use it ([BehaviourSteering.cling], [BehaviourSteering.avoidWalls]) call it every
     * step for every agent.
     */
    fun nearestInto(px: Double, py: Double, range: Double, out: DoubleArray): Boolean {
        require(out.size >= 5) { "nearestInto needs an out array of at least 5, got ${out.size}" }
        rebuild()
        var bestD = range
        var found = false
        val scratch = DoubleArray(2)
        for (i in owner.indices) {
            closestPointOnSegment(
                px, py,
                packed[i * 4], packed[i * 4 + 1], packed[i * 4 + 2], packed[i * 4 + 3],
                scratch
            )
            val d = hypot(px - scratch[0], py - scratch[1])
            if (d <= bestD) {
                // <= rather than <, plus insertion order, so an exact tie takes the LATER segment
                // consistently rather than whichever the loop saw first at an equal distance.
                bestD = d; found = true
                out[0] = d; out[1] = scratch[0]; out[2] = scratch[1]
                if (d > 1e-12) { out[3] = (px - scratch[0]) / d; out[4] = (py - scratch[1]) / d }
                else { out[3] = 0.0; out[4] = 0.0 }
            }
        }
        return found
    }

    /** The distance from ([px], [py]) to the nearest wall, or [Double.MAX_VALUE] if there are none. */
    fun distanceTo(px: Double, py: Double): Double {
        rebuild()
        var best = Double.MAX_VALUE
        val scratch = DoubleArray(2)
        for (i in owner.indices) {
            closestPointOnSegment(
                px, py,
                packed[i * 4], packed[i * 4 + 1], packed[i * 4 + 2], packed[i * 4 + 3],
                scratch
            )
            val d = hypot(px - scratch[0], py - scratch[1])
            if (d < best) best = d
        }
        return best
    }

    /**
     * Whether a circle of [radius] could stand anywhere along the straight line from ([x0], [y0])
     * to ([x1], [y1]) — the test [NavGrid.stringPull] shortens a grid path with, and the one
     * [BehaviourSteering.route] decides it has passed a waypoint on.
     *
     * The swept capsule against every wall, computed as a **segment-to-segment distance** and
     * deliberately *not* as `sweep(…) == null`. The two agree everywhere except one case, and that
     * case is the one that matters here: [sweep] reports nothing for a circle that is already
     * overlapping a wall at the start of its move, because a contact at a negative time is a
     * depenetration problem rather than a collision. Asking `sweep` for clearance from a position
     * that is already too close therefore gets the answer "clear" about the very wall you are
     * pressed against.
     *
     * That cost a whole evening once. An agent that clipped a corner and ended up against a wall
     * replanned, and the string-pull — starting from the agent's real position, one unit from the
     * wall — was told the shortcut straight through that wall was clear. It took it, and the agent
     * spent the rest of the run grinding along the face of a wall its own route went through.
     * A distance test has no such hole: the distance from a segment to a wall it starts on is zero,
     * whichever end you measure from.
     */
    fun clearPath(x0: Double, y0: Double, x1: Double, y1: Double, radius: Double): Boolean {
        rebuild()
        for (i in owner.indices) {
            val d = segmentDistance(
                x0, y0, x1, y1,
                packed[i * 4], packed[i * 4 + 1], packed[i * 4 + 2], packed[i * 4 + 3]
            )
            if (d < radius - 1e-9) return false
        }
        return true
    }

    /**
     * Whether the open segment from ([x0], [y0]) to ([x1], [y1]) crosses any wall — line of sight
     * for a point, with no radius.
     *
     * A separate routine from [clearPath] rather than `clearPath(radius = 0)` because the swept test
     * divides by the side the circle centre starts on, and with a zero radius an eye exactly on the
     * plane of a wall has no side. This is the straight segment-segment crossing test, which does
     * not care.
     */
    fun lineOfSight(x0: Double, y0: Double, x1: Double, y1: Double): Boolean {
        rebuild()
        for (i in owner.indices) {
            if (segmentsCross(
                    x0, y0, x1, y1,
                    packed[i * 4], packed[i * 4 + 1], packed[i * 4 + 2], packed[i * 4 + 3]
                )
            ) return false
        }
        return true
    }

    /**
     * Move ([px], [py]) to the nearest position where a circle of [radius] is not overlapping a
     * wall, writing `x, y` into [out]. Returns true if it had to move.
     *
     * Up to [PUSH_OUT_PASSES] passes, because pushing out of one wall can push into another (an
     * agent in the corner of a room). It converges for any convex corner and gives up quietly in a
     * crack narrower than the agent, which is the honest outcome: there is no non-overlapping
     * position to find.
     */
    fun pushOut(px: Double, py: Double, radius: Double, out: DoubleArray): Boolean {
        require(out.size >= 2) { "pushOut needs an out array of at least 2, got ${out.size}" }
        rebuild()
        var x = px
        var y = py
        var moved = false
        val near = DoubleArray(5)
        var pass = 0
        while (pass < PUSH_OUT_PASSES) {
            if (!nearestInto(x, y, radius, near)) break
            val d = near[0]
            var nx = near[3]
            var ny = near[4]
            if (d <= 1e-12) {
                // Dead on the wall: no normal to read off the offset, so take the segment's own.
                nx = 0.0; ny = 1.0
            }
            x = near[1] + nx * (radius + WALL_SKIN)
            y = near[2] + ny * (radius + WALL_SKIN)
            moved = true
            pass++
        }
        out[0] = x; out[1] = y
        return moved
    }

    override fun toString(): String = "ObstacleField(${walls.size} walls, $segmentCount segments)"
}

/**
 * How far outside a wall a contact is resolved to.
 *
 * Resting exactly on the surface makes the next step's sweep start at `t = 0` against the wall it is
 * already touching, which either stalls the agent or costs it every response iteration it has. A skin
 * this size is invisible at any drawing scale and is three orders of magnitude above the rounding of
 * a coordinate in a 200-unit arena.
 */
const val WALL_SKIN: Double = 1e-6

/** How many times [ObstacleField.pushOut] will try to find non-overlapping ground. */
const val PUSH_OUT_PASSES: Int = 4

/**
 * How much closer to its waypoint [BehaviourSteering.route] has to get to count as having moved.
 *
 * An agent at any usable speed closes tenths of a unit per step, so a thousandth is far below real
 * progress and far above the rounding of a coordinate. Its only job is to stop an agent that is
 * grinding on a wall from banking a millionth of a unit a step and never being called stalled.
 */
const val PROGRESS_EPSILON: Double = 1e-3

/**
 * How many wall responses one integration step may resolve.
 *
 * Four is enough to slide along one wall, into a corner, along the second, and out; past that the
 * remaining displacement is dropped rather than applied, because an agent wedged in a crack should
 * stop, not squeeze through on the iteration the loop ran out of.
 */
const val WALL_RESPONSE_ITERATIONS: Int = 4

/**
 * The closest point of the segment `a`–`b` to ([px], [py]), written into [out] as `x, y`.
 *
 * A degenerate segment (both endpoints the same) answers with that point, which is the right answer
 * and not an error — a "wall" of zero length is a post, and an agent can still be next to it.
 */
fun closestPointOnSegment(
    px: Double, py: Double,
    ax: Double, ay: Double, bx: Double, by: Double,
    out: DoubleArray
) {
    val ex = bx - ax
    val ey = by - ay
    val l2 = ex * ex + ey * ey
    if (l2 < 1e-18) { out[0] = ax; out[1] = ay; return }
    var t = ((px - ax) * ex + (py - ay) * ey) / l2
    if (t < 0.0) t = 0.0
    if (t > 1.0) t = 1.0
    out[0] = ax + ex * t
    out[1] = ay + ey * t
}

/**
 * The shortest distance between the two closed segments `p`–`q` and `r`–`s`. Zero if they cross.
 *
 * Four point-to-segment queries and a crossing test, which is the whole of it: for two segments
 * that do not cross, the closest pair always has at least one endpoint in it, so the minimum over
 * the four "endpoint of one against the other" distances is exact rather than approximate.
 */
fun segmentDistance(
    px: Double, py: Double, qx: Double, qy: Double,
    rx: Double, ry: Double, sx: Double, sy: Double
): Double {
    if (segmentsCross(px, py, qx, qy, rx, ry, sx, sy)) return 0.0
    val o = DoubleArray(2)
    var best = Double.MAX_VALUE
    closestPointOnSegment(px, py, rx, ry, sx, sy, o); best = min(best, hypot(px - o[0], py - o[1]))
    closestPointOnSegment(qx, qy, rx, ry, sx, sy, o); best = min(best, hypot(qx - o[0], qy - o[1]))
    closestPointOnSegment(rx, ry, px, py, qx, qy, o); best = min(best, hypot(rx - o[0], ry - o[1]))
    closestPointOnSegment(sx, sy, px, py, qx, qy, o); best = min(best, hypot(sx - o[0], sy - o[1]))
    return best
}

/** Whether the two closed segments `p`–`q` and `r`–`s` cross, including touching at a point. */
fun segmentsCross(
    px: Double, py: Double, qx: Double, qy: Double,
    rx: Double, ry: Double, sx: Double, sy: Double
): Boolean {
    fun orient(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Double =
        (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

    fun onSpan(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Boolean =
        cx >= min(ax, bx) - 1e-12 && cx <= max(ax, bx) + 1e-12 &&
            cy >= min(ay, by) - 1e-12 && cy <= max(ay, by) + 1e-12

    val d1 = orient(px, py, qx, qy, rx, ry)
    val d2 = orient(px, py, qx, qy, sx, sy)
    val d3 = orient(rx, ry, sx, sy, px, py)
    val d4 = orient(rx, ry, sx, sy, qx, qy)
    if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return true
    if (abs(d1) < 1e-12 && onSpan(px, py, qx, qy, rx, ry)) return true
    if (abs(d2) < 1e-12 && onSpan(px, py, qx, qy, sx, sy)) return true
    if (abs(d3) < 1e-12 && onSpan(rx, ry, sx, sy, px, py)) return true
    if (abs(d4) < 1e-12 && onSpan(rx, ry, sx, sy, qx, qy)) return true
    return false
}

/**
 * The earliest touch of a circle of [r] centred at ([px], [py]) moving by ([dx], [dy]) against the
 * one segment `a`–`b`, written into [out] as `t, normalX, normalY`. Returns false when it misses.
 *
 * Exposed because it is the primitive the whole file rests on and a test that wants to pin a
 * time-of-impact by arithmetic should be able to call it without building a world.
 */
fun sweepSegment(
    px: Double, py: Double, dx: Double, dy: Double, r: Double,
    ax: Double, ay: Double, bx: Double, by: Double,
    out: DoubleArray
): Boolean {
    require(out.size >= 3) { "sweepSegment needs an out array of at least 3, got ${out.size}" }
    var bestT = Double.MAX_VALUE
    var bestNx = 0.0
    var bestNy = 0.0
    var found = false

    val ex = bx - ax
    val ey = by - ay
    val l2 = ex * ex + ey * ey

    if (l2 > 1e-18) {
        val l = sqrt(l2)
        val ux = ex / l
        val uy = ey / l
        // Unit normal to the segment; which of the two is "outward" depends on which side we start.
        val gx = -uy
        val gy = ux
        val s0 = (px - ax) * gx + (py - ay) * gy
        val sd = dx * gx + dy * gy
        if (abs(s0) >= r && abs(sd) > 1e-18) {
            val side = if (s0 >= 0.0) 1.0 else -1.0
            val t = (side * r - s0) / sd
            if (t >= 0.0 && t <= 1.0) {
                val cx = px + dx * t
                val cy = py + dy * t
                val proj = (cx - ax) * ux + (cy - ay) * uy
                if (proj >= 0.0 && proj <= l) {
                    bestT = t; bestNx = side * gx; bestNy = side * gy; found = true
                }
            }
        }
    }

    // The two endpoint caps. A circle rounding the end of a wall meets a circle, not a line.
    val a2 = dx * dx + dy * dy
    if (a2 > 1e-18) {
        var e = 0
        while (e < 2) {
            val cxE = if (e == 0) ax else bx
            val cyE = if (e == 0) ay else by
            val fx = px - cxE
            val fy = py - cyE
            val c = fx * fx + fy * fy - r * r
            if (c >= 0.0) {
                val bq = 2.0 * (fx * dx + fy * dy)
                val disc = bq * bq - 4.0 * a2 * c
                if (disc >= 0.0) {
                    val t = (-bq - sqrt(disc)) / (2.0 * a2)
                    if (t >= 0.0 && t <= 1.0 && t < bestT) {
                        val hx = px + dx * t - cxE
                        val hy = py + dy * t - cyE
                        val m = hypot(hx, hy)
                        if (m > 1e-12) {
                            bestT = t; bestNx = hx / m; bestNy = hy / m; found = true
                        }
                    }
                }
            }
            e++
        }
    }

    if (!found) return false
    out[0] = bestT; out[1] = bestNx; out[2] = bestNy
    return true
}

// ==============================================================================================
// Navigation
// ==============================================================================================

/**
 * A radius-aware occupancy grid over a world's walls, and the A\* that searches it.
 *
 * Built once per (world geometry, inflation radius) pair and cached by [ScriptWorld]; see the file
 * KDoc for why a grid rather than a visibility graph. A cell is blocked when its centre is within
 * [inflate] of any wall segment or of the arena boundary, so a route through free cells is a route a
 * circle of [inflate] can follow — *given* that the cells are small enough that a wall cannot slip
 * between two centres, which [cellSize] is chosen to guarantee.
 *
 * @property inflate the agent radius plus whatever clearance the route asked for
 * @property coarse true when [cellSize] had to be enlarged to stay under the cell cap, which means
 *   the grid may have closed a gap the agent would in fact have fitted through. A route that fails
 *   on a coarse grid is worth retrying with a smaller world or a bigger cap; a route that fails on a
 *   fine one really is blocked.
 */
class NavGrid internal constructor(
    val width: Double,
    val height: Double,
    val cols: Int,
    val rows: Int,
    val cellSize: Double,
    val inflate: Double,
    val coarse: Boolean,
    private val blocked: BooleanArray
) {
    /** How many of the grid's cells a circle of [inflate] cannot stand in. */
    val blockedCells: Int get() = blocked.count { it }

    /** Total cells. */
    val cellCount: Int get() = blocked.size

    /** Whether the cell at column [c], row [r] is unusable (out of range counts as blocked). */
    fun isBlocked(c: Int, r: Int): Boolean =
        c < 0 || r < 0 || c >= cols || r >= rows || blocked[r * cols + c]

    /** The column containing world x [x], clamped into the grid. */
    fun colOf(x: Double): Int = ((x / cellSize).toInt()).coerceIn(0, cols - 1)

    /** The row containing world y [y], clamped into the grid. */
    fun rowOf(y: Double): Int = ((y / cellSize).toInt()).coerceIn(0, rows - 1)

    /** The world x of the centre of column [c]. */
    fun centreX(c: Int): Double = (c + 0.5) * cellSize

    /** The world y of the centre of row [r]. */
    fun centreY(r: Int): Double = (r + 0.5) * cellSize

    /**
     * The nearest free cell to ([x], [y]) within [searchCells] rings, as a packed `row * cols + col`,
     * or -1.
     *
     * Needed at both ends of a search. A start point can legitimately be inside a blocked cell — an
     * agent standing against a wall is within `inflate` of it by definition — and a goal can be a
     * doorway the caller nominated by eye. Refusing to route in either case would make routing
     * useless exactly where it is wanted. Rings are scanned outward and, within a ring, in a fixed
     * order, so "the nearest free cell" is one cell and not a coin flip.
     */
    fun nearestFree(x: Double, y: Double, searchCells: Int = 24): Int {
        val c0 = colOf(x)
        val r0 = rowOf(y)
        if (!isBlocked(c0, r0)) return r0 * cols + c0
        var ring = 1
        while (ring <= searchCells) {
            var best = -1
            var bestD = Double.MAX_VALUE
            var dr = -ring
            while (dr <= ring) {
                var dc = -ring
                while (dc <= ring) {
                    if (abs(dr) == ring || abs(dc) == ring) {
                        val c = c0 + dc
                        val r = r0 + dr
                        if (!isBlocked(c, r)) {
                            val d = hypot(centreX(c) - x, centreY(r) - y)
                            if (d < bestD) { bestD = d; best = r * cols + c }
                        }
                    }
                    dc++
                }
                dr++
            }
            if (best >= 0) return best
            ring++
        }
        return -1
    }

    /**
     * A\* from ([sx], [sy]) to ([gx], [gy]), returning the cell indices of the path including both
     * ends, or `null` if there is no route inside [maxExpansions].
     *
     * Eight-connected with octile costs and an octile heuristic, which is admissible on this graph,
     * so the path is a shortest one *on the grid*. Diagonal moves are refused when either of the two
     * cells they cut between is blocked: without that the path clips wall corners, and it clips them
     * by up to the cell diagonal, which is more than the string-pull can recover because the corner
     * really is in the way.
     *
     * The open set is ordered by `(f, cellIndex)` — a total order over distinct cells, so ties do not
     * depend on how the heap rebalanced.
     */
    fun route(sx: Double, sy: Double, gx: Double, gy: Double, maxExpansions: Int = 20_000): IntArray? {
        val start = nearestFree(sx, sy)
        val goal = nearestFree(gx, gy)
        if (start < 0 || goal < 0) return null
        if (start == goal) return intArrayOf(start)

        val n = blocked.size
        val g = DoubleArray(n) { Double.MAX_VALUE }
        val from = IntArray(n) { -1 }
        val closed = BooleanArray(n)
        val gc = goal % cols
        val gr = goal / cols

        fun h(index: Int): Double {
            val dc = abs(index % cols - gc).toDouble()
            val dr = abs(index / cols - gr).toDouble()
            return (dc + dr) + (SQRT2 - 2.0) * min(dc, dr)
        }

        val open = java.util.PriorityQueue<DoubleArray>(
            compareBy<DoubleArray>({ it[0] }, { it[1] })
        )
        g[start] = 0.0
        open.add(doubleArrayOf(h(start), start.toDouble()))

        var expansions = 0
        while (open.isNotEmpty()) {
            val head = open.poll()
            val cur = head[1].toInt()
            if (closed[cur]) continue
            closed[cur] = true
            if (cur == goal) break
            if (++expansions > maxExpansions) return null
            val cc = cur % cols
            val cr = cur / cols
            var k = 0
            while (k < 8) {
                val dc = NEIGHBOUR_DC[k]
                val dr = NEIGHBOUR_DR[k]
                k++
                val nc = cc + dc
                val nr = cr + dr
                if (isBlocked(nc, nr)) continue
                if (dc != 0 && dr != 0) {
                    // No corner cutting: both orthogonal companions must be free.
                    if (isBlocked(cc + dc, cr) || isBlocked(cc, cr + dr)) continue
                }
                val ni = nr * cols + nc
                if (closed[ni]) continue
                val stepCost = if (dc != 0 && dr != 0) SQRT2 else 1.0
                val tentative = g[cur] + stepCost
                if (tentative < g[ni]) {
                    g[ni] = tentative
                    from[ni] = cur
                    open.add(doubleArrayOf(tentative + h(ni), ni.toDouble()))
                }
            }
        }

        if (!closed[goal]) return null
        var count = 1
        var walk = goal
        while (walk != start) { walk = from[walk]; count++ }
        val out = IntArray(count)
        walk = goal
        var i = count - 1
        while (i >= 0) { out[i] = walk; walk = from[walk]; i-- }
        return out
    }

    /**
     * Turn a cell path into a short list of world waypoints, dropping every one a circle of
     * [inflate] could have skipped.
     *
     * This is where the grid's staircase goes. The test is [ObstacleField.clearPath] against the
     * real wall geometry, not a grid ray, so a smoothed leg is passable by a criterion the grid
     * itself is only an approximation of. Greedy from the front, which is `O(waypoints²)` in the
     * worst case and a few dozen tests in practice; the alternative (funnel) is only worth its
     * complexity on a navigation mesh, where the channel is a sequence of portals rather than a
     * sequence of squares.
     *
     * [startX]/[startY] and [goalX]/[goalY] replace the first and last cell centres, so a route ends
     * at the place that was asked for rather than at the middle of the cell containing it. The
     * start may legitimately be closer to a wall than [inflate] — an agent replanning from against
     * a wall is the ordinary case — and when it is, every shortcut from it fails and the first leg
     * is the short hop to the first free cell centre. That is the right answer and it is why
     * [ObstacleField.clearPath] has to be honest about a start that is already too close.
     */
    fun stringPull(
        path: IntArray,
        field: ObstacleField,
        startX: Double, startY: Double,
        goalX: Double, goalY: Double
    ): DoubleArray {
        val n = path.size
        val xs = DoubleArray(n)
        val ys = DoubleArray(n)
        for (i in 0 until n) {
            xs[i] = centreX(path[i] % cols)
            ys[i] = centreY(path[i] / cols)
        }
        xs[0] = startX; ys[0] = startY
        xs[n - 1] = goalX; ys[n - 1] = goalY

        val out = ArrayList<Double>()
        out.add(xs[0]); out.add(ys[0])
        var i = 0
        while (i < n - 1) {
            var j = n - 1
            while (j > i + 1) {
                if (field.clearPath(xs[i], ys[i], xs[j], ys[j], inflate)) break
                j--
            }
            out.add(xs[j]); out.add(ys[j])
            i = j
        }
        val r = DoubleArray(out.size)
        for (k in out.indices) r[k] = out[k]
        return r
    }

    /** The blocked cells as one [FLine] per cell outline — for looking at what the router saw. */
    fun blockedFLines(z: Number = 0): List<FLine> {
        val out = ArrayList<FLine>()
        for (r in 0 until rows) for (c in 0 until cols) {
            if (!blocked[r * cols + c]) continue
            val x = c * cellSize
            val y = r * cellSize
            out.add(
                FLine().also {
                    it.moveTo(x, y, z); it.lineTo(x + cellSize, y, z)
                    it.lineTo(x + cellSize, y + cellSize, z); it.lineTo(x, y + cellSize, z)
                    it.lineTo(x, y, z)
                }
            )
        }
        return out
    }

    override fun toString(): String =
        "NavGrid(${cols}x$rows @ %.2f, inflate %.2f, %d blocked%s)"
            .format(cellSize, inflate, blockedCells, if (coarse) ", COARSE" else "")

    companion object {
        /**
         * Build a grid over a [width] x [height] arena, blocking everything within [inflate] of a
         * wall or of the boundary.
         *
         * [cellSize] is `inflate * 1.2`, which is below `inflate * sqrt(2)` and therefore below the
         * point at which a segment could pass through a cell without coming within [inflate] of its
         * centre. That is the property that makes "free cells are standable" true rather than
         * probable. The size is enlarged — and [coarse] set — only when the cap [maxCells] would
         * otherwise be exceeded.
         */
        fun build(
            width: Double,
            height: Double,
            field: ObstacleField,
            inflate: Double,
            maxCells: Int = 40_000
        ): NavGrid {
            require(width > 0.0 && height > 0.0) { "nav grid needs a positive arena" }
            require(inflate > 0.0 && inflate.isFinite()) { "nav inflate must be > 0, got $inflate" }
            require(maxCells >= 64) { "nav maxCells must be >= 64, got $maxCells" }
            var cs = inflate * 1.2
            var cols = max(1, ceil(width / cs).toInt())
            var rows = max(1, ceil(height / cs).toInt())
            var coarse = false
            while (cols.toLong() * rows.toLong() > maxCells) {
                cs *= 1.25
                cols = max(1, ceil(width / cs).toInt())
                rows = max(1, ceil(height / cs).toInt())
                coarse = true
            }
            val blocked = BooleanArray(cols * rows)
            val segs = field.segments()
            val scratch = DoubleArray(2)
            for (r in 0 until rows) {
                val cy = (r + 0.5) * cs
                for (c in 0 until cols) {
                    val cx = (c + 0.5) * cs
                    if (cx < inflate || cy < inflate || cx > width - inflate || cy > height - inflate) {
                        blocked[r * cols + c] = true
                        continue
                    }
                    var hit = false
                    var i = 0
                    while (i < segs.size) {
                        closestPointOnSegment(cx, cy, segs[i], segs[i + 1], segs[i + 2], segs[i + 3], scratch)
                        if (hypot(cx - scratch[0], cy - scratch[1]) <= inflate) { hit = true; break }
                        i += 4
                    }
                    blocked[r * cols + c] = hit
                }
            }
            return NavGrid(width, height, cols, rows, cs, inflate, coarse, blocked)
        }
    }
}

private val SQRT2 = sqrt(2.0)

/** Fixed neighbour order: E, NE, N, NW, W, SW, S, SE. Fixed, so expansion order is fixed. */
private val NEIGHBOUR_DC = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
private val NEIGHBOUR_DR = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)

// ==============================================================================================
// Scalar environment fields
// ==============================================================================================

/**
 * A scalar quantity spread over the arena — light, a chemical, temperature — that an agent can
 * sense and climb with [BehaviourSteering.gradient].
 *
 * A function rather than a raster, because most of the interesting ones are analytic (a point
 * source falls off as `1/(1+d²)`) and a raster of a function is a resolution to argue about. Wrap a
 * raster in one if you have a raster. Must be finite everywhere an agent can stand; a field that
 * returns a NaN takes its drive out for that agent and logs, like any other bad drive.
 */
fun interface ScalarField {
    /** The value at ([x], [y]). */
    fun at(x: Double, y: Double): Double
}

/**
 * A field that is the sum of inverse-square-ish point sources — the standard "some lamps in a room".
 *
 * `strength / (1 + (d / falloff)²)`, which is bounded at the source instead of infinite, so an agent
 * that reaches a lamp gets a finite reading and a finite gradient rather than a division by zero one
 * step before it arrives.
 *
 * @param sources `x, y, strength` triples
 */
fun pointSourceField(vararg sources: Double, falloff: Double = 20.0): ScalarField {
    require(sources.size % 3 == 0 && sources.isNotEmpty()) {
        "pointSourceField takes x, y, strength triples, got ${sources.size} doubles"
    }
    require(falloff > 0.0 && falloff.isFinite()) { "falloff must be > 0, got $falloff" }
    val s = sources.copyOf()
    return ScalarField { x, y ->
        var total = 0.0
        var i = 0
        while (i < s.size) {
            val dx = x - s[i]
            val dy = y - s[i + 1]
            val q = (dx * dx + dy * dy) / (falloff * falloff)
            total += s[i + 2] / (1.0 + q)
            i += 3
        }
        total
    }
}

/** A disc of [segments] points, centred on ([cx], [cy]) — the polygon a "round pillar" wants. */
internal fun discPoints(cx: Double, cy: Double, radius: Double, segments: Int): DoubleArray {
    require(segments >= 3) { "a disc needs at least 3 segments, got $segments" }
    require(radius > 0.0 && radius.isFinite()) { "disc radius must be > 0, got $radius" }
    val p = DoubleArray(segments * 2)
    for (i in 0 until segments) {
        val a = 2.0 * Math.PI * i / segments
        p[i * 2] = cx + cos(a) * radius
        p[i * 2 + 1] = cy + sin(a) * radius
    }
    return p
}
