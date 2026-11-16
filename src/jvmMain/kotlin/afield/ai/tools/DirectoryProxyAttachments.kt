package afield.ai.tools

import afield.app.server.DirectoryProxyFiles
import afield.app.server.fileProxyTargetOrNull
import afield.app.server.readFileAsText
import afield.document.graph.Box

/** Resolve a proxy or one of its children without falling through to arbitrary path resolution. */
fun resolveProxyAttachment(boxes: Collection<Box>, raw: String): AttachmentResponse? {
    val requested = raw.trim()
    if (requested.isEmpty()) return null
    val explicit = requested.startsWith("proxy:")
    val token = if (explicit) requested.removePrefix("proxy:").substringBefore('/') else requested
    require(token.isNotBlank()) { "A proxy reference needs a box uid." }
    val available = boxes.filter { it.getPropertyRaw("_tombstone") != true }
    val matches = available.filter { it.id == token }.ifEmpty { available.filter { it.name == token } }
    require(matches.size <= 1) { "Ambiguous proxy box '$token'; use its uid." }
    val box = matches.singleOrNull()
    if (box == null) {
        require(!explicit) { "Unknown proxy box '$token'." }
        return null
    }
    val root = fileProxyTargetOrNull(box)
    if (root == null) {
        require(!explicit) { "Box '$token' is not an available file proxy." }
        return null
    }
    val relative = if (explicit) requested.removePrefix("proxy:").substringAfter('/', "") else ""
    if (root.isDirectory && relative.isEmpty()) return AttachmentResponse(
        file = "proxy:${box.id}/", language = "markdown", content = DirectoryProxyFiles.catalogue(root, box.id)
    )
    val file = if (relative.isNotEmpty()) {
        require(root.isDirectory) { "Proxy '$token' does not point to a directory." }
        DirectoryProxyFiles.resolve(root, relative)
    } else root
    val (content, language) = readFileAsText(file)
        ?: throw IllegalArgumentException("Proxy source is unavailable, too large or has no readable text: ${file.name}")
    return AttachmentResponse(
        file = if (root.isDirectory) DirectoryProxyFiles.reference(box.id, relative) else file.path,
        language = language, content = content
    )
}
