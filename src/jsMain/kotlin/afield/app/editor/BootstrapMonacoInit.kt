package afield.app.editor

import afield.app.canvas.path
import afield.app.client.main
import afield.app.protocol.Metadata

import afield.app.canvas.invoke
import afield.app.client.addOutput
import afield.app.client.clear
import afield.app.client.installFragileErrorMarkerPruning
import afield.app.client.monaco
import afield.app.client.rememberEditedPropertyForCurrentSelection
import afield.app.client.selectedProperty
import afield.app.client.selectionContextChanged
import afield.app.client.setSelectedEditorProperty
import afield.app.protocol.handle
import kotlinx.browser.document

internal val monacoPropertyNames = arrayOf(
    "code",
    "vertex",
    "fragment",
    "stackSL",
    "rectSL",
    "msl",
    "vertex_fragment",
    "compute",
    "task",
    "mesh",
    "raygen",
    "miss",
    "closesthit",
    "anyhit",
    "intersection",
    "callable",
    "vertex_s",
    "fragment_s",
    "compute_s",
    "task_s",
    "mesh_s",
    "raygen_s",
    "miss_s",
    "closesthit_s",
    "anyhit_s",
    "intersection_s",
    "callable_s",
    "preamble",
    "introduction",
    "attic",
    "notes",
    "metadata",
    "contents"
)
internal val monacoPropertyDescriptions = arrayOf(
    "Kotlin (main)",
    "Vertex Shader (GLSL)",
    "Fragment Shader (GLSL)",
    "StackSL Shader Function",
    "RectSL Scene",
    "Vertex+Fragment Shader (Metal, visionOS)",
    "Shared Vertex+Fragment Shader (Slang)",
    "Compute Shader (GLSL)",
    "Task Shader (GLSL)",
    "Mesh Shader (GLSL)",
    "Ray Generation Shader (GLSL)",
    "Miss Shader (GLSL)",
    "Closest Hit Shader (GLSL)",
    "Any Hit Shader (GLSL)",
    "Intersection Shader (GLSL)",
    "Callable Shader (GLSL)",
    "Vertex Shader (Slang)",
    "Fragment Shader (Slang)",
    "Compute Shader (Slang)",
    "Task Shader (Slang)",
    "Mesh Shader (Slang)",
    "Ray Generation Shader (Slang)",
    "Miss Shader (Slang)",
    "Closest Hit Shader (Slang)",
    "Any Hit Shader (Slang)",
    "Intersection Shader (Slang)",
    "Callable Shader (Slang)",
    "Kotlin (preamble)",
    "Kotlin (introduction)",
    "Kotlin (attic)",
    "Notes (Markdown)",
    "Metadata about this box",
    "File contents"
)
internal val monacoPropertyLanguages = arrayOf(
    "kotlin",
    "glsl",
    "glsl",
    "stacksl",
    "rectsl",
    "msl",
    "slang",
    "glsl",
    "glsl",
    "glsl",
    "glsl",
    "glsl",
    "glsl",
    "glsl",
    "glsl",
    "glsl",
    "slang",
    "slang",
    "slang",
    "slang",
    "slang",
    "slang",
    "slang",
    "slang",
    "slang",
    "slang",
    "slang",
    "kotlin",
    "kotlin",
    "kotlin",
    "markdown",
    "yaml",
    "plaintext"
)

private fun propertyIndexOf(name: String?): Int {
    if (name == null) return -1
    for (i in monacoPropertyNames.indices) {
        if (monacoPropertyNames[i] == name) return i
    }
    return -1
}

private fun isTruthy(value: dynamic): Boolean = js("Boolean(value)") as Boolean

internal fun monacoUpdateLanguageExtensionSurfaces(editor: dynamic) {
    if (editor == null) return
    val w = js("window")
    val hasGeneric = js("typeof window.updateLanguageExtensions === 'function'") as Boolean
    if (hasGeneric) {
        try {
            w.updateLanguageExtensions(editor)
            return
        } catch (_: dynamic) {}
    }
    try {
        w.updateStackSLFlowLens?.invoke(editor)
    } catch (_: dynamic) {}
    try {
        w.updateStackSLEmbeddedHighlight?.invoke(editor)
    } catch (_: dynamic) {}
    try {
        w.updateStackSLDiagnostics?.invoke(editor)
    } catch (_: dynamic) {}
}

