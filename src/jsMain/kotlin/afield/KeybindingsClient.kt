package afield

import kotlinx.browser.window
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * The client half of user-customizable keybindings.
 *
 * Every command declares its default shortcut as a spec string on `action.keys` (see [KeySpec]). This
 * layer is the only thing that turns a spec into an actual binding, deriving *both*:
 *
 *  - `action.keybindings` — the Monaco keybinding integers, used when an editor has focus, and
 *  - `action.canvasKeybinding` — the DOM predicate quickpick.js runs when the canvas has focus.
 *
 * Because both halves come from one spec, rebinding a command in `~/.afield/keybindings.yaml` moves
 * the shortcut everywhere at once. Commands that need extra conditions beyond the chord (for example
 * "copy, but not when there is a text selection") keep those on `action.canvasGuard`, which is ANDed
 * with the chord match rather than replacing it.
 *
 * A spec can be a two-key chord (`cmd+k cmd+s`). [ChordState] does the waiting, on the canvas and in
 * an editor alike: a capture-phase listener that holds the first key, shows it on screen, and waits
 * [KeySpec.CHORD_TIMEOUT_MS] for the second. The first key is hidden from every other listener while
 * it waits, so a command bound to the first key alone does not fire at once; it fires when the wait
 * runs out, or at once when a key arrives that completes no chord.
 */
object Keybindings {

    /** Command id -> user specs. An entry present with an empty list means "unbound". */
    private var userBindings: Map<String, List<String>> = emptyMap()

    /** Command id -> shortcut in force, for commands not registered right now. See [labelsFor]. */
    private var effective: Map<String, List<String>> = emptyMap()

    private var path: String = ""

    private var firstApplyDone = false

    /**
     * Chords that are matched by hand-written global keydown listeners rather than by the action
     * system, declared here so they still land in `keybindings.yaml` and stay rebindable. The
     * listeners ask [matches] instead of testing modifiers themselves.
     */
    private val virtualCommands: List<CommandBinding> = listOf(
        CommandBinding(
            id = "open-command-palette",
            label = "Command Palette",
            detail = "Open Field's command palette",
            defaults = listOf("cmd+shift+p", "f1"),
            group = "Global"
        ),
        CommandBinding(
            id = "open-anything",
            label = "Open Anything",
            detail = "Search boxes, properties and text across the document",
            defaults = listOf("cmd+p"),
            group = "Global"
        ),
        CommandBinding(
            id = "toggle-docs",
            label = "Toggle Documentation",
            detail = "Show or hide the documentation pane",
            defaults = listOf("cmd+/"),
            group = "Global"
        ),
        CommandBinding(
            id = "toggle-drawing-layer",
            label = "Toggle Drawing Layer",
            detail = "Draw and annotate over the canvas; mouse and keyboard go to the drawing while it is on",
            defaults = listOf("alt+d"),
            group = "Global"
        ),
        CommandBinding(
            id = "show-cheatsheet",
            label = "Show Cheatsheet",
            detail = "Show the keyboard shortcut cheatsheet overlay",
            defaults = listOf("?"),
            group = "Global"
        )
    ) + (1..9).flatMap { slot ->
        listOf(
            CommandBinding(
                id = "restore-bookmark-$slot",
                label = "Restore Bookmark $slot",
                detail = "Restore the canvas view saved in bookmark slot $slot",
                defaults = listOf("shift+f$slot"),
                group = "Bookmarks"
            ),
            CommandBinding(
                id = "save-bookmark-$slot",
                label = "Save Bookmark $slot",
                detail = "Save the current canvas view to bookmark slot $slot",
                defaults = listOf("cmd+shift+f$slot"),
                group = "Bookmarks"
            )
        )
    }

    private val virtualById: Map<String, CommandBinding> = virtualCommands.associateBy { it.id }

    // ── resolution ───────────────────────────────────────────────────────────────

