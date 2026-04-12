
import {
        sendWithReply,
        monacoInit,
        monacoRegisterActions,
        monacoStartSelectionMonitor,
        monacoHandleOutput,
        monacoLaunchAll,
        monacoContinueAll,
        monacoKindForIcon,
        monacoGetEnclosingScope
} from "./afield.mjs"

require.config({
        paths: {
                'vs':  document.location.protocol + "//"+document.location.host+'/min/vs', 'bithero': './',
        }
});

window.MonacoEnvironment = {
        getWorkerUrl: function (workerId, label) {
                return `data:text/javascript;charset=utf-8,${encodeURIComponent(`
        self.MonacoEnvironment = {
          baseUrl: '${document.location.protocol}//${document.location.host}/min/'
        };
        importScripts('${document.location.protocol}//${document.location.host}/min/vs/base/worker/workerMain.js');`)}`;
        }
};

require(["vs/editor/editor.main"], function (e, is) {
        require(["vs/basic-languages/python/python"], function (e, is) {
        });
        require(["vs/basic-languages/clojure/clojure"], function (e, is) {
        });
        require(["vs/basic-languages/yaml/yaml"], function (e, is) {
        });
        require(["bithero/glsl", "bithero/slang", "bithero/forth", "bithero/nano"], function () {
                initMonaco()
                require(["vs/platform/quickinput/common/quickInput"], function (is) {
                        initQuickPick(is)
                })
        })
});


window.currentEditorGeneration = window.currentEditorGeneration || 0
window.lastErrorIs = window.lastErrorIs || []
window.currentSelectedCode = window.currentSelectedCode || []
window.currentSelectedProperty = window.currentSelectedProperty || "code"
const DEBUG_MONACO = false;
const monacoLog = (...args) => {
        if (DEBUG_MONACO) console.info(...args);
};

const kindForIcon = monacoKindForIcon
const SYMBOL_CONTEXT_DEBOUNCE_MS = 180
const SYMBOL_CONTEXT_CACHE_MAX = 200
const SYMBOL_CONTEXT_POLL_MS = 250

const stopSymbolContextPolling = (editor) => {
        if (!editor || editor._afieldSymbolContextPollTimer == null) return
        clearInterval(editor._afieldSymbolContextPollTimer)
        editor._afieldSymbolContextPollTimer = null
}

const captureBadgeViewState = (node) => {
        const details = node && node.querySelector("details.afield-symbol-context-disclosure")
        const body = node && node.querySelector(".afield-symbol-context-disclosure-body")
        return {
                detailsOpen: !!(details && details.open),
                scrollTop: body ? body.scrollTop : 0,
        }
}

const applyDisclosureLayout = (editor, node) => {
        if (!node) return
        const body = node.querySelector(".afield-symbol-context-disclosure-body")
        if (!body) return
        const layoutHeight = (editor && typeof editor.getLayoutInfo === "function" && editor.getLayoutInfo())
                ? editor.getLayoutInfo().height
                : ((editor && editor._domElement && editor._domElement.clientHeight) || 0)
        const maxHeight = layoutHeight > 0 ? Math.min(200, Math.floor(layoutHeight / 2)) : 200
        body.style.maxHeight = `${Math.max(0, maxHeight)}px`
        body.style.overflow = "auto"
}

const syncSymbolContextPolling = (editor) => {
        if (!editor) return
        const meta = editor._afieldSymbolContextMeta
        const node = editor && editor._domElement && editor._domElement.querySelector(".hoverlay-left")
        const details = node && node.querySelector("details.afield-symbol-context-disclosure")
        const shouldPoll = !!(meta && meta.isLiveValue && details && details.open)
        if (!shouldPoll) {
                stopSymbolContextPolling(editor)
                return
        }
        if (editor._afieldSymbolContextPollTimer != null) return

        editor._afieldSymbolContextPollTimer = setInterval(() => {
                const request = buildSymbolContextRequest(editor, editor._afieldSymbolContextResolveContext, { allowUnfocused: true })
                if (!request || !editor._afieldSymbolContextMeta || request.key !== editor._afieldSymbolContextMeta.key) {
                        stopSymbolContextPolling(editor)
                        return
                }
                const state = ensureSymbolContextState()
                if (state.inFlight != null) return
                dispatchSymbolContextRequest(request, { forceRefresh: true })
        }, SYMBOL_CONTEXT_POLL_MS)
}

const attachDisclosureToggle = (editor, node) => {
        if (!node) return
        const details = node.querySelector("details.afield-symbol-context-disclosure")
        if (!details) {
                syncSymbolContextPolling(editor)
                return
        }
        details.addEventListener("toggle", () => {
                syncSymbolContextPolling(editor)
        })
}

