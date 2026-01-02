package afield

import afield.logger.lblock
import afield.logger.lprintln
import afield.stdlib.Action
import afield.stdlib.Delegation
import afield.stdlib.Marker
import afield.stdlib.actions
import checkUpdateVersion
import defaultUpdateUrl
import downloadAndUnzip
import fetchUpdateInfo
import formatKotlinCode
import io.ktor.util.collections.*
import kotlinx.coroutines.*
import promoteStagingToLive
import writeLocalVersion
import kotlin.script.experimental.api.KotlinType

var needsResync = ConcurrentSet<Pair<Box, String>>()
var Box.offline by Delegation<Boolean>(defaultValue = { true }) // boxes are offline until they've finished their stdlib execution
var Box.preamble by Delegation<String>(defaultValue = { "" })
var Box.autorun by Delegation<Boolean>(defaultValue = { false })


interface HasProperties {
    fun setPropertyRaw(name: String, value: Any, doResync: Boolean = true)
    fun getPropertyRaw(name: String, default: Any? = null): Any?
}

interface HasHierarchy : HasProperties {
    var children: MutableSet<Box>
    var parents: MutableSet<Box>
}

open class Box(val id: String, val output: DefaultOutput = DefaultOutput()) : HasOutput by output, HasProperties,
    HasHierarchy {


    lateinit var inside: SessionInformation
    fun isInsideInitialized() = ::inside.isInitialized

    val properties = mutableMapOf<String, Any?>()
    val markers = mutableMapOf<String, Marker>()

    var ki by lazyVar {
        makeKI(this)
    }
    var ke: BoxExecution = KotlinExecution()

    var frame by Delegation<Rect>(defaultValue = { Rect(0.0, 0.0, 0.0, 0.0) }, hook = ::onFrameChange)
    var viewport by Delegation<Rect>(defaultValue = { Rect(0.0, 0.0, 0.0, 0.0) }, hook = ::onViewportChange)
    var name by Delegation<String>(defaultValue = { "" }, hook = ::onNameChange)
    var code by Delegation<String>()
    var customization by Delegation<String>()

    val thisBox: Box
        get() = this

    var runGeneration = mutableMapOf("default" to -1)
    var fragmentGeneration: Int = -1
    var jobs = mutableListOf<Job>()

    override var children by Delegation<MutableSet<Box>>()
    override var parents by Delegation<MutableSet<Box>>()
    var visible by Delegation<Boolean>()

    init {
        children = mutableSetOf()
        parents = mutableSetOf()
    }

    val root: Box
        get() = inside.root

    fun connect(to: Box, property: String = "children") {
        if (to.getPropertyRaw("_tombstone").isTrue()) {
        } else {
            // we drive "children" through the actual concrete var Box.children, but other connections (without counter-properties) are now possible here
            val (accessor, counterName, counterAccessor) = if (property == "children") {
                Triple(::children, "parents", to::parents)
            } else {
                Triple({ to.getPropertyRaw(property) as MutableSet<Box> }, null, null)
            }

            if (!accessor().contains(to)) {
                accessor() += to
                counterAccessor?.let { it() += this }

                needsResync.add(this to property)
                if (counterName != null)
                    needsResync.add(to to counterName)

                thisSessionInfo.get().topologyGeneration++

                if (property == "children") {
                    onTopologyChange(this, true, to)
                }
            }
        }
        needsResync.add((this as Box) to "actions")
        needsResync.add((to as Box) to "actions")
    }

    fun disconnect(to: Box, property: String = "children") {

        // we drive "children" through the actual concrete var Box.children, but other connections (without counter-properties) are now possible here
        val (accessor, counterName, counterAccessor) = if (property == "children") {
            Triple(::children, "parents", to::parents)
        } else {
            Triple({ to.getPropertyRaw(property) as MutableSet<Box> }, null, null)
        }

        if (accessor().contains(to)) {
            accessor() -= to
            counterAccessor?.let { it() -= this }

            needsResync.add(this to property)
            if (counterName != null)
                needsResync.add(to to counterName)

            thisSessionInfo.get().topologyGeneration++
            if (property == "children") {
                onTopologyChange(this, false, to)
            }
        }
        needsResync.add((this as Box) to "actions")
        needsResync.add((to as Box) to "actions")
    }


    companion object {
        var uiResyncProperties = setOf(
            "frame",
            "name",
            "code",
            "code_language",
            "filename",
            "preamble",
            "fragment",
            "vertex",
            "mesh",
            "task",
            "children",
            "parents",
            "nested",
            "nestedIn",
            "visible",
            "actions",
            "compute",
            "customization",
            "_tombstone",
            ".*_editorState",
            "code_decorationState",
            "metadata",
            "offline",
            "bookmarks",
            "autorun"
        ).map { it.toRegex() }

        var ioPersistentProperties = mutableSetOf(
            "frame",
            "name",
            "code",
            "code_language",
            "filename",
            "preamble",
            "fragment",
            "vertex",
            "task",
            "mesh",
            "children",
            "parents",
            "compute",
            "customization",
            "source_id",
            "_tombstone",
            "isRemoteJSRoot",
            "isPythonRoot",
            ".*_editorState",
            "metadata",
            "nested",
            "nestedIn",
            "playlist",
            "pauseList",
            "bookmarks",
            "slideShow",
            "autorun",
        )

        var ioSeparateProperties = mutableMapOf(
            "fragment" to "glsl",
            "vertex" to "glsl",
            "compute" to "glsl",
            "task" to "glsl",
            "mesh" to "glsl",
            "code" to "kt",
            "customization" to "js",
            "remote_js" to "js",
            "metadata" to "yaml"
        )
        val context = ThreadLocal<Box>()

        init {

            actionTable["-connect"] = { boxes ->
                val a = boxes[0]
                var names = ""
                boxes.drop(1).forEach {
                    a.connect(it)
                    names += it.name + ", "
                }
                notify("Connected ${a.name} -> ${names.trim(' ', ',')}")
            }

            actionTable["-copy"] = { boxes ->
                var names = ""
                boxes.forEach {
                    names += it.name + ", "
                }
                copyBoxes(boxes)
                notify("Copied ${names.trim(' ', ',')}")
            }

            actionTable["-disconnect"] = { boxes ->
                val a = boxes[0]
                var names = ""
                boxes.drop(1).forEach {
                    a.disconnect(it)
                    names += it.name + ", "
                }
                notify("Disconnected ${a.name} from ${names.trim(' ', ',')}")
            }

            actionTable["-format-kotlin"] = { boxes ->
                boxes.forEach {
                    it.code = formatKotlinCode(it.code)
                }
                notify("Formated 'code' as Kotlin on ${boxes.size}")
            }

            actionTable["-delete-box"] = { boxes ->
                var names = ""
                boxes.forEach {
                    it.setPropertyRaw("_tombstone", true)
                    names += it.name + ", "
                    thisSessionInfo.get().root.disconnect(it)
                }
                notify("Deleted ${names.trim(' ', ',')}")
            }

            actionTable["-update-check"] = { boxes ->
                var names = ""
                notify("Checking for updates from '${defaultUpdateUrl}'", "update")
                val session = thisSession.get()
                GlobalScope.launch(Dispatchers.IO) {
                    val ui = fetchUpdateInfo()
                    thisSession.set(session)
                    if (ui == null) {
                        notify("Update server returned nothing", "update")
                        return@launch
                    }
                    val local = checkUpdateVersion()
                    thisSession.set(session)
                    if (local == null) {
                        notify("Local version appears to be versionless", "update")
                        return@launch
                    }
                    if (local.hash == ui.hash) {
                        notify("Already up to date '${local.hash}'", "update")
                        return@launch
                    }
                    notify("Downloading update ${local.hash} / '${local.description}'", "update")
                    val done = downloadAndUnzip(ui.url)
                    if (!done) {
                        notify("Problem downloading update", "update")
                        return@launch
                    }

                    notify("Applied update, please restart Field", "update")
                    val success = promoteStagingToLive()
                    if (!success) {
                        notify("Error applying update", "update")
                    } else {
                        writeLocalVersion(ui)
                    }
                }
            }
        }

    }

    @JvmOverloads
    override fun setPropertyRaw(name: String, value: Any, doResync: Boolean) {
        if (doResync && uiResyncProperties.any { it.matches(name) }) {
            val a = properties[name]
            if (a != value) needsResync.add(this to name)
            properties[name] = value
        } else {
            properties[name] = value
        }
    }

    override fun getPropertyRaw(name: String, default: Any?): Any? {
        return properties.getOrElse(name) { default }
    }

    override fun toString(): String {
        if (::inside.isInitialized && this == root) return "(root box)"
        return "box[$name]"
    }

    fun resyncAll() {
        properties.filter { (name, _) -> uiResyncProperties.any { it.matches(name) } }.forEach { (name, value) ->
            if (value != null)
                needsResync.add(this to name)
        }
    }
}

