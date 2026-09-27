package afield.stacksl.study

import afield.Box
import afield.BoxExecution
import afield.CompletionRequest
import afield.Execute
import afield.ExecuteSymbol
import afield.ExecutionTrace
import afield.IsRunning
import afield.Message
import afield.Output
import afield.RunsAt
import afield.Runloop
import afield.SessionInformation
import afield.configureOutputProxies
import afield.dispatchExecutionOutput
import afield.documents
import afield.early
import afield.four.Renderer
import afield.four.modules.Mesh
import afield.four.modules.Module
import afield.four.modules.ModulePointer
import afield.four.modules.ShaderKind
import afield.four.modules.parameters
import afield.four.modules.pointer
import afield.inMain
import afield.isTrue
import afield.autorun
import afield.logger.lprintln
import afield.newRoot
import afield.plugins
import afield.runtime.MainLanguageRuntime
import afield.runtime.TopologyWatcher
import afield.runtime.applyRuntimeToggle
import afield.runtime.isMainLanguage
import afield.runtime.registerMainLanguageRuntime
import afield.payload
import afield.send
import afield.stdlib.Action
import afield.stdlib.actions
import afield.stdlib.begin
import afield.stdlib.preprocess
import afield.stdlib.stackAutoShaderSources
import afield.stdlib.vec
import afield.thisSession
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * StackSL as one of Field's main languages, and the home of every StackSL program's study.
 *
 * Mark a box "Set Main Language: StackSL" and its children are StackSL programs: each edits its
 * `stackSL` property, and running it
 *
 *  - compiles it with the real compiler and puts every error and warning on the line it belongs to;
 *  - draws it, live, in the window above it — the first box up the parent chain that holds a running
 *    `renderer`, usually the root itself, which opens one of its own when it holds no Kotlin
 *    ([afield.stdlib.LanguageRoots]) — following every edit that compiles until it is stopped;
 *  - checks the program in as a revision of the box's study (under `studies/<box id>/` in the
 *    document) and shows the study's card in the box's output.
 *
 * A program whose window is closed still runs: its card carries a live WebGL preview.
 *
 * The study tools ([installStackSLStudyTools]) are offered to any AI4 conversation opened on a box's
 * `stackSL` property, and work on that box — so a program needs no tools box, and two programs, or
 * two documents, never share a study.
 */
object StackSLPrograms {
    /** The property a StackSL program lives in. */
    const val PROPERTY = "stackSL"

    /** The output channel a program's study card is shown in. */
    const val CHANNEL = "study"

    /** The main-language id, as `mainLanguage` stores it. */
    const val LANGUAGE = "stacksl"

    /** Where the window a StackSL root opens for itself goes, and its size: square, 720 by 720. */
    val WINDOW = afield.Rect(80.0, 80.0, 720.0, 720.0)

    /** The open document [box] belongs to, or `null` for a box in no open document. */
    fun sessionOf(box: Box): SessionInformation? = afield.stdlib.BoxStudies.documentOf(box)

    /**
     * The running renderer a program draws in: the nearest box, starting at [box] and walking up its
     * parents, that holds an open `renderer` — the same population `prop<Renderer>(renderer)` searches.
     */
    fun rendererFor(box: Box): Renderer? = afield.stdlib.BoxStudies.rendererAbove(box)

    /**
     * [box]'s study, created on first use. Publishing an accepted edit writes the box's `stackSL`
     * property on the main thread, refusing if the editor changed it since the edit was based on it.
     */
    fun study(box: Box): StackSLStudy {
        (box.getPropertyRaw("_stackSLStudy", null) as? StackSLStudy)?.let { return it }
        synchronized(box) {
            (box.getPropertyRaw("_stackSLStudy", null) as? StackSLStudy)?.let { return it }
            val study = StackSLStudy(
                afield.stdlib.BoxStudies.directory(box),
                { box.getPropertyRaw(PROPERTY, "") as? String ?: "" },
                afield.stdlib.BoxStudies.replacer(box, PROPERTY)
            )
            study.rasterizer = StackSLGpuRasterizer({ rendererFor(box) })
            study.editorLine = { afield.stdlib.BoxStudies.editorLine(box, PROPERTY) }
            study.resume()
            box.properties["_stackSLStudy"] = study
            return study
        }
    }

