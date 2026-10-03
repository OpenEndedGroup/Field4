package afield

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

private const val DEBUG_BOOKMARKS = false
private fun bookmarkDebug(message: String) {
    if (DEBUG_BOOKMARKS) console.info(message)
}

/**
 * Bookmark System - sole owner of the bookmark feature (this single file).
 *
 * Manages 9 numbered bookmark slots (1-9) with keyboard shortcuts:
 * - Shift+F1-F9: Restore bookmark
 * - Shift+Ctrl/Cmd+F1-F9: Save bookmark
 *
 * Slots are stored as JSON-encoded [BookmarkData] on the root box's "bookmarks"
 * property, which rides the generic property machinery for session sync and disk
 * persistence (see the two "bookmarks" entries in Box.uiResyncProperties /
 * Box.ioPersistentProperties in src/jvmMain/kotlin/afield/Boxes.kt). Persistence to
 * metadata.edn happens via the server's autosave loop, not an explicit save keystroke.
 *
 * Public entry point: [initializeBookmarkSystem] (called from Main.kt). Exported to JS:
 * [saveBookmarkSlot]/[restoreBookmarkSlot]/[initializeBookmarkSystem]. Note box uid is a
 * JS dynamic property, not an HTML attribute, so lookups go through [findBoxByUid]
 * (iterating .box elements) rather than a CSS attribute selector.
 *
 * User-facing reference: public/docs/moving_around_the_canvas.md (its Bookmarks section).
 */

@Serializable
data class Bookmark(
    val id: String,
    val name: String,
    val timestamp: Double,
    val scrollPosition: ScrollPosition? = null,
    val selectedBox: String? = null,
    val editedProperty: String? = null,
    val cursorLine: Int? = null,
    val cursorColumn: Int? = null,
    val nestingState: Map<String, NestingInfo>? = null,
    /** box uid -> output channel -> open. Channels absent here are left alone on restore. */
    val outputAreaState: Map<String, Map<String, Boolean>>? = null,
    /** box uid -> output channel -> size, for the panels that were open. */
    val outputAreaSizes: Map<String, Map<String, PanelSize>>? = null,
    /** Restore the canvas pan. */
    val restoreView: Boolean = true,
    /** Restore the selected box, the property open in Monaco and the cursor. */
    val restoreSelection: Boolean = true,
    /** Also close every output panel the bookmark did not record (not just recorded-closed ones). */
    val closeUnrecordedOutputs: Boolean = false
)

@Serializable
data class ScrollPosition(
    val x: Double,
    val y: Double,
    /** Canvas zoom; absent in bookmarks saved before zoom was recorded, which keep the current zoom. */
    val zoom: Double? = null
)

@Serializable
data class PanelSize(
    val width: Double,
    val height: Double
)

@Serializable
data class NestingInfo(
    val open: Boolean
)

@Serializable
data class BookmarkData(
    val version: Int = 1,
    val bookmarks: List<Bookmark>
)

private val json = Json { ignoreUnknownKeys = true }
private var cachedBookmarksJson: String? = null

private fun findBoxByUid(uid: String): HTMLElement? {
    val boxes = document.body?.getElementsByClassName("box")?.asList() ?: return null
    return boxes.firstOrNull { it.asDynamic().uid == uid } as? HTMLElement
}

/**
 * Captures the current scroll/pan position of the main canvas
 */
fun captureScrollPosition(): ScrollPosition {
    return ScrollPosition(
        x = mainTranslation[0],
        y = mainTranslation[1],
        zoom = mainZoom
    )
}

/**
 * Captures the ID of the currently selected box
 */
fun captureSelectedBox(): String? {
    if (selected.size == 1) {
        val selectedBox = selected.iterator().next()
        return selectedBox.asDynamic().uid as? String
    }
    return null
}

/**
 * Captures the currently edited property name
 */
fun captureEditedProperty(): String? {
    return selectedProperty.takeIf { it.isNotEmpty() }
}

/**
 * Captures the cursor position in the Monaco editor
 */
fun captureCursorPosition(): Pair<Int, Int>? {
    return try {
        val w = js("window")
        if (w.globalEditor != null && w.globalEditor != undefined) {
            val position = w.globalEditor.getPosition()
            if (position != null && position != undefined) {
                Pair(position.lineNumber as Int, position.column as Int)
            } else null
        } else null
    } catch (e: dynamic) {
        null
    }
}

/**
 * Captures the open/closed state of all nested boxes
 */
