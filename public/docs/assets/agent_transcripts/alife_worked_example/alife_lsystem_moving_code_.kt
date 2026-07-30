// LAUNCH this box rather than merely running it (undo stops it). The last expression is a
// `Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
// animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
// the box's output every displayed frame, then holds the final frame forever.
// `rasterizeFLinesFramed` takes its framing from the FINISHED curve. Plain `rasterizeFLines` fits
// the raster to whatever it is handed, so a growing prefix rescales every frame: the curve appears
// to stay the same size while the world shrinks around it, and the growth becomes invisible.
fun drawn(koch: List<FLine>, plant: List<FLine>, f: Double): List<Pair<String, Img>> {
    val pct = (f * 100).toInt()
    return listOf(
        "Koch gen 4: " + pct + "% drawn" to rasterizeFLinesFramed(flinePrefix(koch, f), koch, 420, 420),
        "plant gen 5: " + pct + "% drawn" to rasterizeFLinesFramed(flinePrefix(plant, f), plant, 420, 420)
    )
}

// Animated by DRAWING, not by cutting between derivation depths: an L-system's turtle walks in an
// order, and `flinePrefix` truncates the emitted FLines at a fraction of their segment count in
// exactly that order. 240 ticks over 140 displayed frames.
val koch = drawLSystem(LSYSTEM_KOCH_SNOWFLAKE, 4, TurtleParams(angle = Math.PI / 3.0, step = 1.0))
val plant = drawLSystem(LSYSTEM_STOCHASTIC_PLANT, 5, TurtleParams(angle = Math.toRadians(24.0), step = 4.0), seed = 1L)
val ticks = 240
animateEvolution(
    Unit,
    totalSteps = ticks,
    seconds = 7.0,
    columns = 2,
    cellSide = 420,
    step = { it },
    panels = { _, n -> drawn(koch.lines, plant.lines, n.toDouble() / ticks) }
)
