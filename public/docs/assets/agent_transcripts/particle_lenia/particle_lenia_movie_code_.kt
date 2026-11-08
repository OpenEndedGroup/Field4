// 200 particles from a random square, 3000 steps, drawn over the energy field.
animateEvolution(
    ParticleLenia.random(200, side = 12.0, seed = 1L),
    totalSteps = 3000,
    seconds = 7.0,
    cellSide = 400,
    step = { it.step() },
    panels = { pl, n ->
        [
            "step %d   mean E %.3f".format(n, pl.currentEnergy()) to
                pl.render(extent = 24.0, size = 300, cx = 0.0, cy = 0.0),
        ]
    },
)