fun captureNestingState(): Map<String, NestingInfo> {
    val state = mutableMapOf<String, NestingInfo>()

    try {
        val boxes = document.body?.getElementsByClassName("box")?.asList() ?: emptyList()
        boxes.forEach { rawBox ->
            val box = rawBox as? HTMLElement ?: return@forEach
            val nestedArea = box.querySelector(".disclosure-nested") as? HTMLElement
            if (nestedArea != null) {
                val uid = box.asDynamic().uid as? String
                if (uid != null) {
                    val isOpen = nestedArea.style.display != "none"
                    state[uid] = NestingInfo(isOpen)
                }
            }
        }
    } catch (e: dynamic) {
        console.warn("Error capturing nesting state:", e)
    }

    return state
}

private fun disclosureApiFor(box: HTMLElement): dynamic {
    val contain = box.querySelector(".outputArea .disclosure-group") ?: return null
    val di = js("window.disclosureInterface") ?: return null
    return di(contain)
}

/**
 * Captures the open/closed state of every output channel of every box. The "nested"
 * disclosure is left to [captureNestingState].
 */
fun captureOutputAreaState(): Map<String, Map<String, Boolean>> {
    val state = mutableMapOf<String, Map<String, Boolean>>()
    try {
        val boxes = document.body?.getElementsByClassName("box")?.asList() ?: emptyList()
        boxes.forEach { rawBox ->
            val box = rawBox as? HTMLElement ?: return@forEach
            val uid = box.asDynamic().uid as? String ?: return@forEach
            val inter = disclosureApiFor(box) ?: return@forEach
            val ids = (inter.ids() as Array<String>).filter { it != "nested" }
            if (ids.isEmpty()) return@forEach
            state[uid] = ids.associateWith { inter.isOpen(it) as Boolean }
        }
    } catch (e: dynamic) {
        console.warn("Error capturing output area state:", e)
    }
    return state
}

/** The size of every open output panel, so a restore can put it back. */
fun captureOutputAreaSizes(): Map<String, Map<String, PanelSize>> {
    val sizes = mutableMapOf<String, Map<String, PanelSize>>()
    try {
        val boxes = document.body?.getElementsByClassName("box")?.asList() ?: emptyList()
        boxes.forEach { rawBox ->
            val box = rawBox as? HTMLElement ?: return@forEach
            val uid = box.asDynamic().uid as? String ?: return@forEach
            val inter = disclosureApiFor(box) ?: return@forEach
            val open = (inter.ids() as Array<String>).filter { it != "nested" && inter.isOpen(it) as Boolean }
            val channels = open.mapNotNull { channel ->
                val size = inter.getSize(channel) ?: return@mapNotNull null
                channel to PanelSize((size.width as Number).toDouble(), (size.height as Number).toDouble())
            }.toMap()
            if (channels.isNotEmpty()) sizes[uid] = channels
        }
    } catch (e: dynamic) {
        console.warn("Error capturing output area sizes:", e)
    }
    return sizes
}

/** Resizes the panels a bookmark recorded a size for; others keep theirs. */
fun restoreOutputAreaSizes(sizes: Map<String, Map<String, PanelSize>>) {
    try {
        sizes.forEach { (boxId, channels) ->
            val box = findBoxByUid(boxId) ?: return@forEach
            val inter = disclosureApiFor(box) ?: return@forEach
            val existing = (inter.ids() as Array<String>).toSet()
            channels.forEach { (channel, size) ->
                if (channel in existing) inter.setSize(channel, size.width, size.height)
            }
        }
    } catch (e: dynamic) {
        console.warn("Error restoring output area sizes:", e)
    }
}

/**
 * Opens or closes the recorded output channels. Channels and boxes the bookmark does not
 * mention, and recorded channels that no longer exist, are left untouched.
 */