var actionTable = mutableMapOf<String, (List<Box>) -> Unit>()

fun doResync() {
    needsResync.removeAll { (box, name) ->
        try {
            if (box.root == null) return@removeAll true
        } catch (e: UninitializedPropertyAccessException) {
            return@removeAll false
        }
        if (box.getPropertyRaw("_tombstone").isTrue()) return@removeAll true
        val value = box.getPropertyRaw(name, null)
        if (value == null) return@removeAll true

        var message: Message? = null
        when (name) {
            "parents" -> {

            }

            "children" -> {
                message =
                    Message("set-topology").payload(
                        Set_topology(
                            box.id,
                            kind = "box_children",
                            (value as Collection<Box>).mapNotNull {
                                if (it.properties.get("_tombstone").isTrue()) null else it.id
                            })
                    )
            }

            "nested" -> {
                message =
                    Message("set-topology").payload(
                        Set_topology(
                            box.id,
                            kind = "box_nested",
                            (value as Collection<Box>).mapNotNull {
                                if (it.properties.get("_tombstone").isTrue()) null else it.id
                            })
                    )
            }

            "nestedIn" -> {
                val parent = value as? Box
                if (parent != null && !parent.properties.get("_tombstone").isTrue()) {
                    message =
                        Message("set-topology").payload(
                            Set_topology(
                                box.id,
                                kind = "box_nestedIn",
                                listOf(parent.id)
                            )
                        )
                }
            }

            "actions" -> {
                val actionList = box.gather<MutableMap<String, Action>>("actions").entries.map { it.key to it.value }
                lprintln("debug,boxes", "actions for ${box.id}: are ${actionList.map { it.first }} ${actionList.map { it.second.desc }}}")

                message = Message("describe-action").payload(Describe_actions(box.id, actionList.map { (name, action) ->
                    actionTable[name] = { sel ->
                        action.target(sel)
                    }
                    Action_Desc(name, action.desc)
                }))
            }

            else -> {
                message = when (value) {
                    null -> Message("set-null").payload(Set_null(box.id, name))
                    is Rect -> Message("set-rect").payload(Set_rect(box.id, name, value))
                    is String -> {
                        lprintln("info,boxes", "sending set-string for $name = $value")
                        if (Box.ioSeparateProperties.containsKey(name) && box.isInsideInitialized()) {
                            box.inside.pendingSidecarSaves += box.id to name
                        }
                        Message("set-string").payload(Set_string(box.id, name, value))
                    }
                    is Number -> Message("set-number").payload(Set_number(box.id, name, value.toDouble()))
                    is Boolean -> Message("set-boolean").payload(Set_boolean(box.id, name, value))
                    else -> {
                        lprintln("warning,boxes", "trying to resync something that can't be resync'd -- unknown value type $value ${value.javaClass}")
                        null
                    }
                }
            }
        }

        lprintln("info,boxes", "resync $name -> $message (x${sessions.size} sessions)")
        message?.let { message ->
            if (box.isInsideInitialized()) {
                box.inside.sessions.forEach { _send(it, message) }
            }
        }

        true
    }
}

