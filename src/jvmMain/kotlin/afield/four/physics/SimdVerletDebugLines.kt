package afield.four.physics

import afield.graphics.FLine
import afield.stdlib.Vec4
import afield.stdlib.color
import afield.stdlib.pointSize
import afield.stdlib.pointed
import afield.stdlib.stroked
import afield.stdlib.thickness
import afield.stdlib.vec

/**
 * A [SimdVerlet]'s particles and constraints as `FLine`s, for seeing what the solver holds rather
 * than what the scene means.
 *
 * The file is one extension function, `debugLines`, and the list it returns is grouped by
 * appearance rather than by particle: at most four lines, in the order constraint edges, velocity
 * whiskers, unpinned particles, pinned particles, each already carrying its own colour and
 * thickness, and each left out when it would have been empty. So a caller restyles a whole
 * category by editing one element of that list, and the list's own length says only which
 * categories drew something.
 */

/**
 * The solver's own state as `FLine`s: one segment per distance constraint, a dot per particle,
 * pinned particles in their own colour, and — when asked — a velocity whisker per particle.
 *
 * This is the debug view, not a renderer. It draws what the *solver* holds rather than what the
 * scene means, which is the point: a constraint that was never wired, a neighbour index pointing at
 * the wrong particle, a pin that isn't pinned and a sheet that is quietly exploding all look
 * obviously wrong here and look like nothing at all in a shaded mesh. Pair it with `Box.showPlane`
 * for a GPU-free live view:
 *
 *     loop {
 *         cloth.step(gravity = 4f)
 *         showPlane(sim.debugLines(), window = Rect(-1.0, -1.0, 12.0, 9.0))
 *     }
 *
 * Edges come from whichever constraint set the simulation is actually solving —
 * [SimdVerlet.constraints4] if it has one, else [SimdVerlet.constraints]. A symmetric pair (`i -> j`
 * and `j -> i`, as a [Cloth] grid wires it) is drawn once; a one-way constraint is drawn by its
 * owner, so an accidentally asymmetric wiring stays visible rather than being deduplicated away.
 * Unused slots (neighbour `-1` or zero stiffness) draw nothing.
 *
 * [thickness] and [pointSize] are in the units the *output* uses: pixels of the mapped frame for the
 * 2D SVG paths (`showPlane` / `showPlaneGrid`), world units for `Box.lines` and the `afield.four`
 * renderers, where they want to be much smaller. The dot radius convention is `pointSize * 3`.
 *
 * @param particles which particle indices to draw, clamped to the live range. Pass
 *   `cloth.base until cloth.end` to draw one cloth out of a busier simulation.
 * @param edges draw the distance constraints.
 * @param points draw a dot per particle.
 * @param velocityScale length of the velocity whiskers in world units per unit of Verlet
 *   displacement (`x - px`, i.e. one frame's travel). `0f` — the default — draws none.
 */
