package afield.simulation.physics

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The moving colliders, [StaircaseCollider], [Slinky], and the escalator walk that
 * `physics.md`'s slinky section shows. The walk numbers come from the sweep in
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
     * The demo's numbers: at the default step period the slinky walks down the up escalator and
     * keeps pace. Over 20 s the escalator raises 34.8 steps; the slinky must have walked at least
     * 30 of them back down, so that it is never carried more than a few steps up.
     */
    @Test
    fun theDefaultWalkKeepsPaceWithTheEscalator() {
        val scene = SlinkyOnStairs()
        while (scene.time < 20f) scene.step()
        assertFalse(scene.slinky.diverged)
        val behind = scene.stairsTravelled - scene.stepsDescended
        assertTrue(scene.stepsDescended > 30f, "walked ${scene.stepsDescended} steps")
        assertTrue(behind in -1f..4f, "fell ${behind} steps behind the escalator")
    }

    /** With the same air and still stairs there is nothing feeding the walk, and it stops. */
    @Test
    fun onStillStairsTheSameSlinkyStops() {
        val scene = SlinkyOnStairs(stepPeriod = 0f)
        while (scene.time < 10f) scene.step()
        assertTrue(scene.stepsDescended < 2f, "walked ${scene.stepsDescended} steps")
    }
}
