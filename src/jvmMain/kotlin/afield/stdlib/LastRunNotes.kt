@file:afield.CompletionCategory("Workspace")

package afield.stdlib

import afield.Box
import java.util.concurrent.ConcurrentHashMap

/**
 * What each line of a box produced the last time it ran, kept so the editor can show it as a line
 * note ([LineNotes]) without running anything.
 *
 * StackSL and RectSL can evaluate part of a program on demand, because a partial StackSL program is
 * still a pure shader and a RectSL scene a pure description. Forth, Clojure and Python cannot: their
 * programs print, draw, define things and change state that outlives the run. So their runtimes
 * *record* instead — as a run goes, each top-level step (a Forth line, a Clojure form, a Python
 * statement) reports the lines it spans and what it came to — and the note for a line is read back
 * from that record.
 *
 * A note from here always says it is from the last run and how long ago that was. It follows the
 * lines it was recorded against when an edit above moves them, and says so plainly when the line
 * itself has changed since: the value it shows was the value of *different* text. A step that
 * failed says where the run stopped, and a runtime that knows which of its lines never ran marks
 * them not reached.
 */
object LastRunNotes {
    /**
     * What one step of a run produced: its lines [firstLine]..[lastLine] (1-based, counted in the
     * text that ran), a sentence about it, and a picture when the language draws one.
     */
    class Produced(
        val firstLine: Int,
        val lastLine: Int,
        val caption: String,
        val png: ByteArray? = null,
        /** [caption]'s value part for the eye, results marked strong ([LineNotes.Note.value]); empty: the caption. */
        val value: List<LineNotes.Segment> = emptyList(),
        /**
         * The picture, drawn only if a note for these lines is asked for, and then once. A run records a
         * step per line, and drawing each one as it ran cost far more than the run (0.7 ms a picture).
         */
        val picture: (() -> ByteArray?)? = null,
        /** Device pixels per CSS pixel of the picture. */
        val imageScale: Int = 1,
        /**
         * What to show beside the picture, when it is shown: [value] less whatever the picture already
         * says (a Forth stack is drawn, so it is not also written out). Null: [value]. The caption keeps
         * the whole sentence, for a reader that cannot see the picture; a note without its picture (one
         * whose line has changed since) shows [value].
         */
        val besidePicture: List<LineNotes.Segment>? = null,
    )

    private enum class Kind { PRODUCED, STOPPED, NOT_REACHED }

    /** One recorded span, in box lines, with the text those lines held when it ran. */
    private class Entry(
        val first: Int, val lines: List<String>, val kind: Kind, val caption: String, drawPng: () -> ByteArray?, val at: Long,
        val value: List<LineNotes.Segment> = emptyList(), val imageScale: Int = 1,
        val besidePicture: List<LineNotes.Segment>? = null,
    ) {
        val last get() = first + lines.size - 1
        val png: ByteArray? by lazy(drawPng)
    }

    private val records = ConcurrentHashMap<String, List<Entry>>()

    private fun key(box: Box, property: String) = box.id + "\u001f" + property

    /**
     * Record a run of [code], which sits in [box]'s [property] starting after box line [lineOffset]
     * (0-based, as `Execute.line` counts; 0 for a whole-box run). [produced] is what its steps came to,
     * [stoppedAt] the run-relative line it failed on (with [stopReason]) or null, and [notReached]
     * run-relative lines the runtime knows it never got to. A run replaces what an earlier run
     * recorded for any of the lines it covered, and leaves the rest — a fragment run updates only the
     * fragment's lines.
     */
    fun record(
        box: Box,
        property: String,
        code: String,
        lineOffset: Int,
        produced: List<Produced>,
        stoppedAt: Int? = null,
        stopReason: String? = null,
        notReached: Collection<Int> = emptyList(),
        at: Long = System.currentTimeMillis(),
    ) {
        val codeLines = code.split("\n")
        fun linesOf(first: Int, last: Int) = (first..last).map { codeLines.getOrElse(it - 1) { "" } }
        val fresh = ArrayList<Entry>()
        produced.filter { it.firstLine >= 1 && it.lastLine >= it.firstLine && it.lastLine <= codeLines.size }
            // A later step over the same lines replaces an earlier one.
            .associateBy { it.firstLine to it.lastLine }.values
            .forEach { p ->
                fresh += Entry(lineOffset + p.firstLine, linesOf(p.firstLine, p.lastLine), Kind.PRODUCED, p.caption,
                    { p.png ?: p.picture?.invoke() }, at, p.value, p.imageScale, p.besidePicture)
            }
        if (stoppedAt != null && stoppedAt in 1..codeLines.size) {
            fresh.removeAll { stoppedAt + lineOffset in it.first..it.last }
            fresh += Entry(lineOffset + stoppedAt, linesOf(stoppedAt, stoppedAt), Kind.STOPPED, stopReason?.let { oneLine(it, 160) } ?: "an error", { null }, at)
        }
        notReached.filter { it in 1..codeLines.size && codeLines[it - 1].isNotBlank() }
            .filter { line -> fresh.none { line + lineOffset in it.first..it.last } }
            .forEach { fresh += Entry(lineOffset + it, linesOf(it, it), Kind.NOT_REACHED, "", { null }, at) }
        val covered = (lineOffset + 1)..(lineOffset + codeLines.size)
        records.compute(key(box, property)) { _, old ->
            (old.orEmpty().filter { it.last < covered.first || it.first > covered.last } + fresh).sortedBy { it.first }
        }
    }

