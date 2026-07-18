package afield

import afield.ai.Ai4GlslAutocomplete
import afield.ai.KotlinSourceIndex
import afield.ai.shaderSpecForProperty
import afield.four.synth.AudioPatchSpec
import afield.four.synth.InlineAudioSummaryTarget
import afield.logger.PinnedConsole
import afield.logger.lprintln
import afield.macros.checkAtticAndExecute
import afield.macros.checkIntroductionAndExecute
import afield.macros.checkPreambleAndExecute
import afield.macros.gatherPreambleUpwards
import afield.macros.standardExecutionPractice
import afield.stdlib.*
import io.ktor.websocket.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotated
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.SourceCodeCompletionVariant

@RunsAt(newRoot)
fun installKEActions(root: Box) {
    root.actions["reset"] = Action(
        "Reset Kotlin Environment for Box",
        detail = "This drops all variables and declarations live in the current box and starts again"
    ) {
        it.forEach {
            it.ki = makeKI(it, reason = "reset action")
        }
    }
}

// refactor this to make the contract about is-running much-much clearer
interface BoxExecution {

    fun executeFragment(inside: Box, execute: Execute, reply: ((Message) -> Unit))
    // Awaited variant: runs the fragment to completion (used by scripted transcript
    // drivers so a following op / the run result observes its output). Default just
    // fires the normal (fire-and-forget) path for engines that don't support waiting.
    suspend fun executeFragmentAndWait(inside: Box, execute: Execute, reply: ((Message) -> Unit)) {
        executeFragment(inside, execute, reply)
    }
    fun executeAll(inside: Box, execute: Execute, reply: ((Message) -> Unit))
    fun launchAll(
        inside: Box,
        execute: Execute,
        code: String = execute.code,
        strand: String = "default",
        reply: ((Message) -> Unit)
    )

    fun continueAll(
        inside: Box,
        execute: Execute,
        code: String = execute.code,
        strand: String = "default",
        reply: ((Message) -> Unit)
    ) {
        launchAll(inside, execute, code, strand, reply)
    }

    fun executeSymbol(inside: Box, executeSymbol: ExecuteSymbol, reply: ((Message) -> Unit))

    fun stop(inside: Box)

    fun complete(inside: Box, completionRequest: CompletionRequest, reply: ((Message) -> Unit))

    fun describeSymbolContext(inside: Box, request: SymbolContextRequest): SymbolContextResponse = SymbolContextResponse()

    val languageName: String
}

private fun escapeForHtml(text: String?): String {
    if (text == null) return ""
    return text.replace("<", "&lt;").replace(">", "&gt;")
}

private fun simplifyTypeForOutput(type: String?): String {
    if (type.isNullOrBlank()) return ""
    return type.replace("kotlin.", "")
}

private val completionDescriptorRenderer = DescriptorRenderer.COMPACT_WITH_SHORT_TYPES.withOptions {
    withDefinedIn = false
}

private fun renderCompletionType(type: org.jetbrains.kotlin.types.KotlinType?): String {
    if (type == null) return "Unit"
    return runCatching { completionDescriptorRenderer.renderType(type) }
        .getOrElse { simplifyTypeForOutput(type.toString()) }
}

private fun renderCompletionParameter(parameter: ValueParameterDescriptor): String {
    val renderedType = renderCompletionType(parameter.varargElementType ?: parameter.type)
    return buildString {
        if (parameter.isCrossinline) append("crossinline ")
        if (parameter.isNoinline) append("noinline ")
        if (parameter.varargElementType != null) append("vararg ")
        append(parameter.name.asString())
        append(": ")
        append(renderedType)
        if (parameter.declaresDefaultValue()) append(" = ...")
    }
}

private fun renderFunctionCompletionSignature(descriptor: FunctionDescriptor): String {
    val receiver = descriptor.extensionReceiverParameter?.let { renderCompletionType(it.type) + "." }.orEmpty()
    val prefix = buildString {
        if (descriptor.isSuspend) append("suspend ")
        append("fun ")
        append(receiver)
        append(descriptor.name.asString())
    }
    val parameters = descriptor.valueParameters.map(::renderCompletionParameter)
    val returnType = renderCompletionType(descriptor.returnType)
    val singleLine = "$prefix(${parameters.joinToString(", ")}): $returnType"

    if (parameters.size <= 2 && singleLine.length <= 100) return singleLine

    return buildString {
        append(prefix)
        append("(\n")
        parameters.forEachIndexed { index, parameter ->
            append("    ")
            append(parameter)
            if (index != parameters.lastIndex) append(',')
            append('\n')
        }
        append("): ")
        append(returnType)
    }
}

private fun buildCompletionDocumentation(
    descriptor: DeclarationDescriptor,
    existingDocumentation: String
): String {
    if (descriptor !is FunctionDescriptor) return existingDocumentation.trim()

    val sections = mutableListOf<String>()
    sections += buildString {
        append("```kotlin\n")
        append(renderFunctionCompletionSignature(descriptor))
        append("\n```")
    }

    existingDocumentation.trim().takeIf { it.isNotEmpty() }?.let { sections += it }

    descriptor.valueParameters.takeIf { it.isNotEmpty() }?.let { parameters ->
        sections += buildString {
            append("**Parameters**\n")
            parameters.forEach { parameter ->
                append("- `")
                append(renderCompletionParameter(parameter))
                append("`\n")
            }
        }.trimEnd()
    }

    descriptor.extensionReceiverParameter?.let { receiver ->
        sections += "**Receiver** `${renderCompletionType(receiver.type)}`"
    }

    descriptor.returnType?.let { returnType ->
        sections += "**Returns** `${renderCompletionType(returnType)}`"
    }

    return sections.joinToString("\n\n").trim()
}

private fun completionIconLooksLikeValue(icon: String): Boolean {
    val key = icon.lowercase()
    return key in setOf("property", "field", "variable", "value", "constant", "local", "val", "var") ||
            key.contains("variable") ||
            key.contains("property")
}

private fun renderCompletionFallbackSignature(displayText: String, tail: String, icon: String = ""): String {
    val display = displayText.trimEnd()
    val suffix = tail.trim()
    if (suffix.isBlank()) return display
    if (display.isBlank()) return suffix
    if (
        completionIconLooksLikeValue(icon) &&
        ':' !in display &&
        !display.contains('(') &&
        suffix.firstOrNull() !in setOf(':', ')', ',', '.', '[', '<')
    ) {
        return "$display: $suffix"
    }
    val separator = if (suffix.firstOrNull() in setOf(':', ')', ',', '.', '[', '<')) "" else " "
    return display + separator + suffix
}

private fun completionTraceEnabled(): Boolean =
    System.getenv("AFIELD_COMPLETION_TRACE")
        ?.trim()
        ?.lowercase()
        ?.let { it !in setOf("0", "false", "off", "no") }
        ?: true

