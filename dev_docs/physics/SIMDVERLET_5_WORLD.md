# SimdVerlet: the world layer — colliders, tearing, wind

`SIMDVERLET_4_CONSTRAINTS.md` is the fabric. This is what the fabric is *in*.

Before this, the solver knew about particles and distance constraints and nothing else: a cloth
could sag under gravity and that was the entire vocabulary. It could not drape over anything, tear,
or flutter. The nearest thing to a collider anywhere in `afield.four.physics` was
`SimdVerletDyn4jBridge`, which nominates individual particles to become circle rigid bodies in a
separate 2D world and hard-requires `CollisionPlane.XY`.

Three pieces, and one new pipeline stage each for the first two.

| | file | registered in | runs |
| --- | --- | --- | --- |
| colliders | `SimdVerletColliders.kt` | `SimdVerlet.positionProjections` | after integration |
| tearing | `SimdVerletTearing.kt` | `SimdVerlet.constraintEdits` | after the colliders |
| wind | `SimdVerletWind.kt` | `SimdVerlet.bulkForces` | before the constraint solve |
| rope | `Strand.kt` | its own slots, plus `positionProjections` for bending | with everything else |

`step()` in full, now: queued removals, zero the accumulators, gravity, the per-particle force
generators, `bulkForces`, `accumulateForces`, `iterations` of the constraint solve, Verlet
integration, `positionProjections`, `constraintEdits`. Everything up to integration is force space;
the last two stages are the only places positions are written directly.

Two post-integration maps rather than one, so the order between them is in the type rather than in
insertion order. Colliders first because tearing has to measure the configuration the frame
actually ended in: an edge stretched over the shoulder of a sphere really is that stretched.

## Why a collider is a projection and not a force

This is the one design decision worth arguing, and the argument is arithmetic rather than taste.

`solveDirectedDist4InPlaceRange` wraps its whole body in `repeat(iterations) { … }` and inside it
reads `x/y/z` and only ever *adds* into `ax/ay/az`. Nothing writes a position between passes. So a
cloth spring of stiffness `k` acts at `k * iterations` — which is the finding
`SIMDVERLET_4_CONSTRAINTS.md` records — while a penalty force registered in `bulkForces` runs
exactly once. At the cloth starting place's `60 x 16` a penalty collider is therefore sixteen times
softer than the fabric it has to hold up.

The obvious answer, multiply it by sixteen, walks into the other jaw. Measured: a 12 x 12 sheet
dropped onto a sphere of radius 1.2, deepest penetration over 400 frames
(`SimdVerletCollidersTest.penaltyForceCollidersInheritTheIterationScaleTrap`):

| penalty `k` | iterations = 1 | iterations = 16 |
| --- | --- | --- |
| 400 | 0.2841 | 0.7103 |
| 1 600 | 0.1762 | 0.5239 |
| 6 400 | **0.0910** | 0.1550 |
| 25 600 | 0.3877 | 0.4277 |
| 102 400 | 0.5890 | 0.9037 |
| *projection* | **0.0000** | **0.0000** |

Both failures are there. The best stiffness at one iteration is 1.7× worse at sixteen with nothing
changed but a solver setting the collider knows nothing about. And stiffening past the optimum makes
it *worse*, six times over, because that is the explicit-integration limit (`k·dt²/m`) rather than a
tuning problem — there is no "just turn it up". Nothing in a 256× sweep of stiffness, at either
iteration count, comes within two orders of magnitude of the projection. The test asserts that ratio
rather than printing it.

A projection has no stiffness to get wrong. It costs one square root per particle per collider.

## Contact response

Given an outward unit normal `n` and a penetration depth `d > 0`:

1. the position moves to the surface, `x += n·d`;
2. the Verlet velocity `v = x - px` — a per-frame *displacement*, not a per-second velocity — is
   split into `v_n = (v·n)n` and `v_t = v - v_n`;