    /**
     * [study], with a first revision checked in if it has none. Renders probe frames, so call it off
     * the main thread (the GPU waits for a frame the main thread draws).
     */
    fun readyStudy(box: Box): StackSLStudy {
        val study = study(box)
        if (study.currentId() == afield.stdlib.SourceStudy.EMPTY) study.checkIn("the starting program")
        viewer(box)
        return study
    }

    /** Run [box] as the editor's run does: draw it, follow its edits, and check it in. */
    fun run(box: Box) = box.ke.executeAll(box, Execute(box.id, "run", 1, box.getPropertyRaw(PROPERTY, "") as? String ?: "", false, property = PROPERTY)) {}

    /** The card showing [box]'s study, created (and shown) on first use. */
    fun viewer(box: Box): StackSLStudyViewer {
        (box.getPropertyRaw("_stackSLViewer", null) as? StackSLStudyViewer)?.let { return it }
        synchronized(box) {
            (box.getPropertyRaw("_stackSLViewer", null) as? StackSLStudyViewer)?.let { return it }
            val viewer = StackSLStudyViewer(study(box), box, CHANNEL)
            box.properties["_stackSLViewer"] = viewer
            viewer.refreshLater()
            return viewer
        }
    }

    /** [box]'s binding to the window above it. */
    fun live(box: Box): StackSLLive =
        box.getPropertyRaw("_stackSLLive", null) as? StackSLLive ?: synchronized(box) {
            box.getPropertyRaw("_stackSLLive", null) as? StackSLLive ?: StackSLLive(box).also { box.properties["_stackSLLive"] = it }
        }

    /** Whether a conversation focused on [box]'s [property] is about a StackSL program. */
    fun isProgram(box: Box, property: String): Boolean = property == PROPERTY

    // Check-ins render probe frames, so they run here rather than on the main thread or a socket.
    internal val background = Executors.newSingleThreadExecutor { r -> Thread(r, "stacksl-programs").apply { isDaemon = true } }
}

/** One problem with a StackSL program, as the editor shows it: an error refuses the program. */
data class StackSLProblem(
    val error: Boolean,
    val message: String,
    /** 1-based line in the program, when the problem has one. */
    val line: Int?,
    /** The problem in one line, as tool results and logs quote it. */
    val summary: String,
)

/**
 * A StackSL program drawn live in a window: a fullscreen triangle whose fragment shader is the study
 * template with the program in its `{{ stack.auto.program }}` slot, and the seconds since the binding
 * began in `pc.params.x` — the same shader the study's evidence is rendered with.
 *
 * A new program builds a new pipeline (a `Module` compiles in its constructor, so a failure is known at
 * once) and replaces the old one in the window; the old one is retired through the renderer's deferred
 * queue, because a frame in flight may still be using it. All of it happens on the main thread.
 */
class StackSLLive internal constructor(private val box: Box) {
    private val templateBox = Box("stacksl-live-${box.id}")
    private val name = "stacksl-live-${box.id}"
    private val started = System.nanoTime()
    private var renderer: Renderer? = null
    private var pointer: ModulePointer? = null
    private var shown: String? = null

    /** The program the window above the box is drawing, or `null` — also when that window closed or changed. */
    val showing: String? get() = shown?.takeIf { renderer?.isClosed == false && StackSLPrograms.rendererFor(box) === renderer }

    /** A stage the card holds the window at — how many root words have run — or null for the whole program. */
    @Volatile
    var pinnedStage: Int? = null

    /** Whether the window shows the stage the editor's cursor line ends, rather than the whole program. */
    @Volatile
    var followCursor: Boolean = false

