@file:afield.CompletionCategory("Audio")
package afield.four.synth

import afield.graphics.FLine
import afield.stdlib.Vec4
import afield.stdlib.color
import afield.stdlib.filled
import afield.stdlib.fline
import afield.stdlib.hsl
import afield.stdlib.rect
import afield.stdlib.stroked
import afield.stdlib.thickness
import afield.stdlib.vec
import kotlin.math.max
import kotlin.math.min

data class AbletonSetFLineDebugOptions(
    val pixelsPerBeat: Double = 0.5,
    val clipLaneHeight: Double = 18.0,
    val automationLaneHeight: Double = 14.0,
    val trackGap: Double = 8.0,
    val clipVerticalPadding: Double = 2.0,
    val automationSamples: Int = 96,
    val includeMainTrack: Boolean = true,
    val includeGuides: Boolean = true,
    val markAutomationEvents: Boolean = true
)

class AbletonSetFLineDebugOptionsBuilder {
    var pixelsPerBeat: Double = 0.5
    var clipLaneHeight: Double = 18.0
    var automationLaneHeight: Double = 14.0
    var trackGap: Double = 8.0
    var clipVerticalPadding: Double = 2.0
    var automationSamples: Int = 96
    var includeMainTrack: Boolean = true
    var includeGuides: Boolean = true
    var markAutomationEvents: Boolean = true

    fun build(): AbletonSetFLineDebugOptions =
        AbletonSetFLineDebugOptions(
            pixelsPerBeat = pixelsPerBeat,
            clipLaneHeight = clipLaneHeight,
            automationLaneHeight = automationLaneHeight,
            trackGap = trackGap,
            clipVerticalPadding = clipVerticalPadding,
            automationSamples = automationSamples,
            includeMainTrack = includeMainTrack,
            includeGuides = includeGuides,
            markAutomationEvents = markAutomationEvents
        )
}

fun abletonSetFLineDebugOptions(
    block: AbletonSetFLineDebugOptionsBuilder.() -> Unit
): AbletonSetFLineDebugOptions =
    AbletonSetFLineDebugOptionsBuilder().apply(block).build()

fun AbletonSet.toDebugFLines(
    options: AbletonSetFLineDebugOptions = AbletonSetFLineDebugOptions()
): List<FLine> {
    val tracksToDraw = if (options.includeMainTrack) allTracks else tracks
    if (tracksToDraw.isEmpty()) return emptyList()

    val endBeat = max(visibleEndBeat(tracksToDraw), 16.0)
    val width = endBeat * options.pixelsPerBeat
    val lines = mutableListOf<FLine>()
    var y = 0.0

    tracksToDraw.forEachIndexed { visibleIndex, track ->
        val automationLanes = track.automation.size
        val trackHeight = options.clipLaneHeight + automationLanes * options.automationLaneHeight
        val trackColor = abletonTrackColor(track.color, visibleIndex, alpha = 0.82f)

        if (options.includeGuides) {
            lines += guideLine(0.0, y + options.clipLaneHeight, width, y + options.clipLaneHeight).tag(
                kind = "track-guide",
                track = track
            )
            lines += guideLine(0.0, y + trackHeight, width, y + trackHeight, alpha = 0.16f).tag(
                kind = "track-bottom",
                track = track
            )
        }

        track.clips.forEach { clip ->
            val x0 = clip.currentStartBeat * options.pixelsPerBeat
            val x1 = clip.currentEndBeat * options.pixelsPerBeat
            val clipTop = y + options.clipVerticalPadding
            val clipHeight = (options.clipLaneHeight - options.clipVerticalPadding * 2.0).coerceAtLeast(1.0)
            lines += rectLine(
                x = x0,
                y = clipTop,
                w = (x1 - x0).coerceAtLeast(0.5),
                h = clipHeight,
                color = withAlpha(trackColor, if (clip.disabled) 0.16f else 0.34f),
                filled = true
            ).tag(kind = "clip", track = track, clip = clip)
        }

        track.automation.forEachIndexed { automationIndex, envelope ->
            val laneTop = y + options.clipLaneHeight + automationIndex * options.automationLaneHeight
            val laneBottom = laneTop + options.automationLaneHeight
            val automationColor = automationColor(trackColor, automationIndex)
            lines += automationLine(
                envelope = envelope,
                startBeat = 0.0,
                endBeat = endBeat,
                laneTop = laneTop + 1.0,
                laneBottom = laneBottom - 1.0,
                xScale = options.pixelsPerBeat,
                samples = options.automationSamples,
                color = automationColor
            ).tag(kind = "automation", track = track, automation = envelope)

            if (options.markAutomationEvents) {
                val eventMarks = envelope.events
                    .filter { it.time in 0.0..endBeat }
                    .map { event ->
                        val x = event.time * options.pixelsPerBeat
                        tickLine(x, laneTop + 2.0, x, laneBottom - 2.0, withAlpha(automationColor, 0.72f))
                            .tag(kind = "automation-event", track = track, automation = envelope, event = event)
                    }
                lines += eventMarks
            }
        }

        y += trackHeight + options.trackGap
    }

    return lines
}

