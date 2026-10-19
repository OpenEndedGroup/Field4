package afield.app.canvas

import kotlinx.serialization.Serializable

/**
 * What a canvas bookmark records, as JSON on the root box's `bookmarks` property.
 *
 * Shared so the server can read a document's bookmarks too: a canvas flight names its stops by
 * bookmark, and box code learns of a misspelt one when it calls `canvasFlight`, not later in the
 * browser. The bookmark feature itself is `src/jsMain/kotlin/afield/app/canvas/bookmarks.kt`.
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
) {
    /** The slot, 1-9, from the id `bookmark_N`. */
    val slot: Int? get() = id.removePrefix("bookmark_").toIntOrNull()
}

@Serializable
data class ScrollPosition(
    val x: Double,
    val y: Double,
    /** Canvas zoom; absent in bookmarks saved before zoom was recorded, which keep the current zoom. */
    val zoom: Double? = null,
    /**
     * The canvas split (the root `panes` JSON, empty for none) and each pane column's and row's
     * translation; absent in bookmarks saved before split panes, which keep the current split.
     */
    val panes: String? = null,
    val paneX: List<Double>? = null,
    val paneY: List<Double>? = null,
    /** Unlinked panes' own translations, keyed "col,row"; absent for bookmarks that predate unlinking. */
    val paneOwnX: Map<String, Double>? = null,
    val paneOwnY: Map<String, Double>? = null,
    /**
     * The main pane's rectangle on screen when the bookmark was saved, in CSS px. A canvas flight
     * uses it to show the same part of the canvas in a window of another size; absent in bookmarks
     * saved before flights, which a flight treats as saved in the current window.
     */
    val viewX: Double? = null,
    val viewY: Double? = null,
    val viewW: Double? = null,
    val viewH: Double? = null
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
) {
    /** The saved slots in slot order, 1 to 9: the stops of the palette's flight. */
    fun inSlotOrder(): List<Bookmark> = bookmarks.sortedBy { it.slot ?: Int.MAX_VALUE }

    /**
     * The bookmark [reference] names: its name (ignoring case and surrounding space), else its slot
     * (`"3"` or `"F3"`), else its id. Null when none matches.
     */
    fun find(reference: String): Bookmark? {
        val wanted = reference.trim()
        bookmarks.firstOrNull { it.name.trim().equals(wanted, ignoreCase = true) }?.let { return it }
        val slot = wanted.removePrefix("F").removePrefix("f").toIntOrNull()
        if (slot != null) bookmarks.firstOrNull { it.slot == slot }?.let { return it }
        return bookmarks.firstOrNull { it.id == wanted }
    }

    /** What to tell someone whose [reference] matched nothing. */
    fun describeMissing(reference: String): String =
        if (bookmarks.isEmpty()) "No bookmark named \"$reference\": this document has no bookmarks (Cmd+Shift+F1-F9 saves one)"
        else "No bookmark named \"$reference\"; this document has " +
            inSlotOrder().joinToString(", ") { "\"${it.name}\" (F${it.slot ?: "?"})" }
}
