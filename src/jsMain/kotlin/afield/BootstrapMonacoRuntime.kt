package afield

import kotlinx.browser.window

private var currentSelectedCode: dynamic = js("[]")
private var currentSelectedProperty: String = "code"
private var currentValueAtOrigination = ""
private val recentChangeCache = mutableListOf<String>()

private var provisionalDiffKey: String? = null
private var provisionalDiffText: String? = null
private var provisionalDiffRetry: Int? = null
private var provisionalListenerInstalled = false

private fun ensureSelectionState() {
    val w = js("window")
    if (w.currentSelectedCode != null) {
        currentSelectedCode = w.currentSelectedCode
    } else {
        w.currentSelectedCode = currentSelectedCode
    }
    val prop = w.currentSelectedProperty as? String
    if (prop != null) {
        currentSelectedProperty = prop
    } else {
        w.currentSelectedProperty = currentSelectedProperty
    }
    if (w.currentEditorGeneration == null) {
        w.currentEditorGeneration = 0
    }
    if (w.lastErrorIs == null) {
        w.lastErrorIs = js("[]")
    }
}

private fun currentProvisionalEntry(): ProvisionalEntry? {
    val w = js("window")
    val provisional = w.Provisional ?: return null
    val selected = w.currentSelectedCode
    val len = (selected?.length as? Number)?.toInt() ?: 0
    if (len != 1) return null
    val uid = selected[0].uid as? String ?: return null
    val property = w.currentSelectedProperty as? String ?: return null
    val has = provisional.has(uid, property) as? Boolean ?: false
    if (!has) return null
    val contents = provisional.get(uid, property) as? String ?: return null
    return ProvisionalEntry(uid, property, contents)
}

private fun updateProvisionalDiff() {
    val w = js("window")
    val editor = w.globalEditor ?: return
    val entry = currentProvisionalEntry()
    if (entry == null) {
        if (provisionalDiffKey != null && w.AIDiff != null) {
            w.AIDiff.clear(editor)
        }
        provisionalDiffKey = null
        provisionalDiffText = null
        return
    }
    val aidiff = w.AIDiff
    if (aidiff == null) {
        if (provisionalDiffRetry == null) {
            provisionalDiffRetry = window.setTimeout({
                provisionalDiffRetry = null
                updateProvisionalDiff()
            }, 50)
        }
        return
    }
    val key = "${entry.uid}::${entry.property}"
    if (provisionalDiffKey == key && provisionalDiffText == entry.contents) return
    provisionalDiffKey = key
    provisionalDiffText = entry.contents
    val options = js("{}")
    options.monaco = w.globalMonaco
    aidiff.apply(editor, entry.contents, options)
}

internal fun resolveCurrentProvisional(accept: Boolean) {
    val w = js("window")
    val entry = currentProvisionalEntry() ?: return
    if (w.AIDiff != null && w.globalEditor != null) {
        val options = js("{}")
        options.monaco = w.globalMonaco
        if (accept) w.AIDiff.acceptAllRemaining(w.globalEditor, options)
        else w.AIDiff.rejectAllRemaining(w.globalEditor, options)
    }
    val payload = js("{}")
    payload.uid = entry.uid
    payload.property = entry.property
    send(if (accept) "provisional-accept" else "provisional-reject", payload)
}