const setEditorContextBadge = (editor, payload = null) => {
        const node = editor && editor._domElement && editor._domElement.querySelector(".hoverlay-left")
        if (!node) return

        const previousState = captureBadgeViewState(node)
        const next = ((payload && payload.html) || "").trim()
        if (next.length === 0) {
                node.innerHTML = ""
                node.style.opacity = "0"
                node.style.pointerEvents = "none"
                editor._afieldSymbolContextMeta = null
                stopSymbolContextPolling(editor)
                return
        }

        node.innerHTML = next
        if (previousState.detailsOpen) {
                const details = node.querySelector("details.afield-symbol-context-disclosure")
                if (details) details.open = true
        }
        applyDisclosureLayout(editor, node)
        const body = node.querySelector(".afield-symbol-context-disclosure-body")
        if (body && previousState.detailsOpen) {
                body.scrollTop = previousState.scrollTop
        }
        node.style.opacity = "1"
        node.style.pointerEvents = "auto"
        editor._afieldSymbolContextMeta = payload
        attachDisclosureToggle(editor, node)
        syncSymbolContextPolling(editor)
}

window.setEditorContextBadge = setEditorContextBadge

const ensureSymbolContextState = () => {
        if (!window.afieldSymbolContextState) {
                        window.afieldSymbolContextState = {
                        inFlight: null,
                        queued: null,
                        latestDesiredKey: null,
                        lastNotifiedKey: null,
                        cache: new Map(),
                }
        }
        return window.afieldSymbolContextState
}

const trimSymbolContextCache = (cache) => {
        while (cache.size > SYMBOL_CONTEXT_CACHE_MAX) {
                const firstKey = cache.keys().next()
                if (firstKey.done) break
                cache.delete(firstKey.value)
        }
}

const findIdentifierBounds = (text, offset) => {
        if (!text || text.length === 0) return null
        const safeOffset = Math.max(0, Math.min(offset, text.length))
        const isIdentifierChar = (ch) => /[A-Za-z0-9_]/.test(ch)

        let probe = -1
        if (safeOffset < text.length && isIdentifierChar(text[safeOffset])) {
                probe = safeOffset
        } else if (safeOffset > 0 && isIdentifierChar(text[safeOffset - 1])) {
                probe = safeOffset - 1
        }
        if (probe < 0) return null

        let start = probe
        while (start > 0 && isIdentifierChar(text[start - 1])) start--

        let end = probe + 1
        while (end < text.length && isIdentifierChar(text[end])) end++

        if (end <= start) return null
        return {
                start,
                end,
                text: text.slice(start, end),
        }
}

const buildSymbolContextRequest = (editor, resolveContext, options = {}) => {
        const allowUnfocused = !!options.allowUnfocused
        if (!editor || (!allowUnfocused && typeof editor.hasTextFocus === "function" && !editor.hasTextFocus())) return null
        const model = editor.getModel && editor.getModel()
        if (!model || model.getLanguageId() !== "kotlin") return null

        const context = resolveContext ? resolveContext(editor) : null
        if (!context || !context.uid || !context.property) return null

        const position = editor.getPosition && editor.getPosition()
        if (!position) return null

        const allCode = model.getValue()
        const offset = model.getOffsetAt(position)
        const bounds = findIdentifierBounds(allCode, offset)
        if (!bounds || !bounds.text) return null

        return {
                key: `${context.uid}::${context.property}::${model.getVersionId()}::${bounds.start}:${bounds.end}`,
                editor,
                payload: {
                        uid: context.uid,
                        property: context.property,
                        allCode,
                        line: position.lineNumber,
                        column: position.column,
                        position: offset,
                        forceRefresh: false,
                },
        }
}

const dispatchSymbolContextRequest = (request, options = {}) => {
        const state = ensureSymbolContextState()
        state.inFlight = request

        const requestPayload = {
                ...request.payload,
                forceRefresh: !!options.forceRefresh,
        }

        sendWithReply("symbol-context", requestPayload, "symbol-context", (message, payload) => {
                const completed = state.inFlight
                state.inFlight = null

                if (completed && payload && typeof payload.html === "string") {
                        const cachedPayload = {
                                html: payload.html || "",
                                isLiveValue: !!payload.isLiveValue,
                                key: completed.key,
                        }
                        if (!cachedPayload.isLiveValue) {
                                state.cache.set(completed.key, cachedPayload)
                                trimSymbolContextCache(state.cache)
                        } else {
                                state.cache.delete(completed.key)
                        }

                        if (state.latestDesiredKey === completed.key) {
                                const previousHtml = completed.editor && completed.editor._afieldSymbolContextMeta
                                        ? completed.editor._afieldSymbolContextMeta.html
                                        : null
                                if (previousHtml !== cachedPayload.html ||
                                        !completed.editor._afieldSymbolContextMeta ||
                                        completed.editor._afieldSymbolContextMeta.isLiveValue !== cachedPayload.isLiveValue) {
                                        setEditorContextBadge(completed.editor, cachedPayload)
                                } else {
                                        completed.editor._afieldSymbolContextMeta = cachedPayload
                                        syncSymbolContextPolling(completed.editor)
                                }
                                state.lastNotifiedKey = completed.key
                        }
                }

                if (state.queued && (!completed || state.queued.key !== completed.key)) {
                        const next = state.queued
                        state.queued = null
                        dispatchSymbolContextRequest(next)
                }

                return true
        })
}

