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

/**
 * Box `notes` are Markdown too, so the viewer reads them beside the files. They get their own
 * path namespace rather than a second field to carry: `guideTarget` rejects `box/…` (no `.md`
 * extension, and a subdirectory that is neither `docs` nor `guides`), so no file can claim one.
 */
const val documentGuideNotePrefix = "box/"

fun documentGuideNotePath(uid: String) = documentGuideNotePrefix + uid

fun documentGuideNoteUid(path: String): String? =
    if (path.startsWith(documentGuideNotePrefix)) path.removePrefix(documentGuideNotePrefix).ifBlank { null } else null

/** [kind] is `guide` for a Markdown file on disk, `note` for a box's `notes` property. */
@Serializable
data class DocumentGuideEntry(val path: String, val title: String, val kind: String = "guide", val uid: String = "")

@Serializable
data class DocumentGuideFile(
    val path: String,
    val contents: String,
    val revision: String,
    val kind: String = "guide",
    val uid: String = ""
)

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
