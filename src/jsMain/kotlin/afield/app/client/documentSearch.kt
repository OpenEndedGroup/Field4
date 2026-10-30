package afield.app.client

import afield.app.canvas.CanvasCamera
import afield.app.canvas.invoke

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLDivElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSpanElement
import org.w3c.dom.asList
import org.w3c.dom.events.KeyboardEvent

private data class SearchMatch(
    val box: HTMLElement,
    val property: String,
    val start: Int,
    val end: Int,
    val startLine: Int,
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int
)

private val searchableProperties = listOf(
    "name",
    "code",
    "vertex",
    "fragment",
    "stackSL",
    "rectSL",
    "vertex_fragment",
    "compute",
    "task",
    "mesh",
    "raygen",
    "miss",
    "closesthit",
    "anyhit",
    "intersection",
    "callable",
    "vertex_s",
    "fragment_s",
    "compute_s",
    "task_s",
    "mesh_s",
    "raygen_s",
    "miss_s",
    "closesthit_s",
    "anyhit_s",
    "intersection_s",
    "callable_s",
    "preamble",
    "attic",
    "metadata"
)

private var searchMatches: List<SearchMatch> = emptyList()
private var activeMatchIndex = -1

private var searchContainer: HTMLDivElement? = null
private var searchInput: HTMLInputElement? = null
private var searchCount: HTMLSpanElement? = null
private var searchPrev: HTMLButtonElement? = null
private var searchNext: HTMLButtonElement? = null
private var searchClose: HTMLButtonElement? = null
private var activeHighlightedBox: HTMLElement? = null
private var activeEditorDecorationOwner: dynamic = null
private var activeEditorDecorationCollection: dynamic = null

private const val searchWidgetId = "document-search"
private const val searchRefocusDelayMs = 180
private const val searchHighlightRetryDelayMs = 80
private const val searchHighlightMaxAttempts = 8

fun initializeDocumentSearch() {
    ensureSearchWidget()
    registerSearchKeybindings()
}

private fun ensureSearchWidget() {
    if (searchContainer != null) return
    val container = document.createElement("div") as HTMLDivElement
    container.id = searchWidgetId

    val input = document.createElement("input") as HTMLInputElement
    input.placeholder = "Search boxes"
    input.setAttribute("aria-label", "Search box code and names")
    input.spellcheck = false
    input.oninput = {
        performSearch(input.value)
        null
    }
    input.onkeydown = { event ->
        val ke = event
        if (ke.key == "Enter") {
            ke.preventDefault()
            if (ke.shiftKey) goToPreviousMatch() else goToNextMatch()
        } else if (ke.key == "Escape") {
            ke.preventDefault()
            hideSearch()
        }
        null
    }

    val count = document.createElement("span") as HTMLSpanElement
    count.classList.add("search-count", "field-panel-status")
    count.setAttribute("role", "status")
    count.textContent = ""

    val prev = document.createElement("button") as HTMLButtonElement
    prev.textContent = "↑"
    prev.classList.add("search-button", "field-panel-icon")
    prev.title = "Previous match (Shift+Enter)"
    prev.setAttribute("aria-label", "Previous match (Shift+Enter)")
    prev.type = "button"
    prev.onclick = {
        goToPreviousMatch()
        null
    }

    val next = document.createElement("button") as HTMLButtonElement
    next.textContent = "↓"
    next.classList.add("search-button", "field-panel-icon")
    next.title = "Next match (Enter)"
    next.setAttribute("aria-label", "Next match (Enter)")
    next.type = "button"
    next.onclick = {
        goToNextMatch()
        null
    }

    val close = document.createElement("button") as HTMLButtonElement
    close.textContent = "×"
    close.classList.add("search-button", "close", "field-panel-icon")
    close.type = "button"
    close.title = "Close"
    close.setAttribute("aria-label", "Close")
    close.onclick = {
        hideSearch()
        null
    }

    // One row: the field, the counter in the status-line style, then three identical icon buttons.
    container.appendChild(input)
    container.appendChild(count)
    container.appendChild(prev)
    container.appendChild(next)
    container.appendChild(close)
    // Keys and clicks typed into search stay off the canvas, as in every Field panel.
    container.addEventListener("mousedown", { it.stopPropagation() })
    container.addEventListener("wheel", { it.stopPropagation() })

    document.body?.appendChild(container)

    searchContainer = container
    searchInput = input
    searchCount = count
    searchPrev = prev
    searchNext = next
    searchClose = close
    updateCounter()
}