3. an *approaching* normal component is reflected and scaled by `restitution`; a separating one is
   left alone, so a projection can only remove energy along the normal;
4. the tangential part loses at most `friction · d` of its length, then a further
   `tangentialDamping` fraction of what is left;
5. `px` is rewritten to `x_new - v_new`.

**The velocity is read before the push-out**, and that is not a detail. Read after, the frame's
approach displacement has already been cut down by the correction before being reflected, and a
particle dropped three units with `restitution = 0.9` came back **0.46** instead of **2.38** (the
ideal is 0.81 × 3 = 2.43). Read before, `restitution = 1` preserves speed exactly, which is what it
has to mean.

The friction is Coulomb and *position-based*: bounding the tangential correction by `μ·d` is the
right bound rather than a convenient one, because for a particle held against a surface the
per-frame penetration `d` is exactly the displacement the normal force would have produced
(`a_n·dt²`), so `μ·d` is the Coulomb limit already in displacement units. It gives static friction
for free. A 10 x 10 sheet started slightly off-centre on top of a sphere, 600 frames:

| `friction` | centroid height |
| --- | --- |
| 0.0 | -32.240 (slid off, still falling) |
| 0.9 | 0.789 (still on the pole) |

### The stability band, measured

A projection may lose energy at a contact and must never gain any. That is the property that
replaces "tune the stiffness", and it is a test rather than a claim: a particle dropped three units
onto a plane, `damping = 1` so the contact is the only thing that can change the energy, 3000
frames.

| `restitution` | rebound peak | peak energy / initial | bounces |
| --- | --- | --- | --- |
| 0.00 | 0.000 | 1.000 | 1 |
| 0.25 | 0.172 | 1.000 | 3 |
| 0.50 | 0.720 | 1.000 | 5 |
| 0.75 | 1.644 | 1.000 | 10 |
| 0.95 | 2.653 | 1.000 | 34 |
| 1.00 | **2.943** | 1.002 | 42 |

The 1.002 is the discrete integrator's own half-step bookkeeping, not the contact. The ratio is
asserted under 1.02 at every setting, and the rebound asserted monotone.

Friction has an angle of repose, and it is the right one — which is the check that `μ·d` really is
the Coulomb limit in displacement units rather than a bound that merely looks like one. An 8 × 8
sheet on a 30-degree slope, 900 frames:

| `friction` | distance slid |
| --- | --- |
| 0.00 | 54.52 |
| 0.30 | 26.51 |
| 0.50 | 7.83 |
| 0.70 | 0.61 |
| 1.00 | 0.61 |

The crossing straddles `tan 30° = 0.577` exactly where it should.

### What it costs

`SimdVerletWorldMeasurements` (`./gradlew jvmMeasurements`; a `*Measurements` class so `check`
excludes it, and assertion-free on purpose — a wall-clock floor on a shared machine is a flake
generator). Steady-state cost of one `projectRange` over 40 000 particles:

| shape | ns / particle | | shape | ns / particle |
| --- | --- | --- | --- | --- |
| plane, vector | **0.77** | | capsule | 2.95 |
| plane, scalar | 1.42 | | box, inside | 2.21 |
| sphere, vector | **1.45** | | sdf, 1 step | 2.80 |
| sphere, scalar | 9.03 | | box, outside | 12.18 |

So the vector paths are worth 1.8× on the plane and 6.2× on the sphere. And the answer to "at what
collider count does this stop being cheap" is: at these sizes it does not. Thirty-two spheres over a
4096-particle cloth is a *predicted* 2.9 % of the step, and the measured "% over bare" column
wanders between −3.3 and +2.7 with no trend — the collider cost is **below the noise floor of the
constraint solve**, which is the honest reading and not the same thing as free. The wind and tear
passes on a 2304-particle sheet are inside the same noise; one sphere is +11 %.

### The shapes

