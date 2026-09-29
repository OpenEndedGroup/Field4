package afield

import afield.layout.BoxLayout
import afield.logger.lblock
import afield.logger.lprintln
import afield.stdlib.Action
import afield.stdlib.Delegation
import afield.stdlib.Marker
import afield.stdlib.actions
import afield.four.synth.musicClock
import afield.four.synth.patClock
import io.ktor.util.collections.*
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.*
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.script.experimental.api.KotlinType

var needsResync = ConcurrentSet<Pair<Box, String>>()

/**
 * The box graph's edge sets — the properties whose value is a `MutableSet<Box>`.
 *
 * They are the second half of the race diagnosed in `PropertyBag.kt`: fixing the property *bag* stopped
 * `resyncAll` throwing, but the sets stored *in* the bag were still plain `LinkedHashSet`s, walked on the
 * main runloop and structurally mutated from replay and execution threads:
 *
 *  - `doResync`'s `"children"` / `"nested"` branches do `(value as? Collection<*>)?.mapNotNull { … }`
 *    over the live set, on the runloop, every 500 ms;
 *  - [visitParents] does `nextFringe.addAll(it.parents)` over the live set, and is how [gather] — and so
 *    `doResync`'s `"actions"` branch, and every preamble/shader/actor lookup — reaches a box's ancestors;
 *  - meanwhile `Box.connect` / `Box.disconnect` do `accessor() += to` from macro replay on
 *    `Dispatchers.IO`, and `nestInto` mutates `nested` directly.
 *
 * See [asTopologySet] for why the fix is a `CopyOnWriteArraySet` here where it was a bespoke class for
 * the bag, and `setPropertyRaw` for where it is applied.
 */
private val topologySetProperties = setOf("children", "parents", "nested")

/**
 * A topology edge set as a `CopyOnWriteArraySet`, which is exactly the right JDK class here and needs no
 * bespoke replacement:
 *
 *  - **insertion-ordered**, which matters — `SavingContext` persists `children` in iteration order and the
 *    frontend receives `set-topology` in it;
 *  - **iteration is over an immutable array snapshot**, so no walker can ever see a
 *    `ConcurrentModificationException`, whatever another thread is doing;
 *  - `removeIf` / `removeAll` / `retainAll` are overridden by the class rather than falling back to
 *    `iterator().remove()`, so `agent_backdoor.kt`'s tombstone sweep (`children.removeIf { … }`) keeps
 *    working;
 *  - `add` and `contains` become O(n), which is nothing on sets this size (a handful of edges, worst case
 *    a few hundred children on a document root) against the message serialisation that follows every
 *    topology change. It is not on any per-frame path.
 *
 * Serialisation is unaffected: `SavingContext.normalizeValue` and `withoutMetarootTopology` both match on
 * `is Set<*>` and build their own `LinkedHashSet`, so a `CopyOnWriteArraySet` goes through them unchanged
 * and in the same order.
 *
 * Values that are not sets are returned untouched — `nestedIn` holds a single `Box`, and a property can
 * hold anything.
 */
private fun asTopologySet(value: Any): Any = when {
    value !is Set<*> -> value
    value is java.util.concurrent.CopyOnWriteArraySet<*> -> value
    else -> java.util.concurrent.CopyOnWriteArraySet(value)
}

var Box.offline by Delegation<Boolean>(defaultValue = { true }) // boxes are offline until they've finished their stdlib execution
var Box.preamble by Delegation<String>(defaultValue = { "" })
var Box.introduction by Delegation<String>(defaultValue = { "" })
var Box.attic by Delegation<String>(defaultValue = { "" })
var Box.notes by Delegation<String>(defaultValue = { "" })
var Box.autorun by Delegation<Boolean>(defaultValue = { false })
/** Comma-separated membership used by the frontend and server visibility intersection. */
var Box.layers by Delegation<String>(defaultValue = { "default" })

/** Persisted frame overrides keyed by layer; updated when the session leaves an active layer. */
var Box.layerFrames by Delegation<MutableMap<String, Rect>>(defaultValue = { mutableMapOf() })
var Box.templateId by Delegation<String?>(defaultValue = { null }) // tracks which template this box was created from

const val METAROOT_ID = "metaroot"
private const val JOBS_RUNNING_STRAND = "__jobs__"
private const val JOBS_RUNNING_MODE = "job"

val metaroot: Box by lazy {
    val root = Box(METAROOT_ID)
    root.name = METAROOT_ID
    root.visible = false
    root.patClock = musicClock()
    val info = SessionInformation(
        filename = "<metaroot>",
        documentName = METAROOT_ID,
        root = root,
        ephemeral = true
    )
    root.inside = info
    root
}

fun attachRootToMetaroot(root: Box) {
    if (root == metaroot) return
    val meta = metaroot
    if (!root.parents.contains(meta)) {
        root.parents.add(meta)
        needsResync.add(root to "parents")
    }
    if (!meta.children.contains(root)) {
        meta.children.add(root)
        needsResync.add(meta to "children")
    }
}

fun detachRootFromMetaroot(root: Box) {
    if (root == metaroot) return
    val meta = metaroot
    if (root.parents.remove(meta)) {
        needsResync.add(root to "parents")
    }
    if (meta.children.remove(root)) {
        needsResync.add(meta to "children")
    }
}


interface HasProperties {
    fun setPropertyRaw(name: String, value: Any, doResync: Boolean = true)
    fun getPropertyRaw(name: String, default: Any? = null): Any?

    /**
     * Erases [name] from the bag, leaving it *unset* rather than set to null.
     *
     * [setPropertyRaw] takes a non-null `Any`, so this is the only way back to the state a property
     * was in before anything wrote it — which is what null means for a nullable property. See
     * [afield.stdlib.Delegation.setValue], which routes null writes here.
     */
    fun removePropertyRaw(name: String, doResync: Boolean = true)
}

interface HasHierarchy : HasProperties {
    var children: MutableSet<Box>
    var parents: MutableSet<Box>
}

data class CachedLaunchReturn(
    val code: String,
    val returnValue: Any
)

data class RunningState(
    val watermark: Int,
    val mode: String
)

/**
 * Hierarchical Field object identified by a stable [id] and backed by [output].
 *
 * A box owns properties, topology links, execution state, and display/error output; [output]
 * supplies the [HasOutput] implementation that rendering and logging helpers delegate to.
 */
