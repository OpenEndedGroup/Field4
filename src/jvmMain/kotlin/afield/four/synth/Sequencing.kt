@file:afield.CompletionCategory("Patterns")
package afield.four.synth

import afield.GeneratorScope
import afield.RateLimiter
import afield.ToHTML
import afield._printHere
import afield.generator
import afield.locationForTextStringOnStack
import afield.stdlib.Marker
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

private const val PIANO_ROLL_BLUE = "#0af"
private const val PIANO_ROLL_ORANGE = "#f80"
private const val PIANO_ROLL_BACKGROUND = "#000000"
private const val PIANO_ROLL_FONT = "\"Iosevka Web\", \"IBM Plex Mono\", monospace"

data class ScheduledNote(
    val start: Double,
    val duration: Double,
    val pitch: Float,
    val velocity: Float = 1f,
    val label: String? = null,
    val color: String? = null
) {
    init {
        require(start.isFinite()) { "ScheduledNote.start must be finite" }
        require(duration.isFinite()) { "ScheduledNote.duration must be finite" }
        require(duration >= 0.0) { "ScheduledNote.duration must be >= 0" }
        require(pitch.isFinite()) { "ScheduledNote.pitch must be finite" }
        require(velocity.isFinite()) { "ScheduledNote.velocity must be finite" }
    }

    val end: Double
        get() = start + duration

    val frequencyHz: Double
        get() = midiToFrequency(pitch.toDouble())

    fun intersects(windowStartSeconds: Double, windowEndSeconds: Double): Boolean =
        end >= windowStartSeconds && start <= windowEndSeconds

    override fun toString(): String = buildString {
        append("ScheduledNote(")
        append("start=${start.prettySeconds()}, duration=${duration.prettySeconds()}, pitch=${pitch.prettyPitch()}")
        if (velocity != 1f) append(", velocity=${velocity.prettyPitch()}")
        if (label != null) append(", label=\"${label.prettyText(24)}\"")
        if (color != null) append(", color=$color")
        append(")")
    }
}

fun note(
    pitch: Number,
    duration: Number,
    start: Number = 0.0,
    velocity: Number = 1.0,
    label: String? = null,
    color: String? = null
): ScheduledNote = ScheduledNote(
    start = start.toDouble(),
    duration = duration.toDouble(),
    pitch = pitch.toFloat(),
    velocity = velocity.toFloat(),
    label = label,
    color = color
)

fun midiToFrequency(midi: Number): Double = 440.0 * 2.0.pow((midi.toDouble() - 69.0) / 12.0)

fun frequencyToMidi(freqHz: Number): Double = 69.0 + 12.0 * (ln(freqHz.toDouble() / 440.0) / ln(2.0))

private fun Double.prettySeconds(): String = String.format(Locale.US, "%.4fs", this).replace(".0000s", "s")

private fun Float.prettyPitch(): String = String.format(Locale.US, "%.3f", this).trimEnd('0').trimEnd('.')

private fun String.prettyText(limit: Int): String = if (length <= limit) this else take(limit - 3) + "..."

data class NoteWindowSnapshot(
    val notes: List<ScheduledNote>,
    val playheadSeconds: Double,
    val windowStartSeconds: Double,
    val windowEndSeconds: Double,
    val exhausted: Boolean = false,
    val generatedUntilSeconds: Double = windowEndSeconds
) : ToHTML {

    init {
        require(windowStartSeconds <= windowEndSeconds) { "windowStartSeconds must be <= windowEndSeconds" }
    }

    override fun toHTML(): String = pianoRollSvg()

    override fun toString(): String = synthPrettyObject(
        this,
        "notes" to notes.take(4),
        "playheadSeconds" to playheadSeconds,
        "windowStartSeconds" to windowStartSeconds,
        "windowEndSeconds" to windowEndSeconds,
        "exhausted" to exhausted,
        "generatedUntilSeconds" to generatedUntilSeconds
    )
}

