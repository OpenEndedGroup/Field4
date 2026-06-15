package afield

import kotlinx.browser.document

internal val monacoPropertyNames = arrayOf(
    "code",
    "vertex",
    "fragment",
    "stackSL",
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
    "metadata",
    "contents"
)
internal val monacoPropertyDescriptions = arrayOf(
    "Kotlin (main)",
    "Vertex Shader (GLSL)",
    "Fragment Shader (GLSL)",
    "StackSL Shader Function",
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
    "Metadata about this box",
    "File contents"
)
internal val monacoPropertyLanguages = arrayOf(
    "kotlin",
    "glsl",
    "glsl",
    "stacksl",
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

private fun installDynamicPropertyActionLabel(action: dynamic, baseLabel: String, propName: String) {
    val getter = {
        computePropertyActionLabel(js("window"), baseLabel, propName)
    }
    js(
        """(function(action, getter) {
            Object.defineProperty(action, 'label', {
                get: getter,
                enumerable: true
            });
        })"""
    )(action, getter)
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
    if ((ed.afieldPropertySwitchActionsInstalled as? Boolean) == true) return
    ed.afieldPropertySwitchActionsInstalled = true

    val w = js("window")
    disposePropertySwitchActions(w.propertyActionDisposables)

    val keyCodes = arrayOf(
        monaco.KeyCode.KeyM,
        monaco.KeyCode.KeyV,
        monaco.KeyCode.KeyF,
        monaco.KeyCode.KeyM,
        monaco.KeyCode.KeyP,
        monaco.KeyCode.KeyD
    )

    val disposables = mutableListOf<dynamic>()

    for (i in monacoPropertyNames.indices) {
        val actionId = "switch-property-" + monacoPropertyNames[i]
        val baseLabel = "Edit: " + monacoPropertyDescriptions[i]
        val propName = monacoPropertyNames[i]

        val action = js("{}")
        action.id = actionId
        installDynamicPropertyActionLabel(action, baseLabel, propName)
        action.contextMenuGroupId = "navigation"
        action.contextMenuOrder = 2.0

        if (i < keyCodes.size) {
            val binding = (monaco.KeyMod.Alt as Int) or (keyCodes[i] as Int)
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

    w.propertyActionDisposables = disposables.toTypedArray()
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
        monaco.editor.setModelLanguage(globalEditor.getModel(), lang)
        try {
            w.updateStackSLFlowLens?.invoke(globalEditor)
        } catch (_: dynamic) {}
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
    options.scrollBeyondLastLine = false
    options.lineNumbersMinChars = 2
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

    val fontOptions = js("{}")
    fontOptions.fontFamily = "Iosevka Web"
    fontOptions.fontSize = 15
    fontOptions.fontLigatures = true
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
