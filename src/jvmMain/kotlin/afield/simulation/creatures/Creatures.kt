@file:Suppress("MemberVisibilityCanBePrivate")

/**
 * Evolving 2D creatures: a genetic algorithm over bodies of points and springs whose every point is a foot
 * with a cyclic contact program, and whose joints are angle motors following cyclic target curves.
 *
 * [CreatureEvolution.evolve] (or [CreatureEvolution.evolveFlow]) breeds a population under an
 * [EvolutionConfig]; [CreatureEvolution.simulate] scores one [CreatureGenome]; a [CreatureEvaluation] plays
 * back as FLines ([CreatureEvaluation.frameLines]) with a per-foot score ([CreatureEvaluation.notationLines]).
 *
 * The world is not in here. A [ContactModel] says what a foot receives from its surroundings, and
 * [CreatureSimulationConfig.fitness] says what counts as doing well, so a water strider, a crawler on a
 * floor and a swimmer differ only in box code. The `evolving_water_bugs` starting point writes two.
 */
package afield.simulation.creatures

import afield.simulation.physics.SimdVerlet
import afield.runtime.lifecycle.generator2
import afield.geometry.path.FLine
import afield.graphics.render.color
import afield.graphics.render.pointSize
import afield.graphics.render.pointed
import afield.graphics.render.thickness
import afield.math.linalg.vec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlin.math.*
import kotlin.random.Random

private const val MIN_REST_EDGE_LENGTH = 1e-4f
private const val JOINT_TARGET_KNOT_COUNT = 4
private const val MAX_JOINT_TARGET_SIGNAL = 0.9f
private const val MIN_SPRING_STIFFNESS = 3.5f
private const val DEFAULT_SPRING_STIFFNESS = 6.0f
private const val MAX_SPRING_STIFFNESS = 12.0f
private const val DEFAULT_MAX_ACTUATION_BUDGET_PER_CYCLE = 4.5f
private const val DEFAULT_ACTUATION_EFFORT_PENALTY = 0.05f
private const val DEFAULT_JOINT_TARGET_ANGLE_SCALE = 1.0f
private const val DEFAULT_JOINT_MOTOR_KP = 1.0f
private const val DEFAULT_JOINT_MOTOR_KD = 0.15f
private const val DEFAULT_JOINT_TARGET_FILTER_RATE = 8.5f
private const val DEFAULT_MAX_JOINT_IMPULSE_PER_STEP = 0.02f
private const val DEFAULT_TARGET_SLEW_PENALTY = 0.04f
private const val DEFAULT_MAX_MEAN_STRETCH_ERROR = 2.0f
private const val DEFAULT_MAX_PEAK_STRETCH_ERROR = 3.0f
private const val DEFAULT_MAX_CENTER_SPEED = 4.0f

data class Float2(val x: Float, val y: Float) {
    operator fun plus(other: Float2) = Float2(x + other.x, y + other.y)
    operator fun minus(other: Float2) = Float2(x - other.x, y - other.y)
    operator fun times(scale: Float) = Float2(x * scale, y * scale)
    operator fun div(scale: Float) = Float2(x / scale, y / scale)

    fun dot(other: Float2): Float = x * other.x + y * other.y
    fun perpDot(other: Float2): Float = x * other.y - y * other.x
    fun length(): Float = sqrt(x * x + y * y)
    fun lengthSquared(): Float = x * x + y * y
    fun normalizedOr(fallback: Float2 = Float2(1f, 0f)): Float2 {
        val len = length()
        return if (len <= 1e-6f) fallback else this / len
    }

    fun perpendicular(): Float2 = Float2(-y, x)
    fun lerp(other: Float2, t: Float): Float2 = this * (1f - t) + other * t
}

data class EdgeGene(val a: Int, val b: Int) {
    fun normalized(): EdgeGene =
        if (a <= b) this else EdgeGene(b, a)
}

data class ContactProgram(
    val phase: Float,
    val duty: Float = 0.35f,
    val smooth: Float = 0.2f,
    val strength: Float = 1f
) {
    fun normalized(): ContactProgram = copy(
        phase = wrap01(phase),
        duty = duty.coerceIn(0f, 0.95f),
        smooth = smooth.coerceIn(0.02f, 0.95f),
        strength = strength.coerceIn(0f, 1f)
    )

    fun sample(currentPhase: Float): Float {
        val c = normalized()
        if (c.duty <= 1e-4f || c.strength <= 1e-4f) return 0f
        return c.strength * smoothGate(currentPhase, c.phase, c.duty, c.smooth)
    }
}

data class JointProgram(
    val pivot: Int,
    val a: Int,
    val b: Int,
    val targets: List<Float> = List(JOINT_TARGET_KNOT_COUNT) { 0f }
) {
    fun normalized(): JointProgram {
        val normalizedTargets = normalizedJointTargets(targets)
        return if (a <= b) {
            copy(targets = normalizedTargets)
        } else {
            JointProgram(
                pivot = pivot,
                a = b,
                b = a,
                targets = normalizedTargets.map { -it }
            )
        }
    }

    fun sample(currentPhase: Float): Float {
        val c = normalized()
        if (c.targets.all { abs(it) <= 1e-4f }) return 0f
        return cyclicSplineSample(c.targets, currentPhase)
    }

    fun shifted(steps: Int): JointProgram =
        copy(targets = rotateValues(targets, steps))

    fun meanAbsSignal(sampleCount: Int = 24): Float {
        if (sampleCount <= 0) return 0f
        var sum = 0f
        repeat(sampleCount) { index ->
            sum += abs(sample(index / sampleCount.toFloat()))
        }
        return sum / sampleCount.toFloat()
    }

    internal fun key(): JointKey {
        val normalized = normalized()
        return JointKey(normalized.pivot, normalized.a, normalized.b)
    }
}

data class CreatureGenome(
    val points: List<Float2>,
    val edges: List<EdgeGene>,
    val feet: List<ContactProgram>,
    val joints: List<JointProgram>,
    val clockPeriod: Float = 1f,
    val massPerLength: Float = 1.1f,
    val basePointMass: Float = 0.3f,
    val springStiffness: Float = DEFAULT_SPRING_STIFFNESS,
    val damping: Float = 0.968f,
    val freeDrag: Float = 12f,
    val footDrag: Float = 180f,
    val footStickiness: Float = 640f,
    val jointTorqueScale: Float = 58f
) {
    fun normalized(): CreatureGenome {
        val edgeSet = LinkedHashSet<EdgeGene>()
        edges.map { it.normalized() }
            .filter { it.a != it.b }
            .forEach(edgeSet::add)

        val pointCount = points.size
        val normalizedFeet = MutableList(pointCount) { index ->
            feet.getOrNull(index)?.normalized() ?: ContactProgram(phase = 0f, strength = 0f)
        }

        return copy(
            edges = edgeSet.sortedWith(compareBy<EdgeGene> { it.a }.thenBy { it.b }),
            feet = normalizedFeet,
            joints = joints.map { it.normalized() }
                .distinctBy { it.key() }
                .sortedWith(
                    compareBy<JointProgram> { it.pivot }
                        .thenBy { it.a }
                        .thenBy { it.b }
                ),
            clockPeriod = clockPeriod.coerceIn(0.2f, 4f),
            massPerLength = massPerLength.coerceIn(0.05f, 10f),
            basePointMass = basePointMass.coerceIn(0.05f, 10f),
            springStiffness = springStiffness.coerceIn(MIN_SPRING_STIFFNESS, MAX_SPRING_STIFFNESS),
            damping = damping.coerceIn(0.7f, 0.9995f),
            freeDrag = freeDrag.coerceIn(0f, 400f),
            footDrag = footDrag.coerceIn(0f, 4000f),
            footStickiness = footStickiness.coerceIn(0f, 4000f),
            jointTorqueScale = jointTorqueScale.coerceIn(0f, 220f)
        )
    }

    fun validationIssues(): List<String> {
        val g = normalized()
        val issues = mutableListOf<String>()
        val pointCount = g.points.size

        if (pointCount < 2) issues += "creatures need at least 2 points"
        if (g.edges.isEmpty()) issues += "creatures need at least one edge"
        if (g.feet.size != pointCount) issues += "feet program count must match point count"

        g.points.forEachIndexed { index, point ->
            if (!point.x.isFinite() || !point.y.isFinite()) {
                issues += "point $index has non-finite coordinates"
            }
        }

        val degrees = IntArray(pointCount)
        val edgeSet = HashSet<Long>()
        for (edge in g.edges) {
            if (edge.a !in 0 until pointCount || edge.b !in 0 until pointCount) {
                issues += "edge $edge points outside valid range"
                continue
            }
            if (edge.a == edge.b) issues += "edge $edge is self-connected"
            val key = edgeKey(edge.a, edge.b)
            if (!edgeSet.add(key)) issues += "duplicate edge $edge"
            degrees[edge.a]++
            degrees[edge.b]++

            val length = distance(g.points[edge.a], g.points[edge.b])
            if (length <= MIN_REST_EDGE_LENGTH) issues += "edge $edge has near-zero rest length"
        }

        degrees.forEachIndexed { index, degree ->
            if (degree > 4) issues += "point $index has degree $degree, but SimdVerlet DirectedConstraints4 needs degree <= 4"
        }

        edgeCrossings(g.points, g.edges).forEach { crossing ->
            val first = g.edges[crossing.firstIndex].normalized()
            val second = g.edges[crossing.secondIndex].normalized()
            issues += "edge $first crosses edge $second"
        }

        if (issues.isEmpty() && !isConnected(pointCount, g.edges)) {
            issues += "creature graph must stay connected"
        }

        val adjacency = buildAdjacency(pointCount, g.edges)
        val validJointKeys = candidateJointKeys(adjacency).toHashSet()
        g.joints.forEach { joint ->
            if (joint.pivot !in 0 until pointCount || joint.a !in 0 until pointCount || joint.b !in 0 until pointCount) {
                issues += "joint $joint points outside valid range"
            } else if (joint.a == joint.b || joint.a == joint.pivot || joint.b == joint.pivot) {
                issues += "joint $joint must connect two distinct neighbors around a pivot"
            } else if (joint.key() !in validJointKeys) {
                issues += "joint $joint does not refer to two actual edges meeting at its pivot"
            }
        }

        return issues
    }
}

data class CreatureSimulationConfig(
    val warmupCycles: Int = 1,
    val measuredCycles: Int = 5,
    val stepsPerCycle: Int = 120,
    val solverIterations: Int = 16,
    val gravity: Float = 0f,
    val targetDirection: Float2 = Float2(1f, 0f),
    val recordFrames: Boolean = true,
    val recordEvery: Int = 4,
    val maxAbsCoordinate: Float = 200f,
    val fitnessObjective: FitnessObjective = FitnessObjective.DIRECTIONAL_PROGRESS,
    val maxActuationBudgetPerCycle: Float = DEFAULT_MAX_ACTUATION_BUDGET_PER_CYCLE,
    val actuationEffortPenalty: Float = DEFAULT_ACTUATION_EFFORT_PENALTY,
    val jointTargetAngleScale: Float = DEFAULT_JOINT_TARGET_ANGLE_SCALE,
    val jointMotorKp: Float = DEFAULT_JOINT_MOTOR_KP,
    val jointMotorKd: Float = DEFAULT_JOINT_MOTOR_KD,
    val jointTargetFilterRate: Float = DEFAULT_JOINT_TARGET_FILTER_RATE,
    val maxJointImpulsePerStep: Float = DEFAULT_MAX_JOINT_IMPULSE_PER_STEP,
    val targetSlewPenalty: Float = DEFAULT_TARGET_SLEW_PENALTY,
    val maxMeanStretchError: Float = DEFAULT_MAX_MEAN_STRETCH_ERROR,
    val maxPeakStretchError: Float = DEFAULT_MAX_PEAK_STRETCH_ERROR,
    val maxCenterSpeed: Float = DEFAULT_MAX_CENTER_SPEED,
    /** How a foot meets the world: the creature's environment. See [ContactModel]. */
    val contact: ContactModel = ContactModel.Drag,
    /**
     * Replaces [fitnessObjective] when set. Called with the finished evaluation (its `fitness` still holds
     * the built-in objective's score); a creature that exploded scores [explodedFitness] without calling it.
     */
    val fitness: ((CreatureEvaluation) -> Float)? = null,
    /**
     * The score of a creature whose simulation blew up or broke a stability limit (and of a non-finite custom
     * fitness). Keep it below anything a living creature can score under your [fitness].
     */
    val explodedFitness: Float = -1000f
)

/**
 * What one foot is doing on one physics step: its program's contact strength in `0..1` for the current
 * phase, where it is, how fast it moves, and the genome (for evolved gains such as
 * [CreatureGenome.footStickiness], [CreatureGenome.footDrag] and [CreatureGenome.freeDrag]).
 */
data class FootStep(
    val contact: Float,
    val position: Float2,
    val velocity: Float2,
    val genome: CreatureGenome
)

/**
 * The environment a creature moves in, as the force each foot receives. Every point of the body is a foot
 * (most of them evolve programs that never touch down), and each gets its own [Foot] from [foot], so a foot
 * can remember where it planted.
 *
 * The evolution itself knows nothing about water, ground or air: a water strider, a crawler on a floor, or
 * something swimming in syrup differ only in this interface and in [CreatureSimulationConfig.gravity].
 */
interface ContactModel {
    fun foot(index: Int): Foot

    /** Where the body starts, given the genome's centred rest shape. A floor model lifts it onto the floor. */
    fun place(points: List<Float2>): List<Float2> = points

    interface Foot {
        /** The force (acceleration) this foot receives this step. */
        fun force(step: FootStep): Float2

        /** How firmly the foot is gripping, `0..1`; above 0.35 playback draws it as down. */
        val grip: Float

        /** The point it is held to, drawn as a cross in playback, or null. */
        val anchor: Float2? get() = null
    }

    /** A viscous medium with nothing to push against except [CreatureGenome.freeDrag]: no foot ever grips. */
    object Drag : ContactModel {
        override fun foot(index: Int) = object : Foot {
            override val grip = 0f
            override fun force(step: FootStep) = step.velocity * -step.genome.freeDrag
        }
    }
}

enum class FitnessObjective {
    DIRECTIONAL_PROGRESS,
    NET_CENTROID_DISPLACEMENT
}

data class CreatureSnapshot(
    val step: Int,
    val phase: Float,
    val center: Float2,
    val points: List<Float2>,
    val down: List<Boolean>,
    val anchors: List<Float2?>,
    val jointTorques: List<Float>,
    val jointTargets: List<Float>
)

