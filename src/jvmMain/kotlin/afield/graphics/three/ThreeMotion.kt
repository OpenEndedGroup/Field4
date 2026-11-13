package afield.graphics.three

import afield.geometry.kinematics.RigidMotion
import afield.math.linalg.Vec3
import afield.math.linalg.Vec4
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Declared motions in the standard viewer: parts that turn or slide, played on a timeline with a scrubber,
 * and tinted by a value measured at each step of the motion.
 *
 * A motion is an [afield.geometry.kinematics.RigidMotion], sent as its own JSON, so the viewer plays exactly
 * the motion a clearance check walked. The per-step values ([ThreeMotionTrack]) are plain numbers with the
 * bands that colour them, measured wherever the box likes; none of it is three.js, so another renderer can play
 * the same data.
 *
 * ```kotlin
 * val swing = RigidMotion.rotation(point = vec(0.0, 0.0, 0.0), axis = vec(0.0, 0.0, 1.0), from = 0.0, to = 95.0)
 * val steps = swing.stepsBy(5.0)
 * three {
 *     mesh(door, id = "door", wireframe = false)
 *     motion("door", motion = swing, track = ThreeMotionTrack(
 *         values = steps.map { it.value }, samples = steps.map { gapAt(it) }, unit = "mm",
 *         bands = listOf(ThreeTintBand.under(12.0, "under the 12 mm clearance"))))
 *     timeline(periodSeconds = 6.0)
 * }
 * ```
 *
 * The toolbar gets **Play**, a scrubber and **At rest**, and **Tracks** when a track has points to follow. Playing
 * moves each part from its motion's `from` to its `to` and back; the part glows in the colour of the band its
 * current step's value is in, and the readout names the worst of them. `FieldViewer.motion` drives the same
 * from a page.
 */

/** A band of per-step values: every value below [below] (and not in an earlier band) is in it. */
@Serializable
data class ThreeTintBand(
    val below: Double,
    /** RGB in 0..1, for the part's glow and its tracks. */
    val color: List<Double>,
    val label: String = "",
    /** Whether a part glows while its current step is in this band; `false` colours only its tracks. */
    val tint: Boolean = true,
) {
    init {
        require(!below.isNaN()) { "a tint band's limit must be a number" }
        require(color.size == 3 && color.all { it.isFinite() && it in 0.0..1.0 }) { "a tint band's colour is three values in 0..1, got $color" }
    }

    companion object {
        /** Red, and glowing: the values that break a rule. */
        fun under(limit: Double, label: String = "", color: Vec4 = Vec4(0.85f, 0.12f, 0.08f, 1f)) =
            ThreeTintBand(limit, listOf(color.x.toDouble(), color.y.toDouble(), color.z.toDouble()), label, tint = true)

        /** Amber, tracks only: the values that come close. */
        fun near(limit: Double, label: String = "", color: Vec4 = Vec4(0.92f, 0.6f, 0.1f, 1f)) =
            ThreeTintBand(limit, listOf(color.x.toDouble(), color.y.toDouble(), color.z.toDouble()), label, tint = false)
    }
}

/**
 * A value measured at each step of a motion — a clearance gap, say — and how to colour it.
 *
 * [values] are the motion's own coordinate at each step (degrees, or distance for a slide), [samples] the value
 * measured there, and [labels], when given, a word per step (the nearest obstacle). The viewer looks up the step
 * nearest the motion's current value.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ThreeMotionTrack(
    val values: List<Double>,
    val samples: List<Double>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val labels: List<String> = emptyList(),
    /** In increasing order of [ThreeTintBand.below]; a step in none is uncoloured (its track is teal). */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val bands: List<ThreeTintBand> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val unit: String = "",
    @EncodeDefault(EncodeDefault.Mode.NEVER) val decimals: Int = 2,
    /**
     * Points on the part, in its rest coordinates (where the motion's value 0 puts it, as
     * [afield.geometry.kinematics.RigidMotion] defines), whose paths **Tracks** draws through the steps, each
     * segment in its step's band colour: the corners of a leaf, say.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val points: List<List<Double>> = emptyList(),
) {
    init {
        require(values.isNotEmpty() && values.size == samples.size) { "a motion track needs one sample per value, got ${values.size} values and ${samples.size} samples" }
        require(values.all(Double::isFinite)) { "a motion track's values must be finite" }
        require(samples.all(Double::isFinite)) { "a motion track's samples must be finite (JSON has no ∞; write a large value for nothing in reach)" }
        require(labels.isEmpty() || labels.size == values.size) { "a motion track's labels, when given, are one per step" }
        require(bands.zipWithNext().all { (a, b) -> a.below < b.below }) { "a motion track's bands go in increasing order of below" }
        require(points.all { it.size == 3 && it.all(Double::isFinite) }) { "a motion track's points are three finite numbers each" }
        require(decimals in 0..6) { "decimals must be in 0..6" }
    }

    companion object {
        /**
         * The track of a swept clearance check ([afield.geometry.kinematics.sweptClearance]): its steps' motion
         * values, gaps and nearest obstacles, so the viewer tints the part exactly where the check found it
         * close. A step with nothing in reach (an infinite gap) is sent as [farGap].
         */
        fun of(
            clearance: afield.geometry.kinematics.SweptClearance,
            bands: List<ThreeTintBand>,
            unit: String = "",
            points: List<List<Double>> = emptyList(),
            farGap: Double = 1e9,
        ) = ThreeMotionTrack(
            values = clearance.steps.map { it.value },
            samples = clearance.steps.map { if (it.gap.isFinite()) it.gap else farGap },
            labels = clearance.steps.map { it.obstacle.orEmpty() }.takeIf { l -> l.any { it.isNotEmpty() } }.orEmpty(),
            bands = bands, unit = unit, points = points,
        )

        /** Track points from vectors. */
        fun points(vararg p: Vec3): List<List<Double>> = p.map { listOf(it.x.toDouble(), it.y.toDouble(), it.z.toDouble()) }
    }
}

/** The motion of one or more parts (a leaf and its glass, say), as the viewer receives it. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ThreePartMotion(
    /** The [ThreeDrawable.id]s that move together. */
    val parts: List<String>,
    /** [RigidMotion.toJson], as JSON. */
    val motion: JsonObject,
    /** The motion value the parts' geometry is drawn at; **At rest** shows them there. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val at: Double = 0.0,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val track: ThreeMotionTrack? = null,
    /** What the readout calls it; the first part's id when `null`. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val label: String? = null,
)

/** How the timeline plays its parameter `t` (0 at every motion's `from`, 1 at its `to`). */
@Serializable
enum class ThreeTimelineShape {
    /** Open (0 → 1), hold, close, hold, easing in and out, round and round. */
    SWING,
    /** 0 → 1, then from 0 again. */
    LOOP,
    /** 0 → 1, once. */
    ONCE,
}

/** The timeline the motions play on. See [ThreeSceneBuilder.timeline]. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ThreeTimeline(
    val periodSeconds: Double = 7.0,
    val shape: ThreeTimelineShape = ThreeTimelineShape.SWING,
    /** Start playing when the view opens. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val autoplay: Boolean = false,
    /** The play button's word. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val playLabel: String = "Play",
) {
    init {
        require(periodSeconds.isFinite() && periodSeconds in 0.5..3600.0) { "a timeline's period is 0.5 to 3600 seconds, got $periodSeconds" }
        require(playLabel.isNotBlank() && playLabel.length <= 24) { "a play label is 1 to 24 characters" }
    }
}

internal fun RigidMotion.toThreeJson(): JsonObject = Json.parseToJsonElement(toJson()).jsonObject