fun restoreOutputAreaState(state: Map<String, Map<String, Boolean>>, closeUnrecorded: Boolean = false) {
    try {
        if (closeUnrecorded) {
            val boxes = document.body?.getElementsByClassName("box")?.asList() ?: emptyList()
            boxes.forEach { rawBox ->
                val box = rawBox as? HTMLElement ?: return@forEach
                val uid = box.asDynamic().uid as? String ?: return@forEach
                val inter = disclosureApiFor(box) ?: return@forEach
                val recorded = state[uid].orEmpty()
                (inter.ids() as Array<String>).forEach { channel ->
                    if (channel != "nested" && channel !in recorded && inter.isOpen(channel) as Boolean) {
                        inter.close(channel)
                    }
                }
            }
        }
        state.forEach { (boxId, channels) ->
            val box = findBoxByUid(boxId) ?: return@forEach
            val inter = disclosureApiFor(box) ?: return@forEach
            val existing = (inter.ids() as Array<String>).toSet()
            channels.forEach { (channel, open) ->
                if (channel !in existing || channel == "nested") return@forEach
                if (open == (inter.isOpen(channel) as Boolean)) return@forEach
                if (open) {
                    (box.querySelector(".outputArea") as? HTMLElement)?.style?.visibility = "visible"
                    inter.open(channel)
                } else {
                    inter.close(channel)
                }
            }
        }
    } catch (e: dynamic) {
        console.warn("Error restoring output area state:", e)
    }
}

/**
 * Captures complete current state as a bookmark
 */
fun captureBookmark(bookmarkNumber: Int): Bookmark {
    val cursor = captureCursorPosition()
    val timestamp = js("Date.now()") as Double

    return Bookmark(
        id = "bookmark_$bookmarkNumber",
        name = "Bookmark $bookmarkNumber",
        timestamp = timestamp,
        scrollPosition = captureScrollPosition(),
        selectedBox = captureSelectedBox(),
        editedProperty = captureEditedProperty(),
        cursorLine = cursor?.first,
        cursorColumn = cursor?.second,
        nestingState = captureNestingState(),
        outputAreaState = captureOutputAreaState(),
        outputAreaSizes = captureOutputAreaSizes()
    )
}

/**
 * Restores the scroll/pan position of the main canvas
 */
fun restoreScrollPosition(position: ScrollPosition) {
    try {
        if (position.zoom != null && position.zoom != mainZoom) {
            panViewportForDrawingLayer(position.x, position.y, position.zoom)
            return
        }
        mainTranslation = listOf(position.x, position.y)
        drawingLayerViewportChanged(position.x, position.y)
        mainCanvas.style.transform = mainCanvasTransform(position.x, position.y)
        updateBackgroundGrid(position.x, position.y, panning = false)
        canvasGestures.rebuildConnectionGroup()
    } catch (e: dynamic) {
        console.warn("Error restoring scroll position:", e)
    }
}

/**
 * Selects and focuses a specific box
 */
fun restoreSelectedBox(boxId: String) {
    try {
        val box = findBoxByUid(boxId)
        if (box != null) {
            // Clear current selection
            val currentSelected = selected.toList()
            currentSelected.forEach { deselectBox(it) }

            // Select the target box
            selectBox(box)

            // Scroll into view
            box.scrollIntoView(js("{ behavior: 'smooth', block: 'center' }"))
        }
    } catch (e: dynamic) {
        console.warn("Error restoring selected box:", e)
    }
}

/**
 * Opens the Monaco editor for a specific property of a box
 */
fun restoreEditedProperty(boxId: String, property: String) {
    try {
        restoreSelectedBox(boxId)
        selectedProperty = property
        // Tell Monaco runtime not to re-apply its remembered view state for the upcoming open
        js("window").suppressNextEditorStateRestore = true
        showMonaco()
        js("window").selectionContextChanged?.let { fn ->
            try {
                fn()
            } catch (_: dynamic) {}
        }
    } catch (e: dynamic) {
        console.warn("Error restoring edited property:", e)
    }
}

/**
 * Restores cursor position in the Monaco editor
 */
fun restoreCursorPosition(line: Int, column: Int) {
    try {
        val w = js("window")
        if (w.globalEditor != null && w.globalEditor != undefined) {
            w.globalEditor.setPosition(JSON.parse("{ \"lineNumber\": $line, \"column\": $column }"))
            w.globalEditor.revealLineInCenter(line)
            w.globalEditor.focus()
            // Persist this as the new remembered state so Monaco doesn't bounce back
            val vs = w.globalEditor.saveViewState()
            if (vs != null && vs != undefined) {
                w.globalEditor.restoreViewState(vs)
            }
        }
    } catch (e: dynamic) {
        console.warn("Error restoring cursor position:", e)
    }
}

/**
 * Restores the open/closed state of nested boxes
 */
fun restoreNestingState(state: Map<String, NestingInfo>) {
    try {
        state.forEach { (boxId, boxState) ->
            val box = findBoxByUid(boxId)
            if (box != null) {
                val nestedArea = box.querySelector(".disclosure-nested") as? HTMLElement
                if (nestedArea != null) {
                    nestedArea.style.display = if (boxState.open) "" else "none"
                }
            }
        }
    } catch (e: dynamic) {
        console.warn("Error restoring nesting state:", e)
    }
}

