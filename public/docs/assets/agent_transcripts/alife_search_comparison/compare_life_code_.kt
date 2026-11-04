val soups = [randomSoup(48, 48, density = 0.35, seed = 101L), randomSoup(48, 48, density = 0.12, seed = 202L)]
val c = compareSearch(
    LifeRuleGenome(radius = 2),
    caEdgeOfChaosObjective(soups, warmup = 20, scored = 30),
    budget = 120,
    seeds = [1L, 2L, 3L, 4L],
    label = "life rule, edge of chaos",
)
println(c.table() + c.budgetTable([30, 60, 120]))
showImg(c.plot(360, 260))
