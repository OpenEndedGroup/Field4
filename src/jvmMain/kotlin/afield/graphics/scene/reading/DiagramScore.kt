package afield.graphics.scene.reading

import afield.math.linalg.Vector3f
import afield.math.linalg.Vector4f

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
    var overstrokeFraction: Float? = null, // null keeps the frame's authored extension fraction
    var overstrokeDistance: Float? = null, // null keeps the authored extension distance in metres
    var morph: Float = 0f, // a text morph's progress, carried on its first layout run: 0 first line, 1 second
    var tint: Vector4f? = null, // colour mixed into the item's RGB by tintAmount; alpha untouched
    var tintAmount: Float = 0f,
    var tintAt: Double = Double.NEGATIVE_INFINITY, // start of the governing tint cue: the latest started one wins
    var saturation: Float = 0f, // extra saturation: each colour pushed this many times its own distance from its grey
    var saturationAt: Double = Double.NEGATIVE_INFINITY,
) {
    /**
     * [base] as this pose colours it, alpha kept: first [saturation] pushes the RGB away from its
     * own luminance (so an off-white flares toward the hue it leans to), then [tint] is mixed in by
     * [tintAmount]. Channels are clamped to 0..1. A fresh vector.
     */
    fun tinted(base: Vector4f): Vector4f = tinted(null, base)

    /**
     * As [tinted], but with a [palette] the colour moves along the ramp: saturation pushes its
     * position away from the neutral middle (`u − 1` grows by `1 + saturation`), the tint pulls
     * the position toward the tint's, and the result is the ramp's colour there. Warm and cool
     * colours flare toward their own ends instead of all toward one hue.
     */
    fun tinted(palette: DiagramPalette?, base: Vector4f): Vector4f {
        if (palette != null) {
            if (tint == null && saturation == 0f) return Vector4f(base)
            var u = palette.coordinate(base)
            u = 1f + (u - 1f) * (1f + saturation)
            tint?.let { u += (palette.coordinate(it) - u) * tintAmount.coerceIn(0f, 1f) }
            return palette.at(u.coerceIn(0f, 2f)).apply { w = base.w }
        }
        var r = base.x; var g = base.y; var b = base.z
        if (saturation != 0f) {
            val grey = .2126f * r + .7152f * g + .0722f * b
            val k = 1f + saturation
            r = grey + (r - grey) * k; g = grey + (g - grey) * k; b = grey + (b - grey) * k
        }
        tint?.let { t ->
            val a = tintAmount.coerceIn(0f, 1f)
            r += (t.x - r) * a; g += (t.y - g) * a; b += (t.z - b) * a
        }
        return Vector4f(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), base.w)
    }
}

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
    private val morphCues = LinkedHashMap<DiagramTextMorph, MutableList<MorphCue>>()

    private class MorphCue(val at: Double, val seconds: Double, val from: Float, val to: Float)

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

    fun fadeIn(frame: DiagramTextFrame, at: Double, seconds: Double = .8) = fadeIn(frame.path, at, seconds)

    fun fadeOut(frame: DiagramTextFrame, at: Double, seconds: Double = 1.2) = fadeOut(frame.path, at, seconds)

    /** Absolute, seekable overrun at each end; scoring never mutates the authored frame settings. */
    fun overstroke(
        frame: DiagramTextFrame,
        fromFraction: Float,
        toFraction: Float,
        at: Double,
        seconds: Double = 1.0,
        fromDistance: Float = frame.overstrokeDistance,
        toDistance: Float = fromDistance,
    ) {
        timing(at, seconds)
        require(listOf(fromFraction, toFraction, fromDistance, toDistance).all { it.isFinite() && it >= 0f })
        track(frame.path) { t, p ->
            val u = phase(t, at, seconds)
            p.overstrokeFraction = fromFraction + (toFraction - fromFraction) * u
            p.overstrokeDistance = fromDistance + (toDistance - fromDistance) * u
        }
    }

    /** A holder fades as one assembly; its two texts and each structural path remain scoreable too. */
    fun fadeIn(holder: DiagramTextFrustum, at: Double, seconds: Double = .8) = fadeIn(holder.group, at, seconds)

    fun fadeOut(holder: DiagramTextFrustum, at: Double, seconds: Double = 1.2) = fadeOut(holder.group, at, seconds)

    fun blurIn(holder: DiagramTextFrustum, at: Double, seconds: Double = 4.0, radius: Float = .8f) {
        blurIn(holder.front, at, seconds, radius)
        blurIn(holder.back, at, seconds, radius)
    }

    fun blurOut(holder: DiagramTextFrustum, at: Double, seconds: Double = 4.0, radius: Float = .8f) {
        blurOut(holder.front, at, seconds, radius)
        blurOut(holder.back, at, seconds, radius)
    }

    /** Translate the two text ends once. Their frames, rails and attachments follow automatically. */
    fun move(holder: DiagramTextFrustum, from: Vector3f, to: Vector3f, at: Double, seconds: Double = 1.0) {
        move(holder.front, from, to, at, seconds)
        move(holder.back, from, to, at, seconds)
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
    /**
     * Mix [color] into the item's RGB from [from] to [to] of the way over [seconds] starting [at]:
     * a flare that settles (`from = 1, to = 0`, the default), a hold (`1, 1`), or a release. Before
     * [at] the cue does nothing; once started, it holds its final amount, and of the cues that have
     * started the latest governs. Alpha, and so opacity, is untouched. Coloured spans are tinted too.
     */
    fun tint(item: DiagramItem, color: Vector4f, at: Double, seconds: Double = 2.0, from: Float = 1f, to: Float = 0f) {
        timing(at, seconds)
        require(from.isFinite() && to.isFinite())
        val c = Vector4f(color)
        track(item) { t, p ->
            if (t >= at && at >= p.tintAt) {
                p.tint = c
                p.tintAmount = from + (to - from) * phase(t, at, seconds)
                p.tintAt = at
            }
        }
    }

    /**
     * Push the item's colours away from grey by [from] times their own chroma, easing to [to] over
     * [seconds] from [at]: the default is a flare that settles. Each coloured span flares toward its
     * own hue, so syntax classes stay distinct while they glow. Same cue rules as [tint].
     */
    fun saturate(item: DiagramItem, at: Double, seconds: Double = 2.0, from: Float = 3f, to: Float = 0f) {
        timing(at, seconds)
        require(from.isFinite() && to.isFinite() && from >= -1f && to >= -1f)
        track(item) { t, p ->
            if (t >= at && at >= p.saturationAt) {
                p.saturation = from + (to - from) * phase(t, at, seconds)
                p.saturationAt = at
            }
        }
    }

    fun saturate(group: DiagramGroup, at: Double, seconds: Double = 2.0, from: Float = 3f, to: Float = 0f, stagger: Double = 0.0) {
        require(group.owner === diagram.owner && stagger.isFinite() && stagger >= 0)
        for ((i, item) in group.items.filter { it !is DiagramParticle }.withIndex()) saturate(item, at + i * stagger, seconds, from, to)
    }

    fun tint(group: DiagramGroup, color: Vector4f, at: Double, seconds: Double = 2.0, from: Float = 1f, to: Float = 0f, stagger: Double = 0.0) {
        require(group.owner === diagram.owner && stagger.isFinite() && stagger >= 0)
        for ((i, item) in group.items.filter { it !is DiagramParticle }.withIndex()) tint(item, color, at + i * stagger, seconds, from, to)
    }

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
    fun transition(from: DiagramGroup, to: DiagramGroup, at: Double, seconds: Double = 1.5, drawPaths: Boolean = true) {
        require(from !== to && from.items.none { it in to.items }) {
            "Crossfade groups must be disjoint"
        }
        fadeOut(from, at, seconds)
        fadeIn(to, at, seconds)
        if (drawPaths) for (path in to.items.filterIsInstance<DiagramPath>()) drawIn(path, at, seconds)
    }

    /** Mechanical holders crossfade complete lines, without changing their stroke reveal. */
    fun transition(from: DiagramTextFrustum, to: DiagramTextFrustum, at: Double, seconds: Double = 1.5) =
        transition(from.group, to.group, at, seconds, drawPaths = false)

    fun exitAll(at: Double, seconds: Double = 1.2, erasePaths: Boolean = true) {
        for (item in diagram.items) {
            fadeOut(item, at, seconds)
            if (erasePaths && item is DiagramPath) drawOut(item, at, seconds)
        }
    }

    /**
     * Play [morph] from [from] to [to] (0 is its first line, 1 its second) over `[at, at + seconds]`.
     * Matched pieces glide, unmatched ones fade where they stand. Several cues on one morph run in
     * time order; before the first the morph holds at its `from`, between cues at the last `to`. A
     * morph with no cue shows its first line.
     */
    fun morph(morph: DiagramTextMorph, at: Double, seconds: Double = 1.2, from: Float = 0f, to: Float = 1f) {
        require(morph.from.owner === diagram.owner) { "Scored morph belongs to another diagram" }
        timing(at, seconds)
        require(from.isFinite() && to.isFinite())
        morphCues.getOrPut(morph) { ArrayList() }.add(MorphCue(at, seconds, from, to))
    }

    private fun morphTracks() {
        for (morph in diagram.textMorphs) {
            val cues = morphCues[morph].orEmpty().sortedBy { it.at }
            val progress: (Double) -> Float = { t ->
                if (cues.isEmpty()) 0f
                else {
                    var p = cues.first().from
                    for (c in cues) if (t >= c.at) p = c.from + (c.to - c.from) * phase(t, c.at, c.seconds)
                    p
                }
            }
            track(morph.from) { t, p -> p.morph = progress(t) }
            for (piece in morph.pieces) {
                val weight: (Float) -> Float = when (piece.role) {
                    DiagramTextMorph.Role.GLIDE -> { _ -> 1f }
                    DiagramTextMorph.Role.CROSS_FROM, DiagramTextMorph.Role.FADE_OUT -> { m -> 1f - m.coerceIn(0f, 1f) }
                    DiagramTextMorph.Role.CROSS_TO, DiagramTextMorph.Role.FADE_IN -> { m -> m.coerceIn(0f, 1f) }
                }
                track(piece.text) { t, p -> p.opacity *= weight(progress(t)) }
            }
        }
    }

    internal fun build(): DiagramScore {
        morphTracks()
        return DiagramScore(diagram, duration, tracks.mapValues { it.value.toList() }, blurCapacities.toMap())
    }
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
