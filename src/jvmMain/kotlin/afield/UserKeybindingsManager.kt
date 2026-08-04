package afield

import afield.logger.lprintln
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.yaml.snakeyaml.Yaml
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

/**
 * `~/.afield/keybindings.yaml` — the user's shortcut overrides, watched and applied live exactly like
 * `theme.css` (see [UserThemeManager]).
 *
 * The file is *generated* from the command inventory the client reports rather than from a table kept
 * here, so it can never drift from the real defaults, and commands added to Field later get appended
 * on the next run instead of quietly missing. Only lines the user has actually changed matter: the
 * client falls back to a command's own default for anything the file does not mention.
 */
object UserKeybindingsManager {
    private fun defaultBindingsFile(): File =
        File(File(System.getProperty("user.home"), ".afield"), "keybindings.yaml")

    /**
     * The file being watched. Normally `~/.afield/keybindings.yaml`; [watchFile] points it elsewhere.
     */
    @Volatile
    private var bindingsFile: File = defaultBindingsFile()

    private val configDir: File
        get() = bindingsFile.parentFile ?: defaultBindingsFile().parentFile

    /**
     * Watches [file] instead of `~/.afield/keybindings.yaml` — or the default again, when null — and
     * re-reads immediately.
     *
     * Field never calls this itself. It exists so that a test, or a documentation capture that has to
     * show a rebind actually taking effect, can drive the whole real path (file → watcher → parse →
     * push → client re-registration) against a scratch file rather than rewriting the shortcuts of
     * whoever happens to be running Field at the time.
     */
    fun watchFile(file: File?) {
        bindingsFile = file ?: defaultBindingsFile()
        // Same rule as a missing `~/.afield/keybindings.yaml`: if the file is not there, write it from
        // what Field knows about its own commands. So pointing this at a fresh path is also how you
        // get a pristine copy of the defaults out of a running Field.
        val known = inventory
        if (!bindingsFile.exists() && known.isNotEmpty()) applyInventory(CommandInventory(known))
        else reloadNow()
    }

    /** The bindings in force right now, exactly as the clients were last told about them. */
    val resolved: UserKeybindings
        get() = current

    private const val header = """# Field keybindings.
#
# Edit this file while Field is running — it is watched, and changes apply live (there is also a
# "Reload Keybindings" command). Field generated the entries below from its own defaults, so this is
# a complete list of every rebindable command; new commands are appended as they appear.
#
# Syntax
#   command-id: cmd+shift+d      one shortcut
#   command-id: [alt+enter, f5]  several shortcuts for the same command
#   command-id: ""               no shortcut at all
#   (delete a line)              go back to Field's default — Field writes the line back, with the
#                                default as its value, the next time it takes stock of its commands
#
# Modifiers are cmd, alt and shift, joined with `+`. `cmd` means Command on macOS and Control
# elsewhere (ctrl, control, meta and mod are accepted spellings of it); `alt` is Option on macOS.
# Keys are a-z, 0-9, f1-f24, enter, escape, backspace, delete, space, tab, up, down, left, right,
# home, end, pageup, pagedown, insert, and punctuation written as itself: [ ] ' ; , . / \ - = `
# A shifted character stands for shift plus that key, so `?` means the same as `shift+/`. One key per
# shortcut: there are no two-keystroke chords, so `cmd+k cmd+s` is rejected rather than half-obeyed.
#
# Some commands only make sense in one place, and Field records that here as well:
#   #scope canvas   only when the canvas has focus (bare letters, so they don't hijack typing)
#   #scope editor   only inside a code editor
# Shortcuts with no scope comment work in both.

bindings:
"""

    @Volatile
    private var started = false

    @Volatile
    private var lastSignature: String = ""

    /**
     * Every command any client has reported this run, used to seed and top up the file. It has to be
     * the union rather than the latest report: commands that only exist for a selection (`connect`,
     * `-copy`, the server-declared box actions) come and go as the selection changes, and a shortcut
     * the user set for one of them must not stop being recognized the moment it deregisters.
     */
    private val inventoryById = LinkedHashMap<String, CommandBinding>()

    private val inventory: List<CommandBinding>
        get() = synchronized(inventoryById) { inventoryById.values.toList() }

