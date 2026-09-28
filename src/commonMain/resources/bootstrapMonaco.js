
import {
        sendWithReply,
        monacoInit,
        monacoRegisterActions,
        monacoStartSelectionMonitor,
	monacoHandleOutput,
	monacoLaunchAll,
	monacoContinueAll,
	monacoGetEnclosingScope
} from "./afield.mjs"
import { renderFieldMarkdown } from "./markdownView.js"
import { installFieldSuggest, requestLineNote, lineNoteHtml } from "./fieldSuggest.js"

if (!window.require || typeof window.require.config !== "function") {
        throw new Error("Monaco AMD loader is missing; expected /min/vs/loader.js to define window.require before bootstrapMonaco.js")
}

window.require.config({
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

const loadOptionalPersonalLanguageExtensions = () => {
        window.require(["bithero/bend"], function () {
                if (window.globalEditor && typeof window.updateLanguageExtensions === "function") {
                        window.updateLanguageExtensions(window.globalEditor)
                }
        }, function (err) {
                console.info("optional Bend language extension not loaded", err && (err.message || err))
        })
}

const FIELD_LANGUAGE_MODULES = [
        "bithero/glsl", "bithero/slang", "bithero/msl", "bithero/forth", "bithero/fieldml",
        "bithero/languageExtensions", "bithero/stacksl", "bithero/rectsl", "bithero/datalog", "bithero/fnrewrite",
        "bithero/sonicpi", "bithero/faust",
        "bithero/nano", "bithero/kotlinjs", "bithero/morpho", "bithero/petri", "bithero/brief", "bithero/pd",
]
const LANGUAGE_MODULE_DEADLINE_MS = 10000

// These used to be one grouped require with initMonaco() as its callback, which made every syntax
// highlighter a single point of failure for the whole editor: a module whose factory throws (a
// Monarch grammar that does not compile, say) never settles at all -- the loader runs neither the
// callback nor an errorback -- so the require hung, initMonaco() never ran, and the page came up
// with a live websocket, no Monaco, and nothing in the console. Load them one at a time instead
// and start the editor once they have settled or the deadline passes, naming what did not arrive.
const loadFieldLanguageModules = () => new Promise((resolve) => {
        const missing = new Set(FIELD_LANGUAGE_MODULES)
        let settled = false
        const finish = () => {
                if (settled) return
                settled = true
                clearTimeout(deadline)
                resolve([...missing])
        }
        const deadline = setTimeout(finish, LANGUAGE_MODULE_DEADLINE_MS)
        FIELD_LANGUAGE_MODULES.forEach((id) => {
                const arrived = () => {
                        missing.delete(id)
                        if (missing.size === 0) finish()
                }
                window.require([id], arrived, function (err) {
                        console.error(`language module ${id} failed to load`, err)
                        arrived()
                })
        })
})

window.require(["vs/editor/editor.main"], function (e, is) {
        window.require(["vs/basic-languages/python/python"], function (e, is) {
        });
        window.require(["vs/basic-languages/clojure/clojure"], function (e, is) {
        });
        window.require(["vs/basic-languages/yaml/yaml"], function (e, is) {
        });
        window.require(["vs/basic-languages/markdown/markdown"], function (e, is) {
        });
        loadFieldLanguageModules().then(function (neverLoaded) {
                if (neverLoaded.length) {
                        console.error(
                                "starting Monaco without language modules that never resolved: " +
                                `${neverLoaded.join(", ")} (their factories most likely threw)`)
                }
                initMonaco()
                loadOptionalPersonalLanguageExtensions()
                window.require(["vs/platform/quickinput/common/quickInput"], function (is) {
                        initQuickPick(is)
                })
        })
}, function (err) {
        console.error("Monaco itself failed to load; the page will have no editor", err)
});


window.currentEditorGeneration = window.currentEditorGeneration || 0
window.lastErrorIs = window.lastErrorIs || []
window.currentSelectedCode = window.currentSelectedCode || []
window.currentSelectedProperty = window.currentSelectedProperty || "code"
const DEBUG_MONACO = false;
const monacoLog = (...args) => {
	if (DEBUG_MONACO) console.info(...args);
};

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
                if (typeof editor._afieldScheduleBottomOverlayFade === "function") {
                        editor._afieldScheduleBottomOverlayFade()
                }
        })
}

