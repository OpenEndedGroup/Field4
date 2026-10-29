/*
LAUNCH this box rather than merely running it (undo stops it). The last expression is a
Frames, and a Frames that is only built never runs.

Orbium, a Lenia lifeform from Bert Chan's catalogue, gliding beside the same cells with the
growth curve's centre mu moved from 0.15 to 0.12, where they burst and fill the torus.
*/
val orbium = LeniaPresets.orbium()

fun fields(a: Img, b: Img, n: Int) = [
    "Orbium, step %d: mass %.4f".format(n, a.mean()) to a.colormap("inferno", 0f, 1f).upscaleNearest(6),
    "mu 0.12, step %d: mass %.4f".format(n, b.mean()) to b.colormap("inferno", 0f, 1f).upscaleNearest(6),
]

// The same cells, with the growth curve centred at 0.12 instead of 0.15.
val lowMu = leniaRuleFromCatalogue(radius = 13, t = 10.0, mu = 0.12, sigma = 0.015)

// At Orbium's 0.62 cells per step, 200 steps is about two laps of the 64-cell torus.
animateEvolution(
    orbium.seed(64, 64) to orbium.seed(64, 64),
    totalSteps = 200,
    seconds = 7.0,
    columns = 2,
    cellSide = 384,
    step = { (a, b) -> a.leniaStep(orbium.rule) to b.leniaStep(lowMu) },
    panels = { (a, b), n -> fields(a, b, n) },
)
