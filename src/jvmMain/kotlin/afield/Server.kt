@file:OptIn(DelicateCoroutinesApi::class)

package afield

import afield.ai.Ai4CompletionDoc
import afield.four.util.Options
import afield.logger.lprintln
import afield.stdlib.begin
import com.aallam.openai.api.BetaOpenAI
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.LoadState
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.autohead.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.partialcontent.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.joml.Vector2f
import org.joml.Vector4f
import java.awt.Desktop
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds


/***
 */

class SessionInformation(
    val filename: String,
    var boxes: ConcurrentHashMap<String, Box> = ConcurrentHashMap<String, Box>(),
    var root: Box,
    var sessions: MutableSet<WebSocketSession> = mutableSetOf<WebSocketSession>(),
    var topologyGeneration: Int = 0,
    var autorunTriggered: Boolean = false
) {
    // Track sidecar-bearing properties that need to be written to disk on next save.
    val pendingSidecarSaves = ConcurrentHashMap.newKeySet<Pair<String, String>>()

    val fileWatchManager: FileWatchManager by lazy {
        FileWatchManager(this).also { it.start() }
    }

    fun cleanup() {
        fileWatchManager.stop()
    }
}

fun SessionInformation.scheduleAutorunBoxes(delayMs: Long = 500L) {
    if (autorunTriggered) return
    autorunTriggered = true
    autorunBoxes(boxes.values, delayMs)
}

fun SessionInformation.autorunBoxes(targetBoxes: Collection<Box>, delayMs: Long = 500L) {
    GlobalScope.launch {
        delay(delayMs)
        targetBoxes.filter { it.autorun.isTrue() && it.id != "root" && !it.getPropertyRaw("_tombstone").isTrue() }.forEach { box ->
            launch {
                var attempts = 0
                while (box.offline && attempts < 20) {
                    delay(100)
                    attempts++
                }
                try {
                    box.begin()
                } catch (t: Throwable) {
                    lprintln("warning,autorun", "autorun failed for ${box.name}: ${t.message}")
                }
            }
        }
    }
}

var sessions = ConcurrentHashMap<WebSocketSession, SessionInformation>()
var documents = ConcurrentHashMap<String, SessionInformation>()

var min_frame_delay = 1L

val home = option("workspace") { System.getProperty("user.home") + "/Documents/AFieldWorkspace" }
val app = option("app") { File(".").absolutePath }

val preferSystemBrowser: Boolean = (System.getenv("AFIELD_SYSTEM_BROWSER")
    ?: System.getProperty("afield.systemBrowser") ?: "true").lowercase() != "false"

@OptIn(DelicateCoroutinesApi::class)
actual fun _send(message: Message) {
    _send(message, document = ".*")
}


@OptIn(DelicateCoroutinesApi::class)
fun _send(message: Message, document: String = ".*") {
    val m = Json.encodeToString(message)
    val documentRegex = document.toRegex()
    sessions.forEach {
        GlobalScope.launch {
            if (it.value.filename.matches(documentRegex)) it.key.send(m)
        }
    }
}


var lastFn by PersistentString { "welcome" }

@OptIn(DelicateCoroutinesApi::class)
fun _send(session: WebSocketSession?, message: Message) {
    if (session == null) {
        _send(message)
        return
    }
    val m = Json.encodeToString(message)
    GlobalScope.launch {
        session.send(m)
    }
}


@OptIn(DelicateCoroutinesApi::class)
fun notify(text: String, category: String = "") {
    thisSession.get()
    _send(thisSession.get(), Message("notify").payload(Notify(text, category)))
}


val thisSession = ThreadLocal<WebSocketSession>()
val thisSessionInfo = ThreadLocal<SessionInformation>()
var globalPlaywriteContext: Page? = null