class NoteSequenceScope internal constructor(
    private val scope: GeneratorScope<Unit, ScheduledNote>
) {

    var cursorSeconds: Double = 0.0

    suspend fun note(
        pitch: Number,
        duration: Number,
        velocity: Number = 1.0,
        start: Number? = null,
        label: String? = null,
        color: String? = null
    ): ScheduledNote {
        val scheduled = ScheduledNote(
            start = start?.toDouble() ?: cursorSeconds,
            duration = duration.toDouble(),
            pitch = pitch.toFloat(),
            velocity = velocity.toFloat(),
            label = label,
            color = color
        )
        emit(scheduled)
        cursorSeconds = max(cursorSeconds, scheduled.end)
        return scheduled
    }

    suspend fun chord(
        vararg pitches: Number,
        durationSeconds: Number,
        velocity: Number = 1.0,
        startSeconds: Number? = null,
        label: String? = null,
        color: String? = null
    ) {
        val start = startSeconds?.toDouble() ?: cursorSeconds
        pitches.forEach { pitch ->
            emit(
                ScheduledNote(
                    start = start,
                    duration = durationSeconds.toDouble(),
                    pitch = pitch.toFloat(),
                    velocity = velocity.toFloat(),
                    label = label,
                    color = color
                )
            )
        }
        cursorSeconds = max(cursorSeconds, start + durationSeconds.toDouble())
    }

    fun rest(durationSeconds: Number) {
        cursorSeconds += durationSeconds.toDouble()
    }

    fun advance(durationSeconds: Number) {
        rest(durationSeconds)
    }

    suspend fun emit(note: ScheduledNote) {
        scope.yield(note)
        cursorSeconds = max(cursorSeconds, note.end)
    }
}

