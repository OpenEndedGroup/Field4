@file:afield.runtime.completion.CompletionCategory("Workspace")

package afield.runtime.languages

import afield.data.collections.get
import afield.data.collections.invoke
import afield.data.collections.s
import afield.data.collections.times
import afield.data.graph.main
import afield.document.context.why
import afield.document.graph.minusAssign
import afield.document.graph.named
import afield.document.graph.plusAssign
import afield.document.properties.compareTo
import afield.document.properties.prop
import afield.document.workspace.file
import afield.graphics.render.rangeTo
import afield.graphics.shader.miss
import afield.io.osc.main
import afield.lang.stacksl.evolution.render
import afield.math.autodiff.compareTo
import afield.math.autodiff.div
import afield.math.autodiff.minus
import afield.math.autodiff.plus
import afield.math.autodiff.times
import afield.math.control.main
import afield.math.linalg.d
import afield.math.linalg.invoke
import afield.math.linalg.remAssign
import afield.math.linalg.set
import afield.math.linalg.times
import afield.math.statistics.main
import afield.math.units.div
import afield.math.units.minus
import afield.math.units.plus
import afield.math.units.times
import afield.runtime.execution.start
import afield.runtime.lifecycle.once
import afield.simulation.automata.main

import afield.animation.keyframes.h
import afield.audio.synth.*
import afield.graphics.render.label
import afield.graphics.vulkan.buffers.add
import afield.lang.stacksl.h
import afield.math.linalg.x
import afield.media.image.open
import afield.study.source.BoxStudies
import afield.ui.output.h
import afield.document.graph.Box
import afield.app.protocol.IsRunning
import afield.app.protocol.Message
import afield.app.protocol.Rect
import afield.document.graph.RunningState
import afield.document.graph.captureLaunchEpoch
import afield.document.graph.registerLaunchJob
import afield.runtime.lifecycle.Runloop
import afield.app.server.disposeBoxRenderer
import afield.graphics.vulkan.Renderer
import afield.graphics.vulkan.util.Window
import afield.runtime.lifecycle.inMain
import afield.app.diagnostics.logging.lprintln
import afield.app.protocol.payload
import afield.runtime.isMainLanguage
import afield.runtime.mainLanguage
import afield.app.protocol.send

/**
 * The roots of the small languages that draw in a window — RectSL and StackSL — made and given a
 * window by the language, so that starting one takes a command rather than Kotlin.
 *
 * [adopt] is "Make a RectSL Scene" and "Make a StackSL Program": the selected box goes beneath a root of
 * that language — the one already above it, or a new one placed just above it — and becomes a scene.
 *
 * A box marked "Set Main Language: RectSL" (or StackSL) is the root its scenes draw beneath. When a
 * scene starts and there is no running window above it, [ensure] opens one on that root — a `Window`
 * and a `Renderer` held in the root's `window` and `renderer` properties, exactly where
 * `prop<Renderer>(renderer)` would have put them, with the render loop running as the root's job. So the
 * root shows as running, stopping it closes the window (and, as for any renderer box, stops the scenes
 * drawing in it first), and closing the window stops them too; running a scene again reopens it.
 *
 * A root that holds Kotlin of its own is left to open its own window: that Kotlin may be about to, and
 * two render loops on one renderer would fight. [ensure] only says so.
 */
