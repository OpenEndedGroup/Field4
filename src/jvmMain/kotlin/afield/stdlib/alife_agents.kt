@file:afield.CompletionCategory("ALife")

package afield.stdlib

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Agent-based spatial ALife: boids flocking, a Physarum (slime-mould) deposit–sense–move population
 * over a trail [Img], and diffusion-limited aggregation.
 *
 * See `alife.kt` for the family overview.
 *
 * These are the three canonical demonstrations that **global order can have no global rule**: a flock's
 * alignment, a slime mould's transport network and a dendrite's fractal dimension are all properties
 * nothing in the code mentions. Which is exactly why each one here ships with a control arm — a run of
 * the same code with one interaction switched off — because "emergence" is otherwise the easiest thing
 * in computing to claim and the hardest to check. See [polarization], [Img.topShare] and
 * [massRadiusDimension] for the three numbers those controls fail.
 *
 * Unlike the rest of the family these types are **mutable**: `step()` advances the population in place.
 * Copying 50 000 agents per frame would be the entire cost of a Physarum run, and the systems are
 * simulations rather than values.
 */

// ==============================================================================================
// Boids
// ==============================================================================================

/**
 * Reynolds' three boid steering weights plus the numbers that bound the motion.
 *
 * @property perceptionRadius how far an agent sees for alignment and cohesion
 * @property separationRadius the (smaller) radius inside which it actively pushes away
 * @property separationWeight strength of the push away from close neighbours
 * @property alignmentWeight strength of the pull toward the mean neighbour heading — set this to 0 and
 *   the flock stops being a flock, which is the control arm the [polarization] claim is made against
 * @property cohesionWeight strength of the pull toward the neighbour centroid
 * @property maxSpeed speed cap (agents fly at close to this)
 * @property maxForce per-step cap on the steering acceleration; without it a boid in a dense cluster
 *   receives an unbounded separation impulse and leaves at implausible speed
 */
data class BoidParams(
    val perceptionRadius: Double = 12.0,
    val separationRadius: Double = 4.5,
    val separationWeight: Double = 1.6,
    val alignmentWeight: Double = 1.0,
    val cohesionWeight: Double = 0.9,
    val maxSpeed: Double = 1.4,
    val maxForce: Double = 0.06
) {
    init {
        require(perceptionRadius > 0.0) { "perceptionRadius must be > 0, got $perceptionRadius" }
        require(separationRadius > 0.0) { "separationRadius must be > 0, got $separationRadius" }
        require(maxSpeed > 0.0) { "maxSpeed must be > 0, got $maxSpeed" }
        require(maxForce > 0.0) { "maxForce must be > 0, got $maxForce" }
    }
}

/**
 * A population of boids on a torus of `width x height`, stored as parallel arrays.
 *
 * The domain wraps, which matters for more than tidiness: in a bounded box the walls do half the
 * flocking (agents that bounce off the same wall end up parallel), so a polarization measured in a box
 * is not evidence about the *rules*. On a torus there is nothing to align a flock but its neighbours.
 *
 * @property width domain width
 * @property height domain height
 * @property x agent x positions
 * @property y agent y positions
 * @property vx agent x velocities
 * @property vy agent y velocities
 */