`PlaneCollider` (half-space), `SphereCollider`, `CapsuleCollider`, `BoxCollider` (inside or out) and
`SdfCollider` (callback + finite-difference gradient). Plane and sphere carry a JDK Incubator Vector
path with a scalar remainder tail; the rest are scalar over a range, because the segment clamp, the
six-face least-penetration choice and an opaque callback are all branch-divergent per lane and a
masked version was not worth the second copy of the arithmetic.

`Collider.vectorized = false` exists so `vectorAndScalarPathsAgree` can put the two paths side by
side. That test asserts `Collider.laneCount > 1` and a non-multiple particle count *first*, so that
neither the vectors nor the tail can quietly go untested on a machine that has neither.

`SdfCollider.maxSteps > 1` re-evaluates after each correction, which is what makes a field whose
gradient is not unit length converge onto the surface: on `0.5·(r-1)` one step reaches 0.60 and
twelve reach 0.9998. A well-formed field needs one step.

`ColliderSet.relaxations` runs the whole list more than once for a particle in the crease between
two shapes; one pass leaves it slightly inside whichever came first.

## Tearing

`strain = (|x_i - x_j| - rest) / rest`, and `threshold` is a fraction of rest length. That is the
same quantity `SIMDVERLET_4_CONSTRAINTS.md` reports as "peak strain" — now measurable directly with
`SimdVerlet.peakStrain()`, which had been re-derived by hand every time it was wanted, alongside
`SimdVerlet.liveEdgeCount()`.

**Breaking is symmetric.** A `Cloth` wires every grid edge twice, `i → j` and `j → i`, because the
solver is directed and only writes to the particle that owns the slot. Retiring one direction leaves
a one-way spring: an object that accelerates its own centre of mass, and one `debugLines` goes on
drawing as a live edge because it deduplicates only genuinely symmetric pairs.

**`Cloth.enableTearing` does one more thing, and it is the interesting one.** The curvature force is
written from the *grid*, not from the constraint set, so retiring an edge leaves the flattening
force still pulling the two lips of the rip toward each other with nothing behind it — the sheet
heals in the picture while the solver says it is torn. The tear callback calls
`Cloth.suppressCurvatureAt` on both endpoints, which removes exactly the two stencils that spanned
the broken edge.

### Two things the tests found

**The tear count is a fact about the geometry, not about the threshold.** A uniform sheet uniformly
pinned carries a uniform load, so its fourteen attachment edges are at the same strain and fail in
the same frame at every threshold from 4 % to 25 %. The first sweep read 14, 14, 14, 14, 14. What
the threshold sets is the load at failure:

| `threshold` | failure load | load / threshold | edges torn |
| --- | --- | --- | --- |
| 0.04 | 3.4 | 86 | 14 |
| 0.06 | 4.5 | 75 | 14 |
| 0.09 | 5.5 | 62 | 14 |
| 0.14 | 7.6 | 55 | 14 |
| 0.25 | 14.8 | 59 | 14 |

(14 x 9 sheet, 226 edges, stiffness 60 × 16, settled at `g = -2` then ramped to -120 over 900
frames.) Strain is proportional to load for a Hookean fabric, so `load / threshold` is roughly
constant down the column — asserted, at a 1.8× band. The threshold is a material property, and the
load it survives follows from the stiffness rather than being something a tuner has to discover.

**`maxBreaksPerStep` is a different rip, not a slower one.** A sheet failing one edge per frame goes
on carrying load while it fails, so more of it reaches the threshold: 14 edges unthrottled against
22 at one per step, reproducibly. The KDoc claimed the opposite until the test measured it.

Determinism is the property everything else rests on. `tearsTheSameWayTwice` replays three rate
settings twice each and compares the surviving constraint set *slot for slot*, not just the count.
With a rate limit the candidates are ranked by strain descending and ties broken by
`(particle, slot)` ascending — never by hash or float-equality luck.

## Rope

