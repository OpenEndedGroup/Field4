package afield.four.util

import afield.Generator
import afield.four.Renderer
import afield.four.modules.fromContext
import afield.stdlib.Vec2
import afield.logger.lprintln
import org.lwjgl.glfw.GLFW
import kotlin.collections.set

data class MouseEvent(
    val pos: Vec2,
    val delta: Vec2 = Vec2(),
    val scroll: Vec2 = Vec2(),
    val buttonsDown: Set<String> = emptySet(),
    val button: String? = null,
    val type: Type
) {
    enum class Type { MOVE, DOWN, UP, DRAG, SCROLL }
    fun isDown(name: String) = buttonsDown.contains(name)
}

class MouseEvents(
    val renderer: Renderer = fromContext(),
    val name: String = "__mouseevents__"
) {
    companion object {
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
    fun onDrag(name: String, callback: (MouseEvent) -> Boolean) { drag[name] = callback }
    fun onScroll(name: String, callback: (MouseEvent) -> Boolean) { scroll[name] = callback }

    private val wasDown = mutableSetOf<String>()
    private var lastPos = Vec2()

    init {
        lastPos.set(renderer.window.cursor)

        renderer.window.onPoll[name] = sequence {
            while (true) {
                val pos = Vec2(renderer.window.cursor)
                val delta = Vec2(pos).sub(lastPos)
                lastPos.set(pos)

                val scrollDelta = renderer.window.consumeScrollDelta()

                val buttonsRequested: Set<String> = (down.keys + up.keys).toSet()
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
                        val ev = MouseEvent(pos, delta, Vec2(), buttonsDownSnapshot, null, MouseEvent.Type.MOVE)
                        move.values.retainAll { it(ev) }
                    }
                }

                if ((delta.x != 0f || delta.y != 0f) && nowDown.isNotEmpty() && drag.isNotEmpty()) {
                    val ev = MouseEvent(pos, delta, Vec2(), buttonsDownSnapshot, null, MouseEvent.Type.DRAG)
                    drag.values.retainAll { it(ev) }
                }

                // DOWN: fire; collect UntilUp; decide handler retention
                newDown.forEach { bName ->
                    down[bName]?.let { map ->
                        val toKeep = mutableListOf<Pair<String, (MouseEvent) -> DownResult>>()
                        val ev = MouseEvent(pos, Vec2(), Vec2(), buttonsDownSnapshot, bName, MouseEvent.Type.DOWN)
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
                        val ev = MouseEvent(pos, Vec2(), Vec2(), buttonsDownSnapshot, bName, MouseEvent.Type.UP)
                        pendingGenerators.remove(bName) // remaining gens driven above with input=false
                        pendingUp.remove(bName)?.forEach { it(ev) }
                        up[bName]?.let { map ->
                            map.values.retainAll { it(ev) }
                        }
                    }
                }

                if (scrollDelta.x != 0f || scrollDelta.y != 0f) {
                    if (scroll.isNotEmpty()) {
                        val ev = MouseEvent(pos, Vec2(), scrollDelta, buttonsDownSnapshot, null, MouseEvent.Type.SCROLL)
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
