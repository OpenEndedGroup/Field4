package afield

import kotlinx.serialization.Serializable

/**
 * The shared keybinding vocabulary for `~/.afield/keybindings.yaml`.
 *
 * One spec string ("cmd+shift+d") is the single source of truth for a command's shortcut. The client
 * derives *both* the Monaco keybinding integer (for in-editor use) and the canvas-level DOM event
 * predicate from it, so a rebind can never leave the two halves disagreeing.
 *
 * `cmd` is Monaco's `CtrlCmd`: Command on macOS, Control elsewhere. It is written `cmd` in the file
 * because that is what a Field user types; `ctrl`, `control`, `command`, `meta`, `mod` and `ctrlcmd`
 * are all accepted as input and canonicalize to it.
 */
@Serializable
data class KeyChord(
    val ctrlCmd: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    /** Canonical key token — see [KeySpec.canonicalKeyToken]. */
    val key: String
)

object KeySpec {

    /**
     * Canonical token -> the DOM `KeyboardEvent.code` we match on, plus the Monaco `KeyCode` member
     * names to try (newer name first, then the pre-0.34 `US_*`/`KEY_*` spelling, since Field's bundled
     * Monaco and the docs build have historically disagreed about which exists).
     */
    private val namedKeys: Map<String, Pair<String, List<String>>> = buildMap {
        put("enter", "Enter" to listOf("Enter"))
        put("escape", "Escape" to listOf("Escape"))
        put("backspace", "Backspace" to listOf("Backspace"))
        put("delete", "Delete" to listOf("Delete"))
        put("space", "Space" to listOf("Space"))
        put("tab", "Tab" to listOf("Tab"))
        put("up", "ArrowUp" to listOf("UpArrow"))
        put("down", "ArrowDown" to listOf("DownArrow"))
        put("left", "ArrowLeft" to listOf("LeftArrow"))
        put("right", "ArrowRight" to listOf("RightArrow"))
        put("home", "Home" to listOf("Home"))
        put("end", "End" to listOf("End"))
        put("pageup", "PageUp" to listOf("PageUp"))
        put("pagedown", "PageDown" to listOf("PageDown"))
        put("insert", "Insert" to listOf("Insert"))
        put("[", "BracketLeft" to listOf("BracketLeft", "US_OPEN_SQUARE_BRACKET"))
        put("]", "BracketRight" to listOf("BracketRight", "US_CLOSE_SQUARE_BRACKET"))
        put("'", "Quote" to listOf("Quote", "US_QUOTE"))
        put(";", "Semicolon" to listOf("Semicolon", "US_SEMICOLON"))
        put(",", "Comma" to listOf("Comma", "US_COMMA"))
        put(".", "Period" to listOf("Period", "US_DOT"))
        put("/", "Slash" to listOf("Slash", "US_SLASH"))
        put("\\", "Backslash" to listOf("Backslash", "US_BACKSLASH"))
        put("-", "Minus" to listOf("Minus", "US_MINUS"))
        put("=", "Equal" to listOf("Equal", "US_EQUAL"))
        put("`", "Backquote" to listOf("Backquote", "US_BACKTICK"))
    }

    /** Spellings a human might reasonably write, mapped onto a canonical token. */
    private val keyAliases: Map<String, String> = buildMap {
        put("return", "enter")
        put("esc", "escape")
        put("del", "delete")
        put("spacebar", "space")
        put("arrowup", "up")
        put("arrowdown", "down")
        put("arrowleft", "left")
        put("arrowright", "right")
        put("bracketleft", "[")
        put("bracketright", "]")
        put("openbracket", "[")
        put("closebracket", "]")
        put("quote", "'")
        put("semicolon", ";")
        put("comma", ",")
        put("period", ".")
        put("dot", ".")
        put("slash", "/")
        put("backslash", "\\")
        put("minus", "-")
        put("dash", "-")
        put("equal", "=")
        put("equals", "=")
        put("backquote", "`")
        put("backtick", "`")
        put("plus", "=")
    }

    /**
     * Shifted punctuation written as the character it produces on a US layout. Writing `?` is much
     * friendlier than `shift+/`, and the client also matches these against `event.key` directly so
     * they keep working on layouts where the character is not literally Shift plus that key.
     */
    private val shiftedChars: Map<String, String> = buildMap {
        put("?", "/")
        put("~", "`")
        put("!", "1")
        put("@", "2")
        put("#", "3")
        put("$", "4")
        put("%", "5")
        put("^", "6")
        put("&", "7")
        put("*", "8")
        put("(", "9")
        put(")", "0")
        put("_", "-")
        put("{", "[")
        put("}", "]")
        put(":", ";")
        put("\"", "'")
        put("<", ",")
        put(">", ".")
        put("|", "\\")
    }