data class CreatureEvaluation(
    val genome: CreatureGenome,
    val fitness: Float,
    val netCentroidMotion: Float,
    /** Where the centroid ended up relative to where measurement began. */
    val displacement: Float2,
    val forwardTravel: Float,
    val forwardProgress: Float,
    val lateralDrift: Float,
    val meanStretchError: Float,
    val meanActuationEffort: Float,
    val programmedActuationBudgetPerCycle: Float,
    val meanTargetSlew: Float,
    val meanSpeed: Float,
    val frames: List<CreatureSnapshot>,
    val exploded: Boolean,
    /** How far the centroid moved in the first half of measurement, and in the second. */
    val firstHalfDisplacement: Float2 = Float2(0f, 0f),
    val secondHalfDisplacement: Float2 = Float2(0f, 0f)
) {
    /**
     * The smaller of the two half-run distances along [direction], so a creature that topples once and
     * then lies still scores about zero, and only one that keeps going scores well.
     */
    fun sustainedTravel(direction: Float2 = Float2(1f, 0f)): Float {
        val d = direction.normalizedOr()
        return min(firstHalfDisplacement.dot(d), secondHalfDisplacement.dot(d))
    }

    fun summary(): String =
        "fitness=${"%.3f".format(fitness)} net=${"%.3f".format(netCentroidMotion)} forward=${"%.3f".format(forwardTravel)} " +
            "progress=${"%.3f".format(forwardProgress)} lateral=${"%.3f".format(lateralDrift)} " +
            "stretch=${"%.3f".format(meanStretchError)} effort=${"%.3f".format(meanActuationEffort)} " +
            "budget=${"%.3f".format(programmedActuationBudgetPerCycle)} slew=${"%.3f".format(meanTargetSlew)} " +
            "speed=${"%.3f".format(meanSpeed)} " +
            "frames=${frames.size} exploded=$exploded"

    /**
     * Frame [index] as 3D FLines: x and y are the surface, z is height. Show them with
     * `three { lines(...); camera(upAxis = ThreeUpAxis.Z) }` so the viewer's grid is the water.
     *
     * A planted foot sits on the surface (height 0) and every other point is [lift] above it. Each edge
     * arcs upward by [arc] times its length, so legs bow like a strider's. On the surface: the body's shadow, a drop line from every point, and a ring where each planted foot is holding.
     */
    fun frameLines3d(
        index: Int,
        lift: Float = 0.2f,
        arc: Float = 0.06f,
        alignNetMotionToPositiveX: Boolean = true
    ): List<FLine> {
        if (frames.isEmpty()) return emptyList()
        val frame = frames[index.coerceIn(0, frames.lastIndex)]
        val alignment = motionAlignmentTransform(alignNetMotionToPositiveX)
        val ground = frame.points.map(alignment::transform)
        val height = frame.down.map { if (it) 0f else lift }

        fun project(p: Float2, z: Float) = vec(p.x, p.y, z)

        val shadow = FLine().apply {
            color = vec(0.35, 0.5, 0.6, 0.35)
            thickness = 1.2
        }
        val drops = FLine().apply {
            color = vec(0.6, 0.7, 0.8, 0.3)
            thickness = 0.6
        }
        val rings = FLine().apply {
            color = vec(1.0, 0.55, 0.25, 0.8)
            thickness = 1.2
        }
        val legs = FLine().apply {
            color = vec(0.72, 0.87, 0.88, 0.95)
            thickness = 2.0
        }
        for (edge in genome.edges) {
            val a = ground[edge.a]
            val b = ground[edge.b]
            shadow.moveTo(project(a, 0f))
            shadow.lineTo(project(b, 0f))

            // A cubic whose controls sit a third of the way along, raised so the curve peaks arc * length above
            // the straight line between its ends (a cubic reaches 3/4 of its control offset).
            val za = height[edge.a]
            val zb = height[edge.b]
            val rise = arc * distance(a, b) * 4f / 3f
            legs.moveTo(project(a, za))
            legs.cubicTo(
                project(a.lerp(b, 1f / 3f), (za + (zb - za) / 3f) + rise),
                project(a.lerp(b, 2f / 3f), (za + (zb - za) * 2f / 3f) + rise),
                project(b, zb)
            )
        }
        ground.forEachIndexed { i, p ->
            drops.moveTo(project(p, 0f))
            drops.lineTo(project(p, height[i]))
        }
        frame.anchors.forEach { anchor ->
            anchor ?: return@forEach
            val at = alignment.transform(anchor)
            val r = 0.12f
            for (k in 0..16) {
                val t = k / 16f * 2f * PI.toFloat()
                val p = project(Float2(at.x + r * cos(t), at.y + r * sin(t)), 0f)
                if (k == 0) rings.moveTo(p) else rings.lineTo(p)
            }
        }

        val upPoints = FLine().apply {
            pointed = true
            pointSize = 1.4
            color = vec(0.31, 0.61, 0.93, 1.0)
        }
        val downPoints = FLine().apply {
            pointed = true
            pointSize = 2.2
            color = vec(0.98, 0.69, 0.22, 1.0)
        }
        ground.forEachIndexed { i, p ->
            (if (frame.down[i]) downPoints else upPoints).moveTo(project(p, height[i]))
        }

        return listOf(shadow, drops, rings, legs, upPoints, downPoints)
    }

    fun frameLines(
        index: Int,
        trailLength: Int = 40,
        alignNetMotionToPositiveX: Boolean = true
    ): List<FLine> {
        if (frames.isEmpty()) return emptyList()
        val clampedIndex = index.coerceIn(0, frames.lastIndex)
        val frame = frames[clampedIndex]
        val alignment = motionAlignmentTransform(alignNetMotionToPositiveX)
        val points = frame.points.map { alignment.transform(it) }
        val anchors = frame.anchors.map { anchor -> anchor?.let(alignment::transform) }

        val edgeLine = FLine().apply {
            color = vec(0.72, 0.87, 0.88, 0.9)
            thickness = 2.25
        }
        for (edge in genome.edges) {
            val a = points[edge.a]
            val b = points[edge.b]
            edgeLine.moveTo(vec(a.x, a.y, 0))
            edgeLine.lineTo(vec(b.x, b.y, 0))
        }

        // Two point clouds rather than per-node colours: feet that are down, and everything else.
        // Sizes suit showPlane, where a dot's radius is 3 * pointSize pixels.
        val upPoints = FLine().apply {
            pointed = true
            pointSize = 1.4
            color = vec(0.31, 0.61, 0.93, 1.0)
        }
        val downPoints = FLine().apply {
            pointed = true
            pointSize = 2.2
            color = vec(0.98, 0.69, 0.22, 1.0)
        }
        points.forEachIndexed { pointIndex, point ->
            val cloud = if (frame.down[pointIndex]) downPoints else upPoints
            cloud.moveTo(vec(point.x, point.y, 0))
        }

        val anchorMarks = FLine().apply {
            color = vec(1.0, 0.42, 0.18, 0.75)
            thickness = 1.5
        }
        anchors.forEach { anchor ->
            anchor ?: return@forEach
            val r = 0.08f
            anchorMarks.moveTo(vec(anchor.x - r, anchor.y - r, 0))
            anchorMarks.lineTo(vec(anchor.x + r, anchor.y + r, 0))
            anchorMarks.moveTo(vec(anchor.x - r, anchor.y + r, 0))
            anchorMarks.lineTo(vec(anchor.x + r, anchor.y - r, 0))
        }

        val trail = FLine().apply {
            color = vec(0.93, 0.93, 0.55, 0.85)
            thickness = 1.6
        }
        val start = max(0, clampedIndex - trailLength + 1)
        val centers = frames.subList(start, clampedIndex + 1).map { alignment.transform(it.center) }
        if (centers.isNotEmpty()) {
            trail.moveTo(vec(centers.first().x, centers.first().y, 0))
            for (i in 1 until centers.size) {
                trail.lineTo(vec(centers[i].x, centers[i].y, 0))
            }
        }

        return listOf(edgeLine, upPoints, downPoints, anchorMarks, trail)
    }

    fun playback(
        loop: Boolean = true,
        trailLength: Int = 40,
        alignNetMotionToPositiveX: Boolean = true
    ): Sequence<List<FLine>> = sequence {
        if (frames.isEmpty()) return@sequence
        do {
            for (i in frames.indices) {
                yield(frameLines(i, trailLength, alignNetMotionToPositiveX))
            }
        } while (loop)
    }

    fun notationLines(
        index: Int,
        scoreWidth: Float = 6f,
        rowHeight: Float = 0.55f,
        origin: Float2? = null
    ): List<FLine> {
        if (frames.isEmpty()) return emptyList()
        val clampedIndex = index.coerceIn(0, frames.lastIndex)
        val frame = frames[clampedIndex]
        val resolvedOrigin = resolveNotationOrigin(scoreWidth, rowHeight, origin)
        val left = resolvedOrigin.x
        val right = resolvedOrigin.x + scoreWidth
        val phaseX = left + wrap01(frame.phase) * scoreWidth

        fun rowY(pointIndex: Int): Float = resolvedOrigin.y - pointIndex * rowHeight

        val baseline = FLine().apply {
            color = vec(0.42, 0.46, 0.54, 0.55)
            thickness = 0.8
        }
        val footDown = FLine().apply {
            color = vec(0.98, 0.69, 0.22, 0.9)
            thickness = 3.2
        }
        val footTransitions = FLine().apply {
            color = vec(0.95, 0.95, 0.95, 0.75)
            thickness = 1.1
        }
        val pivotMarkers = FLine().apply {
            pointed = true
            pointSize = 1.2
        }

        genome.points.indices.forEach { pointIndex ->
            val y = rowY(pointIndex)
            baseline.moveTo(vec(left, y, 0))
            baseline.lineTo(vec(right, y, 0))

            notationSegments(genome.feet[pointIndex]).forEach { segment ->
                val x0 = left + segment.start * scoreWidth
                val x1 = left + segment.end * scoreWidth
                footDown.moveTo(vec(x0, y, 0))
                footDown.lineTo(vec(x1, y, 0))

                val tick = rowHeight * 0.28f
                footTransitions.moveTo(vec(x0, y - tick, 0))
                footTransitions.lineTo(vec(x0, y + tick, 0))
                footTransitions.moveTo(vec(x1, y - tick, 0))
                footTransitions.lineTo(vec(x1, y + tick, 0))
            }
        }

        val torqueCurves = mutableListOf<FLine>()
        genome.joints.forEachIndexed { jointIndex, joint ->
            val torque = frame.jointTorques.getOrElse(jointIndex) { 0f }
            val magnitude = (abs(torque) / genome.jointTorqueScale.coerceAtLeast(1e-4f)).coerceIn(0f, 1f)
            if (magnitude <= 1e-4f) return@forEachIndexed

            val bow = (0.14f + magnitude * scoreWidth * 0.14f) * if (torque >= 0f) 1f else -1f
            val color =
                if (torque >= 0f) vec(0.92, 0.54, 0.31, 0.82)
                else vec(0.41, 0.72, 0.95, 0.82)
            val curve = FLine().apply {
                this.color = color
                thickness = 0.7 + magnitude * 1.9
            }
            addNotationBow(curve, phaseX, rowY(joint.pivot), rowY(joint.a), bow)
            addNotationBow(curve, phaseX, rowY(joint.pivot), rowY(joint.b), bow)
            torqueCurves += curve

            pivotMarkers.moveTo(vec(phaseX, rowY(joint.pivot), 0))
            pivotMarkers.here.color = color
            pivotMarkers.here.pointSize = 0.9 + magnitude * 0.6
        }

        val playhead = FLine().apply {
            color = vec(0.98, 0.98, 0.98, 0.9)
            thickness = 1.4
        }
        val top = resolvedOrigin.y + rowHeight * 0.45f
        val bottom = rowY(genome.points.lastIndex.coerceAtLeast(0)) - rowHeight * 0.45f
        playhead.moveTo(vec(phaseX, top, 0))
        playhead.lineTo(vec(phaseX, bottom, 0))

        return buildList {
            add(baseline)
            add(footDown)
            add(footTransitions)
            addAll(torqueCurves)
            add(pivotMarkers)
            add(playhead)
        }
    }

    fun notationPlayback(
        loop: Boolean = true,
        scoreWidth: Float = 6f,
        rowHeight: Float = 0.55f,
        origin: Float2? = null
    ): Sequence<List<FLine>> = sequence {
        if (frames.isEmpty()) return@sequence
        do {
            for (i in frames.indices) {
                yield(notationLines(i, scoreWidth, rowHeight, origin))
            }
        } while (loop)
    }

    fun playbackWithNotation(
        loop: Boolean = true,
        trailLength: Int = 40,
        alignNetMotionToPositiveX: Boolean = true,
        scoreWidth: Float = 6f,
        rowHeight: Float = 0.55f,
        notationOrigin: Float2? = null
    ): Sequence<List<FLine>> = sequence {
        if (frames.isEmpty()) return@sequence
        do {
            for (i in frames.indices) {
                yield(
                    frameLines(i, trailLength, alignNetMotionToPositiveX) +
                        notationLines(i, scoreWidth, rowHeight, notationOrigin)
                )
            }
        } while (loop)
    }

    private fun motionAlignmentTransform(enabled: Boolean): MotionAlignmentTransform {
        if (!enabled || frames.size < 2) return MotionAlignmentTransform.identity()
        val pivot = frames.first().center
        val displacement = frames.last().center - pivot
        if (displacement.lengthSquared() <= 1e-8f) return MotionAlignmentTransform.identity()
        val rotation = -atan2(displacement.y, displacement.x)
        return MotionAlignmentTransform(pivot = pivot, cos = cos(rotation), sin = kotlin.math.sin(rotation))
    }

    private fun resolveNotationOrigin(scoreWidth: Float, rowHeight: Float, origin: Float2?): Float2 {
        origin ?: run {
            val verticalExtent = genome.points.lastIndex.coerceAtLeast(0) * rowHeight
            return Float2(
                x = -scoreWidth * 0.5f,
                y = max(2.2f, verticalExtent * 0.5f + 1.5f)
            )
        }
        return origin
    }

    private fun addNotationBow(line: FLine, x: Float, y0: Float, y1: Float, bow: Float) {
        if (abs(y1 - y0) <= 1e-6f) return
        val controlX = x + bow
        val c1Y = lerp(y0, y1, 0.33f)
        val c2Y = lerp(y0, y1, 0.66f)
        line.moveTo(vec(x, y0, 0))
        line.cubicTo(
            vec(controlX, c1Y, 0),
            vec(controlX, c2Y, 0),
            vec(x, y1, 0)
        )
    }
}

private data class MotionAlignmentTransform(
    val pivot: Float2,
    val cos: Float,
    val sin: Float
) {
    fun transform(point: Float2): Float2 {
        val translated = point - pivot
        return Float2(
            translated.x * cos - translated.y * sin,
            translated.x * sin + translated.y * cos
        ) + pivot
    }

    companion object {
        fun identity(): MotionAlignmentTransform = MotionAlignmentTransform(Float2(0f, 0f), 1f, 0f)
    }
}

private data class PhaseInterval(val start: Float, val end: Float)

