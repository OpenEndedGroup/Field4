@file:afield.CompletionCategory("Graphics")

package afield.stdlib

import afield.Box
import afield.four.modules.ShaderInterfaceTransition
import afield.logger.lprintln
import java.util.concurrent.ConcurrentHashMap

/**
 * What the last full shader reload of a box did to its interface, said where the person editing it is
 * looking.
 *
 * Two places. On the shader property, a line note ([LineNotes]) — the summary on the property's first
 * line and on each line that declares a binding the reload changed: "uniform block `Light` grew 16→32
 * bytes, buffer reallocated; push constant `tint` removed". It is a note rather than an error marker
 * because the reload succeeded; nothing there is wrong. In the box's `code`, an error marker on each line
 * that names a binding or member the reload removed, renamed or retyped ([markShaderReferenceLines]):
 * that code was compiled against the old preamble, and the next run will compile it against the new
 * one, so the marker says so and suggests re-running.
 *
 * A reload with no interface change clears the note; the markers go the way error markers go, on the
 * box's next successful run.
 */
object ShaderTransitionNotes {
    private class Recorded(val summary: String, val changedNames: Set<String>, val details: Map<String, String>)

    private val byBoxProperty = ConcurrentHashMap<String, Recorded>()
    private val registered = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun key(box: Box, property: String) = box.id + "\u001f" + property

    /** The summary the last reload of [box] recorded against [property], or null. */
    fun summary(box: Box, property: String): String? = byBoxProperty[key(box, property)]?.summary

    /**
     * Record [transition] as what the reload just did to [box]'s shader [properties] (the properties the
     * person edits — `fragment`, or `fragment_s`, or the shared `vertex_fragment`), log its summary, and
     * mark the lines of `code` that use a name it made stale. [carryNotes] are the values the reload
     * could not carry. Returns the summary, `""` when nothing changed.
     */
    fun record(box: Box, properties: Collection<String>, transition: ShaderInterfaceTransition?, carryNotes: List<String> = emptyList()): String {
        ensureRegistered()
        val summary = listOfNotNull(transition?.summary()?.takeIf { it.isNotBlank() }, carryNotes.takeIf { it.isNotEmpty() }?.joinToString("; "))
            .joinToString("; ")
        properties.forEach { property ->
            if (summary.isBlank()) byBoxProperty.remove(key(box, property))
            else byBoxProperty[key(box, property)] = Recorded(
                summary,
                transition?.changes?.flatMap { listOf(it.name, it.oldName) + it.members.map { m -> m.name } }?.toSet().orEmpty(),
                transition?.staleNames.orEmpty(),
            )
        }
        if (summary.isNotBlank()) lprintln("info,graphics", "shader reload of ${box.name}: $summary")
        val stale = transition?.staleNames.orEmpty()
        if (stale.isNotEmpty()) markShaderReferenceLines(box, stale.mapValues { (_, why) -> "$why by the last shader reload" })
        return summary
    }

    private fun ensureRegistered() {
        if (!registered.compareAndSet(false, true)) return
        val shaderProperties = listOf(
            "vertex", "fragment", "vertex_fragment", "compute", "task", "mesh",
            "raygen", "miss", "closesthit", "anyhit", "intersection", "callable",
            "vertex_s", "fragment_s", "compute_s", "task_s", "mesh_s",
        )
        shaderProperties.forEach { property ->
            LineNotes.register(property, { box -> byBoxProperty.containsKey(key(box, property)) }) { box, source, line ->
                val recorded = byBoxProperty[key(box, property)] ?: return@register null
                val text = source.lines().getOrNull(line - 1) ?: return@register null
                val declares = recorded.changedNames.any { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(text) }
                if (line != 1 && !declares) null
                else LineNotes.Note("Last shader reload: ${recorded.summary}", volatile = true)
            }
        }
    }
}

/** The 1-based lines of [code] that use [name] as a member — `.name` — or by key, `["name"]`; comments are ignored. */
internal fun kotlinLinesReferencing(code: String, name: String): List<Int> {
    val n = Regex.escape(name)
    val pattern = Regex("""\.\s*$n\b|\[\s*"$n"\s*]""")
    return code.lines().mapIndexedNotNull { index, line ->
        if (pattern.containsMatchIn(line.substringBefore("//"))) index + 1 else null
    }
}

/**
 * Put an error marker on each line of [box]'s `code` that uses one of [names] — keyed by the shader
 * name, valued by why it no longer means what it did — with a suggestion to re-run. At most a dozen
 * lines per name, so a common member name cannot paint the whole box. Returns how many were marked.
 */
fun markShaderReferenceLines(box: Box, names: Map<String, String>): Int {
    val code = box.getPropertyRaw("code", null) as? String ?: return 0
    var marked = 0
    names.forEach { (name, why) ->
        kotlinLinesReferencing(code, name).take(12).forEach { line ->
            runCatching {
                box.error("$why. This line uses `$name`, which was generated for the shader's old interface; re-run the box to compile it against the new one.", "code", line)
            }
            marked++
        }
    }
    return marked
}
