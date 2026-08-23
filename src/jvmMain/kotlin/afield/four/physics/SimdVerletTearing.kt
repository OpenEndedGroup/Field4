package afield.four.physics

import kotlin.math.max
import kotlin.math.sqrt

/**
 * Constraint tearing: retiring a distance constraint that has been stretched past a strain
 * threshold, so a sheet under load rips instead of stretching forever.
 *
 * Registered as a [SimdVerlet.constraintEdits] pass — normally by [SimdVerlet.addTearing] — which
 * runs last in the frame, after integration *and* after the colliders. That ordering is the whole
 * design: an edge stretched over the shoulder of a [SphereCollider] really is that stretched, and
 * a tear model that measured strain before the projection would be deciding on a configuration
 * the frame never ended in.
 *
 * ## Strain
 *
 * `strain = (|x_i - x_j| - rest) / rest`, so [threshold] is a fraction: `0.5f` breaks an edge half
 * again as long as it should be. That is the same quantity the stiffness table in
 * `dev_docs/physics/SIMDVERLET_4_CONSTRAINTS.md` reports as "peak strain", which is what makes it
 * a threshold you can pick from a measurement rather than by bisection — the shipped cloth
 * settles at 11 %, so anything above about 0.2 tears only under real load. [peakStrain] measures
 * it for a whole simulation.
 *
 * ## Symmetry, and why breaking is not just clearing a slot
 *
 * A [Cloth] wires every grid edge twice, `i -> j` and `j -> i`, because the solver is directed and
 * only ever writes to the particle that owns the slot. Retiring one direction would leave a
 * *one-way* spring: `j` still pulled toward `i`, `i` free. That is a physically absurd object —
 * it accelerates its own centre of mass — and it draws as a live edge in
 * [SimdVerlet.debugLines], which deduplicates only genuinely symmetric pairs. So [breakEdge]
 * always retires both directions, and reports one broken edge for the pair.
 *
 * ## Determinism
 *
 * A torn sheet must tear the same way twice, or nothing about it can be tested. With
 * [maxBreaksPerStep] `< 0` (the default, unlimited) every edge over threshold goes in one pass, in
 * ascending `(particle, slot)` order, and order cannot matter because the set is fixed before any
 * of them move. With a limit, the candidates are ranked by strain descending and ties are broken
 * by `(particle, slot)` ascending — never by hash or float-equality luck — so a rate-limited tear
 * is as reproducible as an unlimited one. `SimdVerletTearingTest.tearsTheSameWayTwice` replays a
 * whole scene and compares the surviving constraint set slot for slot.
 *
 * @param constraints the set to edit; this must be the set the simulation actually solves.
 * @param range which owning particles to scan. `null` is the whole state. Note that an edge is
 *   scanned if its *owner* is in range, so a range boundary can leave an edge scanned from one end
 *   only — which is fine, because breaking is symmetric.
 * @param onTear called once per broken edge with the pair `(i, j)`, `i < j`. The hook [Cloth] uses
 *   to drop the curvature stencil at both ends of the tear; see [enableTearing].
 */