data class EvolutionConfig(
    val populationSize: Int = 28,
    val generations: Int = 12,
    val eliteCount: Int = 4,
    val tournamentSize: Int = 4,
    val crossoverRate: Float = 0.35f,
    val mutationSteps: IntRange = 2..5,
    val immigrantsPerGeneration: Int = 1,
    val immigrantFraction: Float = 0.08f,
    val stagnationGenerations: Int = 80,
    val stagnationMutationBoost: IntRange = 2..4,
    val stagnationImmigrantFraction: Float = 0.3f,
    val minPoints: Int = 4,
    val maxPoints: Int = 9,
    val targetEdgeCount: Int? = null,
    val targetJointActivations: Int? = null,
    val alwaysUpFootFraction: Float = 0f,
    val cyclingFootFraction: Float = 0f,
    val simulation: CreatureSimulationConfig = CreatureSimulationConfig(),
    val randomSeed: Int = 1
)

data class EvolutionGeneration(
    val generation: Int,
    val bestFitness: Float,
    val meanFitness: Float,
    val medianFitness: Float
)

data class EvolutionResult(
    val best: CreatureEvaluation,
    val history: List<EvolutionGeneration>
)

data class EvolutionUpdate(
    val generation: EvolutionGeneration,
    val generationBest: CreatureEvaluation,
    val champion: CreatureEvaluation,
    val generationsSinceImprovement: Int,
    val stagnating: Boolean
)

class EvolutionController {
    @Volatile
    private var stopRequested: Boolean = false

    fun requestStop() {
        stopRequested = true
    }

    fun reset() {
        stopRequested = false
    }

    fun isStopRequested(): Boolean = stopRequested
}

internal data class JointKey(val pivot: Int, val a: Int, val b: Int)
private data class EdgeRest(val a: Int, val b: Int, val restLength: Float)
private data class EdgeCrossing(val firstIndex: Int, val secondIndex: Int, val point: Float2)
private data class EdgeConnectionCandidate(val a: Int, val b: Int, val distance: Float)
private data class RuntimeClock(var phase: Float = 0f, var step: Int = 0)
private data class ScoredGenome(val genome: CreatureGenome, val evaluation: CreatureEvaluation)
private data class JointMotorPose(
    val restAngle: Float,
    val currentAngle: Float,
    val angleVelocity: Float,
    val aLen2: Float,
    val bLen2: Float,
    val aInvMass: Float,
    val bInvMass: Float,
    val pivotInvMass: Float,
    val aCoeffX: Float,
    val aCoeffY: Float,
    val bCoeffX: Float,
    val bCoeffY: Float,
    val pivotCoeffX: Float,
    val pivotCoeffY: Float
)

object CreatureEvolution {
    fun randomGenome(
        random: Random = Random.Default,
        minPoints: Int = 4,
        maxPoints: Int = 9,
        targetEdgeCount: Int? = null,
        targetJointActivations: Int? = null,
        alwaysUpFootFraction: Float = 0f,
        cyclingFootFraction: Float = 0f,
        maxActuationBudgetPerCycle: Float = DEFAULT_MAX_ACTUATION_BUDGET_PER_CYCLE
    ): CreatureGenome {
        require(minPoints >= 2) { "minPoints must be >= 2" }
        require(maxPoints >= minPoints) { "maxPoints must be >= minPoints" }

        val pointCount = random.nextInt(minPoints, maxPoints + 1)
        val points = mutableListOf<Float2>()
        val edges = mutableListOf<EdgeGene>()
        val degrees = mutableListOf<Int>()

        points += Float2(0f, 0f)
        degrees += 0

        for (i in 1 until pointCount) {
            val possibleParents = degrees.indices.filter { degrees[it] < 4 }
            val parent = possibleParents.random(random)
            val base = points[parent]
            val angle = random.nextFloat() * (2f * PI.toFloat())
            val length = randomFloat(random, 0.65f, 1.35f)
            val offset = Float2(cos(angle) * length, kotlin.math.sin(angle) * length)
            points += base + offset + Float2(
                gaussian(random, 0.08f),
                gaussian(random, 0.08f)
            )
            edges += EdgeGene(parent, i)
            degrees[parent]++
            degrees += 1
        }

        val maxPossibleEdges = maxReachableEdgeCount(pointCount)
        val desiredEdgeCount = targetEdgeCount
            ?.coerceIn(pointCount - 1, maxPossibleEdges)
            ?: (edges.size + random.nextInt(0, min(3, pointCount / 2) + 1))

        while (edges.size < desiredEdgeCount) {
            val candidates = mutableListOf<Pair<Int, Int>>()
            for (a in 0 until pointCount) {
                if (degrees[a] >= 4) continue
                for (b in a + 1 until pointCount) {
                    if (degrees[b] >= 4) continue
                    if (edges.any { it.normalized() == EdgeGene(a, b) }) continue
                    val distance = distance(points[a], points[b])
                    if (distance in 0.4f..2.4f && !wouldCrossExistingEdges(points, edges, EdgeGene(a, b))) {
                        candidates += a to b
                    }
                }
            }
            if (candidates.isEmpty()) break
            val (a, b) = candidates.random(random)
            edges += EdgeGene(a, b)
            degrees[a]++
            degrees[b]++
        }

        val centeredPoints = centerPoints(points)
        val feet = MutableList(pointCount) { ContactProgram(phase = 0f, strength = 0f) }
        val sortedByY = centeredPoints.indices.sortedBy { centeredPoints[it].y }
        val footCount = min(max(2, pointCount / 3), pointCount)
        val footPhaseStride = 1f / footCount.toFloat()
        for (footIndex in 0 until footCount) {
            val pointIndex = sortedByY[footIndex]
            feet[pointIndex] = ContactProgram(
                phase = wrap01(footIndex * footPhaseStride + gaussian(random, 0.05f)),
                duty = randomFloat(random, 0.18f, 0.52f),
                smooth = randomFloat(random, 0.12f, 0.45f),
                strength = randomFloat(random, 0.65f, 1f)
            )
        }

        val seedGenome = CreatureGenome(
            points = centeredPoints,
            edges = edges,
            feet = feet,
            joints = emptyList(),
            clockPeriod = randomFloat(random, 0.7f, 1.45f),
            massPerLength = randomFloat(random, 0.6f, 1.8f),
            basePointMass = randomFloat(random, 0.2f, 0.7f),
            springStiffness = randomFloat(random, 4.8f, 8.2f),
            damping = randomFloat(random, 0.93f, 0.985f),
            freeDrag = randomFloat(random, 4f, 28f),
            footDrag = randomFloat(random, 70f, 240f),
            footStickiness = randomFloat(random, 280f, 980f),
            jointTorqueScale = randomFloat(random, 18f, 72f)
        ).normalized()
            .repairCrossingEdges()
            .repairAlwaysUpFeet(alwaysUpFootFraction)
            .repairCyclingFeet(cyclingFootFraction, random)

        return seedGenome.withJointPopulation(random, targetJointActivations)
            .repairActuationBudget(maxActuationBudgetPerCycle)
    }

    fun simulate(
        genome: CreatureGenome,
        config: CreatureSimulationConfig = CreatureSimulationConfig()
    ): CreatureEvaluation {
        val sanitized = genome.normalized()
            .repairNearZeroEdges()
            .repairConnectivity()
            .repairCrossingEdges()
            .repairNearZeroEdges()
            .repairConnectivity()
            .repairCrossingEdges()
            .pruneInvalidJoints()
            .repairActuationBudget(config.maxActuationBudgetPerCycle)
            .normalized()
        val issues = sanitized.validationIssues()
        require(issues.isEmpty()) {
            "invalid creature genome:\n" + issues.joinToString(separator = "\n") { " - $it" }
        }
        return CreatureRuntime(sanitized, config).run()
    }

    /**
     * Runs a whole evolution on the calling thread and returns the champion. [onGeneration] sees every
     * generation as it finishes; [controller] stops it early from another thread.
     */
    fun evolve(
        config: EvolutionConfig = EvolutionConfig(),
        controller: EvolutionController? = null,
        onGeneration: (EvolutionUpdate) -> Unit = {}
    ): EvolutionResult =
        runBlocking {
            evolveSuspend(config, controller) { onGeneration(it) }
        }

    suspend fun evolveSuspend(
        config: EvolutionConfig = EvolutionConfig(),
        controller: EvolutionController? = null,
        onGeneration: suspend (EvolutionUpdate) -> Unit = {}
    ): EvolutionResult =
        evolveInternal(
            config = config,
            controller = controller,
            onGeneration = onGeneration
        )

    fun evolveFlow(
        config: EvolutionConfig = EvolutionConfig(),
        controller: EvolutionController = EvolutionController()
    ): Flow<EvolutionUpdate> =
        flow {
            evolveInternal(
                config = config,
                controller = controller,
                onGeneration = { emit(it) }
            )
        }

    private suspend fun evolveInternal(
        config: EvolutionConfig,
        controller: EvolutionController?,
        onGeneration: suspend (EvolutionUpdate) -> Unit
    ): EvolutionResult {
        require(config.populationSize > 0) { "populationSize must be > 0" }
        require(config.generations > 0) { "generations must be > 0" }
        require(config.eliteCount in 0..config.populationSize) { "eliteCount must be between 0 and populationSize" }
        require(config.tournamentSize > 0) { "tournamentSize must be > 0" }
        require(config.immigrantsPerGeneration >= 0) { "immigrantsPerGeneration must be >= 0" }
        require(config.immigrantFraction in 0f..1f) { "immigrantFraction must be between 0 and 1" }
        require(config.stagnationGenerations >= 0) { "stagnationGenerations must be >= 0" }
        require(config.stagnationMutationBoost.first >= 0 && config.stagnationMutationBoost.last >= config.stagnationMutationBoost.first) {
            "stagnationMutationBoost must be a non-negative IntRange"
        }
        require(config.stagnationImmigrantFraction in 0f..1f) { "stagnationImmigrantFraction must be between 0 and 1" }
        require(config.minPoints >= 2 && config.maxPoints >= config.minPoints) { "point range is invalid" }
        require(config.alwaysUpFootFraction in 0f..1f) { "alwaysUpFootFraction must be between 0 and 1" }
        require(config.cyclingFootFraction in 0f..1f) { "cyclingFootFraction must be between 0 and 1" }
        require(config.alwaysUpFootFraction + config.cyclingFootFraction <= 1f + 1e-6f) {
            "alwaysUpFootFraction + cyclingFootFraction must be <= 1"
        }
        require(config.mutationSteps.first > 0 && config.mutationSteps.last >= config.mutationSteps.first) {
            "mutationSteps must be a positive IntRange"
        }

        val random = Random(config.randomSeed)
        val fastSimulation = config.simulation.copy(recordFrames = false)
        var population = MutableList(config.populationSize) {
            randomGenome(
                random = random,
                minPoints = config.minPoints,
                maxPoints = config.maxPoints,
                targetEdgeCount = config.targetEdgeCount,
                targetJointActivations = config.targetJointActivations,
                alwaysUpFootFraction = config.alwaysUpFootFraction,
                cyclingFootFraction = config.cyclingFootFraction,
                maxActuationBudgetPerCycle = config.simulation.maxActuationBudgetPerCycle
            )
        }

        val history = mutableListOf<EvolutionGeneration>()
        var champion: CreatureEvaluation? = null
        fun result() = EvolutionResult(best = champion ?: simulate(population.first(), config.simulation), history = history)
        var generationsSinceImprovement = 0

        repeat(config.generations) { generation ->
            val scored = mutableListOf<ScoredGenome>()
            for (genome in population) {
                if (!evolutionCheckpoint(controller)) {
                    return result()
                }
                // Score the repaired genome, and breed from that: it is the creature that earned the score.
                // A genome repair cannot make valid is dropped rather than ending the run.
                val evaluation = runCatching { simulate(genome, fastSimulation) }.getOrNull() ?: continue
                scored += ScoredGenome(evaluation.genome, evaluation)
            }
            scored.sortByDescending { it.evaluation.fitness }

            if (!evolutionCheckpoint(controller)) {
                return result()
            }
            check(scored.isNotEmpty()) { "no genome in generation $generation survived repair" }
            val bestFast = scored.first()
            val bestFull = simulate(bestFast.genome, config.simulation)
            val previous = champion
            val improved = previous == null || bestFull.fitness > previous.fitness
            val currentChampion = if (improved) bestFull else previous!!
            champion = currentChampion
            if (improved) {
                generationsSinceImprovement = 0
            } else {
                generationsSinceImprovement++
            }

            val fitnesses = scored.map { it.evaluation.fitness }
            val generationInfo = EvolutionGeneration(
                generation = generation,
                bestFitness = fitnesses.first(),
                meanFitness = fitnesses.average().toFloat(),
                medianFitness = fitnesses[fitnesses.size / 2]
            )
            history += generationInfo
            val stagnating =
                config.stagnationGenerations > 0 &&
                    generationsSinceImprovement >= config.stagnationGenerations
            onGeneration(
                EvolutionUpdate(
                    generation = generationInfo,
                    generationBest = bestFull,
                    champion = currentChampion,
                    generationsSinceImprovement = generationsSinceImprovement,
                    stagnating = stagnating
                )
            )

            if (generation == config.generations - 1) return@repeat
            if (!evolutionCheckpoint(controller)) {
                return result()
            }

            val nextPopulation = mutableListOf<CreatureGenome>()
            scored.take(config.eliteCount).mapTo(nextPopulation) { it.genome }

            val immigrantCount = effectiveImmigrantCount(config, stagnating)
                .coerceAtMost(max(0, config.populationSize - nextPopulation.size))
            val mutationRange = effectiveMutationRange(config, stagnating)

            repeat(immigrantCount) {
                if (!evolutionCheckpoint(controller)) {
                    return result()
                }
                if (nextPopulation.size < config.populationSize) {
                    nextPopulation += randomGenome(
                        random = random,
                        minPoints = config.minPoints,
                        maxPoints = config.maxPoints,
                        targetEdgeCount = config.targetEdgeCount,
                        targetJointActivations = config.targetJointActivations,
                        alwaysUpFootFraction = config.alwaysUpFootFraction,
                        cyclingFootFraction = config.cyclingFootFraction,
                        maxActuationBudgetPerCycle = config.simulation.maxActuationBudgetPerCycle
                    )
                }
            }

            while (nextPopulation.size < config.populationSize) {
                if (!evolutionCheckpoint(controller)) {
                    return result()
                }
                val parentA = tournament(scored, config.tournamentSize, random).genome
                val parentB = tournament(scored, config.tournamentSize, random).genome
                var child =
                    if (chance(random, config.crossoverRate)) crossover(parentA, parentB, random)
                    else parentA

                val mutationCount = random.nextInt(mutationRange.first, mutationRange.last + 1)
                repeat(mutationCount) {
                    child = mutate(child, random, config)
                }

                nextPopulation += repairGenome(child, random, config)
            }

            population = nextPopulation
        }

        return result()
    }

