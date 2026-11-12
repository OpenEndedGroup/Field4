// Thirty seasons over seven seconds. TreeGrowth is mutable and step() returns it, so it is the
// `step` here directly. render() frames the whole grid, so the tree grows inside a fixed frame.
animateEvolution(
    TreeGrowth(seed = 4L),
    totalSteps = 30,
    seconds = 7.0,
    columns = 2,
    cellSide = 450,
    step = { it.step() },
    panels = { t, n ->
        [
            "season " + n + ": " + t.nodeCount + " metamers" to t.render(TreeView.STRUCTURE, 4),
            "bud light, " + t.shedMetamersTotal + " shed" to t.render(TreeView.LIGHT, 4),
        ]
    },
)
