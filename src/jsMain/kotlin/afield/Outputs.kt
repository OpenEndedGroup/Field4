package afield

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.Text
import kotlin.math.*

private val knownOutputs = mutableListOf<dynamic>()

private class EditorDecorations {
    var collection: dynamic = null
    var contents: MutableList<dynamic> = mutableListOf()
}
private val perEditorDecorations = mutableMapOf<Double, EditorDecorations>()

private fun getOrCreateEditorDecorations(editor: dynamic): EditorDecorations {
    val key = weakHash(editor)
    val existing = perEditorDecorations[key]
    if (existing != null && existing.collection != null) return existing
    existing?.collection?.clear()
    val ed = EditorDecorations()
    ed.collection = editor.createDecorationsCollection(emptyArray<Any>())
    perEditorDecorations[key] = ed
    return ed
}

private fun editorDecorationsFor(editor: dynamic): EditorDecorations? {
    return perEditorDecorations[weakHash(editor)]
}

private var executionDecorations = mutableListOf<dynamic>()
private var executionsCollection: dynamic = null
private val stackTraceHintCollections = mutableMapOf<Double, dynamic>()
private val debounceTimers = mutableMapOf<String, Int>()

private var orderCounter = 0
private var addPromptBoxUid = 0
private val recentLocalLaunchOutputClears = mutableMapOf<String, Double>()

private const val executionMarkerGutterInsetPx = -12
private const val outputHeightFudgePx = 5.0
private const val remoteClearSuppressionWindowMs = 2_000.0
private var outputMaxHeightPx = 500.0
private var outputScrollTailExistingPx = 40.0
private var outputScrollTailNewPx = 46.0
private const val maxRenderedOutputRows = 80
private const val maxRenderedOutputChars = 40_000
private const val maxOutputMessageBreaks = 80
private val htmlBreakRegex = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

private fun executionMarkerStateClass(mode: String): String {
    return when (mode) {
        "launching" -> "running-launching"
        "continue" -> "running-continued"
        "ending" -> "running-ending"
        else -> "running"
    }
}

private fun debounceTimeout(key: String, delay: Int, fn: () -> Unit) {
    debounceTimers[key]?.let { window.clearTimeout(it) }
    val id = window.setTimeout({
        debounceTimers.remove(key)
        fn()
    }, delay)
    debounceTimers[key] = id
}

private fun weakHash(d: dynamic): Double {
    val existing = d.__weakheash__
    return if (existing != null) {
        existing as Double
    } else {
        val v = js("Math.random()") as Double
        d.__weakheash__ = v
        v
    }
}

private fun focusedMonacoEditorOrNull(): dynamic {
    val w = js("window")
    val embeddedEditor = runCatching {
        val registry = w.embeddedEditors
        val entry = if (registry != null) registry.getFocused() else null
        entry?.editor
    }.getOrNull()
    if (embeddedEditor != null) return embeddedEditor

    val globalEditor = w.globalMonacoEditor ?: return null
    val hasTextFocus = runCatching {
        globalEditor.hasTextFocus() as Boolean
    }.getOrDefault(false)
    return if (hasTextFocus) globalEditor else null
}

private fun activeMonacoEditor(): dynamic {
    val w = js("window")
    return focusedMonacoEditorOrNull() ?: w.globalMonacoEditor
}

private fun outputEditorOrNull(output: dynamic): dynamic {
    val outputEditor = output.editor
    if (outputEditor != null) return outputEditor
    return js("window").globalMonacoEditor
}

private fun knownOutputsForEditor(editor: dynamic): List<dynamic> {
    return knownOutputs.filter { output ->
        val outputEditor = outputEditorOrNull(output) ?: return@filter false
        sameEditor(outputEditor, editor)
    }
}

private fun isStructuralClosingLine(text: String): Boolean {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return true
    return trimmed.all { it == '}' || it == ')' || it == ']' || it == ';' }
}

private fun normalizeInlineOutputTargetLine(editor: dynamic, lineNumber: Int): Int {
    val model = editor.getModel() ?: return lineNumber
    var line = lineNumber
    val lineCount = ((model.getLineCount() as? Number)?.toInt() ?: line).coerceAtLeast(1)
    if (line > lineCount) line = lineCount
    if (line < 1) line = 1

    while (line > 1) {
        val lineText = (model.getLineContent(line) as? String) ?: break
        if (!isStructuralClosingLine(lineText)) break
        line--
    }
    return line
}

private fun sameEditor(a: dynamic, b: dynamic): Boolean {
    return weakHash(a) == weakHash(b)
}

private fun findReusableOutput(editor: dynamic, belowLine: Int, extraClass: String, allowNearby: Boolean = true): dynamic {
    val candidates = knownOutputs.filter { output ->
        val outputEditor = output.editor ?: return@filter false
        sameEditor(outputEditor, editor) && (output.kind as? String) == extraClass
    }

    val exact = candidates
        .filter { (it.line as? Number)?.toInt() == belowLine }
        .maxByOrNull { (it.insertionOrder as? Number)?.toInt() ?: -1 }
    if (exact != null) return exact
    if (!allowNearby) return null

    return candidates
        .filter {
            val line = (it.line as? Number)?.toInt() ?: return@filter false
            kotlin.math.abs(line - belowLine) <= 1
        }
        .sortedWith(
            compareBy<dynamic> {
                kotlin.math.abs(((it.line as? Number)?.toInt() ?: Int.MAX_VALUE) - belowLine)
            }.thenByDescending {
                (it.insertionOrder as? Number)?.toInt() ?: -1
            }
        )
        .firstOrNull()
}

private fun isAppendableRichHtml(text: String): Boolean {
    return text.contains("afield-appendable-output")
}

private fun findKnownOutputForElement(element: HTMLElement?): dynamic {
    val outputRoot = element?.closest(".execution_output") as? HTMLElement ?: return null
    return knownOutputs.firstOrNull { output ->
        (output.domNode as? HTMLElement) == outputRoot
    }
}

private fun scheduleKnownOutputRecompute(element: HTMLElement?) {
    val output = findKnownOutputForElement(element) ?: return
    val editor = output.editor ?: js("window").globalMonacoEditor ?: return
    scheduleOutputHeightRecompute(editor, output)
}

private fun flash(node: dynamic) {
    node.style.transition = "background 0s"
    node.style.background = "rgba(255,255,255,0.4)"
    debounceTimeout("blink" + weakHash(node), 20) {
        node.style.transition = "background 0.3s"
        node.style.background = "rgba(0,0,0,1)"
    }
}

private fun pulseOutput(node: dynamic) {
    val element = node as? HTMLElement
    if (element != null) {
        val inErrorOutput = element.parentElement?.classList?.contains("error") == true
        if (inErrorOutput) {
            element.classList.remove("error-pulse")
            window.requestAnimationFrame {
                element.classList.add("error-pulse")
                window.setTimeout({
                    element.classList.remove("error-pulse")
                }, 500)
            }
        }
        element.classList.remove("flash-inverted")
        window.requestAnimationFrame {
            element.classList.add("flash-inverted")
        }
    }
    flash(node)
}

private fun updateOutputFlashSignature(output: dynamic, nextSignature: String): Boolean {
    val previous = output.flashSignature as? String
    output.flashSignature = nextSignature
    return previous != null && previous == nextSignature
}

private fun newRange(startLine: Int, startCol: Int, endLine: Int, endCol: Int): dynamic {
    val w = js("window")
    val ctor = w.globalMonaco.Range
    return js("Reflect.construct")(ctor, arrayOf(startLine, startCol, endLine, endCol))
}

