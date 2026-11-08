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
    panels = { pl, n -> ["step %d   mean E %.3f".format(n, pl.currentEnergy()) to pl.render(extent = 24.0, size = 300, cx = 0.0, cy = 0.0)] },
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
    caption = "mean particle energy, steps 0 to 3000: %.3f down to %.3f".format(pl.energyHistory.first(), pl.currentEnergy()),
)
:::endfold
```

![three dark panels: white dots arranged in a ring of petals over a bright orange energy field; a purple disc of the potential U with a bright centre and dark holes where the petals are; the control, a featureless square of evenly spaced dots; below, an energy curve that falls steeply and then in steps](assets/particle_lenia/particle-lenia.png)

The printed rotation (mean angular velocity about the centroid) and drift (speed of the centroid) are
both small after 3000 steps: under the article's default rule this flower is close to still.

## A creature that turns and travels

A narrower kernel ring and weaker repulsion give a creature that moves. With `sigmaK = 0.6` and
`cRep = 0.3` (the other numbers unchanged), 100 particles started in an 8.5-unit square settle by step 3000
into a pinwheel: four loops of particles about 10 units across, with two small satellite clusters.
The pinwheel then spins steadily, and its centre of mass slowly travels along a curve.
Between steps 3000 and 12000 its mean energy stays at −0.634, its spread at 3.08 to 3.09, its
rotation at 0.0024 to 0.0028 rad per unit time, and its centroid speed at 0.0052 to 0.0059. The
video below follows it on a fixed frame, with the centroid's path drawn as it goes.

```diffstatic_runner {data-lang=kotlin video="assets/particle_lenia/particle-lenia-rotator.mp4" capture="box" open-outputs="true" wait-output="true" wait-output-kind="display" execution="launch" duration="8" fps="20" timeout="600" w="600" embed=true name="particle-lenia-rotator-video" alt="100 white dots gather into a pinwheel of four loops with two small satellite clusters, which spins while its centre drifts, leaving a thin white trail of its centre's path"}
// The rotator rule: a narrower kernel ring and weaker repulsion than the article's defaults.
val rule = ParticleLeniaParams(sigmaK = 0.6, cRep = 0.3)
val track = mutableListOf<Pair<Double, Double>>()

animateEvolution(
    ParticleLenia.random(100, side = 12.0 * Math.sqrt(0.5), seed = 1L, params = rule),
    totalSteps = 12000,
    seconds = 7.0,
    cellSide = 400,
    step = { pl ->
        pl.step()
        if (pl.steps % 50 == 0) track += pl.centroid()
        pl
    },
    panels = { pl, n ->
        val img = pl.render(extent = 24.0, size = 300, cx = 0.0, cy = 0.0)
        // the centroid's path so far, as small dots
        ["step %d".format(n) to splatDots(img, track, 24.0, 0.0, 0.0, dotRadius = 1.0)]
    },
)
```

To look for it, the same seed was run for 5000 to 10000 steps at each point of two grids, measuring
the centroid's displacement and the population's rotation over two 2000-step windows 3000 steps
apart. A creature counts as moving if both windows agree. The first grid varied `muG` (0.5, 0.6,
0.7), `sigmaG` (0.1, 0.15, 0.2) and `wK` (0.015, 0.022, 0.03) at 200 particles, and found nothing
that kept moving. The second varied the particle count (12, 25, 50, 100, 200), `muK` (3, 4, 5),
`sigmaK` (0.4, 0.6) and `cRep` (0.3, 1). Three settings rotated steadily in both windows: 100
particles at `muK = 4, sigmaK = 0.6, cRep = 0.3` (0.52 and 0.55 rad per window), 100 at
`muK = 5, sigmaK = 0.6, cRep = 1` (0.32 and 0.32), and 200 at `muK = 5, sigmaK = 0.6, cRep = 0.3`
(0.24 and 0.25). None travelled in a straight line. The article's glider was not found.

## Thousands of particles on the GPU

Each CPU step sums over every pair of particles, so it costs `O(n²)`.
[`ShaderParticleLenia`](reference/simulation.md#shaderparticlelenia-kt) runs the same law as a Vulkan compute shader over a
[`GpuNeighbourGrid`](reference/graphics.md#gpuneighbourgrid-kt), the grid the [GPU boids](neural_particles.md) use. Each
particle sums only over the 3 × 3 block of cells around it, so the grid's cell has to be at least as
wide as the kernel's cutoff, `muK + 3 sigmaK`. Each step is two passes: one computes every velocity
from the old positions, and the other moves the particles. The GPU velocities agree with the CPU's to
1.6 × 10⁻⁴ of the largest speed over 1600 particles.

The box below goes beneath a renderer box. It seeds 64 clusters of 100 particles each under the
rotator rule, 6400 particles in all, and runs 20 steps per frame in the native window. Clusters that
drift into each other merge into larger creatures. The video after the code is the native window
for the first 15 seconds after launch.

```diffstatic {data-lang=kotlin}
import afield.simulation.physics.ShaderParticleLenia

prop<ShaderParticleLenia>(lenia)

// 64 clusters of 100 particles under the rotator rule: 6400 particles, one creature per cluster.
once.lenia {
    val grid = GpuNeighbourGrid(
        capacity = 6400,
        cellsPerSide = 40,
        domainMin = Vector2f(-120f, -120f),
        domainSize = 240f,
        renderer = renderer,
        name = "leniaGrid",
    )
    ShaderParticleLenia(grid, 6400, renderer, ParticleLeniaParams(sigmaK = 0.6, cRep = 0.3), name = "lenia")
        .also {
            it.reset(creatures = 64, perCreature = 100, side = 8.5f, spacing = 26f, seed = 1)
            it.install(renderer.renderList.others)
            it.installDrawInto()
        }
}

lenia.substeps = 20
lenia.pointSize = 3f
lenia.viewScale = 110f
println("particles: " + lenia.count + ", cutoff " + lenia.cutoff + ", cell " + lenia.grid.cellSize)
```

```diffstatic_runner {data-lang=kotlin capture="native-window" capture-box="plv_renderer" transcript="dev_docs/agents/transcripts/docs_capture/gpu_particle_lenia_video.yaml" video="assets/particle_lenia/gpu.mp4" duration="15" wait="0.2" timeout="300" embed=true hidden=true name="particle-lenia-gpu" alt="64 square clusters of gold points on black each pull themselves into a creature, rings of dotted petals and small three-lobed pinwheels, which slowly turn; neighbours that touch merge into larger elongated colonies"}
// Built and launched from dev_docs/agents/transcripts/docs_capture/gpu_particle_lenia_video.yaml
// (a renderer box on a smaller window and the box above beneath it).
```

## Where to take it

- **Per-particle rules**, the article's 3-D variant, and gliders from its other parameter sets.