const queueSymbolContextRequest = (request) => {
        const state = ensureSymbolContextState()
        state.latestDesiredKey = request.key

        if (state.lastNotifiedKey === request.key) return
        if (state.inFlight && state.inFlight.key === request.key) return
        if (state.queued && state.queued.key === request.key) return

        if (state.cache.has(request.key)) {
                const cached = state.cache.get(request.key)
                if (cached && cached.isLiveValue) {
                        state.cache.delete(request.key)
                } else {
                setEditorContextBadge(request.editor, cached)
                state.lastNotifiedKey = request.key
                return
                }
        }

        if (state.inFlight) {
                state.queued = request
                return
        }

        dispatchSymbolContextRequest(request)
}

const installSymbolContextMonitor = (editor, resolveContext) => {
        if (!editor || editor._afieldSymbolContextInstalled) return
        editor._afieldSymbolContextInstalled = true
        editor._afieldSymbolContextResolveContext = resolveContext

        let debounceTimer = null
        const schedule = () => {
                if (debounceTimer !== null) clearTimeout(debounceTimer)
                debounceTimer = setTimeout(() => {
                        debounceTimer = null
                        const request = buildSymbolContextRequest(editor, resolveContext)
                        if (!request) {
                                setEditorContextBadge(editor, "")
                                return
                        }
                        queueSymbolContextRequest(request)
                }, SYMBOL_CONTEXT_DEBOUNCE_MS)
        }

        editor.onDidChangeCursorPosition(() => {
                stopSymbolContextPolling(editor)
                schedule()
        })
        if (typeof editor.onDidFocusEditorText === "function") {
                editor.onDidFocusEditorText(() => {
                        schedule()
                })
        }
        editor.onDidChangeModelContent(() => {
                const state = ensureSymbolContextState()
                state.lastNotifiedKey = null
                stopSymbolContextPolling(editor)
                schedule()
        })
    }

window.installSymbolContextMonitor = installSymbolContextMonitor

const isSuggestWidgetVisible = () => {
        const widget = document.querySelector('.editor-widget.suggest-widget');
        if (!widget) return false;
        const style = window.getComputedStyle(widget);
        if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return false;
        return widget.classList.contains('visible') || widget.classList.contains('show');
}

