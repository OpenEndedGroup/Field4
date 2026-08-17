package afield

import afield.ai.KotlinSourceIndex
import afield.logger.lprintln
import org.jetbrains.kotlin.com.intellij.lang.Language
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.psi.KtCallExpression
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipFile
import kotlin.reflect.KParameter
import kotlin.reflect.full.primaryConstructor

internal object KotlinCompletionSupport {
    private val constructorInfoCache = HashMap<String, ConstructorInfo?>()
    private val callableInfoCache = HashMap<String, CallableInfo?>()
    private val classpathSourceCache = HashMap<String, List<SourceCallableInfo>>()
    private val classpathClassDocCache = HashMap<String, ReceiverDocumentationInfo?>()
    private val receiverTypealiasDocCache = HashMap<String, ReceiverDocumentationInfo?>()

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
            return if (includeDefault && !defaultValue.isNullOrBlank()) "$typed = ${defaultValue.trim()}" else typed
        }

        fun namedArgumentPreview(): String =
            if (defaultValue.isNullOrBlank()) "$name = " else "$name = ${defaultValue.trim()}"

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
        val sourceFile: String? = null,
        val sourceLine: Int = -1,
        val isObject: Boolean = false
    ) {
        fun signature(): String =
            if (isObject) simpleName else "$simpleName(${parameters.joinToString(", ") { it.signature() }})"

        fun namedArgumentSignature(): String =
            if (isObject) simpleName else "$simpleName(${parameters.joinToString(", ") { it.namedArgumentPreview().trimEnd() }})"

        fun label(): String = namedArgumentSignature()

        fun namedArgumentBlock(): String = when {
            isObject -> simpleName
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

    private data class CallableInfo(
        val name: String,
        val ownerName: String? = null,
        val parameters: List<ConstructorParameterInfo> = emptyList(),
        val returnType: String? = null,
        val documentation: String? = null,
        val sourceFile: String? = null,
        val sourceLine: Int = -1,
        val sourceName: String? = null
    ) {
        private fun typedSignature(includeDefaults: Boolean = true): String {
            val renderedParams = parameters.joinToString(", ") { it.signature(includeDefault = includeDefaults) }
            val suffix = returnType?.takeIf { it.isNotBlank() }?.let { ": ${shortenType(it)}" }.orEmpty()
            return "fun $name($renderedParams)$suffix"
        }

        private fun multilineSignature(): String {
            if (parameters.isEmpty()) return typedSignature()
            val singleLine = typedSignature()
            if (parameters.size <= 2 && singleLine.length <= 110) return singleLine
            return buildString {
                append("fun ").append(name).append("(\n")
                parameters.forEachIndexed { index, parameter ->
                    append("    ").append(parameter.signature())
                    if (index != parameters.lastIndex) append(',')
                    append('\n')
                }
                append(')')
                returnType?.takeIf { it.isNotBlank() }?.let {
                    append(": ").append(shortenType(it))
                }
            }
        }

        fun label(): String = typedSignature(includeDefaults = false)

        fun documentationMarkdown(existingDocumentation: String = ""): String {
            val sections = mutableListOf<String>()

            sections += buildString {
                append("```kotlin\n")
                append(multilineSignature())
                append("\n```")
            }

            documentation?.trim()?.takeIf { it.isNotEmpty() }?.let { sections += it }

            val documentedParameters = parameters.filter { !it.documentation.isNullOrBlank() }
            if (documentedParameters.isNotEmpty()) {
                sections += buildString {
                    append("**Parameters**\n")
                    documentedParameters.forEach { parameter ->
                        append(parameter.parameterSummary()).append('\n')
                    }
                }.trimEnd()
            }

            returnType?.takeIf { it.isNotBlank() }?.let {
                sections += "**Returns** `${shortenType(it)}`"
            }

            buildList {
                ownerName?.takeIf { it.isNotBlank() }?.let { add("**Defined In** `$it`") }
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
        val sourceLine: Int,
        val sourceName: String,
        val isObject: Boolean = false,
        val classDocumentation: String? = null,
        val parameters: List<ConstructorParameterInfo> = emptyList()
    )

    private data class SourceCallableInfo(
        val ownerName: String? = null,
        val sourceFile: String,
        val sourceLine: Int,
        val sourceName: String,
        val documentation: String? = null,
        val parameters: List<ConstructorParameterInfo> = emptyList(),
        val returnType: String? = null
    )

    private data class ReceiverDocumentationInfo(
        val displayName: String,
        val documentation: String,
        val sourceName: String = "",
        val sourcePath: String = "",
        val sourceLine: Int = -1
    )

    private data class IndexedDocumentationTarget(
        val sourceName: String,
        val sourceFile: String,
        val sourceLine: Int,
        val hasDocumentation: Boolean
    )

    private data class ParsedKDoc(
        val body: String? = null,
        val parameterDocs: Map<String, String> = emptyMap()
    )

    private data class CallContext(
        val calleeText: String,
        val namedArguments: Set<String>
    )

    fun enrichMetadata(
        box: Box,
        allCode: String,
        metadata: Metadata,
        symbolName: String?,
        position: Int,
        receiverTypeHint: String? = null,
        requireReceiverOwnerMatch: Boolean = false,
        descriptorText: String? = null
    ): Metadata {
        val name = symbolName
            ?.substringBefore('<')
            ?.substringBefore('(')
            ?.substringAfterLast('.')
            ?.trim()
            ?: metadata.text.substringBefore('<').substringBefore('(').trim()

        if (name.isBlank()) return metadata

        val receiverOwnerHint = inferReceiverOwnerName(box, allCode, position, receiverTypeHint)
            ?: descriptorOwnerName(descriptorText)
        val receiverDocumentation = receiverDocumentationInfo(box, allCode, receiverOwnerHint)

        if (metadata.icon in setOf("property", "val", "var")) {
            val target = resolveIndexedDocumentationTarget(
                simpleName = name,
                receiverOwnerHint = receiverOwnerHint,
                kinds = setOf("val", "var"),
                requireReceiverOwnerMatch = requireReceiverOwnerMatch
            ) ?: return metadata.withReceiverDocumentation(receiverDocumentation)
            return metadata.copy(
                documentation = linkifyKDocSymbols(
                    if (target.hasDocumentation) {
                        metadata.documentation
                    } else {
                        leadingFallbackSignature(metadata.documentation)
                    }
                ),
                generateDocumentation = !target.hasDocumentation,
                documentationSourceName = target.sourceName,
                documentationSourcePath = target.sourceFile,
                documentationSourceLine = target.sourceLine
            ).withReceiverDocumentation(receiverDocumentation)
        }

        if (name.firstOrNull()?.isUpperCase() == true) {
            if (requireReceiverOwnerMatch) return metadata
            val info = resolveConstructorInfo(box, allCode, name) ?: return metadata.withReceiverDocumentation(receiverDocumentation)
            val fallbackDocumentation = withoutLeadingFallbackSignature(metadata.documentation)
            val documentation = info.documentationMarkdown(
                if (info.sourceFile == null) fallbackDocumentation else ""
            )
            return metadata.copy(
                displayText = info.label(),
                documentation = linkifyKDocSymbols(documentation),
                generateDocumentation = info.classDocumentation.isNullOrBlank() && info.sourceFile != null,
                documentationSourceName = info.fqName.orEmpty(),
                documentationSourcePath = info.sourceFile.orEmpty(),
                documentationSourceLine = info.sourceLine
            ).withReceiverDocumentation(receiverDocumentation)
        }

        val callableInfo = resolveCallableInfo(
            box = box,
            allCode = allCode,
            simpleName = name,
            position = position,
            displayText = metadata.displayText,
            tail = metadata.tail,
            receiverOwnerHint = receiverOwnerHint,
            requireReceiverOwnerMatch = requireReceiverOwnerMatch,
            descriptorText = descriptorText
        )

        if (callableInfo == null) return metadata.withReceiverDocumentation(receiverDocumentation)

        val hasSourceDocumentation = !callableInfo.documentation.isNullOrBlank() ||
            callableInfo.parameters.any { !it.documentation.isNullOrBlank() }

        return metadata.copy(
            displayText = callableInfo.label(),
            tail = callableInfo.returnType?.takeIf { it.isNotBlank() }?.let { ": ${shortenType(it)}" } ?: metadata.tail,
            documentation = linkifyKDocSymbols(callableInfo.documentationMarkdown()),
            generateDocumentation = !hasSourceDocumentation && !callableInfo.sourceFile.isNullOrBlank(),
            documentationSourceName = callableInfo.sourceName.orEmpty(),
            documentationSourcePath = callableInfo.sourceFile.orEmpty(),
            documentationSourceLine = callableInfo.sourceLine
        ).withReceiverDocumentation(receiverDocumentation)
    }

    fun enrichReceiverDocumentation(
        box: Box,
        allCode: String,
        metadata: Metadata,
        position: Int,
        receiverTypeHint: String? = null,
        descriptorText: String? = null
    ): Metadata {
        val receiverOwnerHint = inferReceiverOwnerName(box, allCode, position, receiverTypeHint)
            ?: descriptorOwnerName(descriptorText)
        return metadata.withReceiverDocumentation(receiverDocumentationInfo(box, allCode, receiverOwnerHint))
    }

    private fun Metadata.withReceiverDocumentation(info: ReceiverDocumentationInfo?): Metadata {
        if (info == null || info.documentation.isBlank()) return this
        return copy(
            receiverDisplayName = info.displayName,
            receiverDocumentation = info.documentation,
            receiverDocumentationSourceName = info.sourceName,
            receiverDocumentationSourcePath = info.sourcePath,
            receiverDocumentationSourceLine = info.sourceLine
        )
    }

    fun classifyMetadata(
        metadata: Metadata,
        symbolName: String?,
        extraInfo: Map<String, Any?> = emptyMap()
    ): Metadata {
        val name = symbolName
            ?.substringBefore('<')
            ?.substringBefore('(')
            ?.substringAfterLast('.')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: metadata.text.substringBefore('<').substringBefore('(').substringAfterLast('.').trim()

        // The docs index is keyed by symbol NAME, so with no source file to disambiguate it answers for
        // whichever documented `set`/`pos`/`get` it happens to know — it filed a graphics preamble's
        // `Mesh.pos` under Data and `Mesh.set` under Imaging. Where the candidate is actually declared is
        // specific to THIS row, so it wins whenever the docs lookup would be a name-only guess; a lookup
        // backed by a real source file is curated knowledge and still comes first.
        val docsAreSourceBacked =
            metadata.documentationSourceName.isNotBlank() || metadata.documentationSourcePath.isNotBlank()
        val category = normalizedCompletionCategory(metadata.category)
            ?: normalizedCompletionCategory(extraInfo["completionCategory"]?.toString())
            ?: normalizedCompletionCategory(extraInfo["category"]?.toString())
            ?: declarationCategory(extraInfo).takeIf { !docsAreSourceBacked }
            ?: KotlinSourceIndex.getCompletionCategoryForSymbol(
                symbolName = name,
                sourceName = metadata.documentationSourceName.takeIf { it.isNotBlank() },
                sourcePath = metadata.documentationSourcePath.takeIf { it.isNotBlank() },
                // The declaring container turns "what category is the name `scale`?" — a question with 42
                // answers in this tree — into "what category is `FLine.scale`?", which has one.
                declaredIn = extraInfo[AaCompletionClient.DECLARED_IN]?.toString()?.takeIf { it.isNotBlank() }
            )?.let(::normalizedCompletionCategory)
            ?: declarationCategory(extraInfo)
            ?: inferCompletionCategory(metadata, name, extraInfo)
            ?: return metadata

        return if (metadata.category == category) metadata else metadata.copy(category = category)
    }

    fun isHiddenCompletionCategory(category: String): Boolean =
        normalizedCompletionCategory(category) == "Internal"

    fun withCategoryDocumentationHeader(metadata: Metadata): Metadata {
        val category = normalizedCompletionCategory(metadata.category) ?: return metadata
        val existing = metadata.documentation.trim()
        val header = "**Category** `$category`"
        if (existing.startsWith(header)) return metadata
        return metadata.copy(
            documentation = if (existing.isBlank()) header else "$header\n\n$existing"
        )
    }

    private fun inferCompletionCategory(
        metadata: Metadata,
        simpleName: String,
        extraInfo: Map<String, Any?>
    ): String? {
        val declaredIn = extraInfo[AaCompletionClient.DECLARED_IN]?.toString().orEmpty()
        val sourcePath = metadata.documentationSourcePath.lowercase()
        val sourceName = metadata.documentationSourceName.lowercase()
        val tail = metadata.tail.lowercase()
        val text = listOf(
            simpleName,
            metadata.text,
            metadata.displayText,
            metadata.tail,
            metadata.documentationSourceName,
            // The receiver is deliberately absent when the declaration is known — see above.
            if (declaredIn.isBlank()) extraInfo["receiverQualifiedName"]?.toString().orEmpty() else "",
            if (declaredIn.isBlank()) extraInfo["receiverType"]?.toString().orEmpty() else "",
            extraInfo["original"]?.toString().orEmpty()
        ).joinToString(" ").lowercase()

        categoryFromPath(sourcePath)?.let { return it }
        categoryFromQualifiedName(sourceName)?.let { return it }
        // Where the candidate is DECLARED, before anything inferred from its type or its spelling. It was
        // read above only to decide whether the receiver joins `text`, never mapped — so a member of
        // `afield.graphics.FLine` whose own file carries no `@file:CompletionCategory` fell through to
        // guessing from its return type. `categoryFromQualifiedName` already knew the answer.
        categoryFromQualifiedName(declaredIn.lowercase())?.let { return it }
        categoryFromQualifiedName(tail)?.let { return it }
        categoryFromQualifiedName(text)?.let { return it }

        if (metadata.icon == "keyword") return "Kotlin"
        if (sourcePath.startsWith("jar:")) return "External"
        if (tail.startsWith("kotlin.") || text.contains(" defined in kotlin")) return "Kotlin"
        if (tail.startsWith("java.") || tail.startsWith("javax.") || tail.startsWith("org.") || tail.startsWith("com.")) {
            return "External"
        }

        return null
    }

    /**
     * The section a candidate belongs in judged by WHERE IT IS DECLARED, or null when the AA path told us
     * nothing (a K1 result, a keyword, a snippet).
     *
     * This is the fix for section headers that described the receiver instead of the row: the fallback
     * inference blends `receiverQualifiedName` into the text it matches on, so on a `ComputeMesh.` panel
     * the receiver's own package (`afield.four`) matched first and `let`, `also`, `apply` and `equals` were
     * all announced as GRAPHICS. Every row claiming to be graphics is no grouping at all.
     */
    private fun declarationCategory(extraInfo: Map<String, Any?>): String? {
        val declaredIn = extraInfo[AaCompletionClient.DECLARED_IN]?.toString().orEmpty()
        val proximity = extraInfo[AaCompletionClient.DECLARATION_PROXIMITY]?.toString().orEmpty()
        if (declaredIn.isNotBlank()) {
            categoryFromQualifiedName(declaredIn)?.let { return it }
            // A universal declaration we can't place by package is still worth separating: it applies to
            // every receiver, so it belongs with the other generic helpers rather than among this type's.
            if (proximity == "universal") return "Generic"
            return null
        }
        // No declaring package at all, but it came back for a receiver: it was declared in the box's own
        // preamble or body (a `prop<>` binding, a supermap's `Mesh.pos`, an `@exported` value). Those are
        // the most specific names in the panel, and "Local" is both true and the client's top-weighted
        // category.
        return if (proximity.isNotBlank()) "Local" else null
    }

    private fun normalizedCompletionCategory(raw: String?): String? {
        val value = raw?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() } ?: return null
        return when (value.lowercase().replace("_", " ").replace("-", " ")) {
            "math", "maths", "numeric", "numbers", "linear algebra" -> "Math"
            "visualization", "visualisation", "viz", "plot", "plotting" -> "Visualization"
            "graphics", "drawing", "rendering", "shader", "shaders", "vulkan", "geometry" -> "Graphics"
            "internal", "implementation", "system" -> "Internal"
            "audio", "synth", "sound", "music" -> "Audio"
            "data", "io", "files", "file", "json", "csv", "tables" -> "Data"
            "workspace", "document", "documents", "persistence" -> "Workspace"
            "kotlin", "language" -> "Kotlin"
            "generic", "universal" -> "Generic"
            "local", "box" -> "Local"
            "external", "library", "classpath" -> "External"
            "ai", "assistant" -> "AI"
            else -> value.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private fun categoryFromPath(path: String): String? = when {
        path.isBlank() -> null
        path.contains("/afield/math/") -> "Math"
        path.contains("/afield/visualization/") || path.contains("plotly") || path.contains("visualizer") -> "Visualization"
        path.contains("/afield/graphics/") || path.contains("/afield/four/") || path.contains("/afield/stacksl/") -> "Graphics"
        path.contains("/afield/internal/") || path.contains("/afield/server/") || path.contains("/afield/ai/") -> "Internal"
        path.contains("/afield/workspace/") || path.contains("/afield/docs/") -> "Workspace"
        path.contains("/afield/stdlib/") && path.containsAny("math", "linalg", "stats", "random", "correlation", "interpolant") -> "Math"
        path.contains("/afield/stdlib/") && path.containsAny("color", "shader", "drawing", "fline", "stacksl", "voronoi") -> "Graphics"
        path.contains("/afield/stdlib/") && path.containsAny("json", "file", "saved", "graph", "cluster", "wfc", "table") -> "Data"
        path.contains("/afield/stdlib/") && path.containsAny("osc", "audio", "synth") -> "Audio"
        path.startsWith("jar:") && path.contains("kotlin-stdlib") -> "Kotlin"
        path.startsWith("jar:") -> "External"
        else -> null
    }

    private fun categoryFromQualifiedName(value: String): String? = when {
        value.isBlank() -> null
        // `also`/`let`/`apply` are declared in the ROOT `kotlin` package, whose fq name has no trailing
        // dot — matching only "kotlin." filed every one of them as Generic instead.
        value == "kotlin" || value.startsWith("kotlin.") || value.contains(" defined in kotlin") -> "Kotlin"
        value.startsWith("java.") || value.startsWith("javax.") || value.startsWith("org.") || value.startsWith("com.") -> {
            when {
                value.contains("afield.stdlib.linalg") || value.contains("matrix") || value.contains("vector") || value.contains("quaternion") -> "Math"
                else -> "External"
            }
        }
        value.contains("synth") || value.contains("afield.audio") -> "Audio"
        value.contains("afield.math") -> "Math"
        value.contains("afield.visualization") || value.contains("plotly") || value.contains("visualizer") -> "Visualization"
        value.contains("afield.graphics") || value.contains("afield.four") || value.contains("fline") ||
            value.contains("shader") || value.contains("stacksl") || value.contains("color") -> "Graphics"
        value.contains("afield.internal") || value.contains("afield.server") || value.contains("afield.ai") -> "Internal"
        value.contains("afield.workspace") || value.contains("document") -> "Workspace"
        value.containsAny(" sin ", " cos ", " tan ", " sqrt ", " pow ", " abs ", " floor ", " ceil ", " lerp ", " clamp ") -> "Math"
        else -> null
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { contains(it) }

    private fun receiverDocumentationInfo(
        box: Box,
        allCode: String,
        receiverOwnerHint: String?
    ): ReceiverDocumentationInfo? {
        val raw = receiverOwnerHint
            ?.trim()
            ?.substringBefore('<')
            ?.substringBefore('?')
            ?.takeIf { it.isNotBlank() }
            ?: return null

        sourceTypealiasReceiverDocumentation(raw)?.let { return it }

        val classNames = LinkedHashSet<String>()
        resolveReceiverClassNames(allCode, raw).forEach { classNames.add(it) }
        if (classNames.isEmpty()) return null

        val classLoader = box.ki.classLoader ?: Thread.currentThread().contextClassLoader
        return classNames.asSequence()
            .mapNotNull { className ->
                runCatching { Class.forName(className, false, classLoader) }
                    .recoverCatching { classLoader.loadClass(className) }
                    .getOrNull()
            }
            .mapNotNull { classpathSourceClassDoc(it) }
            .firstOrNull()
    }

    private fun sourceTypealiasReceiverDocumentation(typeName: String): ReceiverDocumentationInfo? {
        val raw = typeName.trim().substringBefore('<').substringBefore('?')
        if (receiverTypealiasDocCache.containsKey(raw)) return receiverTypealiasDocCache[raw]
        val resolved = indexedTypealiasReceiverDocumentation(raw) ?: scanSourceTypealiasReceiverDocumentation(raw)
        receiverTypealiasDocCache[raw] = resolved
        return resolved
    }

    private fun scanSourceTypealiasReceiverDocumentation(raw: String): ReceiverDocumentationInfo? {
        val root = Paths.get(System.getProperty("user.dir")).normalize()
        val rawSimple = raw.substringAfterLast('.')
        val candidates = mutableListOf<ReceiverDocumentationInfo>()

        defaultSourceRoots(root).forEach { sourceRoot ->
            if (!Files.exists(sourceRoot)) return@forEach
            Files.walk(sourceRoot).use { paths ->
                paths
                    .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                    .forEach pathLoop@{ path ->
                        val text = runCatching { Files.readString(path) }.getOrNull() ?: return@pathLoop
                        val packageName = packageName(text)
                        typealiasRegex.findAll(text).forEach aliasLoop@{ match ->
                            val alias = match.groupValues[1]
                            val target = match.groupValues[2].trim()
                            val targetSimple = target.substringAfterLast('.')
                            if (alias != rawSimple && target != raw && targetSimple != rawSimple) return@aliasLoop
                            val doc = leadingKDoc(text, match.range.first)?.takeIf { it.isNotBlank() } ?: return@aliasLoop
                            val relativePath = runCatching { root.relativize(path).toString().replace("\\", "/") }
                                .getOrDefault(path.toString())
                            candidates += ReceiverDocumentationInfo(
                                displayName = alias,
                                documentation = doc,
                                sourceName = if (packageName.isBlank()) alias else "$packageName.$alias",
                                sourcePath = relativePath,
                                sourceLine = text.substring(0, match.range.first).count { it == '\n' } + 1
                            )
                        }
                    }
            }
        }

        return candidates.sortedWith(
            compareByDescending<ReceiverDocumentationInfo> { it.sourceName.startsWith("afield.stdlib.") }
                .thenBy { it.sourcePath }
                .thenBy { it.sourceLine }
        ).firstOrNull()
    }

    private fun indexedTypealiasReceiverDocumentation(typeName: String): ReceiverDocumentationInfo? {
        val index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex() ?: return null
        val raw = typeName.trim().substringBefore('<').substringBefore('?')
        val rawSimple = raw.substringAfterLast('.')
        val definition = index.definitions
            .asSequence()
            .filter { it.kind == "typealias" && !it.kdoc.isNullOrBlank() }
            .firstOrNull {
                val alias = it.name.substringAfterLast('.')
                alias == rawSimple || indexedTypealiasTargetMatches(it, raw, rawSimple)
            } ?: return null
        return ReceiverDocumentationInfo(
            displayName = definition.name.substringAfterLast('.'),
            documentation = definition.kdoc.orEmpty(),
            sourceName = definition.name,
            sourcePath = definition.filePath,
            sourceLine = definition.line
        )
    }

    private fun indexedTypealiasTargetMatches(
        definition: afield.ai.KotlinDefinitionRecord,
        raw: String,
        rawSimple: String
    ): Boolean {
        val root = Paths.get(System.getProperty("user.dir")).normalize()
        val path = root.resolve(definition.filePath).normalize()
        val text = runCatching { Files.readString(path) }.getOrNull() ?: return false
        val lineStart = lineStartOffsets(text).getOrNull((definition.line - 1).coerceAtLeast(0)) ?: return false
        val lineEnd = text.indexOf('\n', lineStart).takeIf { it >= 0 } ?: text.length
        val line = text.substring(lineStart, lineEnd)
        val match = typealiasRegex.find(line) ?: return false
        val target = match.groupValues[2].trim()
        return target == raw || target.substringAfterLast('.') == rawSimple
    }

    private val typealiasRegex =
        Regex("""\btypealias\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*([A-Za-z_][A-Za-z0-9_.]*)""")

    private fun resolveIndexedDocumentationTarget(
        simpleName: String,
        receiverOwnerHint: String?,
        kinds: Set<String>,
        requireReceiverOwnerMatch: Boolean
    ): IndexedDocumentationTarget? {
        val index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex() ?: return null
        val candidates = index.definitions.filter {
            it.kind in kinds && it.name.substringAfterLast('.') == simpleName
        }
        val definition = (if (!receiverOwnerHint.isNullOrBlank()) {
            candidates.firstOrNull { ownerMatches(indexedOwnerName(it.name), receiverOwnerHint) }
        } else if (requireReceiverOwnerMatch) {
            null
        } else {
            candidates.singleOrNull() ?: candidates.firstOrNull { it.name == simpleName }
        }) ?: return null

        return IndexedDocumentationTarget(
            sourceName = definition.name,
            sourceFile = definition.filePath,
            sourceLine = definition.line,
            hasDocumentation = !definition.kdoc.isNullOrBlank()
        )
    }

    private fun withoutLeadingFallbackSignature(documentation: String): String {
        val trimmed = documentation.trim()
        if (!trimmed.startsWith("```kotlin")) return trimmed
        val closingFence = trimmed.indexOf("```", startIndex = "```kotlin".length)
        if (closingFence == -1) return trimmed
        return trimmed.substring(closingFence + 3).trim()
    }

    private fun leadingFallbackSignature(documentation: String): String {
        val trimmed = documentation.trim()
        if (!trimmed.startsWith("```kotlin")) return trimmed
        val closingFence = trimmed.indexOf("```", startIndex = "```kotlin".length)
        if (closingFence == -1) return trimmed
        return trimmed.substring(0, closingFence + 3).trim()
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
                ?.takeUnless { reflected ->
                    sourceInfo != null &&
                        reflected.fqName != null &&
                        reflected.fqName != sourceInfo.sourceName
                }

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

            val fqName = sourceInfo?.sourceName ?: reflectionInfo?.fqName ?: candidates.firstOrNull()
            ConstructorInfo(
                simpleName = simpleName,
                fqName = fqName,
                parameters = parameters,
                classDocumentation = sourceInfo?.classDocumentation,
                sourceFile = sourceInfo?.sourceFile,
                sourceLine = sourceInfo?.sourceLine ?: -1,
                isObject = sourceInfo?.isObject ?: false
            )
        }
        constructorInfoCache[cacheKey] = resolved
        return resolved
    }

    @Synchronized
    private fun resolveCallableInfo(
        box: Box,
        allCode: String,
        simpleName: String,
        position: Int,
        displayText: String,
        tail: String,
        receiverOwnerHint: String?,
        requireReceiverOwnerMatch: Boolean,
        descriptorText: String?
    ): CallableInfo? {
        if (requireReceiverOwnerMatch && receiverOwnerHint.isNullOrBlank()) return null
        val cacheKey = buildCallableCacheKey(
            allCode,
            simpleName,
            receiverOwnerHint,
            displayText,
            descriptorText,
            tail,
            requireReceiverOwnerMatch
        )
        if (callableInfoCache.containsKey(cacheKey)) return callableInfoCache[cacheKey]

        val resolved = resolveSourceCallableInfo(
            allCode = allCode,
            simpleName = simpleName,
            receiverOwnerHint = receiverOwnerHint,
            requireReceiverOwnerMatch = requireReceiverOwnerMatch,
            displayText = displayText,
            descriptorText = descriptorText,
            tail = tail
        ) ?: resolveClasspathCallableInfo(
            box = box,
            allCode = allCode,
            simpleName = simpleName,
            receiverOwnerHint = receiverOwnerHint,
            requireReceiverOwnerMatch = requireReceiverOwnerMatch,
            displayText = displayText,
            descriptorText = descriptorText,
            tail = tail
        )

        val callable = resolved?.let {
            CallableInfo(
                name = simpleName,
                ownerName = it.ownerName,
                parameters = it.parameters,
                returnType = it.returnType,
                documentation = it.documentation,
                sourceFile = it.sourceFile,
                sourceLine = it.sourceLine,
                sourceName = it.sourceName
            )
        }

        callableInfoCache[cacheKey] = callable
        return callable
    }

    private fun buildCacheKey(allCode: String, simpleName: String): String {
        val imports = importCandidates(allCode, simpleName).sorted().joinToString("|")
        return "$simpleName::$imports"
    }

    private fun buildCallableCacheKey(
        allCode: String,
        simpleName: String,
        receiverOwnerHint: String?,
        displayText: String,
        descriptorText: String?,
        tail: String,
        requireReceiverOwnerMatch: Boolean
    ): String {
        val imports = importCandidates(allCode, simpleName).sorted().joinToString("|")
        return listOf(
            simpleName,
            receiverOwnerHint.orEmpty(),
            displayText,
            descriptorText.orEmpty(),
            tail.trim(),
            requireReceiverOwnerMatch.toString(),
            imports
        ).joinToString("::")
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

    private fun resolveSourceCallableInfo(
        allCode: String,
        simpleName: String,
        receiverOwnerHint: String?,
        requireReceiverOwnerMatch: Boolean,
        displayText: String,
        descriptorText: String?,
        tail: String
    ): SourceCallableInfo? {
        val index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex() ?: return null
        val root = Paths.get(System.getProperty("user.dir")).normalize()
        val signatureText = descriptorText?.takeIf { it.isNotBlank() } ?: displayText
        val expectedParamCount = parameterCountHint(signatureText)
        val expectedParamTypes = parameterTypeHints(signatureText)
        val expectedReturnType = tail.removePrefix(":").trim().ifBlank { null }
        val hasSignatureHints = expectedParamCount != null || !expectedParamTypes.isNullOrEmpty()

        val candidates = index.definitions
            .asSequence()
            .filter { it.kind == "fun" && it.name.substringAfterLast('.') == simpleName }
            .mapNotNull { definition ->
                parseSourceCallableInfo(definition, root, simpleName)?.let { definition to it }
            }
            .filter { (_, info) ->
                !requireReceiverOwnerMatch || ownerMatches(info.ownerName, receiverOwnerHint) || hasSignatureHints
            }
            .toList()

        if (candidates.isEmpty()) return null

        val scored = candidates.map { (definition, info) ->
            scoreCallableCandidate(
                definitionName = definition.name,
                info = info,
                receiverOwnerHint = receiverOwnerHint,
                expectedParamCount = expectedParamCount,
                expectedParamTypes = expectedParamTypes,
                expectedReturnType = expectedReturnType,
                displayText = displayText,
                allCode = allCode
            ) to info
        }.sortedByDescending { it.first }

        val best = scored.firstOrNull() ?: return null
        if (best.first <= 0) return null
        return mergeCallableInfoFromCompatibleBase(best.second, candidates.map { it.second })
    }

    private fun mergeCallableInfoFromCompatibleBase(
        selected: SourceCallableInfo,
        candidates: List<SourceCallableInfo>
    ): SourceCallableInfo {
        val needsDefaults = selected.parameters.any { it.defaultValue.isNullOrBlank() }
        val needsDocumentation = selected.documentation.isNullOrBlank() ||
            selected.parameters.any { it.documentation.isNullOrBlank() }
        if (!needsDefaults && !needsDocumentation) return selected

        val base = candidates.asSequence()
            .filter { it !== selected }
            .filter { callableShapeMatches(selected, it) }
            .filter { ownerIsAssignableFrom(it.ownerName.orEmpty(), selected.ownerName.orEmpty()) }
            .sortedByDescending { inherited ->
                var score = 0
                if (!inherited.documentation.isNullOrBlank()) score += 20
                score += inherited.parameters.count { !it.defaultValue.isNullOrBlank() } * 3
                score += inherited.parameters.count { !it.documentation.isNullOrBlank() }
                score
            }
            .firstOrNull()
            ?: return selected

        return selected.copy(
            documentation = selected.documentation?.takeIf { it.isNotBlank() } ?: base.documentation,
            parameters = selected.parameters.mapIndexed { index, parameter ->
                val inherited = base.parameters.getOrNull(index) ?: return@mapIndexed parameter
                parameter.copy(
                    defaultValue = parameter.defaultValue?.takeIf { it.isNotBlank() } ?: inherited.defaultValue,
                    documentation = parameter.documentation?.takeIf { it.isNotBlank() } ?: inherited.documentation,
                    optional = parameter.optional || inherited.optional || inherited.defaultValue != null
                )
            }
        )
    }

    private fun callableShapeMatches(
        selected: SourceCallableInfo,
        candidate: SourceCallableInfo
    ): Boolean {
        if (selected.parameters.size != candidate.parameters.size) return false
        selected.returnType?.let { selectedReturn ->
            candidate.returnType?.let { candidateReturn ->
                if (!parameterTypesMatch(candidateReturn, selectedReturn)) return false
            }
        }
        return selected.parameters.indices.all { index ->
            val left = selected.parameters[index].type
            val right = candidate.parameters[index].type
            left.isBlank() || right.isBlank() || parameterTypesMatch(left, right)
        }
    }

    private fun resolveClasspathCallableInfo(
        box: Box,
        allCode: String,
        simpleName: String,
        receiverOwnerHint: String?,
        requireReceiverOwnerMatch: Boolean,
        displayText: String,
        descriptorText: String?,
        tail: String
    ): SourceCallableInfo? {
        if (receiverOwnerHint.isNullOrBlank()) return null
        val signatureText = descriptorText?.takeIf { it.isNotBlank() } ?: displayText
        val expectedParamCount = parameterCountHint(signatureText)
        val expectedParamTypes = parameterTypeHints(signatureText)
        val expectedReturnType = tail.removePrefix(":").trim().ifBlank { null }
        val owners = resolveReceiverClassNames(allCode, receiverOwnerHint)
        if (owners.isEmpty()) {
            lprintln("info,completion", "completion classpath-source miss: no class candidates for receiver=$receiverOwnerHint symbol=$simpleName")
            return null
        }

        val classLoader = box.ki.classLoader ?: Thread.currentThread().contextClassLoader
        val candidates = owners.asSequence()
            .mapNotNull { ownerName ->
                val clazz = runCatching { Class.forName(ownerName, false, classLoader) }
                    .recoverCatching { classLoader.loadClass(ownerName) }
                    .getOrNull()
                if (clazz == null) {
                    lprintln("info,completion", "completion classpath-source miss: could not load receiver=$ownerName symbol=$simpleName")
                    null
                } else {
                    classpathSourceMethods(clazz, simpleName)
                }
            }
            .flatten()
            .filter { !requireReceiverOwnerMatch || ownerMatches(it.ownerName, receiverOwnerHint) }
            .toList()

        if (candidates.isEmpty()) {
            lprintln(
                "info,completion",
                "completion classpath-source miss: no source methods receiver=$receiverOwnerHint owners=${owners.take(4)} symbol=$simpleName display='$displayText' tail='$tail'"
            )
            return null
        }

        val scored = candidates.map { info ->
            scoreCallableCandidate(
                definitionName = info.sourceName,
                info = info,
                receiverOwnerHint = receiverOwnerHint,
                expectedParamCount = expectedParamCount,
                expectedParamTypes = expectedParamTypes,
                expectedReturnType = expectedReturnType,
                displayText = displayText,
                allCode = allCode
            ) to info
        }.sortedByDescending { it.first }

        val best = scored.firstOrNull() ?: return null
        if (best.first <= 0) {
            lprintln(
                "info,completion",
                "completion classpath-source miss: candidates scored <=0 receiver=$receiverOwnerHint symbol=$simpleName best=${best.first} display='$displayText'"
            )
            return null
        }
        return best.second
    }

    private fun resolveReceiverClassNames(allCode: String, receiverOwnerHint: String): List<String> {
        val raw = receiverOwnerHint.trim().substringBefore('<').substringBefore('?')
        if (raw.isBlank()) return emptyList()
        if ('.' in raw) return listOf(raw)

        val imported = Regex(
            pattern = "^\\s*import\\s+([A-Za-z0-9_.]+\\.${Regex.escape(raw)})\\s*$",
            options = setOf(RegexOption.MULTILINE)
        ).findAll(allCode).map { it.groupValues[1] }.toList()
        if (imported.isNotEmpty()) return imported

        return findClassesNamed(raw)
            ?.filter { '$' !in it }
            ?.sortedWith(
                compareBy<String>(
                    { !it.startsWith("afield.") },
                    { it }
                )
            )
            .orEmpty()
    }

    private fun classpathSourceMethods(clazz: Class<*>, simpleName: String): List<SourceCallableInfo> {
        val key = "${clazz.name}#$simpleName"
        classpathSourceCache[key]?.let { return it }
        val loaded = loadClasspathSourceMethods(clazz, simpleName)
        classpathSourceCache[key] = loaded
        return loaded
    }

    private fun classpathSourceClassDoc(clazz: Class<*>): ReceiverDocumentationInfo? {
        val key = clazz.name
        if (classpathClassDocCache.containsKey(key)) return classpathClassDocCache[key]
        val loaded = loadClasspathSourceClassDoc(clazz)
        classpathClassDocCache[key] = loaded
        return loaded
    }

    private fun loadClasspathSourceMethods(clazz: Class<*>, simpleName: String): List<SourceCallableInfo> {
        val source = classpathSourceFor(clazz) ?: return emptyList()
        return source.use { (zip, entryName, sourcePath) ->
            val entry = zip.getEntry(entryName) ?: return@use emptyList()
            val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            parseJavaSourceMethods(
                text = text,
                ownerName = clazz.name,
                simpleName = simpleName,
                sourcePath = sourcePath
            )
        }
    }

    private fun loadClasspathSourceClassDoc(clazz: Class<*>): ReceiverDocumentationInfo? {
        val source = classpathSourceFor(clazz) ?: return null
        return source.use { (zip, entryName, sourcePath) ->
            val entry = zip.getEntry(entryName) ?: return@use null
            val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            parseJavaSourceClassDoc(
                text = text,
                ownerName = clazz.name,
                sourcePath = sourcePath
            )
        }
    }

    private data class OpenClasspathSource(
        val zip: ZipFile,
        val entryName: String,
        val sourcePath: String
    ) : AutoCloseable {
        override fun close() {
            zip.close()
        }
    }

    private fun classpathSourceFor(clazz: Class<*>): OpenClasspathSource? {
        val codeSource = clazz.protectionDomain?.codeSource?.location ?: return null
        val binaryPath = runCatching { Paths.get(codeSource.toURI()) }.getOrNull() ?: return null
        if (!Files.isRegularFile(binaryPath) || binaryPath.fileName.toString().endsWith("-sources.jar")) return null

        val sourceJarName = binaryPath.fileName.toString().removeSuffix(".jar") + "-sources.jar"
        val searchRoot = binaryPath.parent?.parent ?: binaryPath.parent ?: return null
        val sourceJar = runCatching {
            Files.walk(searchRoot, 3).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString() == sourceJarName }
                    .findFirst()
                    .orElse(null)
            }
        }.getOrNull() ?: return null

        val entryBase = clazz.name.substringBefore('$').replace('.', '/')
        val zip = ZipFile(sourceJar.toFile())
        val entryName = listOf("$entryBase.java", "$entryBase.kt")
            .firstOrNull { zip.getEntry(it) != null }
        if (entryName == null) {
            zip.close()
            return null
        }
        return OpenClasspathSource(
            zip = zip,
            entryName = entryName,
            sourcePath = "jar:${sourceJar.toUri()}!/$entryName"
        )
    }

    private fun parseJavaSourceMethods(
        text: String,
        ownerName: String,
        simpleName: String,
        sourcePath: String
    ): List<SourceCallableInfo> {
        val out = mutableListOf<SourceCallableInfo>()
        val lines = text.split('\n')
        var pendingDoc: ParsedKDoc? = null
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("/**")) {
                val docLines = mutableListOf<String>()
                var done = false
                while (i < lines.size) {
                    val line = lines[i].trim()
                    docLines += line
                    if (line.contains("*/")) {
                        done = true
                        break
                    }
                    i++
                }
                pendingDoc = if (done) parseJavaDoc(docLines.joinToString("\n")) else null
                i++
                continue
            }

            if (trimmed.isBlank() || trimmed.startsWith("@")) {
                i++
                continue
            }

            parseJavaMethodLine(trimmed, simpleName)?.let { method ->
                val doc = pendingDoc ?: ParsedKDoc()
                out += SourceCallableInfo(
                    ownerName = ownerName.substringAfterLast('.'),
                    sourceFile = sourcePath,
                    sourceLine = i + 1,
                    sourceName = "$ownerName.$simpleName",
                    documentation = doc.body,
                    parameters = method.parameters.map { parameter ->
                        parameter.copy(documentation = doc.parameterDocs[parameter.name] ?: parameter.documentation)
                    },
                    returnType = method.returnType
                )
            }
            pendingDoc = null
            i++
        }
        return out
    }

    private fun parseJavaSourceClassDoc(
        text: String,
        ownerName: String,
        sourcePath: String
    ): ReceiverDocumentationInfo? {
        val simpleName = ownerName.substringAfterLast('.').substringBefore('$')
        val lines = text.split('\n')
        var pendingDoc: ParsedKDoc? = null
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("/**")) {
                val docLines = mutableListOf<String>()
                var done = false
                while (i < lines.size) {
                    val line = lines[i].trim()
                    docLines += line
                    if (line.contains("*/")) {
                        done = true
                        break
                    }
                    i++
                }
                pendingDoc = if (done) parseJavaDoc(docLines.joinToString("\n")) else null
                i++
                continue
            }

            if (trimmed.isBlank() || trimmed.startsWith("@")) {
                i++
                continue
            }

            val isClassDeclaration = Regex("""\b(class|interface|enum)\s+${Regex.escape(simpleName)}\b""")
                .containsMatchIn(trimmed)
            if (isClassDeclaration) {
                val doc = pendingDoc?.body?.trim().orEmpty()
                if (doc.isBlank()) return null
                return ReceiverDocumentationInfo(
                    displayName = simpleName,
                    documentation = doc,
                    sourceName = ownerName,
                    sourcePath = sourcePath,
                    sourceLine = i + 1
                )
            }

            pendingDoc = null
            i++
        }
        return null
    }

    private data class ParsedJavaMethod(
        val returnType: String,
        val parameters: List<ConstructorParameterInfo>
    )

    private fun parseJavaMethodLine(line: String, simpleName: String): ParsedJavaMethod? {
        if (!line.contains("$simpleName(")) return null
        val beforeParen = line.substringBefore('(').trim()
        val afterParen = line.substringAfter('(', "")
        if (afterParen.isBlank()) return null
        val rawName = beforeParen.substringAfterLast(' ')
        if (rawName != simpleName) return null
        if (beforeParen.contains("=")) return null

        val returnType = beforeParen
            .split(Regex("\\s+"))
            .asReversed()
            .drop(1)
            .firstOrNull { it !in javaMethodModifiers }
            ?: return null
        val closeIndex = line.indexOf(')', line.indexOf('('))
        if (closeIndex == -1) return null
        val parameterText = line.substring(line.indexOf('(') + 1, closeIndex)
        val parameters = splitTopLevelConstructorParameters(parameterText)
            .mapNotNull { parseJavaParameter(it) }
        return ParsedJavaMethod(returnType = javaTypeToKotlinish(returnType), parameters = parameters)
    }

    private val javaMethodModifiers = setOf(
        "public", "protected", "private", "static", "final", "abstract", "synchronized", "native",
        "strictfp", "default", "transient", "volatile"
    )

    private fun parseJavaParameter(raw: String): ConstructorParameterInfo? {
        var text = raw.trim()
        if (text.isBlank()) return null
        text = text.replace(Regex("@[A-Za-z0-9_.]+(\\([^)]*\\))?\\s*"), "")
        text = text.replace(Regex("\\bfinal\\s+"), "").trim()
        val name = text.substringAfterLast(' ').trim().removePrefix("...")
        val type = text.substringBeforeLast(' ', "").trim().replace("...", "...")
        if (name.isBlank() || type.isBlank()) return null
        return ConstructorParameterInfo(
            name = name,
            type = javaTypeToKotlinish(type),
            vararg = "..." in type
        )
    }

    private fun javaTypeToKotlinish(raw: String): String {
        val arrays = raw.count { it == '[' }
        val base = raw.replace("[]", "").replace("...", "").trim()
        val mapped = when (base) {
            "boolean" -> "Boolean"
            "byte" -> "Byte"
            "char" -> "Char"
            "double" -> "Double"
            "float" -> "Float"
            "int" -> "Int"
            "long" -> "Long"
            "short" -> "Short"
            "void" -> "Unit"
            else -> base.substringAfterLast('.')
        }
        return if (arrays > 0) "Array<$mapped>" else mapped
    }

    private fun parseJavaDoc(raw: String): ParsedKDoc {
        val body = mutableListOf<String>()
        val parameterDocs = linkedMapOf<String, StringBuilder>()
        var activeParameter: String? = null
        raw.lineSequence()
            .map { line ->
                line.trim()
                    .removePrefix("/**")
                    .removeSuffix("*/")
                    .trim()
                    .removePrefix("*")
                    .trim()
            }
            .forEach { line ->
                val parameterMatch = Regex("""^@param\s+([A-Za-z_][A-Za-z0-9_]*)\s*(.*)$""").matchEntire(line)
                when {
                    line.isBlank() -> {
                        activeParameter = null
                        if (body.lastOrNull()?.isNotBlank() == true) body += ""
                    }
                    parameterMatch != null -> {
                        val name = parameterMatch.groupValues[1]
                        parameterDocs.getOrPut(name) { StringBuilder() }
                            .append(cleanJavaDocMarkdown(parameterMatch.groupValues[2].trim()))
                        activeParameter = name
                    }
                    line.startsWith("@return") -> {
                        activeParameter = null
                    }
                    line.startsWith("@") -> {
                        activeParameter = null
                    }
                    activeParameter != null -> {
                        parameterDocs.getValue(activeParameter).append(' ').append(cleanJavaDocMarkdown(line))
                    }
                    else -> {
                        activeParameter = null
                        body += cleanJavaDocMarkdown(line)
                    }
                }
            }
        return ParsedKDoc(
            body = body.joinToString("\n").trim().ifBlank { null },
            parameterDocs = parameterDocs.mapValues { it.value.toString().trim() }
        )
    }

    private fun cleanJavaDocMarkdown(raw: String): String =
        raw.replace(Regex("<code>(.*?)</code>"), "`$1`")
            .replace("<p>", "\n\n")
            .replace("</p>", "")
            .replace(Regex("<[^>]+>"), "")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

    private fun parseSourceCallableInfo(
        definition: afield.ai.KotlinDefinitionRecord,
        root: Path,
        simpleName: String
    ): SourceCallableInfo? {
        val path = root.resolve(definition.filePath).normalize()
        if (!Files.exists(path)) return null
        val rawText = runCatching { Files.readString(path) }.getOrNull() ?: return null
        val lineOffsets = lineStartOffsets(rawText)
        val lineStart = lineOffsets.getOrNull((definition.line - 1).coerceAtLeast(0)) ?: return null
        val funIndex = rawText.indexOf("fun", lineStart).takeIf { it >= 0 } ?: return null
        val parenOpen = rawText.indexOf('(', funIndex).takeIf { it > funIndex } ?: return null
        val close = matchingDelimiter(rawText, parenOpen, '(', ')')
        if (close <= parenOpen) return null

        val header = rawText.substring(funIndex, close)
        val headerName = header.substringAfter("fun").trim()
        val rawCallableName = headerName.substringBefore('(').substringAfterLast(' ').trim()
        val parsedName = rawCallableName.substringAfterLast('.').trim()
        if (parsedName != simpleName) return null

        val parsedKDoc = parseKDoc(definition.kdoc)
        val parameters = splitTopLevelConstructorParameters(rawText.substring(parenOpen + 1, close))
            .mapNotNull { parseParameter(it) }
            .map { parameter ->
                parameter.copy(
                    documentation = parsedKDoc.parameterDocs[parameter.name] ?: parameter.documentation
                )
            }

        var cursor = skipWhitespace(rawText, close + 1)
        var returnType: String? = null
        if (cursor < rawText.length && rawText[cursor] == ':') {
            cursor++
            val end = findFunctionHeaderEnd(rawText, cursor)
            returnType = rawText.substring(cursor, end).trim().ifBlank { null }
        } else if (cursor < rawText.length && rawText[cursor] == '=') {
            val expressionStart = skipWhitespace(rawText, cursor + 1)
            returnType = inferExpressionBodyReturnType(rawText, expressionStart)
        }

        val relativePath = runCatching { root.relativize(path).toString().replace("\\", "/") }
            .getOrDefault(path.toString())
        val ownerName = rawCallableName.substringBeforeLast('.', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?.substringAfterLast('.')
            ?: definition.name.substringBeforeLast('.', "").ifBlank { null }

        return SourceCallableInfo(
            ownerName = ownerName,
            sourceFile = relativePath,
            sourceLine = definition.line,
            sourceName = definition.name,
            documentation = parsedKDoc.body,
            parameters = parameters,
            returnType = returnType
        )
    }

    private fun inferExpressionBodyReturnType(rawText: String, expressionStart: Int): String? {
        if (expressionStart !in rawText.indices) return null
        val snippet = rawText.substring(expressionStart, rawText.length.coerceAtMost(expressionStart + 240))
        val constructorLike = Regex("""^([A-Za-z_][A-Za-z0-9_.]*)\s*(?:<[^(){}=\n]*>)?\s*\(""")
            .find(snippet)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.substringAfterLast('.').firstOrNull()?.isUpperCase() == true }
        return constructorLike
    }

    private fun parseKDoc(kdoc: String?): ParsedKDoc {
        if (kdoc.isNullOrBlank()) return ParsedKDoc()

        val body = mutableListOf<String>()
        val parameterDocs = linkedMapOf<String, StringBuilder>()
        var activeParameter: String? = null

        kdoc.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            val parameterMatch = Regex("""^\s*@param\s+([A-Za-z_][A-Za-z0-9_]*)\s*(.*)$""").matchEntire(line)
            when {
                parameterMatch != null -> {
                    val name = parameterMatch.groupValues[1]
                    parameterDocs.getOrPut(name) { StringBuilder() }
                        .append(parameterMatch.groupValues[2].trim())
                    activeParameter = name
                }
                line.trimStart().startsWith("@") -> activeParameter = null
                activeParameter != null && line.isNotBlank() -> {
                    parameterDocs.getValue(activeParameter).append(' ').append(line.trim())
                }
                else -> {
                    activeParameter = null
                    body += line
                }
            }
        }

        return ParsedKDoc(
            body = body.joinToString("\n").trim().ifBlank { null },
            parameterDocs = parameterDocs.mapValues { it.value.toString().trim() }
        )
    }

    // ---- Source-card navigation (documentation-panel code links) -----------

    @Volatile
    private var cardIndexRef: afield.ai.KotlinSourceIndexDb? = null

    @Volatile
    private var cardSimpleNameLookup: Map<String, List<afield.ai.KotlinDefinitionRecord>> = emptyMap()

    private fun simpleNameLookup(): Map<String, List<afield.ai.KotlinDefinitionRecord>> {
        val index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex() ?: return emptyMap()
        if (cardIndexRef !== index) {
            cardSimpleNameLookup = index.definitions.groupBy { it.name.substringAfterLast('.') }
            cardIndexRef = index
        }
        return cardSimpleNameLookup
    }

    private fun cardKindRank(kind: String): Int = when (kind) {
        "typealias" -> 5
        "class", "object", "interface" -> 4
        "fun" -> 3
        "val", "var" -> 2
        else -> 1
    }

    private val cardCandidateComparator =
        compareByDescending<afield.ai.KotlinDefinitionRecord> { cardKindRank(it.kind) }
            .thenByDescending { it.name.startsWith("afield.stdlib.") }
            .thenBy { it.filePath }
            .thenBy { it.line }

    private fun bestDefinitionForSimpleName(simpleName: String): afield.ai.KotlinDefinitionRecord? {
        if (simpleName.isBlank()) return null
        return simpleNameLookup()[simpleName]?.minWithOrNull(cardCandidateComparator)
    }

    /**
     * Resolve a KDoc `[Symbol]` reference to a project source location, or null if
     * it isn't a known project declaration. Increment 1 covers the project index +
     * typealiases only, so classpath-only types (e.g. `kotlin.Float`) stay plain text
     * rather than becoming dead links.
     *
     * [reference] may be qualified (`Foo.bar`): resolution keys on the last segment,
     * but a qualifier is *verified* against each candidate's owner so a bare `bar`
     * declared under an unrelated type never becomes a wrong link for `[Foo.bar]`.
     * An unverifiable qualifier resolves to null (left literal), never a wrong link.
     */
    private fun resolveSymbolLocation(reference: String): IndexedDocumentationTarget? {
        if (reference.isBlank()) return null
        val simpleName = reference.substringAfterLast('.')
        val d = if (reference.contains('.')) {
            val owner = reference.substringBeforeLast('.').substringAfterLast('.')
            simpleNameLookup()[simpleName]
                ?.filter { it.name.substringBeforeLast('.', "").substringAfterLast('.') == owner }
                ?.minWithOrNull(cardCandidateComparator)
        } else {
            bestDefinitionForSimpleName(simpleName)
        } ?: return null
        return IndexedDocumentationTarget(
            sourceName = d.name,
            sourceFile = d.filePath,
            sourceLine = d.line,
            hasDocumentation = !d.kdoc.isNullOrBlank()
        )
    }

    // Matches either a KDoc `[display][Target]` alternate-link (group 1 = display
    // text, group 2 = target symbol) or a bare `[Ident]` reference (group 3).
    private val kdocLinkRegex =
        Regex("""\[([^\[\]]+)]\[([A-Za-z_][A-Za-z0-9_.]*)]|\[([A-Za-z_][A-Za-z0-9_.]*)]""")

    private fun sourceCardHref(name: String, path: String, line: Int): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "field-source:?path=${enc(path)}&line=$line&name=${enc(name)}"
    }

    /**
     * Rewrite bare KDoc `[Symbol]` cross-references into markdown links with a
     * `field-source:` href, but only when the symbol resolves to a known source
     * location. Existing `[label](url)` links and anything inside code — both
     * fenced ``` ``` ``` blocks and inline `` `code` `` spans — are left untouched.
     */
    internal fun linkifyKDocSymbols(markdown: String): String {
        if (markdown.isEmpty() || !markdown.contains('[')) return markdown
        val parts = markdown.split("```")
        val sb = StringBuilder(markdown.length + 64)
        parts.forEachIndexed { index, part ->
            if (index > 0) sb.append("```")
            // Even segments are outside code fences; odd segments are code.
            if (index % 2 == 0) sb.append(linkifyOutsideFence(part)) else sb.append(part)
        }
        return sb.toString()
    }

    /**
     * Linkify a non-fenced region, still skipping inline `` `code` `` spans:
     * split on single backticks and only rewrite the even (non-code) segments,
     * mirroring the fence handling one level up.
     */
    private fun linkifyOutsideFence(text: String): String {
        if (!text.contains('[')) return text
        if (!text.contains('`')) return linkifyKDocSegment(text)
        val spans = text.split('`')
        val sb = StringBuilder(text.length + 32)
        spans.forEachIndexed { index, span ->
            if (index > 0) sb.append('`')
            if (index % 2 == 0) sb.append(linkifyKDocSegment(span)) else sb.append(span)
        }
        return sb.toString()
    }

    private fun linkifyKDocSegment(text: String): String {
        if (!text.contains('[')) return text
        return kdocLinkRegex.replace(text) { match ->
            val target = match.groupValues[2]
            if (target.isNotEmpty()) {
                // KDoc `[display][Target]`: link the display text to the target
                // symbol only, never linkifying the display text on its own.
                val display = match.groupValues[1]
                val loc = resolveSymbolLocation(target)
                if (loc == null) match.value
                else "[$display](${sourceCardHref(loc.sourceName, loc.sourceFile, loc.sourceLine)})"
            } else {
                // Bare `[Ident]`. Skip the label of an existing [label](url) link.
                if (text.getOrNull(match.range.last + 1) == '(') return@replace match.value
                val label = match.groupValues[3]
                val loc = resolveSymbolLocation(label)
                if (loc == null) match.value
                else "[$label](${sourceCardHref(loc.sourceName, loc.sourceFile, loc.sourceLine)})"
            }
        }
    }

    /**
     * Build a documentation card for the declaration a reader clicked in the panel.
     * Reuses the same markdown shape as completion documentation so the card renders
     * identically, and linkifies its own `[Symbol]` references so navigation recurses.
     */
    internal fun resolveSourceCard(symbol: String?, path: String?, line: Int): SourceCardResult {
        val index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex() ?: return SourceCardResult()
        val root = Paths.get(System.getProperty("user.dir")).normalize()

        val record = when {
            !path.isNullOrBlank() && line > 0 ->
                index.definitions.firstOrNull { it.filePath == path && it.line == line }
                    ?: index.definitions.firstOrNull { it.filePath.endsWith(path) && it.line == line }
                    ?: symbol?.let { bestDefinitionForSimpleName(it.substringAfterLast('.')) }
            !symbol.isNullOrBlank() -> bestDefinitionForSimpleName(symbol.substringAfterLast('.'))
            else -> null
        } ?: return SourceCardResult()

        val simpleName = record.name.substringAfterLast('.')
        val bodyMarkdown = when (record.kind) {
            "fun" -> parseSourceCallableInfo(record, root, simpleName)?.let { sci ->
                CallableInfo(
                    name = simpleName,
                    ownerName = sci.ownerName,
                    parameters = sci.parameters,
                    returnType = sci.returnType,
                    documentation = sci.documentation,
                    sourceFile = sci.sourceFile,
                    sourceLine = sci.sourceLine,
                    sourceName = sci.sourceName
                ).documentationMarkdown()
            } ?: genericCardMarkdown(record, root)
            else -> genericCardMarkdown(record, root)
        }
        if (bodyMarkdown.isBlank()) return SourceCardResult()

        val category = record.completionCategory.orEmpty()
        val documentation = buildString {
            if (category.isNotBlank()) append("**Category** `").append(category).append("`\n\n")
            append(bodyMarkdown)
        }

        return SourceCardResult(
            found = true,
            title = simpleName,
            documentation = linkifyKDocSymbols(documentation),
            category = category,
            sourceName = record.name,
            sourcePath = record.filePath,
            sourceLine = record.line
        )
    }

    private fun genericCardMarkdown(record: afield.ai.KotlinDefinitionRecord, root: Path): String {
        val text = runCatching { Files.readString(root.resolve(record.filePath).normalize()) }.getOrNull()
        val signature = text?.let { declarationSignature(it, record.line) }
            ?: "${record.kind} ${record.name.substringAfterLast('.')}"
        val sections = mutableListOf("```kotlin\n$signature\n```")
        parseKDoc(record.kdoc).body?.takeIf { it.isNotBlank() }?.let { sections += it }
        sections += "**Source** `${record.filePath}`"
        return sections.joinToString("\n\n")
    }

    /**
     * Extract a readable declaration header starting at [line] (1-based): accumulate
     * source lines until the signature is balanced or a body `{` opens, then trim the
     * trailing brace. Functions use the richer callable parser; this serves types,
     * typealiases, objects and properties.
     */
    private fun declarationSignature(text: String, line: Int): String? {
        val lines = text.split("\n")
        if (line < 1 || line > lines.size) return null
        val sb = StringBuilder()
        var i = line - 1
        val limit = (line - 1 + 8).coerceAtMost(lines.size)
        while (i < limit) {
            val raw = lines[i]
            val braceAt = raw.indexOf('{')
            if (braceAt >= 0) {
                sb.append(raw.substring(0, braceAt))
                break
            }
            sb.append(raw)
            val open = sb.count { it == '(' }
            val balanced = open > 0 && open == sb.count { it == ')' }
            if (open == 0 || balanced || sb.trim().endsWith("=")) break
            sb.append('\n')
            i++
        }
        return sb.toString().trim().trimEnd('{').trim().ifBlank { null }
    }

    // ---- Raw source view (GET /api/source, Stage B) ------------------------

    private fun sourceLanguageFor(name: String): String = when {
        name.endsWith(".kt", true) || name.endsWith(".kts", true) -> "kotlin"
        name.endsWith(".java", true) -> "java"
        name.endsWith(".glsl", true) || name.endsWith(".frag", true) || name.endsWith(".vert", true) -> "glsl"
        name.endsWith(".metal", true) || name.endsWith(".msl", true) -> "msl"
        name.endsWith(".js", true) || name.endsWith(".mjs", true) -> "javascript"
        else -> ""
    }

    private fun sourceWindow(lines: List<String>, line: Int, span: Int): Triple<Int, Int, String> {
        if (lines.isEmpty()) return Triple(0, 0, "")
        val start = if (line > 0) (line - 3).coerceIn(1, lines.size) else 1
        val end = if (line > 0) (start + span - 1).coerceAtMost(lines.size) else span.coerceAtMost(lines.size)
        return Triple(start, end, lines.subList(start - 1, end).joinToString("\n"))
    }

    /**
     * Read a window of source text for the "view source" affordance. Guarded: project
     * files must resolve under `./src`; jar entries must live in a `-sources.jar`.
     * Returns [SourceFileResponse.found] = false for anything else (no dead views).
     */
    internal fun readSourceExcerpt(rawPath: String, line: Int, context: Int): SourceFileResponse {
        val span = context.coerceIn(1, 400)
        return if (rawPath.startsWith("jar:")) readJarExcerpt(rawPath, line, span)
        else readProjectExcerpt(rawPath, line, span)
    }

    private fun readProjectExcerpt(rawPath: String, line: Int, span: Int): SourceFileResponse {
        val root = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val srcRoot = root.resolve("src").normalize()
        val rel = rawPath.removePrefix("./").replace('\\', '/')
        val path = root.resolve(rel).normalize()
        if (!path.startsWith(srcRoot) || !Files.isRegularFile(path)) return SourceFileResponse()
        val lines = runCatching { Files.readAllLines(path) }.getOrNull() ?: return SourceFileResponse()
        val (start, end, code) = sourceWindow(lines, line, span)
        val relDisplay = runCatching { root.relativize(path).toString().replace('\\', '/') }.getOrDefault(rel)
        return SourceFileResponse(true, relDisplay, sourceLanguageFor(relDisplay), start, end, code)
    }

    private fun readJarExcerpt(rawPath: String, line: Int, span: Int): SourceFileResponse {
        // rawPath = jar:file:///abs/foo-sources.jar!/pkg/Foo.kt
        val body = rawPath.removePrefix("jar:")
        val sep = body.lastIndexOf("!/")
        if (sep < 0) return SourceFileResponse()
        val jarFile = runCatching { Paths.get(URI(body.substring(0, sep))).toFile() }.getOrNull()
            ?: return SourceFileResponse()
        val entryName = body.substring(sep + 2)
        if (!jarFile.name.endsWith("-sources.jar") || !jarFile.isFile) return SourceFileResponse()
        val text = runCatching {
            ZipFile(jarFile).use { zip ->
                val entry = zip.getEntry(entryName) ?: return SourceFileResponse()
                zip.getInputStream(entry).bufferedReader().use { it.readText() }
            }
        }.getOrNull() ?: return SourceFileResponse()
        val (start, end, code) = sourceWindow(text.split("\n"), line, span)
        return SourceFileResponse(true, entryName, sourceLanguageFor(entryName), start, end, code)
    }

    private fun scoreCallableCandidate(
        definitionName: String,
        info: SourceCallableInfo,
        receiverOwnerHint: String?,
        expectedParamCount: Int?,
        expectedParamTypes: List<String>?,
        expectedReturnType: String?,
        displayText: String,
        allCode: String
    ): Int {
        var score = 20

        if (!receiverOwnerHint.isNullOrBlank()) {
            score += ownerCompatibilityScore(info.ownerName, receiverOwnerHint)
        }

        expectedParamCount?.let {
            score += if (info.parameters.size == it) 40 else -20 * kotlin.math.abs(info.parameters.size - it)
        }

        expectedParamTypes?.takeIf { it.isNotEmpty() }?.let { expectedTypes ->
            expectedTypes.forEachIndexed { index, expected ->
                val actual = info.parameters.getOrNull(index)?.type?.let(::shortenType).orEmpty()
                score += when {
                    actual.isBlank() -> 0
                    parameterTypesMatch(actual, expected) -> 35
                    else -> -25
                }
            }
        }

        expectedReturnType?.let {
            val actualReturn = info.returnType?.let(::shortenType)
            score += if (actualReturn == shortenType(it)) 30 else -10
        }

        if (displayText.contains("fun ")) score += 5
        if (definitionName.substringAfterLast('.') in allCode) score += 10
        return score
    }

    private fun ownerCompatibilityScore(candidateOwner: String?, receiverOwner: String?): Int {
        val candidate = candidateOwner?.takeIf { it.isNotBlank() } ?: return 0
        val receiver = receiverOwner?.takeIf { it.isNotBlank() } ?: return 0
        if (candidate == receiver) return 120
        if (candidate.substringAfterLast('.') == receiver.substringAfterLast('.')) return 90
        if (ownerIsAssignableFrom(candidate, receiver)) return 70
        return -40
    }

    private fun ownerIsAssignableFrom(candidateOwner: String, receiverOwner: String): Boolean {
        val candidateNames = resolveOwnerClassNames(candidateOwner)
        val receiverNames = resolveOwnerClassNames(receiverOwner)
        if (candidateNames.isEmpty() || receiverNames.isEmpty()) return false
        val classLoader = Thread.currentThread().contextClassLoader
        val candidateClasses = candidateNames.mapNotNull { name ->
            runCatching { Class.forName(name, false, classLoader) }.getOrNull()
        }
        val receiverClasses = receiverNames.mapNotNull { name ->
            runCatching { Class.forName(name, false, classLoader) }.getOrNull()
        }
        return candidateClasses.any { candidate ->
            receiverClasses.any { receiver -> candidate.isAssignableFrom(receiver) }
        }
    }

    private fun resolveOwnerClassNames(owner: String): List<String> {
        val raw = owner.substringBefore('<').substringBefore('?').trim()
        if (raw.isBlank()) return emptyList()
        if ('.' in raw) return listOf(raw)
        return findClassesNamed(raw).orEmpty()
    }

    private fun descriptorOwnerName(descriptorText: String?): String? {
        val text = descriptorText?.takeIf { it.isNotBlank() } ?: return null
        return Regex("""\bdefined\s+in\s+([A-Za-z_][A-Za-z0-9_.$]*(?:\.[A-Za-z_][A-Za-z0-9_$]*)*)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.substringBefore('[')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun inferReceiverOwnerName(
        box: Box,
        allCode: String,
        position: Int,
        receiverTypeHint: String?
    ): String? {
        receiverTypeHint?.takeIf { it.isNotBlank() }?.let { return it.substringAfterLast('.') }
        inferEnclosingLambdaReceiverOwner(allCode, position)?.let { return it }

        val receiverExpression = extractReceiverExpressionAtCompletion(allCode, position) ?: return null
        val identifier = receiverExpression.trim()
        if (!identifier.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return null

        box.ki.context.vars[identifier]?.value?.let { value ->
            value::class.simpleName?.takeIf { it.isNotBlank() }?.let { return it }
        }

        val explicitType = explicitTypeForLocal(allCode, identifier)
        if (!explicitType.isNullOrBlank()) return explicitType.substringAfterLast('.')

        val constructedType = constructedTypeForLocal(allCode, identifier)
        if (!constructedType.isNullOrBlank()) return constructedType.substringAfterLast('.')

        return null
    }

    private fun inferEnclosingLambdaReceiverOwner(allCode: String, position: Int): String? {
        val callName = enclosingLambdaCallName(allCode, position) ?: return null
        val index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex() ?: return null
        val root = Paths.get(System.getProperty("user.dir")).normalize()

        return index.definitions.asSequence()
            .filter { it.kind == "fun" && it.name.substringAfterLast('.') == callName }
            .mapNotNull { parseSourceCallableInfo(it, root, callName) }
            .flatMap { it.parameters.asSequence() }
            .mapNotNull { parameter ->
                Regex("""([A-Za-z_][A-Za-z0-9_.]*)\s*\.\s*\(\s*\)\s*->""")
                    .find(parameter.type)
                    ?.groupValues
                    ?.get(1)
                    ?.substringAfterLast('.')
            }
            .firstOrNull()
    }

    private fun enclosingLambdaCallName(allCode: String, position: Int): String? {
        var cursor = position.coerceIn(0, allCode.length) - 1
        var braceDepth = 0

        while (cursor >= 0) {
            when (allCode[cursor]) {
                '}' -> braceDepth++
                '{' -> {
                    if (braceDepth > 0) {
                        braceDepth--
                    } else {
                        return callNameBeforeLambdaBrace(allCode, cursor)
                    }
                }
            }
            cursor--
        }
        return null
    }

    private fun callNameBeforeLambdaBrace(code: String, bracePosition: Int): String? {
        var cursor = bracePosition
        while (cursor > 0 && code[cursor - 1].isWhitespace()) cursor--

        if (cursor > 0 && code[cursor - 1] == ')') {
            var parenDepth = 1
            cursor--
            while (cursor > 0 && parenDepth > 0) {
                cursor--
                when (code[cursor]) {
                    ')' -> parenDepth++
                    '(' -> parenDepth--
                }
            }
            if (parenDepth != 0) return null
        }

        while (cursor > 0 && code[cursor - 1].isWhitespace()) cursor--
        val end = cursor
        while (cursor > 0 && (code[cursor - 1].isLetterOrDigit() || code[cursor - 1] == '_')) cursor--
        if (cursor == end) return null
        return code.substring(cursor, end)
    }

    private fun extractReceiverExpressionAtCompletion(allCode: String, position: Int): String? {
        if (position <= 0 || position > allCode.length) return null
        val bounds = getTokenBounds(allCode, position)
        val beforePrefix = bounds.start
        if (beforePrefix <= 0) return null

        val isSafeDot = beforePrefix >= 2 &&
            allCode[beforePrefix - 1] == '.' &&
            allCode[beforePrefix - 2] == '?'
        val isDot = !isSafeDot && allCode[beforePrefix - 1] == '.'
        if (!isDot && !isSafeDot) return null

        val dotPos = if (isSafeDot) beforePrefix - 2 else beforePrefix - 1
        return extractReceiverExpression(allCode, dotPos)
    }

    private fun extractReceiverExpression(text: String, endPos: Int): String {
        var pos = endPos - 1
        var parenDepth = 0
        var bracketDepth = 0

        while (pos >= 0) {
            val ch = text[pos]
            when {
                ch == ')' -> {
                    parenDepth++
                    pos--
                }
                ch == '(' && parenDepth > 0 -> {
                    parenDepth--
                    pos--
                }
                ch == ']' -> {
                    bracketDepth++
                    pos--
                }
                ch == '[' && bracketDepth > 0 -> {
                    bracketDepth--
                    pos--
                }
                parenDepth > 0 || bracketDepth > 0 -> pos--
                ch.isLetterOrDigit() || ch == '_' || ch == '.' || ch == '?' -> pos--
                else -> break
            }
        }

        return text.substring(pos + 1, endPos).trim()
    }

    private fun explicitTypeForLocal(allCode: String, identifier: String): String? {
        val regex = Regex("\\b(?:val|var)\\s+${Regex.escape(identifier)}\\s*:\\s*([A-Za-z0-9_.<>?]+)")
        val match = regex.find(allCode) ?: return null
        return match.groupValues[1]
            .substringBefore('<')
            .substringBefore('?')
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun constructedTypeForLocal(allCode: String, identifier: String): String? {
        val regex = Regex("\\b(?:val|var)\\s+${Regex.escape(identifier)}\\s*=\\s*([A-Za-z_][A-Za-z0-9_.]*)\\s*(?:<[^\\n=(){}]*>)?\\s*\\(")
        val match = regex.find(allCode) ?: return null
        val callee = match.groupValues[1]
            .substringBefore('<')
            .trim()
            .takeIf { it.isNotBlank() }
            ?: return null

        if (callee.substringAfterLast('.').firstOrNull()?.isUpperCase() == true) return callee

        val openParen = allCode.indexOf('(', match.range.first).takeIf { it >= 0 } ?: return callee
        val closeParen = matchingDelimiter(allCode, openParen, '(', ')')
        val argumentCount = if (closeParen > openParen) {
            splitTopLevelConstructorParameters(allCode.substring(openParen + 1, closeParen)).size
        } else {
            null
        }

        return sourceFunctionReturnTypeForCall(allCode, callee, argumentCount) ?: callee
    }

    private fun sourceFunctionReturnTypeForCall(
        allCode: String,
        calleeName: String,
        argumentCount: Int?
    ): String? {
        val simpleName = calleeName.substringAfterLast('.')
        val index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex() ?: return null
        val root = Paths.get(System.getProperty("user.dir")).normalize()
        return index.definitions.asSequence()
            .filter { it.kind == "fun" && it.name.substringAfterLast('.') == simpleName }
            .mapNotNull { parseSourceCallableInfo(it, root, simpleName) }
            .filter { info -> argumentCount == null || info.parameters.size == argumentCount }
            .sortedWith(
                compareByDescending<SourceCallableInfo> { it.sourceName.startsWith("afield.stdlib.") }
                    .thenByDescending { it.sourceName in allCode }
                    .thenBy { it.sourceFile }
                    .thenBy { it.sourceLine }
            )
            .mapNotNull { it.returnType?.takeIf { returnType -> returnType.isNotBlank() } }
            .firstOrNull()
    }

    private fun indexedOwnerName(definitionName: String): String? =
        definitionName.substringBeforeLast('.', "")
            .takeIf { it.isNotBlank() }

    private fun ownerMatches(candidateOwner: String?, receiverOwner: String?): Boolean {
        val candidate = candidateOwner?.takeIf { it.isNotBlank() } ?: return false
        val receiver = receiverOwner?.takeIf { it.isNotBlank() } ?: return false
        return candidate == receiver ||
            candidate.substringAfterLast('.') == receiver.substringAfterLast('.')
    }

    private fun parameterCountHint(displayText: String): Int? {
        val open = displayText.indexOf('(')
        val close = displayText.lastIndexOf(')')
        if (open == -1 || close <= open) return null
        val inside = displayText.substring(open + 1, close).trim()
        if (inside.isEmpty()) return 0
        return splitTopLevelConstructorParameters(inside).size
    }

    private fun parameterTypeHints(displayText: String): List<String>? {
        val open = displayText.indexOf('(')
        val close = displayText.lastIndexOf(')')
        if (open == -1 || close <= open) return null
        val inside = displayText.substring(open + 1, close).trim()
        if (inside.isEmpty()) return emptyList()
        return splitTopLevelConstructorParameters(inside)
            .mapNotNull { raw ->
                val withoutDefault = raw.substringBefore('=').trim()
                val typeText = if (':' in withoutDefault) {
                    withoutDefault.substringAfter(':').trim()
                } else {
                    withoutDefault
                }
                typeText
                    .removePrefix("vararg ")
                    .substringBefore('=')
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let(::shortenType)
            }
    }

    private fun parameterTypesMatch(actual: String, expected: String): Boolean {
        fun normalize(type: String): String =
            shortenType(type)
                .removeSuffix("?")
                .replace(" ", "")
        return normalize(actual) == normalize(expected)
    }

    private fun findFunctionHeaderEnd(text: String, start: Int): Int {
        var parenDepth = 0
        var angleDepth = 0
        var braceDepth = 0
        var bracketDepth = 0
        var inString = false
        var stringChar = '"'
        var i = start
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
                '{' -> if (braceDepth == 0 && parenDepth == 0 && angleDepth == 0 && bracketDepth == 0) return i
                '=' -> if (braceDepth == 0 && parenDepth == 0 && angleDepth == 0 && bracketDepth == 0) return i
                '[' -> bracketDepth++
                ']' -> if (bracketDepth > 0) bracketDepth--
                '\n' -> if (parenDepth == 0 && angleDepth == 0 && bracketDepth == 0) {
                    val next = text.indexOfFirstNonWhitespace(i + 1)
                    if (next == -1 || text.getOrNull(next) in listOf('{', '=')) return i
                }
            }
            i++
        }
        return text.length
    }

    private fun lineStartOffsets(text: String): List<Int> {
        val offsets = mutableListOf(0)
        text.forEachIndexed { index, ch ->
            if (ch == '\n' && index + 1 < text.length) offsets += index + 1
        }
        return offsets
    }

    private fun String.indexOfFirstNonWhitespace(start: Int): Int {
        var cursor = start
        while (cursor < length && this[cursor].isWhitespace()) cursor++
        return if (cursor < length) cursor else -1
    }

    private fun sourceFileCandidates(root: Path, simpleName: String, fqName: String?): List<Path> {
        val ordered = LinkedHashSet<Path>()
        val index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex()
        index?.definitions
            ?.asSequence()
            ?.filter { it.kind in setOf("class", "object") && it.name.substringAfterLast('.') == simpleName }
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
                            if (Regex("\\b(?:class|object)\\s+${Regex.escape(simpleName)}\\b").containsMatchIn(raw)) {
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

        val declarationMatch = Regex("\\b(class|object)\\s+${Regex.escape(simpleName)}\\b").find(rawText) ?: return null
        val isObject = declarationMatch.groupValues[1] == "object"
        val classStart = declarationMatch.range.first
        val classDocumentation = leadingKDoc(rawText, classStart)
        var cursor = declarationMatch.range.last + 1
        cursor = skipWhitespace(rawText, cursor)
        if (cursor < rawText.length && rawText[cursor] == '<') {
            val genericEnd = matchingDelimiter(rawText, cursor, '<', '>')
            if (genericEnd <= cursor) return null
            cursor = genericEnd + 1
            cursor = skipWhitespace(rawText, cursor)
        }
        val parameters = if (isObject) {
            emptyList()
        } else {
            if (cursor >= rawText.length || rawText[cursor] != '(') return null

            val close = matchingDelimiter(rawText, cursor, '(', ')')
            if (close <= cursor) return null

            val constructorText = rawText.substring(cursor + 1, close)
            splitTopLevelConstructorParameters(constructorText)
                .mapNotNull { parseParameter(it) }
        }

        val relativePath = runCatching { root.relativize(path).toString().replace("\\", "/") }
            .getOrDefault(path.toString())

        return SourceConstructorInfo(
            sourceFile = relativePath,
            sourceLine = rawText.substring(0, classStart).count { it == '\n' } + 1,
            sourceName = if (packageName.isBlank()) simpleName else "$packageName.$simpleName",
            isObject = isObject,
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

    private fun shortenType(type: String): String {
        val receiverFunctionMarker = "__afield_receiver_function_dot__"
        val receiverFunctionTypes = Regex("""((?:\b[_a-zA-Z$][_a-zA-Z0-9$]*\.)*)([_a-zA-Z$][_a-zA-Z0-9$]*)\.\(\)""")
        val protectedReceiverFunctions = type.replace(receiverFunctionTypes) {
            "${it.groupValues[2]}$receiverFunctionMarker()"
        }
        return protectedReceiverFunctions
            .replace("(\\b[_a-zA-Z$][_a-zA-Z0-9$]*\\b\\.)+".toRegex(), "")
            .replace(receiverFunctionMarker, ".")
    }
}
