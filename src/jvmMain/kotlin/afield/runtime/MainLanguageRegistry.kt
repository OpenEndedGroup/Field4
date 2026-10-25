package afield.runtime

import afield.document.graph.Box
import afield.runtime.execution.BoxExecution
import afield.runtime.lifecycle.RunsAt
import afield.runtime.lifecycle.early
import afield.runtime.lifecycle.newRoot
import afield.app.notifications.notify
import afield.app.commands.Action
import afield.document.properties.Delegation
import afield.app.commands.actions
import kotlin.reflect.KClass

/**
 * The registry that lets a box choose the language its children run in, such as Python instead of
 * Kotlin.
 *
 * A language runtime registers itself with [registerMainLanguageRuntime]. Every registered runtime
 * then gets a "Set Main Language: ..." command in the palette, next to "Set Main Language: Kotlin".
 * Running one on a selected box records the choice in the box's persistent `mainLanguage` property
 * and converts the box's children to that runtime. Choosing Kotlin clears the property and converts
 * them back.
 */

/** One language a box can give its children: an id, a palette label, and the hooks that convert them. */
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
