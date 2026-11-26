package afield.simulation.physics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The moving colliders, [StaircaseCollider], [Slinky], and the walks that `physics.md`'s
 * slinky section shows. The walk numbers come from the sweeps in
 * `dev_docs/physics/SLINKY_ESCALATOR.md`.
 */
class SlinkyTest {

    /** One particle resting on a floor that moves sideways picks up the floor's speed through friction. */
    @Test
    fun aMovingFloorCarriesWhatRestsOnIt() {
        val sim = SimdVerlet(SimdVerlet.State3.empty())
        val p = sim.addParticle(x0 = 0f, y0 = -0.01f)
        val floor = PlaneCollider(0f, -1f, 0f, offset = 0f).also {
            it.friction = 1f
            it.setVelocity(2f, 0f)
        }
        sim.addColliders(floor)
        val dt = 1f / 600f
        repeat(1200) { sim.step(dt, 1f, 50f, 0, constraints = null as SimdVerlet.DirectedConstraints3?) }
        val vx = (sim.state.x[p] - sim.state.px[p]) / dt
        assertEquals(2f, vx, 0.05f, "a particle on a conveyor moves with it")
        assertTrue(sim.state.x[p] > 3f, "and has been carried along, to x=${sim.state.x[p]}")
    }

    /** The same floor held still and moved by hand between frames pushes but never drags. */
    @Test
    fun aFloorMovedByHandDoesNotDrag() {
        val sim = SimdVerlet(SimdVerlet.State3.empty())
        val p = sim.addParticle(x0 = 0f, y0 = -0.01f)
        val floor = PlaneCollider(0f, -1f, 0f, offset = 0f).also { it.friction = 1f }
        sim.addColliders(floor)
        repeat(600) { sim.step(1f / 600f, 1f, 50f, 0, constraints = null as SimdVerlet.DirectedConstraints3?) }
        assertEquals(0f, sim.state.x[p], 1e-4f)
    }

    /** A rising staircase lifts what stands on a tread, and folds its origin back by whole steps. */
    @Test
    fun anEscalatorLiftsAndStaysNearTheOrigin() {
        val sim = SimdVerlet(SimdVerlet.State3.empty())
        val stairs = StaircaseCollider(run = 2f, rise = 1f).also {
            it.friction = 1f
            it.stepPeriod = 0.5f
        }
        sim.addColliders(stairs)
        val p = sim.addParticle(x0 = 1f, y0 = -0.01f)
        val dt = 1f / 600f
        repeat(600) { sim.step(dt, 1f, 50f, 0, constraints = null as SimdVerlet.DirectedConstraints3?) }
        // One second at two steps a second: up two rises and back two runs, riding the tread.
        assertEquals(-2f, sim.state.y[p], 0.05f)
        // x lags a little: Coulomb friction needs a few frames to bring the particle up to tread speed.
        assertEquals(-3f, sim.state.x[p], 0.2f)
        assertTrue(abs(stairs.originX) <= stairs.run, "origin folded back: ${stairs.originX}")
        assertEquals(stairs.speed, kotlin.math.hypot(2f, 1f) / 0.5f, 1e-4f)
    }

    /** A shut slinky stood on the floor stays standing and keeps its coils apart. */
    @Test
    fun aShutSlinkyStands() {
        val sim = SimdVerlet(SimdVerlet.State3.empty())
        val slinky = sim.addSlinky(coils = 40)
        val floor = sim.addColliders(PlaneCollider(0f, -1f, 0f, offset = 0f))
        slinky.restingOn = floor
        repeat(120) { slinky.step() }
        assertFalse(slinky.diverged)
        val top = -slinky.centre(39).y
        assertTrue(top in 0.7f..1.05f, "40 coils of pitch 0.025 stand about one diameter tall, got $top")
        assertTrue(abs(slinky.centroid().x) < 0.01f, "and do not wander: ${slinky.centroid().x}")
    }

    /**
     * Kinetic energy plus spring energy, with no gravity: rails and crossed diagonals, each
     * directed slot holding half of its symmetric pair.
     */
    private fun energy(slinky: Slinky, h: Float): Double {
        val st = slinky.sim.state
        val cs = slinky.constraints
        var e = 0.0
        for (i in slinky.base until slinky.end) {
            val m = 1.0 / st.invMass!![i]
            val vx = (st.x[i] - st.px[i]) / h; val vy = (st.y[i] - st.py[i]) / h; val vz = (st.z[i] - st.pz[i]) / h
            e += 0.5 * m * (vx * vx + vy * vy + vz * vz)
            for ((n, r, k) in listOf(Triple(cs.n0, cs.r0, cs.s0), Triple(cs.n1, cs.r1, cs.s1), Triple(cs.n2, cs.r2, cs.s2), Triple(cs.n3, cs.r3, cs.s3))) {
                val j = n[i]
                if (j < 0 || k[i] == 0f) continue
                val l = kotlin.math.sqrt((st.x[j] - st.x[i]).let { it * it } + (st.y[j] - st.y[i]).let { it * it } + (st.z[j] - st.z[i]).let { it * it })
                e += 0.25 * k[i] * (l - r[i]) * (l - r[i])
            }
        }
        return e
    }

