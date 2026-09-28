package afield

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLLinkElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event

/**
 * The drawing layer: an Excalidraw scene laid over the whole box canvas, for notation.
 *
 * - **One scene per document**, held as `.excalidraw` JSON in the root box's `drawing` property.
 *   That property rides the generic resync/persistence machinery, like `bookmarks` (see the
 *   `drawing` entries in `Box.uiResyncProperties` and `ioPersistentProperties`). The root box never
 *   writes sidecars (`SavingContext.toTransfer`), so the scene is inline in `metadata.edn`; the
 *   string is a complete `.excalidraw` file, which Excalidraw itself can open.
 * - **The camera is Field's.** Scene coordinates are Field world coordinates. Excalidraw draws
 *   `(p + scroll) * zoom` and Field draws `p * mainZoom + mainTranslation`, so Excalidraw's zoom
 *   is [mainZoom] and its scroll is `mainTranslation / mainZoom`. Panning or zooming either side
 *   moves the other (`applyViewportTranslation` → [drawingLayerViewportChanged]; Excalidraw's
 *   `onScrollChange` → [panViewportForDrawingLayer]).
 * - **Modal input.** Inactive, the layer is visible but `pointer-events: none` and Excalidraw is in
 *   view mode, so Field behaves exactly as before. Active (`toggle-drawing-layer`, Alt+D), the
 *   layer takes the pointer and keyboard focus, and key events are kept from bubbling out to
 *   Field's window/document shortcuts.
 *
 * The Excalidraw bundle (React included) is ~750 KB before lazy chunks, so it is loaded on first
 * use: the first toggle, or the first time a document arrives with a non-empty drawing. It is
 * built from `tools/excalidraw-bundle/` into `src/jsMain/resources/excalidraw/`.
 */

private const val DRAWING_PROPERTY = "drawing"
private const val TOGGLE_COMMAND = "toggle-drawing-layer"
private const val BUNDLE_URL = "/excalidraw/field-excalidraw.js"
private const val CSS_URL = "/excalidraw/field-excalidraw.css"

var drawingLayerActive = false
    private set

private var host: HTMLElement? = null
private var handle: dynamic = null
private var loading = false

/** Latest scene from the server, held until the bundle has loaded. */
private var pendingRemoteScene: String? = null

/** The last scene this client sent, so the server's echo of it is not re-applied. */
private var lastSentScene: String? = null

private fun ensureHost(): HTMLElement {
    host?.let { return it }
    val el = document.createElement("div") as HTMLElement
    el.id = "field-drawing-layer"
    el.className = "field-drawing-layer field-drawing-inactive"
    // Key events Excalidraw has handled stop here, before Field's window/document shortcuts see
    // them. React listens on its own root (a child of this element), so it gets them first.
    // Capture-phase Field listeners (bookmarks' F-keys, keycast) still run, which is harmless.
    val stopIfActive: (Event) -> Unit = { e -> if (drawingLayerActive) e.stopPropagation() }
    for (type in listOf("keydown", "keyup", "keypress")) el.addEventListener(type, stopIfActive)
    document.body!!.appendChild(el)
    host = el
    return el
}

private fun ensureLoaded(then: () -> Unit = {}) {
    if (handle != null) {
        then(); return
    }
    if (loading) return
    loading = true

    if (document.querySelector("link[data-field-drawing]") == null) {
        val link = document.createElement("link") as HTMLLinkElement
        link.rel = "stylesheet"
        link.href = CSS_URL
        link.setAttribute("data-field-drawing", "1")
        document.head!!.appendChild(link)
    }
    // Excalidraw resolves its fonts against this; without it they come from a CDN.
    window.asDynamic().EXCALIDRAW_ASSET_PATH = "/excalidraw/"

    val el = ensureHost()
    val load: dynamic = js("(url) => import(url)")
    load(BUNDLE_URL).then({ module: dynamic ->
        val opts: dynamic = js("({})")
        opts.initialScene = pendingRemoteScene ?: currentRootDrawing()
        opts.initialCamera = arrayOf(mainTranslation[0] / mainZoom, mainTranslation[1] / mainZoom, mainZoom)
        opts.onCamera = { scrollX: Double, scrollY: Double, zoom: Double ->
            panViewportForDrawingLayer(scrollX * zoom, scrollY * zoom, zoom)
        }
        opts.onScene = { json: String -> sendScene(json) }
        handle = module.mountDrawingLayer(el, opts)
        pendingRemoteScene = null
        loading = false
        handle.setActive(drawingLayerActive)
        then()
    }, { error: dynamic ->
        loading = false
        console.error("[drawing] could not load $BUNDLE_URL", error)
        notify("Could not load the drawing layer (see console)", "error")
    })
}