    /**
     * Reads `action.keys`, which may be absent, a single spec string, or an array of them. A single
     * string is never split — `,` is itself a bindable key (`cmd+,`), so alternatives must be a list.
     */
    private fun declaredSpecs(keys: dynamic): List<String> {
        if (keys == null || keys == undefined) return emptyList()
        (keys as? String)?.let { single ->
            return listOfNotNull(single.trim().takeIf { it.isNotEmpty() })
        }
        val length = (keys.length as? Number)?.toInt() ?: return emptyList()
        return (0 until length).mapNotNull { (keys[it] as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } }
    }

    /**
     * The effective specs for [id]: the user's list when the file mentions the command at all
     * (including an explicit empty list, which unbinds it), otherwise [defaults].
     */
    fun resolve(id: String, defaults: List<String>): List<String> =
        userBindings[id] ?: defaults

    /**
     * Parsed chords, memoized. [matches] is on the keydown path — the bookmark listener alone asks
     * about 18 commands for every keystroke — so this must not re-parse specs each time.
     *
     * Keyed by the resolved specs rather than the command id: the same id can be asked about with
     * different fallback defaults (a global listener passes none, `installOnAction` passes the
     * action's own), and keying on the id would let the first caller poison the entry.
     */
    private val chordCache = mutableMapOf<String, List<List<KeyChord>>>()

    /** Every shortcut for [id] as a sequence: one chord for a plain shortcut, two for a chord. */
    private fun sequencesFor(id: String, defaults: List<String>): List<List<KeyChord>> {
        val specs = resolve(id, defaults)
        if (specs.isEmpty()) return emptyList()
        return chordCache.getOrPut(specs.joinToString("\u0000")) { specs.mapNotNull { KeySpec.parseSequence(it) } }
    }

    // ── monaco ───────────────────────────────────────────────────────────────────

    /** True once a chord has been installed while Monaco was still loading — see [awaitMonaco]. */
    private var installedBeforeMonaco = false

    private fun monacoReady(): Boolean {
        val monaco = window.asDynamic().monaco
        if (monaco == null || monaco == undefined) return false
        val keyCodes = monaco.KeyCode
        return keyCodes != null && keyCodes != undefined
    }

    private fun monacoKeyCode(chord: KeyChord): Int? {
        val monaco = window.asDynamic().monaco
        if (monaco == null || monaco == undefined) return null
        val keyCodes = monaco.KeyCode ?: return null
        for (name in KeySpec.monacoKeyCodeNames(chord)) {
            val value = keyCodes[name]
            val number = (value as? Number)?.toInt()
            if (number != null && number != 0) return number
        }
        return null
    }

    /**
     * The Monaco keybinding integer for a one- or two-chord [sequence]. Two chords go through
     * `monaco.KeyMod.chord`, which is also why Field stops at two.
     */
    fun monacoBinding(sequence: List<KeyChord>): Int? {
        val first = monacoBinding(sequence.firstOrNull() ?: return null) ?: return null
        if (sequence.size == 1) return first
        val second = monacoBinding(sequence[1]) ?: return null
        val chord = window.asDynamic().monaco?.KeyMod?.chord ?: return null
        return (chord(first, second) as? Number)?.toInt()
    }

    /** The Monaco keybinding integer for [chord], or null when Monaco has no code for that key. */
    fun monacoBinding(chord: KeyChord): Int? {
        val monaco = window.asDynamic().monaco
        if (monaco == null || monaco == undefined) return null
        val mods = monaco.KeyMod ?: return null
        val code = monacoKeyCode(chord) ?: return null
        var result = code
        if (chord.ctrlCmd) result = result or ((mods.CtrlCmd as? Number)?.toInt() ?: 0)
        if (chord.alt) result = result or ((mods.Alt as? Number)?.toInt() ?: 0)
        if (chord.shift) result = result or ((mods.Shift as? Number)?.toInt() ?: 0)
        return result
    }

