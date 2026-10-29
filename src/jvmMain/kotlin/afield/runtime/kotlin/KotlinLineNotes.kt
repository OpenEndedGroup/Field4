package afield.runtime.kotlin

import afield.document.graph.Box
import afield.ui.output.LastRunNotes

/**
 * Line notes for a Kotlin box: each top-level `val` or `var` a run declared, and the value it held
 * when the run finished.
 *
 * The REPL hands back only a snippet's final value, but it also publishes the snippet's properties
 * after every evaluation (`box.ki.context.vars`, the same map `fromContext()` searches), so a
 * declaration's value can be read back by name once the run is over. A statement that declares
 * nothing has no note: its value was never kept.
 */

/** Reads a finished run's declarations out of the box's variable table and records them as notes. */
object KotlinLineNotes {
    /** A top-level property declaration, modifiers and annotations allowed, at the start of a line. */
    private val declaration = Regex("""^(?:(?:@[\w.]+(?:\([^)]*\))?|private|internal|public|lateinit|const)\s+)*(?:val|var)\s+([A-Za-z_]\w*)\b""")

    /** Record what the declarations of [code] (box [property], after box line [lineOffset]) came to. */
    fun record(box: Box, property: String, code: String, lineOffset: Int) {
        val vars = runCatching { box.ki.context.vars }.getOrNull() ?: return
        val produced = code.split("\n").mapIndexedNotNull { index, line ->
            val name = declaration.find(line)?.groupValues?.get(1) ?: return@mapIndexedNotNull null
            val info = vars[name] ?: return@mapIndexedNotNull null
            val shown = render(info.value)
            LastRunNotes.Produced(index + 1, index + 1, "$name = $shown",
                value = listOf(afield.ui.output.LineNotes.plain("$name = "), afield.ui.output.LineNotes.strong(shown)))
        }
        LastRunNotes.record(box, property, code, lineOffset, produced)
    }

    /**
     * [value] on one short line. Collections, maps, arrays and long strings are cut before they are
     * turned into text, so a note never builds the whole of a large value to show its start.
     */
    internal fun render(value: Any?, depth: Int = 0): String {
        fun items(count: Int, first: Sequence<Any?>, open: String, close: String) =
            if (depth > 0) "$count items" else
                first.take(4).joinToString(", ", open, (if (count > 4) ", … ($count)" else "") + close) { render(it, depth + 1) }
        return runCatching {
            when (value) {
                null -> "null"
                is CharSequence -> "\"" + LastRunNotes.oneLine(value.subSequence(0, minOf(value.length, 80)).toString(), 80) + "\""
                is Collection<*> -> items(value.size, value.asSequence(), "[", "]")
                is Map<*, *> -> items(value.size, value.entries.asSequence().map { "${render(it.key, depth + 1)}=${render(it.value, depth + 1)}" }, "{", "}")
                is Array<*> -> items(value.size, value.asSequence(), "[", "]")
                is IntArray -> items(value.size, value.asSequence(), "[", "]")
                is FloatArray -> items(value.size, value.asSequence(), "[", "]")
                is DoubleArray -> items(value.size, value.asSequence(), "[", "]")
                is LongArray -> items(value.size, value.asSequence(), "[", "]")
                is ByteArray -> items(value.size, value.asSequence(), "[", "]")
                else -> LastRunNotes.oneLine(value.toString(), 80)
            }
        }.getOrElse { value?.javaClass?.simpleName ?: "null" }
    }
}
