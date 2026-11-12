// One tree, 30 seasons. Each step() is one season: shadow, light, Borchert-Honda allocation,
// growth and shedding.
val tree = TreeGrowth(seed = 4L)
val seasons = [2, 5, 10, 20, 30]
val strip = ArrayList<Pair<String, Img>>()
var views: List<Pair<String, Img>> = []
for (s in 1..30) {
    tree.step()
    if (s in seasons)
        strip.add("season " + s + ": " + tree.nodeCount + " metamers" to tree.render(TreeView.STRUCTURE, 3))
    if (s == 20) {
        views = [
            "season 20: bud light" to tree.render(TreeView.LIGHT, 3),
            "resource flow (log)" to tree.render(TreeView.RESOURCE, 3),
            "shadow grid" to tree.render(TreeView.SHADOW, 3),
        ]
    }
}
println(tree)
println("a tree (one root, n-1 edges, acyclic): " + tree.isTree())

// Borchert-Honda only ever splits, so what reaches the buds is what the root was given.
println(
    "worst resource conservation error over 30 seasons: " + "%.1e".format(tree.history.maxOf { it.conservationError }),
)
val last = tree.history.last()
println(
    "season 30: " +
        last.buds +
        " buds read " +
        last.baseLight.f6 +
        " units of light; root gets " +
        last.baseResource.f6,
)

// Apical control: the same seed, four values of lambda.
val lambdas = [0.44, 0.5, 0.56, 0.62]
val crowns = lambdas.map { lam ->
    val t = TreeGrowth(params = TreeGrowthParams(lambda = lam), seed = 4L).stepTimes(30)
    val xs = (0 until t.nodeCount).map { t.x(it) }
    val ys = (0 until t.nodeCount).map { t.y(it) }
    println(
        "λ = " +
            lam +
            ": " +
            t.nodeCount +
            " metamers, crown " +
            (xs.max() - xs.min()).f0 +
            " wide, " +
            (ys.max() - ys.min()).f0 +
            " tall",
    )
    "lambda = " + lam to t.render(TreeView.STRUCTURE, 3)
}

showImg(montage(strip + views + crowns, columns = 5, cellSide = 300))
