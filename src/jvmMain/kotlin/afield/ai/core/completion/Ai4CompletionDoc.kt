package afield.ai.core.completion

import afield.ai.KotlinDefinitionRecord
import afield.ai.KotlinSourceIndex
import afield.ai.core.bridge.OpenAiIdeCodexBridge
import afield.ai.core.logging.SilentLog
import com.openai.models.ReasoningEffort
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

object Ai4CompletionDoc {
    private val bridge: OpenAiIdeCodexBridge by lazy {
        val model = System.getenv("AFIELD_COMPLETION_DOC_MODEL") ?: "gpt-5.2"
        OpenAiIdeCodexBridge(
            model = model,
            log = SilentLog,
            reasoningEffort = ReasoningEffort.LOW
        )
    }
    private val threadByKey = ConcurrentHashMap<String, OpenAiIdeCodexBridge.ThreadState>()

    fun resolveDoc(
        fileKey: String,
        label: String,
        displayText: String,
        insertText: String,
        detail: String,
        existingDoc: String,
        allCode: String,
        line: Int,
        column: Int
    ): String {
        val symbol = extractSymbolName(displayText, insertText, label)
        val definition = findDefinition(symbol)
        val definitionSnippet = definition?.let { loadDefinitionSnippet(it) }
        val cursorContext = snippetAroundCursor(allCode, line, column)

        val instructions = """
You write concise usage notes for code completion candidates.
Respond in markdown only. Keep it short and directly useful.
If you include code, keep it minimal and relevant.
        """.trimIndent()

        val state = threadByKey[fileKey] ?: bridge.startThread(
            fileKey = fileKey,
            fileText = "",
            instructions = instructions
        ).also { threadByKey[fileKey] = it }

        val prompt = buildString {
            appendLine("Completion candidate:")
            appendLine("- symbol: ${symbol.ifBlank { label }}")
            appendLine("- label: $label")
            if (displayText.isNotBlank()) appendLine("- display: $displayText")
            if (detail.isNotBlank()) appendLine("- detail: $detail")
            if (insertText.isNotBlank()) {
                appendLine()
                appendLine("Insert text:")
                appendLine("```kotlin")
                appendLine(insertText.trimEnd())
                appendLine("```")
            }
            if (cursorContext.isNotBlank()) {
                appendLine()
                appendLine("Current context:")
                appendLine("```kotlin")
                appendLine(cursorContext.trimEnd())
                appendLine("```")
            }
            if (definitionSnippet != null) {
                appendLine()
                appendLine("Definition source (${definitionSnippet.path}:${definitionSnippet.line}):")
                if (!definitionSnippet.fileId.isNullOrBlank()) {
                    appendLine("fileId: ${definitionSnippet.fileId}")
                }
                appendLine("```kotlin")
                appendLine(definitionSnippet.snippet.trimEnd())
                appendLine("```")
            }
            if (existingDoc.isNotBlank()) {
                appendLine()
                appendLine("Existing documentation (if any):")
                appendLine(existingDoc.trimEnd())
            }
            appendLine()
            appendLine("Write a short usage note with a one-line summary and 2-4 bullets.")
        }

        val result = bridge.send(
            state = state,
            userPrompt = prompt,
            maxOutputTokens = 400
        )
        return result.text.trimEnd()
    }

    private data class DefinitionSnippet(
        val path: String,
        val line: Int,
        val fileId: String?,
        val snippet: String
    )

    private fun extractSymbolName(displayText: String, insertText: String, label: String): String {
        val candidates = listOf(displayText, insertText, label)
        for (raw in candidates) {
            val text = raw.trim()
            if (text.isEmpty()) continue
            parseSymbolFromDeclaration(text)?.let { return it }
            parseQualifiedName(text)?.let { return it }
        }
        return label.trim()
    }

    private fun unwrapIdentifier(raw: String): String =
        if (raw.startsWith("`") && raw.endsWith("`") && raw.length > 1) {
            raw.removePrefix("`").removeSuffix("`")
        } else {
            raw
        }