/**
 * Restores complete state from a bookmark
 */
fun restoreBookmark(bookmark: Bookmark) {
    try {
        bookmarkDebug("restoring bookmark ${bookmark.name} at ${bookmark.timestamp}")
        bookmarkDebug(bookmark.toString())

        // Restore scroll position
        if (bookmark.restoreView) bookmark.scrollPosition?.let { restoreScrollPosition(it) }

        // Restore nesting state first (before selecting boxes)
        bookmark.nestingState?.let { restoreNestingState(it) }
        restoreOutputAreaSizes(bookmark.outputAreaSizes.orEmpty())
        restoreOutputAreaState(bookmark.outputAreaState.orEmpty(), bookmark.closeUnrecordedOutputs)

        if (!bookmark.restoreSelection) {
            notify("Restored ${bookmark.name}", "bookmarks")
            return
        }

        // Restore selected box and edited property
        bookmark.selectedBox?.let { boxId ->
            if (bookmark.editedProperty != null) {
                bookmarkDebug("restoring property ${bookmark.editedProperty} in box $boxId")
                restoreEditedProperty(boxId, bookmark.editedProperty)
            } else {
                bookmarkDebug("restoring selection in box $boxId")
                restoreSelectedBox(boxId)
            }
        }

        // Restore cursor position (after opening editor)
        if (bookmark.cursorLine != null && bookmark.cursorColumn != null) {
            // Wait longer than Monaco's own view-state restore to avoid flicker back
            window.setTimeout({
                bookmarkDebug("restoring line/col ${bookmark.cursorLine}:${bookmark.cursorColumn}")
                restoreCursorPosition(bookmark.cursorLine, bookmark.cursorColumn)
            }, 500)
        }

        notify("Restored ${bookmark.name}", "bookmarks")
    } catch (e: dynamic) {
        console.warn("Error restoring bookmark:", e)
        notify("Error restoring bookmark", "error")
    }
}

/**
 * Loads bookmarks from the root box property
 */
fun loadBookmarks(): BookmarkData {
    return try {
        val rootBox = findBoxByUid("root")
        val bookmarksJson = (rootBox?.asDynamic()?.bookmarks as? String) ?: cachedBookmarksJson

        if (bookmarksJson != null && bookmarksJson.isNotEmpty()) {
            json.decodeFromString<BookmarkData>(bookmarksJson)
        } else {
            BookmarkData(version = 1, bookmarks = emptyList())
        }
    } catch (e: dynamic) {
        console.warn("Error loading bookmarks:", e)
        BookmarkData(version = 1, bookmarks = emptyList())
    }
}

/**
 * Saves bookmarks to the root box property
 */
fun saveBookmarks(bookmarkData: BookmarkData) {
    try {
        val bookmarksJson = json.encodeToString(BookmarkData.serializer(), bookmarkData)

        cachedBookmarksJson = bookmarksJson

        // Keep the DOM copy in sync so subsequent loads see the freshly saved value
        val rootBox = findBoxByUid("root")
        rootBox?.asDynamic()?.bookmarks = bookmarksJson



        send(
            Message("set-string").payload(
                Set_string("root", "bookmarks", bookmarksJson)
            )
        )
    } catch (e: dynamic) {
        console.warn("Error saving bookmarks:", e)
        notify("Error saving bookmarks", "error")
    }
}

