
import afield.*
import kotlinx.browser.document
import kotlinx.coroutines.DelicateCoroutinesApi
import org.w3c.dom.*

external fun disclosureInterface(container: Element): dynamic
external fun aiChatUpdate(container: Element, uid: String, markdown: String)

fun insertHTMLWithScripts(container: Element, htmlString: String, isModule : Boolean = false) {
    // Create a document fragment from the HTML string
    val range = document.createRange()
    val fragment = range.createContextualFragment(htmlString)

    // Get all <script> elements in the fragment
    val scripts: NodeList = fragment.querySelectorAll("script")

    // Loop through each <script> and replace it with a new one to trigger execution
    for (i in 0 until scripts.length) {
        val oldScript = scripts.item(i) as? Element ?: continue
        val newScript = document.createElement("script")

        if (isModule) newScript.setAttribute("type", "module")
        // Copy over all attributes (e.g., src, type, etc.)
        val attributes = oldScript.attributes
        for (j in 0 until attributes.length) {
            val attr = attributes.item(j)
            if (attr != null) {
                newScript.setAttribute(attr.nodeName, attr.nodeValue ?: "")
            }
        }

        // Copy the script content
        newScript.textContent = oldScript.textContent

        // Replace the old script with the new one in the fragment
        oldScript.parentNode?.replaceChild(newScript, oldScript)
    }

    // Insert the fragment into the container at the desired location
    container.appendChild(fragment)
}