private fun getMonacoLineHeightPx(editor: dynamic): Double {
    val fallback = 20.0
    return try {
        val w = js("window")
        val monaco = w.globalMonaco
        val lineHeightOption = monaco?.editor?.EditorOption?.lineHeight
        val value = if (lineHeightOption != null) editor.getOption(lineHeightOption) else null
        val lineHeight = (value as? Number)?.toDouble()
        if (lineHeight != null && lineHeight > 0.0) lineHeight else fallback
    } catch (_: dynamic) {
        fallback
    }
}

private fun preferredOutputLines(editor: dynamic, domNode: HTMLElement?, domNodeInner: HTMLElement?): Int {
    val innerScrollHeight = (domNodeInner?.scrollHeight as? Number)?.toDouble() ?: 0.0
    val outerScrollHeight = (domNode?.scrollHeight as? Number)?.toDouble() ?: 0.0
    val renderedChildExtent = domNodeInner?.let { inner ->
        val innerRect = inner.getBoundingClientRect()
        (0 until inner.children.length).mapNotNull { index ->
            val child = inner.children.item(index) ?: return@mapNotNull null
            val rect = runCatching {
                child.asDynamic().getBoundingClientRect?.call(child)
            }.getOrNull() ?: return@mapNotNull null
            val bottom = (rect.bottom as? Number)?.toDouble() ?: return@mapNotNull null
            bottom - innerRect.top
        }.maxOrNull() ?: 0.0
    } ?: 0.0
    // Prefer actual content height (inner). Using outer zone height can lock outputs
    // into an oversized state after an initial over-estimate.
    val measuredHeight = max(
        0.0,
        max(
            renderedChildExtent,
            if (innerScrollHeight > 0.0) innerScrollHeight else outerScrollHeight
        )
    )
    if (measuredHeight <= 0.0) return 2

    val lineHeight = getMonacoLineHeightPx(editor)
    val lines = max(2.0, ceil((measuredHeight + outputHeightFudgePx) / lineHeight)).toInt()
    val maxLines = max(2.0, floor(outputMaxHeightPx / lineHeight)).toInt()
    return min(lines, maxLines)
}

private fun updateOutputControlVisibility(output: dynamic) {
    val zoneControl = output.zone?.marginDomNode as? HTMLElement
    val domNodeControl = (output.domNodeControl as? HTMLElement) ?: zoneControl ?: return
    output.domNodeControl = domNodeControl

    val id = output.id?.toString() ?: return
    val small = (output.small as? Number)?.toInt() ?: 0
    val currentHeightPx = (output.zone?.heightInPx as? Number)?.toInt() ?: (small * 19 + 18)
    val isExpanded = currentHeightPx > small * 19 + 18

    val domNode = output.domNode as? HTMLElement
    val scrollHeight = (domNode?.scrollHeight as? Number)?.toDouble() ?: 0.0
    val clientHeight = (domNode?.clientHeight as? Number)?.toDouble() ?: 0.0
    val overflowPx = max(0.0, scrollHeight - clientHeight)

    val hasMeaningfulOverflow = overflowPx > 2.0
    val showToggle = isExpanded || hasMeaningfulOverflow
    val toggle = if (showToggle) {
        "<div class='toggleBox hoverable' onclick='toggleArea(\"$id\")'>&varr;</div>"
    } else {
        ""
    }
    domNodeControl.innerHTML = "<div class='closeBox hoverable' onclick='removeArea(\"$id\")'>&#215;</div>$toggle"
}

private fun recomputeOutputHeight(editor: dynamic, output: dynamic) {
    val zone = output.zone ?: return
    val previousSmall = (output.small as? Number)?.toInt() ?: 2
    val currentHeightPx = (zone.heightInPx as? Number)?.toInt() ?: (previousSmall * 19 + 18)
    val userCollapsed = output.userCollapsed as? Boolean ?: false

    val preferredLines = preferredOutputLines(
        editor,
        output.domNode as? HTMLElement,
        output.domNodeInner as? HTMLElement
    )
    output.big = preferredLines
    output.small = min(2, preferredLines)

    val targetHeight = if (userCollapsed) output.small else output.big
    val targetHeightInt = (targetHeight as? Number)?.toInt() ?: output.small
    val targetHeightPx = (targetHeightInt as Number).toInt() * 19 + 18
    if (targetHeightPx == currentHeightPx) {
        updateOutputControlVisibility(output)
        return
    }

    editor.changeViewZones { changeAccessor: dynamic ->
        zone.heightInPx = targetHeightPx
        changeAccessor.layoutZone(output.id)
    }
    window.requestAnimationFrame {
        updateOutputControlVisibility(output)
    }
}

private fun scheduleOutputHeightRecompute(editor: dynamic, output: dynamic) {
    if (output.heightRecomputeQueued as? Boolean == true) return
    output.heightRecomputeQueued = true
    val firstTimeout = output.heightRecomputeFirstTimeout as? Int
    val secondTimeout = output.heightRecomputeSecondTimeout as? Int
    val thirdTimeout = output.heightRecomputeThirdTimeout as? Int
    if (firstTimeout != null) window.clearTimeout(firstTimeout)
    if (secondTimeout != null) window.clearTimeout(secondTimeout)
    if (thirdTimeout != null) window.clearTimeout(thirdTimeout)
    window.requestAnimationFrame {
        output.heightRecomputeQueued = false
        recomputeOutputHeight(editor, output)
        output.heightRecomputeFirstTimeout = window.setTimeout({
            recomputeOutputHeight(editor, output)
        }, 35)
        output.heightRecomputeSecondTimeout = window.setTimeout({
            recomputeOutputHeight(editor, output)
        }, 150)
        output.heightRecomputeThirdTimeout = window.setTimeout({
            recomputeOutputHeight(editor, output)
        }, 350)
    }
}

private fun scrollOutputToBottom(domNode: HTMLElement?, domNodeInner: HTMLElement?, tailPx: Double) {
    if (domNode == null) return
    val innerHeight = (domNodeInner?.scrollHeight as? Number)?.toDouble() ?: 0.0
    val outerHeight = (domNode.scrollHeight as? Number)?.toDouble() ?: 0.0
    val clientHeight = (domNode.clientHeight as? Number)?.toDouble() ?: 0.0
    val contentHeight = max(innerHeight, outerHeight)
    if (contentHeight <= 0.0 || clientHeight <= 0.0) return

    val maxScrollTop = max(0.0, outerHeight - clientHeight)
    val target = if (maxScrollTop <= 4.0) {
        maxScrollTop
    } else {
        (contentHeight - tailPx).coerceIn(0.0, maxScrollTop)
    }
    domNode.scrollTo(0.0, target)
}

