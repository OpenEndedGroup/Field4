# Neural particle systems

```diffstatic_runner {data-lang=kotlin screenshot="assets/neural_particles/word.png" capture="native-window" wait="50" native-wait-frames="30" embed=true hidden=true timeout="900" duration="0" name="neural-particles-word" alt="The word FIELD drawn in warm gold particles on black, the letter outlines bright and their interiors a dimmer haze" macro-file="../../dev_docs/agents/transcripts/neural_particles.yaml" capture-box="np_renderer"}
// Built and launched from dev_docs/agents/transcripts/neural_particles.yaml
// (renderer, an offline training box, and the live system).
```

Three hundred thousand particles, each one asking a small neural network where to go next, every
frame. Nobody wrote the rule that assembles them into that word — it was fitted, offline, in about
a second of Kotlin, and then compiled into a shader.

A particle system is normally something you *write*: a few lines of arithmetic, run over a million
points. That is a good deal when you know the arithmetic. It stops being one when what you have is
not a rule but a **wish** — a shape you want the cloud to become, a motion you want it to have, a
behaviour you can recognise but not write down. Tuning coefficients until a picture looks right is
the usual answer, and it is slow, unrepeatable, and gets harder with every parameter you add.

Fitting the rule instead is worth doing when:

- **You can state the destination but not the route.** "Become these letters" is easy to specify
  and hard to hand-write as a velocity field. Training takes a second and the answer runs live.
- **You have a rule that is expensive, and want a cheap one that behaves like it.** A learned rule
  costs the same every frame no matter how intricate the thing it imitates.
- **The target changes and you do not want to re-derive anything.** Retraining is a couple of
  seconds and swaps a buffer; the shader is never recompiled. In the first scene below the target
  is a string you can edit while it runs.

The catch, and this page is honest about it throughout: a trained rule that *looks* convincing is
very cheap to obtain, and almost worthless as evidence. Almost any attraction-and-repulsion rule
with a speed limit produces something an audience will accept as flocking. So everything here is
graded against a rule we already have, and the numbers are in
`dev_docs/NEURAL_PARTICLES_MEASUREMENTS.md`.

The machinery in between — taking a network trained in Kotlin and turning it into a GLSL forward
pass that runs per-particle — is reusable on its own, and is the first thing below.

## Particle rules that were trained

There are two kinds of rule a particle can follow. The first reads only its own state — a velocity
field `v(x, t)`, blind to every other particle in the buffer. The second reads its neighbours — a
force that depends on the company it keeps.

Field fits either one, and the difference matters more than it sounds. A velocity field is a
**map**: every particle is travelling somewhere specific and will arrive, which is what makes
shapes and words reachable. An interaction is a **force**, which is what makes flocks, swarms and
crowds — and which needs a neighbour search underneath it before any of it is affordable.

The two are built in that order below: tier one is sections 1 and 2, the neighbour search is
section 3, and tier two is sections 4 to 6.

## Source files

| file | what is in it |
|---|---|
| `afield/stdlib/neuralparticles.kt` | `MlpWeights`, `MlpWeights.glsl`, `FlowMatchExport`, `MlpAgreement` — the network-to-shader bridge |
| `afield/stdlib/neuralparticles_system.kt` | `ShaderNeuralParticles`, `NeuralParticleSettings` — tier one, as a running system |
| `afield/stdlib/flowmatching.kt` | `flowMatch`, `contourComponents`, `mixtureSource` — the CPU half, shared with [Flow matching](flow_matching.md) |
| `afield/stdlib/deepsets.kt` | `trainDeepSets`, `trainEquivariant`, and their GLSL emitters — tier two's learned rule |
| `afield/four/util/GpuNeighbourGrid.kt` | hash, sort, ranges: the neighbour search both flocks share |
| `afield/four/physics/ShaderBoids.kt` | `ShaderBoids`, `BoidSettings` — the written rule and the learned one, in one system |

Two transcripts open as complete documents from [Agent transcripts](agent_transcripts.md) or the
welcome screen: **`neural_particles`** and **`learned_boids`**. Everything below is reachable from
one of them, and both are the fastest way in — open one, edit a number, watch it change.

---