class BoidFlock(
    val width: Double,
    val height: Double,
    val x: FloatArray,
    val y: FloatArray,
    val vx: FloatArray,
    val vy: FloatArray
) {
    init {
        require(width > 0.0 && height > 0.0) { "domain must be positive, got ${width}x$height" }
        require(x.size == y.size && x.size == vx.size && x.size == vy.size) {
            "boid arrays must be the same length"
        }
        require(x.isNotEmpty()) { "a flock needs at least one boid" }
    }

    /** How many agents. */
    val count: Int get() = x.size

    /** The flock's current [polarization] — the alignment order parameter. */
    fun order(): Double = polarization(vx, vy)

    /** Nearest-neighbour distances under the toroidal metric. See [nearestNeighborDistances]. */
    fun neighborDistances(): DoubleArray = nearestNeighborDistances(x, y, width, height)

    /** Mean speed — a sanity readout, since a flock whose agents have stalled is not flocking. */
    fun meanSpeed(): Double {
        var s = 0.0
        for (i in x.indices) s += hypot(vx[i].toDouble(), vy[i].toDouble())
        return s / count
    }

    /** An independent copy, so a run can be forked to compare two parameter sets from one initial state. */
    fun copy(): BoidFlock =
        BoidFlock(width, height, x.copyOf(), y.copyOf(), vx.copyOf(), vy.copyOf())

    override fun toString(): String =
        "BoidFlock($count boids on ${width}x$height, polarization %.3f)".format(order())

    companion object {
        /**
         * A population at uniformly random positions with uniformly random headings at speed
         * `maxSpeed` — the *disordered* initial condition, which is the only honest place to start a
         * self-organisation claim from.
         */
        fun random(
            count: Int,
            width: Double = 200.0,
            height: Double = 200.0,
            speed: Double = 1.4,
            seed: Long = 1L
        ): BoidFlock {
            require(count >= 1) { "count must be >= 1, got $count" }
            val rng = kotlin.random.Random(seed)
            val x = FloatArray(count) { (rng.nextDouble() * width).toFloat() }
            val y = FloatArray(count) { (rng.nextDouble() * height).toFloat() }
            val vx = FloatArray(count)
            val vy = FloatArray(count)
            for (i in 0 until count) {
                val a = rng.nextDouble() * 2.0 * PI
                vx[i] = (cos(a) * speed).toFloat()
                vy[i] = (sin(a) * speed).toFloat()
            }
            return BoidFlock(width, height, x, y, vx, vy)
        }
    }
}

/**
 * One boid step, mutating the flock in place: accumulate the three steering terms from every neighbour
 * within [BoidParams.perceptionRadius] (toroidal metric), cap the total force, integrate, wrap.
 *
 * `O(N²)` on purpose. A spatial hash is the right structure for 50 000 agents and it is a different
 * (and considerably longer) piece of code; the flocks this family renders are a few hundred agents, and
 * a reference implementation whose correctness is legible is worth more here than one whose bucket
 * arithmetic is.
 */
fun BoidFlock.step(p: BoidParams = BoidParams()) {
    val n = count
    val ax = DoubleArray(n)
    val ay = DoubleArray(n)
    val pr2 = p.perceptionRadius * p.perceptionRadius
    val sr2 = p.separationRadius * p.separationRadius
    for (i in 0 until n) {
        var sepX = 0.0; var sepY = 0.0
        var aliX = 0.0; var aliY = 0.0
        var cohX = 0.0; var cohY = 0.0
        var neighbours = 0
        var close = 0
        for (j in 0 until n) {
            if (i == j) continue
            val dx = wrappedDelta(x[i].toDouble(), x[j].toDouble(), width)
            val dy = wrappedDelta(y[i].toDouble(), y[j].toDouble(), height)
            val d2 = dx * dx + dy * dy
            if (d2 > pr2 || d2 < 1e-12) continue
            neighbours++
            aliX += vx[j]; aliY += vy[j]
            cohX += dx; cohY += dy
            if (d2 < sr2) {
                // Push away, weighted by 1/d so a very close neighbour dominates.
                val d = sqrt(d2)
                sepX -= dx / d / d; sepY -= dy / d / d
                close++
            }
        }
        if (neighbours == 0) continue
        var fx = 0.0
        var fy = 0.0
        // Alignment: steer toward the mean neighbour velocity, expressed as a desired-minus-current.
        val mvx = aliX / neighbours
        val mvy = aliY / neighbours
        val mvm = hypot(mvx, mvy)
        if (mvm > 1e-9) {
            fx += p.alignmentWeight * (mvx / mvm * p.maxSpeed - vx[i])
            fy += p.alignmentWeight * (mvy / mvm * p.maxSpeed - vy[i])
        }
        // Cohesion: steer toward the neighbour centroid (already a relative offset).
        val ccx = cohX / neighbours
        val ccy = cohY / neighbours
        val ccm = hypot(ccx, ccy)
        if (ccm > 1e-9) {
            fx += p.cohesionWeight * (ccx / ccm * p.maxSpeed - vx[i])
            fy += p.cohesionWeight * (ccy / ccm * p.maxSpeed - vy[i])
        }
        // Separation.
        if (close > 0) {
            val sm = hypot(sepX, sepY)
            if (sm > 1e-9) {
                fx += p.separationWeight * (sepX / sm * p.maxSpeed - vx[i])
                fy += p.separationWeight * (sepY / sm * p.maxSpeed - vy[i])
            }
        }
        val fm = hypot(fx, fy)
        if (fm > p.maxForce) { fx = fx / fm * p.maxForce; fy = fy / fm * p.maxForce }
        ax[i] = fx; ay[i] = fy
    }
    for (i in 0 until n) {
        var nvx: Double = vx[i] + ax[i]
        var nvy: Double = vy[i] + ay[i]
        val s = hypot(nvx, nvy)
        if (s > p.maxSpeed) { nvx = nvx / s * p.maxSpeed; nvy = nvy / s * p.maxSpeed }
        // A stalled boid has no heading, so polarization would silently drop; nudge it back to speed.
        if (s < p.maxSpeed * 0.25 && s > 1e-9) {
            val k = p.maxSpeed * 0.25 / s
            nvx *= k; nvy *= k
        }
        vx[i] = nvx.toFloat()
        vy[i] = nvy.toFloat()
        x[i] = wrapTo(x[i] + vx[i].toDouble(), width).toFloat()
        y[i] = wrapTo(y[i] + vy[i].toDouble(), height).toFloat()
    }
}