open class Box(val id: String, val output: DefaultOutput = DefaultOutput()) : HasOutput by output, HasProperties,
    HasHierarchy {


    lateinit var inside: SessionInformation

    internal fun profilingDocument(): String = if (::inside.isInitialized) inside.filename else ""
    fun isInsideInitialized() = ::inside.isInitialized

    // A [PropertyBag], not a `mutableMapOf()`: this map is read, written and *iterated* by the main
    // runloop, macro/transcript replay on Dispatchers.IO, the AI auto-namer's pool and box execution at
    // the same time, and an unsynchronised LinkedHashMap made every walk of it (resyncAll, saving,
    // history diffing, ...) a ConcurrentModificationException waiting to happen. See PropertyBag.kt.
    val properties: MutableMap<String, Any?> = PropertyBag()
    // ConcurrentHashMap: `Box.__m` (stdlib.kt) does computeIfAbsent from box code, i.e. from execution
    // threads. Never iterated, so this was not a CME risk -- but concurrent computeIfAbsent on a
    // HashMap can lose an entry, and on CHM it is atomic, which is what the caller wanted anyway.
    val markers: MutableMap<String, Marker> = ConcurrentHashMap()

    // Kept as a named delegate (not an anonymous `by lazyVar { }`) so disposeKi()
    // can check whether the engine was ever created and release it without forcing
    // a pointless lazy initialization.
    private val kiDelegate = lazyVar<KotlinScriptEngine> {
        makeKI(this, reason = "box.ki lazy initialization")
    }
    var ki: KotlinScriptEngine by kiDelegate

    /**
     * This box's engine only if one was ever built. Reading [ki] would build one, which for a box
     * that has never run is both wasteful and misleading — callers that are *inspecting* engines
     * (see [scanForStaleClasses]) want to skip such boxes, not create work for them.
     */
    val liveKi: KotlinScriptEngine? get() = if (kiDelegate.isInitialized) ki else null

    var ke: BoxExecution = KotlinExecution()

    /**
     * Dispose this box's Kotlin engine (the K2 REPL compilation state: FIR sessions,
     * project environment, ProtoBuf metadata) if it was ever created. Each box owns
     * its own engine, and until now nothing ever disposed it, so every deleted or
     * detached box leaked its whole compilation state (~tens of MB each) -- fatal for
     * long-lived sessions that create/run/delete many boxes (e.g. the docs capture
     * loop). Safe to call repeatedly; a later `ki` access lazily recreates a fresh
     * engine.
     *
     * Also forgets the `_preamble` cache, because the cache records what was applied to *that*
     * engine and the engine is now gone. [makeKI] does clear it when rebuilding, but on the runloop
     * inside its async bootstrap, while `executeInBox` runs `checkPreambleAndExecute` on its own
     * schedule. When the preamble check wins that race it compares the gathered preamble against a
     * cache that still lists every line, computes an empty `extra`, and executes nothing — so a
     * brand-new engine never receives the declarations. Measured on 2026-08-20 via
     * `afield.SharedTypes`: a box whose engine had just been recycled failed with
     * `Unresolved reference '_particle'`, with no preamble execution logged between the rebuild and
     * the compile. Clearing here, synchronously with the disposal, makes the ordering irrelevant —
     * and covers every recycling caller, [reloadPersonal] included, not just the one that found it.
     *
     * `_introduction` and `_attic` are the same kind of record — "this text has run in this engine" —
     * and are forgotten with it: `checkIntroductionAndExecute` runs the introduction only when it differs
     * from `_introduction`, so a stale copy made the next engine skip the introduction entirely.
     */
    fun disposeKi() {
        if (!kiDelegate.isInitialized) return
        try {
            ki.dispose()
        } catch (t: Throwable) {
            lprintln("error,ki", "disposeKi failed for box $id: ${t.message}")
        }
        // Pass the initializer explicitly: LazyVar nulls its initializer after the
        // first init, so reset() with the default arg would leave `ki` unrecoverable
        // ("Initializer cleared"). This keeps a later `ki` access able to rebuild.
        kiDelegate.reset { makeKI(this, reason = "box.ki re-initialization after disposeKi") }
        // The engine that ran this preamble no longer exists; see the KDoc above for the race this
        // closes. Deliberately after the reset, so "engine gone" and "preamble not applied" become
        // true together and no caller can observe one without the other.
        forgetEngineExecutionCaches()
    }

    /**
     * Install [engine] as this box's engine, disposing the one it replaces and forgetting what that one
     * had run — the single way to swap engines, for the sites that used to assign `ki = makeKI…` and
     * leak the old K2 compilation state (the `reset` action, execute-all, [afield.workspace.copyBox]).
     *
     * Only an engine that was actually built is disposed, so a box that never ran costs nothing, and
     * installing the engine already current is a no-op: `makeKIHere` hands back the existing engine for
     * a tombstoned box, which disposing would break. The new engine is installed before the old one is
     * disposed, so no reader of [ki] can be handed a disposed engine.
     */
    fun replaceKi(engine: KotlinScriptEngine) {
        val previous = liveKi
        if (previous === engine) return
        ki = engine
        if (previous != null) {
            try {
                previous.dispose()
            } catch (t: Throwable) {
                lprintln("error,ki", "replaceKi failed to dispose the previous engine for box $id: ${t.message}")
            }
        }
        forgetEngineExecutionCaches()
    }

    /** The "already ran in this engine" records: `_preamble`, `_introduction`, `_attic`. */
    private fun forgetEngineExecutionCaches() {
        properties.remove("_preamble")
        properties.remove("_introduction")
        properties.remove("_attic")
    }

    var frame by Delegation<Rect>(defaultValue = { Rect(0.0, 0.0, 0.0, 0.0) }, hook = ::onFrameChange)
    var viewport by Delegation<Rect>(defaultValue = { Rect(0.0, 0.0, 0.0, 0.0) }, hook = ::onViewportChange)
    var name by Delegation<String>(defaultValue = { "" }, hook = ::onNameChange)
    var code by Delegation<String>()
    var customization by Delegation<String>()

    /** This receiver as an explicit [Box] value, useful where the implicit box context is hidden. */
    val thisBox: Box
        get() = this

    // These three are written by every thread that can run a box -- Phased, ReturnValueRegistry, the
    // launch coroutines in execution.kt, KotlinJs, FieldMLInterface, the agent backdoor -- and the two
    // that get *iterated* are iterated on the main runloop: syncExecutionStatus walks
    // currentRunningStates every 500ms (Server.kt's resync loop) and the language interfaces walk
    // runGeneration.keys. Concurrent, unsynchronised LinkedHashMaps in that position are the same
    // defect as the property bag (see PropertyBag.kt); found by that audit, not by a sighting.
    // Neither holds nulls and neither has an order anyone reads, so a ConcurrentHashMap is exact.
    var runGeneration: MutableMap<String, Int> = ConcurrentHashMap(mapOf("default" to -1))
    var fragmentGeneration: Int = -1
    var jobs: MutableList<Job> = BoxJobList(this)
        set(value) {
            field = BoxJobList(this).also { it.addAll(value) }
        }
    val cachedLaunchReturns: MutableMap<String, CachedLaunchReturn> = ConcurrentHashMap()
    val currentRunningStates: MutableMap<String, RunningState> = ConcurrentHashMap()
    internal val launchEpoch = AtomicInteger(0)

    /**
     * Teardown that [stopTrackedJobs] runs for resources the end phase cannot be trusted to release.
     *
     * `Frames.atEnd` — what `.end { }` appends to — does now run on stop (see
     * [registerEndPhaseJob]), but it runs *asynchronously*, over Runloop ticks after stop has
     * returned, and only for a `Frames` that was suspended in `frame()` when stop arrived. A body
     * that already returned never reaches the end phase at all. So the end phase is still the wrong
     * place for anything holding an OS resource: that registers here instead, through
     * [registerStopHook], and stop drains these synchronously before it returns.
     *
     * This generalises a workaround the tree already had: `Server.stop` disposes an `afield.four`
     * renderer explicitly, outside the end phase, for exactly this reason.
     *
     * These are *box*-lifetime: they survive re-runs and go only when the box stops, which is what a
     * DMX socket wants (a re-run must not open a gap on the wire; see `DmxSockets`). Most things a
     * run registers want the *run*'s lifetime instead — released by the next run of the strand as
     * well as by stop — and belong to its [RunScope]; [Box.streamProcess] moved there. Stop drains
     * both, hooks first. See `dev_docs/frames_stop_end_phase.md` for the measurement.
     */
    internal val stopHooks: MutableCollection<Runnable> = CopyOnWriteArrayList()

    /**
     * This box's open [RunScope]s — the owners of what each run of it registered. [stopTrackedJobs]
     * closes them all; a later run of a strand closes that strand's earlier ones.
     */
    val runScopes: BoxRunScopes = BoxRunScopes(this)

    override var children by Delegation<MutableSet<Box>>()
    override var parents by Delegation<MutableSet<Box>>()
    var visible by Delegation<Boolean>()

    init {
        // CopyOnWriteArraySet, not mutableSetOf(): the box graph's edge sets are walked on the main
        // runloop while replay threads connect and disconnect boxes. See [topologySetProperties].
        children = CopyOnWriteArraySet()
        parents = CopyOnWriteArraySet()
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
                @Suppress("UNCHECKED_CAST")
                Triple({ to.getPropertyRaw(property) as MutableSet<Box> }, null, null)
            }

            if (!accessor().contains(to)) {
                accessor() += to
                counterAccessor?.let { it() += this }

                needsResync.add(this to property)
                if (counterName != null)
                    needsResync.add(to to counterName)

                (if (::inside.isInitialized) inside else thisSessionInfo.get()).topologyGeneration++

                if (property == "children") {
                    onTopologyChange(this, true, to)
                }
            }
        }
        needsResync.add(this to "actions")
        needsResync.add(to to "actions")
    }

    fun disconnect(to: Box, property: String = "children") {

        // we drive "children" through the actual concrete var Box.children, but other connections (without counter-properties) are now possible here
        val (accessor, counterName, counterAccessor) = if (property == "children") {
            Triple(::children, "parents", to::parents)
        } else {
            @Suppress("UNCHECKED_CAST")
            Triple({ to.getPropertyRaw(property) as MutableSet<Box> }, null, null)
        }

        if (accessor().contains(to)) {
            accessor() -= to
            counterAccessor?.let { it() -= this }

            needsResync.add(this to property)
            if (counterName != null)
                needsResync.add(to to counterName)

            (if (::inside.isInitialized) inside else thisSessionInfo.get()).topologyGeneration++
            if (property == "children") {
                onTopologyChange(this, false, to)
            }
        }
        needsResync.add(this to "actions")
        needsResync.add(to to "actions")
    }


    companion object {
        var uiResyncProperties = setOf(
            "frame",
            "name",
            "code",
            "code_language",
            "contents",
            "contents_language",
            "default_property",
            "file_proxy_mode",
            "file_proxy_path",
            "file_proxy_original_path",
            "file_proxy_mime",
            "file_proxy_error",
            "filename",
            "preamble",
            "attic",
            "notes",
            "fragment",
            "stackSL",
            "rectSL",
            // Metal Shading Language for a visionOS head — edits must reach the editor.
            "msl",
            "vertex",
            "vertex_fragment",
            "mesh",
            "task",
            "raygen",
            "miss",
            "closesthit",
            "anyhit",
            "intersection",
            "callable",
            "fragment_s",
            "vertex_s",
            "mesh_s",
            "task_s",
            "raygen_s",
            "miss_s",
            "closesthit_s",
            "anyhit_s",
            "intersection_s",
            "callable_s",
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
            // Set by reloadPersonal when a box's Kotlin environment is dropped out from under it.
            // Has to reach the client or the mark is invisible and the box looks fine while holding
            // classes from a personal/ build that no longer exists.
            NEEDS_RERUN_PROPERTY,
            ".*_editorState",
            "code_decorationState",
            "metadata",
            "offline",
            // Bookmark feature: the root box's "bookmarks" property holds a JSON-encoded
            // BookmarkData (9 numbered slots; see src/jsMain/kotlin/afield/bookmarks.kt).
            // Registering it here rides the generic property machinery for UI/session
            // resync (doResync); the matching ioPersistentProperties entry below gives it
            // disk persistence. No bookmark-specific sync/storage code is needed.
            "bookmarks",
            // Drawing layer: the root box's "drawing" property is the document's Excalidraw scene
            // (src/jsMain/kotlin/afield/drawingLayer.kt). Same generic path as "bookmarks".
            "drawing",
            "autorun",
            "layers",
            "layerFrames",
            "ai4_auto_name"
        ).map { it.toRegex() }

        var ioPersistentProperties = mutableSetOf(
            "frame",
            "name",
            "code",
            "code_language",
            "contents_language",
            "default_property",
            "file_proxy_mode",
            "file_proxy_path",
            "file_proxy_original_path",
            "file_proxy_mime",
            "filename",
            "preamble",
            "introduction",
            "attic",
            "notes",
            "geometryReviews",
            "fragment",
            "stackSL",
            "rectSL",
            // Metal Shading Language for a visionOS head. Being in ioSeparateProperties only
            // chooses the sidecar file; a property must be listed here to be written at all.
            "msl",
            "vertex",
            "vertex_fragment",
            "task",
            "mesh",
            "raygen",
            "miss",
            "closesthit",
            "anyhit",
            "intersection",
            "callable",
            "fragment_s",
            "vertex_s",
            "task_s",
            "mesh_s",
            "raygen_s",
            "miss_s",
            "closesthit_s",
            "anyhit_s",
            "intersection_s",
            "callable_s",
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
            // Bookmark feature: persists the root box's "bookmarks" property to metadata.edn
            // (see the uiResyncProperties entry above and src/jsMain/kotlin/afield/bookmarks.kt).
            "bookmarks",
            "drawing",
            "slideShow",
            "autorun",
            "layers",
            "layerFrames",
            "_controlGroupValues",
            "ai4_auto_name",
            "ai4_auto_name_signature",
            "ai4_chat_history",
            "ai4_chat_running",
            "fileManagerAccessHistory"
        )

        var ioSeparateProperties = mutableMapOf(
            "fragment" to "glsl",
            "stackSL" to "stacksl",
            "rectSL" to "rectsl",
            // Metal Shading Language for a visionOS head: both entry points in one translation unit,
            // saved beside the box as `<name>.metal`.
            "msl" to "metal",
            "vertex" to "glsl",
            "vertex_fragment" to "slang",
            "compute" to "glsl",
            "task" to "glsl",
            "mesh" to "glsl",
            "raygen" to "glsl",
            "miss" to "glsl",
            "closesthit" to "glsl",
            "anyhit" to "glsl",
            "intersection" to "glsl",
            "callable" to "glsl",
            "fragment_s" to "slang",
            "vertex_s" to "slang",
            "compute_s" to "slang",
            "task_s" to "slang",
            "mesh_s" to "slang",
            "raygen_s" to "slang",
            "miss_s" to "slang",
            "closesthit_s" to "slang",
            "anyhit_s" to "slang",
            "intersection_s" to "slang",
            "callable_s" to "slang",
            "code" to "kt",
            "customization" to "js",
            // No "remote_js" here. `remote_js` is the *name* the RemoteJS/WebXR language-root box
            // carries — box c04566d0 in templates/remote/metadata.json is `"name" "remote_js"` next
            // to `mainLanguage = remotejs` — not a property any box holds. Registering the name as
            // a property key was a confusion present since 52a1d563e (2025-11-03), the first commit
            // carrying this file, and nothing ever wrote it: that root keeps its text in `code`,
            // and its children keep their Javascript in `code` and `customization` above. Measured
            // across ~/Documents/AFieldWorkspace on 2026-09-04, over the 401 directories holding a
            // metadata.edn: 354 `*_customization_.js` sidecars and zero `*_remote_js_.js`. It was
            // absent from ioPersistentProperties and uiResyncProperties too, so it could not have
            // round-tripped even if something had set it. See SidecarPropertyRegistrationSmokeTest,
            // which now requires every key here to have a matching ioPersistentProperties pattern.
            "notes" to "md",
            "ai4_chat_history" to "md",
            "metadata" to "yaml"
        )

        /**
         * Properties that never reach disk, whatever [ioPersistentProperties] says.
         *
         * This is a deny-list rather than a deletion from [ioPersistentProperties] for one
         * concrete reason: `SavingContext.mergePersistentProperties` does
         * `Box.ioPersistentProperties.addAll(...)` with the list stored in every `metadata.edn`
         * it loads. Every document written before this change carries `".*_editorState"` in
         * that list, so removing the entry from the initialiser above would last exactly until
         * the first document load put it straight back. A deny-list applied at the write filter
         * cannot be undone that way.
         *
         * `<property>_editorState` is Monaco view state — cursor line and column, scroll offset,
         * folding — produced by `monitorSelection` in `BootstrapMonacoRuntime.kt` the moment a box
         * is selected. Three other persistence surfaces already treat it as not-document-content:
         * `OperationTranscript.recordEdit` drops it, `HistoryManager`'s
         * `hiddenScrubPropertyPatterns` hides it from the history diff, and `set-string` refuses
         * to rebroadcast it (`doResync = !isEditorStateProperty`). Writing it was the odd one out,
         * and it was expensive: selecting one box in a committed demo document changed
         * `metadata.edn`, which made the save "altered", which detonated the whole derived-artifact
         * fan-out in `saving.kt` — backups, history metrics, operation transcript, screenshots —
         * inside a git working tree. Measured on 2026-09-05 against
         * `dev_docs/agents/demos/midi_clip`: open the document, click one box, touch nothing else,
         * and four paths appear in `git status`, with `code_editorState` the *only* content
         * difference.
         *
         * What is lost: a cursor position no longer survives closing and reopening a document.
         * Within a session the client restores it from its own JS copy of the box, so selecting
         * away from a box and back still works. Values already on disk still load; they simply
         * stop being written back.
         *
         * `code_decorationState` is the existing precedent — in `uiResyncProperties`, deliberately
         * absent from `ioPersistentProperties`.
         */
        val ioNeverPersistedProperties: List<Regex> = listOf(
            Regex(""".*_editorState""")
        )

        /** True when [name] must not be serialised to disk. See [ioNeverPersistedProperties]. */
        fun isNeverPersisted(name: String): Boolean =
            ioNeverPersistedProperties.any { it.matches(name) }

        val context = ThreadLocal<Box>()

        @Suppress("UNCHECKED_CAST")
        private fun nullableContext(): ThreadLocal<Box?> = context as ThreadLocal<Box?>

        fun boxContextElement(box: Box?): kotlin.coroutines.CoroutineContext =
            nullableContext().asContextElement(box) + (box?.let { ProfileOrigin(it) } ?: kotlin.coroutines.EmptyCoroutineContext)

        suspend fun <T> withBoxContext(box: Box?, block: suspend () -> T): T {
            val origin = kotlin.coroutines.coroutineContext[ProfileOrigin]?.takeIf { it.box === box }
            return withContext(nullableContext().asContextElement(box) +
                (origin ?: box?.let { ProfileOrigin(it) } ?: kotlin.coroutines.EmptyCoroutineContext)) {
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
                // Count what actually nested, not what was asked for: `nestInto` refuses a cycle, and
                // selecting a container after one of the boxes already inside it is the easy way to
                // ask for one.
                val nested = children.count { child -> child.nestInto(parent) }
                val refused = children.size - nested
                notify(
                    if (refused == 0) "Nested $nested into ${parent.name}"
                    else "Nested $nested into ${parent.name}, refused $refused already containing it"
                )
            }

            actionTable["-un-nest"] = { boxes ->
                val targets = boxes.filter { it.nestedIn != null }
                targets.forEach { child ->
//                    child.nestedIn?.let {
//                        val parentWas = it
//                        parentWas.nested.remove(child)
//                    }
                    child.nestInto(null)
                }
                val count = targets.size
                notify("Un-nested $count box${if (count == 1) "" else "es"}")
            }

            actionTable["-format-property"] = { boxes ->
                val property = currentActionProperty.get().ifBlank { "code" }
                var formatted = 0
                var unsupported = 0
                boxes.forEach { box ->
                    val current = box.getPropertyRaw(property) as? String
                    if (current == null) {
                        unsupported++
                    } else {
                        val next = formatCodeProperty(property, current)
                        if (next == null) {
                            unsupported++
                        } else {
                            box.setPropertyRaw(property, next)
                            formatted++
                        }
                    }
                }
                if (formatted > 0) {
                    notify("Formatted '$property' on $formatted box${if (formatted == 1) "" else "es"}")
                } else {
                    notify("No formatter for '$property'")
                }
                if (unsupported > 0 && formatted > 0) {
                    lprintln("info,boxes", "format skipped $unsupported box/property values for '$property'")
                }
            }

            actionTable["-format-kotlin"] = { boxes ->
                boxes.forEach {
                    it.code = formatKotlinCode(it.code)
                }
                notify("Formatted 'code' as Kotlin on ${boxes.size}")
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
                    // Stop what the deleted boxes were running, dispose any renderer they own, and
                    // release their K2 compilation state; a deleted box is never re-run.
                    retireBoxes(targets, "delete-box")

                    val countMessage = formatBoxCountMessage(selected.size, targets.size, "Deleted")
                    notify(countMessage)
                }
            }

            actionTable["-layout-boxes"] = { boxes ->
                BoxLayout.organize(boxes)
            }

            actionTable["-layout-document"] = { _ ->
                BoxLayout.organizeDocument()
            }

            actionTable["-update-check"] = { _ ->
                notify("Asking origin what it has…", "update")
                val session = thisSession.get()
                @OptIn(DelicateCoroutinesApi::class)
                GlobalScope.launch(Dispatchers.IO) {
                    val status = checkUpstream()
                    // The coroutine can resume on any IO thread, and notify() reads the session off
                    // a thread local.
                    thisSession.set(session)
                    updateCheckMessages(status).forEach { notify(it, "update") }
                }
            }
        }

    }

    override fun setPropertyRaw(name: String, value: Any, doResync: Boolean) {
        // The one choke point every whole-set assignment to a topology property passes through --
        // Delegation.setValue for `box.children = ...`, and the document loader, which calls
        // setPropertyRaw directly with a plain LinkedHashSet (saving.kt). Normalising here is what
        // makes the guarantee hold for *loaded* documents and not just freshly constructed boxes.
        val incoming = if (name in topologySetProperties) asTopologySet(value) else value
        val oldValue = properties[name]
        if (doResync && uiResyncProperties.any { it.matches(name) }) {
            if (oldValue != incoming) needsResync.add(this to name)
            properties[name] = incoming
        } else {
            properties[name] = incoming
        }

        FlightRecorder.recordTrackedPropertyChange(this, name, oldValue, incoming)
    }

    override fun getPropertyRaw(name: String, default: Any?): Any? {
        return properties.getOrElse(name) { default }
    }

    override fun removePropertyRaw(name: String, doResync: Boolean) {
        if (!properties.containsKey(name)) return
        val oldValue = properties.remove(name)
        // Same resync bookkeeping as setPropertyRaw: the UI has to hear that the property went away,
        // not just that it changed value, or the inspector keeps showing the stale one.
        if (doResync && uiResyncProperties.any { it.matches(name) }) {
            if (oldValue != null) needsResync.add(this to name)
        }
        FlightRecorder.recordTrackedPropertyChange(this, name, oldValue, null)
    }

    override fun toString(): String {
        if (::inside.isInitialized && this == root) return "(root box)"
        return "box[$name]"
    }

    /**
     * Queues every non-null UI-resyncable property on this box for the next resync pass.
     */
    fun resyncAll() {
        properties.filter { (name, _) -> uiResyncProperties.any { it.matches(name) } }.forEach { (name, value) ->
            if (value != null)
                needsResync.add(this to name)
        }
    }

    fun resyncOnly(names: Collection<String>) {
        names.forEach { name ->
            val value = properties[name]
            if (value != null && uiResyncProperties.any { it.matches(name) }) {
                needsResync.add(this to name)
            }
        }
    }

    fun resyncAllExcept(names: Set<String>) {
        properties.filter { (name, _) -> name !in names && uiResyncProperties.any { it.matches(name) } }
            .forEach { (name, value) ->
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
val currentActionProperty: ThreadLocal<String> = ThreadLocal.withInitial { "code" }
private const val RESYNC_BATCH_MESSAGE_LIMIT = 40
private const val RESYNC_BATCH_BYTE_LIMIT = 64 * 1024
private val LOADING_PRIORITY_RESYNC_PROPERTIES = setOf(
    "frame",
    "name",
    "_tombstone",
    "visible",
    "layers",
    "layerFrames",
    "children",
    "parents",
    "nested",
    "nestedIn",
    "offline",
    "autorun",
    "ai4_auto_name"
)

private fun estimatedResyncMessageBytes(message: Message): Int =
    96 + message.name.length + message.uid.length + (message.payload?.toString()?.length ?: 0)

private fun sendResyncMessages(session: WebSocketSession, messages: List<Message>) {
    val chunk = mutableListOf<Message>()
    var chunkBytes = 0

    fun flush() {
        when (chunk.size) {
            0 -> {}
            1 -> _send(session, chunk.single())
            else -> _send(session, Message("message-batch").payload(MessageBatch(chunk.toList())))
        }
        chunk.clear()
        chunkBytes = 0
    }

    messages.forEach { message ->
        val messageBytes = estimatedResyncMessageBytes(message)
        if (chunk.isNotEmpty() && (chunk.size >= RESYNC_BATCH_MESSAGE_LIMIT || chunkBytes + messageBytes > RESYNC_BATCH_BYTE_LIMIT)) {
            flush()
        }
        chunk.add(message)
        chunkBytes += messageBytes
    }
    flush()
}

fun doResync() {
    val batches = mutableMapOf<WebSocketSession, MutableList<Message>>()
    fun enqueue(box: Box, message: Message) {
        if (!box.isInsideInitialized()) return
        box.inside.sessions.forEach { session ->
            batches.computeIfAbsent(session) { mutableListOf() }.add(message)
        }
    }

    needsResync.removeAll { (box, name) ->
        if (!box.isInsideInitialized()) {
            return@removeAll false
        }
        if (box.inside.loading && name !in LOADING_PRIORITY_RESYNC_PROPERTIES) {
            return@removeAll true
        }
        // Skip resyncing a tombstoned box's *other* properties, but ALWAYS let the
        // "_tombstone" message itself through -- it is the signal clients use to remove
        // the box. Swallowing it here left every client that didn't initiate the delete
        // (notably the backdoor/docs-runner's attached page) with the box, its output
        // areas, iframes and observers alive forever -> unbounded browser CPU/memory.
        if (name != "_tombstone" && box.getPropertyRaw("_tombstone").isTrue()) return@removeAll true
        val value = box.getPropertyRaw(name, null)
        if (value == null) {
            val message = Message("set-null").payload(Set_null(box.id, name))
            lprintln("info,boxes", "resync $name -> $message (x${sessions.size} sessions)")
            enqueue(box, message)
            return@removeAll true
        }

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
                lprintln(
                    "debug,boxes",
                    "actions for ${box.id}: are ${actionList.map { it.first }} ${actionList.map { it.second.desc }}}"
                )

                message = Message("describe-action").payload(
                    Describe_actions(
                        box.id,
                        actionList.map { (name, action) ->
                            actionTable[name] = { sel ->
                                action.target(sel)
                            }
                            Action_Desc(name, action.desc, action.detail, action.docHtml, action.docJs)
                        })
                )
            }

            else -> {
                message = when (value) {
                    is Rect -> Message("set-rect").payload(Set_rect(box.id, name, value))
                    is String -> {
                        lprintln("info,boxes", "sending set-string for $name = $value")
                        // The autosave loop only saves when something is pending here. Root
                        // properties never become sidecars, so the drawing layer's scene marks
                        // itself, or a document whose only change is a drawing would never save.
                        val triggersSave = Box.ioSeparateProperties.containsKey(name) ||
                            (box.id == "root" && name == "drawing")
                        if (triggersSave && box.isInsideInitialized() && box.inside.writesToDisk) {
                            box.inside.markSidecarPending(box.id, name)
                        }
                        Message("set-string").payload(box.inside.nextSetStringSync(box.id, name, value))
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
                                    lprintln(
                                        "warning,boxes",
                                        "layerFrames property has invalid types - expected Map<String, Rect>"
                                    )
                                    null
                                } else {
                                    @Suppress("UNCHECKED_CAST")
                                    val mapValue = value as Map<String, Rect>
                                    val jsonString = Json.encodeToString(
                                        MapSerializer(String.serializer(), Rect.serializer()),
                                        mapValue
                                    )
                                    Message("set-string").payload(box.inside.nextSetStringSync(box.id, name, jsonString))
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
                        lprintln(
                            "warning,boxes",
                            "trying to resync something that can't be resync'd -- unknown value type $value ${value.javaClass}"
                        )
                        null
                    }
                }
            }
        }

        lprintln("info,boxes", "resync $name -> $message (x${sessions.size} sessions)")
        message?.let { message ->
            enqueue(box, message)
        }

        true
    }
    batches.forEach { (session, messages) -> sendResyncMessages(session, messages) }
}

