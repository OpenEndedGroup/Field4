package afield.runtime

import afield.Box
import afield.BoxExecution
import afield.RunsAt
import afield.early
import afield.newRoot
import afield.notify
import afield.stdlib.Action
import afield.stdlib.Delegation
import afield.stdlib.actions
import kotlin.reflect.KClass

data class MainLanguageRuntime(
    val id: String,
    val label: String,
    val runtimeClass: KClass<out BoxExecution>,
    val setLegacyRoot: (Box, Boolean) -> Unit,
    val applyTopology: (Collection<Box>) -> Unit,
)

var Box.mainLanguage by Delegation<String?>()

private val registeredMainLanguages = linkedMapOf<String, MainLanguageRuntime>()

fun registerMainLanguageRuntime(runtime: MainLanguageRuntime) {
    registeredMainLanguages[runtime.id] = runtime
}

/**
 * Apply every registered language root synchronously.
 *
 * The UI's [TopologyWatcher] normally does this on the runloop after a graph edit. Semantic
 * automation can connect a language root and execute its child in the same macro batch, so it calls
 * this dispatch barrier before execute/launch rather than racing the asynchronous watcher.
 */
fun refreshMainLanguageRuntimes(boxes: Collection<Box>) {
    registeredMainLanguages.values.forEach { it.applyTopology(boxes) }
}

fun Box.isMainLanguage(id: String): Boolean =
    mainLanguage.equals(id, ignoreCase = true)

@RunsAt(early)
fun installMainLanguageProperty() {
    Box.ioPersistentProperties.add("mainLanguage")
}

@RunsAt(newRoot)
fun installMainLanguageActions(root: Box) {
    root.actions["set-main-language-kotlin"] = Action(
        desc = "Set Main Language: Kotlin",
        detail = "Use standard Kotlin execution for children of the selected language root box.",
    ) { selection ->
        setMainLanguageFor(selection.ifEmpty { listOf(root) }, null)
    }

    registeredMainLanguages.values.forEach { runtime ->
        root.actions["set-main-language-${runtime.id}"] = Action(
            desc = "Set Main Language: ${runtime.label}",
            detail = "Make children of the selected language root box run as ${runtime.label}.",
        ) { selection ->
            setMainLanguageFor(selection.ifEmpty { listOf(root) }, runtime)
        }
    }
}

private fun setMainLanguageFor(roots: List<Box>, runtime: MainLanguageRuntime?) {
    roots.forEach { root ->
        registeredMainLanguages.values.forEach { it.setLegacyRoot(root, false) }
        root.mainLanguage = runtime?.id
        runtime?.setLegacyRoot?.invoke(root, true)
        registeredMainLanguages.values.forEach { it.applyTopology(listOf(root)) }
        notify(
            if (runtime == null) {
                "'${root.name}' children use Kotlin"
            } else {
                "'${root.name}' children use ${runtime.label}"
            },
            "language",
        )
    }
}
