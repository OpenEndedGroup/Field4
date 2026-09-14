# Measuring a world with walls

How `afield.stdlib.alife_walls` was verified on 2026-09-14, the numbers with their populations, and
the three bugs the harness found in the code it was written for.

**Provenance.** Every number below was produced by the branch's own harness,
`afield.examples.stdlib.AlifeWallsGallery` (`FIELD_TEST stdlib.alife.walls.gallery`), and is
transcribed from runs made while writing this page. Populations are stated with every figure,
because a number without its population is not a measurement. What was *not* done is listed under
*What this page does not establish*.

## Method

```bash
# the harness: 56 checks, five figures, under a second of figure time
uv run --project . python tools/run_field_tests.py --id stdlib.alife.walls.gallery

# render the figures somewhere that survives, and LOOK at them
uv run --project . --group backdoor python tools/field_backdoor.py --port 8801 \
  --workspace dev_docs/agents/demos --document alife_walls \
  --command 'run-jvm-main afield.examples.stdlib.AlifeWallsGallery --arg /abs/path/to/out --timeout 600'

# one figure while iterating
#   ALIFE_WALLS_DIR=<dir> is also read, and --only premise,tunnelling selects figures
```

As a FIELD_TEST the harness renders to a temp directory and writes no committed asset, which is the
house rule `checkTestTreeWrites` enforces; `AlifeScriptGallery` is the precedent this copies. The
three committed figures under `dev_docs/assets/alife_walls/` were produced by the `--arg` form
above and **opened and looked at**, not merely byte-checked — see *Figures*.

## The geometry, by arithmetic — population: 6 closed-form cases

Checked absolutely rather than against a control, because the right answer is a sum. If these drift
every simulated result below becomes a tautology.

| case | measured | arithmetic |
| --- | --- | --- |
| time of impact, radius-1 circle driven 10 units at a wall 5 away | `t = 0.400000000000` | exactly `0.4` |
| the normal it reports | `(-1.000, 0.000)` | back along the approach |
| a wall whose span the path passes below | no contact | no contact |
| grazing the *end* of a wall, contact x | `4.133974596` | `5 - sqrt(1 - 0.25) = 4.133974596` |
| `SLIDE`, from velocity `(100, 50)` into a vertical wall | `(0.000000000, 50.000000000)` | `v - n(v·n)` |
| `BOUNCE`, same, `restitution = 0.5` | `(-50.000000000, 50.000000000)` | `v - n(v·n)(1 + e)` |
| `STOP`, same | `(0.000000000, 0.000000000)` | zero |

The three response cases run under `keepVelocity` with `steerResponse = 1`, so nothing
re-accelerates the agent after the contact and the reading is the response's own output. An earlier
version drove them with a `heading` term and measured `-49.998` instead of `-50` — a bound rather
than an identity, because the agent had already started being pushed back at the wall.

Also in this figure, each a single case: an agent spawned 5 units inside a block is pushed to
`5.000` from the wall against its radius of `2.0`; a nav grid at `inflate = 2.0` over one full-height
wall and the boundary blocks `7.0%` of `7056` cells; a goal behind an unbroken wall returns no
route; the same wall with a gap returns a `191.5`-unit route against a `160`-unit direct line; a
sight line is blocked by a wall and clear below its end.

## Tunnelling — population: 1 agent, 12 steps, two integrators

The point of the word "swept". At 900 units/s and 60 Hz the per-step displacement is `15.00` units
against an agent radius of `0.5` — **thirty radii**. A wall here is a line segment with no
thickness, so a discrete "does the new position overlap a wall" test can miss it at any speed; at
this one it misses on 29 steps out of 30.

| arm | final x | wall at |
| --- | --- | --- |
| swept (`ObstacleField.sweep`) | `99.500`, after 7 contacts | `100.0` |
| discrete control | `200.000` | `100.0` |

The control, `AlifeWallsGallery.discreteCrossing`, is the integrator this branch replaced — Euler
plus an endpoint overlap test — written out by hand rather than obtained by switching something
off, because a control that shares code with its subject is not a control. It ends past the wall
and out at the far edge of the arena. Without that arm the swept result says nothing.

## Determinism — population: 12 agents, 1800 steps at 60 Hz, plus 10 agents × 400 steps

A\* is the one thing this branch adds with an open set in it, and an open set ordered by anything
short of a total order is the classic place a simulation stops being reproducible without looking
like it would. So determinism is asserted on the routed maze, not merely on the physics.

- `state()` (48 doubles) compared with `contentEquals` between two worlds built and stepped
  identically in one JVM — equal.
