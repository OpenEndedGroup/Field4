package afield.rectsl.study

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
import afield.autorun
import afield.configureOutputProxies
import afield.dispatchExecutionOutput
import afield.early
import afield.four.Renderer
import afield.four.modules.Mesh
import afield.four.modules.Module
import afield.four.modules.ModulePointer
import afield.four.modules.parameters
import afield.four.modules.pointer
import afield.four.targets.RenderToTexture
import org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST
import org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM
import afield.inMain
import afield.isTrue
import afield.notify
import afield.logger.lprintln
import afield.payload
import afield.newRoot
import afield.plugins
import afield.rectsl.RectSLProblem
import afield.rectsl.SceneProgram
import afield.rectsl.writeTo
import afield.runtime.MainLanguageRuntime
import afield.runtime.TopologyWatcher
import afield.runtime.applyRuntimeToggle
import afield.runtime.isMainLanguage
import afield.runtime.registerMainLanguageRuntime
import afield.send
import afield.stdlib.Action
import afield.stdlib.BoxStudies
import afield.stdlib.actions
import afield.stdlib.LanguageRoots
import afield.stdlib.begin
import afield.thisSession
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * RectSL as one of Field's main languages, and the home of every RectSL scene's study.
 *
 * Mark a box "Set Main Language: RectSL" and its children are RectSL scenes: each edits its `rectSL`
 * property, and running it
 *
 *  - compiles it and puts every problem on the line it belongs to, as you type;
 *  - draws it, live and looping, in the window above it — the first box up the parent chain that holds
 *    a running `renderer`, usually the root itself, which opens one of its own when it holds no Kotlin
 *    ([LanguageRoots]) — following every edit that compiles until stopped;
 *  - checks the scene in as a revision of the box's study (under `studies/<box id>/` in the document)
 *    and shows the study's card in the box's output.
 *
 * The study tools ([installRectSLStudyTools]) are offered to any AI4 conversation opened on a box's
 * `rectSL` property and work on that box, so a scene needs no tools box, and two scenes never share a study.
 */
object RectSLPrograms {
    /** The property a RectSL scene lives in. */
    const val PROPERTY = "rectSL"

    /** The output channel a scene's study card is shown in. */
    const val CHANNEL = "study"

    /** The main-language id, as `mainLanguage` stores it. */
    const val LANGUAGE = "rectsl"

    /** Where the window a RectSL root opens for itself goes, and its size: a 16:9 wall at 960 by 540. */
    val WINDOW = afield.Rect(80.0, 80.0, 960.0, 540.0)

    /** The open document [box] belongs to, or `null`. */
    fun sessionOf(box: Box): SessionInformation? = BoxStudies.documentOf(box)

    /** The running renderer a scene draws in; see [BoxStudies.rendererAbove]. */
    fun rendererFor(box: Box): Renderer? = BoxStudies.rendererAbove(box)

    /** [box]'s study, created on first use; publishing writes the box's `rectSL` on the main thread. */
    fun study(box: Box): RectSLStudy {
        (box.getPropertyRaw("_rectSLStudy", null) as? RectSLStudy)?.let { return it }
        synchronized(box) {
            (box.getPropertyRaw("_rectSLStudy", null) as? RectSLStudy)?.let { return it }
            val study = RectSLStudy(BoxStudies.directory(box), { box.getPropertyRaw(PROPERTY, "") as? String ?: "" }, BoxStudies.replacer(box, PROPERTY))
            study.rasterizer = RectSLGpuRasterizer({ rendererFor(box) })
            study.editorLine = { BoxStudies.editorLine(box, PROPERTY) }
            study.resume()
            box.properties["_rectSLStudy"] = study
            return study
        }
    }

    /** [study], with a first revision checked in if it has none. Draws probe frames, so call it off the main thread. */
    fun readyStudy(box: Box): RectSLStudy {
        val study = study(box)
        if (study.currentId() == afield.stdlib.SourceStudy.EMPTY) study.checkIn("the starting scene")
        viewer(box)
        return study
    }