`Strand` is the 1-D sibling of `Cloth`: `sim.addStrand(count, spacing, stiffness)` appends a chain
and hands back a handle that speaks node indices.

A chain node spends slot 0 on its successor and slot 1 on its predecessor, which leaves **two free
slots on every node of every rope** — and that is the design rather than an accident.
`Strand.attachTo` spends one on each side, so a rope can be tied to a cloth corner, to another rope,
or to any particle with a spare slot, and the join is an ordinary symmetric distance constraint that
the solver, the colliders and `Tearing` all already understand. A knot is an edge: ramp the load and
it is among the things that fail, with nothing written to make it a candidate. A cloth's *interior*
node has all four slots spent, and `attachTo` says so with the particle index rather than failing
quietly.

### How long a rope this solver can hold

A chain is far more demanding than a sheet, for a structural reason rather than a tuning one: in a
cloth the load spreads across a pinned row, in a rope every segment carries everything below it. So
the top segment's strain is `(n−1)·g / (k·iterations·spacing)`, and that model is asserted at a
0.7×–1.4× band:

| nodes | `stiffness × iters` | peak strain | predicted |
| --- | --- | --- | --- |
| 16 | 1600 | 0.712 | 0.766 |
| 32 | 1600 | 1.458 | 1.582 |
| 64 | 1600 | 3.329 | 3.216 |
| 64 | 2400 | 2.135 | 2.144 |
| 64 | 3600 | **22.757** | 1.429 |

The last row is `150 × 24` and does not settle: the setting that would make a 64-node rope
inextensible is past the stability ceiling. Past a few dozen nodes at `dt = 1/60` a hanging rope is
elastic and no stiffness fixes it. A length, not a setting.

### Bending, wrong twice before it was right

`bendStiffness` was a *force* first, like `Cloth.curvatureStrength`. Tip droop on a clamped
cantilever against bend strength:

| bend | 0 | 300 | 1200 | 2000 | 2600 | 3000 | 3400 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 6 nodes | 0.602 | 0.602 | 0.602 | 0.602 | 0.602 | 0.602 | 0.601 |
| 8 nodes | 0.904 | 0.904 | 0.904 | 0.903 | 0.903 | *flails* | NaN |
| 12 nodes | 1.511 | 1.511 | 1.509 | 1.508 | *flails* | NaN | NaN |

Three significant figures of nothing up to 2000, then the explicit integrator loses it — the
*flails* entries hold the tip up at 112 and 124 degrees of joint angle, which is thrashing rather
than stiffness. **The same trap as a penalty collider, arriving by a second road**, and fixed the
same way: a mass-weighted position projection, `x` and `px` moved together so no velocity is
injected, swept in three colours so overlapping stencils do not stack their corrections.

Then the second negative result: **a cantilever is the wrong test for bending**. A rope hanging
under gravity is already straight, so a curvature constraint has nothing to correct, and even the
projection moves a 20-node clamped tip by 0.5 %. What bending controls is how tightly a rope can
*fold*, which needs a rope in compression — 50 nodes heaped on a floor between two anchors:

| `bendStiffness` | mean joint angle | sharpest joint |
| --- | --- | --- |
| 0.00 | 38.75° | 174.19° |
| 0.20 | 16.99° | 145.74° |
| 0.50 | 13.91° | 110.24° |
| 1.00 | 8.26° | 90.74° |

`bendStiffness` is `0..1` and 1 maps onto 0.6 of a correction, because a full one oscillates against
the distance constraints — an uncapped 1.0 sent a 20-node cantilever's tip to (−17539, −94947). A
documented range with a cliff in it is worse than a smaller range without one.

## Wind

Two models, and they are not alternatives.

`sim.addWind(...)` is per-particle drag, `F = drag·(w - v)`. It works on anything — a rope, a point
cloud, a cloth — and it is entirely wrong for a sheet, which has a number: two identical 12 x 12
sheets at rest in the same wind, one face-on and one edge-on, get the **same 345.6** of summed force
out of it, to the last bit, because a particle has no orientation to have.

