package afield.ai.core

import java.security.MessageDigest

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