private fun completionTrace(message: String) {
    if (!completionTraceEnabled()) return
    lprintln("info,completion", message)
    runCatching {
        val path = Paths.get(".field-backdoor", "logs", "completion-trace.log")
        Files.createDirectories(path.parent)
        Files.writeString(
            path,
            "${Instant.now()} $message\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        )
    }
}

private fun callableBeforeArgumentCursor(code: String, position: Int): String? {
    var cursor = position.coerceIn(0, code.length)
    while (cursor > 0 && code[cursor - 1].isWhitespace()) cursor--
    if (cursor == 0 || code[cursor - 1] != '(') return null

    cursor--
    while (cursor > 0 && code[cursor - 1].isWhitespace()) cursor--
    val end = cursor
    while (cursor > 0 && (code[cursor - 1].isLetterOrDigit() || code[cursor - 1] == '_')) cursor--
    if (cursor == end) return null

    return code.substring(cursor, end)
}

private fun escapeNotifyHtml(text: String): String {
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}

private val generatedBoxIdRegex = Regex(
    pattern = """\b(B?[0-9a-f]{8}(?:[_-][0-9a-f]{4}){3}[_-][0-9a-f]{12})(?:__\d+)?\b""",
    options = setOf(RegexOption.IGNORE_CASE)
)

private data class ContainerSummary(
    val text: String,
    val html: String
)

private fun boxContextLabel(currentBox: Box, targetBox: Box): String {
    return when {
        targetBox.name.isNotBlank() -> targetBox.name
        targetBox.id == currentBox.id -> "this box"
        else -> "box ${targetBox.id.take(8)}"
    }
}

private fun canonicalGeneratedBoxIdCandidate(value: String): String {
    val canonical = value
        .removeSuffix("_")
        .replace("_", "")
        .replace("-", "")
        .lowercase()
    return canonical
}

private fun canonicalSessionBoxId(value: String): String {
    return value
        .replace("_", "")
        .replace("-", "")
        .lowercase()
}

private fun resolveGeneratedBoxSummary(box: Box, rawContainer: String): ContainerSummary? {
    val candidates = generatedBoxIdRegex
        .findAll(rawContainer)
        .map { it.groupValues[1] }
        .toList()

    val sessionBoxes = buildList {
        add(box)
        if (box.isInsideInitialized()) {
            addAll(box.inside.boxes.values)
        }
    }

    lprintln(
        "debug,symbol-context",
        "resolveGeneratedBoxSummary raw='$rawContainer' currentBox='${box.id}' currentName='${box.name}' candidates=$candidates sessionBoxes=${sessionBoxes.map { "${it.id}:${it.name}" }}"
    )

    candidates.forEach { candidate ->
        val canonical = canonicalGeneratedBoxIdCandidate(candidate)
        val match = sessionBoxes.firstOrNull { canonicalSessionBoxId(it.id) == canonical }
        if (match != null) {
            val label = boxContextLabel(box, match)
            lprintln(
                "debug,symbol-context",
                "resolveGeneratedBoxSummary matched candidate='$candidate' canonical='$canonical' to box='${match.id}' name='${match.name}' label='$label'"
            )
            return ContainerSummary(
                text = label,
                html = "<i>${escapeNotifyHtml(label)}</i>"
            )
        }
    }

    val rawCanonical = canonicalGeneratedBoxIdCandidate(rawContainer)
        .filter { it in '0'..'9' || it in 'a'..'f' }
    if (rawCanonical.length >= 32) {
        val match = sessionBoxes.firstOrNull { rawCanonical.contains(canonicalSessionBoxId(it.id)) }
        if (match != null) {
            val label = boxContextLabel(box, match)
            lprintln(
                "debug,symbol-context",
                "resolveGeneratedBoxSummary matched rawCanonical='$rawCanonical' to box='${match.id}' name='${match.name}' label='$label'"
            )
            return ContainerSummary(
                text = label,
                html = "<i>${escapeNotifyHtml(label)}</i>"
            )
        }
    }

    lprintln(
        "debug,symbol-context",
        "resolveGeneratedBoxSummary no-match raw='$rawContainer' rawCanonical='$rawCanonical'"
    )

    return null
}

private fun prettifyContainerSummary(box: Box, rawContainer: String): ContainerSummary {
    resolveGeneratedBoxSummary(box, rawContainer)?.let {
        lprintln("debug,symbol-context", "prettifyContainerSummary resolved raw='$rawContainer' -> text='${it.text}'")
        return it
    }
    val text = rawContainer.replace(generatedBoxIdRegex) { matchResult ->
        matchResult.groupValues[1]
            .removeSuffix("_")
            .replace('_', '-')
            .replaceFirstChar { it.lowercase() }
    }
    lprintln("debug,symbol-context", "prettifyContainerSummary fallback raw='$rawContainer' -> text='$text'")
    return ContainerSummary(
        text = text,
        html = escapeNotifyHtml(text)
    )
}

private fun descriptorContainerSummary(box: Box, descriptor: DeclarationDescriptor): ContainerSummary? {
    val container = descriptor.containingDeclaration ?: return null
    val raw = when (container) {
        is PackageViewDescriptor -> container.fqName.asString()
        else -> completionDescriptorRenderer.render(container)
            .lineSequence()
            .firstOrNull()
            ?.trim()
            .orEmpty()
    }
    return prettifyContainerSummary(box, raw)
}

private fun buildFunctionSymbolContextHtml(box: Box, descriptor: FunctionDescriptor): String {
    val container = descriptorContainerSummary(box, descriptor)
    val funcName = descriptor.name.asString()
    return buildString {
        append("<div style='display:inline-block; max-width: 620px;'>")
        append("<div style='color:#ddd; overflow-wrap:anywhere;'><code>")
        append(escapeNotifyHtml(renderFunctionCompletionSignature(descriptor)))
        append("</code></div>")
        if (container != null && container.text.isNotBlank()) {
            append("<div style='color:#777;'>declared in ")
            append(container.html)
            append("</div>")
        }
        // Check if this function was AI-generated (lives in the attic)
        val attic = box.attic
        if (attic.isNotBlank()) {
            val atticName = if (funcName.startsWith("__")) funcName else "__$funcName"
            if (attic.contains("// @ai-gen: $atticName")) {
                val uid = box.id
                append("<div style='color:#b0b0ff; cursor:pointer; margin-top:2px;' ")
                append("onclick=\"if(window.switchToProperty) window.switchToProperty('")
                append(escapeNotifyHtml(uid))
                append("','attic')\">")
                append("\uD83E\uDD16 ai-generated — <u>view in attic</u>")
                append("</div>")
            }
        }
        append("</div>")
    }
}

private fun shouldShowVariableValueDisclosure(value: Any?): Boolean {
    return when (value) {
        null,
        is Number,
        is Boolean,
        is Char,
        is String,
        is Enum<*> -> false

        else -> true
    }
}

private fun renderVariableValueDisclosure(value: Any?): String {
    if (!shouldShowVariableValueDisclosure(value)) return ""
    val rendered = runCatching { value.h }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: return ""
    return "<div class='afield-symbol-context-disclosure-body' style='margin-top:4px; overflow:auto;'>$rendered</div>"
}

private fun renderVariablePlainValue(value: Any?): String {
    val raw = when (value) {
        null -> "null"
        is CharSequence -> value.toString()
        is Char -> value.toString()
        else -> runCatching { value.toString() }.getOrNull().orEmpty()
    }.trim()
    if (raw.isBlank() || raw.contains('@')) return ""
    val clipped = if (raw.length > 1000) raw.take(1000).trimEnd() + "..." else raw
    return buildString {
        append("<div class='afield-symbol-context-plain-value' style='margin-top:6px; color:#bbb;'>")
        append("<span style='color:#777;'>value </span>")
        append("<code>")
        append(escapeNotifyHtml(clipped))
        append("</code>")
        append("</div>")
    }
}

private fun runtimeCompletionDocumentationHtml(
    box: Box,
    candidateName: String,
    icon: String,
    isDotCompletion: Boolean
): String {
    if (isDotCompletion || candidateName.isBlank()) return ""
    if (!completionIconLooksLikeValue(icon)) return ""
    val info = box.ki.context.vars[candidateName] ?: return ""
    return buildRuntimeVariableSymbolContextHtml(
        box = box,
        info = info,
        declarationOverride = runCatching { shortenType(info.descriptor.toString()) }
            .getOrDefault(info.toString(true)),
        locationLabel = "defined in"
    )
}

private fun renderDisplayContent(content: Any?): String {
    return when (content) {
        is String -> content
        else -> content.h
    }
}

private val renderedValueVisualizerHtmlRegex = Regex(
    pattern = """^\s*<div\b[^>]*\bclass\s*=\s*(['"])[^'"]*\bvalue-viz(?:-h2)?\b""",
    options = setOf(RegexOption.IGNORE_CASE)
)

private fun isRenderedValueVisualizerHtml(text: String): Boolean {
    return renderedValueVisualizerHtmlRegex.containsMatchIn(text)
}

private fun renderReturnValueOutput(value: Any?): String {
    return when (value) {
        is String -> if (isRenderedValueVisualizerHtml(value)) value else value.h
        else -> value.h
    }
}

private fun renderVariableLocationSection(
    label: String,
    container: ContainerSummary?,
    value: Any?
): String {
    if (container == null || container.text.isBlank()) return ""
    val locationHtml = "<span style='color:#777;'>$label ${container.html}</span>"
    val disclosure = renderVariableValueDisclosure(value)
    if (disclosure.isBlank()) {
        return "<div>$locationHtml</div>${renderVariablePlainValue(value)}"
    }
    return buildString {
        append("<details class='afield-symbol-context-disclosure' style='margin-top:2px;'>")
        append("<summary tabindex='-1' onfocus='this.blur()' style='color:#777; cursor:pointer; user-select:none; outline:none; box-shadow:none;'>")
        append(locationHtml)
        append("</summary>")
        append(disclosure)
        append("</details>")
    }
}

private fun buildRuntimeVariableSymbolContextHtml(
    box: Box,
    info: KotlinVariableInfo,
    declarationOverride: String? = null,
    locationLabel: String = "declared in"
): String {
    val declaration = declarationOverride
        ?: runCatching { shortenType(info.descriptor.toString()) }
            .getOrDefault(info.toString(true))
    val rawLocation = shortenType(info.line.javaClass.name)
    val location = prettifyContainerSummary(box, rawLocation)
    lprintln(
        "debug,symbol-context",
        "buildRuntimeVariableSymbolContextHtml symbol='${info.name}' declaration='$declaration' rawLocation='$rawLocation' location='${location.text}' valueType='${info.value?.javaClass?.name}'"
    )
    return buildString {
        append("<div style='display:inline-block; max-width: 620px;'>")
        append("<div style='color:#ddd; overflow-wrap:anywhere;'><code>")
        append(escapeNotifyHtml(declaration))
        append("</code></div>")
        append(renderVariableLocationSection(label = locationLabel, container = location, value = info.value))
        append("</div>")
    }
}

private fun buildVariableSymbolContextHtml(
    box: Box,
    descriptor: VariableDescriptor,
    runtimeInfo: KotlinVariableInfo? = null
): String {
    if (runtimeInfo != null) {
        return buildRuntimeVariableSymbolContextHtml(
            box = box,
            info = runtimeInfo,
            declarationOverride = completionDescriptorRenderer.render(descriptor),
            locationLabel = "declared in"
        )
    }

    val declaration = completionDescriptorRenderer.render(descriptor)
    val container = descriptorContainerSummary(box, descriptor)
    return buildString {
        append("<div style='display:inline-block; max-width: 620px;'>")
        append("<div style='color:#ddd; overflow-wrap:anywhere;'><code>")
        append(escapeNotifyHtml(declaration))
        append("</code></div>")
        append(renderVariableLocationSection(label = "declared in", container = container, value = null))
        append("</div>")
    }
}

private fun buildSymbolContextHtml(
    box: Box,
    descriptor: DeclarationDescriptor?,
    fallbackDisplay: String,
    fallbackDocumentation: String,
    runtimeVariableInfo: KotlinVariableInfo? = null
): String {
    runtimeVariableInfo?.let {
        val declarationOverride = (descriptor as? VariableDescriptor)?.let(completionDescriptorRenderer::render)
        return buildRuntimeVariableSymbolContextHtml(
            box = box,
            info = it,
            declarationOverride = declarationOverride,
            locationLabel = "declared in"
        )
    }

    descriptor?.let {
        return when (it) {
            is FunctionDescriptor -> buildFunctionSymbolContextHtml(box, it)
            is VariableDescriptor -> buildVariableSymbolContextHtml(box, it, runtimeVariableInfo)
            else -> {
                val rendered = completionDescriptorRenderer.render(it)
                val container = descriptorContainerSummary(box, it)
                buildString {
                    append("<div style='display:inline-block; max-width: 620px;'>")
                    append("<div style='color:#ddd; overflow-wrap:anywhere;'><code>")
                    append(escapeNotifyHtml(rendered))
                    append("</code></div>")
                    if (container != null && container.text.isNotBlank()) {
                        append("<div style='color:#777;'>declared in ")
                        append(container.html)
                        append("</div>")
                    }
                    append("</div>")
                }
            }
        }
    }

    val summary = fallbackDisplay.ifBlank {
        fallbackDocumentation.lineSequence().firstOrNull()?.trim().orEmpty()
    }
    if (summary.isBlank()) return ""
    return "<span style='color:#ddd;'><code>${escapeNotifyHtml(summary)}</code></span>"
}

private data class SymbolBounds(val start: Int, val end: Int, val text: String)

data class DebugProbeSegment(
    val rawText: String,
    val expandedText: String,
    val localLine: Int,
    val startLine: Int,
    val endLine: Int
)

private fun findSymbolBoundsAt(code: String, position: Int): SymbolBounds? {
    if (code.isEmpty()) return null
    val safePosition = position.coerceIn(0, code.length)
    fun isIdentifierChar(c: Char) = c.isLetterOrDigit() || c == '_'

    val probe = when {
        safePosition < code.length && isIdentifierChar(code[safePosition]) -> safePosition
        safePosition > 0 && isIdentifierChar(code[safePosition - 1]) -> safePosition - 1
        else -> return null
    }

    var start = probe
    while (start > 0 && isIdentifierChar(code[start - 1])) start--

    var end = probe + 1
    while (end < code.length && isIdentifierChar(code[end])) end++

    if (end <= start) return null
    return SymbolBounds(start = start, end = end, text = code.substring(start, end))
}

private fun completionCandidateName(variant: SourceCodeCompletionVariant): String {
    val descriptor: DeclarationDescriptor? = null // extraInfo not available in 2.4 scripting jars
    if (descriptor != null) return descriptor.name.asString()
    return variant.text
        .substringBefore('<')
        .substringBefore('(')
        .substringAfterLast('.')
        .trim()
}

/**
 * Type-aware completion after infix functions.
 *
 * When the cursor sits at the operand of an infix call — `receiver operatorName <caret>` — the
 * plain Kotlin REPL completion returns every symbol in scope alphabetically, burying the values
 * that actually fit. This resolves the infix operator's declared parameter type and lets the
 * completion handler boost candidates whose return type matches it, so e.g. after
 * `pulseBurst(10) through ` the ~dozens of `FnTransform`-producing builders float to the top
 * instead of the generic long tail.
 *
 * The operator's parameter type is discovered generically (no hard-coded operator list). The hot
 * path ([expectedOperandTypesFast]) reads it straight from the indexed Kotlin source — the
 * `infix fun … .<operator>(param: Type)` declarations — which is fast and cannot stall the
 * completion request. (An engine-driven fallback, [expectedOperandTypes], completes the operator
 * token itself and reads its `foo(ParamType)` display text; it is only used before the source index
 * has loaded.) Because infix operators are routinely overloaded on operand type (e.g.
 * `Fn.over(Domain)` and `Fn.over(ClosedRange<*>)`), resolution collects the parameter type of EVERY
 * matching overload into a set and a candidate is boosted when its return type matches ANY of them.
 * Results are cached per operator, so the cost is paid at most once per operator per session.
 */
internal object InfixCompletion {
    /** Ranking boost applied to type-matching operands; comfortably above category+recency, below a strong prefix hit. */
    const val OPERAND_MATCH_BOOST = 160

    private const val maxCacheEntries = 64
    private val paramTypeCache = object : LinkedHashMap<String, Set<String>>(maxCacheEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Set<String>>?): Boolean = size > maxCacheEntries
    }

    private val infixStopWords = setOf(
        "return", "in", "is", "as", "if", "else", "when", "while", "for", "do", "throw",
        "by", "where", "true", "false", "null", "this", "super", "import", "package",
        "val", "var", "fun", "class", "object", "interface", "typealias"
    )

    private fun isIdentChar(c: Char) = c.isLetterOrDigit() || c == '_'

    /**
     * True when [params] contains a comma at bracket-depth 0, i.e. one that separates multiple
     * parameters rather than generic type arguments (so `Map<String, Int>` is treated as a single
     * parameter, but `Domain, Int` is not). Used to skip multi-parameter (non-infix) entries.
     */
    private fun hasTopLevelComma(params: String): Boolean {
        var depth = 0
        for (c in params) {
            when (c) {
                '<', '(', '[' -> depth++
                '>', ')', ']' -> if (depth > 0) depth--
                ',' -> if (depth == 0) return true
            }
        }
        return false
    }

    private data class InfixSite(val operator: String, val operatorEnd: Int, val codeBeforeOperator: String)

    /** Cache of operator name -> resolved operand types, from the source index. Keyed by operator alone
     *  (an infix operator's parameter type does not depend on the surrounding edit), so it is paid at
     *  most once per operator per session and every later completion at that operator is instant. */
    private const val maxSourceCacheEntries = 256
    private val sourceTypeCache = object : LinkedHashMap<String, Set<String>>(maxSourceCacheEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Set<String>>?): Boolean = size > maxSourceCacheEntries
    }

    /**
     * Fast, allocation-light resolution of the infix operator's operand type(s) directly from the
     * indexed Kotlin source — NO second completion, so it never blocks the completion hot path.
     * Returns the type of every `infix fun … .<operator>(param: Type)` overload found (already
     * type-shortened), or an empty set if the cursor is not at an infix site or the operator isn't a
     * source-declared infix function. Cached per operator.
     */
    fun expectedOperandTypesFast(allCode: String, cursor: Int): Set<String> {
        val site = parseInfixSite(allCode, cursor) ?: return emptySet()
        synchronized(sourceTypeCache) { sourceTypeCache[site.operator]?.let { return it } }

        val resolved = runCatching { resolveInfixParamTypesFromSource(site.operator) }.getOrDefault(emptySet())
        synchronized(sourceTypeCache) { sourceTypeCache[site.operator] = resolved }
        return resolved
    }

    // Fallback copy of the on-disk index, parsed at most once and only while the live in-memory
    // index (KotlinSourceIndex.latestIndex) hasn't been populated yet (e.g. during startup sync).
    @Volatile
    private var loadedIndexFallback: afield.ai.KotlinSourceIndexDb? = null

    private fun sourceIndex(): afield.ai.KotlinSourceIndexDb? =
        KotlinSourceIndex.latestIndex
            ?: loadedIndexFallback
            ?: KotlinSourceIndex.loadIndex()?.also { loadedIndexFallback = it }

    private fun resolveInfixParamTypesFromSource(operator: String): Set<String> {
        val index = sourceIndex() ?: return emptySet()
        val root = Paths.get(System.getProperty("user.dir")).normalize()
        val defs = index.definitions.filter { it.kind == "fun" && it.name.substringAfterLast('.') == operator }
        if (defs.isEmpty()) return emptySet()

        val fileTextCache = HashMap<String, List<String>>()
        val types = LinkedHashSet<String>()
        for (def in defs) {
            val lines = fileTextCache.getOrPut(def.filePath) {
                runCatching { Files.readString(root.resolve(def.filePath)).split('\n') }.getOrDefault(emptyList())
            }
            // The declaration may wrap; scan a couple of lines from the indexed start for the signature.
            val start = (def.line - 1).coerceAtLeast(0)
            val window = lines.subList(start, (start + 3).coerceAtMost(lines.size)).joinToString(" ")
            parseInfixParamType(window, operator)?.let { types.add(it) }
        }
        return types
    }

    /** Extract the single parameter type from an `infix fun …<operator>(name: Type…)` declaration line, or null. */
    internal fun parseInfixParamType(declaration: String, operator: String): String? {
        val call = Regex("""\b${Regex.escape(operator)}\s*\(""").find(declaration) ?: return null
        if (!Regex("""\binfix\s+fun\b""").containsMatchIn(declaration.substring(0, call.range.first))) return null
        val open = declaration.indexOf('(', call.range.first)
        if (open < 0) return null
        // Infix functions take exactly one parameter, so the first close paren ends the list;
        // parameter types here contain no nested parentheses.
        val close = declaration.indexOf(')', open)
        if (close < 0) return null
        val param = declaration.substring(open + 1, close)
        val type = param.substringAfter(':', "").trim()
        return type.ifBlank { null }?.let { shortenType(it).trim().ifBlank { null } }
    }

    /** Backward-parse `receiver operatorName <operandPrefix>|` and return the operator token, or null if not an infix operand site. */
    private fun parseInfixSite(allCode: String, cursor: Int): InfixSite? {
        val pos = cursor.coerceIn(0, allCode.length)

        // Skip the operand prefix currently being typed.
        var operandStart = pos
        while (operandStart > 0 && isIdentChar(allCode[operandStart - 1])) operandStart--
        // There must be whitespace between the operator and the operand (infix requires it).
        if (operandStart == 0 || !allCode[operandStart - 1].isWhitespace()) return null

        var i = operandStart
        while (i > 0 && allCode[i - 1].isWhitespace()) i--
        val operatorEnd = i
        while (i > 0 && isIdentChar(allCode[i - 1])) i--
        val operator = allCode.substring(i, operatorEnd)
        if (operator.isEmpty() || !operator[0].isLetter() || operator in infixStopWords) return null

        // Whatever precedes the operator must terminate a value expression (the receiver), and the
        // operator must not itself be a `.member` access.
        var r = i
        while (r > 0 && allCode[r - 1].isWhitespace()) r--
        if (r == 0) return null
        val before = allCode[r - 1]
        val receiverPrecedes = before == ')' || before == ']' || before == '"' || before == '`' || isIdentChar(before)
        if (!receiverPrecedes || before == '.') return null

        return InfixSite(operator = operator, operatorEnd = operatorEnd, codeBeforeOperator = allCode.substring(0, operatorEnd))
    }

    /**
     * The declared operand type(s) of the infix operator at [cursor], already type-shortened (e.g.
     * `{FnTransform}`, or `{Domain, ClosedRange}` for an operator overloaded on operand type).
     * Returns an empty set when the cursor is not at an infix operand site or no overload can be
     * resolved. Every matching single-parameter overload contributes its parameter type, so a later
     * candidate is boosted when it matches ANY overload rather than only the first. [complete] is
     * the engine's completion entry point (kept as a parameter so this stays engine-agnostic and
     * easy to test).
     */
    suspend fun expectedOperandTypes(
        allCode: String,
        cursor: Int,
        complete: suspend (String, Int) -> CompletionResult
    ): Set<String> {
        val site = parseInfixSite(allCode, cursor) ?: return emptySet()
        val cacheKey = "${site.operator}::${site.codeBeforeOperator.hashCode()}::${site.codeBeforeOperator.length}"
        synchronized(paramTypeCache) { paramTypeCache[cacheKey]?.let { return it } }

        val resolved: Set<String> = runCatching {
            val result = complete(allCode, site.operatorEnd) as? CompletionResult.Success ?: return@runCatching emptySet()
            result.metadata.asSequence()
                .filter {
                    it.icon == "method" &&
                        it.displayText.substringBefore('(').substringAfterLast('.').trim() == site.operator &&
                        '(' in it.displayText
                }
                // Infix functions take exactly one parameter; the display text is `operator(ParamType)`.
                // Skip multi-parameter (non-infix) overloads, ignoring commas inside generic arguments.
                .mapNotNull {
                    val paramType = it.displayText.substringAfter('(').substringBeforeLast(')').trim()
                    if (paramType.isBlank() || hasTopLevelComma(paramType)) null
                    else shortenType(paramType).trim().ifBlank { null }
                }
                .toSet()
        }.getOrDefault(emptySet())

        synchronized(paramTypeCache) { paramTypeCache[cacheKey] = resolved }
        return resolved
    }

    /**
     * True when [candidate]'s return type ([SourceCodeCompletionVariant.tail]) matches any of the
     * [expectedTypes] resolved from the infix operator's overloads.
     */
    fun matchesOperandType(candidate: SourceCodeCompletionVariant, expectedTypes: Set<String>): Boolean {
        if (expectedTypes.isEmpty()) return false
        val returnType = shortenType(candidate.tail).trim()
        return returnType.isNotBlank() && returnType in expectedTypes
    }
}

private fun symbolContextResponse(symbol: String = "", html: String = ""): SymbolContextResponse {
    return SymbolContextResponse(
        symbol = symbol,
        html = html,
        isLiveValue = html.contains("afield-symbol-context-disclosure")
    )
}

private suspend fun isolatedSymbolContextComplete(
    box: Box,
    allCode: String,
    position: Int
): CompletionResult {
    lprintln(
        "debug,symbol-context",
        "symbol-context completion via existing box.ki engine=${box.ki::class.simpleName} box='${box.name.ifBlank { "Untitled" }}' (${box.id}) position=$position"
    )
    return box.ki.complete(allCode, position)
}

private data class SymbolContextCacheKey(
    val property: String,
    val codeHash: Int,
    val codeLength: Int,
    val cursor: Int,
    val contextGeneration: Int
)

private object KotlinSymbolContextCache {
    private const val maxEntries = 256
    private val cache = object : LinkedHashMap<SymbolContextCacheKey, SymbolContextResponse>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SymbolContextCacheKey, SymbolContextResponse>?): Boolean {
            return size > maxEntries
        }
    }

    @Synchronized
    fun get(key: SymbolContextCacheKey): SymbolContextResponse? = cache[key]

    @Synchronized
    fun put(key: SymbolContextCacheKey, value: SymbolContextResponse) {
        cache[key] = value
    }
}

private fun resolveInheritedPropertyWithSource(box: Box, name: String): Pair<Any?, Box?> {
    box.getPropertyRaw(name)?.let { return it to box }
    val seen = mutableSetOf(box)
    var fringe = setOf(box)
    while (fringe.isNotEmpty()) {
        val nextFringe = fringe.flatMap { it.parents }.toSet() - seen
        seen.addAll(nextFringe)
        for (parent in nextFringe) {
            parent.getPropertyRaw(name)?.let { return it to parent }
        }
        fringe = nextFringe
    }
    return null to null
}