class NoteProcess internal constructor(
    private val iteratorFactory: () -> Iterator<ScheduledNote>,
    historyRetentionSeconds: Double = 8.0,
    defaultPastSeconds: Double = 1.0,
    defaultFutureSeconds: Double = 6.0
) : ToHTML {

    private val lock = Any()
    private var cachedNotes = ArrayDeque<ScheduledNote>()
    private var sortedNotes = emptyList<ScheduledNote>()
    private var iterator: Iterator<ScheduledNote> = iteratorFactory()
    private var nextNote: ScheduledNote? = null
    private var stateVersion = 0L
    private var lastGeneratedStartSeconds = Double.NEGATIVE_INFINITY
    private var renderCacheKey: PianoRollRenderKey? = null
    private var renderCacheValue: String? = null

    var historyRetentionSeconds: Double = historyRetentionSeconds.coerceAtLeast(0.0)
        set(value) {
            synchronized(lock) {
                field = value.coerceAtLeast(0.0)
                pruneLocked()
            }
        }

    var defaultPastSeconds: Double = defaultPastSeconds.coerceAtLeast(0.0)
        set(value) {
            synchronized(lock) {
                field = value.coerceAtLeast(0.0)
            }
        }

    var defaultFutureSeconds: Double = defaultFutureSeconds.coerceAtLeast(0.0)
        set(value) {
            synchronized(lock) {
                field = value.coerceAtLeast(0.0)
            }
        }

    @Volatile
    var playheadSeconds: Double = 0.0
        private set

    @Volatile
    var generatedUntilSeconds: Double = Double.NEGATIVE_INFINITY
        private set

    @Volatile
    var exhausted: Boolean = false
        private set

    override fun toHTML(): String = pianoRollSvg()

    fun reset(playheadSeconds: Number = this.playheadSeconds): NoteProcess = apply {
        synchronized(lock) {
            cachedNotes = ArrayDeque()
            sortedNotes = emptyList()
            iterator = iteratorFactory()
            nextNote = null
            stateVersion = 0L
            lastGeneratedStartSeconds = Double.NEGATIVE_INFINITY
            renderCacheKey = null
            renderCacheValue = null
            generatedUntilSeconds = Double.NEGATIVE_INFINITY
            exhausted = false
            this.playheadSeconds = playheadSeconds.toDouble()
            primeLocked()
            generatedUntilSeconds = this.playheadSeconds
            touchLocked()
        }
        unrollTo(this.playheadSeconds + defaultFutureSeconds)
        prune()
    }

    fun at(timeSeconds: Number): NoteProcess = apply {
        synchronized(lock) {
            playheadSeconds = timeSeconds.toDouble()
            touchLocked()
            generatedUntilSeconds = max(generatedUntilSeconds, playheadSeconds)
        }
        unrollTo(timeSeconds.toDouble() + defaultFutureSeconds)
        prune()
    }

    fun advanceTo(timeSeconds: Number): NoteProcess = at(timeSeconds)

    fun advanceBy(deltaSeconds: Number): NoteProcess = at(playheadSeconds + deltaSeconds.toDouble())

    fun unrollTo(timeSeconds: Number): NoteProcess = apply {
        synchronized(lock) {
            val target = timeSeconds.toDouble()
            var changed = false
            while (true) {
                primeLocked()
                val candidate = nextNote ?: break
                if (candidate.start > target + 1e-9) break
                cachedNotes.addLast(candidate)
                lastGeneratedStartSeconds = candidate.start
                nextNote = null
                changed = true
            }
            generatedUntilSeconds = max(generatedUntilSeconds, target)
            if (changed) {
                sortedNotes = cachedNotes.toList()
                touchLocked()
            }
        }
    }

    override fun toString(): String = synthPrettyObject(
        this,
        "playheadSeconds" to playheadSeconds,
        "generatedUntilSeconds" to generatedUntilSeconds,
        "cachedNotes" to synchronized(lock) { cachedNotes.size },
        "exhausted" to exhausted,
        "historyRetentionSeconds" to historyRetentionSeconds
    )

    fun prune(): NoteProcess = apply {
        synchronized(lock) {
            pruneLocked()
        }
    }

    fun notesBetween(startSeconds: Number, endSeconds: Number): List<ScheduledNote> {
        val windowStart = min(startSeconds.toDouble(), endSeconds.toDouble())
        val windowEnd = max(startSeconds.toDouble(), endSeconds.toDouble())
        unrollTo(windowEnd)
        synchronized(lock) {
            return sortedNotes.filter { it.intersects(windowStart, windowEnd) }
        }
    }

    fun snapshot(
        pastSeconds: Number = defaultPastSeconds,
        futureSeconds: Number = defaultFutureSeconds
    ): NoteWindowSnapshot {
        val past = pastSeconds.toDouble().coerceAtLeast(0.0)
        val future = futureSeconds.toDouble().coerceAtLeast(0.0)
        val windowStart = playheadSeconds - past
        val windowEnd = playheadSeconds + future

        unrollTo(windowEnd)
        prune()

        synchronized(lock) {
            val notes = sortedNotes.filter { it.intersects(windowStart, windowEnd) }
            return NoteWindowSnapshot(
                notes = notes,
                playheadSeconds = playheadSeconds,
                windowStartSeconds = windowStart,
                windowEndSeconds = windowEnd,
                exhausted = exhausted,
                generatedUntilSeconds = generatedUntilSeconds
            )
        }
    }

    fun pianoRollSvg(
        widthPx: Int = 480,
        heightPx: Int = 96,
        pastSeconds: Number = defaultPastSeconds,
        futureSeconds: Number = defaultFutureSeconds,
        title: String = "Piano Roll",
        background: String = PIANO_ROLL_BACKGROUND,
        primaryColor: String = PIANO_ROLL_BLUE,
        secondaryColor: String = PIANO_ROLL_ORANGE,
        fontFamily: String = PIANO_ROLL_FONT
    ): String {
        val snapshot = snapshot(pastSeconds, futureSeconds)
        val version = synchronized(lock) { stateVersion }
        val key = PianoRollRenderKey(
            version = version,
            widthPx = widthPx,
            heightPx = heightPx,
            title = title,
            background = background,
            primaryColor = primaryColor,
            secondaryColor = secondaryColor,
            fontFamily = fontFamily,
            pastMillis = (pastSeconds.toDouble() * 1000.0).roundToInt(),
            futureMillis = (futureSeconds.toDouble() * 1000.0).roundToInt()
        )
        synchronized(lock) {
            if (renderCacheKey == key && renderCacheValue != null) return renderCacheValue!!
        }
        val svg = snapshot.pianoRollSvg(
            widthPx = widthPx,
            heightPx = heightPx,
            title = title,
            background = background,
            primaryColor = primaryColor,
            secondaryColor = secondaryColor,
            fontFamily = fontFamily
        )
        synchronized(lock) {
            renderCacheKey = key
            renderCacheValue = svg
        }
        return svg
    }

    private fun pruneLocked() {
        val retainFrom = playheadSeconds - max(historyRetentionSeconds, defaultPastSeconds)
        var changed = false
        while (cachedNotes.isNotEmpty() && cachedNotes.first().end < retainFrom) {
            cachedNotes.removeFirst()
            changed = true
        }
        if (changed) {
            sortedNotes = cachedNotes.toList()
            touchLocked()
        }
    }

    private fun touchLocked() {
        stateVersion++
        renderCacheKey = null
        renderCacheValue = null
    }

    private fun primeLocked() {
        if (nextNote != null || exhausted) return
        if (!iterator.hasNext()) {
            exhausted = true
            return
        }
        val candidate = iterator.next()
        if (candidate.start + 1e-9 < lastGeneratedStartSeconds) {
            throw IllegalArgumentException(
                "NoteProcess requires nondecreasing start times; saw ${candidate.start} after $lastGeneratedStartSeconds"
            )
        }
        nextNote = candidate
    }

    private data class PianoRollRenderKey(
        val version: Long,
        val widthPx: Int,
        val heightPx: Int,
        val title: String,
        val background: String,
        val primaryColor: String,
        val secondaryColor: String,
        val fontFamily: String,
        val pastMillis: Int,
        val futureMillis: Int
    )
}

