@file:OptIn(ExperimentalJsExport::class, ExperimentalUuidApi::class)

package afield

import CanvasGestures
import doubleTap
import handleCustomizationRequest
import handleEvalRequest
import handleOutputAreaRequest
import kotlinext.js.asJsObject
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.css.*
import kotlinx.dom.hasClass
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.svg.SVGSVGElement
import org.w3c.dom.url.URLSearchParams
import registerDoubleTap
import web.cssom.important
import web.svg.SVGElement
import web.timers.setTimeout
import kotlin.js.json
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

external fun interact(selector: String): dynamic
external fun interact(selector: HTMLElement): dynamic

external fun notify(text: String, category: String)

external fun setBoxRunning(uid: String, running: Boolean, watermark: Int, strand: String)

external fun showMonaco(): dynamic
external fun hideMonaco(): dynamic
external fun removeNewestOutput(): dynamic
external fun initMenuSupport(): dynamic

external fun notifyPotentialEditedPropertyHasBeenRemoteUpdated(uid: String, property: String, value: String)
external fun notifyPotentialEditedPropertyHasEditorStateRemoteUpdated(uid: String, property: String, value: String)
external fun notifyPotentialEditedPropertyHasHadLanguageChanged(uid: String, property: String, language: String)

external fun setDecorationsForEditor(uid: String, property: String, box: HTMLElement, decorSpec : dynamic)

external fun setActions(actions: dynamic): dynamic

external fun goMarkingMenu(x: Double, y: Double, menus: dynamic, event: dynamic, cancelCallback: dynamic): dynamic
external fun provideMissingMouseup(event: dynamic): dynamic

lateinit var mainCanvas: HTMLElement
lateinit var overlaySVGLayer: SVGSVGElement
external val monaco: dynamic

external val allActions: dynamic

external var originatingMouseDown: MouseEvent

lateinit var moveHandler: (dynamic) -> Unit
lateinit var resizeHandler: (dynamic) -> Unit
lateinit var monacoResizeHandler: (dynamic) -> Unit
var mainTranslation = listOf(0.0, 0.0)

@JsExport
@JsName("launchNumber")
var launchNumber = 0

external fun launchAll(uid: String, code: String)

@JsExport
@JsName("canvasGestures")
var canvasGestures = CanvasGestures(launcher = {
    it.forEach { launchAll(it.unsafeCast<dynamic>().uid as String, it.unsafeCast<dynamic>().code as String) }
})

var selected = mutableSetOf<HTMLElement>()
var selectedList = eval("[]")
var selectedProperty = "code"

var onSelectedChange = mutableMapOf<String, () -> Boolean>()

var events = HashMap<String, () -> Boolean>()

fun serviceEvents() {
    events.entries.retainAll { (k, v) ->
        v()
    }
}

@JsExport
@JsName("select")
fun select(selector: String): List<HTMLElement> {
    val s = document.querySelectorAll(selector)
    return (0 until s.length).mapNotNull {
        s.item(it) as? HTMLElement
    }
}


fun showCheatsheet() {
    println(select("#cheatsheet"))
    (document.getElementById("overlaycanvas") as HTMLElement).style.unsafeCast<dynamic>().backdropFilter =
        "blur(20px) brightness(0.0)"
    select("#cheatsheet").forEach {
        it.style.opacity = "1"
        it.style.unsafeCast<dynamic>().pointerEvents = "auto"
        it.onclick = { event ->
            hideCheatsheet()
        }
    }
}

fun hideCheatsheet() {
    println(select("#cheatsheet"))
    (document.getElementById("overlaycanvas") as HTMLElement).style.unsafeCast<dynamic>().backdropFilter = ""
    select("#cheatsheet").forEach {
        it.style.opacity = "0"
        it.style.unsafeCast<dynamic>().pointerEvents = "none"
    }
}


fun deselectBox(it: HTMLElement) {
    it.classList.remove("selected")
    it.unsafeCast<dynamic>().selected = false
    if (selected.contains(it)) {
        selected.remove(it)

        selectedList = eval("[]")
        selected.forEach { selectedList.push(it) }
        window.unsafeCast<dynamic>().selectedList = selectedList
        window.unsafeCast<dynamic>().selectedProperty = selectedProperty

        events["selectionChanged"] = {
            onSelectedChange.entries.retainAll { (k, v) -> v() }
            false
        }
    }

}

