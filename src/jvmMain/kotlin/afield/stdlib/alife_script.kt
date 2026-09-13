@file:afield.CompletionCategory("ALife")

package afield.stdlib

import afield.graphics.FLine
import afield.stdlib.linalg.Vector2d
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Per-agent **scripted** behaviour for ALife: a coroutine each agent runs as its own little program,
 * on a fixed-step virtual clock, composing into weighted steering drives.
 *
 * See `alife.kt` for the family overview, and `alife_agents.kt` for the family this completes.
 *
 * ### Why this exists
 *
 * Everything in `alife_agents.kt` is a **global rule**. A flock is `BoidFlock.step(p: BoidParams)` —
 * one function, one parameter block, applied identically to every agent on every tick. That is the
 * right shape for the three canonical emergence demonstrations and it is the wrong shape for the
 * moment you want *this* agent to spend two seconds approaching, half a second feeding and then go
 * somewhere else. A global rule has no place to keep "where am I up to". You end up encoding the
 * agent's position in its own program as an integer state field and a `when`, which is the standard
 * way this is done and is also why it is never done twice the same way.
 *
 * A coroutine has a place to keep it: the continuation. So a behaviour here reads as the sequence it
 * is —
 *
 * ```
 * forever {
 *     phase("travel") { moveUntil({ near(site, 6.0) }, maxSeconds = 8.0) { seek(site); separate() } }
 *     phase("feed")   { rest(0.4) }
 * }
 * ```
 *
 * — and the scheduler, not the author, remembers that this agent is 1.3 seconds into its approach.
 *
 * ### The clock, and why it is the point
 *
 * The obvious way to build this is on `kotlinx.coroutines` with `delay` and a real dispatcher. That
 * gives you something you can watch and nothing you can assert, because the interleaving depends on
 * the machine. This scheduler is **mine and it counts ticks**:
 *
 *  - `wait(seconds)` rounds **up** to a whole number of steps, `ceil(seconds / fixedStep)`, minimum
 *    one. Wake-ups are compared as `Long` tick numbers, so there is no floating-point boundary to be
 *    on the wrong side of, and `wait(0.5)` at 60 Hz is exactly 30 steps, every time.
 *  - Resumptions run from a FIFO queue I own, ordered by `(wakeTick, sequence)` with a unique
 *    monotonic sequence number, which is a **total** order. Two behaviours that wake on the same tick
 *    resume in the order they went to sleep.
 *  - Drives are held in a [LinkedHashMap] keyed by a monotonic `Long`, so they are evaluated in the
 *    order they were registered. (Keying a motor map by `Any()` — the natural thing, since the key is
 *    only a handle — makes the evaluation order depend on identity hash codes, i.e. on the run.)
 *  - Every drive reads a **pre-step snapshot** of every agent, so the result does not depend on which
 *    agent is integrated first.
 *  - Randomness is per agent, from `Random(seed, id)`, never from a shared stream, so adding an agent
 *    does not reshuffle the others.
 *
 * The consequence is [ScriptWorld.state]: run a population twice from one seed and the `DoubleArray`
 * is equal bit for bit. That is asserted, not asserted-about — see `AlifeScriptGallery`.
 *
 * ### Composing, not duplicating
 *
 * The steering verbs in [BehaviourSteering] are the Reynolds vocabulary already in this family:
 * [BehaviourSteering.separate], [BehaviourSteering.align] and [BehaviourSteering.cohere] are the
 * three boid terms, so a scripted population can *be* a flock when its script says to be one, and
 * [ScriptWorld.asBoidFlock] hands the state to [polarization], [nearestNeighborDistances] and the
 * rest of `alife.kt`'s measures rather than growing a second set. Trails come out as [FLine]s
 * ([ScriptWorld.trailFLines]) because geometry is FLine's job; nothing here rasterises anything
 * except where a contact sheet needs pixels.
 *
 * ### What it deliberately is not
 *
 * Not a behaviour tree and not a blackboard. Both are ways of giving a *stateless* evaluator somewhere
 * to put the state, and the whole argument above is that a coroutine already has somewhere. There is
 * no reactive re-evaluation either: a behaviour that wants to abandon what it is doing says so, with
 * [BehaviourScope.moveUntil] or [BehaviourScope.timeout] or by cancelling a [BehaviourJob].
 */

// ==============================================================================================
// Errors and identifiers
// ==============================================================================================

/**
 * Thrown into a behaviour at its next suspension point when its [BehaviourJob] is cancelled — by
 * [BehaviourJob.cancel], by its parent being cancelled, or by the agent dying.
 *
 * Caught and swallowed by the scheduler, so `try`/`finally` in a behaviour runs (which is how
 * [BehaviourScope.moveFor] guarantees it takes its drives back out of the motor set) without the
 * cancellation being reported as a failure. Catching it yourself and carrying on is a mistake: the
 * next `wait` throws it again.
 */
class BehaviourCancelled internal constructor(val reason: String) : RuntimeException(reason)

/**
 * Something a behaviour did, stamped with the tick it happened on — the run's audit trail.
 *
 * Phase entries and exits, [BehaviourScope.log] calls, deaths and behaviour failures all land here.
 * Deterministic like everything else, so [ScriptWorld.eventSignature] is a second, *symbolic* witness
 * to reproducibility beside the numeric [ScriptWorld.state]: two runs can agree on every coordinate
 * and still have made different decisions if the drives happened to cancel out.
 *
 * @property tick the step it was recorded on; 0 means "before the first step"
 * @property actorId the agent it belongs to, or -1 for a world-level event
 * @property kind a short stable tag — `phase`, `phase-end`, `log`, `died`, `failed`
 * @property detail the phase name, message or exception text
 */
data class ScriptEvent(val tick: Long, val actorId: Int, val kind: String, val detail: String) {
    override fun toString(): String = "t$tick #$actorId $kind ${if (detail.isEmpty()) "" else detail}".trim()
}

// ==============================================================================================
// Drives, senses and targets
// ==============================================================================================

/**
 * A steering ingredient: given an agent and what it can sense, the velocity it *would like* to have.
 *
 * Return a desired velocity, not an acceleration — the same convention as the three terms inside
 * [BoidFlock.step], and the reason several drives can be blended by weight without their magnitudes
 * fighting. [ScriptSense.speed] is the speed the active behaviour asked for; scale by it rather than
 * by a constant, so one drive works at any pace.
 *
 * Must return a finite vector. A drive that throws, or returns a NaN, is reported as a behaviour
 * failure for that agent and contributes nothing; it does not take the run down.
 *
 * **Do not use `/` on a vector you were handed.** `Vector2d.div` is the one operator on the generated
 * linalg types that writes its result back into the receiver, so `delta / delta.length()` silently
 * mutates `delta`. Build a fresh `Vector2d(x, y)` from doubles, as every drive here does.
 */
fun interface ScriptDrive {
    /** The desired velocity for [self], in world units per second. */
    fun steer(self: ScriptActor, sense: ScriptSense): Vector2d
}

/**
 * One [ScriptDrive] and the weight it carries in the blend.
 *
 * Weights are relative: the motor blend is a weighted *mean* of the desired velocities, so doubling
 * every weight changes nothing and there is no global gain to tune.
 */
data class WeightedDrive(val drive: ScriptDrive, val weight: Double) {
    init {
        require(weight.isFinite() && weight > 0.0) { "drive weight must be finite and > 0, got $weight" }
    }
}

/** One [BehaviourScope.moveFor]'s worth of drives, at the speed that behaviour asked for. */
internal class MotorSet(val speed: Double, val drives: List<WeightedDrive>)