    private fun crossover(a: CreatureGenome, b: CreatureGenome, random: Random): CreatureGenome {
        val left = a.normalized()
        val right = b.normalized()
        val base = if (random.nextBoolean()) left else right
        val other = if (base === left) right else left

        val points =
            if (left.points.size == right.points.size) {
                left.points.indices.map { index ->
                    when (random.nextInt(3)) {
                        0 -> left.points[index]
                        1 -> right.points[index]
                        else -> left.points[index].lerp(right.points[index], random.nextFloat())
                    }
                }
            } else {
                base.points
            }

        val feet =
            if (left.points.size == right.points.size) {
                left.feet.indices.map { index ->
                    when (random.nextInt(3)) {
                        0 -> left.feet[index]
                        1 -> right.feet[index]
                        else -> mixContact(left.feet[index], right.feet[index], random)
                    }
                }
            } else {
                base.feet
            }

        val mixedEdges = crossoverEdges(base, other, points, random)
        val compatibleJoints = crossoverJoints(base, other, random)
        return CreatureGenome(
            points = centerPoints(points),
            edges = mixedEdges,
            feet = feet,
            joints = compatibleJoints,
            clockPeriod = lerp(base.clockPeriod, other.clockPeriod, random.nextFloat()),
            massPerLength = lerp(base.massPerLength, other.massPerLength, random.nextFloat()),
            basePointMass = lerp(base.basePointMass, other.basePointMass, random.nextFloat()),
            springStiffness = lerp(base.springStiffness, other.springStiffness, random.nextFloat()),
            damping = lerp(base.damping, other.damping, random.nextFloat()),
            freeDrag = lerp(base.freeDrag, other.freeDrag, random.nextFloat()),
            footDrag = lerp(base.footDrag, other.footDrag, random.nextFloat()),
            footStickiness = lerp(base.footStickiness, other.footStickiness, random.nextFloat()),
            jointTorqueScale = lerp(base.jointTorqueScale, other.jointTorqueScale, random.nextFloat())
        ).normalized()
            .repairNearZeroEdges()
            .repairConnectivity()
            .repairCrossingEdges()
            .pruneInvalidJoints()
            .withJointPopulation(random)
    }

    private fun mutate(genome: CreatureGenome, random: Random, config: EvolutionConfig): CreatureGenome {
        val normalized = genome.normalized()
        val mutated =
            when (random.nextInt(19)) {
                0, 1 -> mutatePoints(normalized, random)
                2 -> relaxSkeleton(normalized, random)
                3, 4 -> mutateFeet(normalized, random)
                5 -> swapFootActivationTiming(normalized, random)
                6 -> copyFootActivation(normalized, random)
                7, 8 -> mutateJoints(normalized, random)
                9 -> swapJointActivationTiming(normalized, random)
                10 -> copyJointActivation(normalized, random)
                11 -> mutateGlobals(normalized, random)
                12 -> splitRandomEdge(normalized, random, config.maxPoints)
                13 -> addRandomEdge(normalized, random)
                14 -> addRandomPoint(normalized, random, config.maxPoints)
                15 -> splitPointIntoTwo(normalized, random, config.maxPoints)
                16 -> dropRandomPoint(normalized, random, config.minPoints)
                17 -> removeLeafPoint(normalized, random, config.minPoints)
                18 -> removeRedundantEdge(normalized, random)
                else -> normalized
            }
        return repairGenome(mutated, random, config)
    }

    private fun relaxSkeleton(genome: CreatureGenome, random: Random): CreatureGenome {
        val adjacency = buildAdjacency(genome.points.size, genome.edges)
        if (adjacency.all { it.isEmpty() }) return genome

        var points = genome.points
        val factor = randomFloat(random, 0.12f, 0.42f)
        val passes = random.nextInt(1, 4)
        repeat(passes) {
            val next = points.toMutableList()
            val indices =
                adjacency.indices
                    .filter { adjacency[it].isNotEmpty() }
                    .shuffled(random)
                    .take(max(1, adjacency.count { it.isNotEmpty() } / 2))

            indices.forEach { index ->
                val centroid = centroidOf(adjacency[index], points)
                next[index] = points[index].lerp(centroid, factor)
            }
            points = next
        }
        return genome.copy(points = centerPoints(points))
    }

    private fun mutatePoints(genome: CreatureGenome, random: Random): CreatureGenome {
        val points = genome.points.toMutableList()
        val edits = random.nextInt(1, min(3, points.size) + 1)
        repeat(edits) {
            val index = random.nextInt(points.size)
            val point = points[index]
            points[index] = Float2(
                point.x + gaussian(random, 0.18f),
                point.y + gaussian(random, 0.18f)
            )
        }
        return genome.copy(points = centerPoints(points))
    }

    private fun mutateFeet(genome: CreatureGenome, random: Random): CreatureGenome {
        val feet = genome.feet.toMutableList()
        val edits = random.nextInt(1, min(3, feet.size) + 1)
        repeat(edits) {
            val index = random.nextInt(feet.size)
            val current = feet[index]
            feet[index] = current.copy(
                phase = wrap01(current.phase + gaussian(random, 0.08f)),
                duty = (current.duty + gaussian(random, 0.07f)).coerceIn(0.05f, 0.75f),
                smooth = (current.smooth + gaussian(random, 0.07f)).coerceIn(0.05f, 0.9f),
                strength = (current.strength + gaussian(random, 0.18f)).coerceIn(0f, 1f)
            )
        }
        if (feet.size >= 2 && chance(random, 0.2f)) {
            val a = random.nextInt(feet.size)
            var b = random.nextInt(feet.size)
            if (a == b) b = (b + 1) % feet.size
            val temp = feet[a]
            feet[a] = feet[b].copy(phase = temp.phase)
            feet[b] = temp.copy(phase = feet[b].phase)
        }
        return genome.copy(feet = feet)
    }

    private fun swapFootActivationTiming(genome: CreatureGenome, random: Random): CreatureGenome {
        if (genome.feet.size < 2) return genome
        val feet = genome.feet.toMutableList()
        val a = random.nextInt(feet.size)
        var b = random.nextInt(feet.size)
        if (a == b) b = (b + 1) % feet.size

        val phase = feet[a].phase
        feet[a] = feet[a].copy(phase = feet[b].phase)
        feet[b] = feet[b].copy(phase = phase)
        return genome.copy(feet = feet)
    }

    private fun copyFootActivation(genome: CreatureGenome, random: Random): CreatureGenome {
        if (genome.feet.size < 2) return genome
        val feet = genome.feet.toMutableList()
        val source = random.nextInt(feet.size)
        var target = random.nextInt(feet.size)
        if (source == target) target = (target + 1) % feet.size

        feet[target] = feet[source].copy(
            phase = wrap01(feet[source].phase + gaussian(random, 0.03f)),
            strength = feet[source].strength.coerceIn(0f, 1f)
        )
        return genome.copy(feet = feet)
    }

    private fun mutateJoints(genome: CreatureGenome, random: Random): CreatureGenome {
        val candidates = candidateJointKeys(buildAdjacency(genome.points.size, genome.edges))
        val joints = genome.joints.toMutableList()

        if (joints.isNotEmpty() && chance(random, 0.65f)) {
            val index = random.nextInt(joints.size)
            val current = joints[index]
            joints[index] =
                when (random.nextInt(4)) {
                    0 -> current.copy(targets = mutateJointTargets(current.targets, random))
                    1 -> current.copy(targets = smoothJointTargets(current.targets, passes = random.nextInt(1, 3)))
                    2 -> current.shifted(random.nextInt(1, JOINT_TARGET_KNOT_COUNT))
                    else -> current.copy(targets = scaleJointTargets(current.targets, randomFloat(random, 0.75f, 1.2f)))
                }
        }

        if (joints.isNotEmpty() && chance(random, 0.18f)) {
            joints.removeAt(random.nextInt(joints.size))
        }

        if (candidates.isNotEmpty() && (joints.isEmpty() || chance(random, 0.3f))) {
            val occupied = joints.map { it.key() }.toHashSet()
            val available = candidates.filterNot { it in occupied }
            if (available.isNotEmpty()) {
                val key = available.random(random)
                joints += randomJoint(random, key)
            }
        }

        return genome.copy(joints = joints)
    }

    private fun swapJointActivationTiming(genome: CreatureGenome, random: Random): CreatureGenome {
        if (genome.joints.size < 2) return genome
        val joints = genome.joints.toMutableList()
        val a = random.nextInt(joints.size)
        var b = random.nextInt(joints.size)
        if (a == b) b = (b + 1) % joints.size

        val aShift = random.nextInt(1, JOINT_TARGET_KNOT_COUNT)
        val bShift = random.nextInt(1, JOINT_TARGET_KNOT_COUNT)
        joints[a] = joints[a].shifted(bShift)
        joints[b] = joints[b].shifted(aShift)
        return genome.copy(joints = joints)
    }

    private fun copyJointActivation(genome: CreatureGenome, random: Random): CreatureGenome {
        if (genome.joints.isEmpty()) return genome

        val joints = genome.joints.toMutableList()
        val sourceIndex = random.nextInt(joints.size)
        val source = joints[sourceIndex]

        if (joints.size >= 2 && chance(random, 0.65f)) {
            var targetIndex = random.nextInt(joints.size)
            if (targetIndex == sourceIndex) targetIndex = (targetIndex + 1) % joints.size
            val target = joints[targetIndex]
            joints[targetIndex] = target.copy(
                targets = mutateJointTargets(source.targets, random, edits = 1, sigma = 0.05f)
            )
            return genome.copy(joints = joints)
        }

        val occupied = joints.map { it.key() }.toHashSet()
        val available = candidateJointKeys(buildAdjacency(genome.points.size, genome.edges)).filterNot { it in occupied }
        if (available.isEmpty()) return genome

        val key = available.random(random)
        joints += JointProgram(
            pivot = key.pivot,
            a = key.a,
            b = key.b,
            targets = mutateJointTargets(source.targets, random, edits = 1, sigma = 0.05f)
        )
        return genome.copy(joints = joints)
    }

    private fun mutateGlobals(genome: CreatureGenome, random: Random): CreatureGenome =
        genome.copy(
            clockPeriod = (genome.clockPeriod + gaussian(random, 0.08f)).coerceIn(0.3f, 3f),
            massPerLength = (genome.massPerLength + gaussian(random, 0.12f)).coerceIn(0.1f, 4f),
            basePointMass = (genome.basePointMass + gaussian(random, 0.06f)).coerceIn(0.05f, 3f),
            springStiffness = (genome.springStiffness + gaussian(random, 0.18f))
                .coerceIn(4.0f, MAX_SPRING_STIFFNESS),
            damping = (genome.damping + gaussian(random, 0.01f)).coerceIn(0.8f, 0.995f),
            freeDrag = (genome.freeDrag + gaussian(random, 5f)).coerceIn(0f, 250f),
            footDrag = (genome.footDrag + gaussian(random, 40f)).coerceIn(0f, 1500f),
            footStickiness = (genome.footStickiness + gaussian(random, 90f)).coerceIn(0f, 2500f),
            jointTorqueScale = (genome.jointTorqueScale + gaussian(random, 8f)).coerceIn(5f, 180f)
        )

    private fun splitRandomEdge(genome: CreatureGenome, random: Random, maxPoints: Int): CreatureGenome {
        if (genome.points.size >= maxPoints || genome.edges.isEmpty()) return genome
        val edge = genome.edges.random(random)
        val a = genome.points[edge.a]
        val b = genome.points[edge.b]
        val midpoint = (a + b) * 0.5f
        val edgeDir = (b - a).normalizedOr(Float2(1f, 0f))
        val normal = edgeDir.perpendicular()
        val offset = normal * gaussian(random, 0.18f)
        val newPointIndex = genome.points.size
        val newPoints = genome.points + (midpoint + offset)
        val newFeet = genome.feet + mixContact(genome.feet[edge.a], genome.feet[edge.b], random).copy(
            strength = max(genome.feet[edge.a].strength, genome.feet[edge.b].strength) * 0.5f
        )
        val newEdges = genome.edges.filterNot { it.normalized() == edge.normalized() } + listOf(
            EdgeGene(edge.a, newPointIndex),
            EdgeGene(newPointIndex, edge.b)
        )
        return genome.copy(points = centerPoints(newPoints), edges = newEdges, feet = newFeet)
            .refreshJoints(random)
    }

    private fun addRandomPoint(genome: CreatureGenome, random: Random, maxPoints: Int): CreatureGenome {
        if (genome.points.size >= maxPoints) return genome

        val adjacency = buildAdjacency(genome.points.size, genome.edges)
        val degrees = degreeCounts(genome.points.size, genome.edges)
        val anchors = genome.points.indices.filter { degrees[it] < 4 }
        if (anchors.isEmpty()) return genome

        repeat(24) {
            val anchor = anchors.random(random)
            val anchorPoint = genome.points[anchor]
            val neighborhood = adjacency[anchor]
            val radial =
                if (neighborhood.isEmpty()) {
                    randomUnitVector(random)
                } else {
                    (anchorPoint - centroidOf(neighborhood, genome.points)).normalizedOr(randomUnitVector(random))
                }
            val direction =
                radial.lerp(randomUnitVector(random), randomFloat(random, 0.2f, 0.8f))
                    .normalizedOr(radial)
            val candidatePoint =
                anchorPoint +
                    direction * randomFloat(random, 0.55f, 1.2f) +
                    Float2(gaussian(random, 0.08f), gaussian(random, 0.08f))

            val newIndex = genome.points.size
            val newPoints = genome.points + candidatePoint
            val primaryEdge = EdgeGene(anchor, newIndex)
            if (wouldCrossExistingEdges(newPoints, genome.edges, primaryEdge)) return@repeat

            val newEdges = genome.edges.toMutableList()
            newEdges += primaryEdge
            val availablePartners =
                genome.points.indices
                    .filter { it != anchor && degrees[it] < 4 }
                    .sortedBy { distance(candidatePoint, genome.points[it]) }

            for (partner in availablePartners) {
                val edge = EdgeGene(partner, newIndex)
                val length = distance(candidatePoint, genome.points[partner])
                if (length !in 0.45f..2.5f) continue
                if (!wouldCrossExistingEdges(newPoints, newEdges, edge)) {
                    newEdges += edge
                    break
                }
            }

            val sourceFoot = genome.feet[anchor]
            val newFeet = genome.feet + sourceFoot.copy(
                phase = wrap01(sourceFoot.phase + gaussian(random, 0.08f)),
                duty = (sourceFoot.duty + gaussian(random, 0.04f)).coerceIn(0f, 0.85f),
                smooth = (sourceFoot.smooth + gaussian(random, 0.04f)).coerceIn(0.05f, 0.95f),
                strength = (sourceFoot.strength * randomFloat(random, 0.45f, 1f)).coerceIn(0f, 1f)
            )

            return genome.copy(
                points = centerPoints(newPoints),
                edges = newEdges,
                feet = newFeet
            ).refreshJoints(random)
        }

        return genome
    }

    private fun addRandomEdge(genome: CreatureGenome, random: Random): CreatureGenome {
        val degrees = degreeCounts(genome.points.size, genome.edges)
        val existing = genome.edges.map { it.normalized() }.toHashSet()
        val candidates = mutableListOf<EdgeGene>()
        for (a in genome.points.indices) {
            if (degrees[a] >= 4) continue
            for (b in a + 1 until genome.points.size) {
                if (degrees[b] >= 4) continue
                val edge = EdgeGene(a, b)
                if (edge in existing) continue
                val length = distance(genome.points[a], genome.points[b])
                if (length in 0.4f..2.5f && !wouldCrossExistingEdges(genome.points, genome.edges, edge)) {
                    candidates += edge
                }
            }
        }
        if (candidates.isEmpty()) return genome
        return genome.copy(edges = genome.edges + candidates.random(random)).refreshJoints(random)
    }

