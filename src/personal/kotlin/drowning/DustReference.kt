package drowning

import kotlin.math.sqrt

/**
 * The force step and the Verlet integrator, on the CPU, for exactly one purpose: to pin the sign
 * conventions the GLSL depends on.
 *
 * This is **not** a simulator and it is not a mirror to compare numbers against — a hand-mirrored test
 * that asserts stored values blesses whatever the code did on the day it was written. It implements one
 * *property*: a spring network under the documented convention, released from a perturbed start, must
 * **contract** toward its rest lengths. Flip the sign of the edge force and it diverges instead, which
 * is the whole content of the assertion.
 *
 * That property is worth a file because the convention is easy to get backwards and expensive to debug
 * when you do. `gEdgeDir` points from a particle *toward* its neighbour, so a positive `edgeForce`
 * result pulls the pair together. Writing Hooke's law as `-12 *` instead of `12 *` therefore pushes
 * hardest exactly where the network is already overstretched. The field does not wobble — it detonates,
 * every particle ends up pinned motionless against the bounding box, and what you see on screen is a
 * smear over six faces that looks like a rendering bug rather than a sign error. That is a real
 * afternoon, and this is the thirty lines that would have saved it.
 *
 * Deliberately narrow: no bounds, no noise, no `aux`, no `stack.auto`. Only the edge term, the rest
 * term, damping and the position update — the parts whose signs the shader and this file must agree on.
 */
object DustReference {

    /** One integrator's worth of state: positions and previous positions, flat xyz. */
    class State(val count: Int, val position: FloatArray, val previous: FloatArray) {
        companion object {
            /** At rest at [field]'s positions — `previous == position`, so zero velocity. */
            fun atRest(field: DustField) =
                State(field.count, field.position.copyOf(), field.position.copyOf())
        }
    }

    /**
     * One Verlet step, matching [DustShaders.FORCE_COMPUTE] term for term and sign for sign.
     *
     * [edgeStiffness] is the linear coefficient of the documented Hooke program (`input x k *`), so a
     * **positive** value is restoring. [restStiffness] is the same for the tether (`input xyz k *`),
     * where the argument is `rest - pos`, so positive is also restoring — the two arguments have
     * opposite senses and that asymmetry is exactly what a reader gets wrong.
     */
    fun step(
        state: State,
        field: DustField,
        links: DustLinks,
        edgeStiffness: Float,
        restStiffness: Float,
        damping: Float = 0.94f,
        dt: Float = 1f / 60f
    ): State {
        val n = state.count
        val next = FloatArray(n * 3)
        val slots = DustLayout.EDGES

        for (i in 0 until n) {
            val force = FloatArray(3)

            for (s in 0 until slots) {
                val j = links.neighbour[i * slots + s]
                if (j == DustLayout.NONE) continue
                val d = FloatArray(3) { a -> state.position[j * 3 + a] - state.position[i * 3 + a] }
                val len = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
                if (len < 1e-8f) continue
                // Positive f pulls toward the neighbour, because `d/len` points at it. This line is the
                // convention.
                val f = (len - links.restLength[i * slots + s]) * edgeStiffness
                for (a in 0 until 3) force[a] += d[a] / len * f
            }

            for (a in 0 until 3) {
                force[a] += (field.position[i * 3 + a] - state.position[i * 3 + a]) * restStiffness
            }

            for (a in 0 until 3) {
                val velocity = (state.position[i * 3 + a] - state.previous[i * 3 + a]) * damping
                next[i * 3 + a] = state.position[i * 3 + a] + velocity +
                    force[a] * field.invMass[i] * dt * dt
            }
        }
        return State(n, next, state.position)
    }

    /** Root-mean-square distance from the rest positions — how far the field is from where it started. */
    fun rmsDisplacement(state: State, field: DustField): Float {
        var sum = 0.0
        for (k in state.position.indices) {
            val d = (state.position[k] - field.position[k]).toDouble()
            sum += d * d
        }
        return sqrt(sum / state.count).toFloat()
    }

    /** Mean absolute edge extension, `|len - rest|`, over the filled slots. */
    fun meanExtension(state: State, links: DustLinks): Float {
        var sum = 0.0
        var n = 0
        val slots = DustLayout.EDGES
        for (i in 0 until links.count) for (s in 0 until slots) {
            val j = links.neighbour[i * slots + s]
            if (j == DustLayout.NONE) continue
            var d2 = 0f
            for (a in 0 until 3) {
                val d = state.position[j * 3 + a] - state.position[i * 3 + a]
                d2 += d * d
            }
            sum += kotlin.math.abs(sqrt(d2) - links.restLength[i * slots + s])
            n++
        }
        return if (n == 0) 0f else (sum / n).toFloat()
    }

    /** Displace every particle by a fixed radius in a deterministic pseudo-random direction. */
    fun perturbed(field: DustField, radius: Float, seed: Long = 4242L): State {
        val rng = java.util.Random(seed)
        val position = FloatArray(field.count * 3)
        for (i in 0 until field.count) {
            var dx = rng.nextFloat() * 2 - 1
            var dy = rng.nextFloat() * 2 - 1
            var dz = rng.nextFloat() * 2 - 1
            val l = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6f)
            dx /= l; dy /= l; dz /= l
            position[i * 3] = field.position[i * 3] + dx * radius
            position[i * 3 + 1] = field.position[i * 3 + 1] + dy * radius
            position[i * 3 + 2] = field.position[i * 3 + 2] + dz * radius
        }
        return State(field.count, position, position.copyOf())
    }

    /**
     * Run [steps] steps and report whether the field settled or ran away.
     *
     * `contracted` is the whole point: true when the mean edge extension ended below where it started,
     * which is what a restoring network does and what an anti-restoring one cannot.
     */
    data class Outcome(
        val startExtension: Float,
        val endExtension: Float,
        val startDisplacement: Float,
        val endDisplacement: Float,
        val finite: Boolean
    ) {
        val contracted: Boolean get() = finite && endExtension < startExtension

        override fun toString(): String =
            "extension %.5f -> %.5f, displacement %.5f -> %.5f%s"
                .format(startExtension, endExtension, startDisplacement, endDisplacement,
                    if (finite) "" else " [NON-FINITE]")
    }

    /** [step] repeated, with the two ends measured. */
    fun contractsUnder(
        field: DustField,
        links: DustLinks,
        edgeStiffness: Float,
        restStiffness: Float,
        steps: Int = 240,
        perturbation: Float = 0.3f
    ): Outcome {
        var state = perturbed(field, perturbation)
        val startExtension = meanExtension(state, links)
        val startDisplacement = rmsDisplacement(state, field)
        repeat(steps) { state = step(state, field, links, edgeStiffness, restStiffness) }
        return Outcome(
            startExtension = startExtension,
            endExtension = meanExtension(state, links),
            startDisplacement = startDisplacement,
            endDisplacement = rmsDisplacement(state, field),
            finite = state.position.all { it.isFinite() }
        )
    }
}
