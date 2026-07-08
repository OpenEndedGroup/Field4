
import {
        sendWithReply,
        monacoInit,
        monacoRegisterActions,
        monacoStartSelectionMonitor,
        monacoHandleOutput,
        monacoLaunchAll,
        monacoContinueAll,
        monacoKindForIcon,
        monacoGetEnclosingScope,
        aiChatMarkdownToHtml
} from "./afield.mjs"
import { installFieldSuggest } from "./fieldSuggest.js"

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

window.require(["vs/editor/editor.main"], function (e, is) {
        window.require(["vs/basic-languages/python/python"], function (e, is) {
        });
        window.require(["vs/basic-languages/clojure/clojure"], function (e, is) {
        });
        window.require(["vs/basic-languages/yaml/yaml"], function (e, is) {
        });
        window.require(["vs/basic-languages/markdown/markdown"], function (e, is) {
        });
        window.require(["bithero/glsl", "bithero/slang", "bithero/forth", "bithero/fieldml", "bithero/languageExtensions", "bithero/stacksl", "bithero/nano"], function () {
                initMonaco()
                loadOptionalPersonalLanguageExtensions()
                window.require(["vs/platform/quickinput/common/quickInput"], function (is) {
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
const COMPLETION_RECENT_STORAGE_KEY = "afield.kotlinCompletion.recent.v1"
const COMPLETION_RECENT_MAX = 24
const COMPLETION_CATEGORY_ORDER = [
        "AI",
        "Recent",
        "Local",
        "Math",
        "Graphics",
        "Visualization",
        "Audio",
        "Data",
        "Workspace",
        "Kotlin",
        "External",
        "Internal",
        "Other",
]

const completionCategoryLabel = (category) => String(category || "").trim()

const completionCategoryRank = (category) => {
        const label = completionCategoryLabel(category) || "Other"
        const found = COMPLETION_CATEGORY_ORDER.indexOf(label)
        return found >= 0 ? found : COMPLETION_CATEGORY_ORDER.length
}

const completionSortText = (sortPrefix, category, ordinal) => {
        const rank = completionCategoryRank(category).toString(36).padStart(2, "0")
        return sortPrefix + rank + ordinal.toString(36).padStart(6, "0")
}

const completionIsSectionHeader = (item) => !!(item && item.data && item.data.sectionHeader)

const completionRecentKey = (displayText, insertText, detail) =>
        [displayText || "", insertText || "", detail || ""].join("\u001f")

const completionRecentEntries = () => {
        try {
                const raw = window.localStorage && window.localStorage.getItem(COMPLETION_RECENT_STORAGE_KEY)
                const parsed = raw ? JSON.parse(raw) : []
                return Array.isArray(parsed) ? parsed.filter(entry => entry && typeof entry.key === "string") : []
        } catch (_) {
                return []
        }
}

const completionRememberRecent = (recent) => {
        if (!recent || !recent.key) return
        try {
                const rest = completionRecentEntries().filter(entry => entry.key !== recent.key)
                const next = [{key: recent.key, at: Date.now()}, ...rest].slice(0, COMPLETION_RECENT_MAX)
                window.localStorage && window.localStorage.setItem(COMPLETION_RECENT_STORAGE_KEY, JSON.stringify(next))
        } catch (_) {
        }
}

const completionDebugState = () => {
        window.afieldCompletionDebug = window.afieldCompletionDebug || {events: []}
        if (!Array.isArray(window.afieldCompletionDebug.events)) window.afieldCompletionDebug.events = []
        return window.afieldCompletionDebug
}

const completionDebugRecord = (type, payload = {}) => {
        const state = completionDebugState()
        const event = {
                at: Date.now(),
                type,
                ...payload,
        }
        state.events.push(event)
        if (state.events.length > 80) state.events.splice(0, state.events.length - 80)
        state.last = event
        state[type] = event
        if (window.DEBUG_COMPLETION) console.info("[completion-debug]", event)
        return event
}

const completionDebugSuggestionSummary = (suggestions) => {
        const list = Array.isArray(suggestions) ? suggestions : []
        return {
                count: list.length,
                headers: list.filter(completionIsSectionHeader).map(item => item.label),
                ai: list.filter(item => item && item.data && item.data.ai).length,
                categories: [...new Set(list.map(item => item && item.data && item.data.category).filter(Boolean))],
                first: list.slice(0, 10).map(item => ({
                        label: item && item.label,
                        filterText: item && item.filterText,
                        sortText: item && item.sortText,
                        header: completionIsSectionHeader(item),
                        category: item && item.data && item.data.category,
                        ai: !!(item && item.data && item.data.ai),
                })),
        }
}

const completionRowLabel = (item) => {
        if (!item) return ""
        if (typeof item.label === "string") return item.label
        if (item.label && typeof item.label.label === "string") return item.label.label
        return String(item.label || "")
}

const completionRememberRenderSuggestions = (suggestions, key = "") => {
        const list = Array.isArray(suggestions) ? suggestions : []
        const byLabel = new Map()
        list.forEach((item, index) => {
                if (!item || !item.data) return
                const category = completionCategoryLabel(item.data.category) || "Other"
                const label = completionRowLabel(item)
                if (!label) return
                const entry = {
                        category,
                        index,
                        label,
                        ai: !!item.data.ai,
                }
                const previous = byLabel.get(label)
                if (!previous || index < previous.index) {
                        byLabel.set(label, entry)
                }
        })
        window.afieldCompletionRenderState = {
                key,
                at: Date.now(),
                byLabel,
                count: byLabel.size,
                labels: [...byLabel.keys()],
        }
}

const completionDebugRows = () => {
        const rows = [...document.querySelectorAll(".editor-widget.suggest-widget .monaco-list-row")]
        const widget = document.querySelector(".editor-widget.suggest-widget")
        return {
                widget: widget ? {
                        className: widget.className,
                        text: (widget.textContent || "").slice(0, 400),
                        display: getComputedStyle(widget).display,
                        visibility: getComputedStyle(widget).visibility,
                        opacity: getComputedStyle(widget).opacity,
                } : null,
                count: rows.length,
                first: rows.slice(0, 12).map(row => ({
                        aria: row.getAttribute("aria-label") || "",
                        text: (row.textContent || "").trim(),
                        className: row.className,
                })),
        }
}

window.afieldCompletionDebugRows = completionDebugRows

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

const EDITOR_BOTTOM_OVERLAY_FADE_LINES = 2

const rectsIntersect = (a, b) => {
        return a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
}

const visibleRelativeRect = (element, rootRect) => {
        if (!element) return null
        const style = window.getComputedStyle(element)
        if (style.display === "none" || style.visibility === "hidden" || style.opacity === "0") return null
        const rect = element.getBoundingClientRect()
        if (rect.width <= 0 || rect.height <= 0) return null
        return {
                left: rect.left - rootRect.left,
                right: rect.right - rootRect.left,
                top: rect.top - rootRect.top,
                bottom: rect.bottom - rootRect.top,
        }
}

const overlayLayoutRect = (element) => {
        if (!element) return null
        if (element.classList.contains("hoverlay-left") && element.innerHTML.trim().length === 0) return null
        if (element.offsetWidth <= 0 || element.offsetHeight <= 0) return null
        return {
                left: element.offsetLeft,
                right: element.offsetLeft + element.offsetWidth,
                top: element.offsetTop,
                bottom: element.offsetTop + element.offsetHeight,
        }
}

const editorBottomOverlayCoversContent = (editor) => {
        const dom = editor && editor._domElement
        if (!dom) return false

        const overlayRects = [dom.querySelector(".hoverlay"), dom.querySelector(".hoverlay-left")]
                .map(overlayLayoutRect)
                .filter(Boolean)
        if (overlayRects.length === 0) return false

        const rootRect = dom.getBoundingClientRect()
        const coveredContent = dom.querySelectorAll(".execution_output, .view-zones > div, .margin-view-zones > div")
        for (const element of coveredContent) {
                const contentRect = visibleRelativeRect(element, rootRect)
                if (!contentRect) continue
                if (overlayRects.some((overlayRect) => rectsIntersect(overlayRect, contentRect))) return true
        }
        return false
}

const updateEditorBottomOverlayFade = (editor) => {
        if (!editor || !editor._domElement) return

        const dom = editor._domElement
        const position = editor.getPosition && editor.getPosition()
        const visible = position && editor.getScrolledVisiblePosition && editor.getScrolledVisiblePosition(position)
        const layoutInfo = editor.getLayoutInfo && editor.getLayoutInfo()
        const editorHeight = (layoutInfo && Number(layoutInfo.height)) || dom.clientHeight || 0

        if (!position || !visible || !Number.isFinite(Number(visible.top)) || editorHeight <= 0) {
                dom.classList.remove("afield-cursor-near-bottom")
                return
        }

        const visibleTop = Number(visible.top)
        const visibleHeight = Number(visible.height)
        let lineHeight = Number.isFinite(visibleHeight) && visibleHeight > 0 ? visibleHeight : 0
        if (lineHeight <= 0 && typeof editor.getOption === "function") {
                try {
                        const monacoApi = window.globalMonaco || window.monaco
                        const lineHeightOption = monacoApi && monacoApi.editor && monacoApi.editor.EditorOption
                                ? monacoApi.editor.EditorOption.lineHeight
                                : null
                        const optionHeight = lineHeightOption != null ? Number(editor.getOption(lineHeightOption)) : 0
                        if (Number.isFinite(optionHeight) && optionHeight > 0) lineHeight = optionHeight
                } catch (_) {
                }
        }
        if (lineHeight <= 0) lineHeight = 18

        const cursorBottom = visibleTop + lineHeight
        const fadeThreshold = editorHeight - (lineHeight * EDITOR_BOTTOM_OVERLAY_FADE_LINES) - 2
        const nearBottom = cursorBottom >= fadeThreshold && cursorBottom <= editorHeight + lineHeight
        const overlayCoversContent = editorBottomOverlayCoversContent(editor)
        dom.classList.toggle("afield-cursor-near-bottom", nearBottom || overlayCoversContent)
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

        if (typeof editor.onDidChangeCursorPosition === "function") editor.onDidChangeCursorPosition(schedule)
        if (typeof editor.onDidScrollChange === "function") editor.onDidScrollChange(schedule)
        if (typeof editor.onDidChangeModelContent === "function") editor.onDidChangeModelContent(schedule)
        if (typeof editor.onDidLayoutChange === "function") editor.onDidLayoutChange(schedule)
        if (typeof editor.onDidChangeModel === "function") editor.onDidChangeModel(schedule)
        schedule()
}

window.installEditorBottomOverlayFade = installEditorBottomOverlayFade

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
        installEditorBottomOverlayFade(ed)
        installFieldSuggest(ed, monaco)

        installNumberCommands(ed)
        // register a no-keybinding command and get its ID
        const acceptCommandId = ed.addCommand(0, (ctx, ...args) => {
                const payload = (args[0] && typeof args[0] === "object") ? args[0] : {prefix: args[0]}
                completionRememberRecent(payload.recent)
                const prefix = String(payload.prefix || "").trim()
                if (prefix) {
                        ed.getModel().applyEdits([{
                                range: new monaco.Range(1, 1, 1, 1), text: prefix + "\n", forceMoveMarkers: true
                        }]);
                }
        }, '');

        const fieldSuggestLanguages = window.FieldSuggestConfig && window.FieldSuggestConfig.languages
                ? window.FieldSuggestConfig.languages
                : new Set(["kotlin", "python", "clojure", "glsl", "slang", "forth", "nano", "bend", "yaml"])
        const fieldSuggestLanguageSet = fieldSuggestLanguages instanceof Set
                ? fieldSuggestLanguages
                : new Set(fieldSuggestLanguages)
        const fieldSuggestOwnsLanguage = (languageId) => !!(window.FieldSuggestConfig?.enabled && fieldSuggestLanguageSet.has(languageId))

        var completionProvider = {
                triggerCharacters: window.FieldSuggestConfig?.enabled ? [] : ['.'],
                provideCompletionItems: (model, position, context, cancellationToken) => {
                        if (fieldSuggestOwnsLanguage(model.getLanguageId())) {
                                return { suggestions: [], incomplete: false }
                        }
                        var word = model.getWordUntilPosition(position);
                        var languageId = model.getLanguageId()
                        const shouldRefreshAsPrefixChanges = languageId === "kotlin" && word.word.length === 0
	                        var range = {
	                                startLineNumber: position.lineNumber,
	                                endLineNumber: position.lineNumber,
	                                startColumn: word.startColumn,
	                                endColumn: word.endColumn,
	                        };
                                const emptyRange = {
                                        startLineNumber: position.lineNumber,
                                        endLineNumber: position.lineNumber,
                                        startColumn: position.column,
                                        endColumn: position.column,
                                }

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
                                completionRememberRenderSuggestions(cached.merged || cached.base || [], sessionKey)
                                completionDebugRecord("return-cache", {
                                        key: sessionKey,
                                        word: word.word,
                                        rows: completionDebugRows(),
                                        suggestions: completionDebugSuggestionSummary(cached.merged || cached.base || []),
                                })
                                return {
                                        suggestions: cached.merged || cached.base || [],
                                        incomplete: shouldRefreshAsPrefixChanges
                                }
                        }

// Resolve uid/property: check embedded editor first, fall back to main editor globals
				const embEntry = window.embeddedEditors && window.embeddedEditors.getByModel(model);
                        if (typeof window.fieldLanguageCompletionItems === "function") {
                                const completionEditor = embEntry ? embEntry.editor : (window.globalEditor || ed)
                                const localSuggestions = window.fieldLanguageCompletionItems(completionEditor, model, position, context)
                                if (localSuggestions && localSuggestions.length > 0) {
                                        completionRememberRenderSuggestions(localSuggestions, sessionKey)
                                        completionDebugRecord("return-local", {
                                                key: sessionKey,
                                                word: word.word,
                                                suggestions: completionDebugSuggestionSummary(localSuggestions),
                                        })
                                        return {
                                                suggestions: localSuggestions,
                                                incomplete: false
                                        }
                                }
                        }
					const completionUid = embEntry ? embEntry.uid : (window.currentSelectedCode && window.currentSelectedCode[0] ? window.currentSelectedCode[0].uid : null);
					const completionProperty = embEntry ? embEntry.property : window.currentSelectedProperty;
					if (!completionUid) {
                                                completionDebugRecord("return-no-uid", {
                                                        key: sessionKey,
                                                        word: word.word,
                                                        property: completionProperty || "",
                                                })
                                                return { suggestions: [] };
                                        }
                                const isKotlinCompletion = languageId === "kotlin" || completionProperty === "code"

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

                        const rangeForPayload = (payload, activeModel) => {
                                if (payload.useLineColumns) {
                                        return {
                                                startLineNumber: payload.lineStart,
                                                endLineNumber: payload.lineStart,
                                                startColumn: payload.columnsStart + 1,
                                                endColumn: payload.columnsStart + 1,
                                        }
                                }
                                var completionModel = activeModel || model
                                var start = completionModel.getPositionAt(payload.boundsStart)
                                var end = completionModel.getPositionAt(payload.boundsEnd)
                                return {
                                        startLineNumber: start.lineNumber,
                                        endLineNumber: end.lineNumber,
                                        startColumn: start.column,
                                        endColumn: end.column
                                }
                        }

	                        const completionWithTypedStem = (stem, text) => {
	                                const raw = String(text || "")
	                                const typedStem = String(stem || "")
	                                if (!typedStem || !raw) return raw
	                                const trimmedRaw = raw.trimStart()
	                                if (trimmedRaw.startsWith(typedStem)) return raw
	                                return typedStem + raw
	                        }

	                        const buildSuggestions = (payload, opts = {}) => {
                                let idx = opts.offset || 0
                                const rangeObj = rangeForPayload(payload, opts.model || model)
	                                const sortPrefix = "1000"
	                                const items = payload.metadata.map(m => {
	                                        const category = opts.ai ? "AI" : completionCategoryLabel(m.category)
	                                        const sortText = completionSortText(sortPrefix, category, ++idx)
                                        const insertText = opts.ai ? completionWithTypedStem(word.word, m.text) : m.text
                                        const displayText = opts.ai ? completionWithTypedStem(word.word, m.displayText || m.text) : m.displayText
                                        const filterText = opts.ai ? insertText : m.text
                                        const recentKey = completionRecentKey(displayText, insertText, m.tail)
                                        const command = {
                                                id: acceptCommandId,
                                                title: '__completionAccepted',
                                                arguments: [{
                                                        prefix: m.prefix || "",
                                                        recent: isKotlinCompletion ? {key: recentKey} : null,
                                                }],
                                        }

                                        return {
                                                label: displayText,
                                                insertText: insertText,
                                                filterText: filterText,
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
                                                        displayText: displayText,
                                                        insertText: insertText,
                                                        detail: m.tail,
                                                        aiDocPending: false,
                                                        aiDocResolved: !!opts.ai,
                                                        generateDocumentation: !!m.generateDocumentation,
                                                        documentationSourceName: m.documentationSourceName || "",
                                                        documentationSourcePath: m.documentationSourcePath || "",
                                                        documentationSourceLine: m.documentationSourceLine || -1,
                                                        category: category,
                                                        recentKey: recentKey,
                                                        ai: !!opts.ai
                                                },
	                                                command: command
	                                        }
	                                })
		                                if (opts.raw || !isKotlinCompletion) return items
                                        return groupCompletionSuggestions(items, {
                                                emptyRange,
                                                replaceRange: rangeObj,
                                                sessionKey,
                                                sortPrefix,
                                                word: word.word,
                                        })
		                        }

		                        const groupCompletionSuggestions = (items, opts = {}) => {
		                                if (!isKotlinCompletion) return items
	                                const grouped = []
	                                const seenCategories = new Set()
	                                const recentRanks = new Map(completionRecentEntries().map((entry, index) => [entry.key, index]))
	                                const sortedItems = items.filter(item => !completionIsSectionHeader(item)).slice().sort((a, b) => {
                                        const aSort = a.sortText || ""
                                        const bSort = b.sortText || ""
                                        if (aSort < bSort) return -1
                                        if (aSort > bSort) return 1
                                        return String(a.label || "").localeCompare(String(b.label || ""))
                                })
	                                const recentItems = sortedItems
	                                        .filter(item => item.data && recentRanks.has(item.data.recentKey))
	                                        .sort((a, b) => recentRanks.get(a.data.recentKey) - recentRanks.get(b.data.recentKey))
	                                        .map((item, recentIndex) => ({
	                                                ...item,
	                                                sortText: completionSortText(opts.sortPrefix || "1000", "Recent", recentIndex + 1),
                                                        data: {
                                                                ...item.data,
                                                                originalCategory: item.data.category || "",
                                                                category: "Recent",
                                                        },
	                                        }))
	                                const normalItems = sortedItems.filter(item => !(item.data && recentRanks.has(item.data.recentKey)))
	                                const preselectFirstCompletion = () => {
	                                        let selected = false
	                                        grouped.forEach(item => {
	                                                if (!item || completionIsSectionHeader(item)) return
	                                                item.preselect = !selected
	                                                selected = true
	                                        })
	                                }
                                        const pushItem = (item) => {
	                                        const category = item.data && item.data.category ? item.data.category : "Other"
	                                        if (!seenCategories.has(category)) {
	                                                seenCategories.add(category)
	                                        }
	                                        grouped.push(item)
                                        }
	                                recentItems.forEach(pushItem)
		                        normalItems.forEach(pushItem)
		                                preselectFirstCompletion()
		                                        completionDebugRecord(opts.debugType || "build-grouped", {
		                                                key: sessionKey,
		                                                word: word.word,
		                                                summary: completionDebugSuggestionSummary(grouped),
                                        })
		                                return grouped
		                        }

                        window.kotlinAiCompletion = {
                                key: sessionKey,
                                gen: currentGen,
	                                base: [],
	                                baseItems: [],
	                                ai: [],
	                                aiItems: [],
	                                merged: [],
	                                useCacheOnce: false
                        }

                        if (isKotlinCompletion) {
                                sendWithReply("complete-kotlin-ai", basePayload, "complete-kotlin-ai", (message, payload) => {
                                        if (window.currentEditorGeneration !== currentGen) return true
                                        if (!window.kotlinAiCompletion || window.kotlinAiCompletion.key !== sessionKey) return true
		                                        const aiItems = buildSuggestions(payload, {offset: 0, ai: true, model, raw: true})
	                                                const aiSuggestions = groupCompletionSuggestions(aiItems, {
	                                                        emptyRange,
	                                                        replaceRange: range,
	                                                        sessionKey,
	                                                        sortPrefix: "1000",
	                                                        word: word.word,
	                                                        debugType: "build-ai",
	                                                })
		                                        window.kotlinAiCompletion.ai = aiSuggestions
		                                        window.kotlinAiCompletion.aiItems = aiItems
		                                        const baseExisting = window.kotlinAiCompletion.baseItems || []
		                                        window.kotlinAiCompletion.merged = groupCompletionSuggestions([...baseExisting, ...aiItems], {
	                                                        emptyRange,
	                                                        replaceRange: range,
	                                                        sessionKey,
	                                                        sortPrefix: "1000",
	                                                        word: word.word,
	                                                        debugType: "build-merged",
	                                                })
		                                        if (aiItems.length > 0) {
	                                                window.kotlinAiCompletion.useCacheOnce = true
                                                        const activeEditor = embEntry ? embEntry.editor : (window.globalEditor || ed)
                                                        const activeModel = activeEditor && activeEditor.getModel && activeEditor.getModel()
                                                        const activePosition = activeEditor && activeEditor.getPosition && activeEditor.getPosition()
                                                        const sameModel = activeModel === model
                                                        const samePosition = !!(activePosition && activePosition.lineNumber === position.lineNumber && activePosition.column === position.column)
                                                        const hasFocus = !!(activeEditor && activeEditor.hasTextFocus && activeEditor.hasTextFocus())
                                                        const shouldRefresh = !!(activeEditor && activeEditor.trigger && sameModel && samePosition)
                                                        completionDebugRecord("ai-arrived", {
                                                                key: sessionKey,
                                                                word: word.word,
                                                                shouldRefresh,
                                                                sameModel,
                                                                samePosition,
                                                                hasFocus,
                                                                visible: isSuggestWidgetVisible(),
                                                                ai: completionDebugSuggestionSummary(aiSuggestions),
                                                                merged: completionDebugSuggestionSummary(window.kotlinAiCompletion.merged),
                                                                rows: completionDebugRows(),
                                                        })
                                                        if (shouldRefresh) {
                                                                try {
                                                                        activeEditor.trigger("afield-ai", "hideSuggestWidget", {})
                                                                        setTimeout(() => {
                                                                                activeEditor.trigger("afield-ai", "editor.action.triggerSuggest", {})
                                                                        }, 0)
                                                                } catch (error) {
                                                                        completionDebugRecord("ai-refresh-error", {
                                                                                key: sessionKey,
                                                                                message: error && error.message ? error.message : String(error),
                                                                        })
                                                                }
                                                        }
	                                        }
	                                        return true
	                                })
                        }

                        var ret = new Promise(resolve => {
                                sendWithReply("complete", basePayload, "complete-me", (message, payload) => {

                                        if (window.currentEditorGeneration !== currentGen) return true

	                                        const baseItems = buildSuggestions(payload, {offset: 0, model, raw: true})
	                                        const baseSuggestions = groupCompletionSuggestions(baseItems, {
	                                                emptyRange,
	                                                replaceRange: range,
	                                                sessionKey,
	                                                sortPrefix: "1000",
	                                                word: word.word,
	                                                debugType: "build-base",
	                                        })
		                                        window.kotlinAiCompletion.base = baseSuggestions
		                                        window.kotlinAiCompletion.baseItems = baseItems
		                                        const aiExisting = window.kotlinAiCompletion.aiItems || []
		                                        window.kotlinAiCompletion.merged = groupCompletionSuggestions([...baseItems, ...aiExisting], {
	                                                emptyRange,
	                                                replaceRange: range,
	                                                sessionKey,
	                                                sortPrefix: "1000",
	                                                word: word.word,
	                                                debugType: "build-merged",
	                                        })
                                                const visibleSuggestions = aiExisting.length > 0 ? window.kotlinAiCompletion.merged : baseSuggestions
                                                completionRememberRenderSuggestions(visibleSuggestions, sessionKey)

		                                        monacoLog("resolving. Answer is ", {suggestions: visibleSuggestions})
	                                                completionDebugRecord("return-base", {
	                                                        key: sessionKey,
	                                                        word: word.word,
	                                                        base: completionDebugSuggestionSummary(baseSuggestions),
	                                                        merged: completionDebugSuggestionSummary(window.kotlinAiCompletion.merged),
	                                                })
		                                        resolve({
		                                                suggestions: visibleSuggestions,
	                                                incomplete: shouldRefreshAsPrefixChanges
	                                        })

                                        return true
                                })
                        })
                        return ret
                },
                resolveCompletionItem: (item, cancellationToken) => {
                        try {
                                if (!item || !item.data) return item
                                if (item.data.sectionHeader) return item
                                if (item.data.aiDocResolved || item.data.aiDocPending) return item
                                if (item.data.source !== "kotlin") return item
                                const existingDocValue = (item.documentation && item.documentation.value) ? item.documentation.value.trim() : ""
                                if (existingDocValue.length > 0 && !item.data.generateDocumentation) {
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
                                        position: model.getOffsetAt({lineNumber: pos.lineNumber, column: pos.column}),
                                        documentationSourceName: item.data.documentationSourceName || "",
                                        documentationSourcePath: item.data.documentationSourcePath || "",
                                        documentationSourceLine: item.data.documentationSourceLine || -1
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
        monaco.languages.registerCompletionItemProvider("bend", completionProvider)
        monaco.languages.registerCompletionItemProvider("fieldml", completionProvider)
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
                first.runningEntries = first.runningEntries ?? new Map()
                const lastWatermark = first.latestWatermark.get(strand) ?? -Infinity

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

                        first.runningEntries.set(name, mode)
                        if (watermark > lastWatermark) first.latestWatermark.set(strand, watermark)
                        syncBoxRunningClass(first)
                        window.setStatusForExecutionMarker?.(strand, true, mode)

                } else {
                        // Ignore stop messages that are older than the latest start we saw
                        if (watermark < lastWatermark) return

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
        return window.currentSelectedProperty === "notes" && selected && selected.length === 1
}

const selectedNotesMarkdown = () => {
        const editor = window.globalEditor
        if (editor && editor.getModel && notesSelectionIsActive()) {
                return editor.getModel().getValue() || ""
        }
        const selected = window.currentSelectedCode
        if (selected && selected.length === 1 && selected[0]) {
                return selected[0].notes || ""
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

const safeMarkdownUrl = (url, allowImageData = false) => {
        const raw = String(url || "").trim()
        if (!raw) return ""
        try {
                const parsed = new URL(raw, window.location.href)
                const protocol = parsed.protocol.toLowerCase()
                if (protocol === "http:" || protocol === "https:" || protocol === "mailto:" || protocol === "blob:") {
                        return raw
                }
                if (allowImageData && protocol === "data:" && raw.toLowerCase().startsWith("data:image/")) {
                        return raw
                }
                if (protocol === window.location.protocol && !/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(raw)) {
                        return raw
                }
        } catch (_) {
                if (raw.startsWith("#") || raw.startsWith("/") || raw.startsWith("./") || raw.startsWith("../")) {
                        return raw
                }
        }
        return ""
}

const sanitizeNotesMarkdown = (root) => {
        root.querySelectorAll("a[href]").forEach((link) => {
                const safe = safeMarkdownUrl(link.getAttribute("href"))
                if (safe) {
                        link.setAttribute("href", safe)
                        link.setAttribute("rel", "noopener noreferrer")
                } else {
                        link.removeAttribute("href")
                }
        })
        root.querySelectorAll("img[src]").forEach((img) => {
                const safe = safeMarkdownUrl(img.getAttribute("src"), true)
                if (safe) {
                        img.setAttribute("src", safe)
                        img.setAttribute("loading", "lazy")
                } else {
                        img.remove()
                }
        })
}

const escapeCodeHtml = (value) => String(value ?? "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;")

const normalizeCodeFenceLanguage = (language) => {
        const raw = String(language || "").trim().toLowerCase().replace(/^language-/, "")
        if (!raw) return ""
        const aliases = {
                kt: "kotlin",
                kts: "kotlin",
                glsl: "glsl",
                frag: "glsl",
                fragment: "glsl",
                vert: "glsl",
                vertex: "glsl",
                comp: "glsl",
                compute: "glsl",
                shader: "glsl",
                bend: "bend",
                js: "javascript",
                ts: "typescript",
                sh: "bash",
                shell: "bash"
        }
        return aliases[raw] || raw
}

const glslKeywordSet = new Set([
        "attribute", "break", "buffer", "case", "centroid", "coherent", "const", "continue", "default",
        "discard", "do", "else", "flat", "for", "highp", "if", "in", "inout", "invariant", "layout",
        "lowp", "mediump", "noperspective", "out", "patch", "precision", "readonly", "restrict",
        "return", "sample", "shared", "smooth", "struct", "subroutine", "switch", "uniform", "varying",
        "volatile", "while", "writeonly"
])

const glslTypeSet = new Set([
        "void", "bool", "int", "uint", "float", "double",
        "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4", "bvec2", "bvec3", "bvec4",
        "dvec2", "dvec3", "dvec4", "mat2", "mat3", "mat4", "mat2x2", "mat2x3", "mat2x4", "mat3x2", "mat3x3",
        "mat3x4", "mat4x2", "mat4x3", "mat4x4", "dmat2", "dmat3", "dmat4", "sampler1D", "sampler2D",
        "sampler3D", "samplerCube", "sampler2DArray", "samplerCubeArray", "isampler2D", "usampler2D",
        "image1D", "image2D", "image3D", "imageCube", "atomic_uint"
])

const glslLiteralSet = new Set(["true", "false"])

const glslBuiltinSet = new Set([
        "abs", "acos", "all", "any", "asin", "atan", "ceil", "clamp", "cos", "cross", "degrees", "determinant",
        "distance", "dot", "dFdx", "dFdy", "equal", "exp", "exp2", "faceforward", "floor", "fract", "fwidth",
        "greaterThan", "greaterThanEqual", "inversesqrt", "length", "lessThan", "lessThanEqual", "log", "log2",
        "max", "min", "mix", "mod", "normalize", "not", "notEqual", "pow", "radians", "reflect", "refract",
        "round", "sign", "sin", "smoothstep", "sqrt", "step", "tan", "texture", "textureGrad", "textureLod",
        "transpose", "gl_FragCoord", "gl_FragDepth", "gl_GlobalInvocationID", "gl_InstanceIndex",
        "gl_LocalInvocationID", "gl_Position", "gl_VertexIndex"
])

const highlightGlslCode = (source) => {
        let out = ""
        let i = 0
        let lineStart = true
        const text = String(source || "")

        const push = (className, value) => {
                out += `<span class="${className}">${escapeCodeHtml(value)}</span>`
        }

        while (i < text.length) {
                const ch = text[i]
                const next = text[i + 1]

                if (lineStart && ch === "#") {
                        const end = text.indexOf("\n", i)
                        const stop = end === -1 ? text.length : end
                        push("hljs-meta", text.slice(i, stop))
                        i = stop
                        lineStart = false
                        continue
                }

                if (ch === "/" && next === "/") {
                        const end = text.indexOf("\n", i)
                        const stop = end === -1 ? text.length : end
                        push("hljs-comment", text.slice(i, stop))
                        i = stop
                        lineStart = false
                        continue
                }

                if (ch === "/" && next === "*") {
                        const end = text.indexOf("*/", i + 2)
                        const stop = end === -1 ? text.length : end + 2
                        push("hljs-comment", text.slice(i, stop))
                        lineStart = text.slice(i, stop).endsWith("\n")
                        i = stop
                        continue
                }

                if (ch === "\"" || ch === "'") {
                        const quote = ch
                        let j = i + 1
                        while (j < text.length) {
                                if (text[j] === "\\" && j + 1 < text.length) {
                                        j += 2
                                        continue
                                }
                                if (text[j] === quote) {
                                        j++
                                        break
                                }
                                j++
                        }
                        push("hljs-string", text.slice(i, j))
                        lineStart = false
                        i = j
                        continue
                }

                const number = text.slice(i).match(/^(?:0x[0-9a-fA-F]+|(?:\d+\.\d*|\.\d+|\d+)(?:[eE][+-]?\d+)?)[uUlLfF]?/)
                if (number) {
                        push("hljs-number", number[0])
                        i += number[0].length
                        lineStart = false
                        continue
                }

                const ident = text.slice(i).match(/^[A-Za-z_][A-Za-z0-9_]*/)
                if (ident) {
                        const word = ident[0]
                        if (glslKeywordSet.has(word)) push("hljs-keyword", word)
                        else if (glslTypeSet.has(word)) push("hljs-type", word)
                        else if (glslLiteralSet.has(word)) push("hljs-literal", word)
                        else if (glslBuiltinSet.has(word)) push("hljs-built_in", word)
                        else out += escapeCodeHtml(word)
                        i += word.length
                        lineStart = false
                        continue
                }

                out += escapeCodeHtml(ch)
                lineStart = ch === "\n" || (lineStart && /\s/.test(ch))
                i++
        }

        return out
}

const highlightNotesMarkdown = (root) => {
        root.querySelectorAll("pre code").forEach((block) => {
                const rawLanguage = block.className.match(/(?:^|\s)language-([A-Za-z0-9_+-]+)/)?.[1] || block.closest(".ai-chat-code-block")?.dataset?.lang || ""
                const language = normalizeCodeFenceLanguage(rawLanguage)
                block.className = language ? `language-${language}` : ""
                if (language === "glsl") {
                        block.innerHTML = highlightGlslCode(block.textContent || "")
                        block.classList.add("hljs", "language-glsl")
                        return
                }
                if (!window.hljs || typeof window.hljs.highlightElement !== "function") return
                try {
                        if (language && window.hljs.getLanguage && !window.hljs.getLanguage(language)) {
                                block.className = "language-plaintext"
                        }
                        window.hljs.highlightElement(block)
                } catch (_) {
                }
        })
}

const renderNotesMarkdownPreview = () => {
        const preview = ensureNotesPreviewElement()
        if (!preview) return false
        const body = preview.querySelector(".afield-notes-preview-body")
        if (!body) return false
        body.innerHTML = aiChatMarkdownToHtml(selectedNotesMarkdown())
        sanitizeNotesMarkdown(body)
        highlightNotesMarkdown(body)
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
            const key = (e.key || "").toLowerCase();
            const isCtrlOrCmdP = (e.ctrlKey || e.metaKey) && !e.altKey && key === 'p';
            const isCommandPaletteShortcut = e.key === "F1" || (isCtrlOrCmdP && e.shiftKey);
            const isOpenAnythingShortcut = isCtrlOrCmdP && !e.shiftKey;

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
