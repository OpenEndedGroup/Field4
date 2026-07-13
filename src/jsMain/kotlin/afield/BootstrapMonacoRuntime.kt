package afield

import kotlinx.browser.window

private var currentSelectedCode: dynamic = js("[]")
private var currentSelectedProperty: String = "code"
private var currentValueAtOrigination = ""
private val recentChangeCache = mutableListOf<String>()
private const val mainEditorModelCacheLimit = 80
private val mainEditorModelCache = mutableMapOf<String, MainEditorModelEntry>()
private var mainEditorModelSerial = 0L

private var provisionalDiffKey: String? = null
private var provisionalDiffText: String? = null
private var provisionalDiffRetry: Int? = null
private var provisionalListenerInstalled = false
private var provisionalAcceptedValueListenerInstalled = false

private data class MainEditorModelEntry(
    val uid: String,
    val property: String,
    val model: dynamic,
    var lastUsed: Long
)

private fun mainEditorModelKey(uid: String, property: String): String = "$uid\u001f$property"

private fun pruneMainEditorModelCache(activeModel: dynamic) {
    if (mainEditorModelCache.size <= mainEditorModelCacheLimit) return
    val stale = mainEditorModelCache.values
        .filter { entry -> entry.model != activeModel }
        .sortedBy { entry -> entry.lastUsed }
        .take(mainEditorModelCache.size - mainEditorModelCacheLimit)
    stale.forEach { entry ->
        mainEditorModelCache.remove(mainEditorModelKey(entry.uid, entry.property))
        runCatching { entry.model.dispose() }
    }
}

private fun modelLanguageFor(box: dynamic, property: String): String {
    val language = when (property) {
        "code" -> box.code_language as? String
        "contents" -> box.contents_language as? String
        else -> null
    } ?: monacoFindLanguageForPropertyName(property)
    return language.lowercase()
}

private fun modelForSelectedProperty(w: dynamic, box: dynamic, property: String, text: String): dynamic {
    val monaco = w.globalMonaco
    val uid = box.uid as? String ?: ""
    val key = mainEditorModelKey(uid, property)
    val language = modelLanguageFor(box, property)
    val normalized = text.replace("\r\n", "\n")
    mainEditorModelSerial += 1

    val existing = mainEditorModelCache[key]
    if (existing != null) {
        existing.lastUsed = mainEditorModelSerial
        if ((existing.model.getValue() as? String ?: "") != normalized) {
            existing.model.setValue(normalized)
        }
        runCatching { monaco.editor.setModelLanguage(existing.model, language) }
        pruneMainEditorModelCache(existing.model)
        return existing.model
    }

    val model = monaco.editor.createModel(normalized, language)
    runCatching { model.setEOL(0) }
    mainEditorModelCache[key] = MainEditorModelEntry(uid, property, model, mainEditorModelSerial)
    pruneMainEditorModelCache(model)
    return model
}

private fun syncNotesMarkdownPreview() {
    val w = js("window")
    val sync = w.syncNotesMarkdownPreview
    if (sync != null && sync != undefined) {
        try {
            sync()
        } catch (_: dynamic) {
        }
    }
}

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