- `eventSignature()` equal, over **35 events**. The maze script is wrapped in a `phase` and logs its
  arrival for exactly this reason: an empty log's digest compares equal for the trivial reason, and
  the first version of this check was passing on one.
- The run really searched: **15 A\* searches, 4 deferred** by the per-step budget of 8.
- The run really moved: **3 678 units** across 12 agents, floor 1 500.

Cross-JVM, from two independent invocations of `run_field_tests.py` at 02:41:38 and 02:41:46, each
starting its own Field and each spawning its own JVM:

```
run A: SIGNATURE state  ac84ab0f0d989b35
       SIGNATURE events b8e71ea814b27aa6
run B: SIGNATURE state  ac84ab0f0d989b35
       SIGNATURE events b8e71ea814b27aa6
```

And the other direction, which is what stops "deterministic" meaning "frozen": a walls-and-wander
population of 10 agents over 400 steps reproduces at seed 7 (`2600283192849c31`) and **differs** at
seed 8 (`d2e0c34b844678e4`), having made **304 wall contacts**.

## The counterfactual — population: 8 seeds × 12 agents = 96 per arm, 1800 steps (30 s)

An S-maze. The first barrier runs from `y = 45` to the top of the arena, so the only way past is
underneath; the second runs from the floor to `y = 155`, so the only way past *that* is over the
top. No fixed local turning preference solves both, which is what makes this an argument for a plan
rather than for a better local rule.

The two arms are identical in every respect but one drive: same arena, same walls, same integrator,
same speeds, same spawn positions from the same seed, same `avoidWalls(clearance = 3.0, weight =
0.6)` and `separate(range = 4.0, weight = 0.4)`, same arrival predicate (within 6 units), same step
budget. Arm A steers with `route(goal, clearance = 3.5)`; arm B with `seek(goal)`, the verb that was
already there and is untouched by this branch. `avoidWalls` is in **both** arms, so this is routing
against the best local steering available rather than routing against nothing.

| arm | arrived | mean arrival | mean path per agent | wall contacts |
| --- | --- | --- | --- | --- |
| routed | **95 / 96 = 0.990** | 18.49 s | 297.5 | 7 495 |
| direct | **0 / 96 = 0.000** | — | 90.8 | 148 425 |

Floors, all written into the branch worklog **before** the first run:

| floor | declared | measured |
| --- | --- | --- |
| routed arrival fraction | ≥ 0.80 | 0.990 |
| direct arrival fraction | ≤ 0.25 | 0.000 |
| margin | ≥ 0.50 | 0.990 |
| non-degeneracy: mean path, **both** arms | ≥ 40.0 each | 297.5 and 90.8 |

The non-degeneracy floor is on both arms deliberately. The failure mode of a counterfactual like
this is two stuck populations reporting a clean margin of zero against zero; requiring both arms to
have travelled at least 40 units — the start is 50 units from the first barrier — makes that fail
instead of pass. There is a second such floor on wall contacts: an arm with none was tested by an
empty arena.

Two diagnostics print alongside, and both earned their place during the debugging below:

- **Where the stragglers stopped.** `21 at mean (69, 65), mean spread 9` is a trap; `1 at mean
  (166, 113), mean spread 0` is one agent 15 units short of the goal with the clock run out. Those
  are different problems with the same arrival fraction.
- **One routed agent, no crowding.** `arrived at 19.67 s over 333 units, 296 contacts`. Twelve
  agents queuing at a gap is a perfectly good reason for a straggler and says nothing about whether
  the route was right; this arm separates the two.

## The local steering — populations stated per claim

- **`cling`** — 8 agents, 12 s, round a 34-unit pillar: mean distance to the nearest wall `4.001`
  against a requested standoff of `radius 1.0 + distance 3.0 = 4.000`, while travelling `2 477`
  units. Both halves matter: a trail that looks like a circle round a pillar is also what `orbit`
  draws at the right radius, and an agent parked against the wall would score perfectly on distance
  alone.
- **`gradient`** — 16 agents, 10 s, two lamps: mean field value `0.3719` → `0.9505`, with `14` of 16
  ending within range of a source from `4` at the start. It **splits** between the two lamps, which
  is the correct behaviour of a local slope sensor rather than something to weight away.
- **`BOUNCE`** — 10 agents, 10 s, `restitution = 1.0`, in a room with a pillar: `363` wall contacts
  and a mean speed of `28.76` afterwards.

## Figures

Three PNGs under `dev_docs/assets/alife_walls/`, all checked for an ink fraction above 0.002 by the
harness **and** opened and inspected:

- `walls-tunnelling.png` — the two traces, one tick per step, with the wall drawn across both. The
  swept ticks bunch at the wall; the discrete ones march straight through at even spacing.
