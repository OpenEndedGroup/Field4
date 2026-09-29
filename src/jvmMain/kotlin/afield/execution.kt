package afield

import afield.ai.Ai4GlslAutocomplete
import afield.ai.KotlinSourceIndex
import afield.ai.glslSpecForProperty
import afield.ai.shaderSpecForProperty
import afield.four.synth.AudioPatchSpec
import afield.four.synth.InlineAudioSummaryTarget
import afield.logger.PinnedConsole
import afield.logger.lprintln
import afield.macros.checkAtticAndExecute
import afield.macros.parseAtticBlock
import afield.macros.checkIntroductionAndExecute
import afield.macros.checkPreambleAndExecute
import afield.macros.gatherPreambleUpwards
import afield.macros.standardExecutionPractice
import afield.stdlib.*
import io.ktor.websocket.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaField
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

/**
 * Futures and reflection wrap the exception users actually need to see. Keep the original
 * throwable for source-location discovery, but use the first non-transport cause in UI text.
 */
internal fun userFacingThrowable(throwable: Throwable): Throwable {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    var current = throwable
    while (seen.add(current)) {
        val cause = when (current) {
            is ExecutionException, is CompletionException, is InvocationTargetException -> current.cause
            else -> null
        } ?: break
        current = cause
    }
    return current
}

private fun simplifyTypeForOutput(type: String?): String {
    if (type.isNullOrBlank()) return ""
    return type.replace("kotlin.", "")
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

/**
 * Opt in with `AFIELD_COMPLETION_TRACE=1`. This defaulted to *on* from the day it was written
 * (`b3c99b4d0`, 2026-06-24) until it was measured.
 *
 * What 45 days of always-on tracing produced: 53 MB / 201,835 lines, of which 92.3% of the bytes are
 * the two per-candidate kinds (`skip-enrich`, `category`) that say nothing about a failure, 77.5% of
 * lines are exact duplicates of an earlier line once the timestamp is stripped, and the four kinds
 * that exist to diagnose a failure — `error`, `exception`, `empty`, `infix-operand` — emitted
 * *nothing at all*. No latency, no backend (K1 vs AA), no request id: none of what you would
 * actually want. Meanwhile [completionTrace] opens, appends to and closes a 50 MB file once per
 * candidate row on the completion path, peaking at 1,164 writes in one measured second while
 * someone waits for a popup.
 *
 * So it is opt-in, and the failure kinds are logged through [completionFailure] instead, which this
 * flag does not gate.
 */
private fun completionTraceEnabled(): Boolean =
    System.getenv("AFIELD_COMPLETION_TRACE")
        ?.trim()
        ?.lowercase()
        ?.let { it !in setOf("0", "false", "off", "no") }
        ?: false

/**
 * A completion that failed — an engine error, an exception, or an empty reply and why.
 *
 * Always logged, never written to the trace file: these fire only when something went wrong (zero
 * lines in 45 days of tracing), so they cost nothing to keep, and gating them behind
 * [completionTraceEnabled] would have meant turning the trace off also turned off the one thing
 * worth reading.
 */
private fun completionFailure(message: String) = lprintln("info,completion", message)

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
    val info = box.ki.context.vars[candidateName] ?: run {
        lprintln("info,completion", "runtime-doc: no live value for '$candidateName' " +
            "(icon=$icon, ${box.ki.context.vars.size} vars: ${box.ki.context.vars.keys.take(12)}) — has the box been run?")
        return ""
    }
    lprintln("debug,completion", "runtime-doc: rendering live value for '$candidateName' (${info.value?.javaClass?.simpleName})")
    return buildRuntimeVariableSymbolContextHtml(
        box = box,
        info = info,
        declarationOverride = runCatching { shortenType(info.descriptor.toString()) }
            .getOrDefault(info.toString(true)),
        locationLabel = "defined in"
    )
}

/**
 * For a dot completion `receiver.member` where `receiver` is a bare box context variable with a live
 * value and `member` is a plain JVM FIELD, read the field's live value and pretty-print it — so `m.insi|`
 * shows the actual value of `m.inside` while you type. Only public fields are read (via `Class.fields`):
 * fields have no side effects, so this is safe; Kotlin property getters / methods are deliberately NOT
 * read (they could do arbitrary work). Bails (returns "") on chained/call receivers or anything unproven.
 */
private fun dotMemberRuntimeValueHtml(box: Box, allCode: String, memberStart: Int, member: String): String {
    if (member.isBlank() || memberStart <= 0 || memberStart > allCode.length) return ""
    var i = memberStart - 1
    if (allCode[i] != '.') return ""
    i-- // step before the '.'
    if (i >= 0 && allCode[i] == '?') i-- // safe-dot `?.`
    val end = i + 1
    while (i >= 0 && (allCode[i].isLetterOrDigit() || allCode[i] == '_')) i--
    val recvName = allCode.substring(i + 1, end)
    // Only a *bare* variable receiver — bail on chains/calls (`foo.m.`, `bar().m.`, `arr[0].m.`).
    if (recvName.isEmpty() || (i >= 0 && allCode[i] in charArrayOf('.', ')', ']', '"', '\''))) return ""
    val receiverValue = box.ki.context.vars[recvName]?.value ?: run {
        lprintln("debug,completion", "runtime-field: receiver '$recvName' has no live value " +
            "(${box.ki.context.vars.size} vars) — has the box been run?")
        return ""
    }
    val read = readBackingFieldValue(receiverValue, member) ?: return ""
    val declaration = "$member: ${shortenType(read.second)}"
    val declHtml = "<code>${escapeNotifyHtml(declaration)}</code>" +
        " <span style='color:#777;'>on <code>${escapeNotifyHtml(recvName)}</code></span>"
    val disclosure = renderVariableValueDisclosure(read.first)
    // Match the `m` rendering: complex values collapse into a <details> disclosure; simple values (Number,
    // String, …) show inline as a plain value.
    return if (disclosure.isNotBlank()) {
        "<div style='display:inline-block; max-width: 620px;'>" +
            "<details class='afield-symbol-context-disclosure' style='margin-top:2px;'>" +
            "<summary tabindex='-1' onfocus='this.blur()' style='color:#ddd; cursor:pointer; user-select:none; outline:none; box-shadow:none;'>" +
            declHtml + "</summary>" + disclosure + "</details></div>"
    } else {
        "<div style='display:inline-block; max-width: 620px;'>" +
            "<div style='color:#ddd; overflow-wrap:anywhere;'>$declHtml</div>" +
            renderVariablePlainValue(read.first) + "</div>"
    }
}

/**
 * Read a member's value from a live receiver **via its backing field only** — never invoking a getter,
 * so this is guaranteed side-effect-free. Covers (1) public JVM fields (Java public / Kotlin `@JvmField`)
 * and (2) Kotlin stored properties whose backing field exists (`javaField != null`). A computed property
 * (custom `get()` with no backing field) has no `javaField` and is skipped. Returns (value, typeName) or
 * null when there's nothing safely readable.
 */
private fun readBackingFieldValue(receiver: Any, member: String): Pair<Any?, String>? {
    runCatching { receiver.javaClass.fields.firstOrNull { it.name == member } }.getOrNull()?.let { f ->
        val v = runCatching { f.get(receiver) }.getOrNull()
        return v to (f.type.simpleName ?: f.type.name)
    }
    val prop = runCatching { receiver::class.memberProperties.firstOrNull { it.name == member } }.getOrNull()
        ?: return null
    val backing = runCatching { prop.javaField }.getOrNull() ?: return null // computed getter, no field → skip
    val value = runCatching { backing.isAccessible = true; backing.get(receiver) }.getOrNull()
    val type = runCatching { prop.returnType.toString() }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: (backing.type.simpleName ?: backing.type.name)
    return value to type
}

