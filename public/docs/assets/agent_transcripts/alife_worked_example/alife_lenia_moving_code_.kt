// LAUNCH this box rather than merely running it (undo stops it). The last expression is a
// `Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
// animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
// the box's output every displayed frame, then holds the final frame forever.
// inferno on a FIXED 0..1 scale, and the mean field recomputed every frame so the phase verdict
// is measured while you watch. See the note below this clip: an auto-ranged SATURATION field
// renders black and becomes indistinguishable from death, which cost this family a figure once.
fun fields(x: Img, y: Img, n: Int) = listOf(
    "mu=0.06 " + n + " mean " + "%.3f".format(x.mean()) to x.colormap("inferno", 0f, 1f).upscaleNearest(4),
    "mu=0.14 " + n + " mean " + "%.3f".format(y.mean()) to y.colormap("inferno", 0f, 1f).upscaleNearest(4)
)

// Same seed, same ring kernel, one number different: where the growth window is centred.
// 240 Lenia steps over 140 displayed frames -- but geometrically, not two per frame: `ramp = 25`
// spends the first half of the clip on the first 40 steps, because that is where the blob decides
// what it is going to be. Everything after is maintenance.
val a = LeniaPresets.ring(0.06, sigma = 0.017, radius = 8, dt = 0.12)
val b = LeniaPresets.ring(0.14, sigma = 0.017, radius = 8, dt = 0.12)
animateEvolution(
    leniaSeed(96, 96, a, blobRadii = 1.6, seed = 9L) to leniaSeed(96, 96, b, blobRadii = 1.6, seed = 9L),
    totalSteps = 240,
    seconds = 7.0,
    ramp = 25.0,
    columns = 2,
    cellSide = 384,
    step = { (x, y) -> x.leniaTimes(a, 1) to y.leniaTimes(b, 1) },
    panels = { (x, y), n -> fields(x, y, n) }
)