    /** The card showing [box]'s study, created (and shown) on first use. */
    fun viewer(box: Box): RectSLStudyViewer {
        (box.getPropertyRaw("_rectSLViewer", null) as? RectSLStudyViewer)?.let { return it }
        synchronized(box) {
            (box.getPropertyRaw("_rectSLViewer", null) as? RectSLStudyViewer)?.let { return it }
            val viewer = RectSLStudyViewer(study(box), box, CHANNEL)
            box.properties["_rectSLViewer"] = viewer
            viewer.refreshLater()
            return viewer
        }
    }

    /** [box]'s binding to the window above it. */
    fun live(box: Box): RectSLLive =
        box.getPropertyRaw("_rectSLLive", null) as? RectSLLive ?: synchronized(box) {
            box.getPropertyRaw("_rectSLLive", null) as? RectSLLive ?: RectSLLive(box).also { box.properties["_rectSLLive"] = it }
        }

    /** Run [box] as the editor's run does: draw it, follow its edits, and check it in. */
    fun run(box: Box) = box.ke.executeAll(box, Execute(box.id, "run", 1, box.getPropertyRaw(PROPERTY, "") as? String ?: "", false, property = PROPERTY)) {}

    /** Whether a conversation focused on [box]'s [property] is about a RectSL scene. */
    fun isScene(box: Box, property: String): Boolean = property == PROPERTY

    // Check-ins draw probe frames, so they run here rather than on the main thread or a socket.
    internal val background = Executors.newSingleThreadExecutor { r -> Thread(r, "rectsl-programs").apply { isDaemon = true } }
}

/**
 * A RectSL scene drawn live in a window, looping over its duration: the lowered rectangles through
 * [RectSLStudyShader]'s shaders into an 8-bit target the size of the window, shown by a fullscreen pass
 * that hands the swapchain exactly those bytes (see [RectSLStudyShader] for why not straight into the
 * window) — the whole wall fitted in, with black around it.
 *
 * A new scene whose materials compile to the same fragment shader as the one drawing only swaps the
 * scene the mesh is filled from — nothing is rebuilt, so typing a number moves a rectangle on the next
 * frame. A new material builds a new pipeline (a `Module` compiles in its constructor, so a failure is
 * known at once), and a resized window a new target; what they replace is retired through the
 * renderer's deferred queue, because a frame in flight may still use it. All of it on the main thread.
 *
 * The card can hold the clock at one time ([hold]), restart it, and show a scene other than the box's
 * while its sliders move ([preview]).
 */
class RectSLLive internal constructor(private val box: Box) {
    private val name = "rectsl-live-${box.id}"
    private var renderer: Renderer? = null
    private var target: RenderToTexture? = null
    private var composite: ModulePointer? = null
    private var pointer: ModulePointer? = null
    private var builtFragment: String? = null
    private var builtExtent: Pair<Int, Int>? = null
    private var shown: String? = null
    @Volatile private var scene: SceneProgram? = null
    @Volatile private var started = System.nanoTime()
    @Volatile private var frameTime = 0.0

    /** Seconds to hold the window at, or null to play. */
    @Volatile
    var held: Double? = null
        private set

    /** A scene the card is previewing in place of the box's, or null. */
    @Volatile
    var preview: RectSLDrawable? = null

    /** The rectangles to outline in the window: those the editor's line makes (see [RectSLWindowLinks]). */
    @Volatile
    var outlined: Set<String> = emptySet()

    /** The scene the window is drawing, with the lines its rectangles came from, or null. */
    @Volatile
    var drawing: RectSLDrawable? = null
        private set

    /** Takes the click listener off the window it was put on. */
    private var unlisten: (() -> Unit)? = null

    /**
     * The source the window above the box is drawing, or null — also when that window closed, changed,
     * or was resized since (so the next [present] rebuilds its target).
     */
    val showing: String?
        get() = shown?.takeIf { renderer?.isClosed == false && RectSLPrograms.rendererFor(box) === renderer && extentOf(renderer!!) == builtExtent }

    /** The time the window is drawing now, in the scene's seconds. */
    fun time(): Double {
        held?.let { return it }
        val duration = scene?.duration?.takeIf { it > 0 } ?: return 0.0
        return ((System.nanoTime() - started) / 1e9) % duration
    }

