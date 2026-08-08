package afield.petri

import afield.four.physics.OctreeBarnesHut
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The layout solver behind [PetriLayout.FORCE].
 *
 * A spring model finds *a* local minimum of its own energy, and that is not the same thing as a
 * drawing you can read. Thirty nodes of dining philosophers relax to a perfectly respectable energy
 * and come out as a hairball. So this does not trust the physics: it runs the relaxation many times
 * from different random starts and **scores the results**, on the things that actually make a graph
 * legible — edges that cross, nodes that overlap, and edges that pass under an unrelated node —
 * then keeps the best one.
 *
 * Random, but not unpredictable. Every restart is seeded from a hash of the net's own structure, so
 * the same net always produces the same drawing, on every machine and every run. That matters more
 * than it might sound: docs captures are committed PNGs compared byte-for-byte, and a layout that
 * wobbles would churn them on every build.
 */
internal object PetriLayoutSolver {

    /** Centre-to-centre distance below which two nodes are counted as colliding. */
    const val MIN_SEPARATION = 68.0

    /**
     * Restarts to try, at most; in practice the work budget stops the search first on anything
     * dense, and a clean drawing leaves through the early exit.
     *
     * Deliberately not cut short by a patience rule. Stopping after a run of restarts that failed
     * to improve is the obvious economy and it measurably loses: on dining philosophers the restart
     * that finally produces a crossing-free drawing arrives *after* such a run, and a patience of
     * six took the score back from 77 to 202.
     */
    const val RESTARTS = 48

    /**
     * Node count above which repulsion goes through [OctreeBarnesHut] instead of every pair.
     *
     * Measured, and further out than it looks like it should be. Timing the two backends on the
     * same graphs: at 360 nodes the octree is still 1.15x *slower* than the all-pairs loop, at 720
     * it is 1.27x faster, at 1440 1.7x and at 2400 2.1x. Building and walking a tree is simply not
     * worth it until the pair count is enormous, and a first guess of 128 here was wrong by about
     * a factor of four. No hand-written Petri net comes close; this path is for generated ones.
     */
    const val BARNES_HUT_MIN_NODES = 512

    private const val ITERATIONS = 420
    private const val SEPARATION_PASSES = 120

    /** Steepest-descent swap sweeps. Convergence is usually well inside this; it is a safety cap. */
    private const val REFINE_SWEEPS = 24

    /**
     * Above this, crossing-removal is skipped and you get the relaxation alone.
     *
     * Each candidate swap costs a full O(E²) rescore, so the sweep is superlinear however the
     * candidates are restricted. A net this size is generated rather than written, and a drawing of
     * one is a diagram of a hairball whatever we do to it — the honest limit is better than a
     * build that appears to hang.
     */
    private const val REFINE_MAX_NODES = 160

    /**
     * Total hill-climbing *work* one solve may do, across all restarts, in scoring operations.
     *
     * Counting candidate swaps instead is the intuitive thing and it does not bound anything: each
     * candidate costs `O(E² + n·E)` to score, so the same candidate count is 2,000 operations on a
     * 30-node net and 32,000 on a 120-node one. A budget of 900,000 candidates duly held dining
     * philosophers to 0.4s and let a 120-node net run for 269 seconds.
     *
     * Counted work rather than elapsed time, deliberately: a wall-clock budget would make the
     * drawing depend on how busy the machine was, and these layouts end up in committed PNGs.
     *
     * Generous, because it is only ever reached by a net that cannot be drawn well. A net that can
     * be leaves through the early exit as soon as it is clean, so raising this ceiling costs the
     * easy cases nothing and is the difference between finding the good drawing and stopping one
     * restart short of it — which is exactly what a tighter budget did to dining philosophers.
     */
    private const val BUDGET_OPERATIONS = 500_000_000L

    /** An undirected pair of node indices, for scoring. Arc direction does not affect legibility. */
    data class Edge(val a: Int, val b: Int)

    /**
     * How bad a drawing is. Lower is better; [total] is what the search minimises.
     *
     * The weights are a ranking, not a measurement: one crossing is worse than one overlap is worse
     * than one edge grazing a node, and [spread] only ever breaks ties between layouts that are
     * equal on all three.
     */
    data class Score(
        val crossings: Int,
        val overlaps: Int,
        val nodeOnEdge: Int,
        val spread: Double,
    ) {
        val total: Double get() = crossings * 100.0 + overlaps * 40.0 + nodeOnEdge * 25.0 + spread

        override fun toString(): String =
            "crossings=$crossings overlaps=$overlaps nodeOnEdge=$nodeOnEdge spread=%.2f total=%.1f"
                .format(spread, total)
    }

