# Particle rules that were trained

There are two kinds of rule a particle can follow. The first reads only its own state — a velocity
field `v(x, t)`, blind to every other particle in the buffer. The second reads its neighbours — a
force that depends on the company it keeps. Field can now fit either one offline, in Kotlin, in a
few seconds, and run it live on the GPU: three hundred thousand particles carried by a trained
field, or twelve thousand boids whose flocking rule was learned from the hand-written one running
beside it.

Both tiers train on the CPU and evaluate in GLSL, and the numbers that matter are all about whether
the two agree — which is what most of this page is about. The training was not the hard part and
neither were the shaders. **Almost every difficult moment in this work was a measurement problem.**
Several times over a correct system looked broken, or a broken one looked fine, and what fixed it
was a sharper instrument rather than different code. Each section below names the one it turned up.

---

## Tier one: a velocity field as a shader

[Flow matching](flow_matching.md) already fits `v(x, t)` by least squares in about a second of pure
Kotlin. `neuralparticles.kt` takes that network to the GPU. `MlpWeights` is a flat, layer-major
snapshot of the weights, and `MlpWeights.glsl` emits an unrolled forward pass that reads them out
of a storage buffer:

```diffstatic {data-lang=kotlin}
val field = flowMatch(pairing.to, pairing.from, FlowMatchOptions(coupling = FlowCoupling.PAIRED))
val export = field.export()                  // the weights, the training frame, the source cloud
val glsl = export.glsl("neuralField")        // place this immediately after #version
```

The weights live in a buffer rather than baked in as literals, so retraining swaps a buffer between
frames and never recompiles a shader. The layer loop is unrolled and the dot products are not, so
the emitted source stays proportional to the *depth* of the network — a five-thousand-parameter net
is still readable GLSL.

`ShaderNeuralParticles` is the system on top. Each particle is a struct, not a texel:

```diffstatic {data-lang=glsl}
struct Particle { vec2 position; vec2 start; vec2 velocity; float t; float pad; };
```

`start` and `t` are there because a flow is a *map* rather than a field of forces. A particle is not
wandering; it is travelling from one specific source point to wherever the field takes it by
`t = 1`, and when it arrives it goes back and does it again. Neither value fits in a four-channel
texel, which is the concrete reason this is a buffer of structs.

One forward pass per particle per frame. The default flow-matching network is `11 -> 64 -> 64 -> 2`,
about five thousand multiply-adds, so two hundred thousand particles is roughly a GFLOP a frame.
Width is the cheaper dial than count: a `32 x 32` network is four times less work, and a 2D flow
rarely needs 64.

### Measurement one: two comparisons, not one

There are two distinct errors between "the network Smile trained" and "the network the GPU runs",
they are of wildly different sizes, and combining them makes the honest one look like a bug.

**Precision** is fp64 against fp32 and is bounded by the hardware. **Transcription** — a transposed
weight matrix, an off-by-one bias offset, a leaky slope of 0.2 instead of 0.01 — is either exactly
right or grossly wrong, and a grossly wrong one still produces smooth, plausible, entirely fictional
output. There is no picture of a particle system that shows you a transposed layer.

So the two are measured apart. `MlpWeights.evaluate` is an fp32 reference *in Kotlin* that
accumulates in the same order the shader does; it is held to Smile's own forward pass at 2e-06
relative, with no GPU in the room. The GPU is then held to that reference, fp32 against fp32, where
the only remaining freedom is how the hardware chooses to reassociate a dot product:

```diffstatic {data-lang=text}
--- trained flow-matching field: MlpWeights(11->64->64->2, LEAKY, 5058 parameters)
  GPU vs MlpWeights.evaluate: worst relative 5.84e-06 (absolute 9.54e-07) over 8192 comparisons
  GPU vs a transposed network: worst relative 114 (absolute 3.59) over 8192 comparisons
  ok (transposed is 19590259x worse)
```

A single comparison against `Double` would need a tolerance wide enough to absorb the fp32 gap,
which is wide enough to hide a transposed matrix in a small network.

The transposed line is the part worth copying. A tolerance means nothing next to an error whose size
you have not measured, and quoting that size from a comment written on a good day is not a
measurement. So `four.neural-field-parity.smoke` deliberately transposes the network, scores it off
the same readback, and reports the ratio. The margin is re-measured on every run, and it is about
2e7.