const initMonaco = () => {
        console.info("initializing monaco")
        let ed = monacoInit(monaco) || monaco.editor.getEditors()[0]

        monacoRegisterActions(ed, monaco)

        installNumberCommands(ed)


        // register a no-keybinding command and get its ID
        const acceptCommandId = ed.addCommand(0, (ctx, ...args) => {
                ed.getModel().applyEdits([{
                        range: new monaco.Range(1, 1, 1, 1), text: args[0].trim() + "\n", forceMoveMarkers: true
                }]);
        }, '');

        var completionProvider = {
                triggerCharacters: ['.'],
                provideCompletionItems: (model, position, context, cancellationToken) => {
                        var word = model.getWordUntilPosition(position);
                        var languageId = model.getLanguageId()
                        var range = {
                                startLineNumber: position.lineNumber,
                                endLineNumber: position.lineNumber,
                                startColumn: word.startColumn,
                                endColumn: word.endColumn,
                        };

                        var completionInfo = {
                                allCode: model.getValue(), at: [position.lineNumber, position.column], word: word,
                        };

                        monacoLog("complete me", completionInfo, range, ed.getModel().getOffsetAt(completionInfo))

                        const currentGen = window.currentEditorGeneration
                        const sessionKey = `${model.uri.toString()}::${window.currentSelectedProperty}::${position.lineNumber}:${position.column}`

                        if (!window.kotlinAiCompletion) window.kotlinAiCompletion = null
                        if (window.kotlinAiCompletion && window.kotlinAiCompletion.key === sessionKey && window.kotlinAiCompletion.useCacheOnce) {
                                const cached = window.kotlinAiCompletion
                                cached.useCacheOnce = false
                                return {suggestions: cached.merged || cached.base || [], incomplete: false}
                        }

// Resolve uid/property: check embedded editor first, fall back to main editor globals
				const embEntry = window.embeddedEditors && window.embeddedEditors.getByModel(model);
				const completionUid = embEntry ? embEntry.uid : (window.currentSelectedCode && window.currentSelectedCode[0] ? window.currentSelectedCode[0].uid : null);
				const completionProperty = embEntry ? embEntry.property : window.currentSelectedProperty;
				if (!completionUid) return { suggestions: [] };

				const basePayload = {
					uid: completionUid,
					line: completionInfo.at[0],
					allCode: completionInfo.allCode,
					column: completionInfo.at[1],
					position: model.getOffsetAt({
						column: completionInfo.at[1], lineNumber: completionInfo.at[0]
					}),
					property: completionProperty
                        }

                        const rangeForPayload = (payload) => {
                                if (payload.useLineColumns) {
                                        return {
                                                startLineNumber: payload.lineStart,
                                                endLineNumber: payload.lineStart,
                                                startColumn: payload.columnsStart + 1,
                                                endColumn: payload.columnsStart + 1,
                                        }
                                }
                                var start = ed.getModel().getPositionAt(payload.boundsStart)
                                var end = ed.getModel().getPositionAt(payload.boundsEnd)
                                return {
                                        startLineNumber: start.lineNumber,
                                        endLineNumber: end.lineNumber,
                                        startColumn: start.column,
                                        endColumn: end.column
                                }
                        }

                        const buildSuggestions = (payload, opts = {}) => {
                                let idx = opts.offset || 0
                                const rangeObj = rangeForPayload(payload)
                                return payload.metadata.map(m => {
                                        let command = null
                                        if (m.prefix) {
                                                command = {
                                                        id: acceptCommandId,
                                                        title: '__completionAccepted_prefix',
                                                        arguments: [m.prefix],
                                                }
                                        }

                                        const sortPrefix = opts.ai ? "9000" : "1000"
                                        const sortText = sortPrefix + (idx++).toString(36).padStart(6, "0")

                                        return {
                                                label: m.displayText,
                                                insertText: m.text,
                                                filterText: m.text,
                                                range: rangeObj,
                                                kind: kindForIcon(m.icon),
                                                detail: m.tail,
                                                sortText: sortText,
                                                preselect: false,
                                                documentation: {
                                                        value: m.documentation,
                                                        isTrusted: true,
                                                        supportThemeIcons: true,
                                                        supportHtml: true,
                                                },
                                                data: {
                                                        source: languageId,
                                                        displayText: m.displayText,
                                                        insertText: m.text,
                                                        detail: m.tail,
                                                        aiDocPending: false,
                                                        aiDocResolved: !!opts.ai,
                                                        ai: !!opts.ai
                                                },
                                                command: command
                                        }
                                })
                        }

                        window.kotlinAiCompletion = {
                                key: sessionKey,
                                gen: currentGen,
                                base: [],
                                ai: [],
                                merged: [],
                                useCacheOnce: false
                        }

                        if (languageId === "kotlin") {
                                sendWithReply("complete-kotlin-ai", basePayload, "complete-kotlin-ai", (message, payload) => {
                                        if (window.currentEditorGeneration !== currentGen) return true
                                        if (!window.kotlinAiCompletion || window.kotlinAiCompletion.key !== sessionKey) return true
                                        const aiSuggestions = buildSuggestions(payload, {offset: 0, ai: true})
                                        window.kotlinAiCompletion.ai = aiSuggestions
                                        const baseExisting = window.kotlinAiCompletion.base || []
                                        const hasBase = baseExisting.length > 0
                                        window.kotlinAiCompletion.merged = hasBase ? baseExisting : aiSuggestions
                                        if (!hasBase && aiSuggestions.length > 0) {
                                                window.kotlinAiCompletion.useCacheOnce = true
                                                if (isSuggestWidgetVisible()) {
                                                        try {
                                                                if (globalEditor && globalEditor.trigger) {
                                                                        // Close then re-open to refresh items only if user still has the picker open.
                                                                        globalEditor.trigger("afield-ai", "hideSuggestWidget", {})
                                                                        setTimeout(() => {
                                                                                globalEditor.trigger("afield-ai", "editor.action.triggerSuggest", {})
                                                                        }, 0)
                                                                }
                                                        } catch (_) {
                                                        }
                                                }
                                        }
                                        return true
                                })
                        }

                        var ret = new Promise(resolve => {
                                sendWithReply("complete", basePayload, "complete-me", (message, payload) => {

                                        if (window.currentEditorGeneration !== currentGen) return true

                                        const baseSuggestions = buildSuggestions(payload, {offset: 0})
                                        window.kotlinAiCompletion.base = baseSuggestions
                                        const aiExisting = window.kotlinAiCompletion.ai || []
                                        window.kotlinAiCompletion.merged = baseSuggestions.length > 0 ? baseSuggestions : aiExisting

                                        monacoLog("resolving. Answer is ", {suggestions: baseSuggestions})
                                        resolve({suggestions: baseSuggestions})

                                        return true
                                })
                        })
                        return ret
                },
                resolveCompletionItem: (item, cancellationToken) => {
                        try {
                                if (!item || !item.data) return item
                                if (item.data.aiDocResolved || item.data.aiDocPending) return item
                                if (item.data.source !== "kotlin") return item
                                const existingDocValue = (item.documentation && item.documentation.value) ? item.documentation.value.trim() : ""
                                if (existingDocValue.length > 0) {
                                        item.data.aiDocResolved = true
                                        return item
                                }

                                // Find the right editor+model: check focused embedded editor first, fall back to globalEditor
                                const embFocused = window.embeddedEditors && window.embeddedEditors.getFocused();
                                const resolveEditor = embFocused ? embFocused.editor : globalEditor;
                                if (!resolveEditor || !resolveEditor.getModel) return item
                                var model = resolveEditor.getModel()
                                if (!model) return item
                                var pos = resolveEditor.getPosition()
                                if (!pos) return item

                                const resolveUid = embFocused ? embFocused.uid : (window.currentSelectedCode && window.currentSelectedCode[0] ? window.currentSelectedCode[0].uid : null);
                                const resolveProperty = embFocused ? embFocused.property : window.currentSelectedProperty;
                                if (!resolveUid) return item;

                                item.data.aiDocPending = true
                                var rawLabel = item.label
                                var labelText = (typeof rawLabel === "string") ? rawLabel : ((rawLabel && rawLabel.label) ? rawLabel.label : "")
                                var payload = {
                                        uid: resolveUid,
                                        property: resolveProperty,
                                        label: labelText || "",
                                        insertText: item.data.insertText || item.insertText || "",
                                        detail: item.data.detail || item.detail || "",
                                        displayText: item.data.displayText || labelText || "",
                                        existingDocumentation: existingDocValue,
                                        allCode: model.getValue(),
                                        line: pos.lineNumber,
                                        column: pos.column,
                                        position: model.getOffsetAt({lineNumber: pos.lineNumber, column: pos.column})
                                }

                                return new Promise(resolve => {
                                        sendWithReply("resolve-completion", payload, "resolve-completion", (message, payload) => {
                                                if (payload && payload.documentation) {
                                                        var existing = (item.documentation && item.documentation.value) || ""
                                                        var combined = existing ? (existing + "\n\n---\n\n" + payload.documentation) : payload.documentation
                                                        item.documentation = {
                                                                value: combined,
                                                                isTrusted: true,
                                                                supportThemeIcons: true,
                                                                supportHtml: true,
                                                        }
                                                }
                                                item.data.aiDocPending = false
                                                item.data.aiDocResolved = true
                                                resolve(item)
                                                return true
                                        })
                                })
                        } catch (e) {
                                if (item && item.data) {
                                        item.data.aiDocPending = false
                                        item.data.aiDocResolved = true
                                }
                                return item
                        }
                }
        }
        monaco.languages.registerCompletionItemProvider("kotlin", completionProvider)
        monaco.languages.registerCompletionItemProvider("python", completionProvider)
        monaco.languages.registerCompletionItemProvider("clojure", completionProvider)
        monaco.languages.registerCompletionItemProvider("glsl", completionProvider)
        monaco.languages.registerCompletionItemProvider("slang", completionProvider)
        monaco.languages.registerCompletionItemProvider("forth", completionProvider)
        monaco.languages.registerCompletionItemProvider("nano", completionProvider)
        monaco.languages.registerCompletionItemProvider("yaml", completionProvider)


        monacoLog("monitoring selection")
        monacoStartSelectionMonitor()

        globalEditor.getModel().onDidChangeContent((event) => {
                clearAllDecorations()
        })

        installSymbolContextMonitor(ed, () => {
                const selected = window.currentSelectedCode || []
                if (!selected[0] || !selected[0].uid) return null
                return {
                        uid: selected[0].uid,
                        property: window.currentSelectedProperty || "code",
                }
        })

        beginLineTracking(globalEditor)
        initMarkerSupport(globalEditor)
}