private class BoxJobList(private val box: Box) : AbstractMutableList<Job>() {
    private val delegate = mutableListOf<Job>()
    private val completionHandles = IdentityHashMap<Job, DisposableHandle>()
    private val lock = Any()

    override val size: Int
        get() = synchronized(lock) { delegate.size }

    override fun get(index: Int): Job = synchronized(lock) {
        delegate[index]
    }

    override fun add(index: Int, element: Job) {
        synchronized(lock) {
            delegate.add(index, element)
            modCount++
            track(element)
        }
        box.syncJobRunningState()
    }

    /**
     * Append atomically.
     *
     * `AbstractList.add(E)` is `add(size(), element)` — it reads the size *outside* the lock and then
     * takes it, so a job completing in between (`removeCompleted`, from `invokeOnCompletion` on an
     * arbitrary dispatcher) shrinks the list and the index is stale. The result is a production
     * `IndexOutOfBoundsException` from the ordinary `box.jobs.add(job)` that every launch does; the
     * stress test caught `Index: 1501, Size: 1500` in under a second. The single-argument overload is
     * the only one that has to compute an index, so it is the only one that needs this.
     */
    override fun add(element: Job): Boolean {
        synchronized(lock) {
            delegate.add(element)
            modCount++
            track(element)
        }
        box.syncJobRunningState()
        return true
    }

