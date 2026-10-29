package afield.app.canvas

import afield.app.client.FieldPanel
import afield.app.client.activeLayer
import afield.app.client.allActions
import afield.app.client.getAllLayers
import afield.app.client.selected
import afield.app.client.updateActions
import afield.app.client.updateAllBoxVisibility
import afield.app.client.visibleLayers
import afield.app.protocol.Message
import afield.app.protocol.Set_string
import afield.app.protocol.payload
import afield.app.protocol.send
import applyLayerSelection
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/**
 * The layers panel (Alt+L, palette: Show Layers Panel): every layer any box mentions, with its box
 * count, a visibility eye (shift-click solos), a radio for the active layer, Solo, and a button that moves the
 * selection onto the active layer.
 *
 * It does what the `showLayers()` palette does, but floats over the canvas on [FieldPanel], so
 * hiding a layer can never hide the panel itself. Both go through [applyLayerSelection].
 */

private const val EYE_ICON = """<svg width="18" height="12" viewBox="0 0 18 12" xmlns="http://www.w3.org/2000/svg"><path d="M1 6c1.8-3 4.2-4.5 8-4.5s6.2 1.5 8 4.5c-1.8 3-4.2 4.5-8 4.5S2.8 9 1 6Z" fill="none" stroke="currentColor" stroke-width="1.3"/><circle cx="9" cy="6" r="2.3" fill="currentColor"/></svg>"""

private var layersPanel: FieldPanel? = null
private var layersPanelTimer: Int? = null
private var layersPanelSignature: String? = null

private fun ensureLayersPanel(): FieldPanel = layersPanel ?: FieldPanel("layers-panel", "Layers") {
    layersPanelTimer?.let { window.clearInterval(it) }
    layersPanelTimer = null
}.also { layersPanel = it }

@OptIn(ExperimentalJsExport::class)
@JsExport
fun toggleLayersPanel() {
    val panel = ensureLayersPanel()
    if (panel.isVisible) panel.hide() else showLayersPanel()
}

fun showLayersPanel() {
    val panel = ensureLayersPanel()
    if (!panel.isVisible) {
        panel.show()
        // Layer membership is a box property that can change from anywhere; poll while open.
        layersPanelTimer = window.setInterval({ refreshLayersPanel() }, 1000)
    }
    refreshLayersPanel(force = true)
}

private fun layersOf(box: HTMLElement): List<String> =
    ((box.asDynamic().box_layers as? String) ?: "default").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        .ifEmpty { listOf("default") }

private fun canvasBoxes(): List<HTMLElement> =
    (document.body?.getElementsByClassName("box")?.asList() ?: emptyList())
        .mapNotNull { it as? HTMLElement }
        .filter { (it.asDynamic().uid as? String) != "root" }