fun AbletonSet.toDebugFLines(
    block: AbletonSetFLineDebugOptionsBuilder.() -> Unit
): List<FLine> =
    toDebugFLines(abletonSetFLineDebugOptions(block))

fun AbletonSet.debugFLineMap(
    options: AbletonSetFLineDebugOptions = AbletonSetFLineDebugOptions()
): Map<String, List<FLine>> =
    mapOf("ableton-debug" to toDebugFLines(options))

fun AbletonSet.debugFLineMap(
    block: AbletonSetFLineDebugOptionsBuilder.() -> Unit
): Map<String, List<FLine>> =
    debugFLineMap(abletonSetFLineDebugOptions(block))

private fun visibleEndBeat(tracks: List<AbletonTrack>): Double {
    val clipEnd = tracks.flatMap { it.clips }.maxOfOrNull { it.currentEndBeat } ?: 0.0
    val automationEnd = tracks
        .flatMap { it.automation }
        .flatMap { it.events }
        .map { it.time }
        .filter { it >= 0.0 }
        .maxOrNull()
        ?: 0.0
    return max(clipEnd, automationEnd)
}

private fun automationLine(
    envelope: AbletonAutomationEnvelope,
    startBeat: Double,
    endBeat: Double,
    laneTop: Double,
    laneBottom: Double,
    xScale: Double,
    samples: Int,
    color: Vec4
): FLine {
    val valueRange = envelope.displayRange()
    val n = max(2, samples)
    return fline {
        this.color = color
        stroked = true
        filled = false
        thickness = 1.25
        for (i in 0 until n) {
            val t = if (n == 1) 0.0 else i.toDouble() / (n - 1).toDouble()
            val beat = startBeat + (endBeat - startBeat) * t
            val value = envelope.valueAt(beat).asDoubleOrNull()
            val normalized = value?.let { valueRange.normalize(it) } ?: 0.0
            val x = beat * xScale
            val y = laneBottom - normalized * (laneBottom - laneTop)
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
    }
}

private fun AbletonAutomationEnvelope.displayRange(): ClosedFloatingPointRange<Double> {
    target.range?.let { range ->
        if (range.endInclusive > range.start) return range
    }
    val values = events.mapNotNull { it.value.asDoubleOrNull() }
    val minValue = values.minOrNull()
    val maxValue = values.maxOrNull()
    if (minValue != null && maxValue != null && maxValue > minValue) return minValue..maxValue
    val manual = target.manual?.asDoubleOrNull()
    return if (manual != null) {
        (manual - 1.0)..(manual + 1.0)
    } else {
        0.0..1.0
    }
}

private fun ClosedFloatingPointRange<Double>.normalize(value: Double): Double {
    val width = endInclusive - start
    if (width <= 0.0 || !width.isFinite()) return 0.0
    return ((value - start) / width).coerceIn(0.0, 1.0)
}

private fun rectLine(
    x: Double,
    y: Double,
    w: Double,
    h: Double,
    color: Vec4,
    filled: Boolean
): FLine =
    fline {
        rect(x, y, w, h)
        this.color = color
        this.filled = filled
        this.stroked = true
        thickness = 1.0
    }

private fun guideLine(x0: Double, y0: Double, x1: Double, y1: Double, alpha: Float = 0.24f): FLine =
    tickLine(x0, y0, x1, y1, vec(0.72, 0.74, 0.78, alpha))

private fun tickLine(x0: Double, y0: Double, x1: Double, y1: Double, color: Vec4): FLine =
    fline {
        moveTo(x0, y0)
        lineTo(x1, y1)
        this.color = color
        stroked = true
        filled = false
        thickness = 1.0
    }

private fun FLine.tag(
    kind: String,
    track: AbletonTrack? = null,
    clip: AbletonClip? = null,
    automation: AbletonAutomationEnvelope? = null,
    event: AbletonAutomationEvent? = null
): FLine {
    properties["ableton.kind"] = kind
    track?.let {
        properties["ableton.track.index"] = it.index
        properties["ableton.track.id"] = it.id
        properties["ableton.track.name"] = it.name
        properties["ableton.track.color"] = it.color
    }
    clip?.let {
        properties["ableton.clip.id"] = it.id
        properties["ableton.clip.name"] = it.name
        properties["ableton.clip.startBeat"] = it.currentStartBeat
        properties["ableton.clip.endBeat"] = it.currentEndBeat
    }
    automation?.let {
        properties["ableton.automation.id"] = it.id
        properties["ableton.automation.target"] = it.target.name
        properties["ableton.automation.events"] = it.events.size
    }
    event?.let {
        properties["ableton.automation.event.id"] = it.id
        properties["ableton.automation.event.time"] = it.time
        properties["ableton.automation.event.value"] = it.value.toString()
    }
    return this
}

private fun abletonTrackColor(colorIndex: Int?, fallbackIndex: Int, alpha: Float): Vec4 {
    val palette = abletonPalette
    val color = colorIndex?.takeIf { it >= 0 }?.let { palette.getOrNull(it % palette.size) }
        ?: hsl(((fallbackIndex * 0.61803398875) % 1.0), 0.54, 0.62, alpha)
    return withAlpha(color, alpha)
}

private fun automationColor(trackColor: Vec4, automationIndex: Int): Vec4 {
    val lift = min(0.95f, 0.62f + automationIndex * 0.08f)
    return vec(
        min(1.0f, trackColor.x * 0.65f + lift * 0.35f),
        min(1.0f, trackColor.y * 0.65f + lift * 0.35f),
        min(1.0f, trackColor.z * 0.65f + lift * 0.35f),
        0.95f
    )
}

private fun withAlpha(color: Vec4, alpha: Float): Vec4 =
    vec(color.x, color.y, color.z, alpha)

private val abletonPalette: List<Vec4> = listOf(
    vec(0.82, 0.22, 0.20, 1.0),
    vec(0.92, 0.38, 0.20, 1.0),
    vec(0.95, 0.56, 0.22, 1.0),
    vec(0.91, 0.72, 0.25, 1.0),
    vec(0.72, 0.78, 0.28, 1.0),
    vec(0.43, 0.74, 0.32, 1.0),
    vec(0.26, 0.68, 0.45, 1.0),
    vec(0.22, 0.66, 0.61, 1.0),
    vec(0.20, 0.59, 0.78, 1.0),
    vec(0.24, 0.45, 0.86, 1.0),
    vec(0.38, 0.35, 0.88, 1.0),
    vec(0.58, 0.33, 0.82, 1.0),
    vec(0.76, 0.30, 0.67, 1.0),
    vec(0.86, 0.28, 0.48, 1.0),
    vec(0.66, 0.66, 0.66, 1.0),
    vec(0.43, 0.43, 0.43, 1.0),
    vec(0.92, 0.78, 0.66, 1.0),
    vec(0.76, 0.57, 0.42, 1.0),
    vec(0.58, 0.43, 0.32, 1.0),
    vec(0.38, 0.29, 0.24, 1.0),
    vec(0.92, 0.66, 0.74, 1.0),
    vec(0.78, 0.49, 0.65, 1.0),
    vec(0.56, 0.40, 0.59, 1.0),
    vec(0.36, 0.31, 0.43, 1.0),
    vec(0.69, 0.84, 0.96, 1.0),
    vec(0.48, 0.67, 0.84, 1.0),
    vec(0.34, 0.50, 0.68, 1.0),
    vec(0.25, 0.34, 0.50, 1.0),
    vec(0.67, 0.86, 0.76, 1.0),
    vec(0.43, 0.68, 0.56, 1.0),
    vec(0.31, 0.51, 0.43, 1.0),
    vec(0.22, 0.36, 0.32, 1.0)
)
