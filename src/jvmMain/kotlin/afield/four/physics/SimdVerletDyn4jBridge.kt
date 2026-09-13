package afield.four.physics

import org.dyn4j.dynamics.Body
import org.dyn4j.dynamics.BodyFixture
import org.dyn4j.geometry.Circle
import org.dyn4j.geometry.Geometry
import org.dyn4j.geometry.MassType
import org.dyn4j.geometry.Vector2 as DynVec2
import kotlin.math.PI
import kotlin.math.abs

/**
 * Runs a [SimdVerlet] particle simulation and a [Dyn4jScene] in one frame, so that chosen
 * particles collide with rigid bodies as circles while the rest stay ordinary particles.
 *
 * The coupling is by nomination. [nominate] takes a particle index and gives it a circle body in
 * the scene's dyn4j world; every other particle is untouched and costs nothing. Then each [step]
 * advances the particle simulation, copies the nominated particles into their bodies, advances
 * dyn4j, and copies the bodies back. A nominated particle is still a Verlet particle that
 * constraints and force generators pull on; it spends the end of every frame being a rigid body
 * as well.
 *
 * #### The frame, and what it leaves out
 *
 * [step] does not call [SimdVerlet.step]. It runs its own copy of that frame, because it has to
 * decide per particle whether gravity applies. In order: queued removals, zero the accumulators,
 * gravity, the per-particle force generators, the constraint solve, Verlet integration, then the
 * dyn4j half.
 *
 * What the copy leaves out is what this class does not support. There is no
 * [SimdVerlet.bulkForces] pass, no `accumulateForces` hook, and neither post-integration stage
 * runs, so a `ColliderSet` in [SimdVerlet.positionProjections] or a [Tearing] in
 * [SimdVerlet.constraintEdits] is silent under [step]. It also solves only
 * [SimdVerlet.DirectedConstraints3], while [Cloth] and [Strand] build
 * [SimdVerlet.DirectedConstraints4]: step a cloth through this bridge and its constraints go
 * unsolved, because the default `constraints = sim.constraints` is null when the fabric is in
 * `constraints4`. So this is a bridge for loose particles rather than for fabric.
 *
 * #### Positions and velocities, both ways
 *
 * A Verlet particle carries its velocity as the gap between `x` and `px`; a dyn4j body carries
 * one directly, in units per second. So the push sets the body to the particle's *previous*
 * position with `(x - px) / dt` as its velocity and lets dyn4j integrate that forward over the
 * same `dt`: the dyn4j step redoes the frame's last integration, this time with contacts in it.
 * The pull then writes `x` from the body's centre and rebuilds `px` as `centre - velocity * dt`,
 * so the particle's history after a frame comes from the body rather than surviving the hop.
 *
 * Only `x`, `y`, `px` and `py` are written. [CollisionPlane] has one case and the constructor
 * requires it: this is the simulation's x/y plane, and `z` is left alone.
 *
 * #### Gravity, counted once
 *
 * [DynGravityMode.MIRROR_SIM] copies [step]'s `gravity` argument into the dyn4j world and
 * withholds it from the nominated particles in the Verlet pass, so dyn4j is the only thing
 * accelerating them. [DynGravityMode.ZERO] does the reverse: the dyn4j world gets none, and every
 * particle, nominated or not, gets the simulation's. Either way a particle falls once per frame.
 *
 * The two are not the same fall. What the Verlet pass adds `gravity` to is the `ay` accumulator,
 * which is a *force* whenever the state has an `invMass` array, as `SimdVerlet.State3.empty`
 * allocates by default, so a heavy particle falls more slowly in the simulation. A dyn4j world
 * gravity is an acceleration, which every body there feels equally. They agree at inverse mass 1.
 *
 * #### Mass, radius, removal
 *
 * Each frame the body's density is set so its mass is the particle's `1 / invMass` spread over
 * the circle's area, and an inverse mass of zero or less becomes `MassType.INFINITE`, the same
 * convention that pins a particle in the solver. That conversion is cached and redone only when
 * the particle's inverse mass changes.
 *
 * Changing [BulletSpec.radius] destroys the body and builds a new one, at the next [step] rather
 * than when the spec changes, because the fixture's circle is not resized in place. Friction and
 * restitution are written to the existing fixture instead. [unnominate] and [clearNominations]
 * take the bodies back out of the world.
 *
 * Nominations are keyed by particle index, and `SimdVerlet`'s removal is a swap compaction: a
 * deleted slot is filled from the tail. Each frame drops the nominations whose index has fallen
 * out of range, which is as much as an index can tell it — a nomination whose index survives a
 * removal now names whichever particle was swapped into that slot. Re-nominate after removing
 * particles.
 *
 * Nothing in the repository constructs one of these and no test covers it. It is a surface for
 * box code to call, and there is no 3D equivalent: [JoltPhysicsWorld] is a rigid-body world of
 * its own, with nothing wiring it to a particle simulation.
 */

