package afield.examples.stdlib

import afield.graphics.FLine
import afield.stdlib.BehaviourJob
import afield.stdlib.BoidFlock
import afield.stdlib.BoidParams
import afield.stdlib.Img
import afield.stdlib.ScriptWorld
import afield.stdlib.linalg.Vector2d
import afield.stdlib.montage
import afield.stdlib.polarization
import afield.stdlib.rasterizeFLines
import afield.stdlib.rasterizeFLinesFramed
import afield.stdlib.savePng
import afield.stdlib.step
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.system.exitProcess

/*
FIELD_TEST
id: stdlib.alife.script.gallery
runner: jvm-main
entrypoint: afield.examples.stdlib.AlifeScriptGallery
tags: [smoke, stdlib, alife, script, behaviour, capture, cpu]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "alife script gallery rendered"
*/

/**
 * Renders — and *verifies* — the two claims `alife_script.kt` makes: that a scripted run is
 * reproducible, and that per-agent scripting buys something a global rule cannot.
 *
 * Modelled on [AlifeEvolveGallery], and for the same reason: a picture of agents moving around is
 * easy to produce and says nothing, because agents moving around is what agents do. So every claim
 * here is stated **against a control at the same budget in the same substrate**, and every margin is
 * asserted as a floor rather than printed.
 *
 * The order matters. [figurePremise] runs first and asserts the *scheduler* behaves, on behaviours
 * whose correct answer is known by counting rather than by simulating: a `wait(0.5)` at 60 Hz has to
 * be exactly 30 steps, a nested phase has to restore its parent, a cancelled `moveFor` has to take
 * its drives back out, the parallel cap has to throw. If a later figure's environment drifts, that
 * figure fails loudly instead of quietly becoming a tautology where nothing moves and everything
 * "agrees".
 *
 *  - [figurePremise] — the scheduler's arithmetic and the lifecycle guarantees.
 *  - [figureDeterminism] — the same seed gives the same `DoubleArray`, at every checkpoint, twice;
 *    and — the half that stops this being trivial — **a different seed does not**. A frozen
 *    simulation passes the first test perfectly.
 *  - [figureCounterfactual] — the foraging task. A scripted population against two controls, with
 *    floors under the margin *and* floors under both arms' path lengths, so blank-against-blank
 *    cannot pass at zero difference.
 *  - [figureRegime] — a scripted population switching between flocking and scattering on its own
 *    clock, measured with `alife.kt`'s [polarization] through [ScriptWorld.asBoidFlock]. The figure
 *    worth looking at, and the one that shows the bridge is real rather than decorative.
 *
 * As a FIELD_TEST it renders to a temp dir and writes no committed assets. Pass `--out DIR` (or a
 * bare first argument, or set `ALIFE_SCRIPT_DIR`) to put the PNGs somewhere that survives, and
 * `--only name,name` to iterate on one figure.
 */
object AlifeScriptGallery {

    // ==========================================================================================
    // Verification plumbing — same shape as AlifeEvolveGallery's, deliberately
    // ==========================================================================================

    private val failures = ArrayList<String>()
    private var checks = 0

    private fun fail(message: String) {
        failures.add(message)
        println("[alife-script] FAIL $message")
    }

    private fun check(condition: Boolean, message: String) {
        checks++
        if (!condition) fail(message) else println("[alife-script] ok  $message")
    }

    private fun f(v: Double, digits: Int = 3): String = "%.${digits}f".format(v)

    // ==========================================================================================
    // The arena the last three figures share
    // ==========================================================================================

    private const val ARENA = 200.0
    private const val HZ = 60.0

    /** Six foraging sites on a ring, the task the counterfactual is about. */
    private val SITES: List<Vector2d> = (0 until 6).map {
        val a = 2.0 * PI * it / 6.0
        Vector2d(100.0 + cos(a) * 62.0, 100.0 + sin(a) * 62.0)
    }

    /**
     * How close counts as being at a site.
     *
     * A site is a *region*, not a point, and the radius has to be stated before the numbers are
     * measured or it becomes a free parameter to tune until the margin looks good. 12 units on a
     * 200-unit arena is a tenth of the ring's circumference — wide enough that arriving agents
     * register and narrow enough that two adjacent sites never both claim one agent (they are 62
     * units apart).
     */
    private const val SITE_RADIUS = 12.0

    /** Steps every arm of the counterfactual runs for: 900 at 60 Hz is 15 simulation seconds. */
    private const val FORAGE_STEPS = 900

    /** Agents in every arm of the counterfactual. */
    private const val FORAGE_AGENTS = 24

    // ==========================================================================================
    // 1 — the premise: the scheduler itself
    // ==========================================================================================