Two more assertions round it out. The output buffer is prefilled with a sentinel no network can
produce, because a dispatch of the wrong size otherwise reports perfect agreement on the samples it
did reach. And three network shapes are checked, not one: square layers are where a transposition
hides, and equal widths are where an offset error cancels.

### Measurement two: grade a flow as a map, not as a cloud

`four.neural-particles.smoke` is the system assertion on top — a hundred and fifty Euler steps of
feedback, each step's output the next step's input. It reads the particles' own `start` points
back off the GPU, hands those same points to the CPU integrator, and compares the landings **point
by point**: did particle *i* go where particle *i* was supposed to go. A statistic of the cloud
would pass a system that produced a correct-looking shape by the wrong route.

```diffstatic {data-lang=text}
GPU vs CPU Euler, per particle: worst 1.73e-06 of the ring radius (particle 1598)
landing radius: mean 139.8 (target 140.0), spread 5.6
```

The interesting part is that the error does not compound: 1.73e-06 after a hundred and fifty steps,
against 5.84e-06 for a single forward pass. A converging flow is contractive, so it pulls its own
numerical error back in along with everything else.

Reading `start` back rather than regenerating it from the seed is deliberate — it stops the test
agreeing with itself about a source draw that never reached the GPU. And two supporting checks
cover the sharp one's blind spot at zero: that the particles moved at all, and that they landed on
a ring of the radius they were trained on.

---

## A word is not a letter

The figure below is the word FIELD, formed by three hundred thousand particles each running a
forward pass per frame. Getting there needed a piece of work that has nothing to do with the GPU.

```diffstatic_runner {data-lang=kotlin screenshot="assets/neural_particles/word.png" capture="native-window" wait="50" native-wait-frames="30" embed=true hidden=true timeout="900" duration="0" name="neural-particles-word" alt="The word FIELD drawn in warm gold particles on black, the letter outlines bright and their interiors a dimmer haze" macro-file="../../dev_docs/agents/transcripts/neural_particles.yaml" capture-box="np_renderer"}
// Built and launched from dev_docs/agents/transcripts/neural_particles.yaml
// (renderer, an offline training box, and the live system).
```

Integrating a velocity field is a continuous invertible map. It can deform a blob but it cannot tear
one into five, so a standard Gaussian cannot become the five disjoint outlines of a word. Measured
on this target, that configuration sits at a chamfer distance of about 15 against a floor of 0.8,
and forty thousand steps at 128x128 hidden units still measures 12.6.

`mixtureSource` makes the word reachable by giving the source the target's *topology*: one ellipse
per closed subpath, fitted to that subpath's own mean and covariance, in point-for-point
correspondence with the contour it has to become. Three choices inside that construction were each
worth a measurement, and each is pinned as a ratio against its alternative rather than as an
absolute:

| what | alternative | measured |
|---|---|---|
| subpaths, not glyphs | a "D" as one annulus | mean landing error 13.8 -> 4.3 |
| each ellipse rotated into phase | identity pairing | training loss 0.054 -> 0.00097 |
| no jitter on the source | ellipse thickened to an annulus | mean landing error 17.8 -> 3.6 |

The middle one is the least obvious and the most load-bearing. A contour's arc-length origin has
nothing to do with angle zero on the ellipse fitted to it, so the identity pairing carries an
arbitrary rotation per component, and the flow spends its capacity unwinding it.

One more thing had to be right before any of this could be *seen*, and it is not part of the flow at
all. A particle that resets the instant it reaches `t = 1` is on the target for one frame in a
hundred and fifty, so with the phases spread out only a hundred and fiftieth of the cloud is ever on
the shape and the rest — all still in transit — drown it. At `dwellCycles = 0` the picture
is a featureless haze that looks exactly like an undertrained field. At the default of 3 a particle
spends three quarters of its life arrived. Turning it off and back on changes nothing about the
trained field and everything about whether you can tell it apart from a broken one.

### Measurement three: the loss was actively misleading

Through all of that, the training loss was not a quality measure. It was worse than that: it moved
confidently in directions the picture did not follow.