    // ── DOM matching ─────────────────────────────────────────────────────────────

    private fun boolOf(value: dynamic): Boolean = (value as? Boolean) ?: false

    /** True when [event] is exactly [chord]. Modifiers must match exactly, so cmd+d ignores cmd+shift+d. */
    fun matchesChord(event: dynamic, chord: KeyChord): Boolean {
        if (event == null || event == undefined) return false
        val ctrlOrMeta = boolOf(event.ctrlKey) || boolOf(event.metaKey)
        if (chord.ctrlCmd != ctrlOrMeta) return false
        if (chord.alt != boolOf(event.altKey)) return false
        if (chord.shift != boolOf(event.shiftKey)) return false

        // `code` is layout-independent, which matters for Alt chords on macOS where `key` becomes a
        // dead/accented character (Alt+] reports key "’" but code "BracketRight").
        val domCode = KeySpec.domCode(chord)
        if (domCode != null && (event.code as? String) == domCode) return true

        val literal = KeySpec.literalChar(chord) ?: return false
        val key = (event.key as? String) ?: return false
        return key.lowercase() == literal.lowercase()
    }

    /**
     * True when [event] triggers one of [sequences]: a one-chord sequence by matching the event, a
     * two-chord one when [ChordState] has just marked the event as the key that completed it.
     *
     * An event [ChordState] is holding as the first key of a chord matches nothing, and an event that
     * completed a chord matches only that chord, so `cmd+s` alone does not also fire for the second
     * half of `cmd+k cmd+s`.
     */
    private fun matchesAny(event: dynamic, sequences: List<List<KeyChord>>): Boolean {
        if (event == null || event == undefined) return false
        if (event.__fieldChordHeld == true) return false
        val completed = event.__fieldChordCompleted
        if (completed != null && completed != undefined) {
            val done = completed as List<KeyChord>
            return sequences.any { it == done }
        }
        return sequences.any { it.size == 1 && matchesChord(event, it[0]) }
    }

    /** True when [event] triggers command [id]. [defaults] applies when the user has not rebound it. */
    fun matches(event: dynamic, id: String, defaults: List<String> = emptyList()): Boolean {
        val fallback = if (defaults.isNotEmpty()) defaults else (virtualById[id]?.defaults ?: emptyList())
        return matchesAny(event, sequencesFor(id, fallback))
    }

    // ── applying to an action ────────────────────────────────────────────────────

    /**
     * Narrows [declared] to canvas when any chord would type a character in a text field.
     *
     * A bare letter must not reach Monaco: `new-box` is `n`, and an editor binding for `n` swallows
     * the keystroke, so the letter cannot be typed at all. The narrowing lives here rather than in a
     * `keyScope: 'canvas'` on each command because it has to hold for user-authored specs too — the
     * file lets anyone bind any command to a bare letter.
     *
     * It overrides even an explicit `keyScope: 'editor'`. That combination is contradictory, and of
     * the two ways to resolve it, keeping the letter typable is the only tolerable one.
     */
    private fun effectiveScope(declared: String, sequences: List<List<KeyChord>>): String =
        // A chord whose *first* key types a character is canvas-only too; its second key may be bare
        // (`cmd+k s`), since by then Monaco is already waiting for a chord and nothing is typed.
        if (sequences.any { it.isNotEmpty() && KeySpec.typesACharacter(it[0]) }) KeyScope.canvas else declared

