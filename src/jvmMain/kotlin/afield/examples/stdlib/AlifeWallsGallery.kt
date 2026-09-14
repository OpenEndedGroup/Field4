package afield.examples.stdlib

import afield.graphics.FLine
import afield.stdlib.NavGrid
import afield.stdlib.ScriptWorld
import afield.stdlib.WallResponse
import afield.stdlib.closestPointOnSegment
import afield.stdlib.montage
import afield.stdlib.pointSourceField
import afield.stdlib.rasterizeFLines
import afield.stdlib.rasterizeFLinesFramed
import afield.stdlib.savePng
import afield.stdlib.sweepSegment
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
id: stdlib.alife.walls.gallery
runner: jvm-main
entrypoint: afield.examples.stdlib.AlifeWallsGallery
tags: [smoke, stdlib, alife, script, walls, navigation, capture, cpu]
timeoutSec: 600
expect:
  exitCode: 0
  stdoutContains:
    - "alife walls gallery rendered"
*/

/**
 * Renders — and *verifies* — the three claims `alife_walls.kt` makes: that the collision is swept
 * rather than discrete, that the routing is deterministic, and that a routed `seek` reaches places a
 * direct `seek` does not.
 *
 * Modelled on [AlifeScriptGallery] and for the same reason: agents moving around a picture of some
 * walls is easy to produce and proves nothing, so every claim here is stated **against a control at
 * the same budget in the same substrate**, with the floor declared before the number is measured.
 *
 * The order is the order of dependence.
 *
 *  - [figurePremise] — the geometry, checked by *arithmetic* rather than by simulating. A known
 *    time-of-impact, a slide that conserves the tangential velocity exactly, a bounce that negates
 *    and scales the normal one, a nav grid whose blocked-cell count matches what the wall covers.
 *    If this drifts, everything below it is a tautology.
 *  - [figureTunnelling] — the swept test against a discrete one, at a speed where the discrete one
 *    fails. This is the figure that says the word "swept" is load-bearing.
 *  - [figureDeterminism] — the maze run twice in one JVM gives the same `DoubleArray`; a
 *    walls-and-wander run is still seed-sensitive, which is what stops "deterministic" meaning
 *    "frozen". Signatures are printed so a second JVM invocation can be compared against them.
 *  - [figureCounterfactual] — routed `seek` against direct `seek` on an S-maze, over 8 seeds, with
 *    floors on both arrival fractions, on the margin, **and on both arms' path lengths** so that
 *    two stuck populations cannot pass as a win.
 *  - [figureSteering] — [afield.stdlib.BehaviourSteering.cling],
 *    [afield.stdlib.BehaviourSteering.gradient] and [WallResponse.BOUNCE], which are pictures first
 *    and are checked for the one property each that a picture would not show.
 *
 * As a FIELD_TEST it renders to a temp dir and writes no committed assets. Pass `--out DIR` (or a
 * bare first argument, or set `ALIFE_WALLS_DIR`) to put the PNGs somewhere that survives, and
 * `--only name,name` to iterate on one figure.
 *
 * ### Which way up the figures are
 *
 * `rasterizeFLines` writes row 0 at the **top**, so a figure drawn in world coordinates comes out
 * mirrored: the gap at the top of a wall appears at the bottom. Every drawing helper here therefore
 * emits `-y`, which puts the picture the same way up as the coordinates the script was written in.
 * That applies to the *spatial* panels as well as to the plots, which is one difference from
 * [AlifeScriptGallery] (whose spatial panels are symmetric enough not to care) and it is deliberate:
 * a maze that is upside down relative to its own source is a figure that costs a reader a minute
 * every time.
 */
object AlifeWallsGallery {

    // ==========================================================================================
    // Verification plumbing — same shape as AlifeScriptGallery's, deliberately
    // ==========================================================================================

    private val failures = ArrayList<String>()
    private var checks = 0

    private fun fail(message: String) {
        failures.add(message)
        println("[alife-walls] FAIL $message")
    }

    private fun check(condition: Boolean, message: String) {
        checks++
        if (!condition) fail(message) else println("[alife-walls] ok  $message")
    }

    private fun f(v: Double, digits: Int = 3): String = "%.${digits}f".format(v)

    private const val ARENA = 200.0
    private const val HZ = 60.0

    // ==========================================================================================
    // 1 — the premise: the geometry, by arithmetic
    // ==========================================================================================

