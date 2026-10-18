import afield.simulation.creatures.*

/*
The same genetic algorithm with a different world: side-on, under gravity, on a floor at
y = 0. A foot can only grip when its program says so *and* it is on the floor; anything
below the floor is pushed back up. The score is horizontal distance, because a crawler
that only bounces has not gone anywhere. Compare with `water_bug`: only this part changed.
*/
class Floor(val stiffness: Float = 900f, val friction: Float = 40f) : ContactModel {
    override fun place(points: List<Float2>): List<Float2> {
        val lowest = points.minOf { it.y }
        return points.map { Float2(it.x, it.y - lowest) }
    }

    override fun foot(index: Int) = object : ContactModel.Foot {
        var planted: Float2? = null
        override var grip = 0f
        override val anchor get() = planted

        override fun force(step: FootStep): Float2 {
            val g = step.genome
            val p = step.position
            val v = step.velocity
            val onFloor = p.y <= 0.02f
            // The floor: push anything below it back up, damping its fall.
            val up = if (p.y < 0f) -p.y * stiffness - min(v.y, 0f) * friction else 0f
            grip = if (onFloor) step.contact else 0f
            if (grip <= 0.02f) {
                planted = null
                // Sliding contact, or air.
                val slide = if (onFloor) friction else g.freeDrag * 0.1f
                return Float2(-v.x * slide, up - v.y * g.freeDrag * 0.1f)
            }
            val at = planted ?: Float2(p.x, 0f).also { planted = it }
            return Float2((at.x - p.x) * g.footStickiness * grip - v.x * g.footDrag * grip, up)
        }
    }
}

val config = EvolutionConfig(
    populationSize = 28,
    generations = 200,
    minPoints = 5,
    maxPoints = 8,
    targetEdgeCount = 8,
    targetJointActivations = 10,
    alwaysUpFootFraction = 0.5f,
    cyclingFootFraction = 0.5f,
    simulation = CreatureSimulationConfig(
        measuredCycles = 10,
        gravity = -9.8f,
        contact = Floor(),
        fitness = { e -> abs(e.displacement.x) - 2f * e.meanStretchError },
        jointTargetAngleScale = 1.55f,
        jointMotorKp = 1.4f,
        jointMotorKd = 0.22f,
        maxCenterSpeed = 4f,
    ),
)

val controller = EvolutionController()
var champion: CreatureEvaluation? = null
var status = "generation 0"
kotlin.concurrent.thread(isDaemon = true, name = "ground_crawler evolution") {
    CreatureEvolution.evolve(config, controller) { update ->
        champion = update.champion
        status = "generation ${update.generation.generation}: best ${"%.2f".format(update.champion.fitness)}"
    }
}

var i = 0
loop {
    val crawler = champion ?: return@loop
    val c = crawler.frames[i % crawler.frames.size].center
    val floor = fline {
        moveTo(c.x - 5f, 0f)
        lineTo(c.x + 5f, 0f)
    }.apply { color = vec(0.5, 0.45, 0.4, 1.0) }
    showPlane(
        listOf(floor) + crawler.frameLines(i, alignNetMotionToPositiveX = false),
        title = "crawler — $status",
        window = Rect(c.x - 4.0, -1.0, 8.0, 6.0),
        width = 500,
        height = 380,
        yUp = true,
    )
    i++
}.end { controller.requestStop() }