fun selectBox(it: HTMLElement) {
    it.classList.add("selected")
    it.unsafeCast<dynamic>().selected = true
    if (!selected.contains(it)) {
        selected.add(it)

        val p = it.parentNode!!
        p.removeChild(it)
        p.appendChild(it)

        selectedList = eval("[]")
        selected.forEach { selectedList.push(it) }
        window.unsafeCast<dynamic>().selectedList = selectedList
        window.unsafeCast<dynamic>().selectedProperty = selectedProperty

        events["selectionChanged"] = {
            onSelectedChange.entries.retainAll { (k, v) -> v() }
            false
        }
    }
}

var styles = CssBuilder().apply {
    body {
        maxWidth = 100.vw
        maxHeight = 100.vh
//        backgroundColor = Color.red
    }

    ".selected" {
        borderColor = important(rgb(0, 128, 255, 1.0))
    }

    ".doubleClickToEdit" {
        userSelect = UserSelect.none
    }

}

@OptIn(DelicateCoroutinesApi::class)
suspend fun main() {
    launchNumber++
    println(" -- welcome to Main.kt --")
    window.unsafeCast<dynamic>().selected = selected
    window.unsafeCast<dynamic>().selectedList = selectedList
    window.unsafeCast<dynamic>().selectedProperty = selectedProperty

    window.unsafeCast<dynamic>().showCheatsheet = ::showCheatsheet

    initializeClient()
    registerDoubleTap()

    doubleTap["Escape"] = {
        removeNewestOutput()
        null
    }

    doubleTap["Shift"] = {
        if (window.unsafeCast<dynamic>().globalEditor.hasTextFocus()) {
            console.log(" body ")
            window.unsafeCast<dynamic>().globalEditor._domElement.blur()
            document.body!!.focus()
        } else {
            console.log(" editor ")
            window.unsafeCast<dynamic>().globalEditor.focus()
        }
    }

    handlers.put("local-eval") { message, _ ->
        val le = Json.decodeFromJsonElement<LocalEval>(message.payload!!)
        val boxes = document.body!!.getElementsByClassName("box").asList()
        val found = boxes.firstOrNull { it.unsafeCast<dynamic>().uid == le.uid }

        console.log(" -- local eval ", found)
        found?.let {
            try {
                handleEvalRequest(found as HTMLElement, le.prompt)
            } catch (e: Exception) {
                println("exception thrown in local eval")
                e.printStackTrace()
            }
        }

        Handled.yes
    }

    handlers.put("set-rect") { message, _ ->

        val set = Json.decodeFromJsonElement<Set_rect>(message.payload!!)
        if (set.property !== "frame") return@put Handled.no

        console.log(" set rect ", set, set.value)

        val u = set.uid

        val boxes = document.body!!.getElementsByClassName("box").asList()
        val found = boxes.firstOrNull { it.unsafeCast<dynamic>().uid == u }

        if (found === null) {
            console.log(" created box on demand ")
            mainCanvas.append {
                createBox("", frame = set.value, uid = u, sendInitialSync = false)
            }
        } else {

            console.log(" set initial position ")
            (found as HTMLElement).style.transform = "translate(${set.value.x}px, ${set.value.y}px)"

            found.style.width = "${set.value.w}px"
            found.style.height = "${set.value.h}px"
            found.style.visibility = if (set.value.w > 0.0 || set.value.h > 0.0) "visible" else "hidden"

            found.unsafeCast<dynamic>().data_x = set.value.x
            found.unsafeCast<dynamic>().data_y = set.value.y
            found.unsafeCast<dynamic>().data_w = set.value.w
            found.unsafeCast<dynamic>().data_h = set.value.h
        }
        canvasGestures.rebuildConnectionGroup()

        console.log("made it out of set rect", set, set.value)
        Handled.yes
    }
    handlers.put("set-boolean") { message, _ ->
        val set = Json.decodeFromJsonElement<Set_boolean>(message.payload!!)
        val u = set.uid

        val boxes = document.body!!.getElementsByClassName("box").asList()
        var found = boxes.firstOrNull { it.unsafeCast<dynamic>().uid == u }

        fun defaultCreate() {
            mainCanvas.append {
                found = createBox("untitled", frame = Rect(0.0, 0.0, 0.0, 0.0), uid = u, sendInitialSync = false)
            }
        }
        if (found == null) {
            defaultCreate()
        }


        when (set.property) {
            "visible" -> {
                if (found != null) {
                    if (!set.number) found.unsafeCast<dynamic>().style.visibility = "hidden"
                    else found.unsafeCast<dynamic>().style.visibility = "visible"
                    canvasGestures.rebuildConnectionGroup()
                }
            }
            "offline" -> {
                console.log(" got offline message ", set)
                if (found !=null) {
                    if (set.number) found.unsafeCast<HTMLElement>().classList.add("offline")
                    else found.unsafeCast<HTMLElement>().classList.remove("offline")
                }
            }
         }
        Handled.yes
    }

    handlers.put("is-running") { message, _ ->
        println(" got is-running message ")
        val isRunning = Json.decodeFromJsonElement<IsRunning>(message.payload!!)
        setBoxRunning(isRunning.uid, isRunning.isRunning, isRunning.watermark, isRunning.strand)

        Handled.yes
    }
    handlers.put("describe-action") { message, _ ->
        val set = Json.decodeFromJsonElement<Describe_actions>(message.payload!!)
        val u = set.uid

        val boxes = document.body!!.getElementsByClassName("box").asList()
        var found = boxes.firstOrNull { it.unsafeCast<dynamic>().uid == u }

        if (found != null) {
            found.unsafeCast<dynamic>().injectedActions = set.value
            console.log(" injected actions are ${set.value}")
            refreshCommandsFromSelection(
                selected.toList(),
                selected.mapNotNull { it.unsafeCast<dynamic>().injectedActions as List<Action_Desc>? }
                    .toList())
        }

        Handled.yes
    }

    fun defaultCreate(u: String): HTMLElement {
        val boxes = document.body!!.getElementsByClassName("box").asList()
        var found = boxes.firstOrNull { it.unsafeCast<dynamic>().uid == u } as HTMLElement?
        if (found != null) return found!!

        mainCanvas.append {
            found = createBox("untitled", frame = Rect(0.0, 0.0, 0.0, 0.0), uid = u, sendInitialSync = false)
        }
        return found!!
    }

    handlers.put("set-topology") { message, _ ->
        val set = Json.decodeFromJsonElement<Set_topology>(message.payload!!)
        val u = set.uid

        var found = defaultCreate(u)
        found.unsafeCast<dynamic>().box_children = set.value.toTypedArray()

        canvasGestures.rebuildConnectionGroup()
        Handled.yes
    }

    handlers.put("notify") { message, _ ->
        val set = Json.decodeFromJsonElement<Notify>(message.payload!!)
        notify(set.text, set.category)
        Handled.yes
    }

    handlers.put("set-output") { message, _ ->
        val set = Json.decodeFromJsonElement<Set_output>(message.payload!!)
        val u = set.uid

        var found = defaultCreate(u)

        when (set.property) {
            "outputarea" -> {
                handleOutputAreaRequest(found as HTMLElement, set)
            }
        }
        Handled.yes
    }

    handlers.put("set-string") { message, _ ->
        val set = Json.decodeFromJsonElement<Set_string>(message.payload!!)
        val u = set.uid

        var found = defaultCreate(u)

        when (set.property) {
            "name" -> {
                if (found === null) {
                    mainCanvas.append {
                        createBox(set.value, frame = Rect(0.0, 0.0, 0.0, 0.0), uid = u, sendInitialSync = false)
                    }
                } else {
                    found!!.getElementsByClassName("doubleClickToEdit").asList()[0].innerHTML = set.value
                }
            }

            "code" -> {
                found.unsafeCast<dynamic>().code = set.value
                notifyPotentialEditedPropertyHasBeenRemoteUpdated(u, "code", set.value)
            }

            "code_editorState" -> {
                found.unsafeCast<dynamic>().code_editorState = set.value
                notifyPotentialEditedPropertyHasEditorStateRemoteUpdated(u, "code", set.value)
            }

            "code_decorationState" -> {
                found.unsafeCast<dynamic>().decorSpec = set.value
                console.log(set.value)
                val sv = JSON.parse<dynamic>(set.value)
                setDecorationsForEditor(u, "code",found.unsafeCast<HTMLElement>(), sv)
            }

            "code_language" -> {
                if (set.value != found.unsafeCast<dynamic>().code_language) {
                    found.unsafeCast<dynamic>().code_language = set.value
                    notifyPotentialEditedPropertyHasHadLanguageChanged(u, "code", set.value)
                }
            }

            "fragment" -> {
                found.unsafeCast<dynamic>().fragment = set.value
            }

            "metadata" -> {
                found.unsafeCast<dynamic>().metadata = set.value
            }

            "compute" -> {
                found.unsafeCast<dynamic>().compute = set.value
            }

            "preamble" -> {
                found.unsafeCast<dynamic>().preamble = set.value
            }

            "vertex" -> {
                found.unsafeCast<dynamic>().vertex = set.value
            }

            "customization" -> {
                handleCustomizationRequest(found as HTMLElement, set)
            }
        }

        Handled.yes
    }

    val filename = URLSearchParams(document.location?.search).get("file") ?: "default"
    val username = URLSearchParams(document.location?.search).get("user") ?: "everybody"
    val hello = Message("hello").payload(Hello(filename, username))
    onOpen.add(hello)

    val body = document.body ?: error("No body")
    console.log(" well, this is fun 1")
    body.append {
        style {
            unsafe {
                +styles.toString()
            }
        }
    }
    console.log(" well, this is fun 2")
    val mainViewport = body.append {
        div {
            style = CssBuilder().apply {
                width = 100.vw
                height = 100.vh
                backgroundColor = rgb(20, 20, 20, 1.0)
                padding = Padding(0.px)
                margin = Margin(0.px)
            }.toString()
        }
    }.first()

    console.log(" well, this is fun 3")
    mainCanvas = mainViewport.append {
        div {
            style = CssBuilder().apply {
                width = 0.px
                height = 0.px
                overflow = Overflow.visible
                backgroundColor = Color.green
                padding = Padding(0.px)
                margin = Margin(0.px)
            }.toString()
        }

    }.first()

    overlaySVGLayer = document.getElementById("overlaysvglayer") as SVGSVGElement

    mainViewport.oncontextmenu = {
        console.log(" (suppressed right click) ")
        it.preventDefault()
    }

    console.log(" well, this is fun 4")
    mainViewport.onclick = { event ->
        // now handled in gestures.kt
//        if (event.target == mainViewport) {
//            selected.copy().forEach {
//                deselectBox(it)
//            }
//        }
    }

    mainViewport.onmousedown = { event ->
        console.log("-onmousedown d", event.target == mainViewport, event)
        if (event.target == mainViewport) {
            canvasGestures.down(event)
        } else {

        }
    }
    mainViewport.onmouseup = { event ->
        canvasGestures.up(event)
    }
    mainViewport.onmousemove = { event ->
        canvasGestures.move(event)
    }


    console.log(" well, this is fun 4")
    mainViewport.onwheel = { event ->

        // todo, should read "has outputArea_content as parent"
        if (event.target is SVGElement) {
        } else {

            event.preventDefault()
            event.stopPropagation()

            var mc = mainCanvas.unsafeCast<dynamic>()
            val x: Number = (mc.data_x ?: 0.0) - event.deltaX
            val y: Number = (mc.data_y ?: 0.0) - event.deltaY

            (mc as HTMLElement).style.transform = "translate(${x}px, ${y}px)"
            mainTranslation = listOf(x.toDouble(), y.toDouble())

            println(" main translation now ${x} ${y} ${window.innerWidth} ${window.innerHeight}")

            mc.data_x = x
            mc.data_y = y

            send(Message("set-viewport").payload(Set_rect(uid="root", property="viewport", Rect(x.toDouble(),y.toDouble(), window.innerWidth.toDouble(), window.innerHeight.toDouble()))))

            canvasGestures.rebuildConnectionGroup()
        }

        null
    }


    mainCanvas.unsafeCast<dynamic>().data_x = 0
    mainCanvas.unsafeCast<dynamic>().data_y = 0

    // TODO!
    val outputAreaResizing = interact(".outputArea")

    val outputResizeHandler = { event: dynamic ->
        val target: dynamic = event.target

        val parent_w = target.parentElement.parentElement.data_w
        target.style.width = event.rect.width + "px"
        target.style.height = event.rect.height + "px"

        target.data_w = event.rect.width
        target.data_h = event.rect.height
    }

    outputAreaResizing.resizable(
        json("edges" to json("top" to false, "bottom" to true, "left" to false, "right" to true))
    ).on("resizemove") { event: dynamic ->
        outputResizeHandler(event)
    }

    val moncaoResizing = interact(".monaco-editor-container")

    monacoResizeHandler = { event: dynamic ->

        val target: dynamic = event.target
        val x: Number = (target.data_x ?: 0.0) + event.deltaRect.left
        val y: Number = (target.data_y ?: 0.0) + event.deltaRect.top

//        (target as HTMLElement).style.transform = "translate(${x}px, ${y}px)"

        target.style.width = event.rect.width + "px"
        target.style.height = event.rect.height + "px"

        target.data_x = x
        target.data_y = y
        target.data_w = event.rect.width
        target.data_h = event.rect.height
    }

    moncaoResizing.resizable(
        json("edges" to json("top" to true, "bottom" to true, "left" to true, "right" to true))
    ).on("resizemove") { event: dynamic ->
        monacoResizeHandler(event)
    }

    val interactable = interact(".box")

    moveHandler = { event: dynamic ->

        val target: dynamic = event.target
        val x: Number = (target.data_x ?: 0.0) + event.dx
        val y: Number = (target.data_y ?: 0.0) + event.dy

//        (target as HTMLElement).style.transform = "translate(${x}px, ${y}px)" // round trip to server
        target.data_x = x
        target.data_y = y

        var t = window.getComputedStyle(event.target).borderWidth.split("px")[0].toDouble() * 0.0
//        console.log(event.rect, event.target.getClientRects(), t)

        val uid = target.uid
        send(
            Message("set-rect").payload(
                Set_rect(
                    uid = uid,
                    property = "frame",
                    Rect(x.toDouble(), y.toDouble(), event.rect.width - t * 2, event.rect.height - t * 2)
                )
            )
        )
    }

    interactable.on("up") { event ->
        console.log(" interact up ", event)
    }

    interactable.on("dragend") { event ->
        console.log(" interact dragend ", event)
        provideMissingMouseup(event)
    }

    interactable.on("down") { event ->
        val target: dynamic = reorient(event.target)
        if (event.altKey) {

            var already = false
            if (target.classList.contains("running")) {
                notify(" <i>${target.name}</i> is already running", "launch")
                already = true
            } else {
                notify("launched <i>${target.name}</i>", "launch")
                launchAll(target.uid, target["code"])
                already = false
            }

            val menus = mutableListOf(
                json("text" to "stop", "callback" to {
                    notify("<i>${target.name}</i> stopped", "launch")
                    stopBox(target.uid)
                }, "radius" to 1.0, "angle" to 0.0)
            )

            if (!already) {
                menus.add(json("text" to "launch", "callback" to {
                    notify("<i>${target.name}</i> will continue", "launch")
                }, "radius" to 1.0, "angle" to 180.0))
            }

            // needs to be a default for stopBox to also be called when the menu is cancelled

            goMarkingMenu(event.x, event.y, menus.toTypedArray().asJsObject(), event, {
                notify("<i>${target.name}</i> stopped", "launch")
                stopBox(target.uid)
            });

            originatingMouseDown = event

//            event.preventDefault();
//            event.stopPropagation();

        } else {
            if (event.metaKey) {}
            else if (event.shiftKey) {
                if (selected.contains(event.target)) deselectBox(target)
                else selectBox(target)
            } else {
                selected.copy().forEach {
                    deselectBox(it)
                }
                selectBox(target)
            }
        }
    }

    interactable.draggable(json("onmove" to { event: dynamic ->
        console.log(" originating mousedown is ", originatingMouseDown.altKey)

        if (event.altKey) {
        } else if (originatingMouseDown.altKey) {
        } else {
            var targeted = false
            selected.forEach {
                event.target = reorient(it)
                if (event.target) {
                    moveHandler(event)
                    targeted = true
                }
            }
            if (targeted)
                canvasGestures.rebuildConnectionGroup()
        }
    }))



    resizeHandler = { event: dynamic ->

        val target: dynamic = event.target

        console.log(" inside resize handler ", event.deltaRect, event.delta)
        var x: Double = (target.data_x ?: 0.0) + event.deltaRect.left
        var y: Double = (target.data_y ?: 0.0) + event.deltaRect.top

        var w = event.rect.width
        var h = event.rect.height

        if (target.lock_w) w = target.data_w ?: 100.0
        if (target.lock_h) h = target.data_h ?: 100.0

        if (target.lock_x) x = target.data_x ?: 0.0
        if (target.lock_y) y = target.data_y ?: 0.0

        if (target.lock_w && !target.lock_x) {
            x += event.deltaRect.right as Double
        }
        if (target.lock_h && !target.lock_y) {
            y += event.deltaRect.bottom as Double
            // why was this here?
//            if (!target.lock_x)
//                x += event.delta.x as Double
        }

//        (target as HTMLElement).style.transform = "translate(${x}px, ${y}px)" // round-trip to server?
        // renable this for serverless


        target.data_x = x
        target.data_y = y
        target.data_w = w
        target.data_h = h

//        target.style.width = w + "px"
//        target.style.height = h + "px"

        console.log(" -> ${Rect(target.data_x, target.data_y, target.data_w, target.data_h)}")
        val uid = target.uid
        send(
            Message("set-rect").payload(
                Set_rect(
                    uid = uid, property = "frame", Rect(target.data_x, target.data_y, target.data_w, target.data_h)
                )
            )
        )

    }

    interactable.resizable(
        json(
            "edges" to json("top" to true, "bottom" to true, "left" to true, "right" to true),
            "invert" to "reposition"
        )
    ).on("resizemove") { event: dynamic ->

        console.log(" RESIZING ")

        if (originatingMouseDown.altKey) {
        } else if (event.altKey) {
        } else {
            selected.forEach {
                event.target = reorient(it)
                if (event.target) resizeHandler(event)
            }
        }
    }

    interact(".doubleClickToEdit").on("doubletap") { event ->
        console.log(" hello ? anybody? ", event)
        val t = event.target.unsafeCast<HTMLElement>()
        val box = t.parentElement!!.parentElement

        if (t.contentEditable != "true") {
            t.contentEditable = "true"

            fun fireChange() {
                box.unsafeCast<dynamic>().name = t.textContent
                val uid = box.unsafeCast<dynamic>().uid as String
                send(
                    Message("set-string").payload(
                        Set_string(
                            uid = uid, property = "name", value = t.textContent ?: ""
                        )
                    )
                )
            }

            t.onblur = {
                t.contentEditable = "false"
                fireChange()
                null
            }

            setTimeout({
                t.contentEditable = "true"
                val range = document.createRange();
                val selection = (window.unsafeCast<dynamic>()).getSelection()
                range.selectNodeContents(t);
                selection?.removeAllRanges();
                selection?.addRange(range);
                t.focus();
            }, 0);

            setTimeout({
                t.contentEditable = "true"
                val range = document.createRange();
                val selection = (window.unsafeCast<dynamic>()).getSelection()
                range.selectNodeContents(t);
                selection?.removeAllRanges();
                selection?.addRange(range);
                t.focus();
            }, 300);

            t.onkeydown = { event ->
                if (event.keyCode == 13) {
                    event.preventDefault()
                    t.blur()
                    fireChange()
                }
            }
        }
    }

    later({
        initMenuSupport()
        registerBuiltinCommands()
        hideMonaco()

        later({
            notify("finished loading — press <b>?</b> for help", "setup")
            (document.getElementById("overlaycanvas") as HTMLElement).style.unsafeCast<dynamic>().backdropFilter = ""
        }, 100)

    }, 0)
    forever(::serviceEvents, 30)

    onSelectedChange.put("server") {
        val ids = selected.map { it.id as String }
        true
    }

    send(Message("set-viewport").payload(Set_rect(uid="root", property="viewport", Rect(0.0,0.0, window.innerWidth.toDouble(), window.innerHeight.toDouble()))))

    console.log(" printing client ?? ")
    console.log(client)

    registerMouseBug()

    window.addEventListener("resize", {
        canvasGestures.rebuildConnectionGroup()
    })
}


