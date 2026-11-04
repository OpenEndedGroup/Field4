val c = compareSearch(
    LeniaGenome(radius = 6, dt = 0.1, shells = 3),
    leniaSolitonObjective(width = 48, height = 48, steps = 90, initialSeed = 5L, driftScale = 8.0),
    budget = 60,
    seeds = [1L, 2L, 3L],
    label = "lenia, soliton",
)
println(c.table())
showImg(c.plot(360, 260))
