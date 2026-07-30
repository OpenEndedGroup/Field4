// LAUNCH this box rather than merely running it (undo stops it). The last expression is a
// `Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
// animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
// the box's output every displayed frame, then holds the final frame forever.
// The contour panel is framed by the FIELD's extent -- two invisible single-node FLines at the
// corners of the 96x96 sample grid, in the contour extractor's own pixel coordinates. Framed by
// the contours' own bounds instead, the early frames (two loops in one corner) would be drawn
// zoomed right in and the whole figure would zoom out as the pattern spread.
val fieldFrame = listOf(FLine().moveTo(0, 0), FLine().moveTo(95, 95))
fun bridge(s: GrayScottState, n: Int): List<Pair<String, Img>> {
    val loops = s.v.isoContourFLines(0.25, cells = 96)
    return listOf(
        "v field, step " + n to s.v.colormap("magma", 0f, 0.5f).upscaleNearest(4),
        "iso v=0.25: " + loops.size + " loops" to rasterizeFLinesFramed(loops, fieldFrame, 384, 384)
    )
}

// A reaction-diffusion field and its iso-contour FLines, re-extracted afresh every frame -- so the
// geometry tracks the field instead of being a snapshot of it. 5000 steps over 140 displayed
// frames: 36 reaction-diffusion steps AND one marching-squares pass per frame.
animateEvolution(
    grayScottSeed(96, 96, patches = 5, patchSize = 12, seed = 4L),
    totalSteps = 5000,
    seconds = 7.0,
    columns = 2,
    cellSide = 384,
    step = { it.step(GRAY_SCOTT_SPOTS) },
    panels = { s, n -> bridge(s, n) }
)