    @Volatile
    private var current: UserKeybindings = UserKeybindings(path = bindingsFile.absolutePath)

    @OptIn(DelicateCoroutinesApi::class)
    fun start() {
        if (started) return
        started = true
        if (!configDir.exists()) configDir.mkdirs()
        current = read()
        lastSignature = signature()
        // A file that was already broken when Field started has to say so now. Reporting problems only
        // from the watcher meant a typo'd shortcut simply did nothing, silently, until the next save.
        logProblems()

        GlobalScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    val next = signature()
                    if (next != lastSignature) {
                        lastSignature = next
                        current = read()
                        lprintln("info,user-keybindings", "Reloaded keybindings from ${bindingsFile.absolutePath}")
                        logProblems()
                        _send(Message("set-user-keybindings").payload(current))
                    }
                } catch (t: Throwable) {
                    lprintln("error,user-keybindings", "Error watching ${bindingsFile.absolutePath}: ${t.message}")
                }
                delay(750.milliseconds)
            }
        }
    }

    fun sendTo(session: WebSocketSession?) {
        _send(session, Message("set-user-keybindings").payload(current))
    }

    /** Re-reads the file now and pushes it to every client, whatever the watcher has seen. */
    fun reloadNow() {
        lastSignature = signature()
        current = read()
        lprintln("info,user-keybindings", "Reloaded keybindings from ${bindingsFile.absolutePath} (on request)")
        logProblems()
        _send(Message("set-user-keybindings").payload(current))
    }

    /**
     * Records what commands exist and makes sure the file mentions all of them: it is written from
     * scratch when absent, and otherwise only ever *appended* to, so nothing the user wrote is lost.
     */
    fun applyInventory(reported: CommandInventory) {
        val commands = reported.commands.filter { it.id.isNotBlank() }
        if (commands.isEmpty()) return
        val changed = synchronized(inventoryById) {
            commands.filter { inventoryById.put(it.id, it) != it }
        }

        try {
            if (!configDir.exists()) configDir.mkdirs()
            if (!bindingsFile.exists()) {
                bindingsFile.safeSave(header + renderGroups(commands), createBackup = false, atomic = true)
                lprintln(
                    "info,user-keybindings",
                    "Created keybindings file at ${bindingsFile.absolutePath} with ${commands.size} commands"
                )
                reloadNow()
                return
            }

            val text = bindingsFile.readText()
            val mentioned = mentionedIds(text)
            val missing = commands.filter { it.id !in mentioned }
            if (missing.isEmpty()) {
                // Nothing to write, but a command Field had not seen before still has to reach the
                // clients so `effective` can name its shortcut (the cheatsheet asks about commands
                // that are not registered right now). The union only grows, so this settles quickly.
                if (changed.isNotEmpty()) reloadNow()
                return
            }

            val addition = buildString {
                if (!text.endsWith("\n")) append("\n")
                // One such section, not one per run: commands come and go with the selection, and a
                // line the user deleted to get the default back is "missing" again next time, so this
                // path runs more than once over the life of a file.
                if (!text.contains(appendedSectionHeader)) append("\n$appendedSectionHeader\n")
                // A hand-written file is allowed to be a bare mapping with no `bindings:` wrapper
                // (`read` accepts one), and appending indented entries to *that* would produce a file
                // that is no longer valid YAML at all — Field breaking the user's own file.
                //
                // Grouped, like the rest of the file: an appended entry with no `# ── Canvas ──` over
                // it looks like it belongs to whatever section it happens to land under.
                append(renderGroups(missing, indent = if (hasBindingsWrapper(text)) "  " else ""))
            }
            bindingsFile.safeSave(text + addition, createBackup = false, atomic = true)
            lprintln(
                "info,user-keybindings",
                "Appended ${missing.size} new command(s) to ${bindingsFile.absolutePath}: " +
                    missing.joinToString(", ") { it.id }
            )
            reloadNow()
        } catch (t: Throwable) {
            lprintln("error,user-keybindings", "Could not update ${bindingsFile.absolutePath}: ${t.message}")
        }
    }

    // ── rendering ────────────────────────────────────────────────────────────────

    private fun renderGroups(commands: List<CommandBinding>, indent: String = "  "): String = buildString {
        val byGroup = commands.groupBy { it.group.ifBlank { "Other" } }
        for (group in byGroup.keys.sorted()) {
            append("\n$indent# ── $group ")
            append("─".repeat((66 - group.length).coerceAtLeast(3)))
            append("\n")
            append(renderEntries(byGroup.getValue(group), indent))
        }
    }

    /** The one comment introducing entries Field added on its own, and the marker it is found by. */
    private const val appendedSectionHeader = "# Commands Field added after this file was written:"

    /**
     * True when the file keeps its entries under a `bindings:` mapping, which is what Field generates
     * and therefore what almost every file looks like. A bare top-level mapping is also legal input.
     */
    private fun hasBindingsWrapper(text: String): Boolean = text.lines().any { line ->
        line.startsWith("bindings:") || line.trimEnd() == "bindings:"
    }

    private fun renderEntries(commands: List<CommandBinding>, indent: String = "  "): String = buildString {
        for (command in commands.sortedBy { it.id }) {
            val value = when {
                command.defaults.isEmpty() -> "\"\""
                command.defaults.size == 1 -> yamlQuote(command.defaults.single())
                else -> command.defaults.joinToString(", ", "[", "]") { yamlQuote(it) }
            }
            val notes = mutableListOf<String>()
            command.label.trim().takeIf { it.isNotEmpty() }?.let { notes.add(it) }
            if (command.scope != KeyScope.both) notes.add("#scope ${command.scope}")
            val comment = if (notes.isEmpty()) "" else "  # ${notes.joinToString("  ")}"
            append("$indent${yamlKey(command.id)}: $value$comment\n")
        }
    }

    /** Command ids are safe bare keys unless they start with a character YAML reads structurally. */
    private fun yamlKey(id: String): String =
        if (id.matches(Regex("[A-Za-z_][A-Za-z0-9_.-]*"))) id else yamlQuote(id)

    private fun yamlQuote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /**
     * The command ids the file already talks about — including ones commented out, so re-enabling a
     * command by uncommenting it doesn't get a duplicate appended underneath.
     */
    private fun mentionedIds(text: String): Set<String> {
        val ids = mutableSetOf<String>()
        for (raw in text.lines()) {
            val line = raw.trim().removePrefix("#").trim()
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().trim('"', '\'')
            if (key.isNotEmpty() && key != "bindings") ids.add(key)
        }
        return ids
    }

    // ── parsing ──────────────────────────────────────────────────────────────────

    private fun read(): UserKeybindings {
        if (!bindingsFile.exists()) {
            return UserKeybindings(path = bindingsFile.absolutePath)
        }

        val problems = mutableListOf<String>()
        val bindings = mutableMapOf<String, List<String>>()

        val text = runCatching { bindingsFile.readText() }.getOrElse {
            problems.add("Could not read keybindings.yaml: ${it.message}")
            ""
        }

        if (text.isNotBlank()) {
            val root = runCatching { Yaml().load<Any?>(text) }.getOrElse { t ->
                // A YAML syntax error must not silently wipe every shortcut, so keep the last good
                // set and just tell the user where the file is broken.
                problems.add("keybindings.yaml is not valid YAML: ${t.message?.lines()?.firstOrNull() ?: t}")
                return current.copy(problems = problems, lastModified = bindingsFile.lastModified())
            }

            val map = asBindingMap(root)
            if (map == null) {
                if (root != null) problems.add("keybindings.yaml: expected a `bindings:` mapping of command to shortcut")
            } else {
                val known = inventory.map { it.id }.toSet()
                for ((rawKey, rawValue) in map) {
                    val id = rawKey?.toString()?.trim().orEmpty()
                    if (id.isEmpty()) continue
                    // An unrecognized id is usually a typo, but it can also be a command that simply
                    // isn't registered at this moment (the selection-dependent ones). Say so and pass
                    // it through anyway — dropping it would silently ignore a binding that works.
                    if (known.isNotEmpty() && id !in known) {
                        lprintln(
                            "warn,user-keybindings",
                            "keybindings.yaml mentions '$id', which is not a command Field currently " +
                                "has registered — check the spelling if the shortcut does nothing"
                        )
                    }
                    bindings[id] = specsFor(id, rawValue, problems)
                }
            }
        }

        val resolved = UserKeybindings(
            bindings = bindings,
            path = bindingsFile.absolutePath,
            lastModified = bindingsFile.lastModified(),
            problems = problems,
            effective = effectiveBindings(bindings)
        )
        logCollisions(resolved)
        return resolved
    }

    /** Override-or-default for every command reported this run. See [UserKeybindings.effective]. */
    private fun effectiveBindings(bindings: Map<String, List<String>>): Map<String, List<String>> {
        val out = mutableMapOf<String, List<String>>()
        for (command in inventory) out[command.id] = bindings[command.id] ?: command.defaults
        // Overrides for commands nothing has reported yet still belong here.
        for ((id, specs) in bindings) out.getOrPut(id) { specs }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun asBindingMap(root: Any?): Map<Any?, Any?>? {
        val map = root as? Map<Any?, Any?> ?: return null
        // Prefer an explicit `bindings:` section, but accept a bare top-level mapping too — someone
        // writing this file by hand shouldn't have to remember the wrapper.
        val section = map["bindings"]
        if (section is Map<*, *>) return section as Map<Any?, Any?>
        if (map.containsKey("bindings")) return emptyMap()
        return map
    }

    private fun specsFor(id: String, raw: Any?, problems: MutableList<String>): List<String> {
        val candidates: List<String> = when (raw) {
            null -> emptyList()
            is String -> if (raw.isBlank()) emptyList() else listOf(raw)
            is List<*> -> raw.mapNotNull { it?.toString() }.filter { it.isNotBlank() }
            is Number, is Boolean -> listOf(raw.toString())
            else -> {
                problems.add("keybindings.yaml: '$id' should be a shortcut or a list of shortcuts")
                emptyList()
            }
        }

        val out = mutableListOf<String>()
        for (candidate in candidates) {
            val canonical = KeySpec.canonicalize(candidate)
            if (canonical == null) {
                // Say *why*. "not a shortcut Field understands" is no help at all when the answer is
                // either a misspelled key or a two-keystroke chord Field simply does not have.
                val reason = KeySpec.explainFailure(candidate) ?: "Field cannot read it"
                problems.add("keybindings.yaml: '$candidate' (for '$id') is not a shortcut — $reason")
            } else {
                out.add(canonical)
            }
        }
        return out
    }

    // ── diagnostics ──────────────────────────────────────────────────────────────

    private fun logProblems() {
        current.problems.forEach { lprintln("error,user-keybindings", it) }
    }

    /**
     * Warns when one of the user's own shortcuts collides with another command in the same scope.
     * Defaults that clash with each other are Field's own business and stay quiet here.
     */
    private fun logCollisions(resolved: UserKeybindings) {
        if (resolved.bindings.isEmpty() || inventory.isEmpty()) return
        val effective = mutableMapOf<Pair<String, String>, MutableList<String>>()
        for (command in inventory) {
            val specs = resolved.bindings[command.id] ?: command.defaults
            for (spec in specs) {
                effective.getOrPut(spec to command.scope) { mutableListOf() }.add(command.id)
            }
        }
        for ((key, ids) in effective) {
            if (ids.size < 2) continue
            val (spec, scope) = key
            if (ids.none { resolved.bindings.containsKey(it) }) continue
            val where = if (scope == KeyScope.both) "" else " (in the $scope only)"
            lprintln(
                "warn,user-keybindings",
                "keybindings.yaml: '$spec' is bound to ${ids.sorted().joinToString(" and ")}$where — " +
                    "only one of them will win"
            )
        }
    }

    private fun signature(): String {
        if (!bindingsFile.exists()) return "missing"
        return "${bindingsFile.lastModified()}:${bindingsFile.length()}"
    }
}

@RunsAt(installHandlers)
fun installUserKeybindingsHandlers() {
    // The client is the authority on what commands exist; it reports them once the palette and the
    // editor have registered everything.
    handlers["report-command-inventory"] = mutableSetOf({ message, _ ->
        val inventory = Json.decodeFromJsonElement<CommandInventory>(message.payload!!)
        UserKeybindingsManager.applyInventory(inventory)
        Handled.yes
    })

    handlers["reload-user-keybindings"] = mutableSetOf({ _, _ ->
        UserKeybindingsManager.reloadNow()
        Handled.yes
    })
}