const setEditorContextBadge = (editor, payload = null) => {
        const node = editor && editor._domElement && editor._domElement.querySelector(".hoverlay-left")
        if (!node) return

        const previousState = captureBadgeViewState(node)
        // What the cursor's line shows (fieldSuggest.js#requestLineNote), under whatever the word says.
        const lineNote = editor._afieldLineNote
        const cursor = editor.getPosition && editor.getPosition()
        const noteHtml = lineNote && cursor && lineNote.line === cursor.lineNumber ? lineNote.html : ""
        const next = (((payload && payload.html) || "") + noteHtml).trim()
        if (next.length === 0) {
                node.innerHTML = ""
                node.style.opacity = "0"
                node.style.pointerEvents = "none"
                editor._afieldSymbolContextMeta = null
                stopSymbolContextPolling(editor)
                if (typeof editor._afieldScheduleBottomOverlayFade === "function") {
                        editor._afieldScheduleBottomOverlayFade()
                }
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
        if (typeof editor._afieldScheduleBottomOverlayFade === "function") {
                editor._afieldScheduleBottomOverlayFade()
        }
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

const escapeProbeHtml = (value) => String(value ?? "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;")

const buildStackSLContextPayload = (editor, resolveContext, options = {}) => {
        return typeof window.buildStackSLContextPayload === "function"
                ? window.buildStackSLContextPayload(editor, resolveContext, options)
                : null
}

const buildLanguageContextPayload = (editor, resolveContext, options = {}) => {
        if (typeof window.buildLanguageContextPayload === "function") {
                const payload = window.buildLanguageContextPayload(editor, resolveContext, options)
                if (payload) return payload
        }
        return buildStackSLContextPayload(editor, resolveContext, options)
}

const installLanguageExtensions = (editor, monacoApi) => {
        if (typeof window.installLanguageExtensions === "function") {
                window.installLanguageExtensions(editor, monacoApi)
        }
}

const installStackSLFlowLens = (editor, monacoApi) => {
        if (typeof window.installStackSLFlowLens === "function") {
                window.installStackSLFlowLens(editor, monacoApi)
        }
}

const installStackSLEmbeddedHighlight = (editor, monacoApi) => {
        if (typeof window.installStackSLEmbeddedHighlight === "function") {
                window.installStackSLEmbeddedHighlight(editor, monacoApi)
        }
}

const newDebugProbeRequestId = () => {
        if (window.crypto && typeof window.crypto.randomUUID === "function") {
                return window.crypto.randomUUID()
        }
        return `debug-probe-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

const buildDebugProbeRequest = (editor, resolveContext, positionOverride = null) => {
        const model = editor && editor.getModel && editor.getModel()
        if (!model || model.getLanguageId() !== "kotlin") return null

        const context = resolveContext ? resolveContext(editor) : null
        if (!context || !context.uid || !context.property) return null

        const position = positionOverride || (editor.getPosition && editor.getPosition())
        if (!position) return null

        const allCode = model.getValue()
        const offset = model.getOffsetAt(position)
        const bounds = findIdentifierBounds(allCode, offset)
        if (!bounds || !bounds.text) return null

        return {
                editor,
                payload: {
                        requestId: newDebugProbeRequestId(),
                        uid: context.uid,
                        property: context.property,
                        allCode,
                        line: position.lineNumber,
                        position: offset,
                        symbol: bounds.text,
                        maxHits: 24,
                        timeoutMs: 8000,
                },
        }
}

const findDebugProbeBox = (uid) => {
        if (!uid || !document || !document.body) return null
        const boxes = Array.from(document.body.getElementsByClassName("box"))
        return boxes.find((box) => box && box.uid === uid) || null
}

const ensureDebugProbeOutputRoot = (uid, channel) => {
        const box = findDebugProbeBox(uid)
        if (!box) return null
        const outputArea = box.querySelector && box.querySelector(".outputArea")
        if (!outputArea) return null
        outputArea.style.visibility = "visible"
        outputArea.style.height = "auto"
        const contain = outputArea.querySelector(".disclosure-group")
        if (!contain || typeof window.disclosureInterface !== "function") return null
        const inter = window.disclosureInterface(contain)
        if (!inter || typeof inter.upsertDisclosure !== "function") return null
        const channelId = channel || "debug probe"
        const contentRoot = inter.upsertDisclosure(channelId, "Debug Probe", "")
        if (typeof inter.open === "function") inter.open(channelId)
        return contentRoot || null
}

const renderDebugProbeEvent = (editor, payload) => {
        if (!payload) return
        let html = typeof payload.html === "string" ? payload.html : ""
        if (html.trim().length === 0) return
        // Wrap debug probe output in a container that reduces font size by 30%, except for .h2 visualizer
        html = `<div class='afield-debug-probe-root'>${html}</div>`
        const root = ensureDebugProbeOutputRoot(payload.uid, payload.channel)
        if (root && "innerHTML" in root) {
                root.innerHTML = html
                return
        }
        if (!editor || typeof window.addOutput !== "function") return
        const line = Number(payload.line || 1)
        const annotation = payload.annotation || ""
        const sequence = Number(payload.sequence || Date.now())
        window.addOutput(editor, html, line, "normal", annotation, sequence, -1, -1, -1)
}

const installDebugProbeDoubleClick = (editor, resolveContext) => {
        if (!editor || editor._afieldDebugProbeInstalled) return
        editor._afieldDebugProbeInstalled = true

        editor.onMouseDown((e) => {
                const altKey = !!(e && e.event && e.event.altKey)
                const shiftKey = !!(e && e.event && e.event.shiftKey)
                const detail = Number((e && e.event && e.event.detail) || 0)
                if (!(altKey && shiftKey && detail === 2)) return

                const position = e && e.target && e.target.position
                const request = buildDebugProbeRequest(editor, resolveContext, position)
                if (!request) return

                const requestId = request.payload.requestId
                const placeholder = {
                        requestId,
                        uid: request.payload.uid,
                        channel: "debug probe",
                        line: request.payload.line,
                        annotation: `<i>debug ${escapeProbeHtml(request.payload.symbol)}</i>`,
                        html: `<div class='afield-debug-probe-root'><div class='afield-debug-probe-header'><div class='afield-debug-probe-title'><strong>debug probe</strong> <code>${escapeProbeHtml(request.payload.symbol)}</code></div><span class='afield-debug-probe-pill'>waiting</span></div><div class='afield-debug-probe-body'>Attaching to the JVM helper…</div></div>`,
                        sequence: 0,
                }
                renderDebugProbeEvent(editor, placeholder)

                window.afieldActiveDebugProbe = {
                        requestId,
                        editor,
                        uid: request.payload.uid,
                }

                sendWithReply("debug-probe", request.payload, "debug-probe-event", (message, payload) => {
                        const active = window.afieldActiveDebugProbe
                        if (!active || !payload || payload.requestId !== active.requestId) return true
                        renderDebugProbeEvent(active.editor, payload)
                        if (payload.done) {
                                window.afieldActiveDebugProbe = null
                        }
                        return true
                })
        })
}

window.installDebugProbeDoubleClick = installDebugProbeDoubleClick

const buildSymbolContextRequest = (editor, resolveContext, options = {}) => {
        const allowUnfocused = !!options.allowUnfocused
        if (!editor || (!allowUnfocused && typeof editor.hasTextFocus === "function" && !editor.hasTextFocus())) return null
        const model = editor.getModel && editor.getModel()
        if (!model || !["kotlin", "fieldml"].includes(model.getLanguageId())) return null

        const context = resolveContext ? resolveContext(editor) : null
        if (!context || !context.uid || !context.property) return null

        const position = editor.getPosition && editor.getPosition()
        if (!position) return null

        const allCode = model.getValue()
        const offset = model.getOffsetAt(position)
        const bounds = findIdentifierBounds(allCode, offset)
        if (!bounds || !bounds.text) return null

        return {
                key: `${model.getLanguageId()}::${context.uid}::${context.property}::${model.getVersionId()}::${bounds.start}:${bounds.end}`,
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

const GLSL_CONTEXT_PROPERTIES = new Set([
        "vertex", "fragment", "compute", "task", "mesh",
        "raygen", "miss", "closesthit", "anyhit", "intersection", "callable"
])

const buildGlslContextRequest = (editor, resolveContext, options = {}) => {
        const allowUnfocused = !!options.allowUnfocused
        if (!editor || (!allowUnfocused && typeof editor.hasTextFocus === "function" && !editor.hasTextFocus())) return null
        const model = editor.getModel && editor.getModel()
        if (!model || model.getLanguageId() !== "glsl") return null

        const context = resolveContext ? resolveContext(editor) : null
        if (!context || !context.uid || !context.property) return null
        if (!GLSL_CONTEXT_PROPERTIES.has(String(context.property).toLowerCase())) return null

        const position = editor.getPosition && editor.getPosition()
        if (!position) return null

        const allCode = model.getValue()
        const offset = model.getOffsetAt(position)
        const bounds = findIdentifierBounds(allCode, offset)
        const boundsKey = bounds ? `${bounds.start}:${bounds.end}` : `cursor:${offset}`

        return {
                endpoint: "glsl-context",
                replyName: "glsl-context",
                key: `glsl::${context.uid}::${context.property}::${model.getVersionId()}::${boundsKey}`,
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

        sendWithReply(request.endpoint || "symbol-context", requestPayload, request.replyName || "symbol-context", (message, payload) => {
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

const cursorLineIntersectsOverlay = (editor, overlay) => {
        if (!editor || !overlay) return false
        if (overlay.classList.contains("hoverlay-left") && overlay.innerHTML.trim().length === 0) return false

        const style = window.getComputedStyle(overlay)
        if (style.display === "none" || style.visibility === "hidden") return false

        const position = editor.getPosition && editor.getPosition()
        const visible = position && editor.getScrolledVisiblePosition && editor.getScrolledVisiblePosition(position)
        if (!visible || !Number.isFinite(Number(visible.top))) return false

        const rootRect = editor._domElement.getBoundingClientRect()
        const overlayRect = overlay.getBoundingClientRect()
        if (overlayRect.width <= 0 || overlayRect.height <= 0) return false

        const lineTop = rootRect.top + Number(visible.top)
        const visibleHeight = Number(visible.height)
        const lineHeight = Number.isFinite(visibleHeight) && visibleHeight > 0 ? visibleHeight : 18
        const lineBottom = lineTop + lineHeight
        return lineTop < overlayRect.bottom && lineBottom > overlayRect.top
}

const updateEditorBottomOverlayFade = (editor) => {
        if (!editor || !editor._domElement) return

        const dom = editor._domElement
        const leftOverlay = dom.querySelector(".hoverlay-left")
        const rightOverlay = dom.querySelector(".hoverlay")
        dom.classList.toggle(
                "afield-cursor-under-context-badge",
                cursorLineIntersectsOverlay(editor, leftOverlay),
        )
        dom.classList.toggle(
                "afield-cursor-under-right-overlay",
                cursorLineIntersectsOverlay(editor, rightOverlay),
        )
}

const installEditorBottomOverlayFade = (editor) => {
        if (!editor || editor._afieldBottomOverlayFadeInstalled) return
        editor._afieldBottomOverlayFadeInstalled = true

        let queued = false
        const schedule = () => {
                if (queued) return
                queued = true
                requestAnimationFrame(() => {
                        queued = false
                        updateEditorBottomOverlayFade(editor)
                })
        }
        editor._afieldScheduleBottomOverlayFade = schedule

        if (typeof editor.onDidChangeCursorPosition === "function") editor.onDidChangeCursorPosition(schedule)
        if (typeof editor.onDidScrollChange === "function") editor.onDidScrollChange(schedule)
        if (typeof editor.onDidChangeModelContent === "function") editor.onDidChangeModelContent(schedule)
        if (typeof editor.onDidLayoutChange === "function") editor.onDidLayoutChange(schedule)
        if (typeof editor.onDidChangeModel === "function") editor.onDidChangeModel(schedule)
        if (typeof ResizeObserver === "function") {
                const observer = new ResizeObserver(schedule)
                const leftOverlay = editor._domElement && editor._domElement.querySelector(".hoverlay-left")
                if (leftOverlay) observer.observe(leftOverlay)
                editor._afieldBottomOverlayResizeObserver = observer
                if (typeof editor.onDidDispose === "function") {
                        editor.onDidDispose(() => observer.disconnect())
                }
        }
        schedule()
}

window.installEditorBottomOverlayFade = installEditorBottomOverlayFade

// Ask what the cursor's line shows, and redraw the badge with it when the answer is still for that line.
const refreshLineNote = (editor, resolveContext) => {
        const model = editor.getModel && editor.getModel()
        const position = editor.getPosition && editor.getPosition()
        const context = resolveContext ? resolveContext(editor) : null
        if (!model || !position || !context || !context.uid || !context.property) {
                editor._afieldLineNote = null
                return
        }
        const line = position.lineNumber
        const version = model.getVersionId()
        requestLineNote(context.uid, context.property, model.getValue(), line).then((note) => {
                const now = editor.getPosition && editor.getPosition()
                if (!now || now.lineNumber !== line || editor.getModel() !== model || model.getVersionId() !== version) return
                const html = note ? lineNoteHtml(note) : ""
                const before = editor._afieldLineNote ? editor._afieldLineNote.html : ""
                editor._afieldLineNote = html ? { line, html } : null
                if (html !== before) setEditorContextBadge(editor, editor._afieldSymbolContextMeta)
        })
}

const installSymbolContextMonitor = (editor, resolveContext) => {
        if (!editor || editor._afieldSymbolContextInstalled) return
        editor._afieldSymbolContextInstalled = true
        editor._afieldSymbolContextResolveContext = resolveContext
        installEditorBottomOverlayFade(editor)

        let debounceTimer = null
        const schedule = () => {
                if (debounceTimer !== null) clearTimeout(debounceTimer)
                debounceTimer = setTimeout(() => {
                        debounceTimer = null
                        const suggestSignatureUntil = Number(editor._afieldSuggestSignatureUntil || 0)
                        if (suggestSignatureUntil > Date.now()) return
                        if (suggestSignatureUntil > 0) editor._afieldSuggestSignatureUntil = 0
                        refreshLineNote(editor, resolveContext)
                        const localPayload = buildLanguageContextPayload(editor, resolveContext)
                        if (localPayload) {
                                const state = ensureSymbolContextState()
                                state.latestDesiredKey = localPayload.key
                                state.queued = null
                                setEditorContextBadge(editor, localPayload)
                                state.lastNotifiedKey = localPayload.key
                                return
                        }
                        const glslRequest = buildGlslContextRequest(editor, resolveContext)
                        if (glslRequest) {
                                queueSymbolContextRequest(glslRequest)
                                return
                        }
                        const request = buildSymbolContextRequest(editor, resolveContext)
                        if (!request) {
                                const state = ensureSymbolContextState()
                                state.latestDesiredKey = null
                                state.queued = null
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

const initMonaco = () => {
	console.info("initializing monaco")
        let ed = monacoInit(monaco) || monaco.editor.getEditors()[0]

        monacoRegisterActions(ed, monaco)
        installEditorBottomOverlayFade(ed)
	installFieldSuggest(ed, monaco)

	installNumberCommands(ed)


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
        installLanguageExtensions(ed, monaco)
        installStackSLFlowLens(ed, monaco)
        installStackSLEmbeddedHighlight(ed, monaco)
        installDebugProbeDoubleClick(ed, () => {
                const selected = window.currentSelectedCode || []
                if (!selected[0] || !selected[0].uid) return null
                return {
                        uid: selected[0].uid,
                        property: window.currentSelectedProperty || "code",
                }
        })

        beginLineTracking(globalEditor)
        initMarkerSupport(globalEditor)
        window.LineHeatmap?.install?.(globalEditor)
        window.CommentBlocks?.install?.(globalEditor, monaco)
}

const syncBoxRunningClass = (box) => {
        box.classList.remove("running", "running-launching", "running-continued", "running-ending")
        const runningEntries = box.runningEntries ?? new Map()
        if (runningEntries.size === 0) return

        const modes = [...runningEntries.values()]
        const hasFreshLaunch = modes.some((mode) => mode !== "continue" && mode !== "ending" && mode !== "launching")
        const hasLaunching = !hasFreshLaunch && modes.some((mode) => mode === "launching")
        const hasEndingOnly = !hasFreshLaunch && modes.some((mode) => mode === "ending")
        box.classList.add(
                hasFreshLaunch ? "running" : hasLaunching ? "running-launching" : hasEndingOnly ? "running-ending" : "running-continued"
        )
}

const setBoxRunning = (uid, isRunning, watermark, strand, mode = "launch") => {
        // console.log("setBoxRunning, looking for ", uid, isRunning, watermark, strand, mode)
        let first = [...document.querySelectorAll(".box")].find(x => x.uid === uid)
        let name = `${watermark}--${strand}`
        if (first) {
                // Track the highest watermark per strand to ignore stale stop signals
                first.latestWatermark = first.latestWatermark ?? new Map()
                first.latestRunningState = first.latestRunningState ?? new Map()
                first.runningEntries = first.runningEntries ?? new Map()
                const lastWatermark = first.latestWatermark.get(strand) ?? -Infinity
                const lastRunningState = first.latestRunningState.get(strand)

                const parseRunningEntry = (entryName) => {
                        const separatorIndex = entryName.indexOf("--")
                        if (separatorIndex <= 0) return null
                        const entryWatermark = Number(entryName.substring(0, separatorIndex))
                        const entryStrand = entryName.substring(separatorIndex + 2)
                        if (!Number.isFinite(entryWatermark)) return null
                        return { entryWatermark, entryStrand }
                }

                // console.log(" is running ", first, first.runningEntries, watermark, isRunning, mode)
                if (isRunning) {
                        // Ignore delayed start events for a strand when we have already seen a newer watermark.
                        if (watermark < lastWatermark) return
                        // A terminal event wins if a delayed start has the same generation.
                        if (watermark === lastWatermark && lastRunningState === false) return

                        first.runningEntries.set(name, mode)
                        if (watermark > lastWatermark) first.latestWatermark.set(strand, watermark)
                        first.latestRunningState.set(strand, true)
                        syncBoxRunningClass(first)
                        window.setStatusForExecutionMarker?.(strand, true, mode)

                } else {
                        // Ignore stop messages that are older than the latest start we saw
                        if (watermark < lastWatermark) return
                        first.latestWatermark.set(strand, watermark)
                        first.latestRunningState.set(strand, false)

                        window.setStatusForExecutionMarker?.(strand, false, mode)

                        let removedAny = false
                        if (first.runningEntries.delete(name)) {
                                removedAny = true
                        }

                        // Also prune any stale entries for this strand that are at or below this stop watermark.
                        for (const runningEntryName of [...first.runningEntries.keys()]) {
                                const parsed = parseRunningEntry(runningEntryName)
                                if (!parsed) continue
                                if (parsed.entryStrand !== strand) continue
                                if (parsed.entryWatermark <= watermark) {
                                        first.runningEntries.delete(runningEntryName)
                                        removedAny = true
                                }
                        }

                        if (!removedAny) {
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
        setTimeout(() => {
                if (typeof window.syncNotesMarkdownPreview === "function") {
                        window.syncNotesMarkdownPreview()
                }
        }, 0)
}

const NOTES_MARKDOWN_MODE_KEY = "field.notes.markdownMode"

const readNotesMarkdownMode = () => {
        try {
                return window.localStorage.getItem(NOTES_MARKDOWN_MODE_KEY) === "source" ? "source" : "preview"
        } catch (_) {
                return "preview"
        }
}

const writeNotesMarkdownMode = (mode) => {
        try {
                window.localStorage.setItem(NOTES_MARKDOWN_MODE_KEY, mode === "source" ? "source" : "preview")
        } catch (_) {
        }
}

const notesSelectionIsActive = () => {
        const selected = window.currentSelectedCode
        return selected && selected.length === 1 && (window.currentSelectedProperty === "notes" ||
                (window.currentSelectedProperty === "contents" && selected[0].contents_language === "markdown"))
}

const selectedNotesMarkdown = () => {
        const editor = window.globalEditor
        if (editor && editor.getModel && notesSelectionIsActive()) {
                return editor.getModel().getValue() || ""
        }
        const selected = window.currentSelectedCode
        if (selected && selected.length === 1 && selected[0]) {
                return selected[0][window.currentSelectedProperty] || ""
        }
        return ""
}

const ensureNotesPreviewElement = () => {
        const container = document.querySelector(".monaco-editor-container")
        if (!container) return null
        let preview = container.querySelector(".afield-notes-preview")
        if (preview) return preview

        preview = document.createElement("div")
        preview.className = "afield-notes-preview"
        preview.tabIndex = 0

        const toolbar = document.createElement("div")
        toolbar.className = "afield-notes-preview-toolbar"

        const sourceButton = document.createElement("button")
        sourceButton.type = "button"
        sourceButton.className = "afield-notes-preview-source"
        sourceButton.title = "Edit Markdown Source"
        sourceButton.setAttribute("aria-label", "Edit Markdown Source")
        sourceButton.textContent = "<>"
        sourceButton.addEventListener("click", (event) => {
                event.preventDefault()
                event.stopPropagation()
                toggleNotesMarkdownPreview(window.globalEditor, "source")
        })

        const body = document.createElement("div")
        body.className = "afield-notes-preview-body"

        toolbar.appendChild(sourceButton)
        preview.appendChild(toolbar)
        preview.appendChild(body)
        container.appendChild(preview)
        return preview
}

const renderNotesMarkdownPreview = () => {
        const preview = ensureNotesPreviewElement()
        if (!preview) return false
        const body = preview.querySelector(".afield-notes-preview-body")
        if (!body) return false
        const selected = window.currentSelectedCode?.[0]
        const options = selected?.file_proxy_mode === "document" ? {
                documentKey: window.documentGuideSessionKey || window.currentDocumentSessionKey, path: selected.file_proxy_path,
                onLink: (link) => window.openDocumentGuideLink?.(link, selected.file_proxy_path)
        } : {
                onGuide: (link) => window.openDocumentGuideLink?.(link)
        }
        renderFieldMarkdown(body, selectedNotesMarkdown(), options)
        return true
}

const syncNotesMarkdownPreview = () => {
        const container = document.querySelector(".monaco-editor-container")
        if (!container) return false
        const active = notesSelectionIsActive()
        const previewMode = readNotesMarkdownMode() === "preview"
        container.classList.toggle("afield-notes-preview-active", !!(active && previewMode))
        container.classList.toggle("afield-notes-source-active", !!(active && !previewMode))

        const preview = ensureNotesPreviewElement()
        if (preview) {
                preview.style.display = active && previewMode ? "flex" : "none"
        }

        if (!active || !previewMode) return false
        renderNotesMarkdownPreview()
        return true
}

const isNotesMarkdownPreviewActive = () => {
        const container = document.querySelector(".monaco-editor-container")
        return !!(container && container.classList.contains("afield-notes-preview-active"))
}

const focusNotesMarkdownPreview = () => {
        const preview = ensureNotesPreviewElement()
        if (preview && isNotesMarkdownPreviewActive()) {
                preview.focus()
                return true
        }
        return false
}

const toggleNotesMarkdownPreview = (editor, forceMode = null) => {
        const current = readNotesMarkdownMode()
        const next = forceMode || (current === "preview" ? "source" : "preview")
        writeNotesMarkdownMode(next)
        syncNotesMarkdownPreview()
        if (next === "source") {
                const ed = editor || window.globalEditor
                if (ed && typeof ed.focus === "function") ed.focus()
        } else {
                focusNotesMarkdownPreview()
        }
}

window.syncNotesMarkdownPreview = syncNotesMarkdownPreview
window.toggleNotesMarkdownPreview = toggleNotesMarkdownPreview
window.isNotesMarkdownPreviewActive = isNotesMarkdownPreviewActive
window.focusNotesMarkdownPreview = focusNotesMarkdownPreview

const quickInputIsVisible = () => {
        const fieldPalette = document.querySelector(".afield-palette")
        if (fieldPalette) {
                const style = window.getComputedStyle(fieldPalette)
                if (style.display !== "none" && style.visibility !== "hidden" && style.opacity !== "0") return true
        }
        const quickInput = document.querySelector(".quick-input-widget")
        if (!quickInput) return false
        const style = window.getComputedStyle(quickInput)
        return style.display !== "none" && style.visibility !== "hidden" && style.opacity !== "0"
}

const switchFromNotesPreviewToCode = () => {
        const selected = window.currentSelectedCode
        const uid = selected && selected.length === 1 && selected[0] && selected[0].uid
        if (uid && typeof window.switchToProperty === "function") {
                window.switchToProperty(uid, "code")
        } else {
                window.selectedProperty = "code"
                window.currentSelectedProperty = "code"
                if (typeof window.selectionContextChanged === "function") window.selectionContextChanged()
                showMonaco()
        }
        setTimeout(() => {
                if (window.globalEditor && typeof window.globalEditor.focus === "function") {
                        window.globalEditor.focus()
                }
        }, 0)
}

window.addEventListener("keydown", (e) => {
        if (quickInputIsVisible()) return
        const key = e.key || ""
        const isToggle = (e.ctrlKey || e.metaKey) && e.shiftKey && !e.altKey && key.toLowerCase() === "m"
        const isEscape = key === "Escape" || key === "Esc"
        const previewActive = isNotesMarkdownPreviewActive()
        const sourceActive = notesSelectionIsActive() && !previewActive
        if (!previewActive && !sourceActive) return
        if (!isToggle && !isEscape) return
        e.preventDefault()
        e.stopImmediatePropagation()
        if (isEscape) {
                if (previewActive) {
                        switchFromNotesPreviewToCode()
                } else {
                        toggleNotesMarkdownPreview(window.globalEditor, "preview")
                }
        } else {
                toggleNotesMarkdownPreview(window.globalEditor, previewActive ? "source" : "preview")
        }
}, {capture: true})

const showActionMenu = () => {
        openQuickCommandPalette()
}

const showPickMenu_custom = (options) => {
        globalPickMenu_custom(options)
}

// Expose for Kotlin/JS callers (e.g., insertFromWorkspace in commands.kt)
window.showPickMenu_custom = showPickMenu_custom

const showPickMenu = () => {
        globalPickMenu()
}

const openQuickCommandPalette = () => {
        const monacoTextArea = document.querySelector("textarea.inputarea.monaco-mouse-cursor-text")
        const isEditorFocused = document.activeElement === monacoTextArea

        if (isDocsIframeVisible()) {
                hideDocsIframe()
        }

        if (isEditorFocused && window.globalEditor && typeof window.openFieldCommandPalette === "function") {
                window.openFieldCommandPalette({ includeEditor: true, editor: window.globalEditor })
        } else if (isEditorFocused && window.globalEditor) {
                showPickMenu()
        } else {
                if (typeof updateActions === "function") {
                        updateActions()
                }
                showPickMenu()
        }
}

window.openQuickCommandPalette = openQuickCommandPalette

const openAnywhereFromShortcut = () => {
        if (isDocsIframeVisible()) {
                hideDocsIframe()
        }

        if (typeof window.openAnywherePalette === "function") {
                window.openAnywherePalette()
        } else {
                openQuickCommandPalette()
        }
}

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
            // Chords come from ~/.afield/keybindings.yaml (defaults Cmd+Shift+P / F1 and Cmd+P);
            // fall back to the built-in chords if the keybinding layer has not loaded yet.
            const kb = window.fieldKeybindings;
            const key = (e.key || "").toLowerCase();
            const isCtrlOrCmdP = (e.ctrlKey || e.metaKey) && !e.altKey && key === 'p';
            const isCommandPaletteShortcut = kb
                    ? kb.matches(e, "open-command-palette")
                    : (e.key === "F1" || (isCtrlOrCmdP && e.shiftKey));
            const isOpenAnythingShortcut = kb
                    ? kb.matches(e, "open-anything")
                    : (isCtrlOrCmdP && !e.shiftKey);

            if (isCommandPaletteShortcut || isOpenAnythingShortcut) {
                    // If an embedded editor has focus, let it handle its own command palette shortcut
                    // (it has its own command palette action registered)
                    if (isCommandPaletteShortcut && window.embeddedEditors && window.embeddedEditors.getFocused()) {
                            return;
                    }
                    e.preventDefault();
                    e.stopImmediatePropagation();
                    if (isOpenAnythingShortcut) {
                            openAnywhereFromShortcut()
                    } else {
                            openQuickCommandPalette()
                    }
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
        const key = (e.key || "").toLowerCase()
        const isCtrlOrCmdP = (e.ctrlKey || e.metaKey) && !e.altKey && key === 'p'
        const isCommandPaletteShortcut = e.key === "F1" || (isCtrlOrCmdP && e.shiftKey)
        const isOpenAnythingShortcut = isCtrlOrCmdP && !e.shiftKey
        const isHelpShortcut = e.key === "?" && !e.ctrlKey && !e.metaKey && !e.altKey
        if (!isCommandPaletteShortcut && !isOpenAnythingShortcut && !isHelpShortcut) return
        if (isHelpShortcut) {
                const target = e.target
                const tag = target && target.tagName ? target.tagName.toLowerCase() : ""
                if (tag === "input" || tag === "textarea" || tag === "select" || target?.isContentEditable) return
        }

        e.preventDefault()
        e.stopPropagation()
        if (isHelpShortcut) {
                if (typeof window.showCheatsheet === "function") window.showCheatsheet()
                return
        }
        if (typeof hideDocsIframe === "function") hideDocsIframe()
        if (isOpenAnythingShortcut) {
                openAnywhereFromShortcut()
        } else {
                openQuickCommandPalette()
        }
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
        // `toggle-docs` in ~/.afield/keybindings.yaml, Cmd+/ by default. The fallback also accepts the
        // "÷" that Option+/ produces on macOS; the keybinding layer matches on `code` so it doesn't need to.
        const kb = window.fieldKeybindings
        const isToggleDocs = kb
                ? kb.matches(e, "toggle-docs")
                : ((e.ctrlKey || e.metaKey) && (e.key === "/" || e.key === "÷"))
        if (!isToggleDocs) return

        // Cmd+/ is Monaco's comment-line, and this listener preventDefaults unconditionally — so
        // toggling the docs from inside an editor cost you the ability to comment code at all. Let the
        // editor keep the chord; the docs still toggle from the canvas, the palette, or a rebind.
        if (isMonacoTextFocused()) return

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
 * Returns the largest group of lines that encloses line x in curly brackets
 * or triple-quoted strings.
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

const defaultDocsIframeUrl = "/field-docs/"
const historyBaseUrl = new URL("/history", window.location.href).toString()

const normalizeIframeUrl = (url) => {
	if (!url) return null
	try {
		return new URL(url, window.location.href).toString()
	} catch (_) {
		return url
	}
}

const proxyDocsIframeUrl = (url) => {
	const normalized = normalizeIframeUrl(url)
	if (!normalized) return normalized
	try {
		const parsed = new URL(normalized)
		if (parsed.hostname.toLowerCase() !== "openendedgroup.com" || !parsed.pathname.toLowerCase().startsWith("/field4")) {
			return normalized
		}
		const relativePath = parsed.pathname.replace(/^\/field4\/?/i, "")
		return new URL(`/field-docs/${relativePath}${parsed.search}${parsed.hash}`, window.location.href).toString()
	} catch (_) {
		return normalized
	}
}

const historyUrlForFile = (filename) => {
	const filePart = filename ? encodeURIComponent(filename) : "default"
	return `${historyBaseUrl}?file=${filePart}`
}

const getHistoryIframeElement = () => {
	return document.getElementById('history-iframe')
}

const setHistoryIframeContent = (url) => {
	const iframe = getHistoryIframeElement()
	if (!iframe) return
	const normalizedUrl = normalizeIframeUrl(url)
	if (!normalizedUrl) return
	const currentSrc = normalizeIframeUrl(iframe.getAttribute("src") || "") || ""
	if (normalizedUrl !== currentSrc) {
		iframe.src = normalizedUrl
	}
}

const showHistoryIframe = () => {
	const container = document.querySelector('.history-iframe-container')
	if (!container) return
	container.style.visibility = "visible"
	container.style.opacity = "1"
}

const hideHistoryIframe = () => {
	const container = document.querySelector('.history-iframe-container')
	if (!container) return
	try {
		const iframe = getHistoryIframeElement()
		const win = iframe && iframe.contentWindow
		if (win && typeof win.disposeHistoryBrowser === "function") {
			win.disposeHistoryBrowser()
		} else {
			if (win && typeof win.clearParentHighlights === "function") {
				win.clearParentHighlights(true)
			}
			if (win && typeof win.clearParentEditorPreview === "function") {
				win.clearParentEditorPreview()
			}
		}
		if (iframe) {
			iframe.removeAttribute("src")
		}
	} catch (_) {}
	container.style.opacity = "0"
	setTimeout(() => {
		if (container.style.opacity === "0") {
			container.style.visibility = "hidden"
		}
	}, 140)
}

const ensureDocsIframeHasContent = () => {
	const iframe = document.getElementById('docs-iframe')
	if (!iframe) return
	wireDocsIframeKeyForwarding()
	const dataset = iframe.dataset || {}

	const currentSrc = iframe.getAttribute("src") || ""
	const proxiedCurrentSrc = proxyDocsIframeUrl(currentSrc)
	if (currentSrc && proxiedCurrentSrc && normalizeIframeUrl(currentSrc) !== proxiedCurrentSrc) {
		iframe.dataset.docsLoadedOnce = "false"
		setDocsIframeContent(proxiedCurrentSrc)
		return
	}
	if (dataset.docsLoadedOnce === "true") return
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
		const normalizedUrl = proxyDocsIframeUrl(url)
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
		if (typeof window.syncNotesMarkdownPreview === "function" && window.syncNotesMarkdownPreview()) {
			if (typeof window.focusNotesMarkdownPreview === "function") window.focusNotesMarkdownPreview()
		} else if (window.globalEditor && typeof window.globalEditor.focus === "function") {
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
	setHistoryIframeContent(url)
	showHistoryIframe()
}

window.showDocsIframe = showDocsIframe
window.hideDocsIframe = hideDocsIframe
window.showHistoryIframe = showHistoryIframe
window.hideHistoryIframe = hideHistoryIframe
window.setHistoryIframeContent = setHistoryIframeContent
window.getHistoryIframeElement = getHistoryIframeElement
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