/** [step] applied [steps] times. */
fun BoidFlock.stepTimes(p: BoidParams = BoidParams(), steps: Int) {
    require(steps >= 0) { "steps must be >= 0, got $steps" }
    repeat(steps) { step(p) }
}

/** Wrap [v] into `[0, n)` for a `Double` domain. */
private fun wrapTo(v: Double, n: Double): Double {
    var r = v % n
    if (r < 0) r += n
    return r
}

/**
 * Draw the flock as one short arrow [afield.graphics.FLine] per agent — a heading is the thing worth
 * seeing, and a dot per boid shows position while hiding the very thing being claimed.
 *
 * @param length shaft length in domain units
 */
fun BoidFlock.toFLines(length: Double = 4.0, z: Number = 0): List<afield.graphics.FLine> =
    (0 until count).map { i ->
        val s = hypot(vx[i].toDouble(), vy[i].toDouble()).coerceAtLeast(1e-9)
        val ux = vx[i] / s
        val uy = vy[i] / s
        val ex = x[i] + ux * length
        val ey = y[i] + uy * length
        val ang = atan2(uy, ux)
        val f = afield.graphics.FLine()
        f.moveTo(x[i], y[i], z)
        f.lineTo(ex, ey, z)
        f.lineTo(ex - length * 0.4 * cos(ang - 0.5), ey - length * 0.4 * sin(ang - 0.5), z)
        f.moveTo(ex, ey, z)
        f.lineTo(ex - length * 0.4 * cos(ang + 0.5), ey - length * 0.4 * sin(ang + 0.5), z)
        f
    }

/**
 * Render the flock into an [Img] of the given size: each agent splats a small anti-aliased dot, and a
 * short tail behind it shows its heading.
 *
 * @param scale domain units per output pixel is `1 / scale`; 1.0 renders one pixel per unit
 */
