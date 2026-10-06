package afield

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Tool families from `ai4-chat-tool-families.json` (commonMain resources): which calls write notes, change
 * recipes, edit the design, run builds, look at pictures. The chat panel counts them in each round's summary
 * line; the chat archive (`afield.chatlog`) indexes them. Names are matched as [aiChatToolName] gives them.
 * First match wins; `*` matches any run of characters.
 */
class AiChatToolFamilies(val families: List<Family>) {
    @Serializable
    data class Family(
        val name: String,
        val label: String,
        val purpose: String = "",
        /** Singular and plural noun for a round's summary line (`2 notes`); a family without one is not counted there. */
        val count: List<String> = emptyList(),
        val match: List<String>,
    )

    @Serializable
    private data class Catalog(val version: Int, val families: List<Family>)

    private val compiled = families.map { f ->
        f to f.match.map { glob -> Regex(glob.split('*').joinToString(".*") { Regex.escape(it) }) }
    }

    fun familyOf(name: String): String =
        compiled.firstOrNull { (_, globs) -> globs.any { it.matches(name) } }?.first?.name ?: "other"

    fun label(name: String): String = families.firstOrNull { it.name == name }?.label ?: "Other"

    companion object {
        fun parse(json: String): AiChatToolFamilies =
            AiChatToolFamilies(Json { ignoreUnknownKeys = true }.decodeFromString<Catalog>(json).families)
    }
}