## 1. A trained field, running as a shader

[Flow matching](flow_matching.md) fits `v(x, t)` by least squares in about a second of pure Kotlin.
Three calls take that network to the GPU:

```diffstatic {data-lang=kotlin}
val field = flowMatch(pairing.to, pairing.from, FlowMatchOptions(coupling = FlowCoupling.PAIRED))
val export = field.export()                  // weights, training frame, source cloud
val glsl = export.glsl("neuralField")        // place this immediately after #version
```

`export()` is a snapshot: the weights flat and layer-major, plus the centring and scaling the
network was trained in, plus the source cloud so the system knows where particles start.
`glsl(name)` emits an unrolled forward pass that reads those weights out of a storage buffer rather
than baking them in as literals — so **retraining swaps a buffer between frames and never
recompiles a shader**. The layer loop is unrolled and the dot products are not, so the emitted
source stays proportional to the *depth* of the network: a five-thousand-parameter net is still
readable GLSL.

`ShaderNeuralParticles` is the system that runs it:

```diffstatic {data-lang=kotlin}
val particles = ShaderNeuralParticles(export) {
    particleCount = 300_000
    stepsPerCycle = 150
    dwellCycles = 3f        // how long a particle rests on the target once it arrives
}
particles.installInto()     // defaults to renderer.renderList
```

Each particle is a struct rather than a texel, which is worth knowing because it explains the two
unusual fields:

```diffstatic {data-lang=glsl}
struct Particle { vec2 position; vec2 start; vec2 velocity; float t; float pad; };
```

A flow is a *map*, not a field of forces. A particle is not wandering; it is travelling from one
specific source point to wherever the field takes it by `t = 1`, and when it arrives it goes back
and does it again. `start` and `t` are what make that possible, and neither fits in a four-channel
texel.

One forward pass per particle per frame. The default flow-matching network is `11 -> 64 -> 64 -> 2`,
about five thousand multiply-adds, so two hundred thousand particles is roughly a GFLOP a frame.
**Width is the cheaper dial than count** — a `32 x 32` network is four times less work, and a 2D
flow rarely needs 64.

### The setting that decides whether you can see anything

`dwellCycles` is not a cosmetic control and it is the first thing to reach for if the picture is a
haze. A particle that resets the instant it reaches `t = 1` is on the target for exactly one frame
out of `stepsPerCycle`, so with phases spread out only a hundred and fiftieth of the cloud is ever
on the shape and the rest — all still in transit — drown it. At `0f` a correctly trained
300,000-particle system renders a featureless smudge that looks exactly like an undertrained field.
At the default of `3f` a particle spends three quarters of its life arrived.

Set it to `0f` to see the flow alone, or very high to see only the distribution.

---

## 2. Making your own target

The image at the top of this page is the word FIELD. Getting a *word* rather than a single letter
needs one piece of work that has nothing to do with the GPU, and it is the piece you will need for
any target that comes in more than one part.

Integrating a velocity field is a continuous invertible map. It can deform a blob but it cannot
tear one into five, so **a standard Gaussian source cannot become the five disjoint outlines of a
word** — not with more training and not with a bigger network. `mixtureSource` fixes it by giving
the source the target's *topology*: one ellipse per closed subpath, fitted to that subpath's own
mean and covariance, in point-for-point correspondence with the contour it has to become.

```diffstatic {data-lang=kotlin}
val contours = layoutText("FIELD", typeface("Helvetica", 260.0)).toFLines()
val pairing = mixtureSource(contourComponents(contours, total = 12000), seed = 5)
val field = flowMatch(pairing.to, pairing.from, FlowMatchOptions(coupling = FlowCoupling.PAIRED))
```

`contourComponents` splits the outlines into closed subpaths and samples each in proportion to its
length; `mixtureSource` fits an ellipse to each and pairs it, point for point, with its contour.
`FlowCoupling.PAIRED` is what tells `flowMatch` to respect that pairing instead of drawing its own.

Here is that construction on its own, on the CPU, with no GPU involved — the source it builds
against the target it has to reach:

```diffstatic_runner {data-lang=kotlin screenshot="assets/neural_particles/mixture.png" wait="3.0" capture="box" open-outputs="true" w="1000" h="470" padding="16"}
:::static
val contours = layoutText("Fi", typeface("Helvetica", 300.0)).toFLines()
val components = contourComponents(contours, total = 2400, seed = 7)
val pairing = mixtureSource(components, seed = 5)

fun cloud(points: List<Vec3>, size: Double, c: Vec4): FLine {
    val f = FLine()
    points.forEach { f.moveTo(it) }
    f.pointed = true; f.pointSize = size; f.color = c
    return f
}

// Every 100th correspondence, to show the pairing is point-for-point and not a cloud match.
val threads = FLine()
for (i in pairing.from.indices step 100) {
    threads.moveTo(pairing.from[i]); threads.lineTo(pairing.to[i])
}
threads.color = vec(1.0, 0.62, 0.25, 0.75); threads.thickness = 1.2
:::fold draw the source, the target and the pairing
lines(
    listOf(
        cloud(pairing.to, 2.0, vec(0.55, 0.55, 0.62, 0.9)),
        cloud(pairing.from, 1.6, vec(0.44, 0.83, 0.78, 1.0)),
        threads,
    ),
    w = 1000.0, h = 450.0, fitToSize = true,
)
markdown("what", """
    ~~~
closed subpaths found   ${components.size}
points per component    ${components.map { it.size }.joinToString(", ")}
paired points           ${pairing.from.size}
    ~~~
""")
:::endfold
```

Grey is the target — the letter outlines. Teal is the source the flow starts from: one ellipse per
closed subpath, so the "i" arrives as *two* components, its stem and its dot, each with its own
ellipse. A letter with a counter — a "D", an "e" — would contribute two as well, outer and inner.
Orange threads are the correspondence, every hundredth pair. The flow's whole job is to shorten
those threads.

Three choices inside that construction each earn their default:

| what | if you turn it off | why |
|---|---|---|
| subpaths, not glyphs | `contourComponents` given whole letters | a "D" becomes one annulus, and its counter has to be torn open |
| `alignPhase = true` | identity pairing | a contour's arc-length origin has nothing to do with angle zero on its ellipse, so every component carries an arbitrary rotation the flow must unwind |
| no jitter on the source | `jitter` above zero | the ellipse thickens to an annulus and the correspondence blurs |

`alignPhase` is the least obvious and the most load-bearing of the three. The transcript's
`neural_particles` scene has a deliberate failure to try: set it to `false`, retrain, and watch the
letters arrive twisted.

Almost every setting in that scene is live, including the text. Retraining takes a couple of
seconds.

---

## 3. The neighbour grid

Tier two needs a sum over *nearby* particles, which needs a sort. `GpuNeighbourGrid` is that, and
it is useful on its own for anything that needs neighbours on the GPU:

```diffstatic {data-lang=kotlin}
val grid = GpuNeighbourGrid(
    capacity = 12_000,
    cellsPerSide = 32,              // cellSize = domainSize / cellsPerSide = 0.0625
    domainMin = Vector2f(-1f, -1f),
    domainSize = 2f,
)

// Positions are read as a flat float array: particle i's x is at i * strideFloats + offsetFloats,
// so the grid never needs to know what else is in your struct.
grid.install(renderer.renderList, myParticles, count, strideFloats = 8, offsetFloats = 0)
```

`ShaderBoids` calls `install` for you; you need it only when putting the grid under something of
your own.

Four steps, each avoiding something a compute shader cannot cheaply do:

1. **hash** — one invocation per particle writes `key = cell index`, `value = particle index`.
2. **sort** — `GpuRadixSort` sorts the pairs, so a cell's members become a contiguous run. This is
   the step that turns a scatter problem into a gather problem.
3. **ranges** — one invocation per sorted entry compares its key with its neighbours' and writes
   each run's bounds. No atomics: an entry can tell whether it begins a run by looking left.
4. **read** — a consumer walks the 3x3 block of cells around its own.

Cells are row-major rather than Morton-ordered. A hierarchy needs a node's *children* contiguous; a
flat grid needs a *cell's members* contiguous, and row-major does that while making the nine
indices of a 3x3 neighbourhood plain arithmetic instead of a bit interleave.