    /** A solved layout and the score that won it. */
    class Solution(val x: FloatArray, val y: FloatArray, val score: Score)

    /**
     * Relaxes [count] nodes connected by [edges], [RESTARTS] times, and returns the best-scoring run.
     *
     * [seed] must be derived from the graph rather than the clock — see the class note.
     */
    fun solve(
        count: Int,
        edges: List<Edge>,
        radius: Double,
        seed: Int,
        restarts: Int = RESTARTS,
        useBarnesHut: Boolean = count >= BARNES_HUT_MIN_NODES,
    ): Solution {
        require(count > 0) { "nothing to lay out" }
        if (count == 1) return Solution(floatArrayOf(0f), floatArrayOf(0f), Score(0, 0, 0, 0.0))

        // A circle whose circumference can hold everything, so the first iteration is not already
        // resolving a pile at the origin.
        val extent = max(radius, count * MIN_SEPARATION / (2.0 * PI))
        val ideal = max(MIN_SEPARATION, extent * 2.4 / max(2.0, sqrt(count.toDouble())))

        // Every restart is refined before it is judged. Ranking them on their *relaxed* scores and
        // refining only the winner is the obvious economy and it measurably does not work: on
        // dining philosophers the restart that relaxes best is not the one that refines best, and
        // picking on the relaxed score gave back a crossing the full search had removed.
        //
        // So the cost is bounded by a budget of candidate evaluations instead. Counted work rather
        // than elapsed time, deliberately: a wall-clock budget would make the drawing depend on how
        // busy the machine was, and these layouts end up in committed PNGs.
        val budget = Budget(BUDGET_OPERATIONS)
        var best: Solution? = null
        for (restart in 0 until restarts) {
            val random = Random(seed * 31 + restart)
            val x = FloatArray(count)
            val y = FloatArray(count)
            seedPositions(x, y, count, extent, restart, random)
            relax(x, y, count, edges, ideal, extent, useBarnesHut)
            separate(x, y, count)
            val score = refine(x, y, count, edges, budget)
            if (best == null || score.total < best.score.total) best = Solution(x, y, score)
            // Nothing crosses, nothing overlaps, nothing is in the way: by the only criteria we
            // have this cannot be improved on, so further restarts would be superstition.
            if (score.crossings == 0 && score.overlaps == 0 && score.nodeOnEdge == 0) break
            if (budget.exhausted) break
        }
        val winner = best!!
        centre(winner.x, winner.y, count)
        return winner
    }

    /** A deterministic cap on hill-climbing work, shared across every restart in one solve. */
    private class Budget(private var remaining: Long) {
        val exhausted: Boolean get() = remaining <= 0
        fun spend(operations: Long) { remaining -= operations }
    }

    /**
     * Restart 0 is the deterministic circle the old solver always used — it is a genuinely good
     * start for the ring-ish nets that most drum machines are, and starting the search from it
     * means the search can only improve on the previous behaviour, never regress below it.
     */
    private fun seedPositions(
        x: FloatArray,
        y: FloatArray,
        count: Int,
        extent: Double,
        restart: Int,
        random: Random,
    ) {
        if (restart == 0) {
            for (i in 0 until count) {
                val angle = 2.0 * PI * i / count
                x[i] = (cos(angle) * extent).toFloat()
                y[i] = (sin(angle) * extent).toFloat()
            }
            return
        }
        // Later restarts scatter. A jittered circle rather than uniform noise: uniform noise in a
        // disc starts most nodes near the middle and the first iterations are spent undoing that.
        for (i in 0 until count) {
            val angle = 2.0 * PI * (i + random.nextDouble(-0.5, 0.5)) / count
            val r = extent * random.nextDouble(0.35, 1.25)
            x[i] = (cos(angle) * r).toFloat()
            y[i] = (sin(angle) * r).toFloat()
        }
    }

