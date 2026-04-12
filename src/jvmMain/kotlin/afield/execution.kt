package afield

import afield.ai.Ai4GlslAutocomplete
import afield.ai.KotlinSourceIndex
import afield.ai.shaderSpecForProperty
import afield.four.synth.AudioPatchSpec
import afield.four.synth.InlineAudioSummaryTarget
import afield.logger.lprintln
import afield.macros.gatherPreambleUpwards
import afield.macros.standardExecutionPractice
import afield.stdlib.*
import io.ktor.websocket.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotated
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import java.io.File
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.SourceCodeCompletionVariant

@RunsAt(newRoot)
fun installKEActions(root : Box) {
    root.actions["reset"] = Action("Reset Kotlin Environment for Box", detail="This drops all variables and declarations live in the current box and starts again") {
        it.forEach {
            it.ki = makeKI(it)
        }
    }
}

// refactor this to make the contract about is-running much-much clearer
interface BoxExecution {

    fun executeFragment(inside: Box, execute: Execute, reply: ((Message) -> Unit))
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

private fun renderVariableLocationSection(
    label: String,
    container: ContainerSummary?,
    value: Any?
): String {
    if (container == null || container.text.isBlank()) return ""
    val locationHtml = "<span style='color:#777;'>$label ${container.html}</span>"
    val disclosure = renderVariableValueDisclosure(value)
    if (disclosure.isBlank()) {
        return "<div>$locationHtml</div>"
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
    val analysisKi = KotlinInterface(
        implicitReceivers = listOf(KotlinType(Box::class)),
        filePrefix = "${box.id}_symbol_context"
    )
    analysisKi.associatedBox = box
    Box.withBoxContext(box) {
        analysisKi.exec(intro, implicits = listOf(box))
    }
    return analysisKi.complete(allCode, position)
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
        val propRegex = Regex("""var\s+Box\.${Regex.escape(symbol)}\s+by\s+(?:Inheritance|Delegation|InheritanceFromContext)\s*<\s*([^>]+?)\s*>""")
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
    reply?.invoke(Message("output").payload(normalized))
    _sendToPeerSessions(
        Message("peer-output").payload(normalized),
        sessionInfo = box.inside,
        current = originSession
    )
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
        send(Message("set-output").payload(Set_output(box.id, "outputarea", channel, content.toString())))
    }
}


@Suppress("UnstableApiUsage")
class KotlinExecution : BoxExecution {
    private fun normalizedLaunchCode(exec: Execute): String {
        val leadingLines = exec.line.coerceAtLeast(0)
        return "\n".repeat(leadingLines) + exec.code.trimEnd()
    }

    private fun cacheLaunchReturnValue(box: Box, strand: String, code: String, returnValue: Any?) {
        if (returnValue == null) {
            box.cachedLaunchReturns.remove(strand)
            return
        }
        box.cachedLaunchReturns[strand] = CachedLaunchReturn(code, returnValue)
    }

