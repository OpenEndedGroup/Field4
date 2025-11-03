package afield

import kotlinx.browser.document
import kotlinx.html.dom.append
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.MouseEvent

var lastMouse = 0.0 to 0.0

external fun showPickMenu_custom(options: dynamic): dynamic


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
                        })})}
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
@JsExport
fun editHashtag() {
    var selectedBoxes = selected.map { it.id }
    sendWithReply(Message("request-hashtag").payload(RequestHashtags(boxes = selectedBoxes, getOrSet = true)), "hashes") { message, payload ->

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
                id:'connect', label:'Disconnect `$n1` to `$n2`', 
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
                console.log(event)
                let a = event.key=='n' && !event.shiftKey && !event.ctrlKey && !event.altKey && !event.metaKey
                console.log(" returning", a)
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