private fun fallbackRuntimeSymbolContext(box: Box, symbol: String): SymbolContextResponse {
    box.ki.context.functions[symbol]?.let { info ->
        val location = prettifyContainerSummary(box, shortenType(info.line.javaClass.name))
        val html = buildString {
            append("<div style='display:inline-block; max-width: 620px;'>")
            append("<div style='color:#ddd; overflow-wrap:anywhere;'><code>")
            append(escapeNotifyHtml(info.toString(true)))
            append("</code></div>")
            append("<div style='color:#777;'>defined in ")
            append(location.html)
            append("</div>")
            append("</div>")
        }
        return symbolContextResponse(symbol = symbol, html = html)
    }

    box.ki.context.vars[symbol]?.let { info ->
        return symbolContextResponse(
            symbol = symbol,
            html = buildRuntimeVariableSymbolContextHtml(
                box = box,
                info = info,
                declarationOverride = runCatching { shortenType(info.descriptor.toString()) }
                    .getOrDefault(info.toString(true)),
                locationLabel = "defined in"
            )
        )
    }

    // Check if symbol corresponds to a preamble-defined extension property (e.g. from prop<T>())
    runCatching {
        val preamble = gatherPreambleUpwards(box)
        val propRegex =
            Regex("""var\s+Box\.${Regex.escape(symbol)}\s+by\s+(?:Inheritance|Delegation|InheritanceFromContext)\s*<\s*([^>]+?)\s*>""")
        propRegex.find(preamble)?.let { match ->
            val declaredType = shortenType(match.groupValues[1].trim())
            val (value, sourceBox) = resolveInheritedPropertyWithSource(box, symbol)
            val declaration = "var Box.$symbol: $declaredType"
            val locationLabel = if (sourceBox != null && sourceBox.id != box.id) "inherited from" else "property of"
            val locationBox = sourceBox ?: box
            val container = ContainerSummary(
                text = boxContextLabel(box, locationBox),
                html = "<i>${escapeNotifyHtml(boxContextLabel(box, locationBox))}</i>"
            )
            val html = buildString {
                append("<div style='display:inline-block; max-width: 620px;'>")
                append("<div style='color:#ddd; overflow-wrap:anywhere;'><code>")
                append(escapeNotifyHtml(declaration))
                append("</code></div>")
                append(renderVariableLocationSection(label = locationLabel, container = container, value = value))
                append("</div>")
            }
            return symbolContextResponse(symbol = symbol, html = html)
        }
    }

    return symbolContextResponse(symbol = symbol, html = "")
}

private fun renderPrintedOutput(content: Any?): String {
    return when (content) {
        null -> ""
        is String -> content
        is CharSequence -> content.toString()
        is Number, is Boolean, is Char, is Enum<*> -> content.toString()
        else -> ValueVisualizer.visualize(content, appendable = true)
    }
}

internal fun dispatchExecutionOutput(
    box: Box,
    reply: ((Message) -> Unit)?,
    output: Output,
    originSession: WebSocketSession? = thisSession.get()
) {
    val normalized = if (output.uid.isBlank()) output.copy(uid = box.id) else output
    logErrorOutputToTerminal(box, normalized)
    reply?.invoke(Message("output").payload(normalized))
    _sendToPeerSessions(
        Message("peer-output").payload(normalized),
        sessionInfo = box.inside,
        current = originSession
    )
}

private fun logErrorOutputToTerminal(box: Box, output: Output) {
    if (output.kind != "error" && output.kind != "err") return
    val text = terminalTextForOutputHtml(output.html).trim()
    if (text.isEmpty()) return

    val location = buildString {
        append(box.name.ifBlank { box.id })
        append(" ")
        append(output.property.ifBlank { "code" })
        if (output.line > 0) append(":").append(output.line)
    }
    lprintln("error,execution.ui", "$location\n$text")
}

private fun terminalTextForOutputHtml(html: String): String {
    return html
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(p|div|li|summary|details|pre|code|h[1-6])>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
        .lines()
        .joinToString("\n") { it.trimEnd() }
}

fun configureOutputProxies(box: Box, reply: (Message) -> Unit, originSession: WebSocketSession? = thisSession.get()) {
    box.output.proxy = { content, nl, line ->
        val n = renderPrintedOutput(content)
        if (n.trim().length > 0) {
            dispatchExecutionOutput(
                box,
                reply,
                Output(
                    uid = box.id,
                    line = line.i,
                    kind = "normal",
                    html = n.trim() + (if (nl) "\n" else ""),
                    frame = Runloop.runLoop.frame.toInt()
                ),
                originSession = originSession
            )
        }
    }

    box.output.errorProxy = { content, property, nl ->
        val n = content?.toString() ?: ""
        dispatchExecutionOutput(
            box,
            reply,
            Output(
                uid = box.id,
                line = nl.i,
                kind = "err",
                property = property,
                html = if (n.trim().isEmpty()) "" else escapeForHtml(n),
                frame = Runloop.runLoop.frame.toInt()
            ),
            originSession = originSession
        )
    }

    box.output.displayProxy = { channel, content ->
        send(Message("set-output").payload(Set_output(box.id, "outputarea", channel, renderDisplayContent(content))))
    }
}


@Suppress("UnstableApiUsage")
class KotlinExecution : BoxExecution {
    companion object {
        private const val PENDING_LAUNCH_MODE = "launching"
        private const val ACTIVE_LAUNCH_MODE = "launch"
        private const val BATCH_LAUNCH_INTER_BOX_YIELDS = 3

        private data class BatchLaunchRequest(
            val box: Box,
            val exec: Execute,
            val normalizedCode: String,
            val strand: String,
            val pendingWatermark: Int,
            val originSession: WebSocketSession?
        )

        private data class ExpandedBatchLaunch(
            val request: BatchLaunchRequest,
            val sources: List<String>
        )

        private data class PreparedBatchLaunch(
            val request: BatchLaunchRequest,
            val snippets: List<PreparedSnippet>
        )

        private fun resetLaunchSetupState(box: Box) {
            box.properties.remove("_preamble")
            box.properties.remove("_attic")
        }

        @OptIn(DelicateCoroutinesApi::class)
        fun launchBatch(requests: List<Pair<Box, Execute>>, reply: (Message) -> Unit) {
            if (requests.isEmpty()) return

            val originSession = thisSession.get()
            val pending = prepareBatchLaunchRequests(requests, originSession)

            GlobalScope.launch(Dispatchers.Default) {
                runPreparedBatchLaunches(pending, reply)
            }
        }

        suspend fun launchBatchAndWait(requests: List<Pair<Box, Execute>>, reply: (Message) -> Unit) {
            if (requests.isEmpty()) return
            val originSession = thisSession.get()
            val pending = prepareBatchLaunchRequests(requests, originSession)
            runPreparedBatchLaunches(pending, reply)
        }

        private fun prepareBatchLaunchRequests(
            requests: List<Pair<Box, Execute>>,
            originSession: WebSocketSession?
        ): List<BatchLaunchRequest> =
            requests.map { (box, exec) ->
                val strand = exec.strand
                val normalizedCode = normalizedLaunchCodeFor(exec)

                resetLaunchSetupState(box)

                val watermark = box.runGeneration.incrementAndGet(strand)
                box.currentRunningStates[strand] = RunningState(watermark, PENDING_LAUNCH_MODE)
                send(
                    Message("is-running").payload(
                        IsRunning(exec.uid, true, watermark, strand, PENDING_LAUNCH_MODE)
                    )
                )

                BatchLaunchRequest(box, exec, normalizedCode, strand, watermark, originSession)
            }

        private suspend fun runPreparedBatchLaunches(
            pending: List<BatchLaunchRequest>,
            reply: (Message) -> Unit
        ) {
            if (pending.isEmpty()) return
            withContext(Dispatchers.Default) {
                val expanded = pending.map { request ->
                    expandBatchLaunch(request, reply)
                }

                val prepared = expanded.map { launch ->
                    async {
                        compileBatchLaunch(launch, reply)
                    }
                }.awaitAll()

                inMain {
                    evaluateBatchLaunchesInOrder(prepared, reply)
                }.join()
            }
        }

        private suspend fun evaluateBatchLaunchesInOrder(
            prepared: List<PreparedBatchLaunch>,
            reply: (Message) -> Unit
        ) {
            var evaluatedAny = false
            prepared.forEach { launch ->
                if (launch.snippets.isEmpty()) {
                    clearPendingLaunch(launch.request)
                    return@forEach
                }

                if (evaluatedAny) {
                    repeat(BATCH_LAUNCH_INTER_BOX_YIELDS) { yield() }
                }
                evaluatedAny = true

                evaluatePreparedBatchLaunch(launch, reply)
            }
        }

        private fun normalizedLaunchCodeFor(exec: Execute): String {
            val leadingLines = exec.line.coerceAtLeast(0)
            return "\n".repeat(leadingLines) + exec.code.trimEnd()
        }

        private suspend fun expandBatchLaunch(
            request: BatchLaunchRequest,
            reply: (Message) -> Unit
        ): ExpandedBatchLaunch {
            val box = request.box
            val sources = mutableListOf<String>()

            Box.withBoxContext(box) {
                configureOutputProxies(box, reply, request.originSession)
                val lines = request.normalizedCode.lines()
                val breaks = lines.mapIndexedNotNull { index, line ->
                    if (line.trim().startsWith("//%-")) {
                        index
                    } else if (line.trim().startsWith("/*%-*/")) {
                        index
                    } else null
                } + lines.size

                var at = 0
                var lineCounter = 0
                for (breakIndex in breaks) {
                    val source = lines.subList(at, breakIndex).joinToString("\n")
                    val expanded = if (KotlinEngineConfig.offloadCompilation) {
                        withContext(box.ki.compilationDispatcher) { box.ki.applyMacros(box, source) }
                    } else {
                        box.ki.applyMacros(box, source)
                    }
                    val compiledSource = expanded.split("\n").map { line ->
                        lineCounter++
                        line.replace("__line__", "${lineCounter - 1}")
                    }.joinToString("\n")

                    withContext(Runloop.runLoop.main) {
                        runLaunchSetupBlocks(box)
                    }

                    sources += compiledSource
                    at = breakIndex
                }
            }

            return ExpandedBatchLaunch(request, sources)
        }

        private suspend fun compileBatchLaunch(
            expanded: ExpandedBatchLaunch,
            reply: (Message) -> Unit
        ): PreparedBatchLaunch {
            val request = expanded.request
            val box = request.box
            val snippets = mutableListOf<PreparedSnippet>()
            var compileFailed = false

            Box.withBoxContext(box) {
                configureOutputProxies(box, reply, request.originSession)

                // Drop any stale pinned compile error up front; reportCompileDiagnostics re-pins if this
                // compile also fails, so a clean compile simply leaves the panel cleared.
                PinnedConsole.clearError(box.id)

                for (compiledSource in expanded.sources) {
                    val snippet = box.ki.withSourceProperty(request.exec.property) {
                        box.ki.compileSnippet(
                            compiledSource,
                            implicits = listOf(box),
                            onCompileError = { diagnostics ->
                                reportCompileDiagnostics(
                                    box = box,
                                    reply = reply,
                                    originSession = request.originSession,
                                    code = request.normalizedCode,
                                    diagnostics = diagnostics,
                                    fallbackLine = request.exec.line + request.normalizedCode.lines().size,
                                    property = request.exec.property
                                )
                            }
                        )
                    }
                    if (snippet == null) {
                        compileFailed = true
                        snippets.clear()
                        return@withBoxContext
                    }
                    snippets += snippet
                }
            }

            return PreparedBatchLaunch(request, if (compileFailed) emptyList() else snippets)
        }

        private suspend fun runLaunchSetupBlocks(box: Box) {
            runSetupBlock(box, "preamble") { c, e, x -> checkPreambleAndExecute(box, c, e, x) }
            runSetupBlock(box, "introduction") { c, e, x -> checkIntroductionAndExecute(box, c, e, x) }
            runSetupBlock(box, "attic") { c, e, x -> checkAtticAndExecute(box, c, e, x) }
        }

        /**
         * Run one setup block, wiring its compile/eval/exception callbacks to both the existing macro logs
         * and a per-block sticky pinned panel (keyed `box#block`, distinct from the main [Box.id] key). The
         * panel is cleared up front so a now-fixed block stops showing its old error, and re-pinned if this
         * run still fails.
         */
        private suspend fun runSetupBlock(
            box: Box,
            block: String,
            run: suspend (
                onCompileError: (List<ScriptDiagnostic>) -> Unit,
                onEvalError: (List<ScriptDiagnostic>) -> Unit,
                onEvalException: (Throwable) -> Unit
            ) -> Unit
        ) {
            val key = "${box.id}#$block"
            PinnedConsole.clearError(key)
            run(
                { diagnostics ->
                    diagnostics.forEach { lprintln("warning,macros", "setup compile: ${it.message} ${it.location}") }
                    pinSetupError(box, key, block, diagnostics)
                },
                { diagnostics ->
                    diagnostics.forEach { lprintln("warning,macros", "setup eval: ${it.message}") }
                    pinSetupError(box, key, block, diagnostics)
                },
                { throwable ->
                    lprintln("error,macros", "setup exception: ${throwable.message}")
                    pinSetupException(box, key, block, throwable)
                }
            )
        }

        private suspend fun evaluatePreparedBatchLaunch(
            prepared: PreparedBatchLaunch,
            reply: (Message) -> Unit
        ) {
            val request = prepared.request
            val box = request.box
            clearPendingLaunch(request)

            Box.withBoxContext(box) {
                box.resetStdlibRandom()
                resetLaunchSetupState(box)
                configureOutputProxies(box, reply, request.originSession)

                var hasError = false
                val output = mutableListOf("")

                box.output.proxy = { content, nl, line ->
                    val rendered = renderPrintedOutput(content)
                    output[output.size - 1] = output[output.size - 1] + rendered
                    if (nl) output.add("")
                    dispatchExecutionOutput(
                        box,
                        reply,
                        Output(
                            uid = box.id,
                            line = line.i,
                            kind = "normal",
                            html = rendered.trim() + (if (nl) "\n" else ""),
                            frame = Runloop.runLoop.frame.toInt()
                        ),
                        originSession = request.originSession
                    )
                }
                box.output.errorProxy = { content, property, line ->
                    hasError = hasError || content != null
                    dispatchExecutionOutput(
                        box,
                        reply,
                        Output(
                            uid = box.id,
                            line = line.i,
                            kind = "error",
                            property = property,
                            html = escapeForHtml(content?.toString()),
                            frame = Runloop.runLoop.frame.toInt()
                        ),
                        originSession = request.originSession
                    )
                }
                box.output.displayProxy = { channel, content ->
                    send(
                        Message("set-output").payload(
                            Set_output(
                                box.id,
                                "outputarea",
                                channel,
                                renderDisplayContent(content)
                            )
                        )
                    )
                }

                val results = prepared.snippets.map { snippet ->
                    snippet.eval(
                        implicits = listOf(box),
                        onEvalError = { diagnostics ->
                            hasError = true
                            diagnostics.forEach {
                                lprintln("error,execution", "  ${it.code} ${it.message} ${it.location}")
                            }
                            pinEvalError(box, diagnostics)
                        },
                        onEvalException = { throwable ->
                            hasError = true
                            reportEvalException(
                                box,
                                reply,
                                request.originSession,
                                throwable,
                                request.exec.line,
                                request.exec.property
                            )
                        }
                    )
                }

                if (!hasError) {
                    val printedSize = output.sumOf { it.trim().length }
                    results.forEach { returnValue ->
                        reportReturnValueAndLaunch(
                            box = box,
                            reply = reply,
                            originSession = request.originSession,
                            returnValue = returnValue,
                            printedSize = printedSize,
                            line = request.exec.line + request.normalizedCode.lines().size,
                            code = request.normalizedCode,
                            strand = request.strand
                        )
                    }
                } else {
                    lprintln("error,execution", "batch launch eval finished with errors")
                }
            }
        }

        private fun clearPendingLaunch(request: BatchLaunchRequest) {
            val box = request.box
            if (box.currentRunningStates[request.strand]?.watermark == request.pendingWatermark) {
                box.currentRunningStates.remove(request.strand)
            }
            send(
                Message("is-running").payload(
                    IsRunning(request.exec.uid, false, request.pendingWatermark, request.strand, PENDING_LAUNCH_MODE)
                )
            )
        }

        /**
         * Pin the current compile failure for [box] as a sticky panel at the top of the terminal (see
         * [PinnedConsole.error]). Cleared on the next clean compile of the same box. The full diagnostic
         * text still scrolls via the `error,execution` lines; this is the always-visible summary.
         */
        private fun pinCompileError(box: Box, diagnostics: List<ScriptDiagnostic>) {
            val lines = diagnostics.mapNotNull { d ->
                val loc = d.location?.start?.let { " (${it.line}:${it.col})" } ?: ""
                "${d.severity}: ${d.message}$loc".trim().takeIf { it.isNotBlank() }
            }
            PinnedConsole.error(box.id, "compile error · ${box.id.take(8)}", lines)
        }

        /** Pin an eval-time diagnostic failure for [box] (see [pinCompileError]). Cleared on the next run. */
        private fun pinEvalError(box: Box, diagnostics: List<ScriptDiagnostic>) {
            val lines = diagnostics.mapNotNull { d ->
                val loc = d.location?.start?.let { " (${it.line}:${it.col})" } ?: ""
                "${d.severity}: ${d.message}$loc".trim().takeIf { it.isNotBlank() }
            }
            PinnedConsole.error(box.id, "eval error · ${box.id.take(8)}", lines)
        }

        /**
         * Pin a runtime exception thrown while evaluating [box] as a sticky panel (see [pinCompileError]):
         * the exception type + message plus the top few stack frames. The full trace still scrolls via the
         * `error,execution` line; this is the always-visible summary. Cleared on the next run of the box.
         */
        private fun pinEvalException(box: Box, throwable: Throwable) {
            val head = throwable.message?.takeIf { it.isNotBlank() } ?: throwable.javaClass.name
            val frames = throwable.stackTrace.take(6).map { "at $it" }
            PinnedConsole.error(
                box.id,
                "exception · ${box.id.take(8)}",
                listOf("${throwable.javaClass.simpleName}: $head") + frames
            )
        }

        /**
         * Pin a setup-block (preamble/introduction/attic) diagnostic failure. These blocks run before the
         * main code and their errors scroll past easily / are easy to miss, so they get their own sticky
         * panel keyed per block+box (distinct from the main [box.id] key, which is cleared each compile).
         */
        private fun pinSetupError(box: Box, key: String, block: String, diagnostics: List<ScriptDiagnostic>) {
            val lines = diagnostics.mapNotNull { d ->
                val loc = d.location?.start?.let { " (${it.line}:${it.col})" } ?: ""
                "${d.severity}: ${d.message}$loc".trim().takeIf { it.isNotBlank() }
            }
            PinnedConsole.error(key, "$block error · ${box.id.take(8)}", lines)
        }

        /** Pin a runtime exception thrown while running a setup block (see [pinSetupError]/[pinEvalException]). */
        private fun pinSetupException(box: Box, key: String, block: String, throwable: Throwable) {
            val head = throwable.message?.takeIf { it.isNotBlank() } ?: throwable.javaClass.name
            val frames = throwable.stackTrace.take(6).map { "at $it" }
            PinnedConsole.error(
                key,
                "$block exception · ${box.id.take(8)}",
                listOf("${throwable.javaClass.simpleName}: $head") + frames
            )
        }

        private fun reportCompileDiagnostics(
            box: Box,
            reply: ((Message) -> Unit)?,
            originSession: WebSocketSession?,
            code: String,
            diagnostics: List<ScriptDiagnostic>,
            fallbackLine: Int,
            property: String = "code"
        ) {
            lprintln("error,execution", "compiler error")
            pinCompileError(box, diagnostics)
            var first = true
            diagnostics.forEach {
                lprintln("error,execution", "  ${it.code} ${it.message} ${it.location} ${it.severity}")
                it.location?.start?.line?.let { lineNumber ->
                    val sourceLine = code.lines().getOrNull(lineNumber - 1)
                    if (sourceLine != null) {
                        lprintln("debug,execution", "compile error source line $lineNumber: $sourceLine")
                    }
                }
                if (first && it.severity.ordinal > ScriptDiagnostic.Severity.WARNING.ordinal) {
                    first = false
                    val message = improveMessage(box, it.message, code, it)
                    val fixes = suggestionsFor(box, it.message, code, it)
                    val location = it.location
                    val output = if (location != null) {
                        Output(
                            uid = box.id,
                            line = location.start.line,
                            startColumn = location.start.col,
                            endLine = location.end?.line ?: -1,
                            endColumn = location.end?.col ?: -1,
                            kind = "error",
                            property = property,
                            html = message,
                            frame = Runloop.runLoop.frame.toInt(),
                            suggestions = fixes
                        )
                    } else {
                        Output(
                            uid = box.id,
                            line = fallbackLine,
                            kind = "error",
                            property = property,
                            html = message,
                            frame = Runloop.runLoop.frame.toInt(),
                            suggestions = fixes
                        )
                    }
                    dispatchExecutionOutput(box, reply, output, originSession = originSession)
                }
            }
        }

        internal fun reportEvalException(
            box: Box,
            reply: ((Message) -> Unit)?,
            originSession: WebSocketSession?,
            throwable: Throwable,
            lineOffset: Int,
            fallbackProperty: String = "code",
            context: String = "batch launch"
        ) {
            lprintln("error,execution", "exception thrown in $context inside box $box")
            lprintln("error,execution", throwable.stackTraceToString())
            pinEvalException(box, throwable)

            val location = editorLocationForEvalException(box, throwable)
            val stack = stackLocationsForEvalException(box, throwable, location, fallbackProperty)
            dispatchExecutionOutput(
                box,
                reply,
                Output(
                    uid = box.id,
                    line = location?.line ?: lineOffset,
                    kind = "error",
                    property = location?.property ?: fallbackProperty,
                    html = escapeForHtml(throwable.message ?: throwable.javaClass.name),
                    frame = Runloop.runLoop.frame.toInt(),
                    stack = stack
                ),
                originSession = originSession
            )
        }

        private data class EditorStackLocation(val uid: String?, val line: Int, val property: String?)

        private fun editorLocationForEvalException(box: Box, throwable: Throwable): EditorStackLocation? {
            val fallback = throwable.stackTrace.firstOrNull { it.lineNumber > 0 }?.let {
                EditorStackLocation(
                    resolveStackFrameBoxUid(box, it),
                    it.lineNumber,
                    fieldSourcePropertyFromFileName(it.fileName)
                )
            }
            val boxFrames = throwable.stackTrace.filter {
                it.lineNumber > 0 && it.fileName?.contains(box.id) == true
            }
            return boxFrames.firstOrNull { !it.isGeneratedPropertyAccessorFrame() }?.let {
                EditorStackLocation(box.id, it.lineNumber, fieldSourcePropertyFromFileName(it.fileName))
            }
                ?: boxFrames.firstOrNull()?.let {
                    EditorStackLocation(box.id, it.lineNumber, fieldSourcePropertyFromFileName(it.fileName))
                }
                ?: fallback
        }

        private fun stackLocationsForEvalException(
            box: Box,
            throwable: Throwable,
            primary: EditorStackLocation?,
            fallbackProperty: String
        ): List<OutputStackLocation> {
            val seen = linkedSetOf<Triple<String, String, Int>>()
            val out = mutableListOf<OutputStackLocation>()
            throwable.stackTrace.forEach { frame ->
                if (frame.lineNumber <= 0) return@forEach
                val uid = resolveStackFrameBoxUid(box, frame) ?: return@forEach
                val property = fieldSourcePropertyFromFileName(frame.fileName) ?: fallbackProperty.ifBlank { "code" }
                val key = Triple(uid, property, frame.lineNumber)
                if (!seen.add(key)) return@forEach
                val isPrimary = uid == (primary?.uid ?: box.id) &&
                        property == (primary?.property ?: fallbackProperty) &&
                        frame.lineNumber == primary?.line
                out += OutputStackLocation(
                    uid = uid,
                    property = property,
                    line = frame.lineNumber,
                    method = frame.methodName ?: "",
                    primary = isPrimary
                )
            }
            return out
        }

        private fun resolveStackFrameBoxUid(box: Box, frame: StackTraceElement): String? {
            val fileName = frame.fileName ?: return null
            val candidates = buildList {
                if (box.isInsideInitialized()) {
                    addAll(box.inside.boxes.values)
                    add(box.inside.root)
                } else {
                    add(box)
                }
            }
            return candidates.firstOrNull { fileName.contains(it.id) }?.id
        }

        private fun StackTraceElement.isGeneratedPropertyAccessorFrame(): Boolean {
            val method = methodName ?: return false
            if (method.startsWith("get") && method.getOrNull(3)?.isUpperCase() == true) return true
            if (method.startsWith("set") && method.getOrNull(3)?.isUpperCase() == true) return true
            return method.contains("\$delegate") || method.contains("\$delegatedProperties")
        }

        private fun reportReturnValueAndLaunch(
            box: Box,
            reply: (Message) -> Unit,
            originSession: WebSocketSession?,
            returnValue: ResultValue?,
            printedSize: Int,
            line: Int,
            code: String,
            strand: String
        ) {
            when (returnValue) {
                is ResultValue.Unit -> {
                    if (printedSize == 0) reportOk(box, reply, originSession, line)
                    launchPreparedReturnValue(box, null, strand, code, reply, originSession)
                }

                is ResultValue.NotEvaluated -> {}

                is ResultValue.Error -> {
                    dispatchExecutionOutput(
                        box,
                        reply,
                        Output(
                            uid = box.id,
                            line = line,
                            kind = "error",
                            html = escapeForHtml(returnValue.error.message ?: returnValue.error.javaClass.name),
                            frame = Runloop.runLoop.frame.toInt()
                        ),
                        originSession = originSession
                    )
                    launchPreparedReturnValue(box, null, strand, code, reply, originSession)
                }

                is ResultValue.Value -> {
                    val audioPatch = returnValue.value as? AudioPatchSpec
                    if (printedSize == 0) {
                        if (returnValue.value == null) {
                            reportOk(box, reply, originSession, line)
                            launchPreparedReturnValue(box, null, strand, code, reply, originSession)
                            return
                        }
                        val type = simplifyTypeForOutput(returnValue.type)
                        val annotation = "<i>${returnValue.name}=</i>${type}"
                        audioPatch?.inlineSummaryTarget = InlineAudioSummaryTarget(line = line, annotation = annotation)
                        val value = renderReturnValueOutput(returnValue.value)
                        if (value.isNotEmpty()) {
                            dispatchExecutionOutput(
                                box,
                                reply,
                                Output(
                                    uid = box.id,
                                    line = line,
                                    kind = "normal",
                                    html = value,
                                    annotation = annotation,
                                    frame = Runloop.runLoop.frame.toInt()
                                ),
                                originSession = originSession
                            )
                        } else {
                            reportOk(box, reply, originSession, line)
                        }
                    } else {
                        audioPatch?.inlineSummaryTarget = null
                    }

                    launchPreparedReturnValue(box, returnValue.value, strand, code, reply, originSession)
                }

                null -> {
                    if (printedSize == 0) reportOk(box, reply, originSession, line)
                    launchPreparedReturnValue(box, null, strand, code, reply, originSession)
                }
            }
        }

        private fun reportOk(
            box: Box,
            reply: (Message) -> Unit,
            originSession: WebSocketSession?,
            line: Int
        ) {
            dispatchExecutionOutput(
                box,
                reply,
                Output(
                    uid = box.id,
                    line = line,
                    kind = "ok",
                    html = "ok",
                    frame = Runloop.runLoop.frame.toInt()
                ),
                originSession = originSession
            )
        }

        private fun launchPreparedReturnValue(
            box: Box,
            returnValue: Any?,
            strand: String,
            code: String,
            reply: (Message) -> Unit,
            originSession: WebSocketSession?
        ) {
            if (returnValue == null) {
                box.cachedLaunchReturns.remove(strand)
            } else {
                box.cachedLaunchReturns[strand] = CachedLaunchReturn(code, returnValue)
            }

            configureOutputProxies(box, reply, originSession)
            ReturnValueRegistry.launch(box, returnValue, strand) { isRunning, notifiedStrand, watermark ->
                if (isRunning) {
                    box.currentRunningStates[notifiedStrand] = RunningState(watermark, ACTIVE_LAUNCH_MODE)
                } else if (box.currentRunningStates[notifiedStrand]?.watermark == watermark) {
                    box.currentRunningStates.remove(notifiedStrand)
                }
                send(
                    Message("is-running").payload(
                        IsRunning(box.id, isRunning, watermark, notifiedStrand, ACTIVE_LAUNCH_MODE)
                    )
                )
            }
        }
    }