fun BoidFlock.toImg(scale: Double = 1.0, tail: Double = 3.0): Img {
    require(scale > 0.0) { "scale must be > 0, got $scale" }
    val w = max(1, (width * scale).roundToInt())
    val h = max(1, (height * scale).roundToInt())
    val out = Img.zeros(w, h, 1)
    fun splat(px: Double, py: Double, v: Float) {
        val ix = px.roundToInt()
        val iy = py.roundToInt()
        for (dy in -1..1) for (dx in -1..1) {
            val xx = Math.floorMod(ix + dx, w)
            val yy = Math.floorMod(iy + dy, h)
            val weight = if (dx == 0 && dy == 0) 1f else 0.35f
            val i = yy * w + xx
            out.data[i] = min(1f, out.data[i] + v * weight)
        }
    }
    for (i in 0 until count) {
        val s = hypot(vx[i].toDouble(), vy[i].toDouble()).coerceAtLeast(1e-9)
        val steps = max(1, (tail * scale).roundToInt())
        for (t in 0 until steps) {
            val f = t.toDouble() / steps
            splat(
                (x[i] - vx[i] / s * tail * f) * scale,
                (y[i] - vy[i] / s * tail * f) * scale,
                (0.25f + 0.75f * (1f - f.toFloat()))
            )
        }
    }
    return out
}

// ==============================================================================================
// Physarum: deposit, sense, move
// ==============================================================================================

/**
 * Jones' Physarum transport-network model parameters — the "deposit, sense, move" agent whose
 * population reproduces the transport networks *Physarum polycephalum* grows.
 *
 * @property sensorAngle angle between the centre sensor and each side sensor, in radians
 * @property sensorDistance how far ahead the sensors look, in cells
 * @property turnAngle how far an agent rotates when a side sensor wins, in radians
 * @property stepSize how far it moves per step, in cells
 * @property deposit how much trail it leaves per step
 * @property decay fraction of the trail that survives each step (`0.9` = 10 % evaporates)
 * @property blurRadius box-blur radius applied to the trail each step, which is the diffusion term
 * @property sensing when `false` the agent turns at random instead of following the trail — the
 *   control arm. Everything else is identical, so a difference in [Img.topShare] between the two runs
 *   is attributable to the sensing and to nothing else.
 */
data class PhysarumParams(
    val sensorAngle: Double = PI / 4.0,
    val sensorDistance: Double = 9.0,
    val turnAngle: Double = PI / 4.0,
    val stepSize: Double = 1.0,
    val deposit: Double = 5.0,
    val decay: Double = 0.90,
    val blurRadius: Int = 1,
    val sensing: Boolean = true
) {
    init {
        require(sensorDistance > 0.0) { "sensorDistance must be > 0, got $sensorDistance" }
        require(stepSize > 0.0) { "stepSize must be > 0, got $stepSize" }
        require(deposit > 0.0) { "deposit must be > 0, got $deposit" }
        require(decay > 0.0 && decay <= 1.0) { "decay must be in (0, 1], got $decay" }
        require(blurRadius >= 0) { "blurRadius must be >= 0, got $blurRadius" }
    }
}

/**
 * A Physarum population over a trail field.
 *
 * Every agent is a position and a heading; the only state they share is the [trail], which they both
 * write to and read from. That stigmergic loop — deposit into the environment, sense the environment,
 * turn toward more of it — is the entire mechanism, and it is why the resulting network is not stored
 * anywhere: it is the fixed point of the loop.
 *
 * @property trail the deposit field, mutated each step; 1-channel, unbounded above
 * @property x agent x positions in cells
 * @property y agent y positions in cells
 * @property heading agent headings in radians
 */