private fun registerSearchKeybindings() {
    window.addEventListener("keydown", { event ->
        val ke = event as? KeyboardEvent ?: return@addEventListener
        val key = ke.key.lowercase()
        val hasModifier = ke.metaKey || ke.ctrlKey
        val editorHasFocus = try {
            val w = js("window")
            val ed = w.globalEditor
            ed != null && ed != undefined && ed.hasTextFocus && ed.hasTextFocus()
        } catch (_: dynamic) {
            false
        }

        if (key == "f" && hasModifier && !ke.altKey && !ke.shiftKey) {
            if (!editorHasFocus || isSearchVisible()) {
                ke.preventDefault()
                ke.stopPropagation()
                openSearch()
            }
        } else if (ke.key == "Escape" && isSearchVisible()) {
            ke.preventDefault()
            ke.stopPropagation()
            hideSearch()
        }
    }, true)
}

/**
 * Bottom-left of the main canvas pane, above any panel already docked there (history, macro
 * recorder, layers), so it is not mistaken for the editor's own Find at the top right.
 */
private fun positionSearch() {
    val container = searchContainer ?: return
    val pane = CanvasCamera.main.rect
    val paneBottom = pane.y + pane.h
    var bottom = paneBottom - 10.0
    listOf(".history-iframe-container", "#macro-recorder-panel.is-visible", "#layers-panel.is-visible").forEach { sel ->
        val el = document.querySelector(sel) as? HTMLElement ?: return@forEach
        val cs = window.getComputedStyle(el)
        if (cs.display == "none" || cs.visibility == "hidden") return@forEach
        val r = el.getBoundingClientRect()
        if (r.width <= 0.0 || r.height <= 0.0 || r.left > pane.x + 400.0) return@forEach
        bottom = minOf(bottom, r.top - 8.0)
    }
    container.style.left = "${pane.x + 10.0}px"
    container.style.top = "auto"
    container.style.right = "auto"
    container.style.bottom = "${window.innerHeight - bottom}px"
}

private fun openSearch() {
    ensureSearchWidget()
    searchContainer?.style?.display = "flex"
    positionSearch()
    searchInput?.focus()
    searchInput?.select()
    // Re-run existing query to refresh highlights if the widget was reopened.
    searchInput?.value?.let { value ->
        if (value.isNotBlank()) performSearch(value) else clearSearchResults()
    }
}

private fun hideSearch() {
    searchContainer?.style?.display = "none"
    clearSearchResults()
}

private fun isSearchVisible(): Boolean {
    return searchContainer?.style?.display != null && searchContainer?.style?.display != "none"
}

private fun hasSearchFocus(): Boolean {
    return document.asDynamic().activeElement == searchInput
}

private fun restoreSearchFocusIfNeeded(shouldRestore: Boolean) {
    if (!shouldRestore) return
    window.setTimeout({
        searchInput?.focus()
    }, 0)
    window.setTimeout({
        searchInput?.focus()
    }, searchRefocusDelayMs)
}

private fun performSearch(query: String) {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) {
        clearSearchResults()
        return
    }

    val boxes = document.body?.getElementsByClassName("box")?.asList()?.mapNotNull { it as? HTMLElement } ?: emptyList()
    val matches = mutableListOf<SearchMatch>()

    boxes.forEach { box ->
        val uid = box.asDynamic().uid as? String
        if (uid == "root") return@forEach

        searchableProperties.forEach { property ->
            val raw = box.asDynamic()[property] as? String ?: return@forEach
            val text = raw.replace("\r\n", "\n")
            var start = text.indexOf(trimmed, ignoreCase = true)
            while (start >= 0) {
                val end = start + trimmed.length
                val (sl, sc) = toLineColumn(text, start)
                val (el, ec) = toLineColumn(text, end)
                matches += SearchMatch(box, property, start, end, sl, sc, el, ec)
                start = text.indexOf(trimmed, start + trimmed.length, ignoreCase = true)
            }
        }
    }

    searchMatches = matches
    activeMatchIndex = -1
    clearEditorMatchHighlight()
    applyHighlights()
    updateCounter()
}

private fun applyHighlights() {
    clearHighlightClasses()
    if (searchMatches.isEmpty()) return

    val counts = mutableMapOf<HTMLElement, MutableMap<String, Int>>()
    searchMatches.forEach { match ->
        val perProperty = counts.getOrPut(match.box) { linkedMapOf() }
        perProperty[match.property] = (perProperty[match.property] ?: 0) + 1
    }

    counts.forEach { (box, perProperty) ->
        box.classList.add("search-hit")
        updateSearchBadge(box, perProperty)
    }

    val active = searchMatches.getOrNull(activeMatchIndex)
    active?.box?.let { box ->
        box.classList.add("search-hit-active")
        (box.querySelector(".document-search-badge") as? HTMLElement)?.classList?.add("active")
    }
    activeHighlightedBox = active?.box
}

