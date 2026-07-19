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

`afield.examples.ClothSimulationExample` demonstrates the layout. Its optional
curvature generator moves interior particles toward the mean of their four axis
neighbors; `enableCurvatureForce` is **true by default**. Its interactive wrapper
uses PID position controllers for grabbing rather than teleporting particles.

Runnable entry points:

- `afield.examples.TestDirectedConstraints4`: minimal four-slot smoke.
- `afield.examples.ClothSimulationExample`: cloth and interaction example.
- `afield.examples.SimdVerletTinyExample`: earlier three-slot example.
- `./gradlew runSimdVerletBulkKernelsExample`: the repository's dedicated
  `JavaExec` example task.

The normal JVM run task starts `afield.Server`; passing an example class in
`--args` does not select another main class. For the other entry points use
`field_backdoor.py`'s `run-jvm-main` command or an IDE run configuration.
