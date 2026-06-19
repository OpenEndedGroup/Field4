package afield

import handleOutputAreaRequest
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.html.dom.append
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import openEditorInOutput
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.events.MouseEvent
import web.url.URL
import kotlin.js.Date
import kotlin.math.abs

var lastMouse = 0.0 to 0.0

external fun showPickMenu_custom(options: dynamic): dynamic

private fun browserPrompt(message: String, defaultValue: String): String? =
    window.asDynamic().prompt(message, defaultValue)

private fun defaultMp4Path(kind: String): String {
    val stamp = Date.now().toLong().toString()
    return "/tmp/field4-$kind-$stamp.mp4"
}

private fun promptMp4Target(kind: String): Pair<String, Double>? {
    val path = browserPrompt("Output .mp4 path for $kind capture", defaultMp4Path(kind))?.trim()
        ?.takeIf { it.isNotEmpty() } ?: return null

    val durationText = browserPrompt("Duration in seconds", "3")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val duration = durationText.toDoubleOrNull()
    if (duration == null || duration <= 0.0) {
        notify("Duration must be a positive number", "error")
        return null
    }

    return path to duration
}

private fun requireSingleSelectedBox(): dynamic? {
    if (selected.size != 1) {
        notify("Select exactly one box", "command")
        return null
    }
    return selected.first().unsafeCast<dynamic>()
}

private fun sendBoxVideo(request: AgentBoxVideo) {
    val callbackName = "agent-box-video-${Date.now().toLong()}"
    notify("Recording box MP4...", "command")
    sendWithReply(Message("agent-box-video").payload(request), callbackName) { message, _ ->
        val response = Json.decodeFromJsonElement<AgentVideoResponse>(message.payload!!)
        if (response.ok) {
            notify("Saved MP4 to ${response.path}", "command")
        } else {
            notify(response.message.ifEmpty { "MP4 recording failed" }, "error")
        }
        Handled.yes_remove
    }
}

private fun sendWindowVideo(request: AgentWindowVideo) {
    val callbackName = "agent-window-video-${Date.now().toLong()}"
    notify("Recording window MP4...", "command")
    sendWithReply(Message("agent-window-video").payload(request), callbackName) { message, _ ->
        val response = Json.decodeFromJsonElement<AgentVideoResponse>(message.payload!!)
        if (response.ok) {
            notify("Saved MP4 to ${response.path}", "command")
        } else {
            notify(response.message.ifEmpty { "MP4 recording failed" }, "error")
        }
        Handled.yes_remove
    }
}

fun recordSelectedBoxMp4() {
    val box = requireSingleSelectedBox() ?: return
    val uid = box.uid as? String ?: return
    val (path, durationSec) = promptMp4Target("box") ?: return
    sendBoxVideo(
        AgentBoxVideo(
            uid = uid,
            path = path,
            durationSec = durationSec,
            fps = 30
        )
    )
}

fun recordSelectedBoxEditorMp4() {
    val box = requireSingleSelectedBox() ?: return
    val uid = box.uid as? String ?: return
    val (path, durationSec) = promptMp4Target("editor") ?: return
    sendWindowVideo(
        AgentWindowVideo(
            path = path,
            uid = uid,
            showEditor = true,
            editorOnly = true,
            openOutputs = false,
            durationSec = durationSec,
            fps = 30
        )
    )
}

fun recordWindowMp4() {
    val (path, durationSec) = promptMp4Target("window") ?: return
    sendWindowVideo(
        AgentWindowVideo(
            path = path,
            uid = null,
            showEditor = false,
            editorOnly = false,
            openOutputs = false,
            durationSec = durationSec,
            fps = 30
        )
    )
}

private fun downloadEdnFile(filename: String, edn: String) {
    val blob = js("new Blob([edn], { type: 'application/edn' })")
    val url = URL.createObjectURL(blob)
    val link = document.createElement("a")
    link.setAttribute("href", url)
    link.setAttribute("download", filename)
    document.body!!.appendChild(link)
    link.asDynamic().click()
    window.setTimeout({
        document.body!!.removeChild(link)
        URL.revokeObjectURL(url)
    }, 0)
}


fun registerMouseBug() {
    document.addEventListener("mousemove", { event ->
        val mouseEvent = event as MouseEvent
        val x = mouseEvent.pageX - (mainCanvas.unsafeCast<dynamic>().data_x as Double)
        val y = mouseEvent.pageY - (mainCanvas.unsafeCast<dynamic>().data_y as Double)
        lastMouse = x to y
    })
}

private fun allBoxesOnCanvas(): List<HTMLElement> =
    document.body!!.getElementsByClassName("box").asList().mapNotNull { it as? HTMLElement }

