package afield

import afield.server.TerminalInfo
import afield.server.killTerminal
import afield.server.startCodeTerminal
import afield.server.terminalExecutable
import afield.server.terminalInfo
import afield.server.writeToTerminal
import afield.stdlib.FileManager
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * A terminal started by [terminal]: the process keeps running when its panel is closed or the page
 * reloads, and the panel re-attaches with its scrollback.
 */
class FieldTerminal internal constructor(val id: String) {
    /** Types [text] into the terminal; add `"\n"` (or `"\r"`) to submit a line. False once it has exited. */
    fun write(text: String): Boolean = writeToTerminal(id, text.toByteArray(Charsets.UTF_8))

    /** Types [line] and a return. */
    fun send(line: String): Boolean = write(line + "\r")

    /** Ends the process and everything it started. */
    fun kill() = killTerminal(id)

    val running: Boolean get() = terminalInfo(id)?.running == true

    /** The exit code, or null while it runs. */
    val exitCode: Int? get() = terminalInfo(id)?.exitCode

    val info: TerminalInfo? get() = terminalInfo(id)

    override fun toString() = "FieldTerminal($id, ${if (running) "running" else "exited $exitCode"})"
}

/** The terminal each box shows on each channel, so running the box again replaces it rather than piling up. */
private val boxTerminals = ConcurrentHashMap<String, String>()

/**
 * Puts a real terminal — xterm.js on a pseudo-terminal — in this box's output area on [channel].
 *
 * With no [command] it is your login shell. Running the box again kills the terminal it showed on
 * that channel and starts a fresh one, unless [keep] is true, in which case a still-running one is
 * re-shown as it is. At most four terminals run at once, across Field.
 *
 * ```kotlin
 * val t = terminal()                         // a shell in the document's directory
 * t.send("ls -la")
 *
 * terminal("top", command = listOf("top"))   // or any program
 * ```
 *
 * @param channel output-area channel, and its disclosure title
 * @param command argv to run; the first word is looked up on PATH and the usual install places
 * @param directory working directory; defaults to the document's directory, else your home
 * @param width iframe width in CSS pixels
 * @param height iframe height in CSS pixels
 * @param keep re-show a still-running terminal instead of replacing it
 */
fun Box.terminal(
    channel: String = "terminal",
    command: List<String> = listOf(System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh", "-l"),
    directory: Path? = null,
    width: Int = 760,
    height: Int = 420,
    keep: Boolean = false,
): FieldTerminal {
    val key = "$id\u0000$channel"
    val previous = boxTerminals[key]
    val reuse = previous?.takeIf { keep && terminalInfo(it)?.running == true }
    val terminalId = reuse ?: run {
        previous?.let(::killTerminal)
        val dir = (directory ?: runCatching { FileManager.current().path(".") }.getOrNull())
            ?.takeIf { Files.isDirectory(it) } ?: Paths.get(System.getProperty("user.home"))
        val argv = listOf(terminalExecutable(command.first())) + command.drop(1)
        // Roughly the grid the panel will hold, so the first screen is not drawn for 80x24 and re-flowed.
        val info = startCodeTerminal(argv, dir, Path.of(command.first()).fileName.toString(), (width - 20) / 8, (height - 40) / 17)
            .getOrElse { throw IllegalStateException("terminal: ${it.message}", it) }
        boxTerminals[key] = info.id
        info.id
    }
    display(channel, terminalIframe(terminalId, width, height))
    displayOpen(channel)
    return FieldTerminal(terminalId)
}

/** [terminal] running one command line through your shell, e.g. `terminal("build", "./gradlew build")`. */
fun Box.terminal(channel: String, commandLine: String, directory: Path? = null, width: Int = 760, height: Int = 420, keep: Boolean = false): FieldTerminal =
    terminal(channel, listOf(System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh", "-lc", commandLine), directory, width, height, keep)

/** Pixel sizes for the reason given on [keyframeBrowser]: a percentage would leave the panel at its closed width. */
internal fun terminalIframe(id: String, width: Int, height: Int): String {
    val src = "/terminal.html?id=" + URLEncoder.encode(id, StandardCharsets.UTF_8)
    return """<iframe class="field-terminal" src="$src" data-afield-autosize="off" """ +
        """style="display:block;width:${width}px;height:${height}px;border:0;background:#000"></iframe>"""
}