    /**
     * The scheduler's guarantees, on behaviours whose right answer is arithmetic.
     *
     * Everything else in this file is a simulation and can only be checked against a control. These
     * are the properties that are *counted*, so they are checked absolutely, and they are checked
     * first: a determinism result on a world whose clock had quietly started rounding down would
     * still be perfectly reproducible and completely wrong.
     */
    private fun figurePremise(outDir: File) {
        require(outDir.isDirectory)

        // --- the clock rounds up to whole ticks, and that is exact ---------------------------
        run {
            val w = ScriptWorld(fixedStep = 1.0 / HZ, seed = 1L)
            val ticksAt = ArrayList<Long>()
            w.spawn(10.0, 10.0) {
                ticksAt.add(tick)          // 0
                wait(0.5); ticksAt.add(tick)   // 30
                wait(0.5); ticksAt.add(tick)   // 60
                waitTicks(7); ticksAt.add(tick) // 67
                wait(1e-9); ticksAt.add(tick)  // 68 — a sub-tick wait is one tick, never zero
                wait(0.019); ticksAt.add(tick) // 70 — 1.14 ticks rounds UP to 2
            }
            w.run(200)
            check(
                ticksAt == listOf(0L, 30L, 60L, 67L, 68L, 70L),
                "clock: waits land on exactly the ticks arithmetic says ($ticksAt)"
            )
            check(
                abs(w.time - 200.0 / HZ) < 1e-12,
                "clock: time is tick*fixedStep and nothing else (${f(w.time, 6)} s after 200 steps)"
            )
        }

        // --- phases nest and restore, including through a cancellation -----------------------
        run {
            val w = ScriptWorld(seed = 2L)
            val seen = ArrayList<String?>()
            val a = w.spawn(10.0, 10.0) {
                phase("outer") {
                    seen.add(phase)
                    phase("inner") { seen.add(phase); wait(0.1) }
                    seen.add(phase)
                    wait(0.1)
                }
                seen.add(phase)
            }
            w.run(30)
            check(
                seen == listOf("outer", "inner", "outer", null),
                "phase: nests and restores the parent on the way out ($seen)"
            )
            check(a.phase == null, "phase: an agent between phases has no phase")
            val kinds = w.events.filter { it.kind.startsWith("phase") }.map { "${it.kind}:${it.detail}" }
            check(
                kinds == listOf("phase:outer", "phase:inner", "phase-end:inner", "phase-end:outer"),
                "phase: both ends of both phases reach the event log ($kinds)"
            )
        }

        // --- a cancelled moveFor takes its drives back out -----------------------------------
        run {
            val w = ScriptWorld(seed = 3L)
            var job: BehaviourJob? = null
            val a = w.spawn(10.0, 10.0) {
                job = parallel { moveFor(10.0, 30.0) { heading(0.0) } }
                wait(5.0)
            }
            w.run(30)
            check(a.driveCount == 1, "cancel: the parallel behaviour's drive is registered (${a.driveCount})")
            check(a.speed > 1.0, "cancel: and the agent is actually moving under it (${f(a.speed, 2)})")
            job!!.cancel("test")
            w.step()
            check(
                a.driveCount == 0,
                "cancel: cancelling the behaviour removed its drive via `finally` (${a.driveCount})"
            )
            w.run(120)
            check(
                a.speed < 0.5,
                "cancel: and with no drives the agent decays to a stop (${f(a.speed, 3)})"
            )
            check(w.failures.isEmpty(), "cancel: a cancellation is not reported as a failure (${w.failures})")
        }

        // --- timeout cancels an unfinished behaviour and reports it ---------------------------
        run {
            val w = ScriptWorld(seed = 4L)
            val outcomes = ArrayList<Boolean>()
            w.spawn(10.0, 10.0) {
                outcomes.add(timeout(0.2) { wait(5.0) })   // cannot finish
                outcomes.add(timeout(5.0) { wait(0.2) })   // finishes
            }
            w.run(400)
            check(outcomes == listOf(false, true), "timeout: reports which of the two happened ($outcomes)")
        }

        // --- moveUntil reports arrival rather than hoping ------------------------------------
        run {
            val w = ScriptWorld(seed = 5L, wrap = false)
            val arrived = ArrayList<Boolean>()
            w.spawn(10.0, 100.0, maxSpeed = 40.0, steerResponse = 1.0) {
                val goal = point(150.0, 100.0)
                arrived.add(moveUntil(maxSeconds = 10.0, until = { near(goal, 3.0) }) { seek(goal) })
                // 40 units/s cannot cross 130 units in a tenth of a second, so this one must time out.
                val far = point(20.0, 195.0)
                arrived.add(moveUntil(maxSeconds = 0.1, until = { near(far, 1.0) }) { seek(far) })
            }
            w.run(700)
            check(arrived == listOf(true, false), "moveUntil: true on arrival, false on timeout ($arrived)")
        }

        // --- the parallel cap is a check, not a silent drop ----------------------------------
        run {
            val w = ScriptWorld(seed = 6L, maxParallelPerActor = 3)
            w.spawn(10.0, 10.0) {
                repeatTimes(5) { parallel { wait(100.0) } }
            }
            w.run(20)
            check(
                w.failures.size == 1 && w.failures[0].contains("parallel"),
                "parallel: exceeding the per-agent cap fails loudly (${w.failures})"
            )
        }

        // --- a `forever` whose body spends no time still costs a tick -------------------------
        run {
            val w = ScriptWorld(seed = 7L)
            var passes = 0
            w.spawn(10.0, 10.0) { forever { passes++ } }
            w.run(10)
            check(
                passes == 11,
                "forever: a body that spends no simulation time gets one tick spent for it, so it " +
                    "runs once per step rather than spinning the scheduler (11 passes over 10 " +
                    "steps plus the one at tick 0, got $passes)"
            )
        }

        // --- flooding the scheduler inside one step is an error, not a slowdown ---------------
        run {
            // The livelock the guard is really for: many behaviours resuming in one drain. (A bare
            // `while (true) {}` with no suspension point at all cannot be caught by a queue counter
            // — it never returns to the queue — and cannot be written as a lambda that returns.)
            val spin = ScriptWorld(seed = 8L, maxParallelPerActor = 20_000, maxResumptionsPerStep = 200)
            spin.spawn(10.0, 10.0) {
                for (j in 0 until 5_000) parallel { waitTicks(1) }
                wait(1.0)
            }
            val threw = runCatching { spin.run(2) }.exceptionOrNull()
            check(
                threw is IllegalStateException && (threw.message ?: "").contains("livelock"),
                "livelock: 5 000 sub-behaviours launched in one step trips the resumption guard " +
                    "with a message that says what to do (${threw?.message?.take(80)})"
            )
        }

        // --- death cancels, and a dead agent stops being sensed -------------------------------
        run {
            val w = ScriptWorld(seed = 9L)
            val after = ArrayList<String>()
            val a = w.spawn(10.0, 10.0) {
                moveFor(1.0, 20.0) { heading(0.0) }
                after.add("moved")
                die()
                wait(1.0)
                after.add("should never appear")
            }
            val b = w.spawn(50.0, 10.0) { forever { wait(1.0) } }
            w.run(120)
            check(!a.alive && after == listOf("moved"), "death: cancels the program at its next wait ($after)")
            check(a.driveCount == 0, "death: empties the motor set")
            check(
                w.neighbours(b, 200.0).none { it === a },
                "death: a dead agent is not a neighbour any more"
            )
            check(
                w.events.any { it.actorId == a.id && it.kind == "died" },
                "death: is recorded in the event log"
            )
        }
    }