    /**
     * The swept primitive and the three wall responses, on cases whose right answer is a sum.
     *
     * Everything below this figure is a simulation and can only be judged against a control. These
     * are *counted*, so they are checked absolutely and they are checked first — a determinism
     * result on a collision routine that had quietly started reporting the wrong contact time would
     * be perfectly reproducible and completely wrong.
     */
    private fun figurePremise(outDir: File) {
        require(outDir.isDirectory)

        // --- a known time of impact ---------------------------------------------------------
        run {
            // A circle of radius 1 at the origin, moving 10 units along +x, against the vertical
            // wall x = 5. It touches when its centre reaches x = 4, i.e. at t = 0.4 exactly.
            val out = DoubleArray(3)
            val hit = sweepSegment(0.0, 0.0, 10.0, 0.0, 1.0, 5.0, -10.0, 5.0, 10.0, out)
            check(hit, "premise: a circle driven at a wall in front of it reports a contact")
            check(
                abs(out[0] - 0.4) < 1e-12,
                "premise: contact at t = ${f(out[0], 12)}, arithmetic says exactly 0.4"
            )
            check(
                abs(out[1] + 1.0) < 1e-12 && abs(out[2]) < 1e-12,
                "premise: with the normal facing back at the approach, (${f(out[1])}, ${f(out[2])})"
            )
        }

        // --- a miss is a miss, and the endpoint cap is a circle ------------------------------
        run {
            val out = DoubleArray(3)
            check(
                !sweepSegment(0.0, 0.0, 10.0, 0.0, 1.0, 5.0, 2.0, 5.0, 10.0, out),
                "premise: a wall whose span the path passes below is not hit"
            )
            // The same wall, but its lower end is now 0.5 above the path — inside the radius, so the
            // circle rounds the END of the wall rather than meeting its face. That contact is on an
            // endpoint circle and happens LATER than the face solution would have.
            check(
                sweepSegment(0.0, 0.0, 10.0, 0.0, 1.0, 5.0, 0.5, 5.0, 10.0, out),
                "premise: and the cap at the end of a wall is hit when the path grazes it"
            )
            val expected = 5.0 - kotlin.math.sqrt(1.0 - 0.25)   // |P - end| = r  =>  dx = sqrt(r^2 - dy^2)
            check(
                abs(out[0] * 10.0 - expected) < 1e-9,
                "premise: at x = ${f(out[0] * 10.0, 9)}, arithmetic says ${f(expected, 9)} " +
                    "(the endpoint cap is a circle, not a corner)"
            )
        }

        // --- the three responses, to the last mantissa bit ------------------------------------
        //
        // Velocity (100, 50) into the vertical wall x = 60, from x = 20, at 60 Hz. The drive is
        // `keepVelocity` and `steerResponse` is 1, so nothing re-accelerates the agent after the
        // contact and the velocity it leaves the wall with is the velocity the response produced —
        // which is what makes these EXACT comparisons rather than bounds. Under a drive that kept
        // pushing, the reading would depend on how many steps after the bounce you looked.
        //
        //   SLIDE : v - n(v·n)         = (100, 50) - (-1, 0)(-100)         = (  0, 50)
        //   BOUNCE: v - n(v·n)(1 + e)  = (100, 50) - (-1, 0)(-100)(1.5)    = (-50, 50)
        //   STOP  : zero
        for ((response, expected) in listOf(
            WallResponse.SLIDE to doubleArrayOf(0.0, 50.0),
            WallResponse.STOP to doubleArrayOf(0.0, 0.0),
            WallResponse.BOUNCE to doubleArrayOf(-50.0, 50.0)
        )) {
            val w = ScriptWorld(width = ARENA, height = ARENA, fixedStep = 1.0 / HZ, seed = 1L, wrap = false)
            w.addWall(60.0, 0.0, 60.0, 200.0, name = "face")
            val a = w.spawn(
                20.0, 20.0, maxSpeed = 120.0, radius = 1.0, steerResponse = 1.0,
                vx = 100.0, vy = 50.0, wallResponse = response, restitution = 0.5, tag = response.name
            ) {
                moveFor(2.0) { keepVelocity() }
            }
            w.run(60)
            check(
                a.wallContacts > 0,
                "premise: ${response.name} actually reached the wall (${a.wallContacts} contacts)"
            )
            check(
                a.position.x <= 59.0 + 1e-6,
                "premise: ${response.name} did not end up past the wall (x = ${f(a.position.x)})"
            )
            check(
                abs(a.velocity.x - expected[0]) < 1e-9 && abs(a.velocity.y - expected[1]) < 1e-9,
                "premise: ${response.name} left the wall at exactly " +
                    "(${f(expected[0], 1)}, ${f(expected[1], 1)}) — measured " +
                    "(${f(a.velocity.x, 9)}, ${f(a.velocity.y, 9)})"
            )
        }

        // --- depenetration: an agent placed inside a wall comes out --------------------------
        run {
            val w = ScriptWorld(width = ARENA, height = ARENA, seed = 1L, wrap = false)
            w.addRect(90.0, 90.0, 20.0, 20.0, name = "block")
            val a = w.spawn(95.0, 100.0, radius = 2.0, tag = "buried") { rest(1.0) }
            val d = w.wallField.distanceTo(a.position.x, a.position.y)
            check(
                d >= 2.0 - 1e-9,
                "premise: an agent spawned overlapping a wall is pushed to its surface — " +
                    "${f(d)} from the wall against a radius of 2.0"
            )
        }

        // --- the nav grid blocks what the wall covers, and nothing like the whole arena -------
        run {
            val w = ScriptWorld(width = ARENA, height = ARENA, seed = 1L, wrap = false)
            w.addWall(100.0, 0.0, 100.0, 200.0, name = "divider")
            val g = w.navGrid(2.0)
            check(!g.coarse, "premise: the nav grid at inflate 2.0 is fine, not capped ($g)")
            // One full-height wall plus the boundary ring. The wall's own column is roughly
            // 2*inflate wide over 200 units of height; the boundary is a 2-unit frame.
            val frac = g.blockedCells.toDouble() / g.cellCount
            check(
                frac > 0.02 && frac < 0.25,
                "premise: the grid blocks the wall and the boundary and not the arena — " +
                    "${f(frac * 100, 1)}% of ${g.cellCount} cells"
            )
            check(
                w.findRoute(20.0, 100.0, 180.0, 100.0, 2.0) == null,
                "premise: and a goal sealed behind a full-height wall has no route at all"
            )
            // Cut a door in it and the same query succeeds. Same grid, one wall shorter.
            val w2 = ScriptWorld(width = ARENA, height = ARENA, seed = 1L, wrap = false)
            w2.addWall(100.0, 0.0, 100.0, 150.0, name = "divider with a door")
            val r = w2.findRoute(20.0, 100.0, 180.0, 100.0, 2.0)
            check(r != null, "premise: and a wall with a gap in it does")
            if (r != null) {
                val len = polylineLength(r)
                check(
                    len > 160.0,
                    "premise: by a route that is longer than the straight line it could not take — " +
                        "${f(len, 1)} units against a 160-unit direct distance"
                )
            }
        }

        // --- line of sight ------------------------------------------------------------------
        run {
            val w = ScriptWorld(width = ARENA, height = ARENA, seed = 1L, wrap = false)
            w.addWall(100.0, 50.0, 100.0, 150.0, name = "screen")
            check(!w.lineOfSight(50.0, 100.0, 150.0, 100.0), "premise: a wall blocks a sight line")
            check(w.lineOfSight(50.0, 20.0, 150.0, 20.0), "premise: and below its end it does not")
        }
    }

    /** The length of a packed `x, y, x, y, …` polyline. */
    private fun polylineLength(p: DoubleArray): Double {
        var total = 0.0
        var i = 2
        while (i < p.size) {
            total += hypot(p[i] - p[i - 2], p[i + 1] - p[i - 1])
            i += 2
        }
        return total
    }

