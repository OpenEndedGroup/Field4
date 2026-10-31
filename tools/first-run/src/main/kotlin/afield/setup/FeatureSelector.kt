package afield.setup

import org.jline.keymap.BindingReader
import org.jline.keymap.KeyMap
import org.jline.terminal.Attributes
import org.jline.terminal.TerminalBuilder
import org.jline.utils.InfoCmp.Capability
import java.util.concurrent.TimeUnit

internal data class FeatureOption(val number: Int, val extra: Extra, val prerequisites: List<String>, val missing: List<String>, val installable: Set<String> = emptySet()) {
    val enabled get() = missing.all { it in installable }
}

internal fun commandWorks(vararg arguments: String): Boolean = runCatching {
    val process = ProcessBuilder(*arguments).redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
    if (!process.waitFor(3, TimeUnit.SECONDS)) { process.destroyForcibly(); false } else process.exitValue() == 0
}.getOrDefault(false)

internal fun featureOptions(
    platform: String,
    available: (String) -> Boolean = ::toolAvailable,
    nvidia: Boolean = commandWorks("nvidia-smi", "-L"),
    rustErrors: List<String> = paintToolchainErrors(),
    rustup: Boolean = commandWorks("rustup", "show", "active-toolchain"),
    winget: Boolean = false,
    solidcraftErrors: List<String> = solidcraftToolchainErrors(),
): List<FeatureOption> = extrasCatalog.mapIndexedNotNull { index, extra ->
    // Keep catalog numbers stable even when a platform-specific feature is omitted.
    if (platform !in extra.platforms || extra.id == "syphon") return@mapIndexedNotNull null
    val tools = extraTools(setOf(extra.id), platform).sorted().toMutableList()
    if (extra.id == "paint") tools += "rustup"
    val gpu = extra.id in setOf("dlss", "opendlssnr", "diffusion", "ardy", "closd")
    if (gpu && !nvidia) return@mapIndexedNotNull null
    val missing = tools.filter { if (it == "rustup") !rustup else !available(it) }.toMutableList()
    if (extra.id == "paint" && rustErrors.isNotEmpty()) {
        listOf("cargo", "rustc").filter { name -> rustErrors.any { it.startsWith(name) } }.forEach { name ->
            if (name !in missing) missing += "$name >=1.85"
        }
    }
    if (extra.id == "solidcraft" && solidcraftErrors.isNotEmpty()) {
        listOf("cargo", "rustc").filter { name -> solidcraftErrors.any { it.startsWith(name) } }.forEach { name ->
            if (name !in missing) missing += "$name >=1.95"
        }
    }
    FeatureOption(index + 1, extra, tools + if (gpu) listOf("NVIDIA") else emptyList(), missing,
        if (platform == "windows" && winget) wingetPackages.keys else emptySet())
}

internal class FeatureSelection(val options: List<FeatureOption>, initial: Set<String> = emptySet()) {
    val selected = initial.filterTo(linkedSetOf()) { id -> options.any { it.extra.id == id && it.enabled } }
    var cursor = 0
    fun move(delta: Int) { if (options.isNotEmpty()) cursor = (cursor + delta).coerceIn(0, options.lastIndex) }
    fun toggle(index: Int = cursor) {
        val option = options.getOrNull(index) ?: return
        if (option.enabled && !selected.remove(option.extra.id)) selected += option.extra.id
    }
}

internal fun featureRow(ui: TerminalUi, option: FeatureOption, checked: Boolean, focused: Boolean = false): String {
    val prefix = "${if (focused) ">" else " "} ${option.number.toString().padStart(2, ' ')} [${if (checked) "x" else if (!option.enabled) "-" else " "}] "
    val requirements = when {
        !option.enabled -> "needs ${option.missing.joinToString(", ")}"
        option.missing.isNotEmpty() -> "install: ${option.missing.joinToString(", ")}"
        else -> option.prerequisites.joinToString(", ")
    }
    // Keep one row per feature. Narrow terminals abbreviate the title before the prerequisites.
    val maxRight = (ui.width - prefix.length - 12).coerceAtLeast(0)
    fun shorten(text: String, limit: Int) = if (text.length <= limit) text else if (limit >= 3) text.take(limit - 3) + "..." else text.take(limit)
    val right = shorten(requirements, maxRight)
    val title = shorten(option.extra.title, (ui.width - prefix.length - right.length - 2).coerceAtLeast(0))
    val gap = " ".repeat((ui.width - prefix.length - title.length - right.length).coerceAtLeast(0))
    val label = if (option.enabled) ui.strong(title) else ui.crossed(title)
    return (if (focused) ui.accent(prefix) else ui.quiet(prefix)) + label + gap +
        if (option.missing.isEmpty()) ui.quiet(right) else ui.warning(right)
}