fun noteProcess(
    historyRetentionSeconds: Double = 8.0,
    defaultPastSeconds: Double = 1.0,
    defaultFutureSeconds: Double = 6.0,
    block: suspend NoteSequenceScope.() -> Unit
): NoteProcess = NoteProcess(
    iteratorFactory = {
        noteIterator(block)
    },
    historyRetentionSeconds = historyRetentionSeconds,
    defaultPastSeconds = defaultPastSeconds,
    defaultFutureSeconds = defaultFutureSeconds
).reset()

fun notes(
    historyRetentionSeconds: Double = 8.0,
    defaultPastSeconds: Double = 1.0,
    defaultFutureSeconds: Double = 6.0,
    block: suspend NoteSequenceScope.() -> Unit
): NoteProcess = noteProcess(
    historyRetentionSeconds = historyRetentionSeconds,
    defaultPastSeconds = defaultPastSeconds,
    defaultFutureSeconds = defaultFutureSeconds,
    block = block
)

fun Iterable<ScheduledNote>.pianoRollSvg(
    nowSeconds: Number = 0.0,
    pastSeconds: Number = 1.0,
    futureSeconds: Number = 6.0,
    widthPx: Int = 480,
    heightPx: Int = 96,
    title: String = "Piano Roll",
    background: String = PIANO_ROLL_BACKGROUND,
    primaryColor: String = PIANO_ROLL_BLUE,
    secondaryColor: String = PIANO_ROLL_ORANGE,
    fontFamily: String = PIANO_ROLL_FONT
): String {
    val playhead = nowSeconds.toDouble()
    val past = pastSeconds.toDouble().coerceAtLeast(0.0)
    val future = futureSeconds.toDouble().coerceAtLeast(0.0)
    return NoteWindowSnapshot(
        notes = this.filter { it.intersects(playhead - past, playhead + future) }.sortedBy { it.start },
        playheadSeconds = playhead,
        windowStartSeconds = playhead - past,
        windowEndSeconds = playhead + future
    ).pianoRollSvg(
        widthPx = widthPx,
        heightPx = heightPx,
        title = title,
        background = background,
        primaryColor = primaryColor,
        secondaryColor = secondaryColor,
        fontFamily = fontFamily
    )
}