private fun updateSearchBadge(box: HTMLElement, perProperty: Map<String, Int>) {
    var badge = box.querySelector(".document-search-badge") as? HTMLElement
    if (badge == null) {
        badge = document.createElement("div") as HTMLElement
        badge.classList.add("document-search-badge")
        box.appendChild(badge)
    }

    val entries = perProperty.entries.toList()
    val shown = entries.take(3).map { (property, count) ->
        if (count > 1) "$property x$count" else property
    }
    val tail = if (entries.size > 3) " +${entries.size - 3}" else ""
    val total = perProperty.values.sum()
    badge.textContent = "${shown.joinToString(", ")}$tail"
    badge.title = entries.joinToString(", ") { (property, count) ->
        if (count > 1) "$property: $count matches" else "$property: 1 match"
    }
    badge.dataset.asDynamic().count = total.toString()

    val hasProvisionalBadge = box.querySelector(".provisional-badge") != null
    badge.classList.toggle("offset", hasProvisionalBadge)
    badge.classList.remove("active")
}

private fun clearHighlightClasses() {
    val nodes = document.querySelectorAll(".search-hit, .search-hit-active")
    for (i in 0 until nodes.length) {
        (nodes.item(i) as? HTMLElement)?.let { element ->
            element.classList.remove("search-hit")
            element.classList.remove("search-hit-active")
        }
    }
    val badges = document.querySelectorAll(".document-search-badge")
    for (i in 0 until badges.length) {
        (badges.item(i) as? HTMLElement)?.remove()
    }
    activeHighlightedBox = null
}

private fun clearSearchResults() {
    searchMatches = emptyList()
    activeMatchIndex = -1
    updateCounter()
    clearHighlightClasses()
    clearEditorMatchHighlight()
}

private fun focusMatch(index: Int) {
    if (index !in searchMatches.indices) return

    val shouldRestoreFocus = hasSearchFocus()
    activeMatchIndex = index
    val match = searchMatches[index]

    applyHighlights()
    updateCounter(match)
    openMatch(match, allowEditorFocus = !shouldRestoreFocus)
    restoreSearchFocusIfNeeded(shouldRestoreFocus)
}

private fun goToNextMatch() {
    if (searchMatches.isEmpty()) return
    val nextIndex = (activeMatchIndex + 1) % searchMatches.size
    focusMatch(nextIndex)
}

private fun goToPreviousMatch() {
    if (searchMatches.isEmpty()) return
    val prevIndex = if (activeMatchIndex - 1 < 0) searchMatches.lastIndex else activeMatchIndex - 1
    focusMatch(prevIndex)
}

private fun updateCounter(match: SearchMatch? = null) {
    val total = searchMatches.size
    val query = searchInput?.value?.trim().orEmpty()
    val noMatch = query.isNotEmpty() && total == 0
    searchContainer?.classList?.toggle("is-no-match", noMatch)
    searchPrev?.disabled = total == 0
    searchNext?.disabled = total == 0
    if (query.isEmpty()) {
        searchCount?.textContent = ""
        return
    }
    if (total == 0) {
        searchCount?.textContent = "No matches"
        return
    }
    val property = match?.property ?: searchMatches.getOrNull(activeMatchIndex)?.property
    val propertyLabel = property?.let { " · $it" } ?: ""
    searchCount?.textContent = if (activeMatchIndex >= 0) "${activeMatchIndex + 1} of $total$propertyLabel"
        else if (total == 1) "1 match" else "$total matches"
}

private fun openMatch(match: SearchMatch, allowEditorFocus: Boolean) {
    openNestedParents(match.box)
    ensureBoxVisibleForNavigation(match.box)

    val uid = match.box.asDynamic().uid as? String
    val w = window.asDynamic()
    w.suppressNextEditorStateRestore = true
    val switchToProperty = w.switchToProperty
    if (uid != null && switchToProperty != null && switchToProperty != undefined) {
        try {
            switchToProperty(uid, match.property)
        } catch (e: dynamic) {
            console.info("[search] switchToProperty failed", e)
            fallbackSelectMatch(match)
        }
    } else {
        fallbackSelectMatch(match)
        showMonaco()
    }

    scheduleEditorMatchHighlight(match, allowEditorFocus)
}

private fun fallbackSelectMatch(match: SearchMatch) {
    selected.toList().forEach { deselectBox(it) }
    setSelectedEditorProperty(match.property)
    selectBox(match.box)
    selectionContextChanged()
}

private fun clearEditorMatchHighlight() {
    val collection = activeEditorDecorationCollection
    if (collection != null && collection != undefined) {
        try {
            collection.clear()
        } catch (_: dynamic) {
        }
    }
    activeEditorDecorationOwner = null
    activeEditorDecorationCollection = null
}

private fun scheduleEditorMatchHighlight(match: SearchMatch, allowEditorFocus: Boolean, attempt: Int = 0) {
    val delay = if (attempt == 0) 140 else searchHighlightRetryDelayMs
    window.setTimeout({
        highlightEditorMatch(match, allowEditorFocus, attempt)
    }, delay)
}