    /**
     * Rewrites [action]'s `keybindings` and `canvasKeybinding` from its `keys` default and any user
     * override. Idempotent — `keys` is never mutated, so this can run again after every reload.
     */
    fun installOnAction(action: dynamic) {
        if (action == null || action == undefined) return
        val id = (action.id as? String) ?: return

        val defaults = declaredSpecs(action.keys)
        val sequences = sequencesFor(id, defaults)
        val scope = effectiveScope(KeyScope.normalize(action.keyScope as? String), sequences)

        action.keybindings = if (scope == KeyScope.canvas) {
            emptyArray<Int>()
        } else {
            // Monaco may not have loaded yet — actions are registered early. Remember that, so
            // [awaitMonaco] can redo the registrations once the key codes exist; otherwise this
            // action would keep the empty binding array for the rest of the session.
            if (sequences.isNotEmpty() && !monacoReady()) installedBeforeMonaco = true
            sequences.mapNotNull { monacoBinding(it) }.toTypedArray()
        }

        val guard = action.canvasGuard
        val hasGuard = guard != null && guard != undefined
        val canvasLive = scope != KeyScope.editor && sequences.isNotEmpty()
        ChordState.register(id, sequences, canvas = canvasLive, editor = scope != KeyScope.canvas)
        action.canvasKeybinding = if (!canvasLive) {
            { _: dynamic -> false }
        } else {
            { event: dynamic ->
                matchesAny(event, sequences) && (!hasGuard || guard(event) == true)
            }
        }
    }

    // ── inventory reporting ─────────────────────────────────────────────────────

    private fun bindingsFrom(action: dynamic, group: String): CommandBinding? {
        val id = (action?.id as? String)?.takeIf { it.isNotEmpty() } ?: return null
        val defaults = declaredSpecs(action.keys).mapNotNull { KeySpec.canonicalize(it) }
        // Some palette labels describe the current selection — "Connect 'a' to 'b'", "Solo Next
        // Layer: sketches". Those are right in the palette and wrong in `keybindings.yaml`, where the
        // label becomes a comment that outlives the selection it was written from, so a command with
        // a moving label declares a fixed `keyLabel` for the file to use instead.
        val label = (action.keyLabel as? String)?.takeIf { it.isNotBlank() }
            ?: (action.label as? String)
            ?: id
        return CommandBinding(
            id = id,
            label = label,
            detail = (action.detail as? String) ?: "",
            defaults = defaults,
            // The effective scope, so a bare-letter command is written `#scope canvas` rather than
            // claiming a reach into the editor that [effectiveScope] has already taken away.
            scope = effectiveScope(
                KeyScope.normalize(action.keyScope as? String),
                defaults.mapNotNull { KeySpec.parseSequence(it) }
            ),
            group = group
        )
    }

    /** Every command the client knows about, for seeding and topping up `keybindings.yaml`. */
    fun inventory(): CommandInventory {
        val found = mutableMapOf<String, CommandBinding>()

        virtualCommands.forEach { found[it.id] = it }

        val all = window.asDynamic().allActions
        if (all != null && all != undefined) {
            val keys = js("Object.keys")(all) as Array<String>
            for (bucket in keys) {
                val actions = all[bucket] ?: continue
                val length = (actions.length as? Number)?.toInt() ?: continue
                for (i in 0 until length) {
                    bindingsFrom(actions[i], "Canvas")?.let { found.getOrPut(it.id) { it } }
                }
            }
        }

        val editor = window.asDynamic().globalEditor
        val metadata = if (editor != null && editor != undefined) editor.afieldActionMetadata else null
        if (metadata != null && metadata != undefined) {
            val ids = js("Object.keys")(metadata) as Array<String>
            for (id in ids) {
                bindingsFrom(metadata[id], "Editor")?.let { found.getOrPut(it.id) { it } }
            }
        }

        return CommandInventory(commands = found.values.sortedWith(compareBy({ it.group }, { it.id })))
    }

    // ── human-readable labels ───────────────────────────────────────────────────

    private fun isMac(): Boolean = window.navigator.platform.contains("Mac", ignoreCase = true)