- `walls-counterfactual.png` — routed trails, direct trails, the nav grid the router saw, and the
  cumulative arrival curves.
- `walls-steering.png` — cling, gradient and bounce.

Every drawing helper in the harness emits `-y`, because `rasterizeFLines` writes row 0 at the top
and a maze drawn in the obvious sense comes out mirrored — the gap at the top of a wall appears at
the bottom. That applies to the spatial panels here as well as to the plots, which is one
difference from `AlifeScriptGallery`.

## The three bugs this harness found

All three presented identically: "the routing does not work", 0.78 arrived, agents against a wall.

1. **The route planned inside its own avoidance range.** `route(clearance = 0.5)` puts the path 1.7
   units from a corner, and the same agent's `avoidWalls(clearance = 3.0)` pushes on anything within
   4.2. The two disagreed the whole way round every corner: **163 wall contacts per agent** and a
   mean arrival of 17.2 s out of a 20 s budget. Planning at 3.5 fixed it. This is now the first of
   the two warnings in the demo README.
2. **A waypoint follower with no recovery.** A plan is a straight line between waypoints and an
   agent is not — inertia, blended drives, a radius — so it clips corners. A clip puts it against a
   wall, `SLIDE` carries it along the face, and if its waypoint is now through that wall then every
   step steers into the wall with zero tangential component and nothing ever changes its mind.
   Measured with the crowding removed: one agent, travelled 100 units, then pressed on the same
   corner for **1 566 consecutive steps**. `route` now replans when it stops closing on its waypoint
   for `stallSeconds` (0.75 s by default), from where the agent actually is.
3. **A clearance test with a hole in it.** `ObstacleField.clearPath` was `sweep(…) == null`, and
   `sweep` reports nothing for a circle that is *already* overlapping — a contact at a negative time
   is a depenetration problem rather than a collision. So asking it for clearance from a position
   already too close returned "clear" about the very wall the agent was pressed against, and
   `NavGrid.stringPull`, which starts from the agent's real position, took the shortcut straight
   through it. The agent then spent the run grinding along the face of a wall its own route went
   through. `clearPath` is now a segment-to-segment distance test. This one fix moved the
   counterfactual from **0.781 to 0.990** arrived over 96 agents and cut the routed arm's wall
   contacts from **31 862 to 7 495**.

Bug 2's fix alone was not enough — with it and not bug 3's fix, the stranded agents simply moved
from `(69, 65)` to `(69, 160)`, still against the same wall. The diagnostic that separated them was
printing *where* the stragglers stopped.

## What this page does not establish

Each of these is a gap, with the reason it is open.

- **Performance was not measured.** No timing is reported for `route` under a large population. The
  whole harness runs its five figures in 0.7 s, which bounds the work at this scale (96 agents ×
  1800 steps × 2 arms, plus four more figures) and says nothing about 500 agents on a 40 000-cell
  grid. The per-step search budget `maxRouteSearchesPerStep` exists precisely because that cost was
  reasoned about rather than measured.
- **Only two maze topologies were exercised** — the S-maze and the single-wall-with-a-door in the
  premise figure. Concave rooms, crossing walls and a fence with several gaps are all supported by
  the segment representation and none of them appear in a test.
- **Coarse grids are untested.** `NavGrid.coarse` is set when the cell cap forces a larger cell, at
  which point the grid may close a gap that is really open. Every grid in this harness is fine
  (`36x36 @ 5.64` and `84x84 @ 2.40`, neither capped), so the coarse branch has a flag and no
  measurement behind it.
- **Cross-platform reproducibility is not claimed.** The cross-JVM signatures above are two
  invocations on one machine. The arithmetic is IEEE doubles with no intrinsics, which is a reason
  to expect portability and not evidence of it.
- **jvmTest, docsGates and citationGates were not run over this branch at its base commit**, so
  "no regression" in those is unestablished in both directions. `compileKotlinJvm`,
  `compileTestKotlinJvm`, `checkCodebaseReference`, `checkAgentDemos`, `checkAgentTranscriptLayouts`
  and the smoke catalog parse were run, and are reported in the branch worklog with their results.

## Reading the code

- `src/jvmMain/kotlin/afield/stdlib/alife_walls.kt` — obstacles, the swept primitive, the nav grid,
  `ScalarField`.
- `src/jvmMain/kotlin/afield/stdlib/alife_script.kt` — the world's obstacle layer, the swept
  integrator, and the `avoidWalls` / `cling` / `route` / `gradient` drives.
- `src/jvmMain/kotlin/afield/examples/stdlib/AlifeWallsGallery.kt` — the harness.
- `dev_docs/agents/demos/alife_walls/` — the loadable demo.
- `dev_docs/ALIFE.md` — where this sits in the family.
