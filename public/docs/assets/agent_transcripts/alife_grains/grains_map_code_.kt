// MAP-Elites over pitch spread and polyphony: the best set of each kind, not one winner.
val axes = [GrainStat.PITCH_SPREAD, GrainStat.POLYPHONY]
val map = PopulationSearch.mapElites(
    grainLSystemGenome(),
    grainSetObjective(behaviour = axes),
    budget = 800,
    axes = [0.0..12.0, 0.0..6.0],
    bins = [4, 4],
    seed = 1L,
)
println(map)

// One score per cell: spread grows to the right, polyphony upward.
val panels =
    (3 downTo 0).flatMap { row ->
        (0..3).map { col ->
            val elite = map.elites[[col, row]]
            if (elite == null) {
                "empty" to Img.zeros(240, 120, 3)
            } else {
                val grains = decodeGrains(elite.genome, 3)
                val st = GrainSetStats(grains)
                "%.1f st  x%.1f  %.2f".format(st.pitchSpread, st.polyphony, elite.fitness) to
                    grainScoreImg(grains, 240, 120)
            }
        }
    }
showImg(
    montage(panels, columns = 4, cellSide = 240, textScale = 2),
    caption = "pitch spread across, polyphony up; trapezoid fitness in each label",
)
