package afield.ai.core.completion

import afield.ai.KotlinDefinitionRecord
import afield.ai.KotlinSourceIndex
import afield.ai.core.Ai4ModelDefaults
import afield.ai.core.OpenAiIdeCodexBridge
import afield.ai.core.SilentLog
import com.openai.models.ReasoningEffort
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

object Ai4CompletionDoc {
    private val model = Ai4ModelDefaults.envOrDefault(
        "AFIELD_COMPLETION_DOC_MODEL",
        Ai4ModelDefaults.FAST_MODEL
    )
    private val bridge: OpenAiIdeCodexBridge by lazy {
        OpenAiIdeCodexBridge(
            model = model,
            log = SilentLog,
            reasoningEffort = ReasoningEffort.LOW
        )
    }
    private val cache by lazy { GeneratedCompletionDocCache() }
    private val threadByKey = ConcurrentHashMap<String, OpenAiIdeCodexBridge.ThreadState>()
    private val generationLocks = ConcurrentHashMap<String, Any>()

    fun resolveDoc(
        fileKey: String,
        label: String,
        displayText: String,
        insertText: String,
        detail: String,
        existingDoc: String,
        allCode: String,
        line: Int,
        column: Int,
        documentationSourceName: String = "",
        documentationSourcePath: String = "",
        documentationSourceLine: Int = -1
    ): String {
        val symbol = extractSymbolName(displayText, insertText, label)
        val definition = findDefinition(
            symbol = symbol,
            sourceName = documentationSourceName,
            sourcePath = documentationSourcePath,
            sourceLine = documentationSourceLine
        ) ?: return ""
        if (!definition.kdoc.isNullOrBlank()) return ""

        val definitionSnippet = loadDefinitionSnippet(definition) ?: return ""
        val lockKey = "${definition.name}:${definition.filePath}:${definitionSnippet.sourceHash}"
        val generationLock = generationLocks.computeIfAbsent(lockKey) { Any() }

        synchronized(generationLock) {
            cache.lookup(
                sourceName = definition.name,
                sourcePath = definition.filePath,
                sourceLine = definition.line,
                sourceCode = definitionSnippet.source
            )?.let { return generatedDocumentationMarkdown(it.documentation, cached = true) }

            val generated = generateDoc(
                fileKey = fileKey,
                symbol = symbol,
                label = label,
                displayText = displayText,
                insertText = insertText,
                detail = detail,
                existingDoc = existingDoc,
                allCode = allCode,
                line = line,
                column = column,
                definition = definition,
                definitionSnippet = definitionSnippet
            )
            if (generated.isBlank()) return ""

            cache.put(
                sourceName = definition.name,
                sourcePath = definition.filePath,
                sourceLine = definition.line,
                sourceCode = definitionSnippet.source,
                documentation = generated,
                model = model
            )
            return generatedDocumentationMarkdown(generated, cached = false)
        }
    }

    private fun generateDoc(
        fileKey: String,
        symbol: String,
        label: String,
        displayText: String,
        insertText: String,
        detail: String,
        existingDoc: String,
        allCode: String,
        line: Int,
        column: Int,
        definition: KotlinDefinitionRecord,
        definitionSnippet: DefinitionSnippet
    ): String {
        val cursorContext = snippetAroundCursor(allCode, line, column)

        val instructions = """
You draft concise KDoc body text for Kotlin declarations that currently have no documentation.
Infer behavior only from the supplied declaration source and usage context.
Return Markdown only, without /** */ delimiters and without @param, @return, or other KDoc tags.
Write a direct one-sentence summary followed by at most one short explanatory paragraph.
Mention side effects, lifecycle, units, or constraints only when the source supports them.
        """.trimIndent()

        val sourceThreadKey = "$fileKey#${definitionSnippet.sourceHash}"
        val state = threadByKey[sourceThreadKey] ?: bridge.startThread(
            fileKey = sourceThreadKey,
            fileText = definitionSnippet.source,
            instructions = instructions
        ).also { threadByKey[sourceThreadKey] = it }

        val prompt = buildString {
            appendLine("Undocumented indexed declaration:")
            appendLine("- symbol: ${definition.name.ifBlank { symbol.ifBlank { label } }}")
            appendLine("- source: ${definition.filePath}:${definition.line}")
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
            appendLine()
            appendLine("Declaration source:")
            appendLine("```kotlin")
            appendLine(definitionSnippet.source.take(16_000).trimEnd())
            appendLine("```")
            if (existingDoc.isNotBlank()) {
                appendLine()
                appendLine("Existing generated signature/context (not human documentation):")
                appendLine(existingDoc.trimEnd())
            }
            appendLine()
            appendLine("Draft the missing KDoc body.")
        }

        val result = bridge.send(
            state = state,
            userPrompt = prompt,
            maxOutputTokens = 500
        )
        return result.text.trimEnd()
    }

    private data class DefinitionSnippet(
        val source: String,
        val sourceHash: String
    )

    private fun generatedDocumentationMarkdown(documentation: String, cached: Boolean): String =
        buildString {
            append("**AI-generated draft documentation")
            if (cached) append(" (cached)")
            append("**\n\n")
            append(documentation.trim())
        }

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

    private fun findDefinition(
        symbol: String,
        sourceName: String,
        sourcePath: String,
        sourceLine: Int
    ): KotlinDefinitionRecord? {
        val index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex()
        val definitions = index?.definitions.orEmpty()
        if (sourcePath.isNotBlank() && sourceLine > 0) {
            definitions.firstOrNull {
                it.filePath == sourcePath &&
                    it.line == sourceLine &&
                    (
                        sourceName.isBlank() ||
                            it.name == sourceName ||
                            it.name.substringAfterLast('.') == sourceName.substringAfterLast('.')
                        )
            }?.let { return it }
        }
        if (sourcePath.isNotBlank() && sourceName.isNotBlank()) {
            definitions.firstOrNull {
                it.filePath == sourcePath && it.name == sourceName
            }?.let { return it }
        }
        if (sourceName.isNotBlank()) {
            definitions.firstOrNull { it.name == sourceName }?.let { return it }
        }
        if (symbol.isBlank()) return null
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
        val lineOffsets = mutableListOf(0)
        text.forEachIndexed { index, ch ->
            if (ch == '\n' && index + 1 < text.length) lineOffsets += index + 1
        }
        val start = lineOffsets.getOrNull((def.line - 1).coerceAtLeast(0)) ?: return null
        val index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex()
        val nextLine = index?.definitions
            .orEmpty()
            .asSequence()
            .filter { it.filePath == def.filePath && it.line > def.line }
            .minOfOrNull { it.line }
        val end = nextLine
            ?.let { lineOffsets.getOrNull((it - 1).coerceAtLeast(0)) }
            ?: text.length
        val source = text.substring(start, end).trimEnd()
        if (source.isBlank()) return null
        return DefinitionSnippet(source = source, sourceHash = afield.ai.Sha256.hexUtf8(source))
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