    /** Hold the window at [seconds], or play again from there with null. */
    fun hold(seconds: Double?) {
        val now = time()
        held = seconds
        if (seconds == null) started = System.nanoTime() - (now * 1e9).toLong()
    }

    /** Play from the start. */
    fun restart() {
        held = null
        started = System.nanoTime()
    }

    /** Show [good] — or the card's [preview] while there is one — if it is not what the window is drawing. */
    fun present(good: RectSLDrawable?): String? {
        val wanted = preview ?: good ?: return null
        return if (showing == wanted.source) null else show(wanted)
    }

    private fun extentOf(r: Renderer): Pair<Int, Int>? = runCatching {
        r.windowContext(0).swapChain.swapChainExtent.let { it.width() to it.height() }
    }.getOrNull()?.takeIf { it.first > 0 && it.second > 0 }

    /**
     * Draw [drawable] in the window above the box. Returns null when it is drawing, otherwise why not:
     * no window, or a shader the GPU refused (the previous scene keeps drawing).
     */
    fun show(drawable: RectSLDrawable): String? = onMain {
        val r = RectSLPrograms.rendererFor(box) ?: return@onMain "no window: this box has no running renderer above it"
        if (r !== renderer) { hideNow(); renderer = r; listen(r) }
        val extent = extentOf(r) ?: return@onMain "no window: the renderer has no window to draw in"
        val fragment = RectSLStudyShader.fragment(drawable.materials)
        val current = pointer
        if (current != null && builtFragment == fragment && builtExtent == extent && current.current.valid && r.renderList.preTargets[name] === target) {
            scene = drawable.scene
            drawing = drawable
            shown = drawable.source
            return@onMain null
        }
        if (builtExtent != extent || target == null || composite == null) {
            // A new target for a new window size, and the pass that shows it; the scene module is rebuilt below.
            retire(r)
            val t = RenderToTexture(r) {
                width = extent.first
                height = extent.second
                format = VK_FORMAT_R8G8B8A8_UNORM
                useDepth = false
                colorSampled = true
                clearColor = afield.stdlib.vec(0f, 0f, 0f, 1f)
                debugName = name
            }
            val sampler = t.defaultSampler ?: t.setDefaultSampler(anisotropyEnable = false, filter = VK_FILTER_NEAREST)
            var error: String? = null
            val shownBy = Module(parameters(r) {
                this.name = "${this@RectSLLive.name}-composite"
                vertex = RectSLStudyShader.COMPOSITE_VERTEX
                this.fragment = RectSLStudyShader.COMPOSITE_FRAGMENT
                blend = false
                depthTest = false
                textureResolver = { texture -> if (texture.name == "rectslScene") t to sampler else null }
                errorHandler = { message, _ -> error = message }
            }).pointer()
            if (!shownBy.current.valid) {
                r.cleanup.add(Runnable { dispose(shownBy); disposeTarget(t) })
                return@onMain "the GPU refused the window pass: ${error ?: "unknown error"}"
            }
            val fullscreen = Mesh(r, shownBy)
            fullscreen.resize(3, 0, shownBy)
            shownBy.current.meshes["fullscreen"] = fullscreen
            target = t
            composite = shownBy
            builtExtent = extent
        }
        val t = target!!
        var error: String? = null
        val fresh = Module(parameters(r) {
            this.name = this@RectSLLive.name
            vertex = RectSLStudyShader.VERTEX
            this.fragment = fragment
            blend = true
            depthTest = false
            primitiveType = triangles
            t.apply()
            errorHandler = { message, _ -> error = message }
        }).pointer()
        if (!fresh.current.valid) {
            r.cleanup.add(Runnable { dispose(fresh) })
            return@onMain "the GPU refused the scene's shader: ${error ?: "unknown error"}"
        }
        scene = drawable.scene
        val mesh = Mesh(r, fresh)
        mesh.push.bind("worldToClip") { RectSLStudyShader.contain(scene ?: drawable.scene, extent.first, extent.second) }
        mesh.push.bind("time") { frameTime.toFloat() }
        mesh.push.bind("wallWidth") { (scene ?: drawable.scene).wall.width.toFloat() }
        mesh.push.bind("wallHeight") { (scene ?: drawable.scene).wall.height.toFloat() }
        // An outline a couple of the window's points wide, whatever the wall's units and the display's density.
        val points = runCatching { r.window.size().w }.getOrNull()?.takeIf { it > 0 } ?: extent.first.toDouble()
        mesh.update["rectsl-frame"] = sequence {
            while (true) {
                frameTime = time()
                scene?.let {
                    val point = RectSLStudyShader.visible(it, extent.first, extent.second)[2] / points
                    RectSLStudyShader.mesh(it, frameTime, outlined, point).writeTo(mesh)
                }
                yield(Unit)
            }
        }
        fresh.current.meshes["scene"] = mesh
        val previous = pointer
        t += name to fresh
        r.renderList.preTargets[name] = t
        r.renderList.modules["${name}-composite"] = composite!!
        pointer = fresh
        builtFragment = fragment
        drawing = drawable
        shown = drawable.source
        previous?.takeIf { it !== fresh }?.let { old -> r.cleanup.add(Runnable { dispose(old) }) }
        null
    }