@OptIn(ExperimentalJsExport::class)
@JsExport
fun normalizeNestedPanForSelection() {
    val allBoxes = allBoxesOnCanvas()
    val boxByUid = allBoxes.mapNotNull { box ->
        val uid = box.unsafeCast<dynamic>().uid as? String
        if (uid == null) null else uid to box
    }.toMap()

    val parentByUid = linkedMapOf<String, HTMLElement>()
    selected.forEach { box ->
        val uid = box.unsafeCast<dynamic>().uid as? String
        val nested = box.unsafeCast<dynamic>().box_nested as? Array<String>
        if (uid != null && nested != null && nested.isNotEmpty()) {
            parentByUid[uid] = box
        }

        val nestedIn = box.unsafeCast<dynamic>().box_nestedIn as? String
        if (nestedIn != null) {
            boxByUid[nestedIn]?.let { parentByUid[nestedIn] = it }
        }
    }

    if (parentByUid.isEmpty()) {
        notify("Select a nest parent or a box inside a nest", "command")
        return
    }

    val updates = mutableListOf<Set_rect>()
    var adjustedChildren = 0
    var resetNests = 0

    parentByUid.values.forEach { parent ->
        val nestedCanvas = parent.querySelector(".nested-canvas") as? HTMLElement ?: return@forEach
        val nestedContent = nestedCanvas.querySelector(".nested-content") as? HTMLElement

        val panX = (nestedCanvas.asDynamic().data_x as? Number)?.toDouble() ?: 0.0
        val panY = (nestedCanvas.asDynamic().data_y as? Number)?.toDouble() ?: 0.0

        val childIds = (parent.unsafeCast<dynamic>().box_nested as? Array<String>)?.toList() ?: emptyList()
        if (childIds.isEmpty()) {
            nestedContent?.style?.transform = "translate(0px, 0px)"
            nestedCanvas.asDynamic().data_x = 0.0
            nestedCanvas.asDynamic().data_y = 0.0
            return@forEach
        }

        if (abs(panX) > 1e-6 || abs(panY) > 1e-6) {
            childIds.forEach { childId ->
                val child = boxByUid[childId] ?: return@forEach
                val d = child.unsafeCast<dynamic>()

                val x = (d.data_x as? Number)?.toDouble() ?: 0.0
                val y = (d.data_y as? Number)?.toDouble() ?: 0.0
                val w = (d.data_w as? Number)?.toDouble() ?: child.getBoundingClientRect().width
                val h = (d.data_h as? Number)?.toDouble() ?: child.getBoundingClientRect().height
                val nx = x + panX
                val ny = y + panY

                d.data_x = nx
                d.data_y = ny
                child.style.transform = "translate(${nx}px, ${ny}px)"

                updates += Set_rect(childId, "frame", Rect(nx, ny, w, h))
                adjustedChildren++
            }
        }

        nestedContent?.style?.transform = "translate(0px, 0px)"
        nestedCanvas.asDynamic().data_x = 0.0
        nestedCanvas.asDynamic().data_y = 0.0
        resetNests++
    }

    if (updates.isNotEmpty()) {
        send(Message("set-rects").payload(Set_rects(updates)))
    }

    canvasGestures.rebuildConnectionGroup()
    notify("Reset pan on $resetNests nest(s), adjusted $adjustedChildren box frame(s)", "command")
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun newBox() {
    val w = 100.0
    val h = 100.0
    mainCanvas.append {
        createBox("Untitled", frame = Rect(lastMouse.first - w / 2, lastMouse.second - h / 2, w, h))
    }
    canvasGestures.rebuildConnectionGroup()
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun deleteSelectedBoxes() {
    send(
        Message("perform-action").payload(
            Perform_action(
                selected.mapNotNull { it.unsafeCast<dynamic>().uid },
                "-delete-box"
            )
        )
    )
    selected.forEach {
        val uid = it.unsafeCast<dynamic>().uid as? String
        if (uid != null) {
            val emb = js("window.embeddedEditors")
            if (emb != null) {
                (emb.disposeForBox as? Function<*>)?.asDynamic()?.call(emb, uid)
            }
        }
        it.parentElement?.removeChild(it)
    }
    canvasGestures.rebuildConnectionGroup()
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun selectAllBoxes() {
    val boxes = allBoxesOnCanvas()
    if (boxes.isEmpty()) return

    // Normalize selection state then select everything
    selected.toList().forEach { deselectBox(it) }
    boxes.forEach { selectBox(it) }
    canvasGestures.rebuildConnectionGroup()
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun selectAllNestedBoxes() {
    val nestedBoxes = allBoxesOnCanvas().filter { (it.unsafeCast<dynamic>().box_nestedIn as? String) != null }
    if (nestedBoxes.isEmpty()) {
        notify("No nested boxes in this document", "command")
        return
    }
    selected.toList().forEach { deselectBox(it) }
    nestedBoxes.forEach { selectBox(it) }
    canvasGestures.rebuildConnectionGroup()
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun connectFromNestParentToSelection() {
    val selectedNested = selected.filter { (it.unsafeCast<dynamic>().box_nestedIn as? String) != null }
    if (selectedNested.isEmpty()) {
        notify("Select one or more nested boxes", "command")
        return
    }

    val boxByUid = allBoxesOnCanvas().mapNotNull { box ->
        val uid = box.unsafeCast<dynamic>().uid as? String
        if (uid == null) null else uid to box
    }.toMap()

    val groupedByParentUid = linkedMapOf<String, MutableList<String>>()
    selectedNested.forEach { child ->
        val childUid = child.unsafeCast<dynamic>().uid as? String ?: return@forEach
        val parentUid = child.unsafeCast<dynamic>().box_nestedIn as? String ?: return@forEach
        if (!boxByUid.containsKey(parentUid)) return@forEach
        groupedByParentUid.getOrPut(parentUid) { mutableListOf() }.add(childUid)
    }

    if (groupedByParentUid.isEmpty()) {
        notify("No valid nest parents found for selection", "error")
        return
    }

    var linkCount = 0
    groupedByParentUid.forEach { (parentUid, childUids) ->
        val uniqueChildren = childUids.distinct().filter { it != parentUid }
        if (uniqueChildren.isEmpty()) return@forEach
        send(
            Message("perform-action").payload(
                Perform_action(
                    listOf(parentUid) + uniqueChildren,
                    "-connect"
                )
            )
        )
        linkCount += uniqueChildren.size
    }

    if (linkCount > 0) {
        notify("Connected nest parent(s) to $linkCount selected nested box${if (linkCount == 1) "" else "es"}", "command")
    }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun copySelectedBoxesToClipboard() {
    val ids = getSelectedIds()
    if (ids.isEmpty()) return

    val clipboard = window.navigator.asDynamic().clipboard
    if (clipboard == undefined) {
        notify("Clipboard not available", "error")
        return
    }

    sendWithReply(Message("copy-boxes").payload(CopyBoxes(ids)), "copy-boxes") { message, _ ->
        val result = Json.decodeFromJsonElement<CopyBoxesResult>(message.payload!!)

        // Write both text and file formats when the browser allows it.
        try {
            // ClipboardItem.supports is optional and not universally implemented; guard all calls.
            val supportsEdn = try {
                js("typeof ClipboardItem !== 'undefined' && ClipboardItem.supports && ClipboardItem.supports('application/edn')") as Boolean
            } catch (_: dynamic) { false }
            val supportsOctetStream = try {
                js("typeof ClipboardItem !== 'undefined' && ClipboardItem.supports && ClipboardItem.supports('application/octet-stream')") as Boolean
            } catch (_: dynamic) { false }

            val clipboardData = js("({})")
            clipboardData["text/plain"] = js("new Blob([result.edn], { type: 'text/plain' })")

            var fileType: String? = null
            if (supportsEdn) {
                fileType = "application/edn"
            } else if (supportsOctetStream) {
                fileType = "application/octet-stream"
            }

            if (fileType != null) {
                val blob = js("new Blob([result.edn], { type: fileType })")
                val file = js("new File([blob], 'metadata.edn', { type: fileType })")
                clipboardData[fileType] = file
            }

            if (fileType != null) {
                val clipboardItem = js("new ClipboardItem(clipboardData)")
                clipboard.write(js("Array").of(clipboardItem)).then({
                    console.info("[clipboard] copied as text and $fileType")
                }, { error: dynamic ->
                    console.info("[clipboard] write failed, using text-only fallback", error)
                    clipboard.writeText(result.edn)
                })
            } else {
                // Browsers sanitize custom file types; fall back to text-only.
                clipboard.writeText(result.edn)
            }
        } catch (e: Exception) {
            console.info("[clipboard] modern clipboard API failed, using text-only fallback", e)
            clipboard.writeText(result.edn)
        }

        Handled.yes_remove
    }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun exportSelectedBoxesToDownload() {
    val ids = getSelectedIds()
    if (ids.isEmpty()) return

    sendWithReply(Message("copy-boxes").payload(CopyBoxes(ids)), "copy-boxes") { message, _ ->
        val result = Json.decodeFromJsonElement<CopyBoxesResult>(message.payload!!)
        val base = if (result.filename.isNotBlank()) result.filename else "selection"
        downloadEdnFile("${base}_selection.edn", result.edn)
        Handled.yes_remove
    }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun exportDocumentToDownload() {
    sendWithReply(Message("export-document"), "export-document") { message, _ ->
        val result = Json.decodeFromJsonElement<ExportDocumentResult>(message.payload!!)
        val name = if (result.filename.isNotBlank()) result.filename else "document"
        downloadEdnFile("$name.edn", result.edn)
        Handled.yes_remove
    }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun pasteBoxesFromClipboard() {
    val clipboard = window.navigator.asDynamic().clipboard
    if (clipboard == undefined) {
        notify("Clipboard not available", "error")
        return
    }

    // Try to read clipboard items (supports both text and files)
    clipboard.read().then({ clipboardItems: dynamic ->
        var processed = false

        // Iterate through clipboard items
        val items = clipboardItems
        if (items.length > 0) {
            val item = items[0]

            // Check if there's an EDN file in the clipboard
            if (item.types.includes("application/edn")) {
                item.getType("application/edn").then({ blob: dynamic ->
                    blob.text().then({ text: String ->
                        if (text.isNotBlank()) {
                            processPasteText(text)
                        }
                    }, { err: dynamic ->
                        console.info("[clipboard] failed to read EDN blob", err)
                    })
                }, { err: dynamic ->
                    console.info("[clipboard] failed to get EDN type", err)
                })
                processed = true
            }
            // Fallback to plain text
            else if (item.types.includes("text/plain")) {
                item.getType("text/plain").then({ blob: dynamic ->
                    blob.text().then({ text: String ->
                        if (text.isNotBlank()) {
                            processPasteText(text)
                        }
                    }, { err: dynamic ->
                        console.info("[clipboard] failed to read text blob", err)
                    })
                }, { err: dynamic ->
                    console.info("[clipboard] failed to get text type", err)
                })
                processed = true
            }
        }

        // If no items were processed, try the old text-only API as fallback
        if (!processed) {
            clipboard.readText().then({ text: String ->
                if (text.isNotBlank()) {
                    processPasteText(text)
                }
            }, { err: dynamic ->
                console.info("[clipboard] read failed", err)
            })
        }
    }, { err: dynamic ->
        console.info("[clipboard] failed to read items, falling back to text", err)
        // Fallback to text-only API
        clipboard.readText().then({ text: String ->
            if (text.isNotBlank()) {
                processPasteText(text)
            }
        }, { err: dynamic ->
            console.info("[clipboard] read failed", err)
        })
    })
}

private fun processPasteText(text: String) {
    sendWithReply(Message("paste-boxes").payload(PasteBoxes(text)), "paste-boxes") { message, _ ->
        val result = Json.decodeFromJsonElement<PasteBoxesResult>(message.payload!!)
        later({
            val boxes = result.newUids.mapNotNull { uid ->
                document.body!!.getElementsByClassName("box").asList()
                    .firstOrNull { it.unsafeCast<dynamic>().uid == uid } as? HTMLElement
            }
            if (boxes.isNotEmpty()) {
                selected.copy().forEach { deselectBox(it) }
                boxes.forEach { selectBox(it) }
                canvasGestures.rebuildConnectionGroup()
            }
        }, 50)
        Handled.yes_remove
    }
}


@OptIn(ExperimentalJsExport::class)
@JsExport
fun moveSelectedBoxesForward() {
    selected.forEach {
        it.parentNode?.appendChild(it)
    }
    canvasGestures.rebuildConnectionGroup()
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun showAllLayers() {
    val layers = getAllLayers().filter { it.isNotBlank() }.toSet().ifEmpty { setOf("default") }
    setVisibleLayers(layers, animated = true, duration = 450.0)

    val hostBox = selected.firstOrNull() ?: allBoxesOnCanvas().firstOrNull()
    val hostUid = hostBox?.unsafeCast<dynamic>()?.uid as? String
    if (hostBox != null && hostUid != null) {
        handleOutputAreaRequest(
            hostBox,
            Set_output(
                uid = hostUid,
                property = "outputarea",
                channel = "layers",
                value = ""
            )
        )
    }

    notify("Showing all ${layers.size} layer${if (layers.size == 1) "" else "s"}", "command")
}

private fun layerNamesInNavigationOrder(): List<String> =
    getAllLayers().filter { it.isNotBlank() }.distinct().sorted().ifEmpty { listOf("default") }

private fun currentLayerForNavigation(layers: List<String>): String {
    val active = activeLayer?.takeIf { layers.contains(it) }
    if (active != null) return active

    val soloVisible = visibleLayers.singleOrNull()?.takeIf { layers.contains(it) }
    if (soloVisible != null) return soloVisible

    return layers.first()
}

private fun soloLayerTarget(offset: Int): String {
    val layers = layerNamesInNavigationOrder()
    val current = currentLayerForNavigation(layers)
    val currentIndex = layers.indexOf(current).takeIf { it >= 0 } ?: 0
    val nextIndex = (currentIndex + offset + layers.size) % layers.size
    return layers[nextIndex]
}

private fun soloLayer(offset: Int) {
    val target = soloLayerTarget(offset)
    setVisibleLayers(setOf(target), animated = true, duration = 450.0)
    setActiveLayer(target, animated = true, duration = 450.0)
    notify("Solo layer \"$target\"", "command")
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun soloNextLayer() {
    soloLayer(1)
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun soloPreviousLayer() {
    soloLayer(-1)
}

private fun jsCommandStringLiteral(value: String): String = JSON.stringify(value)

@OptIn(ExperimentalJsExport::class)
@JsExport
fun refreshLayerShortcutActions() {
    val next = soloLayerTarget(1)
    val previous = soloLayerTarget(-1)
    val nextLabel = jsCommandStringLiteral("Solo Next Layer: $next")
    val previousLabel = jsCommandStringLiteral("Solo Previous Layer: $previous")
    val nextDetail = jsCommandStringLiteral("Hide every other layer and make \"$next\" active. Shortcut: Alt+]")
    val previousDetail = jsCommandStringLiteral("Hide every other layer and make \"$previous\" active. Shortcut: Alt+[")

    allActions.layerNavigation = eval(
        """
        [{
            id:'solo-next-layer',
            label:$nextLabel,
            detail:$nextDetail,
            keybindings:(typeof monaco !== 'undefined') ? [(() => {
                const key = monaco.KeyCode.BracketRight || monaco.KeyCode.US_CLOSE_SQUARE_BRACKET || monaco.KeyCode.CloseBracket || 0;
                return key ? (monaco.KeyMod.Alt | key) : 0;
            })()].filter(Boolean) : [],
            canvasKeybinding: (event) => event.altKey && !event.shiftKey && !event.ctrlKey && !event.metaKey && (event.key === ']' || event.code === 'BracketRight'),
            run: () => {
                window.soloNextLayer()
                if (typeof updateActions === 'function') updateActions()
            }
        },{
            id:'solo-previous-layer',
            label:$previousLabel,
            detail:$previousDetail,
            keybindings:(typeof monaco !== 'undefined') ? [(() => {
                const key = monaco.KeyCode.BracketLeft || monaco.KeyCode.US_OPEN_SQUARE_BRACKET || monaco.KeyCode.OpenBracket || 0;
                return key ? (monaco.KeyMod.Alt | key) : 0;
            })()].filter(Boolean) : [],
            canvasKeybinding: (event) => event.altKey && !event.shiftKey && !event.ctrlKey && !event.metaKey && (event.key === '[' || event.code === 'BracketLeft'),
            run: () => {
                window.soloPreviousLayer()
                if (typeof updateActions === 'function') updateActions()
            }
        }]
    """.trimIndent()
    )
}

@OptIn(ExperimentalJsExport::class)
@JsExport()
fun insertFromWorkspace() {
    // Capture mouse position when the menu is opened
    val mouseX = lastMouse.first
    val mouseY = lastMouse.second

    sendWithReply(Message("request-templates").payload(Directory(command = "list")), "templates") { message, payload ->

        println(" request templates got $message $payload")

        val directory = Json.decodeFromJsonElement<Directory>(message.payload!!)

        val options = eval("[]")

        // Parse template metadata if available
        val metadataList = try {
            directory.metadata?.let {
                Json.decodeFromJsonElement<List<TemplateMetadata>>(it)
            } ?: emptyList()
        } catch (e: Exception) {
            console.info("[templates] failed to parse template metadata: ${e.message}", e)
            emptyList()
        }

        val metadataMap = metadataList.associateBy { it.name }

        // Helper function to escape strings for JavaScript
        fun escapeJs(str: String): String {
            return str.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\u2028", "\\u2028")  // Unicode line separator
                .replace("\u2029", "\\u2029")  // Unicode paragraph separator
        }

        directory.value.forEach { templateName ->
            val metadata = metadataMap[templateName]

            // Format the description and detail
            val description = when (metadata?.source) {
                "workspace" -> "Workspace file"
                "workspace-template" -> "Workspace template"
                "bundled-template" -> "Bundled template"
                "remote-template" -> "Remote template"
                else -> "Template"
            }

            val detail = if (metadata != null && metadata.boxDocumentation.isNotEmpty()) {
                buildString {
                    // Add last modified date if available
                    if (metadata.lastModified > 0) {
                        // lastModified is already in milliseconds
                        val date = Date(metadata.lastModified)
                        append("Modified: ${date.toLocaleDateString()}\n\n")
                    }

                    // Add box documentation
                    append(metadata.boxDocumentation)
                }
            } else {
                "No documentation available"
            }

            val escapedTemplateName = escapeJs(templateName)
            val escapedDescription = escapeJs(description)
            val escapedDetail = escapeJs(detail)

            val e = """
            ({
                id:'insert-$escapedTemplateName', 
                label:'$escapedTemplateName', 
                description: '$escapedDescription',
                detail: '$escapedDetail',
                canvasKeybinding: (event) => false, 
                run: () => {
                    import("./afield.mjs").then( (afield) => {
                        afield.send("insert-template", {
                            command:"insert",
                            value:["$escapedTemplateName"],
                            mouseX: $mouseX,
                            mouseY: $mouseY
                        })
                    })
                }
            })
        """.trimIndent()
            options.push(eval(e))
        }

        if (directory.value.size > 0) {
            showPickMenu_custom(options)
        }

        canvasGestures.rebuildConnectionGroup()
        Handled.yes_remove
    }
}

fun openDocsSearch() {
    // Switch to documentation browser and load the search page
    switchToDocsIframe("/docs-search.html")
}

@OptIn(ExperimentalJsExport::class)
@JsExport()
fun saveAsTemplate() {
    val selectedIds = getSelectedIds()
    if (selectedIds.isEmpty()) {
        notify("No boxes selected", "error")
        return
    }

    // Call the helper function with the selected IDs; guard when helper is missing
    val helper = js("window._saveAsTemplateHelper")
    val hasHelper = js("typeof window._saveAsTemplateHelper === 'function'") as Boolean
    if (!hasHelper) {
        notify("Template helper is not available yet. Try again after Monaco loads.", "error")
        return
    }
    helper(selectedIds.toTypedArray())
}


@OptIn(ExperimentalJsExport::class)
@JsExport
fun editHashtag() {
    var selectedBoxes = selected.map { it.id }
    sendWithReply(
        Message("request-hashtag").payload(RequestHashtags(boxes = selectedBoxes, getOrSet = true)),
        "hashes"
    ) { message, payload ->

        println(" request templates got $message $payload")

        val directory = Json.decodeFromJsonElement<RequestHashtags>(message.payload!!)

        val options = eval("[]")


        Handled.yes_remove
    }
}

external fun updateActions(): Unit

@OptIn(ExperimentalJsExport::class)
@JsExport
@JsName("getSelectedIds")
fun getSelectedIds() = selected.map { it.unsafeCast<dynamic>().uid as String }.toList()

fun parseKeybindingSpec(spec: String): String? {
    val tokens = spec.lowercase().replace(",", "+").split('+', ' ', '\t').filter { it.isNotBlank() }
    val mods = mutableListOf<String>()
    var mainKey: String? = null
    for (raw in tokens) {
        val t = raw.trim()
        when (t) {
            "alt", "option" -> mods.add("monaco.KeyMod.Alt")
            "shift" -> mods.add("monaco.KeyMod.Shift")
            "ctrl", "control", "cmd", "command", "meta", "ctrlcmd" -> mods.add("monaco.KeyMod.CtrlCmd")
            else -> {
                val key = when {
                    t.length == 1 && t[0] in 'a'..'z' -> "monaco.KeyCode.Key" + t.uppercase()
                    t.length == 1 && t[0] in '0'..'9' -> "monaco.KeyCode.Digit" + t
                    t.startsWith("f") && t.drop(1).toIntOrNull() != null -> "monaco.KeyCode." + t.uppercase()
                    t == "enter" || t == "return" -> "monaco.KeyCode.Enter"
                    t == "escape" || t == "esc" -> "monaco.KeyCode.Escape"
                    t == "backspace" -> "monaco.KeyCode.Backspace"
                    t == "delete" || t == "del" -> "monaco.KeyCode.Delete"
                    t == "space" || t == "spacebar" -> "monaco.KeyCode.Space"
                    t == "tab" -> "monaco.KeyCode.Tab"
                    t == "left" || t == "arrowleft" -> "monaco.KeyCode.LeftArrow"
                    t == "right" || t == "arrowright" -> "monaco.KeyCode.RightArrow"
                    t == "up" || t == "arrowup" -> "monaco.KeyCode.UpArrow"
                    t == "down" || t == "arrowdown" -> "monaco.KeyCode.DownArrow"
                    else -> null
                }
                if (key != null) mainKey = key
            }
        }
    }
    if (mainKey == null && mods.isEmpty()) return null
    return (mods + listOfNotNull(mainKey)).joinToString(" | ")
}


fun refreshCommandsFromSelection(boxes: List<HTMLElement>, selection: List<List<Action_Desc>>) {
    val all = eval("[]")
    val selId = selected.map { it.unsafeCast<dynamic>().uid as String }.map { "'$it'" }.joinToString(",")
    val allBoxes = allBoxesOnCanvas()
    val hasNestedBoxes = allBoxes.any { (it.unsafeCast<dynamic>().box_nestedIn as? String) != null }
    val hasSelectedNestedBoxes = boxes.any { (it.unsafeCast<dynamic>().box_nestedIn as? String) != null }

    fun escapeJsString(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "")
    }

    selection.flatMap { it }.associateBy { it.id }.toMap().entries.forEach {
        val fullDesc = it.value.description
        val pipeIndex = fullDesc.indexOf('|')
        val rawLabel = if (pipeIndex >= 0) fullDesc.substring(0, pipeIndex).trim() else fullDesc
        val label = escapeJsString(rawLabel)
        val kbSpec = if (pipeIndex >= 0) fullDesc.substring(pipeIndex + 1) else ""
        val detail = it.value.detail.ifBlank { "" }
        val detailEscaped = escapeJsString(detail)

        val usesContextualShaderUpdateShortcut =
            it.key == "moduleHere_update" || it.key == "computeModuleHere_update"
        val kbExpr = if (usesContextualShaderUpdateShortcut) null else parseKeybindingSpec(kbSpec.trim())
        val kbLine = if (kbExpr != null) "keybindings: [$kbExpr]," else "keybindings: [],"

        val e = """
            ({
                id:'${it.key}', label:'$label', 
                detail:'$detailEscaped',
                $kbLine
                canvasKeybinding: (event) => false, 
                run: () => { 
                    clearErrors(globalEditor)                
                    
                    import("./afield.mjs").then( (afield) => {
                        afield.send("perform-action", {
                                uid_action: '${it.key}',
                                uid_currentSelection: getSelectedIds()
                        })
                        
                    })}
            })
        """.trimIndent()
        val a = eval(e)
        all.push(a)
    }

    if (boxes.size >= 1) {
        run {
            val e = """
            ({
                id:'fmt-kotlin', label:'Format Kotlin', 
                detail:'Fix the formatting of your code',
                canvasKeybinding: (event) => false, 
                run: () => { 
                    import("./afield.mjs").then( (afield) => {afield.send("perform-action", {
                                uid_action: '-format-kotlin',
                                uid_currentSelection: [${selId}]
                        })})}
            })
        """.trimIndent()
            val a = eval(e)
            all.push(a)
        }

        run {
            val e = """
            ({
                id:'-copy', label:'Duplicate box(es)', 
                detail:'Duplicate the currently selected boxes',
                canvasKeybinding: (event) => (event.key=='D'||event.key=='d') && event.shiftKey && (event.ctrlKey || event.metaKey) && !event.altKey, 
                keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.KEY_D],
                run: () => { 
                    import("./afield.mjs").then( (afield) => {afield.send("perform-action", {
                                uid_action: '-copy',
                                uid_currentSelection: [${selId}]
                        })})}
            })
        """.trimIndent()
            val a = eval(e)
            all.push(a)
        }

        run {
            val e = """
            ({
                id:'export-selection-edn', label:'Export selection (.edn)', 
                detail:'Downloads the selected boxes as a self contained .edn file',
                canvasKeybinding: (event) => false,
                run: () => { 
                    exportSelectedBoxesToDownload()
                }
            })
        """.trimIndent()
            val a = eval(e)
            all.push(a)
        }
    }
    fun jsStringLiteral(value: String): String = JSON.stringify(value)

    if (boxes.size >= 2) {
        val sourceName = (boxes.first().unsafeCast<dynamic>().name as? String)?.trim().orEmpty()
        val targetNames = boxes.drop(1).map { (it.unsafeCast<dynamic>().name as? String)?.trim().orEmpty() }
        val safeSource = sourceName.ifBlank { "box 1" }
        val safeTargets = targetNames.map { it.ifBlank { "..." } }

        val targetLabel = when {
            safeTargets.isEmpty() -> ""
            safeTargets.size <= 3 -> safeTargets.joinToString(", ")
            else -> safeTargets.take(2).joinToString(", ") + ", +${safeTargets.size - 2} more"
        }

        val connectLabel = if (targetLabel.isNotEmpty()) "Connect '$safeSource' to '$targetLabel'" else "Connect '$safeSource'"
        val disconnectLabel =
            if (targetLabel.isNotEmpty()) "Disconnect '$safeSource' from '$targetLabel'" else "Disconnect '$safeSource'"
        val nestLabel = if (targetLabel.isNotEmpty()) "Nest '$targetLabel' into '$safeSource'" else "Nest into '$safeSource'"

        run {
            val e = """
            ({
                id:'connect', label:${jsStringLiteral(connectLabel)}, 
                detail:${jsStringLiteral("Shared selected variables from one box to another")},
                canvasKeybinding: (event) => event.key=='g' && !event.shiftKey && !event.ctrlKey && !event.altKey && !event.metaKey, 
                run: () => { 
                    import("./afield.mjs").then( (afield) => {afield.send("perform-action", {
                                uid_action: '-connect',
                                uid_currentSelection: getSelectedIds()
                        })})}
            })
        """.trimIndent()
            val a = eval(e)
            all.push(a)
        }
        run {
            val e = """
            ({
                id:'nest-into', label:${jsStringLiteral(nestLabel)}, 
                detail:${jsStringLiteral("Hide one box 'inside' the nest of another box")},
                canvasKeybinding: (event) => false, 
                run: () => { 
                    import("./afield.mjs").then( (afield) => {afield.send("perform-action", {
                                uid_action: '-nest-into',
                                uid_currentSelection: getSelectedIds()
                        })})}
            })
        """.trimIndent()
            val a = eval(e)
            all.push(a)
        }
        run {
            val e = """
            ({
                id:'disconnect', label:${jsStringLiteral(disconnectLabel)}, 
                detail:${jsStringLiteral("Stop sharing variables between these two boxes")},
                canvasKeybinding: (event) => event.key=='u' && !event.shiftKey && !event.ctrlKey && !event.altKey && !event.metaKey, 
                run: () => { 
                    import("./afield.mjs").then( (afield) => {afield.send("perform-action", {
                                uid_action: '-disconnect',
                                uid_currentSelection: getSelectedIds()
                        })})}
            })
        """.trimIndent()
            val a = eval(e)
            all.push(a)
        }
    }

    if (activeLayer != null) {
        val layerLabel = activeLayer!!
        val countLabel = if (boxes.size == 1) "1 box" else "${boxes.size} boxes"
        val moveLabel = "Move $countLabel to active layer \"$layerLabel\""
        run {
            val e = """
                ({
                    id:'move-to-active-layer', label:${jsStringLiteral(moveLabel)},
                    detail:${jsStringLiteral("Assign selected box"+(if (boxes.size>1) "es" else "")+" to the current active layer")},
                    canvasKeybinding: (event) => false,
                    run: () => {
                        const layer = ${jsStringLiteral(layerLabel)};
                        if (!layer) return;
                        import("./afield.mjs").then((afield) => {
                            const ids = (typeof getSelectedIds === 'function') ? getSelectedIds() : [];
                            ids.forEach((id) => {
                                afield.send("set-string", { uid: id, property: "layers", value: layer });
                                const el = document.querySelector('.box[uid="' + id + '"]');
                                if (Entities.el) { Entities.el.box_layers = layer; }
                            });
                            if (typeof updateAllBoxVisibility === 'function') {
                                updateAllBoxVisibility(true, 300);
                            }
                        });
                    }
                })
            """.trimIndent()
            val a = eval(e)
            all.push(a)
        }
    }
    run {
        val e = """
            ({
                id:'-update-check', label:'Check for updates & download', 
                detail:'Check the shared server for updates to Field and download them',
                canvasKeybinding: (event) => false, 
                run: () => { 
                    import("./afield.mjs").then( (afield) => {afield.send("perform-action", {
                                uid_action: '-update-check',
                                uid_currentSelection: [${selId}]
                        })})}
            })
        """.trimIndent()
        val a = eval(e)
        all.push(a)
    }
    if (boxes.isNotEmpty()) {
        run {
            val e = """
            ({
                id:'normalize-nested-pan', label:'Normalize Nested contents', 
                detail:'Reset nest offset so that the boxes appear in the current position when the document is opened',
                canvasKeybinding: (event) => false, 
                run: () => { 
                    normalizeNestedPanForSelection()
                }
            })
        """.trimIndent()
            val a = eval(e)
            all.push(a)
        }
        run {
            val e = """
            ({
                id:'un-nest', label:'Un-nest selection', 
                detail:'Bring a nested box out of it\'s nest',
                canvasKeybinding: (event) => false, 
                run: () => { 
                    import("./afield.mjs").then( (afield) => {afield.send("perform-action", {
                                uid_action: '-un-nest',
                                uid_currentSelection: getSelectedIds()
                        })})}
            })
        """.trimIndent()
            console.log(e)
            val a = eval(e)
            all.push(a)
        }
    }
    run {
        val e = """
        ({
            id:'select-all-boxes', label:'Select All Boxes',
            detail:'Select every box on the canvas',
            canvasKeybinding: (event) => false,
            run: () => { selectAllBoxes() }
        })
    """.trimIndent()
        val a = eval(e)
        all.push(a)
    }
    if (hasNestedBoxes) {
        run {
            val e = """
            ({
                id:'select-all-nested', label:'Select all nested',
                detail:'Select all boxes that are currently nested inside another box',
                canvasKeybinding: (event) => false,
                run: () => { selectAllNestedBoxes() }
            })
        """.trimIndent()
            val a = eval(e)
            all.push(a)
        }
    }
    if (hasSelectedNestedBoxes) {
        run {
            val e = """
            ({
                id:'connect-nest-parent-to-selection', label:'Connect from nest parent to selection',
                detail:'For each selected nested box, connect its nest parent to it',
                canvasKeybinding: (event) => false,
                run: () => { connectFromNestParentToSelection() }
            })
        """.trimIndent()
            val a = eval(e)
            all.push(a)
        }
    }
    allActions.fromSelection = all
    updateActions()
}

fun registerBuiltinCommands() {
    // Expose openEditorInOutput to window so inline JS commands can call it
    val w = js("window")
    w.openEditorInOutput = ::openEditorInOutput
    w.recordSelectedBoxMp4 = ::recordSelectedBoxMp4
    w.recordSelectedBoxEditorMp4 = ::recordSelectedBoxEditorMp4
    w.recordWindowMp4 = ::recordWindowMp4
    w.soloNextLayer = ::soloNextLayer
    w.soloPreviousLayer = ::soloPreviousLayer
    w.refreshLayerShortcutActions = ::refreshLayerShortcutActions
    w.openUserThemeProxyBox = ::openUserThemeProxyBox

    allActions.builtin = eval(
        """
        [{
            id:'new-box',
            label:"New Box",
            keybindings:(typeof monaco !== 'undefined') ? [monaco.KeyCode.KEY_N] : [],
            detail:"Make a new box under the mouse",
            canvasKeybinding: (event) => {
                let a = event.key=='n' && !event.shiftKey && !event.ctrlKey && !event.altKey && !event.metaKey
                return a
            },
            run: () => {
                newBox()
            }
        },{
            id:'delete-box',
            label:"Delete Box(es)",
            keybindings:(typeof monaco !== 'undefined') ? [monaco.KeyMod.CtrlCmd | monaco.KeyCode.Backspace] : [],
            detail:"Delete the selected boxes",
            canvasKeybinding: (event) => (event.keyCode==8 || event.keyCode==46) && (event.ctrlKey || event.metaKey),
            run: () => {
                deleteSelectedBoxes()
            }
        },{
            id:'copy-selection',
            label:"Copy Selection",
            keybindings:(typeof monaco !== 'undefined') ? [monaco.KeyMod.CtrlCmd | monaco.KeyCode.KEY_C] : [],
            detail:"Copy selected boxes (and all their code & properties)",
            canvasKeybinding: (event) => {
                const copyChord = (event.key=='c') && !event.shiftKey && !event.altKey && (event.ctrlKey || event.metaKey)
                if (!copyChord) return false
                const sel = (window.getSelection && window.getSelection()) || null
                const hasDomSelection = !!(sel && sel.rangeCount > 0 && !sel.isCollapsed && (sel.toString() || "").length > 0)
                return !hasDomSelection
            },
            run: () => {
                if (window.globalEditor && window.globalEditor.hasTextFocus && window.globalEditor.hasTextFocus()) return
                if (window.embeddedEditors && window.embeddedEditors.getFocused && window.embeddedEditors.getFocused()) return
                const sel = (window.getSelection && window.getSelection()) || null
                const hasDomSelection = !!(sel && sel.rangeCount > 0 && !sel.isCollapsed && (sel.toString() || "").length > 0)
                if (hasDomSelection) return
                copySelectedBoxesToClipboard()
            }
        },{
            id:'paste-selection',
            label:"Paste Selection",
            keybindings:(typeof monaco !== 'undefined') ? [monaco.KeyMod.CtrlCmd | monaco.KeyCode.KEY_V] : [],
            detail:"Paste as new boxes",
            canvasKeybinding: (event) => (event.key=='v') && !event.shiftKey && !event.altKey && (event.ctrlKey || event.metaKey),
            run: () => {
                if (window.globalEditor && window.globalEditor.hasTextFocus && window.globalEditor.hasTextFocus()) return
                if (window.embeddedEditors && window.embeddedEditors.getFocused && window.embeddedEditors.getFocused()) return
                pasteBoxesFromClipboard()
            }
        },{
            id:'export-document-edn',
            label:"Export document (.edn)",
            detail:"Download a self-contained .edn file of the whole document",
            canvasKeybinding: (event) => false,
            run: () => {
                exportDocumentToDownload()
            }
        },{
            id:'move-forward',
            label:"Move selected boxes forward",
            detail:"Bring to front",
            run: () => {
                    moveSelectedBoxesForward()
            }
        },{
            id:'show-all-layers',
            label:"Show All Layers",
            detail:"Restore visibility for every layer and reopen the layers palette",
            canvasKeybinding: (event) => false,
            run: () => {
                showAllLayers()
            }
        },{
            id:'insert-from-workspace',
            label:"Insert template",
            keybindings:(typeof monaco !== 'undefined') ? [monaco.KeyMod.CtrlCmd | monaco.KeyCode.KEY_T] : [],
            detail:"Search for boxes that you might want to add to this document",
            canvasKeybinding: (event) => (event.key=='t') && (event.ctrlKey || event.metaKey),
            run: () => {
                insertFromWorkspace()
            }
        },{
            id:'open-user-theme',
            label:"Open User Theme",
            detail:"Open ~/.afield/theme.css as an absolute file proxy",
            canvasKeybinding: (event) => false,
            run: () => {
                window.openUserThemeProxyBox()
            }
        },{
            id:'search-docs-and-code',
            label:"Search Documentation & Code",
            keybindings:(typeof monaco !== 'undefined') ? [monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.KEY_D] : [],
            detail:"Search Field4 documentation, code examples, and API references using AI",
            canvasKeybinding: (event) => (event.key=='D') && (event.ctrlKey || event.metaKey) && event.shiftKey,
            run: () => {
                window.switchToDocsIframe("/docs-search.html");
            }
        },{
            id:'edit-hashtag',
            label:"Edit hashtag",
            keybindings:(typeof monaco !== 'undefined') ? [monaco.KeyMod.CtrlCmd | monaco.KeyCode.KEY_H] : [],
            detail:"Edit the metadata associated with this document",
            canvasKeybinding: (event) => (event.key=='h') && (event.ctrlKey || event.metaKey),
            run: () => {
                editHashtag()
            }
        },{
            id:'save-as-template',
            label:"Save selection as template",
            detail:"Make the selected boxes a new template in your workspace for sharing later",
            canvasKeybinding: (event) => false,
            run: () => {
                saveAsTemplate()
            }
        },{
            id:'provisional-accept-all',
            label:"Accept All Provisional (Everywhere)",
            detail:"If AI has proposed changes to your code (shown in orange) accept them all",
            canvasKeybinding: (event) => false,
            run: () => {
                import("./afield.mjs").then( (afield) => {afield.send("provisional-accept-all", {})})
            }
        },{
            id:'provisional-reject-all',
            label:"Reject All Provisional (Everywhere)",
            detail:"If AI has proposed changes to your code (shown in orange) reject them all",
            canvasKeybinding: (event) => false,
            run: () => {
                import("./afield.mjs").then( (afield) => {afield.send("provisional-reject-all", {})})
            }
        },{
            id:'switch-to-docs-iframe',
            label:"Switch to Documentation Iframe",
            detail:"Switch to the documentation mode",
            canvasKeybinding: (event) => false,
            run: () => {
                window.switchToDefaultDocs();
            }
        },{
            id:'show-document-history',
            label:"Show Document History",
            detail:"Switch to the history view mode",
            canvasKeybinding: (event) => false,
            run: () => {
                const filename = window.currentDocumentFilename || "default";
                window.switchToHistoryForFile(filename);
            }
        },{
            id:'switch-to-monaco-editor',
            label:"Switch to Monaco Editor",
            detail:"Switch back to editing code",
            canvasKeybinding: (event) => false,
            run: () => {
                window.switchToMonaco();
            }
        },{
            id:'add-editor-to-output',
            label:"Add Editor to Output Area",
            detail:"Open an embedded text editor for a property (e.g. fragment, vertex) in the selected box's output area",
            canvasKeybinding: (event) => false,
            run: () => {
                if (!window.selectedList || window.selectedList.length !== 1) {
                    if (typeof window.notify === 'function') window.notify('Select exactly one box', 'command');
                    return;
                }
                const box = window.selectedList[0];
                const uid = box.uid;
                if (!uid) return;
                const props = [
                    {label:'fragment',  description:'Fragment Shader (GLSL)'},
                    {label:'stackSL',   description:'StackSL Shader Function'},
                    {label:'vertex_fragment', description:'Shared Vertex+Fragment Shader (Slang)'},
                    {label:'fragment_s', description:'Fragment Shader (Slang)'},
                    {label:'vertex',    description:'Vertex Shader (GLSL)'},
                    {label:'vertex_s',  description:'Vertex Shader (Slang)'},
                    {label:'compute',   description:'Compute Shader (GLSL)'},
                    {label:'compute_s', description:'Compute Shader (Slang)'},
                    {label:'task',      description:'Task Shader (GLSL)'},
                    {label:'task_s',    description:'Task Shader (Slang)'},
                    {label:'mesh',      description:'Mesh Shader (GLSL)'},
                    {label:'mesh_s',    description:'Mesh Shader (Slang)'},
                    {label:'raygen',    description:'Ray Generation Shader (GLSL)'},
                    {label:'raygen_s',  description:'Ray Generation Shader (Slang)'},
                    {label:'miss',      description:'Miss Shader (GLSL)'},
                    {label:'miss_s',    description:'Miss Shader (Slang)'},
                    {label:'closesthit', description:'Closest Hit Shader (GLSL)'},
                    {label:'closesthit_s', description:'Closest Hit Shader (Slang)'},
                    {label:'anyhit',    description:'Any Hit Shader (GLSL)'},
                    {label:'anyhit_s',  description:'Any Hit Shader (Slang)'},
                    {label:'intersection', description:'Intersection Shader (GLSL)'},
                    {label:'intersection_s', description:'Intersection Shader (Slang)'},
                    {label:'callable',  description:'Callable Shader (GLSL)'},
                    {label:'callable_s', description:'Callable Shader (Slang)'},
                    {label:'preamble',  description:'Kotlin (preamble)'},
                    {label:'introduction', description:'Kotlin (introduction)'},
                    {label:'attic',     description:'Kotlin (attic)'},
                    {label:'metadata',  description:'Metadata (YAML)'},
                    {label:'code',      description:'Kotlin (main)'},
                ];
                if (typeof window.showPickMenu_custom === 'function') {
                    window.showPickMenu_custom(props.map(p => ({
                        label: '✎ ' + p.label,
                        detail: p.description,
                        run: () => { window.openEditorInOutput(uid, p.label); }
                    })));
                } else {
                    window.openEditorInOutput(uid, 'fragment');
                }
            }
        },{
            id:'record-selected-box-mp4',
            label:"Record Selected Box MP4",
            detail:"Record a short MP4 clipped to the selected box and its outputs",
            canvasKeybinding: (event) => false,
            run: () => {
                window.recordSelectedBoxMp4();
            }
        },{
            id:'record-selected-editor-mp4',
            label:"Record Selected Editor MP4",
            detail:"Record a short MP4 clipped to the selected box's Monaco editor",
            canvasKeybinding: (event) => false,
            run: () => {
                window.recordSelectedBoxEditorMp4();
            }
        },{
            id:'record-window-mp4',
            label:"Record Window MP4",
            detail:"Record a short MP4 of the current Field window",
            canvasKeybinding: (event) => false,
            run: () => {
                window.recordWindowMp4();
            }
        }]
    """.trimIndent()
    )
    updateActions()

    onSelectedChange["refreshCommands"] = {
        refreshCommandsFromSelection(
            selected.toList(),
            selected.mapNotNull { it.unsafeCast<dynamic>().injectedActions as List<Action_Desc>? ?: emptyList() }
                .toList())
        true
    }
}