    private fun normalizedLaunchCode(exec: Execute): String {
        return normalizedLaunchCodeFor(exec)
    }

    private fun cacheLaunchReturnValue(box: Box, strand: String, code: String, returnValue: Any?) {
        if (returnValue == null) {
            box.cachedLaunchReturns.remove(strand)
            return
        }
        box.cachedLaunchReturns[strand] = CachedLaunchReturn(code, returnValue)
    }

    override fun executeFragment(inside: Box, execute: Execute, reply: ((Message) -> Unit)) {
        val originSession = thisSession.get()

        val ln = Math.max(0, execute.line)

        val code = ("\n" * ln) + execute.code.trimEnd()

        val dur = +execute.code.count { it == '\n' }

        val existingFunction =
            inside.ki.context.functions.filter { it.key == code.trim() }.map { it.value }.firstOrNull()
        if (existingFunction != null) {
            lprintln("debug,execution", "found existing function: $existingFunction")

            dispatchExecutionOutput(
                inside,
                reply,
                Output(
                    uid = inside.id,
                    line = ln + dur,
                    kind = "normal",
                    html = existingFunction.function.toString() + "<br>",
                    annotation = "<i>${existingFunction.name}=</i>(function)",
                    frame = Runloop.runLoop.frame.toInt()
                ),
                originSession = originSession
            )
            return
        }

        lprintln("debug,execution", "global scope ts is ${thisSession.get()}")
        val wm = ++inside.fragmentGeneration

        inside._currentlyExecutingFragment = true
        send(Message("is-running").payload(IsRunning(execute.uid, true, wm, "__fragment__")))
        inMain {
            executeInBox(
                inside,
                code,
                reply,
                originSession,
                0,
                property = execute.property,
                runSetupBlocks = true,
                finishedHandler = {
                    inside._currentlyExecutingFragment = false
                    send(Message("is-running").payload(IsRunning(execute.uid, false, wm, "__fragment__")))
                }) {}
        }


    }

    override suspend fun executeFragmentAndWait(inside: Box, execute: Execute, reply: ((Message) -> Unit)) {
        val originSession = thisSession.get()
        val ln = Math.max(0, execute.line)
        val code = ("\n" * ln) + execute.code.trimEnd()
        val wm = ++inside.fragmentGeneration
        inside._currentlyExecutingFragment = true
        send(Message("is-running").payload(IsRunning(execute.uid, true, wm, "__fragment__")))
        // Run on the main dispatcher (drained by the runloop) and JOIN so the caller
        // observes completion + output — the awaited analogue of executeFragment.
        inMain {
            executeInBox(
                inside,
                code,
                reply,
                originSession,
                0,
                property = execute.property,
                runSetupBlocks = true,
                finishedHandler = {
                    inside._currentlyExecutingFragment = false
                    send(Message("is-running").payload(IsRunning(execute.uid, false, wm, "__fragment__")))
                }) {}
        }.join()
    }

    override fun executeAll(inside: Box, execute: Execute, reply: ((Message) -> Unit)) {
        val originSession = thisSession.get()

        val code = execute.code.trimEnd()

        val wm = ++inside.fragmentGeneration
        send(Message("is-running").payload(IsRunning(execute.uid, true, wm, "__fragment__")))
        inMain {
            inside.resetStdlibRandom()
            executeInBox(
                inside,
                code,
                reply,
                originSession,
                code.split("\n").size,
                property = execute.property,
                finishedHandler = {
                    send(Message("is-running").payload(IsRunning(execute.uid, false, wm, "__fragment__")))
                }) {}
        }


    }

    override fun launchAll(inside: Box, execute: Execute, code: String, strand: String, reply: ((Message) -> Unit)) {
        val originSession = thisSession.get()

        resetLaunchSetupState(inside)

        val normalizedCode = normalizedLaunchCode(execute)
        val pendingLaunchMode = PENDING_LAUNCH_MODE
        val launchMode = ACTIVE_LAUNCH_MODE
        val pendingWatermark = inside.runGeneration.incrementAndGet(strand)
        inside.currentRunningStates[strand] = RunningState(pendingWatermark, pendingLaunchMode)
        val telemetrySnippetId = CodeUseTelemetryService.newSnippetKey()
        val telemetryContext = CodeUseTelemetryService.captureBoxContext(
            inside,
            property = execute.property,
            mode = "launch",
            strand = strand
        )
        CodeUseTelemetryService.recordLaunchLifecycle(
            snippetId = telemetrySnippetId,
            source = normalizedCode,
            context = telemetryContext,
            lifecycle = pendingLaunchMode,
            watermark = pendingWatermark
        )
        send(
            Message("is-running").payload(
                IsRunning(execute.uid, true, pendingWatermark, strand, pendingLaunchMode)
            )
        )

        inMain {

            inside.resetStdlibRandom()
            resetLaunchSetupState(inside)


            // TODO! support pre-compilation. This executes the box (once) and grabs it's return value, then launch-all will simply do that. This means you end up with a lot in frames {}
            // this is just the field2 design

            executeInBox(
                inside,
                normalizedCode,
                reply,
                originSession,
                execute.line,
                property = execute.property,
                finishedHandler = {
                    if (inside.currentRunningStates[strand]?.watermark == pendingWatermark) {
                        inside.currentRunningStates.remove(strand)
                    }
                    send(
                        Message("is-running").payload(
                            IsRunning(execute.uid, false, pendingWatermark, strand, pendingLaunchMode)
                        )
                    )
                    CodeUseTelemetryService.recordLaunchLifecycle(
                        snippetId = telemetrySnippetId,
                        source = "",
                        context = telemetryContext,
                        lifecycle = "launch-prepare-finished",
                        watermark = pendingWatermark
                    )
                }
            ) { returnValue ->
                if (inside.currentRunningStates[strand]?.watermark == pendingWatermark) {
                    inside.currentRunningStates.remove(strand)
                    send(
                        Message("is-running").payload(
                            IsRunning(execute.uid, false, pendingWatermark, strand, pendingLaunchMode)
                        )
                    )
                }
                CodeUseTelemetryService.recordLaunchLifecycle(
                    snippetId = telemetrySnippetId,
                    source = "",
                    context = telemetryContext,
                    lifecycle = "launch-return",
                    watermark = pendingWatermark,
                    returnType = returnValue?.javaClass?.name.orEmpty()
                )
                cacheLaunchReturnValue(inside, strand, normalizedCode, returnValue)
                configureOutputProxies(inside, reply, originSession)

                launchReturnValue(inside, returnValue, strand = strand) { isRunning, strand, watermark ->
                    if (isRunning) {
                        inside.currentRunningStates[strand] = RunningState(watermark, launchMode)
                    } else if (inside.currentRunningStates[strand]?.watermark == watermark) {
                        inside.currentRunningStates.remove(strand)
                    }
                    send(
                        Message("is-running").payload(
                            IsRunning(execute.uid, isRunning, watermark, strand, launchMode)
                        )
                    )
                    CodeUseTelemetryService.recordLaunchLifecycle(
                        snippetId = telemetrySnippetId,
                        source = "",
                        context = CodeUseTelemetryService.captureBoxContext(
                            inside,
                            property = execute.property,
                            mode = launchMode,
                            strand = strand
                        ),
                        lifecycle = if (isRunning) "launch-running" else "launch-stopped",
                        watermark = watermark
                    )
                }
            }
        }
    }