    private fun relax(
        x: FloatArray,
        y: FloatArray,
        count: Int,
        edges: List<Edge>,
        ideal: Double,
        extent: Double,
        useBarnesHut: Boolean,
    ) {
        val dx = FloatArray(count)
        val dy = FloatArray(count)
        val repulsion = (ideal * ideal).toFloat()

        val tree = if (useBarnesHut) OctreeBarnesHut(initialCapacity = max(1024, count * 2)) else null
        val z = if (useBarnesHut) FloatArray(count) else FloatArray(0)
        val dz = if (useBarnesHut) FloatArray(count) else FloatArray(0)
        val mass = if (useBarnesHut) FloatArray(count) { 1f } else FloatArray(0)

        for (pass in 0 until ITERATIONS) {
            // Linear cooling: big moves early to escape the seed, small ones late to settle.
            val cooling = (0.12 * (1.0 - pass.toDouble() / ITERATIONS)).toFloat()
            java.util.Arrays.fill(dx, 0f)
            java.util.Arrays.fill(dy, 0f)

            if (tree != null) {
                java.util.Arrays.fill(dz, 0f)
                tree.build(x, y, z, mass, null, count)
                // Negative g is repulsion: the kernel is `g * m / r²` along (other - self).
                tree.applyForces(
                    x, y, z, dx, dy, dz, mass, null, count,
                    theta = 0.7f, softening = (MIN_SEPARATION * 0.25).toFloat(), g = -repulsion,
                )
            } else {
                repelExactly(x, y, dx, dy, count, repulsion)
            }

            for ((a, b) in edges) {
                val ax = x[b] - x[a]
                val ay = y[b] - y[a]
                val distance = max(1e-6f, hypot(ax.toDouble(), ay.toDouble()).toFloat())
                val pull = ((distance - ideal) * 0.6).toFloat()
                dx[a] += ax / distance * pull
                dy[a] += ay / distance * pull
                dx[b] -= ax / distance * pull
                dy[b] -= ay / distance * pull
            }

            val limit = (extent * 0.2).toFloat()
            for (i in 0 until count) {
                // A very gentle pull to the origin so a lightly-connected node does not drift away.
                // Any stronger and it fights the separation pass and the graph balls up again.
                dx[i] -= x[i] * 0.004f
                dy[i] -= y[i] * 0.004f
                x[i] += (dx[i] * cooling).coerceIn(-limit, limit)
                y[i] += (dy[i] * cooling).coerceIn(-limit, limit)
            }
        }
    }

    /** All-pairs Coulomb repulsion — the same `1/r²` law [OctreeBarnesHut] approximates. */
    private fun repelExactly(
        x: FloatArray,
        y: FloatArray,
        dx: FloatArray,
        dy: FloatArray,
        count: Int,
        repulsion: Float,
    ) {
        val eps2 = (MIN_SEPARATION * 0.25f * MIN_SEPARATION * 0.25f).toFloat()
        for (i in 0 until count) for (j in i + 1 until count) {
            val ax = x[i] - x[j]
            val ay = y[i] - y[j]
            val dist2 = ax * ax + ay * ay + eps2
            val invDist = 1f / sqrt(dist2)
            val push = repulsion * invDist * invDist
            dx[i] += ax * invDist * push
            dy[i] += ay * invDist * push
            dx[j] -= ax * invDist * push
            dy[j] -= ay * invDist * push
        }
    }

    /**
     * Whatever the springs settled on, no two nodes may overlap.
     *
     * This is a legibility constraint rather than an energy term, so it is enforced rather than
     * balanced — the spring model has no idea how big the shapes it is positioning are.
     */
    private fun separate(x: FloatArray, y: FloatArray, count: Int) {
        val minimum = MIN_SEPARATION.toFloat()
        repeat(SEPARATION_PASSES) {
            for (i in 0 until count) for (j in i + 1 until count) {
                var ax = x[i] - x[j]
                var ay = y[i] - y[j]
                var distance = hypot(ax.toDouble(), ay.toDouble()).toFloat()
                if (distance < 1e-6f) {
                    // Deterministic nudge rather than a random one, so a restart is reproducible.
                    ax = (((i * 31 + j) % 7) - 3).toFloat().takeIf { it != 0f } ?: 1f
                    ay = (((i * 17 + j) % 5) - 2).toFloat()
                    distance = max(1e-3f, hypot(ax.toDouble(), ay.toDouble()).toFloat())
                }
                if (distance >= minimum) continue
                val shove = (minimum - distance) / 2f
                x[i] += ax / distance * shove
                y[i] += ay / distance * shove
                x[j] -= ax / distance * shove
                y[j] -= ay / distance * shove
            }
        }
    }

