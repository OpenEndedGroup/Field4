val world = flowLeniaWorld(
    96,
    64,
    [FlowLeniaPresets.rings(), FlowLeniaPresets.cell()],
    positions = [38.0 to 32.0, 58.0 to 32.0],
    mixing = FlowLeniaMixing.SAMPLED,
)

animateEvolution(
    world,
    totalSteps = 1200,
    seconds = 9.0,
    columns = 2,
    cellSide = 384,
    step = { it.step() },
    panels = { w, n ->
        [
            ("step " + n) to w.speciesImg().upscaleNearest(4),
            ("rings %.0f, cell %.0f".format(w.speciesMass(0), w.speciesMass(1))) to w.massPlot(384, 256),
        ]
    },
)