fun Any?.isTrue(): Boolean {
    if (this == null) return false
    return when (this) {
        is Boolean -> this
        is Int -> if (this != 0) return true else false
        is String -> this.length > 0
        else -> false
    }
}

fun box(
    u: String,
    createIfMissing: Boolean = true,
    initIfCreated: Boolean = true,
    root: SessionInformation = thisSessionInfo.get()
): Box {
    if (u == "root") return root.root
    if (createIfMissing) {
        return root.boxes.computeIfAbsent(u) {
            val b = Box(u)
            b.inside = root
            b.parents.add(root.root)
            root.root.children.add(b)

            if (initIfCreated) {
                b.ki
            }

            b
        }
    } else return root.boxes[u]!!
}

var intro = """
import afield.*
import afield.stdlib.*
import afield.Box
import afield.four.util.*
import afield.graphics.FLine
import afield.four.*
import afield.four.modules.*
import afield.four.targets.*
import afield.four.buffers.*
import org.joml.*
import kotlinx.coroutines.yield
"""

fun makeKI(box: Box): KotlinInterface {

    // force resync of actions per box
    box.actions

    logTime("ki_standardlib") {
        val ki = KotlinInterface(implicitReceivers = listOf(KotlinType(Box::class)), filePrefix = box.id)
        val si = thisSessionInfo.get()
        inMain {
//        if (box.properties.containsKey("_tombstone")) return@inMain
            if (box.properties.containsKey("_tombstone")) return@inMain

            launch(Runloop.miscPool.asCoroutineDispatcher()) {

                lblock("info,ki", "executing stdlib import") {
//                    if (box.properties.containsKey("_tombstone")) return@launch
                    if (box.properties.containsKey("_tombstone")) return@launch
                    lprintln("info,ki", "$box executing imports")
                    ki.exec(
                        intro, implicits = listOf(box)
                    )
                    lprintln("info,ki", "$box executing preamble")
                    try {
                        box.preamble.let { ki.exec(it, implicits = listOf(box)) }
                    } catch (t: Throwable) {
                        lprintln("error,ki", "preamble threw an exception, clearing it")
                        t.printStackTrace()
                        box.preamble = ""
                    }
                    lprintln("info,ki", "$box box is no longer offline")
                    box.offline = false
                    si?.topologyGeneration++
                }
            }
        }
        return ki
    }
}