internal fun selectFeatures(options: List<FeatureOption>, initial: Set<String>): Set<String>? {
    val ui = terminalUi
    val state = FeatureSelection(options, initial)
    if (ui.color == TerminalColor.NONE) return selectFeaturesByNumber(state)
    val terminal = runCatching { TerminalBuilder.builder().system(true).dumb(false).nativeSignals(false)
        .provider(if (System.getProperty("os.name").startsWith("Windows")) "jansi" else "exec").build() }.getOrNull()
        ?: return selectFeaturesByNumber(state)
    terminal.use { term ->
        val original = term.enterRawMode()
        try {
            // Handle Ctrl+C in the selector so the finally block restores the terminal.
            val raw = term.attributes
            raw.setLocalFlag(Attributes.LocalFlag.ISIG, false)
            term.attributes = raw
            term.puts(Capability.enter_ca_mode)
            val keys = KeyMap<String>().apply {
                bind("up", "\u001b[A", "\u001bOA", "k")
                bind("down", "\u001b[B", "\u001bOB", "j")
                bind("toggle", " ")
                bind("done", "\r", "\n")
                bind("cancel", "q", "\u0003", "\u0004")
                bind("all", "a")
                bind("none", "n")
                bind("pageUp", "\u001b[5~")
                bind("pageDown", "\u001b[6~")
            }
            val reader = BindingReader(term.reader())
            while (true) {
                val width = term.width.takeIf { it >= 24 } ?: ui.width
                val height = term.height.takeIf { it >= 8 } ?: 24
                val pageSize = (height - 7).coerceAtLeast(1)
                val start = (state.cursor - pageSize + 1).coerceAtLeast(0)
                val page = options.drop(start).take(pageSize)
                val frame = TerminalUi(ui.color, width)
                val lines = mutableListOf(frame.strong("  Make it yours") + " ".repeat((width - 18).coerceAtLeast(1)) + frame.quiet("2/4"), "")
                page.forEach { option -> lines += featureRow(frame, option, option.extra.id in state.selected, options.indexOf(option) == state.cursor) }
                lines += ""
                lines += frame.quiet("  ${state.selected.size} selected | ${start + 1}-${start + page.size} of ${options.size}")
                if (width >= 60) {
                    lines += frame.quiet("  Up/Down: move   Space: toggle   Enter: review")
                    lines += frame.quiet("  a: select available   n: clear   q: cancel")
                } else {
                    lines += frame.quiet("  Arrows move | Space toggle")
                    lines += frame.quiet("  Enter review | q cancel")
                }
                val footerWidth = width
                val escapes = Regex("\\u001b\\[[0-9;]*m")
                term.writer().print("\u001b[H\u001b[2J" + lines.map { line ->
                    if (escapes.replace(line, "").length <= footerWidth) line else escapes.replace(line, "").take(footerWidth)
                }.joinToString("\r\n"))
                term.flush()
                when (reader.readBinding(keys)) {
                    "up" -> state.move(-1)
                    "down" -> state.move(1)
                    "pageUp" -> state.move(-pageSize)
                    "pageDown" -> state.move(pageSize)
                    "toggle" -> state.toggle()
                    "all" -> options.indices.forEach { if (options[it].enabled && options[it].extra.id !in state.selected) state.toggle(it) }
                    "none" -> state.selected.clear()
                    "done" -> return state.selected.toSet()
                    "cancel", null -> return null
                }
            }
        } finally {
            term.attributes = original
            term.puts(Capability.exit_ca_mode)
            term.flush()
        }
    }
}

private fun selectFeaturesByNumber(state: FeatureSelection): Set<String>? {
    val ui = terminalUi
    while (true) {
        state.options.forEach { println(featureRow(ui, it, it.extra.id in state.selected)) }
        print(ui.prompt("Toggle numbers/names; Enter to review; q to cancel:")); System.out.flush()
        val answer = readlnOrNull()?.trim() ?: return null
        if (answer.isEmpty()) return state.selected.toSet()
        if (answer == "q") return null
        val ids = runCatching { selectedExtras(answer) }.getOrElse { ui.paragraph(it.message.orEmpty()); continue }
        ids.forEach { id ->
            val index = state.options.indexOfFirst { it.extra.id == id }
            if (index < 0 || !state.options[index].enabled) ui.paragraph("$id is unavailable with the current prerequisites.")
            else state.toggle(index)
        }
    }
}
