# A walking slinky: model, energy, and measurements

User-facing page: `public/docs/physics.md#a-slinky-walking-down-stairs`. Code:
`src/jvmMain/kotlin/afield/simulation/physics/Slinky.kt` (`Slinky`, `SlinkyOnStairs`) and
`SimdVerletColliders.kt` (moving colliders, `StaircaseCollider`, `ColliderSet.relax`). Tests:
`SlinkyTest`. Starting point: `dev_docs/agents/transcripts/slinky_escalator.yaml`. Idea:
https://morethanamachine.com/posts/slinky-escalator-benchmark/ (no source published; this is built
from standard physics on `SimdVerlet`).

## Model

Two rim particles per coil (side view). Forces, in `constraints4`: rail springs between
neighbouring rims at `stiffness`, rest `pitch * (1 - preload)`, and crossed diagonals to the
opposite rim of each neighbouring coil at `shearStiffness`, rest `sqrt(diameter² + pitch²)`. A
position pass per substep, `passes` times: rigid rungs; the face contact (`contactOnFace`: rim
`k` of coil c + 1 at least `pitch` above coil c's face, projected along its whole gradient, which
turns coil c's rung like a lever); then `restingOn.relax` and the colliders against three samples
along every rung, each particle taking the weighted mean of its samples' votes. After the passes:
inelastic stop of touching coils, internal (relative-velocity) damping. World drag is
`velocityKeptPerSecond` (`air`, default 0.9 kept per second). `SlinkyOnStairs` steps in substeps
of 1/600 s whatever its frame, and moves the starting hand per substep, so a 1/300 s frame (the
page's 1/5 slow motion) is the same walk as 1/60 s frames.

## Energy: the four pumps

The old walk was powered by solver errors, not by the fall. Each was found by measuring kinetic
plus spring plus gravitational energy, which must only fall, in free space and on still stairs:

- **Shear alignment** (a projection shifting neighbouring coil centres onto one axis across one
  coil's normal) read a plain bend as shear and multiplied a free slinky's energy 16x in ten
  frames. Replaced by the diagonal springs. A bisector-normal version still pumped.
- **Lopsided contact** (push the lower rim and the upper rim apart along the lower normal,
  ignoring that the normal turns with the rung) did work at every touch: 20x in two seconds.
  `contactOnFace` adds the lever term. A plain rim-to-rim distance contact is energy-safe but
  blind to direction: the shut stack rolled round its rims and collapsed like a deck of cards.
- **Summed edge votes.** Three rung samples each moving both rung ends the whole way put a rung
  lying flat on a tread out by up to 2.6x its penetration, so every landing bounced. Now averaged.
- **The fold guard** moved positions without previous positions, handing coils velocity whenever
  it fired. Making it a teleport fixed that; it has since gone with the contact it guarded, and
  the per-pass clamp of one pitch is all that remains.

`SlinkyTest.aSnappedSlinkyOnlyLosesEnergy` is the guard: a stretched arc released in empty space
with no damping must never rise above its first frame and must ring down below 5% in 2 s. Positive
control (2026-11-26): with the lever term zeroed it rises 16% and ends at ~20%.

Rings (`perCoil` 4-8, an earlier experiment) were removed: they only stood because of the alignment
projection, and with energy-safe contact (including a rigid-ring version with the inertia tensor)
the ring stack slid apart in z. A ring slinky needs a shear model that stands without pumping.

## Escalator

A constant-speed escalator is an inertial frame, and friction is computed relative to the moving
tread, so `SlinkyOnStairs` starts the escalator when the hand lets go and boosts every particle to
tread velocity (otherwise the stack starts at rest relative to the world and slides off the front
edge). The stairs' pass is registered before the coil pass, so the coil pass projects against
where the treads are now, not a substep ago. With light air the walk keeps its own pace and the
escalator period sets the drift. Heavy air (0.01, the old default) was what made the old "matched"
walk: with honest contact it only stands.

## Measurements (30 s per run, JVM harness, defaults unless named)

Defaults: run 1.5, rise 1.1, stiffness 32000, preload 0.5, shearStiffness 16000, friction 1,
air 0.9, hand 0.6 s, reach `0.5 run + 0.4`. "Regular" = longest run of flip gaps >= 0.2 s within
+-30% of their mean. One run per row; the walk is chaotic.

| scene | flips | regular | steps | outcome |
| --- | --- | --- | --- | --- |
| defaults | 128 | 46 | 333 | 2.6 steps a flip, walks the whole time |
| rise 1.6 | 131 | 113 | 400 | 3 steps a flip |
| run 2.0, rise 3.0 | 134 | 94 | 341 | 2.5 steps a flip, big drops |
| run 2.1, stiffness 16000 | 21 | 10 | 21 | 1 step a flip, stands at ~12 s |
| run 1.9-2.2, stiffness 12000-20000 | 2-66 | 1-12 | | 1 step a flip, all stop |
| escalator 0.09 s/step | 128 | 38 | 336 vs 327 carried | within ~10 steps of start for 30 s |
| escalator 0.10-0.15 s/step | 120-130 | 40-80 | | outwalks it, 30-110 steps down |
| escalator 0.12 s/step | 110 | 28 | 266 vs 245 | carried up 35, then walks back |
| run 2.1, stiffness 16000, escalator 0.6 | 17 | 13 | | keeps pace 9 s, stops, rides up |

## Open

Stiffer shear: the one-step walk comes to rest as a shut stack leaning about 45°, which a real
helix would not hold, so `shearStiffness` (16000) is too soft for a shut stack, and a stiffer one
will change every number above. A one-step gait that does not run out; the escalator period range that holds station (only 0.09
held over 30 s, and it wanders); rings; real units against a filmed toy.

## Method

A throwaway `kotlinc` harness against `build/classes/kotlin/jvm/main` driving `SlinkyOnStairs`
(about 20 s of CPU per 30 s run, eight in parallel), not checked in; the durable checks are
`SlinkyTest` and the page's fences, which recompute the figures from the library.
