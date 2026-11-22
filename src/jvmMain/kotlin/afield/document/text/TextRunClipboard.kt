package afield.document.text

import afield.app.protocol.TextRunClipboardEdit
import afield.document.graph.Box
import afield.tools.automation.OperationTranscript
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** A cut can be claimed once, including after the document has been saved and reopened. */
@Serializable
internal data class TextRunCut(
    val token: String,
    val digest: String,
    val start: Int,
    val end: Int,
)

private fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun attachedRuns(source: Box): List<Pair<Box, TextRunAnchor>> {
    return (source.getPropertyRaw("children") as? Collection<*>).orEmpty().filterIsInstance<Box>().mapNotNull { child ->
        val anchor = TextRunAnchor.decode(child.getPropertyRaw(TEXT_RUN_ANCHOR))
        if (anchor?.sourceUid == source.id && anchor.property == "contents" && child.getPropertyRaw("_tombstone") != true)
            child to anchor else null
    }
}

/**
 * Handle a verified exact edit instead of diffing snapshots. The clipboard carries a token, not
 * authoritative ranges: only the source document's still-pending cuts can claim that token.
 */
internal fun applyTextRunClipboardEdit(
    target: Box, value: String, edit: TextRunClipboardEdit, knownPassage: (String) -> Boolean = { false },
    boxByUid: (String) -> Box?,
): Boolean {
    if (target.getPropertyRaw("box_kind") != "text-frame" || edit.token.length != 36) return false
    val before = (target.getPropertyRaw("contents") as? String).orEmpty().normalizedRunSource()
    val after = value.normalizedRunSource()
    val (start, end) = edit.start to edit.end
    if (start < 0 || end < start || end > before.length) return false
    val insertedLength = after.length - (before.length - (end - start))
    if (insertedLength < 0 || !after.regionMatches(0, before, 0, start) ||
        !after.regionMatches(start + insertedLength, before, end, before.length - end)) return false
    val exactDiff = TextDiff(
        listOf(TextMatch(0, 0, start), TextMatch(end, start + insertedLength, before.length - end)),
        boundedOut = false,
    )

    return when (edit.action) {
        "transclude" -> {
            val source = boxByUid(edit.sourceUid) ?: return false
            if (source.getPropertyRaw("box_kind") != "text-frame" || source.getPropertyRaw("_tombstone") == true) return false
            val capturedStart = edit.sourceStart ?: return false
            val capturedEnd = edit.sourceEnd ?: return false
            val quote = edit.quote?.normalizedRunSource()?.takeIf { it.isNotBlank() && it.length <= 65_536 } ?: return false
            if (capturedStart < 0 || capturedEnd < capturedStart || capturedEnd - capturedStart != quote.length ||
                after.substring(start, start + insertedLength) != "[Source passage](field://passage/${edit.token})") return false
            val sourceText = (source.getPropertyRaw("contents") as? String).orEmpty().normalizedRunSource()
            // Prefer the captured offset; only use a new position if the full quote is unique.
            val at = if (sourceText.regionMatches(capturedStart, quote, 0, quote.length)) capturedStart
                else sourceText.indexOf(quote).takeIf { it == sourceText.lastIndexOf(quote) } ?: -1
            val located = at >= 0
            val location = if (located) at else capturedStart
            val anchor = TextRunAnchor(1, edit.token, source.id, "contents", location, location + quote.length,
                runExcerpt(quote, 0, quote.length), lost = !located)
            if (!knownPassage(anchor.id) && source.textRunSources().none { it.id == anchor.id }) {
                source.writeTextRunSources(source.textRunSources() + anchor)
            }
            target.setTextRunEditedContents(value, exactDiff)
            true
        }
        "cut" -> {
            if (edit.sourceUid != target.id || start == end || insertedLength != 0) return false
            val cuts = attachedRuns(target).filter { (_, anchor) ->
                !anchor.lost && anchor.start >= start && anchor.end <= end && anchor.matches(before)
            }
            val sources = target.textRunSources().filter {
                !it.lost && it.start >= start && it.end <= end && it.matches(before)
            }
            target.setTextRunEditedContents(value, exactDiff)
            if (cuts.isNotEmpty() || sources.isNotEmpty()) {
                val fingerprint = digest(before.substring(start, end))
                if (sources.isNotEmpty()) {
                    val pending = sources.associate { anchor ->
                        anchor.id to anchor.copy(lost = true, cut = TextRunCut(edit.token, fingerprint, anchor.start - start, anchor.end - start))
                    }
                    target.writeTextRunSources(target.textRunSources().map { pending[it.id] ?: it })
                }
                for ((child, anchor) in cuts) {
                    val cut = TextRunCut(edit.token, fingerprint, anchor.start - start, anchor.end - start)
                    child.setPropertyRaw(TEXT_RUN_ANCHOR, TextRunAnchor.encode(anchor.copy(lost = true, cut = cut)))
                }
            }
            true
        }
        "paste" -> {
            if (insertedLength == 0) return false
            val source = boxByUid(edit.sourceUid) ?: return false
            if (source.getPropertyRaw("_tombstone") == true) return false
            val fragment = after.substring(start, start + insertedLength)
            val fingerprint = digest(fragment)
            fun eligible(anchor: TextRunAnchor): Boolean {
                val cut = anchor.cut
                return anchor.lost && cut?.token == edit.token && cut.digest == fingerprint &&
                    cut.start >= 0 && cut.start < cut.end && cut.end <= insertedLength
            }
            fun moved(anchor: TextRunAnchor): TextRunAnchor {
                val cut = anchor.cut!!
                val nextStart = start + cut.start
                val nextEnd = start + cut.end
                return anchor.copy(sourceUid = target.id, start = nextStart, end = nextEnd,
                    excerpt = runExcerpt(after, nextStart, nextEnd), lost = false, cut = null)
            }
            val moves = attachedRuns(source).filter { (_, anchor) -> eligible(anchor) }
            val runs = source.textRunSources().filter(::eligible)
            if (moves.isEmpty() && runs.isEmpty()) return false
            target.setTextRunEditedContents(value, exactDiff)
            if (runs.isNotEmpty()) {
                val ids = runs.map { it.id }.toSet()
                if (source != target) source.writeTextRunSources(source.textRunSources().filterNot { it.id in ids })
                target.writeTextRunSources(target.textRunSources().filterNot { it.id in ids } + runs.map(::moved))
            }
            for ((child, anchor) in moves) {
                // A note can contain the pasted prose, but it cannot become its own parent.
                if (child == target) {
                    child.setPropertyRaw(TEXT_RUN_ANCHOR, TextRunAnchor.encode(anchor.copy(cut = null)))
                    continue
                }
                if (source != target) {
                    if (child !in target.children) {
                        target.connect(child)
                        OperationTranscript.recordConnect(target, child, connected = true)
                    }
                    source.disconnect(child)
                    OperationTranscript.recordConnect(source, child, connected = false)
                }
                child.setPropertyRaw(TEXT_RUN_ANCHOR, TextRunAnchor.encode(moved(anchor)))
            }
            true
        }
        else -> false
    }
}
