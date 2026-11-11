// Listen to the six children, type the indices of the ones you like (e.g. "1 4"),
// press return: they survive and the rest are their crossovers, each mutated once.
val breeder = GrainBreeder(seed = 1L)

fun show() {
    displayClearAll()
    val scores = breeder.scores()
    val listing =
        breeder.population.withIndex().joinToString("<br>") { (i, g) ->
            "%d: %s &nbsp; trapezoid fitness %.2f".format(i, grainRule(g), scores[i])
        }
    display("main", "generation ${breeder.generation}<br>" + listing)
    for ((i, g) in breeder.population.withIndex()) display("child $i", renderGrains(decodeGrains(g, 3)).wav())
    stringInput("parents (e.g. 1 4)") { s ->
        val picks = s.split(" ", ",").mapNotNull { it.trim().toIntOrNull() }
        if (picks.isNotEmpty()) {
            breeder.breed(picks)
            show()
        }
    }
}

show()
