package afield

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

private const val searchWidgetId = "document-search"
private const val searchRefocusDelayMs = 180

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
    count.classList.add("search-count")
    count.textContent = "0 / 0"

    val prev = document.createElement("button") as HTMLButtonElement
    prev.innerHTML = "&#8593;"
    prev.classList.add("search-button")
    prev.onclick = {
        goToPreviousMatch()
        null
    }

    val next = document.createElement("button") as HTMLButtonElement
    next.innerHTML = "&#8595;"
    next.classList.add("search-button")
    next.onclick = {
        goToNextMatch()
        null
    }

    val close = document.createElement("button") as HTMLButtonElement
    close.innerHTML = "&times;"
    close.classList.add("search-button", "close")
    close.onclick = {
        hideSearch()
        null
    }

    container.appendChild(input)
    container.appendChild(prev)
    container.appendChild(next)
    container.appendChild(count)
    container.appendChild(close)

    document.body?.appendChild(container)

    searchContainer = container
    searchInput = input
    searchCount = count
    searchPrev = prev
    searchNext = next
    searchClose = close
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

private fun openSearch() {
    ensureSearchWidget()
    searchContainer?.style?.display = "flex"
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
    applyHighlights()
    updateCounter()
}

private fun applyHighlights() {
    clearHighlightClasses()
    if (searchMatches.isEmpty()) return

    val counts = mutableMapOf<HTMLElement, Int>()
    searchMatches.forEach { match ->
        counts[match.box] = (counts[match.box] ?: 0) + 1
    }

    counts.keys.forEach { box -> box.classList.add("search-hit") }

    val active = searchMatches.getOrNull(activeMatchIndex)
    active?.box?.classList?.add("search-hit-active")
    activeHighlightedBox = active?.box
}

private fun clearHighlightClasses() {
    val nodes = document.querySelectorAll(".search-hit, .search-hit-active")
    for (i in 0 until nodes.length) {
        (nodes.item(i) as? HTMLElement)?.let { element ->
            element.classList.remove("search-hit")
            element.classList.remove("search-hit-active")
        }
    }
    activeHighlightedBox = null
}

private fun clearSearchResults() {
    searchMatches = emptyList()
    activeMatchIndex = -1
    updateCounter()
    clearHighlightClasses()
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
    val current = if (activeMatchIndex >= 0) activeMatchIndex + 1 else 0
    val total = searchMatches.size
    val property = match?.property ?: searchMatches.getOrNull(activeMatchIndex)?.property
    val propertyLabel = property?.let { " · $it" } ?: ""
    searchCount?.textContent = "$current / $total$propertyLabel"
}

private fun openMatch(match: SearchMatch, allowEditorFocus: Boolean) {
    openNestedParents(match.box)
    match.box.scrollIntoView(js("{ behavior: 'smooth', block: 'center', inline: 'center' }"))

    selected.toList().forEach { deselectBox(it) }
    selectBox(match.box)

    selectedProperty = match.property
    window.asDynamic().selectedProperty = match.property
    window.asDynamic().selectionContextChanged?.let { fn ->
        try {
            fn()
        } catch (_: dynamic) {}
    }

    val uid = match.box.asDynamic().uid as? String
    val w = window.asDynamic()
    val switchToProperty = w.switchToProperty
    if (uid != null && switchToProperty != null && switchToProperty != undefined) {
        try {
            switchToProperty(uid, match.property)
        } catch (e: dynamic) {
            console.info("[search] switchToProperty failed", e)
        }
    } else {
        showMonaco()
    }

    window.setTimeout({
        try {
            val editor = w.globalEditor
            val monaco = w.globalMonaco
            if (editor != null && editor != undefined && monaco != null && monaco != undefined) {
                val startLine = match.startLine
                val startColumn = match.startColumn
                val endLine = match.endLine
                val endColumn = match.endColumn
                val range = js("new monaco.Range(startLine, startColumn, endLine, endColumn)")
                editor.setSelection(range)
                editor.revealRangeInCenter(range)
                if (allowEditorFocus) editor.focus()
            }
        } catch (e: dynamic) {
            console.info("[search] failed to highlight match", e)
        }
    }, 120)
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