    // ==========================================================================================
    // 2 — determinism, and its anti-tautology half
    // ==========================================================================================

    /**
     * A scripted run of one program from one seed twice, compared on every coordinate.
     *
     * Compared with `contentEquals` on [ScriptWorld.state] rather than on a summary, because that is
     * the strongest available form of the claim: a digest can collide, a mean can tie, an array of
     * every agent's position and velocity cannot agree by accident. Checked at four checkpoints
     * rather than only at the end, so a divergence that later re-converges — two agents swapping,
     * which a symmetric population makes entirely possible — is still caught.
     *
     * Then the half that makes the first half mean something. A world whose agents never moved would
     * pass every reproducibility check perfectly, and so would a world whose scripts all deadlocked
     * on the first tick, so the run is required to have *done* something — a floor under the travel
     * and under the phase count — and to be genuinely sensitive to its initial conditions, which a
     * fixed point is not.
     *
     * The sensitivity check is to a **perturbed start**, not to a different seed, and the reason is a
     * measurement that surprised me. Seeds 11 and 12 produce byte-identical foraging runs, because
     * nothing in the foraging script ever draws a random number: the per-agent streams exist and are
     * never read. That is worth knowing rather than working around — a seed here is an input to
     * `wander` and to nothing else, so a script without `wander` is reproducible across seeds as
     * well as within one. The seed's *effect* is asserted separately, on a world that does wander.
     */
    private fun figureDeterminism(outDir: File) {
        require(outDir.isDirectory)
        val checkpoints = listOf(1, 60, 300, FORAGE_STEPS)

        val a = forageWorld(seed = 11L, scripted = true)
        val b = forageWorld(seed = 11L, scripted = true)
        var previous = 0
        for (cp in checkpoints) {
            a.run(cp - previous)
            b.run(cp - previous)
            previous = cp
            check(
                a.state().contentEquals(b.state()),
                "determinism: ${FORAGE_AGENTS} scripted agents agree on all ${a.state().size} " +
                    "coordinates at step $cp (${a.stateSignature()})"
            )
        }
        check(
            a.eventSignature() == b.eventSignature() && a.events.size == b.events.size,
            "determinism: and on the whole ${a.events.size}-event phase timeline (${a.eventSignature()})"
        )
        check(
            a.failures.isEmpty() && b.failures.isEmpty(),
            "determinism: with no behaviour failures on either run (${a.failures.take(2)})"
        )

        // --- the anti-tautology half ---------------------------------------------------------
        val perturbed = forageWorld(seed = 11L, scripted = true, nudge = 1e-3)
        perturbed.run(FORAGE_STEPS)
        check(
            !a.state().contentEquals(perturbed.state()),
            "determinism: nudging one agent's spawn by 1e-3 units changes the run, so the agreement " +
                "above is a reproducible simulation and not a fixed point agreeing with itself"
        )

        // The seed's actual job, on a script that draws from it. Both directions, because "two seeds
        // differ" is also what a broken rng that returned garbage would report.
        val w1 = wanderWorld(41L)
        val w2 = wanderWorld(41L)
        val w3 = wanderWorld(42L)
        w1.run(240); w2.run(240); w3.run(240)
        check(
            w1.state().contentEquals(w2.state()),
            "determinism: a wandering population — which does draw from the seed — reproduces under " +
                "the same seed (${w1.stateSignature()})"
        )
        check(
            !w1.state().contentEquals(w3.state()),
            "determinism: and does not under a different one (${w3.stateSignature()}), so the " +
                "per-agent random streams are wired to the seed rather than being ignored"
        )

        val path = a.totalPathLength()
        check(
            path > DETERMINISM_PATH_FLOOR,
            "determinism: the reproduced run actually went somewhere — ${f(path, 0)} units of travel " +
                "across $FORAGE_AGENTS agents over $FORAGE_STEPS steps, floor $DETERMINISM_PATH_FLOOR"
        )
        val phases = a.events.count { it.kind == "phase" }
        check(
            phases > DETERMINISM_PHASE_FLOOR,
            "determinism: and actually did something scripted — $phases phase entries, " +
                "floor $DETERMINISM_PHASE_FLOOR"
        )
    }

    /**
     * Floor under the travel the reproducible run has to show.
     *
     * Measured 6 706 units for 24 agents over 900 steps; floored at 3 500, a little over half. Not a
     * tight fence on purpose — its job is to fail when the population stops moving, which would make
     * every determinism check above pass vacuously, and a tight fence would instead fail every time
     * the steering is tuned.
     */
    private const val DETERMINISM_PATH_FLOOR: Double = 3500.0

    /** Same idea for the scripting: measured 228 phase entries, floored at 120. */
    private const val DETERMINISM_PHASE_FLOOR: Int = 120

    /**
     * A population whose only drive is [afield.stdlib.BehaviourSteering.wander] — the world the seed
     * actually reaches, and so the only place a seed-sensitivity claim can be made.
     */
    private fun wanderWorld(seed: Long): ScriptWorld {
        val w = ScriptWorld(width = ARENA, height = ARENA, fixedStep = 1.0 / HZ, seed = seed, wrap = true)
        for (i in 0 until 12) {
            w.spawn(40.0 + i * 10.0, 100.0, maxSpeed = 25.0, tag = "wander") {
                forever { moveFor(2.0, 25.0) { wander(turn = 8.0) } }
            }
        }
        return w
    }

    // ==========================================================================================
    // 3 — the counterfactual: what scripting buys
    // ==========================================================================================