    private fun splitPointIntoTwo(genome: CreatureGenome, random: Random, maxPoints: Int): CreatureGenome {
        if (genome.points.size >= maxPoints) return genome

        val adjacency = buildAdjacency(genome.points.size, genome.edges)
        val candidates = adjacency.indices.filter { adjacency[it].size >= 2 }
        if (candidates.isEmpty()) return genome

        repeat(24) {
            val pointIndex = candidates.random(random)
            val neighbors = adjacency[pointIndex].shuffled(random)
            if (neighbors.size < 2) return@repeat

            val splitAt = random.nextInt(1, neighbors.size)
            val keepNeighbors = neighbors.take(splitAt)
            val movedNeighbors = neighbors.drop(splitAt)
            if (keepNeighbors.isEmpty() || movedNeighbors.isEmpty()) return@repeat

            val base = genome.points[pointIndex]
            val offsetDirection =
                (base - centroidOf(neighbors, genome.points)).perpendicular().normalizedOr(randomUnitVector(random))
            val offset = offsetDirection * randomFloat(random, 0.12f, 0.32f)
            val newIndex = genome.points.size
            val newPoints = genome.points.toMutableList().apply {
                this[pointIndex] = base + offset
                add(base - offset)
            }

            val newEdges = genome.edges.filterNot { it.a == pointIndex || it.b == pointIndex }.toMutableList()
            keepNeighbors.forEach { neighbor -> newEdges += EdgeGene(pointIndex, neighbor) }
            movedNeighbors.forEach { neighbor -> newEdges += EdgeGene(newIndex, neighbor) }
            newEdges += EdgeGene(pointIndex, newIndex)

            val sourceFoot = genome.feet[pointIndex]
            val newFeet = genome.feet + sourceFoot.copy(
                phase = wrap01(sourceFoot.phase + gaussian(random, 0.1f)),
                duty = (sourceFoot.duty + gaussian(random, 0.04f)).coerceIn(0f, 0.85f),
                smooth = (sourceFoot.smooth + gaussian(random, 0.04f)).coerceIn(0.05f, 0.95f),
                strength = (sourceFoot.strength * randomFloat(random, 0.6f, 1f)).coerceIn(0f, 1f)
            )

            return genome.copy(
                points = centerPoints(newPoints),
                edges = newEdges,
                feet = newFeet
            ).refreshJoints(random)
        }

        return genome
    }

    private fun dropRandomPoint(genome: CreatureGenome, random: Random, minPoints: Int): CreatureGenome {
        if (genome.points.size <= minPoints) return genome

        val adjacency = buildAdjacency(genome.points.size, genome.edges)
        val candidates = genome.points.indices
        val scored = candidates.map { index ->
            val removed = genome.removePoint(index)
            Triple(index, connectedComponents(removed.points.size, removed.edges).size, adjacency[index].size)
        }
        val fewestComponents = scored.minOfOrNull { it.second } ?: return genome
        val lowestDegree = scored.filter { it.second == fewestComponents }.minOfOrNull { it.third } ?: return genome
        val pool = scored.filter { it.second == fewestComponents && it.third <= lowestDegree + 1 }
        val removeIndex = pool.random(random).first

        var removed = genome.removePoint(removeIndex)
        val neighbors = adjacency[removeIndex]
        if (neighbors.size == 2) {
            val a = remapAfterRemoval(neighbors[0], removeIndex)
            val b = remapAfterRemoval(neighbors[1], removeIndex)
            val bridge = EdgeGene(a, b).normalized()
            val existing = removed.edges.map { it.normalized() }.toHashSet()
            if (a != b && bridge !in existing && distance(removed.points[a], removed.points[b]) > MIN_REST_EDGE_LENGTH) {
                removed = removed.copy(edges = removed.edges + bridge)
            }
        }
        return removed.refreshJoints(random)
    }

    private fun removeLeafPoint(genome: CreatureGenome, random: Random, minPoints: Int): CreatureGenome {
        if (genome.points.size <= minPoints) return genome
        val adjacency = buildAdjacency(genome.points.size, genome.edges)
        val leafCandidates = adjacency.indices.filter { adjacency[it].size == 1 }
        if (leafCandidates.isEmpty()) return genome
        val removeIndex = leafCandidates.random(random)
        return genome.removePoint(removeIndex).refreshJoints(random)
    }

    private fun removeRedundantEdge(genome: CreatureGenome, random: Random): CreatureGenome {
        val bridges = bridgeSet(genome.points.size, genome.edges)
        val removable = genome.edges.filterNot { it.normalized() in bridges }
        if (removable.isEmpty()) return genome
        val edge = removable.random(random)
        return genome.copy(edges = genome.edges.filterNot { it.normalized() == edge.normalized() }).refreshJoints(random)
    }

    private fun CreatureGenome.removePoint(index: Int): CreatureGenome {
        val newPoints = points.filterIndexed { pointIndex, _ -> pointIndex != index }
        val newFeet = feet.filterIndexed { pointIndex, _ -> pointIndex != index }
        fun remap(value: Int): Int = if (value < index) value else value - 1
        val newEdges = edges.mapNotNull { edge ->
            if (edge.a == index || edge.b == index) null
            else EdgeGene(remap(edge.a), remap(edge.b))
        }
        val newJoints = joints.mapNotNull { joint ->
            if (joint.pivot == index || joint.a == index || joint.b == index) null
            else JointProgram(
                pivot = remap(joint.pivot),
                a = remap(joint.a),
                b = remap(joint.b),
                targets = joint.targets
            )
        }
        return copy(points = centerPoints(newPoints), feet = newFeet, edges = newEdges, joints = newJoints)
    }

    private fun CreatureGenome.refreshJoints(random: Random, targetJointActivations: Int? = null): CreatureGenome {
        val normalized = normalized()
        val candidateSet = candidateJointKeys(buildAdjacency(normalized.points.size, normalized.edges)).toHashSet()
        val kept = normalized.joints.filter { it.key() in candidateSet }
        return normalized.copy(joints = kept).withJointPopulation(random, targetJointActivations)
    }

    private fun CreatureGenome.withJointPopulation(random: Random, targetJointActivations: Int? = null): CreatureGenome {
        val normalized = normalized()
        val candidates = candidateJointKeys(buildAdjacency(normalized.points.size, normalized.edges))
        if (candidates.isEmpty()) return normalized.copy(joints = emptyList())

        val current = normalized.joints.associateBy { it.key() }.toMutableMap()
        val desired = targetJointActivations?.coerceIn(1, candidates.size)
            ?: min(max(1, normalized.points.size - 2), min(candidates.size, normalized.points.size * 2))
        val target = if (current.isEmpty()) random.nextInt(1, desired + 1) else desired

        while (current.size < target) {
            val available = candidates.filterNot { it in current.keys }
            if (available.isEmpty()) break
            val key = available.random(random)
            current[key] = randomJoint(random, key)
        }

        return normalized.copy(
            joints = current.values
                .sortedWith(compareBy<JointProgram> { it.pivot }.thenBy { it.a }.thenBy { it.b })
        )
    }

    private fun CreatureGenome.pruneInvalidJoints(): CreatureGenome {
        val normalized = normalized()
        val candidateSet = candidateJointKeys(buildAdjacency(normalized.points.size, normalized.edges)).toHashSet()
        return normalized.copy(joints = normalized.joints.filter { it.key() in candidateSet })
    }

    private fun repairGenome(
        genome: CreatureGenome,
        random: Random,
        config: EvolutionConfig
    ): CreatureGenome {
        var repaired = genome.normalized()
        repaired = repaired.repairNearZeroEdges()
        repaired = repaired.repairConnectivity()
        repaired = repaired.repairCrossingEdges()
        repaired = repaired.repairEdgeCount(random, config.targetEdgeCount)
        repaired = repaired.repairNearZeroEdges()
        repaired = repaired.repairConnectivity()
        repaired = repaired.repairCrossingEdges()
        repaired = repaired.repairAlwaysUpFeet(config.alwaysUpFootFraction)
        repaired = repaired.repairCyclingFeet(config.cyclingFootFraction, random)
        repaired = repaired.refreshJoints(random, config.targetJointActivations)
        repaired = repaired.repairActuationBudget(config.simulation.maxActuationBudgetPerCycle)
        return repaired.normalized()
    }

    private fun CreatureGenome.repairNearZeroEdges(): CreatureGenome {
        var repaired = normalized()
        var guard = 0
        while (guard < 64) {
            val degenerate = repaired.edges.firstOrNull {
                distance(repaired.points[it.a], repaired.points[it.b]) <= MIN_REST_EDGE_LENGTH
            }?.normalized() ?: break

            val merged = repaired.tryMergeEdgeEndpoints(degenerate)
            repaired =
                when {
                    merged != null -> merged
                    else -> repaired.dropEdge(degenerate)
                }.pruneInvalidJoints().normalized()
            guard++
        }
        return repaired
    }

    private fun CreatureGenome.repairCrossingEdges(): CreatureGenome {
        if (edges.size < 2) return this

        var repaired = normalized()
        var guard = 0
        while (guard < 64) {
            val crossing = findFirstEdgeCrossing(repaired.points, repaired.edges) ?: break
            val first = repaired.edges[crossing.firstIndex].normalized()
            val second = repaired.edges[crossing.secondIndex].normalized()
            val newPointIndex = repaired.points.size
            val newPoints = repaired.points + crossing.point
            val newFeet = repaired.feet + ContactProgram(phase = 0f, duty = 0f, strength = 0f)
            val newEdges =
                repaired.edges.filterIndexed { index, _ ->
                    index != crossing.firstIndex && index != crossing.secondIndex
                } + listOf(
                    EdgeGene(first.a, newPointIndex),
                    EdgeGene(first.b, newPointIndex),
                    EdgeGene(second.a, newPointIndex),
                    EdgeGene(second.b, newPointIndex)
                )

            repaired = repaired.copy(
                points = centerPoints(newPoints),
                feet = newFeet,
                edges = newEdges
            ).normalized()
            guard++
        }
        return repaired
    }

    private fun CreatureGenome.repairConnectivity(): CreatureGenome {
        var repaired = normalized()
        var guard = 0
        while (guard < 64) {
            val components = connectedComponents(repaired.points.size, repaired.edges)
            if (components.size <= 1) break

            val degrees = degreeCounts(repaired.points.size, repaired.edges)
            val candidate = bestConnectionCandidate(repaired.points, components, degrees)
            repaired =
                if (candidate != null) {
                    repaired.copy(edges = repaired.edges + EdgeGene(candidate.a, candidate.b))
                } else {
                    val primary = components.maxWithOrNull(
                        compareBy<List<Int>>({ it.size }, { -it.minOrNull().orEmptyIndex() })
                    ) ?: break
                    repaired.keepOnlyPoints(primary.toHashSet())
                }.pruneInvalidJoints().normalized()
            guard++
        }
        return repaired
    }

    private fun CreatureGenome.tryMergeEdgeEndpoints(edge: EdgeGene): CreatureGenome? {
        if (points.size <= 2) return null

        val keep = min(edge.a, edge.b)
        val remove = max(edge.a, edge.b)
        val mergedPoint = (points[keep] + points[remove]) * 0.5f
        val mergedFoot = mergeContacts(feet[keep], feet[remove])

        fun remap(value: Int): Int =
            when {
                value == remove -> keep
                value < remove -> value
                else -> value - 1
            }

        val newPoints = points.toMutableList().apply {
            this[keep] = mergedPoint
            removeAt(remove)
        }
        val newFeet = feet.toMutableList().apply {
            this[keep] = mergedFoot
            removeAt(remove)
        }
        val newEdges = edges.mapNotNull { current ->
            val remapped = EdgeGene(remap(current.a), remap(current.b)).normalized()
            if (remapped.a == remapped.b) null else remapped
        }
        if (newEdges.isEmpty()) return null

        val newJoints = joints.mapNotNull { joint ->
            val remapped = JointProgram(
                pivot = remap(joint.pivot),
                a = remap(joint.a),
                b = remap(joint.b),
                targets = joint.targets
            ).normalized()
            if (remapped.a == remapped.b || remapped.a == remapped.pivot || remapped.b == remapped.pivot) null else remapped
        }

        val merged = copy(
            points = centerPoints(newPoints),
            feet = newFeet,
            edges = newEdges,
            joints = newJoints
        ).normalized()

        val maxDegree = degreeCounts(merged.points.size, merged.edges).maxOrNull() ?: 0
        return if (maxDegree <= 4) merged else null
    }

    private fun CreatureGenome.dropEdge(edge: EdgeGene): CreatureGenome =
        copy(edges = edges.filterNot { it.normalized() == edge.normalized() }).normalized()

    private fun CreatureGenome.keepOnlyPoints(keep: Set<Int>): CreatureGenome {
        if (keep.isEmpty() || keep.size == points.size) return this

        val ordered = keep.toList().sorted()
        val remap = IntArray(points.size) { -1 }
        ordered.forEachIndexed { newIndex, oldIndex -> remap[oldIndex] = newIndex }

        val newPoints = ordered.map { points[it] }
        val newFeet = ordered.map { feet[it] }
        val newEdges = edges.mapNotNull { edge ->
            val a = remap[edge.a]
            val b = remap[edge.b]
            if (a == -1 || b == -1) null else EdgeGene(a, b)
        }
        val newJoints = joints.mapNotNull { joint ->
            val pivot = remap[joint.pivot]
            val a = remap[joint.a]
            val b = remap[joint.b]
            if (pivot == -1 || a == -1 || b == -1) null
            else JointProgram(
                pivot = pivot,
                a = a,
                b = b,
                targets = joint.targets
            )
        }

        return copy(
            points = centerPoints(newPoints),
            feet = newFeet,
            edges = newEdges,
            joints = newJoints
        ).normalized()
    }

    private fun CreatureGenome.repairEdgeCount(random: Random, targetEdgeCount: Int?): CreatureGenome {
        if (targetEdgeCount == null) return this
        val desired = targetEdgeCount.coerceIn(points.size - 1, maxReachableEdgeCount(points.size))
        var repaired = this
        var guard = 0
        while (repaired.edges.size < desired && guard < 32) {
            val next = addRandomEdge(repaired, random)
            if (next.edges.size == repaired.edges.size) break
            repaired = next
            guard++
        }

        guard = 0
        while (repaired.edges.size > desired && guard < 32) {
            val next = removeRedundantEdge(repaired, random)
            if (next.edges.size == repaired.edges.size) break
            repaired = next
            guard++
        }
        return repaired
    }