    /**
     * The coil pass can only take energy out. A slinky in empty space, with no drag and no
     * internal damping, is opened into a stretched arc and let go, so that it snaps shut and its
     * coils slam into each other. From the first frame on its energy must never rise, and the
     * inelastic contacts must ring it down to almost nothing within two seconds. A contact that
     * pushed only along one coil's normal, without turning the coil, failed both: its energy
     * climbed 16% above the first frame and was still a fifth of it after two seconds.
     */
    @Test
    fun aSnappedSlinkyOnlyLosesEnergy() {
        val sim = SimdVerlet(SimdVerlet.State3.empty())
        val slinky = sim.addSlinky(coils = 40, stiffness = 32000f, preload = 0.5f)
        slinky.internalDamping = 0f
        val st = sim.state
        for (c in 0 until slinky.coils) {
            val angle = -1.25f + 2.5f * c / (slinky.coils - 1)
            slinky.moveHeld(c, 3f * kotlin.math.sin(angle), -3f * kotlin.math.cos(angle), angle)
            for (k in 0 until 2) {
                val i = slinky[c, k]
                st.px[i] = st.x[i]; st.py[i] = st.y[i]
            }
        }
        val h = 1f / 60f / slinky.substeps
        slinky.step(gravity = 0f)
        val start = energy(slinky, h)
        var most = start
        repeat(120) {
            slinky.step(gravity = 0f)
            most = maxOf(most, energy(slinky, h))
        }
        assertFalse(slinky.diverged)
        assertTrue(most <= start * 1.02, "energy rose from ${start.toInt()} to ${most.toInt()}")
        val end = energy(slinky, h)
        assertTrue(end < start * 0.05, "and rang down to ${end.toInt()} of ${start.toInt()}")
    }

    /**
     * The scene as shipped: the hand lifts the top coil over, lets go, and the slinky walks end
     * over end down the stairs on its own, two or three steps a flip, for the whole 20 seconds.
     */
    @Test
    fun theDefaultSlinkyWalksDownTheStairs() {
        val scene = SlinkyOnStairs()
        while (scene.time < 20f) scene.step()
        assertFalse(scene.slinky.diverged)
        assertTrue(scene.flips > 60, "flipped ${scene.flips} times")
        val perFlip = scene.stepsDescended / scene.flips
        assertTrue(perFlip in 1.5f..3.5f, "$perFlip steps a flip")
        assertTrue(scene.flipTimes.last() > 19f, "still walking at the end: last flip at ${scene.flipTimes.last()}")
    }

    /**
     * On long treads the walk is one step a flip, and it runs out: the fall from one step is not
     * quite enough to pay for the next flip, and the slinky comes to rest on a tread.
     */
    @Test
    fun onLongTreadsItWalksOneStepAtATimeAndStops() {
        val scene = SlinkyOnStairs(run = 2.1f, stiffness = 16000f)
        while (scene.time < 20f) scene.step()
        assertTrue(scene.flips in 8..40, "flipped ${scene.flips} times")
        assertEquals(1f, scene.stepsDescended / scene.flips, 0.15f, "one step a flip")
        assertTrue(scene.flipTimes.last() < 17f, "and stopped: last flip at ${scene.flipTimes.last()}")
    }

    /**
     * Slinky and escalator on the same rhythm: at 0.09 s a step the slinky walks down about as
     * fast as the treads carry it up, and over 30 s never strays more than fifteen steps from
     * where it was let go.
     */
    @Test
    fun onAMatchedEscalatorItStaysNearWhereItStarted() {
        val scene = SlinkyOnStairs(stepPeriod = 0.09f)
        var furthest = 0f
        while (scene.time < 30f) {
            scene.step()
            furthest = maxOf(furthest, abs(scene.stairsTravelled - scene.stepsDescended))
        }
        assertFalse(scene.slinky.diverged)
        assertTrue(scene.stepsDescended > 250f, "walked ${scene.stepsDescended} steps")
        assertTrue(furthest < 15f, "strayed $furthest steps")
    }
}