fun stopBox(uid: String) {
    console.log(" sending stop-box message")
    send(Message("stop-box").payload(Stop_box(uid = uid)))

}

var retired = mutableSetOf<String>()

@OptIn(ExperimentalUuidApi::class)
fun TagConsumer<HTMLElement>.createBox(
    name: String, frame: Rect, uid: String = Uuid.random().toString(), sendInitialSync: Boolean = true
): HTMLElement {
    lateinit var oa: HTMLElement
    val d1 = div(classes = "box offline") {
        style = CssBuilder().apply {
            position = Position.absolute
            color = rgb(255, 255, 255, 0.8)
            borderRadius = 5.px
            backdropFilter = "blur(4px)"
            border = Border(1.px, BorderStyle.solid, rgb(255, 255, 255, 0.0))
            backgroundColor = rgb(50, 50, 50, 0.5)
            display = Display.flex
            flexDirection = FlexDirection.row
            flexWrap = FlexWrap.wrap
            justifyContent = JustifyContent.center
            alignItems = Align.center
            width = frame.w.px
            height = frame.h.px
            visible = frame.w > 0 || frame.h > 0
        }.toString()
        div {
            span(classes = "doubleClickToEdit") {
                +name
            }
        }
        div(classes = "statusArea") {
            div(classes = "tooltiptext") {
                +""
            }
        }
        div(classes = "outputArea") {
            style = CssBuilder().apply {
//                padding = Padding(5.px)
                position = Position.absolute
                left = 0.px;
                top = LinearDimension("calc( 100% + 10px )");
//                right = 0.px;
//                borderRadius = 5.px;
//                background = "rgba(0,0,0,0.5)";
                userSelect = UserSelect.none;
                overflow = Overflow.visible;
                visibility = Visibility.hidden;
                height = 0.px;
            }.toString()
            div(classes = "disclosure-group") {
            }

//            div(classes = "outputArea_controls") {
//                style = CssBuilder().apply {
//                    position = Position.absolute
//                    left = -30.px
//                    top = -0.px
//                    width = 35.px
//                }.toString()
//                div(classes = "closebox hoverable") {
//                    style = CssBuilder().apply {
//                        textAlign = TextAlign.center
//                    }.toString()
//                    unsafe { +"&#215;" }
//                    onMouseDownFunction = { event ->
//                        console.log("clicked ${event.target}")
//                        oa.style.visibility = "hidden"
//                    }
//                }
//                div(classes = "togglebox hoverable") {
//                    style = CssBuilder().apply {
//                        textAlign = TextAlign.center
//                    }.toString()
//                    unsafe { +"&varr;" }
//                    onMouseDownFunction = { event ->
//                        console.log("clicked ${event.target}")
//                        val content = oa.getElementsByClassName("outputArea_content").asList()[0] as HTMLElement
//                        val h = (content.children[0]!!.getClientRects()[0].height + 20).px.toString()
//                        if (content.style.height != "30px") {
//                            content.style.height = 30.px.toString()
//                            oa.style.height = 30.px.toString()
//                        } else {
//                            content.style.height = h
//                            oa.style.height = h
//                        }
//
//                    }
//                }
//            }
//            div(classes = "outputArea_content") {
//                style = CssBuilder().apply {
////                    overflow = Overflow.scroll
//                }.toString()
//            }
        }
    }
    d1.style.transform = "translate(${frame.x}px, ${frame.y}px)"

    val d1d = d1.unsafeCast<dynamic>()
    d1d.data_x = frame.x
    d1d.data_y = frame.y
    d1d.data_w = frame.w
    d1d.data_h = frame.h
    d1d.uid = uid
    d1d.name = name

    oa = d1.getElementsByClassName("outputArea").asList()[0] as HTMLElement

    if (sendInitialSync) {
        send(
            Message("set-string").payload(
                Set_string(
                    uid = uid, property = "name", value = name
                )
            )
        )

        send(
            Message("set-rect").payload(
                Set_rect(
                    uid = uid, property = "frame", value = frame
                )
            )
        )
    }
    return d1
}

fun later(f: () -> Unit, delay_ms: Int = 100) {
    setTimeout({
        f()
    }, delay_ms)
}

fun forever(f: () -> Unit, delay_ms: Int) {
    setTimeout({
        forever(f, delay_ms)
        f()
    }, delay_ms)
}

fun reorient(it: dynamic): dynamic {
    if (it == null) return null
    if (it.unsafeCast<HTMLElement>().hasClass("box")) return it
    return it.unsafeCast<HTMLElement>().parentElement?.let { parent ->
        reorient(parent)
    } ?: null
}


fun <E> MutableSet<E>.copy() = HashSet<E>(this)