    /** Stop drawing in the window. Nothing to hide costs nothing, and does not wait for the main thread. */
    fun hide() {
        if (pointer == null && shown == null && target == null) return
        onMain { hideNow() }
    }

    private fun hideNow() {
        renderer?.let { retire(it) }
        // Forget the window too, so the next show listens to it again.
        renderer = null
        unlisten?.invoke(); unlisten = null
        shown = null
        drawing = null
        outlined = emptySet()
    }

    /**
     * A click in [r]'s window puts the editor on the line that made the rectangle under it
     * ([RectSLWindowLinks]); the listener goes when the scene leaves the window.
     */
    private fun listen(r: Renderer) {
        unlisten?.invoke()
        val window = runCatching { r.window }.getOrNull() ?: return
        unlisten = window.addMouseButtonListener { button, action, _ ->
            if (button != org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT || action != org.lwjgl.glfw.GLFW.GLFW_PRESS) return@addMouseButtonListener
            runCatching {
                val size = window.size()
                val at = window.cursor
                pick(at.x.toDouble() / size.w, at.y.toDouble() / size.h, size.w / size.h)
            }.onFailure { lprintln("warning,rectsl", "picking a rectangle in the window failed: $it") }
        }
    }

    /**
     * What a click at ([u], [v]) of the window does — `u` across from the left, `v` down from the top, both
     * 0..1, in a window [aspect] wide for each unit high: find the topmost rectangle under it now and put the
     * editor on the line that made it. Returns that line, or null when the click missed.
     */
    fun pick(u: Double, v: Double, aspect: Double): Int? {
        val d = drawing ?: return null
        if (u !in 0.0..1.0 || v !in 0.0..1.0) return null
        val point = RectSLStudyShader.wallPoint(d.scene, (aspect * 1000).toInt(), 1000, u, v)
        val hit = RectSLWindowLinks.hit(d.scene, time(), point.x, point.y) ?: return null
        val line = d.origins[hit.id]?.line ?: return null
        RectSLWindowLinks.reveal(box, line)
        return line
    }

    /** Take the target, its pass and the scene module out of [r]'s frame and queue them for disposal. */
    private fun retire(r: Renderer) {
        val t = target; val c = composite; val m = pointer
        if (t != null && r.renderList.preTargets[name] === t) r.renderList.preTargets.remove(name)
        if (c != null && r.renderList.modules["${name}-composite"] === c) r.renderList.modules.remove("${name}-composite")
        if (!r.isClosed) r.cleanup.add(Runnable {
            m?.let(::dispose)
            c?.let(::dispose)
            t?.let(::disposeTarget)
        })
        target = null; composite = null; pointer = null
        builtFragment = null; builtExtent = null
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

    private fun disposeTarget(rtt: RenderToTexture) {
        runCatching {
            rtt.modules.clear()
            rtt.defaultSampler?.cleanup()
            rtt.imageViewArray.map { it.imageView }.distinctBy { it.vkImageView }.forEach { it.cleanup() }
            rtt.colorImageRefs.filterNotNull().distinctBy { it.vkImage }.forEach { it.cleanup() }
            rtt.commandBuffers.forEach { it.cleanup() }
            rtt.fences.forEach { it.cleanup() }
        }
    }

    private fun <T> onMain(block: () -> T): T =
        if (Runloop.isMainThread) block() else runBlocking { withContext(Runloop.runLoop.main) { block() } }
}

/**
 * How a child of a RectSL root runs: see [RectSLPrograms]. Run and launch both draw the scene and follow
 * its edits until stopped; run also checks it in as a revision. Compiling happens off the main thread
 * (a `{{ … }}` escape compiles Kotlin); drawing and reporting on it.
 */
class RectSLExecution(val inside: Box) : BoxExecution {
    override val languageName: String get() = RectSLPrograms.LANGUAGE