```diffstatic {data-lang=text}
FIELD, single Gaussian:   chamfer 14.9   loss 1.333
FIELD, mixture source:    chamfer  5.5   loss 0.001
floor (the target's own sampling noise)  0.8
ceiling (source left where it started)   288.6
```

Two ways to read that wrongly. Inside the single-Gaussian family, more training lowers the loss and
leaves the chamfer where it was — forty thousand steps at 128x128 units buys a better fit to an
average over paths that have to cross, which is the wrong target rather than an under-fitted one.
And across the two families the loss differs by a factor of a thousand while the chamfer differs by
a factor of three, so the loss cannot be read as a scale of anything. A flow-matching loss measures
how well the field fits an average; it has no opinion about whether that average was worth fitting.

The floor and the ceiling in that table are the reason the middle number means anything. 5.5 is
about two thirds of the way from the single-Gaussian result to the floor, and still nearly seven
times the floor — the remaining gap is the next piece of work, not a rounding error.
`MixtureSourceTest` asserts that the mixture stays *above* 0.8, deliberately, so that closing the
gap fails the test and forces the numbers on this page to be re-measured rather than drift.

One caveat on the two chamfer figures: `FlowMatchField.sample` draws unseeded, so they move between
runs. The single-Gaussian result sits between 13 and 15 across the runs we have; the mixture between
5.2 and 5.5.

---

## The neighbour grid

Tier two needs a sum over *nearby* particles, which needs a sort. `GpuNeighbourGrid` is the standard
construction, and every step of it exists to avoid something a compute shader cannot cheaply do:

1. **hash** — one invocation per particle writes `key = cell index`, `value = particle index`.
2. **sort** — `GpuRadixSort` sorts the pairs, so a cell's members become a contiguous run. This is
   the step that turns a scatter problem into a gather problem.
3. **ranges** — one invocation per sorted entry compares its key with its neighbours' and writes
   each run's bounds. No atomics: an entry can tell whether it begins a run by looking left.
4. **read** — a consumer walks the 3x3 block of cells around its own.

Cells are row-major rather than Morton-ordered. A hierarchy needs a node's *children* contiguous; a
flat grid needs a *cell's members* contiguous, and row-major does that while making the nine indices
of a 3x3 neighbourhood plain arithmetic instead of a bit interleave.

The 3x3 block covers one cell width, so the cell size is the largest interaction radius the query
answers correctly. Make the cells smaller than the radius and the query silently misses neighbours:
the shader still runs, the flock still flocks, and it is wrong.

Step 2 cost an afternoon for a reason that was also a diagnostic problem rather than a coding one.
Buffers built for read-back carry `streamReads()`, which is the standing advice on
[Field probes](field_probes.md) — but it changes the access pattern, and `toBuffer()` used to
respond to a binding it could not satisfy by quietly allocating a compatible one instead. The sort
then sorted a *different allocation*, perfectly, and left the caller's buffer untouched. No error,
no warning: the keys came back exactly as the hash had written them, which is indistinguishable from
the dispatch never running. `toBuffer()` now throws.

### Measurement four: verify neighbour queries as sets, never as forces

The obvious test runs a force law on both sides and compares the totals. It is the wrong test. A
query that misses a tenth of its neighbours produces a force a few percent out, which disappears
inside any tolerance loose enough to allow for fp32, and the resulting flock still looks like a
flock. Every failure mode of a spatial grid has that shape — cells fractionally too small, an
off-by-one on the block, a run boundary that drops its last entry, a particle on a cell edge counted
in the wrong cell. None of them look like anything.

So `four.neighbour-grid.smoke` compares the *set* of indices the grid yields against the set brute
force yields, per particle, and requires equality. One missing index fails. That is an assertion no
tolerance can absorb.

```diffstatic {data-lang=text}
--- uniform:       neighbour sets: 0 of 3043 particles disagree; mean neighbourhood 12.2
--- clustered:     neighbour sets: 0 of 4000 particles disagree; mean neighbourhood 871.7
--- out of domain: neighbour sets: 0 of 1201 particles disagree; mean neighbourhood 4.7
```

Three distributions, because a uniform cloud gives every cell a similar occupancy, which is the case
where a run-boundary off-by-one costs least and hides best. The clustered case, at 872 neighbours a
particle, is the one that finds it. Particles outside the domain are clamped into the edge cells
rather than dropped, because a lost particle is a particle that silently stops interacting.