/**
 * Saves the current state as a bookmark (1-9)
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun saveBookmarkSlot(bookmarkNumber: Int) {
    if (bookmarkNumber < 1 || bookmarkNumber > 9) {
        notify("Invalid bookmark number: $bookmarkNumber", "error")
        return
    }

    try {
        val bookmarkData = loadBookmarks()
        val previous = bookmarkData.bookmarks.find { it.id == "bookmark_$bookmarkNumber" }
        // Re-saving a slot keeps its name and options; only the captured state changes.
        val newBookmark = captureBookmark(bookmarkNumber).let { fresh ->
            if (previous == null) fresh else fresh.copy(
                name = previous.name,
                restoreView = previous.restoreView,
                restoreSelection = previous.restoreSelection,
                closeUnrecordedOutputs = previous.closeUnrecordedOutputs
            )
        }

        // Remove existing bookmark with same id, then add new one
        val updatedBookmarks = bookmarkData.bookmarks.filter { it.id != newBookmark.id } + newBookmark

        saveBookmarks(BookmarkData(version = 1, bookmarks = updatedBookmarks))
        notify("Saved ${newBookmark.name}", "bookmarks")
        refreshBookmarksPanel()
    } catch (e: dynamic) {
        console.warn("Error saving bookmark slot:", e)
        notify("Error saving bookmark", "error")
    }
}

/**
 * Restores a bookmark by slot number (1-9)
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun restoreBookmarkSlot(bookmarkNumber: Int) {
    if (bookmarkNumber < 1 || bookmarkNumber > 9) {
        notify("Invalid bookmark number: $bookmarkNumber", "error")
        return
    }

    try {
        val bookmarkData = loadBookmarks()
        val bookmark = bookmarkData.bookmarks.find { it.id == "bookmark_$bookmarkNumber" }

        if (bookmark != null) {
            restoreBookmark(bookmark)
        } else {
            notify("Bookmark $bookmarkNumber not set", "bookmarks")
        }
    } catch (e: dynamic) {
        console.warn("Error restoring bookmark slot:", e)
        notify("Error restoring bookmark", "error")
    }
}

/**
 * Registers keyboard shortcuts for bookmarks. Defaults are Shift+F1-F9 to restore and
 * Cmd/Ctrl+Shift+F1-F9 to save; both are rebindable per slot in `~/.afield/keybindings.yaml` as
 * `restore-bookmark-N` / `save-bookmark-N`.
 */
fun registerBookmarkShortcuts() {
    fun handler(event: dynamic) {
        // Matched here rather than via canvasKeybinding because this listener runs in the capture
        // phase, so the F-keys reach us even when Monaco has focus.
        // Both listeners below see a real keystroke; handle it once (a toggle run twice is a no-op).
        if (event.__fieldBookmarkHandled == true) return
        event.__fieldBookmarkHandled = true
        if (Keybindings.matches(event, "show-bookmarks")) {
            // Consumed, as the drawing layer's Alt+D is: the palette action shares this id, and a
            // rebinding in keybindings.yaml would otherwise also fire it from the canvas.
            event.preventDefault()
            event.stopImmediatePropagation()
            toggleBookmarksPanel()
            return
        }
        for (slot in 1..9) {
            if (Keybindings.matches(event, "save-bookmark-$slot")) {
                event.preventDefault()
                saveBookmarkSlot(slot)
                return
            }
            if (Keybindings.matches(event, "restore-bookmark-$slot")) {
                event.preventDefault()
                restoreBookmarkSlot(slot)
                return
            }
        }
    }

    // Capture at the window level so Monaco's DOM doesn't swallow the F-keys
    window.addEventListener("keydown", { event -> handler(event) }, true)
    document.addEventListener("keydown", { event -> handler(event) })
}

/**
 * Registers bookmark actions in the command palette
 */
fun registerBookmarkActions() {
    val bookmarkActions = js("[]")

    // Add actions for bookmarks 1-9
    for (i in 1..9) {
        // Save bookmark action
        val saveAction = js("{}")
        saveAction.id = "save-bookmark-$i"
        saveAction.label = "Save Bookmark $i"
        // No `keys` here: registerBookmarkShortcuts owns the chord in the capture phase, so a
        // canvas predicate as well would fire the action twice.
        saveAction.run = eval("() => { saveBookmarkSlot($i) }")
        bookmarkActions.push(saveAction)

        // Restore bookmark action
        val restoreAction = js("{}")
        restoreAction.id = "restore-bookmark-$i"
        restoreAction.label = "Restore Bookmark $i"
        restoreAction.run = eval("() => { restoreBookmarkSlot($i) }")
        bookmarkActions.push(restoreAction)
    }

    val panelAction = js("{}")
    panelAction.id = "show-bookmarks"
    panelAction.label = "Show Bookmarks"
    panelAction.detail = "List the saved bookmarks, with their options and save/restore buttons"
    // No `keys`: alt+b is matched in registerBookmarkShortcuts, like the F-keys.
    panelAction.run = { toggleBookmarksPanel() }
    bookmarkActions.push(panelAction)

    // Add to builtin actions
    val existingBuiltin = allActions.builtin ?: js("[]")
    for (i in 0 until bookmarkActions.length) {
        existingBuiltin.push(bookmarkActions[i])
    }
    allActions.builtin = existingBuiltin

    updateActions()
}

