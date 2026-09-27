package afield.rectsl.study

import afield.Box
import afield.rectsl.SampledRect
import afield.rectsl.SceneProgram
import afield.stdlib.BoxStudies
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The editor and a scene's window, each pointing at the other.
 *
 *  - **The editor's line, in the window.** While a scene box is selected with its `rectSL` in the editor,
 *    the rectangles the cursor's line makes are outlined in the window: those of the `rect` or the `array`
 *    on it, and of the statement that starts on it (a `group`'s line outlines every part), or — on a
 *    comment — of the statement below. Put the cursor on a line to see what it draws.
 *  - **The window's rectangle, in the editor.** Clicking a rectangle in the window selects its scene box and
 *    puts the editor on the line that made it — and so outlines it.
 *
 * Both come from the same record the study's Trace uses: which line made each rectangle
 * ([afield.rectsl.compileRectSLWithSource]).
 */
object RectSLWindowLinks {
    /** The editor's cursor line in [box]'s `rectSL`, while the box is selected with that property showing; else null. */
    fun cursorLine(box: Box): Int? = BoxStudies.editorLine(box, RectSLPrograms.PROPERTY)

    /**
     * The rectangles [line] of [source] makes, by id: those whose own word is on it and those of the
     * statement that starts on it (a `group`'s line outlines every part), with their copies — so
     * `"dot" rect …` outlines the dots an `array` below it makes too — or, for a comment or a blank line,
     * those of the statement just below.
     */
    fun idsForLine(drawable: RectSLDrawable, line: Int): Set<String> {
        val origins = drawable.origins
        val groups = drawable.scene.nodes.filterIsInstance<afield.rectsl.GroupNode>().associateBy { it.id }
        // A group stands for every rectangle in it, however deep.
        fun rects(id: String, into: MutableSet<String>) {
            val group = groups[id]
            if (group == null) into += id else group.children.forEach { rects(it, into) }
        }
        fun on(l: Int): Set<String> {
            val direct = origins.filterValues { it.line == l || it.statementLine == l }.keys
            val bases = direct.mapTo(HashSet(), ::baseId)
            val out = HashSet<String>()
            origins.keys.filter { it in direct || baseId(it) in bases }.forEach { rects(it, out) }
            return out
        }
        on(line).takeIf { it.isNotEmpty() }?.let { return it }
        val lines = drawable.source.lines()
        var next = line
        while (next <= lines.size && lines[next - 1].trim().let { it.isEmpty() || it.startsWith("#") }) next++
        return if (next != line && next <= lines.size) on(next) else emptySet()
    }

    /** The id [id] was copied from, through any number of copies: `dot-copy-3` and `dot-copy-copy` are `dot`'s. */
    fun baseId(id: String): String = id.replace(COPY, "")

    private val COPY = Regex("(-copy(-\\d+)?)+$")

    /** The topmost visible rectangle of [scene] at [time] under wall point ([x], [y]), or null. */
    fun hit(scene: SceneProgram, time: Double, x: Double, y: Double): SampledRect? =
        scene.sample(time).rects.lastOrNull { r ->
            if (r.alpha <= 0.02) return@lastOrNull false
            // Into the rectangle's own frame: turned back by its rotation, about its centre.
            val dx = x - r.center.x; val dy = y - r.center.y
            val c = cos(-r.rotation); val s = sin(-r.rotation)
            abs(c * dx - s * dy) <= 0.5 * r.size.x * abs(r.scale.x) && abs(s * dx + c * dy) <= 0.5 * r.size.y * abs(r.scale.y)
        }

    /** Select [box] and put its editor on [line] of `rectSL`, in every page open on its document. */
    fun reveal(box: Box, line: Int) = BoxStudies.reveal(box, RectSLPrograms.PROPERTY, line)
}
