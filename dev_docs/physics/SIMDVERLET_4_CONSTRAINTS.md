# SimdVerlet DirectedConstraints4 Extension

Design notes for the 4-slot distance-constraint extension to the `SimdVerlet`
soft-body engine. The per-method API surface lives in KDoc on
`SimdVerlet.DirectedConstraints4` (in
`src/jvmMain/kotlin/afield/four/physics/SimdVerlet.kt`); this doc only captures
the rationale that doesn't belong inline.

## Why 4 slots

`DirectedConstraints4` extends the existing `DirectedConstraints3` from 3 to 4
outgoing distance constraints per particle. The motivating case is a regular
cloth grid: each interior particle has four axis neighbors (up, down, left,
right), so it needs four slots to pin all of them from a single owner. Slots are
stored as parallel arrays (`n*` neighbor indices, `r*` rest lengths, `s*`
stiffnesses); an unused slot is `neighbor = -1` or `stiffness = 0`.

Populate with `setSlot(index, slot, neighbor, restLength, stiffness)`. Particle
removal is handled by `State.applyQueuedRemovalsSwap`, which uses the (internal)
`swapConstraints` / `remapNeighbors` helpers to keep the swap-remove compaction
consistent.

## SIMD solver and thread safety

The constraints are solved by `solveDirectedDist4InPlace` /
`solveDirectedDist4InPlaceRange` using the JDK Incubator Vector API. Neighbor
positions are gathered through the per-slot maps in `ConstraintScratch`
(`map0`..`map3` — the 4th map is what this extension added), distances use SIMD
sqrt, and a scalar remainder-lane fallback finishes the tail.

The important design constraint is that the solver is **range-parallel safe**:
each particle accumulates only onto its own position, and there are *no scatter
writes back to neighbors*. That means non-overlapping particle ranges can be
solved on separate threads, each with its own scratch, without locking. This
accumulate-own-particle-only rule is what makes the directed (rather than
symmetric) constraint layout worthwhile.

## Cloth grid wiring and curvature force

`afield.examples.ClothSimulationExample` wires a grid of particles with 4-way
constraints and returns a `Sequence<List<FLine>>` of the cloth wireframe for
visualization. It exposes an optional **curvature force**: for each particle it
computes the average position of its four grid neighbors and pushes the particle
toward that average (scaled by `curvatureStrength`). This is a cheap bending
term that tries to flatten local kinks in the cloth; it is off by default
because it costs work proportional to particle count.

Interactive grabbing is layered on top via `InteractiveClothSimulation`
(`grabNearest` / `moveGrabbed` / `releaseGrabbed`, with 2D and 3D overloads).
Grabs are implemented as PID position controllers that smoothly drive the
grabbed particle to the target rather than teleporting it.

## Running the examples

`jvmRun` cannot launch these: its `mainClass` is hardcoded to `afield.Server`
(see `build.gradle.kts`), so `./gradlew :jvmRun --args="afield.examples.X"` just
boots the full Field4 server with a stray program argument — it never calls the
example's `main()`. To actually run one, use an IDE run configuration on the
example's `main()`, or add a dedicated `JavaExec` Gradle task pointed at the
example class (the repo already uses this pattern, e.g. the
`runSimdVerletBulkKernelsExample` task).

Relevant examples:
- `afield.examples.TestDirectedConstraints4` — minimal 4-slot smoke test.
- `afield.examples.ClothSimulationExample` — full interactive cloth.
- `afield.examples.SimdVerletTinyExample` — the 3-slot warmup.

## Tuning

- Higher iteration counts (8-10) give more stable constraints at higher cost.
- 4 slots per particle is the maximum; leave unused slots at `-1` / stiffness 0.
- The curvature force is optional and adds per-particle cost.
