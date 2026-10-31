package afield.graphics.vulkan.util

/**
 * Mouse input from a Vulkan renderer's window, delivered to named callbacks.
 *
 * [MouseEvents] registers handlers for down, up, move, drag and scroll, keyed by a name so that re-running a box replaces its handler rather than adding another. Each handler receives a [MouseEvent] with position, delta, scroll, buttons held, and the window size.
 */

import afield.runtime.lifecycle.Generator
import afield.graphics.vulkan.Renderer
import afield.document.context.fromContext
import afield.app.diagnostics.logging.lprintln
import afield.math.linalg.Vec2
import org.lwjgl.glfw.GLFW

data class MouseEvent(
    /** Where the pointer is, in points from the window's top-left corner — the same units as [windowSize], not framebuffer pixels. */
    val pos: Vec2,
    /** How far the pointer moved since the previous poll, in the same points. Only [Type.MOVE] and [Type.DRAG] carry one; a press, release or scroll arrives with zero. */
    val delta: Vec2 = Vec2(),
    /** Wheel and trackpad movement since the previous poll, horizontal then vertical. Only a [Type.SCROLL] event carries one. */
    val scroll: Vec2 = Vec2(),
    /**
     * The buttons held at this poll, under the names `left`, `right`, `middle` and the GLFW numbers.
     * Only buttons some handler asked about are read, so one with no `onDown`, `onUp` or per-button
     * `onDrag` never appears here however hard it is held. An `onDrag` with no button named asks for
     * left, right and middle.
     */
    val buttonsDown: Set<String> = emptySet(),
    /** Which button this event is about. A press, a release and a per-button drag name one; a move, a scroll and an any-button drag leave it null. */
    val button: String? = null,
    /** Which of the five kinds of event this is. */
    val type: Type,
    /** The window's size, in the same units as [pos]: window points, not framebuffer pixels. */
    val windowSize: Vec2 = Vec2(1f, 1f),
) {
    enum class Type { MOVE, DOWN, UP, DRAG, SCROLL }
    fun isDown(name: String) = buttonsDown.contains(name)

    /** [pos] as a fraction of the window: (0, 0) at its top-left corner, (1, 1) at its bottom-right. */
    val uv: Vec2 get() = Vec2(pos.x / windowSize.x, pos.y / windowSize.y)
}

