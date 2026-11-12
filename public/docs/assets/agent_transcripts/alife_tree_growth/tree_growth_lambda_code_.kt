// Apical control, live: drag the slider and the same seed regrows for 30 seasons under that lambda.
// 0.5 sends growth resource where the light is; above it the leader wins, below it the side branches.
fun regrow(lam: Double) {
    val t = TreeGrowth(params = TreeGrowthParams(lambda = lam), seed = 4L).stepTimes(30)
    showImg(
        montage(
            [
                "lambda = " + lam.f2 + ": " + t.nodeCount + " metamers" to t.render(TreeView.STRUCTURE, 4),
                "bud light" to t.render(TreeView.LIGHT, 4),
            ],
            columns = 2,
            cellSide = 480,
        ),
    )
}
slider("apical control λ", min = 0.40, max = 0.66, step = 0.01, initialValue = 0.5) { regrow(it) }
displayOpen("controls")
regrow(0.5)
