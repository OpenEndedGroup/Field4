package afield.ai.tools

import java.security.MessageDigest

/**
 * Reading a long attachment one window at a time, under the guards every attachment shares.
 *
 * The slice is taken after ordinary resolution, so an attachment already in the turn's context and one
 * the document's own resolver fetches are treated alike. The window is bounded (2 to 65536 characters)
 * and has to start inside the content. The content's SHA-256 comes back as `sourceHash`, and a later
 * read that passes it back is refused when the source has changed since, with a message asking for a
 * fresh read from the start rather than a splice of two versions. A `find` string moves the window to
 * the first match at or after the offset, with a little room before it, and an empty window with
 * `searchMatched` false says there was none. An offset that would split a surrogate pair is refused,
 * and a computed start or end that would is moved back one, so a window never ends mid-character.
 * `nextOffset` is null on the last one.
 */

/** Slice after ordinary resolution so attachments, property refs and resolver paths share guards. */
internal fun AttachmentResponse.excerpt(request: AttachmentRequest): AttachmentResponse {
    require(request.maxChars in 2..65536) { "maxChars must be between 2 and 65536" }
    require(request.offset in 0..content.length) { "offset must be between 0 and ${content.length}" }
    val hash = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    require(request.expectedSourceHash.isEmpty() || request.expectedSourceHash == hash) {
        "Source changed since the previous read. Read again from offset 0 and reconcile; current sourceHash=$hash"
    }
    fun splitsPair(at: Int) = at > 0 && at < content.length &&
        content[at - 1].isHighSurrogate() && content[at].isLowSurrogate()
    require(!splitsPair(request.offset)) { "offset splits a Unicode character; use the previous nextOffset" }
    val match = request.find.takeIf { it.isNotEmpty() }?.let { content.indexOf(it, request.offset) }
    if (match == -1) return copy(content = "", offset = request.offset, totalChars = content.length,
        sourceHash = hash, nextOffset = null, isExcerpt = true, searchMatched = false, matchOffset = null)
    var start = if (match != null) (match - minOf(1000, request.maxChars / 4)).coerceAtLeast(request.offset) else request.offset
    if (splitsPair(start)) start--
    var end = (start.toLong() + request.maxChars).coerceAtMost(content.length.toLong()).toInt()
    if (splitsPair(end)) end--
    return copy(
        content = content.substring(start, end), offset = start,
        totalChars = content.length, sourceHash = hash,
        nextOffset = end.takeIf { it < content.length },
        isExcerpt = start != 0 || end != content.length,
        searchMatched = match?.let { true }, matchOffset = match
    )
}