/**
 * Initializes the bookmark system
 */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun initializeBookmarkSystem() {
    // Register keyboard shortcuts
    registerBookmarkShortcuts()

    // Register actions
    registerBookmarkActions()

    console.info("[bookmarks] initialized")
}

// ── Bookmarks panel ─────────────────────────────────────────────────────────────────────────

private var bookmarksPanel: FieldPanel? = null
private var bookmarksPanelTimer: Int? = null
private var bookmarksPanelRenderedJson: String? = null

private fun ensureBookmarksPanel(): FieldPanel = bookmarksPanel ?: FieldPanel("bookmarks-panel", "Bookmarks") {
    bookmarksPanelTimer?.let { window.clearInterval(it) }
    bookmarksPanelTimer = null
}.also { bookmarksPanel = it }

@OptIn(ExperimentalJsExport::class)
@JsExport
fun toggleBookmarksPanel() {
    val panel = ensureBookmarksPanel()
    if (panel.isVisible) {
        panel.hide()
        return
    }
    panel.show()
    refreshBookmarksPanel(force = true)
    // Another client (or an undo) can change the root's bookmarks; re-render when the JSON moves.
    bookmarksPanelTimer = window.setInterval({ refreshBookmarksPanel() }, 1000)
}

private fun currentBookmarksJson(): String =
    (document.body?.getElementsByClassName("box")?.asList()
        ?.firstOrNull { it.asDynamic().uid == "root" }?.asDynamic()?.bookmarks as? String)
        ?: cachedBookmarksJson ?: ""

fun refreshBookmarksPanel(force: Boolean = false) {
    val panel = bookmarksPanel ?: return
    if (!panel.isVisible) return
    val jsonNow = currentBookmarksJson()
    if (!force && jsonNow == bookmarksPanelRenderedJson) return
    // Never pull a name field out from under the user while they type in it.
    if (!force && panel.root.contains(document.activeElement) && document.activeElement?.tagName == "INPUT") return
    bookmarksPanelRenderedJson = jsonNow

    val slots = loadBookmarks().bookmarks.associateBy { it.id }
    panel.status.textContent = "${slots.size} of 9 slots saved · Shift+F1–F9 restores, Cmd+Shift+F1–F9 saves"
    panel.body.innerHTML = ""
    for (slot in 1..9) {
        panel.body.appendChild(bookmarkCard(slot, slots["bookmark_$slot"]))
    }
}

private fun updateBookmark(slot: Int, change: (Bookmark) -> Bookmark) {
    val data = loadBookmarks()
    saveBookmarks(data.copy(bookmarks = data.bookmarks.map { if (it.id == "bookmark_$slot") change(it) else it }))
    refreshBookmarksPanel(force = true)
}

private fun deleteBookmark(slot: Int) {
    val data = loadBookmarks()
    saveBookmarks(data.copy(bookmarks = data.bookmarks.filter { it.id != "bookmark_$slot" }))
    refreshBookmarksPanel(force = true)
}

private fun panelButton(label: String, title: String, action: () -> Unit): HTMLElement =
    FieldPanel.el("button", text = label).also {
        it.setAttribute("type", "button")
        it.title = title
        it.addEventListener("click", { action() })
    }

