package afield.four.util

import afield.four.Renderer
import afield.four.modules.fromContext
import org.lwjgl.glfw.GLFW


/** Result contract for "down" handlers. */
sealed class DownResult {
    /** Keep the handler registered (default behavior). */
    data object Keep : DownResult()

    /** Unregister the handler after this down event. */
    data object Remove : DownResult()

    /**
     * Register a one-shot callback that will be invoked the next time the same key/button goes UP
     * (i.e., the matching release for this specific press). The handler itself remains registered.
     */
    class UntilUp(val onUp: () -> Unit) : DownResult()
}


class KeyEvents(val renderer: Renderer = fromContext(), val name: String = "__keyevents__") {
    companion object {
        val namesToGLFW = mutableMapOf<String, Int>()
        val GLTFToNames = mutableMapOf<Int, String>()

        init {
            GLFW::class.java.declaredFields.filter { it.type == Integer.TYPE && it.name.lowercase().contains("_key_") }
                .associate { it.name.lowercase().replace("glfw_key_", "") to it.get(null) as Int }.forEach { (k, v) ->
                    namesToGLFW[k] = v
                    GLTFToNames[v] = k
                }
        }
    }

    // Existing API kept, but underlying type is now () -> DownResult
    private val down = mutableMapOf<String, MutableMap<String, () -> DownResult>>()
    private val up = mutableMapOf<String, MutableMap<String, () -> Boolean>>()

    // Per-press, transient onUp callbacks created by down-handlers
    private val pendingUp = mutableMapOf<String, MutableList<() -> Unit>>()


    fun onDownAndUp(key: String, name: String, callback: () -> DownResult) {
        down.computeIfAbsent(key) { mutableMapOf() }[name] = callback
    }

    // === Registration (backwards compatible) ===
    fun onDownUntilUp(key: String, name: String, onUp: () -> Unit) {
        onDownAndUp(key, name) { DownResult.UntilUp(onUp) }
    }

    fun onDown(key: String, name: String, callback: () -> Boolean) {
        onDownAndUp(key, name) { if (callback()) DownResult.Keep else DownResult.Remove }
    }

    fun onUp(key: String, name: String, callback: () -> Boolean) {
        up.computeIfAbsent(key) { mutableMapOf() }[name] = callback
    }

    private val wasDown = mutableSetOf<String>()

    init {
        renderer.window.onPoll[name] = sequence {
            while (true) {
                val keys: Set<String> = down.keys + up.keys
                val nowDown = mutableSetOf<String>()

                keys.forEach { n ->
                    namesToGLFW[n]?.let { if (renderer.window.isKeyPressed(it)) nowDown.add(n) }
                        ?: println(" -- warning, no such key: $n")
                }

                val newDown = nowDown - wasDown
                val newUp = wasDown - nowDown

                // Fire DOWN; collect UntilUp callbacks; decide handler retention
                newDown.forEach { n ->
                    down[n]?.let { map ->
                        val toKeep = mutableListOf<Pair<String, () -> DownResult>>()
                        map.forEach { (k, cb) ->
                            when (val r = cb()) {
                                DownResult.Keep -> toKeep += k to cb
                                DownResult.Remove -> { /* drop */
                                }

                                is DownResult.UntilUp -> {
                                    pendingUp.getOrPut(n) { mutableListOf() }.add(r.onUp)
                                    toKeep += k to cb  // keep handler for future presses
                                }
                            }
                        }
                        // replace with only those retained
                        map.clear()
                        toKeep.forEach { (k, cb) -> map[k] = cb }
                    }
                }

                // Fire transient onUp from down-handlers first
                newUp.forEach { n ->
                    pendingUp.remove(n)?.forEach { it.invoke() }
                }

                // Then fire normal UP handlers; allow self-unregister via Boolean
                newUp.forEach { n ->
                    up[n]?.let { map ->
                        map.values.retainAll { it.invoke() }
                    }
                }

                wasDown.clear()
                wasDown.addAll(nowDown)

                yield(true)
            }
        }
    }
}
