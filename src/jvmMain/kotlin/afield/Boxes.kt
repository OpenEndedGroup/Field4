package afield

import afield.logger.lblock
import afield.logger.lprintln
import afield.stdlib.Action
import afield.stdlib.Delegation
import afield.stdlib.Marker
import afield.stdlib.actions
import io.ktor.util.collections.*
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.script.experimental.api.KotlinType

var needsResync = ConcurrentSet<Pair<Box, String>>()
var Box.offline by Delegation<Boolean>(defaultValue = { true }) // boxes are offline until they've finished their stdlib execution
var Box.preamble by Delegation<String>(defaultValue = { "" })
var Box.introduction by Delegation<String>(defaultValue = { "" })
var Box.autorun by Delegation<Boolean>(defaultValue = { false })
var Box.layers by Delegation<String>(defaultValue = { "default" })
var Box.layerFrames by Delegation<MutableMap<String, Rect>>(defaultValue = { mutableMapOf() })
var Box.templateId by Delegation<String?>(defaultValue = { null }) // tracks which template this box was created from


interface HasProperties {
    fun setPropertyRaw(name: String, value: Any, doResync: Boolean = true)
    fun getPropertyRaw(name: String, default: Any? = null): Any?
}

interface HasHierarchy : HasProperties {
    var children: MutableSet<Box>
    var parents: MutableSet<Box>
}

