// LAUNCH this box rather than merely running it (undo stops it). The last expression is a
// `Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
// animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
// the box's output every displayed frame, then holds the final frame forever.
// One pixel per domain unit, with an 8-unit motion streak behind each agent -- long enough to join
// up at nine units of travel per frame. `order()` reads the velocity array, so neither choice
// touches what is measured; the number in each caption is computed fresh every frame.
fun flocks(a: BoidFlock, b: BoidFlock, n: Int) = listOf(
    "flock " + n + ": order " + "%.2f".format(a.order()) to a.toImg(1.0, tail = 8.0),
    "no align " + n + ": " + "%.2f".format(b.order()) to b.toImg(1.0, tail = 8.0)
)

// The same initial state, twice, stepped together -- so the ONLY difference between the two panels
// is the alignment weight. 900 steps over 140 displayed frames: six or seven steps per frame, and
// no ramp -- a flock's rate of change is roughly constant, which is exactly when linear is right.
val params = BoidParams()
val blind = params.copy(alignmentWeight = 0.0)
val start = BoidFlock.random(400, 260.0, 260.0, speed = 1.4, seed = 4L)
animateEvolution(
    start.copy() to start.copy(),
    totalSteps = 900,
    seconds = 7.0,
    columns = 2,
    cellSide = 300,
    step = { (a, b) -> a.step(params); b.step(blind); a to b },
    panels = { (a, b), n -> flocks(a, b, n) }
)