    /**
     * The foraging task, three ways.
     *
     * Six sites on a ring; 24 agents; 15 simulation seconds. The measurements are **positional and
     * arm-neutral** — how many sites have an agent within [SITE_RADIUS] of them, averaged over every
     * step, and how many distinct sites were ever reached. Deliberately not read off the phase log,
     * because two of the three arms have no phases and a metric only one arm can score on is not a
     * comparison.
     *
     * The arms:
     *
     *  - **scripted** — each agent walks its own rotation of the site list, so agent `i` starts at
     *    site `i mod 6`. Per-agent state, in the continuation.
     *  - **global** — the *strong* control, and the one the claim is really against: the same
     *    substrate, the same integrator, the same speeds, the same clock, the same steering verbs,
     *    but **one rule with one parameter block** — every agent steers at whichever site the global
     *    clock currently nominates. This is the shape `BoidFlock.step(p: BoidParams)` has, expressed
     *    in this layer, and it is what a global rule can do about a task like this.
     *  - **boids** — `BoidFlock.step`, the existing global-rule flock, with no notion of the task at
     *    all. A weaker control and a necessary one: it says how much of the score is available by
     *    simply moving around, and its integrator is its own (per-step velocities, `Float` state), so
     *    it is a bound rather than a matched comparison and is reported as one.
     *
     * The claim is not that scripted agents move more. It is that **they divide the work**: the
     * global arm can put everyone at one site, and cannot put four agents at each of six.
     */
    private fun figureCounterfactual(outDir: File) {
        val scripted = forageWorld(seed = 21L, scripted = true)
        val global = forageWorld(seed = 21L, scripted = false)
        val scriptedOcc = Occupancy()
        val globalOcc = Occupancy()
        repeat(FORAGE_STEPS) {
            scripted.step(); scriptedOcc.sampleWorld(scripted)
            global.step(); globalOcc.sampleWorld(global)
        }

        val flock = BoidFlock.random(FORAGE_AGENTS, ARENA, ARENA, speed = 1.4, seed = 21L)
        val boidOcc = Occupancy()
        var boidPath = 0.0
        val px = flock.x.copyOf(); val py = flock.y.copyOf()
        repeat(FORAGE_STEPS) {
            flock.step(BoidParams())
            boidOcc.sampleFlock(flock)
            for (i in 0 until FORAGE_AGENTS) {
                boidPath += hypot(
                    wrapDelta(px[i].toDouble(), flock.x[i].toDouble(), ARENA),
                    wrapDelta(py[i].toDouble(), flock.y[i].toDouble(), ARENA)
                )
                px[i] = flock.x[i]; py[i] = flock.y[i]
            }
        }

        println("[alife-script] arm          meanBreadth  sitesReached  travel")
        for ((name, occ, path) in listOf(
            Triple("scripted", scriptedOcc, scripted.totalPathLength()),
            Triple("global  ", globalOcc, global.totalPathLength()),
            Triple("boids   ", boidOcc, boidPath)
        )) {
            println(
                "[alife-script] $name     ${f(occ.meanBreadth, 3).padStart(11)}  " +
                    "${occ.everReached.toString().padStart(12)}  ${f(path, 0).padStart(6)}"
            )
        }

        // --- the non-emptiness floor: blank against blank cannot pass -------------------------
        check(
            scripted.totalPathLength() > ARM_PATH_FLOOR,
            "counterfactual: the scripted arm moved — ${f(scripted.totalPathLength(), 0)} units, " +
                "floor $ARM_PATH_FLOOR"
        )
        check(
            global.totalPathLength() > ARM_PATH_FLOOR,
            "counterfactual: the global arm moved too, so this is not a live arm against a dead one " +
                "— ${f(global.totalPathLength(), 0)} units, floor $ARM_PATH_FLOOR"
        )
        check(
            boidPath > ARM_PATH_FLOOR,
            "counterfactual: the boid arm moved too — ${f(boidPath, 0)} units, floor $ARM_PATH_FLOOR"
        )
        check(
            globalOcc.meanBreadth > GLOBAL_BREADTH_FLOOR,
            "counterfactual: the global arm is not scoring zero either — mean breadth " +
                "${f(globalOcc.meanBreadth, 3)}, floor $GLOBAL_BREADTH_FLOOR. A control that " +
                "cannot score at all makes the margin below meaningless."
        )

        // --- the margins ----------------------------------------------------------------------
        val margin = scriptedOcc.meanBreadth - globalOcc.meanBreadth
        check(
            margin > BREADTH_MARGIN,
            "counterfactual: scripting divides the work — mean breadth " +
                "${f(scriptedOcc.meanBreadth, 3)} scripted against ${f(globalOcc.meanBreadth, 3)} " +
                "for one global rule at the same speeds on the same integrator, margin " +
                "${f(margin, 3)} over a floor of $BREADTH_MARGIN (measured $BREADTH_MEASURED)"
        )
        check(
            scriptedOcc.meanBreadth > boidOcc.meanBreadth + BREADTH_MARGIN,
            "counterfactual: and beats what simply moving around is worth — ${f(boidOcc.meanBreadth, 3)} " +
                "for a boid flock with no notion of the task"
        )
        check(
            scriptedOcc.everReached == SITES.size,
            "counterfactual: the scripted arm reached all ${SITES.size} sites " +
                "(${scriptedOcc.everReached})"
        )
        check(
            scriptedOcc.minBreadthAfterWarmup >= SUSTAINED_BREADTH,
            "counterfactual: and held at least $SUSTAINED_BREADTH sites occupied on every step after " +
                "the first 3 s, not just on average (worst step ${scriptedOcc.minBreadthAfterWarmup})"
        )

        // --- the picture ----------------------------------------------------------------------
        val frame = arenaFrame()
        val panels = listOf(
            "scripted %.2f".format(scriptedOcc.meanBreadth) to
                rasterizeFLinesFramed(scripted.trailFLines(stride = 2) + siteRings(), frame, 300, 300, 6),
            "one global rule %.2f".format(globalOcc.meanBreadth) to
                rasterizeFLinesFramed(global.trailFLines(stride = 2) + siteRings(), frame, 300, 300, 6),
            "boids %.2f".format(boidOcc.meanBreadth) to
                rasterizeFLinesFramed(boidTrailLines(flock) + siteRings(), frame, 300, 300, 6),
            "sites occupied per step" to
                rasterizeFLines(breadthPlot(scriptedOcc, globalOcc, boidOcc), 300, 300, 6)
        )
        montage(panels, columns = 2, cellSide = 300).savePng(File(outDir, "script-counterfactual.png").path)
    }