class PhysarumSwarm(
    val trail: Img,
    val x: FloatArray,
    val y: FloatArray,
    val heading: FloatArray
) {
    init {
        require(trail.channels == 1) { "the Physarum trail must be a 1-channel field" }
        require(x.size == y.size && x.size == heading.size) { "Physarum arrays must be the same length" }
        require(x.isNotEmpty()) { "a swarm needs at least one agent" }
    }

    /** How many agents. */
    val count: Int get() = x.size

    /** Trail field width in cells. */
    val width: Int get() = trail.width

    /** Trail field height in cells. */
    val height: Int get() = trail.height

    override fun toString(): String =
        "PhysarumSwarm($count agents on ${width}x$height, trail max %.2f)".format(trail.max())

    companion object {
        /**
         * A swarm at uniformly random positions and headings on an empty trail — the disordered start
         * a network-formation claim has to begin from.
         */
        fun random(width: Int, height: Int, count: Int, seed: Long = 1L): PhysarumSwarm {
            require(width >= 4 && height >= 4) { "field must be at least 4x4, got ${width}x$height" }
            require(count >= 1) { "count must be >= 1, got $count" }
            val rng = kotlin.random.Random(seed)
            return PhysarumSwarm(
                Img.zeros(width, height, 1),
                FloatArray(count) { (rng.nextDouble() * width).toFloat() },
                FloatArray(count) { (rng.nextDouble() * height).toFloat() },
                FloatArray(count) { (rng.nextDouble() * 2.0 * PI).toFloat() }
            )
        }

        /**
         * A swarm seeded inside a centred disc of radius `min(w, h) * discFraction / 2`, all headings
         * random — the initial condition that produces the classic radiating network, because the
         * agents have somewhere to expand *into*.
         */
        fun disc(width: Int, height: Int, count: Int, discFraction: Double = 0.5, seed: Long = 1L): PhysarumSwarm {
            require(discFraction > 0.0 && discFraction <= 1.0) { "discFraction must be in (0, 1], got $discFraction" }
            val rng = kotlin.random.Random(seed)
            val r = min(width, height) * discFraction / 2.0
            val cx = width / 2.0
            val cy = height / 2.0
            val xs = FloatArray(count)
            val ys = FloatArray(count)
            val hs = FloatArray(count)
            for (i in 0 until count) {
                val a = rng.nextDouble() * 2.0 * PI
                val rr = r * sqrt(rng.nextDouble())
                xs[i] = (cx + rr * cos(a)).toFloat()
                ys[i] = (cy + rr * sin(a)).toFloat()
                hs[i] = (rng.nextDouble() * 2.0 * PI).toFloat()
            }
            return PhysarumSwarm(Img.zeros(width, height, 1), xs, ys, hs)
        }
    }
}

/**
 * One Physarum step: every agent deposits, samples its three sensors, turns toward the strongest, and
 * moves; then the trail diffuses and decays.
 *
 * Order matters and is the classic place this model is got wrong. Depositing *before* sensing means an
 * agent can smell its own fresh deposit, which makes every agent self-trapping and the network never
 * forms. Here the deposit for the step happens after the move, so the field an agent senses is the one
 * the *previous* step left. The trail is diffused and decayed once per step, after all agents have
 * moved — a per-agent decay would make the result depend on agent ordering.
 *
 * Returns a **new** swarm whose trail is the blurred, decayed successor field. The agent arrays are the
 * same objects, mutated in place — the population is a simulation, not a value, and copying it per step
 * would be most of the cost of a long run.
 *
 * @param rng the source of randomness for tie-breaks and for the sensing-off control arm
 */
