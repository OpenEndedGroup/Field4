// LAUNCH this box rather than merely running it (undo stops it). The last expression is a
// `Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
// animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
// the box's output every displayed frame, then holds the final frame forever.
// The autocatalyst field on a FIXED 0..0.5 magma range. Auto-ranging per frame is what makes an
// animation of a growing pattern appear to stand still, and it is the single easiest way to turn
// a reaction-diffusion clip into a lie.
fun view(s: GrayScottState, n: Int) =
    listOf("maze - step " + n + " of 6000" to s.v.colormap("magma", 0f, 0.5f).upscaleNearest(3))

// The `maze` preset, the same 6000 steps the figure above measures, over 140 displayed frames --
// but NOT 43 steps per frame. `ramp = 40` makes the last displayed frame advance forty times as
// many steps as the first, which is the schedule a log-time process needs: the mid-point of the
// clip is step 819 rather than step 3000. This is the number to change if you disagree.
animateEvolution(
    grayScottSeed(128, 128, patches = 6, patchSize = 14, seed = 11L),
    totalSteps = 6000,
    seconds = 7.0,
    ramp = 40.0,
    cellSide = 420,
    step = { it.step(GRAY_SCOTT_MAZE) },
    panels = { s, n -> view(s, n) }
)