fun SimdVerlet.debugLines(
    particles: IntRange = 0 until size,
    edges: Boolean = true,
    points: Boolean = true,
    velocityScale: Float = 0f,
    edgeColor: Vec4 = vec(0.36, 0.78, 1.0, 0.45),
    pointColor: Vec4 = vec(0.74, 0.82, 0.94, 0.9),
    pinnedColor: Vec4 = vec(1.0, 0.42, 0.32, 1.0),
    velocityColor: Vec4 = vec(0.55, 0.95, 0.6, 0.75),
    thickness: Double = 1.4,
    pointSize: Double = 0.7
): List<FLine> {
    val range = particles.first.coerceAtLeast(0)..particles.last.coerceAtMost(size - 1)
    if (range.isEmpty()) return emptyList()

    val x = state.x
    val y = state.y
    val z = state.z
    val out = ArrayList<FLine>(4)

    val c4 = constraints4
    val c3 = if (c4 == null) constraints else null

    if (edges && (c4 != null || c3 != null)) {
        val line = FLine()
        val slots = IntArray(4)
        for (i in range) {
            val used = neighborSlots(c4, c3, i, slots)
            for (s in 0 until used) {
                val j = slots[s]
                if (j !in 0 until size) continue
                // A symmetric pair is one edge. Let the lower index draw it, but only when the
                // higher one really does own the reverse constraint and is itself being drawn.
                if (j < i && j in range && hasConstraint(c4, c3, j, i)) continue
                line.moveTo(x[i], y[i], z[i])
                line.lineTo(x[j], y[j], z[j])
            }
        }
        if (line.nodes.isNotEmpty()) {
            line.color = edgeColor
            line.thickness = thickness
            out.add(line)
        }
    }

    if (velocityScale != 0f) {
        val px = state.px
        val py = state.py
        val pz = state.pz
        val line = FLine()
        for (i in range) {
            val vx = (x[i] - px[i]) * velocityScale
            val vy = (y[i] - py[i]) * velocityScale
            val vz = (z[i] - pz[i]) * velocityScale
            if (vx == 0f && vy == 0f && vz == 0f) continue
            line.moveTo(x[i], y[i], z[i])
            line.lineTo(x[i] + vx, y[i] + vy, z[i] + vz)
        }
        if (line.nodes.isNotEmpty()) {
            line.color = velocityColor
            line.thickness = thickness
            out.add(line)
        }
    }

    if (points) {
        val invMass = state.invMass
        val free = FLine()
        val pinned = FLine()
        for (i in range) {
            val target = if (invMass != null && invMass[i] == 0f) pinned else free
            target.moveTo(x[i], y[i], z[i])
        }
        for ((line, color) in listOf(free to pointColor, pinned to pinnedColor)) {
            if (line.nodes.isEmpty()) continue
            line.color = color
            line.pointed = true
            line.pointSize = pointSize
            // Nothing to stroke — every node is a moveTo — but say so, so a renderer that would
            // otherwise set up a stroke pass for this line doesn't bother.
            line.stroked = false
            out.add(line)
        }
    }

    return out
}

/** Neighbour indices of the slots particle [i] actually uses, written into [into]; returns how many. */
private fun neighborSlots(
    c4: SimdVerlet.DirectedConstraints4?,
    c3: SimdVerlet.DirectedConstraints3?,
    i: Int,
    into: IntArray
): Int {
    var n = 0
    if (c4 != null) {
        if (i >= c4.size) return 0
        if (c4.n0[i] >= 0 && c4.s0[i] != 0f) into[n++] = c4.n0[i]
        if (c4.n1[i] >= 0 && c4.s1[i] != 0f) into[n++] = c4.n1[i]
        if (c4.n2[i] >= 0 && c4.s2[i] != 0f) into[n++] = c4.n2[i]
        if (c4.n3[i] >= 0 && c4.s3[i] != 0f) into[n++] = c4.n3[i]
        return n
    }
    if (c3 != null) {
        if (i >= c3.size) return 0
        if (c3.n0[i] >= 0 && c3.s0[i] != 0f) into[n++] = c3.n0[i]
        if (c3.n1[i] >= 0 && c3.s1[i] != 0f) into[n++] = c3.n1[i]
        if (c3.n2[i] >= 0 && c3.s2[i] != 0f) into[n++] = c3.n2[i]
    }
    return n
}

/** Whether [from] owns a live constraint to [to]. */
private fun hasConstraint(
    c4: SimdVerlet.DirectedConstraints4?,
    c3: SimdVerlet.DirectedConstraints3?,
    from: Int,
    to: Int
): Boolean {
    if (c4 != null) {
        if (from >= c4.size) return false
        return (c4.n0[from] == to && c4.s0[from] != 0f) ||
            (c4.n1[from] == to && c4.s1[from] != 0f) ||
            (c4.n2[from] == to && c4.s2[from] != 0f) ||
            (c4.n3[from] == to && c4.s3[from] != 0f)
    }
    if (c3 != null) {
        if (from >= c3.size) return false
        return (c3.n0[from] == to && c3.s0[from] != 0f) ||
            (c3.n1[from] == to && c3.s1[from] != 0f) ||
            (c3.n2[from] == to && c3.s2[from] != 0f)
    }
    return false
}