    private val modifierAliases: Map<String, String> = buildMap {
        listOf("cmd", "command", "ctrl", "control", "meta", "mod", "ctrlcmd", "super").forEach { put(it, "cmd") }
        listOf("alt", "option", "opt").forEach { put(it, "alt") }
        listOf("shift").forEach { put(it, "shift") }
    }

    /** True when [token] is a key Field knows how to bind. */
    fun isKnownKeyToken(token: String): Boolean = canonicalKeyToken(token) != null

    /**
     * Normalizes one key token, or null if unrecognized. Canonical tokens are: `a`..`z`, `0`..`9`,
     * `f1`..`f24`, the named keys above, and the punctuation characters above.
     */
    fun canonicalKeyToken(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val lower = t.lowercase()
        if (lower.length == 1 && (lower[0] in 'a'..'z' || lower[0] in '0'..'9')) return lower
        if (namedKeys.containsKey(lower)) return lower
        keyAliases[lower]?.let { return it }
        if (lower.length >= 2 && lower[0] == 'f') {
            val n = lower.drop(1).toIntOrNull()
            if (n != null && n in 1..24) return lower
        }
        return null
    }

    /**
     * Parses a spec such as `cmd+shift+d`, `alt+]`, `f12` or `?`. Separators may be `+`, `-`, space or
     * comma; a trailing `-` or `+` is treated as the key itself (so `cmd+-` and `cmd+plus` both work).
     * Returns null for a blank or unparseable spec.
     */
    fun parse(spec: String): KeyChord? {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) return null

        val tokens = tokenize(trimmed)
        if (tokens.isEmpty()) return null

        var ctrlCmd = false
        var alt = false
        var shift = false
        var key: String? = null

        for (token in tokens) {
            val lower = token.lowercase()
            val modifier = modifierAliases[lower]
            if (modifier != null) {
                when (modifier) {
                    "cmd" -> ctrlCmd = true
                    "alt" -> alt = true
                    "shift" -> shift = true
                }
                continue
            }
            // A bare shifted character (`?`) means Shift plus its unshifted key.
            val shifted = shiftedChars[token]
            if (shifted != null) {
                shift = true
                key = shifted
                continue
            }
            key = canonicalKeyToken(token) ?: return null
        }

