// LAUNCH this box rather than merely running it (undo stops it). The last expression is a
// `Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
// animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
// the box's output every displayed frame, then holds the final frame forever.
// Both boards are integer-upscaled to the same 384 pixels, so a cell is a block you can count:
// twenty-four pixels for the glider's 16-cell torus, six for the R-pentomino's 64-cell walled
// board. Small boards on purpose -- see the note above this clip, which measures why.
fun boards(g: IntGrid, r: IntGrid, n: Int) = listOf(
    "glider, gen " + n to g.toImg(2).upscaleNearest(24),
    "R-pent " + n + ": " + r.population() + " live" to r.toImg(2).upscaleNearest(6)
)

// 400 generations over 200 displayed frames: TWO generations per frame. Slower than every other
// clip on this page on purpose -- the glider's period is FOUR generations, and a spaceship stepped
// nine generations at a time is a strobe rather than a spaceship.
animateEvolution(
    LifePatterns.glider.centeredOn(16, 16) to
        LifePatterns.rPentomino.centeredOn(64, 64, wrap = false),
    totalSteps = 400,
    seconds = 7.0,
    framesTarget = 200,
    columns = 2,
    cellSide = 384,
    step = { (g, r) -> g.step(LIFE) to r.step(LIFE) },
    panels = { (g, r), n -> boards(g, r, n) }
)