class Tearing internal constructor(
    val sim: SimdVerlet,
    val constraints: SimdVerlet.DirectedConstraints4,
    val name: String,
    var threshold: Float,
    var range: IntRange?,
    var maxBreaksPerStep: Int,
    var onTear: ((i: Int, j: Int) -> Unit)?
) {
    /** Edges broken since this object was made. */
    var brokenTotal: Int = 0
        private set

    /** Edges broken in the most recent [scan]. */
    var brokenLastStep: Int = 0
        private set

    /**
     * The largest strain seen in the most recent [scan], broken edges included. `-1f` before the
     * first scan, and after a scan that found no live constraints at all.
     */
    var peakStrainLastStep: Float = -1f
        private set

    // Rate-limited scans rank candidates; kept between frames so a torn cloth isn't allocating
    // three arrays a frame.
    private var candOwner = IntArray(0)
    private var candSlot = IntArray(0)
    private var candStrain = FloatArray(0)

    /** The pass itself, exposed for callers who would rather drive it than leave it registered. */
    fun scan(state: SimdVerlet.State3): Int {
        brokenLastStep = 0
        peakStrainLastStep = -1f
        val r = range
        val start = (r?.first ?: 0).coerceAtLeast(0)
        val end = (if (r == null) state.size else r.last + 1)
            .coerceAtMost(minOf(state.size, constraints.size))
        if (end <= start) return 0

        val limited = maxBreaksPerStep >= 0
        var candidates = 0
        if (limited && maxBreaksPerStep == 0) {
            // Still worth walking: peakStrainLastStep is a readout callers watch while tuning.
            for (i in start until end) {
                for (slot in 0 until 4) {
                    val s = slotStrain(state, i, slot) ?: continue
                    if (s > peakStrainLastStep) peakStrainLastStep = s
                }
            }
            return 0
        }

        for (i in start until end) {
            for (slot in 0 until 4) {
                val s = slotStrain(state, i, slot) ?: continue
                if (s > peakStrainLastStep) peakStrainLastStep = s
                if (s <= threshold) continue
                if (!limited) {
                    if (breakSlot(i, slot)) brokenLastStep++
                } else {
                    if (candidates == candOwner.size) growCandidates()
                    candOwner[candidates] = i
                    candSlot[candidates] = slot
                    candStrain[candidates] = s
                    candidates++
                }
            }
        }

        if (limited && candidates > 0) {
            // Strain descending, then (owner, slot) ascending. An insertion sort: the candidate
            // list is the edges *over threshold* in one frame, which is a handful even in a
            // catastrophic tear, and a stable comparator here is worth more than an O(n log n)
            // that would need boxing to carry three parallel arrays.
            for (a in 1 until candidates) {
                val o = candOwner[a]
                val sl = candSlot[a]
                val st = candStrain[a]
                var b = a - 1
                while (b >= 0 && (candStrain[b] < st || (candStrain[b] == st && (candOwner[b] > o || (candOwner[b] == o && candSlot[b] > sl))))) {
                    candOwner[b + 1] = candOwner[b]
                    candSlot[b + 1] = candSlot[b]
                    candStrain[b + 1] = candStrain[b]
                    b--
                }
                candOwner[b + 1] = o
                candSlot[b + 1] = sl
                candStrain[b + 1] = st
            }
            var k = 0
            while (k < candidates && brokenLastStep < maxBreaksPerStep) {
                if (breakSlot(candOwner[k], candSlot[k])) brokenLastStep++
                k++
            }
        }

        brokenTotal += brokenLastStep
        return brokenLastStep
    }

    /**
     * Breaks the edge between [i] and [j] by hand, in both directions, whether or not it is
     * strained. Returns false if there was no live constraint between them. Fires [onTear].
     */
    fun breakEdge(i: Int, j: Int): Boolean {
        var found = false
        for (slot in 0 until 4) {
            if (neighbor(i, slot) == j && stiffness(i, slot) != 0f) {
                retire(i, slot)
                found = true
            }
            if (neighbor(j, slot) == i && stiffness(j, slot) != 0f) {
                retire(j, slot)
                found = true
            }
        }
        if (found) {
            brokenTotal++
            onTear?.invoke(minOf(i, j), maxOf(i, j))
        }
        return found
    }

    /** Unregisters the pass from [sim]. Already-broken edges stay broken. */
    fun detach() {
        sim.removeConstraintEdit(name)
    }

    /**
     * Retires slot [slot] of particle [i] and its reverse. Returns false if the slot was already
     * dead — which happens routinely, because the reverse direction of an edge broken earlier in
     * this same scan is still in the loop's way.
     */
    private fun breakSlot(i: Int, slot: Int): Boolean {
        val j = neighbor(i, slot)
        if (j < 0 || stiffness(i, slot) == 0f) return false
        retire(i, slot)
        if (j < constraints.size) {
            for (s in 0 until 4) {
                if (neighbor(j, s) == i) retire(j, s)
            }
        }
        onTear?.invoke(minOf(i, j), maxOf(i, j))
        return true
    }

    /** Strain of one slot, or null if the slot is unused, dead, or has no rest length to divide by. */
    private fun slotStrain(state: SimdVerlet.State3, i: Int, slot: Int): Float? {
        val j = neighbor(i, slot)
        if (j < 0 || j >= state.size) return null
        if (stiffness(i, slot) == 0f) return null
        val rest = restLength(i, slot)
        if (rest <= 0f) return null
        val dx = state.x[j] - state.x[i]
        val dy = state.y[j] - state.y[i]
        val dz = state.z[j] - state.z[i]
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        if (!dist.isFinite()) return null
        return (dist - rest) / rest
    }

    private fun neighbor(i: Int, slot: Int): Int = when (slot) {
        0 -> constraints.n0[i]
        1 -> constraints.n1[i]
        2 -> constraints.n2[i]
        else -> constraints.n3[i]
    }

    private fun stiffness(i: Int, slot: Int): Float = when (slot) {
        0 -> constraints.s0[i]
        1 -> constraints.s1[i]
        2 -> constraints.s2[i]
        else -> constraints.s3[i]
    }

    private fun restLength(i: Int, slot: Int): Float = when (slot) {
        0 -> constraints.r0[i]
        1 -> constraints.r1[i]
        2 -> constraints.r2[i]
        else -> constraints.r3[i]
    }

    private fun retire(i: Int, slot: Int) = constraints.setSlot(i, slot, -1, 0f, 0f)

    private fun growCandidates() {
        val next = max(16, candOwner.size * 2)
        candOwner = candOwner.copyOf(next)
        candSlot = candSlot.copyOf(next)
        candStrain = candStrain.copyOf(next)
    }
}