fun NoteWindowSnapshot.pianoRollSvg(
    widthPx: Int = 480,
    heightPx: Int = 96,
    title: String = "Piano Roll",
    background: String = PIANO_ROLL_BACKGROUND,
    primaryColor: String = PIANO_ROLL_BLUE,
    secondaryColor: String = PIANO_ROLL_ORANGE,
    fontFamily: String = PIANO_ROLL_FONT
): String {
    val spanSeconds = (windowEndSeconds - windowStartSeconds).coerceAtLeast(0.001)
    val w = widthPx.toDouble()
    val h = heightPx.toDouble()
    val padLeft = 34.0
    val padRight = 8.0
    val padTop = 18.0
    val padBottom = 12.0
    val innerW = (w - padLeft - padRight).coerceAtLeast(1.0)
    val innerH = (h - padTop - padBottom).coerceAtLeast(1.0)
    val x0 = padLeft
    val y0 = padTop

    if (notes.isEmpty()) {
        return """
            <svg xmlns="http://www.w3.org/2000/svg" width="$widthPx" height="$heightPx" viewBox="0 0 $widthPx $heightPx">
              <rect x="0" y="0" width="$widthPx" height="$heightPx" fill="$background"/>
              <text x="${x0.f2()}" y="13" font-family="$fontFamily" font-size="10" fill="$primaryColor">$title</text>
              ${buildRollBadges(
                  listOf(
                      RollBadge("now ${formatSeconds(playheadSeconds)}s"),
                      RollBadge("${formatSeconds(spanSeconds)}s span")
                  ),
                  widthPx = widthPx,
                  y = 13,
                  fontFamily = fontFamily
              )}
              <line x1="${xForTime(playheadSeconds, windowStartSeconds, spanSeconds, x0, innerW).f2()}" y1="${y0.f2()}" x2="${xForTime(playheadSeconds, windowStartSeconds, spanSeconds, x0, innerW).f2()}" y2="${(y0 + innerH).f2()}" stroke="$secondaryColor" stroke-width="1.4" opacity="0.85"/>
              <text x="${(widthPx / 2.0).f2()}" y="${(heightPx / 2.0).f2()}" text-anchor="middle" dominant-baseline="middle" font-family="$fontFamily" font-size="11" fill="$primaryColor" opacity="0.7">Waiting for notes</text>
            </svg>
        """.trimIndent()
    }

    val minPitchRaw = floor((notes.minOfOrNull { it.pitch.toDouble() } ?: 60.0) - 1.0)
    val maxPitchRaw = ceil((notes.maxOfOrNull { it.pitch.toDouble() } ?: 72.0) + 1.0)
    val paddedMinPitch = minPitchRaw
    val paddedMaxPitch = max(maxPitchRaw, paddedMinPitch + 6.0)
    val pitchSpan = (paddedMaxPitch - paddedMinPitch).coerceAtLeast(1.0)
    val laneHeight = max(4.0, innerH / (pitchSpan + 1.0))

    fun yForPitch(pitch: Double): Double {
        val t = ((pitch - paddedMinPitch) / pitchSpan).coerceIn(0.0, 1.0)
        return y0 + innerH - t * innerH
    }

    val horizontalGrid = buildString {
        val startPitch = paddedMinPitch.toInt()
        val endPitch = paddedMaxPitch.toInt()
        for (pitch in startPitch..endPitch) {
            val top = yForPitch(pitch + 0.5)
            val bottom = yForPitch(pitch - 0.5)
            val height = (bottom - top).coerceAtLeast(0.5)
            val noteClass = ((pitch % 12) + 12) % 12
            val opacity = when {
                noteClass == 0 -> 0.08
                noteClass in setOf(1, 3, 6, 8, 10) -> 0.045
                else -> 0.02
            }
            append(
                """<rect x="${x0.f2()}" y="${top.f2()}" width="${innerW.f2()}" height="${height.f2()}" fill="$primaryColor" opacity="${opacity.f3()}"/>"""
            )
            append('\n')
            if (noteClass == 0) {
                append(
                    """<line x1="${x0.f2()}" y1="${top.f2()}" x2="${(x0 + innerW).f2()}" y2="${top.f2()}" stroke="$primaryColor" opacity="0.12" stroke-width="1"/>"""
                )
                append('\n')
            }
        }
    }

    val timeStep = selectTimeDivision(spanSeconds)
    val verticalGrid = buildString {
        var gridTime = floor(windowStartSeconds / timeStep) * timeStep
        while (gridTime <= windowEndSeconds + 1e-9) {
            val x = xForTime(gridTime, windowStartSeconds, spanSeconds, x0, innerW)
            val rel = gridTime - playheadSeconds
            val isNow = abs(rel) < timeStep * 0.18
            val color = if (isNow) secondaryColor else primaryColor
            val opacity = if (isNow) 0.55 else 0.14
            val dash = if (isNow) "" else " stroke-dasharray=\"2,3\""
            append(
                """<line x1="${x.f2()}" y1="${y0.f2()}" x2="${x.f2()}" y2="${(y0 + innerH).f2()}" stroke="$color" stroke-width="${if (isNow) "1.4" else "1"}" opacity="$opacity"$dash/>"""
            )
            append('\n')
            val label = if (isNow) "now" else formatRelativeSeconds(rel)
            append(
                """<text x="${x.f2()}" y="${(y0 + innerH + 10).f2()}" text-anchor="middle" font-family="$fontFamily" font-size="9" fill="$color" opacity="${if (isNow) "0.92" else "0.7"}">${escapeRollXml(label)}</text>"""
            )
            append('\n')
            gridTime += timeStep
        }
    }

    val noteRects = buildString {
        notes.sortedWith(compareBy<ScheduledNote> { it.start }.thenByDescending { it.pitch }).forEach { note ->
            val clampedStart = note.start.coerceAtLeast(windowStartSeconds)
            val clampedEnd = note.end.coerceAtMost(windowEndSeconds)
            val x = xForTime(clampedStart, windowStartSeconds, spanSeconds, x0, innerW)
            val endX = xForTime(clampedEnd, windowStartSeconds, spanSeconds, x0, innerW)
            val width = max(2.0, endX - x)
            val y = yForPitch(note.pitch.toDouble()) - laneHeight * 0.42
            val fill = note.color ?: primaryColor
            val velocity = note.velocity.coerceIn(0f, 1f)
            val active = note.start <= playheadSeconds && note.end > playheadSeconds
            val past = note.end <= playheadSeconds
            val opacity = when {
                active -> 0.92
                past -> 0.28 + velocity * 0.18
                else -> 0.52 + velocity * 0.38
            }.coerceIn(0.18, 0.98)
            val stroke = if (active) secondaryColor else fill
            val strokeOpacity = if (active) 0.95 else 0.45
            append(
                """<rect x="${x.f2()}" y="${y.f2()}" width="${width.f2()}" height="${max(3.0, laneHeight * 0.84).f2()}" rx="2" fill="$fill" opacity="${opacity.f3()}" stroke="$stroke" stroke-opacity="${strokeOpacity.f3()}" stroke-width="${if (active) "1.2" else "0.7"}"/>"""
            )
            append('\n')
            append(
                """<line x1="${x.f2()}" y1="${y.f2()}" x2="${x.f2()}" y2="${(y + max(3.0, laneHeight * 0.84)).f2()}" stroke="${if (active) secondaryColor else fill}" stroke-width="1" opacity="0.95"/>"""
            )
            append('\n')
            if (!note.label.isNullOrBlank() && width >= 28.0) {
                append(
                    """<text x="${(x + 4).f2()}" y="${(y + max(9.0, laneHeight * 0.62)).f2()}" font-family="$fontFamily" font-size="8" fill="$background" opacity="0.95">${escapeRollXml(note.label)}</text>"""
                )
                append('\n')
            }
        }
    }

    val rangeMid = ((paddedMinPitch + paddedMaxPitch) * 0.5).roundToInt().toFloat()
    val pitchLabels = listOf(
        paddedMaxPitch.roundToInt().toFloat(),
        rangeMid,
        paddedMinPitch.roundToInt().toFloat()
    ).distinct()
        .joinToString("\n") { pitch ->
            val y = yForPitch(pitch.toDouble())
            """<text x="${(x0 - 4).f2()}" y="${(y + 3).f2()}" text-anchor="end" font-family="$fontFamily" font-size="9" fill="$primaryColor" opacity="0.75">${escapeRollXml(formatPitchName(pitch))}</text>"""
        }

    val badges = buildRollBadges(
        listOf(
            RollBadge("now ${formatSeconds(playheadSeconds)}s"),
            RollBadge("${notes.count { it.start <= playheadSeconds && it.end > playheadSeconds }} on", secondaryColor),
            RollBadge("${notes.size} notes"),
            RollBadge("${formatPitchName(paddedMinPitch.toFloat())}-${formatPitchName(paddedMaxPitch.toFloat())}")
        ),
        widthPx = widthPx,
        y = 13,
        fontFamily = fontFamily
    )

    return """
        <svg xmlns="http://www.w3.org/2000/svg" width="$widthPx" height="$heightPx" viewBox="0 0 $widthPx $heightPx">
          <rect x="0" y="0" width="$widthPx" height="$heightPx" fill="$background"/>
          <text x="${x0.f2()}" y="13" font-family="$fontFamily" font-size="10" fill="$primaryColor">$title</text>
          $badges
          $horizontalGrid
          $verticalGrid
          $noteRects
          <line x1="${xForTime(playheadSeconds, windowStartSeconds, spanSeconds, x0, innerW).f2()}" y1="${y0.f2()}" x2="${xForTime(playheadSeconds, windowStartSeconds, spanSeconds, x0, innerW).f2()}" y2="${(y0 + innerH).f2()}" stroke="$secondaryColor" stroke-width="1.5" opacity="0.95"/>
          $pitchLabels
        </svg>
    """.trimIndent()
}