private fun applyScrollTailToKnownOutputs() {
    knownOutputs.forEach { output ->
        if (output.autoScroll as? Boolean == false) return@forEach
        scrollOutputToBottom(
            output.domNode as? HTMLElement,
            output.domNodeInner as? HTMLElement,
            outputScrollTailExistingPx
        )
    }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun setOutputScrollTail(existingPx: Double, newPx: Double) {
    val existingSafe = if (existingPx.isFinite()) existingPx else outputScrollTailExistingPx
    val newSafe = if (newPx.isFinite()) newPx else outputScrollTailNewPx
    outputScrollTailExistingPx = max(0.0, existingSafe)
    outputScrollTailNewPx = max(0.0, newSafe)
    applyScrollTailToKnownOutputs()
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun nudgeOutputScrollTail(deltaPx: Double) {
    if (!deltaPx.isFinite()) return
    outputScrollTailExistingPx = max(0.0, outputScrollTailExistingPx + deltaPx)
    outputScrollTailNewPx = max(0.0, outputScrollTailNewPx + deltaPx)
    applyScrollTailToKnownOutputs()
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun getOutputScrollTail(): String {
    return "existing=$outputScrollTailExistingPx new=$outputScrollTailNewPx"
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun setOutputMaxHeight(px: Double) {
    if (!px.isFinite()) return
    outputMaxHeightPx = max(120.0, px)
    val w = js("window")
    val globalEditor = w.globalMonacoEditor
    knownOutputs.forEach { output ->
        val editor = output.editor ?: globalEditor
        if (editor != null) {
            recomputeOutputHeight(editor, output)
        }
    }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun getOutputMaxHeight(): Double {
    return outputMaxHeightPx
}

private fun clearOutputErrorMarkerDecoration(editor: dynamic, output: dynamic) {
    output.errorMarkerOriginalText = null
    val model = editor.getModel() ?: return
    val id = output.errorMarkerDecorationId as? String ?: run {
        output.errorMarkerDecorationId = null
        return
    }
    model.deltaDecorations(arrayOf(id), emptyArray<Any>())
    output.errorMarkerDecorationId = null
}

private fun resetOutputErrorMarkerState(editor: dynamic, output: dynamic) {
    output.errorMarkerSuppressed = null
    output.errorMarkerSuppressedColumn = null
    output.errorMarkerBackendColumn = null
    clearOutputErrorMarkerDecoration(editor, output)
}

private fun clearEditorDecorations(editor: dynamic) {
    val decorations = perEditorDecorations.remove(weakHash(editor)) ?: return
    decorations.contents.clear()
    decorations.collection?.clear()
}

fun clearStackTraceHints(editor: dynamic) {
    val collection = stackTraceHintCollections.remove(weakHash(editor)) ?: return
    collection.clear()
}

private fun clearAllStackTraceHints() {
    stackTraceHintCollections.values.forEach { it.clear() }
    stackTraceHintCollections.clear()
}

private fun clearOutputsForEditor(editor: dynamic, clearInlineDecorations: Boolean = false) {
    val outputs = knownOutputsForEditor(editor)
    if (outputs.isNotEmpty()) {
        outputs.forEach { output ->
            resetOutputErrorMarkerState(editor, output)
        }
        editor.changeViewZones { changeAccessor: dynamic ->
            outputs.forEach { output ->
                changeAccessor.removeZone(output.id)
            }
        }
        outputs.forEach { output ->
            val domNode = output.domNode as? HTMLElement
            domNode?.parentElement?.removeChild(domNode)
        }
        knownOutputs.removeAll(outputs.toSet())
    }
    if (clearInlineDecorations) {
        clearEditorDecorations(editor)
    }
    clearStackTraceHints(editor)
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun clearEditorOutputsForBox(uid: String) {
    if (uid.isBlank()) return
    val w = js("window")
    val seenEditors = mutableSetOf<Double>()

    fun clearOnce(editor: dynamic) {
        if (editor == null || editor == undefined) return
        val key = weakHash(editor)
        if (!seenEditors.add(key)) return
        clearOutputsForEditor(editor, clearInlineDecorations = true)
    }

    val selected = w.currentSelectedCode
    val selectedUid = if (selected != null && (selected.length as? Number)?.toInt() == 1) {
        selected[0].uid as? String
    } else {
        null
    }
    if (selectedUid == uid) {
        clearOnce(w.globalMonacoEditor ?: w.globalEditor)
    }

    val registry = w.embeddedEditors
    if (registry != null && registry != undefined && registry.getAll != null && registry.getAll != undefined) {
        val entries = registry.getAll()
        val length = (entries.length as? Number)?.toInt() ?: 0
        for (i in 0 until length) {
            val entry = entries[i] ?: continue
            if ((entry.uid as? String) == uid) {
                clearOnce(entry.editor)
            }
        }
    }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun clearEditorOutputsForBoxBeforeLaunch(uid: String) {
    if (uid.isBlank()) return
    recentLocalLaunchOutputClears[uid] = window.performance.now()
    clearEditorOutputsForBox(uid)
}

fun clearEditorOutputsForBoxFromServer(uid: String) {
    if (uid.isBlank()) return
    val now = window.performance.now()
    val lastLocalClear = recentLocalLaunchOutputClears[uid]
    if (lastLocalClear != null && now - lastLocalClear < remoteClearSuppressionWindowMs) {
        return
    }
    recentLocalLaunchOutputClears.entries.removeAll { now - it.value > remoteClearSuppressionWindowMs }
    clearEditorOutputsForBox(uid)
}

fun setStackTraceHints(editor: dynamic, locations: List<OutputStackLocation>) {
    val model = editor.getModel() ?: return
    clearStackTraceHints(editor)
    if (locations.isEmpty()) return

    val lineCount = ((model.getLineCount() as? Number)?.toInt() ?: 1).coerceAtLeast(1)
    val decorations = locations
        .filter { it.line > 0 }
        .map { location ->
            val line = location.line.coerceIn(1, lineCount)
            val options = js("{}")
            options.linesDecorationsClassName = "kotlin-stack-marker"
            val hover = js("{}")
            hover.value = "Stack frame: `${location.property}:${location.line}`" +
                if (location.method.isNotBlank()) " in `${location.method}`" else ""
            options.hoverMessage = hover
            val entry = js("{}")
            entry.range = newRange(line, 1, line, 1)
            entry.options = options
            entry
        }
        .toTypedArray()
    if (decorations.isEmpty()) return

    val collection = editor.createDecorationsCollection(decorations)
    stackTraceHintCollections[weakHash(editor)] = collection
}

private fun setOutputErrorMarkerDecoration(
    editor: dynamic,
    output: dynamic,
    startLineInput: Int,
    startColumnInput: Int,
    _endLineInput: Int,
    _endColumnInput: Int
) {
    val model = editor.getModel() ?: return
    val lineCount = ((model.getLineCount() as? Number)?.toInt() ?: 1).coerceAtLeast(1)

    val suppressed = output.errorMarkerSuppressed as? Boolean ?: false
    val suppressedColumn = (output.errorMarkerSuppressedColumn as? Number)?.toInt()
    if (suppressed && suppressedColumn != null && suppressedColumn == startColumnInput) {
        clearOutputErrorMarkerDecoration(editor, output)
        return
    }

    if (startLineInput < 1 || startColumnInput < 1) {
        clearOutputErrorMarkerDecoration(editor, output)
        return
    }

    val startLine = startLineInput.coerceIn(1, lineCount)
    val startLineLength = (model.getLineLength(startLine) as? Number)?.toInt()?.coerceAtLeast(0) ?: 0
    if (startLineLength == 0) {
        clearOutputErrorMarkerDecoration(editor, output)
        return
    }

    val startColumn = startColumnInput.coerceIn(1, startLineLength)
    val endLine = startLine
    val endColumn = (startColumn + 1).coerceAtMost(startLineLength + 1)

    val options = js("{}")
    options.inlineClassName = "kotlin-error-marker"
    options.stickiness = 1

    val entry = js("{}")
    entry.range = newRange(startLine, startColumn, endLine, endColumn)
    entry.options = options

    val originalText = try {
        model.getValueInRange(entry.range) as? String
    } catch (_: dynamic) {
        null
    }
    if (originalText == null || originalText.isEmpty()) {
        clearOutputErrorMarkerDecoration(editor, output)
        return
    }

    val existingId = output.errorMarkerDecorationId as? String
    val oldIds = if (existingId != null) arrayOf(existingId) else emptyArray<String>()
    val newIds = model.deltaDecorations(oldIds, arrayOf(entry)) as Array<String>
    output.errorMarkerDecorationId = newIds.firstOrNull()
    output.errorMarkerOriginalText = originalText
    output.errorMarkerBackendColumn = startColumnInput
    output.errorMarkerSuppressed = false
    output.errorMarkerSuppressedColumn = null
}

private fun pruneFragileErrorMarkers(editor: dynamic) {
    if (knownOutputs.isEmpty()) return
    val model = editor.getModel() ?: return

    knownOutputs.forEach { output ->
        val id = output.errorMarkerDecorationId as? String ?: return@forEach
        val original = output.errorMarkerOriginalText as? String ?: return@forEach
        val range = model.getDecorationRange(id)
        if (range == null) {
            clearOutputErrorMarkerDecoration(editor, output)
            return@forEach
        }
        val now = try {
            model.getValueInRange(range) as? String
        } catch (_: dynamic) {
            null
        }
        if (now == null || now != original) {
            output.errorMarkerSuppressed = true
            output.errorMarkerSuppressedColumn = output.errorMarkerBackendColumn
            clearOutputErrorMarkerDecoration(editor, output)
        }
    }
}

private var fragileErrorMarkerPruneInstalled = false

fun installFragileErrorMarkerPruning(editor: dynamic) {
    if (fragileErrorMarkerPruneInstalled) return
    fragileErrorMarkerPruneInstalled = true
    editor.onDidChangeModelContent { _: dynamic ->
        debounceTimeout("prune-error-markers", 25) {
            pruneFragileErrorMarkers(editor)
        }
    }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun clear(editor: dynamic) {
    // Group outputs by owning editor so we remove zones from the correct editor
    val byEditor = mutableMapOf<dynamic, MutableList<dynamic>>()
    for (output in knownOutputs) {
        val owningEditor = output.editor ?: editor
        byEditor.getOrPut(owningEditor) { mutableListOf() }.add(output)
    }
    for ((ed, outputs) in byEditor) {
        for (output in outputs) {
            resetOutputErrorMarkerState(ed, output)
        }
        ed.changeViewZones { changeAccessor: dynamic ->
            for (output in outputs) {
                changeAccessor.removeZone(output.id)
            }
        }
        for (output in outputs) {
            val domNode = output.domNode as? HTMLElement
            domNode?.parentElement?.removeChild(domNode)
        }
    }
    knownOutputs.clear()

    for (ed in perEditorDecorations.values) {
        ed.collection?.clear()
    }
    perEditorDecorations.clear()
    clearAllStackTraceHints()
    if (executionsCollection != null) executionsCollection.clear()
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun clearErrors(editor: dynamic) {
    clearAllStackTraceHints()
    val toRemove = mutableListOf<dynamic>()
    for (output in knownOutputs) {
        if ((output.kind as? String) == "error") {
            resetOutputErrorMarkerState(editor, output)
            editor.changeViewZones { changeAccessor: dynamic ->
                changeAccessor.removeZone(output.id)
            }
            val domNode = output.domNode as? HTMLElement
            domNode?.parentElement?.removeChild(domNode)
            toRemove.add(output)
        }
    }
    knownOutputs.removeAll(toRemove.toSet())
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun removeNewestOutput() {
    val editor = activeMonacoEditor() ?: return
    var bestOrder = -1
    var best: dynamic = null
    for (output in knownOutputsForEditor(editor)) {
        val insertionOrder = (output.insertionOrder as? Number)?.toInt() ?: -1
        if (insertionOrder > bestOrder) {
            bestOrder = insertionOrder
            best = output
        }
    }
    if (best != null) {
        resetOutputErrorMarkerState(editor, best)
        editor.changeViewZones { changeAccessor: dynamic ->
            changeAccessor.removeZone(best.id)
        }
        val domNode = best.domNode as? HTMLElement
        domNode?.parentElement?.removeChild(domNode)
        knownOutputs.remove(best)
    }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun clearFocusedEditorOutputs() {
    val editor = activeMonacoEditor() ?: return
    clearOutputsForEditor(editor, clearInlineDecorations = true)
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun removeArea(id: String) {
    val found = knownOutputs.firstOrNull { output ->
        output.id?.toString() == id
    } ?: return
    val w = js("window")
    val editor = found.editor ?: w.globalMonacoEditor
    resetOutputErrorMarkerState(editor, found)
    editor.changeViewZones { changeAccessor: dynamic ->
        changeAccessor.removeZone(id)
    }
    knownOutputs.remove(found)
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun toggleArea(id: String) {
    val found = knownOutputs.firstOrNull { output ->
        output.id?.toString() == id
    } ?: return
    val w = js("window")
    val editor = found.editor ?: w.globalMonacoEditor
    val zone = found.zone ?: return
    val wasCollapsed = (zone.heightInPx as? Number)?.toInt() == ((found.small as? Number)?.toInt() ?: 2) * 19 + 18

    val domNode = found.domNode as? HTMLElement
    val domNodeInner = found.domNodeInner as? HTMLElement
    val preferredLines = preferredOutputLines(editor, domNode, domNodeInner)
    found.big = preferredLines
    found.small = min(2, preferredLines)
    found.userCollapsed = !wasCollapsed

    editor.changeViewZones { changeAccessor: dynamic ->
        zone.heightInPx = (if (wasCollapsed) (found.big as? Number)?.toInt() ?: 2 else (found.small as? Number)?.toInt() ?: 2) * 19 + 18
        changeAccessor.layoutZone(id)
        window.setTimeout({
            val domNode = found.domNode as? HTMLElement ?: return@setTimeout
            domNode.scrollTo(js("{ left: 0, top: 1000, behavior: 'smooth' }"))
        }, 100)
    }
    window.requestAnimationFrame {
        updateOutputControlVisibility(found)
    }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun afieldToggleLazyOutput(buttonLike: dynamic) {
    val button = buttonLike as? HTMLElement ?: return
    val wrapper = button.parentElement as? HTMLElement ?: return
    val panel = wrapper.querySelector(".afield-lazy-output-panel") as? HTMLElement ?: return
    val ds = button.dataset.asDynamic()
    val originalLabel = (ds.afieldBrowseLabel as? String)?.ifBlank { null } ?: button.textContent ?: "…"
    val isOpen = (ds.afieldBrowseOpen as? String) == "1"

    if (isOpen) {
        panel.style.display = "none"
        panel.classList.remove("is-open")
        ds.afieldBrowseOpen = "0"
        button.textContent = originalLabel
        scheduleKnownOutputRecompute(wrapper)
        return
    }

    if ((ds.afieldBrowseLoaded as? String) != "1") {
        val src = ds.afieldBrowseSrc as? String ?: return
        val iframeHeight = (panel.dataset.asDynamic().afieldBrowseHeight as? String)?.toIntOrNull() ?: 264

        val header = document.createElement("div") as HTMLElement
        header.classList.add("afield-lazy-output-header")

        val link = document.createElement("a") as HTMLElement
        link.setAttribute("href", src)
        link.setAttribute("target", "_blank")
        link.setAttribute("rel", "noopener noreferrer")
        link.textContent = "open inspector"
        link.classList.add("afield-lazy-output-link")
        header.appendChild(link)

        val iframe = document.createElement("iframe") as HTMLElement
        iframe.setAttribute("src", src)
        iframe.setAttribute("loading", "lazy")
        iframe.setAttribute("data-afield-autosize", "off")
        iframe.classList.add("afield-lazy-output-frame")
        iframe.style.height = "${iframeHeight}px"
        iframe.addEventListener("load", { _: dynamic ->
            scheduleKnownOutputRecompute(wrapper)
        })

        panel.classList.add("is-open")
        panel.appendChild(header)
        panel.appendChild(iframe)
        ds.afieldBrowseLoaded = "1"
    } else {
        panel.classList.add("is-open")
        panel.style.display = "block"
    }

    ds.afieldBrowseOpen = "1"
    button.textContent = "hide"
    scheduleKnownOutputRecompute(wrapper)
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun afieldExpandLazyValue(linkLike: dynamic) {
    val link = linkLike as? HTMLElement ?: return
    val src = link.getAttribute("data-afield-lazy-src")?.takeIf { it.isNotBlank() } ?: return
    val wrapper = link.parentElement as? HTMLElement ?: return
    val parent = wrapper.parentElement as? HTMLElement
    val ds = link.dataset.asDynamic()
    if ((ds.afieldLazyLoading as? String) == "1") return

    val originalHtml = wrapper.outerHTML
    ds.afieldLazyLoading = "1"
    link.textContent = "loading…"

    window.fetch(src).then({ response: dynamic ->
        val ok = response.ok as? Boolean ?: false
        if (!ok) {
            val status = response.status?.toString() ?: "?"
            js("throw new Error('lazy value request failed: ' + status)")
        }
        response.text()
    }).then({ html: dynamic ->
        wrapper.outerHTML = html as String
        scheduleKnownOutputRecompute(parent)
        null
    }).catch { err: dynamic ->
        console.error("[lazy-value] expand failed", err)
        wrapper.outerHTML = originalHtml
        scheduleKnownOutputRecompute(parent)
        null
    }
}

private fun upsertLazyValueViewer(boxUid: String, title: String, src: String) {
    val box = findBoxByUid(boxUid) ?: run {
        console.warn("[lazy-value] box not found for viewer: $boxUid")
        return
    }

    val outputArea = box.getElementsByClassName("outputArea").item(0) as? HTMLElement ?: return
    outputArea.style.visibility = "visible"
    outputArea.style.height = "auto"

    val contain = outputArea.querySelector(".disclosure-group") ?: return
    val factory = js("window.disclosureInterface") ?: run {
        console.info("[lazy-value] disclosureInterface not ready yet; skipping viewer")
        return
    }
    val inter = factory(contain)
    val normalized = title.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "value" }
    val channelId = "__lazy_view__$normalized"
    val contentRoot = (inter.upsertDisclosure as (String, String, String) -> Element).invoke(channelId, title, "") as HTMLElement
    contentRoot.innerHTML = """
        <div class="value-viz-output-viewer">
            <iframe data-afield-autosize="off" src="${src}" loading="lazy"
                class="value-viz-output-viewer-frame"></iframe>
        </div>
    """.trimIndent()
    (inter.open as (String) -> Unit).invoke(channelId)
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun afieldOpenLazyValueInOutputArea(linkLike: dynamic) {
    val link = linkLike as? HTMLElement ?: return
    val boxUid = link.getAttribute("data-afield-box-uid")?.takeIf { it.isNotBlank() } ?: return
    val src = link.getAttribute("data-afield-view-src")?.takeIf { it.isNotBlank() } ?: return
    val title = link.getAttribute("data-afield-view-title")?.takeIf { it.isNotBlank() } ?: "Columns"
    upsertLazyValueViewer(boxUid, title, src)
}

@Suppress("unused")
private val installAfieldToggleLazyOutputOnWindow = run {
    window.asDynamic().afieldToggleLazyOutput = ::afieldToggleLazyOutput
    window.asDynamic().afieldExpandLazyValue = ::afieldExpandLazyValue
    window.asDynamic().afieldOpenLazyValueInOutputArea = ::afieldOpenLazyValueInOutputArea
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun addInlineOutput(
    editor: dynamic,
    lineNumber: Int,
    text: String,
    extraClass: String,
    frameMarker: Int,
    shouldFlash: Boolean = true
) {
    val edDecor = getOrCreateEditorDecorations(editor)
    var line = lineNumber
    val model = editor.getModel()
    val lineCount = (model.getLineCount() as Number).toInt()
    if (line >= lineCount) line = lineCount
    if (line == 0) line = 1
    while (line > 1 && (model.getLineLength(line) as Number).toInt() == 0) {
        line--
    }
    val lineLength = (model.getLineLength(line) as Number).toInt()

    for (i in edDecor.contents.size - 1 downTo 0) {
        val entry = edDecor.contents[i]
        val entryFrame = (entry.frameMarker as? Number)?.toInt() ?: 0
        if (entryFrame < frameMarker) {
            edDecor.contents.removeAt(i)
            continue
        }
        if ((entry._line as? Number)?.toInt() == line) {
            edDecor.contents.removeAt(i)
        }
    }

    val options = js("{}")
    val after = js("{}")
    val inlineOutputSpacer = "\u00A0\u00A0"
    after.content = if (text.isEmpty()) text else inlineOutputSpacer + text
    val flashClass = if (shouldFlash) " flash-inverted" else ""
    after.inlineClassName =
        "tick_text$flashClass $extraClass q${js("Math.random()") as Double}"
    after.inlineClassNameAffectsLetterSpacing = true
    after.cursorStops = 1
    options.after = after
    options.stickiness = 0
    options.blockPadding = js("{ left: 100 }")

    val entry = js("{}")
    entry.range = newRange(line, lineLength, line, lineLength + 1)
    entry.options = options
    entry._line = line
    entry._text = text
    entry._extraClass = extraClass
    entry.isTick = true
    entry.frameMarker = frameMarker
    edDecor.contents.add(entry)
    edDecor.collection.set(edDecor.contents.toTypedArray())
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun addExecutionMarker(editor: dynamic, startLine: Int, endLine: Int): String {
    return addExecutionMarker(editor, startLine, endLine, markerKey = null)
}

private fun addExecutionMarker(editor: dynamic, startLine: Int, endLine: Int, markerKey: String?): String {
    if (executionsCollection == null) {
        executionsCollection = editor.createDecorationsCollection(emptyArray<Any>())
    }

    val n = (window.performance.now() as Number).toInt()
    val className = "execution-marker-$n"

    val sheet = document.styleSheets.asDynamic()[0]
    val rule = ".$className { background: black; width: 5px !important; margin-left:${executionMarkerGutterInsetPx}px;}"
    sheet.insertRule(rule, sheet.cssRules.length)

    val range = newRange(startLine, 1, endLine, 1)
    val found = mutableListOf<Int>()
    executionDecorations.forEachIndexed { index, _ ->
        val rangeNow = executionsCollection.getRange(index) ?: return@forEachIndexed
        if (range.intersectRanges(rangeNow) as? Boolean == true) {
            found.add(0, index)
        }
    }
    for (idx in found) {
        executionDecorations.removeAt(idx)
        executionsCollection.set(executionDecorations.toTypedArray())
    }

    val options = js("{}")
    options.isWholeLine = true
    options.linesDecorationsClassName = "$className ${executionMarkerStateClass("launch")}"

    val decoration = js("{}")
    decoration.range = range
    decoration.options = options
    decoration._sourceClassname = markerKey ?: className
    decoration._visualClassname = className

    executionsCollection.append(arrayOf(decoration))
    executionDecorations.add(decoration)
    return className
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun ensureExecutionMarkerForLaunch(editor: dynamic, markerKey: String, startLine: Int, endLine: Int): Boolean {
    val key = markerKey.ifBlank { "default" }
    if (executionsCollection != null) {
        executionDecorations.forEachIndexed { index, decor ->
            if (decor._sourceClassname == key && executionsCollection.getRange(index) != null) return true
        }
    }
    addExecutionMarker(editor, startLine, endLine, markerKey = key)
    return true
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun setStatusForExecutionMarker(className: String, running: Boolean, mode: String): Boolean {
    val collection = executionsCollection ?: return false
    var found = false
    val stale = mutableListOf<Int>()
    executionDecorations.forEachIndexed { index, decor ->
        if (decor._sourceClassname == className) {
            val range = collection.getRange(index)
            if (range == null) {
                stale.add(0, index)
                return@forEachIndexed
            }
            val visualClassName = decor._visualClassname as? String ?: className
            decor.options.linesDecorationsClassName =
                if (running) "$visualClassName ${executionMarkerStateClass(mode)}" else visualClassName
            decor.range = range
            found = true
        }
    }
    for (idx in stale) {
        executionDecorations.removeAt(idx)
    }
    if (found || stale.isNotEmpty()) {
        collection.set(executionDecorations.toTypedArray())
    }
    return found
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun clearAllDecorations() {
    for (ed in perEditorDecorations.values) {
        ed.collection?.set(emptyArray<Any>())
        ed.contents.clear()
    }
    perEditorDecorations.clear()
}

private fun looksLikeHTML(text: String): Boolean {
    val trimmed = text.trim()
    val regex = Regex("^(?:<\\?xml\\b[^>]*\\?>|<!DOCTYPE\\b[^>]*>|<!--[\\s\\S]*?-->|<[A-Za-z][-\\w:]*\\b(?:\\s[^>]*)?>)")
    return regex.containsMatchIn(trimmed)
}

private data class TypeParamsSegment(val text: String, val isTypeParams: Boolean)

private fun splitTypeParamsSegments(text: String): List<TypeParamsSegment>? {
    if (!text.contains('<') || !text.contains('>')) return null

    val segments = mutableListOf<TypeParamsSegment>()
    var plainStart = 0
    var groupStart = -1
    var depth = 0

    var i = 0
    while (i < text.length) {
        when (text[i]) {
            '<' -> {
                if (groupStart == -1) {
                    if (i > plainStart) {
                        segments.add(TypeParamsSegment(text.substring(plainStart, i), isTypeParams = false))
                    }
                    groupStart = i
                    depth = 1
                } else {
                    depth++
                }
            }

            '>' -> {
                if (groupStart != -1) {
                    depth--
                    if (depth <= 0) {
                        val endExclusive = i + 1
                        segments.add(TypeParamsSegment(text.substring(groupStart, endExclusive), isTypeParams = true))
                        plainStart = endExclusive
                        groupStart = -1
                        depth = 0
                    }
                }
            }
        }
        i++
    }

    if (groupStart != -1) return null
    if (plainStart < text.length) {
        segments.add(TypeParamsSegment(text.substring(plainStart), isTypeParams = false))
    }
    if (segments.none { it.isTypeParams }) return null
    return segments
}

private fun decorateTypeParamsInTextNode(textNode: Text) {
    val text = textNode.data
    val segments = splitTypeParamsSegments(text) ?: return

    val frag = document.createDocumentFragment()
    segments.forEach { seg ->
        if (seg.text.isEmpty()) return@forEach
        if (seg.isTypeParams) {
            val span = document.createElement("span") as HTMLElement
            span.classList.add("kotlin-type-params")
            span.textContent = seg.text
            frag.appendChild(span)
        } else {
            frag.appendChild(document.createTextNode(seg.text))
        }
    }

    textNode.parentNode?.replaceChild(frag, textNode)
}

private fun decorateTypeParamsRec(node: Node) {
    val children = node.childNodes
    if (children.length == 0) return

    val snapshot = mutableListOf<Node>()
    for (i in 0 until children.length) {
        val child = children.item(i) ?: continue
        snapshot.add(child)
    }

    snapshot.forEach { child ->
        when (child.nodeType.toInt()) {
            3 -> decorateTypeParamsInTextNode(child.unsafeCast<Text>())
            1 -> {
                val tag = (child as? Element)?.tagName?.lowercase()
                if (tag != "script" && tag != "style") {
                    decorateTypeParamsRec(child)
                }
            }
        }
    }
}

private fun decorateTypeParamsInHtml(html: String): String {
    if (!html.contains('&') && !html.contains('<')) return html
    val container = document.createElement("div") as HTMLElement
    container.innerHTML = html
    decorateTypeParamsRec(container)
    return container.innerHTML
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun chatKeyboardCallback(event: dynamic, id: Int, input: dynamic): Boolean {
    if (event.isComposing as? Boolean == true) return true
    val key = event.key as? String ?: ""
    if (key == "Enter" && event.shiftKey as? Boolean != true) {
        event.preventDefault()
        for (output in knownOutputs) {
            if ((output.addPromptBox_uid as? Number)?.toInt() == id) {
                removeArea(output.id.toString())
                val callback = output.acceptCallback
                if (callback != null) {
                    callback(input.value as String)
                }
            }
        }
        return false
    }
    if (key == "Escape") {
        event.preventDefault()
        for (output in knownOutputs) {
            if ((output.addPromptBox_uid as? Number)?.toInt() == id) {
                removeArea(output.id.toString())
            }
        }
        return false
    }
    return true
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun addPromptBox(editor: dynamic, belowLine: Int, acceptCallback: dynamic) {
    editor.changeViewZones { changeAccessor: dynamic ->
        val domNode = document.createElement("div") as HTMLElement
        addPromptBoxUid += 1

        domNode.innerHTML = """
            <textarea
              rows="1"
              placeholder=">"
              id="promptBox_$addPromptBoxUid"
              class="afield-prompt-box"
              oninput="this.style.height='auto';this.style.height=this.scrollHeight+'px';"
              onfocus="this.style.height='auto';this.style.height=this.scrollHeight+'px';"
              onkeydown="return window.chatKeyboardCallback(event, $addPromptBoxUid, this);"
            ></textarea>
        """.trimIndent()

        domNode.style.zIndex = "100"

        val zone = js("{}")
        zone.afterLineNumber = belowLine
        zone.heightInLines = 1
        zone.domNode = domNode

        val id = changeAccessor.addZone(zone)

        window.setTimeout({
            val steal = document.body?.querySelector("#promptBox_$addPromptBoxUid") as? HTMLElement
            steal?.focus()
        }, 100)

        val output = js("{}")
        output.id = id
        output.zone = zone
        output.big = 1
        output.small = 1
        output.line = belowLine
        output.domNode = domNode
        output.domNodeInner = null
        output.domNodeAnnotation = null
        output.insertionOrder = ++orderCounter
        output.kind = ""
        output.addPromptBox_uid = addPromptBoxUid
        output.acceptCallback = acceptCallback
        knownOutputs.add(output)
    }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun addOutput(
    editor: dynamic,
    text: String,
    belowLineInput: Int,
    extraClass: String,
    annotation: String?,
    frameMarker: Int,
    markerStartColumn: Int,
    markerEndLine: Int,
    markerEndColumn: Int
) {
    var textClean = text
    val isHTML = looksLikeHTML(text)
    val hasEmbeddedIframe = text.contains("<iframe", ignoreCase = true)
    val richHtml = isHTML || hasEmbeddedIframe
    val replaceRichHtml = richHtml && !isAppendableRichHtml(text)
    if (!richHtml) {
        textClean = textClean.replace("\n", "<br>")
    }
    if (extraClass == "error") {
        textClean = decorateTypeParamsInHtml(textClean)
    }

    var belowLine = belowLineInput
    if (belowLine == -1) {
        belowLine = (editor.getModel().getValue() as String).split("\n").size
    }
    if (!richHtml && extraClass != "error") {
        belowLine = normalizeInlineOutputTargetLine(editor, belowLine)
    }
    val canInline = !textClean.contains("<br>") && extraClass != "error" && !richHtml

    val edDecor = editorDecorationsFor(editor)
    var repeatedInlineOutput = false
    if (edDecor != null && edDecor.contents.isNotEmpty()) {
        var changed = false
        for (i in edDecor.contents.size - 1 downTo 0) {
            val entry = edDecor.contents[i]
            val entryFrame = (entry.frameMarker as? Number)?.toInt() ?: 0
            if (entryFrame < frameMarker) {
                edDecor.contents.removeAt(i)
                changed = true
                continue
            }
            if ((entry._line as? Number)?.toInt() == belowLine) {
                if (entry.isTick as? Boolean == true) {
                    val previousText = entry._text as? String ?: ""
                    val previousClass = entry._extraClass as? String ?: ""
                    repeatedInlineOutput =
                        repeatedInlineOutput || (previousText == textClean && previousClass == extraClass)
                }
                edDecor.contents.removeAt(i)
                changed = true
            }
        }
        if (changed) {
            edDecor.collection?.set(edDecor.contents.toTypedArray())
        }
    }

    val found = if (canInline) null else findReusableOutput(
        editor,
        belowLine,
        extraClass,
        allowNearby = !richHtml && extraClass != "error"
    )
    if (found != null) {
        found.autoScroll = !replaceRichHtml
        val shouldPulse = if (replaceRichHtml) {
            val repeatedContent = updateOutputFlashSignature(found, textClean)
            found.domNodeInner.innerHTML = textClean
            repeatedContent
        } else {
            found.flashSignature = null
            appendWithBadge(found.domNodeInner, textClean)
        }
        scheduleOutputHeightRecompute(editor, found)

        if (extraClass == "error") {
            setOutputErrorMarkerDecoration(editor, found, belowLine, markerStartColumn, markerEndLine, markerEndColumn)
        } else {
            resetOutputErrorMarkerState(editor, found)
        }

        val annotationNode = found.domNodeAnnotation as? HTMLElement
        if (annotationNode != null) {
            annotationNode.innerHTML = annotation ?: ""
        }

        val domNode = found.domNode as HTMLElement
        domNode.className = ""
        domNode.classList.add("execution_output")
        domNode.classList.add(extraClass)
        (found.domNodeInner as? HTMLElement)?.classList?.toggle("copyable-error-output", extraClass == "error")
        domNode.style.asDynamic().overflow = if (replaceRichHtml) "hidden" else "scroll"
        domNode.style.zIndex = "11"
        if (shouldPulse) {
            pulseOutput(found.domNodeInner)
        }
        if (found.autoScroll as? Boolean != false) {
            window.setTimeout({
                scrollOutputToBottom(domNode, found.domNodeInner as? HTMLElement, outputScrollTailExistingPx)
            }, 10)
        }
        debounceTimeout("align", 50) {
            clearTabSpans()
            var n = 0
            while (n++ < 10 && alignSpansWithinXPixels(150.0)) {
            }
        }
        return
    }

    var extraText = ""
    var allowInline = true
    val decorationMatch = edDecor?.contents?.firstOrNull { entry ->
        (entry._line as? Number)?.toInt() == belowLine
    }
    if (decorationMatch != null) {
        if (decorationMatch.isTick as? Boolean == true) {
            edDecor.contents.remove(decorationMatch)
            edDecor.collection?.set(edDecor.contents.toTypedArray())
            allowInline = true
        } else {
            extraText += decorationMatch._text as? String ?: ""
            edDecor.contents.remove(decorationMatch)
            edDecor.collection?.set(edDecor.contents.toTypedArray())
            allowInline = false
        }
    } else {
        allowInline = true
    }

    if (allowInline && canInline) {
        addInlineOutput(editor, belowLine, textClean, extraClass, frameMarker, shouldFlash = repeatedInlineOutput)
    } else {
        editor.changeViewZones { changeAccessor: dynamic ->
            val domNode = document.createElement("div") as HTMLElement
            domNode.style.asDynamic().overflow = if (replaceRichHtml) "hidden" else "scroll"
            domNode.classList.add(extraClass)
            domNode.classList.add("execution_output")

            val domNodeAnnotation = document.createElement("div") as HTMLElement
            val domNodeInner = document.createElement("div") as HTMLElement
            domNode.appendChild(domNodeInner)
            if (replaceRichHtml) {
                domNodeInner.innerHTML = textClean
            } else {
                appendWithBadge(domNodeInner, textClean)
            }

            domNode.appendChild(domNodeAnnotation)
            domNodeAnnotation.innerHTML = annotation ?: ""
            domNodeAnnotation.classList.add("output_annotation")

            domNodeInner.onmousedown = { _: dynamic -> }
            domNodeInner.classList.add("inner_output")
            if (extraClass == "error") {
                domNodeInner.classList.add("copyable-error-output")
            }
            domNodeInner.style.background = "rgba(0,0,0,1)"
            domNodeInner.style.borderRadius = "8px"
            domNodeInner.style.width = "calc( 100% - 8px )"
            domNodeInner.style.padding = "6px 6px 4px 6px"
            domNodeInner.style.marginLeft = "3px"
            domNodeInner.style.fontSize = "10px"
            domNodeInner.style.asDynamic().overflowX = if (replaceRichHtml) "hidden" else "scroll"

            domNode.style.zIndex = "11"

            val domNodeControl = document.createElement("div") as HTMLElement
            domNodeControl.style.borderLeft = "4px solid rgba(255,255,255,0.23)"
            domNodeControl.style.borderTop = "4px solid rgba(255,255,255,0.23)"
            domNodeControl.style.borderBottom = "4px solid rgba(255,255,255,0.23)"
            domNodeControl.style.marginLeft = "2px"
            domNodeControl.style.opacity = "0.5"
            domNodeControl.style.zIndex = "1000"
            domNodeControl.style.borderRadius = "4px"
            domNodeControl.style.paddingTop = "4px"
            domNodeControl.style.paddingLeft = "2px"

            domNodeControl.style.asDynamic().pointerEvents = "auto"
            domNodeControl.classList.add("notselectable")

            domNode.onwheel = { wheel: dynamic ->
                domNode.scrollBy(js("{ top: wheel.deltaY }"))
                wheel.preventDefault()
                wheel.stopPropagation()
                wheel.stopImmediatePropagation()
            }

            var lines = if (replaceRichHtml) {
                2
            } else {
                min(5, textClean.split("\n").size) + 1
            }
            lines = max(2, lines)

            if (!replaceRichHtml) {
                window.setTimeout({
                    scrollOutputToBottom(domNode, domNodeInner, outputScrollTailNewPx)
                }, 100)
            }

            val zone = js("{}")
            zone.afterLineNumber = belowLine
            zone.heightInPx = lines * 19 + 18
            zone.domNode = domNode
            zone.marginDomNode = domNodeControl

            if (extraClass != "error") {
                for (i in 0 until domNodeInner.children.length) {
                    val child = domNodeInner.children.item(i)
                    child?.addEventListener("mousemove", { e: dynamic ->
                        e.stopPropagation()
                        e.stopImmediatePropagation()
                    })
                }
            }

            val id = changeAccessor.addZone(zone)

            val output = js("{}")
            output.id = id
            output.zone = zone
            output.big = lines
            output.small = min(2, lines)
            output.line = belowLine
            output.domNode = domNode
            output.domNodeInner = domNodeInner
            output.domNodeAnnotation = domNodeAnnotation
            output.domNodeControl = domNodeControl
            output.insertionOrder = ++orderCounter
            output.kind = extraClass
            output.flashSignature = if (replaceRichHtml) textClean else null
            output.autoScroll = !replaceRichHtml
            output.userCollapsed = false
            output.editor = editor
            updateOutputControlVisibility(output)
            knownOutputs.add(output)
            if (extraClass == "error") {
                setOutputErrorMarkerDecoration(editor, output, belowLine, markerStartColumn, markerEndLine, markerEndColumn)
            }
            scheduleOutputHeightRecompute(editor, output)
        }
    }

    debounceTimeout("align", 50) {
        clearTabSpans()
        var n = 0
        while (n++ < 10 && alignSpansWithinXPixels(150.0)) {
        }
    }
}

private fun getInsetClientRectByMargin(el: dynamic): dynamic {
    val rect = el.getBoundingClientRect()
    val style = window.getComputedStyle(el)
    val top = style.marginTop.toDoubleOrNull() ?: 0.0
    val right = style.marginRight.toDoubleOrNull() ?: 0.0
    val bottom = style.marginBottom.toDoubleOrNull() ?: 0.0
    val left = style.marginLeft.toDoubleOrNull() ?: 0.0

    val inset = js("{}")
    inset.top = rect.top + top
    inset.left = rect.left + left
    inset.right = rect.right - right
    inset.bottom = rect.bottom - bottom
    inset.width = inset.right - inset.left
    inset.height = inset.bottom - inset.top
    return inset
}

private fun clearTabSpans() {
    val spans = document.querySelectorAll("span.tab")
    for (i in 0 until spans.length) {
        val span = spans.item(i) as? HTMLElement ?: continue
        span.setAttribute("style", "")
    }
}

private fun alignSpansWithinXPixels(x: Double): Boolean {
    val spans = document.querySelectorAll("span.tab")
    val spanData = mutableListOf<dynamic>()
    for (i in 0 until spans.length) {
        val span = spans.item(i) as? HTMLElement ?: continue
        val rect = getInsetClientRectByMargin(span)
        val entry = js("{}")
        entry.span = span
        entry.left = rect.left
        entry.top = rect.top
        entry.height = rect.height
        entry.width = rect.width
        spanData.add(entry)
    }

    fun closestTop(items: List<dynamic>, check: dynamic): Double {
        if (items.isEmpty()) return 100.0
        var minDist = Double.POSITIVE_INFINITY
        for (item in items) {
            val dist = abs((item.top as Number).toDouble() - (check.top as Number).toDouble())
            if (dist < minDist) {
                minDist = dist
            }
        }
        return minDist
    }

    val groups = mutableListOf<dynamic>()
    for (item in spanData) {
        var closestGroup: dynamic = null
        var minDist = Double.POSITIVE_INFINITY
        for (group in groups) {
            val dist = abs((group.avgLeft as Number).toDouble() - (item.left as Number).toDouble())
            if (dist < minDist) {
                minDist = dist
                closestGroup = group
            }
        }

        val group = if (closestGroup != null &&
            minDist <= x &&
            closestTop(closestGroup.items as List<dynamic>, item) > 3.0
        ) {
            closestGroup
        } else {
            val newGroup = js("{}")
            newGroup.items = mutableListOf<dynamic>()
            newGroup.avgLeft = item.left
            groups.add(newGroup)
            newGroup
        }

        (group.items as MutableList<dynamic>).add(item)
        var maxLeft = 0.0
        for (entry in group.items as MutableList<dynamic>) {
            maxLeft = max(maxLeft, (entry.left as Number).toDouble())
        }
        group.avgLeft = maxLeft
    }

    var stop = false
    for (group in groups) {
        if (stop) break
        val items = group.items as MutableList<dynamic>
        var maxLeft = 0.0
        for (item in items) {
            maxLeft = max(maxLeft, (item.left as Number).toDouble())
        }
        if (items.size > 1) {
            for (item in items) {
                val span = item.span as HTMLElement
                val padding = maxLeft - (item.left as Number).toDouble()
                if (span.style.marginLeft.isNullOrEmpty()) {
                    span.style.marginLeft = "${padding}px"
                    stop = true
                } else if (span.style.left.endsWith("px")) {
                    val existing = span.style.marginLeft.replace("px", "").toDoubleOrNull() ?: 0.0
                    if (abs(existing - padding) > 2.0) {
                        span.style.marginLeft = "${padding}px"
                        stop = true
                    }
                }
            }
        }
    }
    return stop
}

private fun appendWithBadge(div: dynamic, msg: String): Boolean {
    val container = div as? HTMLElement ?: return false
    val message = trimLargeLogMessageStart(msg)
    val last = container.lastElementChild
    val isLastLogLine = last?.classList?.contains("log-line") == true

    if (isLastLogLine && (last as HTMLElement).dataset.asDynamic().msg == message) {
        val badge = last.querySelector(".badge") as HTMLElement
        val current = (badge.dataset.asDynamic().count as? String)?.toIntOrNull() ?: 1
        val next = current + 1
        badge.dataset.asDynamic().count = next.toString()
        badge.textContent = "×$next"
        badge.classList.add("show")
        trimOutputContainerStart(container)
        return true
    }

    val line = document.createElement("div") as HTMLElement
    line.className = "log-line"
    line.dataset.asDynamic().msg = message

    val text = document.createElement("div") as HTMLElement
    text.className = "log-msg"
    text.innerHTML = message

    val badge = document.createElement("span") as HTMLElement
    badge.className = "badge"
    badge.dataset.asDynamic().count = "1"

    line.appendChild(text)
    line.appendChild(badge)
    container.appendChild(line)
    trimOutputContainerStart(container)
    container.scrollTop = container.scrollHeight.toDouble()
    return false
}

private fun trimLargeLogMessageStart(message: String): String {
    val parts = htmlBreakRegex.split(message)
    if (parts.size <= maxOutputMessageBreaks) return message
    val keep = parts.takeLast(maxOutputMessageBreaks)
    val removed = parts.size - keep.size
    return "<span class='trimmed-output'>[trimmed $removed earlier lines]</span><br>" + keep.joinToString("<br>")
}

private fun ensureTrimMarker(container: HTMLElement): HTMLElement {
    val first = container.firstElementChild as? HTMLElement
    if (first?.classList?.contains("output-trim-marker") == true) return first

    val marker = document.createElement("div") as HTMLElement
    marker.className = "log-line output-trim-marker"
    val text = document.createElement("div") as HTMLElement
    text.className = "log-msg"
    marker.appendChild(text)
    container.insertBefore(marker, container.firstChild)
    return marker
}

private fun trimOutputContainerStart(container: HTMLElement) {
    var removedRows = 0
    while (container.children.length > maxRenderedOutputRows) {
        val first = container.firstElementChild as? HTMLElement ?: break
        if (first.classList.contains("output-trim-marker")) {
            val next = first.nextElementSibling ?: break
            container.removeChild(next)
        } else {
            container.removeChild(first)
        }
        removedRows++
    }

    var textLength = (container.textContent ?: "").length
    while (textLength > maxRenderedOutputChars && container.children.length > 1) {
        val first = container.firstElementChild as? HTMLElement ?: break
        val victim = if (first.classList.contains("output-trim-marker")) {
            first.nextElementSibling ?: break
        } else {
            first
        }
        textLength -= (victim.textContent ?: "").length
        container.removeChild(victim)
        removedRows++
    }

    if (removedRows > 0) {
        val marker = ensureTrimMarker(container)
        val previous = (marker.dataset.asDynamic().trimmedRows as? String)?.toIntOrNull() ?: 0
        val next = previous + removedRows
        marker.dataset.asDynamic().trimmedRows = next.toString()
        val text = marker.querySelector(".log-msg") as? HTMLElement
        text?.textContent = "[trimmed $next earlier output rows]"
    }
}
