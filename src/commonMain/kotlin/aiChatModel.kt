package afield

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Model identity recorded with a particular response, never inferred from the current settings. */
@Serializable
data class AiChatModelIdentity(
    val provider: String,
    val requestedModel: String,
    val responseModel: String? = null
) {
    fun label(): String {
        val providerLabel = when (provider) {
            "openai" -> "OpenAI"
            "openrouter" -> "OpenRouter"
            "anthropic" -> "Anthropic"
            else -> provider
        }
        val actual = responseModel?.takeIf { it.isNotBlank() }
        return "${actual ?: requestedModel} · $providerLabel" + if (actual == null) " (requested)" else ""
    }

    fun description(): String = if (responseModel.isNullOrBlank()) {
        "Requested $requestedModel from $provider; response model not yet reported."
    } else {
        "Provider: $provider. Requested: $requestedModel. Reported by response: $responseModel."
    }

    fun marker(): String = "<<model:${Json.encodeToString(this)}>>\n"

    companion object {
        /** Old transcripts have no metadata; do not relabel them with today's model. */
        fun splitFromContent(content: String): Pair<AiChatModelIdentity?, String> {
            val line = content.substringBefore('\n')
            if (!line.startsWith("<<model:") || !line.endsWith(">>")) return null to content
            val identity = runCatching {
                Json.decodeFromString<AiChatModelIdentity>(line.removePrefix("<<model:").removeSuffix(">>"))
            }.getOrNull() ?: return null to content
            return identity to content.substringAfter('\n', "").trimStart()
        }
    }
}
