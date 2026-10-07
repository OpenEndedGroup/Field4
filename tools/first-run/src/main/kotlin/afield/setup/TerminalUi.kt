package afield.setup

import afield.asciiWelcome
import java.util.concurrent.TimeUnit

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
    fun crossed(text: String) = styled(text, "2;9")
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
        return lines.joinToString("\n") { line -> indent + accent(line) }
    }

    fun welcome() {
        println("\n${wordmark()}\n")
        paragraph("Field·4 is an experimental, live-coding environment for new-media art and data visualization.")
    }

    fun section(number: Int, title: String, total: Int = 4) {
        println()
        val progress = "$number/$total"
        val available = width - 2 - title.length - progress.length
        if (available < 2) {
            println(" ".repeat((width - progress.length).coerceAtLeast(0)) + quiet(progress))
            paragraph(title, paint = ::strong)
        } else {
            println("  " + strong(title) + " " + quiet("-".repeat(available - 2)) + " " + quiet(progress))
        }
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
        val numberLabel = accent(number.toString().padStart(2, ' '))
        if (title.length + number.toString().length + 4 + (if (unavailable) 16 else 0) > width) {
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