    override fun set(index: Int, element: Job): Job {
        val previous = synchronized(lock) {
            val old = delegate.set(index, element)
            if (old !in delegate) untrack(old)
            track(element)
            old
        }
        box.syncJobRunningState()
        return previous
    }

    override fun removeAt(index: Int): Job {
        val removed = synchronized(lock) {
            val old = delegate.removeAt(index)
            if (old !in delegate) untrack(old)
            modCount++
            old
        }
        box.syncJobRunningState()
        return removed
    }

    /**
     * Remove one occurrence, under [lock], without going through `AbstractCollection`.
     *
     * Overriding this is a crash fix, not a tidy-up. `AbstractMutableList` inherits `remove(E)` from
     * `java.util.AbstractCollection`, which walks `iterator()` and calls `it.remove()` on the match —
     * and the iterator below routed its `remove()` back here. The two mutually recursed until the
     * stack died. It only *looked* rare: a job that has already completed was pulled by
     * [removeCompleted], and `AbstractCollection.remove` never reaches `it.remove()` for an element it
     * cannot find, so the ordinary `inside.jobs.remove(job)` that `Phased`, [stopTrackedJobs],
     * `ReturnValueRegistry` and every synth return value do was usually a no-op. Remove a job that is
     * still in the list and it was a `StackOverflowError` — which is how it killed the docs runner's
     * `running_and_stopping.mp4` capture on 2026-08-23.
     */
    override fun remove(element: Job): Boolean {
        val removed = removeTracked(element)
        if (removed) box.syncJobRunningState()
        return removed
    }