private fun currentRootDrawing(): String? = findRootBoxElement()?.asDynamic()?.drawing as? String

private fun findRootBoxElement(): HTMLElement? =
    document.body?.getElementsByClassName("box")?.asList()?.firstOrNull { it.asDynamic().uid == "root" } as? HTMLElement

private fun sendScene(json: String) {
    lastSentScene = json
    findRootBoxElement()?.asDynamic()?.drawing = json
    // The exported `send(name, payload)` stamps clientId/clientSequence, so acceptSetStringSync can
    // drop stale echoes of our own writes.
    val payload: dynamic = js("({})")
    payload.uid = "root"
    payload.property = DRAWING_PROPERTY
    payload.value = json
    send("set-string", payload)
}

/** Called from the frontend `set-string` handler for `root.drawing`. */
fun onRemoteDrawingScene(json: String) {
    if (json == lastSentScene) return
    val h = handle
    when {
        h != null -> h.applyRemoteScene(json)
        else -> {
            pendingRemoteScene = json
            // A document that already has notation shows it without waiting for a toggle.
            if (json.isNotBlank()) ensureLoaded()
        }
    }
}

/** Called by every writer of [mainTranslation]: Field panned or zoomed, so the drawing follows. */
fun drawingLayerViewportChanged(x: Double, y: Double) {
    handle?.setCamera(x / mainZoom, y / mainZoom, mainZoom)
}

fun setDrawingLayerActive(active: Boolean) {
    drawingLayerActive = active
    val el = ensureHost()
    el.classList.toggle("field-drawing-active", active)
    el.classList.toggle("field-drawing-inactive", !active)
    ensureLoaded { handle?.setActive(active) }
    notify(if (active) "Drawing layer: on (Alt+D to return)" else "Drawing layer: off", "drawing")
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun toggleDrawingLayer() = setDrawingLayerActive(!drawingLayerActive)

/** For tests and the backdoor: `window.drawingLayerState()`. */
@OptIn(ExperimentalJsExport::class)
@JsExport
fun drawingLayerState(): dynamic {
    val h = handle
    val state: dynamic = if (h != null) h.state() else js("({ ready: false })")
    state.active = drawingLayerActive
    state.loading = loading
    return state
}

fun initializeDrawingLayer() {
    // Owns its chord in the capture phase, as the bookmarks do: the key must work while Excalidraw
    // has focus, where the canvas keybinding would never see it.
    window.addEventListener("keydown", { event ->
        if (Keybindings.matches(event, TOGGLE_COMMAND)) {
            event.preventDefault()
            event.stopImmediatePropagation()
            toggleDrawingLayer()
        }
    }, true)

    val action: dynamic = js("({})")
    action.id = TOGGLE_COMMAND
    action.label = "Toggle Drawing Layer"
    action.detail = "Draw and annotate over the canvas with Excalidraw"
    // No `keys`: the capture listener above owns the chord (see Keybindings.virtualCommands).
    action.run = { toggleDrawingLayer() }
    val builtin = allActions.builtin ?: js("[]")
    builtin.push(action)
    allActions.builtin = builtin
    updateActions()

    window.asDynamic().toggleDrawingLayer = ::toggleDrawingLayer
    window.asDynamic().drawingLayerState = ::drawingLayerState
}