    // ==========================================================================================
    // 2 — tunnelling: why the word "swept" is in the name
    // ==========================================================================================

    /**
     * One agent at a speed that carries it thirty radii per step, straight at a wall.
     *
     * A discrete collision test asks whether the agent's *new* position overlaps a wall. A wall here
     * is a line segment, which has no thickness at all, so the question is "is the agent's centre
     * within its radius of the segment" — false for any step that steps clean over it. At 900
     * units/s and 60 Hz the displacement is 15 units and the agent's radius is 0.5, so a discrete
     * test has a one-in-thirty chance of noticing and takes it thirty times a second.
     *
     * The control arm is [discreteCrossing], which is the integrator this file replaced — Euler plus
     * an endpoint overlap test — run on the identical numbers. It has to **fail**, or the swept arm
     * passing says nothing.
     */
    private fun figureTunnelling(outDir: File) {
        require(outDir.isDirectory)
        val speed = TUNNEL_SPEED
        val radius = TUNNEL_RADIUS
        val perStep = speed / HZ
        println(
            ("[alife-walls] tunnelling: %.1f units/s is %.2f units per step against a radius of " +
                "%.2f — %.0f radii").format(speed, perStep, radius, perStep / radius)
        )
        check(
            perStep > radius * 8.0,
            "tunnelling: the speed is genuinely a tunnelling speed — ${f(perStep, 2)} units per " +
                "step against a radius of $radius, which is ${f(perStep / radius, 1)} radii"
        )

        val w = ScriptWorld(width = ARENA, height = ARENA, fixedStep = 1.0 / HZ, seed = 1L, wrap = false)
        w.addWall(TUNNEL_WALL_X, 40.0, TUNNEL_WALL_X, 160.0, name = "thin wall")
        val swept = ArrayList<Double>()
        val a = w.spawn(
            20.0, 100.0, maxSpeed = speed, radius = radius, steerResponse = 1.0,
            vx = speed, vy = 0.0, tag = "swept"
        ) {
            moveFor(1.0, speed) { heading(0.0) }
        }
        repeat(TUNNEL_STEPS) { w.step(); swept.add(a.position.x) }

        val discrete = discreteCrossing(20.0, 100.0, speed, radius, TUNNEL_STEPS)

        println(
            "[alife-walls] tunnelling: swept ends at x = %.3f, discrete at x = %.3f, wall at %.1f"
                .format(swept.last(), discrete.last(), TUNNEL_WALL_X)
        )
        check(
            swept.last() < TUNNEL_WALL_X - radius + 1e-3,
            "tunnelling: the swept integrator stops at the wall — ends at " +
                "x = ${f(swept.last())}, wall at $TUNNEL_WALL_X"
        )
        check(
            a.wallContacts > 0,
            "tunnelling: having actually touched it (${a.wallContacts} contacts), not merely run out " +
                "of time — the run is ${f(TUNNEL_STEPS / HZ, 2)} s and the wall is 80 units away"
        )
        check(
            discrete.last() > TUNNEL_WALL_X + radius,
            "tunnelling: and the discrete control on the identical numbers ends at " +
                "x = ${f(discrete.last())}, past the wall. Without this the swept arm proves nothing."
        )

        // The two traces, side by side, with the wall drawn across both.
        val panels = listOf(
            "swept: stops at the wall" to rasterizeFLinesFramed(
                tunnelLines(swept) + wallLines(w), tunnelFrame(), 320, 240, 8
            ),
            "discrete: passes through it" to rasterizeFLinesFramed(
                tunnelLines(discrete) + wallLines(w), tunnelFrame(), 320, 240, 8
            )
        )
        montage(panels, columns = 2, cellSide = 300).savePng(File(outDir, "walls-tunnelling.png").path)
    }

    /** 900 units/s: 15 units of displacement per 1/60 s step. */
    private const val TUNNEL_SPEED: Double = 900.0

    /** 0.5 units, so one step is thirty radii. */
    private const val TUNNEL_RADIUS: Double = 0.5

    /** Where the thin wall stands. */
    private const val TUNNEL_WALL_X: Double = 100.0

    /** Enough steps to cross the whole arena at [TUNNEL_SPEED] — 12 at 15 units each is 180. */
    private const val TUNNEL_STEPS: Int = 12

    /**
     * The integrator this file replaced: Euler, plus an endpoint overlap test against the wall.
     *
     * Deliberately written out rather than obtained by switching something off, because a control
     * that shares code with the thing it is controlling for is not a control. Returns the x of each
     * step.
     */
    private fun discreteCrossing(
        x0: Double,
        y0: Double,
        speed: Double,
        radius: Double,
        steps: Int
    ): List<Double> {
        val out = ArrayList<Double>()
        var x = x0
        val y = y0
        val dt = 1.0 / HZ
        val scratch = DoubleArray(2)
        repeat(steps) {
            x += speed * dt
            closestPointOnSegment(x, y, TUNNEL_WALL_X, 40.0, TUNNEL_WALL_X, 160.0, scratch)
            if (hypot(x - scratch[0], y - scratch[1]) < radius) {
                // "Collision": back off to the surface. Never reached at this speed.
                x = TUNNEL_WALL_X - radius
            }
            out.add(x)
        }
        return out
    }

    // ==========================================================================================
    // 3 — determinism, in both directions
    // ==========================================================================================

