package afield

import afield.ai.KotlinSourceIndex
import org.jetbrains.kotlin.com.intellij.lang.Language
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.psi.KtCallExpression
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor

internal object KotlinCompletionSupport {
    private val constructorInfoCache = HashMap<String, ConstructorInfo?>()

    data class ReplacementBounds(val start: Int, val end: Int)

    data class NamedArgumentCompletions(
        val bounds: ReplacementBounds,
        val metadata: List<Metadata>
    )

    private data class ConstructorParameterInfo(
        val name: String,
        val type: String = "",
        val defaultValue: String? = null,
        val documentation: String? = null,
        val optional: Boolean = false,
        val vararg: Boolean = false
    ) {
        fun signature(includeDefault: Boolean = true): String {
            val prefix = buildString {
                if (vararg) append("vararg ")
                append(name)
            }
            val typed = if (type.isBlank()) prefix else "$prefix: ${shortenType(type)}"
            return if (includeDefault && !defaultValue.isNullOrBlank()) "$typed = ${defaultValue!!.trim()}" else typed
        }

        fun namedArgumentPreview(): String =
            if (defaultValue.isNullOrBlank()) "$name = " else "$name = ${defaultValue!!.trim()}"

        fun parameterSummary(): String =
            buildString {
                append("- `").append(signature()).append('`')
                documentation?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    append("  ")
                    append(it)
                }
                if (optional && defaultValue.isNullOrBlank()) {
                    append("  _(default available)_")
                }
            }
    }

    private data class ConstructorInfo(
        val simpleName: String,
        val fqName: String? = null,
        val parameters: List<ConstructorParameterInfo> = emptyList(),
        val classDocumentation: String? = null,
        val sourceFile: String? = null
    ) {
        fun signature(): String =
            "$simpleName(${parameters.joinToString(", ") { it.signature() }})"

        fun namedArgumentSignature(): String =
            "$simpleName(${parameters.joinToString(", ") { it.namedArgumentPreview().trimEnd() }})"

        fun label(maxChars: Int = 180): String {
            val raw = namedArgumentSignature()
            return if (raw.length <= maxChars) raw else raw.take(maxChars - 1) + "..."
        }

        fun namedArgumentBlock(): String = when {
            parameters.isEmpty() -> "$simpleName()"
            parameters.size <= 2 -> "$simpleName(${parameters.joinToString(", ") { it.namedArgumentPreview().trimEnd() }})"
            else -> buildString {
                append(simpleName).append("(\n")
                parameters.forEachIndexed { index, parameter ->
                    append("    ").append(parameter.namedArgumentPreview().trimEnd())
                    if (index != parameters.lastIndex) append(',')
                    append('\n')
                }
                append(')')
            }
        }

        fun documentationMarkdown(existingDocumentation: String = ""): String {
            val sections = mutableListOf<String>()

            sections += buildString {
                append("```kotlin\n")
                append(namedArgumentBlock())
                append("\n```")
            }

            classDocumentation?.trim()?.takeIf { it.isNotEmpty() }?.let { sections += it }

            if (parameters.isNotEmpty()) {
                sections += buildString {
                    append("**Parameters**\n")
                    parameters.forEach {
                        append(it.parameterSummary()).append('\n')
                    }
                }.trimEnd()
            }

            buildList {
                fqName?.takeIf { it.isNotBlank() }?.let { add("**Type** `$it`") }
                sourceFile?.takeIf { it.isNotBlank() }?.let { add("**Source** `$it`") }
            }.takeIf { it.isNotEmpty() }?.let { sections += it.joinToString("  \n") }

            existingDocumentation.trim().takeIf { it.isNotEmpty() }?.let { sections += it }

            return sections.joinToString("\n\n")
        }
    }

    private data class ReflectionConstructorInfo(
        val fqName: String?,
        val parameters: List<ConstructorParameterInfo>
    )

    private data class SourceConstructorInfo(
        val sourceFile: String,
        val classDocumentation: String? = null,
        val parameters: List<ConstructorParameterInfo> = emptyList()
    )

    private data class CallContext(
        val calleeText: String,
        val namedArguments: Set<String>
    )

    fun enrichMetadata(
        box: Box,
        allCode: String,
        metadata: Metadata,
        symbolName: String?
    ): Metadata {
        val name = symbolName
            ?.substringBefore('<')
            ?.substringBefore('(')
            ?.substringAfterLast('.')
            ?.trim()
            ?: metadata.text.substringBefore('<').substringBefore('(').trim()

        if (name.isBlank() || name.firstOrNull()?.isUpperCase() != true) return metadata

        val info = resolveConstructorInfo(box, allCode, name) ?: return metadata
        val documentation = info.documentationMarkdown(metadata.documentation)
        return metadata.copy(
            displayText = info.label(),
            documentation = documentation
        )
    }

    fun namedArgumentCompletions(
        box: Box,
        allCode: String,
        position: Int
    ): NamedArgumentCompletions? {
        val callContext = findCallContext(box.ki, allCode, position) ?: return null
        val calleeName = callContext.calleeText.substringAfterLast('.').trim()
        if (calleeName.isBlank() || calleeName.firstOrNull()?.isUpperCase() != true) return null

        val info = resolveConstructorInfo(box, allCode, calleeName) ?: return null
        if (info.parameters.isEmpty()) return null

        val bounds = getTokenBounds(allCode, position)
        val metadata = info.parameters
            .filterNot { callContext.namedArguments.contains(it.name) }
            .map { parameter ->
                Metadata(
                    icon = "property",
                    tail = shortenType(parameter.type),
                    text = "${parameter.name} = ",
                    displayText = parameter.namedArgumentPreview(),
                    documentation = buildString {
                        append("```kotlin\n")
                        append(parameter.namedArgumentPreview().trimEnd())
                        append("\n```")
                        append("\n\n")
                        append("`").append(parameter.signature()).append("`")
                        parameter.documentation?.trim()?.takeIf { it.isNotEmpty() }?.let {
                            append("\n\n").append(it)
                        }
                        append("\n\n**In** `").append(info.simpleName).append("`")
                    }.trim()
                )
            }

        if (metadata.isEmpty()) return null
        return NamedArgumentCompletions(
            bounds = ReplacementBounds(bounds.start, bounds.end),
            metadata = metadata
        )
    }

    @Synchronized
    private fun resolveConstructorInfo(box: Box, allCode: String, simpleName: String): ConstructorInfo? {
        val cacheKey = buildCacheKey(allCode, simpleName)
        if (constructorInfoCache.containsKey(cacheKey)) return constructorInfoCache[cacheKey]

        val resolved = run {
            val candidates = resolveClassCandidates(allCode, simpleName)
            val sourceInfo = candidates.asSequence()
                .mapNotNull { resolveSourceInfo(simpleName, it) }
                .firstOrNull()
                ?: resolveSourceInfo(simpleName, null)

            val reflectionInfo = candidates.asSequence()
                .mapNotNull { resolveReflectionInfo(box, it) }
                .firstOrNull()

            if (reflectionInfo == null && sourceInfo == null) return@run null

            val mergedNames = LinkedHashSet<String>()
            reflectionInfo?.parameters?.forEach { mergedNames.add(it.name) }
            sourceInfo?.parameters?.forEach { mergedNames.add(it.name) }

            val reflectedByName = reflectionInfo?.parameters?.associateBy { it.name }.orEmpty()
            val sourceByName = sourceInfo?.parameters?.associateBy { it.name }.orEmpty()

            val parameters = mergedNames.mapNotNull { name ->
                val reflected = reflectedByName[name]
                val source = sourceByName[name]
                val merged = ConstructorParameterInfo(
                    name = name,
                    type = reflected?.type ?: source?.type.orEmpty(),
                    defaultValue = source?.defaultValue ?: reflected?.defaultValue,
                    documentation = source?.documentation ?: reflected?.documentation,
                    optional = reflected?.optional ?: source?.optional ?: false,
                    vararg = reflected?.vararg ?: source?.vararg ?: false
                )
                merged.takeIf { it.name.isNotBlank() }
            }

            val fqName = reflectionInfo?.fqName ?: candidates.firstOrNull()
            ConstructorInfo(
                simpleName = simpleName,
                fqName = fqName,
                parameters = parameters,
                classDocumentation = sourceInfo?.classDocumentation,
                sourceFile = sourceInfo?.sourceFile
            )
        }
        constructorInfoCache[cacheKey] = resolved
        return resolved
    }

    private fun buildCacheKey(allCode: String, simpleName: String): String {
        val imports = importCandidates(allCode, simpleName).sorted().joinToString("|")
        return "$simpleName::$imports"
    }

    private fun resolveClassCandidates(allCode: String, simpleName: String): List<String> {
        val ordered = LinkedHashSet<String>()

        importCandidates(allCode, simpleName).forEach { ordered.add(it) }
        findClassesNamed(simpleName)
            ?.sortedWith(
                compareBy<String>(
                    { !it.startsWith("afield.") },
                    { !it.startsWith("kotlin.") },
                    { !it.startsWith("java.") },
                    { it }
                )
            )
            ?.forEach { ordered.add(it) }

        return ordered.toList()
    }

    private fun importCandidates(allCode: String, simpleName: String): List<String> {
        val direct = Regex(
            pattern = "^\\s*import\\s+([A-Za-z0-9_.]+\\.${Regex.escape(simpleName)})\\s*$",
            options = setOf(RegexOption.MULTILINE)
        )
        return direct.findAll(allCode).map { it.groupValues[1] }.distinct().toList()
    }

    private fun resolveReflectionInfo(box: Box, fqName: String): ReflectionConstructorInfo? {
        val classLoader = box.ki.classLoader ?: Thread.currentThread().contextClassLoader
        val clazz = runCatching { Class.forName(fqName, false, classLoader) }.getOrNull() ?: return null
        val kClass = runCatching { clazz.kotlin }.getOrNull() ?: return null
        val constructor = kClass.primaryConstructor ?: kClass.constructors.minByOrNull { it.parameters.size } ?: return null
        val parameters = constructor.parameters
            .filter { it.kind == KParameter.Kind.VALUE && !it.name.isNullOrBlank() }
            .map {
                ConstructorParameterInfo(
                    name = it.name!!,
                    type = it.type.toString(),
                    defaultValue = null,
                    documentation = null,
                    optional = it.isOptional,
                    vararg = it.isVararg
                )
            }

        return ReflectionConstructorInfo(
            fqName = kClass.qualifiedName ?: fqName,
            parameters = parameters
        )
    }

    private fun resolveSourceInfo(simpleName: String, fqName: String?): SourceConstructorInfo? {
        val root = Paths.get(System.getProperty("user.dir")).normalize()
        val candidateFiles = sourceFileCandidates(root, simpleName, fqName)
        candidateFiles.forEach { path ->
            val raw = runCatching { Files.readString(path) }.getOrNull() ?: return@forEach
            val parsed = parseSourceInfo(raw, path, root, simpleName, fqName)
            if (parsed != null) return parsed
        }
        return null
    }

    private fun sourceFileCandidates(root: Path, simpleName: String, fqName: String?): List<Path> {
        val ordered = LinkedHashSet<Path>()
        val index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex()
        index?.definitions
            ?.asSequence()
            ?.filter { it.kind == "class" && it.name.substringAfterLast('.') == simpleName }
            ?.map { root.resolve(it.filePath).normalize() }
            ?.filter { Files.exists(it) }
            ?.forEach { ordered.add(it) }

        if (ordered.isEmpty()) {
            defaultSourceRoots(root).forEach { sourceRoot ->
                if (!Files.exists(sourceRoot)) return@forEach
                Files.walk(sourceRoot).use { paths ->
                    paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                        .forEach { path ->
                            val raw = runCatching { Files.readString(path) }.getOrNull() ?: return@forEach
                            if (Regex("\\bclass\\s+${Regex.escape(simpleName)}\\b").containsMatchIn(raw)) {
                                ordered.add(path)
                            }
                        }
                }
            }
        }

        if (fqName == null || ordered.size <= 1) return ordered.toList()

        val targetPackage = fqName.substringBeforeLast('.', "")
        return ordered.sortedByDescending { path ->
            val filePackage = packageName(runCatching { Files.readString(path) }.getOrDefault(""))
            if (filePackage == targetPackage) 1 else 0
        }
    }

    private fun defaultSourceRoots(root: Path): List<Path> = listOf(
        root.resolve("src/jvmMain/kotlin"),
        root.resolve("src/commonMain/kotlin"),
        root.resolve("src/jsMain/kotlin"),
        root.resolve("src/jsChatMain/kotlin"),
        root.resolve("src/personal/kotlin")
    )

    private fun parseSourceInfo(
        rawText: String,
        path: Path,
        root: Path,
        simpleName: String,
        fqName: String?
    ): SourceConstructorInfo? {
        val packageName = packageName(rawText)
        if (fqName != null) {
            val expectedPackage = fqName.substringBeforeLast('.', "")
            if (expectedPackage.isNotBlank() && packageName != expectedPackage) return null
        }

        val classMatch = Regex("\\bclass\\s+${Regex.escape(simpleName)}\\b").find(rawText) ?: return null
        val classStart = classMatch.range.first
        val classDocumentation = leadingKDoc(rawText, classStart)
        var cursor = classMatch.range.last + 1
        cursor = skipWhitespace(rawText, cursor)
        if (cursor < rawText.length && rawText[cursor] == '<') {
            val genericEnd = matchingDelimiter(rawText, cursor, '<', '>')
            if (genericEnd <= cursor) return null
            cursor = genericEnd + 1
            cursor = skipWhitespace(rawText, cursor)
        }
        if (cursor >= rawText.length || rawText[cursor] != '(') return null

        val close = matchingDelimiter(rawText, cursor, '(', ')')
        if (close <= cursor) return null

        val constructorText = rawText.substring(cursor + 1, close)
        val parameters = splitTopLevelConstructorParameters(constructorText)
            .mapNotNull { parseParameter(it) }

        val relativePath = runCatching { root.relativize(path).toString().replace("\\", "/") }
            .getOrDefault(path.toString())

        return SourceConstructorInfo(
            sourceFile = relativePath,
            classDocumentation = classDocumentation,
            parameters = parameters
        )
    }

    private fun packageName(rawText: String): String {
        val match = Regex("^\\s*package\\s+([A-Za-z0-9_.]+)", RegexOption.MULTILINE).find(rawText)
        return match?.groupValues?.get(1).orEmpty()
    }

    private fun leadingKDoc(rawText: String, declarationStart: Int): String? {
        val prefix = rawText.substring(0, declarationStart)
        val end = prefix.lastIndexOf("*/")
        if (end == -1) return null
        val start = prefix.lastIndexOf("/**", end)
        if (start == -1) return null
        val between = prefix.substring(end + 2)
        if (between.any { !it.isWhitespace() }) return null
        return cleanComment(prefix.substring(start, end + 2))
    }

    private fun splitTopLevelConstructorParameters(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var parenDepth = 0
        var angleDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        var inString = false
        var stringChar = '"'
        var inLineComment = false
        var inBlockComment = false
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (inLineComment) {
                current.append(ch)
                if (ch == '\n') inLineComment = false
                i++
                continue
            }
            if (inBlockComment) {
                current.append(ch)
                if (ch == '*' && i + 1 < raw.length && raw[i + 1] == '/') {
                    current.append('/')
                    inBlockComment = false
                    i += 2
                } else {
                    i++
                }
                continue
            }
            if (inString) {
                current.append(ch)
                if (ch == '\\' && i + 1 < raw.length) {
                    current.append(raw[i + 1])
                    i += 2
                    continue
                }
                if (ch == stringChar) inString = false
                i++
                continue
            }

            if (ch == '/' && i + 1 < raw.length && raw[i + 1] == '/') {
                current.append("//")
                inLineComment = true
                i += 2
                continue
            }
            if (ch == '/' && i + 1 < raw.length && raw[i + 1] == '*') {
                current.append("/*")
                inBlockComment = true
                i += 2
                continue
            }
            if (ch == '"' || ch == '\'') {
                inString = true
                stringChar = ch
                current.append(ch)
                i++
                continue
            }

            when (ch) {
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                '<' -> angleDepth++
                '>' -> if (angleDepth > 0) angleDepth--
                '{' -> braceDepth++
                '}' -> if (braceDepth > 0) braceDepth--
                '[' -> bracketDepth++
                ']' -> if (bracketDepth > 0) bracketDepth--
            }

            if (ch == ',' && parenDepth == 0 && angleDepth == 0 && braceDepth == 0 && bracketDepth == 0) {
                out += current.toString()
                current.setLength(0)
            } else {
                current.append(ch)
            }
            i++
        }

        current.toString().takeIf { it.isNotBlank() }?.let { out += it }
        return out
    }

    private fun parseParameter(rawSegment: String): ConstructorParameterInfo? {
        var segment = rawSegment.trim()
        if (segment.isBlank()) return null

        val documentation = leadingParameterComment(segment)
        segment = removeLeadingComment(segment).trimStart()
        segment = removeLeadingAnnotations(segment).trimStart()

        val words = segment.split(Regex("\\s+"), limit = 6)
        val modifiers = mutableSetOf<String>()
        var consumed = 0
        while (consumed < words.size) {
            val word = words[consumed]
            if (word in setOf("public", "private", "protected", "internal", "val", "var", "vararg", "crossinline", "noinline")) {
                modifiers.add(word)
                consumed++
            } else {
                break
            }
        }

        val remainder = segment.split(Regex("\\s+"), limit = consumed + 1).lastOrNull()?.takeIf { consumed > 0 } ?: segment
        val colon = topLevelIndexOf(remainder, ':')
        if (colon == -1) return null
        val equals = topLevelIndexOf(remainder, '=')
        val name = remainder.substring(0, colon).trim().substringAfterLast(' ').trim()
        if (name.isBlank()) return null
        val typeEnd = if (equals == -1) remainder.length else equals
        val type = remainder.substring(colon + 1, typeEnd).trim()
        val defaultValue = if (equals == -1) null else remainder.substring(equals + 1).trim()

        return ConstructorParameterInfo(
            name = name,
            type = type,
            defaultValue = defaultValue,
            documentation = documentation,
            optional = defaultValue != null,
            vararg = modifiers.contains("vararg")
        )
    }

    private fun leadingParameterComment(segment: String): String? {
        val trimmed = segment.trimStart()
        return when {
            trimmed.startsWith("/**") -> {
                val end = trimmed.indexOf("*/")
                if (end == -1) null else cleanComment(trimmed.substring(0, end + 2))
            }
            trimmed.startsWith("/*") -> {
                val end = trimmed.indexOf("*/")
                if (end == -1) null else cleanComment(trimmed.substring(0, end + 2))
            }
            trimmed.startsWith("//") -> trimmed.lineSequence().firstOrNull()?.removePrefix("//")?.trim()
            else -> null
        }
    }

    private fun removeLeadingComment(segment: String): String {
        val trimmed = segment.trimStart()
        return when {
            trimmed.startsWith("/**") || trimmed.startsWith("/*") -> {
                val end = trimmed.indexOf("*/")
                if (end == -1) trimmed else trimmed.substring(end + 2)
            }
            trimmed.startsWith("//") -> trimmed.substringAfter('\n', "")
            else -> trimmed
        }
    }

    private fun removeLeadingAnnotations(segment: String): String {
        var remaining = segment
        while (remaining.startsWith("@")) {
            var i = 1
            while (i < remaining.length && (remaining[i].isLetterOrDigit() || remaining[i] == '_' || remaining[i] == '.')) i++
            if (i < remaining.length && remaining[i] == '(') {
                val end = matchingDelimiter(remaining, i, '(', ')')
                if (end <= i) break
                i = end + 1
            }
            remaining = remaining.substring(i).trimStart()
        }
        return remaining
    }

    private fun cleanComment(rawComment: String): String {
        return rawComment
            .removePrefix("/**")
            .removePrefix("/*")
            .removeSuffix("*/")
            .lineSequence()
            .map { it.trim().removePrefix("*").trim() }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun findCallContext(ki: KotlinScriptEngine, code: String, position: Int): CallContext? {
        if (code.isBlank()) return null
        // PSI-based call context analysis requires the K1 compiler state
        val k1 = ki as? KotlinInterface ?: return null
        val project = k1.compiler.state.getCompilationState(k1.compilerConfig).environment.project
        val language = Language.findLanguageByID("kotlin") ?: return null
        val file = PsiFileFactory.getInstance(project).createFileFromText(language, code)
        val offsets = buildList {
            val bounded = position.coerceIn(0, code.length)
            add((bounded - 1).coerceAtLeast(0))
            if (bounded < code.length) add(bounded)
        }.distinct()

        offsets.forEach { offset ->
            val element = file.findElementAt(offset) ?: return@forEach
            val call = enclosingCallExpression(element, position) ?: return@forEach
            val calleeText = call.calleeExpression?.text?.trim().orEmpty()
            if (calleeText.isBlank()) return@forEach
            val namedArguments = call.valueArguments
                .mapNotNull { it.getArgumentName()?.asName?.asString() }
                .toSet()
            return CallContext(calleeText, namedArguments)
        }
        return null
    }

    private fun enclosingCallExpression(element: PsiElement, position: Int): KtCallExpression? {
        var current: PsiElement? = element
        while (current != null) {
            if (current is KtCallExpression) {
                val args = current.valueArgumentList
                if (args == null) {
                    current = current.parent
                    continue
                }
                val start = args.textRange.startOffset
                val end = args.textRange.endOffset
                if (position in start..end) return current
            }
            current = current.parent
        }
        return null
    }

    private fun skipWhitespace(text: String, start: Int): Int {
        var cursor = start
        while (cursor < text.length && text[cursor].isWhitespace()) cursor++
        return cursor
    }

    private fun matchingDelimiter(text: String, start: Int, open: Char, close: Char): Int {
        if (start !in text.indices || text[start] != open) return -1
        var depth = 0
        var inString = false
        var stringChar = '"'
        var inLineComment = false
        var inBlockComment = false
        var i = start
        while (i < text.length) {
            val ch = text[i]
            if (inLineComment) {
                if (ch == '\n') inLineComment = false
                i++
                continue
            }
            if (inBlockComment) {
                if (ch == '*' && i + 1 < text.length && text[i + 1] == '/') {
                    inBlockComment = false
                    i += 2
                } else {
                    i++
                }
                continue
            }
            if (inString) {
                if (ch == '\\' && i + 1 < text.length) {
                    i += 2
                    continue
                }
                if (ch == stringChar) inString = false
                i++
                continue
            }

            if (ch == '/' && i + 1 < text.length && text[i + 1] == '/') {
                inLineComment = true
                i += 2
                continue
            }
            if (ch == '/' && i + 1 < text.length && text[i + 1] == '*') {
                inBlockComment = true
                i += 2
                continue
            }
            if (ch == '"' || ch == '\'') {
                inString = true
                stringChar = ch
                i++
                continue
            }

            if (ch == open) depth++
            if (ch == close) {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        return -1
    }

    private fun topLevelIndexOf(text: String, target: Char): Int {
        var parenDepth = 0
        var angleDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        var inString = false
        var stringChar = '"'
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inString) {
                if (ch == '\\' && i + 1 < text.length) {
                    i += 2
                    continue
                }
                if (ch == stringChar) inString = false
                i++
                continue
            }
            if (ch == '"' || ch == '\'') {
                inString = true
                stringChar = ch
                i++
                continue
            }
            when (ch) {
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                '<' -> angleDepth++
                '>' -> if (angleDepth > 0) angleDepth--
                '{' -> braceDepth++
                '}' -> if (braceDepth > 0) braceDepth--
                '[' -> bracketDepth++
                ']' -> if (bracketDepth > 0) bracketDepth--
            }
            if (ch == target && parenDepth == 0 && angleDepth == 0 && braceDepth == 0 && bracketDepth == 0) return i
            i++
        }
        return -1
    }

    private fun shortenType(type: String): String =
        type.replace("(\\b[_a-zA-Z$][_a-zA-Z0-9$]*\\b\\.)+".toRegex(), "")
}