    /** Forget everything recorded for [box]'s [property]. */
    fun forget(box: Box, property: String = "code") {
        records.remove(key(box, property))
    }

    /**
     * The note for [line] (1-based) of [source], the text of [box]'s [property] as the editor holds it
     * now, from the last run's record; null when the record says nothing about that line. [now] is
     * the clock the note's age is read from.
     */
    fun note(box: Box, property: String, source: String, line: Int, now: Long = System.currentTimeMillis()): LineNotes.Note? {
        val entries = records[key(box, property)] ?: return null
        val current = source.split("\n")
        fun matchesAt(e: Entry, first: Int) =
            first >= 1 && first + e.lines.size - 1 <= current.size && e.lines.indices.all { current[first - 1 + it] == e.lines[it] }

        // Where each entry's text is now: where it was, else the nearest place it moved to.
        fun placeOf(e: Entry): Int? {
            if (matchesAt(e, e.first)) return e.first
            val lo = maxOf(1, line - e.lines.size + 1)
            return (lo..line).filter { matchesAt(e, it) }.minByOrNull { kotlin.math.abs(it - e.first) }
        }

        val found = entries.mapNotNull { e -> placeOf(e)?.let { e to it } }
            .filter { (e, at) -> line in at until at + e.lines.size }
            // The narrowest step that covers the line says the most about it.
            .minWithOrNull(compareBy({ it.first.lines.size }, { kotlin.math.abs(it.second - it.first.first) }))
        if (found != null) {
            val (e, _) = found
            val ago = ago(now - e.at)
            val meta = "last run, $ago"
            return when (e.kind) {
                Kind.PRODUCED -> {
                    val png = e.png
                    val value = if (png != null && e.besidePicture != null) e.besidePicture else e.value.ifEmpty { listOf(LineNotes.plain(e.caption)) }
                    LineNotes.Note("$meta · ${e.caption}", png, volatile = true, meta = meta, value = value,
                        imageScale = if (png != null) e.imageScale else 1)
                }
                Kind.STOPPED -> LineNotes.Note("$meta · stopped here: ${e.caption}", volatile = true, meta = meta,
                    value = listOf(LineNotes.plain("stopped here: "), LineNotes.strong(e.caption)))
                Kind.NOT_REACHED -> LineNotes.Note("$meta · not reached: the run stopped above this line", volatile = true, meta = meta,
                    value = listOf(LineNotes.plain("not reached: the run stopped above this line")))
            }
        }
        // Nothing recorded matches the text: say so if a recorded step sat on this line, since what it
        // produced was produced by other text.
        val stale = entries.filter { line in it.first..it.last && it.kind != Kind.NOT_REACHED }.minByOrNull { it.lines.size } ?: return null
        val then = when (stale.kind) {
            Kind.STOPPED -> "it stopped here: ${stale.caption}"
            else -> stale.caption
        }
        val meta = "changed since the last run (${ago(now - stale.at)}), so this is what the old text did"
        val value = if (stale.kind == Kind.STOPPED) listOf(LineNotes.plain("it stopped here: "), LineNotes.strong(stale.caption))
            else stale.value.ifEmpty { listOf(LineNotes.plain(stale.caption)) }
        return LineNotes.Note("$meta · $then", volatile = true, meta = meta, value = value)
    }

    /** "just now", "12 s ago", "4 min ago", "3 h ago", "2 days ago". */
    fun ago(millis: Long): String {
        val s = maxOf(0L, millis) / 1000
        return when {
            s < 2 -> "just now"
            s < 90 -> "$s s ago"
            s < 90 * 60 -> "${(s + 30) / 60} min ago"
            s < 36 * 3600 -> "${(s + 1800) / 3600} h ago"
            else -> "${(s + 43200) / 86400} days ago"
        }
    }

    /** [text] on one line, cut to [limit] characters. */
    fun oneLine(text: String, limit: Int = 120): String {
        val flat = text.replace(Regex("\\s*\n\\s*"), " ⏎ ").trim()
        return if (flat.length > limit) flat.take(limit - 1) + "…" else flat
    }
}

/**
 * Line notes for every box whose runtime records its runs ([LastRunNotes]) — Forth, Clojure and Python
 * boxes — on the `code` property they keep their programs in. A box nothing recorded for is left to
 * the other providers.
 */
@afield.RunsAt(afield.plugins)
fun installLastRunLineNotes() {
    LineNotes.register("code") { box, source, line -> LastRunNotes.note(box, "code", source, line) }
}