    override fun continueAll(inside: Box, execute: Execute, code: String, strand: String, reply: ((Message) -> Unit)) {
        val cached = inside.cachedLaunchReturns[strand]
        val normalizedCode = normalizedLaunchCode(execute)

        if (cached == null || cached.code != normalizedCode) {
            lprintln("debug,execution", "continue cache miss for ${inside.id} on strand $strand")
            launchAll(inside, execute, code, strand, reply)
            return
        }

        val originSession = thisSession.get()
        val launchMode = "continue"
        lprintln("debug,execution", "continuing cached return value for ${inside.id} on strand $strand")
        val telemetrySnippetId = CodeUseTelemetryService.newSnippetKey()
        val telemetryContext = CodeUseTelemetryService.captureBoxContext(
            inside,
            property = execute.property,
            mode = launchMode,
            strand = strand
        )
        CodeUseTelemetryService.recordLaunchLifecycle(
            snippetId = telemetrySnippetId,
            source = normalizedCode,
            context = telemetryContext,
            lifecycle = "continue-start",
            returnType = cached.returnValue.javaClass.name
        )
        inMain {
            configureOutputProxies(inside, reply, originSession)
            launchReturnValue(inside, cached.returnValue, strand = strand) { isRunning, notifiedStrand, watermark ->
                if (isRunning) {
                    inside.currentRunningStates[notifiedStrand] = RunningState(watermark, launchMode)
                } else if (inside.currentRunningStates[notifiedStrand]?.watermark == watermark) {
                    inside.currentRunningStates.remove(notifiedStrand)
                }
                send(
                    Message("is-running").payload(
                        IsRunning(execute.uid, isRunning, watermark, notifiedStrand, launchMode)
                    )
                )
                CodeUseTelemetryService.recordLaunchLifecycle(
                    snippetId = telemetrySnippetId,
                    source = "",
                    context = CodeUseTelemetryService.captureBoxContext(
                        inside,
                        property = execute.property,
                        mode = launchMode,
                        strand = notifiedStrand
                    ),
                    lifecycle = if (isRunning) "continue-running" else "continue-stopped",
                    watermark = watermark,
                    returnType = cached.returnValue.javaClass.name
                )
            }
        }
    }


    suspend fun executeInBox(
        box: Box,
        code: String,
        reply: ((Message) -> Unit)?,
        originSession: WebSocketSession?,
        ln: Int,
        property: String = "code",
        runSetupBlocks: Boolean = true,
        finishedHandler: () -> Unit = {},
        handleReturn: (Any?) -> Unit
    ) {

        Box.withBoxContext(box) {
            val telemetrySnippetId = CodeUseTelemetryService.newSnippetKey()
            val telemetryContext = CodeUseTelemetryService.captureBoxContext(
                box,
                property = property,
                mode = "execute"
            )
            CodeUseTelemetryService.recordLaunchLifecycle(
                snippetId = telemetrySnippetId,
                source = code,
                context = telemetryContext,
                lifecycle = "execute-start"
            )
            var dur = code.split("\n").count()
            var telemetryFinished = false
            fun finishTelemetry(lifecycle: String, returnValue: Any? = null, exception: Throwable? = returnValue as? Throwable) {
                if (telemetryFinished) return
                telemetryFinished = true
                CodeUseTelemetryService.recordLaunchLifecycle(
                    snippetId = telemetrySnippetId,
                    source = "",
                    context = telemetryContext,
                    lifecycle = lifecycle,
                    returnType = returnValue?.javaClass?.name.orEmpty(),
                    exception = exception
                )
            }
            try {
                var hasError = false
                var output = mutableListOf<String>("")
                box.output.proxy = { content, nl, line ->
                    val rendered = renderPrintedOutput(content)
                    output[output.size - 1] = output[output.size - 1] + rendered
                    if (nl) output.add("")

//                    lprintln("debug,execution", "out $content")
                    dispatchExecutionOutput(
                        box,
                        reply,
                        Output(
                            uid = box.id,
                            line = line.i,
                            kind = "normal",
                            html = rendered.trim() + (if (nl) "\n" else ""),
                            frame = Runloop.runLoop.frame.toInt()
                        ),
                        originSession = originSession
                    )

                }
                box.output.errorProxy = { content, property, line ->
                    hasError = hasError || content != null
                    dispatchExecutionOutput(
                        box,
                        reply,
                        Output(
                            uid = box.id,
                            line = (line + ln).i,
                            kind = "error",
                            property = property,
                            html = escapeForHtml(content?.toString()),
                            frame = Runloop.runLoop.frame.toInt()
                        ),
                        originSession = originSession
                    )
                }
                box.output.displayProxy = { channel, content ->
                    send(
                        Message("set-output").payload(
                            Set_output(
                                box.id,
                                "outputarea",
                                channel,
                                renderDisplayContent(content)
                            )
                        )
                    )
                }

                // Clear any stale pinned compile error before re-running; re-pinned below on failure.
                PinnedConsole.clearError(box.id)
                val returnValues: List<ResultValue?> = box.ki.withSourceProperty(property) {
                    standardExecutionPractice(box.ki, code, { compileError ->
                        hasError = true
                        lprintln("error,execution", "compiler error")
                        pinCompileError(box, compileError)
                        var first = true
                        compileError.forEach {
                            lprintln("error,execution", "  ${it.code} ${it.message} ${it.location} ${it.severity}")
                            it.location?.start?.line?.let { lineNumber ->
                                val lineIndex = lineNumber - 1
                                val sourceLine = code.lines().getOrNull(lineIndex)
                                if (sourceLine != null) {
                                    lprintln("debug,execution", "compile error source line $lineNumber: $sourceLine")
                                }
                            }
                            if (first && it.severity.ordinal > ScriptDiagnostic.Severity.WARNING.ordinal) {
                                var message = it.message
                                val fixes = suggestionsFor(box, message, code, it)
                                message = improveMessage(box, message, code, it)
                                first = false
                                if (it.location != null) {
                                    dispatchExecutionOutput(
                                        box,
                                        reply,
                                        Output(
                                            uid = box.id,
                                            line = it.location!!.start.line,
                                            startColumn = it.location!!.start.col,
                                            endLine = it.location!!.end?.line ?: -1,
                                            endColumn = it.location!!.end?.col ?: -1,
                                            kind = "error",
                                            property = property,
                                            html = message,
                                            frame = Runloop.runLoop.frame.toInt(),
                                            suggestions = fixes
                                        ),
                                        originSession = originSession
                                    )
                                } else {
                                    dispatchExecutionOutput(
                                        box,
                                        reply,
                                        Output(
                                            uid = box.id,
                                            line = ln + dur,
                                            kind = "error",
                                            property = property,
                                            html = message,
                                            frame = Runloop.runLoop.frame.toInt(),
                                            suggestions = fixes
                                        ),
                                        originSession = originSession
                                    )
                                }
                            }
                        }
                    }, { evalError ->
                        hasError = true
                        lprintln("error,execution", "eval error")
                        evalError.forEach {
                            lprintln("error,execution", "  ${it.code} ${it.message} ${it.location}")
                        }
                        pinEvalError(box, evalError)
                    }, { evalException ->
                        hasError = true
                        lprintln("error,execution", "exception thrown in fragment inside box $box")
                        lprintln("error,execution", evalException.stackTraceToString())
                        pinEvalException(box, evalException)

                        val location = editorLocationForEvalException(box, evalException)
                        val firstLine = location?.line ?: ln
                        val stack = stackLocationsForEvalException(box, evalException, location, property)
                        dispatchExecutionOutput(
                            box,
                            reply,
                            Output(
                                uid = box.id,
                                line = firstLine,
                                kind = "error",
                                property = location?.property ?: property,
                                html = escapeForHtml(evalException.message ?: evalException.javaClass.name),
                                frame = Runloop.runLoop.frame.toInt(),
                                stack = stack
                            ),
                            originSession = originSession
                        )

                    }, implicits = listOf(box), runSetupBlocks = runSetupBlocks)
                }

                if (!hasError) {
                    var size = 0
                    output.forEach { size += it.trim().length }
//                    reply?.invoke(
//                        Message("output").payload(
//                            Output(
//                                line = ln + dur, kind = "normal", html = output.joinToString(separator = "<br>")
//                            )
//                        )
//                    )

                    returnValues.forEach { returnValue ->
                        when (returnValue) {
                            is ResultValue.Unit -> {
                                if (size == 0) dispatchExecutionOutput(
                                    box,
                                    reply,
                                    Output(
                                        uid = box.id,
                                        line = dur,
                                        kind = "ok",
                                        html = "ok",
                                        frame = Runloop.runLoop.frame.toInt()
                                    ),
                                    originSession = originSession
                                )
                                handleReturn(null)
                                finishTelemetry("execute-finished")
                            }

                            is ResultValue.NotEvaluated -> {}

                            is ResultValue.Error -> {
                                dispatchExecutionOutput(
                                    box,
                                    reply,
                                    Output(
                                        uid = box.id,
                                        line = ln + dur,
                                        kind = "error",
                                        html = escapeForHtml(
                                            returnValue.error.message ?: returnValue.error.javaClass.name
                                        ),
                                        frame = Runloop.runLoop.frame.toInt()
                                    ),
                                    originSession = originSession
                                )
                                handleReturn(null)
                                finishTelemetry("execute-error", returnValue.error)
                            }

                            is ResultValue.Value -> {
                                val audioPatch = returnValue.value as? AudioPatchSpec
                                if (size == 0) {
                                    if (returnValue.value == null) {
                                        dispatchExecutionOutput(
                                            box,
                                            reply,
                                            Output(
                                                uid = box.id,
                                                line = dur,
                                                kind = "ok",
                                                html = "ok",
                                                frame = Runloop.runLoop.frame.toInt()
                                            ),
                                            originSession = originSession
                                        )
                                        handleReturn(null)
                                        finishTelemetry("execute-finished")
                                        return@forEach
                                    }
                                    val name = returnValue.name
                                    val type = simplifyTypeForOutput(returnValue.type)
                                    val annotation = "<i>${name}=</i>${type}"
                                    audioPatch?.inlineSummaryTarget = InlineAudioSummaryTarget(
                                        line = ln + dur,
                                        annotation = annotation
                                    )
                                    val value = renderReturnValueOutput(returnValue.value)

                                    if (value.length > 0) {
                                        dispatchExecutionOutput(
                                            box,
                                            reply,
                                            Output(
                                                uid = box.id,
                                                line = ln + dur,
                                                kind = "normal",
                                                html = value,
                                                annotation = annotation,
                                                frame = Runloop.runLoop.frame.toInt()
                                            ),
                                            originSession = originSession
                                        )
                                    } else dispatchExecutionOutput(
                                        box,
                                        reply,
                                        Output(
                                            uid = box.id,
                                            line = ln + dur,
                                            kind = "ok",
                                            html = "ok",
                                            frame = Runloop.runLoop.frame.toInt()
                                        ),
                                        originSession = originSession
                                    )
                                } else audioPatch?.inlineSummaryTarget = null

                                handleReturn(returnValue.value)
                                finishTelemetry("execute-finished", returnValue.value)
                            }

                            null -> {
                                if (size == 0) dispatchExecutionOutput(
                                    box,
                                    reply,
                                    Output(
                                        uid = box.id,
                                        line = ln + dur,
                                        kind = "ok",
                                        html = "ok",
                                        frame = Runloop.runLoop.frame.toInt()
                                    ),
                                    originSession = originSession
                                )
                                handleReturn(null)
                                finishTelemetry("execute-finished")
                            }

                        }

                    }
                } else {
                    lprintln("error,execution", "execution finished with errors")
                    finishTelemetry("execute-error")
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                finishTelemetry("execute-exception", t, t)
                lprintln("error,execution", "uncaught exception while executing fragment inside box $box")
                lprintln("error,execution", t.stackTraceToString())
                dispatchExecutionOutput(
                    box,
                    reply,
                    Output(
                        uid = box.id,
                        line = ln + dur,
                        kind = "error",
                        html = escapeForHtml(t.message ?: t.javaClass.name),
                        frame = Runloop.runLoop.frame.toInt()
                    ),
                    originSession = originSession
                )
            } finally {
                finishTelemetry("execute-finished")
                finishedHandler()
            }
        }

    }

    fun launchReturnValue(
        inside: Box,
        returnValue: Any?,
        strand: String = "default",
        notify: (running: Boolean, strand: String, watermark: Int) -> Unit
    ) {
        ReturnValueRegistry.launch(inside, returnValue, strand, notify)
    }

    override fun stop(inside: Box) {
        inside.runGeneration.incrementAndGet("default")
        val jobsToCancel = inside.jobs.toList()
        inside.jobs.clear()
        inside.clearRunningStates()
        jobsToCancel.forEach { it.cancel() }
    }

    private fun completeYamlMetadata(box: Box, allCode: String, position: Int, line: Int, column: Int): Completion {
        // Helper function to calculate bounds for replacing a prefix
        fun calculateBounds(
            lines: List<String>,
            currentLineIndex: Int,
            prefixInLine: String,
            currentPosition: Int
        ): Pair<Int, Int> {
            // Calculate line start position, accounting for newline characters
            val lineStart = lines.take(currentLineIndex).sumOf { lineText: String -> lineText.length + 1 }
            val beforeCursor = lines[currentLineIndex].substring(0, minOf(column, lines[currentLineIndex].length))
            val prefixStart = beforeCursor.lastIndexOf(prefixInLine)
            return if (prefixStart >= 0) {
                Pair(lineStart + prefixStart, currentPosition)
            } else {
                Pair(currentPosition, currentPosition)
            }
        }

        // Get the current line text to understand context
        val lines = allCode.split("\n")
        val currentLineIndex = line - 1
        if (currentLineIndex < 0 || currentLineIndex >= lines.size) {
            return Completion(boundsStart = position, boundsEnd = position, metadata = emptyList())
        }

        val currentLine = lines[currentLineIndex]
        val beforeCursor = currentLine.substring(0, minOf(column, currentLine.length))

        // Common YAML keys for Field metadata
        val commonKeys = listOf(
            "tags" to "List of tags for categorizing this box (used in templates and documentation)",
            "docOrder" to "Priority for documentation generation (lower numbers first)",
            "doc" to "Text added to the documentation if this box ends up in a template",
            "boxPreamble" to "Text added before this specific box's code in documentation",
            "execBeforeDoc" to "Execute code before documenting (integer value)",
            "description" to "Description of this box"
        )

        val completions = mutableListOf<Metadata>()
        var boundsStart = position
        var boundsEnd = position

        // Check if we're completing a key (at start of line or after whitespace, before ':')
        val keyPattern = Regex("^\\s*([a-zA-Z0-9_\\-]*)$")
        val keyMatch = keyPattern.find(beforeCursor)

        if (keyMatch != null) {
            // Complete YAML keys
            val prefix = keyMatch.groupValues.getOrNull(1) ?: ""
            if (prefix.isNotEmpty()) {
                val bounds = calculateBounds(lines, currentLineIndex, prefix, position)
                boundsStart = bounds.first
                boundsEnd = bounds.second
            }

            commonKeys.forEach { (key, description) ->
                if (key.startsWith(prefix, ignoreCase = true)) {
                    completions.add(
                        Metadata(
                            icon = "keyword",
                            tail = "metadata key",
                            text = "$key: ",
                            displayText = key,
                            documentation = description
                        )
                    )
                }
            }
        }

        // Check if we're completing a tag value
        // Pattern matches: "tags:" followed by optional list items, then the current item prefix
        val textBeforeCursor = allCode.substring(0, position)

        // Match multi-line list format: tags:\n  - item\n  - [prefix]
        val tagsMultiLineMatch =
            Regex("tags:\\s*(?:\\n\\s*-\\s*[\\w\\-]+)*\\n\\s*-\\s*([a-zA-Z0-9_\\-]*)$", RegexOption.MULTILINE)
                .find(textBeforeCursor)

        // Match single-line array format: tags: [item1, item2, [prefix]
        val tagsSingleLineMatch = Regex("tags:\\s*\\[(?:[^\\]]*,\\s*)?([a-zA-Z0-9_\\-]*)$")
            .find(beforeCursor)

        if (tagsMultiLineMatch != null || tagsSingleLineMatch != null) {
            val prefix = tagsMultiLineMatch?.groupValues?.getOrNull(1)
                ?: tagsSingleLineMatch?.groupValues?.getOrNull(1)
                ?: ""

            // Only collect tags if we have a meaningful context
            if (tagsMultiLineMatch != null || tagsSingleLineMatch != null) {
                // Complete tag values - collect existing tags from the workspace
                val existingTags = collectTagsFromWorkspace(box)

                // Calculate bounds to replace the prefix
                if (prefix.isNotEmpty()) {
                    val bounds = calculateBounds(lines, currentLineIndex, prefix, position)
                    boundsStart = bounds.first
                    boundsEnd = bounds.second
                }

                existingTags.forEach { tag ->
                    if (tag.startsWith(prefix, ignoreCase = true)) {
                        completions.add(
                            Metadata(
                                icon = "value",
                                tail = "tag",
                                text = tag,
                                displayText = tag,
                                documentation = "Existing tag from workspace"
                            )
                        )
                    }
                }

                // Also suggest some common tags if no prefix or few matches
                if (completions.size < 3) {
                    val commonTags = listOf(
                        "example" to "Example code",
                        "template" to "Template code",
                        "documentation" to "Code for documentation",
                        "tutorial" to "Tutorial content",
                        "demo" to "Demonstration",
                        "utility" to "Utility code",
                        "animation" to "Animation code",
                        "graphics" to "Graphics code",
                        "shader" to "Shader code",
                        "audio" to "Audio code",
                        "interaction" to "Interactive code"
                    )

                    commonTags.forEach { (tag, description) ->
                        if (tag.startsWith(prefix, ignoreCase = true) &&
                            completions.none { it.text == tag }
                        ) {
                            completions.add(
                                Metadata(
                                    icon = "value",
                                    tail = "suggested",
                                    text = tag,
                                    displayText = tag,
                                    documentation = description
                                )
                            )
                        }
                    }
                }
            }
        }

        return Completion(
            boundsStart = boundsStart,
            boundsEnd = boundsEnd,
            metadata = completions
        )
    }

    private fun collectTagsFromWorkspace(box: Box): Set<String> {
        val tags = mutableSetOf<String>()
        val maxBoxesToScan = 100  // Limit scanning for performance

        // Collect tags from all boxes in the current workspace
        try {
            val root = box.root
            root.children.take(maxBoxesToScan).forEach { child ->
                val metadata = child.metadata
                if (metadata.isNotBlank()) {
                    // Pass "tags" as the key to extract
                    val childTags = tagsForString(metadata, "tags")
                    childTags?.let { tags.addAll(it) }
                }
            }

            // Also try to collect from saved documents in the workspace directory
            val sessionInfo = thisSessionInfo.get()
            if (sessionInfo != null) {
                val workspaceDir = File(sessionInfo.filename).parentFile
                if (workspaceDir?.exists() == true) {
                    val tagDb = tagDatabase(workspaceDir.absolutePath)
                    tagDb.forEach { (_, tagList) ->
                        tags.addAll(tagList)
                    }
                }
            }
        } catch (e: Exception) {
            // If we can't collect tags, just return what we have
            lprintln("warning,completion", "Failed to collect tags from workspace: ${e.javaClass.simpleName}")
        }

        return tags
    }

    override fun describeSymbolContext(inside: Box, request: SymbolContextRequest): SymbolContextResponse {
        val cacheKey = SymbolContextCacheKey(
            property = request.property,
            codeHash = request.allCode.hashCode(),
            codeLength = request.allCode.length,
            cursor = request.position,
            contextGeneration = inside.ki.context.generation
        )
        if (!request.forceRefresh) {
            KotlinSymbolContextCache.get(cacheKey)?.let { return it }
        }

        val bounds = findSymbolBoundsAt(request.allCode, request.position)
        if (bounds == null || bounds.text.isBlank()) {
            val empty = SymbolContextResponse()
            if (!request.forceRefresh) {
                KotlinSymbolContextCache.put(cacheKey, empty)
            }
            return empty
        }

        val symbol = bounds.text
        if (request.forceRefresh) {
            val runtimeOnly = fallbackRuntimeSymbolContext(inside, symbol)
            if (runtimeOnly.html.isNotBlank()) {
                return runtimeOnly
            }
        }

        val response = runCatching {
            when (val completion = runBlocking { isolatedSymbolContextComplete(inside, request.allCode, bounds.end) }) {
                is CompletionResult.Success -> {
                    val bestVariant = completion.metadata
                        .firstOrNull { completionCandidateName(it) == symbol }
                        ?: completion.metadata.firstOrNull { completionCandidateName(it).startsWith(symbol) }

                    if (bestVariant != null) {
                        val descriptor: DeclarationDescriptor? = null // extraInfo not available in 2.4 scripting jars
                        symbolContextResponse(
                            symbol = symbol,
                            html = buildSymbolContextHtml(
                                box = inside,
                                descriptor = descriptor,
                                fallbackDisplay = bestVariant.displayText,
                                fallbackDocumentation = bestVariant.tail,
                                runtimeVariableInfo = inside.ki.context.vars[symbol]
                            )
                        )
                    } else {
                        fallbackRuntimeSymbolContext(inside, symbol)
                    }
                }

                else -> fallbackRuntimeSymbolContext(inside, symbol)
            }
        }.getOrElse {
            fallbackRuntimeSymbolContext(inside, symbol)
        }

        if (!request.forceRefresh && !response.isLiveValue) {
            KotlinSymbolContextCache.put(cacheKey, response)
        }
        return response
    }

    fun resolveDebugProbeSegment(box: Box, allCode: String, line: Int): DebugProbeSegment? {
        val lines = allCode.lines()
        if (line !in 1..lines.size) return null

        val breaks = lines.mapIndexedNotNull { index, rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("//%-") || trimmed.startsWith("/*%-*/")) index else null
        } + lines.size

        var at = 0
        for (breakIndex in breaks) {
            val startLine = at + 1
            val endLine = breakIndex.coerceAtLeast(startLine)
            if (line in startLine..endLine) {
                val rawText = lines.subList(at, breakIndex).joinToString("\n")
                val expanded = runCatching {
                    box.ki.applyMacros(box, rawText, previewOnly = true)
                }.getOrElse { rawText }

                var globalLine = startLine - 1
                val expandedText = expanded
                    .split("\n")
                    .map { current ->
                        globalLine++
                        current.replace("__line__", "${globalLine - 1}")
                    }
                    .joinToString("\n")

                return DebugProbeSegment(
                    rawText = rawText,
                    expandedText = expandedText,
                    localLine = (line - startLine + 1).coerceAtLeast(1),
                    startLine = startLine,
                    endLine = endLine
                )
            }
            at = breakIndex
        }
        return null
    }

