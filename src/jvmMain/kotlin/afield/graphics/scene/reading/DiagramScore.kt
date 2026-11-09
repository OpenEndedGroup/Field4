package afield.graphics.scene.reading

import afield.math.linalg.Vector3f

/**
 * Absolute, seekable scene state. Evaluating a score neither advances physics nor accumulates
 * edits.
 */
data class DiagramPose(
    var opacity: Float = 1f,
    var reveal: Float = 1f,
    var erase: Float = 0f,
    val offset: Vector3f = Vector3f(),
    var scale: Float = 1f,
    var blur: Float = 0f, // additive Gaussian-radius score channel, in ems
)

class DiagramScore
internal constructor(
    val diagram: ReadingDiagram,
    val duration: Double,
    private val tracks: Map<String, List<(Double, DiagramPose) -> Unit>>,
    internal val blurCapacities: Map<DiagramText, Float> = emptyMap(),
) {
    fun pose(item: DiagramItem, time: Double): DiagramPose {
        require(item.owner === diagram.owner && time.isFinite())
        return DiagramPose().also { p -> tracks[item.id]?.forEach { it(time, p) } }
    }
}

/** Opacity, stroke arclength, draw offset and size are score channels, independent of topology. */
@ReadingDsl
class DiagramScoreBuilder
internal constructor(private val diagram: ReadingDiagram, private val duration: Double) {
    private val blurCapacities = LinkedHashMap<DiagramText, Float>()
    private val tracks = LinkedHashMap<String, MutableList<(Double, DiagramPose) -> Unit>>()

    private fun track(item: DiagramItem, block: (Double, DiagramPose) -> Unit) {
        require(item.owner === diagram.owner) { "Scored item belongs to another diagram" }
        tracks.getOrPut(item.id) { ArrayList() }.add(block)
    }

    private fun phase(time: Double, at: Double, seconds: Double): Float {
        val x = ((time - at) / seconds).coerceIn(0.0, 1.0).toFloat()
        return x * x * (3f - 2f * x)
    }

    private fun timing(at: Double, seconds: Double) {
        require(
            at.isFinite() &&
                at >= 0 &&
                seconds.isFinite() &&
                seconds > 0 &&
                at + seconds <= duration + 1e-8,
        )
    }

    fun fadeIn(item: DiagramItem, at: Double, seconds: Double = .8) {
        timing(at, seconds)
        track(item) { t, p -> p.opacity *= phase(t, at, seconds) }
    }

    fun fadeOut(item: DiagramItem, at: Double, seconds: Double = 1.2) {
        timing(at, seconds)
        track(item) { t, p -> p.opacity *= 1f - phase(t, at, seconds) }
    }

    /** Radius is in ems, independent of opacity. Prepare/rebuild Drawing after adding blur tracks. */
    fun blur(item: DiagramText, from: Float, to: Float, at: Double, seconds: Double = 4.0) {
        timing(at, seconds)
        require(listOf(from, to).all { it.isFinite() && it >= 0f })
        val capacity = (blurCapacities[item] ?: 0f) + maxOf(from, to)
        require(capacity.isFinite())
        blurCapacities[item] = capacity
        track(item) { t, p -> p.blur += from + (to - from) * phase(t, at, seconds) }
    }

    /** A word resolves slowly from a broad Gaussian cloud into its exact Slug outline. */
    fun blurIn(item: DiagramText, at: Double, seconds: Double = 4.0, radius: Float = .8f) =
        blur(item, radius, 0f, at, seconds)

    /** A word dissolves into a broad Gaussian cloud; opacity remains an independent channel. */
    fun blurOut(item: DiagramText, at: Double, seconds: Double = 4.0, radius: Float = .8f) =
        blur(item, 0f, radius, at, seconds)

    fun drawIn(path: DiagramPath, at: Double, seconds: Double = 1.0) {
        timing(at, seconds)
        track(path) { t, p -> p.reveal *= phase(t, at, seconds) }
    }

    fun drawOut(path: DiagramPath, at: Double, seconds: Double = 1.2) {
        timing(at, seconds)
        track(path) { t, p -> p.erase = maxOf(p.erase, phase(t, at, seconds)) }
    }

    /** Metres, in diagram coordinates. Text anchors and dots on paths follow these transforms. */
    fun move(item: DiagramItem, from: Vector3f, to: Vector3f, at: Double, seconds: Double = 1.0) {
        timing(at, seconds)
        val a = Vector3f(from)
        val b = Vector3f(to)
        require(listOf(a.x, a.y, a.z, b.x, b.y, b.z).all { it.isFinite() })
        track(item) { t, p -> p.offset.add(Vector3f(a).lerp(b, phase(t, at, seconds))) }
    }

    /**
     * Resize glyph runs and SDF dots; paths retain their world-space anchors and authored width.
     */
    fun resize(item: DiagramItem, from: Float, to: Float, at: Double, seconds: Double = 1.0) {
        require(item is DiagramText || item is DiagramDot) {
            "Resize a text run or dot; edit path width or anchors separately"
        }
        timing(at, seconds)
        require(from.isFinite() && to.isFinite() && from > 0 && to > 0)
        track(item) { t, p -> p.scale *= from + (to - from) * phase(t, at, seconds) }
    }

    /** Convenience for a text/dot entrance; the graph itself is unchanged throughout the cue. */
    fun enter(item: DiagramItem, at: Double, seconds: Double = .8, rise: Float = .03f) {
        fadeIn(item, at, seconds)
        move(item, Vector3f(0f, -rise, 0f), Vector3f(), at, seconds)
    }

    /**
     * Advanced channel intervention; mutate only the supplied pose, never persistent scene state.
     */
    fun animate(item: DiagramItem, channel: (seconds: Double, pose: DiagramPose) -> Unit) =
        track(item, channel)

    fun fadeIn(group: DiagramGroup, at: Double, seconds: Double = .8, stagger: Double = 0.0) {
        require(group.owner === diagram.owner && stagger.isFinite() && stagger >= 0)
        for ((i, item) in group.items.filter { it !is DiagramParticle }.withIndex()) fadeIn(
            item,
            at + i * stagger,
            seconds,
        )
    }

    fun fadeOut(group: DiagramGroup, at: Double, seconds: Double = 1.2) {
        require(group.owner === diagram.owner)
        group.items.forEach { fadeOut(it, at, seconds) }
    }

    /** Crossfade two authored selections while current eye poses and physics keep advancing. */
    fun transition(from: DiagramGroup, to: DiagramGroup, at: Double, seconds: Double = 1.5) {
        require(from !== to && from.items.none { it in to.items }) {
            "Crossfade groups must be disjoint"
        }
        fadeOut(from, at, seconds)
        fadeIn(to, at, seconds)
        for (path in to.items.filterIsInstance<DiagramPath>()) drawIn(path, at, seconds)
    }

    fun exitAll(at: Double, seconds: Double = 1.2, erasePaths: Boolean = true) {
        for (item in diagram.items) {
            fadeOut(item, at, seconds)
            if (erasePaths && item is DiagramPath) drawOut(item, at, seconds)
        }
    }

    internal fun build() = DiagramScore(diagram, duration, tracks.mapValues { it.value.toList() }, blurCapacities.toMap())
}

fun diagramScore(
    diagram: ReadingDiagram,
    duration: Double,
    block: DiagramScoreBuilder.() -> Unit,
): DiagramScore {
    require(duration.isFinite() && duration > 0)
    return DiagramScoreBuilder(diagram, duration).apply(block).build()
}

/** Playback advances the score clock. A paused score still permits the CPU physics to settle. */
class DiagramPlayback(var score: DiagramScore) {
    var time = 0.0
    var playing = true
    var repeat = true

    fun restart() {
        time = 0.0
    }

    fun seek(seconds: Double) {
        require(seconds.isFinite())
        time = seconds.coerceAtLeast(0.0)
    }

    internal fun advance(seconds: Double) {
        if (playing) time += seconds
    }

    val sampleTime
        get() = if (repeat) time % score.duration else time.coerceAtMost(score.duration)
}