fun renderLayersPalette(contentRoot: HTMLElement) {
    contentRoot.innerHTML = ""
    contentRoot.classList.add("layers-palette")
    val animateDuration = 450.0
    contentRoot.style.apply {
        display = "flex"
        flexDirection = "column"
        width = "50%"
        setProperty("gap", "8px")
        padding = "8px"
        backgroundColor = "#1c1c1c"
        borderRadius = "6px"
        color = "#f0f0f0"
        fontSize = "13px"
        fontWeight = "100"
    }

    val controls = document.createElement("div") as HTMLElement
    controls.style.apply {
        display = "flex"
        justifyContent = "flex-end"
        setProperty("gap", "4px")
        alignItems = "center"
        setProperty("padding-right", "4px")
    }

    val list = document.createElement("div") as HTMLElement
    list.classList.add("layers-list")
    list.style.apply {
        display = "flex"
        flexDirection = "column"
        setProperty("gap", "4px")
    }

    fun button(label: String, handler: () -> Unit): HTMLButtonElement {
        val btn = document.createElement("button") as HTMLButtonElement
        btn.textContent = label
        btn.style.apply {
            padding = "4px 8px"
            backgroundColor = "#2c2c2c"
            color = "#f0f0f0"
            border = "1px solid rgba(255,255,255,0.08)"
            borderRadius = "4px"
            cursor = "pointer"
        }
        btn.onclick = {
            handler()
            null
        }
        return btn
    }

    fun applyLayerState(nextVisible: Set<String>, desiredActive: String?) {
        if (nextVisible.isEmpty()) return
        val normalizedVisible = nextVisible.filter { it.isNotBlank() }.toMutableSet()
        if (normalizedVisible.isEmpty()) normalizedVisible.add("default")

        val activeCandidate = when {
            desiredActive != null && normalizedVisible.contains(desiredActive) -> desiredActive
            desiredActive != null -> normalizedVisible.firstOrNull()
            activeLayer == null -> null
            normalizedVisible.contains(activeLayer!!) -> activeLayer
            else -> normalizedVisible.firstOrNull()
        }
        layerDebugLog(
            "palette applyLayerState nextVisible=${normalizedVisible.joinToString(",")} " +
                "desiredActive=${desiredActive ?: "null"} activeCandidate=${activeCandidate ?: "null"} " +
                "prevVisible=${visibleLayers.joinToString(",")} prevActive=${activeLayer ?: "null"}"
        )

        setVisibleLayers(normalizedVisible.toSet(), animated = true, duration = animateDuration)
        if (activeCandidate != activeLayer) {
            setActiveLayer(activeCandidate, animated = true, duration = animateDuration)
        }
    }

    fun renderList() {
        val allLayers = getAllLayers().toList().sorted()
        val available = allLayers.toSet()
        val visibleNow = visibleLayers.filter { available.contains(it) }.toMutableSet().ifEmpty {
            if (allLayers.isNotEmpty()) mutableSetOf(allLayers.first()) else mutableSetOf("default")
        }
        val activeNow = activeLayer?.takeIf { visibleNow.contains(it) }

        list.innerHTML = ""
        if (allLayers.isEmpty()) {
            val empty = document.createElement("div") as HTMLElement
            empty.textContent = "No layers found"
            empty.style.opacity = "0.7"
            list.appendChild(empty)
            return
        }

        allLayers.forEach { layerName ->
            val row = document.createElement("div") as HTMLElement
            row.style.apply {
                display = "grid"
                setProperty("grid-template-columns", "1fr auto auto")
                setProperty("gap", "6px")
                alignItems = "center"
                padding = "4px 6px"
                setProperty("border", "none")
                setProperty("border-bottom", "1px solid rgba(255,255,255,0.03)")
                backgroundColor = if (layerName == activeNow) "#22272d" else "transparent"
            }

            val name = document.createElement("div") as HTMLElement
            name.textContent = layerName
            name.style.apply {
                setProperty("overflow", "hidden")
                setProperty("text-overflow", "ellipsis")
                setProperty("white-space", "nowrap")
                fontWeight = if (layerName == activeNow) "400" else "100"
            }

            val eye = document.createElement("button") as HTMLButtonElement
            eye.innerHTML = """
                <svg width="18" height="12" viewBox="0 0 18 12" xmlns="http://www.w3.org/2000/svg">
                    <path d="M1 6c1.8-3 4.2-4.5 8-4.5s6.2 1.5 8 4.5c-1.8 3-4.2 4.5-8 4.5S2.8 9 1 6Z" fill="none" stroke="currentColor" stroke-width="1.3"/>
                    <circle cx="9" cy="6" r="2.3" fill="currentColor"/>
                </svg>
            """.trimIndent()
            fun updateEye(isVisible: Boolean) {
                eye.style.opacity = if (isVisible) "1" else "0.25"
            }
            eye.title = "Toggle visibility (shift+click to solo)"
            eye.style.apply {
                background = "none"
                border = "none"
                color = "#f0f0f0"
                padding = "2px"
                cursor = "pointer"
                lineHeight = "1"
            }
            updateEye(visibleNow.contains(layerName))
            eye.onclick = { event ->
                val shiftSolo = (event.asDynamic().shiftKey as? Boolean) == true
                val nextVisible = if (shiftSolo) {
                    mutableSetOf(layerName)
                } else {
                    val next = visibleLayers.toMutableSet()
                    if (visibleNow.contains(layerName)) next.remove(layerName) else next.add(layerName)
                    next
                }
                if (nextVisible.isEmpty()) {
                    updateEye(true)
                } else {
                    val nextActive = when {
                        activeLayer == null -> null
                        nextVisible.contains(activeLayer!!) -> activeLayer
                        else -> nextVisible.firstOrNull()
                    }
                    applyLayerState(nextVisible, nextActive)
                    renderList()
                }
                null
            }

            val activeToggle = document.createElement("input") as HTMLInputElement
            activeToggle.type = "radio"
            activeToggle.name = "active-layer"
            activeToggle.title = "Set active layer"
            activeToggle.checked = layerName == activeNow
            activeToggle.style.apply {
                setProperty("transform", "translateY(-3px)")
            }
            activeToggle.onchange = {
                if (activeToggle.checked) {
                    val nextVisible = visibleLayers.toMutableSet()
                    nextVisible.add(layerName)
                    applyLayerState(nextVisible, layerName)
                    renderList()
                }
                null
            }

            row.append(name, eye, activeToggle)
            list.appendChild(row)
        }
    }

    val refreshButton = document.createElement("button") as HTMLButtonElement
    refreshButton.innerHTML = """
        <svg width="16" height="16" viewBox="0 0 16 16" xmlns="http://www.w3.org/2000/svg">
            <path d="M3.2 6.4a4.6 4.6 0 0 1 7.4-1.6h-1v1.5l3.2-2.2-3.2-2.2v1.4A6.2 6.2 0 0 0 2 6.5c0 3.4 2.7 6.1 6.1 6.1 1.6 0 3-.6 4.1-1.7l-1.1-1.1A4.6 4.6 0 0 1 2 6.5l1.2-.1Z" fill="currentColor"/>
        </svg>
    """.trimIndent()
    refreshButton.title = "Refresh layers"
    refreshButton.style.apply {
        background = "none"
        border = "none"
        color = "#f0f0f0"
        padding = "4px"
        cursor = "pointer"
        lineHeight = "1"
    }
    refreshButton.onclick = {
        renderList()
        null
    }

    controls.append(refreshButton)
    contentRoot.append(controls, list)
    renderList()
}