    private fun CreatureGenome.repairAlwaysUpFeet(alwaysUpFootFraction: Float): CreatureGenome {
        val fraction = alwaysUpFootFraction.coerceIn(0f, 1f)
        if (fraction <= 1e-6f) return normalized()
        val pointCount = points.size
        val alwaysUpCount = floor(pointCount * fraction).toInt().coerceIn(0, pointCount)
        if (alwaysUpCount <= 0) return normalized()
        if (alwaysUpCount >= pointCount) {
            return copy(feet = List(pointCount) { ContactProgram(phase = 0f, duty = 0f, strength = 0f) })
        }

        val normalized = normalized()
        val ranked = normalized.feet.indices.sortedWith(
            compareByDescending<Int> { normalized.feet[it].strength }
                .thenBy { normalized.points[it].y }
        )
        val alwaysUpSet = ranked.takeLast(alwaysUpCount).toHashSet()
        val repairedFeet = normalized.feet.mapIndexed { index, foot ->
            if (index in alwaysUpSet) {
                ContactProgram(
                    phase = 0f,
                    duty = 0f,
                    smooth = foot.smooth.coerceIn(0.05f, 0.95f),
                    strength = 0f
                )
            } else {
                foot.normalized()
            }
        }
        return normalized.copy(feet = repairedFeet)
    }

    private fun CreatureGenome.repairCyclingFeet(cyclingFootFraction: Float, random: Random): CreatureGenome {
        val fraction = cyclingFootFraction.coerceIn(0f, 1f)
        val normalized = normalized()
        if (fraction <= 1e-6f) return normalized
        val pointCount = normalized.points.size
        val requiredCyclingCount = ceil(pointCount * fraction).toInt().coerceIn(0, pointCount)
        if (requiredCyclingCount <= 0) return normalized

        val feet = normalized.feet.toMutableList()
        val currentCycling = feet.indices.filter { feet[it].hasFootCycleEvent() }.toMutableSet()
        if (currentCycling.size >= requiredCyclingCount) return normalized

        val candidates =
            feet.indices
                .filterNot { it in currentCycling }
                .sortedWith(
                    compareBy<Int> { feet[it].isAlwaysUpFoot() }
                        .thenByDescending { feet[it].strength }
                        .thenBy { normalized.points[it].y }
                )

        candidates.forEach { index ->
            if (currentCycling.size >= requiredCyclingCount) return@forEach
            feet[index] = feet[index].asCyclingFoot(random)
            currentCycling += index
        }

        return normalized.copy(feet = feet)
    }

    private fun ContactProgram.isAlwaysUpFoot(): Boolean {
        val normalized = normalized()
        return normalized.strength <= 1e-4f || normalized.duty <= 1e-4f
    }

    private fun ContactProgram.hasFootCycleEvent(): Boolean {
        val normalized = normalized()
        return normalized.strength > 1e-4f && normalized.duty > 1e-4f && normalized.duty < 0.95f
    }

    private fun ContactProgram.asCyclingFoot(random: Random): ContactProgram {
        val normalized = normalized()
        val phase =
            if (normalized.hasFootCycleEvent()) {
                normalized.phase
            } else {
                random.nextFloat()
            }
        return normalized.copy(
            phase = phase,
            duty = normalized.duty.coerceIn(0.14f, 0.72f),
            smooth = normalized.smooth.coerceIn(0.05f, 0.9f),
            strength = normalized.strength.coerceIn(0.55f, 1f)
        )
    }

    private fun CreatureGenome.repairActuationBudget(maxBudgetPerCycle: Float): CreatureGenome {
        val normalized = normalized()
        val clampedBudget = maxBudgetPerCycle.coerceAtLeast(0f)
        if (normalized.joints.isEmpty() || normalized.jointTorqueScale <= 1e-6f) return normalized
        if (clampedBudget <= 1e-6f) return normalized.copy(jointTorqueScale = 0f)

        val currentBudget = normalized.programmedActuationBudgetPerCycle()
        if (currentBudget <= clampedBudget + 1e-5f) return normalized

        val scale = (clampedBudget / currentBudget).coerceIn(0f, 1f)
        return normalized.copy(jointTorqueScale = normalized.jointTorqueScale * scale)
    }

    private fun crossoverJoints(base: CreatureGenome, other: CreatureGenome, random: Random): List<JointProgram> {
        val candidateSet = candidateJointKeys(buildAdjacency(base.points.size, base.edges)).toHashSet()
        val baseMap = base.joints.filter { it.key() in candidateSet }.associateBy { it.key() }
        val otherMap = other.joints.filter { it.key() in candidateSet }.associateBy { it.key() }
        val keys = (baseMap.keys + otherMap.keys).toMutableSet()
        if (keys.isEmpty()) return emptyList()

        val joints = mutableListOf<JointProgram>()
        keys.shuffled(random).forEach { key ->
            val left = baseMap[key]
            val right = otherMap[key]
            val selected =
                when {
                    left != null && right != null -> {
                        when (random.nextInt(3)) {
                            0 -> left
                            1 -> right
                            else -> JointProgram(
                                pivot = key.pivot,
                                a = key.a,
                                b = key.b,
                                targets = mixJointTargets(left.targets, right.targets, random)
                            )
                        }
                    }
                    left != null -> left
                    else -> right
                } ?: return@forEach

            if (chance(random, 0.8f)) joints += selected
        }
        return joints.distinctBy { it.key() }
    }

    private fun crossoverEdges(
        base: CreatureGenome,
        other: CreatureGenome,
        points: List<Float2>,
        random: Random
    ): List<EdgeGene> {
        val pointCount = points.size
        val baseEdges = base.edges
            .map { it.normalized() }
            .filter { it.a in 0 until pointCount && it.b in 0 until pointCount }
            .distinct()
        if (baseEdges.isEmpty()) return emptyList()
        if (other.points.size != pointCount) return baseEdges

        val otherEdges = other.edges
            .map { it.normalized() }
            .filter { it.a in 0 until pointCount && it.b in 0 until pointCount }
            .distinct()

        val baseSet = baseEdges.toHashSet()
        val otherSet = otherEdges.toHashSet()
        val shared = baseEdges.filter { it in otherSet }
        val exclusive = (baseEdges + otherEdges)
            .distinct()
            .filterNot { it in shared }
            .shuffled(random)

        val selected = mutableListOf<EdgeGene>()
        val degrees = IntArray(pointCount)

        fun tryAdd(edge: EdgeGene): Boolean {
            val normalized = edge.normalized()
            if (normalized.a == normalized.b) return false
            if (degrees[normalized.a] >= 4 || degrees[normalized.b] >= 4) return false
            if (distance(points[normalized.a], points[normalized.b]) <= MIN_REST_EDGE_LENGTH) return false
            if (wouldCrossExistingEdges(points, selected, normalized)) return false
            selected += normalized
            degrees[normalized.a]++
            degrees[normalized.b]++
            return true
        }

        shared.shuffled(random).forEach(::tryAdd)

        val targetEdgeCount = max(
            1,
            (((baseEdges.size + otherEdges.size) * 0.5f).roundToInt())
        ).coerceAtMost(maxReachableEdgeCount(pointCount))

        exclusive.forEach { edge ->
            val mustFill = selected.size < targetEdgeCount
            val keep = mustFill || chance(random, 0.6f)
            if (keep) tryAdd(edge)
        }

        if (selected.isEmpty()) {
            baseEdges.shuffled(random).firstOrNull { tryAdd(it) }
        }

        return selected.ifEmpty { baseEdges }
    }

    private fun mixContact(a: ContactProgram, b: ContactProgram, random: Random): ContactProgram =
        ContactProgram(
            phase = wrap01(lerpPhase(a.phase, b.phase, random.nextFloat())),
            duty = lerp(a.duty, b.duty, random.nextFloat()),
            smooth = lerp(a.smooth, b.smooth, random.nextFloat()),
            strength = lerp(a.strength, b.strength, random.nextFloat())
        )

    private fun mergeContacts(a: ContactProgram, b: ContactProgram): ContactProgram =
        ContactProgram(
            phase = wrap01(lerpPhase(a.phase, b.phase, 0.5f)),
            duty = lerp(a.duty, b.duty, 0.5f),
            smooth = lerp(a.smooth, b.smooth, 0.5f),
            strength = max(a.strength, b.strength)
        ).normalized()

    private fun randomJoint(random: Random, key: JointKey): JointProgram =
        JointProgram(
            pivot = key.pivot,
            a = key.a,
            b = key.b,
            targets = randomJointTargets(random)
        )

    private fun tournament(
        scored: List<ScoredGenome>,
        tournamentSize: Int,
        random: Random
    ): ScoredGenome {
        var best = scored[random.nextInt(scored.size)]
        repeat(max(0, tournamentSize - 1)) {
            val challenger = scored[random.nextInt(scored.size)]
            if (challenger.evaluation.fitness > best.evaluation.fitness) {
                best = challenger
            }
        }
        return best
    }

    private fun effectiveImmigrantCount(
        config: EvolutionConfig,
        stagnating: Boolean
    ): Int {
        val fraction = if (stagnating) config.stagnationImmigrantFraction else config.immigrantFraction
        val fractionalCount = ceil(config.populationSize * fraction).toInt()
        return max(config.immigrantsPerGeneration, fractionalCount)
    }

    private fun effectiveMutationRange(
        config: EvolutionConfig,
        stagnating: Boolean
    ): IntRange =
        if (!stagnating) {
            config.mutationSteps
        } else {
            (config.mutationSteps.first + config.stagnationMutationBoost.first)..
                (config.mutationSteps.last + config.stagnationMutationBoost.last)
        }

    private suspend fun evolutionCheckpoint(controller: EvolutionController?): Boolean {
        currentCoroutineContext().ensureActive()
        if (controller?.isStopRequested() == true) return false
        kotlinx.coroutines.yield()
        currentCoroutineContext().ensureActive()
        return controller?.isStopRequested() != true
    }
}

