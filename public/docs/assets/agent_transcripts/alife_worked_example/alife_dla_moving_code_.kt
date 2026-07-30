// LAUNCH this box rather than merely running it (undo stops it). The last expression is a
// `Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
// animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
// the box's output every displayed frame, then holds the final frame forever.
// Grow the cluster once, then replay `Aggregate.points` -- which IS the order the particles stuck
// in -- at a uniform rate over seven seconds.
val dla = dlaCluster(300, 300, particles = 6000, seed = 2L)

// The crop is the colour-range discipline in geometry: taken ONCE, from the finished cluster, never
// from the frame -- a crop that tracked the growing prefix would zoom out as the cluster grew and
// the screening this clip is about would be the one thing you could not see.
//
// Per AXIS rather than a square around maxRadius: a DLA cluster is anisotropic, and maxRadius is set
// by its single longest arm, so a square crop leaves a third of the panel permanently empty.
val pad = 2
val x0 = (dla.points.minOf { it.x }.toInt() - pad).coerceAtLeast(0)
val y0 = (dla.points.minOf { it.y }.toInt() - pad).coerceAtLeast(0)
val cw = (dla.points.maxOf { it.x }.toInt() + pad + 1 - x0).coerceAtMost(300 - x0)
val ch = (dla.points.maxOf { it.y }.toInt() + pad + 1 - y0).coerceAtMost(300 - y0)

// `arrivalPrefixImg` fixes the colour ramp to the FULL cluster's particle count, so a branch keeps
// the colour it arrived with; `arrivalOrderImg` normalises by its own count and would re-map the
// newest particle to the top of turbo on every frame.
fun cluster(dla: Aggregate, n: Int) =
    listOf("particles " + n + " of " + dla.points.size to
        dla.arrivalPrefixImg(n).crop(x0, y0, cw, ch).colormap("turbo", 0f, 1f).upscaleNearest(3))

// Why the crop: a cluster is a fixed amount of ink, and how legible its growth is depends entirely on
// how much of the frame that ink occupies. Note the particle count in the first line -- `particles`
// is a ceiling, not a promise, and this cluster stopped well short of the 6000 asked for.
println(dla)
println("cropped to " + cw + " x " + ch + " of 300 x 300, so " +
    "%.1f".format(100.0 * dla.points.size / (cw * ch)) + "% of the panel is ever lit, against " +
    "%.1f".format(100.0 * dla.points.size / 90000.0) + "% uncropped")

animateEvolution(
    Unit,
    totalSteps = dla.points.size,
    seconds = 7.0,
    cellSide = 660,
    step = { it },
    panels = { _, n -> cluster(dla, n) }
)