class MouseEvents(
    /** The renderer whose window is polled. This becomes that window's `mouseEvents` if it does not already have one. */
    val renderer: Renderer = fromContext(),
    /**
     * The key this polling takes in the window's poll map, which is not the name a handler is
     * registered under. A second [MouseEvents] built with the same name takes over the polling from
     * the first, so re-running a box leaves one; the `name` passed to `onDown`, `onMove` and the
     * rest is what keeps a box from stacking up duplicate handlers inside it.
     */
    val name: String = "__mouseevents__"
) {
    companion object {
        /** What `onDrag(name)` without a button listens to. */
        private val ANY_DRAG_BUTTONS = setOf("left", "right", "middle")

        val namesToGLFW = mutableMapOf<String, Int>()
        val GLFWToNames = mutableMapOf<Int, String>()

        init {
            GLFW::class.java.declaredFields
                .filter { it.type == Integer.TYPE && it.name.lowercase().contains("_mouse_button_") }
                .associate { it.name.lowercase().replace("glfw_mouse_button_", "") to it.get(null) as Int }
                .forEach { (k, v) ->
                    namesToGLFW[k] = v
                    GLFWToNames[v] = k
                }
            namesToGLFW["left"] = GLFW.GLFW_MOUSE_BUTTON_LEFT
            namesToGLFW["right"] = GLFW.GLFW_MOUSE_BUTTON_RIGHT
            namesToGLFW["middle"] = GLFW.GLFW_MOUSE_BUTTON_MIDDLE
            GLFWToNames[GLFW.GLFW_MOUSE_BUTTON_LEFT] = "left"
            GLFWToNames[GLFW.GLFW_MOUSE_BUTTON_RIGHT] = "right"
            GLFWToNames[GLFW.GLFW_MOUSE_BUTTON_MIDDLE] = "middle"
            for (b in GLFW.GLFW_MOUSE_BUTTON_1..GLFW.GLFW_MOUSE_BUTTON_LAST) {
                val n = GLFWToNames[b] ?: b.toString()
                namesToGLFW.putIfAbsent(n, b)
                GLFWToNames.putIfAbsent(b, n)
            }
        }
    }

    // Down now returns DownResult; keep Boolean overload for compatibility
    private val down = mutableMapOf<String, MutableMap<String, (MouseEvent) -> DownResult>>()
    private val up = mutableMapOf<String, MutableMap<String, (MouseEvent) -> Boolean>>()

    private val move = mutableMapOf<String, (MouseEvent) -> Boolean>()
    private val drag = mutableMapOf<String, (MouseEvent) -> Boolean>()
    private val buttonDrag = mutableMapOf<String, MutableMap<String, (MouseEvent) -> Boolean>>()
    private val scroll = mutableMapOf<String, (MouseEvent) -> Boolean>()

    // Transient per-press onUp callbacks created by down-handlers
    private val pendingUp = mutableMapOf<String, MutableList<(MouseEvent) -> Unit>>()
    // Transient per-press per-frame generators created by down-handlers
    private val pendingGenerators = mutableMapOf<String, MutableList<Generator<Boolean, Boolean>>>()

    // === Registration (backwards compatible) ===
    fun onDown(button: String, name: String, callback: () -> Boolean) =
        onDown(button, name) { _ -> if (callback()) DownResult.Keep else DownResult.Remove }

    fun onDown(button: String, name: String, callback: (MouseEvent) -> DownResult) {
        down.computeIfAbsent(button) { mutableMapOf() }[name] = callback
    }

    /** Handy helper to attach a one-shot onUp for this button press. */
    fun onDownUntilUp(button: String, name: String, onUp: (MouseEvent) -> Unit) =
        onDown(button, name) { ev -> DownResult.OnUp { onUp(ev) } }

    fun onUp(button: String, name: String, callback: () -> Boolean) =
        onUp(button, name) { _ -> callback() }

    fun onUp(button: String, name: String, callback: (MouseEvent) -> Boolean) {
        up.computeIfAbsent(button) { mutableMapOf() }[name] = callback
    }

    fun onMove(name: String, callback: (MouseEvent) -> Boolean) { move[name] = callback }
    /**
     * Calls [callback] whenever the mouse moves with any of the left, right or middle buttons down.
     * Return false to remove it. Needs no `onDown` beside it.
     */
    fun onDrag(name: String, callback: (MouseEvent) -> Boolean) { drag[name] = callback }

    /** Calls [callback] whenever the mouse moves with [button] down. Return false to remove it. */
    fun onDrag(button: String, name: String, callback: (MouseEvent) -> Boolean) {
        buttonDrag.computeIfAbsent(button) { mutableMapOf() }[name] = callback
    }
    fun onScroll(name: String, callback: (MouseEvent) -> Boolean) { scroll[name] = callback }

    private val wasDown = mutableSetOf<String>()
    private var lastPos = Vec2()

    init {
        lastPos.set(renderer.window.cursor)
        if (renderer.window.mouseEvents==null) renderer.window.mouseEvents = this

        renderer.window.onPoll[name] = sequence {
            while (true) {
                val pos = Vec2(renderer.window.cursor)
                val delta = Vec2(pos).sub(lastPos)
                lastPos.set(pos)

                val scrollDelta = renderer.window.consumeScrollDelta()

                // Only the buttons some handler names are polled. A drag handler counts: it used to
                // fire only when an onDown or onUp for the same button happened to be registered.
                val buttonsRequested: Set<String> =
                    (down.keys + up.keys + buttonDrag.keys + (if (drag.isNotEmpty()) ANY_DRAG_BUTTONS else emptySet())).toSet()
                // The cursor is in window points; Window.width/height are the framebuffer's
                // pixels, twice as many on a Retina display, which put uv's far corner at (0.5, 0.5).
                val points = renderer.window.size()
                val size = Vec2(points.w.toFloat(), points.h.toFloat())
                val nowDown = mutableSetOf<String>()

                buttonsRequested.forEach { bName ->
                    val glfw = namesToGLFW[bName]
                    if (glfw != null) {
                        if (renderer.window.isMouseButtonPressed(glfw)) nowDown.add(bName)
                    } else lprintln("warning,input", "no such mouse button: $bName")
                }

                val newDown = nowDown - wasDown
                val newUp = wasDown - nowDown
                val buttonsDownSnapshot = nowDown.toSet()

                if (delta.x != 0f || delta.y != 0f) {
                    if (move.isNotEmpty()) {
                        val ev = MouseEvent(pos, delta, Vec2(), buttonsDownSnapshot, null, MouseEvent.Type.MOVE, size)
                        move.values.retainAll { it(ev) }
                    }
                }

                if ((delta.x != 0f || delta.y != 0f) && nowDown.isNotEmpty()) {
                    if (drag.isNotEmpty()) {
                        val ev = MouseEvent(pos, delta, Vec2(), buttonsDownSnapshot, null, MouseEvent.Type.DRAG, size)
                        drag.values.retainAll { it(ev) }
                    }
                    nowDown.forEach { bName ->
                        buttonDrag[bName]?.let { map ->
                            val ev = MouseEvent(pos, delta, Vec2(), buttonsDownSnapshot, bName, MouseEvent.Type.DRAG, size)
                            map.values.retainAll { it(ev) }
                        }
                    }
                }

                // DOWN: fire; collect UntilUp; decide handler retention
                newDown.forEach { bName ->
                    down[bName]?.let { map ->
                        val toKeep = mutableListOf<Pair<String, (MouseEvent) -> DownResult>>()
                        val ev = MouseEvent(pos, Vec2(), Vec2(), buttonsDownSnapshot, bName, MouseEvent.Type.DOWN, size)
                        map.forEach { (k, cb) ->
                            when (val r = cb(ev)) {
                                DownResult.Keep -> toKeep += k to cb
                                DownResult.Remove -> { /* drop */ }
                                is DownResult.OnUp -> {
                                    pendingUp.getOrPut(bName) { mutableListOf() }.add { upEv ->
                                        r.onUp.invoke()
                                    }
                                    toKeep += k to cb
                                }
                                is DownResult.WhileDown -> {
                                    val gen = r.generator
                                    val first = gen.start()
                                    if (!gen.isCompleted) {
                                        pendingGenerators.getOrPut(bName) { mutableListOf() }.add(gen)
                                    }
                                    // ignore first output
                                    toKeep += k to cb
                                }
                            }
                        }
                        map.clear()
                        toKeep.forEach { (k, cb) -> map[k] = cb }
                    }
                }

                // Step per-frame generators for buttons that have generators (down or released)
                pendingGenerators.entries.toList().forEach { (bName, gens) ->
                    val input = nowDown.contains(bName)
                    val toRemove = mutableListOf<Generator<Boolean, Boolean>>()
                    gens.forEach { g ->
                        if (!g.isCompleted) g.next(input)
                        if (g.isCompleted) toRemove += g
                    }
                    gens.removeAll(toRemove)
                    if (gens.isEmpty()) pendingGenerators.remove(bName)
                }

                // UP: first fire transient per-press callbacks, then regular UP handlers
                if (newUp.isNotEmpty()) {
                    newUp.forEach { bName ->
                        val ev = MouseEvent(pos, Vec2(), Vec2(), buttonsDownSnapshot, bName, MouseEvent.Type.UP, size)
                        pendingGenerators.remove(bName) // remaining gens driven above with input=false
                        pendingUp.remove(bName)?.forEach { it(ev) }
                        up[bName]?.let { map ->
                            map.values.retainAll { it(ev) }
                        }
                    }
                }

                if (scrollDelta.x != 0f || scrollDelta.y != 0f) {
                    if (scroll.isNotEmpty()) {
                        val ev = MouseEvent(pos, Vec2(), scrollDelta, buttonsDownSnapshot, null, MouseEvent.Type.SCROLL, size)
                        scroll.values.retainAll { it(ev) }
                    }
                }

                wasDown.clear()
                wasDown.addAll(nowDown)

                yield(true)
            }
        }
    }
}