/**
 * Registers tearing on this simulation and hands back the live handle: retune [Tearing.threshold],
 * read [Tearing.brokenTotal], call [Tearing.breakEdge] to cut something by hand, all between
 * frames.
 *
 *     val tear = sim.addTearing(threshold = 0.35f)
 *     repeat(400) { cloth.step(gravity = 4f, iterations = 16) }
 *     println("${tear.brokenTotal} edges gone")
 *
 * @param threshold strain (a fraction of rest length) above which an edge is retired.
 * @param maxBreaksPerStep `< 0` (the default) tears everything over threshold at once, which is
 *   what a real sheet failing under load does. A small positive number spreads the failure over
 *   frames, most strained edge first, which is what you want when the tear is the thing being
 *   watched. It is a *different* rip and not merely a slower one, and that is worth knowing before
 *   reaching for it: a sheet failing one edge per frame goes on carrying load while it fails, so
 *   more of it reaches the threshold. Measured on `SimdVerletTearingTest`'s 14 x 9 sheet, 14 edges
 *   go unthrottled and 22 at one per step. Deterministic either way.
 * @param constraints the set to edit; defaults to the one the simulation is solving.
 */
fun SimdVerlet.addTearing(
    threshold: Float = 0.5f,
    range: IntRange? = null,
    maxBreaksPerStep: Int = -1,
    name: String = "tearing",
    constraints: SimdVerlet.DirectedConstraints4? = this.constraints4,
    onTear: ((i: Int, j: Int) -> Unit)? = null
): Tearing {
    val set = requireNotNull(constraints) {
        "cannot tear: this simulation has no 4-slot constraint set (add a Cloth, or set constraints4)"
    }
    val tearing = Tearing(this, set, name, threshold, range, maxBreaksPerStep, onTear)
    addConstraintEdit(name) { state, _ -> tearing.scan(state) }
    return tearing
}

/**
 * Tearing for one cloth, wired to its curvature stencil.
 *
 * The extra thing this does over [SimdVerlet.addTearing] is the reason the hook exists: [Cloth]'s
 * curvature force pulls each interior node toward the mean of its four axis neighbours, and that
 * stencil is written from the grid, not from the constraint set. Tear an edge and the curvature
 * force goes on flattening the sheet *across the rip* — the two lips of the tear are held together
 * by a force that no longer has a spring behind it, and the sheet heals in the picture while the
 * solver says it is torn. So the tear callback suppresses curvature at both endpoints
 * ([Cloth.suppressCurvatureAt]), which removes exactly the stencils that spanned the broken edge.
 *
 * @param range defaults to this cloth's own particles, so a tear in one cloth doesn't cut another.
 */
fun Cloth.enableTearing(
    threshold: Float = 0.5f,
    maxBreaksPerStep: Int = -1,
    passName: String = "$name.tearing",
    alsoOnTear: ((i: Int, j: Int) -> Unit)? = null
): Tearing = sim.addTearing(
    threshold = threshold,
    range = base until end,
    maxBreaksPerStep = maxBreaksPerStep,
    name = passName,
    constraints = constraints,
    onTear = { i, j ->
        suppressCurvatureAt(i)
        suppressCurvatureAt(j)
        alsoOnTear?.invoke(i, j)
    }
)