    private fun source(execute: Execute, code: String = execute.code): String =
        if (execute.property == RectSLPrograms.PROPERTY) code
        else inside.getPropertyRaw(RectSLPrograms.PROPERTY, "") as? String ?: ""

    override fun executeFragment(inside: Box, execute: Execute, reply: (Message) -> Unit) = start(execute, source(execute), "default", reply, checkIn = true)

    override fun executeAll(inside: Box, execute: Execute, reply: (Message) -> Unit) = start(execute, source(execute), "default", reply, checkIn = true)

    override fun launchAll(inside: Box, execute: Execute, code: String, strand: String, reply: (Message) -> Unit) =
        start(execute, source(execute, code), strand, reply, checkIn = false)

    override fun executeSymbol(inside: Box, executeSymbol: ExecuteSymbol, reply: (Message) -> Unit) =
        Execute(executeSymbol.uid, "run", 1, "", false).let { start(it, source(it), "default", reply, checkIn = true) }

    override fun stop(inside: Box) {
        inside.runGeneration.keys.toList().forEach { inside.runGeneration[it] = (inside.runGeneration[it] ?: 0) + 1 }
        RectSLPrograms.live(inside).hide()
    }

    // Completion comes from rectsl.js in the browser.
    override fun complete(inside: Box, completionRequest: CompletionRequest, reply: (Message) -> Unit) {}