    override fun executeFragment(box: Box, exec: Execute, reply: ((Message) -> Unit)) {
        val originSession = thisSession.get()

        val ln = Math.max(0, exec.line)

        val code = ("\n" * ln) + exec.code.trimEnd()

        val dur = +exec.code.count { it == '\n' }

        val existingFunction = box.ki.context.functions.filter { it.key == code.trim() }.map { it.value }.firstOrNull()
        if (existingFunction != null) {
            lprintln("debug,execution", "found existing function: $existingFunction")

            dispatchExecutionOutput(
                box,
                reply,
                Output(
                    uid = box.id,
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
        val wm = ++box.fragmentGeneration

        box._currentlyExecutingFragment = true
        send(Message("is-running").payload(IsRunning(exec.uid, true, wm, "__fragment__")))
        inMain {
            execute(box, code, reply, originSession, 0, runSetupBlocks = true, finishedHandler = {
                box._currentlyExecutingFragment = false
                send(Message("is-running").payload(IsRunning(exec.uid, false, wm, "__fragment__")))
            }) {}
        }


    }

    override fun executeAll(box: Box, exec: Execute, reply: ((Message) -> Unit)) {
        val originSession = thisSession.get()

        val code = exec.code.trimEnd()

        val wm = ++box.fragmentGeneration
        send(Message("is-running").payload(IsRunning(exec.uid, true, wm, "__fragment__")))
        inMain {
            box.resetStdlibRandom()
            execute(box, code, reply, originSession, code.split("\n").size, finishedHandler = {
                send(Message("is-running").payload(IsRunning(exec.uid, false, wm, "__fragment__")))
            }) {}
        }


    }

    override fun launchAll(box: Box, exec: Execute, code: String, strand: String, reply: ((Message) -> Unit)) {
        val originSession = thisSession.get()

        box.preamble = "" // still controversial
        box.properties.remove("_attic") //?

        val normalizedCode = normalizedLaunchCode(exec)

        inMain {

            box.resetStdlibRandom()
            box.preamble = ""


            // TODO! support pre-compilation. This executes the box (once) and grabs it's return value, then launch-all will simply do that. This means you end up with a lot in frames {}
            // this is just the field2 design

            execute(box, normalizedCode, reply, originSession, exec.line) { returnValue ->
                val launchMode = "launch"
                box.currentLaunchModes[strand] = launchMode

                cacheLaunchReturnValue(box, strand, normalizedCode, returnValue)
                configureOutputProxies(box, reply, originSession)

                launchReturnValue(box, returnValue, strand = strand, { isRunning, strand, watermark ->
                    if (!isRunning && box.runGeneration[strand] == watermark) {
                        box.currentLaunchModes.remove(strand)
                    }
                    send(
                        Message("is-running").payload(
                            IsRunning(exec.uid, isRunning, watermark, strand, launchMode)
                        )
                    )
                })
            }
        }
    }

    override fun continueAll(box: Box, exec: Execute, code: String, strand: String, reply: ((Message) -> Unit)) {
        val cached = box.cachedLaunchReturns[strand]
        val normalizedCode = normalizedLaunchCode(exec)

        if (cached == null || cached.code != normalizedCode) {
            lprintln("debug,execution", "continue cache miss for ${box.id} on strand $strand")
            launchAll(box, exec, code, strand, reply)
            return
        }

        val originSession = thisSession.get()
        val launchMode = "continue"
        box.currentLaunchModes[strand] = launchMode
        lprintln("debug,execution", "continuing cached return value for ${box.id} on strand $strand")
        inMain {
            configureOutputProxies(box, reply, originSession)
            launchReturnValue(box, cached.returnValue, strand = strand) { isRunning, notifiedStrand, watermark ->
                if (!isRunning && box.runGeneration[notifiedStrand] == watermark) {
                    box.currentLaunchModes.remove(notifiedStrand)
                }
                send(
                    Message("is-running").payload(
                        IsRunning(exec.uid, isRunning, watermark, notifiedStrand, launchMode)
                    )
                )
            }
        }
    }


    suspend fun execute(
        box: Box,
        code: String,
        reply: ((Message) -> Unit)?,
        originSession: WebSocketSession?,
        ln: Int,
        runSetupBlocks: Boolean = true,
        finishedHandler: () -> Unit = {},
        handleReturn: (Any?) -> Unit
    ) {

        Box.withBoxContext(box) {
            try {
                var dur = code.split("\n").count()
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
                    send(Message("set-output").payload(Set_output(box.id, "outputarea", channel, content.toString())))
                }

            val returnValues: List<ResultValue?> = standardExecutionPractice(box.ki, code, { compileError ->
                hasError = true
                lprintln("error,execution", "compiler error")
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
                        message = improveMessage(box, message)
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
                                    html = escapeForHtml(message),
                                    frame = Runloop.runLoop.frame.toInt()
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
                                    html = escapeForHtml(message),
                                    frame = Runloop.runLoop.frame.toInt()
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
            }, { evalException ->
                hasError = true
                lprintln("error,execution", "exception thrown in fragment inside box $box")
                lprintln("error,execution", evalException.stackTraceToString())

                var fallbackFirstLine = -1
                var firstLineInThisBox = -1
                evalException.stackTrace.forEach { e ->
                    if (fallbackFirstLine == -1) {
                        fallbackFirstLine = e.lineNumber
                    }
                    if(e.fileName?.contains(box.id) == true && firstLineInThisBox == -1) {
                        firstLineInThisBox = e.lineNumber
                    }
                }

                var firstLine = if (firstLineInThisBox!=-1) firstLineInThisBox else fallbackFirstLine
                dispatchExecutionOutput(
                    box,
                    reply,
                    Output(
                        uid = box.id,
                        line = firstLine,
                        kind = "error",
                        html = escapeForHtml(evalException.message ?: evalException.javaClass.name),
                        frame = Runloop.runLoop.frame.toInt()
                    ),
                    originSession = originSession
                )

            }, implicits = listOf(box), runSetupBlocks = runSetupBlocks)

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
                                    html = escapeForHtml(returnValue.error.message ?: returnValue.error.javaClass.name),
                                    frame = Runloop.runLoop.frame.toInt()
                                ),
                                originSession = originSession
                            )
                            handleReturn(null)
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
                                    return@forEach
                                }
                                val name = returnValue.name
                                val type = simplifyTypeForOutput(returnValue.type)
                                val annotation = "<i>${name}=</i>${type}"
                                audioPatch?.inlineSummaryTarget = InlineAudioSummaryTarget(
                                    line = ln + dur,
                                    annotation = annotation
                                )
                                val value = returnValue.value.h

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
                        }

                    }

                }
            } else {
                lprintln("error,execution", "execution finished with errors")
            }
            } finally {
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

    override fun stop(box: Box) {
        box.runGeneration.incrementAndGet("default")
        box.jobs.removeAll {
            it.cancel()
            true
        }
    }

    private fun completeYamlMetadata(box: Box, allCode: String, position: Int, line: Int, column: Int): Completion {
        // Helper function to calculate bounds for replacing a prefix
        fun calculateBounds(lines: List<String>, currentLineIndex: Int, prefixInLine: String, currentPosition: Int): Pair<Int, Int> {
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
        val tagsMultiLineMatch = Regex("tags:\\s*(?:\\n\\s*-\\s*[\\w\\-]+)*\\n\\s*-\\s*([a-zA-Z0-9_\\-]*)$", RegexOption.MULTILINE)
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
                        completions.none { it.text == tag }) {
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
            val filename = thisSessionInfo.get().filename
            if (filename != null) {
                val workspaceDir = File(filename).parentFile
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

    fun describeSymbolContext(box: Box, request: SymbolContextRequest): SymbolContextResponse {
        val cacheKey = SymbolContextCacheKey(
            property = request.property,
            codeHash = request.allCode.hashCode(),
            codeLength = request.allCode.length,
            cursor = request.position,
            contextGeneration = box.ki.context.generation
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
            val runtimeOnly = fallbackRuntimeSymbolContext(box, symbol)
            if (runtimeOnly.html.isNotBlank()) {
                return runtimeOnly
            }
        }

        val response = runCatching {
            when (val completion = runBlocking { isolatedSymbolContextComplete(box, request.allCode, bounds.end) }) {
                is CompletionResult.Success -> {
                    val bestVariant = completion.metadata
                        .firstOrNull { completionCandidateName(it) == symbol }
                        ?: completion.metadata.firstOrNull { completionCandidateName(it).startsWith(symbol) }

                    if (bestVariant != null) {
                        val descriptor: DeclarationDescriptor? = null // extraInfo not available in 2.4 scripting jars
                        symbolContextResponse(
                            symbol = symbol,
                            html = buildSymbolContextHtml(
                                box = box,
                                descriptor = descriptor,
                                fallbackDisplay = bestVariant.displayText,
                                fallbackDocumentation = bestVariant.tail,
                                runtimeVariableInfo = box.ki.context.vars[symbol]
                            )
                        )
                    } else {
                        fallbackRuntimeSymbolContext(box, symbol)
                    }
                }

                else -> fallbackRuntimeSymbolContext(box, symbol)
            }
        }.getOrElse {
            fallbackRuntimeSymbolContext(box, symbol)
        }

        if (!request.forceRefresh && !response.isLiveValue) {
            KotlinSymbolContextCache.put(cacheKey, response)
        }
        return response
    }

    override fun complete(box: Box, exec: CompletionRequest, reply: (Message) -> Unit) {

        val position = exec.position
        val allCode = exec.allCode
        val property = exec.property

        // Handle YAML metadata completion
        if (property == "metadata") {
            val completions = completeYamlMetadata(box, allCode, position, exec.line, exec.column)
            reply(Message("complete").payload(completions))
            return
        }

        if (shaderSpecForProperty(property) != null) {
            val docName = File(thisSessionInfo.get().filename).name
            val fileKey = "afield://$docName/${box.id}/$property"
            GlobalScope.launch(Dispatchers.IO) {
                fun trimContext(value: String, maxChars: Int = 4000, maxLines: Int = 200): String {
                    if (value.isBlank()) return ""
                    val lines = value.split("\n")
                    val limitedLines = if (lines.size > maxLines) lines.take(maxLines) else lines
                    val joined = limitedLines.joinToString("\n").trimEnd()
                    return if (joined.length > maxChars) joined.take(maxChars).trimEnd() else joined
                }

                val context = linkedMapOf<String, String>()
                val codeContext = trimContext(box.getPropertyRaw("code") as? String ?: "")
                if (codeContext.isNotBlank()) {
                    context["code"] = codeContext
                }
                val shaderProps = listOf(
                    "vertex" to box.vertex,
                    "fragment" to box.fragment,
                    "vertex_fragment" to box.getPropertyRaw("vertex_fragment") as? String,
                    "mesh" to box.mesh,
                    "task" to box.task,
                    "compute" to box.compute,
                    "vertex_s" to box.getPropertyRaw("vertex_s") as? String,
                    "fragment_s" to box.getPropertyRaw("fragment_s") as? String,
                    "mesh_s" to box.getPropertyRaw("mesh_s") as? String,
                    "task_s" to box.getPropertyRaw("task_s") as? String,
                    "compute_s" to box.getPropertyRaw("compute_s") as? String
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
                        line = exec.line,
                        column = exec.column,
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
                            append(if (property.lowercase().endsWith("_s") || property == "vertex_fragment") "slang" else "glsl")
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

        fun completionFor(c: CompletionResult.Success): List<Metadata> {
            val activeToken = allCode.substring(c.bounds.start, c.bounds.end).trim()
            return c.metadata.map {
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
                val shouldEnrich = activeToken.isNotBlank() &&
                    candidateName.isNotBlank() &&
                    candidateName.startsWith(activeToken)

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
                    val embeddedKdoc = it.extraInfo?.get("kdoc") as? String
                    if (!embeddedKdoc.isNullOrBlank()) {
                        extraDoc = embeddedKdoc
                    } else {
                        val kdoc = try { KotlinSourceIndex.getKDocForSymbol(candidateName) } catch (_: Exception) { null }
                        if (kdoc != null) {
                            extraDoc = kdoc
                        }
                    }
                }

                // For dot-completion, try qualified KDoc lookup using receiver type from extraInfo
                if (isDotCompletion && extraDoc.isEmpty() && candidateName.isNotEmpty()) {
                    val receiverType = it.extraInfo?.get("receiverType") as? String
                    if (receiverType != null) {
                        val kdoc = try { KotlinSourceIndex.getKDocForSymbol("$receiverType.$candidateName") } catch (_: Exception) { null }
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
                        append(it.displayText)
                        if (it.tail.isNotBlank()) append(it.tail)
                        append("\n```")
                        if (extraDoc.isNotBlank()) {
                            append("\n\n")
                            append(extraDoc.trim())
                        }
                    }
                } else {
                    extraDoc.trim()
                }

                val rawMetadata = Metadata(
                    icon = it.icon,
                    tail = it.tail,
                    text = it.text,
                    displayText = mainText.trim(),
                    documentation = documentation
                )

                if (shouldEnrich) {
                    KotlinCompletionSupport.enrichMetadata(
                        box = box,
                        allCode = allCode,
                        symbolName = symbolName,
                        metadata = rawMetadata
                    )
                } else {
                    rawMetadata
                }
            }
        }

        fun importHelpFor(c: CompletionResult.Success): List<Metadata> {
            val s = allCode.substring(c.bounds.start, c.bounds.end)
            if (s.length>0)
            return classesWithPrefix(s).map {
                Metadata(
                    icon = "method",
                    tail = it.first,
                    text = s + it.first,
                    displayText = "(import) ${it.second}",
                    documentation = "import ${it.second}",
                    prefix = "import ${it.second}"
                )
            }
            else return emptyList()
        }

        runBlocking {
            launch(Runloop.runLoop.main) {
                val c = box.ki.complete(allCode, position)
                when (c) {
                    is CompletionResult.Success -> {
                        val synthetic = KotlinCompletionSupport.namedArgumentCompletions(box, allCode, position)
                        val boundsStart = synthetic?.bounds?.start ?: c.bounds.start
                        val boundsEnd = synthetic?.bounds?.end ?: c.bounds.end
                        val completions = Completion(
                            boundsStart = boundsStart,
                            boundsEnd = boundsEnd,
                            metadata = (synthetic?.metadata ?: emptyList()) + completionFor(c) + importHelpFor(c)
                        )

                        reply(
                            Message("complete").payload(
                                completions
                            )
                        )
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

fun improveMessage(b : Box, errorMessage : String) : String {
    val unresolved = Regex("Unresolved reference: ([^\\s]+)")
    val match = unresolved.find(errorMessage) ?: return errorMessage

    val missing = match.groupValues[1].trim('\'', '`', '"', '.', ';')
    if (missing.isBlank()) return errorMessage

    val candidates = collectDefinedNames(b)
    val suggestions = suggestCloseNames(missing, candidates)

    if (suggestions.isEmpty()) return errorMessage

    val alternatives = suggestions.joinToString(", ") { "`$it`" }
    return "$errorMessage. Did you mean $alternatives?"
}

private fun collectDefinedNames(box: Box): Set<String> {
    val visited = mutableSetOf<Box>()
    val names = LinkedHashSet<String>()

    fun visit(b: Box) {
        if (!visited.add(b)) return
        names.addAll(b.properties.keys)
        names.addAll(b.ki.context.vars.keys)
        b.parents.forEach { visit(it) }
    }

    visit(box)
    return names
}

private fun suggestCloseNames(missing: String, candidates: Set<String>, limit: Int = 3): List<String> {
    val maxDistance = when {
        missing.length <= 4 -> 1
        missing.length <= 8 -> 2
        else -> 3
    }

    return candidates
        .filter { it.isNotBlank() && it != missing }
        .map { candidate ->
            val distance = levenshteinDistance(missing, candidate)
            candidate to distance
        }
        .filter { it.second <= maxDistance }
        .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first.length })
        .take(limit)
        .map { it.first }
}

private fun levenshteinDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length

    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)

    for (i in a.indices) {
        current[0] = i + 1
        for (j in b.indices) {
            val cost = if (a[i] == b[j]) 0 else 1
            current[j + 1] = minOf(
                previous[j + 1] + 1,
                current[j] + 1,
                previous[j] + cost
            )
        }
        val temp = previous
        previous = current
        current = temp
    }

    return previous[b.length]
}