    override fun complete(inside: Box, completionRequest: CompletionRequest, reply: (Message) -> Unit) {

        val position = completionRequest.position
        val allCode = completionRequest.allCode
        val property = completionRequest.property

        // Handle YAML metadata completion
        if (property == "metadata") {
            val completions =
                completeYamlMetadata(inside, allCode, position, completionRequest.line, completionRequest.column)
            reply(Message("complete").payload(completions))
            return
        }

        if (GlslCompletionSupport.isGlslProperty(property)) {
            reply(Message("complete").payload(GlslCompletionSupport.complete(inside, completionRequest)))
            return
        }

        if (shaderSpecForProperty(property) != null) {
            val docName = File(thisSessionInfo.get().filename).name
            val fileKey = "afield://$docName/${inside.id}/$property"
            @OptIn(DelicateCoroutinesApi::class)
            GlobalScope.launch(Dispatchers.IO) {
                fun trimContext(value: String, maxChars: Int = 4000, maxLines: Int = 200): String {
                    if (value.isBlank()) return ""
                    val lines = value.split("\n")
                    val limitedLines = if (lines.size > maxLines) lines.take(maxLines) else lines
                    val joined = limitedLines.joinToString("\n").trimEnd()
                    return if (joined.length > maxChars) joined.take(maxChars).trimEnd() else joined
                }

                val context = linkedMapOf<String, String>()
                val codeContext = trimContext(inside.getPropertyRaw("code") as? String ?: "")
                if (codeContext.isNotBlank()) {
                    context["code"] = codeContext
                }
                val shaderProps = listOf(
                    "vertex" to inside.vertex,
                    "fragment" to inside.fragment,
                    "vertex_fragment" to inside.getPropertyRaw("vertex_fragment") as? String,
                    "mesh" to inside.mesh,
                    "task" to inside.task,
                    "compute" to inside.compute,
                    "raygen" to inside.raygen,
                    "miss" to inside.miss,
                    "closesthit" to inside.closesthit,
                    "anyhit" to inside.anyhit,
                    "intersection" to inside.intersection,
                    "callable" to inside.callable,
                    "vertex_s" to inside.getPropertyRaw("vertex_s") as? String,
                    "fragment_s" to inside.getPropertyRaw("fragment_s") as? String,
                    "mesh_s" to inside.getPropertyRaw("mesh_s") as? String,
                    "task_s" to inside.getPropertyRaw("task_s") as? String,
                    "compute_s" to inside.getPropertyRaw("compute_s") as? String,
                    "raygen_s" to inside.getPropertyRaw("raygen_s") as? String,
                    "miss_s" to inside.getPropertyRaw("miss_s") as? String,
                    "closesthit_s" to inside.getPropertyRaw("closesthit_s") as? String,
                    "anyhit_s" to inside.getPropertyRaw("anyhit_s") as? String,
                    "intersection_s" to inside.getPropertyRaw("intersection_s") as? String,
                    "callable_s" to inside.getPropertyRaw("callable_s") as? String
                )
                shaderProps.forEach { (name, value) ->
                    if (name != property && !value.isNullOrBlank()) {
                        context[name] = trimContext(value)
                    }
                }
                val candidates = try {
                    Ai4GlslAutocomplete.complete(
                        fileKey = fileKey,
                        property = property,
                        code = allCode,
                        context = context,
                        line = completionRequest.line,
                        column = completionRequest.column,
                        position = position
                    )
                } catch (_: Exception) {
                    emptyList()
                }

                val metadata = candidates.take(3).mapIndexed { idx, candidate ->
                    val display = candidate.text.lineSequence().firstOrNull()?.trim().orEmpty()
                    val doc = buildString {
                        val notes = candidate.documentation.ifBlank { "AI4 shader completion" }
                        append("**Notes**\n")
                        notes.lineSequence().forEach { line ->
                            val trimmed = line.trim()
                            if (trimmed.isNotEmpty()) append("- ").append(trimmed).append("\n")
                        }
                        if (candidate.text.isNotBlank()) {
                            append("\n**Insertion**\n")
                            append("```")
                            append(
                                if (property.lowercase()
                                        .endsWith("_s") || property == "vertex_fragment"
                                ) "slang" else "glsl"
                            )
                            append("\n")
                            append(candidate.text.trimEnd())
                            append("\n```\n")
                        }
                    }.trimEnd() + "\n"
                    Metadata(
                        icon = "keyword",
                        tail = "AI4 ${idx + 1}",
                        text = candidate.text,
                        displayText = if (display.isNotEmpty()) display else "AI4 completion ${idx + 1}",
                        documentation = doc
                    )
                }

                reply(
                    Message("complete").payload(
                        Completion(
                            boundsStart = position,
                            boundsEnd = position,
                            metadata = metadata
                        )
                    )
                )
            }
            return
        }

        /**
         * Builds completion [Metadata] (including documentation) for each candidate in a K2 scripting
         * completion result.
         *
         * KDoc/documentation resolution: the K2 2.4 scripting jars do not expose a `DeclarationDescriptor`
         * here (the `descriptor` below is permanently null, so the `afield.Doc`-annotation branch it guards
         * is unreachable). Symbol names are therefore derived by string-parsing the completion item's
         * `text`/`displayText`. Documentation is then resolved by: (1) `extraInfo["kdoc"]`, embedded during
         * reflection-based completion in [afield.KotlinInterface2] (including receiver-qualified
         * `ReceiverType.method` KDoc for dot-completion); then (2) `KotlinSourceIndex.getKDocForSymbol(...)`
         * by candidate name for plain name-completions, or by `receiverType.candidate` for dot-completion.
         */
        fun completionFor(c: CompletionResult.Success, infixExpectedTypes: Set<String> = emptySet()): List<Metadata> {
            val activeToken = allCode.substring(c.bounds.start, c.bounds.end).trim()
            val argumentCallable = callableBeforeArgumentCursor(allCode, position)
            return c.metadata.mapNotNull {
//                println(it.extraInfo)
                var extraDoc = ""
                val descriptor: DeclarationDescriptor? = null // extraInfo not available in 2.4 scripting jars
                var mainText = it.displayText
                var symbolName: String? = null
                val rawSymbolName = it.text.substringBefore('<').substringBefore('(').substringAfterLast('.').trim()

                descriptor?.let {
//                                                mainText = DescriptorRenderer.HTML.render(descriptor as DeclarationDescriptor)
                    mainText = completionDescriptorRenderer.render(descriptor) + "\n"
                    // Extract symbol name for KDoc lookup
                    symbolName = descriptor.name.asString()
                    (it as? Annotated)?.let {
                        it.annotations.forEach {
                            if (it.fqName.toString() == "afield.Doc") {
                                extraDoc += it.allValueArguments.values.joinToString(separator = "\n")
                                    .trim('\"')
                            }
                        }
                    }
                }

                val candidateName = (symbolName ?: rawSymbolName).trim()
                val shouldEnrich = candidateName.isNotBlank() && (
                        (activeToken.isNotBlank() && candidateName.startsWith(activeToken)) ||
                                candidateName == argumentCallable
                        )

                // Try to get KDoc from the source index for afield symbols.
                // Skip for dot-completion (member access) since the simple name is ambiguous —
                // e.g. "add" could be Vector3f.add or LittleHistory.add.
                val isDotCompletion = c.bounds.start > 0 && allCode.let { code ->
                    val charBefore = code.getOrNull(c.bounds.start - 1)
                    charBefore == '.' || (charBefore == '?' && c.bounds.start > 1 && code[c.bounds.start - 2] == '.')
                }

                // KDoc lookup for name completions (not dot-completion) — check extraInfo first, then index
                if (extraDoc.isEmpty() && candidateName.isNotEmpty() && !isDotCompletion) {
                    // Check if KDoc was embedded in the completion item
                    val embeddedKdoc = it.extraInfo.get("kdoc") as? String
                    if (!embeddedKdoc.isNullOrBlank()) {
                        extraDoc = embeddedKdoc
                    } else {
                        val kdoc = try {
                            KotlinSourceIndex.getKDocForSymbol(candidateName)
                        } catch (_: Exception) {
                            null
                        }
                        if (kdoc != null) {
                            extraDoc = kdoc
                        }
                    }
                }

                // For dot-completion, try qualified KDoc lookup using receiver type from extraInfo
                if (isDotCompletion && extraDoc.isEmpty() && candidateName.isNotEmpty()) {
                    val receiverType = it.extraInfo.get("receiverType") as? String
                    if (receiverType != null) {
                        val kdoc = try {
                            KotlinSourceIndex.getKDocForSymbol("$receiverType.$candidateName")
                        } catch (_: Exception) {
                            null
                        }
                        if (kdoc != null) {
                            extraDoc = kdoc
                        }
                    }
                }

                // For K2 without descriptor, build documentation from the display text (signature)
                val documentation = if (descriptor != null) {
                    buildCompletionDocumentation(descriptor, extraDoc)
                } else if (it.displayText.isNotBlank()) {
                    // Build signature-based documentation for all K2 items
                    buildString {
                        append("```kotlin\n")
                        append(renderCompletionFallbackSignature(it.displayText, it.tail, it.icon))
                        append("\n```")
                        if (extraDoc.isNotBlank()) {
                            append("\n\n")
                            append(extraDoc.trim())
                        }
                    }
                } else {
                    extraDoc.trim()
                }

                val runtimeDocumentationHtml = runtimeCompletionDocumentationHtml(
                    box = inside,
                    candidateName = candidateName,
                    icon = it.icon,
                    isDotCompletion = isDotCompletion
                )

                val rawMetadata = Metadata(
                    icon = it.icon,
                    tail = it.tail,
                    text = it.text,
                    displayText = mainText.trim(),
                    documentation = documentation,
                    runtimeDocumentationHtml = runtimeDocumentationHtml
                )

                val receiverTypeHint = it.extraInfo.get("receiverQualifiedName") as? String
                    ?: it.extraInfo.get("receiverType") as? String
                    ?: if (!isDotCompletion && it.icon in setOf("property", "val", "var")) "Box" else null
                val descriptorText = it.extraInfo.get("original")?.toString()
                val enrichedMetadata = if (shouldEnrich) {
                    val enriched = KotlinCompletionSupport.enrichMetadata(
                        box = inside,
                        allCode = allCode,
                        symbolName = symbolName,
                        metadata = rawMetadata,
                        position = position,
                        receiverTypeHint = receiverTypeHint,
                        requireReceiverOwnerMatch = isDotCompletion,
                        descriptorText = descriptorText
                    )
                    if (completionTraceEnabled()) {
                        completionTrace(
                            "completion enrich candidate='$candidateName' dot=$isDotCompletion active='$activeToken' display='${it.displayText}' tail='${it.tail}' text='${it.text}' icon='${it.icon}' receiverHint='${receiverTypeHint.orEmpty()}' extra=${it.extraInfo} enriched=${enriched != rawMetadata} finalDisplay='${enriched.displayText}' finalTail='${enriched.tail}' source='${enriched.documentationSourcePath}:${enriched.documentationSourceLine}' sourceName='${enriched.documentationSourceName}' generate=${enriched.generateDocumentation} docChars=${enriched.documentation.length}"
                        )
                    }
                    enriched
                } else {
                    if (completionTraceEnabled() && candidateName.isNotBlank()) {
                        completionTrace(
                            "completion skip-enrich candidate='$candidateName' dot=$isDotCompletion active='$activeToken' argumentCallable='${argumentCallable.orEmpty()}' display='${it.displayText}' tail='${it.tail}' text='${it.text}' icon='${it.icon}' extra=${it.extraInfo}"
                        )
                    }
                    KotlinCompletionSupport.enrichReceiverDocumentation(
                        box = inside,
                        allCode = allCode,
                        metadata = rawMetadata,
                        position = position,
                        receiverTypeHint = receiverTypeHint,
                        descriptorText = descriptorText
                    )
                }
                val categorizedMetadata = KotlinCompletionSupport.classifyMetadata(
                    metadata = enrichedMetadata,
                    symbolName = candidateName,
                    extraInfo = it.extraInfo
                )
                if (completionTraceEnabled() && categorizedMetadata.category.isNotBlank() && candidateName.isNotBlank()) {
                    completionTrace(
                        "completion category candidate='$candidateName' category='${categorizedMetadata.category}' source='${categorizedMetadata.documentationSourcePath}:${categorizedMetadata.documentationSourceLine}' sourceName='${categorizedMetadata.documentationSourceName}'"
                    )
                }
                if (KotlinCompletionSupport.isHiddenCompletionCategory(categorizedMetadata.category)) {
                    if (completionTraceEnabled() && candidateName.isNotBlank()) {
                        completionTrace("completion hide-internal candidate='$candidateName' category='${categorizedMetadata.category}'")
                    }
                    return@mapNotNull null
                }
                if (infixExpectedTypes.isNotEmpty() && InfixCompletion.matchesOperandType(it, infixExpectedTypes)) {
                    categorizedMetadata.copy(contextScore = InfixCompletion.OPERAND_MATCH_BOOST)
                } else {
                    categorizedMetadata
                }
            }
        }

        fun importHelpFor(c: CompletionResult.Success): List<Metadata> {
            val s = allCode.substring(c.bounds.start, c.bounds.end)
            if (s.length > 0)
                return classesWithPrefix(s).map {
                    Metadata(
                        icon = "method",
                        tail = it.first,
                        text = s + it.first,
                        displayText = "(import) ${it.second}",
                        documentation = "import ${it.second}",
                        prefix = "import ${it.second}",
                        category = "External"
                    )
                }
            else return emptyList()
        }

        fun fieldSnippetCompletions(activeToken: String): List<Metadata> {
            val token = activeToken.trim()
            if (token.isNotEmpty() && !"go".startsWith(token)) return emptyList()
            return listOf(
                Metadata(
                    icon = "snippet",
                    tail = "Field coroutine",
                    text = "go {\n    \n}",
                    displayText = "go { }",
                    documentation = "```kotlin\n" +
                        "go {\n" +
                        "    \n" +
                        "}\n" +
                        "```\n\n" +
                        "Launch a suspendable block on this box with Field error reporting.",
                    category = "snippets",
                    documentationSourceName = "afield.stdlib.go",
                    documentationSourcePath = "src/jvmMain/kotlin/afield/stdlib/actors.kt",
                    documentationSourceLine = 166
                )
            )
        }

        fun emptyCompletion(): Completion =
            Completion(
                boundsStart = position,
                boundsEnd = position,
                metadata = emptyList()
            )

        fun replyEmpty(reason: String) {
            if (completionTraceEnabled()) {
                completionTrace("completion empty reason='$reason' uid='${inside.id}' property='$property' position=$position")
            }
            reply(Message("complete").payload(emptyCompletion()))
        }

        runBlocking {
            launch(Runloop.runLoop.main) {
                val c = runCatching {
                    inside.ki.complete(allCode, position)
                }.getOrElse {
                    completionTrace(
                        "completion exception uid='${inside.id}' property='$property' position=$position error='${it.javaClass.simpleName}: ${it.message.orEmpty()}'"
                    )
                    replyEmpty("exception")
                    return@launch
                }
                when (c) {
                    is CompletionResult.Success -> {
                        // Resolve the infix operator's operand type from indexed source: fast, cached,
                        // and crucially NO second completion, so the completion request never stalls.
                        // (The engine-driven InfixCompletion.expectedOperandTypes remains as a tested,
                        // engine-agnostic reference but is deliberately kept off the hot path.)
                        val infixExpectedTypes = InfixCompletion.expectedOperandTypesFast(allCode, position)
                        if (completionTraceEnabled() && infixExpectedTypes.isNotEmpty()) {
                            completionTrace("completion infix-operand expectedTypes='${infixExpectedTypes.joinToString(",")}' uid='${inside.id}' position=$position")
                        }
                        val synthetic = KotlinCompletionSupport.namedArgumentCompletions(inside, allCode, position)
                        val tokenBounds = runCatching { getTokenBounds(allCode, position) }
                            .getOrElse { CompletionTokenBounds(position, position) }
                        val typedToken = allCode.substring(
                            tokenBounds.start.coerceIn(0, allCode.length),
                            tokenBounds.end.coerceIn(0, allCode.length)
                        )
                        val fieldSnippets = fieldSnippetCompletions(typedToken)
                        val useTokenBounds = c.metadata.isEmpty() && fieldSnippets.isNotEmpty()
                        val boundsStart = synthetic?.bounds?.start ?: if (useTokenBounds) tokenBounds.start else c.bounds.start
                        val boundsEnd = synthetic?.bounds?.end ?: if (useTokenBounds) tokenBounds.end else c.bounds.end

                        val enriched = completionFor(c, infixExpectedTypes)
                        val infixMatches = if (infixExpectedTypes.isEmpty()) emptyList() else enriched.filter { it.contextScore > 0 }
                        // At an infix operand with nothing typed yet, show ONLY the fitting values, so
                        // they surface no matter how the client ranks (client-side boosting can't help
                        // if the editor is still running an older fieldSuggest.js). Once the user types a
                        // character we hand back the full list with the fitting values merely boosted, so
                        // nothing is permanently hidden. Guarded on a few matches so a bad type
                        // resolution never collapses completion to an empty/tiny list.
                        val infixFilterActive = typedToken.isBlank() && infixMatches.size >= 3
                        val bodyMetadata = if (infixFilterActive) infixMatches else enriched

                        val completions = Completion(
                            boundsStart = boundsStart,
                            boundsEnd = boundsEnd,
                            metadata = ((synthetic?.metadata ?: emptyList()) + fieldSnippets + bodyMetadata + importHelpFor(c))
                                .map(KotlinCompletionSupport::withCategoryDocumentationHeader)
                                // Front-load the type-matching operands after an infix function so they
                                // lead the list even for clients that lean on server order. Stable, so
                                // everything else keeps its relative order.
                                .let { if (infixExpectedTypes.isEmpty()) it else it.sortedByDescending { m -> m.contextScore } }
                        )

                        reply(
                            Message("complete").payload(
                                completions
                            )
                        )
                    }

                    is CompletionResult.Error -> {
                        completionTrace(
                            "completion error uid='${inside.id}' property='$property' position=$position error='${c.errorName}: ${c.errorValue}'"
                        )
                        replyEmpty("error")
                    }
                }
            }
        }
    }