---

## Tier two: a learned interaction

A velocity field is blind to the other particles. An interaction is the other kind of rule, and the
Deep Sets form is the right family for it:

```diffstatic {data-lang=text}
a_i = phi( v_i, SUM_j psi(x_j - x_i, v_j - v_i), MEAN_j psi(...) )
```

Two small networks and a reduction. The aggregation is permutation-invariant and takes any number of
neighbours, so one set of weights serves a particle with three neighbours and a particle with three
hundred. Classical Reynolds boids is one particular choice of `psi` and `phi`, which means the
family provably contains something worth finding — and it means there is an exact oracle to grade
against, which learning a rule from recorded motion would not have.

Both the sum and the mean are fed to `phi`, and reaching for one reduction cannot work: separation
is a **sum** (being crowded by twenty neighbours pushes harder than by two) while alignment and
cohesion are **means**. Ablated, that branch is worth a factor of four — 0.1128 with it against
0.4869 without, on a scale where predicting the batch mean scores 0.9991.

`trainDeepSets` and `trainEquivariant` fit it by reverse-mode autodiff over `ADTape`, because
Smile's MLP cannot express a gradient flowing back through a sum over a variable-length set with
shared weights. Both halves of either form come out as ordinary `MlpWeights`, so the GLSL emitter
and the parity smoke above apply to them unchanged. The `learned_boids` scene runs the equivariant
form, for the reason measurement six gives.

`ShaderBoids` then runs either the hand-written Reynolds rule or the trained one, branching only
where the force is computed — same buffer, same neighbour search, same integration, same speed
limit. The two flocks are an A/B of the rule and of nothing else.

### Measurement six: a symmetry the rule has and the model did not

The first version of this worked, ran, and flocked, and it was wrong in a way that took a person
watching it to notice: the learned flock drifted steadily out of its containment while the written
flock, under the identical containment, sat still.

Nothing in the plain Deep Sets form makes it **rotationally equivariant**. The true rule satisfies
`a(R x) = R a(x)` identically, because every term is built from the input vectors themselves. A
network built from scalar weights on Cartesian components has a preferred direction baked in by its
random initialisation. Measured, on a configuration where the answer is known exactly:

| | plain form | equivariant form |
|---|---|---|
| symmetric ring, 12 neighbours (true force: exactly zero) | 0.14 | 2.9e-08 |
| symmetric ring, 48 neighbours | 0.50 | 1.2e-07 |
| a 90-degree rotation, as a fraction of a typical force | 18% | — |
| arbitrary rotations, worst over 160 | — | 9.3e-06 |
| relative error against the rule | 0.302 | **0.262** |

A residual that is fixed in direction and *grows with crowding* — because it rides on the
aggregated message — is a steady drift, and that is what carried the flock away.

`EquivariantWeights` removes it by construction rather than by fitting. `psi` sees only invariants
of a neighbour — `r`, the proximity `p`, `|dv|` and `u . dv` — and emits **scalars**; the vector
comes from that neighbour's own direction and velocity difference. Those are aggregated, and `phi`
sees only the *lengths* of the aggregates and emits scalars again. Rotate every input and nothing
any network sees changes, so the output rotates with the inputs. For a symmetric neighbourhood the
direction vectors cancel before a network is reached at all, so the answer is identically zero.

Boids is still inside the family — separation is a coefficient on `u` proportional to `p`, cohesion
one proportional to `r`, alignment a coefficient of one on `dv` — so nothing is given up. And the
constraint *improved* the fit rather than costing accuracy, which is the usual result when a
symmetry is real: the unconstrained model had been spending capacity approximating something it
could have been given.

The GPU gets its own assertion, because a shader can break the property while every parity number
stays green — one component read where a length was meant and it is gone. So the smoke rotates the
settled flock bodily and requires the forces to be the rotation of the originals: **4.45e-05**,
against a parity of 1.62e-05.

```diffstatic_runner {data-lang=kotlin screenshot="assets/neural_particles/flocks.png" capture="native-window" wait="180" native-wait-frames="30" embed=true hidden=true timeout="900" duration="0" name="neural-particles-flocks" alt="Two flocks of points on black, each a sweeping arc: on the left a blue-grey flock, on the right a warm-toned one, both curled around the wandering point they are fleeing" macro-file="../../dev_docs/agents/transcripts/learned_boids.yaml" capture-box="lb_renderer"}
// Built and launched from dev_docs/agents/transcripts/learned_boids.yaml
// (renderer, a training box, and the two flocks).
```