    /**
     * The shortcut(s) currently bound to [id], as platform-appropriate labels like `Cmd+Shift+P`.
     * An empty list means the command genuinely has no shortcut; **null** means Field cannot say —
     * the command is neither registered right now nor mentioned in the file, which is the normal
     * state of a selection-scoped command like `connect` before anything has been selected. Callers
     * must not render null as "unbound": it would claim a key is dead when it is merely dormant.
     */
    fun labelsFor(id: String): List<String>? {
        val declared = declaredSpecs(actionKeysFor(id))
        val specs = when {
            declared.isNotEmpty() -> resolve(id, declared)
            virtualById.containsKey(id) -> resolve(id, virtualById[id]!!.defaults)
            // Not registered at the moment. The server's `effective` map covers both commands it has
            // seen this run and any the file overrides, with the override already applied — so it is
            // the answer, not a default to resolve against.
            effective.containsKey(id) -> effective.getValue(id)
            userBindings.containsKey(id) -> userBindings.getValue(id)
            else -> return null
        }
        return specs.mapNotNull { KeySpec.parseSequence(it) }.map { KeySpec.labelSequence(it, isMac()) }
    }

    /** The `keys` default declared by whichever registration owns [id], if it is registered now. */
    private fun actionKeysFor(id: String): dynamic {
        val all = window.asDynamic().allActions
        if (all != null && all != undefined) {
            val buckets = js("Object.keys")(all) as Array<String>
            for (bucket in buckets) {
                val actions = all[bucket] ?: continue
                val length = (actions.length as? Number)?.toInt() ?: continue
                for (i in 0 until length) {
                    if ((actions[i]?.id as? String) == id) return actions[i].keys
                }
            }
        }
        val editor = window.asDynamic().globalEditor
        val metadata = if (editor != null && editor != undefined) editor.afieldActionMetadata else null
        if (metadata != null && metadata != undefined) {
            val action = metadata[id]
            if (action != null && action != undefined) return action.keys
        }
        return null
    }

    private var reportedSignature: String = ""
    private var reportTimer: Int? = null

    /**
     * Reports the inventory to the server, debounced and deduplicated. Called from `updateActions`,
     * which fires often; the server only needs to hear about it when the command set actually changes.
     */
    fun scheduleInventoryReport() {
        reportTimer?.let { window.clearTimeout(it) }
        reportTimer = window.setTimeout({
            reportTimer = null
            val inventory = inventory()
            val signature = inventory.commands.joinToString("|") { "${it.id}:${it.defaults.joinToString(",")}:${it.scope}" }
            if (signature == reportedSignature) return@setTimeout
            reportedSignature = signature
            send(Message("report-command-inventory").payload(inventory))
        }, 1200)
    }

    // ── reload ───────────────────────────────────────────────────────────────────

    /**
     * Tears down and rebuilds every keybinding registration. Monaco caches an action's keybinding at
     * registration time, so both a reload and a late-arriving Monaco need this — merely re-resolving
     * the specs in place would have no effect.
     */
    private fun reregisterAll() {
        val w = window.asDynamic()
        // Every action re-registers its chords below; virtual commands are re-read here.
        ChordState.clear()
        registerVirtualChords()

        // Field commands (canvas + editor palette actions).
        if (w.resetActionRegistrations != undefined) w.resetActionRegistrations()
        if (w.updateActions != undefined) w.updateActions()

        // The editor's own core actions, which register outside the action system.
        val editor = w.globalEditor
        val monaco = w.monaco
        if (editor != null && editor != undefined && monaco != null && monaco != undefined) {
            val disposables = editor.afieldCoreActionDisposables
            if (disposables != null && disposables != undefined) {
                val length = (disposables.length as? Number)?.toInt() ?: 0
                for (i in 0 until length) {
                    val handle = disposables[i]
                    if (handle != null && handle != undefined && handle.dispose != undefined) handle.dispose()
                }
            }
            editor.afieldCoreActionsRegistered = false
            monacoRegisterActions(editor, monaco)
        }

        // The cheatsheet spells the shortcuts out, so it has to be redrawn whenever they move.
        if (w.renderCheatsheetKeys != undefined) w.renderCheatsheetKeys()
    }