private fun computePropertyActionLabel(w: dynamic, baseLabel: String, propName: String): String {
    val currentCode = w.currentSelectedCode
    val len = (currentCode?.length as? Number)?.toInt() ?: 0
    if (len > 0 && currentCode[0] != null) {
        val propValue = currentCode[0][propName]
        val exists = js("propValue !== undefined && propValue !== null") as Boolean
        if (exists) {
            val hasContent = if (js("typeof propValue === 'string' || propValue instanceof String") as Boolean) {
                val trimmed = (propValue as String).trim()
                trimmed.isNotEmpty()
            } else {
                js("Boolean(propValue)") as Boolean
            }
            return baseLabel + if (hasContent) " ●" else " ○"
        }
    }
    return baseLabel
}

private fun disposePropertySwitchActions(previous: dynamic) {
    val len = (previous?.length as? Number)?.toInt() ?: 0
    if (len <= 0) return
    for (i in 0 until len) {
        val entry = previous[i]
        try {
            entry?.dispose?.invoke()
        } catch (_: dynamic) {
        }
    }
}

internal fun monacoInstallPropertySwitchActions(monaco: dynamic, ed: dynamic) {
    val w = js("window")
    if ((ed.afieldPropertySwitchActionsInstalled as? Boolean) == true) return

    disposePropertySwitchActions(ed.afieldPropertyActionDisposables)
    disposePropertySwitchActions(w.propertyActionDisposables)
    ed.afieldPropertySwitchActionsInstalled = true

    // By name, as quickpick.js's palettePropertySwitchKeybindings has them. These were once matched
    // to monacoPropertyNames by position, and inserting "msl" silently moved Alt-P and Alt-D down
    // one property each.
    val keyCodes = mapOf(
        "code" to monaco.KeyCode.KeyM,
        "vertex" to monaco.KeyCode.KeyV,
        "fragment" to monaco.KeyCode.KeyF,
        "stackSL" to monaco.KeyCode.KeyM,
        "rectSL" to monaco.KeyCode.KeyM,
        "vertex_fragment" to monaco.KeyCode.KeyP,
        "compute" to monaco.KeyCode.KeyD
    )

    val disposables = mutableListOf<dynamic>()

    for (i in monacoPropertyNames.indices) {
        val actionId = "switch-property-" + monacoPropertyNames[i]

        val action = js("{}")
        action.id = actionId
        action.label = "Edit: " + monacoPropertyDescriptions[i]
        action.contextMenuGroupId = "navigation"
        action.contextMenuOrder = 2.0

        val keyCode = keyCodes[monacoPropertyNames[i]]
        if (keyCode != null) {
            val binding = (monaco.KeyMod.Alt as Int) or (keyCode as Int)
            action.keybindings = arrayOf(binding)
        } else {
            action.keybindings = emptyArray<Int>()
        }

        action.run = { editorNow: dynamic ->
            monacoApplyPropertySelection(monaco, editorNow, i)
        }

        val handle = ed.addAction(action)
        disposables.add(handle)
    }

    val handles = disposables.toTypedArray()
    ed.afieldPropertyActionDisposables = handles
    w.propertyActionDisposables = handles
}

internal fun monacoApplyPropertySelection(monaco: dynamic, editor: dynamic, index: Int) {
    if (index !in monacoPropertyNames.indices) return
    val w = js("window")
    val property = monacoPropertyNames[index]
    setSelectedEditorProperty(property)
    rememberEditedPropertyForCurrentSelection(property)
    if (w.selectionContextChanged != null) {
        try {
            w.selectionContextChanged()
        } catch (_: dynamic) {}
    }

    var lang = monacoPropertyLanguages[index]
    val currentSelectedCode = w.currentSelectedCode
    if (property == "code" &&
        currentSelectedCode != null &&
        (currentSelectedCode.length as? Number)?.toInt() ?: 0 > 0 &&
        currentSelectedCode[0].code_language != null
    ) {
        lang = currentSelectedCode[0].code_language as String
    } else if (property == "contents" &&
        currentSelectedCode != null &&
        (currentSelectedCode.length as? Number)?.toInt() ?: 0 > 0 &&
        currentSelectedCode[0].contents_language != null
    ) {
        lang = currentSelectedCode[0].contents_language as String
    }

    val globalEditor = w.globalEditor ?: editor
    if (globalEditor != null) {
        // Lower-cased, like the other two callers (`Main.kt`'s editor-open path and
        // `BootstrapMonacoRuntime.modelLanguageFor`). A box's `code_language` is a display label —
        // "Kotlin", "Clojure", "MorphoHDL" — and `setModelLanguage` resolves ids, never aliases or
        // spellings, falling back silently to plaintext. Passing the label through here dropped
        // both the grammar *and* fieldSuggest (which gates on `model.getLanguageId()`) for any box
        // whose property tab was switched by hand. See the comment at the top of
        // `src/commonMain/resources/morpho.js`.
        monaco.editor.setModelLanguage(globalEditor.getModel(), lang.lowercase())
        monacoUpdateLanguageExtensionSurfaces(globalEditor)
        globalEditor._domElement
            .querySelectorAll(".hoverlay")[0]
            .innerHTML = "$property <i>($lang)</i>"
        if (w.selectionContextChanged != null) {
            try {
                w.selectionContextChanged()
            } catch (_: dynamic) {}
        }
    }
}

