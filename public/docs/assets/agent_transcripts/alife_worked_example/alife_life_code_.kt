// Conway's Life. Every claim here is about an object that predates this code, stated as an
// integer-grid equality rather than as an observation about a picture.
val glider = LifePatterns.glider.centeredOn(40, 40)
println("glider, 4 generations: rigid translation = " + translationBetween(glider, glider.stepTimes(LIFE, 4)))
println("glider, 8 generations: rigid translation = " + translationBetween(glider, glider.stepTimes(LIFE, 8)))

// The control: one birth count away and the same seed is not a spaceship at all.
println(
    "same seed under B34/S23, 4 generations: " + translationBetween(glider, glider.stepTimes(lifeRule("B34/S23"), 4)),
)

val block = LifePatterns.block.centeredOn(20, 20)
println("block is bit-identical after one step: " + block.step(LIFE).sameCellsAs(block))
println("blinker's detected period: " + LifePatterns.blinker.centeredOn(20, 20).runUntilCycle(LIFE, 20).period)
println("toad's detected period: " + LifePatterns.toad.centeredOn(20, 20).runUntilCycle(LIFE, 20).period)

/*
The unbounded R-pentomino reaches a peak of 319 cells at generation 821 and settles to
116 cells from generation 1103, including six gliders (30 live cells) still in flight.
A 620-wide board leaves room for their travel. The measurements below report population,
peak and final left clearance; final bounds cannot rule out earlier edge contact.
*/
var g = LifePatterns.rPentomino.centeredOn(620, 620, wrap = false)
var peak = 0
var peakAt = 0
for (gen in 1..1103) {
    g = g.step(LIFE)
    val pop = g.population()
    if (pop > peak) {
        peak = pop
        peakAt = gen
    }
}
val b = g.liveBounds()!!
println("R-pentomino peak population " + peak + " at generation " + peakAt)
println(
    "population at generation 1103 = " + g.population() + ", live cells " + b[0] + " clear of the board edge",
)

showImg(
    montage(
        (0..4).map {
            "gen " + it to LifePatterns.glider.centeredOn(16, 16).stepTimes(LIFE, it).toImg(2).upscaleNearest(12)
        },
        columns = 5,
        cellSide = 200,
    ),
    caption = "one glider, five generations: the same shape displaced by (1, 1)",
)
