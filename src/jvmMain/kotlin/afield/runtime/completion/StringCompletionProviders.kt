package afield.runtime.completion

import afield.document.graph.Box
import afield.document.graph.isTrue

/**
 * [StringCompletions] providers for strings that name parts of the open document: boxes, output
 * channels and the variables a box can find with `fromContextWithNameOrNull`. Field's own API uses
 * them (`named("…")`, `display("…", …)`), and your `src/personal` code, or a class declared in a
 * box, can name them in a [CompletesWith] of its own:
 *
 * ```kotlin
 * fun Box.boxCalled(@CompletesWith(BoxNames::class) name: String): Box = named(name)
 * ```
 */

/** The names of the boxes in the document being edited, `named("…")`'s argument. */
object BoxNames : StringCompletions {
    override fun candidates(context: StringCompletionContext): List<String> {
        val box = context.receiver as? Box ?: context.box
        return documentBoxes(box)
            .filter { !it.getPropertyRaw("_tombstone").isTrue() }
            .map { it.name }
            .filter { it.isNotBlank() }
            .sorted()
    }
}

/**
 * Output channels, `display("…", …)`'s first argument: the channels the box already shows, then
 * channels other boxes in the document use, then the default `output`.
 */
object OutputChannels : StringCompletions {
    override fun candidates(context: StringCompletionContext): List<String> {
        val box = context.receiver as? Box ?: context.box
        val others = documentBoxes(box).asSequence()
            .filter { it !== box }
            .flatMap { runCatching { it.output.retainedAreas.channels() }.getOrDefault(emptyList()) }
            .sorted()
        return (box.output.retainedAreas.channels() + others + "output").distinct()
    }
}

/**
 * Variables the box being edited can reach with `fromContextWithNameOrNull("…")`: the top-level
 * variables of its parents, then of their parents, nearest generation first. Its own variables are
 * left out, as the lookup leaves them out unless asked.
 */
object ContextVariableNames : StringCompletions {
    override fun candidates(context: StringCompletionContext): List<String> {
        val names = LinkedHashSet<String>()
        val visited = HashSet<Box>()
        visited += context.box
        var generation: List<Box> = context.box.parents.toList()
        while (generation.isNotEmpty()) {
            generation = generation.filter { visited.add(it) }
            generation.forEach { b -> b.liveKi?.context?.vars?.keys?.let(names::addAll) }
            generation = generation.flatMap { it.parents }
        }
        return names.toList()
    }
}

/** Every box in [box]'s document, or none for a box that is not in one yet. */
private fun documentBoxes(box: Box): Collection<Box> = runCatching { box.root.children.toList() }.getOrDefault(emptyList())