    private fun parseSymbolFromDeclaration(text: String): String? {
        val match = Regex("^(fun|class|interface|object|val|var|typealias)\\s+(.+)$")
            .find(text)
            ?: return null
        val kind = match.groupValues[1]
        val remainder = match.groupValues[2].trimStart()
        return when (kind) {
            "fun" -> {
                val head = remainder.substringBefore("(").trim()
                parseQualifiedName(head)
            }
            "val", "var" -> {
                val head = remainder.substringBefore(":").substringBefore("=").trim()
                parseQualifiedName(head)
            }
            "class", "interface", "object", "typealias" -> {
                Regex("^(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)")
                    .find(remainder)?.groupValues?.get(1)?.let { unwrapIdentifier(it) }
            }
            else -> null
        }
    }

    private fun parseQualifiedName(raw: String): String? {
        if (raw.isBlank()) return null
        val noGenerics = stripGenericArgs(raw)
        val compact = removeWhitespaceOutsideBackticks(noGenerics).replace("?", "")
        if (compact.isBlank()) return null
        val regex = Regex("^(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)(\\.`[^`]+`|\\.[A-Za-z_][A-Za-z0-9_]*)*$")
        val match = regex.find(compact) ?: return null
        return match.value.split(".").joinToString(".") { unwrapIdentifier(it) }
    }

    private fun stripGenericArgs(raw: String): String {
        val sb = StringBuilder(raw.length)
        var depth = 0
        for (ch in raw) {
            when (ch) {
                '<' -> depth++
                '>' -> if (depth > 0) depth--
                else -> if (depth == 0) sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun removeWhitespaceOutsideBackticks(raw: String): String {
        val sb = StringBuilder(raw.length)
        var inBackticks = false
        for (ch in raw) {
            if (ch == '`') {
                inBackticks = !inBackticks
                sb.append(ch)
                continue
            }
            if (!inBackticks && ch.isWhitespace()) continue
            sb.append(ch)
        }
        return sb.toString()
    }

    private fun findDefinition(symbol: String): KotlinDefinitionRecord? {
        if (symbol.isBlank()) return null
        val index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex()
        val definitions = index?.definitions.orEmpty()
        definitions.firstOrNull { it.name == symbol }?.let { return it }
        definitions.firstOrNull { it.name.equals(symbol, ignoreCase = true) }?.let { return it }
        val dotIdx = symbol.lastIndexOf('.')
        if (dotIdx > 0 && dotIdx < symbol.length - 1) {
            val short = symbol.substring(dotIdx + 1)
            definitions.firstOrNull { it.name == short }?.let { return it }
            return definitions.firstOrNull { it.name.equals(short, ignoreCase = true) }
        }
        return null
    }

    private fun loadDefinitionSnippet(def: KotlinDefinitionRecord): DefinitionSnippet? {
        val path = Paths.get(def.filePath)
        if (!Files.exists(path)) return null
        val text = runCatching { Files.readString(path, StandardCharsets.UTF_8) }.getOrNull() ?: return null
        val lines = text.split("\n")
        val center = (def.line - 1).coerceIn(0, lines.lastIndex)
        val start = (center - 20).coerceAtLeast(0)
        val end = (center + 20).coerceAtMost(lines.lastIndex)
        val snippet = lines.subList(start, end + 1).joinToString("\n")
        return DefinitionSnippet(def.filePath, def.line, def.fileId, snippet)
    }

    private fun snippetAroundCursor(text: String, line: Int, column: Int): String {
        if (text.isBlank()) return ""
        val lines = text.split("\n")
        val center = (line - 1).coerceIn(0, lines.lastIndex)
        val start = (center - 12).coerceAtLeast(0)
        val end = (center + 12).coerceAtMost(lines.lastIndex)
        val snippet = lines.subList(start, end + 1).toMutableList()
        val lineIdx = center - start
        if (lineIdx in snippet.indices) {
            val raw = snippet[lineIdx]
            val col = (column - 1).coerceIn(0, raw.length)
            snippet[lineIdx] = raw.substring(0, col) + "<cursor>" + raw.substring(col)
        }
        return snippet.joinToString("\n")
    }
}
