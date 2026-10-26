package afield.app.protocol

import kotlinx.serialization.Serializable

/** The audience surface attached to an ordinary guide step. */
@Serializable
data class AudienceContent(
    val type: String = "html",
    val html: String = "",
    val url: String = "",
    val videoId: String = "",
    val document: String = "",
    val rect: List<Double>? = null,
    val macro: AudienceMacro? = null,
    val interactiveGuide: Boolean = false
)

@Serializable
data class AudienceMacro(val name: String, val scope: String = "document", val project: String = "")

/** Guide navigation remains on the JVM; Electron only renders its current audience surface. */
@Serializable
data class PresentationState(
    val slideshowId: String,
    val hostUid: String,
    val title: String,
    val index: Int,
    val titles: List<String>,
    val content: AudienceContent? = null,
    val transitionMs: Int = 450,
    val sectionPaths: List<List<String>> = emptyList(),
    val stepIds: List<String> = emptyList(),
    val cueIndex: Int = 0,
    val cueTitles: List<String> = emptyList(),
    val cueAction: Boolean = false,
    val checkpoint: String = ""
)
