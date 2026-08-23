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

- **`dev_docs/agents/demos/simdverlet_world/`** — the openable document. Three boxes, one per
  capability, each an explicit `loop { }` through `showPlane`. Open `metadata.edn` in Field, or
  replay `build.yaml`; see that folder's `README.md`.
- `SimdVerletCollidersTest` (14 cases), `SimdVerletTearingTest` (10), `SimdVerletWindTest` (9),
  `SimdVerletWorldCaptureTest` (3) in `src/jvmTest/kotlin/afield/four/physics/`.
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
- **No `Rope`/`Strand`.** The obvious sibling to `Cloth`, and the obvious next thing.