private fun bookmarkCard(slot: Int, bookmark: Bookmark?): HTMLElement {
    val el = FieldPanel::el
    val card = el("div", "field-panel-card" + if (bookmark == null) " is-empty" else "", null)
    val head = el("div", "field-panel-card-head", null)
    head.appendChild(el("span", "field-panel-pill" + if (bookmark != null) " is-accent" else "", "F$slot"))
    if (bookmark == null) {
        head.appendChild(el("span", "field-panel-meta", "empty"))
        head.appendChild(el("span", "", ""))
    } else {
        val name = document.createElement("input") as org.w3c.dom.HTMLInputElement
        name.type = "text"
        name.value = bookmark.name
        name.title = "Rename this bookmark"
        name.addEventListener("change", {
            val next = name.value.trim().ifEmpty { "Bookmark $slot" }
            updateBookmark(slot) { it.copy(name = next) }
        })
        name.addEventListener("keydown", { event -> if (event.asDynamic().key == "Enter") name.blur() })
        head.appendChild(name)
        head.appendChild(el("span", "field-panel-meta", savedAgo(bookmark.timestamp)))
    }
    val actions = el("div", "field-panel-actions", null)
    if (bookmark != null) {
        actions.appendChild(panelButton("Restore", "Restore this bookmark (Shift+F$slot)") { restoreBookmarkSlot(slot) })
    }
    actions.appendChild(panelButton(if (bookmark == null) "Save here" else "Save", "Save the current view to slot $slot (Cmd+Shift+F$slot)") { saveBookmarkSlot(slot) })
    if (bookmark != null) {
        actions.appendChild(panelButton("×", "Delete this bookmark") { deleteBookmark(slot) })
    }
    head.appendChild(actions)
    card.appendChild(head)
    if (bookmark == null) return card

    val detail = el("div", "bookmark-detail", null)
    detail.appendChild(bookmarkMinimap(bookmark))
    val summary = el("div", "bookmark-summary", null)
    fun line(label: String, value: String) {
        val l = el("div", "bookmark-summary-line", "$label ")
        l.appendChild(el("strong", "", value))
        summary.appendChild(l)
    }
    bookmark.scrollPosition?.let { pos ->
        val zoom = pos.zoom?.takeIf { it != 1.0 }?.let { " at ${(it * 100).toInt()}%" } ?: ""
        line("view", "(${pos.x.toInt()}, ${pos.y.toInt()})$zoom")
    }
    val selected = bookmark.selectedBox?.let { boxLabel(it) }
    when {
        selected == null -> line("selection", "none")
        bookmark.editedProperty != null -> line(
            "editing",
            "${bookmark.editedProperty} of $selected" + (bookmark.cursorLine?.let { ", line $it" } ?: "")
        )
        else -> line("selected", selected)
    }
    val outputs = bookmark.outputAreaState.orEmpty()
    val open = outputs.values.sumOf { channels -> channels.count { it.value } }
    val closed = outputs.values.sumOf { channels -> channels.count { !it.value } }
    line("output panels", "$open open, $closed closed")
    val pills = el("div", "bookmark-pills", null)
    outputs.forEach { (uid, channels) ->
        channels.filter { it.value }.keys.forEach { channel ->
            val size = bookmark.outputAreaSizes?.get(uid)?.get(channel)?.let { " ${it.width.toInt()}×${it.height.toInt()}" } ?: ""
            pills.appendChild(el("span", "field-panel-pill", "${boxLabel(uid)} · $channel$size"))
        }
    }
    if (pills.childElementCount > 0) summary.appendChild(pills)
    detail.appendChild(summary)
    card.appendChild(detail)

    val options = el("div", "bookmark-options", null)
    fun option(label: String, title: String, value: Boolean, change: (Bookmark, Boolean) -> Bookmark) {
        val wrap = el("label", "field-panel-toggle", null)
        wrap.title = title
        val box = document.createElement("input") as org.w3c.dom.HTMLInputElement
        box.type = "checkbox"
        box.checked = value
        box.addEventListener("change", { updateBookmark(slot) { change(it, box.checked) } })
        wrap.appendChild(box)
        wrap.appendChild(el("span", "", label))
        options.appendChild(wrap)
    }
    option("restore view", "Pan the canvas back to where it was", bookmark.restoreView) { b, v -> b.copy(restoreView = v) }
    option("restore selection", "Reselect the box and reopen its editor and cursor", bookmark.restoreSelection) { b, v -> b.copy(restoreSelection = v) }
    option(
        "close unrecorded panels",
        "Also close output panels that did not exist when this bookmark was saved",
        bookmark.closeUnrecordedOutputs
    ) { b, v -> b.copy(closeUnrecordedOutputs = v) }
    card.appendChild(options)
    return card
}

