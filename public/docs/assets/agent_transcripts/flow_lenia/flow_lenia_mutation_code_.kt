val species = [FlowLeniaPresets.wanderers(), FlowLeniaPresets.rings(), FlowLeniaPresets.cell()]
val neutral = flowLeniaWorld(192, 192, species, mixing = FlowLeniaMixing.SAMPLED, scale = 2)
neutral.mutation = FlowLeniaMutation(rate = 0.05, radius = 12.0, scale = 0.0)
val mutating = flowLeniaWorld(192, 192, species, mixing = FlowLeniaMixing.SAMPLED, scale = 2)
mutating.mutation = FlowLeniaMutation(rate = 0.05, radius = 12.0, scale = 0.3)

fun masses(w: FlowLeniaWorld) = w.speciesMasses().joinToString(" ") { it.f0 }

animateEvolution(
    neutral to mutating,
    totalSteps = 2400,
    seconds = 9.0,
    columns = 2,
    cellSide = 384,
    step = { (a, b) ->
        a.step()
        b.step()
        a to b
    },
    panels = { (a, b), n ->
        [
            ("neutral " + masses(a)) to vstack(a.lineageImg().resize(384, 384), a.massPlot(384, 128)),
            ("size 0.3 " + masses(b)) to vstack(b.lineageImg().resize(384, 384), b.massPlot(384, 128)),
        ]
    },
)