    /**
     * Floor under every arm's travel, so the comparison cannot be blank against blank.
     *
     * Measured: scripted 6 706, global 8 715, boids 23 464 units. Floored at 5 000, which the
     * tightest arm clears by 34%. Its job is to catch an arm that stopped, not to fence the steering.
     *
     * Worth noticing that the scripted arm travels the *least* of the three and scores the most. That
     * is the shape of the result: the claim is not that scripting makes agents busier.
     */
    private const val ARM_PATH_FLOOR: Double = 5000.0

    /**
     * Floor under the *control's* score.
     *
     * The direction that is easy to forget. A margin is only evidence if the thing being beaten was
     * capable of scoring, and a control arm that had quietly broken — agents piled on the spawn
     * point, sites never approached — would hand the scripted arm a margin it had not earned.
     * Measured 0.897 for the global arm — it does reach its nominated site, and when it is there
     * that is one site occupied out of six, which is exactly the ceiling on what one global rule can
     * do about this task. Floored at 0.6.
     */
    private const val GLOBAL_BREADTH_FLOOR: Double = 0.6

    /** Measured margin: 4.663 — mean breadth 5.560 scripted against the global rule's 0.897. */
    private const val BREADTH_MEASURED: String = "4.663"

    /** Floored at 2.5, a little over half the measured margin. */
    private const val BREADTH_MARGIN: Double = 2.5

    /**
     * The stronger form of the same claim: not a good *average*, a good *worst step*.
     *
     * A mean of 5.56 out of 6 is compatible with a population that periodically all arrives at once
     * and then all leaves; this says it does not. Measured a worst step of **6** sites occupied after
     * the 3-second warm-up — i.e. after the stagger settles, every site is occupied on every single
     * step — and floored at 4 rather than 6 because a perfect worst case is exactly the kind of
     * number that one tuning change turns into an unexplained red.
     */
    private const val SUSTAINED_BREADTH: Int = 4

    // ==========================================================================================
    // 4 — regime switching: the bridge into alife.kt's measures
    // ==========================================================================================

    /**
     * A population that decides, on its own clock, whether to be a flock.
     *
     * Every agent runs the same two-phase script — `flock` for four seconds, then `scatter` for four
     * — and the two phases install different drive sets: `align` + `cohere` + `separate`, which are
     * the three Reynolds terms, against `wander` + `separate`, which are not. Nothing tells the
     * population to be ordered; the order is measured out of it with [polarization], through
     * [ScriptWorld.asBoidFlock], on exactly the same statistic `alife_agents.kt` makes its flocking
     * claim with.
     *
     * This is the figure that shows the scripting layer earns its place rather than sitting beside
     * the family. A single [BoidParams] cannot do this — not because the parameters are wrong but
     * because there is nowhere in `step(p)` to keep *when*. And the claim is checked in both
     * directions, with a floor under the ordered phase and a ceiling over the disordered one, because
     * "polarization went up" is also what a population that had simply collapsed to a point would
     * report.
     */
    private fun figureRegime(outDir: File) {
        val w = ScriptWorld(width = ARENA, height = ARENA, fixedStep = 1.0 / HZ, seed = 31L, wrap = true)
        val n = 90
        val start = kotlin.random.Random(31L)
        for (i in 0 until n) {
            val a = 2.0 * PI * i / n
            val r = 30.0 + (i % 7) * 4.0
            // Random headings, which is the disordered initial condition BoidFlock.random uses. A
            // population at rest has no mean neighbour velocity, so `align` would have nothing to
            // work with and the ordered phase could not be reached from it at all.
            val h = start.nextDouble() * 2.0 * PI
            w.spawn(
                100.0 + cos(a) * r, 100.0 + sin(a) * r,
                maxSpeed = 30.0, steerResponse = 0.12,
                vx = cos(h) * 30.0, vy = sin(h) * 30.0, tag = "boid"
            ) {
                forever {
                    phase("flock") {
                        moveFor(PHASE_SECONDS, 30.0) {
                            align(range = 26.0, weight = 1.0)
                            cohere(range = 26.0, weight = 0.55)
                            separate(range = 7.0, weight = 0.9)
                        }
                    }
                    phase("scatter") {
                        moveFor(PHASE_SECONDS, 30.0) {
                            // turn=6 rad/s at 60 Hz is 0.05 rad per step, so a heading random-walks
                            // only 0.05*sqrt(300) = 0.9 rad across a whole phase — the first version
                            // of this figure measured polarization *rising* during "scatter" (0.736)
                            // because the wander preserved the alignment it inherited. It has to be
                            // fast enough to decorrelate within the phase to be a scatter at all.
                            wander(turn = 40.0, weight = 1.0)
                            separate(range = 12.0, weight = 0.8)
                        }
                    }
                }
            }
        }

        val cycle = (PHASE_SECONDS * HZ).toInt()
        val steps = cycle * 4
        val order = ArrayList<Double>()
        val phaseNames = ArrayList<String>()
        val snapshots = LinkedHashMap<String, List<FLine>>()
        repeat(steps) { s ->
            w.step()
            val fl = w.asBoidFlock()
            order.add(polarization(fl.vx, fl.vy))
            phaseNames.add(w.actors[0].phase ?: "-")
            if (s % cycle == cycle - 10) {
                snapshots["t%.1fs %s".format(w.time, phaseNames.last())] = agentArrows(w)
            }
        }

        /** The mean over the **last second** of the phase that ends at [endStep] — its settled value. */
        fun settled(endStep: Int): Double {
            val window = (endStep - HZ.toInt())until endStep
            return window.map { order[it] }.average()
        }
        val flock1 = settled(cycle)
        val scatter1 = settled(cycle * 2)
        val flock2 = settled(cycle * 3)
        val scatter2 = settled(cycle * 4)
        check(
            phaseNames[cycle - 5] == "flock" && phaseNames[cycle * 2 - 5] == "scatter",
            "regime: the windows land in the phases they claim to " +
                "(${phaseNames[cycle - 5]}, ${phaseNames[cycle * 2 - 5]})"
        )
        println(
            "[alife-script] polarization: flock %s / scatter %s / flock %s / scatter %s"
                .format(f(flock1), f(scatter1), f(flock2), f(scatter2))
        )

        check(
            flock1 > FLOCK_ORDER_FLOOR && flock2 > FLOCK_ORDER_FLOOR,
            "regime: the flocking phases are ordered — polarization ${f(flock1)} and ${f(flock2)}, " +
                "floor $FLOCK_ORDER_FLOOR (measured $FLOCK_ORDER_MEASURED)"
        )
        check(
            scatter1 < SCATTER_ORDER_CEILING && scatter2 < SCATTER_ORDER_CEILING,
            "regime: the scattering phases are not — ${f(scatter1)} and ${f(scatter2)}, " +
                "ceiling $SCATTER_ORDER_CEILING. Checked as a ceiling because a run where " +
                "polarization only ever rose would be a population that had collapsed, not one flocking."
        )
        check(
            flock2 - scatter1 > REGIME_MARGIN && flock2 - scatter2 > REGIME_MARGIN,
            "regime: and the population goes back and forth rather than settling — second ordered " +
                "phase ${f(flock2)} against neighbouring disordered ${f(scatter1)}/${f(scatter2)}, " +
                "margin floor $REGIME_MARGIN"
        )
        val nn = w.asBoidFlock().neighborDistances().average()
        check(
            nn > COLLAPSE_FLOOR,
            "regime: and has not collapsed to a point, which is the other way to score well on " +
                "polarization — mean nearest-neighbour distance ${f(nn, 2)}, floor $COLLAPSE_FLOOR"
        )
        check(
            w.failures.isEmpty(),
            "regime: no behaviour failures across $n agents and $steps steps (${w.failures.take(2)})"
        )

        val frame = arenaFrame()
        val panels = snapshots.entries.map { (label, lines) ->
            label to rasterizeFLinesFramed(lines, frame, 300, 300, 6)
        } + listOf(
            "polarization, %.0f s".format(steps / HZ) to
                rasterizeFLines(orderPlot(order, phaseNames), 300, 300, 6)
        )
        montage(panels, columns = 3, cellSide = 260).savePng(File(outDir, "script-regime.png").path)
    }

