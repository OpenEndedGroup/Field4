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
 * User-facing reference: public/docs/spatial_navigation.md.
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
    val nestingState: Map<String, NestingInfo>? = null
)

@Serializable
data class ScrollPosition(
    val x: Double,
    val y: Double
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
        y = mainTranslation[1]
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
        nestingState = captureNestingState()
    )
}

/**
 * Restores the scroll/pan position of the main canvas
 */
fun restoreScrollPosition(position: ScrollPosition) {
    try {
        mainTranslation = listOf(position.x, position.y)
        drawingLayerViewportChanged(position.x, position.y)
        mainCanvas.style.transform = "translate(${position.x}px, ${position.y}px)"
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
        bookmark.scrollPosition?.let { restoreScrollPosition(it) }

        // Restore nesting state first (before selecting boxes)
        bookmark.nestingState?.let { restoreNestingState(it) }

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
        val newBookmark = captureBookmark(bookmarkNumber)

        // Remove existing bookmark with same id, then add new one
        val updatedBookmarks = bookmarkData.bookmarks.filter { it.id != newBookmark.id } + newBookmark

        saveBookmarks(BookmarkData(version = 1, bookmarks = updatedBookmarks))
        notify("Saved Bookmark $bookmarkNumber", "bookmarks")
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