    /**
     * The one removal-by-value path, shared by [remove] and the iterator, so neither can fall back to
     * `AbstractCollection`'s iterator-driven implementation again.
     */
    private fun removeTracked(element: Job): Boolean = synchronized(lock) {
        val index = delegate.indexOfFirst { it === element || it == element }
        if (index < 0) return@synchronized false
        val old = delegate.removeAt(index)
        if (old !in delegate) untrack(old)
        modCount++
        true
    }

    /**
     * Iterate a snapshot, and route `remove()` back through the locked path.
     *
     * Every *method* in this class takes [lock] and bumps `modCount`, which reads as thread-safe and is
     * not: the iterator inherited from `AbstractMutableList` checks `modCount` **outside** the lock. So
     * `jobs.any { it.isActive && !it.isCancelled }` — which `syncJobRunningState` runs for every box on
     * the main runloop every 500 ms via `syncExecutionStatus`, and which `Server.kt` and
     * `agent_backdoor.kt` also run — raced [removeCompleted]; and `removeCompleted` fires from
     * `job.invokeOnCompletion`, i.e. on whatever dispatcher thread happened to finish the job.
     *
     * Locking every method and leaving the iterator alone is exactly the `Collections.synchronizedList`
     * trap described in `PropertyBag.kt`. This is the same fix in the same shape: reads from a snapshot,
     * writes through to the live list.
     */
    override fun iterator(): MutableIterator<Job> = object : MutableIterator<Job> {
        private val snapshot = synchronized(lock) { ArrayList(delegate) }
        private var index = 0
        private var last: Job? = null

        override fun hasNext(): Boolean = index < snapshot.size
        override fun next(): Job = snapshot[index++].also { last = it }
        override fun remove() {
            val target = checkNotNull(last) { "BoxJobList iterator: remove() before next()" }
            if (removeTracked(target)) box.syncJobRunningState()
            last = null
        }
    }