    private var wantedFor: Triple<String, Int?, Int?>? = null
    private var wantedIs: String = ""

    /**
     * What the window should draw for [program]: the stage the card holds it at, else — when it follows
     * the cursor — the stage [cursorLine] ends, else the whole program. A stage is the program up to
     * that line ([afield.stacksl.StackSLOutline.stageProgram]): what that line leaves on the stack, drawn.
     */
    fun wanted(program: String, cursorLine: Int?): String {
        val key = Triple(program, pinnedStage, cursorLine?.takeIf { followCursor })
        if (key == wantedFor) return wantedIs
        val words = key.second ?: key.third?.let { line ->
            runCatching { StackSLStudyShader.stages(program).filter { it.line == line }.maxOfOrNull { it.wordsThrough } }.getOrNull()
        }
        val result = words?.let { n ->
            runCatching { afield.stacksl.StackSLCompiler.outline(program).let { o -> if (n >= o.root.size) program else o.stageProgram(program, n) } }.getOrNull()
        } ?: program
        wantedFor = key; wantedIs = result
        return result
    }

    /**
     * Draw [source] in the window above the box. Returns `null` when it is drawing, otherwise why not:
     * no window, or a shader the GPU refused (the previous program keeps drawing).
     */
    fun show(source: String): String? = onMain {
        val r = StackSLPrograms.rendererFor(box) ?: return@onMain "no window: this box has no running renderer above it"
        if (r !== renderer) { hideNow(); renderer = r }
        if (shown == source && pointer?.current?.valid == true && r.renderList.modules[name] === pointer) return@onMain null
        templateBox.stackAutoShaderSources["program"] = source
        val fragment = try {
            preprocess(templateBox, StackSLStudyShader.FRAGMENT, ShaderKind.FRAGMENT_SHADER)
        } catch (e: Exception) {
            return@onMain "the program did not lower to GLSL: ${e.message}"
        }
        var error: String? = null
        val fresh = Module(parameters(r) {
            this.name = this@StackSLLive.name
            vertex = StackSLStudyShader.VERTEX
            this.fragment = fragment
            blend = false
            depthTest = false
            errorHandler = { message, _ -> error = message }
        }).pointer()
        if (!fresh.current.valid) {
            r.cleanup.add(Runnable { dispose(fresh) })
            return@onMain "the GPU refused the shader: ${error ?: "unknown error"}"
        }
        val mesh = Mesh(r, fresh)
        mesh.resize(3, 0, fresh)
        fresh.current.meshes["fullscreen"] = mesh
        mesh.push.bind("params") { vec(((System.nanoTime() - started) / 1e9).toFloat(), 0f, 0f, 0f) }
        val previous = pointer
        r.renderList.modules[name] = fresh
        pointer = fresh
        shown = source
        previous?.let { old -> r.cleanup.add(Runnable { dispose(old) }) }
        null
    }

    /** Stop drawing in the window. Nothing to hide costs nothing, and does not wait for the main thread. */
    fun hide() {
        if (pointer == null && shown == null) return
        onMain { hideNow() }
    }

    private fun hideNow() {
        val r = renderer
        val old = pointer
        if (r != null && old != null) {
            if (r.renderList.modules[name] === old) r.renderList.modules.remove(name)
            if (!r.isClosed) r.cleanup.add(Runnable { dispose(old) })
        }
        pointer = null
        shown = null
    }

    private fun dispose(module: ModulePointer) {
        runCatching {
            module.current.meshes.values.toSet().forEach { mesh ->
                (mesh.v.values + mesh.vn.values).toSet().forEach { it.cleanup() }
                mesh.t?.cleanup()
            }
            module.current.meshes.clear()
            module.current.cleanup()
        }
    }

    private fun <T> onMain(block: () -> T): T =
        if (Runloop.isMainThread) block() else runBlocking { withContext(Runloop.runLoop.main) { block() } }
}

