package afield.four.physics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the collider family — the post-integration position projection that gives [SimdVerlet] a
 * world to hit.
 *
 * Every test here that claims a physical outcome states its own premise first. A "the cloth is
 * above the sphere" assertion is worthless unless the same cloth *without* the collider is
 * measured going through it, because a cloth that never fell, a gravity that was zero and a
 * simulation that diverged to NaN all satisfy "above the sphere" too. So the counterfactual is
 * measured, printed and floored at a number, and only then is the collided run checked.
 */
class SimdVerletCollidersTest {

    private fun sim(): SimdVerlet = SimdVerlet(SimdVerlet.State3.empty())

    /** A deterministic scatter of particles with non-zero velocities, for the path-agreement test. */
    private fun scatter(count: Int, seed: Long): SimdVerlet.State3 {
        val state = SimdVerlet.State3.empty(count)
        val rng = java.util.Random(seed)
        repeat(count) {
            val x = (rng.nextFloat() - 0.5f) * 6f
            val y = (rng.nextFloat() - 0.5f) * 6f
            val z = (rng.nextFloat() - 0.5f) * 6f
            state.addParticle(
                x0 = x, y0 = y, z0 = z,
                px0 = x - (rng.nextFloat() - 0.5f) * 0.4f,
                py0 = y - (rng.nextFloat() - 0.5f) * 0.4f,
                pz0 = z - (rng.nextFloat() - 0.5f) * 0.4f
            )
        }
        return state
    }

    private fun copyOf(state: SimdVerlet.State3): SimdVerlet.State3 {
        val out = SimdVerlet.State3.empty(state.size)
        for (i in 0 until state.size) {
            out.addParticle(
                x0 = state.x[i], y0 = state.y[i], z0 = state.z[i],
                px0 = state.px[i], py0 = state.py[i], pz0 = state.pz[i],
                invMass0 = state.invMass?.get(i) ?: 1f
            )
        }
        return out
    }

    // ------------------------------------------------------------------------------------------
    // The two code paths
    // ------------------------------------------------------------------------------------------