    @OptIn(DelicateCoroutinesApi::class)
    private fun start(execute: Execute, first: String, strand: String, reply: (Message) -> Unit, checkIn: Boolean) {
        val originSession = thisSession.get()
        val trace = ExecutionTrace.forEngine(inside, execute, if (checkIn) "run" else "launch", reply)
        inMain {
            configureOutputProxies(inside, trace.reply, originSession)
            // Starting a scene is what opens its window, when its root has none (see LanguageRoots).
            val noWindow = LanguageRoots.ensure(inside, RectSLPrograms.LANGUAGE, "RectSL", RectSLPrograms.WINDOW)
            val generation = inside.runGeneration.getOrPut(strand) { -1 } + 1
            inside.runGeneration[strand] = generation
            // Running as Field counts it, so what stops a box's dependents — a window's teardown — stops this scene too.
            inside.currentRunningStates[strand] = afield.RunningState(generation, RectSLPrograms.LANGUAGE)
            send(Message("is-running").payload(IsRunning(execute.uid, true, generation, strand)))
            GlobalScope.launch(Dispatchers.Default) {
                val live = RectSLPrograms.live(inside)
                val startedAt = System.currentTimeMillis()
                var toldNoWindow = noWindow == null
                try {
                    var lastGood = compile(first, trace.reply, originSession)
                    val refused = live.present(lastGood)?.takeIf { !it.startsWith("no window") }
                    val ok = lastGood != null && refused == null
                    withContext(Runloop.runLoop.main) {
                        refused?.let { inside.error(it, RectSLPrograms.PROPERTY, 1) }
                        if (ok) trace.finish(true) else trace.finish(false, "the scene has problems; the window keeps the last scene that compiled")
                    }
                    if (refused != null) lastGood = null
                    if (checkIn && ok) checkInLater()
                    else RectSLPrograms.background.execute { runCatching { RectSLPrograms.viewer(inside).refreshLater() } }
                    // Follow the box: report each new edit's problems once, and keep the last edit that
                    // compiled in the window — including a window that opens, or is relaunched, later.
                    var last = first
                    var changedAt = 0L
                    while (inside.runGeneration[strand] == generation) {
                        delay(FOLLOW_MS)
                        if (inside.runGeneration[strand] != generation) break
                        val (now, cursor) = withContext(Runloop.runLoop.main) {
                            (inside.getPropertyRaw(RectSLPrograms.PROPERTY, "") as? String ?: "") to RectSLWindowLinks.cursorLine(inside)
                        }
                        // The editor's line, outlined in the window.
                        live.outlined = live.drawing?.let { d -> cursor?.let { RectSLWindowLinks.idsForLine(d, it) } }.orEmpty()
                        if (now != last) {
                            last = now
                            changedAt = System.currentTimeMillis()
                            compile(now, trace.reply, originSession)?.let { lastGood = it }
                        }
                        if (changedAt > 0 && System.currentTimeMillis() - changedAt > SETTLE_MS) {
                            changedAt = 0
                            (inside.getPropertyRaw("_rectSLViewer", null) as? RectSLStudyViewer)?.refreshLater()
                        }
                        live.present(lastGood)?.takeIf { !it.startsWith("no window") }?.let {
                            withContext(Runloop.runLoop.main) { inside.error(it, RectSLPrograms.PROPERTY, 1) }
                            lastGood = null
                        }
                        // A card drawn before the window was up says the CPU drew it; redraw it once it is.
                        if (live.showing != null) (inside.getPropertyRaw("_rectSLViewer", null) as? RectSLStudyViewer)?.redrawIfDrawnWithoutWindow()
                        // Say why there is no window — after a moment, since a root's own Kotlin may be opening one.
                        else if (!toldNoWindow && System.currentTimeMillis() - startedAt > NO_WINDOW_GRACE_MS) {
                            toldNoWindow = true
                            notify("'${inside.name}': $noWindow", RectSLPrograms.LANGUAGE)
                        }
                    }
                } catch (e: Exception) {
                    lprintln("warning,rectsl", "running '${inside.name}' failed: $e")
                } finally {
                    if (inside.currentRunningStates[strand]?.watermark == generation) inside.currentRunningStates.remove(strand)
                    send(Message("is-running").payload(IsRunning(execute.uid, false, generation, strand)))
                }
            }
        }
    }

    /** Compile [source] and put its problems on its lines, replacing the last report; the drawable if it has no errors. */
    private suspend fun compile(source: String, reply: (Message) -> Unit, originSession: io.ktor.websocket.WebSocketSession?): RectSLDrawable? {
        val (drawable, problems) = try {
            val compiled = RectSLStudy.compile(source)
            val program = compiled.program
            (if (compiled.ok && program != null) RectSLDrawable(source, program, compiled.materials, compiled.origins) else null) to compiled.problems
        } catch (e: Exception) {
            null to listOf(RectSLProblem(e.message ?: "$e", null, null))
        }
        withContext(Runloop.runLoop.main) {
            dispatchExecutionOutput(inside, reply,
                Output(uid = inside.id, line = -1, kind = "err", property = RectSLPrograms.PROPERTY, html = "", frame = Runloop.runLoop.frame.toInt()),
                originSession = originSession)
            problems.forEach { p -> inside.error((if (p.error) "" else "warning: ") + p.message, RectSLPrograms.PROPERTY, p.line ?: 1) }
        }
        return drawable
    }

    private fun checkInLater() {
        RectSLPrograms.background.execute {
            try {
                val study = RectSLPrograms.readyStudy(inside)
                // Running the box shows its card as it stands: a new revision redraws it, and so does this.
                if (study.revisions.liveState()["dirty"] == true) study.checkIn("run by hand")
                else RectSLPrograms.viewer(inside).refreshLater()
            } catch (e: afield.stdlib.StudyRejection) {
                inMain { inside.error("not checked in: ${e.message}", RectSLPrograms.PROPERTY, 1) }
            } catch (e: Exception) {
                lprintln("warning,rectsl", "checking in '${inside.name}' failed: $e")
            }
        }
    }