    /**
     * Actions are registered during startup, often before Monaco has finished loading — and a chord
     * cannot be turned into a Monaco keybinding integer until `monaco.KeyCode` exists. Watch for
     * Monaco arriving and redo the registrations once, so those commands are not left with an empty
     * keybinding array for the rest of the session.
     */
    private fun awaitMonaco() {
        if (monacoReady() && !installedBeforeMonaco) return
        var elapsed = 0
        fun tick() {
            if (monacoReady()) {
                if (installedBeforeMonaco) {
                    installedBeforeMonaco = false
                    reregisterAll()
                }
                return
            }
            elapsed += 150
            if (elapsed > 60_000) return
            window.setTimeout(::tick, 150)
        }
        window.setTimeout(::tick, 150)
    }

    /**
     * Installs [next] everywhere. [announceSuccess] is for the "reloaded" notification only — a file
     * with problems in it says so whatever push it arrives on, including the first one at startup,
     * because a shortcut that silently does nothing is the hardest kind of mistake to find.
     */
    fun apply(next: UserKeybindings, announceSuccess: Boolean) {
        userBindings = next.bindings
        effective = next.effective
        chordCache.clear()
        path = next.path
        window.asDynamic().fieldUserKeybindingsPath = next.path

        reregisterAll()
        // A reload can land before Monaco is up (the server pushes on connect); keep watching.
        awaitMonaco()

        val problems = next.problems
        if (problems.isNotEmpty()) {
            problems.take(3).forEach { notify(it, "error") }
        } else if (announceSuccess) {
            notify("Reloaded keybindings from ${next.path}", "command")
        }
    }

    /** The two-key chords of the hand-matched global commands, which have no action to install on. */
    private fun registerVirtualChords() {
        for (command in virtualCommands) {
            ChordState.register(command.id, sequencesFor(command.id, command.defaults), canvas = true, editor = true, virtual = true)
        }
    }

    fun install() {
        ChordState.install()
        registerVirtualChords()
        val bridge = js("{}")
        bridge.matches = { event: dynamic, id: String -> matches(event, id) }
        bridge.installOnAction = { action: dynamic -> installOnAction(action) }
        bridge.scheduleInventoryReport = { scheduleInventoryReport() }
        bridge.resolve = { id: String -> resolve(id, virtualById[id]?.defaults ?: emptyList()).toTypedArray() }
        // null (not an empty array) when Field cannot say — see labelsFor.
        bridge.labels = { id: String -> labelsFor(id)?.toTypedArray() }
        // For regressions and the curious: the first key being held, as a label, or null.
        bridge.pendingChord = { ChordState.pendingLabel() }
        window.asDynamic().fieldKeybindings = bridge

        awaitMonaco()

        handlers.put("set-user-keybindings") { message, _ ->
            val next = Json.decodeFromJsonElement<UserKeybindings>(message.payload!!)
            // The first push on connect is just Field catching the client up; only a genuine
            // file change (or an explicit reload) is worth a *success* notification.
            apply(next, announceSuccess = firstApplyDone)
            firstApplyDone = true
            Handled.yes
        }
    }
}

/** Asks the server to re-read `keybindings.yaml` now and push it to every client. */
fun reloadUserKeybindings() {
    send(Message("reload-user-keybindings"))
}