    /**
     * Hill-climbing on the score itself, by swapping which node sits at which position.
     *
     * This is the part that actually removes crossings, and it exists because a spring model
     * fundamentally cannot: springs minimise energy, and the number of times two edges cross is not
     * a term in any energy they know about. On a symmetric graph every random restart relaxes to
     * the *same* shape up to a rotation — and every term in [Score] is rotation-invariant — so
     * restarts alone measurably changed nothing.
     *
     * Swapping two nodes' positions is the right move to search over because it permutes the set of
     * occupied points without altering it. Every pairwise distance is preserved exactly, so the
     * separation guarantee established above cannot be undone here, and only [Score.crossings],
     * [Score.nodeOnEdge] and [Score.spread] can move. That is what makes the search safe to run to
     * convergence rather than having to re-check the invariant after each step.
     *
     * Steepest-descent, not first-improvement: take the single best swap available in each sweep,
     * because an early mediocre swap often blocks a much better one involving the same node.
     *
     * Only swaps involving a node that is *currently part of a defect* are considered. Trying all
     * `n²/2` pairs is what the obvious implementation does and it is unusable — measured at 0.4s for
     * 30 nodes, 13s for 48 and 104s for 72, because each candidate costs a full `O(E²)` rescore.
     * Moving a node that is not in anybody's way cannot reduce a crossing count, so restricting the
     * candidates to offenders costs nothing in quality and takes the sweep back to `|offenders|·n`.
     * A clean layout has no offenders and returns immediately.
     */
    private fun refine(x: FloatArray, y: FloatArray, count: Int, edges: List<Edge>, budget: Budget): Score {
        var current = score(x, y, count, edges)
        if (edges.isEmpty() || count > REFINE_MAX_NODES) return current

        repeat(REFINE_SWEEPS) {
            if (budget.exhausted) return current
            val offenders = offenders(x, y, count, edges)
            if (offenders.isEmpty()) return current
            // What a sweep will actually cost: one rescore per candidate, each proportional to the
            // edge-pair and node-edge work inside `score`.
            val perCandidate = edges.size.toLong() * edges.size / 2 + count.toLong() * edges.size
            budget.spend(offenders.size.toLong() * count * perCandidate)

            var bestI = -1
            var bestJ = -1
            var bestScore = current

            for (i in offenders) for (j in 0 until count) {
                if (i == j) continue
                swap(x, y, i, j)
                // `overlaps` is deliberately not recomputed here: a swap permutes the occupied
                // points without changing the set, so the multiset of pairwise distances — and
                // therefore the overlap count — is identical on both sides of it. Recomputing it
                // would be O(n²) per candidate to re-derive a constant.
                val candidate = scoreWithoutOverlaps(x, y, count, edges, bestScore.overlaps)
                swap(x, y, i, j)
                if (candidate.total < bestScore.total - 1e-9) {
                    bestScore = candidate
                    bestI = i
                    bestJ = j
                }
            }

            if (bestI < 0) return current // a local optimum under swapping; nothing left to take
            swap(x, y, bestI, bestJ)
            current = bestScore
        }
        return current
    }

    /**
     * The nodes taking part in a crossing or standing on an edge — the only ones worth moving.
     *
     * Both endpoints of each offending edge are included, because either of them could be the one
     * in the wrong place.
     */
    private fun offenders(x: FloatArray, y: FloatArray, count: Int, edges: List<Edge>): IntArray {
        val bad = LinkedHashSet<Int>()
        for (i in edges.indices) for (j in i + 1 until edges.size) {
            val p = edges[i]
            val q = edges[j]
            if (p.a == q.a || p.a == q.b || p.b == q.a || p.b == q.b) continue
            if (segmentsCross(
                    x[p.a], y[p.a], x[p.b], y[p.b],
                    x[q.a], y[q.a], x[q.b], y[q.b],
                )
            ) {
                bad += p.a; bad += p.b; bad += q.a; bad += q.b
            }
        }
        val clearance = MIN_SEPARATION * 0.45
        for (edge in edges) for (n in 0 until count) {
            if (n == edge.a || n == edge.b) continue
            if (pointSegmentDistance(x[n], y[n], x[edge.a], y[edge.a], x[edge.b], y[edge.b]) < clearance) {
                bad += n; bad += edge.a; bad += edge.b
            }
        }
        return bad.toIntArray()
    }