suspend fun makeKIHere(box: Box): KotlinInterface {
    val ki = KotlinInterface(implicitReceivers = listOf(KotlinType(Box::class)), filePrefix = box.id)
    val si = thisSessionInfo.get()
    if (box.properties.containsKey("_tombstone")) return box.ki

    ki.exec(
        intro, implicits = listOf(box)
    )
    box.preamble.let { ki.exec(it, implicits = listOf(box)) }
    si?.topologyGeneration++
    box.offline = false
    return ki
}

inline fun Box.visitParents(v: (Box) -> Unit) {
    val fringe = mutableSetOf(this)
    val seen = mutableSetOf<Box>()
    while (fringe.isNotEmpty()) {
        val nextFringe = mutableSetOf<Box>()
        fringe.forEach {
            if (!seen.contains(it)) {
                nextFringe.addAll(it.parents)
                seen.add(it)
                v(it)
            }
        }
        fringe.clear()
        fringe.addAll(nextFringe)
    }
}

fun <T : MutableMap<String, *>> Box.gather(name: String): T {
    val all = mutableListOf<MutableMap<String, *>>()
    visitParents {
        val m = it.getPropertyRaw(name)
        if (m != null) {
            all.add(m as MutableMap<String, *>)
        }
    }
    val together = mutableMapOf<String, Any?>()
    all.reversed().forEach {
        together.putAll(it)
    }
    return together as T
}

inline fun <reified T> Box.findWithNameOrNull(start: Box, name: String): T? {
    visitParents {
        val v = it.getPropertyRaw(name)
        if (v is T) {
            return@findWithNameOrNull v
        }
    }
    return null
}