    /**
     * The maze twice, and a walls-and-wander run at two seeds.
     *
     * The first half is what the navigation has to survive: A\* over a grid is the one thing added
     * here with an open set in it, and an open set ordered by anything short of a total order is the
     * classic place a simulation stops being reproducible without looking like it would. Compared
     * with `contentEquals` on every coordinate, not on a digest.
     *
     * The second half is what stops the first from being trivial. A world where nothing moves
     * reproduces perfectly. So the same scenario is run at two seeds and required to **differ** —
     * and separately required to have gone somewhere.
     */
    private fun figureDeterminism(outDir: File) {
        require(outDir.isDirectory)

        val a = mazeWorld(seed = 1L, routed = true)
        val b = mazeWorld(seed = 1L, routed = true)
        repeat(MAZE_STEPS) { a.step(); b.step() }
        check(
            a.state().contentEquals(b.state()),
            "determinism: the routed maze run reproduces coordinate for coordinate " +
                "(${a.stateSignature()})"
        )
        check(
            a.eventSignature() == b.eventSignature(),
            "determinism: and decision for decision (${a.eventSignature()})"
        )
        check(
            a.routeSearches > 0,
            "determinism: having actually searched — ${a.routeSearches} A* searches, " +
                "${a.routeSearchesDeferred} deferred by the per-step budget"
        )
        val travelled = a.totalPathLength()
        check(
            travelled > DETERMINISM_PATH_FLOOR,
            "determinism: and actually moved — ${f(travelled, 0)} units across " +
                "$MAZE_AGENTS agents over $MAZE_STEPS steps, floor $DETERMINISM_PATH_FLOOR"
        )

        // The cross-JVM witness. Printed rather than asserted, because a second invocation is the
        // only thing that can check it and this process is the first one.
        println("[alife-walls] SIGNATURE state  ${a.stateSignature()}")
        println("[alife-walls] SIGNATURE events ${a.eventSignature()}")

        // Seed sensitivity, on a scenario the seed can actually reach: walls plus wander, so the
        // per-agent random streams are in the loop.
        val p = bounceWorld(seed = 7L)
        val q = bounceWorld(seed = 7L)
        val r = bounceWorld(seed = 8L)
        repeat(400) { p.step(); q.step(); r.step() }
        check(
            p.state().contentEquals(q.state()),
            "determinism: a walls-and-wander run reproduces at one seed (${p.stateSignature()})"
        )
        check(
            !p.state().contentEquals(r.state()),
            "determinism: and does not across seeds (${r.stateSignature()}), so the run is " +
                "reproducible rather than frozen"
        )
        val contacts = p.actors.sumOf { it.wallContacts }
        check(
            contacts > DETERMINISM_CONTACT_FLOOR,
            "determinism: and the walls were in the loop — $contacts wall contacts across " +
                "${p.actors.size} agents, floor $DETERMINISM_CONTACT_FLOOR"
        )
    }

    /**
     * Floor under the travel the reproducible maze run has to show.
     *
     * The first version of this constant was 6 000 and it failed: I had floored the *total* at a
     * number I had estimated for the *per-agent* mean. Twelve agents covering 254 units each is
     * about 3 000 in total, so the floor is 1 500 — half of it, loose on purpose. Its job is to fail
     * when the population stops moving, which would make every determinism check above it pass
     * vacuously; a tight fence would instead fail every time the steering is retuned.
     */
    private const val DETERMINISM_PATH_FLOOR: Double = 1500.0

    /** A run with no wall contacts in it has not exercised the collision code. */
    private const val DETERMINISM_CONTACT_FLOOR: Int = 40

    // ==========================================================================================
    // 4 — the counterfactual: what routing buys over direct steering
    // ==========================================================================================

    /**
     * Routed `seek` against direct `seek`, on an S-maze, over eight seeds.
     *
     * The two arms are **identical in every respect but one drive**: the same arena, the same walls,
     * the same integrator, the same speeds, the same spawn positions from the same seed, the same
     * `avoidWalls` and `separate` terms at the same weights, the same arrival predicate and the same
     * step budget. Arm A steers with [afield.stdlib.BehaviourSteering.route]; arm B steers with
     * [afield.stdlib.BehaviourSteering.seek], which is the verb that was already there and is
     * untouched by this branch.
     *
     * The maze is an S and that is the point. A single barrier can be beaten by a local rule that
     * always turns the same way; this one requires going *down* around the first wall and *up*
     * around the second, so no fixed local preference solves it and the decision has to come from a
     * plan. `avoidWalls` is in **both** arms at the same weight, so the comparison is not
     * routing-against-nothing, it is routing-against-the-best-local-steering-available.
     *
     * Every floor below was written into the branch worklog before the numbers were measured, and
     * two of them are floors on *both* arms' path lengths — because the failure mode of a
     * counterfactual like this is two stuck populations reporting a clean margin of zero against
     * zero.
     */
    private fun figureCounterfactual(outDir: File) {
        require(outDir.isDirectory)
        val routed = ArrayList<ArmResult>()
        val direct = ArrayList<ArmResult>()
        for (seed in MAZE_SEEDS) {
            routed.add(runArm(seed, routed = true))
            direct.add(runArm(seed, routed = false))
        }
        val r = ArmResult.pool(routed)
        val d = ArmResult.pool(direct)

        println("[alife-walls] counterfactual, ${MAZE_SEEDS.size} seeds x $MAZE_AGENTS agents = ${r.n} per arm")
        println("[alife-walls]   %-8s arrived %3d/%3d = %.3f   mean arrival %6.2f s   mean path %7.1f   wall contacts %5d"
            .format("routed", r.arrived, r.n, r.fraction, r.meanArrivalSeconds, r.meanPath, r.contacts))
        println("[alife-walls]   %-8s arrived %3d/%3d = %.3f   mean arrival %6.2f s   mean path %7.1f   wall contacts %5d"
            .format("direct", d.arrived, d.n, d.fraction, d.meanArrivalSeconds, d.meanPath, d.contacts))

        check(
            r.fraction >= ROUTED_ARRIVAL_FLOOR,
            "counterfactual: routed seek reaches the goal — ${f(r.fraction)} of ${r.n} agents " +
                "across ${MAZE_SEEDS.size} seeds, floor $ROUTED_ARRIVAL_FLOOR"
        )
        check(
            d.fraction <= DIRECT_ARRIVAL_CEILING,
            "counterfactual: direct seek does not — ${f(d.fraction)} of ${d.n}, " +
                "ceiling $DIRECT_ARRIVAL_CEILING"
        )
        check(
            r.fraction - d.fraction >= ARRIVAL_MARGIN,
            "counterfactual: margin ${f(r.fraction - d.fraction)}, floor $ARRIVAL_MARGIN"
        )
        check(
            r.meanPath >= NON_DEGENERACY_PATH && d.meanPath >= NON_DEGENERACY_PATH,
            "counterfactual: BOTH arms went somewhere — mean per-agent travel ${f(r.meanPath, 1)} " +
                "routed and ${f(d.meanPath, 1)} direct, floor $NON_DEGENERACY_PATH each. Two stuck " +
                "populations would otherwise report a clean margin of zero against zero."
        )
        check(
            r.contacts > 0 && d.contacts > 0,
            "counterfactual: and BOTH arms met the geometry — ${r.contacts} and ${d.contacts} wall " +
                "contacts. An arm with none has been tested by an empty arena."
        )
        check(
            r.failures == 0 && d.failures == 0,
            "counterfactual: no behaviour failures (${r.failures} routed, ${d.failures} direct)"
        )

        // The picture: one seed's two arms, the grid the router saw, and the arrival curves.
        val showRouted = mazeWorld(seed = MAZE_SEEDS.first(), routed = true)
        val showDirect = mazeWorld(seed = MAZE_SEEDS.first(), routed = false)
        val routedTrails = recordTrails(showRouted, MAZE_STEPS)
        val directTrails = recordTrails(showDirect, MAZE_STEPS)
        val grid = showRouted.navGrid(MAZE_RADIUS + MAZE_CLEARANCE)
        println("[alife-walls] nav grid: $grid")

        val frame = arenaFrame()
        val panels = listOf(
            "routed seek, seed ${MAZE_SEEDS.first()}" to rasterizeFLinesFramed(
                wallLines(showRouted) + markerLines() + routedTrails, frame, 320, 320, 8
            ),
            "direct seek, same seed" to rasterizeFLinesFramed(
                wallLines(showDirect) + markerLines() + directTrails, frame, 320, 320, 8
            ),
            "nav grid, inflate ${f(MAZE_RADIUS + MAZE_CLEARANCE, 2)}" to rasterizeFLinesFramed(
                gridLines(grid) + wallLines(showRouted) + markerLines(), frame, 320, 320, 8
            ),
            "arrived, cumulative (${r.n} per arm)" to rasterizeFLines(
                arrivalPlot(routed, direct), 320, 320, 8
            )
        )
        montage(panels, columns = 2, cellSide = 300)
            .savePng(File(outDir, "walls-counterfactual.png").path)
    }