    private fun swap(x: FloatArray, y: FloatArray, i: Int, j: Int) {
        val tx = x[i]; x[i] = x[j]; x[j] = tx
        val ty = y[i]; y[i] = y[j]; y[j] = ty
    }

    private fun centre(x: FloatArray, y: FloatArray, count: Int) {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until count) {
            minX = min(minX, x[i]); maxX = max(maxX, x[i])
            minY = min(minY, y[i]); maxY = max(maxY, y[i])
        }
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        for (i in 0 until count) { x[i] -= cx; y[i] -= cy }
    }

    // ── scoring ────────────────────────────────────────────────────────────────────────────

    fun score(x: FloatArray, y: FloatArray, count: Int, edges: List<Edge>): Score {
        var overlaps = 0
        for (i in 0 until count) for (j in i + 1 until count) {
            if (hypot((x[i] - x[j]).toDouble(), (y[i] - y[j]).toDouble()) < MIN_SEPARATION - 1e-3) overlaps++
        }
        return scoreWithoutOverlaps(x, y, count, edges, overlaps)
    }

    /** [score] with the overlap count supplied rather than measured — see the note in `refine`. */
    private fun scoreWithoutOverlaps(
        x: FloatArray,
        y: FloatArray,
        count: Int,
        edges: List<Edge>,
        overlaps: Int,
    ): Score {
        var crossings = 0
        for (i in edges.indices) for (j in i + 1 until edges.size) {
            val p = edges[i]
            val q = edges[j]
            // Edges that share an endpoint meet there by construction; that is not a crossing.
            if (p.a == q.a || p.a == q.b || p.b == q.a || p.b == q.b) continue
            if (segmentsCross(
                    x[p.a], y[p.a], x[p.b], y[p.b],
                    x[q.a], y[q.a], x[q.b], y[q.b],
                )
            ) crossings++
        }

        // An arrow running under a circle it has nothing to do with reads as a connection that is
        // not there, which is a worse lie than a crossing.
        var nodeOnEdge = 0
        val clearance = MIN_SEPARATION * 0.45
        for (edge in edges) for (n in 0 until count) {
            if (n == edge.a || n == edge.b) continue
            if (pointSegmentDistance(x[n], y[n], x[edge.a], y[edge.a], x[edge.b], y[edge.b]) < clearance) nodeOnEdge++
        }

        // Total edge length, normalised out of pixel units, as a tie-break towards compactness.
        var length = 0.0
        for ((a, b) in edges) length += hypot((x[a] - x[b]).toDouble(), (y[a] - y[b]).toDouble())
        val spread = if (edges.isEmpty()) 0.0 else length / (edges.size * MIN_SEPARATION)

        return Score(crossings, overlaps, nodeOnEdge, spread)
    }

    /** Proper segment intersection: touching at an endpoint or collinear overlap does not count. */
    private fun segmentsCross(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
    ): Boolean {
        val d1 = cross(cx, cy, dx, dy, ax, ay)
        val d2 = cross(cx, cy, dx, dy, bx, by)
        val d3 = cross(ax, ay, bx, by, cx, cy)
        val d4 = cross(ax, ay, bx, by, dx, dy)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }

    private fun cross(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Double =
        (bx - ax).toDouble() * (py - ay).toDouble() - (by - ay).toDouble() * (px - ax).toDouble()

    private fun pointSegmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Double {
        val vx = (bx - ax).toDouble()
        val vy = (by - ay).toDouble()
        val wx = (px - ax).toDouble()
        val wy = (py - ay).toDouble()
        val len2 = vx * vx + vy * vy
        if (len2 < 1e-9) return hypot(wx, wy)
        val t = ((wx * vx + wy * vy) / len2).coerceIn(0.0, 1.0)
        return hypot(wx - t * vx, wy - t * vy)
    }

    /**
     * A seed that depends only on the graph.
     *
     * String hashes are specified in the JDK, so this is stable across machines and runs — which is
     * what keeps committed layout captures byte-identical.
     */
    fun seedFor(names: List<String>, edges: List<Edge>): Int {
        var hash = 17
        for (name in names) hash = hash * 31 + name.hashCode()
        for ((a, b) in edges) hash = hash * 31 + (a * 7919 + b)
        return hash
    }
}
