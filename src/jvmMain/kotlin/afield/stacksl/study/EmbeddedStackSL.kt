package afield.stacksl.study

import afield.Box
import afield.Completion
import afield.RunsAt
import afield.plugins
import afield.stacksl.StackSLVocabulary
import afield.stdlib.BoxStudies
import afield.stdlib.LineNotes
import afield.stdlib.stackAutoShaderSources
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * StackSL written inside a Kotlin box's `code`: where it is, and the editor's help for it there — the
 * StackSL vocabulary when completion opens inside it, and each line's stage as a line note.
 *
 * A string is StackSL when it begins with a `#stacksl` line (the convention the editor already
 * highlights), or when it is assigned to one of the box's `stack.auto` slots — `module.program = """…"""`
 * or `module.program = "uv 4 * fbm"`, where `program` is a `{{ stack.auto.program }}` in one of the box's
 * shaders. Both forms are found in the text as it is being typed, so help does not wait for a run.
 *
 * A line note inside a slot's program is drawn alone, as the numbers card's **Render this function
 * alone** is: on the StackSL study's frame, with its input, not the one the host shader passes, and the
 * caption says so. A program that samples a texture or reads the host's push constants has no picture.
 */
object EmbeddedStackSL {
    /** A StackSL string in Kotlin: its text from [bodyStart] (an offset of the code) and the [slot] it is assigned to, if any. */
    data class Region(val bodyStart: Int, val text: String, val slot: String?) {
        val bodyEnd get() = bodyStart + text.length
    }

    private val header = Regex("""^(?:[ \t]*(?:\r?\n|$))*[ \t]*#stacksl\b[^\n]*(?:\r?\n|$)""", RegexOption.IGNORE_CASE)
    private val slotTag = Regex("""stack\.auto\.([A-Za-z_][A-Za-z0-9_]*)""")
    private val assignedTo = Regex("""\.\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*$""")
    private val plainString = Regex(""""((?:[^"\\\n]|\\.)*)"""")

    /** The names of [box]'s `stack.auto` slots: those its shaders declare and those it has bound. */
    fun slots(box: Box): Set<String> =
        listOf("vertex", "fragment", "compute", "task", "mesh").flatMap { p ->
            (box.getPropertyRaw(p, null) as? String)?.let { t -> slotTag.findAll(t).map { it.groupValues[1] }.toList() }.orEmpty()
        }.toSet() + box.stackAutoShaderSources.keys

    /** Every StackSL string in [code], given the box's [slots]. */
    fun regions(code: String, slots: Set<String>): List<Region> {
        val out = mutableListOf<Region>()
        val masked = StringBuilder(code)
        var search = 0
        while (search < code.length) {
            val open = code.indexOf("\"\"\"", search).takeIf { it >= 0 } ?: break
            val start = open + 3
            val close = code.indexOf("\"\"\"", start).takeIf { it >= 0 } ?: break
            val content = code.substring(start, close)
            val slot = assignedTo.find(code.substring(code.lastIndexOf('\n', open - 1) + 1, open))?.groupValues?.get(1)?.takeIf { it in slots }
            val head = header.find(content)
            when {
                head != null -> out += Region(start + head.value.length, content.substring(head.value.length), slot)
                slot != null -> out += Region(start, content, slot)
            }
            for (i in open until close + 3) masked.setCharAt(i, ' ')
            search = close + 3
        }
        // One-line strings, outside the raw ones: only a slot's.
        plainString.findAll(masked).forEach { m ->
            val lineStart = masked.lastIndexOf("\n", m.range.first - 1) + 1
            val slot = assignedTo.find(masked.substring(lineStart, m.range.first))?.groupValues?.get(1)?.takeIf { it in slots } ?: return@forEach
            if (!m.groupValues[1].contains('\\')) out += Region(m.range.first + 1, m.groupValues[1], slot)
        }
        return out.sortedBy { it.bodyStart }
    }

    /** The StackSL string of [box]'s [code] that offset [position] is in, or null. */
    fun regionAt(box: Box, code: String, position: Int): Region? = regions(code, slots(box)).firstOrNull { position in it.bodyStart..it.bodyEnd }

    /** Completion at [position] of [code], when it is inside StackSL: the vocabulary, with bounds in the code's offsets. */
    fun complete(box: Box, code: String, position: Int): Completion? {
        val region = regionAt(box, code, position) ?: return null
        val inner = StackSLVocabulary.complete(region.text, position - region.bodyStart)
        return inner.copy(boundsStart = inner.boundsStart + region.bodyStart, boundsEnd = inner.boundsEnd + region.bodyStart)
    }

    private val studies = ConcurrentHashMap<Box, StackSLStudy>()

    /** [line] (1-based) of [code]'s StackSL, as a note: the stage it ends, drawn alone; null outside StackSL. */
    fun lineNote(box: Box, code: String, line: Int): LineNotes.Note? {
        val lineStart = code.lines().take(line - 1).sumOf { it.length + 1 }
        val region = regionAt(box, code, lineStart) ?: regionAt(box, code, lineStart + (code.lines().getOrNull(line - 1)?.length ?: 0)) ?: return null
        val before = code.substring(0, region.bodyStart)
        val relative = line - (before.count { it == '\n' } + 1) + 1
        // Only the study's line-note machinery is needed; its folder is a scratch one, outside the document.
        val study = studies.getOrPut(box) {
            StackSLStudy(File(System.getProperty("java.io.tmpdir"), "field-embedded-stacksl/${box.id}"), { "" }, { _, _ -> false })
                .also { s -> s.rasterizer = StackSLGpuRasterizer({ BoxStudies.rendererAbove(box) }) }
        }
        val note = study.lineNote(region.text, relative) ?: return null
        val where = region.slot?.let { "stack.auto.$it, " }.orEmpty()
        return LineNotes.Note("$where${note.caption}, drawn alone — not with your shader's input", note.png)
    }
}

/** StackSL inside a Kotlin box's code gets its line notes from [EmbeddedStackSL]. */
@RunsAt(plugins)
fun installEmbeddedStackSLLineNotes() {
    LineNotes.register("code") { box, source, line -> EmbeddedStackSL.lineNote(box, source, line) }
}
