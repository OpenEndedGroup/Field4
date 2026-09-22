# Measurements behind the trained particle rules

Moved out of `public/docs/neural_particles.md` on 2026-09-20, where it was most of the page. That
page is a guide to *using* the two systems; this is the record of how each claim in it was
established, kept because the interesting part of this work was almost never the training or the
shaders.

**Almost every difficult moment was a measurement problem.** Several times over, a correct system
looked broken or a broken one looked fine, and what fixed it was a sharper instrument rather than
different code. The six sections below each name the instrument that settled the question, in the
order they came up.

Source: `src/jvmMain/kotlin/afield/stdlib/neuralparticles.kt`, `neuralparticles_system.kt`,
`deepsets.kt`, `src/jvmMain/kotlin/afield/four/util/GpuNeighbourGrid.kt`,
`src/jvmMain/kotlin/afield/four/physics/ShaderBoids.kt`. The assertions are the four smokes named at
the end of the public page.

---

## Measurement one: two comparisons, not one

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

## Measurement two: grade a flow as a map, not as a cloud

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


## Measurement three: the loss was actively misleading

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

## Measurement four: verify neighbour queries as sets, never as forces

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


## Measurement five: the feature map, found by ablating the target term by term

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


## Measurement six: a symmetry the rule has and the model did not

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

---

## Measurement seven: the shared-seed condition is weaker than the argument for it

`blendWith` interpolates two fields in weight space, and the standard argument says both must be
trained from the same initialisation: hidden units are exchangeable, so two runs from different
random starts represent similar functions through different permutations of the same machinery, and
averaging across a permutation should produce nothing.

Measured, it is weaker than that. Across three seed pairs the midpoint stayed tidy whether the
initialisations were shared or not. The likely reason is that the other condition does most of the
work: both fields are trained from one shared source cloud, which makes the two training problems
nearly the same problem, and a nearly-identical problem lands in a nearly-identical basin whatever
the start.

Recorded rather than acted on. Three pairs is not a result to relax a condition on, and the cost of
passing the same `seed` is nothing, so `public/docs/neural_particles.md` still states it as a
requirement. **Open:** whether it stays tidy as the two clouds are allowed to diverge -- the regime
where the shared-source explanation predicts it should stop.

---

## The discipline, in general

Two habits that the sections above keep arriving at independently.

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



## What does not work yet

**The learned rule cannot predict the equilibrium residual.**
(reproducer: `EquivariantInteractionTest.kt#fun inTheEquilibriumRegimeTheModelIsNoBetterThanPredictingZero`)
In a settled flock, separation and
cohesion are large and nearly cancelling, and the acceleration that survives is a small difference
of big terms. The reproducer relaxes a 220-boid flock under the true rule until it stops
rearranging, then scores the learned rule on the configuration that produces: **1.315 relative,
where predicting exactly zero scores 1.000**. In that regime the model is not merely no better
than the zero baseline, it is slightly worse than it.

Treat the *number* as a range and not a figure: earlier ad-hoc runs, which sampled the settled
state differently, put it at roughly 0.8 to 0.9, and it moves with the seed and a great deal with
exactly how the separation window is specified. The reproducer's assertion is set accordingly — it
requires only that the model fail to beat the zero baseline by a meaningful margin, because a tight
bound on a quantity this unstable would be a flake dressed as a finding. What every version of the
measurement says is the same thing: this is not a model that learned the residual; it is a model
that learned to be small. Training on neighbourhoods
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
the public page.

So the motion comes from outside the interaction. `driverStrength` adds a pull toward, or a push
away from, a point that wanders on a smooth random walk; it is applied after the rule exactly like
the containment term, defaults to zero, and the network knows nothing about it. That keeps it
forcing rather than a modelling error, and keeps the A/B comparing the two rules and nothing else.

**A relative force error of a tenth is not boids.** It is worth restating because the picture is
persuasive and the picture is not evidence. Almost any attraction-plus-repulsion rule with a speed
limit produces something an audience accepts, which is why "it flocks" was never allowed to count
here.

---
