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
        "const", "lateinit", "companion", "inner",
        // `actual`/`expect` are ordinary modifiers to Kotlin but were missing here, so every
        // documented `actual fun` in a platform source set had its KDoc slid onto the next
        // declaration (caught by afield.docs.KDocAttachmentGuard).
        "actual", "expect"
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
                    var kdocLine = lines[j].trimStart()
                    if (j == i) {
                        if (kdocLine == "/**") {
                            // First line with only /**
                            j++
                            continue
                        }
                        // A one-liner (`/** text */`) or a block whose first line already carries
                        // text: strip the opening marker here, or it survives into the extracted
                        // comment and is rendered literally on completion cards and in the
                        // generated codebase reference.
                        kdocLine = kdocLine.removePrefix("/**").trimStart()
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
                        // A second KDoc block before any declaration means this block documents
                        // nothing in particular — a file-level overview, most often. Stop here, or
                        // this block would be attached to the *next* declaration and, worse, the
                        // outer loop would resume past that declaration's own KDoc and drop it.
                        if (declLineText.startsWith("/**")) {
                            break
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
     * The declaration a single source line declares, as `kind to name`, or null if this line is not
     * a declaration this parser can see.
     *
     * `internal` rather than private because it is the *definition* of which KDoc blocks attach:
     * [extractKDocs] walks forward from a comment until this returns non-null, so a line it cannot
     * read is silently skipped and the comment slides onto whatever parses next.
     * [afield.docs.KDocAttachmentGuard] calls it directly to find those sites before they publish a
     * wrong caption, and must therefore ask exactly the same question this scan asks.
     */
    internal fun parseDeclaration(line: String): Pair<String, String>? {
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
        
        // `constructor(` carries its parameter list with no space, so the keyword has to be cut at the
        // first `(`; no declaration keyword contains one, and a line that is really a call still fails
        // the whitelist below.
        var keyword = tokens[idx].substringBefore('(')
        var remainder = s.substringAfter(keyword, "").trimStart()

        // `fun interface Foo` declares a SAM interface, not a function. Treating it as unparseable
        // dropped the declaration *and* slid its KDoc onto whatever parsed next.
        if (keyword == "fun" && FUN_INTERFACE.containsMatchIn(remainder)) {
            keyword = "interface"
            remainder = remainder.removePrefix("interface").trimStart()
        }

        val name = when (keyword) {
            "fun" -> {
                val head = remainder.substringBefore("(").trim()
                parseQualifiedName(head)
            }
            "class", "interface", "object" -> {
                Regex("^(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)")
                    .find(remainder)?.groupValues?.get(1)?.let { unwrapIdentifier(it) }
            }
            // A `typealias` is a declaration the reference emits (it is in the generator's TYPE_KINDS),
            // so failing to parse it here dropped its KDoc *and* slid the prose onto the next symbol —
            // which is what `linalg.kt`'s Vec2/Vec3/Vec4/Quat aliases were doing until 2026-07-28.
            "typealias" -> parseQualifiedName(remainder.substringBefore("=").trim())
            "val", "var" -> {
                val head = propertyNameHead(remainder)
                parseQualifiedName(head)
            }
            // A secondary constructor carries no name of its own on its line. It is recognised anyway,
            // as itself, so that a KDoc written above one *stops* here instead of sliding onto the next
            // real declaration; no consumer looks up a symbol called `constructor`.
            "constructor" -> "constructor"
            else -> null
        } ?: return null

        if (name.isBlank()) return null

        return when (keyword) {
            "class", "interface", "object", "fun", "val", "var", "typealias", "constructor" ->
                keyword to name
            else -> null
        }
    }
    
    /** `fun` followed by the `interface` keyword — a SAM declaration, not a function. */
    private val FUN_INTERFACE = Regex("""^interface\b""")

    /**
     * The name part of a property declaration's text, with any delegate or initialiser removed.
     *
     * `by` is cut *before* `:` because a delegated property need have no type annotation at all
     * (`var FLine.color by Delegation<Vector4f> { vec(1, 1, 1, 1) }`), in which case there is no `:`
     * to stop at and the whole delegate expression would otherwise be taken for the name — which
     * fails to parse, dropping the declaration and sliding its KDoc onto the next one.
     */
    // ` get(` is cut for the same reason as `:` and `=`: an accessor that follows the name directly,
    // with no explicit type between them, otherwise stays in the head and takes the parentheses with
    // it — `val n get()=x` reduces to `nget()`, which is not an identifier, so the declaration is
    // unparseable and its KDoc slides onto whatever parses next. A type is what normally saves this
    // (`val n: T get()=x` is already cut at the colon), so the gap is invisible until someone writes
    // a documented accessor without one. `DlssReconstruction.sdkAllocatedBytes` was the first.
    private fun propertyNameHead(remainder: String): String =
        remainder.substringBefore(" by ").substringBefore(":").substringBefore(" get(")
            .substringBefore("=").trim()

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
}
