package afield.document.text

import afield.document.graph.Box
import afield.tools.automation.OperationTranscript
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

internal const val TEXT_FRAME_REVIEW = "textFrameReview"

data class TextReviewEdit(
    val find: String,
    val replace: String,
    val occurrence: Int = 0,
    val reason: String = "",
    val supersedes: String = "",
)

@Serializable
data class TextReviewSuggestion(
    val id: String,
    val original: String,
    val replacement: String,
    val reason: String,
    val start: Int?,
    val status: String = "pending",
    val comments: List<String> = emptyList(),
    val supersedes: String = "",
) {
    internal val pending get() = status == "pending"
    internal val end get() = start?.plus(original.length)
}

internal fun Box.textReviewSuggestions(): List<TextReviewSuggestion> =
    (getPropertyRaw(TEXT_FRAME_REVIEW) as? String)?.let {
        runCatching { Json.decodeFromString<List<TextReviewSuggestion>>(it) }.getOrNull()
    }.orEmpty()

private fun Box.writeTextReview(suggestions: List<TextReviewSuggestion>) {
    val old = getPropertyRaw(TEXT_FRAME_REVIEW)?.toString().orEmpty()
    val value = Json.encodeToString(suggestions)
    setPropertyRaw(TEXT_FRAME_REVIEW, value)
    OperationTranscript.recordEdit(this, TEXT_FRAME_REVIEW, old, value)
}

internal fun Box.proposeTextReview(edits: List<TextReviewEdit>): List<TextReviewSuggestion> = synchronized(textContentsLock) {
    require(getPropertyRaw("box_kind") == "text-frame") { "Review suggestions require a text frame." }
    require(edits.size in 1..24) { "Propose between 1 and 24 changes at a time." }
    val text = getPropertyRaw("contents")?.toString().orEmpty().normalizedRunSource()
    val existing = textReviewSuggestions()
    val revisedIds = edits.map { it.supersedes }.filter { it.isNotBlank() }
    val pending = existing.filter { it.pending }
    require(revisedIds.size == revisedIds.toSet().size && revisedIds.all { id -> pending.any { it.id == id } }) {
        "Each superseded suggestion must still be pending on this frame and can only be revised once in a batch."
    }
    require(pending.size - revisedIds.size + edits.size <= 100) { "Review existing suggestions before adding more." }
    val kept = pending.filter { it.start != null && it.id !in revisedIds }
    val additions = mutableListOf<TextReviewSuggestion>()
    for (edit in edits) {
        val original = edit.find.normalizedRunSource()
        val replacement = edit.replace.normalizedRunSource()
        require(original.isNotBlank() && original.length <= 12_000 && replacement.length <= 12_000 &&
            original != replacement && edit.reason.length <= 2_000) { "Give a changed replacement and a nonblank exact quote of up to 12,000 characters." }
        require(edit.occurrence >= 0) { "Occurrence must be zero (unique quote) or positive." }
        var at = text.indexOf(original)
        repeat((edit.occurrence - 1).coerceAtLeast(0)) {
            require(at >= 0) { "The selected occurrence was not found. Read the current Markdown." }
            at = text.indexOf(original, at + 1)
        }
        require(at >= 0) { "The exact text was not found. Read the current Markdown." }
        require(edit.occurrence != 0 || text.indexOf(original, at + 1) < 0) {
            "That text repeats. Give more context or specify its one-based occurrence."
        }
        val end = at + original.length
        require(text.runBoundary(at) && text.runBoundary(end)) { "The quote must end at character boundaries." }
        require((kept.asSequence() + additions.asSequence()).none { it.start!! < end && it.end!! > at }) {
            "A pending suggestion already covers part of that quote."
        }
        additions += TextReviewSuggestion(UUID.randomUUID().toString(), original, replacement, edit.reason, at,
            supersedes = edit.supersedes)
    }
    writeTextReview(existing.map { if (it.id in revisedIds) it.copy(status = "superseded") else it } + additions)
    additions
}

private fun String.countReviewQuotes(quote: String): Int {
    var count = 0
    var at = indexOf(quote)
    while (at >= 0) {
        count++
        at = indexOf(quote, at + 1)
    }
    return count
}