/**
 * The waiting half of a two-key chord.
 *
 * One capture-phase `keydown` listener on `window`, installed before anything else listens, so it
 * sees every key before Monaco or any other listener does. When a key is the first chord of some
 * registered two-key sequence it is *held*: marked so that [Keybindings.matches] and every
 * `canvasKeybinding` ignore it, stopped, and shown in a small hint. Then, for the next non-modifier
 * key:
 *
 *  - it completes a sequence: a palette or editor action runs at once, and a hand-matched global
 *    command (the palette, the cheatsheet, bookmarks) gets the event marked with that sequence, so its
 *    own listener's [Keybindings.matches] says yes. Nothing bound to the key alone runs;
 *  - it completes nothing: the held key fires first, so a command bound to it alone still runs, and
 *    then the new key goes on as if nothing had been waiting;
 *  - Escape: the wait is cancelled and the Escape is swallowed.
 *
 * If no second key comes within [KeySpec.CHORD_TIMEOUT_MS], the held key fires. Outside an editor that
 * is a synthetic `KeyboardEvent` re-dispatched on the element that had focus, which every listener in
 * Field reads the same way (by `key`, `code` and the modifier flags) and which this listener lets
 * through. Monaco does not read synthetic events, so inside an editor the action bound to the held key
 * is run directly instead. Monaco has the chords too (`KeyMod.chord`), but never sees their keys while
 * this listener holds them: its own resolver fires a bound prefix at once, which is the behaviour this
 * object exists to replace.
 *
 * In a text field or code editor only chords whose first key has Cmd or Alt are held, and only for
 * commands that are live there; a bare first key in an editor is typing.
 */
internal object ChordState {

    private class Entry(
        val id: String,
        val sequence: List<KeyChord>,
        val virtual: Boolean,
        val canvas: Boolean,
        val editor: Boolean,
    )

    private val byId = mutableMapOf<String, List<Entry>>()

    private var held: dynamic = null
    private var heldTarget: dynamic = null
    private var heldEditable = false
    private var candidates: List<Entry> = emptyList()
    private var timer: Int? = null
    private var hint: org.w3c.dom.HTMLElement? = null

    fun clear() = byId.clear()

    fun register(id: String, sequences: List<List<KeyChord>>, canvas: Boolean, editor: Boolean, virtual: Boolean = false) {
        if (sequences.isEmpty() || (!canvas && !editor)) byId.remove(id)
        else byId[id] = sequences.map { Entry(id, it, virtual, canvas, editor) }
    }

    fun pendingLabel(): String? =
        if (held == null || held == undefined) null else candidates.firstOrNull()?.let { KeySpec.label(it.sequence[0], isMac()) }

    private fun isMac(): Boolean = window.navigator.platform.contains("Mac", ignoreCase = true)

    private fun isModifierKey(event: dynamic): Boolean =
        when (event.key as? String) { "Shift", "Control", "Alt", "Meta", "OS", "CapsLock", "AltGraph" -> true; else -> false }

    private fun isEditable(target: dynamic): Boolean {
        val el: dynamic = if (target != null && target != undefined && target.nodeType == 1) target else kotlinx.browser.document.activeElement
        if (el == null || el == undefined) return false
        val tag = ((el.tagName as? String) ?: "").lowercase()
        if (tag == "input" || tag == "textarea" || tag == "select") return true
        if (el.isContentEditable == true) return true
        return el.closest != undefined && el.closest(".monaco-editor, .afield-palette, .quick-input-widget") != null
    }

    /** The action registered as [id] right now, or null. */
    private fun actionFor(id: String): dynamic {
        val last = window.asDynamic().lastActions
        if (last == null || last == undefined) return null
        val n = (last.length as? Number)?.toInt() ?: 0
        for (i in 0 until n) if ((last[i]?.id as? String) == id) return last[i]
        return null
    }

    /** Entries live where [editable] says the key is going. Actions only while they are registered. */
    private fun live(editable: Boolean): List<Entry> =
        byId.values.flatten().filter { e ->
            (e.virtual || actionFor(e.id) != null) &&
                if (editable) e.editor && (e.sequence[0].ctrlCmd || e.sequence[0].alt) else e.canvas
        }

    fun install() {
        window.addEventListener("keydown", { event: dynamic -> onKeyDown(event) }, true)
    }