    override fun executeSymbol(inside: Box, executeSymbol: ExecuteSymbol, reply: (Message) -> Unit) {
        // For Kotlin, construct a function call: symbolName()
        val functionCall = "${executeSymbol.symbol}()"

        // Create an Execute object for executeFragment
        val exec = Execute(
            uid = executeSymbol.uid,
            type = "fragment",
            line = executeSymbol.line,
            code = functionCall,
            singleLine = true
        )

        // Execute as a fragment
        executeFragment(inside, exec, reply)
    }

    override val languageName: String = "Kotlin"
}

private fun MutableMap<String, Int>.incrementAndGet(name: String): Int {
    this[name] = computeIfAbsent(name) { 0 } + 1
    return this[name]!!
}

fun improveMessage(
    b: Box,
    errorMessage: String,
    code: String? = null,
    diagnostic: ScriptDiagnostic? = null
): String {
    val hints = mutableListOf<String>()

    // Note: "Did you mean" / "add import" suggestions are no longer appended here as inert text.
    // They are surfaced as structured, actionable rows via suggestionsFor() (Apply / Ask AI4
    // buttons in the error output area). The numeric-mismatch hint stays as explanatory context.
    numericMismatchHint(errorMessage)?.let { hints += escapeNotifyHtml(it) }

    val escapedMessage = escapeNotifyHtml(errorMessage)
    if (hints.isEmpty()) return escapedMessage
    return appendErrorHints(escapedMessage, hints)
}

/**
 * Structured, actionable fixes for an error message — the data behind the Apply / Ask AI4
 * buttons in the error output area. Reuses the same ranking engine as [improveMessage]
 * ([suggestResolvableSymbols], [numericMismatchHint]) but returns [OutputSuggestion]s instead of
 * baked HTML. Position ([OutputSuggestion.line]/[OutputSuggestion.column]) comes from the
 * diagnostic so the client can locate and replace the offending token.
 */
fun suggestionsFor(
    b: Box,
    errorMessage: String,
    code: String? = null,
    diagnostic: ScriptDiagnostic? = null
): List<OutputSuggestion> {
    val out = mutableListOf<OutputSuggestion>()
    val line = diagnostic?.location?.start?.line ?: -1
    val column = diagnostic?.location?.start?.col ?: -1

    unresolvedReferenceName(errorMessage)?.let { missing ->
        suggestResolvableSymbols(b, missing, code, diagnostic).forEach { s ->
            val importFqn = s.detail
                .takeIf { it.startsWith("add import ") }
                ?.removePrefix("add import ")
                ?.trim()
                .orEmpty()
            if (importFqn.isNotEmpty()) {
                // Import (optionally also a rename when the class name differs from the token).
                val rename = if (!s.name.equals(missing, ignoreCase = true)) s.name else ""
                out += OutputSuggestion(
                    kind = "import",
                    label = importFqn,
                    detail = if (rename.isNotEmpty()) "import + rename to ${s.name}" else "add import",
                    token = missing,
                    replacement = rename,
                    importFqn = importFqn,
                    line = line,
                    column = column,
                    aiInstruction = buildString {
                        append("Add the missing import `import $importFqn`")
                        if (rename.isNotEmpty()) append(" and replace `$missing` with `${s.name}`")
                        append(" to resolve the unresolved reference `$missing`.")
                    }
                )
            } else {
                // Rename to an existing symbol.
                out += OutputSuggestion(
                    kind = "rename",
                    label = s.name,
                    detail = s.detail,
                    token = missing,
                    replacement = s.name,
                    line = line,
                    column = column,
                    aiInstruction = "Replace the unresolved reference `$missing` with `${s.name}`."
                )
            }
        }
    }

    numericWrapHelper(errorMessage)?.let { helper ->
        val typeName = when (helper) {
            ".d" -> "Double"
            ".f" -> "Float"
            ".i" -> "Int"
            ".l" -> "Long"
            else -> "the expected type"
        }
        out += OutputSuggestion(
            kind = "wrap",
            label = "make value $typeName",
            detail = "fix the numeric type mismatch",
            replacement = helper,
            line = line,
            column = column,
            aiInstruction = "Fix the numeric type mismatch by converting the offending value to " +
                "$typeName — rewrite a numeric literal cleanly, or use Field's `$helper` helper on an expression."
        )
    }

    if (out.isEmpty() && errorMessage.contains("Overload resolution ambiguity", ignoreCase = true)) {
        out += OutputSuggestion(
            kind = "generic",
            label = "resolve overload ambiguity",
            detail = "let AI4 disambiguate the call",
            line = line,
            column = column,
            aiInstruction = "Resolve the overload resolution ambiguity in this call, " +
                "disambiguating the arguments or adding explicit types as needed."
        )
    }

    return out
}

/** The Field numeric helper (`.d`/`.f`/`.i`/`.l`) that resolves a numeric argument-type mismatch, or null. */
private fun numericWrapHelper(errorMessage: String): String? {
    val match = Regex(
        """Argument type mismatch:\s*actual type is ['"`]([^'"`]+)['"`],\s*but ['"`]([^'"`]+)['"`]\s+was expected""",
        RegexOption.IGNORE_CASE
    ).find(errorMessage) ?: return null
    val actual = simplifyTypeForOutput(match.groupValues[1].substringBefore('?'))
    val expected = simplifyTypeForOutput(match.groupValues[2].substringBefore('?'))
    val numericTypes = setOf("Byte", "Short", "Int", "Long", "Float", "Double")
    if (actual !in numericTypes || expected !in numericTypes) return null
    return when (expected) {
        "Double" -> ".d"
        "Float" -> ".f"
        "Int" -> ".i"
        "Long" -> ".l"
        else -> null
    }
}

private data class ErrorMessageSuggestion(
    val name: String,
    val detail: String,
    val score: Int,
    val sourceRank: Int
) {
    fun renderHtml(): String {
        val renderedName = renderErrorSuggestionName(name)
        return if (detail.isBlank()) {
            renderedName
        } else {
            "$renderedName <span style='color:#aaa;font-style:normal;'>(${highlightErrorSuggestionDetail(detail)})</span>"
        }
    }
}

private fun unresolvedReferenceHint(suggestions: List<ErrorMessageSuggestion>): String =
    buildString {
        append("Did you mean:")
        suggestions.forEach { suggestion ->
            append('\n')
            append("  - ")
            append(suggestion.renderHtml())
        }
    }

private fun renderErrorSuggestionName(name: String): String =
    "<code style='font-weight:700;font-style:normal;color:#f2f2f2;'>`${escapeNotifyHtml(name)}`</code>"

private fun highlightErrorSuggestionDetail(detail: String): String {
    val keywords = setOf("private", "protected", "internal", "public", "import", "fun", "val", "var", "for", "keyword")
    val types = setOf(
        "Unit", "Nothing", "Any", "String", "Boolean", "Byte", "Short", "Int", "Long", "Float", "Double", "Char",
        "Pair", "Triple", "List", "MutableList", "Set", "MutableSet", "Map", "MutableMap", "Array"
    )
    val token = Regex(
        """\b(?:private|protected|internal|public|import|fun|val|var|for|keyword|Unit|Nothing|Any|String|Boolean|Byte|Short|Int|Long|Float|Double|Char|Pair|Triple|List|MutableList|Set|MutableSet|Map|MutableMap|Array)\b|(?<![A-Za-z0-9_])\d+(?:\.\d+)?[fFlL]?(?![A-Za-z0-9_])"""
    )

    return buildString {
        var cursor = 0
        token.findAll(detail).forEach { match ->
            append(escapeNotifyHtml(detail.substring(cursor, match.range.first)))
            val value = match.value
            val escaped = escapeNotifyHtml(value)
            when {
                value in keywords -> append("<span style='color:#f2cf66;font-weight:700;font-style:normal;'>$escaped</span>")
                value in types -> append("<span style='color:#8ec8ff;font-weight:700;font-style:normal;'>$escaped</span>")
                else -> append("<span style='color:#9ee37d;font-style:normal;'>$escaped</span>")
            }
            cursor = match.range.last + 1
        }
        append(escapeNotifyHtml(detail.substring(cursor)))
    }
}

private fun appendErrorHints(errorMessage: String, hints: List<String>): String =
    buildString {
        append(errorMessage.trimEnd())
        hints.forEachIndexed { index, hint ->
            val previous = hints.getOrNull(index - 1)
            val separator = when {
                index == 0 -> " "
                previous?.contains('\n') == true || hint.contains('\n') -> "\n"
                else -> " "
            }
            append(separator)
            append(hint)
        }
    }

private data class ErrorMessageSymbol(
    val name: String,
    val detail: String = "",
    val sourceRank: Int = 10,
    val allowExactName: Boolean = false
)

private fun unresolvedReferenceName(errorMessage: String): String? {
    val match = Regex(
        """Unresolved reference(?::|\s+)?\s*['"`]?([A-Za-z_][A-Za-z0-9_]*)['"`]?""",
        RegexOption.IGNORE_CASE
    ).find(errorMessage) ?: return null
    return match.groupValues[1].trim('\'', '`', '"', '.', ';').takeIf { it.isNotBlank() }
}

