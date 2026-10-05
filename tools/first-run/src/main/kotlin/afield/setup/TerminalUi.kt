package afield.setup

import afield.asciiWelcome
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

internal enum class TerminalColor { NONE, ANSI, TRUE_COLOR }

internal fun terminalColor(environment: Map<String, String>, console: Boolean, windows: Boolean): TerminalColor {
    if (!console || "NO_COLOR" in environment || environment["CLICOLOR"] == "0" || environment["TERM"] == "dumb") return TerminalColor.NONE
    if (windows && environment["WT_SESSION"] == null && environment["ANSICON"] == null && environment["ConEmuANSI"] != "ON") return TerminalColor.NONE
    return if (environment["COLORTERM"]?.lowercase() in setOf("truecolor", "24bit") || "WT_SESSION" in environment) TerminalColor.TRUE_COLOR else TerminalColor.ANSI
}

internal fun terminalWidth(environment: Map<String, String>, console: Boolean, windows: Boolean): Int {
    environment["COLUMNS"]?.toIntOrNull()?.takeIf { it > 0 }?.let { return it.coerceIn(24, 180) }
    if (console && !windows) {
        val columns = runCatching {
            val proc = ProcessBuilder("stty", "size").redirectInput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            if (!proc.waitFor(250, TimeUnit.MILLISECONDS)) { proc.destroyForcibly(); return@runCatching null }
            proc.inputStream.bufferedReader().use { it.readText() }.trim().split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()
        }.getOrNull()
        columns?.takeIf { it > 0 }?.let { return it.coerceIn(24, 180) }
    }
    return 80
}

internal class TerminalUi(val color: TerminalColor, val width: Int) {
    private val reset = if (color == TerminalColor.NONE) "" else "\u001b[0m"
    private fun styled(text: String, code: String): String = if (color == TerminalColor.NONE) text else "\u001b[${code}m$text$reset"
    fun accent(text: String) = styled(text, "96")
    fun strong(text: String) = styled(text, "1")
    fun quiet(text: String) = styled(text, "2")
    fun good(text: String) = styled(text, "92")
    fun warning(text: String) = styled(text, "93")
    fun error(text: String) = styled(text, "91;1")

    private val compactWordmark = listOf(
        "   ______  ____  ______  __      ____",
        "  / ____/ /  _/ / ____/ / /     / __ \\",
        " / /_     / /  / __/  / /     / / / /",
        "/ __/   _/ /  / /___ / /___  / /_/ /",
        "/_/     /___/ /_____//_____/ /_____/",
    )

    fun logoLines(): List<String> {
        val original = asciiWelcome.trimIndent().lines().map(String::trimEnd).dropWhile(String::isBlank).dropLastWhile(String::isBlank)
        return when {
            width >= original.maxOf(String::length) + 4 -> original
            width >= compactWordmark.maxOf(String::length) + 4 -> compactWordmark
            else -> listOf("F I E L D")
        }
    }

    fun wordmark(): String {
        val lines = logoLines()
        val contentWidth = lines.maxOf(String::length)
        val indent = " ".repeat(((width - contentWidth) / 2).coerceAtLeast(0))
        val colors = listOf(intArrayOf(58, 213, 202), intArrayOf(126, 155, 255), intArrayOf(202, 139, 242), intArrayOf(255, 195, 131))
        return lines.mapIndexed { row, line ->
            indent + buildString {
                line.forEachIndexed { column, glyph ->
                    if (glyph == ' ' || color == TerminalColor.NONE) append(glyph)
                    else if (color == TerminalColor.ANSI) {
                        val band = (column * 4 / contentWidth).coerceAtMost(3)
                        append("\u001b[${listOf(96, 94, 95, 93)[band]}m$glyph")
                    } else {
                        // A diagonal wash across the same geometry: cool faces, warm far edge.
                        val position = ((column.toDouble() / contentWidth * .85 + row.toDouble() / lines.size * .15) * 3).coerceIn(0.0, 3.0)
                        val left = position.toInt().coerceAtMost(2)
                        val blend = position - left
                        val shade = if (glyph == ':') .65 else 1.0
                        val rgb = (0..2).map { ((colors[left][it] * (1 - blend) + colors[left + 1][it] * blend) * shade).roundToInt() }
                        append("\u001b[38;2;${rgb.joinToString(";")}m$glyph")
                    }
                }
                append(reset)
            }
        }.joinToString("\n")
    }

    fun welcome() {
        println("\n${wordmark()}\n")
        val title = if (width >= 40) "FIELD 4  /  MAKE YOURSELF AT HOME" else "FIELD 4 / SETUP"
        println(" ".repeat(((width - title.length) / 2).coerceAtLeast(0)) + strong(title))
        println()
        paragraph("Code, graphics, sound. A workspace for trying things.")
        paragraph("Let's get the essentials ready. Everything else can wait.", dim = true)
    }

    fun section(number: String, title: String) {
        println()
        val label = "$number  $title"
        if (label.length + 2 > width) {
            println("  " + accent(number))
            paragraph(title, indent = 4, paint = ::strong)
            println()
            return
        }
        println("  " + accent(number) + "  " + strong(title) + " " + quiet("-".repeat((width - label.length - 4).coerceIn(0, 60))))
        println()
    }

    fun paragraph(text: String, dim: Boolean = false, indent: Int = 2, paint: (String) -> String = { it }) {
        val limit = (width - indent).coerceAtLeast(16)
        var line = ""
        fun flush() { if (line.isNotEmpty()) println(" ".repeat(indent) + paint(if (dim) quiet(line) else line)) }
        text.split(Regex("\\s+")).forEach { word ->
            if (line.isNotEmpty() && line.length + 1 + word.length > limit) { flush(); line = "" }
            line = if (line.isEmpty()) word else "$line $word"
        }
        flush()
    }

    fun status(label: String, value: String, needsAttention: Boolean = false) {
        if (value.length + 23 > width) {
            println("  " + quiet(label))
            paragraph(value, indent = 4, paint = if (needsAttention) ::warning else ::good)
            return
        }
        println("  " + quiet(label.padEnd(20)) + " " + if (needsAttention) warning(value) else good(value))
    }

    fun choice(number: Int, title: String, hint: String = "", unavailable: Boolean = false) {
        val numberLabel = accent(number.toString().padStart(2, '0'))
        if (title.length + 6 + (if (unavailable) 16 else 0) > width) {
            println("  $numberLabel")
            paragraph(title, indent = 6, paint = ::strong)
            if (unavailable) paragraph("separate setup", indent = 6, paint = ::warning)
        } else println("  $numberLabel  " + strong(title) + if (unavailable) "  " + warning("separate setup") else "")
        if (hint.isNotEmpty()) paragraph(hint, dim = true, indent = 6)
    }

    fun command(arguments: List<String>) { println("  " + accent(">") + " " + strong(commandDisplay(arguments))) }
    fun prompt(text: String) = "\n  ${accent(">")} ${strong(text)} "
    fun success(text: String) { println("\n  " + good(strong(text))) }
}

internal val terminalUi by lazy {
    val environment = System.getenv()
    val console = System.console() != null
    val windows = System.getProperty("os.name").startsWith("Windows")
    TerminalUi(terminalColor(environment, console, windows), terminalWidth(environment, console, windows))
}
