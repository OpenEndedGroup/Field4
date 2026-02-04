package afield.ai

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Data class representing a KDoc comment found in source code
 */
data class KDocComment(
    val text: String,
    val startLine: Int,
    val endLine: Int,
    val declarationLine: Int,
    val declarationName: String?,
    val declarationType: String? // "class", "fun", "val", "var"
)

/**
 * Utility object for extracting KDoc comments from Kotlin source files
 */
object KDocExtractor {
    
    private val modifierTokens = setOf(
        "public", "private", "internal", "protected",
        "open", "final", "abstract", "sealed", "data", "enum", "annotation", "value",
        "inline", "suspend", "tailrec", "operator", "infix", "external", "override",
        "const", "lateinit", "companion", "inner"
    )
    
    /**
     * Extract all KDoc comments from a Kotlin source file
     */
    fun extractKDocs(sourceText: String): List<KDocComment> {
        val result = mutableListOf<KDocComment>()
        val lines = sourceText.split("\n")
        var i = 0
        
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            
            // Check for KDoc start
            if (trimmed.startsWith("/**")) {
                val kdocStartLine = i + 1 // 1-indexed
                val kdocLines = mutableListOf<String>()
                var kdocEndLine = i
                var foundEnd = false
                
                // Collect KDoc lines
                var j = i
                while (j < lines.size) {
                    val kdocLine = lines[j].trimStart()
                    if (j == i && kdocLine == "/**") {
                        // First line with only /**
                        j++
                        continue
                    }
                    
                    if (kdocLine.contains("*/")) {
                        // End of KDoc
                        val beforeEnd = kdocLine.substringBefore("*/").trimStart()
                        if (beforeEnd.startsWith("*")) {
                            val content = beforeEnd.substring(1).trimStart()
                            if (content.isNotEmpty()) {
                                kdocLines.add(content)
                            }
                        } else if (beforeEnd.isNotEmpty()) {
                            kdocLines.add(beforeEnd)
                        }
                        kdocEndLine = j + 1 // 1-indexed
                        foundEnd = true
                        j++
                        break
                    }
                    
                    // Process KDoc content line
                    if (kdocLine.startsWith("*")) {
                        val content = kdocLine.substring(1).trimStart()
                        kdocLines.add(content)
                    } else {
                        kdocLines.add(kdocLine)
                    }
                    j++
                }
                
                if (foundEnd) {
                    // Look for the declaration after the KDoc
                    var declLine = j
                    var declaration: Pair<String, String>? = null
                    
                    // Skip blank lines and annotations
                    while (declLine < lines.size) {
                        val declLineText = lines[declLine].trimStart()
                        if (declLineText.isEmpty()) {
                            declLine++
                            continue
                        }
                        if (declLineText.startsWith("@")) {
                            declLine++
                            continue
                        }
                        
                        // Try to parse declaration
                        declaration = parseDeclaration(declLineText)
                        if (declaration != null) {
                            break
                        }
                        declLine++
                    }
                    
                    if (declaration != null) {
                        val kdocText = kdocLines.joinToString("\n")
                        result.add(
                            KDocComment(
                                text = kdocText,
                                startLine = kdocStartLine,
                                endLine = kdocEndLine,
                                declarationLine = declLine + 1, // 1-indexed
                                declarationName = declaration.second,
                                declarationType = declaration.first
                            )
                        )
                    }
                    
                    i = declLine
                } else {
                    i = j
                }
            } else {
                i++
            }
        }
        
        return result
    }
    
    /**
     * Parse a declaration line to extract type and name
     */
    private fun parseDeclaration(line: String): Pair<String, String>? {
        var s = line.trimStart()
        if (s.isEmpty()) return null
        if (s.startsWith("package ") || s.startsWith("import ")) return null
        if (s.startsWith("//")) return null
        
        // Skip annotations
        val annotationRegex = Regex("^@\\w[\\w\\.]*\\s*(\\([^)]*\\))?\\s*")
        while (true) {
            val match = annotationRegex.find(s)
            if (match != null && match.range.first == 0) {
                s = s.substring(match.range.last + 1).trimStart()
            } else {
                break
            }
        }
        
        // Skip modifiers
        val tokens = s.split(Regex("\\s+"))
        var idx = 0
        while (idx < tokens.size && modifierTokens.contains(tokens[idx])) {
            idx++
        }
        if (idx >= tokens.size) return null
        
        val keyword = tokens[idx]
        val remainder = s.substringAfter(keyword, "").trimStart()
        
        val name = when (keyword) {
            "fun" -> {
                if (remainder.startsWith("interface")) return null
                val head = remainder.substringBefore("(").trim()
                parseQualifiedName(head)
            }
            "class", "interface", "object" -> {
                Regex("^(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)")
                    .find(remainder)?.groupValues?.get(1)?.let { unwrapIdentifier(it) }
            }
            "val", "var" -> {
                val head = remainder.substringBefore(":").substringBefore("=").trim()
                parseQualifiedName(head)
            }
            else -> null
        } ?: return null
        
        if (name.isBlank()) return null
        
        return when (keyword) {
            "class", "interface", "object", "fun", "val", "var" -> keyword to name
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
    
    private fun unwrapIdentifier(raw: String): String =
        if (raw.startsWith("`") && raw.endsWith("`") && raw.length > 1) {
            raw.removePrefix("`").removeSuffix("`")
        } else {
            raw
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
    
    /**
     * Build a map of symbol names to their KDoc for quick lookup
     */
    fun buildKDocIndex(sourceText: String): Map<String, String> {
        val kdocs = extractKDocs(sourceText)
        return kdocs.mapNotNull { kdoc ->
            kdoc.declarationName?.let { name ->
                name to kdoc.text
            }
        }.toMap()
    }
    
    /**
     * Extract KDoc for a specific symbol from a file
     */
    fun extractKDocForSymbol(filePath: Path, symbolName: String): String? {
        if (!filePath.exists() || !filePath.isRegularFile()) return null
        
        return try {
            val sourceText = filePath.readText()
            val index = buildKDocIndex(sourceText)
            index[symbolName]
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Get KDoc for a symbol from the afield package
     */
    fun getKDocForAfieldSymbol(symbolName: String): String? {
        val projectRoot = Paths.get(System.getProperty("user.dir"))
        val sourceRoots = listOf(
            projectRoot.resolve("src/jvmMain/kotlin"),
            projectRoot.resolve("src/personal/kotlin")
        ).filter { Files.exists(it) }
        
        for (root in sourceRoots) {
            val result = searchKDocInDirectory(root, symbolName)
            if (result != null) return result
        }
        
        return null
    }
    
    private fun searchKDocInDirectory(dir: Path, symbolName: String): String? {
        if (!Files.exists(dir)) return null
        
        try {
            Files.walk(dir).use { stream ->
                stream.filter { it.isRegularFile() && it.toString().endsWith(".kt") }
                    .forEach { file ->
                        val kdoc = extractKDocForSymbol(file, symbolName)
                        if (kdoc != null) return kdoc
                    }
            }
        } catch (e: Exception) {
            // Continue searching
        }
        
        return null
    }
}
