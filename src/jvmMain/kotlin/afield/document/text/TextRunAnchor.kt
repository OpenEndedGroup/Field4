package afield.document.text

import afield.document.graph.Box
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val TEXT_RUN_ANCHOR = "textRunAnchor"
internal const val TEXT_RUN_SOURCES = "textRunSources"
private const val EXCERPT_LENGTH = 120

private fun String.runBoundary(offset: Int) = offset == 0 || offset == length ||
    !(this[offset - 1].isHighSurrogate() && this[offset].isLowSurrogate())

internal fun runExcerpt(text: String, start: Int, end: Int): String {
    val stop = minOf(end, start + EXCERPT_LENGTH)
    return text.substring(start, if (text.runBoundary(stop)) stop else stop - 1)
}

// A shared UTF-16 half is not a surviving character: for example, 🍎 and 🍏 share their high surrogate.
private fun sameUnit(before: String, oldAt: Int, after: String, newAt: Int): Boolean {
    val unit = before[oldAt]
    return unit == after[newAt] && when {
        unit.isHighSurrogate() -> before.getOrNull(oldAt + 1) == after.getOrNull(newAt + 1)
        unit.isLowSurrogate() -> before.getOrNull(oldAt - 1) == after.getOrNull(newAt - 1)
        else -> true
    }
}

/** A versioned range in a box string property, in UTF-16 offsets after newline normalization. */
@Serializable
internal data class TextRunAnchor(
    val version: Int,
    val id: String,
    val sourceUid: String,
    val property: String,
    val start: Int,
    val end: Int,
    val excerpt: String,
    val lost: Boolean = false,
    val cut: TextRunCut? = null,
) {
    fun matches(text: String): Boolean = start >= 0 && end > start && end <= text.length &&
        text.runBoundary(start) && text.runBoundary(end) &&
        (runExcerpt(text, start, end) == excerpt || text.substring(start, minOf(end, start + EXCERPT_LENGTH)) == excerpt)

    fun rebase(before: String, after: String, diff: TextDiff): TextRunAnchor {
        if (lost) return this
        if (!matches(before)) return copy(lost = true)
        if (diff.boundedOut) {
            // A bulk edit can change both sides of an untouched passage. Recover it only if
            // the entire passage occurs once in each revision; never guess among duplicates.
            val passage = before.substring(start, end)
            val at = after.indexOf(passage)
            if (at >= 0 && at == after.lastIndexOf(passage) &&
                before.indexOf(passage) == start && before.lastIndexOf(passage) == start) {
                return copy(start = at, end = at + passage.length)
            }
        }
        var first = Int.MAX_VALUE
        var last = -1
        var hasText = false
        for ((oldStart, newStart, length) in diff.runs) {
            val low = maxOf(start, oldStart)
            val high = minOf(end, oldStart + length)
            if (low >= high) continue
            if (first == Int.MAX_VALUE) first = newStart + low - oldStart
            last = newStart + high - oldStart
            hasText = hasText || (low until high).any { !before[it].isWhitespace() }
        }
        // A replacement with no surviving characters is a lost passage, not a new attachment.
        if (!hasText) return copy(lost = true)
        return copy(start = first, end = last, excerpt = runExcerpt(after, first, last))
    }

    companion object {
        private val wire = Json { encodeDefaults = true; explicitNulls = false }
        fun decode(value: Any?): TextRunAnchor? = (value as? String)?.let { raw ->
            runCatching { wire.decodeFromString<TextRunAnchor>(raw) }.getOrNull()?.takeIf { it.version == 1 }
        }
        fun encode(anchor: TextRunAnchor): String = wire.encodeToString(anchor)
        fun decodeSources(value: Any?): List<TextRunAnchor> = (value as? String)?.let { raw ->
            runCatching { wire.decodeFromString<List<TextRunAnchor>>(raw) }.getOrNull()
        }.orEmpty().filter { it.version == 1 }
        fun encodeSources(anchors: List<TextRunAnchor>): String = wire.encodeToString(anchors)
    }
}

internal fun String.normalizedRunSource() = replace("\r\n", "\n").replace('\r', '\n')

internal fun Box.textRunSources() = TextRunAnchor.decodeSources(getPropertyRaw(TEXT_RUN_SOURCES))
internal fun Box.writeTextRunSources(sources: List<TextRunAnchor>) =
    setPropertyRaw(TEXT_RUN_SOURCES, TextRunAnchor.encodeSources(sources))