fun PhysarumSwarm.step(p: PhysarumParams, rng: kotlin.random.Random): PhysarumSwarm {
    val w = width
    val h = height
    val t = trail
    fun sample(px: Double, py: Double): Float {
        val ix = Math.floorMod(px.roundToInt(), w)
        val iy = Math.floorMod(py.roundToInt(), h)
        return t.data[iy * w + ix]
    }
    for (i in 0 until count) {
        val a = heading[i].toDouble()
        if (p.sensing) {
            val fwd = sample(x[i] + cos(a) * p.sensorDistance, y[i] + sin(a) * p.sensorDistance)
            val lft = sample(
                x[i] + cos(a - p.sensorAngle) * p.sensorDistance,
                y[i] + sin(a - p.sensorAngle) * p.sensorDistance
            )
            val rgt = sample(
                x[i] + cos(a + p.sensorAngle) * p.sensorDistance,
                y[i] + sin(a + p.sensorAngle) * p.sensorDistance
            )
            heading[i] = when {
                fwd >= lft && fwd >= rgt -> a
                lft > rgt -> a - p.turnAngle
                rgt > lft -> a + p.turnAngle
                // Exactly tied side sensors (an empty field, typically): pick a side at random rather
                // than always the same one, which would put a systematic curl into every trajectory.
                else -> if (rng.nextBoolean()) a - p.turnAngle else a + p.turnAngle
            }.toFloat()
        } else {
            heading[i] = (a + (rng.nextDouble() - 0.5) * 2.0 * p.turnAngle).toFloat()
        }
        val na = heading[i].toDouble()
        x[i] = wrapTo(x[i] + cos(na) * p.stepSize, w.toDouble()).toFloat()
        y[i] = wrapTo(y[i] + sin(na) * p.stepSize, h.toDouble()).toFloat()
    }
    // Deposit after moving, so nothing senses its own current step.
    val dep = p.deposit.toFloat()
    for (i in 0 until count) {
        val ix = Math.floorMod(x[i].roundToInt(), w)
        val iy = Math.floorMod(y[i].roundToInt(), h)
        t.data[iy * w + ix] += dep
    }
    val diffused = if (p.blurRadius == 0) t else t.boxBlur(p.blurRadius, Border.WRAP)
    val decayed = diffused.map { it * p.decay.toFloat() }
    return PhysarumSwarm(decayed, x, y, heading)
}

/** [step] applied [steps] times with one [kotlin.random.Random] seeded from [seed]. */
fun PhysarumSwarm.stepTimes(p: PhysarumParams, steps: Int, seed: Long = 1L): PhysarumSwarm {
    require(steps >= 0) { "steps must be >= 0, got $steps" }
    val rng = kotlin.random.Random(seed)
    var s = this
    repeat(steps) { s = s.step(p, rng) }
    return s
}

// ==============================================================================================
// Diffusion-limited aggregation
// ==============================================================================================

/**
 * A grown aggregate.
 *
 * @property mask 1-channel binary occupancy of the cluster
 * @property points the occupied cells, in the order they stuck — so growth order is available, not
 *   only the final shape
 * @property center the seed location, which is the origin every radial measurement is taken from
 * @property maxRadius the furthest occupied cell from [center], in cells
 * @property walkSteps how many random-walk steps were taken in total (the cost, and the thing a
 *   naive implementation makes quadratic)
 */
class Aggregate(
    val mask: Img,
    val points: List<Vec2>,
    val center: Vec2,
    val maxRadius: Double,
    val walkSteps: Long
) {
    /** [massRadiusDimension] of this aggregate, fitted over the range its own size supports. */
    fun fractalDimension(rMin: Double = 4.0, rMaxFraction: Double = 0.7): MassRadiusFit =
        massRadiusDimension(points, center, rMin, max(rMin * 2.0, maxRadius * rMaxFraction))

    override fun toString(): String =
        "Aggregate(${points.size} particles, max radius %.1f, %d walk steps)".format(maxRadius, walkSteps)
}

/**
 * Grow a diffusion-limited aggregation cluster: a seed at the centre, then particles released far away
 * that random-walk until they touch the cluster and stick.
 *
 * DLA is the canonical "structure from noise": nothing in the rule mentions branches, and the result
 * is a branched dendrite with a **fractal dimension of about 1.71 in the plane** — a number that comes
 * from the physics and not from the code. That makes it the one system in this family with a
 * quantitative literature value to hit, so [Aggregate.fractalDimension] is the sharpest evidence here:
 * a filled disc scores 2.0, a uniform sprinkle scores 2.0, and only a real DLA cluster scores ~1.7.
 *
 * Two standard efficiency measures, both of which change the runtime by orders of magnitude and
 * neither of which changes the physics: walkers are launched on a circle just outside the current
 * cluster radius rather than at the domain edge, and a walker that wanders past a kill radius is
 * abandoned and relaunched rather than followed home.
 *
 * @param particles how many to try to attach
 * @param stickiness probability a walker adjacent to the cluster actually sticks; below 1 the cluster
 *   is denser and less branched, which is the standard way DLA is interpolated toward Eden growth
 * @param maxWalkSteps abandon the whole run if the total walk budget is exhausted, so a pathological
 *   parameter set cannot hang a docs build
 */
