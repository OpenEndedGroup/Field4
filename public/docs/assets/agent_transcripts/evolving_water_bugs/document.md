# Field Document: evolving_water_bugs

This is a machine-readable narrative mirror of `metadata.edn`. The canonical storage format is still EDN; this file is written beside it for code and AI tools.

```edn [field-document version="1" source="metadata.edn"]
{"format" "field-document-md" "version" 1 "source" "metadata.edn" "boxes" 2 "persistentProperties" ["frame" "name" "code" "code_language" "contents_language" "default_property" "file_proxy_mode" "file_proxy_path" "file_proxy_original_path" "file_proxy_mime" "filename" "preamble" "introduction" "attic" "notes" "geometryReviews" "geometryAppearance" "fragment" "stackSL" "rectSL" "msl" "vertex" "vertex_fragment" "task" "mesh" "raygen" "miss" "closesthit" "anyhit" "intersection" "callable" "fragment_s" "vertex_s" "task_s" "mesh_s" "raygen_s" "miss_s" "closesthit_s" "anyhit_s" "intersection_s" "callable_s" "children" "parents" "compute" "compute_s" "customization" "source_id" "templateId" "_tombstone" "isRemoteJSRoot" "isPythonRoot" ".*_editorState" "metadata" "nested" "nestedIn" "playlist" "pauseList" "bookmarks" "drawing" "panes" "slideShow" "autorun" "layers" "layerFrames" "_controlGroupValues" "_controlMidi" "keyframeData" "keyframeInterpolants" "ai4_auto_name" "ai4_auto_name_signature" "ai4_chat_history" "ai4_chat_running" "ai4_chat_suspension" "ai4_session" "fileManagerAccessHistory" "kitRole"]}
```

## 1. the same evolution, on the ground

<!-- field-box id="ground_crawler" index="0" -->

```edn [field-box id="ground_crawler" role="properties"]
{"id" "ground_crawler" "index" 0 "name" "the same evolution, on the ground" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "the same evolution, on the ground" "_tombstone" false "frame" #afield/rect [1500.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "ground_crawler_code_.kt" "lines" 93 "characters" 3305 "sha256" "29aafb8e7e930f8979ebcfb5f545ef16381963796e8c478d19bf7222758d0a5f"}]}
```

```kotlin [field-property box="ground_crawler" name="code" path="ground_crawler_code_.kt"]
import afield.simulation.creatures.*
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        override val anchor
            get() = planted

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
    val crawler = update.champion
    val status = "generation ${update.generation.generation}: best ${crawler.fitness.f2}"
    val c = crawler.frames[i % crawler.frames.size].center
    val floor = fline {
        moveTo(c.x - 5f, 0f)
        lineTo(c.x + 5f, 0f)
    }
        .apply { color = vec(0.5, 0.45, 0.4, 1.0) }
    showPlane(
        [floor] + crawler.frameLines(i, alignNetMotionToPositiveX = false),
        title = "crawler — $status",
        window = Rect(c.x - 4.0, -1.0, 8.0, 6.0),
        width = 500,
        height = 380,
        yUp = true,
    )
    i++
}
```

## 2. evolving a water bug

<!-- field-box id="water_bug" index="1" -->

```edn [field-box id="water_bug" role="properties"]
{"id" "water_bug" "index" 1 "name" "evolving a water bug" "parents" ["root"] "children" [] "properties" {"children" #{} "parents" #{#afield/box "root"} "name" "evolving a water bug" "_tombstone" false "frame" #afield/rect [80.0 80.0 300.0 100.0]} "blockProperties" [{"name" "code" "language" "kotlin" "path" "water_bug_code_.kt" "lines" 95 "characters" 3647 "sha256" "a0922b2cab974892b5e975d4b173e76614b425bd6e9d4bc7f91123b08d303f56"}]}
```

```kotlin [field-property box="water_bug" name="code" path="water_bug_code_.kt"]
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
```