    /** Seconds each of the two phases lasts. */
    private const val PHASE_SECONDS: Double = 5.0

    /**
     * Measured for the two ordered phases across 90 agents.
     *
     * The two differ — 0.972 then 0.696 — and the difference is not noise. The first flocking phase
     * starts from the spawn ring, which is disordered but *evenly spread*, so every agent has
     * neighbours and alignment propagates. The second starts from whatever five seconds of hard
     * wandering left behind, which is patchier, and five seconds is not quite enough to order it
     * fully. A figure that reported one number for "the flocking phase" would have hidden that.
     */
    private const val FLOCK_ORDER_MEASURED: String = "0.972 / 0.696"

    /** Floored at 0.60, 14% under the tighter of the two measured values. */
    private const val FLOCK_ORDER_FLOOR: Double = 0.60

    /** Measured 0.082 and 0.073 for the two disordered phases. Ceilinged at 0.45, well above both. */
    private const val SCATTER_ORDER_CEILING: Double = 0.45

    /** Measured swings of 0.61 and 0.62 around the second ordered phase. Floored at 0.30. */
    private const val REGIME_MARGIN: Double = 0.30

    /** Measured 5.6 units mean nearest-neighbour distance against a 7-unit separation range. */
    private const val COLLAPSE_FLOOR: Double = 2.0

    // ==========================================================================================
    // The shared foraging world
    // ==========================================================================================

    /**
     * The counterfactual's two matched arms, differing **only** in whether the site order is
     * per-agent.
     *
     * Same world, same seed, same spawn ring, same speeds, same steering verbs, same integrator. The
     * scripted arm gives agent `i` its own rotation of the site list and lets its continuation
     * remember where it is up to; the global arm reads the site index off the global clock, which is
     * all a rule with one parameter block can do. Everything else is held.
     *
     * @param nudge added to agent 0's spawn x. Only [figureDeterminism] passes it, to show the run is
     *   sensitive to its initial conditions rather than being a fixed point.
     */
    private fun forageWorld(seed: Long, scripted: Boolean, nudge: Double = 0.0): ScriptWorld {
        val w = ScriptWorld(width = ARENA, height = ARENA, fixedStep = 1.0 / HZ, seed = seed, wrap = true)
        val k = SITES.size
        for (i in 0 until FORAGE_AGENTS) {
            val a = 2.0 * PI * i / FORAGE_AGENTS
            val x = 100.0 + cos(a) * 18.0 + (if (i == 0) nudge else 0.0)
            val y = 100.0 + sin(a) * 18.0
            if (scripted) {
                val order = (0 until k).map { (it + i) % k }
                w.spawn(x, y, maxSpeed = 45.0, tag = "scripted") {
                    forever {
                        for (s in order) {
                            val site = point(SITES[s].x, SITES[s].y)
                            phase("travel") {
                                moveUntil(maxSeconds = 5.0, until = { near(site, SITE_RADIUS * 0.5) }) {
                                    arrive(site, slowRadius = 14.0, stopRadius = 1.0)
                                    separate(range = 6.0, weight = 0.35)
                                }
                            }
                            phase("feed") { rest(1.2) }
                        }
                    }
                }
            } else {
                w.spawn(x, y, maxSpeed = 45.0, tag = "global") {
                    forever {
                        // One rule, one parameter block: the site is a function of the global clock
                        // and nothing else. No per-agent state anywhere in this behaviour.
                        val s = ((time / GLOBAL_DWELL).toInt()) % k
                        val site = point(SITES[s].x, SITES[s].y)
                        moveFor(1.0 / HZ * 6.0, 45.0) {
                            arrive(site, slowRadius = 14.0, stopRadius = 1.0)
                            separate(range = 6.0, weight = 0.35)
                        }
                    }
                }
            }
        }
        return w
    }

