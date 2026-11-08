# Particle Lenia

[Lenia](alife_examples.md#5-lenia) runs on a grid: every cell holds a value, and mass comes and goes as
cells grow and decay. Particle Lenia, by Alexander Mordvintsev, Eyvind Niklasson and Ettore Randazzo
at Google Research ([the article](https://google-research.github.io/self-organising-systems/particle-lenia/),
[live demo](https://znah.net/lenia/)), keeps the Lenia kernel and growth curve but takes the grid away.
A fixed set of particles defines a field over the plane, and the particles move through it. Nothing is
created or destroyed, so the mass is the particle count and stays constant.

Each particle adds a ring-shaped bump to the Lenia potential `U`, and a short-range repulsion `R` keeps
particles from piling on top of each other:

```diffstatic {data-lang=text}
U(x) = Σ K(|x − p_i|)                 K(r) = wK · exp(−((r − muK) / sigmaK)²)
R(x) = (cRep / 2) Σ max(1 − |x − p_i|, 0)²
E(x) = R(x) − G(U(x))                 G(u) = exp(−((u − muG) / sigmaG)²)
dp_i/dt = −∇E(p_i)
```

A particle is rewarded (its energy drops) where `U` is close to `muG` and it is not crowded. Every
particle slides downhill on `E`, so the population as a whole looks for an arrangement in which each
member has the right amount of neighbourhood. The gradient is computed analytically, not by
differencing. [`ParticleLenia`](reference/alife.md#alife-particle-lenia-kt) holds the particles and
[`ParticleLeniaParams`](reference/alife.md#alife-particle-lenia-kt) the rule; the defaults are the
article's (`muK = 4`, `sigmaK = 1`, `wK = 0.022`, `muG = 0.6`, `sigmaG = 0.15`, `cRep = 1`, `dt = 0.1`).

## From a random square to a flower

The article's starting point is 200 particles scattered uniformly over a 12-unit square. Below, they
run for 3000 steps, drawn as white dots over `−E`: dark is empty space, where `E = 0`, and bright is
an energy well. In the first hundred steps the crowded square bursts outward to a ring. Particles
left in the middle sit where `U` is too high for the growth curve, so they get no reward there, and
over the next few thousand steps they move out to the rim. The result is a ring of petals.

```diffstatic_runner {data-lang=kotlin video="assets/particle_lenia/particle-lenia.mp4" capture="box" open-outputs="true" wait-output="true" wait-output-kind="display" execution="launch" duration="8" fps="20" timeout="600" w="600" embed=true name="particle-lenia-video" alt="200 white dots over a dark field: the square of dots bursts into a bright ring, dots in the middle drift out, and the ring settles into a flower of petals around an empty centre"}
// 200 particles from a random square, 3000 steps, drawn over the energy field.
animateEvolution(
    ParticleLenia.random(200, side = 12.0, seed = 1L),
    totalSteps = 3000,
    seconds = 7.0,
    cellSide = 400,
    step = { it.step() },
    panels = { pl, n -> ["step %d   mean E %.3f".format(n, pl.energyHistory.last()) to pl.render(extent = 24.0, size = 300, cx = 0.0, cy = 0.0)] },
)
```

The box below runs the same seed to step 3000 and draws three things: the particles over `−E`, the
potential `U` they produce, and the mean particle energy over the run. The energy falls quickly
from 0.52 to below zero as the square spreads out, then drops in steps, each one a rearrangement in
which a few particles find a better place. The control is the same seed with `wK = 0`. With no
kernel, `U` is zero everywhere and the growth term is a constant, so only the repulsion is left. The
particles spread until no two are closer than one unit, then stop. They make no ring and no petals, and the control's mean energy is exactly 0.5: each particle's own
repulsion term at distance zero, `cRep / 2`, which the sum includes, as the article's does.

```diffstatic_runner {data-lang=kotlin screenshot="assets/particle_lenia/particle-lenia.png" wait="40.0" timeout="300" capture="box" open-outputs="true" padding="20"}
val pl = ParticleLenia.random(200, side = 12.0, seed = 1L)
for (n in [0, 100, 500, 3000]) {
    pl.stepTimes(n - pl.steps)
    println(pl)
}

// Control: the same seed with no kernel, so repulsion alone moves the particles.
val control = ParticleLenia.random(200, side = 12.0, seed = 1L, params = ParticleLeniaParams(wK = 0.0)).stepTimes(3000)
println("CONTROL, wK = 0: " + control)

:::fold draw the particles over -E, the potential U, and the energy curve
showImg(
    montage(
        [
            "particles over -E, step 3000" to pl.render(extent = 24.0, size = 360),
            "U, step 3000" to pl.fieldImg(extent = 24.0, size = 360, which = "U").colormap("magma", 0f, 1f),
            "control, wK = 0, step 3000" to control.render(extent = 24.0, size = 360),
        ],
        columns = 3,
        cellSide = 300,
    ),
)
showImg(
    energyPlotImg(pl.energyHistory, 720, 160),
    channel = "energy",
    caption = "mean particle energy, steps 0 to 3000: %.3f down to %.3f".format(pl.energyHistory.first(), pl.energyHistory.last()),
)
:::endfold
```

![three dark panels: white dots arranged in a ring of petals over a bright orange energy field; a purple disc of the potential U with a bright centre and dark holes where the petals are; the control, a featureless square of evenly spaced dots; below, an energy curve that falls steeply and then in steps](assets/particle_lenia/particle-lenia.png)

The printed rotation (mean angular velocity about the centroid) and drift (speed of the centroid) are
both small after 3000 steps under this rule and seed: the flower is close to still. Across seeds 1
and 2 with 50, 100, 200 and 400 particles, every run settled into a near-stationary shape. The
article's moving creatures (rotators and gliders) come from other parameters and seeds, which
this page has not reproduced yet.

## Where to take it

- **Thousands of particles.** Each step sums over every pair, so it costs `O(n²)`; 200 particles
  step comfortably on the CPU. A GPU version with a neighbour grid would follow the
  [million-agent Physarum](alife_examples.md#a-million-agents-on-the-gpu) pattern.
- **Per-particle rules**, the article's 3-D variant, and gliders from its other parameter sets.
