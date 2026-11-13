@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package afield.ai.conversation

import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault

/** Stable passage identity plus an immutable content revision. */
@Serializable
data class GardenPassage(
    val id: String,
    val revision: String,
    @EncodeDefault
    val role: String = "ai",
    @EncodeDefault
    val model: AiChatModelIdentity? = null
)

@Serializable
data class GardenAnchor(
    val node: String,
    val passage: String,
    val revision: String,
    @EncodeDefault
    val quote: String = "",
    @EncodeDefault
    val mode: String = "through",
    @EncodeDefault
    val quoteStart: Int = -1,
    @EncodeDefault
    val quoteEnd: Int = -1
)

@Serializable
data class GardenEdit(val id: String, val box: String, val name: String, val property: String, val before: String, val after: String)

@Serializable
data class GardenNode(
    val id: String,
    @EncodeDefault
    val prompt: String = "",
    @EncodeDefault
    val anchor: GardenAnchor? = null,
    /** Frozen ancestry: content hashes, never another branch's mutable head. */
    @EncodeDefault
    val context: List<GardenPassage> = emptyList(),
    @EncodeDefault
    val passages: List<GardenPassage> = emptyList(),
    @EncodeDefault
    val tags: List<String> = emptyList(),
    @EncodeDefault
    val status: String = "queued",
    @EncodeDefault
    val property: String = "code",
    @EncodeDefault
    val edits: List<GardenEdit> = emptyList(),
    @EncodeDefault
    val error: String = "",
    @EncodeDefault
    val contextMode: String = "path",
    /** Future replies after a move; context remains the record of the original agent run. */
    @EncodeDefault
    val replyContext: List<GardenPassage>? = null
)

@Serializable
data class ConversationGarden(
    @EncodeDefault
    val version: Int = 1,
    @EncodeDefault
    val revision: Long = 0,
    @EncodeDefault
    val nodes: List<GardenNode> = emptyList(),
    /** Shared content-addressed text: transclusions and ancestry do not copy prose or code. */
    @EncodeDefault
    val texts: Map<String, String> = emptyMap()
)

@Serializable
data class GardenRequest(
    val uid: String,
    @EncodeDefault
    val action: String = "read",
    @EncodeDefault
    val node: String = "",
    @EncodeDefault
    val prompt: String = "",
    @EncodeDefault
    val anchor: GardenAnchor? = null,
    @EncodeDefault
    val property: String = "code",
    @EncodeDefault
    val anchorText: String = "",
    @EncodeDefault
    val knownRevision: Long = -1,
    @EncodeDefault
    val wholeDocument: Boolean = false,
    @EncodeDefault
    val beforeNode: String = ""
)

@Serializable
data class GardenReply(
    val uid: String,
    @EncodeDefault
    val garden: ConversationGarden? = null,
    @EncodeDefault
    val error: String = "",
    @EncodeDefault
    val busy: Boolean = false
)