    private fun onKeyDown(event: dynamic) {
        if (event.__fieldChordReplay == true) return
        if (isModifierKey(event)) return

        if (held != null) {
            val completed = candidates.firstOrNull { Keybindings.matchesChord(event, it.sequence[1]) }
            val first = held
            val target = heldTarget
            val editable = heldEditable
            cancelWait()
            when {
                completed != null -> {
                    // Not to the browser either: `cmd+k cmd+s` must not also save the page.
                    event.__fieldChordCompleted = completed.sequence
                    event.preventDefault()
                    if (!completed.virtual) {
                        event.stopImmediatePropagation()
                        runAction(completed.id, event, editable)
                    }
                }
                (event.key as? String) == "Escape" -> {
                    event.preventDefault(); event.stopImmediatePropagation()
                }
                else -> fireHeld(first, target, editable)
            }
            return
        }

        val editable = isEditable(event.target)
        val entries = live(editable)
        val starts = entries.filter { it.sequence.size == 2 && Keybindings.matchesChord(event, it.sequence[0]) }
        if (starts.isEmpty()) return
        event.__fieldChordHeld = true
        event.preventDefault()
        event.stopImmediatePropagation()
        held = event
        heldTarget = event.target
        heldEditable = editable
        candidates = starts
        showHint(KeySpec.label(starts[0].sequence[0], isMac()))
        timer = window.setTimeout({
            val first = held
            val target = heldTarget
            val wasEditable = heldEditable
            cancelWait()
            if (first != null) fireHeld(first, target, wasEditable)
        }, KeySpec.CHORD_TIMEOUT_MS)
    }

    private fun runAction(id: String, event: dynamic, editable: Boolean) {
        val action = actionFor(id) ?: return
        val guard = action.canvasGuard
        if (!editable && guard != null && guard != undefined && guard(event) != true) return
        action.run()
    }

    /** The held key, late: as a replayed event outside an editor, as a direct run inside one. */
    private fun fireHeld(original: dynamic, target: dynamic, editable: Boolean) {
        if (editable) {
            val single = live(true).firstOrNull { !it.virtual && it.sequence.size == 1 && Keybindings.matchesChord(original, it.sequence[0]) }
            if (single != null) {
                runAction(single.id, original, true)
                return
            }
        }
        replay(original, target)
    }

    private fun cancelWait() {
        timer?.let { window.clearTimeout(it) }
        timer = null
        held = null
        heldTarget = null
        heldEditable = false
        candidates = emptyList()
        hint?.style?.display = "none"
    }

    /** Re-dispatches the held key, so a command bound to it alone runs after all. */
    private fun replay(original: dynamic, target: dynamic) {
        val init = js("{}")
        init.key = original.key
        init.code = original.code
        init.ctrlKey = original.ctrlKey
        init.metaKey = original.metaKey
        init.altKey = original.altKey
        init.shiftKey = original.shiftKey
        init.bubbles = true
        init.cancelable = true
        val KeyboardEventCtor = window.asDynamic().KeyboardEvent
        val copy = js("new KeyboardEventCtor('keydown', init)")
        copy.__fieldChordReplay = true
        val dest: dynamic = if (target != null && target != undefined && target.isConnected == true) target else kotlinx.browser.document.body
        dest.dispatchEvent(copy)
    }

    private fun showHint(label: String) {
        val doc = kotlinx.browser.document
        val el = hint ?: (doc.createElement("div") as org.w3c.dom.HTMLElement).also {
            it.id = "field-chord-hint"
            it.setAttribute(
                "style",
                "position:fixed;left:50%;bottom:28px;transform:translateX(-50%);z-index:100000;" +
                    "padding:6px 12px;border-radius:4px;background:rgba(20,20,20,0.92);color:#eee;" +
                    "font:12px/1.4 var(--field-mono-font, monospace);pointer-events:none;" +
                    "box-shadow:0 2px 8px rgba(0,0,0,0.4)",
            )
            doc.body?.appendChild(it)
            hint = it
        }
        el.textContent = "$label … (waiting for the second key)"
        el.style.display = "block"
    }
}