class Server {
    companion object {

        lateinit var ourServer: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
        var ai_assistant: String? by PersistentString()
        var ai_thread: String? by PersistentString()

        var args: Array<String> = emptyArray()

        private fun openSystemBrowser(url: String) {
            try {
                if (Desktop.isDesktopSupported()) {
                    val d = Desktop.getDesktop()
                    if (d.isSupported(Desktop.Action.BROWSE)) {
                        d.browse(URI(url))
                        return
                    }
                }
            } catch (t: Throwable) {
                lprintln("warning,server", "Failed Desktop browse: ${t.message}")
            }

            val command = when {
                Options.isMac -> listOf("open", url)
                Options.isWindows -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
                else -> listOf("xdg-open", url)
            }

            try {
                ProcessBuilder(command).start()
            } catch (t: Throwable) {
                lprintln("warning,server", "Failed to launch system browser via $command: ${t.message}")
            }
        }

        @OptIn(DelicateCoroutinesApi::class, BetaOpenAI::class)
        fun server() {
            Options.doVulkanSDK()

            lprintln("info,server", "app directory is $app")
            System.setProperty("joml.format", "false")

            initializeClassCache()

            runAll(plugins)
            runAll(installHandlers)

            inMain {
                while (true) {
                    try {
                        val now = System.currentTimeMillis()
                        while (System.currentTimeMillis() - now < 1000 * 5) {
                            delay(500)
                            doResync()
                        }

                        documents.forEach { _, info ->
                            // Only persist when we know sidecar-bearing properties changed.
                            if (info.pendingSidecarSaves.isEmpty()) return@forEach

//                            lblock(" saving to ${info.filename}")
                            run {
                                try {
                                    // Include root box in saved boxes to persist root properties like bookmarks
                                    val allBoxes = info.boxes.values.toMutableSet()
                                    allBoxes.add(info.root)
                                    SavingContext().save(info.filename, allBoxes)
                                    info.pendingSidecarSaves.clear()
                                } catch (ex: Exception) {
                                    lprintln("error,server", "trouble saving")
                                    ex.printStackTrace()
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        lprintln("error,server", "exception inside saving / resync loop -- will retry")
                        t.printStackTrace()
                    }
                }
            }

            inMain {
                var t = System.currentTimeMillis()
                while (true) {
                    yield()
                    val now = System.currentTimeMillis()
                    if (now - t < min_frame_delay) {
                        Thread.sleep(min_frame_delay - (now - t))
                    }
                    t = System.currentTimeMillis()
                }
            }

            handlers["initialize"] = mutableSetOf({ message, reply ->
                sendProvisionalSet(thisSessionInfo.get().filename)

                Handled.yes
            })


            handlers["hello"] = mutableSetOf({ message, reply ->
                val hello = Json.decodeFromJsonElement<Hello>(message.payload!!)
                lprintln("info,server", "adding a session for $hello to ${thisSession.get()}")

                fun String.isHttpUrl(): Boolean =
                    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

                File(home).mkdirs()

                if (hello.filename.isHttpUrl()) {
                    // there are two options.
                    // take a clone into a fresh dir
                    // or, pull from remote repo
                    // TODO
                } else {

                }

                val fn = home + "/" + hello.filename

                lastFn = hello.filename

                val found = documents[fn]

                if (found == null) {
                    val root = Box("root")
                    root.name = "root"
                    root.visible = false

                    runAll(newRoot, root)

                    val si = SessionInformation(filename = fn, root = root)
                    root.inside = si
                    documents[fn] = si
                    sessions.put(thisSession.get(), si)
                    thisSessionInfo.set(si)
                    si.sessions.add(thisSession.get())

                    try {
                        val boxes = SavingContext().load(fn)
                        lprintln("info,server", "loaded boxes from $fn")
                        boxes.forEach {
                            lprintln("debug,server", "${it.id} ${it.frame} ${it.properties.get("_tombstone")}")
                        }

                        boxes.forEach {
                            it.children.removeIf { it.getPropertyRaw("_tombstone").isTrue() }
                            it.parents.removeIf { it.getPropertyRaw("_tombstone").isTrue() }
                        }

                        root.children.removeIf { it.getPropertyRaw("_tombstone").isTrue() }

                        root.children.forEach {
                            it.ki
                        } // kick off stdlib load

                        si.topologyGeneration++

                        // Initialize the file watch manager
                        si.fileWatchManager
                        si.scheduleAutorunBoxes()
                    } catch (fnf: FileNotFoundException) {
                        lprintln("warning,server", "file '$fn' not found; will be created on first save")
                    }

                } else {
                    sessions.put(thisSession.get(), found)
                    thisSessionInfo.set(found)
                    found.sessions.add(thisSession.get())

                    found.boxes.values.forEach {
                        lprintln("info,server", "resync all")
                        it.resyncAll()
                    }
                    found.topologyGeneration++
                    found.scheduleAutorunBoxes()
                }



                Handled.yes
            })

            handlers["set-string"] = mutableSetOf({ message, reply ->

                val set = Json.decodeFromJsonElement<Set_string>(message.payload!!)

                if (set.property == "name") {
                    lprintln("debug,server", "got set-string for name ${set.value}")
                    box(set.uid).name = set.value
                } else
                    box(set.uid).setPropertyRaw(set.property, set.value, doResync = true)

                // If this is a sidecar-backed property, mark it for persistence.
                if (Box.ioSeparateProperties.containsKey(set.property)) {
                    thisSessionInfo.get().pendingSidecarSaves += set.uid to set.property
                }

                Handled.yes
            })
            handlers["provisional-accept"] = mutableSetOf({ message, reply ->
                val target = Json.decodeFromJsonElement<ProvisionalTarget>(message.payload!!)
                acceptProvisional(thisSessionInfo.get().filename, target.uid, target.property)
                Handled.yes
            })
            handlers["provisional-reject"] = mutableSetOf({ message, reply ->
                val target = Json.decodeFromJsonElement<ProvisionalTarget>(message.payload!!)
                rejectProvisional(thisSessionInfo.get().filename, target.uid, target.property)
                Handled.yes
            })
            handlers["provisional-accept-all"] = mutableSetOf({ message, reply ->
                acceptAllProvisional(thisSessionInfo.get().filename)
                Handled.yes
            })
            handlers["provisional-reject-all"] = mutableSetOf({ message, reply ->
                rejectAllProvisional(thisSessionInfo.get().filename)
                Handled.yes
            })
            handlers["set-viewport"] = mutableSetOf({ message, reply ->
                val set = Json.decodeFromJsonElement<Set_rect>(message.payload!!)
                try {
                    thisSessionInfo.get().boxes.forEach {
                        it.value.viewport = set.value
                    }
                    doResync()
                    Handled.yes
                } catch (n: NullPointerException) {
                    // this is fine, we're just early on the set-viewport
                    Handled.yes
                }
            })

            handlers["set-rect"] = mutableSetOf({ message, reply ->

                val set = Json.decodeFromJsonElement<Set_rect>(message.payload!!)

                // Updates box frame or property; resyncs on main thread
                if (set.property == "frame") {
                    val b = box(set.uid)
                    b.frame = set.value
                    inMain {
                        doResync() // subsequent calls do nothing, so this effectively combines multiple moves to the same update
                    }
                } else {
                    box(set.uid).setPropertyRaw(set.property, set.value, doResync = true)
                    inMain {
                        doResync()
                    }
                }

                Handled.yes
            })

            handlers["complete"] = mutableSetOf({ message, reply ->

                val exec = Json.decodeFromJsonElement<CompletionRequest>(message.payload!!)

                val box = box(exec.uid)

                box.ke.complete(box, exec, reply)

                Handled.yes
            })

            handlers["resolve-completion"] = mutableSetOf({ message, reply ->
                val exec = Json.decodeFromJsonElement<CompletionResolveRequest>(message.payload!!)
                val docName = File(thisSessionInfo.get().filename).name
                val fileKey = "afield://$docName/${exec.uid}/${exec.property}"

                GlobalScope.launch(Dispatchers.IO) {
                    val doc = runCatching {
                        Ai4CompletionDoc.resolveDoc(
                            fileKey = fileKey,
                            label = exec.label,
                            displayText = exec.displayText,
                            insertText = exec.insertText,
                            detail = exec.detail,
                            existingDoc = exec.existingDocumentation,
                            allCode = exec.allCode,
                            line = exec.line,
                            column = exec.column
                        )
                    }.getOrElse { "" }
                    reply(Message("resolve-completion").payload(CompletionResolveResult(doc)))
                }

                Handled.yes
            })


            handlers["perform-action"] = mutableSetOf(
                { message, reply: (Message) -> Unit ->
                    val exec = Json.decodeFromJsonElement<Perform_action>(message.payload!!)
                    val action = actionTable[exec.uid_action]
                    if (action == null) {
                        lprintln("warning,server", "no such action '$action'")
                    } else {
                        action.invoke(exec.uid_currentSelection.mapNotNull { box(it, createIfMissing = false) })
                    }
                    Handled.yes
                })

            handlers["copy-boxes"] = mutableSetOf({ message, reply ->
                val req = Json.decodeFromJsonElement<CopyBoxes>(message.payload!!)
                val info = thisSessionInfo.get()
                val boxes = req.uids.mapNotNull { uid ->
                    if (uid == "root") info.root else info.boxes[uid]
                }.toSet()
                val boxesWithNested = collectAllNestedBoxes(boxes)
                val edn = SavingContext().exportEdnPackage(boxesWithNested, info.filename)
                val filename = File(info.filename).nameWithoutExtension.ifBlank { "document" }
                val countMessage = formatBoxCountMessage(boxes.size, boxesWithNested.size, "Copied")
                notify("$countMessage to clipboard", "copy")
                reply(Message("copy-boxes").payload(CopyBoxesResult(edn, filename)))
                Handled.yes
            })

            handlers["export-document"] = mutableSetOf({ _, reply ->
                val info = thisSessionInfo.get()
                // Include root box in export to preserve root properties like bookmarks
                val allBoxes = info.boxes.values.toMutableSet()
                allBoxes.add(info.root)
                val edn = SavingContext().exportEdnPackage(allBoxes, info.filename)
                val filename = File(info.filename).nameWithoutExtension.ifBlank { "document" }
                reply(Message("export-document").payload(ExportDocumentResult(edn, filename)))
                Handled.yes
            })

            handlers["paste-boxes"] = mutableSetOf({ message, reply ->
                val req = Json.decodeFromJsonElement<PasteBoxes>(message.payload!!)
                val info = thisSessionInfo.get()
                val usedIds = info.boxes.keys
                val idMap = mutableMapOf<String, String>()
                val filter: (String) -> String = { source ->
                    idMap.getOrPut(source) {
                        if (source == "root") return@getOrPut "root"
                        if (usedIds.contains(source)) UUID.randomUUID().toString() else source
                    }
                }

                val newBoxes = SavingContext().loadFromEdnText(req.edn, info.filename, filter)
                
                // Only translate top-level boxes, not nested children. Nested boxes have frames that are
                // relative to their parent's position (offset by -parent.x, -(parent.y + parent.h)).
                // Translating them would incorrectly add the offset twice since they move with their parent.
                val nestedBoxes = newBoxes.flatMap { it.nested }.toSet()
                newBoxes.forEach { b ->
                    if (b.id != "root" && b !in nestedBoxes) {
                        b.frame = b.frame.copy(x = b.frame.x + 10.0, y = b.frame.y + 10.0)
                    }
                    b.resyncAll()
                }
                info.topologyGeneration++
                doResync()
                notify("Pasted ${newBoxes.size} box${if (newBoxes.size == 1) "" else "es"} (+10,+10)", "paste")
                reply(Message("paste-boxes").payload(PasteBoxesResult(newBoxes.map { it.id })))
                Handled.yes
            })

            handlers["slideshow-command"] = mutableSetOf({ message, reply ->
                val cmd = Json.decodeFromJsonElement<SlideShowCommand>(message.payload!!)
                val info = thisSessionInfo.get()

                // Find the box with an active slideshow
                val slideShowBox = info.boxes.values.firstOrNull { it.getPropertyRaw("slideShow") != null }
                if (slideShowBox != null) {
                    val slideShow = slideShowBox.getPropertyRaw("slideShow") as? SlideShowInstance
                    if (slideShow != null) {
                        GlobalScope.launch {
                            when {
                                cmd.command == "next" -> slideShow.next()
                                cmd.command == "previous" -> slideShow.previous()
                                cmd.command == "start" -> slideShow.start()
                                cmd.command == "home" -> slideShow.home()
                                cmd.command == "stop" -> slideShow.stop()
                                cmd.command.startsWith("callback:") -> {
                                    // Handle custom link callbacks
                                    val callbackName = cmd.command.substring(9) // Remove "callback:" prefix
                                    val executed = slideShow.executeCallback(callbackName)
                                    if (!executed) {
                                        lprintln("warning,slideshow", "No callback registered for: $callbackName")
                                    }
                                }
                                else -> {
                                    // Try to parse as a step number
                                    val stepNum = cmd.command.toIntOrNull()
                                    if (stepNum != null) {
                                        slideShow.goToStep(stepNum)
                                    }
                                }
                            }
                        }
                    }
                }
                Handled.yes
            })

            handlers["slider-update"] = mutableSetOf({ message, reply ->
                val update = Json.decodeFromJsonElement<SliderUpdate>(message.payload!!)
                lprintln("debug,slider", "Received slider update: ${update.sliderId} = ${update.value}")
                
                // Get the callback for this slider and invoke it
                val callback = getSliderCallback(update.sliderId)
                if (callback != null) {
                    GlobalScope.launch {
                        try {
                            callback(update.value)
                        } catch (e: Exception) {
                            lprintln("error,slider", "Error in slider callback: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                } else {
                    lprintln("warning,slider", "No callback registered for slider: ${update.sliderId}")
                }
                
                Handled.yes
            })

            handlers["vec2-update"] = mutableSetOf({ message, reply ->
                val update = Json.decodeFromJsonElement<Vec2Update>(message.payload!!)
                lprintln("debug,controls", "Received vec2 update: ${update.controlId} = (${update.x}, ${update.y})")
                
                val callback = getVec2Callback(update.controlId)
                if (callback != null) {
                    GlobalScope.launch {
                        try {
                            callback(Vector2f(update.x.toFloat(), update.y.toFloat()))
                        } catch (e: Exception) {
                            lprintln("error,controls", "Error in vec2 callback: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                } else {
                    lprintln("warning,controls", "No callback registered for vec2: ${update.controlId}")
                }
                
                Handled.yes
            })

            handlers["checkbox-update"] = mutableSetOf({ message, reply ->
                val update = Json.decodeFromJsonElement<CheckboxUpdate>(message.payload!!)
                lprintln("debug,controls", "Received checkbox update: ${update.controlId} = ${update.value}")
                
                val callback = getCheckboxCallback(update.controlId)
                if (callback != null) {
                    GlobalScope.launch {
                        try {
                            callback(update.value)
                        } catch (e: Exception) {
                            lprintln("error,controls", "Error in checkbox callback: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                } else {
                    lprintln("warning,controls", "No callback registered for checkbox: ${update.controlId}")
                }
                
                Handled.yes
            })

            handlers["string-update"] = mutableSetOf({ message, reply ->
                val update = Json.decodeFromJsonElement<StringUpdate>(message.payload!!)
                lprintln("debug,controls", "Received string update: ${update.controlId} = ${update.value}")
                
                val callback = getStringCallback(update.controlId)
                if (callback != null) {
                    GlobalScope.launch {
                        try {
                            callback(update.value)
                        } catch (e: Exception) {
                            lprintln("error,controls", "Error in string callback: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                } else {
                    lprintln("warning,controls", "No callback registered for string: ${update.controlId}")
                }
                
                Handled.yes
            })

            handlers["color-update"] = mutableSetOf({ message, reply ->
                val update = Json.decodeFromJsonElement<ColorUpdate>(message.payload!!)
                lprintln("debug,controls", "Received color update: ${update.controlId} = rgba(${update.r}, ${update.g}, ${update.b}, ${update.a})")
                
                val callback = getColorCallback(update.controlId)
                if (callback != null) {
                    GlobalScope.launch {
                        try {
                            callback(Vector4f(update.r.toFloat(), update.g.toFloat(), update.b.toFloat(), update.a.toFloat()))
                        } catch (e: Exception) {
                            lprintln("error,controls", "Error in color callback: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                } else {
                    lprintln("warning,controls", "No callback registered for color: ${update.controlId}")
                }
                
                Handled.yes
            })


            registerTemplate(home)

            val port = System.getenv("PORT")?.toInt() ?: 9092
            ourServer = embeddedServer(Netty, port) {
                install(ContentNegotiation) {
                    json()
                }

                install(CORS) {
                    allowHeader(HttpHeaders.ContentType)
                    allowMethod(HttpMethod.Get)
                    allowMethod(HttpMethod.Post)
                    allowMethod(HttpMethod.Delete)
                    anyHost()
                }

                install(Compression) {
                    gzip()
                }

                install(WebSockets) {
                    pingPeriod = 10.seconds
                    timeout = 180.seconds
                    maxFrameSize = Long.MAX_VALUE
                    masking = false
                }

                install(PartialContent)
                install(AutoHeadResponse)

                routing {
                    webSocket("/ws") {
                        val ts = this
                        thisSession.set(ts)
                        try {
                            for (frame in incoming) {
                                if (frame is Frame.Text) {
                                    val text = frame.readText()
                                    sessions.entries.removeIf { (session, info) ->
                                        if (session.outgoing.isClosedForSend) {
                                            info.sessions.remove(session)
                                            // Stop file watcher if this was the last session for this document
                                            if (info.sessions.isEmpty()) {
                                                info.cleanup()
                                            }
                                            true
                                        } else false
                                    }

                                    var message: Message? = null
                                    try {
                                        message = Json.decodeFromString<Message>(text)
                                    } catch (t: SerializationException) {
                                    }

                                    if (message == null) {
                                        launch {
                                            thisSession.set(ts)
                                            thisSessionInfo.set(sessions.get(ts))
                                            try {
                                                rawHandlers.values.forEach {
                                                    it.forEach {
                                                        it(text)
                                                    }
                                                }
                                            } catch (t: Throwable) {
                                                t.printStackTrace()
                                            }
                                        }
                                    } else {
                                        launch { // should this block or not?...
                                            thisSession.set(ts)
                                            thisSessionInfo.set(sessions.get(ts))
                                            val savedScope = this.coroutineContext
                                            try {
                                                val r = handle(message) { message2 ->
                                                    val message3 = message.replyTo?.let { message2.toName(it) } ?: message2
                                                    val m = Json.encodeToString(message3)
                                                    GlobalScope.launch {
                                                        ts.send(m) // this enables, say, _print() to appear while this code is hogging the main execution thread
                                                    }
                                                }
                                            } catch (t: Throwable) {
                                                t.printStackTrace()
                                            }
                                        }
                                    }
                                }
                            }
                        } finally {
                            val info = sessions.remove(ts)
                            if (info != null) {
                                info.sessions.remove(ts)
                                // Stop file watcher if this was the last session for this document
                                if (info.sessions.isEmpty()) {
                                    info.cleanup()
                                }
                            }
                        }
                    }
                    route("/dir") {
                        handle {
                            val originalContent = File("./src/commonMain/resources/dir.html").readText(Charsets.UTF_8)
                            val modifiedContent = filter(originalContent)
                            call.respondText(modifiedContent, ContentType.Text.Html)
                        }
                    }
                    route("/browse") {
                        handle {
                            val originalContent =
                                File("./src/commonMain/resources/browser.html").readText(Charsets.UTF_8)
                            val modifiedContent = filter(originalContent)
                            call.respondText(modifiedContent, ContentType.Text.Html)
                        }
                    }
                    route("/tree") {
                        handle {
                            val originalContent = File("./src/commonMain/resources/tree.html").readText(Charsets.UTF_8)
                            val modifiedContent = filter(originalContent)
                            call.respondText(modifiedContent, ContentType.Text.Html)
                        }
                    }
                    route("/inspect") {
                        handle {
                            val originalContent =
                                File("./src/commonMain/resources/inspector.html").readText(Charsets.UTF_8)
                            val modifiedContent = filter(originalContent)
                            call.respondText(modifiedContent, ContentType.Text.Html)
                        }
                    }
                    route("/logs") {
                        handle {
                            val originalContent =
                                File("./src/commonMain/resources/logs.html").readText(Charsets.UTF_8)
                            val modifiedContent = filter(originalContent)
                            call.respondText(modifiedContent, ContentType.Text.Html)
                        }
                    }
                    route("/keyframe") {
                        handle {
                            val originalContent =
                                File("./src/commonMain/resources/keyframe_editor.html").readText(Charsets.UTF_8)
                            val modifiedContent = filter(originalContent)
                            call.respondText(modifiedContent, ContentType.Text.Html)
                        }
                    }

                    files("./build/js/packages/afield/kotlin")
                    files("./src/jsChatMain/resources/static/")
                    files("./src/jsMain/kotlin/resources/")
                    files("./src/jsMain/resources/")
                    files("./src/jsMain/resources/monaco")
                    files("./src/commonMain/kotlin/resources/")

                    route("/chat") {
                        handle {
                            call.respondFile(File("./build/js/packages/afield-js-chat/kotlin/static/chat.html"))
                        }
                    }

                    static("/") {
                        intercept(ApplicationCallPipeline.Call) {
                            var effectivePath = URI(call.request.uri).path
                            if (effectivePath.endsWith("/")) effectivePath = effectivePath + "index.html"
                            if (effectivePath.endsWith("index.html")) {
                                // List directories in the order you want to search for index.html
                                val directories = listOf(
                                    File("./build/kotlin-webpack/js/productionExecutable/"),
                                    File("./build/kotlin-webpack/jsChat/productionExecutable/"),
                                    File("./src/jsChatMain/resources/"),
                                    File("./src/jsMain/resources/"),
                                    File("./src/commonMain/resources/"),
//                                    File("./build/vite/dist/")
                                )

                                val indexFile = directories.map { File(it, effectivePath.trim('.', '/')) }
                                    .firstOrNull { it.exists() }

                                if (indexFile != null) {
                                    val originalContent = indexFile.readText(Charsets.UTF_8)

                                    lprintln("info,server", "serving index file from ${indexFile.absolutePath}")

                                    val modifiedContent = filter(originalContent)
                                    call.respondText(modifiedContent, ContentType.Text.Html)
                                    finish() // Stop further processing of this call
                                }
                            }
                        }
                        files(home + "/")
                    }

                    route("/project") {
                        get("{...}") {
                            lprintln("debug,server", "routing /project for ${call.request.uri}")
                            // Serve files from the project directory ($home)
                            val fullUri = call.request.uri
                            val pathOnly = URI(fullUri).path
                            val relativeUri = pathOnly.removePrefix("/project/")

                            val file = File(home, relativeUri)
                            if (file.exists() && file.isFile && file.canonicalPath.startsWith(File(home).canonicalPath)) {
                                // Security check: ensure the file is within the home directory
                                call.respondFile(file)
                            } else {
                                lprintln("warning,server", "project file not found or outside project directory: $relativeUri")
                                call.respond(HttpStatusCode.NotFound, "File not found in project directory")
                            }
                        }
                    }

                    route("/local") {
                        get("{...}") {
                            lprintln("debug,server", "routing /local dynamically ${call.request.uri}")
                            // Obtain the full request URI, strip any query or fragment, and remove the "/local" prefix
                            val fullUri = call.request.uri
                            val pathOnly = URI(fullUri).path
                            val relativeUri = pathOnly.removePrefix("/local/")

                            // Check the dynamic mappings for a matching prefix
                            val match = dynamicRoutes.getRoutes().entries.filter { relativeUri.startsWith(it.key) }
                                .maxByOrNull { it.key.length } // use longest match if overlapping

                            if (match != null) {
                                // Remove the mapping prefix to get the file's relative path
                                val fileRelativePath = relativeUri.removePrefix(match.key)
                                val file = File(match.value, fileRelativePath)
                                if (file.exists() && file.isFile) {
                                    call.respondFile(file)
                                    return@get
                                } else {
                                    lprintln("warning,server", "route found but missing file $fileRelativePath")
                                    call.respond(HttpStatusCode.NotFound, "File '${file}' not found")
                                    return@get
                                }
                            }
                            lprintln("warning,server", "failed to find route for $fullUri; checked ${dynamicRoutes.getRoutes().entries}")
                            call.respond(HttpStatusCode.NotFound, "No dynamic route for $fullUri")
                        }
                    }
                }

                val initialURL = "http://localhost:${port}/index.html?file=${lastFn}"
                lprintln("info,server", asciiWelcome)
                lprintln("info,server", "welcome to Field, alpha021")
                lprintln("info,server", initialURL)
                lprintln("info,server", "---")

                if (preferSystemBrowser) {
//                    thread { openSystemBrowser(initialURL) }
                }
                else {
                    thread {
                        Playwright.create().let { pw ->
                            val browser = pw.chromium().launch(
                                BrowserType.LaunchOptions()
//                                    .setHeadless(!showPlaywrightWindow)
                                    .setArgs(
                                        listOf(
                                            "--app=${initialURL}",
                                        )
                                    )
                            )
                            val ctx = browser.newContext(Browser.NewContextOptions().setViewportSize(null))
                            val page = ctx.newPage()
                            page.navigate(initialURL)
                            page.waitForLoadState(LoadState.NETWORKIDLE)
                            lprintln("info,server", "network idle")

                            globalPlaywriteContext = page
                        }
                    }
                }
            }.start(wait = true)
        }


        fun stop(box: Box) {
            box.ke.stop(box)
        }

        @JvmStatic
        fun main(args: Array<String>) {
            System.setProperty("java.awt.headless", "true")

            runAll(early)

            Companion.args = args

            thread {
                server()
            }

            Runloop.runLoop.start()

        }

    }
}

fun languageNameForProperty(box: Box, property: String): String {
    if (property == "code") return box.ke.languageName
    else return when (property) {
        "vertex" -> "GLSL (vertex)"
        "fragment" -> "GLSL (fragment)"
        "compute" -> "GLSL (compute)"
        "mesh" -> "GLSL (mesh)"
        "task" -> "GLSL (task)"
        else -> "Kotlin"
    }
}