/**
 * How a child of a StackSL root runs: see [StackSLPrograms]. Run and launch both draw the program and
 * follow its edits until stopped; run also checks it in as a revision.
 */
class StackSLExecution(val inside: Box) : BoxExecution {
    override val languageName: String get() = StackSLPrograms.LANGUAGE

    private fun source(execute: Execute, code: String = execute.code): String =
        if (execute.property == StackSLPrograms.PROPERTY) code
        else inside.getPropertyRaw(StackSLPrograms.PROPERTY, "") as? String ?: ""

    override fun executeFragment(inside: Box, execute: Execute, reply: (Message) -> Unit) = start(execute, source(execute), "default", reply, checkIn = true)

    override fun executeAll(inside: Box, execute: Execute, reply: (Message) -> Unit) = start(execute, source(execute), "default", reply, checkIn = true)

    override fun launchAll(inside: Box, execute: Execute, code: String, strand: String, reply: (Message) -> Unit) =
        start(execute, source(execute, code), strand, reply, checkIn = false)

    override fun executeSymbol(inside: Box, executeSymbol: ExecuteSymbol, reply: (Message) -> Unit) =
        start(Execute(executeSymbol.uid, "run", 1, "", false), source(Execute(executeSymbol.uid, "run", 1, "", false)), "default", reply, checkIn = true)

    override fun stop(inside: Box) {
        inside.runGeneration.keys.toList().forEach { inside.runGeneration[it] = (inside.runGeneration[it] ?: 0) + 1 }
        StackSLPrograms.live(inside).hide()
    }

    // Completion comes from stacksl.js in the browser.
    override fun complete(inside: Box, completionRequest: CompletionRequest, reply: (Message) -> Unit) {}

    @OptIn(DelicateCoroutinesApi::class)
    private fun start(execute: Execute, first: String, strand: String, reply: (Message) -> Unit, checkIn: Boolean) {
        val originSession = thisSession.get()
        val trace = ExecutionTrace.forEngine(inside, execute, if (checkIn) "run" else "launch", reply)
        inMain {
            configureOutputProxies(inside, trace.reply, originSession)
            // Starting a program is what opens its window, when its root has none (see LanguageRoots).
            val noWindow = afield.stdlib.LanguageRoots.ensure(inside, StackSLPrograms.LANGUAGE, "StackSL", StackSLPrograms.WINDOW)
            val startedAt = System.currentTimeMillis()
            var toldNoWindow = noWindow == null
            val generation = inside.runGeneration.getOrPut(strand) { -1 } + 1
            inside.runGeneration[strand] = generation
            // Running as Field counts it, so what stops a box's dependents — a window's teardown — stops this program too.
            inside.currentRunningStates[strand] = afield.RunningState(generation, StackSLPrograms.LANGUAGE)
            send(Message("is-running").payload(IsRunning(execute.uid, true, generation, strand)))
            val live = StackSLPrograms.live(inside)
            var lastGood = if (report(first, trace.reply, originSession)) first else null
            val refused = lastGood?.let { live.show(it) }?.takeIf { !it.startsWith("no window") }
            refused?.let { inside.error(it, StackSLPrograms.PROPERTY, 1); lastGood = null }
            val ok = lastGood != null
            if (ok) trace.finish(true) else trace.finish(false, "the program has errors; the window keeps the last program that compiled")
            if (checkIn && ok) checkInLater()
            else StackSLPrograms.background.execute { runCatching { StackSLPrograms.viewer(inside) } }
            // Follow the box: report each new edit's problems once, and keep the last edit without
            // errors in the window — including a window that opens, or is relaunched, after the start.
            GlobalScope.launch(Runloop.runLoop.main) {
                var last = first
                var changedAt = 0L
                try {
                    while (inside.runGeneration[strand] == generation) {
                        delay(FOLLOW_MS)
                        if (inside.runGeneration[strand] != generation) break
                        val now = inside.getPropertyRaw(StackSLPrograms.PROPERTY, "") as? String ?: ""
                        if (now != last) {
                            last = now
                            changedAt = System.currentTimeMillis()
                            if (report(now, trace.reply, originSession)) lastGood = now
                        }
                        // Once the typing settles, let the card say there is an unchecked edit.
                        if (changedAt > 0 && System.currentTimeMillis() - changedAt > SETTLE_MS) {
                            changedAt = 0
                            (inside.getPropertyRaw("_stackSLViewer", null) as? StackSLStudyViewer)?.refreshLater()
                        }
                        val good = lastGood
                        // The whole program, or the stage the card or the editor's line asks for.
                        val wanted = good?.let { live.wanted(it, if (live.followCursor) afield.stdlib.BoxStudies.editorLine(inside, StackSLPrograms.PROPERTY) else null) }
                        if (good != null && wanted != null && live.showing != wanted) {
                            val refused = live.show(wanted)?.takeIf { !it.startsWith("no window") }
                            // A stage the GPU refuses falls back to the whole program; only the program itself failing is an error.
                            if (refused != null && wanted != good) live.show(good)
                            else if (refused != null) {
                                inside.error(refused, StackSLPrograms.PROPERTY, 1)
                                lastGood = null
                            }
                        }
                        // Say why there is no window — after a moment, since a root's own Kotlin may be opening one.
                        if (!toldNoWindow && live.showing == null && System.currentTimeMillis() - startedAt > NO_WINDOW_GRACE_MS) {
                            toldNoWindow = true
                            afield.notify("'${inside.name}': $noWindow", StackSLPrograms.LANGUAGE)
                        }
                    }
                } finally {
                    if (inside.currentRunningStates[strand]?.watermark == generation) inside.currentRunningStates.remove(strand)
                    send(Message("is-running").payload(IsRunning(execute.uid, false, generation, strand)))
                }
            }
        }
    }