    /**
     * How long the global arm's clock leaves the population at one site.
     *
     * Chosen to give the global arm its **best** shot: 2.5 s is long enough to arrive and short
     * enough to visit all six sites inside the run, so it reaches every site the scripted arm does.
     * A control tuned to fail is not a control.
     */
    private const val GLOBAL_DWELL: Double = 2.5

    // ==========================================================================================
    // Measurement
    // ==========================================================================================

    /**
     * Sites occupied, per step — the counterfactual's metric, computed from positions so it means
     * the same thing for a scripted world, a global-rule world and a [BoidFlock].
     */
    private class Occupancy {
        val perStep = ArrayList<Int>()
        private val ever = BooleanArray(SITES.size)

        fun sampleWorld(w: ScriptWorld) {
            val xs = DoubleArray(w.living.size) { w.living[it].position.x }
            val ys = DoubleArray(w.living.size) { w.living[it].position.y }
            sample(xs, ys)
        }

        fun sampleFlock(fl: BoidFlock) =
            sample(DoubleArray(fl.count) { fl.x[it].toDouble() }, DoubleArray(fl.count) { fl.y[it].toDouble() })

        private fun sample(xs: DoubleArray, ys: DoubleArray) {
            var n = 0
            for ((si, s) in SITES.withIndex()) {
                var hit = false
                for (i in xs.indices) {
                    val d = hypot(wrapDelta(xs[i], s.x, ARENA), wrapDelta(ys[i], s.y, ARENA))
                    if (d <= SITE_RADIUS) { hit = true; break }
                }
                if (hit) { n++; ever[si] = true }
            }
            perStep.add(n)
        }

        val meanBreadth: Double get() = if (perStep.isEmpty()) 0.0 else perStep.average()
        val everReached: Int get() = ever.count { it }

        /** The worst single step after a 3-second warm-up, which is the claim's stronger form. */
        val minBreadthAfterWarmup: Int
            get() = perStep.drop((3.0 * HZ).toInt()).minOrNull() ?: 0
    }

    /** Signed offset from [a] to [b] on a torus of size [n], the shorter way round. */
    private fun wrapDelta(a: Double, b: Double, n: Double): Double {
        var d = b - a
        if (d > n * 0.5) d -= n
        if (d < -n * 0.5) d += n
        return d
    }

    // ==========================================================================================
    // Drawing — FLine geometry, rasterised only where a contact sheet needs pixels
    // ==========================================================================================

    /** Two single-node lines at the arena corners, so every panel shares one coordinate frame. */
    private fun arenaFrame(): List<FLine> =
        listOf(FLine().moveTo(0.0, 0.0, 0), FLine().moveTo(ARENA, ARENA, 0))

    /** The foraging sites, as rings of radius [SITE_RADIUS], so the task is visible in the figure. */
    private fun siteRings(): List<FLine> = SITES.map { s ->
        FLine().also { l ->
            for (i in 0..24) {
                val a = 2.0 * PI * i / 24.0
                val x = s.x + cos(a) * SITE_RADIUS
                val y = s.y + sin(a) * SITE_RADIUS
                if (i == 0) l.moveTo(x, y, 0) else l.lineTo(x, y, 0)
            }
        }
    }

    /** Short heading arrows, one per agent — the readable way to show alignment in a still. */
    private fun agentArrows(w: ScriptWorld): List<FLine> = w.living.map { a ->
        val v = a.velocity
        val m = hypot(v.x, v.y)
        val ux = if (m > 1e-9) v.x / m else 1.0
        val uy = if (m > 1e-9) v.y / m else 0.0
        FLine().also {
            it.moveTo(a.position.x, a.position.y, 0)
            it.lineTo(a.position.x + ux * 6.0, a.position.y + uy * 6.0, 0)
        }
    } + arenaFrame()

    /**
     * The boid arm as heading arrows, not as trails.
     *
     * A [BoidFlock]'s state *is* its four arrays — it keeps no history — so there is no trail to
     * draw without recording one alongside, and this panel deliberately shows less than the other
     * two rather than pretending otherwise. What it does show is the thing that matters for the
     * comparison: where the flock ended up relative to the six sites, and that it is aligned.
     */
    private fun boidTrailLines(fl: BoidFlock): List<FLine> {
        val out = ArrayList<FLine>()
        for (i in 0 until fl.count) {
            val vx = fl.vx[i].toDouble(); val vy = fl.vy[i].toDouble()
            val m = hypot(vx, vy)
            val ux = if (m > 1e-9) vx / m else 1.0
            val uy = if (m > 1e-9) vy / m else 0.0
            out.add(FLine().also {
                it.moveTo(fl.x[i].toDouble(), fl.y[i].toDouble(), 0)
                it.lineTo(fl.x[i] + ux * 8.0, fl.y[i] + uy * 8.0, 0)
            })
        }
        return out
    }

