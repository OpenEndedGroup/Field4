@file:afield.CompletionCategory("Synthesis")
package afield.pd

import afield.graphics.FLine
import afield.stdlib.color
import afield.stdlib.layoutText
import afield.stdlib.thickness
import afield.stdlib.toFLines
import afield.stdlib.typeface
import afield.stdlib.vec
import java.awt.Font

/**
 * A Pd patch read as a graph: its objects, where they sit, and what is wired to what.
 *
 * Pd's file format is one statement per `;`, and an object's identity is its *position* in the
 * sequence of `#X obj`, `#X msg`, `#X floatatom`, `#X symbolatom`, `#X listbox` and `#X text`
 * statements of its canvas — `#X connect 0 0 1 0` means "outlet 0 of the first of those to inlet 0
 * of the second". A subpatch (`#N canvas` … `#X restore`) counts as one object of its parent, and its
 * own contents are skipped here.
 *
 * Nothing here runs Pd: it is the text alone, which is what lets a line note or a drawing work while
 * the patch is being typed and before libpd is even built.
 */
class PdPatchGraph private constructor(
    /** The top-level objects, in Pd's index order. */
    val objects: List<Node>,
    /** The top-level connections. */
    val connections: List<Connection>,
) {
    /**
     * One object. [kind] is `obj`, `msg`, `floatatom`, `symbolatom`, `listbox`, `text` or `pd` for a
     * subpatch; [text] is what Pd shows in its box (`osc~ 440`); [line] is 0-based in the source.
     */
    data class Node(val index: Int, val kind: String, val x: Int, val y: Int, val text: String, val line: Int) {
        /** The class name for an `obj` — `osc~` for `[osc~ 440]` — else [kind]. */
        val className: String get() = if (kind == "obj") text.substringBefore(' ') else kind

        /** How Pd would show it: `[osc~ 440]`, `[set 3(` for a message. */
        val label: String get() = when (kind) {
            "msg" -> "[$text("
            "floatatom", "symbolatom", "listbox" -> "[$kind]"
            "text" -> "\"$text\""
            else -> "[$text]"
        }

        /** The name a `[s name]`/`[send name]` sends to, or null. */
        val sendsTo: String? get() = namedArgument("s", "send")

        /** The name a `[r name]`/`[receive name]` listens on, or null. */
        val receivesFrom: String? get() = namedArgument("r", "receive", "r~", "receive~").takeIf { !className.endsWith("~") }

        private fun namedArgument(vararg classes: String): String? {
            if (kind != "obj" || className !in classes) return null
            return text.split(Regex("\\s+")).getOrNull(1)
        }
    }

    /** `#X connect from outlet to inlet`, with the 0-based source [line] it was written on. */
    data class Connection(val from: Int, val outlet: Int, val to: Int, val inlet: Int, val line: Int)

    /** The object written on source [line], or null. */
    fun objectOnLine(line: Int): Node? = objects.firstOrNull { it.line == line }

    /** The connection written on source [line], or null. */
    fun connectionOnLine(line: Int): Connection? = connections.firstOrNull { it.line == line }

    /** Connections arriving at object [index]. */
    fun into(index: Int): List<Connection> = connections.filter { it.to == index }

    /** Connections leaving object [index]. */
    fun outOf(index: Int): List<Connection> = connections.filter { it.from == index }

    /** Every name sent to by an `[s]` in the patch. */
    val sendNames: Set<String> get() = objects.mapNotNull { it.sendsTo }.toSet()

    /** Every name listened on by an `[r]` in the patch. */
    val receiveNames: Set<String> get() = objects.mapNotNull { it.receivesFrom }.toSet()

    /** A sentence about source [line], for a line note; null for a line that says nothing. */
    fun describeLine(line: Int, lastValue: (String) -> PdInstance.Message? = { null }): String? {
        objectOnLine(line)?.let { node ->
            val ins = into(node.index).joinToString(", ") { c ->
                "${objects.getOrNull(c.from)?.label ?: "#${c.from}"} (outlet ${c.outlet} → inlet ${c.inlet})"
            }
            val outs = outOf(node.index).joinToString(", ") { c ->
                "${objects.getOrNull(c.to)?.label ?: "#${c.to}"} (outlet ${c.outlet} → inlet ${c.inlet})"
            }
            return buildString {
                append("${node.label} is object ${node.index}")
                if (ins.isNotEmpty()) append("; fed by $ins")
                if (outs.isNotEmpty()) append("; feeds $outs")
                if (ins.isEmpty() && outs.isEmpty() && node.kind != "text") append(", connected to nothing")
                val name = node.sendsTo ?: node.receivesFrom
                if (name != null) {
                    val seen = lastValue(name)
                    append(if (seen != null) ". Last on '$name': ${seen.args.joinToString(" ").ifEmpty { seen.selector }}"
                    else ". Nothing seen on '$name' yet")
                }
                append('.')
            }
        }
        connectionOnLine(line)?.let { c ->
            val a = objects.getOrNull(c.from)
            val b = objects.getOrNull(c.to)
            if (a == null || b == null) return "connects object ${c.from} to ${c.to}, but the patch has only ${objects.size} objects."
            return "${a.label} outlet ${c.outlet} → ${b.label} inlet ${c.inlet}."
        }
        return null
    }

    /**
     * The patch drawn as Pd draws it — a box per object at its own coordinates, a line per
     * connection from the bottom edge of its source to the top edge of its target — as [FLine]s for
     * `Box.lines(...)`. Pd's y axis grows downward, as Field's 2D outputs do, so coordinates carry
     * over unchanged.
     *
     * @param labels draw each object's text in it; off gives an anonymous diagram (and needs no font)
     */
    fun toFLines(labels: Boolean = true, charWidth: Double = 7.0, boxHeight: Double = 18.0): List<FLine> {
        val widths = objects.associate { it.index to (it.label.length * charWidth + 8.0).coerceAtLeast(24.0) }
        val out = ArrayList<FLine>()
        val ink = vec(0.82, 0.82, 0.82, 1.0)
        val wire = vec(0.45, 0.62, 0.95, 1.0)
        for (c in connections) {
            val a = objects.getOrNull(c.from) ?: continue
            val b = objects.getOrNull(c.to) ?: continue
            val ax = a.x + 4.0 + c.outlet * 12.0
            val bx = b.x + 4.0 + c.inlet * 12.0
            out += FLine().moveTo(ax, a.y + boxHeight).lineTo(bx, b.y.toDouble()).also {
                it.color = wire
                it.thickness = 1.5
            }
        }
        for (n in objects) {
            if (n.kind == "text") continue
            val w = widths.getValue(n.index)
            out += FLine().moveTo(n.x, n.y).lineTo(n.x + w, n.y).lineTo(n.x + w, n.y + boxHeight)
                .lineTo(n.x, n.y + boxHeight).lineTo(n.x, n.y).also {
                    it.color = ink
                    it.thickness = if (n.kind == "msg") 1.0 else 1.25
                }
        }
        if (labels) {
            runCatching {
                val face = typeface(Font.MONOSPACED, 11.0)
                for (n in objects) {
                    val text = if (n.kind == "text") n.text else n.text.ifEmpty { n.kind }
                    val layout = layoutText(text, face)
                    out += layout.toFLines(color = ink, thickness = 0.0, filled = true, stroked = false)
                        .map { line -> line.transform { p -> vec(p.x + n.x + 4.0, p.y + n.y + 13.0, p.z) } }
                }
            }
        }
        return out
    }

    companion object {
        private val COUNTED = setOf("obj", "msg", "floatatom", "symbolatom", "listbox", "text")

        /**
         * [source] with every statement that is not a patch statement blanked, line for line.
         *
         * A Pd file is a sequence of messages, and a statement like `freq 440;` in one would be
         * *sent* while the file loads. A Pd box keeps its messages next to its patch — to be run one
         * line at a time — and `//` comments too, so both are removed before Pd sees the text. Line
         * numbers are kept, so a problem Pd reports still lands on the box's own line.
         */
        fun statementsOnly(source: String): String {
            val out = ArrayList<String>()
            var inStatement = false
            for (raw in source.lines()) {
                val t = raw.trim()
                if (!inStatement && !t.startsWith("#")) {
                    // A message line or a comment: one line each, never the start of a statement.
                    out += ""
                    continue
                }
                out += raw
                if (t.isNotEmpty()) inStatement = !(t.endsWith(";") && !t.endsWith("\\;"))
            }
            return out.joinToString("\n")
        }

        /** Read [source], the text of a `.pd` file or a patch written inline. Never throws. */
        fun parse(source: String): PdPatchGraph {
            val objects = ArrayList<Node>()
            val connections = ArrayList<Connection>()
            var depth = 0
            var sawRootCanvas = false
            for ((lineNo, raw) in source.lines().withIndex()) {
                val t = raw.trim().removeSuffix(";").trim()
                if (t.isEmpty()) continue
                val f = t.split(Regex("\\s+"))
                when {
                    f[0] == "#N" && f.getOrNull(1) == "canvas" -> {
                        if (!sawRootCanvas && depth == 0 && objects.isEmpty()) sawRootCanvas = true else depth++
                    }
                    f[0] == "#X" && f.getOrNull(1) == "restore" -> {
                        if (depth > 0) {
                            depth--
                            if (depth == 0) {
                                val name = f.drop(4).joinToString(" ")
                                objects += Node(objects.size, "pd", f.int(2), f.int(3), name, lineNo)
                            }
                        }
                    }
                    depth > 0 -> Unit
                    f[0] == "#X" && f.getOrNull(1) == "connect" && f.size >= 6 ->
                        connections += Connection(f.int(2), f.int(3), f.int(4), f.int(5), lineNo)
                    f[0] == "#X" && f.getOrNull(1) in COUNTED -> {
                        val kind = f[1]
                        val body = f.drop(4)
                        val text = when (kind) {
                            // An atom box's own fields are width, limits and names, not text.
                            "floatatom", "symbolatom", "listbox" -> ""
                            else -> body.joinToString(" ").replace(" \\,", ",").replace("\\;", ";").replace("\\$", "$")
                        }
                        objects += Node(objects.size, kind, f.int(2), f.int(3), text, lineNo)
                    }
                }
            }
            return PdPatchGraph(objects, connections)
        }

        private fun List<String>.int(i: Int): Int = getOrNull(i)?.toDoubleOrNull()?.toInt() ?: 0
    }
}