private fun recordCurrentAcceptedProvisionalValue() {
    val w = js("window")
    val entry = currentProvisionalEntry() ?: return
    val editor = w.globalEditor ?: return
    val payload = js("{}")
    payload.uid = entry.uid
    payload.property = entry.property
    payload.value = editor.getValue() as String
    send("provisional-record-accepted-value", payload)
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
    if (!provisionalAcceptedValueListenerInstalled) {
        provisionalAcceptedValueListenerInstalled = true
        val w = js("window")
        w.addEventListener("ai-diff-hunk-accepted", { _: dynamic ->
            recordCurrentAcceptedProvisionalValue()
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
        val propertyValue = codeDiv[currentSelectedProperty] as? String ?: ""
        val vs = js("JSON.stringify")(globalEditor.saveViewState()) as String
        val editorStateKey = "${currentSelectedProperty}_editorState"
        val mainEditorHasFocus = runCatching {
            globalEditor.hasTextFocus() as? Boolean ?: false
        }.getOrDefault(false)
        val embeddedPeer = if (w.embeddedEditors != null && w.embeddedEditors != undefined) {
            w.embeddedEditors.getFor(codeDiv.uid, currentSelectedProperty)
        } else null
        val hasEmbeddedPeer = embeddedPeer != null && embeddedPeer != undefined

        if (hasEmbeddedPeer && !mainEditorHasFocus && nc != propertyValue) {
            notifyPotentialEditedPropertyHasBeenRemoteUpdated(
                codeDiv.uid as String,
                currentSelectedProperty,
                propertyValue
            )
        } else {
            if (codeDiv[editorStateKey] != vs) {
                codeDiv[editorStateKey] = vs
                val payload = js("{}")
                payload.uid = codeDiv.uid
                payload.property = editorStateKey
                payload.value = vs
                send("set-string", payload)
            }

            if (nc != propertyValue) {
                codeDiv[currentSelectedProperty] = nc
                if (hasEmbeddedPeer) {
                    notifyEmbeddedEditorOfRemoteUpdate(codeDiv.uid as String, currentSelectedProperty, nc)
                }
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

                if (currentSelectedProperty == "notes") {
                    syncNotesMarkdownPreview()
                }
            }
        }
    }

    if (monacoNotEqual(next, currentSelectedCode) || nextProperty != currentSelectedProperty) {
        val currentGen = (w.currentEditorGeneration as? Number)?.toInt() ?: 0
        w.currentEditorGeneration = currentGen + 1

        if (w.HistoryLens != null && w.HistoryLens != undefined) {
            w.HistoryLens.close(globalEditor)
        }
        w.clear(globalEditor)
        if (w.setEditorContextBadge != null) {
            w.setEditorContextBadge(globalEditor, "")
        }

        currentSelectedCode = next
        currentSelectedProperty = nextProperty
        w.currentSelectedCode = currentSelectedCode
        w.currentSelectedProperty = currentSelectedProperty

        val nextLen = (currentSelectedCode.length as? Number)?.toInt() ?: 0

        // Display mode from ?editorOnly / ?noeditor (see Main.kt). In no-editor the editor never
        // shows (and must not steal focus); in editor-only it stays visible even with no selection.
        val displayMode = (w.fieldDisplayMode as? String) ?: "normal"
        if (nextLen == 1) {
            if (displayMode != "no-editor") w.showMonaco()
            var code = currentSelectedCode[0][currentSelectedProperty] as? String
            if (code == null) code = ""
            val state = currentSelectedCode[0]["${currentSelectedProperty}_editorState"] as? String
            val suppressRestore = (w.suppressNextEditorStateRestore as? Boolean) == true
            if (suppressRestore) {
                w.suppressNextEditorStateRestore = false
            }
            w.pauseLineTracking()
            val model = modelForSelectedProperty(w, currentSelectedCode[0], currentSelectedProperty, code)
            if (globalEditor.getModel() != model) {
                globalEditor.setModel(model)
            }
            model.setEOL(0)
            w.unpauseLineTracking()
            if (state != null && !suppressRestore && displayMode != "no-editor") {
                window.setTimeout({
                    globalEditor.restoreViewState(js("JSON.parse")(state))
                    globalEditor.focus()
                }, 0)
            }
            currentValueAtOrigination = code
        } else {
            // editor-only keeps the (empty) editor on screen; everything else hides it on deselect.
            if (displayMode == "editor-only") w.showMonaco() else w.hideMonaco()
        }

        if (nextLen > 0) {
            val useCodeLanguage = currentSelectedProperty == "code"
            val useContentsLanguage = currentSelectedProperty == "contents"
            var lang = when {
                useCodeLanguage -> currentSelectedCode[0].code_language as? String
                useContentsLanguage -> currentSelectedCode[0].contents_language as? String
                else -> null
            }
            if (lang == null) {
                lang = monacoFindLanguageForPropertyName(currentSelectedProperty)
            }
            w.globalMonaco.editor.setModelLanguage(globalEditor.getModel(), lang.lowercase())
            monacoUpdateLanguageExtensionSurfaces(globalEditor)
            globalEditor._domElement
                .querySelectorAll(".hoverlay")[0]
                .innerHTML = "$currentSelectedProperty <i>($lang)</i>"
        } else {
            globalEditor._domElement.querySelectorAll(".hoverlay")[0].innerHTML = ""
        }

        syncNotesMarkdownPreview()

        val monaco = w.globalMonaco
        if (monaco != null && monaco != undefined) {
            monacoInstallPropertySwitchActions(monaco, globalEditor)
        }
        if (w.LineHeatmap != null && w.LineHeatmap != undefined) {
            w.LineHeatmap.scheduleRefresh(globalEditor, true)
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
    monacoUpdateLanguageExtensionSurfaces(w.globalEditor)
}

fun notifyPotentialEditedPropertyHasEditorStateRemoteUpdated(uid: String, property: String, value: String) {
    val w = js("window")
    val selected = w.currentSelectedCode
    val len = (selected?.length as? Number)?.toInt() ?: 0
    if (len != 1) return
    if (selected[0].uid != uid) return
    if (w.currentSelectedProperty != property) return
}

private fun forceEditedPropertyToRemoteValue(w: dynamic, selected: dynamic, editor: dynamic, property: String, value: String) {
    val normalized = value.replace("\r\n", "\n")
    if (w.AIDiff != null && w.AIDiff != undefined) {
        w.AIDiff.clear(editor)
    }

    val selection = editor.getSelection()
    if (w.pauseLineTracking != null && w.pauseLineTracking != undefined) {
        w.pauseLineTracking()
    }
    editor.setValue(normalized)
    editor.getModel().setEOL(0)
    if (w.unpauseLineTracking != null && w.unpauseLineTracking != undefined) {
        w.unpauseLineTracking()
    }
    runCatching {
        if (selection != null && selection != undefined) {
            editor.setSelection(selection)
        }
    }

    selected[0][property] = normalized
    currentValueAtOrigination = normalized
    recentChangeCache.add(normalized)
    if (recentChangeCache.size > 5) recentChangeCache.removeAt(0)
    if (property == "notes") {
        syncNotesMarkdownPreview()
    }
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
    val normalizedValue = value.replace("\r\n", "\n")
    val historyRestoreIsAuthoritative = (w.__historyRestoreAuthoritative as? Boolean) == true
    if (historyRestoreIsAuthoritative) {
        forceEditedPropertyToRemoteValue(w, selected, editor, property, normalizedValue)
        return
    }
    if (currentValue == normalizedValue || currentValueAtOrigination == normalizedValue) return
    if (recentChangeCache.contains(normalizedValue)) return

    val a = currentValueAtOrigination.split("\n").toTypedArray()
    val b = currentValue.split("\n").toTypedArray()
    val c = normalizedValue.split("\n").toTypedArray()
    val merged = w.diff.mergeDigIn(b, a, c)
    val result = (merged.result as Array<*>).joinToString("\n") { it?.toString() ?: "" }
    console.log(" testing to see if this change is actually a change ", result, currentValue)
    if (result == currentValue) return

    val selection = editor.getSelection()
    editor.setValue(result)
    editor.setSelection(selection)
    currentValueAtOrigination = result
    selected[0][property] = result
    if (property == "notes") {
        syncNotesMarkdownPreview()
    }
}

/**
 * Forward a remote property update to any open embedded editor for (uid, property).
 * Delegates to the JS embeddedEditors registry.
 */
fun notifyEmbeddedEditorOfRemoteUpdate(uid: String, property: String, value: String) {
    val w = js("window")
    val emb = w.embeddedEditors ?: return
    val fn = emb.notifyRemote ?: return
    (fn as Function<*>).asDynamic().call(emb, uid, property, value)
}