Left is the written rule, right the learned one, twelve thousand boids each, seeded from the same
random draw into their own halves of the domain, both fleeing a point that wanders on the same
smooth random walk about each flock's own centre.

An earlier version of this figure showed the learned flock strung into a ribbon and drifting out of
frame while the written one held station, and that picture was the bug report that produced
measurement six below. With the symmetry built in, each flock stays around its own centre — they
meet in the middle of the frame but neither runs away — and the difference between them is the one
that should remain: a relative force error of about a quarter is
a flock that behaves *like* boids, not a flock that *is* boids, and over ten thousand chaotic steps
the two are never the same picture.

### Measurement five: the feature map, found by ablating the target term by term

The single thing that made tier two work is that `psi` is not fed the raw offset. It is fed the
offset and velocity difference *plus* the unit direction scaled by a small radial basis — `u*p` and
`u*p^2`, where `p` is a bounded proximity that rises as neighbours get close.

That was found by taking the target apart, not by intuition. Fed raw Cartesian offsets, the model
reproduces alignment and cohesion to a relative error of 0.036 — both are plain means, linear in the
inputs — and reproduces separation at 0.78, which is barely better than predicting the average.
Separation goes as `1/r` over a four-hundred-fold range, and a `tanh` network cannot form the
*product* of a direction and a steep radial profile out of Cartesian components at any width worth
paying for. With the radial features, separation drops to about 0.107.

The claim being made is about radial interactions in general rather than about boids: a radial law
*is* a direction times a profile, so handing the network `u` already multiplied by a couple of
profile terms states the form of a two-body interaction. What is left to learn is the profile's
coefficients, the velocity dependence, and how the summed and meaned branches combine.

### Always against a floor and a ceiling

Several numbers in this work are meaningless on their own, so every figure in tier two is quoted
against two references: a floor of zero, since the target is exact, and a ceiling — the error of a
model that learned nothing but the average.

```diffstatic {data-lang=text}
learned boids: relative error 0.1063 (predicting the mean scores 0.9991), 1064 parameters
  1 neighbours:  relative error 0.1445
  3 neighbours:  relative error 0.0966
 30 neighbours:  relative error 0.1075
```

The last three are the property the Deep Sets form exists for: one set of weights over any neighbour
count. Thirty is beyond anything it trained on, so it is extrapolation and allowed to be worse.

### Grade a force, at `dt = 0`

`four.shader-boids.smoke` holds every acceleration check at `dt = 0`. Boids is chaotic — two
implementations agreeing to seven digits diverge visibly within a few hundred steps — so comparing
trajectories measures nothing but Lyapunov time. With `dt = 0` the configuration is fixed and the
acceleration is a pure function of it.

```diffstatic {data-lang=text}
uniform:    worst relative 9.74e-06; largest |a| 11.231
clustered:  worst relative 8.15e-05; largest |a| 173.906
integration and the speed limit: worst velocity disagreement 9.31e-09;
            2167 of 3000 hit the ceiling; 0 exceed it
weights at zero: 0 of 3000 have a non-zero acceleration
learned interaction: GPU vs CPU learned rule, worst relative 0.000102 over 3000 boids
```

The integration law is checked from a *single* readback, because each struct carries the velocity
the step integrated from alongside the force and the result. Checking `v' = v + a·dt` across two
readbacks requires knowing that exactly one step happened in between, which a caller cannot know —
`dt` is read through a lambda when the push buffer is written, so a frame already in flight can pick
up a new value. Recording the input beside the output turned a flaky assertion into an exact one.

---

## What does not work yet

**The learned rule cannot predict the equilibrium residual.** In a settled flock, separation and
cohesion are large and nearly cancelling, and the acceleration that survives is a small difference
of big terms. Sampling neighbourhoods from an equilibrated flock and scoring the learned rule there
puts it at roughly 0.8 to 0.9 relative — where predicting exactly zero scores 1.000. Treat that as
a range and not a figure: it moves with the seed, and it moves a great deal with exactly how the
separation window is specified, so a single number quoted from one run is a point estimate of
something that is not a constant. What it says either way is that this is not a model that learned
the residual; it is a model that learned to be small. Training on neighbourhoods
drawn from a running flock rather than from uniform discs is worth about a factor of ten in absolute
force error and does not fix this.

