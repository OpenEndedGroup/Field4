@file:afield.CompletionCategory("Synthesis")
package afield.pd

import afield.Box
import afield.graphics.FLine
import afield.graphics.lines
import afield.stdlib.Typefaces
import kotlin.concurrent.thread
import afield.stdlib.color
import afield.stdlib.filled
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

        /**
         * How Pd would show it: `[osc~ 440]`, `[set 3(` for a message. A GUI object (`hsl`, `tgl`, …)
         * is drawn in Pd as a slider or a box, not as its creation arguments, so it is labelled with
         * its class and its receive name: `[hsl pitch]`.
         */
        val label: String get() = when {
            kind == "obj" && className in GUI_CLASSES -> {
                val receive = text.split(Regex("\\s+")).getOrNull(GUI_RECEIVE_FIELD[className] ?: -1)
                    ?.takeIf { it != "empty" }
                if (receive != null) "[$className $receive]" else "[$className]"
            }
            else -> when (kind) {
            "msg" -> "[$text("
            "floatatom", "symbolatom", "listbox" -> "[$kind]"
            "text" -> "\"$text\""
            else -> "[$text]"
            }
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
     * How many inlets and outlets object [index] shows: the larger of what Pd's class is known to
     * have ([KNOWN_PORTS], the commonest vanilla classes) and one more than the highest one wired in
     * this patch. The text alone cannot say more — an abstraction's ports are in another file — so an
     * unknown object with nothing wired shows one of each.
     */
    fun ports(index: Int): Pair<Int, Int> {
        val node = objects.getOrNull(index) ?: return 1 to 1
        val known = KNOWN_PORTS[node.className] ?: when (node.kind) {
            "msg", "floatatom", "symbolatom", "listbox" -> 1 to 1
            "text" -> 0 to 0
            else -> 1 to 1
        }
        val wiredIn = (into(index).maxOfOrNull { it.inlet } ?: -1) + 1
        val wiredOut = (outOf(index).maxOfOrNull { it.outlet } ?: -1) + 1
        return maxOf(known.first, wiredIn) to maxOf(known.second, wiredOut)
    }

    /**
     * The patch drawn as Pd draws it — a box per object at its own coordinates, its inlets as tabs
     * along the top edge and its outlets along the bottom, spread across the box's width with the
     * first at the left edge and the last at the right, and a wire per connection from the outlet it
     * leaves to the inlet it enters — as [FLine]s for `Box.lines(...)`. Pd's y axis grows downward,
     * as Field's 2D outputs do, so coordinates carry over unchanged.
     *
     * @param labels draw each object's text in it; off gives an anonymous diagram (and needs no font)
     */
    fun toFLines(labels: Boolean = true, charWidth: Double = 7.0, boxHeight: Double = 18.0): List<FLine> {
        val widths = objects.associate { it.index to (it.label.length * charWidth + 8.0).coerceAtLeast(24.0) }
        val out = ArrayList<FLine>()
        val ink = vec(0.82, 0.82, 0.82, 1.0)
        val wire = vec(0.45, 0.62, 0.95, 1.0)
        val tab = 7.0
        // The left edge of port k of n on a box w wide, as Pd spaces them: first flush left, last flush right.
        fun portX(n: Node, k: Int, count: Int): Double {
            val w = widths.getValue(n.index)
            return if (count <= 1) n.x.toDouble() else n.x + (w - tab) * k / (count - 1)
        }
        for (c in connections) {
            val a = objects.getOrNull(c.from) ?: continue
            val b = objects.getOrNull(c.to) ?: continue
            val ax = portX(a, c.outlet, ports(a.index).second) + tab / 2
            val bx = portX(b, c.inlet, ports(b.index).first) + tab / 2
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
            val (ins, outs) = ports(n.index)
            for (k in 0 until ins) out += portTab(portX(n, k, ins), n.y.toDouble(), tab, ink)
            for (k in 0 until outs) out += portTab(portX(n, k, outs), n.y + boxHeight - 2.5, tab, ink)
        }
        if (labels) {
            runCatching {
                val face = typeface(Font.MONOSPACED, 11.0)
                for (n in objects) {
                    val text = when {
                        n.kind == "text" -> n.text
                        n.kind == "obj" && n.className in GUI_CLASSES -> n.label.removePrefix("[").removeSuffix("]")
                        else -> n.text.ifEmpty { n.kind }
                    }
                    val layout = layoutText(text, face)
                    out += layout.toFLines(color = ink, thickness = 0.0, filled = true, stroked = false)
                        .map { line -> line.transform { p -> vec(p.x + n.x + 4.0, p.y + n.y + 13.5, p.z) } }
                }
            }
        }
        return out
    }

    private fun portTab(x: Double, y: Double, w: Double, colour: afield.stdlib.linalg.Vector4f): FLine =
        FLine().moveTo(x, y).lineTo(x + w, y).lineTo(x + w, y + 2.5).lineTo(x, y + 2.5).lineTo(x, y).also {
            it.color = colour
            it.filled = true
            it.thickness = 0.5
        }

    companion object {
        /** Pd's built-in GUI classes, which [Node.label] names by receive rather than by arguments. */
        val GUI_CLASSES: Set<String> = setOf("hsl", "vsl", "tgl", "nbx", "bng", "hradio", "vradio", "vu", "cnv")

        /** Where the receive name sits among a GUI object's words (class first), as Pd saves it. */
        private val GUI_RECEIVE_FIELD = mapOf("hsl" to 8, "vsl" to 8, "tgl" to 4, "nbx" to 8, "bng" to 6,
            "hradio" to 6, "vradio" to 6, "cnv" to 5)

        /**
         * Inlets and outlets of the vanilla classes a patch most often uses, as Pd creates them with
         * their usual arguments. Not exhaustive; [ports] also counts what the patch wires.
         */
        val KNOWN_PORTS: Map<String, Pair<Int, Int>> = buildMap {
            for (op in listOf("+", "-", "*", "/", "max", "min", "mod", "div", "pow", "==", "!=", ">", "<", ">=", "<=",
                "&&", "||", "+~", "-~", "*~", "/~", "max~", "min~", "pow~")) put(op, 2 to 1)
            put("osc~", 2 to 1); put("phasor~", 2 to 1); put("cos~", 1 to 1); put("noise~", 1 to 1)
            put("lop~", 2 to 1); put("hip~", 2 to 1); put("bp~", 3 to 1); put("vcf~", 3 to 2)
            put("line~", 2 to 1); put("vline~", 3 to 1); put("line", 3 to 1); put("sig~", 1 to 1)
            put("dac~", 2 to 0); put("adc~", 1 to 2); put("tabwrite~", 1 to 0); put("tabplay~", 1 to 2)
            put("tabosc4~", 2 to 1); put("tabread4~", 2 to 1); put("env~", 1 to 1); put("snapshot~", 1 to 1)
            put("metro", 2 to 1); put("delay", 2 to 1); put("f", 2 to 1); put("float", 2 to 1)
            put("int", 2 to 1); put("i", 2 to 1); put("loadbang", 1 to 1); put("print", 1 to 0)
            put("s", 1 to 0); put("send", 1 to 0); put("r", 0 to 1); put("receive", 0 to 1)
            put("mtof", 1 to 1); put("ftom", 1 to 1); put("random", 2 to 1); put("moses", 2 to 2)
            put("spigot", 2 to 1); put("table", 0 to 0); put("notein", 0 to 3); put("noteout", 3 to 0)
            put("ctlin", 0 to 3); put("ctlout", 3 to 0); put("clip~", 3 to 1); put("wrap~", 1 to 1)
            for (gui in listOf("hsl", "vsl", "tgl", "nbx", "bng", "hradio", "vradio")) put(gui, 1 to 1)
            put("cnv", 0 to 0)
        }

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

/**
 * Draw [graph] in this box's [channel] output, labelled, without stalling the thread that asks.
 *
 * The first glyph a Field process lays out loads the platform font manager, and on macOS that is
 * dispatched to the process's first thread and waited for. A box runs on the main run loop, which is
 * that thread, so a first labelled drawing from a box would wait out [Typefaces]' four-second
 * timeout and then come out with no labels at all. So when fonts are not loaded yet, the patch is
 * drawn unlabelled at once and redrawn with labels from a helper thread as soon as they are.
 */
fun Box.showPdPatch(graph: PdPatchGraph, channel: String = "patch") {
    if (graph.objects.isEmpty()) return
    if (Typefaces.isWarm) {
        runCatching { lines(graph.toFLines(), channel = channel) }
        return
    }
    runCatching { lines(graph.toFLines(labels = false), channel = channel) }
    thread(isDaemon = true, name = "pd-patch-labels") {
        // A first attempt can time out while the font manager's dispatch is still queued behind the
        // run loop; it completes a moment later, so try again for a while before giving up on labels.
        repeat(8) {
            if (runCatching { Typefaces.warm() }.isSuccess) {
                runCatching { lines(graph.toFLines(), channel = channel) }
                return@thread
            }
            Thread.sleep(500)
        }
    }
}
