package afield.runtime

import afield.Box
import afield.BoxExecution
import afield.KotlinExecution
import afield.remotejs.code_language
import afield.logger.lblock
import afield.inMain
import afield.notify
import afield.offline
import afield.sessions
import kotlinx.coroutines.delay
import kotlinx.coroutines.yield
import org.jetbrains.kotlin.util.capitalizeDecapitalize.capitalizeAsciiOnly
import java.util.WeakHashMap
import kotlin.reflect.KClass

class TopologyWatcher(
    private val label: String,
    private val intervalMs: Long = 5L,
    private val onTopologyChanged: (Collection<Box>) -> Unit
) {
    private val lastGeneration = WeakHashMap<Any, Int>()

    fun start() = inMain {
        while (true) {
            sessions.entries.forEach { entry ->
                val current = entry.value.topologyGeneration
                val prev = lastGeneration.put(entry.key, current)
                if (prev == null || prev != current) {
                    onTopologyChanged(entry.value.boxes.values)
                }
            }
            if (intervalMs <= 0) {
                yield()
            } else {
                delay(intervalMs)
            }
        }
    }
}

fun applyRuntimeToggle(
    boxes: Collection<Box>,
    isRoot: (Box) -> Boolean?,
    runtimeClass: KClass<out BoxExecution>,
    newExecution: (Box) -> BoxExecution,
    language: String,
    customization: String,
    notifyCategory: String,
    onAttach: (Box) -> Unit = {},
    onDetach: (Box) -> Unit = {}
) {
    boxes.forEach { root ->
        val targetLanguage = language.capitalizeAsciiOnly()
        if (isRoot(root) == true) {
            root.children.forEach { child ->
                if (runtimeClass.isInstance(child.ke)) {
                    child.offline = false
                    onAttach(child)
                    return@forEach
                }
                child.ke = newExecution(child)
                child.customization = customization
                child.code_language = language
                child.offline = false
                onAttach(child)
                notify("'${child.name}' becomes $targetLanguage", notifyCategory)
            }
        } else {
            root.children.forEach { child ->
                if (runtimeClass.isInstance(child.ke)) {
                    onDetach(child)
                    child.ke = KotlinExecution()
                    child.customization = """this.style.border = """""
                    child.code_language = "Kotlin"
                    notify("'${child.name}' no longer $targetLanguage", notifyCategory)
                }
            }
        }
    }
}