    override fun clear() {
        val changed = synchronized(lock) {
            if (delegate.isEmpty()) {
                false
            } else {
                completionHandles.values.forEach { it.dispose() }
                completionHandles.clear()
                delegate.clear()
                modCount++
                true
            }
        }
        if (changed) box.syncJobRunningState()
    }

    private fun track(job: Job) {
        if (completionHandles.containsKey(job)) return
        completionHandles[job] = job.invokeOnCompletion {
            removeCompleted(job)
        }
    }

    private fun untrack(job: Job) {
        completionHandles.remove(job)?.dispose()
    }

    private fun removeCompleted(job: Job) {
        val changed = synchronized(lock) {
            val removed = delegate.removeAll { it === job || it == job }
            if (removed) {
                completionHandles.remove(job)
                modCount++
            }
            removed
        }
        if (changed) box.syncJobRunningState()
    }
}

private fun Box.syncJobRunningState() {
    val hasActiveJobs = jobs.any { it.isActive && !it.isCancelled }
    val state = currentRunningStates[JOBS_RUNNING_STRAND]
    if (hasActiveJobs && state == null) {
        val generation = (runGeneration[JOBS_RUNNING_STRAND] ?: -1) + 1
        runGeneration[JOBS_RUNNING_STRAND] = generation
        currentRunningStates[JOBS_RUNNING_STRAND] = RunningState(generation, JOBS_RUNNING_MODE)
        if (isInsideInitialized()) {
            send(Message("is-running").payload(IsRunning(id, true, generation, JOBS_RUNNING_STRAND, JOBS_RUNNING_MODE)))
        }
    } else if (!hasActiveJobs && state != null) {
        currentRunningStates.remove(JOBS_RUNNING_STRAND)
        if (isInsideInitialized()) {
            send(Message("is-running").payload(IsRunning(id, false, state.watermark, JOBS_RUNNING_STRAND, JOBS_RUNNING_MODE)))
        }
    }
}

internal fun Box.clearRunningStates() {
    val states = currentRunningStates.toMap()
    currentRunningStates.clear()
    if (!isInsideInitialized()) return
    states.forEach { (strand, state) ->
        send(Message("is-running").payload(IsRunning(id, false, state.watermark, strand, state.mode)))
    }
}

/** Snapshot used to reject work that belongs to a launch superseded by [stopTrackedJobs]. */
internal fun Box.captureLaunchEpoch(): Int = launchEpoch.get()

internal fun Box.isLaunchEpochCurrent(epoch: Int): Boolean = launchEpoch.get() == epoch

/**
 * Register one asynchronous launch job without leaving a gap in which [stopTrackedJobs] can miss it.
 *
 * The checks on both sides of `jobs.add` close both possible orderings: either stop sees and cancels
 * the job, or the second check sees that stop already advanced the epoch and cancels it here.
 */
