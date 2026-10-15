package afield.python

/**
 * Where a Python box's error happened, found from the exception itself rather than from JEP's
 * message.
 *
 * A Kotlin box puts the red marker on the line that failed. A Python box used to guess: the JEP
 * message carries no usable line (its Java stack trace reports line 1 for everything run through
 * `exec`), and the syntax-error pattern expected a `<string>` filename that cells stopped using
 * when they were compiled as `<repl:{box id}>`. So every runtime error, and most syntax errors,
 * were marked on the fragment's first line.
 *
 * [SOURCE] is spliced into the interpreter's startup text and kept free of Kotlin templates, so
 * `PythonErrorsTest` runs exactly this text under a plain `python3`. It defines
 * `_field_error_location(exc, filename)`, which returns `[line, type name, message]`:
 *
 * - a `SyntaxError` raised while compiling the cell reports its own `lineno`;
 * - any other exception reports the innermost traceback frame whose code was compiled from the
 *   cell (`filename`) — so an error inside a function the cell defined is marked on the line in
 *   that function, and an error inside a library is marked on the cell line that called into it;
 * - `line` is `-1` when no frame belongs to the cell.
 *
 * Lines are 1-based within the fragment that ran. [boxLine] turns one into the box line the
 * output area expects, by the same rule `print` uses (`thisLineOffset + f_lineno`).
 */
object PythonErrors {

    /** The fragment line [pythonLine] as a box line, or `-1` (the fragment's end) when unknown. */
    fun boxLine(pythonLine: Int, fragmentStart: Int, droppedLeadingBlank: Int = 0): Int =
        if (pythonLine <= 0) -1 else fragmentStart + pythonLine + droppedLeadingBlank

    /** The text shown beside the marker: `NameError: name 'q' is not defined`. */
    fun describe(type: String, message: String): String =
        if (message.isBlank()) type else "$type: $message"

    val SOURCE: String = """
# ---------- error locations: which line of the cell raised ----------
def _field_error_location(exc, filename):
    try:
        if isinstance(exc, SyntaxError) and getattr(exc, "filename", None) == filename and exc.lineno:
            return [int(exc.lineno), type(exc).__name__, str(exc.msg or "")]
        line = -1
        tb = exc.__traceback__
        while tb is not None:
            if tb.tb_frame.f_code.co_filename == filename:
                line = int(tb.tb_lineno)
            tb = tb.tb_next
        return [line, type(exc).__name__, str(exc)]
    except BaseException:
        return [-1, type(exc).__name__, ""]
"""
}
