package afield

import kotlinx.browser.document

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoRunAiAssist(editor: dynamic) {
    val w = js("window")
    val model = editor.getModel()
    val selection = editor.getSelection()
    val fullText = model.getValue() as String
    val startPosition = selection.getStartPosition()
    val endPosition = selection.getEndPosition()
    val startOffset = (model.getOffsetAt(startPosition) as Number).toInt()
    val endOffset = (model.getOffsetAt(endPosition) as Number).toInt()

    val allText = if (startOffset != endOffset) {
        fullText.substring(0, startOffset) +
            "<rewrite>" +
            fullText.substring(startOffset, endOffset) +
            "</rewrite>" +
            fullText.substring(endOffset)
    } else {
        fullText.substring(0, startOffset) +
            "<cursor>" +
            fullText.substring(startOffset)
    }

    val uid = w.currentSelectedCode[0].uid as String
    val kind = if (startOffset != endOffset) "rewrite" else "cursor"
    val startLine = ((startPosition.lineNumber as Number).toInt() - 1).coerceAtLeast(0)

    val payload = js("{}")
    payload.uid = uid
    payload.prompt = allText
    payload.startLine = startLine
    payload.extraPrompt = ""
    payload.property = w.currentSelectedProperty
    payload.kind = kind
    send("ai-assist", payload)
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoRegisterActions(editor: dynamic, monaco: dynamic) {
    fun keycode(value: dynamic): Int {
        return (value as Number).toInt()
    }
    fun keybinding(vararg values: dynamic): Int {
        var result = 0
        for (value in values) {
            result = result or keycode(value)
        }
        return result
    }

    fun addAction(action: dynamic) {
        editor.addAction(action)
    }

    run {
        val action = js("{}")
        action.id = "send-to-preamble"
        action.label = "Move code to preamble"
        action.keybindings = arrayOf(keybinding(monaco.KeyMod.Alt, monaco.KeyMod.Shift, monaco.KeyCode.KeyP))
        action.precondition = "editorHasSelection"
        action.run = { ed: dynamic ->
            monacoRunSendToPreamble(ed)
        }
        addAction(action)
    }

    run {
        val action = js("{}")
        action.id = "ai-chat"
        action.label = "AI Chat in editor"
        action.keybindings = arrayOf(keybinding(monaco.KeyMod.CtrlCmd, monaco.KeyCode.Digit3))
        action.run = { ed: dynamic ->
            monacoRunAiChatInline(ed)
        }
        addAction(action)
    }

    run {
        val action = js("{}")
        action.id = "ai-chat-panel"
        action.label = "AI Chat (panel)"
        action.keybindings = arrayOf(keybinding(monaco.KeyMod.CtrlCmd, monaco.KeyCode.Digit4))
        action.run = {
            monacoRunAiChatPanel()
        }
        addAction(action)
    }

    run {
        val action = js("{}")
        action.id = "ai-assist"
        action.label = "Apply Intelligence"
        action.keybindings = arrayOf(keybinding(monaco.KeyMod.Alt, monaco.KeyCode.KeyO))
        action.precondition = undefined
        action.keybindingContext = undefined
        action.contextMenuGroupId = "execution"
        action.contextMenuOrder = 1.5
        action.run = { ed: dynamic ->
            monacoRunAiAssist(ed)
        }
        addAction(action)
    }

    val hover = document.createElement("div")
    hover.className = "hoverlay"
    hover.textContent = ".code"
    editor._domElement.appendChild(hover)

    run {
        val action = js("{}")
        action.id = "ai-assist-error"
        action.label = "Apply Intelligence to Error message"
        action.keybindings = arrayOf(keybinding(monaco.KeyMod.Alt, monaco.KeyMod.Shift, monaco.KeyCode.KeyO))
        action.precondition = {
            val w = js("window")
            val lastErrorIs = w.lastErrorIs
            (lastErrorIs != null && (lastErrorIs.length as? Number)?.toInt() == 2)
        }
        action.keybindingContext = undefined
        action.contextMenuGroupId = "execution"
        action.contextMenuOrder = 1.5
        action.run = { ed: dynamic ->
            monacoRunAiAssistError(ed)
        }
        addAction(action)
    }

    run {
        val action = js("{}")
        action.id = "provisional-accept-current"
        action.label = "Accept All Provisional (This Editor)"
        action.keybindings = emptyArray<Int>()
        action.precondition = undefined
        action.keybindingContext = undefined
        action.contextMenuGroupId = "execution"
        action.contextMenuOrder = 1.6
        action.run = {
            monacoRunProvisionalAcceptCurrent()
        }
        addAction(action)
    }

    run {
        val action = js("{}")
        action.id = "provisional-reject-current"
        action.label = "Reject All Provisional (This Editor)"
        action.keybindings = emptyArray<Int>()
        action.precondition = undefined
        action.keybindingContext = undefined
        action.contextMenuGroupId = "execution"
        action.contextMenuOrder = 1.61
        action.run = {
            monacoRunProvisionalRejectCurrent()
        }
        addAction(action)
    }

    run {
        val action = js("{}")
        action.id = "provisional-accept-all"
        action.label = "Accept All Provisional (Everywhere)"
        action.keybindings = emptyArray<Int>()
        action.precondition = undefined
        action.keybindingContext = undefined
        action.contextMenuGroupId = "execution"
        action.contextMenuOrder = 1.62
        action.run = {
            monacoRunProvisionalAcceptAll()
        }
        addAction(action)
    }

    run {
        val action = js("{}")
        action.id = "provisional-reject-all"
        action.label = "Reject All Provisional (Everywhere)"
        action.keybindings = emptyArray<Int>()
        action.precondition = undefined
        action.keybindingContext = undefined
        action.contextMenuGroupId = "execution"
        action.contextMenuOrder = 1.63
        action.run = {
            monacoRunProvisionalRejectAll()
        }
        addAction(action)
    }

    editor.addCommand(keybinding(monaco.KeyMod.CtrlCmd, monaco.KeyCode.KeyP)) {
        editor.trigger("", "editor.action.quickCommand", null)
    }

    run {
        val action = js("{}")
        action.id = "toggle-fold-current-line"
        action.label = "Toggle Fold on Current Line"
        action.keybindings = arrayOf(keybinding(monaco.KeyMod.CtrlCmd, monaco.KeyCode.Quote))
        action.run = { ed: dynamic ->
            monacoRunToggleFold(ed)
        }
        addAction(action)
    }

    run {
        val action = js("{}")
        action.id = "forward-next-property"
        action.label = "Next Property"
        action.keybindings = arrayOf(keybinding(monaco.KeyMod.CtrlCmd, monaco.KeyCode.Period))
        action.run = { ed: dynamic ->
            monacoRunNextProperty(ed, 1)
        }
        addAction(action)
    }

    run {
        val action = js("{}")
        action.id = "back-next-property"
        action.label = "Previous Property"
        action.keybindings = arrayOf(keybinding(monaco.KeyMod.CtrlCmd, monaco.KeyCode.Comma))
        action.run = { ed: dynamic ->
            monacoRunNextProperty(ed, -1)
        }
        addAction(action)
    }

    run {
        val action = js("{}")
        action.id = "execute-fragment"
        action.label = "Run Line / Selection"
        action.keybindings = arrayOf(keybinding(monaco.KeyMod.Alt, monaco.KeyCode.Enter))
        action.precondition = undefined
        action.keybindingContext = undefined
        action.contextMenuGroupId = "execution"
        action.contextMenuOrder = 1.5
        action.run = { ed: dynamic ->
            monacoRunExecuteFragment(ed)
        }
        addAction(action)
    }

    run {
        val action = js("{}")
        action.id = "execute-all"
        action.label = "Run Box"
        action.keybindings = arrayOf(keybinding(monaco.KeyMod.Alt, monaco.KeyCode.Digit0))
        action.precondition = undefined
        action.keybindingContext = undefined
        action.contextMenuGroupId = "execution"
        action.contextMenuOrder = 2.0
        action.run = { ed: dynamic ->
            monacoRunExecuteAll(ed)
        }
        addAction(action)
    }

    run {
        val action = js("{}")
        action.id = "launch-fragment"
        action.label = "Launch Line / Selection"
        action.keybindings = arrayOf(keybinding(monaco.KeyMod.Alt, monaco.KeyCode.Digit1))
        action.precondition = undefined
        action.keybindingContext = undefined
        action.contextMenuGroupId = "execution"
        action.contextMenuOrder = 1.5
        action.run = { ed: dynamic ->
            monacoRunLaunchFragment(ed)
        }
        addAction(action)
    }

    run {
        val action = js("{}")
        action.id = "launch-all"
        action.label = "Launch Box"
        action.keybindings = arrayOf(keybinding(monaco.KeyMod.Alt, monaco.KeyCode.UpArrow))
        action.precondition = undefined
        action.keybindingContext = undefined
        action.contextMenuGroupId = "execution"
        action.contextMenuOrder = 2.0
        action.run = { ed: dynamic ->
            monacoRunLaunchAll(ed)
        }
        addAction(action)
    }

    run {
        val action = js("{}")
        action.id = "stop-all"
        action.label = "Stop Box"
        action.keybindings = arrayOf(keybinding(monaco.KeyMod.Alt, monaco.KeyCode.DownArrow))
        action.precondition = undefined
        action.keybindingContext = undefined
        action.contextMenuGroupId = "execution"
        action.contextMenuOrder = 2.0
        action.run = { ed: dynamic ->
            monacoRunStopAll(ed)
        }
        addAction(action)
    }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoRunAiAssistError(editor: dynamic) {
    val w = js("window")
    val lastErrorIs = w.lastErrorIs
    if (lastErrorIs == null || (lastErrorIs.length as? Number)?.toInt() != 2) return

    val model = editor.getModel()
    var lineNumber = (lastErrorIs[0] as Number).toInt()
    if (lineNumber <= 0) lineNumber = 1
    lastErrorIs[0] = lineNumber

    val endColumn = (model.getLineMaxColumn(lineNumber) as Number).toInt()
    val offset = (model.getOffsetAt(js("{ lineNumber: lineNumber, column: endColumn }")) as Number).toInt()
    val fullText = model.getValue() as String

    val errText = (lastErrorIs[1] as? String)?.trim() ?: ""
    val allText = fullText.substring(0, offset) +
        "<error>" +
        errText +
        "</error>\n" +
        fullText.substring(offset)

    val uid = w.currentSelectedCode[0].uid as String
    val payload = js("{}")
    payload.uid = uid
    payload.prompt = allText
    payload.kind = "error"
    send("ai-assist", payload)
}

private fun extractSelectionOrScope(editor: dynamic): Triple<String, Int, Boolean> {
    val selection = editor.getSelection()
    val model = editor.getModel()
    if (!(selection.isEmpty() as Boolean)) {
        val belowLine = (selection.startLineNumber as Number).toInt() - 1
        val code = model.getValueInRange(selection) as String
        return Triple(code, belowLine, false)
    }

    val lineNumber = (selection.endLineNumber as Number).toInt()
    val getLine = { n: Int ->
        if (n < 0) {
            null
        } else if (n >= (model.getLineCount() as Number).toInt()) {
            null
        } else {
            model.getLineContent(n + 1)
        }
    }
    val scope = monacoGetEnclosingScope(lineNumber - 1, getLine)
    if (scope == null) {
        val line = model.getLineContent(lineNumber) as String
        return Triple(line, lineNumber - 1, true)
    }
    val lines = (scope[0] as Array<*>).map { it?.toString() ?: "" }
    val start = (scope[1] as Number).toInt()
    val code = lines.joinToString("\n")
    return Triple(code, start, true)
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoRunExecuteFragment(editor: dynamic) {
    val w = js("window")
    val (code, belowLine, singleLine) = extractSelectionOrScope(editor)
    val uid = w.currentSelectedCode[0].uid as String

    val payload = js("{}")
    payload.uid = uid
    payload.type = "fragment"
    payload.line = belowLine
    payload.code = code
    payload.singleLine = singleLine

    val handler = w.handleOutput
    val callback: (dynamic, dynamic) -> Boolean = { message, response ->
        handler(response, message, uid)
        true
    }
    sendWithReply("execute-fragment", payload, "exec_$uid", callback.asDynamic())
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoRunExecuteAll(editor: dynamic) {
    val w = js("window")
    val uid = w.currentSelectedCode[0].uid as String
    val code = w.currentSelectedCode[0].code as? String ?: ""

    val payload = js("{}")
    payload.uid = uid
    payload.type = "all"
    payload.line = 0
    payload.code = code
    payload.singleLine = false

    val handler = w.handleOutput
    val callback: (dynamic, dynamic) -> Boolean = { message, response ->
        handler(response, message, uid)
        true
    }
    sendWithReply("execute-all", payload, "exec_$uid", callback.asDynamic())
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoRunSendToPreamble(editor: dynamic) {
    val w = js("window")
    val model = editor.getModel()
    val selection = editor.getSelection()
    val selectedText = model.getValueInRange(selection) as String
    val current = w.currentSelectedCode[0]
    val existing = current.preamble as? String ?: ""
    val updated = selectedText.trim() + "\n" + existing
    current.preamble = updated

    val edit = js("{}")
    edit.range = selection
    edit.text = ""
    edit.forceMoveMarkers = true
    editor.executeEdits("remove-selection", arrayOf(edit))

    val payload = js("{}")
    payload.uid = current.uid
    payload.property = "preamble"
    payload.value = updated
    send("set-string", payload)
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoRunAiChatInline(editor: dynamic) {
    val w = js("window")
    val selection = editor.getSelection()
    val start = (selection.startLineNumber as Number).toInt() - 1
    val uid = w.currentSelectedCode[0].uid as String
    val fullText = editor.getModel().getValue() as String
    val addPromptBox = w.addPromptBox
    val callback: (dynamic) -> Unit = { v ->
        val payload = js("{}")
        payload.uid = uid
        payload.prompt = fullText
        payload.startLine = start
        payload.extraPrompt = v
        payload.property = w.currentSelectedProperty
        payload.kind = "chat"
        send("ai-assist", payload)
    }
    addPromptBox(editor, start, callback.asDynamic())
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoRunAiChatPanel() {
    val w = js("window")
    val current = w.currentSelectedCode
    if (current == null || (current.length as? Number)?.toInt() != 1) return
    val uid = current[0].uid as? String ?: return
    val opener = w.openAiChatForBox
    if (opener != null) {
        opener(uid, w.currentSelectedProperty)
    }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoRunProvisionalAcceptCurrent() {
    resolveCurrentProvisional(true)
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoRunProvisionalRejectCurrent() {
    resolveCurrentProvisional(false)
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoRunProvisionalAcceptAll() {
    resolveAllProvisional(true)
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoRunProvisionalRejectAll() {
    resolveAllProvisional(false)
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoRunToggleFold(editor: dynamic) {
    editor.trigger("keyboard", "editor.toggleFold", null)
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoRunNextProperty(editor: dynamic, inc: Int) {
    monacoNextProperty(editor, inc)
}

private fun extractSelectionOrScopeForLaunch(editor: dynamic): LaunchSelection {
    val selection = editor.getSelection()
    val model = editor.getModel()
    if (!(selection.isEmpty() as Boolean)) {
        val belowLine = (selection.endLineNumber as Number).toInt() - 1
        val top = (selection.startLineNumber as Number).toInt()
        val bottom = (selection.endLineNumber as Number).toInt()
        val code = model.getValueInRange(selection) as String
        return LaunchSelection(code, belowLine, false, top, bottom)
    }

    val lineNumber = (selection.endLineNumber as Number).toInt()
    val getLine = { n: Int ->
        if (n < 0) {
            null
        } else if (n >= (model.getLineCount() as Number).toInt()) {
            null
        } else {
            model.getLineContent(n + 1)
        }
    }
    val scope = monacoGetEnclosingScope(lineNumber - 1, getLine)
    if (scope == null) {
        val line = model.getLineContent(lineNumber) as String
        val start = lineNumber - 1
        return LaunchSelection(line, start, true, start + 1, start + 1)
    }
    val lines = (scope[0] as Array<*>).map { it?.toString() ?: "" }
    val start = (scope[1] as Number).toInt()
    val end = (scope[2] as Number).toInt()
    val code = lines.joinToString("\n")
    return LaunchSelection(code, start, true, start + 1, end)
}

private data class LaunchSelection(
    val code: String,
    val belowLine: Int,
    val singleLine: Boolean,
    val topLine: Int,
    val bottomLine: Int
)

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoRunLaunchFragment(editor: dynamic) {
    val w = js("window")
    val selection = extractSelectionOrScopeForLaunch(editor)
    val className = w.addExecutionMarker(editor, selection.topLine, selection.bottomLine)
    val uid = w.currentSelectedCode[0].uid as String

    val payload = js("{}")
    payload.uid = uid
    payload.type = "fragment"
    payload.line = selection.belowLine
    payload.code = selection.code
    payload.singleLine = selection.singleLine
    payload.strand = className

    val handler = w.handleOutput
    val callback: (dynamic, dynamic) -> Boolean = { message, response ->
        handler(response, message, uid)
        true
    }
    sendWithReply("launch-fragment", payload, "exec_$uid", callback.asDynamic())
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoRunLaunchAll(editor: dynamic) {
    val w = js("window")
    val uid = w.currentSelectedCode[0].uid as String
    val code = w.currentSelectedCode[0].code as? String ?: ""
    w.launchAll(uid, code)
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoRunStopAll(editor: dynamic) {
    val w = js("window")
    val uid = w.currentSelectedCode[0].uid as String
    val code = w.currentSelectedCode[0].code as? String ?: ""
    val line = code.split("\n").size + 1

    val payload = js("{}")
    payload.uid = uid
    payload.type = "launch"
    payload.line = line
    payload.code = code
    payload.singleLine = false

    val callback: (dynamic, dynamic) -> Boolean = { _, _ -> true }
    sendWithReply("stop-all", payload, "stop_$uid", callback.asDynamic())
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoInstallAltDoubleClickHandler(editor: dynamic, monaco: dynamic) {
    // Install Alt+DoubleClick handler
    editor.onMouseDown { e: dynamic ->
        // Check if Alt key is pressed and it's a double click
        if (e.event.altKey as Boolean && e.event.detail as Int == 2) {
            val position = e.target.position
            if (position != null) {
                val model = editor.getModel()
                val lineNumber = (position.lineNumber as Number).toInt()
                val column = (position.column as Number).toInt()
                
                // Get the word at the position
                val wordAtPosition = model.getWordAtPosition(position)
                if (wordAtPosition != null) {
                    val word = wordAtPosition.word as String
                    val startColumn = (wordAtPosition.startColumn as Number).toInt()
                    val endColumn = (wordAtPosition.endColumn as Number).toInt()
                    
                    // Handle true/false toggle
                    when (word) {
                        "true" -> {
                            // Replace with false
                            val range = js("{}")
                            range.startLineNumber = lineNumber
                            range.endLineNumber = lineNumber
                            range.startColumn = startColumn
                            range.endColumn = endColumn
                            
                            val edit = js("{}")
                            edit.range = range
                            edit.text = "false"
                            edit.forceMoveMarkers = true
                            editor.executeEdits("toggle-boolean", arrayOf(edit))
                            
                            // Execute fragment
                            monacoRunExecuteFragment(editor)
                        }
                        "false" -> {
                            // Replace with true
                            val range = js("{}")
                            range.startLineNumber = lineNumber
                            range.endLineNumber = lineNumber
                            range.startColumn = startColumn
                            range.endColumn = endColumn
                            
                            val edit = js("{}")
                            edit.range = range
                            edit.text = "true"
                            edit.forceMoveMarkers = true
                            editor.executeEdits("toggle-boolean", arrayOf(edit))
                            
                            // Execute fragment
                            monacoRunExecuteFragment(editor)
                        }
                        else -> {
                            // Execute symbol
                            monacoRunExecuteSymbol(editor, word, lineNumber - 1)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoRunExecuteSymbol(editor: dynamic, symbol: String, lineNumber: Int) {
    val w = js("window")
    val uid = w.currentSelectedCode[0].uid as String
    
    val payload = js("{}")
    payload.uid = uid
    payload.symbol = symbol
    payload.line = lineNumber
    
    val handler = w.handleOutput
    val callback: (dynamic, dynamic) -> Boolean = { message, response ->
        handler(response, message, uid)
        true
    }
    sendWithReply("execute-symbol", payload, "exec_$uid", callback.asDynamic())
}