internal fun Box.registerLaunchJob(job: Job, epoch: Int): Boolean {
    if (!isLaunchEpochCurrent(epoch)) {
        job.cancel()
        return false
    }
    jobs.add(job)
    if (!isLaunchEpochCurrent(epoch)) {
        jobs.remove(job)
        job.cancel()
        return false
    }
    return true
}

/**
 * Register the job that runs a [Frames] end phase, against the epoch *now* rather than the launch's.
 *
 * The end phase is the one piece of work that legitimately belongs to a launch that stop has already
 * invalidated, because it is stop that creates it: [stopTrackedJobs] bumps [launchEpoch] and cancels
 * the body, the body's pending `yield()` throws, and `Frames.frame()` launches the end job in the
 * catch. Handing that job to [registerLaunchJob] with the *launch* epoch found the epoch stale and
 * cancelled the job while it was still queued on the Framer — which is why the stop button used to
 * skip `.end { }` (dev_docs/frames_stop_end_phase.md).
 *
 * Re-capturing the epoch keeps every other property of the fence: the end job still goes into
 * [jobs], so a *second* stop cancels it, and the strand-generation fence `Frames.shouldCancelAtEnd`
 * still cuts a multi-frame end block short. What it gives up is stop as a synchronous barrier for
 * end-phase code: an end block that calls `frame()` runs over Runloop ticks *after* stop returns.
 * That is why [stopHooks] still exists for anything holding an OS resource.
 */
internal fun Box.registerEndPhaseJob(job: Job): Boolean = registerLaunchJob(job, captureLaunchEpoch())

/**
 * Register [hook] to run on the next [stopTrackedJobs]; returns the handle that unregisters it.
 *
 * Hooks run once — stop drains the list — so a resource whose owner ends normally should unregister
 * through the returned handle rather than rely on the drain. Keep a hook short and idempotent: it
 * runs synchronously on whichever thread called stop, usually the main runloop.
 */
internal fun Box.registerStopHook(hook: Runnable): Runnable {
    stopHooks.add(hook)
    return Runnable { stopHooks.remove(hook) }
}

/** Whether a stop would find anything to cancel or drain: jobs, running strands, stop hooks or run-owned resources. */
internal fun Box.hasSomethingToStop(): Boolean =
    jobs.isNotEmpty() || currentRunningStates.isNotEmpty() || stopHooks.isNotEmpty() || runScopes.liveCount > 0

/**
 * Take [boxes] out of the running document: stop what they run, dispose any renderer they own, and
 * release their engines — the teardown shared by `-delete-box`, history restore and external
 * `metadata.edn` removal, all of which tombstone a box, and by a document's last client leaving.
 *
 * Each of those used to do a different part of it. Deleting a box released its engine but never
 * stopped it, and the other two did neither, so a deleted `loop {}`, audio patch or process kept
 * running on an engine that was gone.
 *
 * Three passes, in this order on purpose. Renderers first, because [disposeBoxRenderer] stops the
 * renderer box's dependents itself and then waits for *their* jobs to unwind before freeing the device
 * — stopping a dependent earlier would clear its job list, and the teardown would wait for nothing and
 * free buffers a cancelled loop is still writing (see `dev_docs/renderer_teardown_order.md`). Then
 * everything else that is still running, through the same [Server.stop] as the stop button, which
 * closes every [RunScope] the box has; a box with nothing running only has its run scopes closed, so an
 * idle Python cell does not interrupt the interpreter it shares. Engines last.
 *
 * So the chain is the same for every way a box leaves: close its runs, dispose its renderer, dispose
 * its engine — stop is the first two of those, and [Server.stop] documents what it guarantees.
 */
internal fun retireBoxes(boxes: Collection<Box>, entrypoint: String) {
    stopBoxes(boxes, entrypoint)
    boxes.forEach { it.disposeKi() }
}

/**
 * The first two passes of [retireBoxes] — renderers, then everything else still running — without
 * releasing the engines; for the `reset` action, which replaces each engine itself.
 */
internal fun stopBoxes(boxes: Collection<Box>, entrypoint: String) {
    boxes.forEach { box ->
        runCatching { disposeBoxRenderer(box) }.onFailure {
            lprintln("warning,box", "disposing the renderer of box ${box.id} ($entrypoint) threw: $it")
        }
    }
    boxes.forEach { box ->
        val running = box.jobs.isNotEmpty() || box.currentRunningStates.isNotEmpty() || box.stopHooks.isNotEmpty()
        when {
            running -> runCatching { Server.stop(box, entrypoint) }.onFailure {
                lprintln("warning,box", "stopping box ${box.id} ($entrypoint) threw: $it")
            }
            // Nothing running, but its runs may still hold registrations (a slider from a run that
            // has finished, a Runloop hook): release those without telling the language to stop,
            // which for a Python cell would interrupt the interpreter its siblings share.
            else -> box.runScopes.closeAll()
        }
    }
}

/** Stop is a barrier: invalidate every strand before snapshotting jobs that are already registered. */
internal fun Box.stopTrackedJobs() {
    launchEpoch.incrementAndGet()
    val strands = buildSet {
        add("default")
        addAll(runGeneration.keys)
        addAll(currentRunningStates.keys)
    }
    strands.forEach { strand ->
        val activeWatermark = currentRunningStates[strand]?.watermark ?: -1
        runGeneration.compute(strand) { _, current -> maxOf(current ?: -1, activeWatermark) + 1 }
    }
    val jobsToCancel = jobs.toList()
    jobs.clear()
    clearRunningStates()
    jobsToCancel.forEach { it.cancel() }
    // After cancelling, matching the order the end phase would have used: cancel the producer, then
    // release what it held. One hook throwing must not strand the rest, so each is isolated.
    val hooksToRun = stopHooks.toList()
    stopHooks.clear()
    hooksToRun.forEach { hook ->
        runCatching { hook.run() }.onFailure {
            lprintln("warning,box", "A stop hook threw while stopping box $id: $it")
        }
    }
    // Then every run's own registrations — audio patches, feeders, control callbacks, Runloop hooks,
    // render modules, processes — newest run first, each run newest-registration first.
    runScopes.closeAll()
}