fun NoteProcess.pianoRollHtml(
    widthPx: Int = 480,
    heightPx: Int = 96,
    pastSeconds: Number = defaultPastSeconds,
    futureSeconds: Number = defaultFutureSeconds
): String = pianoRollSvg(
    widthPx = widthPx,
    heightPx = heightPx,
    pastSeconds = pastSeconds,
    futureSeconds = futureSeconds
)

private class InlinePianoRollState {
    val outputRateLimiter = RateLimiter(10.0, 0.2)
}

private fun Marker.inlinePianoRollState(): InlinePianoRollState =
    properties.computeIfAbsent("__piano_roll__") { InlinePianoRollState() } as InlinePianoRollState

fun Marker.pianoRoll(
    value: NoteProcess,
    pastSeconds: Number = value.defaultPastSeconds,
    futureSeconds: Number = value.defaultFutureSeconds,
    widthPx: Int = 480,
    heightPx: Int = 96
): NoteProcess {
    val location = locationForTextStringOnStack(".pianoRoll(", this.box, end = true, offset = 1)
    val state = inlinePianoRollState()
    state.outputRateLimiter {
        location?.let {
            this.box._printHere(
                it.line,
                value.pianoRollSvg(
                    widthPx = widthPx,
                    heightPx = heightPx,
                    pastSeconds = pastSeconds,
                    futureSeconds = futureSeconds
                )
            )
        }
    }
    return value
}