    private companion object {
        /** How often a running scene looks for a new edit to draw. */
        const val FOLLOW_MS = 60L

        /** How long an edit must stand before the card is rebuilt to show it. */
        const val SETTLE_MS = 1200L

        /** How long a started scene waits for a window before saying why it has none. */
        const val NO_WINDOW_GRACE_MS = 3000L
    }
}

/** How long an autorun scene waits after becoming RectSL before starting, so its window can open. */
private const val AUTOSTART_DELAY_MS = 800L

/** Register RectSL as a main language, so "Set Main Language: RectSL" is offered on a root box. */
@RunsAt(early)
fun installRectSLLanguage() {
    registerMainLanguageRuntime(
        MainLanguageRuntime(
            id = RectSLPrograms.LANGUAGE,
            label = "RectSL",
            runtimeClass = RectSLExecution::class,
            setLegacyRoot = { _, _ -> },
            applyTopology = ::rectSLTopologyNeedsUpdating,
        ),
    )
}

/** Re-apply [rectSLTopologyNeedsUpdating] whenever the graph changes. */
@RunsAt(plugins)
fun installRectSLLanguageWatcher() {
    TopologyWatcher(RectSLPrograms.LANGUAGE) { rectSLTopologyNeedsUpdating(it) }.start()
}

/** The scene a new RectSL box starts with: a wall, a background, and one rectangle to change. */
const val RECTSL_STARTER = """# A RectSL scene. The window above draws it, looping; edit a number and watch it move.
->wall 16 9 meters
->duration 6
->fps 30
->background 0x101014

"square" rect
  0 0 ->xy
  2 ->square
  0xf0b040 solid ->fill
  '( 0 0   6 6.28318 ) ->rot
;
"""

/**
 * "Make a RectSL Scene": the selected box becomes a scene beneath a RectSL root — a new one just above
 * it, if it has none, which opens a window of its own — and starts playing. A selected root gets a new
 * scene beneath it.
 */
@RunsAt(newRoot)
fun installRectSLSceneAction(root: Box) {
    root.actions["make-rectsl-scene"] = Action(
        desc = "Make a RectSL Scene",
        detail = "The selected box becomes a RectSL scene, playing live in a window of its own. Its AI4 chat gets tools that check, draw, compare and version it.",
    ) { selection ->
        inMain {
            LanguageRoots.adopt(selection, RectSLPrograms.LANGUAGE, "RectSL window", "RectSL scene", RectSLPrograms.PROPERTY, RECTSL_STARTER).forEach { scene ->
                scene.properties["_rectSLAutostarted"] = true
                RectSLPrograms.run(scene)
                notify("'${scene.name}' is a RectSL scene: edit it and watch the window, or describe what you want in its AI4 chat", RectSLPrograms.LANGUAGE)
            }
        }
    }
}

/**
 * Give every child of a RectSL root a [RectSLExecution] that edits `rectSL`, and hand a child back to
 * Kotlin when no RectSL root claims it any more.
 */
fun rectSLTopologyNeedsUpdating(values: Collection<Box>) {
    applyRuntimeToggle(
        boxes = values,
        isRoot = { it.isMainLanguage(RectSLPrograms.LANGUAGE) },
        runtimeClass = RectSLExecution::class,
        newExecution = { child -> RectSLExecution(child) },
        language = "RectSL",
        customization = "",
        notifyCategory = RectSLPrograms.LANGUAGE,
        onAttach = { child ->
            if ((child.getPropertyRaw("default_property", null) as? String).isNullOrBlank()) {
                child.setPropertyRaw("default_property", RectSLPrograms.PROPERTY, doResync = true)
            }
            if (child.getPropertyRaw(RectSLPrograms.PROPERTY, null) == null) child.setPropertyRaw(RectSLPrograms.PROPERTY, RECTSL_STARTER, doResync = true)
            // Field's autorun pass may run before this box became RectSL, so an autorun scene starts
            // itself — once: attach is repeated on every graph change.
            if (child.autorun.isTrue() && child.getPropertyRaw("_rectSLAutostarted", null) != true) {
                child.properties["_rectSLAutostarted"] = true
                inMain { delay(AUTOSTART_DELAY_MS); child.begin() }
            }
        },
        onDetach = { child -> runCatching { RectSLPrograms.live(child).hide() } },
    )
}