/**
 * What an agent can see this step — always the **pre-step snapshot**, never the half-integrated
 * present.
 *
 * This is what makes the population order-independent. Integrate agent 0 before agent 1 and let
 * agent 1 read agent 0's new position, and the result depends on the iteration order of a collection;
 * with everyone reading the same snapshot it does not, and a `separate()` between two agents is
 * symmetric instead of giving whichever went first the advantage.
 *
 * @property speed the speed the currently-evaluating behaviour asked for — scale desired velocities
 *   by this
 */
class ScriptSense internal constructor(
    val world: ScriptWorld,
    val self: ScriptActor,
    val speed: Double
) {
    /** [self]'s position as at the start of this step. */
    val position: Vector2d get() = Vector2d(self.sx, self.sy)

    /** [self]'s velocity as at the start of this step. */
    val velocity: Vector2d get() = Vector2d(self.svx, self.svy)

    /**
     * Live agents other than [self] within [range] of it, under the world's metric, in agent-id order.
     *
     * `O(N)` per call and therefore `O(N²)` per step, the same trade [BoidFlock.step] makes and for
     * the same reason: a few hundred scripted agents is the scale this is for, and legible neighbour
     * code is worth more here than bucket arithmetic.
     */
    fun neighbours(range: Double): List<ScriptActor> = world.neighbours(self, range)

    /** Offset from [self] to ([x], [y]), taking the shorter way round if the world wraps. */
    fun delta(x: Double, y: Double): Vector2d =
        Vector2d(world.deltaX(self.sx, x), world.deltaY(self.sy, y))
}

/**
 * Somewhere an agent might steer toward — resolved fresh every step, so a target that moves is
 * followed and a target that has gone (a dead agent, a cleared goal) simply stops pulling.
 *
 * Returning `null` means "no target now", which every drive treats as contributing nothing rather
 * than as an error. Build them with [BehaviourScope.point], [BehaviourScope.actor],
 * [BehaviourScope.nearest] and [BehaviourScope.centroid].
 */
fun interface ScriptTarget {
    /** The position to aim at, or `null` if there is none this step. */
    fun resolve(world: ScriptWorld, self: ScriptActor): Vector2d?
}

// ==============================================================================================
// The agent
// ==============================================================================================

/**
 * One scripted agent: a position, a velocity, a motor set its behaviours write into, and a name for
 * what it is currently doing.
 *
 * State is kept as plain doubles rather than [Vector2d]s. Partly that is `BoidFlock`'s convention and
 * partly it is that the mutating `Vector2d.div` makes a stored vector a liability — a caller who
 * writes `agent.position / 2.0` would move the agent. [position] and [velocity] hand out fresh copies.
 *
 * Create these with [ScriptWorld.spawn]; the constructor is internal because an agent that is not in
 * a world has nothing to step it.
 *
 * @property maxSpeed hard cap on speed, whatever a behaviour asks for
 * @property steerResponse how much of the gap between current and desired velocity is closed per
 *   step, in `0..1`. 1 is instant turning (correct for a projectile, wrong for anything with inertia);
 *   the default 0.25 gives a visible arc without the overshoot a spring would.
 * @property rng this agent's own random stream, seeded from the world seed and the agent id. Per-agent
 *   rather than shared, so inserting an agent does not reshuffle every other agent's future.
 */
class ScriptActor internal constructor(
    val world: ScriptWorld,
    val id: Int,
    x: Double,
    y: Double,
    val maxSpeed: Double,
    val radius: Double,
    val steerResponse: Double,
    val tag: String
) {
    internal var x: Double = x
    internal var y: Double = y
    internal var vx: Double = 0.0
    internal var vy: Double = 0.0

    // The pre-step snapshot every drive reads. See ScriptSense.
    internal var sx: Double = x
    internal var sy: Double = y
    internal var svx: Double = 0.0
    internal var svy: Double = 0.0

    /** False once [ScriptWorld] has killed it; a dead agent is no longer integrated or sensed. */
    var alive: Boolean = true
        internal set

    /** The innermost [BehaviourScope.phase] currently running, or `null` between phases. */
    var phase: String? = null
        internal set

    val rng: Random = Random(world.seed * -0x61c8864680b583ebL + id * 0x2545f4914f6cdd1dL)

    internal val motors = LinkedHashMap<Long, MotorSet>()
    internal var parallelCount: Int = 0
    internal val trailX = ArrayList<Double>()
    internal val trailY = ArrayList<Double>()
    internal val phaseStack = ArrayList<String?>()

    /** A fresh copy of the current position. */
    val position: Vector2d get() = Vector2d(x, y)

    /** A fresh copy of the current velocity, in world units per second. */
    val velocity: Vector2d get() = Vector2d(vx, vy)

    /** Current speed. */
    val speed: Double get() = hypot(vx, vy)

    /** How many drives are currently registered across all active behaviours — a load readout. */
    val driveCount: Int get() = motors.values.sumOf { it.drives.size }

    /** Distance to ([px], [py]) under the world's metric. */
    fun distanceTo(px: Double, py: Double): Double =
        hypot(world.deltaX(x, px), world.deltaY(y, py))

    /** Distance to [p] under the world's metric. */
    fun distanceTo(p: Vector2d): Double = distanceTo(p.x, p.y)

    /**
     * Move this agent immediately, ignoring its velocity — teleporting, so use it for placement and
     * respawns, not for motion. The trail records the jump as a jump.
     */
    fun placeAt(px: Double, py: Double) {
        require(px.isFinite() && py.isFinite()) { "placeAt needs finite coordinates, got ($px, $py)" }
        x = px; y = py
        world.confine(this)
    }

    override fun toString(): String =
        "ScriptActor(#$id ${if (tag.isEmpty()) "" else "$tag "}at %.1f,%.1f phase=%s)".format(x, y, phase ?: "-")
}

// ==============================================================================================
// Jobs
// ==============================================================================================

/**
 * A running behaviour, and the handle that cancels it.
 *
 * Jobs form a tree: an agent's program is a root, and every [BehaviourScope.parallel] inside it is a
 * child. Cancelling a job cancels its children first, so the inner `finally` blocks run before the
 * outer ones and drives come out of the motor set in the order they went in. An agent's death
 * cancels its root.
 *
 * Cancellation is **cooperative and takes effect at the next suspension point**, which for a
 * behaviour means the next `wait` — the same contract kotlinx has, for the same reason: there is no
 * safe place to interrupt arbitrary code. A behaviour with no suspension point in it cannot be
 * cancelled, which is one of the two things [ScriptWorld.maxResumptionsPerStep] catches.
 */
class BehaviourJob internal constructor(
    internal val world: ScriptWorld,
    val actor: ScriptActor,
    internal val parent: BehaviourJob?
) {
    internal val children = ArrayList<BehaviourJob>()
    internal var pending: Sleeper? = null

    /** True once [cancel] has been called, or a parent was cancelled. */
    var cancelled: Boolean = false
        internal set

    /** True once the behaviour has returned, thrown, or finished unwinding a cancellation. */
    var done: Boolean = false
        internal set

    /** Still worth waiting for. */
    val isActive: Boolean get() = !cancelled && !done

    /** Cancel this behaviour and everything it started. Idempotent. */
    fun cancel(reason: String = "cancelled") = world.cancelJob(this, reason)

    override fun toString(): String =
        "BehaviourJob(#${actor.id} ${if (cancelled) "cancelled" else if (done) "done" else "active"})"
}

/** A behaviour parked until [wakeTick]. [seq] breaks ties, giving wake-ups a total order. */
internal class Sleeper(
    val wakeTick: Long,
    val seq: Long,
    val cont: Continuation<Unit>,
    val job: BehaviourJob
)

// ==============================================================================================
// The world
// ==============================================================================================

