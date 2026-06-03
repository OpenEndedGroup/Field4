package afield

import kotlinx.browser.document
import kotlin.math.abs

private fun encodeCommandUri(value: String): String = js("encodeURIComponent")(value) as String

private fun decodeCommandUri(value: String): String = try {
    js("decodeURIComponent")(value) as String
} catch (_: dynamic) {
    value
}

private data class ShaderDeclaration(
    val symbol: String,
    val lineNumber: Int,
    val startColumn: Int,
    val endColumn: Int,
    val kind: String,
    val direction: String? = null,
    val locationKey: String? = null,
    val semanticKey: String? = null
)

private data class ShaderNavigationTarget(
    val property: String,
    val lineNumber: Int,
    val startColumn: Int,
    val endColumn: Int,
    val score: Int
)

private data class ShaderBlockState(
    val kind: String,
    val direction: String? = null,
    val locationKey: String? = null,
    var depth: Int = 0
)

private val shaderLocationPattern = Regex("""\blocation\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
private val shaderSemanticPattern = Regex(""":\s*([A-Za-z_][A-Za-z0-9_]*)""")
private val shaderFieldPattern = Regex(
    """\b([A-Za-z_][A-Za-z0-9_]*)\b\s*(?:\[[^\]]*])?\s*(?::\s*([A-Za-z_][A-Za-z0-9_]*))?\s*;"""
)
private val shaderPushConstantStartPattern = Regex(
    """layout\s*\([^)]*push_constant[^)]*\)\s*uniform\b.*\{""",
    RegexOption.IGNORE_CASE
)
private val shaderInterfaceStructStartPattern = Regex(
    """(?:layout\s*\([^)]*\)\s*)?\b(in|out)\b\s+struct\b.*\{""",
    RegexOption.IGNORE_CASE
)
private val shaderStructStartPattern = Regex("""\bstruct\b.*\{""", RegexOption.IGNORE_CASE)
private val shaderCbufferStartPattern = Regex("""\bcbuffer\b.*\{""", RegexOption.IGNORE_CASE)
private val shaderTopLevelInterfacePattern = Regex(
    """(?:layout\s*\([^)]*\)\s*)?\b(in|out)\b[^;{]*\b([A-Za-z_][A-Za-z0-9_]*)\s*;""",
    RegexOption.IGNORE_CASE
)
private val shaderInstancePattern = Regex("""}\s*([A-Za-z_][A-Za-z0-9_]*)\s*;?""")
private const val shaderNoMatchScore = -1000000
private const val shaderNavigationSelectionDelayMs = 90
private const val shaderNavigationRetryDelayMs = 180

private fun shaderRelatedProperties(property: String): List<String> {
    val current = property.lowercase()
    val glslRayStages = listOf("raygen", "miss", "closesthit", "anyhit", "intersection", "callable")
    val slangRayStages = listOf("raygen_s", "miss_s", "closesthit_s", "anyhit_s", "intersection_s", "callable_s")
    return when (current) {
        "vertex" -> listOf("fragment")
        "fragment" -> listOf("vertex")
        "task" -> listOf("mesh")
        "mesh" -> listOf("task")
        "vertex_s" -> listOf("fragment_s", "vertex_fragment")
        "fragment_s" -> listOf("vertex_s", "vertex_fragment")
        "vertex_fragment" -> listOf("vertex_s", "fragment_s")
        "task_s" -> listOf("mesh_s")
        "mesh_s" -> listOf("task_s")
        in glslRayStages -> glslRayStages.filter { it != current }
        in slangRayStages -> slangRayStages.filter { it != current }
        else -> emptyList()
    }
}

private fun shaderPropertyIndexOf(name: String?): Int {
    if (name == null) return -1
    for (i in monacoPropertyNames.indices) {
        if (monacoPropertyNames[i] == name) return i
    }
    return -1
}

private fun shaderKindFamily(kind: String): String = when {
    kind.startsWith("push") || kind.startsWith("buffer") -> "push"
    kind.startsWith("interface") -> "interface"
    kind.startsWith("struct") -> "struct"
    else -> kind
}

private fun shaderBraceDelta(line: String): Int =
    line.count { it == '{' } - line.count { it == '}' }

private fun shaderLocationKey(line: String): String? =
    shaderLocationPattern.find(line)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { "location:$it" }

private fun shaderSymbolColumns(line: String, symbol: String, preferLast: Boolean = false): Pair<Int, Int>? {
    if (symbol.isBlank()) return null
    val index = if (preferLast) line.lastIndexOf(symbol) else line.indexOf(symbol)
    if (index < 0) return null
    return (index + 1) to (index + symbol.length + 1)
}

private fun shaderFieldDeclaration(line: String, lineNumber: Int, state: ShaderBlockState): ShaderDeclaration? {
    val match = shaderFieldPattern.findAll(line).lastOrNull() ?: return null
    val symbol = match.groupValues.getOrNull(1)?.trim().orEmpty()
    if (symbol.isBlank()) return null
    val symbolColumns = shaderSymbolColumns(line, symbol, preferLast = true) ?: return null
    val semantic = match.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()
    return ShaderDeclaration(
        symbol = symbol,
        lineNumber = lineNumber,
        startColumn = symbolColumns.first,
        endColumn = symbolColumns.second,
        kind = when (state.kind) {
            "push" -> "push-field"
            "cbuffer" -> "buffer-field"
            "interface-struct" -> "interface-field"
            else -> "struct-field"
        },
        direction = state.direction,
        locationKey = state.locationKey,
        semanticKey = semantic
    )
}

private fun shaderParseDeclarations(source: String): List<ShaderDeclaration> {
    val declarations = mutableListOf<ShaderDeclaration>()
    val lines = source.replace("\r\n", "\n").split('\n')
    var blockState: ShaderBlockState? = null

    lines.forEachIndexed { index, rawLine ->
        val lineNumber = index + 1
        val line = rawLine.substringBefore("//")
        val trimmed = line.trim()

        val state = blockState
        if (state != null) {
            shaderFieldDeclaration(line, lineNumber, state)?.let { declarations.add(it) }
            val instanceMatch = shaderInstancePattern.find(line)
            if ((state.kind == "push" || state.kind == "interface-struct") && instanceMatch != null) {
                val instance = instanceMatch.groupValues.getOrNull(1)?.trim().orEmpty()
                val instanceColumns = shaderSymbolColumns(line, instance, preferLast = true)
                if (instance.isNotBlank() && instanceColumns != null) {
                    declarations.add(
                        ShaderDeclaration(
                            symbol = instance,
                            lineNumber = lineNumber,
                            startColumn = instanceColumns.first,
                            endColumn = instanceColumns.second,
                            kind = if (state.kind == "push") "push-instance" else "interface-instance",
                            direction = state.direction,
                            locationKey = state.locationKey
                        )
                    )
                }
            }

            state.depth += shaderBraceDelta(line)
            if (state.depth <= 0) {
                blockState = null
            }
            return@forEachIndexed
        }

        if (trimmed.isBlank()) return@forEachIndexed

        if (shaderPushConstantStartPattern.containsMatchIn(trimmed)) {
            blockState = ShaderBlockState(kind = "push", depth = shaderBraceDelta(line))
            return@forEachIndexed
        }

        val interfaceStructMatch = shaderInterfaceStructStartPattern.find(trimmed)
        if (interfaceStructMatch != null) {
            val direction = interfaceStructMatch.groupValues.getOrNull(1)?.lowercase()
            blockState = ShaderBlockState(
                kind = "interface-struct",
                direction = direction,
                locationKey = shaderLocationKey(trimmed),
                depth = shaderBraceDelta(line)
            )
            return@forEachIndexed
        }

        if (shaderCbufferStartPattern.containsMatchIn(trimmed)) {
            blockState = ShaderBlockState(kind = "cbuffer", depth = shaderBraceDelta(line))
            return@forEachIndexed
        }

        if (shaderStructStartPattern.containsMatchIn(trimmed)) {
            blockState = ShaderBlockState(kind = "struct", depth = shaderBraceDelta(line))
            return@forEachIndexed
        }

        val interfaceMatch = shaderTopLevelInterfacePattern.find(trimmed)
        if (interfaceMatch != null) {
            val direction = interfaceMatch.groupValues.getOrNull(1)?.lowercase()
            val symbol = interfaceMatch.groupValues.getOrNull(2)?.trim().orEmpty()
            val symbolColumns = shaderSymbolColumns(line, symbol, preferLast = true)
            if (symbol.isNotBlank() && symbolColumns != null) {
                declarations.add(
                    ShaderDeclaration(
                        symbol = symbol,
                        lineNumber = lineNumber,
                        startColumn = symbolColumns.first,
                        endColumn = symbolColumns.second,
                        kind = "interface-variable",
                        direction = direction,
                        locationKey = shaderLocationKey(trimmed)
                    )
                )
            }
            return@forEachIndexed
        }

        if (shaderSemanticPattern.containsMatchIn(trimmed)) {
            shaderFieldDeclaration(line, lineNumber, ShaderBlockState(kind = "struct"))?.let { declarations.add(it) }
        }
    }

    return declarations
}

private fun shaderCurrentDeclaration(
    symbol: String,
    declarations: List<ShaderDeclaration>,
    cursorLineNumber: Int
): ShaderDeclaration? {
    return declarations
        .asSequence()
        .filter { it.symbol == symbol }
        .sortedWith(compareBy<ShaderDeclaration>({ abs(it.lineNumber - cursorLineNumber) }, { it.startColumn }, { it.endColumn }))
        .firstOrNull()
}

private fun shaderScoreTarget(
    symbol: String,
    currentDeclaration: ShaderDeclaration?,
    candidate: ShaderDeclaration,
    relationIndex: Int
): Int {
    var score = shaderNoMatchScore
    val symbolMatches = candidate.symbol == symbol

    if (currentDeclaration == null) {
        if (!symbolMatches) return score
        score = 70
    } else {
        val matchedByLocation = !symbolMatches &&
            currentDeclaration.locationKey != null &&
            currentDeclaration.locationKey == candidate.locationKey
        val matchedBySemantic = !symbolMatches &&
            currentDeclaration.semanticKey != null &&
            currentDeclaration.semanticKey == candidate.semanticKey
        if (!symbolMatches && !matchedByLocation && !matchedBySemantic) {
            return shaderNoMatchScore
        }

        score = 0
        if (symbolMatches) score += 55

        if (matchedByLocation) {
            score += 140
        }
        if (matchedBySemantic) {
            score += 140
        }

        val currentFamily = shaderKindFamily(currentDeclaration.kind)
        val candidateFamily = shaderKindFamily(candidate.kind)
        if (currentFamily == candidateFamily) score += 45

        if (currentDeclaration.direction != null && candidate.direction != null) {
            score += if (currentDeclaration.direction != candidate.direction) 30 else -10
        }
    }

    score -= relationIndex * 5
    return score
}

private fun shaderFindLinkedDefinition(
    editor: dynamic,
    position: dynamic,
    modelOverride: dynamic = undefined
): ShaderNavigationTarget? {
    val w = js("window")
    val currentSelectedCode = w.currentSelectedCode
    val currentLen = (currentSelectedCode?.length as? Number)?.toInt() ?: 0
    if (currentLen != 1) return null

    val currentProperty = (w.currentSelectedProperty as? String)?.trim()?.lowercase() ?: return null
    val relatedProperties = shaderRelatedProperties(currentProperty)
    if (relatedProperties.isEmpty()) return null

    val model = if (modelOverride != null && modelOverride != undefined) modelOverride else editor.getModel()
    if (model == null || model == undefined) return null
    val wordAtPosition = model.getWordAtPosition(position) ?: return null
    val symbol = (wordAtPosition.word as? String)?.trim().orEmpty()
    if (symbol.isBlank()) return null

    val currentSource = model.getValue() as? String ?: ""
    val currentLineNumber = ((position.lineNumber as? Number)?.toInt() ?: 1).coerceAtLeast(1)
    val currentDeclaration = shaderCurrentDeclaration(symbol, shaderParseDeclarations(currentSource), currentLineNumber)

    var best: ShaderNavigationTarget? = null
    relatedProperties.forEachIndexed { relationIndex, property ->
        val targetSource = currentSelectedCode[0][property] as? String ?: return@forEachIndexed
        if (targetSource.isBlank()) return@forEachIndexed

        for (candidate in shaderParseDeclarations(targetSource)) {
            val score = shaderScoreTarget(symbol, currentDeclaration, candidate, relationIndex)
            if (score > (best?.score ?: shaderNoMatchScore)) {
                best = ShaderNavigationTarget(
                    property = property,
                    lineNumber = candidate.lineNumber,
                    startColumn = candidate.startColumn,
                    endColumn = candidate.endColumn,
                    score = score
                )
            }
        }
    }

    return best?.takeIf { it.score > 0 }
}

private fun shaderNavigateToTarget(target: ShaderNavigationTarget) {
    val w = js("window")
    val currentSelectedCode = w.currentSelectedCode
    val currentLen = (currentSelectedCode?.length as? Number)?.toInt() ?: 0
    if (currentLen != 1) return
    val uid = currentSelectedCode[0].uid as? String ?: return

    val navigateToBoxProperty = w.navigateToBoxProperty
    try {
        if (navigateToBoxProperty != null && navigateToBoxProperty != undefined) {
            navigateToBoxProperty(uid, target.property, target.lineNumber, target.lineNumber)
        } else {
            val targetIndex = shaderPropertyIndexOf(target.property)
            if (targetIndex >= 0) {
                monacoApplyPropertySelection(w.globalMonaco, w.globalEditor, targetIndex)
            }
        }
    } catch (_: dynamic) {
        val targetIndex = shaderPropertyIndexOf(target.property)
        if (targetIndex >= 0) {
            monacoApplyPropertySelection(w.globalMonaco, w.globalEditor, targetIndex)
        }
    }

    fun applySelection() {
        val editor = w.globalEditor ?: return
        val range = js("{}")
        range.startLineNumber = target.lineNumber
        range.startColumn = target.startColumn
        range.endLineNumber = target.lineNumber
        range.endColumn = target.endColumn
        try {
            editor.setSelection(range)
            editor.revealRangeInCenter(range)
            editor.focus()
        } catch (_: dynamic) {
        }
    }

    // Selection is applied twice because property switching is asynchronous and the
    // first attempt can race Monaco model replacement.
    w.setTimeout({ applySelection() }, shaderNavigationSelectionDelayMs)
    w.setTimeout({ applySelection() }, shaderNavigationRetryDelayMs)
}

private fun monacoRunShaderLinkedDefinition(editor: dynamic, position: dynamic = editor.getPosition()): Boolean {
    if (position == null || position == undefined) return false
    val target = shaderFindLinkedDefinition(editor, position) ?: return false
    shaderNavigateToTarget(target)
    return true
}

private fun runCommandById(editor: dynamic, id: String): Boolean {
    fun tryRun(action: dynamic): Boolean {
        if (action == null || action == undefined) return false
        return try {
            val runnable = action.run
            if (runnable != null) {
                runnable()
                true
            } else {
                false
            }
        } catch (_: dynamic) {
            false
        }
    }

    // 1) Try editor actions
    try {
        val action = editor.getAction(id)
        if (tryRun(action)) return true
    } catch (_: dynamic) {
    }

    // 2) Try lastActions / allActions (Field commands)
    val window = js("window")
    val lastActions = window.lastActions
    val lastLen = (lastActions?.length as? Number)?.toInt() ?: 0
    for (i in 0 until lastLen) {
        val a = lastActions[i]
        if ((a?.id as? String) == id && tryRun(a)) return true
    }
    val allActions = window.allActions
    if (allActions != null && allActions != undefined) {
        try {
            val keys = js("Object.keys")(allActions) as Array<String>
            keys.forEach { key ->
                val actions = allActions[key]
                val len = (actions?.length as? Number)?.toInt() ?: 0
                for (i in 0 until len) {
                    val a = actions[i]
                    if ((a?.id as? String) == id && tryRun(a)) return true
                }
            }
        } catch (_: dynamic) {
        }
    }

    // 3) Only dispatch Monaco commands when they look like Monaco ids
    if (id.startsWith("editor.") || id.startsWith("workbench.")) {
        try {
            editor.trigger("command-link", id, null)
            return true
        } catch (_: dynamic) {
        }

        val monaco = window.globalMonaco
        if (monaco != null && monaco != undefined) {
            try {
                val maybePromise = monaco.commands.executeCommand(id)
                val isPromise = js("maybePromise && typeof maybePromise.then === 'function'") as Boolean
                if (isPromise) {
                    maybePromise.catch { _: dynamic -> }
                }
                return true
            } catch (_: dynamic) {
            }
        }
    }

    return false
}

private fun collectCommandLookup(editor: dynamic): Map<String, String> {
    val lookup = mutableMapOf<String, String>()
    val window = js("window")

    fun addCommand(id: String?, label: String?) {
        val cleanedId = id?.trim()
        val cleanedLabel = label?.trim()
        if (!cleanedId.isNullOrBlank()) {
            lookup.computeIfAbsent(cleanedId.lowercase()) {cleanedId}
        }
        if (!cleanedLabel.isNullOrBlank()) {
            lookup.computeIfAbsent(cleanedLabel.lowercase()){ cleanedId ?: cleanedLabel}
        }
    }

    val allActions = window.allActions
    if (allActions != null && allActions != undefined) {
        try {
            val keys = js("Object.keys")(allActions) as Array<String>
            keys.forEach { key ->
                val actions = allActions[key]
                val len = (actions?.length as? Number)?.toInt() ?: 0
                for (i in 0 until len) {
                    val action = actions[i]
                    addCommand(action?.id as? String, action?.label as? String)
                }
            }
        } catch (_: dynamic) {
        }
    }

    val lastActions = window.lastActions
    val lastLen = (lastActions?.length as? Number)?.toInt() ?: 0
    for (i in 0 until lastLen) {
        val action = lastActions[i]
        addCommand(action?.id as? String, action?.label as? String)
    }

    val editorActions = editor?._actions
    if (editorActions != null && editorActions != undefined) {
        try {
            val keys = js("Object.keys")(editorActions) as Array<String>
            keys.forEach { key ->
                val action = editorActions[key]
                addCommand(key, action?.label as? String)
            }
        } catch (_: dynamic) {
        }
    }

    return lookup
}

private fun monacoInstallCommandLinkDetector(editor: dynamic, monaco: dynamic) {
    val installed = (editor.commandLinkDetectorInstalled as? Boolean) == true
    if (installed) return
    editor.commandLinkDetectorInstalled = true

    val window = js("window")
    val opener = js("{}")
    opener.id = "afield-command-link-opener"
    opener.open = open@{ uri: dynamic, _: dynamic ->
        val raw = when {
            js("typeof uri === 'string'") as Boolean -> uri
            (js("uri && uri.scheme === 'command'") as Boolean) -> {
                val pathPart = try {
                    uri.path as? String
                } catch (_: dynamic) {
                    null
                }
                "command:" + (pathPart ?: "")
            }
            else -> uri?.toString() ?: ""
        }
        if (!raw.startsWith("command:")) return@open false
        val encoded = raw.removePrefix("command:").trimStart('/')
        val commandId = decodeCommandUri(encoded)
        if (commandId.isBlank()) return@open false
        return@open runCommandById(editor, commandId)
    }
    monaco.editor.registerLinkOpener(opener)

    val registered = mutableListOf<dynamic>()
    val languageIds = mutableSetOf<String>()
    try {
        val langs = monaco.languages.getLanguages()
        val len = (langs?.length as? Number)?.toInt() ?: 0
        for (i in 0 until len) {
            val id = langs[i]?.id as? String
            if (!id.isNullOrBlank()) languageIds.add(id)
        }
    } catch (_: dynamic) {
    }
    if (languageIds.isEmpty()) {
        languageIds.addAll(listOf("kotlin", "glsl", "yaml", "plaintext"))
    }

    val regex = Regex("\"([^\"]+)\"")
    languageIds.forEach { langId ->
        val provider = js("{}")
        provider.provideLinks = fun(model: dynamic, _: dynamic): dynamic {
            val lookup = collectCommandLookup(editor)
            if (lookup.isEmpty()) return null else {

                val lines = try {
                    model.getLinesContent()
                } catch (_: dynamic) {
                    null
                }
                if (lines != null) {
                    val totalLines = (lines.length as? Number)?.toInt() ?: 0
                    val links = mutableListOf<dynamic>()
                    for (lineIndex in 0 until totalLines) {
                        val line = lines[lineIndex] as? String ?: continue
                        for (match in regex.findAll(line)) {
                            val candidate = match.groupValues.getOrNull(1)?.trim() ?: ""
                            if (candidate.isEmpty()) continue
                            val targetId = lookup[candidate.lowercase()] ?: continue

                            val startColumn = match.range.first + 2
                            val endColumn = startColumn + candidate.length
                            val link = js("{}")
                            val range = js("{}")
                            range.startLineNumber = lineIndex + 1
                            range.startColumn = startColumn
                            range.endLineNumber = lineIndex + 1
                            range.endColumn = endColumn
                            link.range = range
                            link.url = "command:${encodeCommandUri(targetId)}"
                            link.tooltip = "Run \"$candidate\""
                            links.add(link)
                        }
                    }

                    val result = js("{}")
                    result.links = links.toTypedArray()
                    result.dispose = {}
                    return result
                } else return null
            }
        }
        val disposable = monaco.languages.registerLinkProvider(langId, provider)
        registered.add(disposable)
    }
    window.commandLinkProviderDisposables = registered.toTypedArray()
}

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
        action.id = "paired-shader-definition"
        action.label = "Go to Paired Shader Definition"
        // Intentionally front F12 so paired shader navigation runs before Monaco's
        // ordinary same-file definition lookup, then fall back when no cross-property match exists.
        action.keybindings = arrayOf(keybinding(monaco.KeyCode.F12))
        action.precondition = undefined
        action.keybindingContext = undefined
        action.contextMenuGroupId = "navigation"
        action.contextMenuOrder = 1.4
        action.run = { ed: dynamic ->
            if (!monacoRunShaderLinkedDefinition(ed)) {
                ed.trigger("afield-shader-definition", "editor.action.revealDefinition", null)
            }
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

    val context = document.createElement("div")
    context.className = "hoverlay-left"
    context.innerHTML = ""
    editor._domElement.appendChild(context)

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
            monacoRunExecuteFragmentOrShaderReload(ed)
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
        action.id = "continue-all"
        action.label = "Continue Box"
        action.keybindings = arrayOf(keybinding(monaco.KeyMod.Alt, monaco.KeyMod.Shift, monaco.KeyCode.UpArrow))
        action.precondition = undefined
        action.keybindingContext = undefined
        action.contextMenuGroupId = "execution"
        action.contextMenuOrder = 2.1
        action.run = { ed: dynamic ->
            monacoRunContinueAll(ed)
        }
        addAction(action)
    }

    run {
        val action = js("{}")
        action.id = "stop-all"
        action.label = "Stop Box"
        action.keybindings = arrayOf(
            keybinding(monaco.KeyMod.Alt, monaco.KeyCode.DownArrow),
            keybinding(monaco.KeyMod.Alt, monaco.KeyMod.Shift, monaco.KeyCode.DownArrow)
        )
        action.precondition = undefined
        action.keybindingContext = undefined
        action.contextMenuGroupId = "execution"
        action.contextMenuOrder = 2.0
        action.run = { ed: dynamic ->
            monacoRunStopAll(ed)
        }
        addAction(action)
    }

    monacoInstallCommandLinkDetector(editor, monaco)
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

private fun shaderReloadActionForProperty(property: String): String? = when (property.lowercase()) {
    "vertex", "fragment", "vertex_fragment", "vertex_s", "fragment_s" -> "moduleHere_reload"
    "compute", "compute_s" -> "computeModuleHere_reload"
    "mesh", "task", "mesh_s", "task_s" -> "meshModuleHere_reload"
    "raygen", "miss", "closesthit", "anyhit", "intersection", "callable",
    "raygen_s", "miss_s", "closesthit_s", "anyhit_s", "intersection_s", "callable_s" -> "rayTracingModuleHere_reload"
    else -> null
}

private fun monacoRunExecuteFragmentOrShaderReload(editor: dynamic) {
    val w = js("window")
    val property = (w.currentSelectedProperty as? String)?.trim()?.lowercase()
    if (property == "contents") return
    val actionId = property?.let(::shaderReloadActionForProperty)
    if (actionId == null) {
        monacoRunExecuteFragment(editor)
        return
    }

    val current = w.currentSelectedCode
    val currentLen = (current?.length as? Number)?.toInt() ?: 0
    if (currentLen != 1) return
    val selected = current[0] ?: return
    val uid = selected.uid as? String ?: return

    val model = editor.getModel()
    if (model != null && model != undefined) {
        val value = model.getValue() as? String ?: ""
        selected[property] = value
        val payload = js("{}")
        payload.uid = uid
        payload.property = property
        payload.value = value
        send("set-string", payload)
    }

    send(
        Message("perform-action").payload(
            Perform_action(
                uid_currentSelection = listOf(uid),
                uid_action = actionId
            )
        )
    )
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
    val model = editor.getModel()
    val code = model?.getValue() as? String ?: (w.currentSelectedCode[0].code as? String ?: "")

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
    val model = editor.getModel()
    val code = model?.getValue() as? String ?: (w.currentSelectedCode[0].code as? String ?: "")
    w.launchAll(uid, code)
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoRunContinueAll(editor: dynamic) {
    val w = js("window")
    val uid = w.currentSelectedCode[0].uid as String
    val model = editor.getModel()
    val code = model?.getValue() as? String ?: (w.currentSelectedCode[0].code as? String ?: "")
    w.continueAll(uid, code)
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
    editor.onMouseDown(fun(e: dynamic) {
        // Check if Alt key is pressed and it's a double click
        val altKey = e.event.altKey as? Boolean ?: false
        val shiftKey = e.event.shiftKey as? Boolean ?: false
        val detail = e.event.detail as? Int ?: 0

        if (altKey && !shiftKey && detail == 2) {
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
    })
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