!!! warning "Cell size is the interaction radius"

    The 3x3 block covers one cell width, so `cellSize` — `domainSize / cellsPerSide` — is the
    largest radius the query answers correctly. Make the cells smaller than your interaction radius
    and the query silently misses neighbours: the shader still runs, the flock still flocks, and it
    is wrong. Nothing tells you. Size the grid from the radius, not from the particle count.

Particles outside the domain are clamped into the edge cells rather than dropped, because a lost
particle is a particle that silently stops interacting.

The whole thing installs as **one** entry rather than four, because `ComputeTarget.render` runs every
module before any other consumer, so four separate installs would interleave. If you build
something like it, that ordering is the trap.

---

## 4. Boids on the grid

`ShaderBoids` is the reference consumer — classical Reynolds, three terms, on top of that grid:

```diffstatic {data-lang=kotlin}
val written = ShaderBoids(grid, count = 12_000) {
    radius = 2f / 64f
    separation = 0.02f; separationRadiusFraction = 0.25f
    alignment = 3.0f; cohesion = 200f
    maxSpeed = 0.25f
    containment = 1.5f; containmentRadius = 0.44f   // a soft wall, applied after the rule
    driverStrength = -0.9f                          // negative: flee the driver
}
written.installDrawInto()                           // defaults to renderer.renderList
```

!!! warning "The textbook weights do not flock, and the defaults are the textbook weights"

    `0.02 / 1.2 / 0.8` is what every boids tutorial gives, and at this density it produces an
    evenly spaced gas rather than a flock. The weights are **not scale-free**: separation *sums*
    over neighbours while alignment and cohesion take *means*, so their relative strength depends
    on the density and the interaction radius. Measured in this scene — 57 neighbours a boid,
    separation summing terms of about 137, cohesion meaning an offset of about 0.003 — the textbook
    numbers put separation 27x above alignment and 4600x above cohesion.

    So expect to retune all three whenever you change `radius` or the particle count, and tune them
    by bringing the three *terms* to the same order, not by adjusting the weights until it looks
    right.

`separationRadiusFraction` is the other one worth knowing. One radius for all three terms cannot
clump: separation wants to be short-range while alignment and cohesion want the whole
neighbourhood. Shrinking separation's reach to a quarter is what turns one even disc into groups.

It exists to be the thing the learned rule is graded against, but it stands alone perfectly well as
a fast flocking system. The force it computes is also the oracle that generates tier two's training
data, which is why this is a *written* rule and not a recorded one: learning from recorded motion
would leave nothing exact to compare against.

---

## 5. A learned interaction

A velocity field is blind to the other particles. An interaction is the other kind of rule, and
Deep Sets is the right family for it:

```diffstatic {data-lang=text}
a_i = phi( v_i, SUM_j psi(x_j - x_i, v_j - v_i), MEAN_j psi(...) )
```

Two small networks and a reduction. The aggregation is permutation-invariant and takes any number
of neighbours, so one set of weights serves a particle with three neighbours and a particle with
three hundred. Classical Reynolds is one particular choice of `psi` and `phi`, so the family
provably contains something worth finding.

**Both the sum and the mean are fed to `phi`, and one reduction cannot do.** Separation is a *sum* —
being crowded by twenty neighbours pushes harder than by two — while alignment and cohesion are
*means*. That branch is worth a factor of four.

Training is one call. You supply a batch of `InteractionExample` — a velocity, a list of
neighbours, and the acceleration the rule you are imitating produced — and because you *have* the
rule, no simulation is needed to generate them:

```diffstatic {data-lang=kotlin}
fun sample(): InteractionExample {
    val v = /* a random velocity */
    val neighbours = /* up to 70 of them, as (dx, dy, dvx, dvy) */
    return InteractionExample(v, neighbours, reynoldsForce(v, neighbours))
}

val learnedRule = trainEquivariant(
    radius = radius, velocityScale = velocityScale,
    accelScale = 20f, sumScale = 8f, minSeparation = minSeparation,
    options = DeepSetsOptions(steps = 4000, batch = 16, seed = 5,
                              psiHidden = intArrayOf(8, 8), messageWidth = 4),
) { List(16) { sample() } }

val learned = ShaderBoids(grid, count = 12_000, equivariant = learnedRule) { /* same settings */ }
```

