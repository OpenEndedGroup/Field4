package afield.four.util

import afield.Box
import afield.four.Renderer
import afield.four.modules.fromContext
import afield.stdlib.quat
import afield.stdlib.times
import afield.stdlib.rem
import org.joml.*
import org.lwjgl.glfw.GLFW
import java.awt.event.KeyEvent
import kotlin.collections.set
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty


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

    var down = mutableMapOf<String, MutableMap<String, () -> Boolean>>()
    var up = mutableMapOf<String, MutableMap<String, () -> Boolean>>()

    fun onDown(key: String, name: String, callback: () -> Boolean) {
        down.computeIfAbsent(key) { mutableMapOf() }[name] = callback
    }

    fun onUp(key: String, name: String, callback: () -> Boolean) {
        up.computeIfAbsent(key) { mutableMapOf() }[name] = callback
    }

    val wasDown = mutableSetOf<String>()

    init {
        renderer.window.onPoll[name] = sequence {
            while (true) {

                val keys: Set<String> = down.keys + up.keys

                val nowDown = mutableSetOf<String>()

                keys.forEach { name ->
                    namesToGLFW[name]?.let {
                        if (renderer.window.isKeyPressed(it))
                            nowDown.add(name)
                        else {
                        }
                    } ?: println(" -- warning, no such key: $name")

                }

                val newDown = nowDown - wasDown
                val newUp = wasDown - nowDown

                newDown.forEach { name ->
                    down[name]?.let { map ->
                        map.values.retainAll {
                            it.invoke()
                        }
                    }
                }

                newUp.forEach { name ->
                    up[name]?.let { map ->
                        map.values.retainAll {
                            it.invoke()
                        }
                    }
                }

                yield(true)
            }
        }
    }

}