    /**
     * The three arms' sites-occupied traces, in three stacked bands, each with a baseline.
     *
     * `y` is negated, because `rasterizeFLines` writes row 0 at the top — so a plot drawn in the
     * obvious sense comes out upside down, with "more sites occupied" lower on the page. This is the
     * one place in the file where the drawing has to know which way the raster runs.
     *
     * Bands run top to bottom in argument order, which is the order of the three panels beside them:
     * scripted, one global rule, boids.
     */
    private fun breadthPlot(vararg arms: Occupancy): List<FLine> {
        val out = ArrayList<FLine>()
        val n = arms.maxOf { it.perStep.size }
        val band = 34.0
        val unit = 4.5
        for ((ai, arm) in arms.withIndex()) {
            // Largest base for the first arm, so that after the negation below it is the top band.
            val base = (arms.size - 1 - ai) * band
            out.add(FLine().also { it.moveTo(0.0, -base, 0); it.lineTo(100.0, -base, 0) })
            out.add(FLine().also { l ->
                for ((i, v) in arm.perStep.withIndex()) {
                    val x = i.toDouble() / n * 100.0
                    val y = -(base + v * unit)
                    if (i == 0) l.moveTo(x, y, 0) else l.lineTo(x, y, 0)
                }
            })
        }
        return out
    }

    /**
     * Polarization against time, with a rule at each phase boundary — the trace the two regime
     * claims are read off.
     *
     * Same `y` negation as [breadthPlot], so the ordered phases are the *high* plateaus.
     */
    private fun orderPlot(order: List<Double>, phases: List<String>): List<FLine> {
        val out = ArrayList<FLine>()
        val h = 100.0
        out.add(FLine().also { it.moveTo(0.0, 0.0, 0); it.lineTo(100.0, 0.0, 0) })
        out.add(FLine().also { l ->
            for ((i, v) in order.withIndex()) {
                val x = i.toDouble() / order.size * 100.0
                if (i == 0) l.moveTo(x, -v * h, 0) else l.lineTo(x, -v * h, 0)
            }
        })
        for (i in 1 until phases.size) {
            if (phases[i] != phases[i - 1]) {
                val x = i.toDouble() / phases.size * 100.0
                out.add(FLine().also { it.moveTo(x, 4.0, 0); it.lineTo(x, -h - 4.0, 0) })
            }
        }
        return out
    }

    // ==========================================================================================
    // Driver
    // ==========================================================================================

    /** Figure name to the renderer, and to the PNG it must leave behind (or none). */
    private val figures: List<Triple<String, (File) -> Unit, String?>> = listOf(
        Triple("premise", ::figurePremise, null),
        Triple("determinism", ::figureDeterminism, null),
        Triple("counterfactual", ::figureCounterfactual, "script-counterfactual.png"),
        Triple("regime", ::figureRegime, "script-regime.png")
    )

    @JvmStatic
    fun main(args: Array<String>) {
        var outPath: String? = System.getenv("ALIFE_SCRIPT_DIR")
        var only: Set<String>? = null
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--out" -> { outPath = args.getOrNull(i + 1); i++ }
                "--only" -> { only = args.getOrNull(i + 1)?.split(',')?.map { it.trim() }?.toSet(); i++ }
                else -> if (outPath == null && !args[i].startsWith("--")) outPath = args[i]
            }
            i++
        }
        val chosenOut = outPath
        val outDir = if (chosenOut != null) File(chosenOut) else
            java.nio.file.Files.createTempDirectory("alife-script").toFile()
        outDir.mkdirs()
        println("[alife-script] output -> ${outDir.absolutePath}")

        val chosen = only
        val selected = figures.filter { chosen == null || it.first in chosen }
        check(selected.isNotEmpty(), "at least one figure selected (--only ${chosen?.joinToString(",")})")
        val started = System.nanoTime()
        for ((name, render, _) in selected) {
            val t = System.nanoTime()
            println("[alife-script] --- $name ---")
            runCatching { render(outDir) }.onFailure { fail("$name threw: $it") }
            println("[alife-script] --- $name done in %.1f s ---".format((System.nanoTime() - t) / 1e9))
        }

        // Bidirectional ledger over the figures and their files, same as AlifeEvolveGallery's: a
        // figure that stopped writing its PNG fails, and a leftover PNG from a figure that no longer
        // declares one fails too. "Every declared file exists" passes forever after a deletion.
        val declared = selected.mapNotNull { it.third }.toSortedSet()
        val onDisk = (outDir.listFiles { fl -> fl.name.endsWith(".png") } ?: emptyArray())
            .map { it.name }.toSortedSet()
        check(
            declared == onDisk,
            "figure ledger: declared $declared, on disk $onDisk " +
                "(missing ${declared - onDisk}, unexpected ${onDisk - declared})"
        )
        for (name in declared) {
            val file = File(outDir, name)
            check(file.length() > 2000, "figure ledger: $name is ${file.length()} bytes, not a stub")
            check(inkFraction(file) > 0.002, "figure ledger: $name has ink on it, not a black rectangle")
        }

        println(
            "[alife-script] %d check(s), %d failure(s), %.1f s total"
                .format(checks, failures.size, (System.nanoTime() - started) / 1e9)
        )
        if (failures.isNotEmpty()) {
            println("[alife-script] FAILURES:")
            failures.forEach { println("  - $it") }
            exitProcess(1)
        }
        println("alife script gallery rendered")
    }

    /**
     * The fraction of a PNG's pixels that are meaningfully brighter than its darkest.
     *
     * A byte-length check passes on a 300x300 PNG of pure black, which is what a figure looks like
     * when the geometry missed the frame — the single most common way a rendered figure is wrong
     * while every other check is green. This reads the file back and counts.
     */
    private fun inkFraction(file: File): Double {
        val img = javax.imageio.ImageIO.read(file) ?: return 0.0
        var lit = 0
        var total = 0
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val rgb = img.getRGB(x, y)
            val v = min(255, ((rgb shr 16 and 0xff) + (rgb shr 8 and 0xff) + (rgb and 0xff)) / 3)
            if (v > 96) lit++
            total++
        }
        return if (total == 0) 0.0 else lit.toDouble() / total
    }
}