    /**
     * The vector and scalar projections must agree. Two premises are asserted first: that this
     * machine actually has more than one lane (otherwise the "vector" path *is* the scalar path
     * and the test is a tautology), and that the particle count leaves a remainder, so the scalar
     * tail inside the vector path is exercised too.
     */
    @Test
    fun vectorAndScalarPathsAgree() {
        val lanes = Collider.laneCount
        assertTrue(lanes > 1, "premise: the vector path must have >1 lane to be worth comparing, got $lanes")
        val count = lanes * 7 + 3
        assertTrue(count % lanes != 0, "premise: the range must leave a scalar remainder, $count % $lanes == 0")

        for (case in listOf("plane", "sphere", "sphere-inside")) {
            val a = scatter(count, 12345L)
            val b = copyOf(a)
            // A few pins, so the invMass gate is exercised in both paths.
            for (i in 0 until count step 5) {
                a.invMass!![i] = 0f
                b.invMass!![i] = 0f
            }

            fun make(): Collider = when (case) {
                "plane" -> PlaneCollider(0.2f, 1f, -0.1f, offset = -0.5f)
                "sphere" -> SphereCollider(0.3f, -0.2f, 0.1f, radius = 2.2f)
                else -> SphereCollider(0.3f, -0.2f, 0.1f, radius = 2.0f, inside = true)
            }

            val fast = make().also { it.friction = 0.4f; it.restitution = 0.3f; it.margin = 0.05f }
            val slow = make().also {
                it.friction = 0.4f; it.restitution = 0.3f; it.margin = 0.05f; it.vectorized = false
            }
            val hitFast = fast.projectRange(a, 0, count)
            val hitSlow = slow.projectRange(b, 0, count)

            assertTrue(hitFast > lanes, "premise ($case): the scatter must actually hit, got $hitFast contacts")
            assertEquals(hitSlow, hitFast, "$case: contact counts must match")
            for (i in 0 until count) {
                assertEquals(b.x[i], a.x[i], 2e-5f, "$case: x[$i]")
                assertEquals(b.y[i], a.y[i], 2e-5f, "$case: y[$i]")
                assertEquals(b.z[i], a.z[i], 2e-5f, "$case: z[$i]")
                assertEquals(b.px[i], a.px[i], 2e-5f, "$case: px[$i]")
                assertEquals(b.py[i], a.py[i], 2e-5f, "$case: py[$i]")
                assertEquals(b.pz[i], a.pz[i], 2e-5f, "$case: pz[$i]")
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // The shapes
    // ------------------------------------------------------------------------------------------

    @Test
    fun aPlaneStopsAFall() {
        val free = sim()
        free.addParticle(x0 = 0f, y0 = 2f)
        repeat(200) { free.step(dt = 1f / 60f, damping = 0.99f, gravity = -9.8f, iterations = 0) }
        val fell = free.state.y[0]
        assertTrue(fell < -3f, "premise: with no floor the particle must fall well below it, got $fell")

        val s = sim()
        s.addParticle(x0 = 0f, y0 = 2f)
        s.addColliders(PlaneCollider(0f, 1f, 0f, offset = 0f))
        repeat(200) { s.step(dt = 1f / 60f, damping = 0.99f, gravity = -9.8f, iterations = 0) }
        val rest = s.state.y[0]
        assertTrue(rest >= -1e-4f, "the floor must not be penetrated, got y = $rest")
        assertTrue(rest < 1e-3f, "the particle must reach the floor, got y = $rest")
    }

    @Test
    fun marginHoldsTheParticleOffTheSurface() {
        val s = sim()
        s.addParticle(x0 = 0f, y0 = 3f)
        s.addColliders(SphereCollider(0f, 0f, 0f, radius = 1f).also { it.margin = 0.25f })
        repeat(300) { s.step(dt = 1f / 60f, damping = 0.98f, gravity = -9.8f, iterations = 0) }
        val d = sqrt(s.state.x[0] * s.state.x[0] + s.state.y[0] * s.state.y[0] + s.state.z[0] * s.state.z[0])
        assertTrue(d >= 1.25f - 1e-3f, "margin must be honoured: centre distance $d, wanted >= 1.25")
    }

    @Test
    fun pinnedParticlesAreNeverProjected() {
        val s = sim()
        val pinned = s.addParticle(x0 = 0f, y0 = -5f)
        val free = s.addParticle(x0 = 1f, y0 = -5f)
        s.state.invMass!![pinned] = 0f
        val floor = PlaneCollider(0f, 1f, 0f, offset = 0f)
        s.addColliders(floor)
        s.step(dt = 1f / 60f, damping = 0.99f, gravity = 0f, iterations = 0)
        assertEquals(-5f, s.state.y[pinned], 1e-6f, "a pinned particle must be left where it is")
        assertTrue(s.state.y[free] >= -1e-5f, "the free one must be projected, got ${s.state.y[free]}")
        assertEquals(1, floor.contacts, "only the free particle is a contact")
    }

    @Test
    fun restitutionBouncesAndZeroDoesNot() {
        fun drop(restitution: Float): Float {
            val s = sim()
            s.addParticle(x0 = 0f, y0 = 3f)
            s.addColliders(PlaneCollider(0f, 1f, 0f, offset = 0f).also { it.restitution = restitution })
            var peakAfterFirstContact = -1f
            var touched = false
            repeat(400) {
                s.step(dt = 1f / 60f, damping = 1f, gravity = -9.8f, iterations = 0)
                if (s.state.y[0] < 1e-4f) touched = true
                if (touched && s.state.y[0] > peakAfterFirstContact) peakAfterFirstContact = s.state.y[0]
            }
            assertTrue(touched, "premise: the particle must reach the floor at all")
            return peakAfterFirstContact
        }

        val dead = drop(0f)
        val lively = drop(0.9f)
        println("restitution 0.0 -> rebound peak %.4f ; restitution 0.9 -> rebound peak %.4f".format(dead, lively))
        assertTrue(dead < 0.02f, "a dead surface must not bounce, rebound peak $dead")
        assertTrue(lively > 1.5f, "restitution 0.9 must return most of a 3-unit drop, rebound peak $lively")
    }

    @Test
    fun aBoxKeepsParticlesIn() {
        val s = sim()
        val rng = java.util.Random(7L)
        repeat(64) {
            val x = (rng.nextFloat() - 0.5f) * 1.5f
            val y = (rng.nextFloat() - 0.5f) * 1.5f
            s.addParticle(x0 = x, y0 = y, px0 = x - 0.2f, py0 = y - 0.15f)
        }
        s.addColliders(BoxCollider(-1f, -1f, -1f, 1f, 1f, 1f, inside = true))
        repeat(400) { s.step(dt = 1f / 60f, damping = 0.999f, gravity = -9.8f, iterations = 0) }
        for (i in 0 until s.size) {
            assertTrue(s.state.x[i] >= -1f - 1e-4f && s.state.x[i] <= 1f + 1e-4f, "x[$i] = ${s.state.x[i]}")
            assertTrue(s.state.y[i] >= -1f - 1e-4f && s.state.y[i] <= 1f + 1e-4f, "y[$i] = ${s.state.y[i]}")
            assertTrue(s.state.z[i] >= -1f - 1e-4f && s.state.z[i] <= 1f + 1e-4f, "z[$i] = ${s.state.z[i]}")
        }
    }

    @Test
    fun aCapsuleIsASphereSweptAlongASegment() {
        val capsule = CapsuleCollider(-2f, 0f, 0f, 2f, 0f, 0f, radius = 0.5f)
        val state = SimdVerlet.State3.empty()
        // On the shaft, past the cap, and beyond the end entirely.
        state.addParticle(x0 = 0f, y0 = 0.1f)
        state.addParticle(x0 = 2.2f, y0 = 0.1f)
        state.addParticle(x0 = 4f, y0 = 0f)
        val hit = capsule.project(state)
        assertEquals(2, hit, "the shaft point and the cap point collide, the far one does not")
        assertEquals(0.5f, state.y[0], 1e-5f, "pushed straight off the shaft")
        val capDist = sqrt((state.x[1] - 2f) * (state.x[1] - 2f) + state.y[1] * state.y[1])
        assertEquals(0.5f, capDist, 1e-5f, "pushed onto the spherical cap")
        assertEquals(4f, state.x[2], 1e-6f, "the far particle is untouched")
    }

    @Test
    fun anSdfColliderConvergesOnAFieldWithNonUnitGradient() {
        // Half a distance field: zero on the unit sphere, but reporting half the true distance, so
        // one correction lands halfway and [SdfCollider.maxSteps] is what closes the rest. A field
        // like this is what you get from any implicit surface nobody bothered to normalise.
        val halfDistance = { x: Float, y: Float, z: Float -> 0.5f * (sqrt(x * x + y * y + z * z) - 1f) }
        fun run(steps: Int): Float {
            val state = SimdVerlet.State3.empty()
            state.addParticle(x0 = 0.2f, y0 = 0f, z0 = 0f)
            SdfCollider(halfDistance, maxSteps = steps).project(state)
            return sqrt(state.x[0] * state.x[0] + state.y[0] * state.y[0] + state.z[0] * state.z[0])
        }
        val one = run(1)
        val many = run(12)
        println("sdf 0.5*(r-1): radius after 1 step %.4f, after 12 steps %.4f".format(one, many))
        assertTrue(one < 0.7f, "premise: one step on a half-scale field must stop short, got $one")
        assertTrue(abs(many - 1f) < 0.02f, "twelve steps must reach the surface, got $many")

        // And a well-formed field needs no iteration at all: a torus, hit once.
        val torus = { x: Float, y: Float, z: Float ->
            val q = sqrt(x * x + z * z) - 1.5f
            sqrt(q * q + y * y) - 0.4f
        }
        val state = SimdVerlet.State3.empty()
        state.addParticle(x0 = 1.5f, y0 = 0.1f, z0 = 0f)
        val hits = SdfCollider(torus, maxSteps = 1).project(state)
        assertEquals(1, hits, "premise: the particle starts inside the tube")
        assertTrue(torus(state.x[0], state.y[0], state.z[0]) > -1e-3f, "one step must clear a unit-gradient field")
    }

    @Test
    fun anOutlineIsDrawableForEveryShape() {
        val shapes = listOf(
            PlaneCollider(0f, 1f, 0f, -1f),
            SphereCollider(0f, 0f, 0f, 1f),
            CapsuleCollider(-1f, 0f, 0f, 1f, 0f, 0f, 0.4f),
            BoxCollider(-1f, -1f, -1f, 1f, 1f, 1f)
        )
        for (shape in shapes) {
            val lines = shape.outline()
            assertTrue(lines.isNotEmpty(), "${shape::class.simpleName} must draw something")
            val nodes = lines.sumOf { it.nodes.size }
            assertTrue(nodes >= 2, "${shape::class.simpleName} outline has $nodes nodes")
            for (line in lines) {
                for (node in line.nodes) {
                    assertTrue(node.to.x.isFinite() && node.to.y.isFinite(), "outline must be finite")
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // The reason this is a projection
    // ------------------------------------------------------------------------------------------

    /**
     * The measurement behind the design. A penalty-force collider is registered in
     * [SimdVerlet.bulkForces] and therefore runs *once* per step, while the cloth springs it must
     * overpower run `iterations` times into the same accumulator — so the same collider stiffness
     * that holds a sheet at `iterations = 1` lets it through at `iterations = 16`, and the
     * stiffness that holds it at 16 diverges. The projection is unaffected by either.
     *
     * Printed as a table, and floored: the numbers are the argument in the class KDoc and in
     * `dev_docs/physics/SIMDVERLET_5_WORLD.md`, so they have to fail loudly if they move.
     */
    @Test
    fun penaltyForceCollidersInheritTheIterationScaleTrap() {
        val sphereY = -1.5f
        val radius = 1.2f

        /** Deepest penetration of the sphere over the run; negative means never touched. */
        fun run(iterations: Int, penaltyStiffness: Float?): Float {
            val s = sim()
            val cloth = s.addCloth(
                width = 12, height = 12, spacing = 0.35f, stiffness = 60f, curvatureStrength = 0f,
                position = { c, r -> afield.stdlib.vec((c - 5.5f) * 0.35f, 1.2f, (r - 5.5f) * 0.35f) },
                pinned = { _, _ -> false }
            )
            if (penaltyStiffness != null) {
                s.addBulkForce("penalty") { state, _ ->
                    for (i in 0 until state.size) {
                        val dx = state.x[i]
                        val dy = state.y[i] - sphereY
                        val dz = state.z[i]
                        val d = sqrt(dx * dx + dy * dy + dz * dz)
                        if (d < radius && d > 1e-6f) {
                            val push = penaltyStiffness * (radius - d) / d
                            state.ax[i] += dx * push
                            state.ay[i] += dy * push
                            state.az[i] += dz * push
                        }
                    }
                }
            } else {
                s.addColliders(SphereCollider(0f, sphereY, 0f, radius).also { it.friction = 0.4f })
            }
            var deepest = -1f
            repeat(400) {
                cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = -9.8f, iterations = iterations)
                for (i in cloth.base until cloth.end) {
                    val dx = s.state.x[i]
                    val dy = s.state.y[i] - sphereY
                    val dz = s.state.z[i]
                    val d = sqrt(dx * dx + dy * dy + dz * dz)
                    if (!d.isFinite()) return Float.NaN
                    val pen = radius - d
                    if (pen > deepest) deepest = pen
                }
            }
            return deepest
        }

        val stiffnesses = listOf(400f, 1600f, 6400f, 25600f, 102400f)
        val at1 = stiffnesses.map { run(1, it) }
        val at16 = stiffnesses.map { run(16, it) }
        val projected1 = run(1, null)
        val projected16 = run(16, null)

        println("deepest penetration of the sphere, 12 x 12 sheet dropped on it, 400 frames")
        println("  penalty k    iterations=1    iterations=16")
        for (n in stiffnesses.indices) {
            println("  %9.0f    %12s    %13s".format(stiffnesses[n], fmt(at1[n]), fmt(at16[n])))
        }
        println("  projection   %12s    %13s".format(fmt(projected1), fmt(projected16)))

        // Premise: the penalty collider must at least half-work somewhere in the sweep, or what is
        // being demonstrated is "this one was written badly" rather than the scale trap.
        val bestAt1Index = stiffnesses.indices.minByOrNull { if (at1[it].isFinite()) at1[it] else Float.MAX_VALUE }!!
        val bestAt1 = at1[bestAt1Index]
        assertTrue(
            bestAt1.isFinite() && bestAt1 < 0.15f,
            "premise: the best penalty stiffness must hold the sheet roughly in place at 1 iteration, got ${fmt(bestAt1)}"
        )

        // Jaw one. The same k, with nothing changed but the iteration count of a solve that has
        // nothing to do with the collider, leaks appreciably more.
        val sameKAt16 = at16[bestAt1Index]
        assertTrue(
            !sameKAt16.isFinite() || sameKAt16 > bestAt1 * 1.5f,
            "k=${stiffnesses[bestAt1Index]} must degrade at 16 iterations: ${fmt(bestAt1)} -> ${fmt(sameKAt16)}"
        )

        // Jaw two. Stiffening past the optimum makes it *worse*, not better -- there is no
        // "just turn it up" available, at either iteration count.
        assertTrue(
            !at1.last().isFinite() || at1.last() > bestAt1 * 3f,
            "the stiffest penalty must be far worse than the optimum at 1 iteration: ${fmt(bestAt1)} vs ${fmt(at1.last())}"
        )
        assertTrue(
            !at16.last().isFinite() || at16.last() > at16[bestAt1Index] * 3f,
            "and at 16: ${fmt(at16[bestAt1Index])} vs ${fmt(at16.last())}"
        )

        // And the headline: over a 256x sweep of stiffness and both iteration counts, no penalty
        // setting comes within two orders of magnitude of the projection.
        assertTrue(projected1 < 1e-3f, "the projection must hold at 1 iteration, got ${fmt(projected1)}")
        assertTrue(projected16 < 1e-3f, "the projection must hold at 16 iterations, got ${fmt(projected16)}")
        val bestPenalty = (at1 + at16).filter { it.isFinite() }.min()
        val bestProjection = max(projected1, projected16)
        println("best penalty over the whole sweep %s; worst projection %s".format(fmt(bestPenalty), fmt(bestProjection)))
        assertTrue(
            bestPenalty > 90f * max(bestProjection, 1e-4f),
            "the projection must beat every penalty setting by ~2 orders: ${fmt(bestPenalty)} vs ${fmt(bestProjection)}"
        )
    }

    private fun fmt(v: Float): String = if (v.isNaN()) "diverged" else "%.4f".format(v)

    // ------------------------------------------------------------------------------------------
    // The thing the whole layer is for
    // ------------------------------------------------------------------------------------------

    @Test
    fun aClothDrapesOverASphereInsteadOfThroughIt() {
        val sphereY = -1.4f
        val radius = 1.1f

        fun run(withCollider: Boolean, friction: Float = 0.6f): Triple<Float, Float, SimdVerlet> {
            val s = sim()
            val cloth = s.addCloth(
                width = 16, height = 16, spacing = 0.28f, stiffness = 60f, curvatureStrength = 0f,
                position = { c, r -> afield.stdlib.vec((c - 7.5f) * 0.28f, 0.9f, (r - 7.5f) * 0.28f) },
                pinned = { _, _ -> false }
            )
            if (withCollider) {
                s.addColliders(
                    SphereCollider(0f, sphereY, 0f, radius).also { it.friction = friction },
                    PlaneCollider(0f, 1f, 0f, offset = -3f).also { it.friction = 0.9f }
                )
            }
            // Deepest over the whole run, not at the end: a sheet with no collider is long gone by
            // frame 500, and measuring only the final frame would report "never near the sphere"
            // for the very case that goes straight through it.
            var deepest = -10f
            var lowest = Float.POSITIVE_INFINITY
            repeat(500) {
                cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = -9.8f, iterations = 16)
                for (i in cloth.base until cloth.end) {
                    val dx = s.state.x[i]
                    val dy = s.state.y[i] - sphereY
                    val dz = s.state.z[i]
                    val d = sqrt(dx * dx + dy * dy + dz * dz)
                    if (radius - d > deepest) deepest = radius - d
                    if (s.state.y[i] < lowest) lowest = s.state.y[i]
                }
            }
            return Triple(deepest, lowest, s)
        }

        val (freeDeepest, freeLowest, _) = run(withCollider = false)
        assertTrue(
            freeDeepest > 0.8f,
            "premise: without a collider the sheet must pass right through the sphere, deepest $freeDeepest"
        )
        assertTrue(freeLowest < -3f, "premise: and keep going past the floor, lowest $freeLowest")

        val (deepest, lowest, s) = run(withCollider = true)
        println("drape: deepest sphere penetration %.5f, lowest particle %.3f (floor at -3)".format(deepest, lowest))
        assertTrue(deepest < 1e-3f, "the sheet must stay outside the sphere, deepest penetration $deepest")
        assertTrue(lowest >= -3f - 1e-3f, "and on the floor, lowest $lowest")
        assertTrue(s.state.y.take(s.size).all { it.isFinite() }, "and finite")

        // The drape is a drape and not a puddle: some of the sheet is still up on the sphere.
        val onSphere = (0 until s.size).count { i ->
            val dx = s.state.x[i]
            val dy = s.state.y[i] - sphereY
            val dz = s.state.z[i]
            sqrt(dx * dx + dy * dy + dz * dz) < radius + 0.03f
        }
        println("drape: $onSphere of ${s.size} particles resting on the sphere")
        assertTrue(onSphere > 20, "the sheet must actually lie on the sphere, only $onSphere particles do")
    }

    @Test
    fun frictionHoldsASheetThatOtherwiseSlidesOff() {
        fun slideRadius(friction: Float): Float {
            val s = sim()
            // A sheet started slightly off-centre on top of a sphere, on a slope, no floor: with
            // no friction it slides off, with friction it stays.
            val cloth = s.addCloth(
                width = 10, height = 10, spacing = 0.22f, stiffness = 60f, curvatureStrength = 0f,
                position = { c, r -> afield.stdlib.vec((c - 4.5f) * 0.22f, 1.35f, (r - 4.5f) * 0.22f) },
                pinned = { _, _ -> false }
            )
            s.addColliders(SphereCollider(0f, 0f, 0f, 1.2f).also { it.friction = friction })
            repeat(600) { cloth.step(dt = 1f / 60f, damping = 0.99f, gravity = -9.8f, iterations = 16) }
            // How far the centroid has fallen: a sheet that slides off ends up well below the pole.
            var sum = 0f
            for (i in cloth.base until cloth.end) sum += s.state.y[i]
            return sum / cloth.count
        }

        val slippery = slideRadius(0f)
        val rough = slideRadius(0.9f)
        println("centroid height after 600 frames: friction 0.0 -> %.3f, friction 0.9 -> %.3f".format(slippery, rough))
        assertTrue(slippery < 0.7f, "premise: a frictionless sheet must slide down off the pole, got $slippery")
        assertTrue(rough > slippery + 0.25f, "friction must hold it appreciably higher: $slippery vs $rough")
    }

    @Test
    fun aColliderSetRunsItsShapesInSeriesAndReportsContacts() {
        val s = sim()
        s.addParticle(x0 = 0.05f, y0 = 0.05f)
        val world = s.addColliders(
            SphereCollider(-1f, 0f, 0f, radius = 1.2f),
            SphereCollider(1f, 0f, 0f, radius = 1.2f)
        )
        world.relaxations = 4
        repeat(20) { s.step(dt = 1f / 60f, damping = 0.9f, gravity = 0f, iterations = 0) }
        val dl = sqrt((s.state.x[0] + 1f) * (s.state.x[0] + 1f) + s.state.y[0] * s.state.y[0] + s.state.z[0] * s.state.z[0])
        val dr = sqrt((s.state.x[0] - 1f) * (s.state.x[0] - 1f) + s.state.y[0] * s.state.y[0] + s.state.z[0] * s.state.z[0])
        println("two overlapping spheres, 4 relaxations: distances %.4f and %.4f (both want >= 1.2)".format(dl, dr))
        assertTrue(dl > 1.2f - 5e-3f && dr > 1.2f - 5e-3f, "relaxation must satisfy both shapes: $dl, $dr")
        assertTrue(world.outline().isNotEmpty(), "a set must be drawable")
    }

    @Test
    fun disablingAndDetachingBothStopTheProjection() {
        val s = sim()
        s.addParticle(x0 = 0f, y0 = 1f)
        val floor = PlaneCollider(0f, 1f, 0f, offset = 0f)
        val world = s.addColliders(floor)
        repeat(120) { s.step(dt = 1f / 60f, damping = 0.99f, gravity = -9.8f, iterations = 0) }
        assertTrue(s.state.y[0] >= -1e-4f, "premise: the floor is holding, y = ${s.state.y[0]}")

        floor.enabled = false
        repeat(60) { s.step(dt = 1f / 60f, damping = 0.99f, gravity = -9.8f, iterations = 0) }
        val afterDisable = s.state.y[0]
        assertTrue(afterDisable < -0.2f, "a disabled collider must let go, y = $afterDisable")
        assertEquals(0, floor.contacts, "and report no contacts")

        floor.enabled = true
        world.detach()
        repeat(60) { s.step(dt = 1f / 60f, damping = 0.99f, gravity = -9.8f, iterations = 0) }
        assertTrue(s.state.y[0] < afterDisable, "a detached set must not run at all")
        assertTrue("colliders" !in s.positionProjections, "and must unregister itself")
    }
}
