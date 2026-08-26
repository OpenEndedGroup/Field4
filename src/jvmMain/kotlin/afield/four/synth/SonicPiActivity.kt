package afield.four.synth

import afield.Box
import afield.RawDecoration
import afield.decorationState

/**
 * Sonic Pi's per-line activity, shown in the editor.
 *
 * Sonic Pi tells its client which line just triggered a note — `/flash`, from `play`, `synth`,
 * `sample`, `control`, `play_chord` and the `with_fx`/`live_loop` block boundaries, tagged with the
 * caller's line. That answers "what is making sound, and where in the code", so it belongs on the
 * code rather than in a log.
 *
 * Three things make this less trivial than painting the lines that flashed.
 *
 * The rate is musical, not human. Two `live_loop`s at a modest tempo produce something like sixteen
 * flashes a second and each iteration reports the loop head, the `play` and the `end`. Repainting per
 * flash would push a decoration set over the websocket faster than anyone can see, so
 * [sonicPiActivityDecorations] is a pure function of *recency* that a caller samples at a sane rate —
 * the launch loop's 50ms poll — rather than something the flash handler drives.
 *
 * Lines have to cool off. A flash is an instant, not a duration, so a line that fired 40ms ago and
 * one that fired 400ms ago are both "recent" and should not look identical; without a decay the
 * display latches on and every line that has ever sounded stays lit.
 *
 * And the line numbers need translating. Sonic Pi counts from line 1 of the source it was handed,
 * which is the inside of a Kotlin triple-quoted string — see [inferSonicPiLineOffset] for why that
 * offset is inferred rather than assumed to be zero.
 */

/** How recently a line fired, bucketed for display. */
internal enum class SonicPiHeat(val className: String, val maxAgeMs: Long, val alpha: String) {
    HOT("afield-sonicpi-flash-hot", 120L, "0.42"),
    WARM("afield-sonicpi-flash-warm", 300L, "0.26"),
    COOL("afield-sonicpi-flash-cool", 650L, "0.12");

    companion object {
        fun forAge(ageMs: Long): SonicPiHeat? = entries.firstOrNull { ageMs <= it.maxAgeMs }
    }
}

// A left border rather than a full-width background: the line stays readable while it pulses, and a
// background wide enough to notice is a background too loud to code against.
private fun heatCss(heat: SonicPiHeat): String =
    ".${heat.className} { background: rgba(120, 210, 255, ${heat.alpha}); " +
        "box-shadow: inset 2px 0 0 rgba(120, 210, 255, 0.9); }"

/**
 * Decorations for the lines of `workspace` that are currently sounding.
 *
 * Pure, and it has to be: the whole display is a function of (ages, offset), so the behaviour that
 * matters — decay, translation, coalescing — is testable without an editor, a websocket or a box.
 */
internal fun sonicPiActivityDecorations(
    ages: Map<Int, Long>,
    lineOffset: Int,
    columnLimit: Int = 400
): List<RawDecoration> = ages.entries
    .mapNotNull { (sonicPiLine, ageMs) ->
        val heat = SonicPiHeat.forAge(ageMs) ?: return@mapNotNull null
        val boxLine = sonicPiLine + lineOffset
        if (boxLine < 1) return@mapNotNull null
        // RawDecoration is 1-based and Monaco clamps a range to the line's real end, so a generous
        // end column paints the whole line without this needing to know the text.
        RawDecoration(
            lineStart = boxLine,
            colStart = 1,
            lineEnd = boxLine,
            colEnd = columnLimit,
            classname = heat.className,
            classDef = mapOf(heat.className to heatCss(heat))
        )
    }
    .sortedBy { it.lineStart }

/**
 * Paint one frame of activity onto `box`.
 *
 * Returns the decorations applied, so a caller can tell whether anything changed without reading the
 * box back. Swallows failures: this is decoration, and a box with no live session (a headless run,
 * a box the user has since deleted) must not take the music down with it.
 */
internal fun applySonicPiActivity(
    box: Box,
    sonicPi: SonicPi,
    workspace: String,
    lineOffset: Int
): List<RawDecoration> {
    val decorations = sonicPiActivityDecorations(sonicPi.lineAges(workspace), lineOffset)
    runCatching {
        val state = box.decorationState
        val stale = state.keys.filter { it.startsWith(ACTIVITY_KEY_PREFIX) }
        stale.forEach { state.remove(it) }
        decorations.forEach { state["$ACTIVITY_KEY_PREFIX${it.lineStart}"] = it }
    }
    return decorations
}

/** Remove every activity decoration, for when the music stops. */
internal fun clearSonicPiActivity(box: Box) {
    runCatching {
        val state = box.decorationState
        state.keys.filter { it.startsWith(ACTIVITY_KEY_PREFIX) }.forEach { state.remove(it) }
    }
}

// Namespaced so this shares Box.decorationState with error markers and everything else that
// decorates a box, and only ever clears its own entries.
private const val ACTIVITY_KEY_PREFIX = "sonicpi-activity:"
