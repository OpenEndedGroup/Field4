// Evolution's best rule, in full as the search box prints it, from two of the held-out seeds. Each panel is
// framed on its own creature, with the path its centre has travelled.
val rule = ParticleLeniaParams(
    muK = 4.366173229773033,
    sigmaK = 0.6772483357354411,
    wK = 0.030367731514477567,
    muG = 0.17729432366437387,
    sigmaG = 0.1030105021620745,
    cRep = 1.1856752478847217,
)
val tracks = [mutableListOf<Pair<Double, Double>>(), mutableListOf<Pair<Double, Double>>()]

animateEvolution(
    [3L, 4L].map { ParticleLenia.random(40, side = 12.0 * Math.sqrt(0.2), seed = it, params = rule) },
    totalSteps = 8000,
    seconds = 7.0,
    columns = 2,
    cellSide = 360,
    step = { pls ->
        pls.forEachIndexed { i, pl ->
            pl.step()
            if (pl.steps % 40 == 0) tracks[i] += pl.centroid()
        }
        pls
    },
    panels = { pls, n ->
        pls.mapIndexed { i, pl ->
            val (cx, cy) = pl.centroid()
            val (x0, y0) = tracks[i].firstOrNull() ?: (cx to cy)
            "seed %d, step %d, moved %.0f".format(i + 3, n, Math.hypot(cx - x0, cy - y0)) to
                splatDots(
                    pl.render(extent = 30.0, size = 360, dotRadius = 2.0),
                    tracks[i],
                    30.0,
                    cx,
                    cy,
                    dotRadius = 1.0,
                )
        }
    },
)
