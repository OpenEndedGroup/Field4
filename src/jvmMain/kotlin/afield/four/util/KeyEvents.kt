package afield.four.util

import afield.four.Renderer
import afield.four.modules.fromContext
import afield.logger.lprintln
import org.lwjgl.glfw.GLFW
import afield.Generator
import afield.GeneratorScope

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
    data class OnUp(val onUp: () -> Unit) : DownResult()

    /**
     * Register a per-frame generator that will be stepped every poll.
     * The Boolean input is `true` while the key is down, `false` after release.
     * The generator is driven until completion.
     */
    data class WhileDown(val generator: Generator<Boolean, Boolean>) : DownResult() {
        constructor(g : suspend GeneratorScope<Boolean, Boolean>.() -> Unit) : this(Generator(g))
    }
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
    // Per-press, transient per-frame generators created by down-handlers
    private val pendingGenerators = mutableMapOf<String, MutableList<Generator<Boolean, Boolean>>>()


    fun onDownAndUp(key: String, name: String, callback: () -> DownResult) {
        down.computeIfAbsent(key) { mutableMapOf() }[name] = callback
    }

    // === Registration (backwards compatible) ===
    fun onDownUntilUp(key: String, name: String, onUp: () -> Unit) {
        onDownAndUp(key, name) { DownResult.OnUp(onUp) }
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
                        ?: lprintln("warning,input", "no such key: $n")
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

                                is DownResult.OnUp -> {
                                    pendingUp.getOrPut(n) { mutableListOf() }.add(r.onUp)
                                    toKeep += k to cb  // keep handler for future presses
                                }

                                is DownResult.WhileDown -> {
                                    val gen = r.generator
                                    val first = gen.start()
                                    if (!gen.isCompleted) {
                                        pendingGenerators.getOrPut(n) { mutableListOf() }.add(gen)
                                    }
                                    // ignore first output
                                    toKeep += k to cb  // keep handler for future presses
                                }
                            }
                        }
                        // replace with only those retained
                        map.clear()
                        toKeep.forEach { (k, cb) -> map[k] = cb }
                    }
                }

                // Step per-frame generators for keys that have generators (down or released)
                pendingGenerators.entries.toList().forEach { (key, gens) ->
                    val input = nowDown.contains(key)
                    val toRemove = mutableListOf<Generator<Boolean, Boolean>>()
                    gens.forEach { g ->
                        if (!g.isCompleted) {
                            g.next(input)
                        }
                        if (g.isCompleted) toRemove += g
                    }
                    gens.removeAll(toRemove)
                    if (gens.isEmpty()) pendingGenerators.remove(key)
                }

                // Fire transient onUp from down-handlers first
                newUp.forEach { n ->
                    pendingGenerators.remove(n) // remaining gens will be driven above with input=false
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