`learned_boids` has this written out in full, with the Reynolds force inline.

!!! tip "Train on the density you will run at"

    Draw up to *seventy* neighbours, not a handful. A flock runs at around fifty-seven neighbours
    per boid once cohesion has gathered it, and a model trained only on sparse neighbourhoods is
    extrapolating in deployment — which bites hard here, because cohesion is weighted against the
    *mean* offset, so a small error in the meaned branch becomes a large force.

`trainDeepSets` and `trainEquivariant` fit by reverse-mode autodiff over `ADTape`, because Smile's
MLP cannot express a gradient flowing back through a sum over a variable-length set with shared
weights. Both halves of either form come out as ordinary `MlpWeights`, so the GLSL emitter from
section 1 applies to them unchanged.

**Prefer `trainEquivariant`.** The true rule satisfies `a(R x) = R a(x)` identically, and nothing in
the plain Deep Sets form makes it do so — a network with scalar weights on Cartesian components has
a preferred direction baked in by its initialisation. The symptom is a flock that drifts steadily in
one direction, and the residual *grows with crowding* because it rides on the aggregated message.
`EquivariantWeights` removes it by construction: `psi` sees only invariants of a neighbour and emits
scalars, the vectors come from the neighbour's own direction and velocity difference, and `phi` sees
only the lengths of the aggregates. Rotate every input and nothing any network sees changes.
Constraining it this way also *improved* the fit, which is the usual result when a symmetry is real.

`ShaderBoids` runs either rule, branching only where the force is computed — same buffer, same
neighbour search, same integration, same speed limit — so the two flocks are an A/B of the rule and
nothing else:

```diffstatic_runner {data-lang=kotlin screenshot="assets/neural_particles/flocks.png" capture="native-window" wait="180" native-wait-frames="30" embed=true hidden=true timeout="900" duration="0" name="neural-particles-flocks" alt="Two flocks of points on black, each a sweeping arc: on the left a blue-grey flock, on the right a warm-toned one, both curled around the wandering point they are fleeing" macro-file="../../dev_docs/agents/transcripts/learned_boids.yaml" capture-box="lb_renderer"}
// Built and launched from dev_docs/agents/transcripts/learned_boids.yaml
// (renderer, a training box, and the two flocks).
```

Left is the written rule, right the learned one, twelve thousand boids each, seeded from the same
random draw into their own halves of the domain, both fleeing a point that wanders on the same
smooth random walk about each flock's own centre.

The difference that remains is the one that should: a relative force error of about a quarter is a
flock that behaves *like* boids, not a flock that *is* boids, and over ten thousand chaotic steps
the two are never the same picture.

!!! note "Editing the written rule does not move the learned flock"

    It learned the rule as it was when you trained it. Re-run the training box to catch it up —
    which takes a few seconds, and is worth doing deliberately at least once to see how far the two
    can be pulled apart.

### Cost

Cost is per *neighbour*, not per particle, and the learned rule pays a `psi` forward pass for each
one. Once cohesion has gathered the flock, a boid can have a hundred or more neighbours inside its
radius. At 12,000 boids a flock:

| | frames per second |
|---|---|
| the written rule alone | 116 |
| a `16x16` `psi`, message width 6 | 17.6 |
| an `8x8` `psi`, message width 4 | 71.2 |
| the equivariant `8x8`, both flocks, driven | **77** |

The first row is the control that identifies the bottleneck: the written flock runs at the same
speed on the identical grid whatever `psi` is doing, so there is no point tuning cells or particle
counts. **Depth is the part to keep** — a single hidden layer of 16 costs *more* than two of 8 and
fits worse than either.

---

## 6. The driver

With the symmetry built in and no external forcing, the flock settles into something that reads as
static: an evenly spaced disc that barely changes. This is worth knowing before you conclude
something is broken — the drift that the plain Deep Sets form produced had been supplying the
apparent motion all along, and removing it revealed what the rule on its own actually does here.

So the motion comes from outside the interaction:

