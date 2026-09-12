package afield

import kotlinx.serialization.Serializable

/** Document-relative Markdown operations. Saves require the revision returned by a read. */
@Serializable
data class DocumentGuideRequest(
    val action: String = "list",
    val path: String = "",
    val revision: String = "",
    val contents: String = "",
    val x: Double = 0.0,
    val y: Double = 0.0
)

@Serializable
data class DocumentGuideEntry(val path: String, val title: String)

@Serializable
data class DocumentGuideFile(val path: String, val contents: String, val revision: String)

@Serializable
data class DocumentGuideResponse(
    val ok: Boolean = true,
    val error: String = "",
    val conflict: Boolean = false,
    val document: String = "",
    val readOnly: Boolean = false,
    val entries: List<DocumentGuideEntry> = emptyList(),
    val file: DocumentGuideFile? = null,
    val uid: String? = null
)