fun dlaCluster(
    width: Int,
    height: Int,
    particles: Int = 4000,
    seed: Long = 1L,
    stickiness: Double = 1.0,
    maxWalkSteps: Long = 200_000_000L
): Aggregate {
    require(width >= 16 && height >= 16) { "DLA field must be at least 16x16, got ${width}x$height" }
    require(particles >= 1) { "particles must be >= 1, got $particles" }
    require(stickiness > 0.0 && stickiness <= 1.0) { "stickiness must be in (0, 1], got $stickiness" }
    val rng = kotlin.random.Random(seed)
    val occupied = BooleanArray(width * height)
    val cx = width / 2
    val cy = height / 2
    occupied[cy * width + cx] = true
    val pts = ArrayList<Vec2>(particles)
    pts.add(Vec2(cx.toFloat(), cy.toFloat()))
    var clusterRadius = 0.0
    val domainRadius = min(width, height) / 2.0 - 2.0
    var walks = 0L

    fun touches(px: Int, py: Int): Boolean {
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = px + dx
            val ny = py + dy
            if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
            if (occupied[ny * width + nx]) return true
        }
        return false
    }

    outer@ for (n in 1 until particles) {
        val launchR = min(domainRadius, clusterRadius + 5.0)
        if (launchR < 2.0) break
        val killR = min(domainRadius, launchR * 2.0 + 8.0)
        var placed = false
        var attempts = 0
        while (!placed && attempts < 64) {
            attempts++
            val a = rng.nextDouble() * 2.0 * PI
            var px = (cx + launchR * cos(a)).roundToInt()
            var py = (cy + launchR * sin(a)).roundToInt()
            while (true) {
                if (walks++ > maxWalkSteps) break@outer
                when (rng.nextInt(4)) {
                    0 -> px++
                    1 -> px--
                    2 -> py++
                    else -> py--
                }
                val r = hypot((px - cx).toDouble(), (py - cy).toDouble())
                if (r > killR) break
                if (px < 1 || py < 1 || px >= width - 1 || py >= height - 1) break
                if (touches(px, py)) {
                    if (stickiness < 1.0 && rng.nextDouble() > stickiness) continue
                    occupied[py * width + px] = true
                    pts.add(Vec2(px.toFloat(), py.toFloat()))
                    if (r > clusterRadius) clusterRadius = r
                    placed = true
                    break
                }
            }
            // A cluster that has reached the domain edge cannot grow further; stop rather than spin.
            if (clusterRadius >= domainRadius - 1.0) break@outer
        }
        if (!placed) break
    }

    val mask = Img.zeros(width, height, 1)
    for (i in occupied.indices) if (occupied[i]) mask.data[i] = 1f
    return Aggregate(mask, pts, Vec2(cx.toFloat(), cy.toFloat()), clusterRadius, walks)
}

/**
 * Eden-model (ballistic) growth: repeatedly pick a random empty cell adjacent to the cluster and fill
 * it. Same seed, same neighbourhood, same output type as [dlaCluster] — the **only** difference is that
 * a growth site is chosen uniformly instead of by a diffusing walker.
 *
 * It exists to be the control. Eden growth produces a compact blob with a rough edge and a mass
 * dimension of **2**, so it is the object that proves [Aggregate.fractalDimension] is measuring the
 * *diffusion-limited* part of DLA rather than merely "a growth process happened".
 */