object LanguageRoots {
    /** The nearest box above [box], walking up its parents, marked with main language [language]. */
    fun rootOf(box: Box, language: String): Box? {
        val seen = HashSet<Box>()
        val queue = ArrayDeque(box.parents.toList())
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (!seen.add(next)) continue
            if (next.isMainLanguage(language)) return next
            queue.addAll(next.parents)
        }
        return null
    }

    /**
     * Make sure [box] has a window to draw in: the running renderer above it, or a new window on its
     * [language] root, [title]d after the root and placed at [dimensions]. Call on the main thread, when
     * the box starts — never from a loop that follows it, or closing the window would reopen it at once.
     *
     * Returns null when there is a window, otherwise why there is none.
     */
    fun ensure(box: Box, language: String, label: String, dimensions: Rect): String? {
        check(Runloop.isMainThread) { "LanguageRoots.ensure must run on the main thread" }
        if (BoxStudies.rendererAbove(box)?.takeIf { it.windows.any { w -> !w.shouldClose() } } != null) return null
        val root = rootOf(box, language) ?: return "no window: '${box.name}' is not beneath a $label root"
        if (!(root.getPropertyRaw("code", "") as? String).isNullOrBlank()) {
            return "no window: run '${root.name}', whose Kotlin opens this box's window"
        }
        // A window the person closed leaves its renderer behind; take it down before opening another.
        if (root.getPropertyRaw("renderer", null) is Renderer) disposeBoxRenderer(root)
        return try {
            open(root, root.name.ifBlank { "$label window" }, dimensions)
            null
        } catch (e: Throwable) {
            lprintln("warning,study", "opening a window for '${root.name}' failed: $e")
            "no window: opening one failed: ${e.message ?: e}"
        }
    }

    /**
     * Put each of [boxes] beneath a [language] root and return the scenes to start: a box already
     * beneath one stays there, a box that is not gets a new root named [rootName] placed just above it,
     * and a selected root gets a new scene beneath it. Each scene is marked autorun, so it plays when its
     * document opens, and given [starter] in [property] if that is empty. Call on the main thread.
     */
    fun adopt(boxes: List<Box>, language: String, rootName: String, sceneName: String, property: String, starter: String): List<Box> {
        val scenes = boxes.mapNotNull { box ->
            val session = if (box.isInsideInitialized()) box.inside else return@mapNotNull null
            when {
                box.isMainLanguage(language) -> newBox(session, sceneName, below(box.frame, 360.0, 160.0)).also { box.connect(it) }
                rootOf(box, language) != null -> box
                else -> {
                    val root = newBox(session, rootName, Rect(box.frame.x, box.frame.y - 80.0, 220.0, 50.0))
                    root.mainLanguage = language
                    root.connect(box)
                    box
                }
            }
        }
        scenes.forEach { scene ->
            if ((scene.getPropertyRaw(property, "") as? String).isNullOrBlank()) scene.setPropertyRaw(property, starter, doResync = true)
            scene.setPropertyRaw("default_property", property, doResync = true)
            scene.setPropertyRaw("autorun", true, doResync = true)
        }
        // Give the scenes their language now, rather than on the watcher's next pass, so they can start.
        afield.runtime.refreshMainLanguageRuntimes(scenes.flatMap { it.parents + it })
        afield.document.graph.doResync()
        return scenes
    }

    private fun below(frame: Rect, w: Double, h: Double) = Rect(frame.x, frame.y + frame.h + 40.0, w, h)

    private fun newBox(session: afield.app.server.SessionInformation, name: String, frame: Rect): Box {
        val made = afield.document.graph.box(java.util.UUID.randomUUID().toString(), createIfMissing = true, initIfCreated = false, root = session)
        made.name = name
        made.frame = frame
        afield.document.graph.needsResync.add(session.root to "children")
        made.resyncAll()
        session.topologyGeneration++
        return made
    }

    private fun open(root: Box, title: String, dimensions: Rect) {
        val renderer = Renderer(Window(title, initialDimensions = dimensions))
        root.setPropertyRaw("window", renderer.window, doResync = true)
        root.setPropertyRaw("renderer", renderer, doResync = true)
        val strand = "default"
        val generation = (root.runGeneration[strand] ?: -1) + 1
        root.runGeneration[strand] = generation
        root.currentRunningStates[strand] = RunningState(generation, MODE)
        send(Message("is-running").payload(IsRunning(root.id, true, generation, strand, MODE)))
        val epoch = root.captureLaunchEpoch()
        val job = inMain {
            try {
                renderer.run()
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) lprintln("warning,study", "the window of '${root.name}' failed: $t")
            } finally {
                if (root.currentRunningStates[strand]?.watermark == generation) root.currentRunningStates.remove(strand)
                send(Message("is-running").payload(IsRunning(root.id, false, generation, strand, MODE)))
                // The person closed the window: stop what draws in it and release it, as stopping the root would.
                if (root.getPropertyRaw("renderer", null) === renderer && renderer.windows.all { it.shouldClose() }) disposeBoxRenderer(root)
            }
        }
        root.registerLaunchJob(job, epoch) // the stop fence, not `jobs +=`: a stop in between cannot miss it
    }

    /** The running mode a language's own window reports. */
    private const val MODE = "language-window"
}