const syncBoxRunningClass = (box) => {
        box.classList.remove("running", "running-continued")
        const runningEntries = box.runningEntries ?? new Map()
        if (runningEntries.size === 0) return

        const hasFreshLaunch = [...runningEntries.values()].some((mode) => mode !== "continue")
        box.classList.add(hasFreshLaunch ? "running" : "running-continued")
}

const setBoxRunning = (uid, isRunning, watermark, strand, mode = "launch") => {
        // console.log("setBoxRunning, looking for ", uid, isRunning, watermark, strand, mode)
        let first = [...document.querySelectorAll(".box")].find(x => x.uid === uid)
        let name = `${watermark}--${strand}`
        if (first) {
                // Track the highest watermark per strand to ignore stale stop signals
                first.latestWatermark = first.latestWatermark ?? new Map()
                first.runningEntries = first.runningEntries ?? new Map()
                const lastWatermark = first.latestWatermark.get(strand) ?? -Infinity

                // console.log(" is running ", first, first.runningEntries, watermark, isRunning, mode)
                if (isRunning) {
                        first.runningEntries.set(name, mode)
                        if (watermark > lastWatermark) first.latestWatermark.set(strand, watermark)
                        syncBoxRunningClass(first)
                        setStatusForExecutionMarker(strand, true, mode)

                } else {
                        // Ignore stop messages that are older than the latest start we saw
                        if (watermark < lastWatermark) return

                        setStatusForExecutionMarker(strand, false, mode)

                        if (first.runningEntries.delete(name)) {

                        } else {
                                // TODO
                                monacoLog("tried to remove a watermark that wasn't there; clearing running state")
                                first.runningEntries.clear()
                        }
                        syncBoxRunningClass(first)
                        setTimeout(() => {
                                syncBoxRunningClass(first)
                        }, 100)
                }
                // console.log(" outOfrunning ", first, first.runningEntries, watermark, isRunning, mode)
        } else {
                console.warn("warning: tried to set running status on something that doesn't exist?", uid)
        }
}
window.setBoxRunning = setBoxRunning

let hideAnimationGeneration = 0

const hideMonaco = () => {
        hideAnimationGeneration++
        let a = hideAnimationGeneration
        document.querySelector('.monaco-editor-container').style.transition = "opacity 0.5s"
        document.querySelector('.monaco-editor-container').style.opacity = "0"
        setTimeout(() => {
                if (hideAnimationGeneration === a) document.querySelector('.monaco-editor-container').style.visibility = "hidden"
        }, 500)
}

