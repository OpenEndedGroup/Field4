package afield.graphics.scene.reading

import afield.math.linalg.Vector3f
import afield.simulation.physics.SimdVerlet
import kotlin.math.exp

/** All thread nodes share one structure-of-arrays SIMD solver, separate from the word graph. */
internal class DiagramConnectionPhysics(private val player: ReadingDiagramPlayer) {
    private class Entry(val connection: DiagramConnection, val first: Int) {
        val count = connection.interiorNodes + 2
        var initialized = false
        var goals: List<Vector3f> = emptyList()
    }
    private val entries = buildList {
        var first = 0
        for (c in player.diagram.connections) {
            add(Entry(c, first))
            first += c.interiorNodes + 2
        }
    }
    private val lookup = entries.associateBy { it.connection }
    private val state = SimdVerlet.State3.empty(entries.sumOf { it.count })
    private val constraints = SimdVerlet.DirectedConstraints4.ofCapacity(state.limit)
    private val solver = SimdVerlet(state)

    init {
        for (entry in entries) repeat(entry.count) { local ->
            val index = state.addParticle()
            if (local == 0 || local == entry.count - 1) state.invMass!![index] = 0f
        }
    }

    fun reset() { entries.forEach { it.initialized = false } }

    private fun read(index: Int) = Vector3f(state.x[index], state.y[index], state.z[index])

    private fun pin(index: Int, point: Vector3f) {
        state.x[index] = point.x; state.px[index] = point.x
        state.y[index] = point.y; state.py[index] = point.y
        state.z[index] = point.z; state.pz[index] = point.z
    }

    private fun ends(entry: Entry, frame: DiagramFrame): Pair<Vector3f, Vector3f> {
        val c = entry.connection
        if (entry.initialized) return c.from.resolve(frame, read(entry.first + 1)) to
            c.to.resolve(frame, read(entry.first + entry.count - 2))
        // Before there is an interior state, use the opposite endpoint as the projection guide.
        var a = c.from.center(frame)
        var b = c.to.center(frame)
        repeat(3) { a = c.from.resolve(frame, b); b = c.to.resolve(frame, a) }
        return a to b
    }

    private fun goals(entry: Entry, frame: DiagramFrame, a: Vector3f, b: Vector3f): List<Vector3f> {
        val bow = Vector3f(entry.connection.bow).add(frame.pose(entry.connection.path).offset)
        return List(entry.count) { local ->
            val t = local.toFloat() / (entry.count - 1)
            Vector3f(a).lerp(b, t).fma(4 * t * (1 - t), bow)
        }
    }

    /** Pure snapshot: even a zero-time update or score seek gets exact, freshly constrained endpoints. */
    fun nodes(connection: DiagramConnection, frame: DiagramFrame): List<Vector3f> {
        val entry = lookup.getValue(connection)
        val (a, b) = ends(entry, frame)
        if (!entry.initialized) return goals(entry, frame, a, b)
        return List(entry.count) { local ->
            when (local) {
                0 -> a
                entry.count - 1 -> b
                else -> read(entry.first + local)
            }
        }
    }

    fun step(frame: DiagramFrame, dt: Float) {
        if (entries.isEmpty()) return
        for (entry in entries) {
            val c = entry.connection
            c.validate()
            val (a, b) = ends(entry, frame)
            entry.goals = goals(entry, frame, a, b)
            if (!entry.initialized) {
                entry.goals.forEachIndexed { local, p -> pin(entry.first + local, p) }
                entry.initialized = true
            }
            pin(entry.first, a)
            pin(entry.first + entry.count - 1, b)
            val segmentLengths = entry.goals.zipWithNext { x, y -> x.distance(y) }
            val layoutLength = segmentLengths.sum()
            for (local in 1 until entry.count - 1) {
                val index = entry.first + local
                constraints.clearSlots(index)
                var slot = 0
                for (offset in intArrayOf(-1, 1)) {
                    val other = local + offset
                    if (other !in 0 until entry.count) continue
                    val layoutSegment = segmentLengths[minOf(local, other)]
                    val stableLength = c.restLength?.let { total ->
                        if (layoutLength > 1e-8f) layoutSegment * total / layoutLength else total / (entry.count - 1)
                    } ?: layoutSegment
                    val rest = if (c.lengthMode == DiagramConnectionLength.MINIMIZE) 0f else stableLength * c.lengthScale
                    constraints.setSlot(index, slot++, entry.first + other, rest, c.lengthStiffness)
                }
            }
        }
        solver.step(dt, 1f, 0f, 1, constraints) { s, _ ->
            for (entry in entries) {
                val c = entry.connection
                val omega = 4f / c.responseSeconds
                val drag = exp(-2f * c.dampingRatio * omega * dt)
                val curvature = connectionCurvatureForces(List(entry.count) { read(entry.first + it) })
                for (local in 1 until entry.count - 1) {
                    val i = entry.first + local
                    // Per-thread damping, while the integrator itself still batches all nodes.
                    s.px[i] = s.x[i] - (s.x[i] - s.px[i]) * drag
                    s.py[i] = s.y[i] - (s.y[i] - s.py[i]) * drag
                    s.pz[i] = s.z[i] - (s.z[i] - s.pz[i]) * drag
                    val goal = entry.goals[local]
                    s.ax[i] += (goal.x - s.x[i]) * omega * omega * c.layoutStrength + curvature[local].x * c.curvatureStiffness
                    s.ay[i] += (goal.y - s.y[i]) * omega * omega * c.layoutStrength + curvature[local].y * c.curvatureStiffness
                    s.az[i] += (goal.z - s.z[i]) * omega * omega * c.layoutStrength + curvature[local].z * c.curvatureStiffness
                }
            }
        }
        // Kinematic endpoints never inherit velocity or drift through the integration step.
        for (entry in entries) {
            pin(entry.first, entry.goals.first())
            pin(entry.first + entry.count - 1, entry.goals.last())
        }
    }
}