    /**
     * Floors, all declared in the branch worklog before the run.
     *
     * Set as a *fraction* of the pooled population rather than per seed, because a per-seed floor on
     * eight seeds is eight chances to be unlucky and the claim is about the arms, not about seed 4.
     */
    private const val ROUTED_ARRIVAL_FLOOR: Double = 0.80

    /** The control's ceiling. Direct steering is allowed to get lucky; it is not allowed to work. */
    private const val DIRECT_ARRIVAL_CEILING: Double = 0.25

    /** The margin the whole figure is about. */
    private const val ARRIVAL_MARGIN: Double = 0.50

    /**
     * Mean per-agent travel both arms have to clear.
     *
     * The start is 50 units from the first wall and the goal is 157 units away in a straight line,
     * so an arm that pressed into the barrier and stayed there still covers 50. Floored there: its
     * job is to fail when a population never left home, which is the shape a vacuous margin takes.
     */
    private const val NON_DEGENERACY_PATH: Double = 40.0

    // ==========================================================================================
    // 5 — the local steering verbs
    // ==========================================================================================

    /**
     * `cling`, `gradient` and a bouncing population — three pictures, each with the one check a
     * picture would not make.
     *
     * `cling` is checked on the distance it holds rather than on the shape of the trail, because a
     * trail that looks like a circle round a pillar is also what an `orbit` at the right radius
     * draws and the difference is that this one is reading the wall. `gradient` is checked on the
     * field value the population ends on against the value it started on, which is the only
     * statement a taxis makes. The bouncing arm is checked on speed retained after contact, which is
     * what `restitution` means.
     */
    private fun figureSteering(outDir: File) {
        require(outDir.isDirectory)

        // --- cling: eight agents following a pillar and the arena walls ----------------------
        val clingWorld = ScriptWorld(
            width = ARENA, height = ARENA, fixedStep = 1.0 / HZ, seed = 11L, wrap = false
        )
        clingWorld.addDisc(100.0, 100.0, 34.0, segments = 32, name = "pillar")
        val clingDistance = 3.0
        for (i in 0 until 8) {
            val ang = 2.0 * PI * i / 8.0
            clingWorld.spawn(
                100.0 + cos(ang) * 46.0, 100.0 + sin(ang) * 46.0,
                maxSpeed = 26.0, radius = 1.0, steerResponse = 0.25, tag = "cling"
            ) {
                moveFor(12.0, 26.0) { cling(distance = clingDistance, clockwise = self.id % 2 == 0) }
            }
        }
        val clingTrails = recordTrails(clingWorld, 720)
        val held = clingWorld.living.map {
            clingWorld.wallField.distanceTo(it.position.x, it.position.y)
        }
        val meanHeld = held.average()
        println("[alife-walls] cling: mean wall distance %.3f against a target of %.3f"
            .format(meanHeld, clingDistance + 1.0))
        check(
            abs(meanHeld - (clingDistance + 1.0)) < CLING_TOLERANCE,
            "steering: cling holds the standoff it was asked for — mean ${f(meanHeld)} against " +
                "radius 1.0 + distance $clingDistance, tolerance $CLING_TOLERANCE"
        )
        check(
            clingWorld.totalPathLength() > CLING_TRAVEL_FLOOR,
            "steering: while travelling along the wall rather than parking on it — " +
                "${f(clingWorld.totalPathLength(), 0)} units, floor $CLING_TRAVEL_FLOOR"
        )

        // --- gradient: a population that climbs to two lamps ---------------------------------
        val lamps = pointSourceField(
            55.0, 60.0, 1.0,
            150.0, 145.0, 1.0,
            falloff = 30.0
        )
        val litWorld = ScriptWorld(
            width = ARENA, height = ARENA, fixedStep = 1.0 / HZ, seed = 13L, wrap = false
        )
        litWorld.addWall(100.0, 60.0, 100.0, 140.0, name = "screen")
        val before = ArrayList<Double>()
        for (i in 0 until 16) {
            val x = 30.0 + (i % 4) * 45.0
            val y = 30.0 + (i / 4) * 45.0
            before.add(lamps.at(x, y))
            litWorld.spawn(x, y, maxSpeed = 18.0, radius = 1.0, tag = "phototaxis") {
                moveFor(10.0, 18.0) {
                    gradient(lamps, towards = true, probe = 2.0, weight = 1.0)
                    avoidWalls(clearance = 3.0, weight = 0.5)
                    separate(range = 5.0, weight = 0.4)
                }
            }
        }
        val litTrails = recordTrails(litWorld, 600)
        val after = litWorld.living.map { lamps.at(it.position.x, it.position.y) }
        println("[alife-walls] gradient: mean field %.4f -> %.4f".format(before.average(), after.average()))
        check(
            after.average() > before.average() * GRADIENT_GAIN,
            "steering: a gradient-climbing population ends brighter than it started — " +
                "${f(before.average(), 4)} to ${f(after.average(), 4)}, floor is " +
                "${GRADIENT_GAIN}x the start"
        )
        check(
            after.count { it > 0.5 } >= GRADIENT_SPLIT && before.count { it > 0.5 } < GRADIENT_SPLIT,
            "steering: and it split between the two lamps rather than all finding one — " +
                "${after.count { it > 0.5 }} agents near a source, from " +
                "${before.count { it > 0.5 }} at the start"
        )

        // --- bounce: restitution, in a box ----------------------------------------------------
        val bounce = bounceWorld(seed = 17L)
        val bounceTrails = recordTrails(bounce, 600)
        val speeds = bounce.living.map { it.speed }
        check(
            bounce.actors.sumOf { it.wallContacts } > 20,
            "steering: the bouncing population met the walls repeatedly " +
                "(${bounce.actors.sumOf { it.wallContacts }} contacts)"
        )
        check(
            speeds.average() > 1.0,
            "steering: and kept moving afterwards — mean speed ${f(speeds.average(), 2)}"
        )

        val frame = arenaFrame()
        val panels = listOf(
            "cling(distance = ${f(clingDistance, 1)}), 8 agents, 12 s" to rasterizeFLinesFramed(
                wallLines(clingWorld) + clingTrails, frame, 320, 320, 8
            ),
            "gradient(lamps), 16 agents, 10 s" to rasterizeFLinesFramed(
                wallLines(litWorld) + lampLines() + litTrails, frame, 320, 320, 8
            ),
            "BOUNCE, restitution 1.0, 10 s" to rasterizeFLinesFramed(
                wallLines(bounce) + bounceTrails, frame, 320, 320, 8
            )
        )
        montage(panels, columns = 3, cellSide = 300).savePng(File(outDir, "walls-steering.png").path)
    }