const showMonaco = () => {
        hideAnimationGeneration++
        document.querySelector('.monaco-editor-container').style.visibility = "visible"
        document.querySelector('.monaco-editor-container').style.transition = "opacity 0.05s"
        document.querySelector('.monaco-editor-container').style.opacity = "1"
}

const showActionMenu = () => {
        if (window.triggerQuickInput) {
                triggerQuickInput(() => {
                        globalEditor.getAction("editor.action.quickCommand").run()
                })
        } else {
                globalEditor.getAction("editor.action.quickCommand").run()
        }
}

const showPickMenu_custom = (options) => {
        if (window.triggerQuickInput) {
                triggerQuickInput(() => {
                        globalPickMenu_custom(options)
                })
        } else {
                globalPickMenu_custom(options)
        }
}

// Expose for Kotlin/JS callers (e.g., insertFromWorkspace in commands.kt)
window.showPickMenu_custom = showPickMenu_custom

const showPickMenu = () => {
        if (window.triggerQuickInput) {
                triggerQuickInput(() => {
                        globalPickMenu()
                })
        } else {
                globalPickMenu()
        }
}

const openQuickCommandPalette = () => {
        const monacoTextArea = document.querySelector("textarea.inputarea.monaco-mouse-cursor-text")
        const isEditorFocused = document.activeElement === monacoTextArea

        if (isDocsIframeVisible()) {
                hideDocsIframe()
        }

        if (isEditorFocused && window.globalEditor) {
                window.globalEditor.trigger("", "editor.action.quickCommand", null);
        } else {
                if (typeof updateActions === "function") {
                        updateActions()
                }
                showPickMenu()
        }
}

window.openQuickCommandPalette = openQuickCommandPalette

const syncDocsIframeBoundsToMonaco = () => {
        const monacoContainer = document.querySelector('.monaco-editor-container')
        const docsContainer = document.querySelector('.docs-iframe-container')
        if (!monacoContainer || !docsContainer) return

        const rect = monacoContainer.getBoundingClientRect()
        docsContainer.style.position = "absolute"
        docsContainer.style.top = `${rect.top + window.scrollY}px`
        docsContainer.style.left = `${rect.left + window.scrollX}px`
        docsContainer.style.width = `${rect.width}px`
        docsContainer.style.height = `${rect.height}px`
        docsContainer.style.right = "auto"
        docsContainer.style.bottom = "auto"
}

window.addEventListener("resize", syncDocsIframeBoundsToMonaco)

