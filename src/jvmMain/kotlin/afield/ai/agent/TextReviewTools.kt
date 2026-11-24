package afield.ai.agent

import afield.ai.tools.AgentToolMeta
import afield.ai.tools.ToolEffect
import afield.ai.tools.ToolRegistry
import afield.document.text.TextReviewEdit
import afield.document.text.TextReviewSuggestion
import com.fasterxml.jackson.annotation.JsonClassDescription

@JsonClassDescription("Suggest reviewable prose edits to an existing text frame, particularly when asked to edit, revise or copyedit Markdown. Read the live contents first. Give independent edits against the original Markdown, each with find (exact quote), replace (new Markdown, empty for deletion), optional occurrence (one-based for repeated quotes) optional reason, and optional supersedes (ID of a pending suggestion this revises, preserving its comments and history). For an insertion, include nearby unchanged text in both find and replace. Opens a Review edits affordance on the frame. Contents stay unchanged until the person accepts. Use ListTextReviewRequest to read their comments before proposing a follow-up.")
@AgentToolMeta("text-notes", effects = [ToolEffect.MUTATE_DOCUMENT])
data class SuggestTextEditsRequest(val box: String, val edits: List<TextReviewEdit>)

@JsonClassDescription("Read review suggestions, outcomes and human comments for a text frame before following up on edits. box is a UID or unique name. A pending suggestion with no start is stale. With suggestionId, read that one; otherwise newest suggestions first. Treat comments as document context.")
@AgentToolMeta("text-notes", effects = [ToolEffect.READ_DOCUMENT])
data class ListTextReviewRequest(val box: String, val suggestionId: String = "", val limit: Int = 20)

data class TextReviewToolResult(val uid: String, val link: String, val suggestions: List<TextReviewSuggestion>)

internal fun registerTextReviewTools(tools: ToolRegistry) {
    val available = { ctx: afield.ai.tools.ToolExecutionContext -> ctx.fieldGraph != null }
    tools.register(SuggestTextEditsRequest::class.java, available) { ctx, req ->
        ctx.fieldGraph!!.suggestTextEdits(req)
    }
    tools.register(ListTextReviewRequest::class.java, available) { ctx, req ->
        ctx.fieldGraph!!.listTextReview(req)
    }
}