private fun boxLabel(uid: String): String {
    val box = findBoxByUid(uid) ?: return "missing box"
    return (box.asDynamic().name as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: uid.take(8)
}

private fun savedAgo(timestamp: Double): String {
    val seconds = ((js("Date.now()") as Double) - timestamp) / 1000.0
    return when {
        seconds < 60 -> "just now"
        seconds < 3600 -> "${(seconds / 60).toInt()} min ago"
        seconds < 86400 -> "${(seconds / 3600).toInt()} h ago"
        else -> js("new Date(timestamp).toLocaleDateString()") as String
    }
}

/**
 * The document's boxes as they stand now, with the rectangle the bookmark's pan would show at
 * the bookmark's zoom and the current window size. The panels the bookmark keeps open are drawn
 * under their boxes at their recorded sizes; the selected box takes the accent.
 */
private fun bookmarkMinimap(bookmark: Bookmark): org.w3c.dom.Element {
    val ns = "http://www.w3.org/2000/svg"
    val svg = document.createElementNS(ns, "svg")
    svg.setAttribute("class", "bookmark-map")
    svg.setAttribute("preserveAspectRatio", "xMidYMid meet")
    data class R(val x: Double, val y: Double, val w: Double, val h: Double, val uid: String?)
    val rects = mutableListOf<R>()
    (document.body?.getElementsByClassName("box")?.asList() ?: emptyList()).forEach { raw ->
        val box = raw as? HTMLElement ?: return@forEach
        val uid = box.asDynamic().uid as? String ?: return@forEach
        if (uid == "root") return@forEach
        val r = box.getBoundingClientRect()
        if (r.width <= 0.0 || r.height <= 0.0) return@forEach
        rects += R(
            (r.left - mainTranslation[0]) / mainZoom, (r.top - mainTranslation[1]) / mainZoom,
            r.width / mainZoom, r.height / mainZoom, uid
        )
    }
    // The panels the bookmark keeps open, each hung under its header where the box lays it out
    // now, at the size the bookmark recorded (or its current size, for bookmarks that predate sizes).
    val panels = mutableListOf<R>()
    bookmark.outputAreaState.orEmpty().forEach { (uid, channels) ->
        val box = findBoxByUid(uid) ?: return@forEach
        val disclosures = box.querySelectorAll(".outputArea .disclosure").asList().mapNotNull { it as? HTMLElement }
        channels.filterValues { it }.keys.forEach { channel ->
            val wrap = disclosures.firstOrNull { (it.asDynamic().__disclosureId as? String) == channel } ?: return@forEach
            val header = wrap.getBoundingClientRect()
            if (header.width <= 0.0) return@forEach
            val size: PanelSize = bookmark.outputAreaSizes?.get(uid)?.get(channel) ?: run {
                val current: dynamic = disclosureApiFor(box)?.getSize(channel)
                if (current == null || current == undefined) return@forEach
                PanelSize((current.width as Number).toDouble(), (current.height as Number).toDouble())
            }
            val x = (header.left - mainTranslation[0]) / mainZoom
            val y = (header.top - mainTranslation[1]) / mainZoom
            // An open disclosure's rect already includes its content; a closed one is only the header.
            val headerHeight = if (header.height / mainZoom > size.height) 18.0 else header.height / mainZoom
            panels += R(x, y + headerHeight, size.width, size.height, uid)
        }
    }
    val view = bookmark.scrollPosition?.let {
        val z = it.zoom ?: mainZoom
        R(-it.x / z, -it.y / z, window.innerWidth / z, window.innerHeight / z, null)
    }
    val all = rects + panels + listOfNotNull(view)
    if (all.isEmpty()) return svg
    val minX = all.minOf { it.x }
    val minY = all.minOf { it.y }
    val maxX = all.maxOf { it.x + it.w }
    val maxY = all.maxOf { it.y + it.h }
    val pad = maxOf(maxX - minX, maxY - minY) * 0.04
    svg.setAttribute("viewBox", "${minX - pad} ${minY - pad} ${maxX - minX + 2 * pad} ${maxY - minY + 2 * pad}")
    val openBoxes = bookmark.outputAreaState.orEmpty().filterValues { channels -> channels.values.any { it } }.keys
    rects.forEach { r ->
        val rect = document.createElementNS(ns, "rect")
        rect.setAttribute("x", "${r.x}"); rect.setAttribute("y", "${r.y}")
        rect.setAttribute("width", "${r.w}"); rect.setAttribute("height", "${r.h}")
        val cls = buildString {
            append("bm-box")
            if (r.uid in openBoxes) append(" has-open")
            if (r.uid == bookmark.selectedBox) append(" is-selected")
        }
        rect.setAttribute("class", cls)
        svg.appendChild(rect)
    }
    panels.forEach { r ->
        val rect = document.createElementNS(ns, "rect")
        rect.setAttribute("x", "${r.x}"); rect.setAttribute("y", "${r.y}")
        rect.setAttribute("width", "${r.w}"); rect.setAttribute("height", "${r.h}")
        rect.setAttribute("class", "bm-panel")
        svg.appendChild(rect)
    }
    view?.let { v ->
        val rect = document.createElementNS(ns, "rect")
        rect.setAttribute("x", "${v.x}"); rect.setAttribute("y", "${v.y}")
        rect.setAttribute("width", "${v.w}"); rect.setAttribute("height", "${v.h}")
        rect.setAttribute("class", "bm-view")
        svg.appendChild(rect)
    }
    return svg
}