private class CreatureRuntime(
    private val genome: CreatureGenome,
    private val config: CreatureSimulationConfig
) {
    private val pointCount = genome.points.size
    private val edgeRests = genome.edges.map {
        EdgeRest(it.a, it.b, distance(genome.points[it.a], genome.points[it.b]))
    }
    private val adjacency = buildAdjacency(pointCount, genome.edges)
    private val clock = RuntimeClock()
    // The programs are sampled at fixed phases, so sample each once per cycle step rather than every step.
    private val cycleSteps = max(1, config.stepsPerCycle)
    private val footSignal = Array(genome.feet.size) { f ->
        FloatArray(cycleSteps) { genome.feet[f].sample(it / cycleSteps.toFloat()) }
    }
    private val jointSignal = Array(genome.joints.size) { j ->
        FloatArray(cycleSteps) { genome.joints[j].sample(it / cycleSteps.toFloat()) }
    }
    private val contactValues = FloatArray(pointCount)
    private val contactAnchors = Array<Float2?>(pointCount) { null }
    private val programmedActuationBudgetPerCycle = genome.programmedActuationBudgetPerCycle()
    private val jointRestAngles = FloatArray(genome.joints.size) { jointIndex ->
        val joint = genome.joints[jointIndex]
        val a = genome.points[joint.a] - genome.points[joint.pivot]
        val b = genome.points[joint.b] - genome.points[joint.pivot]
        signedAngle(a, b)
    }
    private val filteredJointTargets = FloatArray(genome.joints.size) { jointIndex ->
        normalizeAngle(jointRestAngles[jointIndex] + genome.joints[jointIndex].sample(0f) * config.jointTargetAngleScale)
    }
    private val stepJointTorques = FloatArray(genome.joints.size)
    private val stepJointTargetSlew = FloatArray(genome.joints.size)
    private var stepActuationTorque = 0f

    private val state = SimdVerlet.State3(
        x = FloatArray(0),
        y = FloatArray(0),
        z = FloatArray(0),
        px = FloatArray(0),
        py = FloatArray(0),
        pz = FloatArray(0),
        ax = FloatArray(0),
        ay = FloatArray(0),
        az = FloatArray(0),
        invMass = FloatArray(0)
    )

    private val constraints = SimdVerlet.DirectedConstraints4(
        n0 = IntArray(pointCount) { -1 },
        n1 = IntArray(pointCount) { -1 },
        n2 = IntArray(pointCount) { -1 },
        n3 = IntArray(pointCount) { -1 },
        r0 = FloatArray(pointCount),
        r1 = FloatArray(pointCount),
        r2 = FloatArray(pointCount),
        r3 = FloatArray(pointCount),
        s0 = FloatArray(pointCount),
        s1 = FloatArray(pointCount),
        s2 = FloatArray(pointCount),
        s3 = FloatArray(pointCount)
    )

    private val sim = SimdVerlet(state, null)
    private lateinit var masses: FloatArray

    init {
        masses = pointMasses(genome.points, edgeRests, genome.massPerLength, genome.basePointMass)
        config.contact.place(genome.points).forEachIndexed { index, point ->
            val invMass = 1f / max(0.05f, masses[index])
            sim.addParticle(
                x0 = point.x,
                y0 = point.y,
                z0 = 0f,
                px0 = point.x,
                py0 = point.y,
                pz0 = 0f,
                invMass0 = invMass
            )
        }

        val restLookup = HashMap<Long, Float>(edgeRests.size * 2)
        edgeRests.forEach { edge ->
            restLookup[edgeKey(edge.a, edge.b)] = edge.restLength
        }

        adjacency.forEachIndexed { index, neighbors ->
            neighbors.forEachIndexed { slot, neighbor ->
                constraints.setSlot(
                    index = index,
                    slot = slot,
                    neighbor = neighbor,
                    restLength = restLookup[edgeKey(index, neighbor)] ?: 0f,
                    stiffness = genome.springStiffness
                )
            }
        }

        installContactForces()
        installJointForces()
    }

    fun run(): CreatureEvaluation {
        val stepsPerCycle = max(1, config.stepsPerCycle)
        val totalSteps = max(1, (config.warmupCycles + config.measuredCycles) * stepsPerCycle)
        val warmupSteps = max(0, config.warmupCycles * stepsPerCycle)
        val dt = genome.clockPeriod / stepsPerCycle.toFloat()
        val direction = config.targetDirection.normalizedOr()

        var measuredStart = currentCenter()
        val midStep = warmupSteps + (totalSteps - warmupSteps) / 2
        var midCenter: Float2? = null
        var previousCenter = measuredStart
        var finalCenter = measuredStart
        var stabilityPreviousCenter = measuredStart
        var forwardProgress = 0f
        var stretchAccum = 0f
        var actuationAccum = 0f
        var targetSlewAccum = 0f
        var speedAccum = 0f
        var measuredSamples = 0
        var exploded = false
        val frames = mutableListOf<CreatureSnapshot>()

        for (step in 0 until totalSteps) {
            val phase = wrap01((step % stepsPerCycle) / stepsPerCycle.toFloat())
            clock.phase = phase
            clock.step = step % stepsPerCycle
            stepActuationTorque = 0f
            stepJointTorques.fill(0f)
            stepJointTargetSlew.fill(0f)

            sim.step(
                dt = dt,
                damping = genome.damping,
                gravity = 0f, // added per point as a force in installContactForces: SimdVerlet would divide it by mass
                iterations = config.solverIterations,
                constraints = constraints
            )

            if (hasExploded()) {
                exploded = true
                break
            }

            val center = currentCenter()
            val centerSpeed = (center - stabilityPreviousCenter).length() / max(1e-5f, dt)
            stabilityPreviousCenter = center
            val stepMeanStretch = meanStretchError()
            val stepPeakStretch = maxStretchError()
            if (isUnstable(centerSpeed, stepMeanStretch, stepPeakStretch)) {
                exploded = true
                break
            }
            if (step == warmupSteps) {
                measuredStart = center
                previousCenter = center
            }

            if (step >= warmupSteps) {
                val delta = center - previousCenter
                forwardProgress += max(0f, delta.dot(direction))
                speedAccum += delta.length() / max(1e-5f, dt)
                stretchAccum += stepMeanStretch
                actuationAccum += stepActuationTorque / stepsPerCycle.toFloat()
                targetSlewAccum += stepJointTargetSlew.averageOrZero() / stepsPerCycle.toFloat()
                measuredSamples++
                previousCenter = center
                finalCenter = center
                if (step == midStep) midCenter = center

                if (config.recordFrames && ((step - warmupSteps) % max(1, config.recordEvery) == 0 || step == totalSteps - 1)) {
                    frames += snapshot(step, phase, center)
                }
            }
        }

        val displacement = finalCenter - measuredStart
        val netCentroidMotion = displacement.length()
        val forwardTravel = displacement.dot(direction)
        val lateralDrift = abs(displacement.perpDot(direction))
        val meanStretch = if (measuredSamples == 0) 0f else stretchAccum / measuredSamples.toFloat()
        val measuredCycles = measuredSamples.toFloat() / stepsPerCycle.toFloat()
        val meanActuationEffort = if (measuredCycles <= 1e-6f) 0f else actuationAccum / measuredCycles
        val meanTargetSlew = if (measuredCycles <= 1e-6f) 0f else targetSlewAccum / measuredCycles
        val meanSpeed = if (measuredSamples == 0) 0f else speedAccum / measuredSamples.toFloat()
        val actuationPenalty = config.actuationEffortPenalty.coerceAtLeast(0f)
        val targetSlewPenalty = config.targetSlewPenalty.coerceAtLeast(0f)

        val fitness =
            if (exploded) {
                config.explodedFitness
            } else {
                when (config.fitnessObjective) {
                    FitnessObjective.DIRECTIONAL_PROGRESS ->
                        forwardTravel +
                            0.20f * forwardProgress -
                            0.30f * lateralDrift -
                            2.25f * meanStretch -
                            actuationPenalty * meanActuationEffort -
                            targetSlewPenalty * meanTargetSlew

                    FitnessObjective.NET_CENTROID_DISPLACEMENT ->
                        netCentroidMotion -
                            2.0f * meanStretch -
                            actuationPenalty * meanActuationEffort -
                            targetSlewPenalty * meanTargetSlew
                }
            }

        val evaluation = CreatureEvaluation(
            genome = genome,
            fitness = fitness,
            netCentroidMotion = netCentroidMotion,
            displacement = displacement,
            forwardTravel = forwardTravel,
            forwardProgress = forwardProgress,
            lateralDrift = lateralDrift,
            meanStretchError = meanStretch,
            meanActuationEffort = meanActuationEffort,
            programmedActuationBudgetPerCycle = programmedActuationBudgetPerCycle,
            meanTargetSlew = meanTargetSlew,
            meanSpeed = meanSpeed,
            frames = frames,
            exploded = exploded,
            firstHalfDisplacement = (midCenter ?: finalCenter) - measuredStart,
            secondHalfDisplacement = finalCenter - (midCenter ?: finalCenter)
        )
        val custom = config.fitness
        return if (custom == null || exploded) evaluation
        else evaluation.copy(fitness = custom(evaluation).takeIf { it.isFinite() } ?: config.explodedFitness)
    }

    private fun installContactForces() {
        genome.feet.forEachIndexed { index, program ->
            val foot = config.contact.foot(index)
            sim.addForceGenerator(index, "contact-$index", generator2 {
                while (true) {
                    val ctx = yield()
                    val dt = max(1e-5f, ctx.dt)
                    val position = Float2(ctx.state.x[index], ctx.state.y[index])
                    val velocity = Float2(
                        (ctx.state.x[index] - ctx.state.px[index]) / dt,
                        (ctx.state.y[index] - ctx.state.py[index]) / dt
                    )
                    val step = FootStep(footSignal[index][clock.step], position, velocity, genome)
                    val force = foot.force(step)
                    contactValues[index] = foot.grip
                    contactAnchors[index] = foot.anchor
                    ctx.state.ax[index] += force.x
                    ctx.state.ay[index] += force.y + config.gravity * masses[index]
                }
            })
        }
    }

    private fun installJointForces() {
        genome.joints.forEachIndexed { jointIndex, joint ->
            sim.addForceGenerator(joint.pivot, "joint-$jointIndex", generator2 {
                while (true) {
                    val ctx = yield()
                    val dt = max(1e-5f, ctx.dt)
                    val pose = currentJointMotorPose(joint, jointRestAngles[jointIndex], ctx, dt) ?: continue
                    val desiredSignal = jointSignal[jointIndex][clock.step]
                    val desiredTarget = pose.restAngle + desiredSignal * config.jointTargetAngleScale
                    val previousTarget = filteredJointTargets[jointIndex]
                    val targetBlend =
                        if (config.jointTargetFilterRate <= 1e-6f) 1f
                        else (1f - exp(-config.jointTargetFilterRate * dt)).coerceIn(0f, 1f)
                    val filteredTarget = blendAngle(previousTarget, desiredTarget, targetBlend)
                    filteredJointTargets[jointIndex] = filteredTarget
                    stepJointTargetSlew[jointIndex] =
                        abs(shortestAngleDelta(previousTarget, filteredTarget)) / max(1e-5f, dt)

                    val angleError = shortestAngleDelta(pose.currentAngle, filteredTarget)

                    var appliedTorque =
                        (config.jointMotorKp * angleError - config.jointMotorKd * pose.angleVelocity) *
                            genome.jointTorqueScale
                    val filteredSignal =
                        if (abs(config.jointTargetAngleScale) <= 1e-6f) 0f
                        else shortestAngleDelta(pose.restAngle, filteredTarget) / config.jointTargetAngleScale
                    val motorIntent = max(abs(desiredSignal), abs(filteredSignal)).coerceIn(0f, 1f)
                    val maxMotorTorque = motorIntent * genome.jointTorqueScale
                    appliedTorque = appliedTorque.coerceIn(-maxMotorTorque, maxMotorTorque)
                    appliedTorque = clampJointTorqueByImpulse(appliedTorque, pose, dt)
                    stepJointTorques[jointIndex] = appliedTorque
                    if (abs(appliedTorque) <= 1e-6f) continue

                    val faX = pose.aCoeffX * appliedTorque
                    val faY = pose.aCoeffY * appliedTorque
                    val fbX = pose.bCoeffX * appliedTorque
                    val fbY = pose.bCoeffY * appliedTorque

                    ctx.state.ax[joint.a] += faX
                    ctx.state.ay[joint.a] += faY
                    ctx.state.ax[joint.b] += fbX
                    ctx.state.ay[joint.b] += fbY

                    ctx.state.ax[joint.pivot] += pose.pivotCoeffX * appliedTorque
                    ctx.state.ay[joint.pivot] += pose.pivotCoeffY * appliedTorque
                    stepActuationTorque += abs(appliedTorque)
                }
            })
        }
    }

    private fun snapshot(step: Int, phase: Float, center: Float2): CreatureSnapshot =
        CreatureSnapshot(
            step = step,
            phase = phase,
            center = center,
            points = currentPoints(),
            down = List(pointCount) { contactValues[it] > 0.35f },
            anchors = contactAnchors.toList(),
            jointTorques = stepJointTorques.toList(),
            jointTargets = filteredJointTargets.toList()
        )

    private fun currentPoints(): List<Float2> =
        List(pointCount) { index -> Float2(state.x[index], state.y[index]) }

    private fun currentCenter(): Float2 {
        var sx = 0f
        var sy = 0f
        for (i in 0 until pointCount) {
            sx += state.x[i]
            sy += state.y[i]
        }
        return Float2(sx / pointCount.toFloat(), sy / pointCount.toFloat())
    }

    private fun meanStretchError(): Float {
        if (edgeRests.isEmpty()) return 0f
        var sum = 0f
        for (edge in edgeRests) {
            val length = distance(
                Float2(state.x[edge.a], state.y[edge.a]),
                Float2(state.x[edge.b], state.y[edge.b])
            )
            sum += abs(length - edge.restLength) / max(1e-4f, edge.restLength)
        }
        return sum / edgeRests.size.toFloat()
    }

    private fun maxStretchError(): Float {
        if (edgeRests.isEmpty()) return 0f
        var maxStretch = 0f
        for (edge in edgeRests) {
            val length = distance(
                Float2(state.x[edge.a], state.y[edge.a]),
                Float2(state.x[edge.b], state.y[edge.b])
            )
            val stretch = abs(length - edge.restLength) / max(1e-4f, edge.restLength)
            if (stretch > maxStretch) {
                maxStretch = stretch
            }
        }
        return maxStretch
    }

    private fun isUnstable(centerSpeed: Float, meanStretch: Float, peakStretch: Float): Boolean {
        if (!centerSpeed.isFinite() || !meanStretch.isFinite() || !peakStretch.isFinite()) return true
        if (centerSpeed > config.maxCenterSpeed) return true
        if (meanStretch > config.maxMeanStretchError) return true
        if (peakStretch > config.maxPeakStretchError) return true
        return false
    }

    private fun currentJointMotorPose(
        joint: JointProgram,
        restAngle: Float,
        ctx: SimdVerlet.ForceContext,
        dt: Float
    ): JointMotorPose? {
        val pivot = joint.pivot
        val a = joint.a
        val b = joint.b
        val ax = ctx.state.x[a] - ctx.state.x[pivot]
        val ay = ctx.state.y[a] - ctx.state.y[pivot]
        val bx = ctx.state.x[b] - ctx.state.x[pivot]
        val by = ctx.state.y[b] - ctx.state.y[pivot]

        val aLen2 = max(1e-4f, ax * ax + ay * ay)
        val bLen2 = max(1e-4f, bx * bx + by * by)
        val aLen = sqrt(aLen2)
        val bLen = sqrt(bLen2)
        if (aLen <= 1e-4f || bLen <= 1e-4f) return null

        val pivotVx = (ctx.state.x[pivot] - ctx.state.px[pivot]) / dt
        val pivotVy = (ctx.state.y[pivot] - ctx.state.py[pivot]) / dt
        val aVx = (ctx.state.x[a] - ctx.state.px[a]) / dt - pivotVx
        val aVy = (ctx.state.y[a] - ctx.state.py[a]) / dt - pivotVy
        val bVx = (ctx.state.x[b] - ctx.state.px[b]) / dt - pivotVx
        val bVy = (ctx.state.y[b] - ctx.state.py[b]) / dt - pivotVy

        val invMass = ctx.state.invMass
        val aInvMass = invMass?.get(a) ?: 1f
        val bInvMass = invMass?.get(b) ?: 1f
        val pivotInvMass = invMass?.get(pivot) ?: 1f

        val omegaA = (ax * aVy - ay * aVx) / aLen2
        val omegaB = (bx * bVy - by * bVx) / bLen2

        val aCoeffX = ay / aLen2
        val aCoeffY = -ax / aLen2
        val bCoeffX = -by / bLen2
        val bCoeffY = bx / bLen2

        return JointMotorPose(
            restAngle = restAngle,
            currentAngle = signedAngle(Float2(ax, ay), Float2(bx, by)),
            angleVelocity = omegaB - omegaA,
            aLen2 = aLen2,
            bLen2 = bLen2,
            aInvMass = aInvMass,
            bInvMass = bInvMass,
            pivotInvMass = pivotInvMass,
            aCoeffX = aCoeffX,
            aCoeffY = aCoeffY,
            bCoeffX = bCoeffX,
            bCoeffY = bCoeffY,
            pivotCoeffX = -(aCoeffX + bCoeffX),
            pivotCoeffY = -(aCoeffY + bCoeffY)
        )
    }

    private fun clampJointTorqueByImpulse(
        torque: Float,
        pose: JointMotorPose,
        dt: Float
    ): Float {
        val maxImpulse = config.maxJointImpulsePerStep.coerceAtLeast(0f)
        if (maxImpulse <= 1e-6f || abs(torque) <= 1e-6f) return torque

        val aCoeff = (1f / sqrt(pose.aLen2)) * pose.aInvMass
        val bCoeff = (1f / sqrt(pose.bLen2)) * pose.bInvMass
        val pivotCoeff =
            sqrt(pose.pivotCoeffX * pose.pivotCoeffX + pose.pivotCoeffY * pose.pivotCoeffY) *
                pose.pivotInvMass
        val maxCoeff = max(aCoeff, max(bCoeff, pivotCoeff))
        if (maxCoeff <= 1e-6f) return torque

        val torqueLimit = maxImpulse / (dt * maxCoeff)
        return torque.coerceIn(-torqueLimit, torqueLimit)
    }

    private fun hasExploded(): Boolean {
        for (i in 0 until pointCount) {
            val x = state.x[i]
            val y = state.y[i]
            if (!x.isFinite() || !y.isFinite()) return true
            if (abs(x) > config.maxAbsCoordinate || abs(y) > config.maxAbsCoordinate) return true
        }
        return false
    }
}

private fun pointMasses(
    points: List<Float2>,
    edges: List<EdgeRest>,
    massPerLength: Float,
    basePointMass: Float
): FloatArray {
    val masses = FloatArray(points.size) { basePointMass }
    edges.forEach { edge ->
        val share = edge.restLength * massPerLength * 0.5f
        masses[edge.a] += share
        masses[edge.b] += share
    }
    return masses
}

private fun CreatureGenome.programmedActuationBudgetPerCycle(): Float {
    val normalized = normalized()
    if (normalized.joints.isEmpty() || normalized.jointTorqueScale <= 1e-6f) return 0f

    var meanAbsSignal = 0f
    normalized.joints.forEach { joint ->
        meanAbsSignal += joint.meanAbsSignal()
    }
    return meanAbsSignal * normalized.jointTorqueScale
}

private fun maxReachableEdgeCount(pointCount: Int): Int =
    min((pointCount * 4) / 2, pointCount * (pointCount - 1) / 2)

private fun buildAdjacency(pointCount: Int, edges: List<EdgeGene>): List<List<Int>> {
    val adjacency = List(pointCount) { mutableListOf<Int>() }
    edges.map { it.normalized() }.forEach { edge ->
        adjacency[edge.a] += edge.b
        adjacency[edge.b] += edge.a
    }
    return adjacency.map { it.distinct().sorted() }
}

private fun candidateJointKeys(adjacency: List<List<Int>>): List<JointKey> {
    val keys = mutableListOf<JointKey>()
    adjacency.forEachIndexed { pivot, neighbors ->
        for (i in 0 until neighbors.size) {
            for (j in i + 1 until neighbors.size) {
                val a = min(neighbors[i], neighbors[j])
                val b = max(neighbors[i], neighbors[j])
                keys += JointKey(pivot, a, b)
            }
        }
    }
    return keys
}