/**
 * A fixed-step arena of scripted agents: the clock, the scheduler, the population and the physics.
 *
 * Drive it yourself — [step] advances exactly one tick and returns, so a Field box can step, draw,
 * step, draw, and [run] is only a loop over [step]. Nothing here starts a thread, and nothing here
 * reads the wall clock; [time] is `tick * fixedStep` and that is the only clock the agents can see.
 *
 * ```
 * val w = ScriptWorld(seed = 7L)
 * repeat(12) { i -> w.spawn(x = 20.0 + i * 12.0, y = 100.0) { forever { … } } }
 * w.run(600)
 * ```
 *
 * The same seed, the same spawns and the same step count give the same [state], bit for bit.
 *
 * @property fixedStep seconds per tick. Also the quantum every [BehaviourScope.wait] is rounded up to,
 *   so it sets the time resolution of every script in the world.
 * @property wrap true for a torus (the metric [BoidFlock] uses, and what `asBoidFlock` assumes);
 *   false to clamp at the walls, which stops agents but does not make them avoid them — that is
 *   [BehaviourSteering.avoidEdges]' job.
 * @property maxParallelPerActor the cap [BehaviourScope.parallel] checks. A behaviour that launches a
 *   sub-behaviour inside a `forever` and never cancels it is the standard way to fill a scheduler with
 *   garbage, and it presents as a gradual slowdown rather than an error. This turns it into an error.
 * @property maxResumptionsPerStep how many behaviour resumptions one step may take before the world
 *   declares a livelock. Catches the two shapes that otherwise hang: a `while (true)` with no `wait`,
 *   and a `wait` short enough to round to zero (it cannot — `wait` has a one-tick floor — but a
 *   hand-rolled `forever` over an empty body would still spin).
 * @property recordTrails whether to accumulate per-agent trails for [trailFLines]. On by default
 *   because the trail is the picture; turn it off for long runs where only the final state matters.
 */
