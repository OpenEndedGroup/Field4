package afield.four.physics

import org.dyn4j.dynamics.Body
import org.dyn4j.dynamics.BodyFixture
import org.dyn4j.geometry.Circle
import org.dyn4j.geometry.Geometry
import org.dyn4j.geometry.MassType
import org.dyn4j.geometry.Vector2 as DynVec2
import kotlin.math.PI
import kotlin.math.abs

enum class CollisionPlane {
    XY
}

enum class DynGravityMode {
    ZERO,
    MIRROR_SIM
}

data class BulletSpec(
    val radius: Float = 0.05f,
    val friction: Double = 0.2,
    val restitution: Double = 0.0,
    val linearDamping: Double = 0.0,
    val angularDamping: Double = 0.0,
    val bullet: Boolean = true,
    val userData: Any? = null
)

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

    fun updateNomination(index: Int, spec: BulletSpec) {
        validateSpec(spec)
        val existing = nominations[index] ?: error("Index $index is not nominated")
        existing.spec = spec
        existing.cachedInvMass = null
    }

    fun unnominate(index: Int): Boolean {
        val removed = nominations.remove(index) ?: return false
        scene.world.removeBody(removed.body)
        return true
    }

    fun clearNominations() {
        val all = nominations.values.toList()
        nominations.clear()
        for (binding in all) {
            scene.world.removeBody(binding.body)
        }
    }

    fun nominatedIndices(): Set<Int> = nominations.keys.toSet()

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