/**
 * The largest `(distance - rest) / rest` over every live constraint the simulation is solving, or
 * `-1f` if it is solving none.
 *
 * The measurement behind the stiffness table in `dev_docs/physics/SIMDVERLET_4_CONSTRAINTS.md`,
 * which had to be re-derived by hand each time it was wanted. It is also the number to read before
 * choosing a tear [Tearing.threshold]: whatever a sheet's settled peak strain is, a threshold
 * below it tears the sheet at rest.
 *
 * Returns `Float.NaN` if any live constraint's endpoints have gone non-finite — the diverged case
 * is worth surfacing rather than skipping, because a solver that has blown up otherwise reports
 * a perfectly reasonable-looking peak strain from whichever edges are still numbers.
 */
fun SimdVerlet.peakStrain(range: IntRange? = null): Float {
    val c4 = constraints4
    val c3 = if (c4 == null) constraints else null
    if (c4 == null && c3 == null) return -1f
    val n = state.size
    val start = (range?.first ?: 0).coerceAtLeast(0)
    val end = (if (range == null) n else range.last + 1).coerceAtMost(n)
    var peak = -1f
    var sawNonFinite = false

    fun consider(i: Int, j: Int, rest: Float, stiff: Float) {
        if (j < 0 || j >= n || stiff == 0f || rest <= 0f) return
        val dx = state.x[j] - state.x[i]
        val dy = state.y[j] - state.y[i]
        val dz = state.z[j] - state.z[i]
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        if (!dist.isFinite()) {
            sawNonFinite = true
            return
        }
        val s = (dist - rest) / rest
        if (s > peak) peak = s
    }

    for (i in start until end) {
        if (c4 != null) {
            if (i >= c4.size) break
            consider(i, c4.n0[i], c4.r0[i], c4.s0[i])
            consider(i, c4.n1[i], c4.r1[i], c4.s1[i])
            consider(i, c4.n2[i], c4.r2[i], c4.s2[i])
            consider(i, c4.n3[i], c4.r3[i], c4.s3[i])
        } else if (c3 != null) {
            if (i >= c3.size) break
            consider(i, c3.n0[i], c3.r0[i], c3.s0[i])
            consider(i, c3.n1[i], c3.r1[i], c3.s1[i])
            consider(i, c3.n2[i], c3.r2[i], c3.s2[i])
        }
    }
    return if (sawNonFinite) Float.NaN else peak
}

/**
 * How many live distance constraints the simulation is solving, counting a symmetric pair once —
 * the same edge count [SimdVerlet.debugLines] draws, so "the picture lost 40 lines" and "40 edges
 * tore" are the same number.
 */
fun SimdVerlet.liveEdgeCount(range: IntRange? = null): Int {
    val c4 = constraints4
    val c3 = if (c4 == null) constraints else null
    if (c4 == null && c3 == null) return 0
    val n = state.size
    val start = (range?.first ?: 0).coerceAtLeast(0)
    val end = (if (range == null) n else range.last + 1).coerceAtMost(n)
    var count = 0

    fun reverseExists(from: Int, to: Int): Boolean {
        if (c4 != null) {
            if (from >= c4.size) return false
            return (c4.n0[from] == to && c4.s0[from] != 0f) || (c4.n1[from] == to && c4.s1[from] != 0f) ||
                (c4.n2[from] == to && c4.s2[from] != 0f) || (c4.n3[from] == to && c4.s3[from] != 0f)
        }
        val c = c3 ?: return false
        if (from >= c.size) return false
        return (c.n0[from] == to && c.s0[from] != 0f) || (c.n1[from] == to && c.s1[from] != 0f) ||
            (c.n2[from] == to && c.s2[from] != 0f)
    }

    fun consider(i: Int, j: Int, stiff: Float) {
        if (j < 0 || j >= n || stiff == 0f) return
        if (j < i && j >= start && j < end && reverseExists(j, i)) return
        count++
    }

    for (i in start until end) {
        if (c4 != null) {
            if (i >= c4.size) break
            consider(i, c4.n0[i], c4.s0[i])
            consider(i, c4.n1[i], c4.s1[i])
            consider(i, c4.n2[i], c4.s2[i])
            consider(i, c4.n3[i], c4.s3[i])
        } else if (c3 != null) {
            if (i >= c3.size) break
            consider(i, c3.n0[i], c3.s0[i])
            consider(i, c3.n1[i], c3.s1[i])
            consider(i, c3.n2[i], c3.s2[i])
        }
    }
    return count
}
