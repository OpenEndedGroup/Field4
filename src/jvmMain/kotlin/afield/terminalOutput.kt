package afield

import afield.server.TerminalInfo
import afield.server.killTerminal
import afield.server.startCodeTerminal
import afield.server.terminalExecutable
import afield.server.terminalInfo
import afield.server.terminalOutputMark
import afield.server.terminalOutputSince
import afield.server.terminalSettled
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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
class FieldTerminal internal constructor(val id: String, startAt: Long = 0) {
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

    /**
     * Suspends until the program has printed something and gone quiet (a shell showing its
     * prompt), or has exited. [send] already waits for this before typing.
     */
    suspend fun awaitReady(timeoutMs: Long = 10_000) = withTimeout(timeoutMs) {
        while (!terminalSettled(id)) delay(50)
    }

    /** Suspends until the process exits, and returns its exit code. */
    suspend fun awaitExit(timeoutMs: Long = Long.MAX_VALUE): Int = withTimeout(timeoutMs) {
        while (true) {
            val info = terminalInfo(id) ?: error("terminal $id is gone")
            info.exitCode?.let { return@withTimeout it }
            delay(50)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    /**
     * Suspends until [pattern] appears in the terminal's output, and returns the match. Colour
     * and cursor escapes are stripped first, so match the text you see.
     *
     * Each terminal reads its output once, front to back: a search starts where the last match
     * ended (at the start, for a fresh terminal), so output printed before you call this still
     * counts, and a prompt is not matched twice.
     *
     * ```kotlin
     * go {
     *     val py = terminal("py", ["python3", "-q"])
     *     py.awaitOutput(">>> ")
     *     val answer = py.sendAndAwait("6 * 7", Regex("""\d+"""))
     *     print(answer.value)  // 42
     * }
     * ```
     *
     * Throws if the process exits first, or after [timeoutMs].
     */
    suspend fun awaitOutput(pattern: Regex, timeoutMs: Long = 30_000): MatchResult = awaitMatch(pattern, timeoutMs, 0)

    /** [awaitOutput] for a literal string. */
    suspend fun awaitOutput(text: String, timeoutMs: Long = 30_000): MatchResult = awaitOutput(Regex(Regex.escape(text)), timeoutMs)

    /**
     * Types [line] and a return, then suspends until [pattern] appears in the reply. The reply
     * starts after the terminal's echo of [line], so `\d+` after sending `6 * 7` matches `42`.
     */
    suspend fun sendAndAwait(line: String, pattern: Regex, timeoutMs: Long = 30_000): MatchResult {
        awaitReady()
        readNew()
        // Whatever was printed before the send is behind us; the reply follows the echoed line.
        unread.setLength(0)
        send(line)
        return awaitMatch(pattern, timeoutMs, skipLine = true)
    }

    /** Output read from the pty but not yet consumed by a match, escapes stripped. */
    private val unread = StringBuilder()
    /** How many bytes of the terminal's output [unread] has taken in. */
    private var readTo = startAt

    private fun readNew() {
        val (bytes, next) = terminalOutputSince(id, readTo) ?: error("terminal $id is gone")
        readTo = next
        if (bytes.isNotEmpty()) unread.append(stripTerminalEscapes(bytes.toString(Charsets.UTF_8)))
    }

    private suspend fun awaitMatch(pattern: Regex, timeoutMs: Long, from: Int = 0, skipLine: Boolean = false): MatchResult =
        withTimeout(timeoutMs) {
            while (true) {
                readNew()
                val start = if (!skipLine) from else unread.indexOf('\n').let { if (it < 0) -1 else it + 1 }
                // Matched against a copy: the MatchResult must not change when [unread] is consumed.
                if (start >= 0) pattern.find(unread.toString(), start)?.let { match ->
                    unread.delete(0, match.range.last + 1)
                    return@withTimeout match
                }
                if (terminalInfo(id)?.running != true) error("terminal $id exited before printing ${pattern.pattern}")
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        }


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
 * terminal("top", command = ["top"])   // or any program
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
    // A re-shown terminal's earlier output was for the earlier run; a fresh one is read from its start.
    return FieldTerminal(terminalId, if (reuse != null) terminalOutputMark(terminalId) else 0L)
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

/** CSI and OSC sequences and stray carriage returns out, so output matches as it reads on screen. */
internal fun stripTerminalEscapes(text: String): String =
    text.replace(Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|\u001B[()][0-9A-Za-z]|\u001B[=>]|\r"), "")