```diffstatic {data-lang=kotlin}
boids.settings.driverStrength = -1.5f     // negative flees, positive pursues
boids.settings.driverRadius = 0.25f
boids.driver = { wanderingPoint() }       // read once a frame
```

`driverStrength` adds a pull toward, or a push away from, a point you supply each frame. It is
applied after the rule exactly like `containment`, defaults to zero, and **the network knows nothing
about it** — which keeps it forcing rather than a modelling error, and keeps an A/B of two rules
honest. No retraining is needed to add or change it.

---

## Settings

`NeuralParticleSettings` — tier one:

| | default | |
|---|---|---|
| `particleCount` | 200,000 | a few hundred thousand is comfortable |
| `stepsPerCycle` | 150 | integrator steps *and* animation speed at once; below ~60 the target visibly loses its corners |
| `dwellCycles` | 3 | how long a particle rests on the target, as a multiple of travel time — see section 1 |
| `phaseSpread` | 1 | 1 is a standing stream with no dead moment; 0 is a single object morphing |
| `arrivalBias` | 0.8 | biases colour and intensity toward arrived particles |
| `sourceJitter` | 0.02 | spread around each source point |
| `randomSeed` | 1 | which source points particles are assigned, and their phases |
| `viewScale`, `viewCenter` | 2.6, origin | framing |
| `flipY` | true | glyph contours come in y-down |
| `pointSize`, `sourceColor`, `targetColor`, `intensity` | | appearance |

`BoidSettings` — tier two. `radius` must not exceed the grid's `cellSize`:

| | default | |
|---|---|---|
| `radius` | 0.06 | interaction radius; **bounded by the grid's cell size** |
| `separation`, `alignment`, `cohesion` | 0.02, 1.2, 0.8 | the three Reynolds weights — the textbook values, which **do not flock** at a realistic density; see section 4 |
| `separationRadiusFraction` | 1 | shrink separation's reach relative to `radius` for smaller clumps |
| `minSeparationFraction` | 0.05 | floor on the separation denominator, so a coincident pair is finite |
| `maxSpeed` | 0.35 | speed limit |
| `dt` | 1/60 | fixed step |
| `containment`, `containmentRadius`, `containmentCenter` | 0, 0.8, origin | a soft wall, applied after the rule |
| `driverStrength`, `driverRadius` | 0, 0.25 | see section 6; negative flees |
| `pointSize`, `slowColor`, `fastColor`, `intensity` | | appearance; colour tracks speed |

---

## Running it

The two transcripts are the intended entry point. The four GPU smokes are the same machinery driven
headlessly, and are what to run after changing any of it:

```diffstatic {data-lang=text}
tools/run_field_tests.py --id four.neural-field-parity.smoke   # one forward pass, CPU vs GPU
tools/run_field_tests.py --id four.neural-particles.smoke      # a whole trajectory, point by point
tools/run_field_tests.py --id four.neighbour-grid.smoke        # neighbour sets vs brute force
tools/run_field_tests.py --id four.shader-boids.smoke          # both rules vs a CPU brute force
```

All four need a GPU and an awake display: `afield.four` has no headless mode, so even a
compute-only dispatch needs a `Window` for the device to exist.

## Limits

- **The learned rule cannot predict the equilibrium residual.** In a settled flock, separation and
  cohesion are large and nearly cancelling, and what survives is a small difference of big terms.
  Scored on a flock relaxed until it stops rearranging, the learned rule does slightly *worse*
  than predicting exactly zero. It learned to be small, not to find the residual.
- **A relative force error of a tenth is not boids**, and the picture is not evidence either way.
  Almost any attraction-plus-repulsion rule with a speed limit produces something an audience
  accepts.
- **No video of the GPU window.** The native surface is stills-only, so the figures on this page are
  screenshots of a live system rather than recordings of one.

## See also

[Flow matching](flow_matching.md) is the CPU half of tier one and worth reading first.
[afield.four Graphics](afield_four_graphics.md) is the compute-then-draw shape both systems are
built on. The API is in [Machine learning](reference/machine-learning.md) and
[Simulation](reference/simulation.md). `dev_docs/NEURAL_PARTICLES_MEASUREMENTS.md` has the numbers,
the falsification tests, and the six measurement problems this work actually consisted of.