window.addEventListener("keydown", (e) => {
            const isCtrlOrCmdP = (e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'p';
            if (e.key === "F1" || isCtrlOrCmdP) {
                    // If an embedded editor has focus, let it handle F1 itself
                    // (it has its own command palette action registered)
                    if (window.embeddedEditors && window.embeddedEditors.getFocused()) {
                            return;
                    }
                    e.preventDefault();
                    e.stopImmediatePropagation();
                    openQuickCommandPalette()
            }
    }, {capture: true}  // run before the browser’s own handlers
)

const isMonacoTextTarget = (el) => {
        if (!el) return false
        const classList = el.classList
        if (classList && (classList.contains("inputarea") || classList.contains("monaco-mouse-cursor-text"))) return true
        const closest = el.closest ? el.closest(".monaco-editor") : null
        if (!closest) return false
        if (el.closest && (el.closest(".monaco-findInput") || el.closest(".monaco-inputbox"))) return false
        return true
}

const isMonacoTextFocused = () => {
        const ed = window.globalEditor
        if (ed && typeof ed.hasTextFocus === "function" && ed.hasTextFocus()) return true
        if (window.embeddedEditors && window.embeddedEditors.getFocused()) return true
        return isMonacoTextTarget(document.activeElement)
}

const runMonacoAction = (id) => {
        // Prefer the focused embedded editor, fall back to the main editor
        const emb = window.embeddedEditors && window.embeddedEditors.getFocused();
        const ed = emb ? emb.editor : window.globalEditor;
        if (!ed) return false
        try {
                const action = ed.getAction && ed.getAction(id)
                if (action && typeof action.run === "function") {
                        action.run()
                        return true
                }
        } catch (_) {}
        try {
                ed.trigger("", id, null)
                return true
        } catch (_) {}
        return false
}

window.addEventListener("keydown", (e) => {
        const isCtrlOrCmd = (e.ctrlKey || e.metaKey) && !e.altKey
        if (!isCtrlOrCmd) return
        const key = (e.key || "").toLowerCase()
        if (key !== "a" && key !== "z") return
        if (!isMonacoTextFocused()) return

        e.preventDefault()
        e.stopImmediatePropagation()

        if (key === "a") {
                runMonacoAction("editor.action.selectAll")
        } else if (key === "z") {
                const cmd = e.shiftKey ? "redo" : "undo"
                runMonacoAction(cmd)
        }
}, {capture: true})

const isDocsIframeVisible = () => {
        const container = document.querySelector('.docs-iframe-container')
        if (!container) return false
        const style = window.getComputedStyle(container)
        return style.visibility !== "hidden" && style.opacity !== "0"
}

const docsIframeKeydownHandler = (e) => {
        const isCtrlOrCmdP = (e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'p'
        if (e.key !== "F1" && !isCtrlOrCmdP) return

        e.preventDefault()
        e.stopPropagation()
        if (typeof hideDocsIframe === "function") hideDocsIframe()
        openQuickCommandPalette()
}

const wireDocsIframeKeyForwarding = () => {
        const iframe = document.getElementById('docs-iframe')
        if (!iframe) return
        if (iframe.dataset.keyForwardingWired === "true") return
        iframe.dataset.keyForwardingWired = "true"

        const attach = () => {
                try {
                        const win = iframe.contentWindow
                        if (!win) return
                        win.removeEventListener('keydown', docsIframeKeydownHandler, true)
                        win.addEventListener('keydown', docsIframeKeydownHandler, true)
                } catch (err) {
                        console.warn('Unable to attach key handler to docs iframe', err)
                }
        }

        iframe.addEventListener('load', () => setTimeout(attach, 0))
        attach()
}

window.addEventListener("keydown", (e) => {
        const isCtrlOrCmdSlash = (e.ctrlKey || e.metaKey) && (e.key === "/" || e.key === "÷")
        if (!isCtrlOrCmdSlash) return

        e.preventDefault()
        e.stopImmediatePropagation()

        if (isDocsIframeVisible()) {
                if (typeof window.exitDocsIframe === "function") {
                        window.exitDocsIframe()
                } else {
                        hideDocsIframe()
                }
        } else {
                window.switchToDocsIframe(null)
        }
}, {capture: true})

window.showActionMenu = showActionMenu


/**
 * Returns the largest group of lines that encloses line x in curly brackets.
 * If no such group exists, it returns the starting line (i.e. the line at index x).
 *
 * @param {number} x - The line number to check.
 * @returns {string[] | null} - An array of lines corresponding to the scope, or null if x is invalid.
 */
function getEnclosingScope(x, getLine) {
        return monacoGetEnclosingScope(x, getLine)
}

window.handleOutput = monacoHandleOutput
window.hideMonaco = hideMonaco
window.showMonaco = showMonaco
window.launchAll = monacoLaunchAll
window.continueAll = monacoContinueAll

// Documentation iframe support
let docsIframeAnimationGeneration = 0

const defaultDocsIframeUrl = "https://openendedgroup.com/field4/"
const historyBaseUrl = new URL("/history", window.location.href).toString()

const normalizeIframeUrl = (url) => {
	if (!url) return null
	try {
		return new URL(url, window.location.href).toString()
	} catch (_) {
		return url
	}
}

const historyUrlForFile = (filename) => {
	const filePart = filename ? encodeURIComponent(filename) : "default"
	return `${historyBaseUrl}?file=${filePart}`
}

const ensureDocsIframeHasContent = () => {
	const iframe = document.getElementById('docs-iframe')
	if (!iframe) return
	wireDocsIframeKeyForwarding()
	const dataset = iframe.dataset || {}
	if (dataset.docsLoadedOnce === "true") return

	const currentSrc = iframe.getAttribute("src") || ""
	const isBlank = !currentSrc || currentSrc === "about:blank" || currentSrc === window.location.href
	if (isBlank) {
		setDocsIframeContent(defaultDocsIframeUrl)
	}
	if (iframe.dataset.docsLoadedOnce !== "true") {
		iframe.dataset.docsLoadedOnce = "true"
	}
}

const showDocsIframe = () => {
        docsIframeAnimationGeneration++
        const container = document.querySelector('.docs-iframe-container')
        syncDocsIframeBoundsToMonaco()
        ensureDocsIframeHasContent()
	if (container) {
		container.style.visibility = "visible"
		container.style.transition = "opacity 0.05s"
		container.style.opacity = "1"
	}
}

const hideDocsIframe = () => {
	docsIframeAnimationGeneration++
	let a = docsIframeAnimationGeneration
        const container = document.querySelector('.docs-iframe-container')
        if (container) {
                container.style.transition = "opacity 0.5s"
                container.style.opacity = "0"
                setTimeout(() => {
			if (docsIframeAnimationGeneration === a) {
				container.style.visibility = "hidden"
			}
		}, 500)
	}
}

const setDocsIframeContent = (url) => {
	const iframe = document.getElementById('docs-iframe')
	if (iframe) {
		const normalizedUrl = normalizeIframeUrl(url)
		if (!normalizedUrl) return
		const alreadyLoaded = iframe.dataset.docsLoadedOnce === "true"
		const currentSrc = normalizeIframeUrl(iframe.getAttribute("src") || "") || ""
		const shouldChange = !alreadyLoaded || normalizedUrl !== currentSrc
		if (shouldChange) {
			iframe.src = normalizedUrl
		}
		if (!alreadyLoaded || shouldChange) {
			iframe.dataset.docsLoadedOnce = "true"
		}
	}
}

const getDocsIframeElement = () => {
	return document.getElementById('docs-iframe')
}

// Switch between Monaco editor and documentation iframe
const switchToMonaco = () => {
	hideDocsIframe()
	showMonaco()
	try {
		if (window.globalEditor && typeof window.globalEditor.focus === "function") {
			window.globalEditor.focus()
		} else {
			const textarea = document.querySelector("textarea.inputarea.monaco-mouse-cursor-text")
			if (textarea) textarea.focus()
		}
	} catch (e) {
		console.warn("Unable to focus Monaco after switching", e)
	}
}

const switchToDocsIframe = (url) => {
	hideMonaco()
	if (url) {
		setDocsIframeContent(url)
	}
	showDocsIframe()
}

const switchToDefaultDocs = () => {
	switchToDocsIframe(defaultDocsIframeUrl)
}

const switchToHistoryForFile = (filename) => {
	const url = historyUrlForFile(filename)
	switchToDocsIframe(url)
}

window.showDocsIframe = showDocsIframe
window.hideDocsIframe = hideDocsIframe
window.setDocsIframeContent = setDocsIframeContent
window.getDocsIframeElement = getDocsIframeElement
window.switchToMonaco = switchToMonaco
window.switchToDocsIframe = switchToDocsIframe
window.switchToDefaultDocs = switchToDefaultDocs
window.switchToHistoryForFile = switchToHistoryForFile
window.defaultDocsIframeUrl = defaultDocsIframeUrl
window.hideDocsIframeOnly = () => {
	hideDocsIframe()
}
window.exitDocsIframe = () => {
	hideDocsIframe()
	const selection = window.selectedList
	if (selection && selection.length === 1) {
		showMonaco()
	}
}

const docsIframeApiUrl = new URL("docsIframeApi.js", window.location.href).toString()

const injectDocsApiIntoIframe = (iframe) => {
	if (!iframe) return

	try {
		const targetWindow = iframe.contentWindow
		const doc = targetWindow && targetWindow.document
		if (!targetWindow || !doc) return
		if (targetWindow.FieldAPI) return
		if (doc.querySelector('script[data-field-api-injected="true"]')) return

		const script = doc.createElement("script")
		script.src = docsIframeApiUrl
		script.defer = true
		script.dataset.fieldApiInjected = "true"
		;(doc.head || doc.body || doc.documentElement).appendChild(script)
	} catch (error) {
		console.warn("Unable to inject docs iframe API", error)
	}
}

const wireDocsIframe = () => {
	const iframe = getDocsIframeElement()
	if (!iframe || iframe.dataset.fieldApiWired) return
	iframe.dataset.fieldApiWired = "true"
	iframe.addEventListener("load", () => {
		injectDocsApiIntoIframe(iframe)
	})
}

wireDocsIframe()


// fails to inline with js()
window._saveAsTemplateHelper = function (selectedIds) {
        let ed = window.ensureEditor && window.ensureEditor();
        if (!ed) {
                console.error("No editor available for quick input");
                return;
        }
        let cmd = ed.addCommand(0, (accessor, func) => {
                let quickInputService = accessor.get(window.globalIS.IQuickInputService);
                func(quickInputService);
        });
        ed.trigger("", cmd, (quickInput) => {
                let inputBox = quickInput.createInputBox();
                inputBox.placeholder = "Enter template name";
                inputBox.prompt = "Save selection as template";
                inputBox.onDidAccept(() => {
                        let templateName = inputBox.value.trim();

                        // Comprehensive validation
                        if (!templateName) {
                                return;
                        }

                        // Check for invalid characters and patterns
                        let invalidPatterns = [
                                /[\/\\]/,  // Path separators
                                /\.\./,    // Parent directory
                                /^\.$/,    // Current directory
                                /[\x00]/,  // Null bytes
                                /^\s/,     // Leading space
                                /\s$/      // Trailing space
                        ];

                        let reservedNames = ['metadata', 'con', 'prn', 'aux', 'nul',
                                'com1', 'com2', 'com3', 'com4', 'com5',
                                'com6', 'com7', 'com8', 'com9',
                                'lpt1', 'lpt2', 'lpt3', 'lpt4', 'lpt5',
                                'lpt6', 'lpt7', 'lpt8', 'lpt9'];

                        for (let pattern of invalidPatterns) {
                                if (pattern.test(templateName)) {
                                        import("./afield.mjs").then((afield) => {
                                                afield.notify("Invalid template name. Avoid path separators and special characters.", "error");
                                        });
                                        return;
                                }
                        }

                        if (reservedNames.includes(templateName.toLowerCase())) {
                                import("./afield.mjs").then((afield) => {
                                        afield.notify("Invalid template name. This is a reserved name.", "error");
                                });
                                return;
                        }

                        inputBox.hide();
                        import("./afield.mjs").then((afield) => {
                                afield.send("save-as-template", {
                                        uids: selectedIds,
                                        templateName: templateName
                                });
                        });
                });
                inputBox.onDidHide(() => {
                        inputBox.dispose();
                        delete window._saveAsTemplateHelper;
                });
                inputBox.show();
        });
}