data class CachedLaunchReturn(
    val code: String,
    val returnValue: Any
)

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
    val cachedLaunchReturns = mutableMapOf<String, CachedLaunchReturn>()
    val currentLaunchModes = mutableMapOf<String, String>()

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
            "fragment_s",
            "vertex_s",
            "mesh_s",
            "task_s",
            "children",
            "parents",
            "nested",
            "nestedIn",
            "visible",
            "actions",
            "compute",
            "compute_s",
            "customization",
            "_tombstone",
            ".*_editorState",
            "code_decorationState",
            "metadata",
            "offline",
            "bookmarks",
            "autorun",
            "layers",
            "layerFrames"
        ).map { it.toRegex() }

        var ioPersistentProperties = mutableSetOf(
            "frame",
            "name",
            "code",
            "code_language",
            "filename",
            "preamble",
            "introduction",
            "fragment",
            "vertex",
            "task",
            "mesh",
            "fragment_s",
            "vertex_s",
            "task_s",
            "mesh_s",
            "children",
            "parents",
            "compute",
            "compute_s",
            "customization",
            "source_id",
            "templateId",
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
            "layers",
            "layerFrames"
        )

        var ioSeparateProperties = mutableMapOf(
            "fragment" to "glsl",
            "vertex" to "glsl",
            "compute" to "glsl",
            "task" to "glsl",
            "mesh" to "glsl",
            "fragment_s" to "slang",
            "vertex_s" to "slang",
            "compute_s" to "slang",
            "task_s" to "slang",
            "mesh_s" to "slang",
            "code" to "kt",
            "customization" to "js",
            "remote_js" to "js",
            "metadata" to "yaml"
        )
        val context = ThreadLocal<Box>()

        @Suppress("UNCHECKED_CAST")
        private fun nullableContext(): ThreadLocal<Box?> = context as ThreadLocal<Box?>

        fun boxContextElement(box: Box?) = nullableContext().asContextElement(box)

        suspend fun <T> withBoxContext(box: Box?, block: suspend () -> T): T {
            return withContext(boxContextElement(box)) {
                block()
            }
        }

        fun <T> withBoxContextNow(box: Box?, block: () -> T): T {
            val previous = nullableContext().get()
            nullableContext().set(box)
            return try {
                block()
            } finally {
                nullableContext().set(previous)
            }
        }

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

            actionTable["-nest-into"] = nest@{ boxes ->
                if (boxes.size < 2) {
                    notify("Select a parent followed by box(es) to nest")
                    return@nest
                }
                val parent = boxes.first()
                val children = boxes.drop(1)
                children.forEach { child ->
                    child.nestInto(parent)
                }
                notify("Nested ${children.size} into ${parent.name}")
            }

            actionTable["-un-nest"] = { boxes ->
                val targets = boxes.filter { it.nestedIn != null }
                targets.forEach {child ->
//                    child.nestedIn?.let {
//                        val parentWas = it
//                        parentWas.nested.remove(child)
//                    }
                    child.nestInto(null)
                }
                val count = targets.size
                notify("Un-nested $count box${if (count == 1) "" else "es"}")
            }

            actionTable["-format-kotlin"] = { boxes ->
                boxes.forEach {
                    it.code = formatKotlinCode(it.code)
                }
                notify("Formated 'code' as Kotlin on ${boxes.size}")
            }

            actionTable["-delete-box"] = { boxes ->
                val selected = boxes
                    .filter { !it.getPropertyRaw("_tombstone").isTrue() && it.id != "root" }
                    .toSet()
                val targets = collectAllNestedBoxes(selected)
                    .filter { !it.getPropertyRaw("_tombstone").isTrue() && it.id != "root" }
                    .toSet()

                if (targets.isEmpty()) {
                    notify("Deleted 0 boxes")
                } else {
                    val root = thisSessionInfo.get().root
                    targets.forEach { target ->
                        // Ensure live parents don't retain stale nested links to deleted boxes.
                        if (target.nestedIn != null) {
                            target.nestedIn = null
                        }
                        target.setPropertyRaw("_tombstone", true)
                        root.disconnect(target)
                    }

                    val countMessage = formatBoxCountMessage(selected.size, targets.size, "Deleted")
                    notify(countMessage)
                }
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
        val oldValue = properties[name]
        if (doResync && uiResyncProperties.any { it.matches(name) }) {
            if (oldValue != value) needsResync.add(this to name)
            properties[name] = value
        } else {
            properties[name] = value
        }

        FlightRecorder.recordTrackedPropertyChange(this, name, oldValue, value)
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

    /**
     * Animates the frame change over the specified duration (in milliseconds)
     */
    fun setFrameAnimated(newFrame: Rect, duration: Double = 500.0) {
        frame = newFrame
        if (isInsideInitialized()) {
            val animMessage = Message("set-animated-rect").payload(
                Set_animated_rect(id, "frame", newFrame, duration)
            )
            inside.sessions.forEach { _send(it, animMessage) }
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
                val children = (value as? Collection<*>)
                    ?.mapNotNull { it as? Box }
                    .orEmpty()
                message = Message("set-topology").payload(
                    Set_topology(
                        box.id,
                        kind = "box_children",
                        children.mapNotNull {
                            if (it.properties.get("_tombstone").isTrue()) null else it.id
                        }
                    )
                )
            }

            "nested" -> {
                val nested = (value as? Collection<*>)
                    ?.mapNotNull { it as? Box }
                    .orEmpty()
                message = Message("set-topology").payload(
                    Set_topology(
                        box.id,
                        kind = "box_nested",
                        nested.mapNotNull {
                            if (it.properties.get("_tombstone").isTrue()) null else it.id
                        }
                    )
                )
            }

            "nestedIn" -> {
                val parent = value as? Box
                if (parent != null && !parent.properties.get("_tombstone").isTrue()) {
                    message = Message("set-topology").payload(
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

                message = Message("describe-action").payload(
                    Describe_actions(
                        box.id,
                        actionList.map { (name, action) ->
                            actionTable[name] = { sel ->
                                action.target(sel)
                            }
                            Action_Desc(name, action.desc, action.detail)
                        })
                )
            }

            else -> {
                message = when (value) {
                    null -> Message("set-null").payload(Set_null(box.id, name))
                    is Rect -> Message("set-rect").payload(Set_rect(box.id, name, value))
                    is String -> {
                        lprintln("info,boxes", "sending set-string for $name = $value")
                        if (Box.ioSeparateProperties.containsKey(name) && box.isInsideInitialized() && !box.inside.ephemeral) {
                            box.inside.pendingSidecarSaves += box.id to name
                        }
                        Message("set-string").payload(Set_string(box.id, name, value))
                    }
                    is Number -> Message("set-number").payload(Set_number(box.id, name, value.toDouble()))
                    is Boolean -> Message("set-boolean").payload(Set_boolean(box.id, name, value))
                    is Map<*, *> -> {
                        // Handle map serialization - specifically for layerFrames: Map<String, Rect>
                        // Validate map contents before serialization
                        if (name == "layerFrames") {
                            try {
                                // Validate all keys are strings and values are Rect
                                val isValid = value.keys.all { it is String } && value.values.all { it is Rect }
                                if (!isValid) {
                                    lprintln("warning,boxes", "layerFrames property has invalid types - expected Map<String, Rect>")
                                    null
                                } else {
                                    @Suppress("UNCHECKED_CAST")
                                    val mapValue = value as Map<String, Rect>
                                    val jsonString = Json.encodeToString(MapSerializer(String.serializer(), Rect.serializer()), mapValue)
                                    Message("set-string").payload(Set_string(box.id, name, jsonString))
                                }
                            } catch (e: Exception) {
                                lprintln("warning,boxes", "failed to serialize map for property $name: ${e.message}")
                                null
                            }
                        } else {
                            lprintln("warning,boxes", "unexpected map property $name - only layerFrames is supported")
                            null
                        }
                    }
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

fun syncExecutionStatus() {
    // Periodically sync execution status for all boxes that are currently running
    // This ensures the frontend stays in sync even after reconnections or state issues
    documents.values.forEach { sessionInfo ->
        sessionInfo.boxes.values.forEach { box ->
            if (box.properties.get("_tombstone").isTrue()) return@forEach

            // Check if the box has any active jobs
            val hasActiveJobs = box.jobs.any { !it.isCancelled && it.isActive }

            if (hasActiveJobs) {
                val activeModes = box.currentLaunchModes.toMap()
                val strandsToSync = if (activeModes.isNotEmpty()) {
                    activeModes.keys
                } else {
                    box.runGeneration.keys
                }

                strandsToSync.forEach { strand ->
                    val generation = box.runGeneration[strand] ?: return@forEach
                    if (generation < 0) return@forEach

                    val message = Message("is-running").payload(
                        IsRunning(
                            box.id,
                            true,
                            generation,
                            strand,
                            activeModes[strand] ?: "launch"
                        )
                    )
                    sessionInfo.sessions.forEach { _send(it, message) }
                }
            }
        }
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
import afield.nano.*
import afield.stdlib.*
import afield.four.synth.*
import afield.stdlib.Vec2
import afield.stdlib.Vec3
import afield.stdlib.Vec4
import afield.stdlib.Quat
import afield.Box
import afield.four.util.*
import afield.graphics.*
import afield.four.*
import afield.four.modules.*
import afield.four.targets.*
import afield.four.buffers.*
import afield.four.texture.*
import afield.graphics.*
import org.joml.*
import kotlinx.coroutines.yield
import kotlinx.coroutines.runBlocking
"""

fun makeKI(box: Box): KotlinInterface {

    // force resync of actions per box
    box.actions

    logTime("ki_standardlib") {
        val ki = KotlinInterface(implicitReceivers = listOf(KotlinType(Box::class)), filePrefix = box.id)
        ki.associatedBox = box  // Set the box reference for @exported support
        val si = thisSessionInfo.get()
        inMain {
//        if (box.properties["_tombstone"].isTrue()) return@inMain
            if (box.properties["_tombstone"].isTrue()) return@inMain

            launch(Runloop.miscPool.asCoroutineDispatcher() + Box.boxContextElement(box)) {

                lblock("info,ki", "executing stdlib import") {
//                    if (box.properties["_tombstone"].isTrue()) return@launch
                    if (box.properties["_tombstone"].isTrue()) return@launch
                    lprintln("info,ki", "$box executing imports")
                    ki.exec(
                        intro, implicits = listOf(box)
                    )
                    lprintln("info,ki", "$box clearing preamble")
                    box.preamble = ""
//                    try {
//                        box.preamble.let { ki.exec(it, implicits = listOf(box)) }
//                    } catch (t: Throwable) {
//                        lprintln("error,ki", "preamble threw an exception, clearing it")
//                        t.printStackTrace()
//                        box.preamble = ""
//                    }
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
    ki.associatedBox = box  // Set the box reference for @exported support
    val si = thisSessionInfo.get()
    if (box.properties["_tombstone"].isTrue()) return box.ki

    Box.withBoxContext(box) {
        ki.exec(
            intro, implicits = listOf(box)
        )
        box.preamble.let { ki.exec(it, implicits = listOf(box)) }
    }
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
