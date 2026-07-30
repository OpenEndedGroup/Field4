// LAUNCH this box rather than merely running it (undo stops it). The last expression is a
// `Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
// animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
// the box's output every displayed frame, then holds the final frame forever.
// Both panels on ONE derived range, not on each other's maxima: a cell an agent occupies every
// step accumulates `deposit` and keeps `decay` of what it had, so it converges to
// deposit / (1 - decay) = 62.5. The first cut of this clip guessed 30 and clipped the network to
// flat cream. The caption's number is `topShare(0.05)`, recomputed every frame.
val p = PhysarumParams()
val ceiling = (p.deposit / (1.0 - p.decay)).toFloat()
fun trails(a: PhysarumSwarm, b: PhysarumSwarm, n: Int) = listOf(
    "sensing " + n + "  " + "%.3f".format(a.trail.topShare(0.05)) to a.trail.colormap("magma", 0f, ceiling),
    "blind " + n + "  " + "%.3f".format(b.trail.topShare(0.05)) to b.trail.colormap("magma", 0f, ceiling)
)

// Sensing and blind, from the same seed, stepped together: 400 steps over 140 displayed frames,
// so three deposit-sense-move rounds per frame. `step` takes its rng explicitly, so each
// population gets its own stream -- exactly what `stepTimes(p, 400, seed = 6L)` does internally,
// which is why these panels land on the same numbers as the figure above.
val rngA = kotlin.random.Random(6L)
val rngB = kotlin.random.Random(6L)
animateEvolution(
    PhysarumSwarm.disc(256, 256, 6000, 0.55, seed = 6L) to
        PhysarumSwarm.disc(256, 256, 6000, 0.55, seed = 6L),
    totalSteps = 400,
    seconds = 7.0,
    columns = 2,
    cellSide = 300,
    step = { (a, b) -> a.step(p, rngA) to b.step(p.copy(sensing = false), rngB) },
    panels = { (a, b), n -> trails(a, b, n) }
)