private val cStyleCommentLanguages = setOf("kotlin", "glsl", "javascript", "typescript", "java", "c", "cpp", "slang", "rust", "swift", "scala", "csharp", "go")
private var proseCacheKey: String? = null
private var proseCache = BooleanArray(0)

private fun lineNumberLabel(monaco: dynamic, n: Int): String {
    val ed = monaco.editor.getEditors()[0] ?: return n.toString()
    val model = ed.getModel() ?: return n.toString()
    val key = "${model.uri}#${model.getVersionId()}"
    if (key != proseCacheKey) {
        val language = model.getLanguageId() as String
        val text = model.getValue() as String
        proseCache = proseLineFlags(text, language in cStyleCommentLanguages)
        proseCacheKey = key
    }
    return if (n - 1 in proseCache.indices && proseCache[n - 1]) "" else n.toString()
}

/**
 * Per line, whether it carries no code: whitespace only or, when [cStyle], nothing outside `//`
 * and (nested, Kotlin-style) block comments. Strings are skipped, so a block-comment opener inside quotes opens nothing.
 */
internal fun proseLineFlags(text: String, cStyle: Boolean): BooleanArray {
    val lines = text.split("\n")
    val flags = BooleanArray(lines.size)
    var depth = 0
    var inString: String? = null
    for ((li, line) in lines.withIndex()) {
        var code = false
        var comment = depth > 0 && line.isNotBlank()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inString != null) {
                code = true
                if (c == '\\' && inString == "\"") i++
                else if (line.startsWith(inString, i)) { i += inString.length; inString = null; continue }
                i++
                continue
            }
            if (cStyle && depth == 0 && line.startsWith("//", i)) { comment = true; break }
            if (cStyle && line.startsWith("/*", i)) { depth++; comment = true; i += 2; continue }
            if (cStyle && depth > 0 && line.startsWith("*/", i)) { depth--; i += 2; continue }
            if (depth == 0 && !c.isWhitespace()) {
                code = true
                if (line.startsWith("\"\"\"", i)) { inString = "\"\"\""; i += 3; continue }
                if (c == '"') inString = "\""
                if (c == '\'') {
                    val close = line.indexOf('\'', i + if (line.getOrNull(i + 1) == '\\') 3 else 2)
                    if (close > i) { i = close + 1; continue }
                }
            }
            i++
        }
        if (inString == "\"") inString = null
        flags[li] = !code && (comment || line.isBlank())
    }
    return flags
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoInit(monaco: dynamic): dynamic {
    val themeColors = js("{}")
    themeColors["editor.background"] = "#1e1e1e"
    themeColors["editorGutter.background"] = "#000000"
    themeColors["editor.lineHighlightBackground"] = "#2e2e2e"
    themeColors["editorCursor.foreground"] = "#ff3b30"
    themeColors["editorCursor.background"] = "#ff3b30"
    val theme = js("{}")
    theme.base = "vs-dark"
    theme.inherit = true
    theme.rules = arrayOf<dynamic>(
        js("{ token: 'operator.stacksl.prefix', foreground: 'ffcf66', fontStyle: 'bold' }"),
        js("{ token: 'operator.stacksl.range', foreground: 'ffcf66', fontStyle: 'bold' }"),
        js("{ token: 'number.stacksl.modifier', foreground: 'ffcf66', fontStyle: 'bold' }"),
        js("{ token: 'number.stacksl.option', foreground: 'ffcf66', fontStyle: 'bold' }"),
        js("{ token: 'number.stacksl.suffix', foreground: 'ffcf66', fontStyle: 'bold' }")
    )
    theme.colors = themeColors
    monaco.editor.defineTheme("dark", theme)

    val container = document.querySelector(".monaco-editor-container") ?: return null
    val options = js("{}")
    options.value = ""
    options.language = "kotlin"
    options.theme = "dark"
    options.automaticLayout = true
    options.selectionClipboard = false
    options.scrollBeyondLastLine = false
    options.lineNumbersMinChars = 2
    // Blank and comment-only lines show no number; see proseLineFlags.
    options.lineNumbers = { n: Int -> lineNumberLabel(monaco, n) }
    options.lineDecorationsWidth = 12
    options.glyphMargin = false
    options.padding = js("{ top: 2, bottom: 2 }")
    options.cursorStyle = "block"
    options.cursorBlinking = "blink"
    options.matchBrackets = "never"
    options["renderLineHighlight"] = "none"
    options["renderLineHighlightOnlyWhenFocus"] = false
    options["bracketPairColorization.enabled"] = true
    val scrollbar = js("{}")
    scrollbar.verticalScrollbarSize = 5
    options.scrollbar = scrollbar

    monaco.editor.create(container, options)
    val ed = monaco.editor.getEditors()[0]
    // Monaco only redraws the gutter of lines it re-renders, so opening a block comment would leave the numbers
    // below it stale. When the set of unnumbered lines changes, hand it a fresh callback to redraw all.
    var lastFlags = ""
    val refreshLineNumbers = {
        lineNumberLabel(monaco, 1)
        val flags = proseCache.joinToString("") { if (it) "1" else "0" }
        if (flags != lastFlags) {
            lastFlags = flags
            val o = js("{}")
            o.lineNumbers = { n: Int -> lineNumberLabel(monaco, n) }
            ed.updateOptions(o)
        }
    }
    ed.onDidChangeModelContent { refreshLineNumbers() }
    ed.onDidChangeModel { refreshLineNumbers() }

    val fontOptions = js("{}")
    fontOptions.fontFamily = "Iosevka Web"
    fontOptions.fontWeight = "300"
    // The phone controller's Edit tab is a narrow editor-only projection: 20% smaller text shows more code.
    val bodyClasses = document.body?.classList
    val phoneEditor = bodyClasses?.contains("mode-controller-mobile") == true && bodyClasses.contains("mode-editor-only")
    fontOptions.fontSize = if (phoneEditor) 12 else 15
    fontOptions.fontLigatures = "\"liga\" on, \"calt\" on, \"dlig\" on, \"TXTR\" on"
    fontOptions.margin = "5px"
    ed.updateOptions(fontOptions)
    monaco.editor.setModelLanguage(ed.getModel(), "kotlin")

    val editorOptions = js("{}")
    editorOptions.quickSuggestions = false
    editorOptions.wordWrap = "on"
    editorOptions.minimap = js("{ enabled: false }")
    editorOptions.suggest = js("{ showWords: false }")
    ed.updateOptions(editorOptions)

    val w = js("window")
    w.globalEditor = ed
    w.globalMonacoEditor = ed
    w.globalMonaco = monaco
    w.globalMonacoEditor._domElement.parentElement.parentElement.style.borderRadius = "5px"
    ed.getModel().setEOL(0)

    w.addOutput = w.addOutput
    w.clear = w.clear

    installFragileErrorMarkerPruning(ed)

    monacoInstallPropertySwitchActions(monaco, ed)

    // Add Alt+DoubleClick handler for execute symbol feature
    monacoInstallAltDoubleClickHandler(ed, monaco)

    return ed
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoNextProperty(editor: dynamic, inc: Int) {
    val w = js("window")
    val monaco = w.globalMonaco ?: return
    val current = w.selectedProperty as? String
    var currentIndex = propertyIndexOf(current)
    if (monacoPropertyNames.isEmpty()) return
    val count = monacoPropertyNames.size
    for (n in 0..count) {
        val nextIndex = ((currentIndex + inc + count) % count + count) % count
        val currentSelectedCode = w.currentSelectedCode
        if (currentSelectedCode != null &&
            (currentSelectedCode.length as? Number)?.toInt() ?: 0 > 0
        ) {
            val candidate = currentSelectedCode[0][monacoPropertyNames[nextIndex]]
            if (isTruthy(candidate)) {
                monacoApplyPropertySelection(monaco, editor, nextIndex)
                return
            }
        }
        currentIndex = nextIndex
    }
}