fun edenCluster(width: Int, height: Int, particles: Int = 4000, seed: Long = 1L): Aggregate {
    require(width >= 16 && height >= 16) { "Eden field must be at least 16x16, got ${width}x$height" }
    require(particles >= 1) { "particles must be >= 1, got $particles" }
    val rng = kotlin.random.Random(seed)
    val occupied = BooleanArray(width * height)
    val cx = width / 2
    val cy = height / 2
    occupied[cy * width + cx] = true
    val pts = ArrayList<Vec2>(particles)
    pts.add(Vec2(cx.toFloat(), cy.toFloat()))
    // Perimeter as a list with an occupancy guard, so picking a uniform site is O(1).
    val perimeter = ArrayList<Int>()
    val onPerimeter = BooleanArray(width * height)
    fun offer(i: Int) {
        if (!occupied[i] && !onPerimeter[i]) { onPerimeter[i] = true; perimeter.add(i) }
    }
    fun offerNeighbours(px: Int, py: Int) {
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = px + dx
            val ny = py + dy
            if (nx < 1 || ny < 1 || nx >= width - 1 || ny >= height - 1) continue
            offer(ny * width + nx)
        }
    }
    offerNeighbours(cx, cy)
    var clusterRadius = 0.0
    while (pts.size < particles && perimeter.isNotEmpty()) {
        val k = rng.nextInt(perimeter.size)
        val i = perimeter[k]
        perimeter[k] = perimeter[perimeter.size - 1]
        perimeter.removeAt(perimeter.size - 1)
        onPerimeter[i] = false
        if (occupied[i]) continue
        occupied[i] = true
        val px = i % width
        val py = i / width
        pts.add(Vec2(px.toFloat(), py.toFloat()))
        val r = hypot((px - cx).toDouble(), (py - cy).toDouble())
        if (r > clusterRadius) clusterRadius = r
        offerNeighbours(px, py)
    }
    val mask = Img.zeros(width, height, 1)
    for (i in occupied.indices) if (occupied[i]) mask.data[i] = 1f
    return Aggregate(mask, pts, Vec2(cx.toFloat(), cy.toFloat()), clusterRadius, 0L)
}

/**
 * A uniform random sprinkle of [particles] cells inside a disc of the same radius as a comparable
 * cluster — the *second* control for [Aggregate.fractalDimension], and the one that catches a
 * dimension estimator fooled by a bounded support.
 *
 * A sprinkle in a disc has mass dimension exactly 2 by construction, so if the estimator reports 1.7
 * for this, the estimator is wrong and not the cluster.
 */
fun sprinkleCluster(width: Int, height: Int, particles: Int, radius: Double, seed: Long = 1L): Aggregate {
    require(radius > 1.0) { "radius must be > 1, got $radius" }
    val rng = kotlin.random.Random(seed)
    val cx = width / 2
    val cy = height / 2
    val mask = Img.zeros(width, height, 1)
    val pts = ArrayList<Vec2>(particles)
    var maxR = 0.0
    repeat(particles) {
        val a = rng.nextDouble() * 2.0 * PI
        val r = radius * sqrt(rng.nextDouble())
        val px = (cx + r * cos(a)).roundToInt().coerceIn(0, width - 1)
        val py = (cy + r * sin(a)).roundToInt().coerceIn(0, height - 1)
        mask.data[py * width + px] = 1f
        pts.add(Vec2(px.toFloat(), py.toFloat()))
        if (r > maxR) maxR = r
    }
    return Aggregate(mask, pts, Vec2(cx.toFloat(), cy.toFloat()), maxR, 0L)
}

/**
 * Colour an aggregate by **arrival order**: the seed at one end of the ramp, the last particle to stick
 * at the other.
 *
 * Not decoration. A DLA cluster's branches are a record of *when* — the tips are young and the interior
 * is old, and screened from further growth — so an arrival-order colouring shows the mechanism (older
 * material is unreachable) that a binary mask hides.
 */
fun Aggregate.arrivalOrderImg(): Img {
    val out = Img.zeros(mask.width, mask.height, 1)
    val n = max(1, points.size - 1)
    points.forEachIndexed { i, p ->
        val ix = p.x.roundToInt().coerceIn(0, mask.width - 1)
        val iy = p.y.roundToInt().coerceIn(0, mask.height - 1)
        out.data[iy * mask.width + ix] = i.toFloat() / n
    }
    return out
}