        val resolved = key ?: return null
        return KeyChord(ctrlCmd = ctrlCmd, alt = alt, shift = shift, key = resolved)
    }

    private fun tokenize(spec: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        for (c in spec) {
            val isSeparator = (c == '+' || c == '-' || c == ' ' || c == '\t' || c == ',') && current.isNotEmpty()
            if (isSeparator) {
                out.add(current.toString())
                current.clear()
            } else if (c == ' ' || c == '\t') {
                // whitespace around a separator — ignore
            } else {
                current.append(c)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())

        // `+`, `-` and `,` are both separators and bindable keys. A lone one is only a key when it is
        // the last token — so `cmd+,` binds comma, while the stray `+` in `cmd + k` is just spacing.
        return out.filterIndexed { index, token ->
            token.isNotEmpty() && (token.length > 1 || token !in separatorChars || index == out.size - 1)
        }
    }

    private val separatorChars = setOf("+", "-", ",")

    /** Renders [chord] back to its canonical spec string. Always `cmd+alt+shift+key` order. */
    fun format(chord: KeyChord): String = buildString {
        if (chord.ctrlCmd) append("cmd+")
        if (chord.alt) append("alt+")
        if (chord.shift) append("shift+")
        append(chord.key)
    }

    /** Canonicalizes a user-written spec, or null if it cannot be parsed. */
    fun canonicalize(spec: String): String? = parse(spec)?.let { format(it) }

    /** The DOM `KeyboardEvent.code` that [chord] matches, or null for a key with no stable code. */
    fun domCode(chord: KeyChord): String? {
        val key = chord.key
        if (key.length == 1 && key[0] in 'a'..'z') return "Key${key.uppercase()}"
        if (key.length == 1 && key[0] in '0'..'9') return "Digit$key"
        if (key.length >= 2 && key[0] == 'f' && key.drop(1).toIntOrNull() != null) return key.uppercase()
        return namedKeys[key]?.first
    }

    /**
     * The Monaco `KeyCode` member names to try for [chord]'s key, in preference order. The client
     * looks each up on `monaco.KeyCode` and takes the first that exists.
     */
    fun monacoKeyCodeNames(chord: KeyChord): List<String> {
        val key = chord.key
        if (key.length == 1 && key[0] in 'a'..'z') {
            return listOf("Key${key.uppercase()}", "KEY_${key.uppercase()}")
        }
        if (key.length == 1 && key[0] in '0'..'9') {
            return listOf("Digit$key", "KEY_$key")
        }
        if (key.length >= 2 && key[0] == 'f' && key.drop(1).toIntOrNull() != null) {
            return listOf(key.uppercase())
        }
        return namedKeys[key]?.second ?: emptyList()
    }

    /**
     * The literal character [chord] produces, for matching against `event.key` on layouts where the
     * DOM code does not line up (see [shiftedChars]). Null for named keys.
     */
    fun literalChar(chord: KeyChord): String? {
        if (chord.key.length != 1) return null
        if (!chord.shift) return chord.key
        return shiftedChars.entries.firstOrNull { it.value == chord.key }?.key ?: chord.key.uppercase()
    }

    /** A human-facing label ("Cmd+Shift+D" / "Ctrl+Shift+D"), used by the palette and cheatsheet. */
    fun label(chord: KeyChord, mac: Boolean): String {
        val parts = mutableListOf<String>()
        if (chord.ctrlCmd) parts.add(if (mac) "Cmd" else "Ctrl")
        if (chord.alt) parts.add(if (mac) "Opt" else "Alt")

        // Shift over punctuation is the character it types: `?`, not `Shift+/`. Letters keep the
        // modifier, because nobody reads `Shift+D` as `D`.
        val shiftedChar = if (chord.shift) shiftedChars.entries.firstOrNull { it.value == chord.key }?.key else null
        if (shiftedChar != null) {
            parts.add(shiftedChar)
            return parts.joinToString("+")
        }

        if (chord.shift) parts.add("Shift")
        parts.add(keyLabel(chord.key))
        return parts.joinToString("+")
    }

    private fun keyLabel(key: String): String = when {
        key.length == 1 && key[0] in 'a'..'z' -> key.uppercase()
        key.length >= 2 && key[0] == 'f' && key.drop(1).toIntOrNull() != null -> key.uppercase()
        key == "up" -> "Up"
        key == "down" -> "Down"
        key == "left" -> "Left"
        key == "right" -> "Right"
        key == "escape" -> "Esc"
        key == "pageup" -> "PageUp"
        key == "pagedown" -> "PageDown"
        else -> key.replaceFirstChar { it.uppercase() }
    }
}

/** Where a binding is live. Bare-letter shortcuts are canvas-only so they don't hijack typing. */
object KeyScope {
    const val both = "both"
    const val canvas = "canvas"
    const val editor = "editor"

    fun normalize(raw: String?): String = when (raw?.trim()?.lowercase()) {
        canvas -> canvas
        editor -> editor
        else -> both
    }
}

/**
 * One command as the client knows it, reported to the server so `keybindings.yaml` can be seeded and
 * kept complete without a hand-maintained duplicate of the defaults.
 */
@Serializable
data class CommandBinding(
    val id: String,
    val label: String = "",
    val detail: String = "",
    /** Default specs, already canonicalized. Empty means the command ships unbound. */
    val defaults: List<String> = emptyList(),
    val scope: String = KeyScope.both,
    /** Grouping hint for the generated file ("Canvas", "Editor", "Global", "Selection"). */
    val group: String = ""
)

@Serializable
data class CommandInventory(
    val commands: List<CommandBinding> = emptyList()
)

/** The resolved user overrides, pushed to every client whenever the file changes. */
@Serializable
data class UserKeybindings(
    /** Command id -> specs. An explicit empty list means "unbind this command". */
    val bindings: Map<String, List<String>> = emptyMap(),
    val path: String = "",
    val lastModified: Long = 0L,
    /** Human-readable parse problems, surfaced in the log and as a notification. */
    val problems: List<String> = emptyList(),
    /**
     * Command id -> the shortcut in force, override or default, for every command Field has seen this
     * run. [bindings] only carries what the file overrides, which is not enough to *describe* a
     * shortcut: commands that exist only for a selection (`connect`, `disconnect`) are usually not
     * registered in the client at the moment something asks. The cheatsheet reads this so it can name
     * their keys without the client having to restate the defaults.
     */
    val effective: Map<String, List<String>> = emptyMap()
)