fun handleOutputAreaRequest(box: HTMLElement, set: Set_output) {

    val outputArea = box.getElementsByClassName("outputArea").asList()[0] as HTMLElement
    outputArea.style.visibility = "visible"

    val contain = outputArea.querySelector(".disclosure-group")
    val di = js("window.disclosureInterface")
    if (di == null) {
        console.info("[output] disclosureInterface not ready yet; skipping channel ${set.channel}")
        return
    }
    val inter = di(contain!!)

    val channelId = set.channel
    val title = if (set.channel == "ai-chat") "AI Chat" else set.channel
    val contentRoot = (inter.upsertDisclosure as (String, String, String) -> Element).invoke(
        channelId,
        title,
        ""
    )
    val isLayersChannel = set.channel.equals("layers", ignoreCase = true)

    if (set.channel == "ai-chat") {
        contentRoot.classList.add("ai-chat-content")
        aiChatUpdate(contentRoot, set.uid, set.value)
        (inter.open as (String) -> Unit).invoke(channelId)
    } else if (isLayersChannel) {
        renderLayersPalette(contentRoot as HTMLElement)
        (inter.open as (String) -> Unit).invoke(channelId)
    } else {
        insertHTMLWithScripts(contentRoot, set.value)
    }

}

private fun disclosureExists(container: Element, channelId: String): Boolean {
    val disclosures = container.querySelectorAll(".disclosure").asList()
    return disclosures.any { (it.asDynamic().__disclosureId as? String) == channelId }
}

fun handleOutputAreaControl(box: HTMLElement, control: OutputControl) {
    val outputArea = box.getElementsByClassName("outputArea").asList().firstOrNull() as? HTMLElement ?: return
    val contain = outputArea.querySelector(".disclosure-group") ?: return
    val di = js("window.disclosureInterface")
    if (di == null) {
        console.info("[output] disclosureInterface not ready yet; skipping control ${control.action}")
        return
    }
    val inter = di(contain)
    val channelId = control.channel?.trim()?.takeIf { it.isNotEmpty() }
    val action = control.action.trim().lowercase()

    when (action) {
        "open" -> {
            if (channelId == null) return
            outputArea.style.visibility = "visible"
            outputArea.style.height = "auto"
            if (!disclosureExists(contain, channelId)) {
                val title = if (channelId == "ai-chat") "AI Chat" else channelId
                (inter.upsertDisclosure as (String, String, String) -> Element).invoke(channelId, title, "")
            }
            (inter.open as (String) -> Unit).invoke(channelId)
        }
        "close" -> {
            if (channelId == null) return
            (inter.close as (String) -> Unit).invoke(channelId)
        }
        "clear" -> {
            if (channelId == null || channelId == "nested") return
            (inter.remove as (String) -> Unit).invoke(channelId)
        }
        "clear-all" -> {
            val ids = contain.querySelectorAll(".disclosure").asList().mapNotNull {
                it.asDynamic().__disclosureId as? String
            }
            ids.forEach { id ->
                if (id != "nested") {
                    (inter.remove as (String) -> Unit).invoke(id)
                }
            }
        }
        else -> console.info("[output] unknown output-control action ${control.action}")
    }
}