internal fun resolveAllProvisional(accept: Boolean) {
    val w = js("window")
    val entry = currentProvisionalEntry()
    if (entry != null && w.AIDiff != null && w.globalEditor != null) {
        val options = js("{}")
        options.monaco = w.globalMonaco
        if (accept) w.AIDiff.acceptAllRemaining(w.globalEditor, options)
        else w.AIDiff.rejectAllRemaining(w.globalEditor, options)
    }
    send(if (accept) "provisional-accept-all" else "provisional-reject-all", js("{}"))
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun monacoStartSelectionMonitor() {
    ensureSelectionState()
    if (!provisionalListenerInstalled) {
        provisionalListenerInstalled = true
        val w = js("window")
        w.addEventListener("provisional-change", { _: dynamic ->
            updateProvisionalDiff()
        })
    }
    monitorSelection()
}

private fun monitorSelection() {
    val w = js("window")
    val next = w.selectedList ?: js("[]")
    val nextProperty = w.selectedProperty as? String ?: currentSelectedProperty
    val globalEditor = w.globalEditor
    if (globalEditor == null) {
        window.setTimeout({ monitorSelection() }, 100)
        return
    }

    val currentLen = (currentSelectedCode.length as? Number)?.toInt() ?: 0
    if (currentLen == 1) {
        val codeDiv = currentSelectedCode[0]
        val nc = globalEditor.getModel().getValue() as String
        val vs = js("JSON.stringify")(globalEditor.saveViewState()) as String
        val editorStateKey = "${currentSelectedProperty}_editorState"
        if (codeDiv[editorStateKey] != vs) {
            codeDiv[editorStateKey] = vs
            val payload = js("{}")
            payload.uid = codeDiv.uid
            payload.property = editorStateKey
            payload.value = vs
            send("set-string", payload)
        }

        if (nc != codeDiv[currentSelectedProperty]) {
            codeDiv[currentSelectedProperty] = nc
            currentValueAtOrigination = codeDiv[currentSelectedProperty] as? String ?: ""
            recentChangeCache.add(currentValueAtOrigination)
            if (recentChangeCache.size > 5) recentChangeCache.removeAt(0)

            val payload = js("{}")
            payload.uid = codeDiv.uid
            payload.property = currentSelectedProperty
            payload.value = codeDiv[currentSelectedProperty]
            send("set-string", payload)

            val payloadState = js("{}")
            payloadState.uid = codeDiv.uid
            payloadState.property = editorStateKey
            payloadState.value = vs
            send("set-string", payloadState)
        }
    }

    if (monacoNotEqual(next, currentSelectedCode) || nextProperty != currentSelectedProperty) {
        val currentGen = (w.currentEditorGeneration as? Number)?.toInt() ?: 0
        w.currentEditorGeneration = currentGen + 1

        if ((next.length as? Number)?.toInt() == 1) {
            w.showMonaco()
        }

        w.clear(globalEditor)

        currentSelectedCode = next
        currentSelectedProperty = nextProperty
        w.currentSelectedCode = currentSelectedCode
        w.currentSelectedProperty = currentSelectedProperty

        val nextLen = (currentSelectedCode.length as? Number)?.toInt() ?: 0
        if (nextLen == 1) {
            var code = currentSelectedCode[0][currentSelectedProperty] as? String
            if (code == null) code = ""
            val state = currentSelectedCode[0]["${currentSelectedProperty}_editorState"] as? String
            if (state != null) {
                var disposable: dynamic = null
                disposable = globalEditor.onDidChangeModelContent { event: dynamic ->
                    if (event.isFlush as? Boolean == true) {
                        if (disposable != null) {
                            disposable.dispose()
                        }
                        window.setTimeout({
                            globalEditor.restoreViewState(js("JSON.parse")(state))
                            globalEditor.focus()
                        }, 300)
                    }
                }
            }
            w.pauseLineTracking()
            globalEditor.setValue(code.replace("\r\n", "\n"))
            globalEditor.getModel().setEOL(0)
            w.unpauseLineTracking()
            currentValueAtOrigination = code
        } else {
            w.hideMonaco()
        }

        if (nextLen > 0) {
            var lang = currentSelectedCode[0].code_language as? String
            if (lang == null) {
                lang = monacoFindLanguageForPropertyName(currentSelectedProperty)
            }
            w.globalMonaco.editor.setModelLanguage(globalEditor.getModel(), lang.lowercase())
            globalEditor._domElement
                .querySelectorAll(".hoverlay")[0]
                .innerHTML = "$currentSelectedProperty <i>($lang)</i>"
        } else {
            globalEditor._domElement.querySelectorAll(".hoverlay")[0].innerHTML = ""
        }

        updateProvisionalDiff()
    }

    window.setTimeout({ monitorSelection() }, 100)
}

fun notifyPotentialEditedPropertyHasHadLanguageChanged(uid: String, property: String, language: String) {
    val w = js("window")
    val selected = w.currentSelectedCode
    val len = (selected?.length as? Number)?.toInt() ?: 0
    if (len != 1) return
    if (selected[0].uid != uid) return
    if (w.currentSelectedProperty != property) return
    w.globalMonaco.editor.setModelLanguage(w.globalEditor.getModel(), language)
}

fun notifyPotentialEditedPropertyHasEditorStateRemoteUpdated(uid: String, property: String, value: String) {
    val w = js("window")
    val selected = w.currentSelectedCode
    val len = (selected?.length as? Number)?.toInt() ?: 0
    if (len != 1) return
    if (selected[0].uid != uid) return
    if (w.currentSelectedProperty != property) return
}

fun notifyPotentialEditedPropertyHasBeenRemoteUpdated(uid: String, property: String, value: String) {
    val w = js("window")
    val selected = w.currentSelectedCode
    val len = (selected?.length as? Number)?.toInt() ?: 0
    if (len != 1) return
    if (selected[0].uid != uid) return
    if (w.currentSelectedProperty != property) return

    val editor = w.globalEditor ?: return
    val currentValue = editor.getValue() as String
    if (currentValue == value || currentValueAtOrigination == value) return
    if (recentChangeCache.contains(value)) return

    val a = currentValueAtOrigination.split("\n").toTypedArray()
    val b = currentValue.split("\n").toTypedArray()
    val c = value.split("\n").toTypedArray()
    val merged = w.diff.mergeDigIn(b, a, c)
    val result = (merged.result as Array<*>).joinToString("\n") { it?.toString() ?: "" }
    if (result == currentValue) return

    val selection = editor.getSelection()
    editor.setValue(result)
    editor.setSelection(selection)
    currentValueAtOrigination = result
    selected[0][property] = result
}
