package afield

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/** A request from an output iframe to a live box binding over Field's ordinary `/ws`. */
@Serializable
data class OutputRequest(val binding: String, val data: JsonElement = JsonNull)

/** Correlated through [Message.replyTo]; failed requests never masquerade as model data. */
@Serializable
data class OutputRequestResult(val ok: Boolean, val data: JsonElement = JsonNull, val error: String? = null)
