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
member has the right amount of neighbourhood. The gradient is computed analytically.
[`ParticleLenia`](reference/alife.md#alife-particle-lenia-kt) holds the particles and
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
    panels = { pl, n ->
        [
            "step %d   mean E %.3f".format(n, pl.currentEnergy()) to
                pl.render(extent = 24.0, size = 300, cx = 0.0, cy = 0.0),
        ]
    },
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
    caption =
        "mean particle energy, steps 0 to 3000: %.3f down to %.3f".format(pl.energyHistory.first(), pl.currentEnergy()),
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

To find moving rules, we ran the same seed for 5000 to 10000 steps at each point of two grids.
For each run, we measured the centroid's displacement and the population's rotation over two
2000-step windows 3000 steps apart. A creature counts as moving if both windows agree. The first grid
varied `muG` (0.5, 0.6, 0.7), `sigmaG` (0.1, 0.15, 0.2) and `wK` (0.015, 0.022, 0.03) at 200 particles, and found nothing
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
    ShaderParticleLenia(grid, 6400, renderer, ParticleLeniaParams(sigmaK = 0.6, cRep = 0.3), name = "lenia").also {
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

## Naming what a creature does

Calling a creature a glider or a rotator needs a measurement. [`measure`](reference/alife.md#alife-particle-lenia-motion-kt)
runs a population for a window of steps, sampling it every 50, and records five things: how far the
centre of mass travelled and how straight its path was, how far the particles turned about the
centre, how the spread changed, and how many separate groups are left at the end (particles closer
than `muK + sigmaK` are joined, the distance at which one particle's ring still reaches another).
`motion` names the window with one of five classes, using fixed thresholds that compare travel and
turning in the same units:

| class | test, in order |
| --- | --- |
| dispersing | more than one group at the end, or the spread grew by more than a quarter |
| gliding | the centroid moved at 0.005 per unit time or faster, on a path at least 0.9 straight, and faster than the rim turned |
| rotating | the rim speed of the turn (angular velocity × spread) is 0.005 or more |
| wandering | the centroid moved at 0.005 or faster any other way |
| static | none of these |

The rotator above, measured from step 3000 to 5000, is rotating: its rim moves at 0.008 per unit time
(0.0026 rad per unit time at a spread of 3.09), while its centre travels at 0.0043.

## The demo's creatures

The article's [live demo](https://znah.net/lenia/) ships a zoo of creatures people found with it,
each saved as a rule and a set of starting positions (its `zoo.js`).
[`ParticleLeniaZoo`](reference/alife.md#alife-particle-lenia-motion-kt) carries nine of them unchanged, so
the classification can be checked against creatures whose behaviour is on record. The box below
starts each one from the demo's positions under its own rule, lets it settle for 2000 steps and
measures the next 4000. Each tile is the creature at the end of the window, 16 units across, with its
centroid's path as dots and a border in the colour of its class.

```diffstatic_runner {data-lang=kotlin screenshot="assets/particle_lenia/zoo.png" wait="30.0" timeout="300" capture="box" open-outputs="true" padding="20"}
// Nine creatures from the demo's zoo, each from the demo's starting positions under its own rule:
// 2000 steps to settle, then a measured window of 4000.
val tiles = mutableListOf<Pair<String, Img>>()
for (c in ParticleLeniaZoo.creatures) {
    val pl = c.lenia().stepTimes(2000)
    val w = pl.measure(4000)
    println("%-14s %3d  %s".format(c.name, c.count, w))
    tiles +=
        "%s: %s".format(c.name, w.motion.name.lowercase()) to
            LeniaMapCell(0.0, 0.0, pl, w).tile(extent = 16.0, size = 240)
}

// The gliders again under the demo's integrator, which carries half of each step into the next.
for (name in ["Iguanmousauru", "Agilienster", "Tarbosaurolop", "Coelopasaurop", "Sauropeltad"]) {
    val euler = ParticleLeniaZoo[name].lenia().stepTimes(2000).measure(4000)
    val demo = ParticleLeniaZoo[name].lenia().also { it.inertia = 0.5 }.stepTimes(2000).measure(4000)
    println("%-14s speed %.4f (Euler)  %.4f (demo's integrator)".format(name, euler.speed, demo.speed))
}

showImg(montage(tiles, columns = 3, cellSide = 240))
```

![nine dark tiles in a three-by-three grid, each a creature over its glowing energy field with a dotted trail: five with green borders whose trails are straight lines (two tiny five-particle creatures, a dense blob inside a bright ring, two larger irregular discs), three with orange borders that turn (a small curled creature, two small rings inside a large oval, a big ring of petals round a triangular core), and one with a grey border, a still ring](assets/particle_lenia/zoo.png)

Five of the nine glide, three rotate and one is still, and the numbers the box prints say how
cleanly:

| creature | particles | class | speed | straightness | rotation, rad/t |
| --- | ---: | --- | ---: | ---: | ---: |
| Iguanmousauru | 5 | gliding | 0.2823 | 1.00 | 0.0000 |
| Agilienster | 5 | gliding | 0.1753 | 1.00 | 0.0000 |
| Tarbosaurolop | 39 | gliding | 0.1254 | 1.00 | 0.0000 |
| Coelopasaurop | 43 | gliding | 0.0134 | 1.00 | 0.0000 |
| Sauropeltad | 25 | gliding | 0.0113 | 1.00 | 0.0000 |
| Orodromic | 14 | rotating | 0.0118 | 0.10 | −0.0077 |
| Nedocernosa | 51 | rotating | 0.0065 | 0.63 | 0.0014 |
| Chungasto | 181 | rotating | 0.0007 | 0.09 | 0.0038 |
| Bellusauropel | 24 | static | 0.0000 | 0.15 | 0.0000 |

The gliders hold their shape: their spread does not change in the window, their heading turns by
less than 0.01 rad, and their rotation is zero to four places. A rigid shape moving in a straight line is a fixed point of the
motion in a frame that travels with it. The demo integrates with half of each step carried into the
next, `p += 0.5 (p − p_prev) + 0.5 dt · v` (set `inertia = 0.5` to get it). That integrator settles to
the same velocity `dt · v` as the article's Euler step, and the second block of output confirms
it: every glider has the same speed under both to four places. Nedocernosa is the closest call.
Its rim turns at 0.0051 per unit time, just over the line, while its centre moves along a wide arc
whose heading changes by 1.6 rad in the window.

The fastest gliders are tiny: five particles, sliding at 0.18 and 0.28 units per unit time. The two
sweeps above never tried rules like theirs, whose growth curve wants a much lower `U`
(`muG` 0.17 to 0.28, against the article's 0.6).

## A map of the growth curve

The growth curve decides which values of `U` a particle is rewarded for: `muG` is the value it
wants, `sigmaG` how fussy it is. The box below holds the rotator's kernel, repulsion, 100 particles
and seed, and runs a grid of 24 growth curves, `muG` from 0.3 to 0.8 across and `sigmaG` from 0.06
to 0.2 up. Each cell settles for 3000 steps and is measured over the next 4000.
[`leniaMotionMap`](reference/alife.md#alife-particle-lenia-motion-kt) runs the cells in parallel on all
cores and returns them row by row, top row first. Each tile is labelled `muG, sigmaG`, framed on its creature,
with its centroid's path and its class colour: grey static, green gliding, orange rotating, blue
wandering, red dispersing.

```diffstatic_runner {data-lang=kotlin screenshot="assets/particle_lenia/growth-map.png" wait="30.0" timeout="300" capture="box" open-outputs="true" padding="20"}
// The rotator's kernel (muK 4, sigmaK 0.6, wK 0.022) and repulsion (cRep 0.3) at 100 particles, seed 1,
// with the growth curve's centre muG across and its width sigmaG up: 3000 steps to settle, 4000 measured.
val muGs = [0.3, 0.4, 0.5, 0.6, 0.7, 0.8]
val sigmaGs = [0.06, 0.1, 0.15, 0.2]
val cells =
    leniaMotionMap(muGs, sigmaGs, settle = 3000, window = 4000) { muG, sigmaG ->
        ParticleLenia.random(
            100,
            side = 12.0 * Math.sqrt(0.5),
            seed = 1L,
            params = ParticleLeniaParams(sigmaK = 0.6, cRep = 0.3, muG = muG, sigmaG = sigmaG),
        )
    }
for (c in cells) println("muG %.2f  sigmaG %.2f  %s".format(c.x, c.y, c.window))
println(cells.groupingBy { it.motion }.eachCount())

showImg(
    montage(cells.map { "%.2f, %.2f".format(it.x, it.y) to it.tile(size = 160) }, columns = muGs.size, cellSide = 160),
)
```

![a six-by-four grid of tiles: in the lower right, grey-bordered square lattices of evenly spaced dots; on the left, flowers of bright petals, most with grey borders; four green-bordered gliders along the top row and in the third row, two orange rotators, three blue wanderers and one red tile whose creature has broken into pieces](assets/particle_lenia/growth-map.png)

Of the 24 cells, 14 are static, four glide, two rotate, three wander and one breaks up. The printed
numbers show three regions:

- **Too high to reach.** In the lower right (`muG` 0.6 to 0.8 with a narrow curve), `U` never
  reaches the value the growth curve rewards, so `G` is close to zero everywhere. The mean energy
  is 0.03 to 0.15. A value of 0.15 is `cRep / 2`, the particle's own repulsion term and nothing
  else, and the particles sit in the same square lattice as the `wK = 0` control in the first
  section.
- **Flowers.** At `muG` 0.3 and 0.4 the creature is a ring of petals with a spread of 3.6 to 5.
  Most of these are still. Two of them (`muG` 0.4 with `sigmaG` 0.10 and 0.15) travel in a
  straight line (straightness 1.00) at 0.0049 and 0.0043 per unit time, just under the threshold.
- **Movers.** In between are the gliders, at `sigmaG` 0.2 (`muG` 0.4, 0.6 and 0.7) and at 0.5,
  0.10, travelling at 0.0057 to 0.0093 per unit time, slower than every glider in the zoo. The
  page's rotator is the cell at 0.60, 0.15 (rim speed 0.0081). Its neighbours glide (0.60, 0.20),
  wander (0.60, 0.10), sit still (0.50, 0.15) or break into three groups (0.70, 0.15).

Each cell is one seed, and the article notes that different seeds can end in different creatures,
so the map shows what this seed does under each rule rather than drawing sharp boundaries.

## Searching for a glider

The map's gliders are slow, and the sweeps in the rotator section found none. The zoo says where to
look: its fast gliders are small, and their growth curves want a low `U`.
[`ParticleLeniaRuleGenome`](reference/alife.md#alife-particle-lenia-motion-kt) turns a rule into a genome
for [`PopulationSearch`](alife_examples.md), the engine behind the ALife page's Lenia and Life-rule
searches. The genome is five numbers (`muK` from 1.5 to 6, `sigmaK` 0.5 to 2.5, `muG` 0.1 to 0.9,
`sigmaG` 0.05 to 0.25, `cRep` 0.2 to 1.5), with `wK` set so that the kernel integrates to one, the
article's normalisation, which keeps `muG` meaning the same thing at every kernel size.
[`particleLeniaGlideObjective`](reference/alife.md#alife-particle-lenia-motion-kt) scores a rule by
dropping 40 particles into a random square, the size of the zoo's Tarbosaurolop. After 3000 steps to
settle it measures two consecutive windows of 1500 steps, and takes the window with the lower speed
times straightness. A creature that splits, or whose spread changes by more than 2 % in either
window, scores zero. The score is the worse of two seeds.

The spread test and the two windows help distinguish a glider from a population that is still
settling. A cloud that is spreading can move its centre and score as well as a glider over one
window. A glider holds its shape, so its spread stays put.

The box below runs evolution and, as the control, random sampling from the same distribution, both
at 300 evaluations and the same seed. Then it runs each arm's best rule from five seeds the search
never saw, for two windows of 4000 steps each, and tabulates the results. It takes a few minutes, so
it runs off the main loop.

```diffstatic_runner {data-lang=kotlin screenshot="assets/particle_lenia/glider-search.png" wait-output="true" wait-output-kind="display" wait="2.0" timeout="900" capture="box" open-outputs="true" padding="20"}
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
A rule whose 40 particles pull themselves into a glider from a random square. The genome is five numbers
(muK, sigmaK, muG, sigmaG, cRep) with wK normalised. A rule scores speed times straightness over two windows
of 1500 steps after 3000 to settle, the worse of seeds 1 and 2, and zero if its creature splits or its spread
changes by more than 2%. Random sampling at the same budget and seed is the control.
*/
val genome = ParticleLeniaRuleGenome()
val objective = particleLeniaGlideObjective(genome, count = 40, seeds = [1L, 2L])

go {
    // three or four minutes of simulation, so it runs off the main loop
    val (runs, checks) = withContext(Dispatchers.Default) {
        val runs = [
            PopulationSearch.random(genome, objective, 300, seed = 5L, label = "random sampling"),
            PopulationSearch.evolve(
                genome,
                objective,
                300,
                seed = 5L,
                mu = 6,
                lambda = 12,
                crossoverRate = 0.3,
                immigrantRate = 0.2,
                label = "evolution",
            ),
        ]
        // each arm's best rule from five seeds the search never saw: two windows of 4000 after 2000 to settle
        val checks = runs.map { run ->
            (3L..7L)
                .toList()
                .parallelStream()
                .map { seed ->
                    val pl = ParticleLenia.random(
                        40,
                        side = 12.0 * Math.sqrt(0.2),
                        seed = seed,
                        params = genome.params(run.best!!.genome),
                    )
                    pl.stepTimes(2000)
                    seed to [pl.measure(4000), pl.measure(4000)]
                }
                .toList()
        }
        runs to checks
    }
    val table = StringBuilder(
        "| | best fitness | muK | sigmaK | muG | sigmaG | cRep |\n| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n",
    )
    for (run in runs) {
        val p = genome.params(run.best!!.genome)
        table.append(
            "| %s | %.4f | %.3f | %.3f | %.3f | %.3f | %.3f |\n"
                .format(run.label, run.bestFitness, p.muK, p.sigmaK, p.muG, p.sigmaG, p.cRep),
        )
    }
    table.append("\n| best of | seed | steps 2000–6000 | steps 6000–10000 | heading drift | spread |\n")
    table.append("| --- | ---: | --- | --- | ---: | ---: |\n")
    for ((run, rows) in runs.zip(checks)) for ((seed, w) in rows) {
        table.append(
            "| %s | %d | %s %.4f | %s %.4f | %.3f | %.2f |\n"
                .format(
                    run.label,
                    seed,
                    w[0].motion.name.lowercase(),
                    w[0].speed,
                    w[1].motion.name.lowercase(),
                    w[1].speed,
                    w[1].headingDrift,
                    w[1].spreadEnd,
                ),
        )
    }
    markdown(table.toString())
    for (run in runs) {
        println(run.summary())
        println("  " + genome.params(run.best!!.genome))
    }
}
```

![two tables in a box's output: the best fitness and rule of random sampling and of evolution, and below, for each arm's best rule and each of seeds 3 to 7, its class and speed over two windows, its heading drift and its spread; four seeds of each arm glide and seed 5 is static for both](assets/particle_lenia/glider-search.png)

Evolution beats the control here: its best rule scores 0.168 against random sampling's 0.132. Both
winners have a growth curve centred on a low `U` (`muG` 0.18 and 0.16) and a narrow one
(`sigmaG` near 0.1), the region the zoo's fast gliders come from, and which none of the sweeps above
reached. On the five held-out seeds both rules glide from four and settle into a still ring from
the fifth (seed 5, spread 5.1 and 5.4). Evolution's gliders are the steadier: 0.103 to 0.169 per
unit time over steps 6000 to 10000, the same speed as in the window before to within 2 %, with a
heading that turns by 0.2 rad or less in 4000 steps. Random sampling's gliders are slower on three
of its four seeds, between 0.023 and 0.057. For comparison, the zoo's 39-particle Tarbosaurolop,
started from the demo's own positions, glides at 0.125.

The creature a seed assembles is sensitive to the rule itself. Rounding the evolved rule to the
three places shown in the table changes which creature some of the seeds settle into, so the
video below takes the rule in full, as the box prints it. It runs seeds 3 and 4 for 8000 steps, each
panel framed on its own creature with the path its centre has travelled.

```diffstatic_runner {data-lang=kotlin video="assets/particle_lenia/glider.mp4" capture="box" open-outputs="true" wait-output="true" wait-output-kind="display" execution="launch" duration="8" fps="20" timeout="600" w="800" embed=true name="particle-lenia-glider-video" alt="two panels, each following 40 white dots: the scattered dots pull together into a small ring of dots with a few more strung along the edge of a bright halo, and the creature then slides steadily across the field, trailing its straight dotted path behind it, 101 and 75 units by step 8000"}
// Evolution's best rule, in full as the search box prints it, from two of the held-out seeds. Each panel is
// framed on its own creature, with the path its centre has travelled.
val rule = ParticleLeniaParams(
    muK = 4.366173229773033,
    sigmaK = 0.6772483357354411,
    wK = 0.030367731514477567,
    muG = 0.17729432366437387,
    sigmaG = 0.1030105021620745,
    cRep = 1.1856752478847217,
)
val tracks = [mutableListOf<Pair<Double, Double>>(), mutableListOf<Pair<Double, Double>>()]

animateEvolution(
    [3L, 4L].map { ParticleLenia.random(40, side = 12.0 * Math.sqrt(0.2), seed = it, params = rule) },
    totalSteps = 8000,
    seconds = 7.0,
    columns = 2,
    cellSide = 360,
    step = { pls ->
        pls.forEachIndexed { i, pl ->
            pl.step()
            if (pl.steps % 40 == 0) tracks[i] += pl.centroid()
        }
        pls
    },
    panels = { pls, n ->
        pls.mapIndexed { i, pl ->
            val (cx, cy) = pl.centroid()
            val (x0, y0) = tracks[i].firstOrNull() ?: (cx to cy)
            "seed %d, step %d, moved %.0f".format(i + 3, n, Math.hypot(cx - x0, cy - y0)) to
                splatDots(
                    pl.render(extent = 30.0, size = 360, dotRadius = 2.0),
                    tracks[i],
                    30.0,
                    cx,
                    cy,
                    dotRadius = 1.0,
                )
        }
    },
)
```

??? aside "Why 40 particles"
    The same search at the article's 200 particles, scored on one seed, returned rules whose
    populations were still spreading out after 10000 steps, and none of them glided from other
    seeds. That result is what led to the two-window, two-seed objective. Forty particles is also the
    size of the zoo's fast gliders, and each evaluation costs a twenty-fifth of one at 200.

## Where to take it

- **A catalogue rather than one winner.** `PopulationSearch.mapElites` keeps the best rule in every
  cell of a grid over behaviour (speed against rim speed, say), which would collect a zoo of our own
  to set beside the demo's.
- **The map on the GPU.** The GPU section's 64 clusters could each carry their own rule, so one
  dispatch would fill the growth-curve map at thousands of particles a cell.
- **Per-particle rules.** Two species with different rules on one field, the article's own
  suggestion for future work.
- **The article's 3-D variant**, whose 256 particles settle into a mushroom-shaped glider
  (`muK` 4.3, `sigmaK` 1.1, `muG` 0.23, `sigmaG` 0.06), and its sonification, in which every particle
  sings at a pitch set by its energy, as loud as it is fast.

## API reference

Implementation and parameter details for the helpers used above:

- [`ParticleLeniaParams`](reference/alife.md#alife-particle-lenia-kt-particleleniaparams)
- [`LeniaMapCell`](reference/alife.md#alife-particle-lenia-motion-kt-leniamapcell)

- [`PopulationSearch.evolve`](reference/search.md#populationsearch-kt--populationsearch--evolve)
- [`PopulationSearch`](reference/search.md#populationsearch-kt-populationsearch)
