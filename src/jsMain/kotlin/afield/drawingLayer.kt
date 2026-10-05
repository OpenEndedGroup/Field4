package afield

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLLinkElement
import org.w3c.dom.asList
import org.w3c.dom.events.Event
import kotlin.js.json

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
 * - **One mount per pane.** On a split canvas (`canvasCamera.kt`) each pane has its own Excalidraw,
 *   sized to the pane and following that pane's camera. Every mount holds the whole scene; a pane
 *   only ever shows its own quadrant, so no clipping is needed. A change in one mount goes to the
 *   server and straight to the others. Only the main (bottom-right) pane shows Excalidraw's
 *   toolbar; the tool travels with the pointer into whichever pane it enters.
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
private var loading = false

/** The loaded bundle, kept to mount one Excalidraw per pane. */
private var bundle: dynamic = null

/** One mount per pane, in [CanvasCamera.panes] order; the last is the main pane's. */
private class PaneMount(val host: HTMLElement, val handle: dynamic)

private val mounts = mutableListOf<PaneMount>()

/** The main pane's mount: the one with the toolbar, and the one tests read. */
private val handle: dynamic get() = mounts.lastOrNull()?.handle

/** Index of the mount the pointer is over, while the layer is active. */
private var hoverMount = -1

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

    ensureHost()
    val load: dynamic = js("(url) => import(url)")
    load(BUNDLE_URL).then({ module: dynamic ->
        bundle = module
        syncPaneMounts()
        pendingRemoteScene = null
        loading = false
        then()
    }, { error: dynamic ->
        loading = false
        console.error("[drawing] could not load $BUNDLE_URL", error)
        notify("Could not load the drawing layer (see console)", "error")
    })
}

private fun currentRootDrawing(): String? = findRootBoxElement()?.asDynamic()?.drawing as? String

/** Excalidraw's camera for [pane]: its container sits at the corner of the pane's inner rect. */
private fun cameraFor(pane: CanvasPane): Array<Double> {
    val r = pane.innerRect
    return arrayOf((pane.tx - r.x) / pane.zoom, (pane.ty - r.y) / pane.zoom, pane.zoom)
}

/**
 * Makes the mounts match the panes (one each, the main pane's mount kept across splits so its
 * history survives), sizes each to its pane and points it at its pane's camera.
 */
private fun syncPaneMounts() {
    val module = bundle ?: return
    val layer = ensureHost()
    val panes = CanvasCamera.panes
    if (mounts.size != panes.size) {
        val main = mounts.removeLastOrNull()
        // (`dynamic` has no `let`: on a dynamic receiver it would call a JS method of that name.)
        val mainApi: dynamic = main?.handle?.api()
        val scene: String? = if (mainApi != null) {
            JSON.stringify(json("elements" to mainApi.getSceneElementsIncludingDeleted()))
        } else {
            pendingRemoteScene ?: currentRootDrawing()
        }
        mounts.forEach { it.handle.unmount(); it.host.remove() }
        mounts.clear()
        for (index in panes.indices) {
            val isMain = index == panes.lastIndex
            if (isMain && main != null) {
                mounts += main
                continue
            }
            val el = document.createElement("div") as HTMLElement
            el.className = "field-drawing-pane"
            // Hosts in pane order: the kept main mount stays last.
            layer.insertBefore(el, main?.host)
            layoutMountHost(el, panes[index], isMain)
            val opts: dynamic = js("({})")
            opts.initialScene = scene
            opts.initialCamera = cameraFor(panes[index])
            opts.onCamera = { scrollX: Double, scrollY: Double, zoom: Double ->
                CanvasCamera.panes.getOrNull(index)?.let { pane ->
                    val r = pane.innerRect
                    panViewportForDrawingLayer(scrollX * zoom + r.x, scrollY * zoom + r.y, zoom, pane)
                }
            }
            opts.onScene = { json: String -> sendScene(json, from = index) }
            opts.onSelection = { ids: Array<String> -> sendSelection(ids) }
            val handle = module.mountDrawingLayer(el, opts)
            handle.setActive(drawingLayerActive)
            mounts += PaneMount(el, handle)
        }
        hoverMount = -1
    }
    mounts.forEachIndexed { index, mount ->
        val pane = panes.getOrNull(index) ?: return@forEachIndexed
        layoutMountHost(mount.host, pane, index == panes.lastIndex)
        val (x, y, zoom) = cameraFor(pane)
        mount.handle.setCamera(x, y, zoom)
    }
}

private fun layoutMountHost(el: HTMLElement, pane: CanvasPane, isMain: Boolean) {
    val r = pane.innerRect
    el.style.left = "${r.x}px"
    el.style.top = "${r.y}px"
    el.style.width = "${r.w}px"
    el.style.height = "${r.h}px"
    // Rounded like the pane's frame, so marks stop at the dividers' curves too.
    el.style.borderRadius = if (CanvasCamera.isSplit) pane.frameRadius() else ""
    // Every mount holds the whole scene; over a narrow band's grey end the next band's marks would
    // show, so the host is cut short there.
    val (emptyRight, emptyBottom) = if (CanvasCamera.isSplit) pane.emptyEnds() else listOf(0.0, 0.0)
    el.style.asDynamic().clipPath = if (emptyRight > 0.0 || emptyBottom > 0.0) "inset(0 ${emptyRight}px ${emptyBottom}px 0)" else ""
    el.classList.toggle("field-drawing-mirror", !isMain)
}