private fun highlightEditorMatch(match: SearchMatch, allowEditorFocus: Boolean, attempt: Int) {
    if (searchMatches.getOrNull(activeMatchIndex) != match) return
    try {
        val w = window.asDynamic()
        val editor: dynamic = w.globalEditor
        val monaco: dynamic = w.globalMonaco
        if (editor == null || editor == undefined || monaco == null || monaco == undefined) {
            retryEditorMatchHighlight(match, allowEditorFocus, attempt)
            return
        }

        val model = editor.getModel()
        val currentProperty = w.currentSelectedProperty as? String
        val selectedProperty = w.selectedProperty as? String
        val expectedText = match.box.asDynamic()[match.property] as? String
        val modelText = model?.getValue() as? String
        val propertyReady = currentProperty == match.property || selectedProperty == match.property
        val textReady = expectedText == null || modelText == expectedText.replace("\r\n", "\n")

        if ((!propertyReady || !textReady) && attempt < searchHighlightMaxAttempts) {
            retryEditorMatchHighlight(match, allowEditorFocus, attempt)
            return
        }

        val startLine = match.startLine
        val startColumn = match.startColumn
        val endLine = match.endLine
        val endColumn = match.endColumn
        val range = js("new monaco.Range(startLine, startColumn, endLine, endColumn)")
        val position = js("{}")
        position.lineNumber = startLine
        position.column = startColumn
        editor.setSelection(range)
        editor.setPosition(position)
        editor.revealRangeInCenter(range)

        if (activeEditorDecorationOwner != editor || activeEditorDecorationCollection == null || activeEditorDecorationCollection == undefined) {
            clearEditorMatchHighlight()
            activeEditorDecorationOwner = editor
            activeEditorDecorationCollection = js("editor.createDecorationsCollection([])")
        }

        val options = js("{}")
        options.inlineClassName = "document-search-editor-match"
        options.stickiness = 1
        val decoration = js("{}")
        decoration.range = range
        decoration.options = options
        activeEditorDecorationCollection.set(arrayOf(decoration))

        if (allowEditorFocus) js("editor.focus()")
    } catch (e: dynamic) {
        console.info("[search] failed to highlight match", e)
    }
}

private fun retryEditorMatchHighlight(match: SearchMatch, allowEditorFocus: Boolean, attempt: Int) {
    if (attempt >= searchHighlightMaxAttempts) {
        try {
            val w = window.asDynamic()
            val editor: dynamic = w.globalEditor
            val monaco: dynamic = w.globalMonaco
            if (editor != null && editor != undefined && monaco != null && monaco != undefined) {
                val startLine = match.startLine
                val startColumn = match.startColumn
                val endLine = match.endLine
                val endColumn = match.endColumn
                val range = js("new monaco.Range(startLine, startColumn, endLine, endColumn)")
                val position = js("{}")
                position.lineNumber = startLine
                position.column = startColumn
                editor.setSelection(range)
                editor.setPosition(position)
                editor.revealRangeInCenter(range)
                if (allowEditorFocus) editor.focus()
            }
        } catch (e: dynamic) {
            console.info("[search] failed to highlight match", e)
        }
        return
    }
    scheduleEditorMatchHighlight(match, allowEditorFocus, attempt + 1)
}

private fun openNestedParents(box: HTMLElement) {
    val parentUid = box.asDynamic().box_nestedIn as? String ?: return
    val parent = document.body?.getElementsByClassName("box")?.asList()
        ?.firstOrNull { (it as? HTMLElement)?.asDynamic()?.uid == parentUid } as? HTMLElement ?: return

    openNestedParents(parent)

    val outputArea = parent.getElementsByClassName("outputArea").asList().firstOrNull() as? HTMLElement ?: return
    val contain = outputArea.querySelector(".disclosure-group")
    val di = js("window").disclosureInterface
    if (di != null && di != undefined && contain != null) {
        try {
            val inter = di(contain)
            if (inter != null && inter != undefined && inter.open != null && inter.open != undefined) {
                inter.open("nested")
            }
        } catch (e: dynamic) {
            console.info("[search] failed to open nested disclosure", e)
        }
    }
    outputArea.style.visibility = "visible"
    outputArea.style.height = "auto"
}

private fun toLineColumn(text: String, index: Int): Pair<Int, Int> {
    val clamped = index.coerceIn(0, text.length)
    val before = text.substring(0, clamped)
    val line = before.count { it == '\n' } + 1
    val lastBreak = before.lastIndexOf('\n')
    val base = if (lastBreak >= 0) lastBreak + 1 else 0
    val column = clamped - base + 1
    return line to column
}