    /** How far the mean clinging distance may sit from the standoff it asked for. */
    private const val CLING_TOLERANCE: Double = 1.5

    /** Eight agents at 26 units/s for 12 s could travel 2 500; floored well under. */
    private const val CLING_TRAVEL_FLOOR: Double = 900.0

    /** The multiple of its starting field value a climbing population has to reach. */
    private const val GRADIENT_GAIN: Double = 1.5

    /** How many of the 16 agents have to end near a source. */
    private const val GRADIENT_SPLIT: Int = 6

    // ==========================================================================================
    // The S-maze the last three figures share
    // ==========================================================================================

    /**
     * The maze: two overlapping barriers with their gaps at opposite ends.
     *
     * The first wall runs from `y = 45` to the top of the arena, so the only way past it is
     * underneath. The second runs from the floor to `y = 155`, so the only way past *that* is over
     * the top. An agent that always turns the same way when it meets a wall solves one of them and
     * is beaten by the other, which is what makes this an argument for a plan rather than for a
     * better local rule.
     */
    private val MAZE_WALLS = listOf(
        doubleArrayOf(70.0, 45.0, 70.0, 200.0),
        doubleArrayOf(130.0, 0.0, 130.0, 155.0)
    )

    private const val MAZE_START_X = 18.0
    private const val MAZE_START_Y = 100.0
    private const val MAZE_GOAL_X = 175.0
    private const val MAZE_GOAL_Y = 100.0

    /** How close to the goal counts as having arrived. */
    private const val MAZE_ARRIVE = 6.0

    private const val MAZE_RADIUS = 1.2

    /**
     * How much room beyond its own radius the route plans for.
     *
     * Set to match `avoidWalls`' reach, and that was a measured correction rather than a guess. The
     * first run planned at `clearance = 0.5`, which puts the route line 1.7 units from a corner —
     * well inside the 4.2-unit range of the `avoidWalls` term the same agent is also running. The
     * two then disagree the whole way round every corner, and the arm recorded 163 wall contacts per
     * agent and a mean arrival of 17.2 s out of a 20 s budget. Planning at 3.5 puts the route
     * outside the avoidance range, so the two terms agree.
     */
    private const val MAZE_CLEARANCE = 3.5

    private const val MAZE_SPEED = 35.0

    /** 1800 steps at 60 Hz is 30 simulation seconds; the routed detour is about 280 units. */
    private const val MAZE_STEPS = 1800

    /** Agents per seed. */
    private const val MAZE_AGENTS = 12