private data class RollBadge(
    val text: String,
    val color: String = PIANO_ROLL_BLUE
)

private fun noteIterator(block: suspend NoteSequenceScope.() -> Unit): Iterator<ScheduledNote> {
    val generator = generator<ScheduledNote> {
        NoteSequenceScope(this).block()
    }
    return object : Iterator<ScheduledNote> {
        private var started = false
        private var finished = false
        private var nextValue: ScheduledNote? = null

        override fun hasNext(): Boolean {
            if (finished) return false
            if (!started) {
                nextValue = generator.start()
                started = true
                if (nextValue == null) finished = true
            }
            return !finished
        }

        override fun next(): ScheduledNote {
            if (!hasNext()) throw NoSuchElementException("No more scheduled notes")
            val out = nextValue ?: throw NoSuchElementException("No more scheduled notes")
            nextValue = generator.next(Unit)
            if (nextValue == null) finished = true
            return out
        }
    }
}

private fun buildRollBadges(
    badges: List<RollBadge>,
    widthPx: Int,
    y: Int,
    fontFamily: String
): String {
    if (badges.isEmpty()) return ""

    var cursor = widthPx - 8.0
    val parts = mutableListOf<String>()
    badges.asReversed().forEach { badge ->
        val estimatedWidth = badge.text.length * 6.2 + 8.0
        cursor -= estimatedWidth
        if (cursor < widthPx * 0.34) return@forEach
        parts += """<text x="${cursor.f2()}" y="$y" font-family="$fontFamily" font-size="10" fill="${badge.color}" opacity="0.92">${escapeRollXml(badge.text)}</text>"""
        cursor -= 8.0
    }
    return parts.joinToString("\n")
}

