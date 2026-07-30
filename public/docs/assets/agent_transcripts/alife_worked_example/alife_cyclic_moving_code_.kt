// LAUNCH this box rather than merely running it (undo stops it). The last expression is a
// `Frames`, and a `Frames` that is only built never runs -- run this box and it prints nothing and
// animates nothing. Launched, it steps the system on the schedule below and pushes a montage PNG to
// the box's output every displayed frame, then holds the final frame forever.
// One palette per rule, FIXED -- it is a function of the rule's state count, never of the frame,
// which is what makes generation 0 and generation 400 the same picture of the same thing. Three
// pixels to a cell, nearest-neighbour, so the bands are countable and the encoder sees flat blocks.
val pal = cyclicPalette(CYCLIC_LABYRINTH.states)
val exPal = cyclicPalette(CYCLIC_EXCITABLE.states)
fun soups(a: IntGrid, b: IntGrid, n: Int) = listOf(
    "labyrinth, gen " + n to a.toImg(pal).upscaleNearest(3),
    "excitable, gen " + n to b.toImg(exPal).upscaleNearest(3)
)

// Two cyclic rules from the same kind of soup, stepped together so the schedule is shared:
// 400 generations over 130 displayed frames, so a little over THREE generations per frame.
val rule = CYCLIC_LABYRINTH
val ex = CYCLIC_EXCITABLE
animateEvolution(
    IntGrid.random(96, 96, states = rule.states, seed = 7L) to
        IntGrid.random(96, 96, states = ex.states, seed = 7L),
    totalSteps = 400,
    seconds = 6.5,
    framesTarget = 130,
    columns = 2,
    cellSide = 288,
    step = { (a, b) -> a.step(rule) to b.step(ex) },
    panels = { (a, b), n -> soups(a, b, n) }
)