private fun renderDisplayContent(content: Any?): String {
    return when (content) {
        is String -> content
        else -> content.h
    }
}

private fun retainedDisplayContent(box: Box, channel: String, content: Any?): String =
    renderDisplayContent(content).also { box.output.retainedAreas.put(channel, it) }

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

/**
 * "🤖 ai-generated — view in attic" for a function whose body an `ai("…")` macro wrote, or "" for
 * everything else.
 *
 * This is the only place the `// @ai-gen:` marker surfaces to a person: `ai()` parks the generated
 * `__name` in the box's attic and replaces the call site with `return __name(…)`, so without the
 * badge a call site says nothing about where its body came from or how to reach it.
 *
 * It shipped with the macro on 2026-04-05 and went dark six days later, when `ba36f5b30` moved
 * completion to K2 and the scripting jars stopped exposing a `DeclarationDescriptor` — the badge sat
 * behind `descriptor is FunctionDescriptor`. Nothing here needs a descriptor: a name and the box's
 * own attic are enough.
 *
 * The lookup goes through [parseAtticBlock], the macro's own parser, rather than the
 * `attic.contains("// @ai-gen: __name")` the original used — that substring matched `__nameOther`'s
 * header too, and badged a function whose body nobody had generated.
 */
internal fun aiGeneratedAtticBadge(box: Box, symbolName: String): String {
    val name = symbolName.substringBefore('<').substringBefore('(').substringAfterLast('.').trim()
    if (name.isEmpty()) return ""
    val attic = box.attic
    if (attic.isBlank()) return ""
    val atticName = if (name.startsWith("__")) name else "__$name"
    if (parseAtticBlock(attic, atticName) == null) return ""
    return buildString {
        append("<div style='color:#b0b0ff; cursor:pointer; margin-top:2px;' ")
        append("onclick=\"if(window.switchToProperty) window.switchToProperty('")
        append(escapeNotifyHtml(box.id))
        append("','attic')\">")
        append("\uD83E\uDD16 ai-generated — <u>view in attic</u>")
        append("</div>")
    }
}

/**
 * The ambient badge for a symbol, from what completion could tell us about it.
 *
 * There is no `DeclarationDescriptor` to render: the K2 2.4 scripting jars do not expose one (see
 * [completionCandidateName]), so this reads the completion variant's own display text and type.
 */
