// LAUNCH this box rather than merely running it (undo stops it). The last expression is a
// `Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
// animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
// the box's output every displayed frame, then holds the final frame forever.
// Compute the four space-time diagrams once, in full: 257 cells wide, 128 generations tall.
val seedRow = IntArray(257) { if ((it * 7919) % 3 == 0) 1 else 0 }
val diagrams = listOf(
    "rule 90" to elementaryCa(90, 257, 128).toImg(2),
    "rule 30" to elementaryCa(30, 257, 128).toImg(2),
    "rule 110" to elementaryCa(110, 257, 128, IntArray(257) { if (it % 13 == 0) 1 else 0 }).toImg(2),
    "rule 184" to elementaryCa(184, 257, 128, seedRow).toImg(2)
)

// 128 generations over 128 displayed frames: ONE row of every diagram per frame. The upscale comes
// AFTER the reveal, so `n` counts generations rather than pixels -- and `rowsRevealed` keeps the
// full height, so montage frames each panel identically instead of rescaling a growing image.
animateEvolution(
    Unit,
    totalSteps = 128,
    seconds = 6.0,
    framesTarget = 128,
    columns = 2,
    cellSide = 520,
    step = { it },
    panels = { _, n ->
        diagrams.map { (name, img) -> name + ", gen " + n to img.rowsRevealed(n).upscaleNearest(2) }
    }
)
