package afield

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val knownOutputs = mutableListOf<dynamic>()
private var decorationsCollection: dynamic = null
private var decorationsCollectionContents = mutableListOf<dynamic>()
private var executionDecorations = mutableListOf<dynamic>()
private var executionsCollection: dynamic = null
private val debounceTimers = mutableMapOf<String, Int>()

private var orderCounter = 0
private var addPromptBoxUid = 0

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

private fun flash(node: dynamic) {
    node.style.transition = "background 0s"
    node.style.background = "rgba(255,255,255,0.4)"
    debounceTimeout("blink" + weakHash(node), 20) {
        node.style.transition = "background 0.3s"
        node.style.background = "rgba(0,0,0,1)"
    }
}

private fun newRange(startLine: Int, startCol: Int, endLine: Int, endCol: Int): dynamic {
    val w = js("window")
    val ctor = w.globalMonaco.Range
    return js("Reflect.construct")(ctor, arrayOf(startLine, startCol, endLine, endCol))
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun clear(editor: dynamic) {
    for (output in knownOutputs) {
        editor.changeViewZones { changeAccessor: dynamic ->
            changeAccessor.removeZone(output.id)
        }
        val domNode = output.domNode as? HTMLElement
        domNode?.parentElement?.removeChild(domNode)
    }
    knownOutputs.clear()

    if (decorationsCollection != null) decorationsCollection.clear()
    if (executionsCollection != null) executionsCollection.clear()
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun clearErrors(editor: dynamic) {
    val toRemove = mutableListOf<dynamic>()
    for (output in knownOutputs) {
        if ((output.kind as? String) == "error") {
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
    var bestOrder = -1
    var best: dynamic = null
    for (output in knownOutputs) {
        val insertionOrder = (output.insertionOrder as? Number)?.toInt() ?: -1
        if (insertionOrder > bestOrder) {
            bestOrder = insertionOrder
            best = output
        }
    }
    if (best != null) {
        val w = js("window")
        w.globalMonacoEditor.changeViewZones { changeAccessor: dynamic ->
            changeAccessor.removeZone(best.id)
        }
        knownOutputs.remove(best)
    }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun removeArea(id: String) {
    val found = knownOutputs.firstOrNull { output ->
        output.id?.toString() == id
    } ?: return
    val w = js("window")
    w.globalMonacoEditor.changeViewZones { changeAccessor: dynamic ->
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
    w.globalMonacoEditor.changeViewZones { changeAccessor: dynamic ->
        val zone = found.zone
        zone.heightInLines = if (zone.heightInLines == found.big) found.small else found.big
        changeAccessor.layoutZone(id)
        window.setTimeout({
            val domNode = found.domNode as? HTMLElement ?: return@setTimeout
            domNode.scrollTo(js("{ left: 0, top: 1000, behavior: 'smooth' }"))
        }, 100)
    }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun addInlineOutput(editor: dynamic, lineNumber: Int, text: String, extraClass: String, frameMarker: Int) {
    if (decorationsCollection == null) {
        decorationsCollection = editor.createDecorationsCollection(emptyArray<Any>())
        decorationsCollectionContents = mutableListOf()
    }
    var line = lineNumber
    val model = editor.getModel()
    val lineCount = (model.getLineCount() as Number).toInt()
    if (line >= lineCount) line = lineCount
    if (line == 0) line = 1
    while (line > 1 && (model.getLineLength(line) as Number).toInt() == 0) {
        line--
    }
    val lineLength = (model.getLineLength(line) as Number).toInt()

    for (i in decorationsCollectionContents.size - 1 downTo 0) {
        val entry = decorationsCollectionContents[i]
        val entryFrame = (entry.frameMarker as? Number)?.toInt() ?: 0
        if (entryFrame < frameMarker) {
            decorationsCollectionContents.removeAt(i)
            continue
        }
        if ((entry._line as? Number)?.toInt() == line) {
            decorationsCollectionContents.removeAt(i)
        }
    }

    val options = js("{}")
    val after = js("{}")
    after.content = text
    after.inlineClassName =
        "tick_text flash-inverted $extraClass q${js("Math.random()") as Double}"
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
    entry.isTick = true
    entry.frameMarker = frameMarker
    decorationsCollectionContents.add(entry)
    decorationsCollection.set(decorationsCollectionContents.toTypedArray())
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun addExecutionMarker(editor: dynamic, startLine: Int, endLine: Int): String {
    if (executionsCollection == null) {
        executionsCollection = editor.createDecorationsCollection(emptyArray<Any>())
    }

    val n = (window.performance.now() as Number).toInt()
    val className = "execution-marker-$n"

    val sheet = document.styleSheets.asDynamic()[0]
    val rule = ".$className { background: black; width: 5px !important; margin-left:-15px;}"
    sheet.insertRule(rule, sheet.cssRules.length)

    val range = newRange(startLine, 1, endLine, 1)
    val found = mutableListOf<Int>()
    executionDecorations.forEachIndexed { index, _ ->
        val rangeNow = executionsCollection.getRange(index)
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
    options.linesDecorationsClassName = "$className running"

    val decoration = js("{}")
    decoration.range = range
    decoration.options = options
    decoration._sourceClassname = className

    executionsCollection.append(arrayOf(decoration))
    executionDecorations.add(decoration)
    return className
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun setStatusForExecutionMarker(className: String, running: Boolean) {
    executionDecorations.forEachIndexed { index, decor ->
        if (decor._sourceClassname == className) {
            decor.options.linesDecorationsClassName =
                if (running) "$className running" else className
            decor.range = executionsCollection.getRange(index)
        }
    }
    executionsCollection?.set(executionDecorations.toTypedArray())
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun clearAllDecorations() {
    decorationsCollection?.set(emptyArray<Any>())
    decorationsCollectionContents.clear()
}

private fun looksLikeHTML(text: String): Boolean {
    val trimmed = text.trim()
    val regex = Regex("^(?:<\\?xml\\b[^>]*\\?>|<!DOCTYPE\\b[^>]*>|<!--[\\s\\S]*?-->|<[A-Za-z][-\\w:]*\\b(?:\\s[^>]*)?>)")
    return regex.containsMatchIn(trimmed)
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
              style="
                box-sizing: border-box;
                height: 30px;
                padding: 8px;
                margin: 3px;
                border: 1px solid rgba(255, 255, 255, 0.1);
                border-radius: 5px;
                resize: vertical;
                overflow: hidden;
                font-style: inherit;
                font-variant: inherit;
                font-weight: inherit;
                font-stretch: inherit;
                line-height: inherit;
                font-family: inherit;
                font-optical-sizing: inherit;
                font-size-adjust: inherit;
                font-kerning: inherit;
                font-feature-settings: inherit;
                font-variation-settings: inherit;
                font-size: 70%;
                font-style: italic;
                width: calc(100% - 20px);
                outline: none;
                color: white;
                background: rgba(0,0,0,0.7);
                  "
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
    frameMarker: Int
) {
    var textClean = text
    val isHTML = looksLikeHTML(text)
    if (!isHTML) {
        textClean = textClean.replace("\n", "<br>")
    }

    var belowLine = belowLineInput
    if (belowLine == -1) {
        belowLine = (editor.getModel().getValue() as String).split("\n").size
    }

    if (decorationsCollectionContents.isNotEmpty()) {
        var changed = false
        for (i in decorationsCollectionContents.size - 1 downTo 0) {
            val entry = decorationsCollectionContents[i]
            val entryFrame = (entry.frameMarker as? Number)?.toInt() ?: 0
            if (entryFrame < frameMarker) {
                decorationsCollectionContents.removeAt(i)
                changed = true
                continue
            }
            if ((entry._line as? Number)?.toInt() == belowLine) {
                decorationsCollectionContents.removeAt(i)
                changed = true
            }
        }
        if (changed) {
            decorationsCollection?.set(decorationsCollectionContents.toTypedArray())
        }
    }

    val found = knownOutputs.firstOrNull { output ->
        (output.line as? Number)?.toInt() == belowLine
    }
    if (found != null) {
        if (isHTML) {
            found.domNodeInner.innerHTML = textClean
        } else {
            appendWithBadge(found.domNodeInner, textClean)
            window.requestAnimationFrame {
                val rect = found.domNodeInner.getBoundingClientRect()
                val l = max(2.0, rect.height / 20.0)
                found.big = l.toInt()
                found.small = min(2, found.big)
            }
        }

        val domNode = found.domNode as HTMLElement
        domNode.className = ""
        domNode.classList.add("execution_output")
        domNode.classList.add(extraClass)
        domNode.style.zIndex = "11"
        if (!isHTML) {
            flash(found.domNodeInner)
            window.setTimeout({
                domNode.scrollTo(0.0, (found.domNodeInner as HTMLElement).scrollHeight - 35.0)
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
    val decorationMatch = decorationsCollectionContents.firstOrNull { entry ->
        (entry._line as? Number)?.toInt() == belowLine
    }
    if (decorationMatch != null) {
        if (decorationMatch.isTick as? Boolean == true) {
            decorationsCollectionContents.remove(decorationMatch)
            decorationsCollection?.set(decorationsCollectionContents.toTypedArray())
            allowInline = true
        } else {
            extraText += decorationMatch._text as? String ?: ""
            decorationsCollectionContents.remove(decorationMatch)
            decorationsCollection?.set(decorationsCollectionContents.toTypedArray())
            allowInline = false
        }
    } else {
        allowInline = true
    }

    if (allowInline &&
        !textClean.contains("<br>") &&
        extraClass != "error" &&
        !isHTML
    ) {
        addInlineOutput(editor, belowLine, textClean, extraClass, frameMarker)
    } else {
        editor.changeViewZones { changeAccessor: dynamic ->
            val domNode = document.createElement("div") as HTMLElement
            domNode.style.asDynamic().overflow = "scroll"
            domNode.classList.add(extraClass)
            domNode.classList.add("execution_output")

            val domNodeAnnotation = document.createElement("div") as HTMLElement
            val domNodeInner = document.createElement("div") as HTMLElement
            domNode.appendChild(domNodeInner)
            appendWithBadge(domNodeInner, textClean)

            domNode.appendChild(domNodeAnnotation)
            domNodeAnnotation.innerHTML = annotation ?: ""
            domNodeAnnotation.classList.add("output_annotation")

            domNodeInner.onmousedown = { _: dynamic -> }
            domNodeInner.classList.add("inner_output")
            domNodeInner.style.background = "rgba(0,0,0,1)"
            domNodeInner.style.borderRadius = "8px"
            domNodeInner.style.marginBottom = "8px"
            domNodeInner.style.width = "calc( 100% - 8px )"
            domNodeInner.style.padding = "4px"
            domNodeInner.style.marginTop = "3px"
            domNodeInner.style.marginLeft = "3px"
            domNodeInner.style.paddingLeft = "8px"
            domNodeInner.style.asDynamic().overflowX = "scroll"

            domNode.style.zIndex = "11"

            val domNodeControl = document.createElement("div") as HTMLElement
            domNodeControl.style.borderLeft = "5px solid rgba(255,255,255,0.3)"
            domNodeControl.style.marginLeft = "3px"
            domNodeControl.style.opacity = "0.5"
            domNodeControl.classList.add("notselectable")

            domNode.onwheel = { wheel: dynamic ->
                domNode.scrollBy(js("{ top: wheel.deltaY }"))
                wheel.preventDefault()
                wheel.stopPropagation()
                wheel.stopImmediatePropagation()
            }

            document.body?.appendChild(domNode)
            val rect = domNode.getBoundingClientRect()
            domNode.parentElement?.removeChild(domNode)

            var lines = min(5, textClean.split("\n").size) + 1
            lines = max(2, (rect.height / 20.0).toInt())

            window.setTimeout({
                domNode.scrollTo(0.0, domNodeInner.scrollHeight - 41.0)
            }, 100)
            flash(domNodeInner)

            val zone = js("{}")
            zone.afterLineNumber = belowLine
            zone.heightInLines = lines
            zone.domNode = domNode
            zone.marginDomNode = domNodeControl

            for (i in 0 until domNodeInner.children.length) {
                val child = domNodeInner.children.item(i)
                child?.addEventListener("mousemove", { e: dynamic ->
                    e.stopPropagation()
                    e.stopImmediatePropagation()
                })
            }

            val id = changeAccessor.addZone(zone)
            domNodeControl.innerHTML =
                "<div class='closeBox hoverable' onclick='removeArea(\"$id\")'>&#215;</div>" +
                    "<div class='toggleBox hoverable' onclick='toggleArea(\"$id\")'>&varr;</div>"

            val output = js("{}")
            output.id = id
            output.zone = zone
            output.big = lines
            output.small = min(2, lines)
            output.line = belowLine
            output.domNode = domNode
            output.domNodeInner = domNodeInner
            output.domNodeAnnotation = domNodeAnnotation
            output.insertionOrder = ++orderCounter
            output.kind = extraClass
            knownOutputs.add(output)
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
    val top = (style.marginTop as? String)?.toDoubleOrNull() ?: 0.0
    val right = (style.marginRight as? String)?.toDoubleOrNull() ?: 0.0
    val bottom = (style.marginBottom as? String)?.toDoubleOrNull() ?: 0.0
    val left = (style.marginLeft as? String)?.toDoubleOrNull() ?: 0.0

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

private fun appendWithBadge(div: dynamic, msg: String) {
    val container = div as? HTMLElement ?: return
    val message = msg
    val last = container.lastElementChild
    val isLastLogLine =
        last != null && last.classList != null && last.classList.contains("log-line")

    if (isLastLogLine && (last as HTMLElement).dataset.asDynamic().msg == message) {
        val badge = last.querySelector(".badge") as HTMLElement
        val current = (badge.dataset.asDynamic().count as? String)?.toIntOrNull() ?: 1
        val next = current + 1
        badge.dataset.asDynamic().count = next.toString()
        badge.textContent = "×$next"
        badge.classList.add("show")
        return
    }

    val line = document.createElement("div") as HTMLElement
    line.className = "log-line"
    line.dataset.asDynamic().msg = message

    val text = document.createElement("span") as HTMLElement
    text.className = "log-msg"
    text.innerHTML = message

    val badge = document.createElement("span") as HTMLElement
    badge.className = "badge"
    badge.dataset.asDynamic().count = "1"

    line.appendChild(text)
    line.appendChild(badge)
    container.appendChild(line)
    container.scrollTop = container.scrollHeight.toDouble()
}