`cloth.addWind(wind)` is the per-quad model, using the sheet's own topology — which is free, since a
`Cloth` is a contiguous row-major grid and the quad at `(col, row)` is four adds. Normal from the
*diagonals* (the average of the two triangulations, so a creased quad's normal does not depend on
which way the code happened to split it), area from half that normal's length, and

    F = 0.5 · ρ · Cd · A · (n·v_rel) · |n·v_rel| · n

quartered onto the corners, plus an optional skin-friction term. Those same two sheets get 209
face-on and 8.2 edge-on — and all 8.2 of that is the skin friction: switch
`tangentialCoefficient` off and the edge-on sheet gets exactly zero.

That single `(n·v_rel)` factor is the flutter: a quad turning edge-on stops being pushed, overshoots,
catches the wind on its other face, and the wave runs down the sheet. Measured as sustained
trailing-edge travel per frame on a 20 x 12 flag in an 8-unit wind with 60 % gusts, damping 0.98,
after the transient:

| `Wind.drag` | drag only | drag + per-quad | ratio |
| --- | --- | --- | --- |
| 0.05 | 0.00160 | 0.00507 | **3.16×** |
| 0.15 | 0.00432 | 0.00937 | 2.17× |
| 0.50 | 0.01790 | 0.02231 | 1.25× |
| *still air* | 0.00013 | | |

The caveat is asserted rather than mentioned: the advantage *falls away* as `drag` rises, because
past a point the drag pass is doing the forcing and the sheet is being carried rather than flying.
The test requires that the ratio decrease monotonically down that column and end under 1.5×, so the
honest limit of the model is part of the contract. Low drag plus the per-quad pass is a flag; high
drag is a sheet in treacle that happens to be moving.

### Gusts

A small closed-form spectrum — a few sines whose directions, spatial frequencies, temporal
frequencies and phases come from a seed. Deliberately **not** a noise lattice: a lattice can align
its cells with the cloth's grid spacing and put a standing pattern into the sheet, which is a bug
that looks like a feature. What it has to be is exactly reproducible, and it is: a whole 400-frame
simulation at seed 5 is bit-identical to itself and differs from seed 6 in 262 of 288 coordinates.

`Wind.windLines(...)` draws the field as whiskers, because a fluttering sheet with no visible air is
a sheet doing something inexplicable.

## Pictures, and two bugs only pictures found

`SimdVerletWorldCaptureTest` renders three filmstrip PNGs — drape, tear, flag — headless through
Skia's raster surface and `outputTo`, the `ChartGallery` path: CPU only, no Window, no Renderer, no
GPU. The geometry is exactly what a live box gets from `sim.debugLines()` and `ColliderSet.outline()`
through `Box.showPlane`. They go to a temp directory by default and to a chosen one with
`-Dafield.physics.captureDir=…`.

What is asserted is what a picture can be wrong about in a way a number catches: that the PNG is not
an empty frame, that each panel has ink but is not a solid block, and — the one that matters — that
the first and last panels differ on a floored fraction of pixels. A harness whose scene had quietly
stopped simulating would otherwise produce five beautiful identical panels and pass.

### What getting it wrong looks like

A second strip, `what-goes-wrong.png`, is six panels: three failure modes, each beside the same
scene done right, with a red cross or a green tick in the corner so it reads without captions. This
is the capture the debug view's own argument demands — if "a mis-wired constraint looks obviously
wrong here and like nothing at all in a shaded mesh" is true of the solver, it has to be true of the
world on top of it, and the way to find out is to build the wrong versions on purpose.

1. **A penalty collider** at `k = 400` against the projection. Left: an empty amber circle with the
   sheet flat on the floor beneath it. Right: the sheet tented over the sphere.