fun refreshLayersPanel(force: Boolean = false) {
    val panel = layersPanel ?: return
    if (!panel.isVisible) return
    val boxes = canvasBoxes()
    // getAllLayers() only sees layers a box names; a box that names none is on "default".
    val all = (getAllLayers() + boxes.flatMap { layersOf(it) }).filter { it.isNotBlank() }.distinct().sorted()
    val counts = all.associateWith { layer -> boxes.count { layer in layersOf(it) } }
    val marks = drawingElementCount()
    val signature = listOf(all, counts, visibleLayers.sorted(), activeLayer, selected.size, marks, drawingLayerVisible).toString()
    if (!force && signature == layersPanelSignature) return
    layersPanelSignature = signature

    val el = FieldPanel::el
    val shown = all.filter { it in visibleLayers }
    panel.status.textContent = "${all.size} layer${if (all.size == 1) "" else "s"} · ${shown.size} shown · " +
        "active: ${activeLayer ?: "none"}"
    panel.body.innerHTML = ""

    all.forEach { layer ->
        val isVisible = layer in visibleLayers
        val isActive = layer == activeLayer
        val card = el("div", "field-panel-card layer-row" + if (isVisible) "" else " is-empty", null)
        val head = el("div", "field-panel-card-head", null)

        // The same eye and radio the showLayers() palette uses, in fixed columns so every row lines up.
        val eye = panelButton("", "Show or hide this layer (shift-click to solo it)") {}
        eye.classList.add("layer-eye")
        if (isVisible) eye.classList.add("is-on")
        eye.innerHTML = EYE_ICON
        eye.addEventListener("click", { event ->
            val next = if (event.asDynamic().shiftKey == true) mutableSetOf(layer) else visibleLayers.toMutableSet().also {
                if (isVisible) it.remove(layer) else it.add(layer)
            }
            // The last visible layer cannot be hidden; there would be nothing left to look at.
            if (next.isNotEmpty()) applyLayerSelection(next, if (isActive && layer !in next) null else activeLayer)
            refreshLayersPanel(force = true)
        })
        head.appendChild(eye)

        val radio = document.createElement("input") as org.w3c.dom.HTMLInputElement
        radio.type = "radio"
        radio.name = "layers-panel-active"
        radio.className = "layer-active"
        radio.title = "Make this the active layer (shows it if hidden)"
        radio.checked = isActive
        radio.addEventListener("change", {
            if (radio.checked) applyLayerSelection(visibleLayers + layer, layer)
            refreshLayersPanel(force = true)
        })
        head.appendChild(radio)

        head.appendChild(el("span", if (isActive) "layer-name is-active" else "layer-name", layer))
        val count = counts[layer] ?: 0
        head.appendChild(el("span", "field-panel-meta", if (count == 1) "1 box" else "$count boxes"))
        head.appendChild(panelButton("Solo", "Show only this layer and make it active") {
            applyLayerSelection(setOf(layer), layer)
            refreshLayersPanel(force = true)
        })
        card.appendChild(head)
        panel.body.appendChild(card)
    }

    // The drawing layer (Excalidraw) is a layer too once it holds something: an eye, but no
    // radio or Solo, since boxes cannot be put on it.
    if (marks > 0) {
        val card = el("div", "field-panel-card layer-row" + if (drawingLayerVisible) "" else " is-empty", null)
        val head = el("div", "field-panel-card-head", null)
        val eye = panelButton("", "Show or hide the drawing (Alt+D draws)") {
            setDrawingLayerVisible(!drawingLayerVisible)
            refreshLayersPanel(force = true)
        }
        eye.classList.add("layer-eye")
        if (drawingLayerVisible) eye.classList.add("is-on")
        eye.innerHTML = EYE_ICON
        head.appendChild(eye)
        head.appendChild(el("span", "", null))
        head.appendChild(el("span", "layer-name", "drawing"))
        head.appendChild(el("span", "field-panel-meta", if (marks == 1) "1 mark" else "$marks marks"))
        head.appendChild(el("span", "", null))
        card.appendChild(head)
        panel.body.appendChild(card)
    }

    val footer = el("div", "field-panel-actions layer-footer", null)
    footer.appendChild(panelButton("Show all", "Show every layer") {
        applyLayerSelection(all.toSet().ifEmpty { setOf("default") }, activeLayer)
        setDrawingLayerVisible(true)
        refreshLayersPanel(force = true)
    })
    val target = activeLayer
    val move = panelButton(
        if (target == null || selected.isEmpty()) "Move selection to active layer"
        else "Move ${selected.size} selected to \"$target\"",
        "Put the selected boxes on the active layer (and only on it)"
    ) {
        if (target != null) moveSelectionToLayer(target)
    }
    if (target == null || selected.isEmpty()) move.setAttribute("disabled", "")
    footer.appendChild(move)
    panel.body.appendChild(footer)
}

private fun panelButton(label: String, title: String, action: () -> Unit): HTMLElement =
    FieldPanel.el("button", text = label).also {
        it.setAttribute("type", "button")
        it.title = title
        it.addEventListener("click", { action() })
    }

/** Put every selected box on [layer], and only on it (the panel's button and the palette command). */
fun moveSelectionToLayer(layer: String) {
    selected.forEach { box ->
        val uid = box.asDynamic().uid as? String ?: return@forEach
        send(Message("set-string").payload(Set_string(uid, "layers", layer)))
        box.asDynamic().box_layers = layer
    }
    updateAllBoxVisibility(animated = true, duration = 300.0)
    refreshLayersPanel(force = true)
}

fun registerLayersPanelAction() {
    val action: dynamic = js("({})")
    action.id = "show-layers-panel"
    action.label = "Show Layers Panel"
    action.detail = "Show, hide, solo and activate layers"
    action.keys = "alt+l"
    action.run = { toggleLayersPanel() }
    val builtin = allActions.builtin ?: js("[]")
    builtin.push(action)
    allActions.builtin = builtin
    updateActions()
}
