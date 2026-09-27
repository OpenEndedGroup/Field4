package afield.rectsl.study

import afield.Box
import afield.Completion
import afield.RunsAt
import afield.plugins
import afield.rectsl.RectSLVocabulary
import afield.stacksl.study.EmbeddedStackSL
import afield.stdlib.LineNotes
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * RectSL written inside a Kotlin box's `code`, as a string whose first line is `#rectsl` (the convention
 * the editor already highlights): the RectSL vocabulary when completion opens inside it, and each line's
 * scene-so-far as a line note — the help a `rectSL` property has, where the scene sits in Kotlin. The
 * StackSL counterpart, [EmbeddedStackSL], also finds strings assigned to `stack.auto` slots; RectSL has
 * no such slot, so only the header marks it.
 */
object EmbeddedRectSL {
    private val header = Regex("""^(?:[ \t]*(?:\r?\n|$))*[ \t]*#rectsl\b[^\n]*(?:\r?\n|$)""", RegexOption.IGNORE_CASE)

    /** Every `#rectsl` string in [code]: its body (after the header line) and where it starts. */
    fun regions(code: String): List<EmbeddedStackSL.Region> {
        val out = mutableListOf<EmbeddedStackSL.Region>()
        var search = 0
        while (search < code.length) {
            val open = code.indexOf("\"\"\"", search).takeIf { it >= 0 } ?: break
            val start = open + 3
            val close = code.indexOf("\"\"\"", start).takeIf { it >= 0 } ?: break
            val content = code.substring(start, close)
            header.find(content)?.let { out += EmbeddedStackSL.Region(start + it.value.length, content.substring(it.value.length), null) }
            search = close + 3
        }
        return out
    }

    private fun regionAt(code: String, position: Int) = regions(code).firstOrNull { position in it.bodyStart..it.bodyEnd }

    /** Completion at [position] of [code], when it is inside a `#rectsl` string: the vocabulary, with bounds in the code's offsets. */
    fun complete(code: String, position: Int): Completion? {
        val region = regionAt(code, position) ?: return null
        val inner = RectSLVocabulary.complete(region.text, position - region.bodyStart)
        return inner.copy(boundsStart = inner.boundsStart + region.bodyStart, boundsEnd = inner.boundsEnd + region.bodyStart)
    }

    private val studies = ConcurrentHashMap<Box, RectSLStudy>()

    /** [line] (1-based) of [code]'s `#rectsl` scene, as a note: the scene built up to it; null outside one. */
    fun lineNote(box: Box, code: String, line: Int): LineNotes.Note? {
        val lineStart = code.lines().take(line - 1).sumOf { it.length + 1 }
        val region = regionAt(code, lineStart) ?: regionAt(code, lineStart + (code.lines().getOrNull(line - 1)?.length ?: 0)) ?: return null
        val relative = line - (code.substring(0, region.bodyStart).count { it == '\n' } + 1) + 1
        val study = studies.getOrPut(box) {
            RectSLStudy(File(System.getProperty("java.io.tmpdir"), "field-embedded-rectsl/${box.id}"), { "" }, { _, _ -> false })
        }
        return study.lineNote(region.text, relative, 0.0)
    }
}

/**
 * Line notes for a Kotlin box's `code`: a `#rectsl` scene's, else StackSL's ([EmbeddedStackSL]). One
 * provider answers per property, so this one asks both; it is registered after StackSL's.
 */
@RunsAt(plugins)
fun installEmbeddedRectSLLineNotes() {
    LineNotes.register("code") { box, source, line -> EmbeddedRectSL.lineNote(box, source, line) ?: EmbeddedStackSL.lineNote(box, source, line) }
}
