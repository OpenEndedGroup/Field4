/*
Fitness is a weighted mean of trapezoids over the set's statistics. The sliders move
three of them and the time scale; releasing one re-runs the search from the same seed.
The duration target stretches with the time scale, so the same grammars win, slower or faster.
*/
var medianPitch = 62.0
var countCentre = 80.0
var polyWeight = 1.0
var timeScale = 1.0

fun terms() = [
    GrainFitnessTerm(
        GrainStat.COUNT,
        Trapezoid(countCentre * 0.15, countCentre * 0.5, countCentre * 1.5, countCentre * 5),
    ),
    GrainFitnessTerm(
        GrainStat.MEDIAN_PITCH,
        Trapezoid(medianPitch - 14, medianPitch - 4, medianPitch + 4, medianPitch + 16),
    ),
    GrainFitnessTerm(GrainStat.PITCH_SPREAD, Trapezoid(1.0, 4.0, 8.0, 16.0)),
    GrainFitnessTerm(
        GrainStat.DURATION,
        Trapezoid(4.0 * timeScale, 12.0 * timeScale, 30.0 * timeScale, 60.0 * timeScale),
    ),
    GrainFitnessTerm(GrainStat.POLYPHONY, Trapezoid(1.0, 1.8, 3.5, 8.0), weight = polyWeight),
    GrainFitnessTerm(GrainStat.MODE_SHARE, Trapezoid(0.0, 0.0, 0.3, 0.6)),
]

fun search() {
    val run = PopulationSearch.evolve(
        grainLSystemGenome(),
        grainSetObjective(terms(), turtle = GrainTurtle(timeScale = timeScale)),
        budget = 300,
        seed = 7L,
        crossoverRate = 0.3,
        immigrantRate = 0.1,
        label = "grains",
    )
    val best = decodeGrains(run.best!!.genome, 3, GrainTurtle(timeScale = timeScale))
    display("main", run.summary() + "<br>" + grainRule(run.best!!.genome) + "<br>" + GrainSetStats(best))
    showImg(fitnessTraceImg(run.trace), channel = "trace", caption = "best-so-far fitness against evaluations, 0 to 1")
    showImg(grainScoreImg(best, 640, 220), channel = "score", caption = "the best set")
    display("audio", renderGrains(best).wav())
}

slider("median pitch", 40.0, 84.0, 1.0, medianPitch, channel = "pitch target") {
    medianPitch = it
    search()
}
slider("grain count", 10.0, 300.0, 5.0, countCentre, channel = "count target") {
    countCentre = it
    search()
}
slider("polyphony weight", 0.0, 4.0, 0.1, polyWeight, channel = "polyphony weight") {
    polyWeight = it
    search()
}
slider("time scale", 0.125, 2.0, 0.025, timeScale, channel = "time scale") {
    timeScale = it
    search()
}
search()
