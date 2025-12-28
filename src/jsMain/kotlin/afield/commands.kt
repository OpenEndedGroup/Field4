package afield

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.html.dom.append
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.events.MouseEvent
import web.url.URL

var lastMouse = 0.0 to 0.0

external fun showPickMenu_custom(options: dynamic): dynamic

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
        val x = (event as MouseEvent).pageX - (mainCanvas.unsafeCast<dynamic>().data_x as Double)
        val y = (event as MouseEvent).pageY - (mainCanvas.unsafeCast<dynamic>().data_y as Double)
        lastMouse = x to y
    })
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
        it.parentElement?.removeChild(it)
    }
    canvasGestures.rebuildConnectionGroup()
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
                    console.log("Copied to clipboard as text and file type $fileType")
                }, { error: dynamic ->
                    console.log("Clipboard write failed, falling back to text-only:", error)
                    clipboard.writeText(result.edn)
                })
            } else {
                // Browsers sanitize custom file types; fall back to text-only.
                clipboard.writeText(result.edn)
            }
        } catch (e: Exception) {
            console.log("Modern clipboard API failed, using text-only fallback:", e)
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
                        console.log("Failed to read EDN blob:", err)
                    })
                }, { err: dynamic ->
                    console.log("Failed to get EDN type:", err)
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
                        console.log("Failed to read text blob:", err)
                    })
                }, { err: dynamic ->
                    console.log("Failed to get text type:", err)
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
                console.log("clipboard read failed", err)
            })
        }
    }, { err: dynamic ->
        console.log("Failed to read clipboard items, falling back to text:", err)
        // Fallback to text-only API
        clipboard.readText().then({ text: String ->
            if (text.isNotBlank()) {
                processPasteText(text)
            }
        }, { err: dynamic ->
            console.log("clipboard read failed", err)
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
@JsExport()
fun insertFromWorkspace() {
    sendWithReply(Message("request-templates").payload(Directory(command = "list")), "templates") { message, payload ->

        println(" request templates got $message $payload")

        val directory = Json.decodeFromJsonElement<Directory>(message.payload!!)

        val options = eval("[]")

        directory.value.forEach {
            val e = """
            ({
                id:'insert-$it', label:'$it', 
                canvasKeybinding: (event) => false, 
                run: () => {
                    import("./afield.mjs").then( (afield) => {
                        afield.send("insert-template", {
                            command:"insert",
                            value:["$it"],
                        })
                    })
                }
            })
        """.trimIndent()
            options.push(eval(e))
        }

        if (directory.value.size > 0) {
            console.log(" opening options ")
            showPickMenu_custom(options)
        }

        canvasGestures.rebuildConnectionGroup()
        Handled.yes_remove
    }
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
                    t == "enter" -> "monaco.KeyCode.Enter"
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
    console.log(" selected list is ", selId.toString(), selection.toList())

    selection.flatMap { it }.associateBy { it.id }.toMap().entries.forEach {
        val fullDesc = it.value.description
        val pipeIndex = fullDesc.indexOf('|')
        val label = if (pipeIndex >= 0) fullDesc.substring(0, pipeIndex).trim() else fullDesc
        val kbSpec = if (pipeIndex >= 0) fullDesc.substring(pipeIndex + 1) else ""

        val kbExpr = parseKeybindingSpec(kbSpec.trim())
        val kbLine = if (kbExpr != null) "keybindings: [$kbExpr]," else "keybindings: [],"

        val e = """
            ({
                id:'${it.key}', label:'$label', 
                $kbLine
                canvasKeybinding: (event) => false, 
                run: () => { 
                    clearErrors(globalEditor)                
                    
                    import("./afield.mjs").then( (afield) => {
                     console.log(" computing selId ", selected, getSelectedIds())
                        afield.send("perform-action", {
                                uid_action: '${it.key}',
                                uid_currentSelection: getSelectedIds()
                        })
                        
                    })}
            })
        """.trimIndent()
        console.log(" about to eval ", e)
        val a = eval(e)
        all.push(a)
    }

    console.log(" refreshCommandsFromSelection - sel is ", selection.size, selection)

    if (boxes.size >= 1) {
        run {
            val e = """
            ({
                id:'fmt-kotlin', label:'Format Kotlin', 
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
                id:'-copy', label:'Copy box(es)', 
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
    if (boxes.size == 2) {
        // todo, check connections before presenting // n1.box_children contains uid of n2?
        val n1 = boxes[0].unsafeCast<dynamic>().name as String
        val n2 = boxes[1].unsafeCast<dynamic>().name as String
        run {
            val e = """
            ({
                id:'connect', label:'Connect `$n1` to `$n2`', 
                canvasKeybinding: (event) => event.key=='g' && !event.shiftKey && !event.ctrlKey && !event.altKey && !event.metaKey, 
                run: () => { 
                    import("./afield.mjs").then( (afield) => {afield.send("perform-action", {
                                uid_action: '-connect',
                                uid_currentSelection: [${selId}]
                        })})}
            })
        """.trimIndent()
            val a = eval(e)
            console.log(" eval 2 sel to ", a)
            all.push(a)
        }
        run {
            val e = """
            ({
                id:'disconnect', label:'Disconnect `$n1` from `$n2`', 
                canvasKeybinding: (event) => event.key=='u' && !event.shiftKey && !event.ctrlKey && !event.altKey && !event.metaKey, 
                run: () => { 
                    import("./afield.mjs").then( (afield) => {afield.send("perform-action", {
                                uid_action: '-disconnect',
                                uid_currentSelection: [${selId}]
                        })})}
            })
        """.trimIndent()
            val a = eval(e)
            console.log(" eval 2 sel to ", a)
            all.push(a)
        }
    }
    run {
        val e = """
            ({
                id:'-update-check', label:'Check for updates', 
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
    console.log(" refreshed commands, got", all)
    allActions.fromSelection = all
    updateActions()
}

fun registerBuiltinCommands() {
    allActions.builtin = eval(
        """
        [{
            id:'new-box',
            label:"New Box",
            canvasKeybinding: (event) => {
                let a = event.key=='n' && !event.shiftKey && !event.ctrlKey && !event.altKey && !event.metaKey
                return a
            },
            run: () => {
                newBox()
            }
        },{
            id:'delete-box',
            label:"Delete Box",
            canvasKeybinding: (event) => (event.keyCode==8 || event.keyCode==46) && (event.ctrlKey || event.metaKey),
            run: () => {
                deleteSelectedBoxes()
            }
        },{
            id:'copy-selection',
            label:"Copy Selection",
            canvasKeybinding: (event) => (event.key=='c') && !event.shiftKey && !event.altKey && (event.ctrlKey || event.metaKey),
            run: () => {
                if (window.globalEditor && window.globalEditor.hasTextFocus && window.globalEditor.hasTextFocus()) return
                copySelectedBoxesToClipboard()
            }
        },{
            id:'paste-selection',
            label:"Paste Selection",
            canvasKeybinding: (event) => (event.key=='v') && !event.shiftKey && !event.altKey && (event.ctrlKey || event.metaKey),
            run: () => {
                if (window.globalEditor && window.globalEditor.hasTextFocus && window.globalEditor.hasTextFocus()) return
                pasteBoxesFromClipboard()
            }
        },{
            id:'export-document-edn',
            label:"Export document (.edn)",
            canvasKeybinding: (event) => false,
            run: () => {
                exportDocumentToDownload()
            }
        },{
            id:'move-forward',
            label:"Move selected boxes forward",
            run: () => {
                    moveSelectedBoxesForward()
            }
        },{
            id:'insert-from-workspace',
            label:"Insert document from workspace",
            canvasKeybinding: (event) => (event.key=='t') && (event.ctrlKey || event.metaKey),
            run: () => {
                insertFromWorkspace()
            }
        },{
            id:'edit-hashtag',
            label:"Edit hashtag",
            canvasKeybinding: (event) => (event.key=='h') && (event.ctrlKey || event.metaKey),
            run: () => {
                editHashtag()
            }
        },{
            id:'save-as-template',
            label:"Save selection as template",
            canvasKeybinding: (event) => false,
            run: () => {
                saveAsTemplate()
            }
        },{
            id:'provisional-accept-all',
            label:"Accept All Provisional (Everywhere)",
            canvasKeybinding: (event) => false,
            run: () => {
                import("./afield.mjs").then( (afield) => {afield.send("provisional-accept-all", {})})
            }
        },{
            id:'provisional-reject-all',
            label:"Reject All Provisional (Everywhere)",
            canvasKeybinding: (event) => false,
            run: () => {
                import("./afield.mjs").then( (afield) => {afield.send("provisional-reject-all", {})})
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
