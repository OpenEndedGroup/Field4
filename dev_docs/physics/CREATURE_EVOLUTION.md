# Creature evolution (`afield.simulation.creatures`)

A genetic algorithm that evolves 2D creatures. It began as a water-bug search in
`src/personal/kotlin/organic/gp/` (`Creatures.kt`, `CreatureEvolutionExample.kt`, package `organic.gp`;
the personal copy is still there and is independent of this one). It now lives in
`src/jvmMain/kotlin/afield/simulation/creatures/Creatures.kt`, and the starting point is the
`evolving_water_bugs` transcript.

## What the library does and what the box does

The library holds what any such creature needs:

- **Genome** (`CreatureGenome`): points, edges (rest lengths, at most degree 4, planar, connected), one
  `ContactProgram` per point (a phase, duty, smoothness and strength: when that foot is down in the cycle),
  `JointProgram`s (a cyclic spline of target angles about a pivot between two edges), and evolvable physical
  gains (clock period, mass, stiffness, damping, `freeDrag`, `footDrag`, `footStickiness`, joint torque).
- **Search** (`CreatureEvolution.evolve` / `evolveFlow`): elitism, tournament selection, crossover, 19
  structural and parametric mutations, immigrants, and more mutation plus immigrants while stagnating.
  Repair keeps every child connected, planar and within its edge, joint and actuation budgets.
- **Body**: `SimdVerlet` particles with `DirectedConstraints4` springs, PD angle motors with filtered targets
  and impulse clamps, and an explosion/instability check.
- **Playback**: `frameLines(i)` (body, feet down in orange, grip anchors, centroid trail) and
  `notationLines(i)` (one row per point with its foot-down intervals, and torque bows at the playhead).

The box holds what makes it a particular creature:

- **`ContactModel`**: one `Foot` per point, returning a force from a `FootStep` (programmed contact, position,
  velocity, genome). The water bug grips the surface film where a foot lands and drags otherwise. The crawler
  pushes up out of a floor and only grips on it. `place(points)` positions the body at the start, and only
  rigid moves are safe there, because rest lengths and angles come from the genome.
- **`fitness`**: a `(CreatureEvaluation) -> Float` that replaces the built-in `fitnessObjective`. It gets
  `displacement`, `netCentroidMotion`, `meanStretchError`, `meanActuationEffort`, `meanTargetSlew` and the rest.
  Exploded creatures score `explodedFitness`, and so does a non-finite result.
- **The experiment's numbers**: population, point and edge counts, foot fractions, motor gains, stability
  limits. The old `simpleExperimentConfig()` preset was the water bug's tuning, so it moved into the box.

## Changes made on the way in

- Gravity is now an acceleration. `SimdVerlet` divides `ax/ay` by mass whenever `invMass` is present, and its
  `gravity` argument adds to `ay`, so heavy points used to fall more slowly. The runtime now passes
  `gravity = 0` and adds `gravity * mass` per point.
- The search breeds from the genome it scored, i.e. the repaired one (`evaluation.genome`). It used to breed
  from the raw child.
- A child that repair cannot validate is dropped. Before, `simulate`'s `require` ended the whole run.
- The throwaway simulation of a random genome that seeded `champion` is gone.
- Foot and joint programs are sampled once per cycle step into tables. Before, each was re-normalized and
  re-splined on every physics step.
- `evolve(config, controller) { update -> }` takes a stop controller and hands over the full `EvolutionUpdate`.
- `CreatureEvaluation.displacement` was added. `CreatureSnapshot.down` is a `List<Boolean>`, because a
  `BooleanArray` broke data-class equality.
- Point sizes and colours suit `showPlane`, where a dot's radius is 3 × `pointSize` px.
- Dead code was removed: `centered()`, `raisedCosinePulse`, and the unread `lateralProgress`.

## Known issues (from a review pass, not yet fixed)

- `isUnstable` counts `centerSpeed > maxCenterSpeed` as an explosion, so the fastest creatures score
  `explodedFitness`. A soft penalty or a separate verdict would be better.
- Motor torque is capped at `|signal| * jointTorqueScale`, so a joint goes limp twice per cycle at its zero
  crossings and cannot hold its rest angle. Under gravity, bodies flatten: the crawler's early champions lie
  flat on the floor. Nothing gives an edge pair any angular stiffness except the motors.
- `targetJointActivations` drifts. `withJointPopulation` only adds joints, and `repairEdgeCount` refreshes
  joints with no target.
- dt is `clockPeriod / stepsPerCycle`, so evolving the clock also changes the integration step and the
  per-step damping.
- The repair chain is written out three times in different orders (`simulate`, `repairGenome`, `crossover`),
  and `mutate` repairs after every step. One `sanitize()` should replace them.
- Elites are re-simulated every generation, although their scores are deterministic.
- `Float2` is a private-looking vector type in a public API; the edge of the API could take `Vec2`.
- `afield.math.optimization`'s `PopulationSearch` (used by `alife_evolution`) is a general engine this
  could sit on. The creature search predates it and has its own stagnation and immigrant logic.

## Tests

`CreatureEvolutionTest` (jvmTest) checks that random genomes validate and simulate, that drag alone goes
nowhere, that evolution is deterministic under a seed and its champion never gets worse, that custom fitness
replaces the objective, and that the controller stops a run. `DemoTranscriptExecSmokeTest` launches both
boxes of the transcript.