internal fun Box.rebaseTextReview(
    suggestions: List<TextReviewSuggestion>, before: String, after: String, diff: TextDiff, exact: Boolean,
) {
    val updated = suggestions.map { suggestion ->
        val start = suggestion.start
        if (!suggestion.pending || start == null) return@map suggestion
        val end = start + suggestion.original.length
        val run = diff.runs.firstOrNull { start >= it.oldStart && end <= it.oldStart + it.length }
        val moved = run?.let { it.newStart + start - it.oldStart }?.takeIf {
            after.startsWith(suggestion.original, it) && (exact ||
                before.countReviewQuotes(suggestion.original) == after.countReviewQuotes(suggestion.original))
        }
        suggestion.copy(start = moved)
    }
    if (updated != suggestions) setPropertyRaw(TEXT_FRAME_REVIEW, Json.encodeToString(updated))
}

private fun Box.acceptTextReview(suggestions: List<TextReviewSuggestion>, id: String?) {
    val raw = getPropertyRaw("contents")?.toString().orEmpty()
    val before = raw.normalizedRunSource()
    val applicable = suggestions.filter { suggestion ->
        suggestion.pending && (id == null || suggestion.id == id) &&
            suggestion.start?.let { before.startsWith(suggestion.original, it) } == true
    }.sortedBy { it.start }
    require(applicable.isNotEmpty()) {
        "The source text changed. Ask AI4 for an updated suggestion."
    }
    val runs = mutableListOf<TextMatch>()
    val after = buildString {
        var cursor = 0
        for (suggestion in applicable) {
            val start = checkNotNull(suggestion.start)
            check(start >= cursor) { "These suggestions overlap. Accept them individually." }
            if (start > cursor) {
                runs.add(TextMatch(cursor, length, start - cursor))
                append(before, cursor, start)
            }
            // Preserve matching text inside each replacement for other tracked passages.
            for ((oldStart, newStart, size) in textMatches(suggestion.original, suggestion.replacement).runs) {
                runs.add(TextMatch(start + oldStart, length + newStart, size))
            }
            append(suggestion.replacement)
            cursor = start + suggestion.original.length
        }
        if (cursor < before.length) {
            runs.add(TextMatch(cursor, length, before.length - cursor))
            append(before, cursor, before.length)
        }
    }
    val value = if ("\r\n" in raw && '\n' !in raw.replace("\r\n", "")) after.replace("\n", "\r\n") else after
    val accepted = applicable.map { it.id }.toSet()
    setTextRunEditedContents(value, TextDiff(runs, boundedOut = false))
    OperationTranscript.recordEdit(this, "contents", raw, value)
    writeTextReview(textReviewSuggestions().map { if (it.id in accepted) it.copy(status = "accepted") else it })
}

internal fun Box.reviewTextSuggestion(action: String, id: String, comment: String): Unit = synchronized(textContentsLock) {
    require(getPropertyRaw("box_kind") == "text-frame") { "Review suggestions require a text frame." }
    val suggestions = textReviewSuggestions()
    if (action == "reject-all") {
        if (suggestions.any { it.pending }) writeTextReview(suggestions.map {
            if (it.pending) it.copy(status = "rejected") else it
        })
        return@synchronized
    }
    if (action == "accept-all") {
        acceptTextReview(suggestions, id = null)
        return@synchronized
    }
    val suggestion = suggestions.firstOrNull { it.id == id } ?: error("That suggestion no longer exists.")
    if (action == "comment") {
        val note = comment.trim()
        require(note.isNotEmpty() && note.length <= 4_000) { "Enter a comment of up to 4,000 characters." }
        writeTextReview(suggestions.map { if (it.id == id) it.copy(comments = it.comments + note) else it })
        return@synchronized
    }
    require(suggestion.pending) { "That suggestion has already been reviewed." }
    when (action) {
        "accept" -> acceptTextReview(suggestions, id)
        "reject" -> writeTextReview(suggestions.map { if (it.id == id) it.copy(status = "rejected") else it })
        else -> error("Unknown review action.")
    }
}