fun handleCustomizationRequest(box: HTMLElement, code: Set_string) {
    console.info("[customize] executing customization code for ${code.property}")
    val f = eval(
        """
        new Function("customize", `${code.value}`)
    """.trimIndent()
    ).unsafeCast<dynamic>()
    f.call(box)
}

@OptIn(DelicateCoroutinesApi::class)
fun handleEvalRequest(box: HTMLElement, code: String) {
    console.info("[customize] evaluating module code")
    insertHTMLWithScripts(box, "<script type='module'>$code</script>", isModule = true)
}

// ── Embedded editor in output area ─────────────────────────────────────

/**
 * Open an embedded Monaco editor for a given property inside a box's output area.
 * Creates a disclosure panel with the editor inside.
 */
fun openEditorInOutput(uid: String, property: String) {
    val box = findBoxByUid(uid) ?: run {
        console.warn("[embedded-editor] box not found: $uid")
        return
    }

    val emb = js("window.embeddedEditors") ?: run {
        console.warn("[embedded-editor] embeddedEditors module not loaded")
        return
    }

    // Check if an editor already exists for this (uid, property)
    val existing = emb.getFor(uid, property)
    if (existing != null) {
        js("if (typeof window.notify === 'function') window.notify('Editor already open for ' + property, 'command')")
        return
    }

    val outputArea = box.getElementsByClassName("outputArea").asList().firstOrNull() as? HTMLElement ?: return
    outputArea.style.visibility = "visible"
    val contain = outputArea.querySelector(".disclosure-group") ?: return
    val di = js("window.disclosureInterface") ?: return
    val inter = di(contain)

    val channelId = "editor:$property"
    val language = (emb.languageFor as Function<*>).asDynamic().call(emb, property) as? String ?: "plaintext"
    val title = "\u270E $property ($language)"

    // Create the disclosure panel
    val contentRoot = (inter.upsertDisclosure as (String, String, String) -> Element).invoke(channelId, title, "")

    // Read current value from the box DOM element
    val currentValue = box.asDynamic()[property] as? String ?: ""

    // Create a container div for Monaco
    val editorContainer = document.createElement("div") as HTMLElement
    editorContainer.style.width = "100%"
    editorContainer.style.height = "100%"
    editorContainer.style.minHeight = "120px"
    editorContainer.asDynamic().style.overflow = "hidden"
    contentRoot.innerHTML = ""
    contentRoot.appendChild(editorContainer)

    // Override disclosure-content font-size — it defaults to 10px in CSS,
    // which Monaco inherits for gutter measurements. Set it to match the
    // editor's configured fontSize so line numbers and controls are sized
    // correctly.
    (contentRoot as HTMLElement).style.fontSize = "13px"
    (contentRoot as HTMLElement).style.padding = "0px"
    (contentRoot as HTMLElement).style.background = "#000000"

    // Create the embedded editor
    val editorId = (emb.create as Function<*>).asDynamic().call(emb, editorContainer, uid, property, language, currentValue) as? String

    if (editorId != null) {
        // The embeddedEditors.js module tags the container with data-embeddedEditorId
        // which the disclosure remove() hook reads for cleanup.
        // Also tag the disclosure content wrapper (contentRoot) so the remove hook can find it
        (contentRoot as HTMLElement).asDynamic().dataset.embeddedEditorId = editorId
    }

    // Open the disclosure
    (inter.open as (String) -> Unit).invoke(channelId)

    // Force Monaco to re-layout after the disclosure has opened and the DOM has settled
    if (editorId != null) {
        val entry = emb.getFor(uid, property)
        if (entry != null) {
            kotlinx.browser.window.requestAnimationFrame {
                entry.editor.layout()
            }
        }
    }
}