2. **Tunnelling**, one parameter apart: the same fast drop onto the same 0.06-thick ledge with
   `margin = 0` and `margin = 0.25`. Left, part of the grid is three units past the ledge; right,
   all of it resting on it. Free particles rather than a cloth, deliberately — at any interesting
   speed a cloth's own springs would be the thing that failed first.
3. **Tearing without dropping the curvature stencil.** Both panels break the same eight edges.
   Without `Cloth.enableTearing`'s hook, **24 cloth nodes are still inside the hole the ball swept**
   and the picture shows a cross of mesh filling the amber circle; with it, **0**, and a clean open
   hole. Every one of those numbers is asserted.

Two bugs no test would have caught:

- `PlaneCollider.outline` drew a single tangent chosen by a cross product, and for a floor at
  `y = -2.4` that came out along `z` — one pixel wide in the XY view a draping cloth is watched in.
  The floor was invisible in exactly the picture it existed for. It draws a cross of two
  perpendicular in-plane directions now; two perpendicular tangents cannot both vanish in an
  axis-aligned projection.
- `Wind.windLines` sampled a grid in the XY plane only, so in the view from above — the only view a
  flag flapping in `z` is visible in — every row collapsed onto the same point and the field drew as
  a row of fans. It takes two 3D corners now.

And one scene design lesson, recorded in the test that failed on it: a hanging sheet with a heavy
corner under a ramped load is not a tearing *picture*. A uniformly pinned sheet fails uniformly, the
whole attachment row goes in a few frames, and four of five panels are an empty field with the pins
still drawn across the top. A concentrated, *moving* load — a sphere pushed through a sheet pinned
all round — is what makes a crack start somewhere and go somewhere.

## Runnable entry points

- **`dev_docs/agents/demos/simdverlet_world/`** — the openable document. Four boxes: one per
  capability, plus a fourth where all five subsystems run in one simulation with a rope tied into
  the sheet and a named knob whose measured table is itself a test. Each is an explicit `loop { }`
  through `showPlane`. Open `metadata.edn` in Field, or replay `build.yaml`; see that folder's
  `README.md`.
- `SimdVerletCollidersTest` (16 cases), `SimdVerletTearingTest` (11), `SimdVerletWindTest` (9),
  `StrandTest` (12), `SimdVerletWorldCaptureTest` (4) in `src/jvmTest/kotlin/afield/four/physics/`
  — 74 in the package, all green.
- `SimdVerletWorldMeasurements` via `./gradlew jvmMeasurements` — the timing tables above.
- `cloth_simdverlet_start` (transcript corpus) — the cloth this adds a world to.

## Known gaps

- **No self-collision.** A sheet can pass through itself, and does in a hard drape. It needs a
  spatial hash, which is also what collider counts beyond a handful need.
- **No swept test.** Every collider sees only where a particle *ended up*, so a fast particle can
  pass through a thin obstacle in one frame. `margin` is the mitigation.
- **`BoxCollider` inside-keeping counts a corner as one contact** even when it resolves against two
  or three walls, so `contacts` is a particle count rather than a contact count there.
- **`ColliderSet` is serial.** Each collider is range-parallel safe on its own; the set is not, and
  nothing here uses `ForceExecutor` yet.
- **A `Strand` in a wind that never stops eventually stretches.** Not a bug in the rope: a torn or
  free piece under a steady force keeps accelerating until something stops it, and a room
  (`BoxCollider(inside = true)`) is what stops it. The demo's fourth box says so in its comments.
- **The per-quad aero pass is invisible in a front view**, because it acts along the sheet's normal
  and a sheet in the x/y plane has its normal out of the screen. That is a property of the plane
  figure, not of the model, and the fix is to pick the other projection — but it does mean a scene
  mixing an XY view with per-quad wind will look like it is doing nothing until the sheet swings.
- **Nothing here is parallel.** `Collider` is range-safe on its own and `ColliderSet` is serial;
  `ForceExecutor` is unused by all of it.
