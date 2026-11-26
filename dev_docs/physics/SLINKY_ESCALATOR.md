# Slinky on an up escalator: model, tuning and measurements

User-facing page: `public/docs/physics.md#a-slinky-on-an-up-escalator`. Code:
`src/jvmMain/kotlin/afield/simulation/physics/Slinky.kt` (`Slinky`, `SlinkyOnStairs`) and
`SimdVerletColliders.kt` (moving colliders, `StaircaseCollider`, `ColliderSet.relax`). Tests:
`SlinkyTest`. Idea: https://morethanamachine.com/posts/slinky-escalator-benchmark/ (no source
published; this is built from standard physics on `SimdVerlet`).

## Model

Two rim particles per coil (side view). Rails: `constraints4` springs between neighbouring rims at
`stiffness`, rest `pitch * (1 - preload)`. A position pass per substep, `passes` times: rigid rungs;
translational shear between neighbouring coil centres (`alignment`); one-sided rim-to-rim contact
along the lower coil's normal, clamped to one pitch per pass; then `restingOn.relax` and the
colliders against three samples along every rung. After the passes: inelastic stop of touching
coils, internal (relative-velocity) damping. World drag is `velocityKeptPerSecond` (`air`).
10 substeps of 1/600 s per 60 Hz frame.

## Traps found on the way (each cost a run)

- **Projection order.** Colliders registered before the slinky's pass let the coil contact push
  coil 0 into the floor every substep; the stack sank. Fix: the coil pass calls
  `ColliderSet.relax` after each of its passes (`Slinky.restingOn`).
- **Teleport corrections pump or starve.** Moving `x` and `px` together for contact left the
  stack's common fall velocity in place, so support never propagated up a 40-coil stack (top in
  free fall). Position-only (standard PBD) corrections fixed it.
- **Rotation-based alignment is unstable** (central-difference tangent feeds back through the
  contact); translational shear is stable.
- **Sign of the inelastic velocity fix**: `v = x - px`, so raising `px` lowers `v`. The first
  version doubled the approach speed instead of removing it.
- **Rung vs step nosing.** With rim-only collision the step corner slips between A and B and the
  slinky hangs across the riser. Rung samples fixed it.
- **Springy stack.** Without the inelastic coil stop every landing returned energy and the walk
  accelerated into a tumble.

## Why drag is the coupling

A constant-speed endless escalator is an inertial frame. Friction is computed relative to the
moving tread, so without world drag the escalator speed cannot change the walk at all. With
`air = 0.01` the walk locks to the escalator; with `air >= 0.05` the lock is short and the slinky
alternates stall and tumble; with `air = 1` (no drag) it either stops or accelerates down the
treads. On still stairs with `air = 0.01` it stands (`SlinkyTest.onStillStairsTheSameSlinkyStops`).

## Measurements (run 40-60 s, defaults: run 2.5, rise 1.5, stiffness 16000, friction 0.6, tip 6, air 0.01)

"Matched" = longest window whose world height stays inside a 2-rise band; steps counted relative to
the treads. One run per row; the dynamics are chaotic, so neighbouring rows are not smooth.

| seconds per step | matched steps (window) | mean walk rate | outcome |
| --- | --- | --- | --- |
| 0.25 | 12 (2.7 s) | 4.5 /s | flung off the treads |
| 0.40 | 9 (3.0 s) | 2.2 /s | falls behind, carried up |
| 0.55 | 19 from t = 0 (51 steps within 20% of 0.575 s period) | 1.74 /s | slow drift up |
| 0.575 | 54 (31.8 s), after two stumbles | 1.67 /s | default; ~100 steps in 60 s vs 104 carried |
| 0.60 | 16 | 1.38 /s | stutters |
| 1.00 | 5 | 0.89 /s | stalls standing between steps |

Natural gait period: 0.573-0.583 s per step (median in the docs height figure: 0.583 s).

## Method

A throwaway `kotlinc` harness against `build/classes/kotlin/jvm/main` (seconds per run, parallel
grids), not checked in; the durable checks are `SlinkyTest` and the three docs fences, which
recompute the figures above from the library.