    /** Put [source]'s problems on its lines, replacing the last report; true if it has no errors. */
    private fun report(source: String, reply: (Message) -> Unit, originSession: io.ktor.websocket.WebSocketSession?): Boolean {
        val problems = runCatching { StackSLStudy.diagnose(source) }.getOrElse { listOf(StackSLProblem(true, it.message ?: "$it", null, "$it")) }
        clearErrors(reply, originSession)
        problems.forEach { p -> inside.error((if (p.error) "" else "warning: ") + p.message, StackSLPrograms.PROPERTY, p.line ?: 1) }
        return problems.none { it.error }
    }

    private fun clearErrors(reply: (Message) -> Unit, originSession: io.ktor.websocket.WebSocketSession?) {
        dispatchExecutionOutput(
            inside, reply,
            Output(uid = inside.id, line = -1, kind = "err", property = StackSLPrograms.PROPERTY, html = "", frame = Runloop.runLoop.frame.toInt()),
            originSession = originSession
        )
    }

    private fun checkInLater() {
        StackSLPrograms.background.execute {
            try {
                val study = StackSLPrograms.readyStudy(inside)
                if (study.revisions.liveState()["dirty"] == true) study.checkIn("run by hand")
            } catch (e: afield.stdlib.StudyRejection) {
                inMain { inside.error("not checked in: ${e.message}", StackSLPrograms.PROPERTY, 1) }
            } catch (e: Exception) {
                lprintln("warning,stacksl", "checking in '${inside.name}' failed: $e")
            }
        }
    }

    private companion object {
        /** How often a running program looks for a new edit to draw. */
        const val FOLLOW_MS = 60L

        /** How long an edit must stand before the card is rebuilt to show it. */
        const val SETTLE_MS = 1200L

        /** How long a started program waits for a window before saying why it has none. */
        const val NO_WINDOW_GRACE_MS = 3000L
    }
}

/** How long an autorun program waits after becoming StackSL before starting, so its window can open. */
private const val AUTOSTART_DELAY_MS = 800L

