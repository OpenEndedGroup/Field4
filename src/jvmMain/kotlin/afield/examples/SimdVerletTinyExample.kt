package afield.examples

import afield.four.physics.OctreeBarnesHutPool
import afield.four.physics.SimdVerlet
import afield.generator2
import kotlin.math.sqrt

/**
 * Tiny 3-particle Verlet example with 2 distance constraints and gravity.
 */
object SimdVerletTinyExample {

    @JvmStatic
    fun main(args: Array<String>) {
        val count = 3

        val state = SimdVerlet.State3(
            x = FloatArray(0),
            y = FloatArray(0),
            z = FloatArray(0),
            px = FloatArray(0),
            py = FloatArray(0),
            pz = FloatArray(0),
            ax = FloatArray(0),
            ay = FloatArray(0),
            az = FloatArray(0),
            invMass = FloatArray(0)
        )

        val constraints = SimdVerlet.DirectedConstraints3(
            n0 = IntArray(count) { -1 },
            n1 = IntArray(count) { -1 },
            n2 = IntArray(count) { -1 },
            r0 = FloatArray(count),
            r1 = FloatArray(count),
            r2 = FloatArray(count),
            s0 = FloatArray(count),
            s1 = FloatArray(count),
            s2 = FloatArray(count)
        )

        val sim = SimdVerlet(state, constraints)
        val treePool = OctreeBarnesHutPool(maxTrees = 1, initialCapacity = 64)

        val p0 = sim.addParticle(x0 = 0f, y0 = 0f, z0 = -1f)
        val p1 = sim.addParticle(x0 = 1f, y0 = 0f, z0 = 0f)
        val p2 = sim.addParticle(x0 = 2f, y0 = 0f, z0 = 0f)

//        sim.addForceGenerator(
//            index = p1,
//            name = "pulse-up",
//            generator2 {
//                var elapsed = 0f
//                while (true) {
//                    val ctx = yield()
//                    elapsed += ctx.dt/5
//                    if (elapsed >= 1f) {
//                        ctx.state.ay[ctx.index] += 12500f
//                        println("pulse-up")
//                        elapsed -= 1f
//                    }
//                }
//            })

        sim.addForceGenerator(
            index = p0,
            name = "bh-separate",
            generator2 {
                while (true) {
                    val ctx = yield()
                    val tree = treePool.acquire()
                    tree.build(
                        x = ctx.state.x,
                        y = ctx.state.y,
                        z = ctx.state.z,
                        massIn = null,
                        invMass = ctx.state.invMass,
                        count = ctx.state.size
                    )
                    tree.applyForces(
                        x = ctx.state.x,
                        y = ctx.state.y,
                        z = ctx.state.z,
                        ax = ctx.state.ax,
                        ay = ctx.state.ay,
                        az = ctx.state.az,
                        massIn = null,
                        invMass = ctx.state.invMass,
                        count = ctx.state.size,
                        theta = 0.7f,
                        softening = 0.05f,
                        g = -1f
                    )
                    treePool.release(tree)
                }
            }
        )

//        sim.addPidPositionController(p0, "p", targetX = { 0.0f}, targetY = { 0.0f}, targetZ = { 0.0f}, kp = 100f, ki = 100f, integralLimit = 10000f, maxForce = Float.POSITIVE_INFINITY)

        constraints.setSlot(index = p0, slot = 0, neighbor = p1, restLength = 1f, stiffness = 1.9f)
        constraints.setSlot(index = p1, slot = 0, neighbor = p2, restLength = 1f, stiffness = 0.9f)

        val dt = 1f / 60f
        val damping = 0.995f
        val gravity = -0.0f

        fun dist(i: Int, j: Int): Float {
            val dx = state.x[j] - state.x[i]
            val dy = state.y[j] - state.y[i]
            val dz = state.z[j] - state.z[i]
            return sqrt(dx * dx + dy * dy + dz * dz)
        }

        repeat(1000) { step ->
            sim.step(
                dt = dt,
                damping = damping,
                gravity = gravity,
                iterations = 4
            )

            val n0 = constraints.n0
            val s0 = constraints.s0
            val d0 = if (n0[p0] >= 0 && s0[p0] != 0f) dist(p0, n0[p0]) else Float.NaN
            val d1 = if (n0[p1] >= 0 && s0[p1] != 0f) dist(p1, n0[p1]) else Float.NaN

            println(
                "step=$step p0=(${state.x[p0]}, ${state.y[p0]}, ${state.z[p0]}) " +
                        "p1=(${state.x[p1]}, ${state.y[p1]}, ${state.z[p1]}) " +
                        "p2=(${state.x[p2]}, ${state.y[p2]}, ${state.z[p2]}) " +
                        "d0=$d0 d1=$d1"
            )
        }
    }
}
