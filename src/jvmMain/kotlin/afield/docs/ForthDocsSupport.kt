package afield.docs

import afield.Box
import afield.forth.ForthInterpreter
import afield.graphics.FLine
import afield.graphics.lines
import afield.h

fun Box.renderForthDocsSnippet(source: String) {
    val forth = ForthInterpreter()
    val stdout = StringBuilder()
    var hadDisplay = false

    forth.onOutput = { text ->
        print(text)
        stdout.append(text)
    }
    forth.onDisplay = { channel, content ->
        hadDisplay = true
        display(channel, content?.toString() ?: "")
    }

    val diagnostics = forth.eval(source)
    if (diagnostics.isNotEmpty()) {
        val message = diagnostics.joinToString("\n") { diag ->
            val prefix = if (diag.line >= 0) "line ${diag.line + 1}: " else ""
            prefix + diag.message
        }
        throw IllegalStateException(message)
    }

    val forthLines = forth.stack.filterIsInstance<FLine>()
    if (forthLines.isNotEmpty()) {
        lines(forthLines, channel = "lines", w = 460.0, h = 320.0, fitToSize = true)
    } else if (!hadDisplay && stdout.isNotBlank()) {
        display("stdout", stdout.toString())
    } else if (!hadDisplay && forth.stack.isNotEmpty()) {
        display("stack", forth.stack.toList().h)
    } else if (!hadDisplay) {
        display("status", "<em>ok</em>")
    }
}