private fun xForTime(timeSeconds: Double, windowStartSeconds: Double, spanSeconds: Double, x0: Double, innerW: Double): Double {
    val t = ((timeSeconds - windowStartSeconds) / spanSeconds).coerceIn(0.0, 1.0)
    return x0 + t * innerW
}

private fun selectTimeDivision(spanSeconds: Double): Double {
    val candidates = doubleArrayOf(0.125, 0.25, 0.5, 1.0, 2.0, 4.0, 8.0, 16.0)
    val target = spanSeconds / 6.0
    return candidates.firstOrNull { it >= target } ?: candidates.last()
}

private fun formatSeconds(value: Double): String {
    val absValue = abs(value)
    val decimals = when {
        absValue >= 10.0 -> 1
        absValue >= 1.0 -> 2
        else -> 3
    }
    return String.format(Locale.US, "%.${decimals}f", value)
}

private fun formatRelativeSeconds(value: Double): String =
    when {
        abs(value) < 0.0005 -> "now"
        value > 0.0 -> "+${formatSeconds(value)}"
        else -> formatSeconds(value)
    }

private fun formatPitchName(pitch: Float): String {
    val rounded = pitch.roundToInt()
    val cents = ((pitch - rounded) * 100f).roundToInt()
    val name = noteNames[(rounded % 12 + 12) % 12]
    val octave = (rounded / 12) - 1
    return if (cents == 0) "$name$octave" else "$name$octave${if (cents > 0) "+" else ""}$cents"
}

private fun escapeRollXml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&#39;")

private fun Double.f2(): String = String.format(Locale.US, "%.2f", this)

private fun Double.f3(): String = String.format(Locale.US, "%.3f", this)

private val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