private fun numericMismatchHint(errorMessage: String): String? {
    val match = Regex(
        """Argument type mismatch:\s*actual type is ['"`]([^'"`]+)['"`],\s*but ['"`]([^'"`]+)['"`]\s+was expected""",
        RegexOption.IGNORE_CASE
    ).find(errorMessage) ?: return null

    val actual = simplifyTypeForOutput(match.groupValues[1].substringBefore('?'))
    val expected = simplifyTypeForOutput(match.groupValues[2].substringBefore('?'))
    val numericTypes = setOf("Byte", "Short", "Int", "Long", "Float", "Double")
    if (actual !in numericTypes || expected !in numericTypes) return null

    val primary = when (expected) {
        "Double" -> "Use Field's `.d` helper on the value, or write a Double literal like `1.0`."
        "Float" -> "Use Field's `.f` helper on the value, or write a Float literal like `1f`."
        "Int" -> "Use Field's `.i` helper on the value when truncation is what you want."
        "Long" -> "Use Field's `.l` helper on the value when a Long is what you want."
        else -> "Use an explicit numeric conversion before passing the value."
    }

    return "Kotlin does not widen numeric types automatically. $primary Field also provides `.d` -> Double, `.f` -> Float, `.i` -> Int, and `.l` -> Long."
}

private fun suggestResolvableSymbols(
    box: Box,
    missing: String,
    code: String?,
    diagnostic: ScriptDiagnostic?,
    limit: Int = 5
): List<ErrorMessageSuggestion> {
    val candidates = LinkedHashMap<String, ErrorMessageSymbol>()
    fun add(symbol: ErrorMessageSymbol) {
        val name = symbol.name.substringBefore('<').substringBefore('(').substringAfterLast('.').trim()
        if (name.isBlank() || (name == missing && !symbol.allowExactName)) return
        val key = if (symbol.allowExactName && symbol.detail.contains("import ")) {
            "${name.lowercase()}|${symbol.detail.lowercase()}"
        } else {
            name.lowercase()
        }
        val existing = candidates[key]
        if (
            existing == null ||
            symbol.sourceRank < existing.sourceRank ||
            (symbol.sourceRank == existing.sourceRank && symbol.detail.length > existing.detail.length)
        ) {
            candidates[key] = symbol.copy(name = name)
        }
    }

    val defined = collectDefinedSymbols(box)
    val local = collectLocalCodeSymbols(missing, code, diagnostic)
    // Overlap the two dominant cost centres: the K1-backed ambient completion (kept on this thread,
    // its two probes sequential because they share one K1 analysis session) runs concurrently with
    // the CPU-bound classpath/index Levenshtein scans on Dispatchers.Default. Results are added in
    // the original collector order below, so dedup precedence and final output are unchanged.
    val (ambient, importHelp, indexed) = runBlocking {
        val importDeferred = async(Dispatchers.Default) { collectImportHelpSymbols(missing) }
        val indexedDeferred = async(Dispatchers.Default) { collectIndexedSymbols(missing) }
        val ambient = collectAmbientCompletionSymbols(box, missing, code, diagnostic)
        Triple(ambient, importDeferred.await(), indexedDeferred.await())
    }

    defined.forEach(::add)
    local.forEach(::add)
    ambient.forEach(::add)
    importHelp.forEach(::add)
    indexed.forEach(::add)

    val maxDistance = maxTypoDistance(missing)
    return candidates.values
        .mapNotNull { symbol ->
            val caseOnly = symbol.name.equals(missing, ignoreCase = true)
            val usefulPrefix = symbol.name.startsWith(missing, ignoreCase = true) && symbol.name.length > missing.length
            val distance = if (caseOnly || usefulPrefix) 0 else typoDistanceWithin(missing, symbol.name, maxDistance)
            if (!caseOnly && !usefulPrefix && distance > maxDistance) return@mapNotNull null
            val score = when {
                caseOnly -> 0
                usefulPrefix -> 1
                else -> 10 + distance
            }
            ErrorMessageSuggestion(
                name = symbol.name,
                detail = formatErrorSuggestionDetail(symbol.detail, symbol.name),
                score = score,
                sourceRank = symbol.sourceRank
            )
        }
        .sortedWith(
            compareBy<ErrorMessageSuggestion> { it.score }
                .thenBy { it.name.length }
                .thenBy { it.sourceRank }
                .thenBy { it.name }
        )
        .take(limit)
}

private fun collectDefinedSymbols(box: Box): List<ErrorMessageSymbol> {
    val visited = mutableSetOf<Box>()
    val symbols = mutableListOf<ErrorMessageSymbol>()

    fun visit(b: Box) {
        if (!visited.add(b)) return
        b.properties.forEach { (name, value) ->
            val type = value?.let { shortenType(it::class.qualifiedName ?: it::class.java.name) }
            symbols += ErrorMessageSymbol(name, type?.let { "property: $it" }.orEmpty(), sourceRank = 0)
        }
        b.ki.context.vars.forEach { (name, info) ->
            symbols += ErrorMessageSymbol(name, shortenVariableInfo(info), sourceRank = 0)
        }
        b.ki.context.functions.forEach { (name, info) ->
            symbols += ErrorMessageSymbol(name, shortenFunctionInfo(info), sourceRank = 0)
        }
        b.parents.forEach { visit(it) }
    }

    visit(box)
    return symbols
}

private fun collectAmbientCompletionSymbols(
    box: Box,
    missing: String,
    code: String?,
    diagnostic: ScriptDiagnostic?
): List<ErrorMessageSymbol> {
    if (code.isNullOrBlank()) return emptyList()
    val bounds = unresolvedTokenBounds(code, diagnostic, missing) ?: return emptyList()
    val probes = buildList {
        add(code.removeRange(bounds.start, bounds.end) to bounds.start)
        add(code to bounds.end)
    }

    return probes.flatMap { (probeCode, cursor) ->
        val completion = runCatching {
            box.ki.completeAndWait(probeCode, cursor.coerceIn(0, probeCode.length))
        }.getOrNull()
        (completion as? CompletionResult.Success)?.metadata.orEmpty().mapNotNull { variant ->
            val name = completionCandidateName(variant).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ErrorMessageSymbol(name, shortenCompletionInfo(variant), sourceRank = 1)
        }
    }
}

private fun collectLocalCodeSymbols(
    missing: String,
    code: String?,
    diagnostic: ScriptDiagnostic?
): List<ErrorMessageSymbol> {
    if (code.isNullOrBlank()) return emptyList()
    val symbols = LinkedHashMap<String, ErrorMessageSymbol>()
    val diagnosticLine = diagnostic?.location?.start?.line

    fun add(name: String, kind: String, line: Int) {
        val unwrapped = name.removePrefix("`").removeSuffix("`").trim()
        if (unwrapped.isBlank() || unwrapped == missing) return
        val distance = diagnosticLine?.let { kotlin.math.abs(it - line) } ?: 0
        val sourceRank = if (distance <= 6) 0 else 1
        symbols.putIfAbsent(
            unwrapped.lowercase(),
            ErrorMessageSymbol(unwrapped, "local $kind at line $line", sourceRank)
        )
    }

    val funRegex = Regex("""\bfun\s+(?:[A-Za-z_][A-Za-z0-9_<>, ?]*\.)?(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)\s*\(""")
    val valRegex = Regex("""\b(val|var)\s+(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)\b""")
    val typeRegex = Regex("""\b(class|interface|object)\s+(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)\b""")

    code.lines().forEachIndexed { index, rawLine ->
        val line = rawLine.substringBefore("//")
        val lineNumber = index + 1
        funRegex.findAll(line).forEach { add(it.groupValues[1], "fun", lineNumber) }
        valRegex.findAll(line).forEach { add(it.groupValues[2], it.groupValues[1], lineNumber) }
        typeRegex.findAll(line).forEach { add(it.groupValues[2], it.groupValues[1], lineNumber) }
    }

    return symbols.values.toList()
}

private fun collectImportHelpSymbols(missing: String): List<ErrorMessageSymbol> {
    if (missing.length < 3 || missing.firstOrNull()?.isUpperCase() != true) return emptyList()
    val maxDistance = maxTypoDistance(missing)
    return runCatching {
        val exact = findClassesNamed(missing).orEmpty()
            .filter { isUsefulImportCandidate(it) }
            .map {
                ErrorMessageSymbol(
                    name = missing,
                    detail = importSuggestionDetail(it),
                    sourceRank = 1,
                    allowExactName = true
                )
            }

        val fuzzy = cache.asSequence()
            .filter { (simpleName, fqNames) ->
                simpleName != missing &&
                        simpleName.firstOrNull()?.isUpperCase() == true &&
                        !simpleName.contains("$") &&
                        !simpleName.lowercase().contains("clojure") &&
                        fqNames.any(::isUsefulImportCandidate)
            }
            .mapNotNull { (simpleName, fqNames) ->
                val usefulPrefix =
                    simpleName.startsWith(missing, ignoreCase = true) && simpleName.length > missing.length
                val distance = if (usefulPrefix) 0 else typoDistanceWithin(missing, simpleName, maxDistance)
                if (!usefulPrefix && distance > maxDistance) return@mapNotNull null
                val fqName = fqNames.firstOrNull(::isUsefulImportCandidate) ?: return@mapNotNull null
                val rank = if (usefulPrefix) 3 else 4 + distance
                rank to ErrorMessageSymbol(simpleName, importSuggestionDetail(fqName), sourceRank = rank)
            }
            .sortedWith(compareBy<Pair<Int, ErrorMessageSymbol>> { it.first }.thenBy { it.second.name.length })
            .take(20)
            .map { it.second }
            .toList()

        exact + fuzzy
    }.getOrDefault(emptyList())
}

private fun isUsefulImportCandidate(fqName: String): Boolean =
    fqName.contains(".") && !fqName.contains("$") && !fqName.lowercase().contains("clojure")

private fun importSuggestionDetail(fqName: String): String =
    "add import ${fqName.replace("$", ".")}"

private fun collectIndexedSymbols(missing: String): List<ErrorMessageSymbol> {
    val index = KotlinSourceIndex.latestIndex ?: KotlinSourceIndex.loadIndex() ?: return emptyList()
    val maxDistance = maxTypoDistance(missing)
    return index.definitions.asSequence()
        .mapNotNull { def ->
            val name = def.name.substringAfterLast('.')
            val caseOnly = name.equals(missing, ignoreCase = true)
            val usefulPrefix = name.startsWith(missing, ignoreCase = true) && name.length > missing.length
            if (!caseOnly && !usefulPrefix && typoDistanceWithin(missing, name, maxDistance) > maxDistance) {
                return@mapNotNull null
            }
            val importName = indexedImportQualifiedName(def)
            val detail = importName?.let(::importSuggestionDetail) ?: indexedSymbolDetail(def)
            ErrorMessageSymbol(
                name = name,
                detail = detail,
                sourceRank = if (importName != null) 2 else 3,
                allowExactName = importName != null && name.equals(missing, ignoreCase = true)
            )
        }
        .take(40)
        .toList()
}

private fun shortenVariableInfo(info: KotlinVariableInfo): String =
    shortenType(info.toString(shortenTypes = true))
        .substringAfter(": ", missingDelimiterValue = "")
        .substringBefore(" = ")
        .take(80)
        .ifBlank { "value" }

private fun shortenFunctionInfo(info: KotlinFunctionInfo): String =
    shortenType(info.toString(shortenTypes = true))
        .replace(Regex("\\s+"), " ")
        .take(100)

private fun shortenCompletionInfo(variant: SourceCodeCompletionVariant): String {
    val signature = renderCompletionFallbackSignature(variant.displayText, variant.tail, variant.icon)
        .replace(Regex("\\s+"), " ")
        .let(::shortenType)
        .trim()
    val detail = formatErrorSuggestionDetail(signature, variant.text)
    val withKind = if (
        detail.startsWith("${variant.text}(") &&
        variant.text.firstOrNull()?.isLowerCase() == true
    ) {
        "fun $detail"
    } else {
        detail
    }
    return withKind.take(110)
}

private fun indexedSymbolDetail(def: afield.ai.KotlinDefinitionRecord): String {
    val sourceLine = indexedDefinitionSourceLine(def)
        ?.substringBefore(" {")
        ?.replace(Regex("\\s+"), " ")
        ?.let(::shortenType)
        ?.let { formatErrorSuggestionDetail(it, def.name.substringAfterLast('.')) }
        ?.take(110)

    return sourceLine ?: "${def.kind} ${def.filePath}:${def.line}"
}

private fun indexedDefinitionFile(def: afield.ai.KotlinDefinitionRecord): File? =
    runCatching {
        File(def.filePath).takeIf { it.exists() }
            ?: File(System.getProperty("user.dir"), def.filePath).takeIf { it.exists() }
    }.getOrNull()

private fun indexedDefinitionSourceLine(def: afield.ai.KotlinDefinitionRecord): String? =
    indexedDefinitionFile(def)
        ?.useLines { lines -> lines.drop(def.line - 1).firstOrNull()?.trim() }

private fun indexedImportQualifiedName(def: afield.ai.KotlinDefinitionRecord): String? {
    val file = indexedDefinitionFile(def) ?: return null
    val packageName = file.useLines { lines ->
        lines.map { it.trim() }
            .firstOrNull { it.startsWith("package ") }
            ?.removePrefix("package ")
            ?.trim()
            ?.takeIf { it.matches(Regex("""[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*""")) }
    } ?: return null
    val importableName = when (def.kind) {
        "fun", "val", "var" -> def.name.substringAfterLast('.')
        else -> def.name
    }.trim('.')
    if (importableName.isBlank()) return null
    return "$packageName.$importableName"
}

private fun formatErrorSuggestionDetail(raw: String, name: String): String {
    if (raw.isBlank()) return ""
    var text = if (raw.startsWith("add import ")) {
        raw.replace(Regex("\\s+"), " ").trim()
    } else {
        shortenType(raw)
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    text = text
        .replace(Regex(""",\s*(?=\))"""), "")
        .trimEnd(',', ';')
        .trim()

    text = text.replace(
        Regex("""(\))\s+([A-Za-z_][A-Za-z0-9_.<>, ?]*\??)$""")
    ) { match ->
        val returnType = match.groupValues[2].trim()
        if (returnType.isBlank() || text.contains("):")) {
            match.value
        } else {
            "${match.groupValues[1]}: $returnType"
        }
    }

    return text.removeRedundantSuggestionName(name).take(140)
}

private fun String.removeRedundantSuggestionName(name: String): String {
    if (name.isBlank()) return this
    val compact = trim()
    return when {
        compact == name -> ""
        compact == "`$name`" -> ""
        else -> compact
    }
}

private fun unresolvedTokenBounds(
    code: String,
    diagnostic: ScriptDiagnostic?,
    missing: String
): CompletionTokenBounds? {
    val start = diagnostic?.location?.start
    if (start != null) {
        val absolute = absoluteOffset(code, start.line, start.col)
        val boundsAtLocation = findSymbolBoundsAt(code, absolute)?.takeIf { it.text == missing }
        if (boundsAtLocation != null) return CompletionTokenBounds(boundsAtLocation.start, boundsAtLocation.end)

        val lineStart = absoluteOffset(code, start.line, 1)
        val lineEnd = code.indexOf('\n', lineStart).let { if (it < 0) code.length else it }
        val lineText = code.substring(lineStart, lineEnd)
        val local = lineText.indexOf(missing)
        if (local >= 0) return CompletionTokenBounds(lineStart + local, lineStart + local + missing.length)
    }

    val first = Regex("""\b${Regex.escape(missing)}\b""").find(code) ?: return null
    return CompletionTokenBounds(first.range.first, first.range.last + 1)
}

private fun absoluteOffset(code: String, line: Int, col: Int): Int {
    var currentLine = 1
    var offset = 0
    while (currentLine < line && offset < code.length) {
        val next = code.indexOf('\n', offset)
        if (next < 0) return code.length
        offset = next + 1
        currentLine++
    }
    return (offset + col - 1).coerceIn(0, code.length)
}

private fun maxTypoDistance(missing: String): Int =
    when {
        missing.length <= 4 -> 1
        missing.length <= 8 -> 2
        else -> 3
    }

private fun typoDistance(a: String, b: String): Int = damerauLevenshteinDistance(a.lowercase(), b.lowercase())

/**
 * Length-pruned [typoDistance]: the edit distance is always at least the length difference, so when
 * the lengths differ by more than [max] the pair can be rejected without running the O(n·m) DP.
 * Returns the exact distance when it is `<= max`, otherwise some value `> max` (enough for callers
 * that only test `distance > max`). Exact-preserving versus [typoDistance] inside the accepted band.
 */
private fun typoDistanceWithin(a: String, b: String, max: Int): Int {
    if (kotlin.math.abs(a.length - b.length) > max) return max + 1
    return typoDistance(a, b)
}

private fun damerauLevenshteinDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length

    val dp = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) dp[i][0] = i
    for (j in 0..b.length) dp[0][j] = j

    for (i in 1..a.length) {
        for (j in 1..b.length) {
            val ai = i - 1
            val bj = j - 1
            val cost = if (a[ai] == b[bj]) 0 else 1
            dp[i][j] = minOf(
                dp[i - 1][j] + 1,
                dp[i][j - 1] + 1,
                dp[i - 1][j - 1] + cost
            )
            if (i > 1 && j > 1 && a[ai] == b[bj - 1] && a[ai - 1] == b[bj]) {
                dp[i][j] = minOf(dp[i][j], dp[i - 2][j - 2] + 1)
            }
        }
    }

    return dp[a.length][b.length]
}