/** Which plane of the simulation the rigid-body scene lives in. Only `XY` is implemented. */
enum class CollisionPlane {
    XY
}

/**
 * Which of the two engines accelerates a nominated particle, so that gravity is applied once
 * rather than twice or not at all. See the file overview above for the difference between the
 * two falls.
 */
enum class DynGravityMode {
    ZERO,
    MIRROR_SIM
}

/**
 * The rigid body a nominated particle becomes: a circle of [radius], with dyn4j's contact
 * parameters.
 *
 * Mass is not here. It is read from the particle's inverse mass every frame, and [radius] only
 * sets the area that mass is spread over. `bullet` is dyn4j's flag for a body fast enough to need
 * its continuous-collision check; it defaults to `true` here, against `false` in [DynBodyDef].
 */
data class BulletSpec(
    val radius: Float = 0.05f,
    val friction: Double = 0.2,
    val restitution: Double = 0.0,
    val linearDamping: Double = 0.0,
    val angularDamping: Double = 0.0,
    val bullet: Boolean = true,
    val userData: Any? = null
)

/**
 * One particle simulation, one rigid-body scene, and the nomination table joining them. The file
 * overview above has the frame in order and what it leaves out.
 *
 * Both halves stay usable on their own: [sim] can be read and edited as an ordinary [SimdVerlet]
 * and [scene] can hold as many static and dynamic bodies as it likes, added through
 * [Dyn4jScene.addBody] rather than through here. What this class owns is only the bodies standing
 * in for nominated particles.
 */
