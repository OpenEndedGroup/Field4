# Evolving Creatures

`afield.simulation.creatures` evolves small 2D creatures: bodies of points joined by springs, where
every point is a foot that steps on its own cycle and the joints between edges bend to cyclic target
curves. A genetic algorithm breeds the shape, the foot timing and the joint curves together.

What the library does not decide is the world. You write how a foot meets its surroundings (a
`ContactModel`) and what counts as doing well (a `fitness`), and the same search evolves water bugs,
floor crawlers or anything else you can write as a force on a foot.

The [evolving_water_bugs](agent_transcripts.md#evolving_water_bugs) starting point is the live
version of this page: two boxes that evolve on their own thread and animate the current champion.

## A water surface

Seen from above, with no gravity, a water bug stands on the surface film. A foot that touches down
grips the film where it landed and is pulled back toward that spot. A foot that is up just drags
through the water. That rule is one class: `foot(index)` makes one `Foot` per point, so each foot can
remember where it planted, and `force` returns the force for this physics step.

`FootStep.contact` is how firmly the foot's program wants to be down right now, from 0 to 1. The
genome carries the evolved gains: `freeDrag`, `footDrag` and `footStickiness`.

```diffstatic_runner {data-lang=kotlin screenshot="assets/evolving_creatures/one_bug.png" wait="4.0" capture="box" open-outputs="true" w="900" h="460" padding="14"}
import afield.simulation.creatures.*
import kotlin.random.Random

class WaterSurface : ContactModel {
    override fun foot(index: Int) = object : ContactModel.Foot {
        var planted: Float2? = null
        override var grip = 0f
        override val anchor get() = planted

        override fun force(step: FootStep): Float2 {
            val g = step.genome
            grip = step.contact
            if (step.contact <= 0.02f) {
                planted = null
                return step.velocity * -g.freeDrag
            }
            val at = planted ?: step.position.also { planted = it }
            val drag = max(g.freeDrag, g.footDrag * step.contact)
            return (at - step.position) * (g.footStickiness * step.contact) - step.velocity * drag
        }
    }
}

val bug = CreatureEvolution.randomGenome(Random(4), minPoints = 6, maxPoints = 6)
val run = CreatureEvolution.simulate(bug, CreatureSimulationConfig(measuredCycles = 4, contact = WaterSurface()))

showPlaneGrid(
    "start" to run.frameLines(0),
    "half way" to run.frameLines(run.frames.size / 2),
    "end\nmoved %.2f".format(run.netCentroidMotion) to run.frameLines(run.frames.lastIndex),
    columns = 3,
    cellWidth = 280,
    cellHeight = 300,
    yUp = true,
)
```

A random body gets almost nowhere. Its feet are blue while they are up and orange while they grip,
a cross marks where a gripping foot planted, and the yellow line is the path of the body's centre.

## Evolving one

`CreatureEvolution.evolve` runs a whole search and calls you back once per generation with the
generation's statistics and the champion so far. `EvolutionConfig` sets the population and the size
of the bodies; `alwaysUpFootFraction` and `cyclingFootFraction` say how many points are body and how
many must actually step. The fitness here rewards distance, and charges for stretching the springs
and for motor effort.

```diffstatic_runner {data-lang=kotlin screenshot="assets/evolving_creatures/evolved_bug.png" wait="40.0" capture="box" open-outputs="true" w="1000" h="520" padding="14"}
import afield.simulation.creatures.*

:::fold the WaterSurface contact model, as above
class WaterSurface : ContactModel {
    override fun foot(index: Int) = object : ContactModel.Foot {
        var planted: Float2? = null
        override var grip = 0f
        override val anchor get() = planted

        override fun force(step: FootStep): Float2 {
            val g = step.genome
            grip = step.contact
            if (step.contact <= 0.02f) {
                planted = null
                return step.velocity * -g.freeDrag
            }
            val at = planted ?: step.position.also { planted = it }
            val drag = max(g.freeDrag, g.footDrag * step.contact)
            return (at - step.position) * (g.footStickiness * step.contact) - step.velocity * drag
        }
    }
}
:::endfold

val config = EvolutionConfig(
    populationSize = 24,
    generations = 25,
    minPoints = 5,
    maxPoints = 8,
    targetEdgeCount = 8,
    alwaysUpFootFraction = 0.5f,
    cyclingFootFraction = 0.5f,
    simulation = CreatureSimulationConfig(
        measuredCycles = 6,
        contact = WaterSurface(),
        fitness = { e -> e.netCentroidMotion - 2f * e.meanStretchError - 0.05f * e.meanActuationEffort },
    ),
)

val best = mutableListOf<Double>()
val mean = mutableListOf<Double>()
val result = CreatureEvolution.evolve(config) { update ->
    best += update.champion.fitness.toDouble()
    mean += update.generation.meanFitness.coerceAtLeast(-2f).toDouble()
}

val bug = result.best
showSignals("champion" to best, "population mean" to mean, title = "fitness by generation", xTitle = "generation")
showPlane(
    bug.frameLines(bug.frames.lastIndex, trailLength = bug.frames.size),
    channel = "swimmer",
    title = "the champion after ${config.generations} generations",
    caption = "moved %.2f over %d cycles".format(bug.netCentroidMotion, config.simulation.measuredCycles),
    width = 480,
    height = 360,
    yUp = true,
)
```

`result.best` is a `CreatureEvaluation`: the genome that won, its fitness, the measurements the
fitness was computed from (`displacement`, `netCentroidMotion`, `meanStretchError`,
`meanActuationEffort` and others), and its recorded frames. Pass a seed in `EvolutionConfig(randomSeed = ...)`
to get a different lineage; the same seed always gives the same champion.

## Watching it move

`frameLines(i)` draws frame `i`, and `notationLines(i)` draws the creature's score: one row per point,
with a bar wherever that foot's program has it down, a playhead at the current phase, and bows
between rows for the joints that are pushing. To animate either, draw a frame per pass of a `loop`.
Evolution takes seconds, so run it on a thread and have the loop draw whatever the latest champion is.
An `EvolutionController` lets the box stop the search when you stop the box.

```diffstatic {data-lang=kotlin}
val controller = EvolutionController()
var champion: CreatureEvaluation? = null
kotlin.concurrent.thread(isDaemon = true) {
    CreatureEvolution.evolve(config, controller) { champion = it.champion }
}

var i = 0
loop {
    val bug = champion ?: return@loop
    val c = bug.frames[i % bug.frames.size].center
    showPlane(
        bug.frameLines(i) + bug.notationLines(i, origin = Float2(c.x - 3f, c.y + 6f)),
        window = Rect(c.x - 4.0, c.y - 4.0, 8.0, 11.0),
        yUp = true,
    )
    i++
}.end { controller.requestStop() }
```

## A different world: a floor

Nothing above is specific to water except the contact model and the fitness. Seen side-on, under
gravity, a floor pushes back anything that sinks below `y = 0`. A foot grips only when its program
says so *and* it is on the floor. `place` lifts the body onto the floor before the run starts, and the
fitness counts horizontal distance only, since bouncing on the spot goes nowhere.

```diffstatic_runner {data-lang=kotlin screenshot="assets/evolving_creatures/crawler.png" wait="40.0" capture="box" open-outputs="true" w="900" h="420" padding="14"}
import afield.simulation.creatures.*

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
            val up = if (p.y < 0f) -p.y * stiffness - min(v.y, 0f) * friction else 0f
            grip = if (onFloor) step.contact else 0f
            if (grip <= 0.02f) {
                planted = null
                val slide = if (onFloor) friction else g.freeDrag * 0.1f
                return Float2(-v.x * slide, up - v.y * g.freeDrag * 0.1f)
            }
            val at = planted ?: Float2(p.x, 0f).also { planted = it }
            return Float2((at.x - p.x) * g.footStickiness * grip - v.x * g.footDrag * grip, up)
        }
    }
}

val config = EvolutionConfig(
    populationSize = 24,
    generations = 25,
    minPoints = 5,
    maxPoints = 8,
    targetEdgeCount = 8,
    alwaysUpFootFraction = 0.5f,
    cyclingFootFraction = 0.5f,
    simulation = CreatureSimulationConfig(
        measuredCycles = 6,
        gravity = -9.8f,
        contact = Floor(),
        fitness = { e -> abs(e.displacement.x) - 2f * e.meanStretchError },
        maxCenterSpeed = 4f,
    ),
)

val crawler = CreatureEvolution.evolve(config).best
val floor = fline {
    moveTo(-6f, 0f)
    lineTo(6f, 0f)
}.apply { color = vec(0.5, 0.45, 0.4, 1.0) }

showPlaneGrid(
    "start" to [floor] + crawler.frameLines(0, alignNetMotionToPositiveX = false),
    "end\nmoved %.2f along the floor".format(crawler.displacement.x) to
        [floor] + crawler.frameLines(crawler.frames.lastIndex, trailLength = crawler.frames.size, alignNetMotionToPositiveX = false),
    columns = 2,
    cellWidth = 400,
    cellHeight = 280,
    yUp = true,
)
```

The body is springs between points, and only the joint motors hold its angles. Under gravity that is
often not enough, so many crawlers evolve to lie flat and inch along on their feet.

## What you can change

| Where | What it does |
|---|---|
| `ContactModel.foot(index)` | The force a foot receives each step. The whole environment lives here. |
| `ContactModel.place(points)` | Where the body starts. Move it rigidly; rest lengths come from the genome. |
| `CreatureSimulationConfig.fitness` | The score. Exploded creatures score `explodedFitness` (default -1000) instead. |
| `CreatureSimulationConfig.gravity` | Added to every point as an acceleration (negative is down). |
| `measuredCycles`, `warmupCycles`, `stepsPerCycle` | How long each creature is watched, and how finely. |
| `maxCenterSpeed`, `maxMeanStretchError`, `maxPeakStretchError` | Stability limits; a creature past one counts as exploded. |
| `jointTargetAngleScale`, `jointMotorKp`, `jointMotorKd` | How far and how hard the joints bend. |
| `EvolutionConfig.populationSize`, `generations`, `randomSeed` | The size and the seed of the search. |
| `minPoints`, `maxPoints`, `targetEdgeCount`, `targetJointActivations` | The size of the bodies. |
| `alwaysUpFootFraction`, `cyclingFootFraction` | How many points are body only, and how many must step. |
