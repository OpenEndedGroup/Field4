val c = compareSearch(
    LSystemGenome(),
    lSystemDimensionObjective(),
    budget = 120,
    seeds = [1L, 2L, 3L, 4L],
    label = "l-system, dimension 1.7",
)
println(c.table() + c.budgetTable([30, 60, 120]))
showImg(c.plot(360, 260))
