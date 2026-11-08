// The rotator rule: a narrower kernel ring and weaker repulsion than the article's defaults.
val rule = ParticleLeniaParams(sigmaK = 0.6, cRep = 0.3)
val track = mutableListOf<Pair<Double, Double>>()

animateEvolution(
    ParticleLenia.random(100, side = 12.0 * Math.sqrt(0.5), seed = 1L, params = rule),
    totalSteps = 12000,
    seconds = 7.0,
    cellSide = 400,
    step = { pl ->
        pl.step()
        if (pl.steps % 50 == 0) track += pl.centroid()
        pl
    },
    panels = { pl, n ->
        val img = pl.render(extent = 24.0, size = 300, cx = 0.0, cy = 0.0)
        // the centroid's path so far, as small dots
        ["step %d".format(n) to splatDots(img, track, 24.0, 0.0, 0.0, dotRadius = 1.0)]
    },
)