/** Register StackSL as a main language, so "Set Main Language: StackSL" is offered on a root box. */
@RunsAt(early)
fun installStackSLLanguage() {
    registerMainLanguageRuntime(
        MainLanguageRuntime(
            id = StackSLPrograms.LANGUAGE,
            label = "StackSL",
            runtimeClass = StackSLExecution::class,
            setLegacyRoot = { _, _ -> },
            applyTopology = ::stackSLTopologyNeedsUpdating,
        ),
    )
}

/** Re-apply [stackSLTopologyNeedsUpdating] whenever the graph changes. */
@RunsAt(plugins)
fun installStackSLLanguageWatcher() {
    TopologyWatcher(StackSLPrograms.LANGUAGE) { stackSLTopologyNeedsUpdating(it) }.start()
}

/** The program a new StackSL box starts with: layered noise over slowly drifting coordinates, coloured. */
const val STACKSL_STARTER = """# A StackSL program. The window above draws it; edit a number and watch it change.
# Coordinates, zoomed and drifting slowly, through layered noise, into colour.
uv 5.0 *
time 0.12 * +
fbm
palette
saturate
"""

/**
 * "Make a StackSL Program": the selected box becomes a program beneath a StackSL root — a new one just
 * above it, if it has none, which opens a window of its own — and starts drawing. A selected root gets a
 * new program beneath it.
 */
@RunsAt(newRoot)
fun installStackSLProgramAction(root: Box) {
    root.actions["make-stacksl-program"] = Action(
        desc = "Make a StackSL Program",
        detail = "The selected box becomes a StackSL program, drawn live in a window of its own. Its AI4 chat gets tools that check, render, compare and version it.",
    ) { selection ->
        inMain {
            afield.stdlib.LanguageRoots.adopt(selection, StackSLPrograms.LANGUAGE, "StackSL window", "StackSL program", StackSLPrograms.PROPERTY, STACKSL_STARTER).forEach { program ->
                program.properties["_stackSLAutostarted"] = true
                StackSLPrograms.run(program)
                afield.notify("'${program.name}' is a StackSL program: edit it and watch the window, or describe what you want in its AI4 chat", StackSLPrograms.LANGUAGE)
            }
        }
    }
}

/**
 * Give every child of a StackSL root a [StackSLExecution] that edits `stackSL`, and hand a child back to
 * Kotlin when no StackSL root claims it any more.
 */
fun stackSLTopologyNeedsUpdating(values: Collection<Box>) {
    applyRuntimeToggle(
        boxes = values,
        isRoot = { it.isMainLanguage(StackSLPrograms.LANGUAGE) },
        runtimeClass = StackSLExecution::class,
        newExecution = { child -> StackSLExecution(child) },
        language = "StackSL",
        customization = "",
        notifyCategory = StackSLPrograms.LANGUAGE,
        onAttach = { child ->
            if ((child.getPropertyRaw("default_property", null) as? String).isNullOrBlank()) {
                child.setPropertyRaw("default_property", StackSLPrograms.PROPERTY, doResync = true)
            }
            if (child.getPropertyRaw(StackSLPrograms.PROPERTY, null) == null) child.setPropertyRaw(StackSLPrograms.PROPERTY, STACKSL_STARTER, doResync = true)
            // Field's autorun pass may run before this box became StackSL, so an autorun program starts
            // itself — once: attach is repeated on every graph change.
            if (child.autorun.isTrue() && child.getPropertyRaw("_stackSLAutostarted", null) != true) {
                child.properties["_stackSLAutostarted"] = true
                // Not over a run that started meanwhile — a run is what gives a box its language at once.
                inMain { kotlinx.coroutines.delay(AUTOSTART_DELAY_MS); if (child.currentRunningStates.isEmpty()) child.begin() }
            }
        },
        onDetach = { child -> runCatching { StackSLPrograms.live(child).hide() } },
    )
}