internal fun validateTextRunAnchor(value: String, sourceBox: (String) -> Box?): String {
    val anchor = TextRunAnchor.decode(value) ?: return ""
    if (anchor.property != "contents") return ""
    val text = (sourceBox(anchor.sourceUid)?.getPropertyRaw(anchor.property) as? String)?.normalizedRunSource()
    return TextRunAnchor.encode(if (text != null && anchor.matches(text)) anchor else anchor.copy(lost = true))
}

internal fun rebaseChildTextRuns(source: Box, property: String, oldValue: Any?, newValue: Any?, exactDiff: TextDiff? = null) {
    if (property != "contents" || oldValue !is String || oldValue == newValue && exactDiff == null) return
    val children = (source.getPropertyRaw("children") as? Collection<*>).orEmpty()
    val sources = source.textRunSources()
    val links = children.filterIsInstance<Box>().mapNotNull { child ->
        val range = TextRunAnchor.decode(child.getPropertyRaw(TEXT_RUN_ANCHOR))
        if (range != null && !range.lost && range.sourceUid == source.id && range.property == property) child to range else null
    }
    if (links.isEmpty() && sources.none { !it.lost }) return
    val before = oldValue.normalizedRunSource()
    val after = (newValue as? String).orEmpty().normalizedRunSource()
    if (before == after && exactDiff == null) return
    val matches = exactDiff ?: textMatches(before, after)
    val updated = sources.map { anchor -> anchor.rebase(before, after, matches) }
    if (updated != sources) source.writeTextRunSources(updated)
    for ((child, range) in links) {
        val next = range.rebase(before, after, matches)
        if (next != range) child.setPropertyRaw(TEXT_RUN_ANCHOR, TextRunAnchor.encode(next))
    }
}

/** A contiguous unchanged run within two revisions of a text property. */
internal data class TextMatch(val oldStart: Int, val newStart: Int, val length: Int)

/**
 * Find unchanged runs with a bounded Myers diff, after removing common edges. The cap bounds
 * whole-document replacements; in that case only the unchanged edges are trusted.
 */
internal fun textMatches(before: String, after: String): TextDiff {
    var prefix = 0
    while (prefix < minOf(before.length, after.length) && sameUnit(before, prefix, after, prefix)) ++prefix
    var suffix = 0
    val limit = minOf(before.length, after.length) - prefix
    while (suffix < limit && sameUnit(before, before.lastIndex - suffix, after, after.lastIndex - suffix)) ++suffix
    val n = before.length - prefix - suffix
    val m = after.length - prefix - suffix
    val middle = if (n > 0 && m > 0) middleMatches(before, after, prefix, n, m) else emptyList()
    val runs = buildList {
        if (prefix > 0) add(TextMatch(0, 0, prefix))
        addAll(middle.orEmpty())
        if (suffix > 0) add(TextMatch(before.length - suffix, after.length - suffix, suffix))
    }
    return TextDiff(runs, boundedOut = middle == null)
}

internal data class TextDiff(val runs: List<TextMatch>, val boundedOut: Boolean)

private fun middleMatches(before: String, after: String, offset: Int, n: Int, m: Int): List<TextMatch>? {
    val maximum = minOf(256, n + m)
    if (kotlin.math.abs(n - m) > maximum) return null
    val zero = maximum + 1
    val furthest = IntArray(2 * maximum + 3)
    val trace = ArrayList<IntArray>(maximum + 1)
    for (depth in 0..maximum) {
        trace.add(furthest.copyOf())
        for (diag in -depth..depth step 2) {
            val i = zero + diag
            var x = if (diag == -depth || (diag != depth && furthest[i - 1] < furthest[i + 1]))
                furthest[i + 1] else furthest[i - 1] + 1
            var y = x - diag
            while (x < n && y < m && sameUnit(before, offset + x, after, offset + y)) { ++x; ++y }
            furthest[i] = x
            if (x < n || y < m) continue

            val runs = mutableListOf<TextMatch>()
            for (step in depth downTo 0) {
                val line = x - y
                val previous = trace[step]
                val j = zero + line
                val back = if (line == -step || (line != step && previous[j - 1] < previous[j + 1])) line + 1 else line - 1
                val px = previous[zero + back]
                val py = px - back
                val length = minOf(x - px, y - py)
                if (length > 0) runs.add(TextMatch(offset + x - length, offset + y - length, length))
                x = px
                y = py
            }
            return runs.asReversed()
        }
    }
    return null
}