/**
 * While the layer is active, the pointer entering a pane carries the current tool into that
 * pane's Excalidraw (only the main pane shows a toolbar) and gives it the keyboard.
 */
private fun followPointerAcrossPanes(clientX: Double, clientY: Double) {
    if (!drawingLayerActive || mounts.size < 2) return
    val index = CanvasCamera.panes.indexOf(CanvasCamera.paneAt(clientX, clientY))
    if (index < 0 || index == hoverMount || index >= mounts.size) return
    val from = mounts.getOrNull(if (hoverMount >= 0) hoverMount else mounts.lastIndex)?.handle?.api()
    val to = mounts[index].handle.api()
    hoverMount = index
    if (from == null || to == null || from == to) return
    val tool = from.getAppState().activeTool
    if (tool.type != "custom") {
        to.setActiveTool(json("type" to tool.type, "locked" to (tool.locked == true)))
    }
    (mounts[index].host.querySelector(".excalidraw-container") as? HTMLElement)?.focus()
}

private fun findRootBoxElement(): HTMLElement? =
    document.body?.getElementsByClassName("box")?.asList()?.firstOrNull { it.asDynamic().uid == "root" } as? HTMLElement

/**
 * The scene goes to the server's `notation-scene` handler rather than as a raw `set-string`: the
 * server *merges* it into the authoritative scene by element version (so a mark code wrote while
 * this client was drawing survives), and that is also where a person's changes reach `onNotation`
 * reactions and arrow auto-anchoring (`NotationServer.kt`). The merged scene comes back to every
 * client as an ordinary `set-string` of `root.drawing`.
 */
private fun sendScene(json: String, from: Int) {
    // The other panes' mounts show the change at once, without waiting for the server's echo.
    mounts.forEachIndexed { index, mount -> if (index != from) mount.handle.applyRemoteScene(json) }
    lastSentScene = json
    findRootBoxElement()?.asDynamic()?.drawing = json
    val payload: dynamic = js("({})")
    payload.scene = json
    send("notation-scene", payload)
}

private fun sendSelection(ids: Array<String>) {
    val payload: dynamic = js("({})")
    payload.ids = ids
    send("notation-selection", payload)
}

/** Called from the frontend `set-string` handler for `root.drawing`. */
fun onRemoteDrawingScene(json: String) {
    if (json == lastSentScene) return
    when {
        mounts.isNotEmpty() -> mounts.forEach { it.handle.applyRemoteScene(json) }
        else -> {
            pendingRemoteScene = json
            // A document that already has notation shows it without waiting for a toggle.
            if (json.isNotBlank()) ensureLoaded()
        }
    }
}

/**
 * Called by `applyViewportTranslation`, the camera's one writer: Field panned, zoomed or changed
 * its split, so every pane's drawing follows.
 */
fun drawingLayerViewportChanged() {
    syncPaneMounts()
}

fun setDrawingLayerActive(active: Boolean) {
    drawingLayerActive = active
    val el = ensureHost()
    el.classList.toggle("field-drawing-active", active)
    el.classList.toggle("field-drawing-inactive", !active)
    ensureLoaded { mounts.forEach { it.handle.setActive(active) } }
    hoverMount = -1
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
    state.mounts = mounts.size
    state.hoverMount = hoverMount
    state.panes = mounts.map { it.handle.state() }.toTypedArray()
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
    window.addEventListener("pointermove", { event ->
        val e = event.asDynamic()
        if ((e.buttons as Number).toInt() == 0) followPointerAcrossPanes((e.clientX as Number).toDouble(), (e.clientY as Number).toDouble())
    }, true)

    val action: dynamic = js("({})")
    action.id = TOGGLE_COMMAND
    action.label = "Toggle Drawing Layer"
    action.detail = "Draw and annotate over the canvas with Excalidraw"
    // No `keys`: the capture listener above owns the chord (see Keybindings.virtualCommands).
    action.run = { toggleDrawingLayer() }
    val anchor: dynamic = js("({})")
    anchor.id = "anchor-drawing-to-boxes"
    anchor.label = "Anchor Drawing to Boxes"
    anchor.detail = "Selected marks (or every unanchored one) follow the box under them; arrow ends anchor separately"
    anchor.run = { send("notation-anchor-selection", js("({})")) }

    val builtin = allActions.builtin ?: js("[]")
    builtin.push(action)
    builtin.push(anchor)
    allActions.builtin = builtin
    updateActions()

    window.asDynamic().toggleDrawingLayer = ::toggleDrawingLayer
    window.asDynamic().drawingLayerState = ::drawingLayerState
    // For tests: choose a tool in the main pane, as its toolbar would.
    window.asDynamic().drawingLayerSetTool = { type: String -> handle?.api()?.setActiveTool(json("type" to type)) }
    // For tests and the backdoor: the live elements as plain objects.
    window.asDynamic().drawingLayerScene = {
        val api = handle?.api()
        if (api == null) js("[]") else JSON.parse<dynamic>(JSON.stringify(api.getSceneElements()))
    }
}
