// The recording: Tidal's eleven arpy samples back to back, a rising scale.
val arpy = DirtRender.requirePinnedLibrary().family("arpy")!!
val notes = (0 until arpy.size).map { arpy.sample(it)!! }
val rate = notes[0].sampleRate.toInt()
val recording = notes.flatMap { it.left.asList() }.toFloatArray()

// Read it as grains, and centre a trapezoid on each of its statistics.
val heard = grainsFromAudio(recording, rate, minHz = 100.0)
val target = GrainSetStats(heard)
println("recording: $target")
val terms = grainTermsAround(target)

fun search(alphabet: String, seed: Long) = PopulationSearch.evolve(
    grainLSystemGenome(alphabet = alphabet),
    grainSetObjective(terms),
    budget = 600,
    seed = seed,
    crossoverRate = 0.3,
    immigrantRate = 0.1,
    label = "toward arpy",
)

// The plain alphabet against the one with < > * / l r, over eight seeds each.
for (alphabet in [GRAIN_ALPHABET, GRAIN_ALPHABET_EXTENDED]) {
    val bests = (1L..8L).map { search(alphabet, it).bestFitness }
    println("%-14s mean best %.3f, worst %.3f".format(alphabet, bests.average(), bests.min()))
}

val run = search(GRAIN_ALPHABET_EXTENDED, 7L)
val best = decodeGrains(run.best!!.genome, 3)
println(grainRule(run.best!!.genome))
println("evolved:   " + GrainSetStats(best))
showImg(grainScoreImg(heard, 640, 160), channel = "recording", caption = "the recording, read as grains")
showImg(grainScoreImg(best, 640, 160), channel = "score", caption = "the evolved set")
// Played from the first arpy note, which the detector heard at this pitch.
display("audio", renderGrains(best, source = notes[0].left, sourceRate = rate, sourcePitch = heard[0].pitch).wav())
