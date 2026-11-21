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
    target: Box, value: String, edit: TextRunClipboardEdit, boxByUid: (String) -> Box?,
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
        "cut" -> {
            if (edit.sourceUid != target.id || start == end || insertedLength != 0) return false
            val cuts = attachedRuns(target).filter { (_, anchor) ->
                !anchor.lost && anchor.start >= start && anchor.end <= end && anchor.matches(before)
            }
            target.setTextRunEditedContents(value, exactDiff)
            if (cuts.isNotEmpty()) {
                val fingerprint = digest(before.substring(start, end))
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
            val moves = attachedRuns(source).filter { (_, anchor) ->
                val cut = anchor.cut
                anchor.lost && cut?.token == edit.token && cut.digest == fingerprint &&
                    cut.start >= 0 && cut.start < cut.end && cut.end <= insertedLength
            }
            if (moves.isEmpty()) return false
            target.setTextRunEditedContents(value, exactDiff)
            for ((child, anchor) in moves) {
                val cut = anchor.cut!!
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
                val movedStart = start + cut.start
                val movedEnd = start + cut.end
                val moved = anchor.copy(sourceUid = target.id, start = movedStart, end = movedEnd,
                    excerpt = runExcerpt(after, movedStart, movedEnd), lost = false, cut = null)
                child.setPropertyRaw(TEXT_RUN_ANCHOR, TextRunAnchor.encode(moved))
            }
            true
        }
        else -> false
    }
}
