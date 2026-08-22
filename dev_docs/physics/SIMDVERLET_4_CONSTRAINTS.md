# SimdVerlet four-slot directed constraints

The API and solver contract are documented beside
`SimdVerlet.DirectedConstraints4` in
`src/jvmMain/kotlin/afield/four/physics/SimdVerlet.kt`. This note records only
the design rationale and runnable examples.

Four outgoing slots match the up/down/left/right neighborhood of an interior
cloth-grid particle. Each particle owns its four neighbor indices, rest lengths,
and stiffness values in parallel arrays; `setSlot(...)` accepts slots 0 through
3. Particle swap-removal updates those arrays and remaps neighbor indices.

`solveDirectedDist4InPlaceRange(...)` gathers neighbor positions with the JDK
Incubator Vector API and writes each correction only to the particle that owns
the constraint. It never scatters a correction to the neighbor. Non-overlapping
particle ranges can therefore run concurrently when each worker has its own
`ConstraintScratch`.

## `Cloth`

`afield.four.physics.Cloth` is the reusable consumer: `sim.addCloth(width, height)`
appends a grid patch to a simulation that may already hold other particles, wires
the four slots, and hands back a handle that speaks grid coordinates
(`cloth[col, row]`, `pin`, `teleport`, `grabNearest`, `lines`). Three things about
that shape are load-bearing rather than incidental:

- **One constraint set per simulation, not per cloth.** The solver walks
  `0 until state.size` and indexes the slot arrays by *particle* index, so a cloth
  can't own a set covering only itself. A cloth therefore adopts
  `SimdVerlet.constraints4` if one is present and otherwise publishes the one it
  creates — two cloths in one simulation share a single set — and `Cloth.step`
  calls `DirectedConstraints4.ensureCapacity(state.size)` so that a simulation
  which grew after the cloth was built doesn't trip the solver's size `require`.
  The slot arrays are `var` precisely so that growth is in-place and every holder
  of the set keeps seeing it.
- **Curvature is one bulk pass, not a generator per particle.** Flattening a
  particle toward the mean of its four axis neighbors is a whole-patch force, and
  the per-particle generator API would charge `width * height` coroutine resumes
  per frame for it. It is registered in `SimdVerlet.bulkForces` instead — a named
  whole-state pass run by `step()` in the same slot as its `accumulateForces`
  argument, but registered on the simulation, so it survives plain
  `sim.step(...)` calls made by code that has never heard of the cloth.
  `cloth.curvatureStrength = 0f` switches it off live.
- **A cloth does not survive particle removal below its top index.** Its particles
  are the contiguous range `[base, base + count)` and both the slots and the
  curvature sweep are written in those terms; `applyQueuedRemovalsSwap` compacts
  and renumbers. Appending particles after a cloth is fine.

Grabbing goes through `addPidPositionController`, so a drag pulls through the
constraint solve instead of teleporting a particle out of the sheet.

Runnable entry points:

- `afield.examples.TestDirectedConstraints4`: minimal four-slot smoke.
- `afield.examples.ClothSimulationExample`: thin demo over `Cloth` — frame
  sequence, grabbed-particle crosshair, and the process-level handle an
  interactive box can call without holding the simulation.
- `afield.examples.SimdVerletTinyExample`: earlier three-slot example.
- `ClothTest` (`jvmTest`): grid wiring, curvature definition, constraint-set
  growth and sharing. It adds its cloths to *non-empty* simulations on purpose —
  `base == 0` hides every offset bug in the wiring.
- `./gradlew runSimdVerletBulkKernelsExample`: the repository's dedicated
  `JavaExec` example task.

The normal JVM run task starts `afield.Server`; passing an example class in
`--args` does not select another main class. For the other entry points use
`field_backdoor.py`'s `run-jvm-main` command or an IDE run configuration.