private fun buildSymbolContextHtml(
    box: Box,
    symbolName: String,
    fallbackDisplay: String,
    fallbackDocumentation: String,
    runtimeVariableInfo: KotlinVariableInfo? = null
): String {
    runtimeVariableInfo?.let {
        return buildRuntimeVariableSymbolContextHtml(
            box = box,
            info = it,
            locationLabel = "declared in"
        )
    }

    // Render the completion's display text plus its
    // type (the variant's `tail`, e.g. `inside : ModulePointer`). The type is what makes the ambient
    // badge worth showing; completion already knows it, so surface it here for both K1 and AA candidates.
    val display = fallbackDisplay.ifBlank {
        fallbackDocumentation.lineSequence().firstOrNull()?.trim().orEmpty()
    }
    if (display.isBlank()) return ""
    val type = fallbackDocumentation.lineSequence().firstOrNull()?.trim()?.removePrefix(":")?.trim().orEmpty()
    val typeSuffix = if (type.isNotBlank() && type != display && !display.contains(type))
        " <span style='color:#888;'>: ${escapeNotifyHtml(type)}</span>" else ""
    val body = "<span style='color:#ddd;'><code>${escapeNotifyHtml(display)}</code>$typeSuffix</span>"
    val badge = aiGeneratedAtticBadge(box, symbolName)
    if (badge.isEmpty()) return body
    return "<div style='display:inline-block; max-width: 620px;'>$body$badge</div>"
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

/**
 * A completion candidate's bare symbol name, parsed out of its `text`.
 *
 * String-parsed on purpose: the K2 2.4 scripting jars expose no `DeclarationDescriptor` on a
 * completion variant, so there is no structured name to ask for.
 */
private fun completionCandidateName(variant: SourceCodeCompletionVariant): String {
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

/**
 * What the server does with a *type fit* — a candidate whose type is right for where the caret is.
 *
 * Two sources feed it, and both end up as a `contextScore` boost on the [Metadata]:
 *  - the infix operand type, resolved here from the source index ([InfixCompletion]);
 *  - the **expected type at the caret**, resolved by the AA sidecar with
 *    `KaExpressionTypeProvider.expectedType` and arriving as an
 *    [AaCompletionClient.EXPECTED_TYPE_MATCH] flag in `extraInfo` (a property's type at
 *    `d.scheme = ‸`, a parameter's at `f(word = ‸)`, a function's return type at `return ‸`).
 *    K1 produces no such flag, so nothing here engages on the K1 fallback path.
 *
 * The boost alone is not enough: it is applied client-side by `fieldSuggest.js`, and an editor running
 * an older copy of that file would ignore it. So at an *untyped* caret the reply is collapsed to the
 * fitting candidates only — which is also what turns `d.scheme = ‸` from a several-hundred-row panel
 * into the three entries of the enum. One keystroke restores the full list (with the fits merely
 * boosted), so nothing is ever permanently hidden.
 */
internal object CompletionTypeFit {
    /** An expected type is *resolved*, not guessed, and two rows can be the whole answer
     *  (`Boolean` → `true`/`false`). */
    const val MIN_FITS_EXPECTED_TYPE = 2

    /** The infix operand type is inferred from indexed source text, so it takes a wider margin before
     *  it is allowed to hide anything. This is the threshold the infix path shipped with. */
    const val MIN_FITS_INFIX = 3

    /** True when the engine flagged at least one candidate as fitting the caret's expected type. */
    fun expectedTypeKnown(metadata: List<SourceCodeCompletionVariant>): Boolean =
        metadata.any { it.extraInfo[AaCompletionClient.EXPECTED_TYPE_MATCH] == "true" }

    /**
     * [enriched] unchanged, or just its type-fitting members when the caret is untyped and there are
     * enough of them to be a plausible answer on their own. Returns [enriched] itself (identity) when it
     * does not collapse, so callers can report which happened.
     */
    fun collapse(
        enriched: List<Metadata>,
        typedToken: String,
        expectedTypeKnown: Boolean,
        infixTypeKnown: Boolean,
    ): List<Metadata> {
        if (!expectedTypeKnown && !infixTypeKnown) return enriched
        if (typedToken.isNotBlank()) return enriched
        val fits = enriched.filter { it.contextScore > 0 }
        val minFits = if (expectedTypeKnown) MIN_FITS_EXPECTED_TYPE else MIN_FITS_INFIX
        return if (fits.size >= minFits) fits else enriched
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
            append(aiGeneratedAtticBadge(box, symbol))
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

/**
 * Boxes that have reported an error output since their current run began.
 *
 * Keyed by box id, cleared by [beginExecutionErrorScope] at the two places a run starts. The set is
 * read once, when the run's `"ok"` goes out, to decide whether the client may clear error markers --
 * see [Output.preserveErrors]. A run that reports an error and still succeeds (`stack.auto` does this
 * by design) would otherwise erase its own diagnostic.
 */
private val boxesWithRunErrors = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

/** Called where a run starts, so an error from the *previous* run is still cleared normally. */
internal fun beginExecutionErrorScope(box: Box) {
    boxesWithRunErrors.remove(box.id)
}

internal fun dispatchExecutionOutput(
    box: Box,
    reply: ((Message) -> Unit)?,
    output: Output,
    originSession: WebSocketSession? = thisSession.get()
) {
    var normalized = if (output.uid.isBlank()) output.copy(uid = box.id) else output
    when (normalized.kind) {
        "error", "err" -> boxesWithRunErrors.add(box.id)
        "ok" -> if (boxesWithRunErrors.remove(box.id)) {
            normalized = normalized.copy(preserveErrors = true)
        }
    }
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
        send(Message("set-output").payload(Set_output(box.id, "outputarea", channel, retainedDisplayContent(box, channel, content))))
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
            val launchEpoch: Int,
            val originSession: WebSocketSession?
        )


        private fun resetLaunchSetupState(box: Box) {
            box.properties.remove("_preamble")
            box.properties.remove("_attic")
        }

        /** One launch waiting its turn in [launchSequencer]: a chord, or a single box. */
        private class QueuedLaunch(
            val label: String,
            val finished: CompletableDeferred<Unit>,
            val run: suspend () -> Unit
        )

        /**
         * Every batch launch in the session, in the order the front end asked for them.
         *
         * A chord is one batch, and within a batch boxes are strictly serialized (see
         * [runPreparedBatchLaunches]) so a later box's macros and compile can see an earlier box's
         * runtime effects. That guarantee used to stop at the batch boundary: each `launch-batch`
         * got its own `GlobalScope.launch`, so chording a second group while the first was still
         * running raced it — a small box in the second group could finish compiling and *evaluate*
         * before boxes still queued in the first group, which for a child box whose parent is in
         * that first group is reliably an error. Queueing every batch through a single consumer
         * makes a second chord *extend* the sequence instead of competing with it.
         *
         * Unlimited and never closed: submission is synchronous and must not block the caller (the
         * websocket handler), and dropping a launch on the floor would be worse than queueing it.
         */
        private val launchSequencer = Channel<QueuedLaunch>(Channel.UNLIMITED)

        /**
         * The single consumer of [launchSequencer]. Started on first use rather than at class-init,
         * and deliberately never allowed to die: one batch throwing must not wedge every launch for
         * the rest of the session, so anything short of this coroutine's own cancellation is logged
         * and the queue moves on.
         */
        @OptIn(DelicateCoroutinesApi::class)
        private val launchPump: Job by lazy {
            GlobalScope.launch(Dispatchers.Default) {
                for (queued in launchSequencer) {
                    try {
                        // Give everything launched so far a chance to actually get going before the
                        // next entry evaluates -- the same courtesy `runPreparedBatchLaunches` pays
                        // between boxes *inside* a chord. A launched return value starts running from
                        // its own runloop coroutine (`ReturnValueRegistry`'s `inMain`), so without
                        // this the next box can evaluate before the previous box's first frame, which
                        // for a child box reading what its parent sets up on frame one is an error.
                        withContext(Runloop.runLoop.main) {
                            repeat(BATCH_LAUNCH_INTER_BOX_YIELDS) { yield() }
                        }
                        queued.run()
                    } catch (t: Throwable) {
                        if (t is CancellationException && !coroutineContext.isActive) throw t
                        lprintln(
                            "error,execution",
                            "queued launch (${queued.label}) threw ${t.javaClass.name}: ${t.message}"
                        )
                        lprintln("error,execution", t.stackTraceToString())
                    } finally {
                        queued.finished.complete(Unit)
                    }
                }
            }
        }

        /**
         * Put [work] at the back of the launch queue, returning a handle that completes when it has
         * run. [label] appears in the log if it throws.
         *
         * Callers do their *preparation* synchronously, before enqueueing, so watermarks — and
         * therefore the "launching" annotations — are assigned in the order the gestures arrived and
         * appear immediately, while each box waits its turn.
         */
        internal fun enqueueLaunch(label: String, work: suspend () -> Unit): CompletableDeferred<Unit> {
            val finished = CompletableDeferred<Unit>()
            launchPump // start the consumer if this is the first launch of the session
            val queued = launchSequencer.trySend(QueuedLaunch(label, finished, work))
            if (!queued.isSuccess) {
                // Cannot happen with an UNLIMITED, never-closed channel; if it ever does, say so
                // rather than silently losing the launch.
                lprintln("error,execution", "could not queue launch ($label): ${queued.exceptionOrNull()}")
                finished.complete(Unit)
            }
            return finished
        }

        private fun enqueueBatchLaunch(
            requests: List<Pair<Box, Execute>>,
            reply: (Message) -> Unit
        ): CompletableDeferred<Unit> {
            val originSession = thisSession.get()
            val pending = prepareBatchLaunchRequests(requests, originSession)
            val label = "chord of ${pending.size} box(es)"
            return enqueueLaunch(label) { runPreparedBatchLaunches(pending, reply) }
        }

        fun launchBatch(requests: List<Pair<Box, Execute>>, reply: (Message) -> Unit) {
            if (requests.isEmpty()) return
            enqueueBatchLaunch(requests, reply)
        }

        suspend fun launchBatchAndWait(requests: List<Pair<Box, Execute>>, reply: (Message) -> Unit) {
            if (requests.isEmpty()) return
            enqueueBatchLaunch(requests, reply).await()
        }

        private fun prepareBatchLaunchRequests(
            requests: List<Pair<Box, Execute>>,
            originSession: WebSocketSession?
        ): List<BatchLaunchRequest> =
            requests.map { (box, exec) ->
                val strand = exec.strand
                val normalizedCode = normalizedLaunchCodeFor(exec)
                val launchEpoch = box.captureLaunchEpoch()

                resetLaunchSetupState(box)

                val watermark = box.runGeneration.incrementAndGet(strand)
                box.currentRunningStates[strand] = RunningState(watermark, PENDING_LAUNCH_MODE)
                send(
                    Message("is-running").payload(
                        IsRunning(exec.uid, true, watermark, strand, PENDING_LAUNCH_MODE)
                    )
                )

                BatchLaunchRequest(box, exec, normalizedCode, strand, watermark, launchEpoch, originSession)
            }

        private suspend fun runPreparedBatchLaunches(
            pending: List<BatchLaunchRequest>,
            reply: (Message) -> Unit
        ) {
            if (pending.isEmpty()) return
            // Process boxes in order; within each box, interleave macro-expansion, compile, and eval per
            // `//%-` fragment (expand fragment N+1 only after fragment N has *run*). This matches
            // `standardExecutionPractice` (the executeAll / single-box launch path), so `//%-` means the
            // same thing everywhere: a later fragment's macros observe earlier fragments' runtime effects
            // (e.g. `once.vertex { ... } //%- moduleHere { }`). Boxes are serialized rather than compiled in
            // parallel — correctness over the previous cross-box compile parallelism.
            withContext(Dispatchers.Default) {
                var evaluatedAny = false
                for (request in pending) {
                    if (evaluatedAny) {
                        withContext(Runloop.runLoop.main) {
                            repeat(BATCH_LAUNCH_INTER_BOX_YIELDS) { yield() }
                        }
                    }
                    evaluatedAny = true
                    // One box throwing out of its launch (a macro expansion that fails outright, say)
                    // used to abandon the rest of the chord — and every abandoned box kept the
                    // "launching" annotation `prepareBatchLaunchRequests` had already sent for it,
                    // stripy forever. Report it against that box and carry on down the batch.
                    try {
                        evaluateInterleavedBatchLaunch(request, reply)
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        lprintln(
                            "error,execution",
                            "batch launch of box ${request.box.id} threw ${t.javaClass.name}: ${t.message}"
                        )
                        lprintln("error,execution", t.stackTraceToString())
                    }
                }
            }
        }

        /**
         * Run one box's launch, interleaving expand -> compile -> eval per `//%-` fragment so a fragment's
         * macro expansion observes prior fragments' runtime effects. Mirrors [standardExecutionPractice]
         * but carries the launch lifecycle (pending watermark, output proxies, return-value launching).
         */
        private suspend fun evaluateInterleavedBatchLaunch(
            request: BatchLaunchRequest,
            reply: (Message) -> Unit
        ) {
            val box = request.box
            val trace = ExecutionTrace(box, request.exec.property, request.exec.code, request.exec.requestId, "launch", reply)
            var executionSucceeded = false

            // Hold the "launching" marker (the stripy annotation `prepareBatchLaunchRequests` sends
            // for every box in the batch) until this box's own work is done and its launch state
            // takes over. This mirrors the single-box path, where `launchAll`'s `finishedHandler`
            // clears the pending marker in `executeInBox`'s `finally` -- i.e. after compile+eval.
            // Clearing it up front, as this used to, meant the FIRST box in a chord showed the
            // marker only for the few milliseconds it spent queued and then looked completely idle
            // for its whole compile+eval, which reads as "that box died"; the later boxes appeared
            // fine only because they were still waiting their turn. Cleared in a `finally` so no
            // exit path (stale launch epoch, compile error, thrown exception) can strand it.
            var pendingCleared = false
            fun clearPendingOnce() {
                if (pendingCleared) return
                pendingCleared = true
                clearPendingLaunch(request)
            }

            try {
                if (!box.isLaunchEpochCurrent(request.launchEpoch)) return

                Box.withBoxContext(box) {
                    box.resetStdlibRandom()
                    resetLaunchSetupState(box)
                    configureOutputProxies(box, trace.reply, request.originSession)
                    // Drop any stale pinned compile error up front; reportCompileDiagnostics re-pins on failure.
                    PinnedConsole.clearError(box.id)
                    beginExecutionErrorScope(box)

                    var hasError = false
                    val output = mutableListOf("")

                    box.output.proxy = { content, nl, line ->
                        val rendered = renderPrintedOutput(content)
                        output[output.size - 1] = output[output.size - 1] + rendered
                        if (nl) output.add("")
                        dispatchExecutionOutput(
                            box,
                            trace.reply,
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
                            trace.reply,
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
                                Set_output(box.id, "outputarea", channel, retainedDisplayContent(box, channel, content))
                            )
                        )
                    }

                    val lines = request.normalizedCode.lines()
                    val breaks = lines.mapIndexedNotNull { index, line ->
                        if (line.trim().startsWith("//%-") || line.trim().startsWith("/*%-*/")) index else null
                    } + lines.size

                    var at = 0
                    var lineCounter = 0
                    val results = mutableListOf<ResultValue?>()

                    for (breakIndex in breaks) {
                        val source = lines.subList(at, breakIndex).joinToString("\n")
                        at = breakIndex

                        // 1) macro-expand this fragment (off the main thread), rewriting __line__ markers with a
                        //    running global line counter so error locations stay correct across fragments.
                        val expanded = if (KotlinEngineConfig.offloadCompilation) {
                            withContext(box.ki.compilationDispatcher) { box.ki.applyMacros(box, source) }
                        } else {
                            box.ki.applyMacros(box, source)
                        }
                        val compiledSource = expanded.split("\n").joinToString("\n") { line ->
                            lineCounter++
                            line.replace("__line__", "${lineCounter - 1}")
                        }

                        // 2) setup blocks (preamble/introduction/attic) on the main thread.
                        val setupSucceeded = withContext(Runloop.runLoop.main) { runLaunchSetupBlocks(box) }
                        if (!setupSucceeded) {
                            hasError = true
                            break
                        }

                        // 3) compile this fragment.
                        val snippet = box.ki.withSourceProperty(request.exec.property) {
                            box.ki.compileSnippet(
                                compiledSource,
                                implicits = listOf(box),
                                onCompileError = { diagnostics ->
                                    reportCompileDiagnostics(
                                        box = box,
                                        reply = trace.reply,
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
                            hasError = true
                            break
                        }

                        // 4) eval this fragment on the main thread. Its runtime effects (e.g. `once.vertex`
                        //    setting the box's shader-source property) are visible to the NEXT fragment's
                        //    macro expansion — which is the whole point of the `//%-` fragment break.
                        var fragmentFailed = false
                        val result = withContext(Runloop.runLoop.main) {
                            snippet.eval(
                                // Commit this snippet's declarations to the REPL context, exactly as the
                                // exec path (`standardExecutionPractice` -> `KotlinInterface2.exec`) does.
                                // `PreparedSnippet.eval` defaults to false, and with it false
                                // `contextUpdater.update()` never runs -- so a batch (chorded) launch never
                                // refreshed `context.vars`/`functions` and never processed `@exported`,
                                // leaving a sibling box's `_a` unresolvable until the exporting box was
                                // re-run through a non-batch path.
                                updateContext = true,
                                implicits = listOf(box),
                                onEvalError = { diagnostics ->
                                    hasError = true
                                    fragmentFailed = true
                                    reportEvalError(
                                        box,
                                        reply,
                                        request.originSession,
                                        diagnostics,
                                        request.exec.line,
                                        request.exec.property
                                    )
                                },
                                onEvalException = { throwable ->
                                    hasError = true
                                    fragmentFailed = true
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
                        results += result
                        if (fragmentFailed) break
                    }

                    if (!hasError) {
                        val printedSize = output.sumOf { it.trim().length }
                        results.forEach { returnValue ->
                            reportReturnValueAndLaunch(
                                box = box,
                                reply = trace.reply,
                                originSession = request.originSession,
                                returnValue = returnValue,
                                printedSize = printedSize,
                                line = request.exec.line + request.normalizedCode.lines().size,
                                code = request.normalizedCode,
                                strand = request.strand,
                                launchEpoch = request.launchEpoch,
                            )
                        }
                        executionSucceeded = true
                    } else {
                        lprintln("error,execution", "batch launch finished with errors")
                    }

                    // Retire the pending marker only now that the launch state has been published.
                    // Deliberately after, not before: the launch state carries a strictly newer
                    // watermark (`ReturnValueRegistry.launchPrepared` increments the generation), so
                    // this stop is ignored by both the box's `currentRunningStates` guard and the
                    // front end's `watermark < lastWatermark` check -- and the box never spends even
                    // a frame looking idle between "compiling and launching" and "running".
                    //
                    // On the runloop, and therefore behind the handler's own `inMain`: a `Frames`
                    // return value (what `loop { }` is) reports itself running from a main-thread
                    // coroutine, so `reportReturnValueAndLaunch` returns *before* that happens.
                    // Queueing this clear behind it keeps the hand-over ordered rather than racing.
                    withContext(Runloop.runLoop.main) { clearPendingOnce() }
                }
            } finally {
                clearPendingOnce()
                trace.finish(executionSucceeded && box.isLaunchEpochCurrent(request.launchEpoch))
            }
        }

        private fun normalizedLaunchCodeFor(exec: Execute): String {
            val leadingLines = exec.line.coerceAtLeast(0)
            return "\n".repeat(leadingLines) + exec.code.trimEnd()
        }

        private suspend fun runLaunchSetupBlocks(box: Box): Boolean {
            if (!runSetupBlock(box, "preamble") { c, e, x -> checkPreambleAndExecute(box, c, e, x) }) return false
            if (!runSetupBlock(box, "introduction") { c, e, x -> checkIntroductionAndExecute(box, c, e, x) }) return false
            return runSetupBlock(box, "attic") { c, e, x -> checkAtticAndExecute(box, c, e, x) }
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
        ): Boolean {
            val key = "${box.id}#$block"
            var failed = false
            PinnedConsole.clearError(key)
            run(
                { diagnostics ->
                    failed = true
                    diagnostics.forEach { lprintln("warning,macros", "setup compile: ${it.message} ${it.location}") }
                    pinSetupError(box, key, block, diagnostics)
                },
                { diagnostics ->
                    failed = true
                    diagnostics.forEach { lprintln("warning,macros", "setup eval: ${it.message}") }
                    pinSetupError(box, key, block, diagnostics)
                },
                { throwable ->
                    failed = true
                    lprintln("error,macros", "setup exception: ${throwable.message}")
                    pinSetupException(box, key, block, throwable)
                }
            )
            return !failed
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
         * Report an eval-time *diagnostic* failure — the evaluator refusing the compiled snippet, as
         * opposed to the snippet throwing (see [reportEvalException]). Pinning alone was all this path
         * used to do, so the box got no error mark and the receipt said only "did not complete": a
         * `ClassFormatError` from `val (a, _) = p` (2026-09-28) looked exactly like a hang. These
         * diagnostics rarely carry a location, so the mark goes on [lineOffset] when they do not; a located
         * one is placed exactly as the compile-error path places it.
         */
        internal fun reportEvalError(
            box: Box,
            reply: ((Message) -> Unit)?,
            originSession: WebSocketSession?,
            diagnostics: List<ScriptDiagnostic>,
            lineOffset: Int,
            property: String = "code"
        ) {
            diagnostics.forEach {
                lprintln("error,execution", "  ${it.code} ${it.message} ${it.location}")
            }
            pinEvalError(box, diagnostics)
            val diagnostic = diagnostics.firstOrNull {
                it.severity.ordinal >= ScriptDiagnostic.Severity.ERROR.ordinal
            } ?: diagnostics.firstOrNull() ?: return
            // The evaluator usually folds the exception into its message already; add it only if not.
            val cause = diagnostic.exception
            val message = if (cause == null || cause.message?.let { it in diagnostic.message } == true) diagnostic.message
                else "${diagnostic.message} — ${cause.javaClass.simpleName}: ${cause.message}"
            val start = diagnostic.location?.start
            dispatchExecutionOutput(
                box,
                reply,
                Output(
                    uid = box.id,
                    line = start?.line ?: lineOffset,
                    startColumn = start?.col ?: -1,
                    kind = "error",
                    property = property,
                    html = escapeForHtml(message),
                    frame = Runloop.runLoop.frame.toInt()
                ),
                originSession = originSession
            )
        }

        /**
         * Pin a runtime exception thrown while evaluating [box] as a sticky panel (see [pinCompileError]):
         * the exception type + message plus the top few stack frames. The full trace still scrolls via the
         * `error,execution` line; this is the always-visible summary. Cleared on the next run of the box.
         */
        private fun pinEvalException(box: Box, throwable: Throwable, stale: String? = null) {
            val visible = userFacingThrowable(throwable)
            val head = visible.message?.takeIf { it.isNotBlank() } ?: visible.javaClass.name
            val frames = throwable.stackTrace.take(6).map { "at $it" }
            PinnedConsole.error(
                box.id,
                "exception · ${box.id.take(8)}",
                listOfNotNull(stale) + listOf("${visible.javaClass.simpleName}: $head") + frames
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
            val visible = userFacingThrowable(throwable)
            val head = visible.message?.takeIf { it.isNotBlank() } ?: visible.javaClass.name
            val frames = throwable.stackTrace.take(6).map { "at $it" }
            PinnedConsole.error(
                key,
                "$block exception · ${box.id.take(8)}",
                listOf("${visible.javaClass.simpleName}: $head") + frames
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
            val visible = userFacingThrowable(throwable)
            lprintln("error,execution", "exception thrown in $context inside box $box")
            lprintln("error,execution", throwable.stackTraceToString())

            // "class kiwi.Solver cannot be cast to class kiwi.Solver" is what a reloaded personal
            // layer looks like from here, and it is unreadable. If that is what happened, lead with
            // the explanation instead; staleLayerDiagnosis also flags the box for re-running, which
            // is the fix. Costs a boolean in any session that never reloaded.
            val stale = runCatching { staleLayerDiagnosis(box, throwable) }.getOrNull()
            if (stale != null) lprintln("error,execution,personal", "box ${box.id}: $stale")
            pinEvalException(box, throwable, stale)

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
                    html = escapeForHtml(
                        listOfNotNull(stale, visible.message ?: visible.javaClass.name).joinToString(" — ")
                    ),
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
            strand: String,
            launchEpoch: Int,
        ) {
            when (returnValue) {
                is ResultValue.Unit -> {
                    if (printedSize == 0) reportOk(box, reply, originSession, line)
                    launchPreparedReturnValue(box, null, strand, code, launchEpoch, reply, originSession)
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
                    launchPreparedReturnValue(box, null, strand, code, launchEpoch, reply, originSession)
                }

                is ResultValue.Value -> {
                    val audioPatch = returnValue.value as? AudioPatchSpec
                    if (printedSize == 0) {
                        if (returnValue.value == null) {
                            reportOk(box, reply, originSession, line)
                            launchPreparedReturnValue(box, null, strand, code, launchEpoch, reply, originSession)
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

                    launchPreparedReturnValue(box, returnValue.value, strand, code, launchEpoch, reply, originSession)
                }

                null -> {
                    if (printedSize == 0) reportOk(box, reply, originSession, line)
                    launchPreparedReturnValue(box, null, strand, code, launchEpoch, reply, originSession)
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
            launchEpoch: Int,
            reply: (Message) -> Unit,
            originSession: WebSocketSession?
        ) {
            if (!box.isLaunchEpochCurrent(launchEpoch)) {
                box.cachedLaunchReturns.remove(strand)
                return
            }
            if (returnValue == null) {
                box.cachedLaunchReturns.remove(strand)
            } else {
                box.cachedLaunchReturns[strand] = CachedLaunchReturn(code, returnValue)
            }

            configureOutputProxies(box, reply, originSession)
            ReturnValueRegistry.launchPrepared(box, returnValue, strand, launchEpoch) { isRunning, notifiedStrand, watermark ->
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
                requestId = execute.requestId,
                sourceText = execute.code,
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
                requestId = execute.requestId,
                sourceText = execute.code,
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
                execute.line,
                property = execute.property,
                requestId = execute.requestId,
                sourceText = execute.code,
                executionMode = "run",
                finishedHandler = {
                    send(Message("is-running").payload(IsRunning(execute.uid, false, wm, "__fragment__")))
                }) {}
        }


    }

    override fun launchAll(inside: Box, execute: Execute, code: String, strand: String, reply: ((Message) -> Unit)) {
        val originSession = thisSession.get()
        val launchEpoch = inside.captureLaunchEpoch()

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

        // Queued, not fired straight at the runloop. A single-box launch is the same kind of event
        // as a chord -- the front end even reaches it *from* a chord, `canvasGestures` sending
        // `launch-all` whenever the selection is one box (Main.kt) -- so it has to take its place in
        // the same sequence. Fired directly, as it used to be, it would evaluate as soon as it
        // compiled: launch a row of boxes and then a single box below it, and the single one runs
        // before the slow-compiling box above it has evaluated, which is exactly the ordering a
        // child box cannot survive. The prologue above stays synchronous, so this box's "launching"
        // annotation is already showing while it waits.
        enqueueLaunch("launch ${inside.id} strand=$strand") {
            withContext(Runloop.runLoop.main) {

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
                requestId = execute.requestId,
                sourceText = execute.code,
                executionMode = "launch",
                accepted = { inside.isLaunchEpochCurrent(launchEpoch) },
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
            ) handleReturn@{ returnValue ->
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
                if (!inside.isLaunchEpochCurrent(launchEpoch)) {
                    inside.cachedLaunchReturns.remove(strand)
                    return@handleReturn
                }
                cacheLaunchReturnValue(inside, strand, normalizedCode, returnValue)
                configureOutputProxies(inside, reply, originSession)

                ReturnValueRegistry.launchPrepared(inside, returnValue, strand, launchEpoch) { isRunning, strand, watermark ->
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
        val launchEpoch = inside.captureLaunchEpoch()
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
            val trace = ExecutionTrace(inside, execute.property, execute.code, execute.requestId, "continue", reply)
            if (!inside.isLaunchEpochCurrent(launchEpoch)) {
                trace.finish(false, "Launch was cancelled before installation")
                return@inMain
            }
            try {
            configureOutputProxies(inside, trace.reply, originSession)
            ReturnValueRegistry.launchPrepared(inside, cached.returnValue, strand, launchEpoch) { isRunning, notifiedStrand, watermark ->
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
            trace.finish(true)
            } catch (failure: Throwable) {
                trace.finish(false, failure.message ?: failure.javaClass.name)
                throw failure
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
        requestId: String = "",
        executionMode: String = "fragment",
        sourceText: String = code,
        accepted: () -> Boolean = { true },
        finishedHandler: () -> Unit = {},
        handleReturn: (Any?) -> Unit
    ) {
        // Running the box is the fix for a recycled engine, so the flag reloadPersonal set comes off
        // here -- on the way in, not on success, since a run that fails has still rebuilt whatever
        // it got to and the flag would otherwise stick around describing a reload two edits ago.
        box.needsRerun = null
        val trace = ExecutionTrace(box, property, sourceText, requestId, executionMode, reply)
        var executionSucceeded = false

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
                        trace.reply,
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
                        trace.reply,
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
                                retainedDisplayContent(box, channel, content)
                            )
                        )
                    )
                }

                // Clear any stale pinned compile error before re-running; re-pinned below on failure.
                PinnedConsole.clearError(box.id)
                beginExecutionErrorScope(box)
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
                                        trace.reply,
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
                                        trace.reply,
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
                        reportEvalError(box, trace.reply, originSession, evalError, ln, property)
                    }, { evalException ->
                        hasError = true
                        lprintln("error,execution", "exception thrown in fragment inside box $box")
                        lprintln("error,execution", evalException.stackTraceToString())
                        pinEvalException(box, evalException)

                        val visible = userFacingThrowable(evalException)
                        val location = editorLocationForEvalException(box, evalException)
                        val firstLine = location?.line ?: ln
                        val stack = stackLocationsForEvalException(box, evalException, location, property)
                        dispatchExecutionOutput(
                            box,
                            trace.reply,
                            Output(
                                uid = box.id,
                                line = firstLine,
                                kind = "error",
                                property = location?.property ?: property,
                                html = escapeForHtml(visible.message ?: visible.javaClass.name),
                                frame = Runloop.runLoop.frame.toInt(),
                                stack = stack
                            ),
                            originSession = originSession
                        )

                    },
                        implicits = listOf(box),
                        runSetupBlocks = runSetupBlocks,
                        onSetupCompileError = { block, diagnostics ->
                            hasError = true
                            val key = "${box.id}#$block"
                            pinSetupError(box, key, block, diagnostics)
                            diagnostics.firstOrNull {
                                it.severity.ordinal >= ScriptDiagnostic.Severity.ERROR.ordinal
                            }?.let { diagnostic ->
                                dispatchExecutionOutput(
                                    box,
                                    trace.reply,
                                    Output(
                                        uid = box.id,
                                        line = diagnostic.location?.start?.line ?: 0,
                                        startColumn = diagnostic.location?.start?.col ?: -1,
                                        kind = "error",
                                        property = block,
                                        html = diagnostic.message,
                                        frame = Runloop.runLoop.frame.toInt()
                                    ),
                                    originSession = originSession
                                )
                            }
                        },
                        onSetupEvalError = { block, diagnostics ->
                            hasError = true
                            pinSetupError(box, "${box.id}#$block", block, diagnostics)
                        },
                        onSetupEvalException = { block, throwable ->
                            hasError = true
                            val visible = userFacingThrowable(throwable)
                            pinSetupException(box, "${box.id}#$block", block, throwable)
                            dispatchExecutionOutput(
                                box,
                                trace.reply,
                                Output(
                                    uid = box.id,
                                    line = 0,
                                    kind = "error",
                                    property = block,
                                    html = escapeForHtml(visible.message ?: visible.javaClass.name),
                                    frame = Runloop.runLoop.frame.toInt()
                                ),
                                originSession = originSession
                            )
                        }
                    )
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
                                    trace.reply,
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
                                    trace.reply,
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
                                            trace.reply,
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
                                            trace.reply,
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
                                        trace.reply,
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
                                    trace.reply,
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
                    executionSucceeded = true
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
                    trace.reply,
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
                try { finishedHandler() } finally { trace.finish(executionSucceeded && accepted()) }
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
        inside.stopTrackedJobs()
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

        // Member access on a live context variable (`m.inside`): show the field's live value in the same
        // rich .h disclosure `m` itself gets. Reuses the backing-field-only reader (never invokes a getter).
        val dotValueHtml = dotMemberRuntimeValueHtml(inside, request.allCode, bounds.start, symbol)
        if (dotValueHtml.isNotBlank()) {
            val resp = symbolContextResponse(symbol = symbol, html = dotValueHtml)
            if (!request.forceRefresh) KotlinSymbolContextCache.put(cacheKey, resp)
            return resp
        }

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
                        symbolContextResponse(
                            symbol = symbol,
                            html = buildSymbolContextHtml(
                                box = inside,
                                symbolName = symbol,
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

        // StackSL or RectSL inside the box's Kotlin — a `#stacksl` or `#rectsl` string, or StackSL assigned to a stack.auto slot —
        // completes as StackSL, from the compiler's vocabulary, rather than as a Kotlin string.
        if (property == "code") {
            (afield.stacksl.study.EmbeddedStackSL.complete(inside, allCode, position) ?: afield.rectsl.study.EmbeddedRectSL.complete(allCode, position))?.let {
                reply(Message("complete").payload(it))
                return
            }
        }

        // A small language's program kept in a property of a Kotlin box (the StackSL gallery's boxes)
        // completes in that language, as it would under a root of its own.
        if (property == "stackSL") {
            reply(Message("complete").payload(afield.stacksl.StackSLVocabulary.complete(allCode, position)))
            return
        }
        if (property == "rectSL") {
            reply(Message("complete").payload(afield.rectsl.RectSLVocabulary.complete(allCode, position)))
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
                            // Which shader language this property holds is the language registry's
                            // answer, not something to re-derive from the property's spelling: the
                            // Slang set is not just the `_s` suffix (`vertex_fragment` is Slang too),
                            // and the registry is what `shaderSpecForProperty` above already agreed
                            // with. This block only runs for a property that has a shader spec.
                            append(if (glslSpecForProperty(property) != null) "glsl" else "slang")
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
         * KDoc/documentation resolution: the K2 2.4 scripting jars expose no `DeclarationDescriptor`
         * here, so symbol names are derived by string-parsing the completion item's
         * `text`/`displayText`. Documentation is then resolved by: (1) `extraInfo["kdoc"]`, embedded during
         * reflection-based completion in [afield.KotlinInterface2] (including receiver-qualified
         * `ReceiverType.method` KDoc for dot-completion); then (2) `KotlinSourceIndex.getKDocForSymbol(...)`
         * by candidate name for plain name-completions, or by `receiverType.candidate` for dot-completion.
         */
        fun completionFor(c: CompletionResult.Success, infixExpectedTypes: Set<String> = emptySet()): List<Metadata> {
            val activeToken = allCode.substring(c.bounds.start, c.bounds.end).trim()
            val argumentCallable = callableBeforeArgumentCursor(allCode, position)
            // The receiver the `@CommonMembers` prior keys on when the backend named none. AA forwards
            // `receiverQualifiedName` on every row; the K1 delegate forwards nothing, and K1 serves the
            // first completion after launch plus every completion while the AA session warms — so without
            // this the prior is absent for the popup a person meets first. Resolved once per request (one
            // caret has one receiver) and only if some row actually asks for it.
            // `KotlinType.typeName` is fully qualified; the documentation lookup matches on simple
            // names (`Box.frame`), and `null` when there is no implicit receiver simply leaves the
            // hint unset, exactly as an unrecognised row does.
            val implicitReceiverHint: String? =
                inside.ki.implicitReceivers.firstOrNull()?.typeName?.substringAfterLast('.')

            // The box's shader-binding comments, gathered and parsed at most once per request — the walk
            // and the parse are far too expensive to repeat for each of a thousand candidate rows.
            val shaderBindingDocs: ShaderBindingDocs.Lookup by lazy(LazyThreadSafetyMode.NONE) {
                ShaderBindingDocs.indexFor(inside)
            }

            val caretReceiverQualifiedName: String? by lazy(LazyThreadSafetyMode.NONE) {
                KotlinCompletionSupport.receiverQualifiedNameAtCompletion(inside, allCode, position).also {
                    if (completionTraceEnabled()) {
                        completionTrace("completion prior-receiver resolved='${it.orEmpty()}'")
                    }
                }
            }
            return c.metadata.mapNotNull {
//                println(it.extraInfo)
                var extraDoc = ""
                val mainText = it.displayText
                val candidateName = it.text.substringBefore('<').substringBefore('(').substringAfterLast('.').trim()
                val shouldEnrich = candidateName.isNotBlank() && (
                        (activeToken.isNotBlank() && candidateName.startsWith(activeToken)) ||
                                candidateName == argumentCallable
                        )

                // Try to get KDoc from the source index for afield symbols.
                // Skip for dot-completion (member access) since the simple name is ambiguous —
                // e.g. "add" could be Vector3f.add or LittleHistory.add.
                val isDotCompletion = KotlinCompletionSupport.isDotSelection(allCode, c.bounds.start)

                // A `// note` written next to a push constant in the shader, carried into the preamble by
                // the generated accessor and read back off it (see [afield.ShaderBindingDocs]). Runs first
                // because it is box-local and receiver-matched: it should win over an index hit for a
                // repository symbol that merely shares the name. Dot completion only — a `Mesh` extension
                // is not in scope as a bare name, so a bare row named `time` is somebody else's.
                if (isDotCompletion && candidateName.isNotEmpty()) {
                    shaderBindingDocs.documentationFor(
                        member = candidateName,
                        receiverType = it.extraInfo["receiverQualifiedName"] as? String
                            ?: it.extraInfo["receiverType"] as? String
                    )?.let { note -> extraDoc = note }
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

                // Build documentation from the display text (signature)
                val documentation = if (it.displayText.isNotBlank()) {
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
                ).ifBlank {
                    // Dot completion on a live context variable: show the actual value of a plain JVM field.
                    // Only for value-like candidates (a property/field) — NOT the same-named methods
                    // (e.g. `x` has a field `x` and methods `x()`/`x(b)`; only the field shows a value).
                    if (isDotCompletion && completionIconLooksLikeValue(it.icon))
                        dotMemberRuntimeValueHtml(inside, allCode, c.bounds.start, candidateName)
                    else ""
                }

                val rawMetadata = Metadata(
                    icon = it.icon,
                    tail = it.tail,
                    text = it.text,
                    displayText = mainText.trim(),
                    documentation = documentation,
                    runtimeDocumentationHtml = runtimeDocumentationHtml
                )

                // The receiver a documentation lookup should match against. The backend names it when
                // it can; when it cannot and the caret is not after a dot, the receiver is the one the
                // *engine* was built with — box code is compiled with `Box` as an implicit receiver, so
                // a bare `frame` is either a local or a member of that type. Ask the engine rather than
                // writing "Box" here, so this follows if a box is ever given another implicit receiver.
                // (Only value-like rows: a callable takes the separate `resolveCallableInfo` path, which
                // does its own receiver reasoning.)
                val receiverTypeHint = it.extraInfo.get("receiverQualifiedName") as? String
                    ?: it.extraInfo.get("receiverType") as? String
                    ?: if (!isDotCompletion && it.icon in setOf("property", "val", "var")) implicitReceiverHint
                    else null
                val descriptorText = it.extraInfo.get("original")?.toString()
                val enrichedMetadata = if (shouldEnrich) {
                    val enriched = KotlinCompletionSupport.enrichMetadata(
                        box = inside,
                        allCode = allCode,
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
                // Two independent type-fit signals, one boost. The infix one is resolved here from the
                // source index (K1 could not be asked); the expected-type one is resolved by the AA
                // sidecar from `KaExpressionTypeProvider.expectedType` and arrives as an extraInfo flag.
                val typeFits = (infixExpectedTypes.isNotEmpty() && InfixCompletion.matchesOperandType(it, infixExpectedTypes)) ||
                    it.extraInfo[AaCompletionClient.EXPECTED_TYPE_MATCH] == "true"
                // How far the candidate's declaration is from the receiver's own type, forwarded verbatim
                // for the client to rank on: a name declared on `Any` or on an unconstrained `T` is true of
                // every receiver, so it should sit below the names that are about THIS type.
                val proximity = it.extraInfo[AaCompletionClient.DECLARATION_PROXIMITY]?.toString().orEmpty()
                val withProximity = if (proximity.isBlank()) categorizedMetadata
                    else categorizedMetadata.copy(declarationProximity = proximity)
                // The receiver type's own statement of which members matter, from `@CommonMembers`. Only
                // the *name* is matched, so one entry covers every overload. This is the popup's only
                // prior at an empty prefix, where there is no text to fuzzy-match and `recency` — the
                // term that would otherwise decide it — is empty for anyone who has not used the member
                // before on this machine.
                // Only when the backend named nothing the prior can load — no receiver at all (K1), or an
                // unqualified one (a bare `receiverType`, or the `"Box"` stand-in above) — does the caret's
                // own receiver stand in. A qualified hint decides alone, so the AA path is unchanged: a
                // member AA's receiver has no prior for must not pick one up from a differently-inferred
                // type for the same expression.
                val priorReceiver = receiverTypeHint?.takeIf { '.' in it } ?: caretReceiverQualifiedName
                val commonRank = CommonMemberPrior.rankOf(priorReceiver, candidateName)
                val withPrior = if (commonRank <= 0) withProximity
                    else withProximity.copy(commonMemberRank = commonRank)
                if (typeFits) {
                    withPrior.copy(contextScore = InfixCompletion.OPERAND_MATCH_BOOST)
                } else {
                    withPrior
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

        fun emptyCompletion(): Completion =
            Completion(
                boundsStart = position,
                boundsEnd = position,
                metadata = emptyList()
            )

        fun replyEmpty(reason: String) {
            completionFailure("completion empty reason='$reason' uid='${inside.id}' property='$property' position=$position")
            reply(Message("complete").payload(emptyCompletion()))
        }

        runBlocking {
            launch(Runloop.runLoop.main) {
                val c = runCatching {
                    inside.ki.complete(allCode, position)
                }.getOrElse {
                    completionFailure(
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
                        val fieldSnippets = KotlinCompletionSupport.fieldSnippetCompletions(
                            activeToken = typedToken,
                            allCode = allCode,
                            tokenStart = tokenBounds.start
                        )
                        val useTokenBounds = c.metadata.isEmpty() && fieldSnippets.isNotEmpty()
                        val boundsStart = synthetic?.bounds?.start ?: if (useTokenBounds) tokenBounds.start else c.bounds.start
                        val boundsEnd = synthetic?.bounds?.end ?: if (useTokenBounds) tokenBounds.end else c.bounds.end

                        // Did the engine tell us the type expected at the caret? (AA only: the sidecar
                        // resolves `expectedType` and flags the candidates that fit — a property's type at
                        // `d.scheme = ‸`, a parameter's at `f(word = ‸)`. K1 has no such signal.)
                        val expectedTypeKnown = CompletionTypeFit.expectedTypeKnown(c.metadata)
                        val typeAware = infixExpectedTypes.isNotEmpty() || expectedTypeKnown
                        val enriched = completionFor(c, infixExpectedTypes)
                        val bodyMetadata = CompletionTypeFit.collapse(
                            enriched, typedToken, expectedTypeKnown, infixExpectedTypes.isNotEmpty()
                        )
                        if (completionTraceEnabled() && typeAware) {
                            completionTrace(
                                "completion type-fit expectedTypeKnown=$expectedTypeKnown " +
                                    "fits=${enriched.count { it.contextScore > 0 }} " +
                                    "collapsed=${bodyMetadata !== enriched} uid='${inside.id}' position=$position"
                            )
                        }

                        // Import ("(import) …") suggestions only make sense when completing a top-level
                        // name to bring into scope. At a dot/safe-dot position you're selecting a member of
                        // a known receiver, so they're pure noise — suppress them there.
                        val importHelp =
                            if (KotlinCompletionSupport.isDotSelection(allCode, c.bounds.start)) emptyList()
                            else importHelpFor(c)

                        val completions = Completion(
                            boundsStart = boundsStart,
                            boundsEnd = boundsEnd,
                            metadata = ((synthetic?.metadata ?: emptyList()) + fieldSnippets + bodyMetadata + importHelp)
                                .map(KotlinCompletionSupport::withCategoryDocumentationHeader)
                                // Front-load the type-matching candidates (infix operand, or expected type
                                // at the caret) so they lead the list even for clients that lean on server
                                // order. Stable, so everything else keeps its relative order.
                                .let { if (!typeAware) it else it.sortedByDescending { m -> m.contextScore } }
                        )

                        reply(
                            Message("complete").payload(
                                completions
                            )
                        )
                    }

                    is CompletionResult.Error -> {
                        completionFailure(
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

        val fuzzy = ImportResolution.index().asSequence()
            .filter { (simpleName, fqNames) ->
                simpleName != missing &&
                        simpleName.firstOrNull()?.isUpperCase() == true &&
                        !simpleName.contains("$") &&
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

/**
 * The same test the import *macro* applies: [ImportResolution]'s blacklist, which is the user's
 * `importClassBlacklist` preference — or [defaultImportClassBlacklist] under `AFIELD_IMPORT_HERMETIC`.
 *
 * This was a local `!fqName.lowercase().contains("clojure")`, which had the typo suggestions below
 * answering to a different policy than the exact-name path through [findClassesNamed]: they would
 * offer `add import java.awt.image.BufferedImage` while the blacklist's `*awt*` refused the same
 * class one branch over, and they read the raw classpath index, so `AFIELD_IMPORT_HERMETIC=1` did
 * not reach them (see `dev_docs/HERMETIC_COMPLETION_INPUTS.md`). Clojure — which really is on the
 * classpath, and really does bury a typo's suggestions — now sits in [defaultImportClassBlacklist],
 * where a person can see it and edit it.
 */
private fun isUsefulImportCandidate(fqName: String): Boolean =
    fqName.contains(".") && isImportableClassName(fqName, ImportResolution.blacklist())

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
        val onDiagnosticLine = firstCodeOccurrence(code, missing, lineStart, lineEnd)
        if (onDiagnosticLine >= 0) {
            return CompletionTokenBounds(onDiagnosticLine, onDiagnosticLine + missing.length)
        }
    }

    val anywhere = firstCodeOccurrence(code, missing)
    if (anywhere < 0) return null
    return CompletionTokenBounds(anywhere, anywhere + missing.length)
}

/**
 * The offset of the first place [missing] appears in [code] as a whole identifier **in code** —
 * never inside a string or character literal, a line comment or a block comment. `-1` when there is
 * no such place.
 *
 * This was `Regex("""\b…\b""").find(code)`, which matched just as happily inside `"no such widget"`
 * or a `// widget goes here` note. The offset feeds a probe that cuts the token out of the source and
 * runs completion in the hole ([collectAmbientCompletionSymbols]), so a match inside a literal asked
 * the engine about a caret the compiler had never complained about — and answered with its
 * suggestions. Returning -1 rather than a wrong offset costs a "did you mean" list nobody could have
 * used.
 *
 * A string template's interpolation is treated as part of its string: skipping a real occurrence there
 * loses a suggestion, which is the safe direction to be wrong in.
 */
internal fun firstCodeOccurrence(code: String, missing: String, from: Int = 0, until: Int = code.length): Int {
    if (missing.isEmpty()) return -1
    val end = until.coerceIn(0, code.length)
    var i = from.coerceIn(0, end)
    var blockCommentDepth = 0

    fun isIdentifierPart(c: Char) = c.isLetterOrDigit() || c == '_'

    while (i < end) {
        val c = code[i]
        val next = code.getOrNull(i + 1)
        when {
            blockCommentDepth > 0 -> {
                if (c == '*' && next == '/') { blockCommentDepth--; i += 2 }
                else if (c == '/' && next == '*') { blockCommentDepth++; i += 2 }
                else i++
            }

            c == '/' && next == '/' -> {
                val lineEnd = code.indexOf('\n', i)
                i = if (lineEnd < 0) end else lineEnd
            }

            c == '/' && next == '*' -> { blockCommentDepth = 1; i += 2 }

            c == '"' && code.startsWith("\"\"\"", i) -> {
                val close = code.indexOf("\"\"\"", i + 3)
                i = if (close < 0) end else close + 3
            }

            c == '"' || c == '\'' -> {
                var j = i + 1
                while (j < code.length) {
                    if (code[j] == '\\') { j += 2; continue }
                    if (code[j] == c || code[j] == '\n') break
                    j++
                }
                i = (j + 1).coerceAtMost(end)
            }

            code.startsWith(missing, i) &&
                !isIdentifierPart(code.getOrNull(i - 1) ?: ' ') &&
                !isIdentifierPart(code.getOrNull(i + missing.length) ?: ' ') -> return i

            else -> i++
        }
    }
    return -1
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