fun syncExecutionStatus() {
    // Periodically sync execution status for all boxes that are currently running
    // This ensures the frontend stays in sync even after reconnections or state issues
    documents.values.forEach { sessionInfo ->
        sessionInfo.boxes.values.forEach { box ->
            if (box.properties.get("_tombstone").isTrue()) return@forEach

            box.syncJobRunningState()
            val activeStates = box.currentRunningStates.toMap()
            activeStates.forEach { (strand, state) ->
                val message = Message("is-running").payload(
                    IsRunning(
                        box.id,
                        true,
                        state.watermark,
                        strand,
                        state.mode
                    )
                )
                sessionInfo.sessions.forEach { _send(it, message) }
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
    if (u == METAROOT_ID) return metaroot
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
import afield.graphquery.*
import afield.datalog.*
import afield.stdlib.Vec2
import afield.stdlib.Vec3
import afield.stdlib.Vec4
import afield.stdlib.Quat
import afield.stdlib.linalg.Vector2f
import afield.stdlib.linalg.Vector2fc
import afield.stdlib.linalg.Vector3f
import afield.stdlib.linalg.Vector3fc
import afield.stdlib.linalg.Vector4f
import afield.stdlib.linalg.Vector4fc
import afield.Box
import afield.four.util.*
import afield.graphics.*
import afield.four.*
import afield.four.modules.*
import afield.four.targets.*
import afield.four.buffers.*
import afield.four.texture.*
import afield.graphics.*
import afield.stdlib.linalg.*
import afield.stdlib.linalg.arena.*
import afield.stdlib.linalg.storage.*
import kotlin.math.*
import kotlinx.coroutines.yield
import kotlinx.coroutines.runBlocking
"""

/**
 * [intro] as individual `import` statements — the exact set every box gets for free, in a form other
 * subsystems can pass around.
 *
 * The completion sidecar analyses a box headlessly, in another process, and cannot see this: it has to
 * be TOLD what is in scope. It used to carry its own hand-written approximation, which had drifted —
 * it was missing `afield.nano`, `afield.graphquery`, `afield.four.targets`, `afield.four.buffers`,
 * `afield.four.texture` and both `afield.stdlib.linalg` sub-packages, so any box using one of those
 * types without writing its own import resolved under the K1 delegate and not under AA, and the user
 * silently got fallback completions. Derived here so the two can no longer disagree.
 */
val introImports: List<String>
    get() = intro.lineSequence().map { it.trim() }.filter { it.startsWith("import ") }.distinct().toList()

private val kotlinStartupLogIds = AtomicLong(0)

private fun Box.kotlinStartupLabel(): String {
    val displayName = name.ifBlank { "Untitled" }
    return "'$displayName' ($id)"
}

private fun kotlinStartupStack(maxFrames: Int = 8): String {
    val ignoredMethods = setOf("kotlinStartupStack", "makeKI", "makeKIHere", "kotlinStartupLabel")
    return Thread.currentThread().stackTrace
        .asSequence()
        .filter { it.className.startsWith("afield.") }
        .filterNot { it.methodName in ignoredMethods }
        .filterNot { it.className.startsWith("afield.logger.") }
        .take(maxFrames)
        .joinToString(" <- ") { frame ->
            "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}"
        }
        .ifBlank { "no afield stack frames" }
}

private fun KotlinScriptEngine.kotlinStartupEngineLabel(): String {
    val backend = this::class.simpleName ?: this::class.java.name.substringAfterLast('.')
    return "$backend execNumber=$execNumber"
}

fun makeKI(box: Box, reason: String = "makeKI call"): KotlinScriptEngine {

    // force resync of actions per box
    box.actions

    logTime("ki_standardlib") {
        val startupId = kotlinStartupLogIds.incrementAndGet()
        val requestedFrom = kotlinStartupStack()
        lprintln(
            "info,ki,startup",
            "request #$startupId async reason=$reason box=${box.kotlinStartupLabel()} caller=$requestedFrom"
        )
        val ki = createKotlinScriptEngine(implicitReceivers = listOf(KotlinType(Box::class)), filePrefix = box.id)
        ki.associatedBox = box  // Set the box reference for @exported support
        // Close the engine's readiness gate now (synchronously, before returning `ki`) so any
        // exec/evaluate that follows waits for the async intro below, which opens it again.
        ki.armBootstrapGate()
        val si = thisSessionInfo.get()
        inMain {
            try {
                if (box.properties["_tombstone"].isTrue()) {
                    lprintln("info,ki,startup", "skip #$startupId tombstone box=${box.kotlinStartupLabel()}")
                    return@inMain
                }

                // Evaluate the stdlib intro on the main RunLoop — the single eval thread — so
                // it never races (or corrupts the K2 evaluator's snippet-classloader chain
                // against) another eval on this engine. The heavy compile still offloads to
                // Dispatchers.Default inside bootstrap, so this does not block the frame loop
                // for the compile, only for the (cheap) import eval. `ki.bootstrap` runs the
                // intro past the engine's readiness gate and opens that gate afterwards, so
                // every later exec/evaluate on this engine waits until the intro has run.
                Box.withBoxContext(box) {
                    lblock(
                        "info,ki,startup",
                        "executing stdlib import #$startupId async-on-main reason=$reason box=${box.kotlinStartupLabel()} engine=${ki.kotlinStartupEngineLabel()}"
                    ) {
                        lprintln(
                            "info,ki,startup",
                            "begin imports #$startupId box=${box.kotlinStartupLabel()} introLength=${intro.length}"
                        )
                        ki.bootstrap(
                            intro, implicits = listOf(box)
                        )
                        lprintln("info,ki,startup", "reset preamble cache #$startupId box=${box.kotlinStartupLabel()}")
                        box.properties.remove("_preamble")
                        lprintln(
                            "info,ki,startup",
                            "complete #$startupId box=${box.kotlinStartupLabel()} box is no longer offline"
                        )
                        box.offline = false
                        si?.topologyGeneration++
                    }
                }
            } finally {
                // Whatever happened above (tombstone skip, or the intro threw before
                // ki.bootstrap could open the gate), make sure the engine's readiness gate
                // opens so a later exec/evaluate can never hang forever. Idempotent.
                ki.releaseBootstrapGate()
            }
        }
        return ki
    }
}

suspend fun makeKIHere(box: Box, reason: String = "makeKIHere call"): KotlinScriptEngine {
    val startupId = kotlinStartupLogIds.incrementAndGet()
    val requestedFrom = kotlinStartupStack()
    lprintln(
        "info,ki,startup",
        "request #$startupId sync reason=$reason box=${box.kotlinStartupLabel()} caller=$requestedFrom"
    )
    val ki = createKotlinScriptEngine(implicitReceivers = listOf(KotlinType(Box::class)), filePrefix = box.id)
    ki.associatedBox = box  // Set the box reference for @exported support
    val si = thisSessionInfo.get()
    if (box.properties["_tombstone"].isTrue()) {
        lprintln("info,ki,startup", "skip #$startupId tombstone box=${box.kotlinStartupLabel()}")
        return box.ki
    }

    Box.withBoxContext(box) {
        lblock(
            "info,ki,startup",
            "executing stdlib import #$startupId sync reason=$reason box=${box.kotlinStartupLabel()} engine=${ki.kotlinStartupEngineLabel()}"
        ) {
            lprintln(
                "info,ki,startup",
                "begin imports #$startupId box=${box.kotlinStartupLabel()} introLength=${intro.length}"
            )
            ki.bootstrap(
                intro, implicits = listOf(box)
            )
            val preamble = box.preamble
            if (preamble.isNotBlank()) {
                lprintln(
                    "info,ki,startup",
                    "begin preamble #$startupId box=${box.kotlinStartupLabel()} preambleLength=${preamble.length}"
                )
            }
            preamble.let { ki.exec(it, implicits = listOf(box)) }
            lprintln("info,ki,startup", "complete #$startupId box=${box.kotlinStartupLabel()}")
        }
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
            @Suppress("UNCHECKED_CAST")
            all.add(m as MutableMap<String, *>)
        }
    }
    val together = mutableMapOf<String, Any?>()
    all.reversed().forEach {
        together.putAll(it)
    }
    @Suppress("UNCHECKED_CAST")
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
