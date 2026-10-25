import afield.simulation.creatures.*
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
Launch it (Alt+Up). Seen from above: a creature of points and springs on a water surface.
Each point is a foot with a cyclic program saying when it touches down. A foot that is down
grips the surface film where it landed; a foot that is up drags through the water. Joints
bend the body to a cyclic target curve. The genetic algorithm evolves the shape, the foot
timing and the joint curves together.

The library does the genetics and the springs. Everything that makes it a *water bug* is
below: how a foot meets the water, and what we reward.
*/

// How a foot meets the water. One instance per foot, so it can remember where it landed.
class WaterSurface : ContactModel {
    override fun foot(index: Int) = object : ContactModel.Foot {
        var planted: Float2? = null
        override var grip = 0f
        override val anchor
            get() = planted

        override fun force(step: FootStep): Float2 {
            val g = step.genome
            grip = step.contact
            if (step.contact <= 0.02f) {
                // Up: just water drag.
                planted = null
                return step.velocity * -g.freeDrag
            }
            // Down: a spring back to where the foot first touched, plus a much heavier drag.
            val at = planted ?: step.position.also { planted = it }
            val drag = max(g.freeDrag, g.footDrag * step.contact)
            return (at - step.position) * (g.footStickiness * step.contact) - step.velocity * drag
        }
    }
}

// What we reward: getting anywhere at all, minus stretching the body and wasted motor effort.
fun swimScore(e: CreatureEvaluation) =
    e.netCentroidMotion - 2f * e.meanStretchError - 0.05f * e.meanActuationEffort - 0.08f * e.meanTargetSlew

val config = EvolutionConfig(
    populationSize = 28,
    generations = 200,
    minPoints = 5,
    maxPoints = 8,
    targetEdgeCount = 8,
    targetJointActivations = 10,
    alwaysUpFootFraction = 0.5f, // half the points are body, not feet
    cyclingFootFraction = 0.5f, // the rest must actually step
    simulation = CreatureSimulationConfig(
        measuredCycles = 10,
        gravity = 0f, // seen from above
        contact = WaterSurface(),
        fitness = ::swimScore,
        jointTargetAngleScale = 1.55f,
        jointMotorKp = 1.4f,
        jointMotorKd = 0.22f,
        jointTargetFilterRate = 6.8f,
        maxJointImpulsePerStep = 0.03f,
        maxMeanStretchError = 1.25f,
        maxPeakStretchError = 2.0f,
        maxCenterSpeed = 2.5f,
    ),
)

// Evolution runs in a cancellable worker; the loop below only draws its latest snapshot.
// Publish one complete update, so the caption and drawing use the same generation.
val latest = AtomicReference<EvolutionUpdate?>(null)
go {
    withContext(Dispatchers.Default) {
        CreatureEvolution.evolveSuspend(config) { latest.set(it) }
    }
}

var i = 0
loop {
    val update = latest.get() ?: return@loop
    val bug = update.champion
    val status = "generation ${update.generation.generation}: best ${bug.fitness.f2}"
    val c = bug.frames[i % bug.frames.size].center
    showPlane(
        bug.frameLines(i) + bug.notationLines(i, origin = Float2(c.x - 3f, c.y + 6f)),
        title = "water bug — $status",
        caption = "orange: a foot gripping the surface; ×: where it gripped; bars: each foot's score",
        window = Rect(c.x - 4.0, c.y - 4.0, 8.0, 11.0),
        width = 500,
        height = 600,
        yUp = true,
    )
    i++
}