private fun centroidOf(indices: Iterable<Int>, points: List<Float2>): Float2 {
    var sx = 0f
    var sy = 0f
    var count = 0
    indices.forEach { index ->
        sx += points[index].x
        sy += points[index].y
        count++
    }
    return if (count == 0) Float2(0f, 0f) else Float2(sx / count.toFloat(), sy / count.toFloat())
}

private fun degreeCounts(pointCount: Int, edges: List<EdgeGene>): IntArray {
    val degrees = IntArray(pointCount)
    edges.forEach { edge ->
        degrees[edge.a]++
        degrees[edge.b]++
    }
    return degrees
}

private fun isConnected(pointCount: Int, edges: List<EdgeGene>): Boolean {
    if (pointCount == 0) return true
    if (pointCount == 1) return true
    val adjacency = buildAdjacency(pointCount, edges)
    val visited = BooleanArray(pointCount)
    val queue = ArrayDeque<Int>()
    queue += 0
    visited[0] = true
    while (queue.isNotEmpty()) {
        val next = queue.removeFirst()
        adjacency[next].forEach { neighbor ->
            if (!visited[neighbor]) {
                visited[neighbor] = true
                queue += neighbor
            }
        }
    }
    return visited.all { it }
}

private fun connectedComponents(pointCount: Int, edges: List<EdgeGene>): List<List<Int>> {
    if (pointCount == 0) return emptyList()
    val adjacency = buildAdjacency(pointCount, edges)
    val visited = BooleanArray(pointCount)
    val components = mutableListOf<List<Int>>()
    for (start in 0 until pointCount) {
        if (visited[start]) continue
        val queue = ArrayDeque<Int>()
        val component = mutableListOf<Int>()
        queue += start
        visited[start] = true
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            component += next
            adjacency[next].forEach { neighbor ->
                if (!visited[neighbor]) {
                    visited[neighbor] = true
                    queue += neighbor
                }
            }
        }
        components += component.sorted()
    }
    return components
}

private fun bestConnectionCandidate(
    points: List<Float2>,
    components: List<List<Int>>,
    degrees: IntArray
): EdgeConnectionCandidate? {
    val sortedComponents = components.sortedWith(
        compareByDescending<List<Int>> { it.size }.thenBy { it.minOrNull().orEmptyIndex() }
    )
    val primary = sortedComponents.firstOrNull() ?: return null
    var best: EdgeConnectionCandidate? = null

    sortedComponents.drop(1).forEach { other ->
        primary.forEach { a ->
            if (degrees[a] >= 4) return@forEach
            other.forEach { b ->
                if (degrees[b] >= 4) return@forEach
                val length = distance(points[a], points[b])
                if (length <= MIN_REST_EDGE_LENGTH) return@forEach
                val candidate = EdgeConnectionCandidate(a, b, length)
                if (best == null || candidate.distance < best!!.distance) {
                    best = candidate
                }
            }
        }
    }

    return best
}

private fun Int?.orEmptyIndex(): Int = this ?: Int.MAX_VALUE

private fun remapAfterRemoval(value: Int, removedIndex: Int): Int =
    if (value < removedIndex) value else value - 1

private fun bridgeSet(pointCount: Int, edges: List<EdgeGene>): Set<EdgeGene> {
    val adjacency = buildAdjacency(pointCount, edges)
    val visited = BooleanArray(pointCount)
    val tin = IntArray(pointCount) { -1 }
    val low = IntArray(pointCount) { -1 }
    val bridges = mutableSetOf<EdgeGene>()
    var timer = 0

    fun dfs(v: Int, parent: Int) {
        visited[v] = true
        tin[v] = timer
        low[v] = timer
        timer++
        adjacency[v].forEach { to ->
            if (to == parent) return@forEach
            if (visited[to]) {
                low[v] = min(low[v], tin[to])
            } else {
                dfs(to, v)
                low[v] = min(low[v], low[to])
                if (low[to] > tin[v]) {
                    bridges += EdgeGene(v, to).normalized()
                }
            }
        }
    }

    if (pointCount > 0) dfs(0, -1)
    return bridges
}

private fun edgeCrossings(points: List<Float2>, edges: List<EdgeGene>): List<EdgeCrossing> {
    val normalizedEdges = edges.map { it.normalized() }
    val crossings = mutableListOf<EdgeCrossing>()
    for (i in 0 until normalizedEdges.size) {
        val first = normalizedEdges[i]
        for (j in i + 1 until normalizedEdges.size) {
            val second = normalizedEdges[j]
            if (sharesVertex(first, second)) continue
            val point =
                properSegmentIntersection(
                    points[first.a],
                    points[first.b],
                    points[second.a],
                    points[second.b]
                ) ?: continue
            crossings += EdgeCrossing(i, j, point)
        }
    }
    return crossings
}

private fun findFirstEdgeCrossing(points: List<Float2>, edges: List<EdgeGene>): EdgeCrossing? =
    edgeCrossings(points, edges).firstOrNull()

private fun wouldCrossExistingEdges(points: List<Float2>, edges: List<EdgeGene>, candidate: EdgeGene): Boolean {
    val normalizedCandidate = candidate.normalized()
    return edges.any { existing ->
        val normalizedExisting = existing.normalized()
        !sharesVertex(normalizedExisting, normalizedCandidate) &&
            properSegmentIntersection(
                points[normalizedExisting.a],
                points[normalizedExisting.b],
                points[normalizedCandidate.a],
                points[normalizedCandidate.b]
            ) != null
    }
}

private fun sharesVertex(a: EdgeGene, b: EdgeGene): Boolean =
    a.a == b.a || a.a == b.b || a.b == b.a || a.b == b.b

private fun properSegmentIntersection(
    a0: Float2,
    a1: Float2,
    b0: Float2,
    b1: Float2,
    epsilon: Float = 1e-4f
): Float2? {
    val r = a1 - a0
    val s = b1 - b0
    val rxs = r.perpDot(s)
    if (abs(rxs) <= epsilon) return null

    val delta = b0 - a0
    val t = delta.perpDot(s) / rxs
    val u = delta.perpDot(r) / rxs
    if (t <= epsilon || t >= 1f - epsilon || u <= epsilon || u >= 1f - epsilon) return null

    return a0 + r * t
}

private fun edgeKey(a: Int, b: Int): Long {
    val lo = min(a, b)
    val hi = max(a, b)
    return (lo.toLong() shl 32) xor (hi.toLong() and 0xffffffffL)
}

private fun distance(a: Float2, b: Float2): Float =
    sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

private fun randomUnitVector(random: Random): Float2 {
    val angle = random.nextFloat() * (2f * PI.toFloat())
    return Float2(cos(angle), kotlin.math.sin(angle))
}

private fun centerPoints(points: List<Float2>): List<Float2> {
    if (points.isEmpty()) return points
    val center = points.reduce { acc, p -> acc + p } / points.size.toFloat()
    return points.map { it - center }
}

private fun lerp(a: Float, b: Float, t: Float): Float = a * (1f - t) + b * t

private fun lerpPhase(a: Float, b: Float, t: Float): Float {
    val delta = shortestPhaseDelta(a, b)
    return wrap01(a + delta * t)
}

private fun signedAngle(a: Float2, b: Float2): Float =
    atan2(a.perpDot(b), a.dot(b))

private fun shortestAngleDelta(from: Float, to: Float): Float {
    var delta = to - from
    val tau = (2f * PI.toFloat())
    while (delta > PI.toFloat()) delta -= tau
    while (delta < -PI.toFloat()) delta += tau
    return delta
}

private fun normalizeAngle(angle: Float): Float = shortestAngleDelta(0f, angle)

private fun blendAngle(from: Float, to: Float, t: Float): Float =
    normalizeAngle(from + shortestAngleDelta(from, to) * t.coerceIn(0f, 1f))

private fun shortestPhaseDelta(from: Float, to: Float): Float {
    var delta = wrap01(to) - wrap01(from)
    if (delta > 0.5f) delta -= 1f
    if (delta < -0.5f) delta += 1f
    return delta
}

private fun normalizedJointTargets(values: List<Float>): List<Float> {
    val resized =
        when {
            values.size == JOINT_TARGET_KNOT_COUNT -> values
            values.isEmpty() -> List(JOINT_TARGET_KNOT_COUNT) { 0f }
            values.size > JOINT_TARGET_KNOT_COUNT -> values.take(JOINT_TARGET_KNOT_COUNT)
            else -> values + List(JOINT_TARGET_KNOT_COUNT - values.size) { values.last() }
        }
    return resized.map { it.coerceIn(-MAX_JOINT_TARGET_SIGNAL, MAX_JOINT_TARGET_SIGNAL) }
}

private fun cyclicSplineSample(values: List<Float>, phase: Float): Float {
    val normalized = normalizedJointTargets(values)
    if (normalized.isEmpty()) return 0f
    if (normalized.size == 1) return normalized.first()

    val count = normalized.size
    val wrapped = wrap01(phase) * count
    val i1 = floor(wrapped).toInt().mod(count)
    val t = wrapped - floor(wrapped)
    val i0 = (i1 - 1).mod(count)
    val i2 = (i1 + 1).mod(count)
    val i3 = (i1 + 2).mod(count)
    return catmullRom(normalized[i0], normalized[i1], normalized[i2], normalized[i3], t)
        .coerceIn(-MAX_JOINT_TARGET_SIGNAL, MAX_JOINT_TARGET_SIGNAL)
}

private fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
    val t2 = t * t
    val t3 = t2 * t
    return 0.5f * (
        (2f * p1) +
            (-p0 + p2) * t +
            (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 +
            (-p0 + 3f * p1 - 3f * p2 + p3) * t3
        )
}

private fun rotateValues(values: List<Float>, steps: Int): List<Float> {
    val normalized = normalizedJointTargets(values)
    if (normalized.isEmpty()) return normalized
    val shift = ((steps % normalized.size) + normalized.size) % normalized.size
    if (shift == 0) return normalized
    return List(normalized.size) { index -> normalized[(index - shift + normalized.size) % normalized.size] }
}

private fun mutateJointTargets(
    values: List<Float>,
    random: Random,
    edits: Int = random.nextInt(1, 3),
    sigma: Float = 0.16f
): List<Float> {
    val mutated = normalizedJointTargets(values).toMutableList()
    repeat(edits.coerceAtLeast(1)) {
        val index = random.nextInt(mutated.size)
        mutated[index] = (mutated[index] + gaussian(random, sigma))
            .coerceIn(-MAX_JOINT_TARGET_SIGNAL, MAX_JOINT_TARGET_SIGNAL)
    }
    if (chance(random, 0.45f)) {
        return smoothJointTargets(mutated, passes = 1)
    }
    return mutated
}

private fun smoothJointTargets(values: List<Float>, passes: Int = 1): List<Float> {
    var current = normalizedJointTargets(values)
    repeat(passes.coerceAtLeast(0)) {
        current =
            List(current.size) { index ->
                val prev = current[(index - 1 + current.size) % current.size]
                val next = current[(index + 1) % current.size]
                (prev * 0.22f + current[index] * 0.56f + next * 0.22f)
                    .coerceIn(-MAX_JOINT_TARGET_SIGNAL, MAX_JOINT_TARGET_SIGNAL)
            }
    }
    return current
}

private fun scaleJointTargets(values: List<Float>, scale: Float): List<Float> =
    normalizedJointTargets(values).map {
        (it * scale).coerceIn(-MAX_JOINT_TARGET_SIGNAL, MAX_JOINT_TARGET_SIGNAL)
    }

private fun mixJointTargets(a: List<Float>, b: List<Float>, random: Random): List<Float> {
    val left = normalizedJointTargets(a)
    val right = normalizedJointTargets(b)
    val mixed =
        List(JOINT_TARGET_KNOT_COUNT) { index ->
            lerp(left[index], right[index], random.nextFloat())
        }
    return smoothJointTargets(mixed, passes = 1)
}

private fun randomJointTargets(random: Random): List<Float> {
    val tau = 2f * PI.toFloat()
    val primaryAmplitude = randomFloat(random, 0.18f, 0.72f)
    val secondaryAmplitude = primaryAmplitude * randomFloat(random, 0f, 0.35f)
    val bias = randomFloat(random, -0.12f, 0.12f)
    val primaryPhase = random.nextFloat()
    val secondaryPhase = random.nextFloat()

    val targets =
        List(JOINT_TARGET_KNOT_COUNT) { index ->
            val t = index / JOINT_TARGET_KNOT_COUNT.toFloat()
            val primary = sin(tau * (t + primaryPhase))
            val secondary = sin(tau * (2f * t + secondaryPhase))
            (bias + primaryAmplitude * primary + secondaryAmplitude * secondary)
                .coerceIn(-MAX_JOINT_TARGET_SIGNAL, MAX_JOINT_TARGET_SIGNAL)
        }

    return smoothJointTargets(targets, passes = 1)
}

private fun notationSegments(program: ContactProgram): List<PhaseInterval> {
    val foot = program.normalized()
    if (foot.strength <= 1e-4f || foot.duty <= 1e-4f) return emptyList()
    val halfWidth = foot.duty.coerceIn(0f, 0.95f) * 0.5f
    if (halfWidth <= 1e-4f) return emptyList()

    val start = wrap01(foot.phase - halfWidth)
    val end = wrap01(foot.phase + halfWidth)
    return if (start < end) {
        listOf(PhaseInterval(start, end))
    } else {
        listOf(
            PhaseInterval(0f, end),
            PhaseInterval(start, 1f)
        )
    }
}

private fun wrap01(value: Float): Float {
    var wrapped = value % 1f
    if (wrapped < 0f) wrapped += 1f
    return wrapped
}

private fun smoothGate(current: Float, center: Float, duty: Float, smooth: Float): Float {
    val halfWidth = duty.coerceIn(0f, 1f) * 0.5f
    if (halfWidth <= 1e-4f) return 0f
    val d = abs(shortestPhaseDelta(center, current))
    if (d >= halfWidth) return 0f

    val ramp = (halfWidth * smooth.coerceIn(0.02f, 0.98f)).coerceAtLeast(1e-4f)
    if (d <= halfWidth - ramp) return 1f

    val t = ((halfWidth - d) / ramp).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}


private fun gaussian(random: Random, sigma: Float): Float {
    val u1 = max(1e-6f, random.nextFloat())
    val u2 = random.nextFloat()
    val mag = sqrt(-2f * kotlin.math.ln(u1))
    val angle = 2f * PI.toFloat() * u2
    return kotlin.math.cos(angle) * mag * sigma
}

private fun FloatArray.averageOrZero(): Float =
    if (isEmpty()) 0f else sum() / size.toFloat()

private fun randomFloat(random: Random, min: Float, max: Float): Float =
    min + (max - min) * random.nextFloat()

private fun chance(random: Random, probability: Float): Boolean =
    random.nextFloat() < probability.coerceIn(0f, 1f)