**The cost is the network, not the search.** Cost is per *neighbour*, not per particle, and the
learned rule pays a `psi` forward pass for each one. Once cohesion has gathered the flock a boid can
have a hundred or more neighbours inside its radius. Measured at 12,000 boids a flock by removing
one flock at a time from the render list and sampling `renderer.frameCounter` over eight seconds:

| | frames per second |
|---|---|
| the written rule alone | 116 |
| a `16x16` `psi`, message width 6 | 17.6 |
| an `8x8` `psi`, message width 4 | 71.2 |
| the equivariant `8x8`, both flocks, driven | **77** |

The middle rows are the control that identifies the bottleneck: the written flock runs at the same
speed on the identical grid whatever `psi` is doing, so there is no point tuning cells or particle
counts. `psi` at `16x16` is about 480 multiply-adds a neighbour against roughly ten for the three
Reynolds terms. Shrinking it to `8x8` with a 4-wide message is 160 — swept beforehand rather than
guessed, and worth 0.302 against 0.287, where predicting the mean scores 0.999. **Depth is the part
to keep**: a single hidden layer of 16 costs *more* than two of 8 and scores 0.350, worse than
either. The equivariant form is cheaper again, because `psi` reads five invariants rather than eight
features and the aggregate is four vec2s rather than six scalars through a wider `phi`.

**Left alone, the flock stops looking alive — and the drift had been hiding that.** With the
symmetry fixed and no external forcing, the scene settles into something that reads as static: an
evenly spaced disc that barely changes. The broken-symmetry residual above had been supplying the
apparent motion all along, so removing it did not break the flock, it revealed what the rule on its
own actually does here.

Whether that settled state is genuinely *still* is a separate question and not one to answer from a
screenshot. Measured on a smaller CPU flock at the same weights, the residual force does not decay
to nothing — it oscillates without trending, so "settled" there means steady churn rather than
rest. A still frame cannot distinguish the two, which is the same trap as every other picture on
this page.

So the motion comes from outside the interaction. `driverStrength` adds a pull toward, or a push
away from, a point that wanders on a smooth random walk; it is applied after the rule exactly like
the containment term, defaults to zero, and the network knows nothing about it. That keeps it
forcing rather than a modelling error, and keeps the A/B comparing the two rules and nothing else.

**A relative force error of a tenth is not boids.** It is worth restating because the picture is
persuasive and the picture is not evidence. Almost any attraction-plus-repulsion rule with a speed
limit produces something an audience accepts, which is why "it flocks" was never allowed to count
here.

---

## Running it

Two transcripts open as complete documents, from
[Agent transcripts](agent_transcripts.md) or from the welcome screen:

- **`neural_particles`** — a renderer, an offline training box, and three hundred thousand
  particles. The training box thinks for a couple of seconds, once; the particles box then runs what
  it learned every frame, forever. Almost every setting is live, including the text.
- **`learned_boids`** — a renderer, a training box, and the two flocks above. Editing the written
  rule's weights does not move the learned flock; it learned the rule as it was when you trained it.
  Re-run the training box to catch it up.

The four GPU smokes are the same machinery driven headlessly:

```diffstatic {data-lang=text}
tools/run_field_tests.py --id four.neural-field-parity.smoke   # one forward pass, CPU vs GPU
tools/run_field_tests.py --id four.neural-particles.smoke      # a whole trajectory, point by point
tools/run_field_tests.py --id four.neighbour-grid.smoke        # neighbour sets vs brute force
tools/run_field_tests.py --id four.shader-boids.smoke          # both rules vs a CPU brute force
```

All four need a GPU and an awake display: `afield.four` has no headless mode, so even a compute-only
dispatch needs a `Window` for the device to exist.

The API is in [Machine learning](reference/machine-learning.md) and
[Simulation](reference/simulation.md). [Flow matching](flow_matching.md) is the CPU half of tier one
and worth reading first; [afield.four Graphics](afield_four_graphics.md) is the compute-then-draw
shape both systems are built on.
