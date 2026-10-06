package afield

import kotlinx.serialization.Serializable

/** A saved state, with stable identity and explicit ancestry. Details are fetched separately. */
@Serializable
data class StateTreeNode(val id: String, val parents: List<String> = emptyList(), val title: String,
    val kind: String = "state", val created: String = "")

/** A provider's existing history. Reading this must never restore, compile or rebuild a state. */
@Serializable
data class StateTreeSnapshot(val nodes: List<StateTreeNode>, val current: String? = null, val note: String = "")

@Serializable
data class StateTreeSource(val id: String, val label: String, val hasState: Boolean = false)

@Serializable
data class StateTreeRequest(val uid: String, val source: String = "", val node: String = "", val offset: Int = 0)

@Serializable
data class StateTreeReply(val sources: List<StateTreeSource> = emptyList(), val source: String = "",
    val tree: StateTreeSnapshot? = null, val total: Int = 0, val offset: Int = 0,
    val detail: String = "", val reference: String = "", val error: String = "")

@Serializable
data class StateTreeReveal(val uid: String, val source: String, val node: String = "", val offset: Int = 0)

@Serializable
data class StateTreeRevealResult(val success: Boolean, val uid: String, val message: String)