    /** Seeds. Eight of them, so the pooled population is 96 per arm. */
    private val MAZE_SEEDS = listOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L)

    /**
     * One arm of the counterfactual, differing from the other **only** in whether the goal drive is
     * [afield.stdlib.BehaviourSteering.route] or [afield.stdlib.BehaviourSteering.seek].
     *
     * Same spawn jitter from the same seed in both arms, so the two populations start on identical
     * coordinates and any difference downstream is the drive.
     */
    private fun mazeWorld(seed: Long, routed: Boolean): ScriptWorld {
        val w = ScriptWorld(
            width = ARENA, height = ARENA, fixedStep = 1.0 / HZ, seed = seed, wrap = false
        )
        for ((i, s) in MAZE_WALLS.withIndex()) w.addWall(s[0], s[1], s[2], s[3], name = "barrier $i")
        val jitter = kotlin.random.Random(seed)
        for (i in 0 until MAZE_AGENTS) {
            val x = MAZE_START_X + jitter.nextDouble(-8.0, 8.0)
            val y = MAZE_START_Y + jitter.nextDouble(-28.0, 28.0)
            w.spawn(
                x, y, maxSpeed = MAZE_SPEED, radius = MAZE_RADIUS, steerResponse = 0.3,
                tag = if (routed) "routed" else "direct"
            ) {
                val goal = point(MAZE_GOAL_X, MAZE_GOAL_Y)
                moveUntil(
                    maxSeconds = MAZE_STEPS / HZ,
                    speed = MAZE_SPEED,
                    until = { near(goal, MAZE_ARRIVE) }
                ) {
                    if (routed) route(goal, clearance = MAZE_CLEARANCE) else seek(goal)
                    avoidWalls(clearance = 3.0, weight = 0.6)
                    separate(range = 4.0, weight = 0.4)
                }
                rest(2.0)
            }
        }
        return w
    }

    /** A box of agents that wander and reflect off the walls — the BOUNCE and seed-sensitivity arm. */
    private fun bounceWorld(seed: Long): ScriptWorld {
        val w = ScriptWorld(
            width = ARENA, height = ARENA, fixedStep = 1.0 / HZ, seed = seed, wrap = false
        )
        w.addPolygon(
            20.0, 20.0, 180.0, 20.0, 180.0, 180.0, 20.0, 180.0,
            name = "room"
        )
        w.addDisc(100.0, 100.0, 22.0, segments = 20, name = "pillar")
        for (i in 0 until 10) {
            val ang = 2.0 * PI * i / 10.0
            w.spawn(
                100.0 + cos(ang) * 58.0, 100.0 + sin(ang) * 58.0,
                maxSpeed = 40.0, radius = 1.0, steerResponse = 0.5,
                vx = cos(ang + 1.3) * 40.0, vy = sin(ang + 1.3) * 40.0,
                tag = "bounce", wallResponse = WallResponse.BOUNCE, restitution = 1.0
            ) {
                forever { moveFor(1.0, 40.0) { keepVelocity(1.0); wander(turn = 1.0, weight = 0.15) } }
            }
        }
        return w
    }

    /** One arm's pooled outcome. */
    private class ArmResult(
        val n: Int,
        val arrived: Int,
        val arrivalSteps: List<Int>,
        val pathTotal: Double,
        val contacts: Int,
        val failures: Int
    ) {
        val fraction: Double get() = if (n == 0) 0.0 else arrived.toDouble() / n
        val meanArrivalSeconds: Double
            get() = if (arrivalSteps.isEmpty()) Double.NaN else arrivalSteps.average() / HZ
        val meanPath: Double get() = if (n == 0) 0.0 else pathTotal / n

        companion object {
            fun pool(arms: List<ArmResult>): ArmResult = ArmResult(
                arms.sumOf { it.n },
                arms.sumOf { it.arrived },
                arms.flatMap { it.arrivalSteps },
                arms.sumOf { it.pathTotal },
                arms.sumOf { it.contacts },
                arms.sumOf { it.failures }
            )
        }
    }

    /** Run one seed of one arm, recording the step each agent first reached the goal on. */
    private fun runArm(seed: Long, routed: Boolean): ArmResult {
        val w = mazeWorld(seed, routed)
        val arrivedAt = HashMap<Int, Int>()
        repeat(MAZE_STEPS) { s ->
            w.step()
            for (a in w.actors) {
                if (arrivedAt.containsKey(a.id)) continue
                if (a.distanceTo(MAZE_GOAL_X, MAZE_GOAL_Y) <= MAZE_ARRIVE) arrivedAt[a.id] = s + 1
            }
        }
        // Sorted by agent id, so the list is a function of the run and not of a hash map's order.
        val steps = w.actors.mapNotNull { arrivedAt[it.id] }
        return ArmResult(
            n = w.actors.size,
            arrived = steps.size,
            arrivalSteps = steps,
            pathTotal = w.totalPathLength(),
            contacts = w.actors.sumOf { it.wallContacts },
            failures = w.failures.size
        )
    }

    // ==========================================================================================
    // Drawing — everything emits -y, so the figures are the same way up as the coordinates
    // ==========================================================================================

    /** Two single-node lines at the arena corners, so every spatial panel shares one frame. */
    private fun arenaFrame(): List<FLine> =
        listOf(FLine().moveTo(0.0, 0.0, 0), FLine().moveTo(ARENA, -ARENA, 0))

    /** The framing for the tunnelling panels, which are a wide strip rather than the whole arena. */
    private fun tunnelFrame(): List<FLine> =
        listOf(FLine().moveTo(0.0, -40.0, 0), FLine().moveTo(ARENA, -160.0, 0))

    /** Every wall in [w], as its outline. */
    private fun wallLines(w: ScriptWorld): List<FLine> = w.obstacles.map { o ->
        FLine().also { l ->
            l.moveTo(o.points[0], -o.points[1], 0)
            for (i in 1 until o.vertexCount) l.lineTo(o.points[i * 2], -o.points[i * 2 + 1], 0)
            if (o.closed) l.lineTo(o.points[0], -o.points[1], 0)
        }
    }

    /** The start cluster and the goal, as rings, so the task is visible in the figure. */
    private fun markerLines(): List<FLine> = listOf(
        ring(MAZE_START_X, MAZE_START_Y, 10.0),
        ring(MAZE_GOAL_X, MAZE_GOAL_Y, MAZE_ARRIVE),
        ring(MAZE_GOAL_X, MAZE_GOAL_Y, MAZE_ARRIVE * 1.8)
    )

    /** The two lamps in the gradient panel. */
    private fun lampLines(): List<FLine> =
        listOf(ring(55.0, 60.0, 5.0), ring(150.0, 145.0, 5.0))

    private fun ring(cx: Double, cy: Double, r: Double, sides: Int = 24): FLine = FLine().also { l ->
        for (i in 0..sides) {
            val a = 2.0 * PI * i / sides
            val x = cx + cos(a) * r
            val y = -(cy + sin(a) * r)
            if (i == 0) l.moveTo(x, y, 0) else l.lineTo(x, y, 0)
        }
    }

    /** The blocked cells of [g], as outlines — what the router actually saw. */
    private fun gridLines(g: NavGrid): List<FLine> {
        val out = ArrayList<FLine>()
        for (r in 0 until g.rows) for (c in 0 until g.cols) {
            if (!g.isBlocked(c, r)) continue
            val x = c * g.cellSize
            val y = r * g.cellSize
            val s = g.cellSize
            out.add(
                FLine().also {
                    it.moveTo(x, -y, 0); it.lineTo(x + s, -y, 0)
                    it.lineTo(x + s, -(y + s), 0); it.lineTo(x, -(y + s), 0); it.lineTo(x, -y, 0)
                }
            )
        }
        return out
    }

    /**
     * Step [w] for [steps] and return one [FLine] per agent, its trail, with `y` negated.
     *
     * Recorded here rather than taken from [ScriptWorld.trailFLines] because the figures need the
     * negation and the world's own trails are in world coordinates, where `y` grows up.
     */
    private fun recordTrails(w: ScriptWorld, steps: Int, stride: Int = 2): List<FLine> {
        val xs = w.actors.map { ArrayList<Double>() }
        val ys = w.actors.map { ArrayList<Double>() }
        repeat(steps) { s ->
            w.step()
            if (s % stride != 0) return@repeat
            for ((i, a) in w.actors.withIndex()) { xs[i].add(a.position.x); ys[i].add(a.position.y) }
        }
        return xs.indices.mapNotNull { i ->
            if (xs[i].size < 2) null else FLine().also { l ->
                l.moveTo(xs[i][0], -ys[i][0], 0)
                for (k in 1 until xs[i].size) l.lineTo(xs[i][k], -ys[i][k], 0)
            }
        }
    }

    /** The tunnelling traces as one line each, drawn along a fixed y. */
    private fun tunnelLines(xs: List<Double>): List<FLine> {
        val out = ArrayList<FLine>()
        out.add(FLine().also { l ->
            l.moveTo(xs.first(), -100.0, 0)
            for (x in xs) l.lineTo(x, -100.0, 0)
        })
        // A tick per step, so the per-step displacement is visible rather than implied.
        for (x in xs) out.add(FLine().also { it.moveTo(x, -94.0, 0); it.lineTo(x, -106.0, 0) })
        return out
    }

    /**
     * Cumulative arrivals against time, one trace per arm, with a baseline.
     *
     * `y` is negated like everything else here, so "more arrived" is higher on the page.
     */
    private fun arrivalPlot(routed: List<ArmResult>, direct: List<ArmResult>): List<FLine> {
        val out = ArrayList<FLine>()
        val h = 100.0
        val total = routed.sumOf { it.n }.toDouble()
        out.add(FLine().also { it.moveTo(0.0, 0.0, 0); it.lineTo(100.0, 0.0, 0) })
        out.add(FLine().also { it.moveTo(0.0, -h, 0); it.lineTo(100.0, -h, 0) })
        for (arm in listOf(routed, direct)) {
            val steps = arm.flatMap { it.arrivalSteps }.sorted()
            out.add(FLine().also { l ->
                l.moveTo(0.0, 0.0, 0)
                var k = 0
                for (s in steps) {
                    k++
                    val x = s.toDouble() / MAZE_STEPS * 100.0
                    l.lineTo(x, -(k / total) * h, 0)
                }
                l.lineTo(100.0, -(k / total) * h, 0)
            })
        }
        return out
    }

    // ==========================================================================================
    // Driver
    // ==========================================================================================

    /** Figure name to the renderer, and to the PNG it must leave behind (or none). */
    private val figures: List<Triple<String, (File) -> Unit, String?>> = listOf(
        Triple("premise", ::figurePremise, null),
        Triple("tunnelling", ::figureTunnelling, "walls-tunnelling.png"),
        Triple("determinism", ::figureDeterminism, null),
        Triple("counterfactual", ::figureCounterfactual, "walls-counterfactual.png"),
        Triple("steering", ::figureSteering, "walls-steering.png")
    )

    @JvmStatic
    fun main(args: Array<String>) {
        var outPath: String? = System.getenv("ALIFE_WALLS_DIR")
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
            java.nio.file.Files.createTempDirectory("alife-walls").toFile()
        outDir.mkdirs()
        println("[alife-walls] output -> ${outDir.absolutePath}")

        val chosen = only
        val selected = figures.filter { chosen == null || it.first in chosen }
        check(selected.isNotEmpty(), "at least one figure selected (--only ${chosen?.joinToString(",")})")
        val started = System.nanoTime()
        for ((name, render, _) in selected) {
            val t = System.nanoTime()
            println("[alife-walls] --- $name ---")
            runCatching { render(outDir) }.onFailure { fail("$name threw: $it") }
            println("[alife-walls] --- $name done in %.1f s ---".format((System.nanoTime() - t) / 1e9))
        }

        // Bidirectional ledger, same as AlifeScriptGallery's: a figure that stopped writing its PNG
        // fails, and a leftover PNG from a figure that no longer declares one fails too. "Every
        // declared file exists" passes forever after a deletion.
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
            "[alife-walls] %d check(s), %d failure(s), %.1f s total"
                .format(checks, failures.size, (System.nanoTime() - started) / 1e9)
        )
        if (failures.isNotEmpty()) {
            println("[alife-walls] FAILURES:")
            failures.forEach { println("  - $it") }
            exitProcess(1)
        }
        println("alife walls gallery rendered")
    }

    /**
     * The fraction of a PNG's pixels that are meaningfully brighter than its darkest.
     *
     * A byte-length check passes on a 300x300 PNG of pure black, which is what a figure looks like
     * when the geometry missed the frame — the single most common way a rendered figure is wrong
     * while every other check is green. This reads the file back and counts. It is still not a
     * substitute for opening the image, which is why the branch worklog records that it was.
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