class SimdVerletDyn4jBridge(
    val sim: SimdVerlet,
    val scene: Dyn4jScene,
    val plane: CollisionPlane = CollisionPlane.XY,
    val gravityMode: DynGravityMode = DynGravityMode.MIRROR_SIM,
    val defaultSpec: BulletSpec = BulletSpec()
) {
    private data class BulletBinding(
        var spec: BulletSpec,
        var body: Body,
        var fixture: BodyFixture,
        var cachedInvMass: Float? = null
    )

    private val nominations = linkedMapOf<Int, BulletBinding>()

    init {
        require(plane == CollisionPlane.XY) { "Only XY collision plane is implemented in v1" }
    }

    /**
     * Gives the particle at [index] a circle rigid body, so that it collides with the scene.
     *
     * The body joins the dyn4j world immediately and is placed on the particle by the next
     * [step], so it is that step which first has it in the right place.
     *
     * Nominating an index that is already nominated replaces its [BulletSpec], which is what
     * [updateNomination] does with the stricter requirement that the index be nominated already.
     * The particle keeps its position, its constraints and its force generators either way.
     *
     * @throws IllegalArgumentException if [index] is not a live particle, or [spec] asks for a
     *   radius of zero or less.
     */
    fun nominate(index: Int, spec: BulletSpec = defaultSpec) {
        require(index in 0 until sim.state.size) { "Particle index out of bounds: $index" }
        validateSpec(spec)

        val existing = nominations[index]
        if (existing != null) {
            existing.spec = spec
            existing.cachedInvMass = null
            return
        }

        val (body, fixture) = createBulletBody(spec)
        nominations[index] = BulletBinding(spec = spec, body = body, fixture = fixture)
    }

    /**
     * Replaces the [BulletSpec] of an already-nominated [index], keeping its body where possible.
     *
     * A changed radius is applied by rebuilding the body at the next [step]; friction and
     * restitution reach the existing fixture.
     *
     * @throws IllegalStateException if [index] is not nominated. Use [nominate] to say "either
     *   way".
     */
    fun updateNomination(index: Int, spec: BulletSpec) {
        validateSpec(spec)
        val existing = nominations[index] ?: error("Index $index is not nominated")
        existing.spec = spec
        existing.cachedInvMass = null
    }

    /**
     * Takes the particle at [index] back to being an ordinary particle, removing its body from
     * the dyn4j world. `false` if it was not nominated.
     *
     * The particle's own position and velocity are whatever the last [step] left, which is the
     * body's, so it carries on from where the rigid body stopped.
     */
    fun unnominate(index: Int): Boolean {
        val removed = nominations.remove(index) ?: return false
        scene.world.removeBody(removed.body)
        return true
    }

    /**
     * Unnominates every particle, removing all of this bridge's bodies from the dyn4j world.
     *
     * Bodies added to [scene] directly are left where they are; this only clears what nomination
     * put there.
     */
    fun clearNominations() {
        val all = nominations.values.toList()
        nominations.clear()
        for (binding in all) {
            scene.world.removeBody(binding.body)
        }
    }

    /**
     * The currently nominated particle indices, as a copy, so it is safe to iterate while
     * calling [nominate] or [unnominate].
     */
    fun nominatedIndices(): Set<Int> = nominations.keys.toSet()

    /**
     * Advances both engines by [dt] and reconciles them: a Verlet frame, then the dyn4j world,
     * then the nominated particles written back from their bodies.
     *
     * This is not [SimdVerlet.step] with something added. It re-runs an abbreviated version of
     * that frame, and the file overview above lists the stages it does not have, of which the
     * post-integration ones — colliders and tearing — are the two most likely to be missed.
     *
     * @param gravity a per-particle force when the state has an `invMass` array, as it does by
     *   default. Under [DynGravityMode.MIRROR_SIM] the same number becomes the dyn4j world's
     *   gravity, which is an acceleration there.
     * @param constraints the 3-slot set to solve. The default is [SimdVerlet.constraints], which
     *   is null for a simulation whose fabric was built by [Cloth] or [Strand] into
     *   `constraints4`; passing null solves nothing.
     * @throws IllegalArgumentException if [dt] is not positive.
     */
    fun step(
        dt: Float,
        damping: Float,
        gravity: Float,
        iterations: Int,
        constraints: SimdVerlet.DirectedConstraints3? = sim.constraints
    ) {
        require(dt > 0f) { "dt must be > 0" }
        runSimdStep(
            dt = dt,
            damping = damping,
            gravity = gravity,
            iterations = iterations,
            constraints = constraints
        )

        configureWorldGravity(gravity)
        pushNominatedParticlesToDyn4j(dt)
        scene.step(dt.toDouble())
        pullNominatedParticlesFromDyn4j(dt)
    }

    private fun runSimdStep(
        dt: Float,
        damping: Float,
        gravity: Float,
        iterations: Int,
        constraints: SimdVerlet.DirectedConstraints3?
    ) {
        val state = sim.state
        state.applyQueuedRemovalsSwap(constraints)
        pruneInvalidNominations(state.size)

        SimdVerlet.zero3(state)

        if (gravity != 0f) {
            val ay = state.ay
            val size = state.size
            val selectiveGravity = gravityMode == DynGravityMode.MIRROR_SIM && nominations.isNotEmpty()
            if (!selectiveGravity) {
                for (i in 0 until size) {
                    ay[i] += gravity
                }
            } else {
                for (i in 0 until size) {
                    if (!nominations.containsKey(i)) {
                        ay[i] += gravity
                    }
                }
            }
        }

        val forceGenerators = state.forceGenerators
        val forceContexts = state.forceContexts
        for (i in 0 until state.size) {
            val ctx = forceContexts[i]
            ctx.index = i
            ctx.dt = dt
            forceGenerators[i].updateNoCollect(ctx)
        }

        if (constraints != null && iterations > 0) {
            SimdVerlet.solveDirectedDist3InPlace(
                state = state,
                constraints = constraints,
                iterations = iterations
            )
        }

        SimdVerlet.integrateVerlet3(state, dt = dt, damping = damping)
    }

    private fun configureWorldGravity(simGravity: Float) {
        when (gravityMode) {
            DynGravityMode.ZERO -> {
                scene.world.gravity = DynVec2(0.0, 0.0)
            }

            DynGravityMode.MIRROR_SIM -> {
                scene.world.gravity = DynVec2(0.0, simGravity.toDouble())
            }
        }
    }

    private fun pushNominatedParticlesToDyn4j(dt: Float) {
        val state = sim.state
        for ((index, binding) in nominations) {
            if (index !in 0 until state.size) continue

            val body = ensureBodyForSpec(binding)
            updateMassFromInvMass(index, binding)

            val px = state.px[index].toDouble()
            val py = state.py[index].toDouble()
            body.transform.setTranslation(px, py)
            body.transform.setRotation(0.0)

            val vx = ((state.x[index] - state.px[index]) / dt).toDouble()
            val vy = ((state.y[index] - state.py[index]) / dt).toDouble()
            body.setLinearVelocity(vx, vy)
            body.angularVelocity = 0.0
            body.linearDamping = binding.spec.linearDamping
            body.angularDamping = binding.spec.angularDamping
            body.isBullet = binding.spec.bullet
            body.userData = binding.spec.userData
            body.setAtRest(false)
        }
    }

    private fun pullNominatedParticlesFromDyn4j(dt: Float) {
        val state = sim.state
        val dtDouble = dt.toDouble()
        for ((index, binding) in nominations) {
            if (index !in 0 until state.size) continue

            val center = binding.body.worldCenter
            val velocity = binding.body.linearVelocity

            state.x[index] = center.x.toFloat()
            state.y[index] = center.y.toFloat()
            state.px[index] = (center.x - velocity.x * dtDouble).toFloat()
            state.py[index] = (center.y - velocity.y * dtDouble).toFloat()
        }
    }

    private fun ensureBodyForSpec(binding: BulletBinding): Body {
        val body = binding.body
        val currentRadius = (binding.fixture.shape as Circle).radius
        val requestedRadius = binding.spec.radius.toDouble()

        if (abs(currentRadius - requestedRadius) > 1.0e-9) {
            scene.world.removeBody(binding.body)
            val (newBody, newFixture) = createBulletBody(binding.spec)
            binding.body = newBody
            binding.fixture = newFixture
            binding.cachedInvMass = null
        } else {
            binding.fixture.friction = binding.spec.friction
            binding.fixture.restitution = binding.spec.restitution
        }

        return binding.body
    }

    private fun updateMassFromInvMass(index: Int, binding: BulletBinding) {
        val invMass = sim.state.invMass?.get(index) ?: 1f
        if (binding.cachedInvMass == invMass) return

        if (invMass <= 0f) {
            binding.body.setMass(MassType.INFINITE)
            binding.cachedInvMass = invMass
            return
        }

        val radius = binding.spec.radius.toDouble()
        val area = PI * radius * radius
        val mass = 1.0 / invMass.toDouble()
        val density = (mass / area).coerceAtLeast(1.0e-9)

        binding.fixture.density = density
        binding.body.updateMass()
        binding.body.setMassType(MassType.NORMAL)
        binding.cachedInvMass = invMass
    }

    private fun createBulletBody(spec: BulletSpec): Pair<Body, BodyFixture> {
        val body = Body()
        val fixture = body.addFixture(Geometry.createCircle(spec.radius.toDouble()))
        fixture.friction = spec.friction
        fixture.restitution = spec.restitution
        fixture.density = 1.0

        body.linearDamping = spec.linearDamping
        body.angularDamping = spec.angularDamping
        body.isBullet = spec.bullet
        body.userData = spec.userData
        body.setMass(MassType.NORMAL)
        scene.world.addBody(body)
        return body to fixture
    }

    private fun pruneInvalidNominations(size: Int) {
        val it = nominations.iterator()
        while (it.hasNext()) {
            val (index, binding) = it.next()
            if (index < 0 || index >= size) {
                scene.world.removeBody(binding.body)
                it.remove()
            }
        }
    }

    private fun validateSpec(spec: BulletSpec) {
        require(spec.radius > 0f) { "Bullet radius must be > 0" }
    }
}
