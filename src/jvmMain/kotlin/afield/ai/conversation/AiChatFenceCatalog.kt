package afield.ai.conversation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The fenced blocks AI4 chat draws as pictures rather than printing as code, read on the JVM from
 * the same catalog the browser renderers read.
 *
 * `src/commonMain/resources/ai4-fence-renderers.json` holds one entry per language, each naming the
 * renderer module and export that draws it in the chat panel alongside the syntax guidance and the
 * worked example the model is shown. There are two today, `mermaid` and `math`. The JVM reads that
 * file out of its resources and turns it into the paragraph of fence instructions added to the
 * chat system prompt, so a language the model is told about always has a renderer behind it and a
 * language the browser cannot draw is never offered. Only chat mode gets that paragraph; the other
 * instruction modes describe no fences.
 *
 * The catalog is checked on first read for a known schema version, one entry per language, a
 * lowercase language name and no blank field. A malformed or absent catalog stops the read and says
 * what is wrong with it, instead of leaving the panel one renderer short of what it advertised.
 */

/** The same packaged catalog that registers preview renderers in the browser. */
internal object AiChatFenceCatalog {
    @Serializable
    data class Entry(
        val language: String,
        val name: String,
        val purpose: String,
        val syntaxGuidance: String,
        val example: String,
        val modulePath: String,
        val exportName: String,
    )

    @Serializable
    private data class FileFormat(val version: Int, val fences: List<Entry>)

    val entries: List<Entry> by lazy {
        val text = checkNotNull(javaClass.getResourceAsStream("/ai4-fence-renderers.json")) {
            "AI4 fence catalog is missing from JVM resources"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val file = Json.decodeFromString<FileFormat>(text)
        require(file.version == 1) { "Unsupported AI4 fence catalog version ${file.version}" }
        require(file.fences.map { it.language }.distinct().size == file.fences.size) {
            "Duplicate AI4 fence language"
        }
        file.fences.forEach { entry ->
            require(Regex("[a-z][a-z0-9_+-]*").matches(entry.language)) { "Invalid fence language" }
            require(listOf(entry.name, entry.purpose, entry.syntaxGuidance, entry.example,
                entry.modulePath, entry.exportName).all { it.isNotBlank() }) { "Incomplete fence ${entry.language}" }
        }
        file.fences
    }

    fun instructionText(fences: List<Entry> = entries): String = buildString {
        if (fences.isEmpty()) return@buildString
        appendLine("AI4 chat renders these complete fenced blocks. Use one when it clarifies the answer; ordinary code fences remain code:")
        for (entry in fences) {
            appendLine("- `${entry.language}` (${entry.name}): ${entry.purpose}")
            appendLine("  Syntax: ${entry.syntaxGuidance}")
            appendLine("  Example:")
            appendLine("  ```${entry.language}")
            entry.example.lineSequence().forEach { appendLine("  $it") }
            appendLine("  ```")
        }
    }.trimEnd()
}
