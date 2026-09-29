@file:afield.CompletionCategory("Workspace")

package afield.stdlib

import afield.Box
import afield.ContextResolution
import afield.ContextWhy
import afield.RunsAt
import afield.ai.core.AgentToolExtensions
import afield.ai.core.SummarisesForToolLog
import afield.plugins
import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription

/** The AI4 tool that explains where a box's inherited or context value comes from ([ContextResolution.explain]). */
@JsonClassDescription(
    "Explain why a value a box's code reads resolves where it does, without running anything: for a name " +
        "(an @exported or prop declaration, e.g. `_speed`, else a plain name as fromContextWithNameOrNull reads it) " +
        "or a type (as fromContext<T>() finds it) — which box supplies it, through which path of parent boxes, " +
        "whether it was a property or a live variable, which other boxes' values it shadows, whether an " +
        "equally near box supplies a different value (an ambiguity), and which preamble line declared the name " +
        "and which box that line came from."
)
data class WhyDoesThisResolve(
    @JsonPropertyDescription("The name the code reads, e.g. `_speed`, `renderer`. Give this or `type`.")
    val name: String? = null,
    @JsonPropertyDescription("A class name, fully qualified or simple (e.g. `afield.four.Renderer`), to resolve by type.")
    val type: String? = null,
    @JsonPropertyDescription("The box asking, by uid or name; the focused box when omitted.")
    val box: String? = null,
)

/** [WhyDoesThisResolve]'s answer: one sentence, and the whole explanation. */
data class WhyDoesThisResolveResult(val summary: String, val explanation: Map<String, Any?>) : SummarisesForToolLog {
    override fun toolLogSummary(): String = summary.take(160)
}

/** Offer [WhyDoesThisResolve] to any conversation focused on a box. */
@RunsAt(plugins)
fun installContextWhyTools() {
    AgentToolExtensions.register(WhyDoesThisResolve::class.java, { it.focus != null }, "afield.stdlib.context-why") { ctx, request ->
        val focus = ctx.focus ?: throw IllegalStateException("WhyDoesThisResolve works from the box whose chat is open")
        val box = request.box?.takeIf { it.isNotBlank() }?.let { findBox(focus.box, it) } ?: focus.box
        whyDoesThisResolve(box, request.name, request.type)
    }
}

/** [WhyDoesThisResolve] for [box]. */
fun whyDoesThisResolve(box: Box, name: String?, type: String?): WhyDoesThisResolveResult {
    require(!name.isNullOrBlank() || !type.isNullOrBlank()) { "give a name or a type" }
    val json = ContextResolution.explain(box, name?.takeIf { it.isNotBlank() }, type?.takeIf { it.isNotBlank() })
    @Suppress("UNCHECKED_CAST")
    val plain = ContextWhy.plain(json) as Map<String, Any?>
    return WhyDoesThisResolveResult(plain["summary"] as? String ?: "", plain)
}

private fun findBox(from: Box, token: String): Box {
    val all = if (from.isInsideInitialized()) from.inside.boxes.values else listOf(from)
    all.firstOrNull { it.id == token }?.let { return it }
    val named = all.filter { it.name == token }
    return named.singleOrNull() ?: throw IllegalArgumentException(
        if (named.isEmpty()) "no box '$token' in this document" else "'$token' names ${named.size} boxes; give a uid"
    )
}