class ScriptWorld(
    val width: Double = 200.0,
    val height: Double = 200.0,
    val fixedStep: Double = 1.0 / 60.0,
    val seed: Long = 1L,
    val wrap: Boolean = true,
    val maxParallelPerActor: Int = 8,
    val maxResumptionsPerStep: Int = 200_000,
    val recordTrails: Boolean = true
) {
    init {
        require(width > 0.0 && height > 0.0) { "world must be positive, got ${width}x$height" }
        require(fixedStep > 0.0 && fixedStep.isFinite()) { "fixedStep must be > 0, got $fixedStep" }
        require(maxParallelPerActor >= 1) { "maxParallelPerActor must be >= 1, got $maxParallelPerActor" }
        require(maxResumptionsPerStep >= 1) { "maxResumptionsPerStep must be >= 1" }
    }

    private val population = ArrayList<ScriptActor>()
    private val roots = ArrayList<BehaviourJob>()
    private val runQueue = ArrayDeque<() -> Unit>()
    private val sleepers = java.util.PriorityQueue<Sleeper>(
        compareBy<Sleeper> { it.wakeTick }.thenBy { it.seq }
    )
    private val eventLog = ArrayList<ScriptEvent>()
    private val failureLog = ArrayList<String>()
    private var seq = 0L
    private var motorKey = 0L
    private var started = false

    /** Steps taken. 0 before the first [step]. */
    var tick: Long = 0L
        private set

    /** Simulation seconds elapsed: exactly `tick * fixedStep`, never a wall-clock reading. */
    val time: Double get() = tick * fixedStep

    /** The population, in spawn order, alive and dead. */
    val actors: List<ScriptActor> get() = population

    /** Live agents, in spawn order. */
    val living: List<ScriptActor> get() = population.filter { it.alive }

    /** Everything the behaviours recorded, in order. See [ScriptEvent]. */
    val events: List<ScriptEvent> get() = eventLog

    /** Drives that threw or returned non-finite vectors, and behaviours that threw. */
    val failures: List<String> get() = failureLog

    /** How many behaviour resumptions the last [step] ran — a load readout, and a livelock warning. */
    var lastStepResumptions: Int = 0
        private set

    // ------------------------------------------------------------------------------------------
    // Population
    // ------------------------------------------------------------------------------------------

    /**
     * Add an agent at ([x], [y]) running [program].
     *
     * The program starts at the *next* scheduler drain — before the first [step] if you spawn before
     * stepping, otherwise on the step after. It is not run inline, so spawning from inside a
     * behaviour cannot reorder the caller.
     *
     * @param vx initial x velocity, world units per second
     * @param vy initial y velocity.
     *
     *   Not cosmetic, and found by measurement: [BehaviourSteering.align] steers toward the *mean
     *   neighbour velocity*, so a population spawned at rest has nothing to align to and the
     *   alignment term contributes exactly zero until something else has started the agents moving.
     *   A 90-agent population spawned at rest reached a polarization of only 0.106 after four
     *   seconds of a flocking script that should have ordered it — not because the steering was
     *   wrong but because the ordered state has to be reached from a *disordered* one, and rest is
     *   not disordered, it is empty. Spawn with random headings to make a self-organisation claim,
     *   the same initial condition [BoidFlock.random] uses and for the same reason.
     * @param program the agent's whole life, as a sequence. Returning from it ends the agent's
     *   scripting (it keeps its velocity and its motor set empties, so it coasts to a halt); most
     *   programs are a [BehaviourScope.forever].
     */
    fun spawn(
        x: Double,
        y: Double,
        maxSpeed: Double = 20.0,
        radius: Double = 1.0,
        steerResponse: Double = 0.25,
        vx: Double = 0.0,
        vy: Double = 0.0,
        tag: String = "",
        program: suspend BehaviourScope.() -> Unit
    ): ScriptActor {
        require(x.isFinite() && y.isFinite()) { "spawn needs finite coordinates, got ($x, $y)" }
        require(maxSpeed.isFinite() && maxSpeed > 0.0) { "maxSpeed must be > 0, got $maxSpeed" }
        require(vx.isFinite() && vy.isFinite()) { "spawn needs a finite velocity, got ($vx, $vy)" }
        require(steerResponse > 0.0 && steerResponse <= 1.0) {
            "steerResponse must be in (0, 1], got $steerResponse"
        }
        val a = ScriptActor(this, population.size, x, y, maxSpeed, radius, steerResponse, tag)
        val m = hypot(vx, vy)
        if (m > maxSpeed) { a.vx = vx / m * maxSpeed; a.vy = vy / m * maxSpeed } else { a.vx = vx; a.vy = vy }
        a.svx = a.vx; a.svy = a.vy
        confine(a)
        population.add(a)
        roots.add(launch(a, null, program))
        return a
    }

    /**
     * Kill [a]: cancel its behaviours (so their `finally` blocks empty its motor set), stop
     * integrating it, and take it out of everyone's neighbour queries.
     *
     * It stays in [actors] with its trail intact, because a picture of a run needs the agents that
     * did not make it.
     */
    fun kill(a: ScriptActor) {
        if (!a.alive) return
        a.alive = false
        a.vx = 0.0; a.vy = 0.0
        record(a.id, "died", "")
        roots.firstOrNull { it.actor === a }?.let { cancelJob(it, "died") }
    }

    /** Live agents other than [self] within [range], in agent-id order, on snapshot positions. */
    fun neighbours(self: ScriptActor, range: Double): List<ScriptActor> {
        require(range.isFinite() && range > 0.0) { "range must be > 0, got $range" }
        val r2 = range * range
        val out = ArrayList<ScriptActor>()
        for (o in population) {
            if (o === self || !o.alive) continue
            val dx = deltaX(self.sx, o.sx)
            val dy = deltaY(self.sy, o.sy)
            if (dx * dx + dy * dy <= r2) out.add(o)
        }
        return out
    }

    // ------------------------------------------------------------------------------------------
    // Geometry
    // ------------------------------------------------------------------------------------------

    /** Signed x offset from [from] to [to], the shorter way round if the world wraps. */
    fun deltaX(from: Double, to: Double): Double =
        if (wrap) wrappedDelta(from, to, width) else to - from

    /** Signed y offset from [from] to [to], the shorter way round if the world wraps. */
    fun deltaY(from: Double, to: Double): Double =
        if (wrap) wrappedDelta(from, to, height) else to - from

    /** Put [a] back inside the arena: wrap if the world is a torus, otherwise clamp. */
    internal fun confine(a: ScriptActor) {
        if (wrap) {
            a.x = wrapInto(a.x, width)
            a.y = wrapInto(a.y, height)
        } else {
            if (a.x < 0.0) { a.x = 0.0; if (a.vx < 0.0) a.vx = 0.0 }
            if (a.y < 0.0) { a.y = 0.0; if (a.vy < 0.0) a.vy = 0.0 }
            if (a.x > width) { a.x = width; if (a.vx > 0.0) a.vx = 0.0 }
            if (a.y > height) { a.y = height; if (a.vy > 0.0) a.vy = 0.0 }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Scheduler
    // ------------------------------------------------------------------------------------------

    internal fun nextSeq(): Long = seq++

    internal fun nextMotorKey(): Long = motorKey++

    internal fun record(actorId: Int, kind: String, detail: String) {
        eventLog.add(ScriptEvent(tick, actorId, kind, detail))
    }

    internal fun launch(
        a: ScriptActor,
        parent: BehaviourJob?,
        block: suspend BehaviourScope.() -> Unit
    ): BehaviourJob {
        val job = BehaviourJob(this, a, parent)
        parent?.children?.add(job)
        val scope = BehaviourScope(this, a, job)
        runQueue.addLast {
            if (job.cancelled) {
                job.done = true
                finish(job)
            } else {
                block.startCoroutine(scope, object : Continuation<Unit> {
                    override val context = EmptyCoroutineContext
                    override fun resumeWith(result: Result<Unit>) {
                        job.done = true
                        job.pending = null
                        val e = result.exceptionOrNull()
                        if (e != null && e !is BehaviourCancelled) {
                            failureLog.add("actor ${a.id}: ${e::class.simpleName}: ${e.message}")
                            record(a.id, "failed", "${e::class.simpleName}: ${e.message}")
                        }
                        finish(job)
                    }
                })
            }
        }
        return job
    }

    /** Detach a finished job from its parent and give back its parallel slot. */
    private fun finish(job: BehaviourJob) {
        val p = job.parent
        if (p != null) {
            p.children.remove(job)
            job.actor.parallelCount = max(0, job.actor.parallelCount - 1)
        }
    }

    internal fun cancelJob(job: BehaviourJob, reason: String) {
        if (job.cancelled || job.done) return
        job.cancelled = true
        // Children first, so inner `finally` blocks run before outer ones.
        for (c in ArrayList(job.children)) cancelJob(c, reason)
        val s = job.pending
        if (s != null) {
            job.pending = null
            sleepers.remove(s)
            runQueue.addLast { s.cont.resumeWithException(BehaviourCancelled(reason)) }
        }
    }

    /** Park [job]'s continuation for [ticks] whole steps. */
    internal suspend fun sleepTicks(job: BehaviourJob, ticks: Long) {
        require(ticks >= 1) { "a wait must be at least one tick, got $ticks" }
        if (job.cancelled) throw BehaviourCancelled("cancelled before waiting")
        if (!job.actor.alive) throw BehaviourCancelled("agent is dead")
        return suspendCoroutine { cont ->
            val s = Sleeper(tick + ticks, nextSeq(), cont, job)
            job.pending = s
            sleepers.add(s)
        }
    }

    /** Convert seconds to whole ticks, rounding **up**, with a one-tick floor. */
    internal fun ticksFor(seconds: Double): Long {
        require(seconds.isFinite() && seconds > 0.0) { "a wait must be finite and > 0, got $seconds" }
        return max(1L, ceil(seconds / fixedStep - 1e-9).toLong())
    }

    private fun drain() {
        var n = 0
        while (runQueue.isNotEmpty()) {
            if (++n > maxResumptionsPerStep) {
                runQueue.clear()
                throw IllegalStateException(
                    "livelock on tick $tick: $maxResumptionsPerStep behaviour resumptions in one step " +
                        "without the queue emptying. A behaviour is looping without waiting — every " +
                        "`forever` body must reach a `wait`, `moveFor` or `rest`."
                )
            }
            runQueue.removeFirst().invoke()
        }
        lastStepResumptions = n
    }

    /** Run every spawned program up to its first suspension point, at tick 0. Idempotent. */
    fun start() {
        if (started) return
        started = true
        snapshot()
        drain()
        if (recordTrails) for (a in population) { a.trailX.add(a.x); a.trailY.add(a.y) }
    }

    // ------------------------------------------------------------------------------------------
    // Stepping
    // ------------------------------------------------------------------------------------------

    /**
     * Advance one tick: wake what is due, run every behaviour to its next suspension point, then
     * integrate.
     *
     * That order matters and is the one thing here that is a choice rather than a consequence.
     * Behaviours run **first**, so a drive registered on this tick is in force on this tick and a
     * `moveFor(1.0)` moves the agent for a full second rather than for a second minus one frame. The
     * alternative — integrate, then decide — makes every behaviour a tick late, which is invisible in
     * a picture and shows up as an off-by-one in any test that counts steps to arrival.
     */
    fun step() {
        start()
        tick++
        while (true) {
            val s = sleepers.peek() ?: break
            if (s.wakeTick > tick) break
            sleepers.poll()
            if (s.job.pending === s) s.job.pending = null
            if (s.job.cancelled) continue
            runQueue.addLast { s.cont.resume(Unit) }
        }
        drain()
        integrate()
        if (recordTrails) for (a in population) { a.trailX.add(a.x); a.trailY.add(a.y) }
    }

    /** [step] applied [steps] times. */
    fun run(steps: Int) {
        require(steps >= 0) { "steps must be >= 0, got $steps" }
        repeat(steps) { step() }
    }

    private fun snapshot() {
        for (a in population) { a.sx = a.x; a.sy = a.y; a.svx = a.vx; a.svy = a.vy }
    }

    private fun integrate() {
        snapshot()
        for (a in population) {
            if (!a.alive) continue
            var dx = 0.0
            var dy = 0.0
            var wsum = 0.0
            var asked = 0.0
            for ((_, m) in a.motors) {
                asked = max(asked, m.speed)
                val sense = ScriptSense(this, a, m.speed)
                for (wd in m.drives) {
                    val v = try {
                        wd.drive.steer(a, sense)
                    } catch (e: Exception) {
                        failureLog.add("actor ${a.id} drive threw: ${e::class.simpleName}: ${e.message}")
                        record(a.id, "failed", "drive ${e::class.simpleName}")
                        continue
                    }
                    if (!v.x.isFinite() || !v.y.isFinite()) {
                        failureLog.add("actor ${a.id} drive returned non-finite (${v.x}, ${v.y})")
                        record(a.id, "failed", "drive non-finite")
                        continue
                    }
                    dx += v.x * wd.weight
                    dy += v.y * wd.weight
                    wsum += wd.weight
                }
            }
            var desX = 0.0
            var desY = 0.0
            if (wsum > 1e-12) {
                desX = dx / wsum
                desY = dy / wsum
                val cap = min(a.maxSpeed, if (asked > 0.0) asked else a.maxSpeed)
                val m = hypot(desX, desY)
                if (m > cap) { desX = desX / m * cap; desY = desY / m * cap }
            }
            a.vx += (desX - a.vx) * a.steerResponse
            a.vy += (desY - a.vy) * a.steerResponse
            val s = hypot(a.vx, a.vy)
            if (s > a.maxSpeed) { a.vx = a.vx / s * a.maxSpeed; a.vy = a.vy / s * a.maxSpeed }
            a.x += a.vx * fixedStep
            a.y += a.vy * fixedStep
            confine(a)
        }
    }

    // ------------------------------------------------------------------------------------------
    // Reproducibility witnesses
    // ------------------------------------------------------------------------------------------

    /**
     * The whole population's state as one array — `x, y, vx, vy` per agent, in agent-id order.
     *
     * The determinism claim is asserted on **this**, with `contentEquals`, not on a summary. A hash
     * can collide and a fitness can tie; an array of every coordinate cannot agree by accident.
     */
    fun state(): DoubleArray {
        val out = DoubleArray(population.size * 4)
        for ((i, a) in population.withIndex()) {
            out[i * 4] = a.x; out[i * 4 + 1] = a.y
            out[i * 4 + 2] = a.vx; out[i * 4 + 3] = a.vy
        }
        return out
    }

    /**
     * A short hex digest of [state] over the **raw bits** of every coordinate — for printing, where a
     * 4N-element array will not fit.
     *
     * Raw bits, not rounded values, so it is exactly as strict as `contentEquals`: two runs whose
     * digests match agree on every coordinate to the last mantissa bit.
     */
    fun stateSignature(): String {
        var h = -0x340d631b7bdddcdbL
        for (v in state()) {
            h = (h xor java.lang.Double.doubleToRawLongBits(v)) * 0x100000001b3L
        }
        return "%016x".format(h)
    }

    /**
     * A digest of the event log — the *symbolic* witness beside the numeric one.
     *
     * Worth having separately because the two can disagree in both directions. Two runs can land on
     * identical coordinates having taken different decisions (drives that cancelled out), and a run
     * whose phase timeline is identical can still have drifted numerically. A claim of
     * reproducibility should cover both.
     */
    fun eventSignature(): String {
        var h = -0x61c8864680b583ebL
        for (e in eventLog) {
            for (c in "${e.tick}|${e.actorId}|${e.kind}|${e.detail}") h = (h xor c.code.toLong()) * 0x100000001b3L
        }
        return "%016x".format(h)
    }

    // ------------------------------------------------------------------------------------------
    // Bridges into the rest of the family
    // ------------------------------------------------------------------------------------------

    /**
     * The live population as a [BoidFlock], so `alife.kt`'s measures — [polarization],
     * [nearestNeighborDistances], [BoidFlock.meanSpeed] — apply to a scripted run unchanged.
     *
     * A **measurement** conversion, one way. The flock's arrays are `Float` and this world's state is
     * `Double`, so stepping the returned flock and reading the result back would quietly lose
     * precision and break the determinism claim. There is deliberately no way back.
     */
    fun asBoidFlock(): BoidFlock {
        val live = living
        require(live.isNotEmpty()) { "asBoidFlock needs at least one live agent" }
        val n = live.size
        val x = FloatArray(n); val y = FloatArray(n)
        val vx = FloatArray(n); val vy = FloatArray(n)
        for (i in 0 until n) {
            x[i] = live[i].x.toFloat(); y[i] = live[i].y.toFloat()
            vx[i] = live[i].vx.toFloat(); vy[i] = live[i].vy.toFloat()
        }
        return BoidFlock(width, height, x, y, vx, vy)
    }

    /**
     * One [FLine] per agent, its trail so far, split wherever it crossed a wrap seam.
     *
     * Split rather than drawn through: a torus is a torus, and a line from `x = 199` to `x = 1` drawn
     * straight is a stroke right across the picture that no agent ever travelled. The split test is a
     * step longer than the fastest agent could move in one tick, which is the only thing that
     * distinguishes a wrap from a fast move.
     *
     * @param stride keep every nth trail point; 1 is all of them
     */
    fun trailFLines(stride: Int = 1, z: Number = 0): List<FLine> {
        require(stride >= 1) { "stride must be >= 1, got $stride" }
        val out = ArrayList<FLine>()
        val jumpX = width * 0.5
        val jumpY = height * 0.5
        for (a in population) {
            var line: FLine? = null
            var px = Double.NaN
            var py = Double.NaN
            var i = 0
            while (i < a.trailX.size) {
                val cx = a.trailX[i]
                val cy = a.trailY[i]
                val broke = !px.isNaN() && (kotlin.math.abs(cx - px) > jumpX || kotlin.math.abs(cy - py) > jumpY)
                val open = line
                if (open == null || broke) {
                    if (open != null && open.nodes.size > 1) out.add(open)
                    line = FLine().also { it.moveTo(cx, cy, z) }
                } else {
                    open.lineTo(cx, cy, z)
                }
                px = cx; py = cy
                i += stride
            }
            line?.let { if (it.nodes.size > 1) out.add(it) }
        }
        return out
    }

    /** Total distance every agent travelled, summed — the "did anything happen at all" readout. */
    fun totalPathLength(): Double {
        var total = 0.0
        for (a in population) {
            for (i in 1 until a.trailX.size) {
                val dx = deltaX(a.trailX[i - 1], a.trailX[i])
                val dy = deltaY(a.trailY[i - 1], a.trailY[i])
                total += hypot(dx, dy)
            }
        }
        return total
    }

    override fun toString(): String =
        "ScriptWorld(${population.size} agents on ${width}x$height, tick $tick, " +
            "%.2f s, %d events)".format(time, eventLog.size)
}

/** Wrap [v] into `[0, n)`. */
private fun wrapInto(v: Double, n: Double): Double {
    var r = v % n
    if (r < 0) r += n
    return r
}

// ==============================================================================================
// The behaviour language
// ==============================================================================================

/**
 * Marks the two scripting receivers, so a steering block cannot accidentally call a suspending
 * behaviour verb on the enclosing scope.
 *
 * Worth the annotation because the mistake it stops is a subtle one rather than a type error. A
 * steering block is built **once**, when the behaviour starts; a `wait` inside it would be a wait
 * inside the construction of a drive list, which is both meaningless and — without this — compiles.
 */
@DslMarker
annotation class ALifeBehaviour

/**
 * The verbs an agent's program is written in: sequencing, waiting on simulation time, repetition,
 * bounded sub-behaviours, named phases, and motion.
 *
 * One of these exists per [BehaviourJob], so `self` and `world` are fixed and a program can be read
 * top to bottom as one agent's life. Every suspending verb here is a cancellation point.
 *
 * The suspension discipline, stated once: **never sleep a thread in a behaviour.** `Thread.sleep`
 * blocks the scheduler that is driving every other agent, and this scheduler is not even on a clock
 * that would notice — [time] advances in [ScriptWorld.step] and nowhere else, so a thread sleep is
 * pure loss. [wait] is the only way to spend time.
 */
@ALifeBehaviour
class BehaviourScope internal constructor(
    val world: ScriptWorld,
    val self: ScriptActor,
    internal val job: BehaviourJob
) {
    /** Simulation seconds since the run began. */
    val time: Double get() = world.time

    /** Steps taken since the run began. */
    val tick: Long get() = world.tick

    /** A fresh copy of this agent's current position. */
    val position: Vector2d get() = self.position

    /** A fresh copy of this agent's current velocity. */
    val velocity: Vector2d get() = self.velocity

    /** The innermost phase currently running, or `null`. */
    val phase: String? get() = self.phase

    /** This agent's own random stream. Per agent, so drawing from it cannot disturb anyone else. */
    val rng: Random get() = self.rng

    // ------------------------------------------------------------------------------------------
    // Time
    // ------------------------------------------------------------------------------------------

    /**
     * Spend [seconds] of **simulation** time, rounded up to a whole number of steps.
     *
     * Use this, or [waitTicks], and never `Thread.sleep` — see the class KDoc. The rounding is up and
     * is the reason the clock is exactly reproducible: at the default 60 Hz, `wait(0.5)` is 30 steps
     * and `wait(0.001)` is 1, always, with no floating-point boundary for a run to land on the wrong
     * side of.
     */
    suspend fun wait(seconds: Double) = world.sleepTicks(job, world.ticksFor(seconds))

    /** Spend exactly [ticks] steps. The primitive [wait] is expressed in. */
    suspend fun waitTicks(ticks: Int) {
        require(ticks >= 1) { "waitTicks needs at least 1 tick, got $ticks" }
        world.sleepTicks(job, ticks.toLong())
    }

    /**
     * Wait until [predicate] holds, testing it once per step, giving up after [maxSeconds].
     *
     * Returns true if it became true, false if it timed out — so the caller decides, rather than the
     * failure being an exception to forget to catch. [predicate] is evaluated between steps, on
     * settled state, and must not suspend or mutate the world.
     */
    suspend fun waitUntil(maxSeconds: Double = 30.0, predicate: () -> Boolean): Boolean {
        val deadline = world.tick + world.ticksFor(maxSeconds)
        while (world.tick < deadline) {
            if (predicate()) return true
            waitTicks(1)
        }
        return predicate()
    }

    // ------------------------------------------------------------------------------------------
    // Control flow
    // ------------------------------------------------------------------------------------------

    /**
     * Repeat [block] for as long as this behaviour is active and the agent is alive.
     *
     * If a pass through [block] spends no simulation time — a body of nothing but synchronous calls —
     * one tick is spent for it. Without that a `forever` whose body forgot to move would spin the
     * scheduler until [ScriptWorld.maxResumptionsPerStep] fired; with it, the body simply runs at one
     * pass per tick, which is almost always what was meant.
     */
    suspend fun forever(block: suspend BehaviourScope.() -> Unit) {
        while (job.isActive && self.alive) {
            val began = world.tick
            block()
            if (world.tick == began) waitTicks(1)
        }
    }

    /** Run [block], then wait [seconds], forever. The period is the *gap*, not the cycle length. */
    suspend fun every(seconds: Double, block: suspend BehaviourScope.() -> Unit) {
        require(seconds.isFinite() && seconds > 0.0) { "every() needs a positive period, got $seconds" }
        while (job.isActive && self.alive) {
            block()
            wait(seconds)
        }
    }

    /** Run [block] [times] times, or until cancelled. */
    suspend fun repeatTimes(times: Int, block: suspend BehaviourScope.(Int) -> Unit) {
        require(times >= 0) { "repeatTimes needs times >= 0, got $times" }
        var i = 0
        while (i < times && job.isActive && self.alive) {
            val began = world.tick
            block(i)
            if (world.tick == began) waitTicks(1)
            i++
        }
    }

    /**
     * Start [block] as a sub-behaviour of this one and carry on immediately.
     *
     * The child is owned by this job: it is cancelled when this one is, when the agent dies, and when
     * the returned [BehaviourJob] is cancelled. It starts at the next scheduler drain, not inline, so
     * launching one cannot reorder the launcher.
     *
     * Capped at [ScriptWorld.maxParallelPerActor] per agent, and the cap is a `check`, not a silent
     * drop. A `parallel` inside a `forever` that is never cancelled is the classic leak here and it
     * presents as a run that gradually gets slower and stranger rather than as an error.
     */
    suspend fun parallel(block: suspend BehaviourScope.() -> Unit): BehaviourJob {
        check(self.parallelCount < world.maxParallelPerActor) {
            "agent ${self.id} already has ${self.parallelCount} parallel behaviours, " +
                "cap is ${world.maxParallelPerActor}. A `parallel` inside a `forever` must be cancelled."
        }
        self.parallelCount++
        return world.launch(self, job, block)
    }

    /**
     * Run [block] with at most [seconds] to finish in. Returns true if it finished, false if it was
     * cut off.
     *
     * Implemented as a child job plus a wait, so an unfinished [block] is *cancelled* — its `finally`
     * blocks run and its drives come out of the motor set. A timeout that left drives behind would be
     * worse than no timeout.
     */
    suspend fun timeout(seconds: Double, block: suspend BehaviourScope.() -> Unit): Boolean {
        val child = parallel(block)
        val deadline = world.tick + world.ticksFor(seconds)
        while (child.isActive && world.tick < deadline) waitTicks(1)
        if (child.isActive) {
            child.cancel("timed out after ${"%.2f".format(seconds)} s")
            return false
        }
        return true
    }

    /**
     * Name what the agent is doing for the duration of [block], and log it.
     *
     * Phases nest and the previous one is restored on the way out, including on cancellation, so
     * [ScriptActor.phase] always names the *innermost active* behaviour rather than the last one to
     * have started. Both the entry and the exit reach [ScriptWorld.events], which makes the phase
     * timeline a thing you can assert on — and it is the readout that shows division of labour, since
     * a population all in one phase at once is a population a single global rule could have produced.
     */
    suspend fun phase(name: String, block: suspend BehaviourScope.() -> Unit) {
        require(name.isNotBlank()) { "a phase needs a name" }
        val previous = self.phase
        self.phaseStack.add(previous)
        self.phase = name
        world.record(self.id, "phase", name)
        try {
            block()
        } finally {
            self.phase = self.phaseStack.removeAt(self.phaseStack.size - 1)
            world.record(self.id, "phase-end", name)
        }
    }

    /** Append a note to [ScriptWorld.events] on this tick. */
    fun log(message: String) = world.record(self.id, "log", message)

    // ------------------------------------------------------------------------------------------
    // Motion
    // ------------------------------------------------------------------------------------------

    /**
     * Steer by [block]'s drives for [seconds], at [speed], then stop steering.
     *
     * The drive set is built **once**, when the behaviour starts, and registered under a fresh key; it
     * is removed in a `finally`, so it goes away on cancellation too. Several `moveFor`s running in
     * [parallel] blend: the motor set is the union, and the blend is a weighted mean of the desired
     * velocities. That is what makes "flee while hugging the wall" a composition rather than a third
     * special case.
     */
    suspend fun moveFor(
        seconds: Double,
        speed: Double = self.maxSpeed,
        block: BehaviourSteering.() -> Unit
    ) {
        require(seconds.isFinite() && seconds > 0.0) { "moveFor needs positive seconds, got $seconds" }
        require(speed.isFinite() && speed > 0.0) { "moveFor needs positive speed, got $speed" }
        val drives = BehaviourSteering(this).apply(block).build()
        val key = world.nextMotorKey()
        self.motors[key] = MotorSet(speed, drives)
        try {
            wait(seconds)
        } finally {
            self.motors.remove(key)
        }
    }

    /**
     * Steer by [block]'s drives until [until] holds or [maxSeconds] runs out. Returns true if [until]
     * became true.
     *
     * The verb most scripts actually want: "go there, and tell me whether you arrived". A plain
     * [moveFor] has to be tuned to the worst-case travel time and then wastes the difference on every
     * other trip.
     */
    suspend fun moveUntil(
        maxSeconds: Double = 10.0,
        speed: Double = self.maxSpeed,
        until: () -> Boolean,
        block: BehaviourSteering.() -> Unit
    ): Boolean {
        require(maxSeconds.isFinite() && maxSeconds > 0.0) { "moveUntil needs positive maxSeconds" }
        require(speed.isFinite() && speed > 0.0) { "moveUntil needs positive speed, got $speed" }
        val drives = BehaviourSteering(this).apply(block).build()
        val key = world.nextMotorKey()
        self.motors[key] = MotorSet(speed, drives)
        try {
            return waitUntil(maxSeconds, until)
        } finally {
            self.motors.remove(key)
        }
    }

    /**
     * Keep the velocity the agent already has, for [seconds] — ballistic motion.
     *
     * Needed because an empty motor set means "desired velocity zero", so an agent with no active
     * `moveFor` brakes. That is the right default (a script that stops scripting should stop) and it
     * is wrong for anything thrown, hence this.
     */
    suspend fun coast(seconds: Double) = moveFor(seconds, self.maxSpeed) { keepVelocity() }

    /** Stop dead and stay stopped for [seconds]. */
    suspend fun rest(seconds: Double) {
        self.vx = 0.0
        self.vy = 0.0
        wait(seconds)
    }

    /**
     * Kill this agent: it stops being integrated and stops being sensed, and its behaviours are
     * cancelled.
     *
     * This *returns* — cancellation lands at the next suspension point, like every other cancellation
     * here — so a program that has more to say after dying will say it. Put `die()` last, or `return`
     * after it.
     */
    fun die() = world.kill(self)

    // ------------------------------------------------------------------------------------------
    // Targets
    // ------------------------------------------------------------------------------------------

    /** A fixed point. Copied on the way in, so a later mutation of [p] cannot move the target. */
    fun point(p: Vector2d): ScriptTarget {
        val x = p.x
        val y = p.y
        return ScriptTarget { _, _ -> Vector2d(x, y) }
    }

    /** A fixed point. */
    fun point(x: Double, y: Double): ScriptTarget = ScriptTarget { _, _ -> Vector2d(x, y) }

    /** Another agent — resolving to `null` once it is dead, so the pull simply stops. */
    fun actor(other: ScriptActor): ScriptTarget {
        require(other.world === world) { "agent ${other.id} belongs to a different world" }
        return ScriptTarget { _, _ -> if (other.alive) Vector2d(other.sx, other.sy) else null }
    }

    /**
     * The nearest live agent within [range] satisfying [accept], re-chosen every step.
     *
     * Ties break on the lower agent id, because "the nearest" over a set of equidistant candidates is
     * otherwise a coin flip weighted by iteration order, and that is exactly the kind of thing that
     * makes a run irreproducible without looking like it would.
     */
    fun nearest(range: Double = max(world.width, world.height), accept: (ScriptActor) -> Boolean = { true }): ScriptTarget =
        ScriptTarget { w, me ->
            var best: ScriptActor? = null
            var bestD = Double.MAX_VALUE
            for (o in w.neighbours(me, range)) {
                if (!accept(o)) continue
                val d = hypot(w.deltaX(me.sx, o.sx), w.deltaY(me.sy, o.sy))
                if (d < bestD) { bestD = d; best = o }
            }
            best?.let { Vector2d(it.sx, it.sy) }
        }

    /**
     * The centroid of the live agents satisfying [accept], on snapshot positions.
     *
     * A plain mean of coordinates, so on a wrapping world it is the mean in the *unwrapped* frame and
     * a population spread across the seam has a centroid in the middle of the arena rather than on
     * the seam. That is the honest thing for a `cohere`-style pull within a cluster and the wrong
     * thing for a population that is genuinely everywhere; it returns `null` when nothing matches.
     */
    fun centroid(accept: (ScriptActor) -> Boolean = { true }): ScriptTarget = ScriptTarget { w, _ ->
        var sx = 0.0
        var sy = 0.0
        var n = 0
        for (o in w.actors) {
            if (!o.alive || !accept(o)) continue
            sx += o.sx; sy += o.sy; n++
        }
        if (n == 0) null else Vector2d(sx / n, sy / n)
    }

    /** True when this agent is within [distance] of [t]'s current position. */
    fun near(t: ScriptTarget, distance: Double): Boolean {
        val p = t.resolve(world, self) ?: return false
        return self.distanceTo(p) <= distance
    }

    /** True when this agent is within [distance] of ([x], [y]). */
    fun near(x: Double, y: Double, distance: Double): Boolean = self.distanceTo(x, y) <= distance

    internal fun resolve(t: ScriptTarget): Vector2d? = t.resolve(world, self)
}

/**
 * The ingredients a [BehaviourScope.moveFor] blends: each verb adds one weighted [ScriptDrive].
 *
 * Built once per behaviour, not per step — the block runs when the behaviour starts, the drives it
 * produced run every step until the behaviour ends. So capture what should be fixed (a heading locked
 * at the start of a dash) in the block, and read through a [ScriptTarget] what should track.
 *
 * Every drive added here is wrapped so that a throw or a non-finite return is logged against the
 * agent and contributes nothing, rather than taking the run down. One bad drive in a population of
 * three hundred should cost you one agent's motion and a line in [ScriptWorld.failures].
 */
@ALifeBehaviour
class BehaviourSteering internal constructor(private val scope: BehaviourScope) {
    private val drives = ArrayList<WeightedDrive>()
    private val world = scope.world

    internal fun build(): List<WeightedDrive> = drives.toList()

    /** Add a custom drive. The escape hatch every one of the verbs below is built on. */
    fun drive(weight: Double = 1.0, drive: ScriptDrive) {
        drives.add(WeightedDrive(drive, weight))
    }

    /** Keep the velocity the agent already has. See [BehaviourScope.coast]. */
    fun keepVelocity(weight: Double = 1.0) = drive(weight) { _, sense -> sense.velocity }

    /** Hold still — a desired velocity of zero, which the agent decays toward. */
    fun stop(weight: Double = 1.0) = drive(weight) { _, _ -> Vector2d(0.0, 0.0) }

    /** Travel along a fixed [heading], normalised here so its length does not become a second gain. */
    fun direction(heading: Vector2d, weight: Double = 1.0) {
        val m = hypot(heading.x, heading.y)
        require(m > 1e-12) { "direction() needs a non-zero heading" }
        val ux = heading.x / m
        val uy = heading.y / m
        drive(weight) { _, sense -> Vector2d(ux * sense.speed, uy * sense.speed) }
    }

    /** Travel along the heading [radians] measures anticlockwise from +x. */
    fun heading(radians: Double, weight: Double = 1.0) {
        require(radians.isFinite()) { "heading must be finite, got $radians" }
        drive(weight) { _, sense -> Vector2d(cos(radians) * sense.speed, sin(radians) * sense.speed) }
    }

    /** Head straight for [t] at full speed. */
    fun seek(t: ScriptTarget, weight: Double = 1.0) = drive(weight) { self, sense ->
        val goal = t.resolve(world, self)
        if (goal == null) Vector2d(0.0, 0.0) else {
            val dx = world.deltaX(self.sx, goal.x)
            val dy = world.deltaY(self.sy, goal.y)
            val d = hypot(dx, dy)
            if (d < 1e-9) Vector2d(0.0, 0.0) else Vector2d(dx / d * sense.speed, dy / d * sense.speed)
        }
    }

    /**
     * Head for [t], easing off inside [slowRadius] and stopping at [stopRadius].
     *
     * The difference from [seek] is worth a separate verb: a `seek` at an arriving agent's speed
     * overshoots and comes back, and a population of overshooting agents jitters around its target in
     * a way that looks like a bug in the steering rather than the absence of a brake.
     */
    fun arrive(t: ScriptTarget, slowRadius: Double = 12.0, stopRadius: Double = 0.5, weight: Double = 1.0) {
        require(slowRadius > stopRadius && stopRadius >= 0.0) {
            "arrive needs slowRadius > stopRadius >= 0, got $slowRadius and $stopRadius"
        }
        drive(weight) { self, sense ->
            val goal = t.resolve(world, self)
            if (goal == null) Vector2d(0.0, 0.0) else {
                val dx = world.deltaX(self.sx, goal.x)
                val dy = world.deltaY(self.sy, goal.y)
                val d = hypot(dx, dy)
                when {
                    d <= stopRadius -> Vector2d(0.0, 0.0)
                    d >= slowRadius -> Vector2d(dx / d * sense.speed, dy / d * sense.speed)
                    else -> {
                        val s = sense.speed * (d - stopRadius) / (slowRadius - stopRadius)
                        Vector2d(dx / d * s, dy / d * s)
                    }
                }
            }
        }
    }

    /** Head directly away from [t]. */
    fun flee(t: ScriptTarget, weight: Double = 1.0) = drive(weight) { self, sense ->
        val from = t.resolve(world, self)
        if (from == null) Vector2d(0.0, 0.0) else {
            val dx = -world.deltaX(self.sx, from.x)
            val dy = -world.deltaY(self.sy, from.y)
            val d = hypot(dx, dy)
            if (d < 1e-9) Vector2d(0.0, 0.0) else Vector2d(dx / d * sense.speed, dy / d * sense.speed)
        }
    }

    /**
     * Circle [t] at [distance], blending a tangential component with a radial correction.
     *
     * Which way round is decided by [clockwise], and a population that all orbits the same way is a
     * population that never mixes; pass `self.id % 2 == 0` for a script that wants both.
     */
    fun orbit(t: ScriptTarget, distance: Double = 20.0, clockwise: Boolean = true, weight: Double = 1.0) {
        require(distance.isFinite() && distance > 0.0) { "orbit distance must be > 0, got $distance" }
        val sign = if (clockwise) 1.0 else -1.0
        drive(weight) { self, sense ->
            val goal = t.resolve(world, self)
            if (goal == null) Vector2d(0.0, 0.0) else {
                val dx = world.deltaX(goal.x, self.sx)
                val dy = world.deltaY(goal.y, self.sy)
                val d = hypot(dx, dy)
                // On top of the target there is no radial direction; pick one from the id so the
                // choice is reproducible rather than whatever the arithmetic happened to produce.
                val rx = if (d > 1e-9) dx / d else if (self.id % 2 == 0) 1.0 else -1.0
                val ry = if (d > 1e-9) dy / d else 0.0
                val tx = ry * sign
                val ty = -rx * sign
                val pull = ((distance - d) / distance * 2.0).coerceIn(-1.5, 1.5)
                val cx = tx + rx * pull
                val cy = ty + ry * pull
                val m = hypot(cx, cy)
                if (m < 1e-9) Vector2d(0.0, 0.0) else Vector2d(cx / m * sense.speed, cy / m * sense.speed)
            }
        }
    }

    /**
     * Drift, turning by at most [turn] radians per second, from **this agent's** random stream.
     *
     * A random *heading* each step is not wander, it is noise: the desired velocities cancel and the
     * agent sits still shaking. This integrates a heading, which is the standard fix and the reason
     * the drive has to carry state of its own between steps.
     */
    fun wander(turn: Double = 2.0, weight: Double = 1.0) {
        require(turn.isFinite() && turn > 0.0) { "wander turn must be > 0, got $turn" }
        var angle = Double.NaN
        drive(weight) { self, sense ->
            if (angle.isNaN()) {
                angle = if (hypot(self.svx, self.svy) > 1e-9) kotlin.math.atan2(self.svy, self.svx)
                else self.rng.nextDouble() * 2.0 * Math.PI
            }
            angle += (self.rng.nextDouble() - 0.5) * 2.0 * turn * world.fixedStep
            Vector2d(cos(angle) * sense.speed, sin(angle) * sense.speed)
        }
    }

    /**
     * Push away from live agents within [range], weighted by how close they are — Reynolds'
     * separation, the same term `BoidParams.separationWeight` scales inside [BoidFlock.step].
     */
    fun separate(range: Double = 6.0, weight: Double = 0.8) {
        require(range.isFinite() && range > 0.0) { "separate range must be > 0, got $range" }
        drive(weight) { self, sense ->
            var ax = 0.0
            var ay = 0.0
            for (o in sense.neighbours(range)) {
                val dx = -world.deltaX(self.sx, o.sx)
                val dy = -world.deltaY(self.sy, o.sy)
                val d = hypot(dx, dy)
                if (d < 1e-9) {
                    // Exactly coincident: separate along a direction fixed by the ids, not by chance.
                    ax += if (self.id < o.id) -1.0 else 1.0
                } else {
                    val f = 1.0 - d / range
                    ax += dx / d * f
                    ay += dy / d * f
                }
            }
            val m = hypot(ax, ay)
            if (m < 1e-9) Vector2d(0.0, 0.0) else Vector2d(ax / m * sense.speed, ay / m * sense.speed)
        }
    }

    /** Match the mean heading of live agents within [range] — Reynolds' alignment term. */
    fun align(range: Double = 15.0, weight: Double = 1.0) {
        require(range.isFinite() && range > 0.0) { "align range must be > 0, got $range" }
        drive(weight) { _, sense ->
            var ax = 0.0
            var ay = 0.0
            var n = 0
            for (o in sense.neighbours(range)) { ax += o.svx; ay += o.svy; n++ }
            if (n == 0) return@drive Vector2d(0.0, 0.0)
            val m = hypot(ax, ay)
            if (m < 1e-9) Vector2d(0.0, 0.0) else Vector2d(ax / m * sense.speed, ay / m * sense.speed)
        }
    }

    /** Steer toward the centroid of live agents within [range] — Reynolds' cohesion term. */
    fun cohere(range: Double = 15.0, weight: Double = 0.7) {
        require(range.isFinite() && range > 0.0) { "cohere range must be > 0, got $range" }
        drive(weight) { self, sense ->
            var ax = 0.0
            var ay = 0.0
            var n = 0
            for (o in sense.neighbours(range)) {
                ax += world.deltaX(self.sx, o.sx)
                ay += world.deltaY(self.sy, o.sy)
                n++
            }
            if (n == 0) return@drive Vector2d(0.0, 0.0)
            val m = hypot(ax / n, ay / n)
            if (m < 1e-9) Vector2d(0.0, 0.0) else Vector2d(ax / n / m * sense.speed, ay / n / m * sense.speed)
        }
    }

    /**
     * Turn away from the arena edges within [margin] — for a world with `wrap = false`, where
     * [ScriptWorld.confine] otherwise pins an agent against a wall with its velocity zeroed.
     *
     * Does nothing on a wrapping world, which has no edges; adding it there is harmless rather than
     * a mistake, so a script can be written once for both.
     */
    fun avoidEdges(margin: Double = 15.0, weight: Double = 1.0) {
        require(margin.isFinite() && margin > 0.0) { "avoidEdges margin must be > 0, got $margin" }
        drive(weight) { self, sense ->
            if (world.wrap) Vector2d(0.0, 0.0) else {
                var ax = 0.0
                var ay = 0.0
                if (self.sx < margin) ax += (margin - self.sx) / margin
                if (self.sy < margin) ay += (margin - self.sy) / margin
                if (self.sx > world.width - margin) ax -= (self.sx - (world.width - margin)) / margin
                if (self.sy > world.height - margin) ay -= (self.sy - (world.height - margin)) / margin
                Vector2d(ax * sense.speed, ay * sense.speed)
            }
        }
    }
}
