let globalIS = null
const DEBUG_QUICKPICK = false;
const qpLog = (...args) => {
        if (DEBUG_QUICKPICK) console.info(...args);
};

const initQuickPick = (is) => {
        globalIS = is.IQuickInputService

        // Expose the quick input module so other scripts (e.g. bootstrapMonaco.js) can access service identifiers.
        window.globalIS = is

        window.addEventListener("keydown", (e) => {
                if (document.activeElement == document.body) {
                        qpLog(e.key)
                        if (e.key == "?")
                                showCheatsheet()

                        let k = e.key
                        let found = lastActions.find(x => {
                                qpLog(" testing ", x)
                                let a = x.canvasKeybinding && x.canvasKeybinding(e)
                                qpLog(" got ", a)
                                return a
                        })
                        if (found) {
                                qpLog(" running ")
                                found.run()
                        }
                }
        })
}
window.initQuickPick = initQuickPick

let allActions = {
        base: []
}
// expose for other modules (docs iframe handler, etc.)
window.allActions = allActions

let lastActions = []
window.lastActions = lastActions
let monacoActionDisposers = new Map()

function normalizeActionsById(actions) {
        const byId = new Map()
        for (const action of actions || []) {
                if (!action || !action.id) continue
                byId.set(action.id, action)
        }
        return [...byId.values()]
}

function updateActions() {
        ensureBuiltinKeybindings();
        if (typeof window.refreshLayerShortcutActions === "function") {
                window.refreshLayerShortcutActions()
        }
        let all = []
        Object.keys(allActions).forEach(key => {
                all.push(...allActions[key])
        })
        setActions(all)
}

function setActions(actions) {
        actions = normalizeActionsById(actions)

        let add = []
        let remove = []
        for (let a of actions) {
                let found = lastActions.find(x => x.id === a.id)
                if (!found) add.push(a)
                else if (found.label !== a.label || found.detail !== a.detail) {
                        remove.push(found)
                        add.push(a)
                }
        }

        for (let a of lastActions) {
                let found = actions.find(x => x.id === a.id)
                if (!found) remove.push(a)
        }

        for (let a of remove) {
                const disposer = monacoActionDisposers.get(a.id) || a.disposer
                if (disposer) {
                        disposer.dispose()
                }
                monacoActionDisposers.delete(a.id)
                delete a.disposer
        }

        if (window.globalEditor) for (let a of add) {
                const existing = monacoActionDisposers.get(a.id)
                if (existing) existing.dispose()
                a.disposer = globalEditor.addAction(a)
                monacoActionDisposers.set(a.id, a.disposer)
        }

        lastActions = actions
        window.lastActions = lastActions
}
window.updateActions = updateActions
window.setActions = setActions

// Ensure builtin actions get monaco keybindings once monaco is available
function ensureBuiltinKeybindings() {
        if (typeof monaco === "undefined") return
        if (!allActions || !allActions.builtin) return
        const map = {
                "new-box": [monaco.KeyCode.KEY_N],
                "delete-box": [monaco.KeyMod.CtrlCmd | monaco.KeyCode.Backspace],
                "copy-selection": [monaco.KeyMod.CtrlCmd | monaco.KeyCode.KEY_C],
                "paste-selection": [monaco.KeyMod.CtrlCmd | monaco.KeyCode.KEY_V],
                "insert-from-workspace": [monaco.KeyMod.CtrlCmd | monaco.KeyCode.KEY_T],
                "search-docs-and-code": [monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.KEY_D],
                "edit-hashtag": [monaco.KeyMod.CtrlCmd | monaco.KeyCode.KEY_H],
        }
        allActions.builtin.forEach(a => {
                const kb = map[a.id]
                if (kb) a.keybindings = kb
        })
}

// --- Anywhere palette support ---
const anywhereProviders = []
const usageKey = "quickpick_anywhere_usage"
let anywhereUsage = {}
const ANYWHERE_RESULT_LIMIT = 150

// load usage eagerly so reranking has data even before the first palette open
loadUsage()

function ensureEditor() {
        let ed = window.globalEditor
        if (!ed && window.monaco && window.monaco.editor && typeof window.monaco.editor.getEditors === "function") {
                const editors = window.monaco.editor.getEditors()
                if (editors && editors.length > 0) {
                        ed = editors[0]
                        window.globalEditor = ed
                }
        }
        if (ed && typeof ed.focus === "function") {
                ed.focus()
        }
        return ed
}
window.ensureEditor = ensureEditor

function triggerQuickInput(run) {
        const editorContainer = document.querySelector('.monaco-editor-container')
        const textarea = document.querySelector("textarea.inputarea.monaco-mouse-cursor-text")
        if (editorContainer) {
                editorContainer.style.opacity = "1"
                editorContainer.style.visibility = "visible"
        }
        if (textarea) {
            try { textarea.focus() } catch (e) {}
        }
        try {
                run()
        } finally {
                // qpLog("quick input fallback", e)
                if (textarea) try { textarea.blur() } catch (_) {}
                const findInput = document.querySelector(".monaco-findInput .input")
                if (findInput) try { findInput.focus() } catch (_) {}
                const qi = document.querySelector('.quick-input-widget')
                if (qi) {
                        qi.style.visibility = "visible"
                        qi.style.display = "block"
                        qi.style.boxShadow = "none"
                }
                if (editorContainer) editorContainer.style.visibility = "hidden"
        }
}
window.triggerQuickInput = triggerQuickInput
window.ensureEditor = ensureEditor

let quickInputCommand = null
let quickInputCommandEditor = null

function loadUsage() {
        try {
                const raw = localStorage.getItem(usageKey)
                if (raw) anywhereUsage = JSON.parse(raw)
        } catch (e) {
                console.warn("failed to load anywhere usage", e)
        }
}

function saveUsage() {
        try {
                localStorage.setItem(usageKey, JSON.stringify(anywhereUsage))
        } catch (e) {
                console.warn("failed to save anywhere usage", e)
        }
}

function bumpUsage(key) {
        const now = Date.now()
        const entry = anywhereUsage[key] || { count: 0, last: 0 }
        entry.count += 1
        entry.last = now
        anywhereUsage[key] = entry
        saveUsage()
}

function registerAnywhereProvider(id, fetcher) {
        qpLog("[anywhere] register provider", id)
        anywhereProviders.push({ id, fetcher })
}
window.registerAnywhereProvider = registerAnywhereProvider

function boxElements() {
        return Array.from(document.getElementsByClassName("box") || [])
}

function boxTitle(box) {
        if (!box) return ""
        const ds = box.dataset || {}
        const textCandidate =
                ds.title ||
                ds.name ||
                box.name ||
                box.title ||
                (box.querySelector?.(".boxtitle, .title, .name, .boxname")?.textContent)
        const raw = (textCandidate || box.textContent || "").trim()
        if (raw) return raw.split("\n")[0].trim()
        return box.uid || ""
}

function bootstrapAnywhereProviders() {
        if (bootstrapAnywhereProviders._installed) return
        bootstrapAnywhereProviders._installed = true

        const propertyNames = [
                "code",
                "vertex",
                "vertex_fragment",
                "vertex_s",
                "fragment",
                "fragment_s",
                "compute",
                "compute_s",
                "task",
                "task_s",
                "mesh",
                "mesh_s",
                "raygen",
                "raygen_s",
                "miss",
                "miss_s",
                "closesthit",
                "closesthit_s",
                "anyhit",
                "anyhit_s",
                "intersection",
                "intersection_s",
                "callable",
                "callable_s",
                "preamble",
                "metadata",
        ]

        // Box name / id provider
        registerAnywhereProvider("box", (query) => {
                const q = (query || "").toLowerCase()
                const results = []
                const boxes = boxElements()
                boxes.forEach((box) => {
                        const title = boxTitle(box)
                        const uid = box.uid || title || "(unknown)"
                        const hay = `${title} ${uid}`.toLowerCase()
                        if (q && !hay.includes(q)) return
                        results.push({
                                id: uid,
                                label: title || uid,
                                description: "Box",
                                detail: "",
                                boxId: uid,
                                property: null,
                                run: () => selectBoxByUid(uid)
                        })
                })
                qpLog("[anywhere] box provider enumeration", { boxes: boxes.length, results: results.length })
                return results
        })

        // Box property text search provider
        registerAnywhereProvider("box-prop", (query) => {
                const q = (query || "").toLowerCase()
                const results = []
                const boxes = boxElements()
                boxes.forEach((box) => {
                        const uid = box.uid || ""
                        const title = boxTitle(box) || uid
                        propertyNames.forEach((prop) => {
                                const val = box[prop]
                                if (typeof val !== "string" || !val) return
                                if (q && !val.toLowerCase().includes(q) && !prop.toLowerCase().includes(q)) return
                                let preview = val.replace(/\s+/g, " ")
                                if (q) {
                                        const idx = preview.toLowerCase().indexOf(q)
                                        if (idx >= 0) {
                                                const start = Math.max(0, idx - 20)
                                                const end = Math.min(preview.length, idx + q.length + 60)
                                                preview = (start > 0 ? "…" : "") + preview.slice(start, end) + (end < preview.length ? "…" : "")
                                        } else {
                                                preview = preview.slice(0, 80)
                                        }
                                } else {
                                        preview = preview.slice(0, 80)
                                }
                                results.push({
                                        id: `${uid}-${prop}`,
                                        label: `${title} / ${prop}`,
                                        description: preview,
                                        detail: "",
                                        boxId: uid,
                                        property: prop,
                                        score: 1,
                                        run: () => {
                                                const el = selectBoxByUid(uid)
                                                setSelectedProperty(prop)
                                                return el
                                        }
                                })
                        })
                })
                qpLog("[anywhere] box-prop enumeration", { boxes: boxes.length, props: propertyNames.length, results: results.length, query: q })
                return results
        })
}
window.bootstrapAnywhereProviders = bootstrapAnywhereProviders
bootstrapAnywhereProviders()

function selectBoxByUid(uid) {
        const boxes = document.getElementsByClassName("box")
        for (let i = 0; i < boxes.length; i++) {
                const box = boxes[i]
                if (box.uid === uid) {
                        return box
                }
        }
        return null
}

function setSelectedProperty(prop) {
        if (!prop) return
        window.selectedProperty = prop
        window.currentSelectedProperty = prop
}

let highlightedBox = null
function clearHoverHighlight() {
        if (!highlightedBox) return
        if (highlightedBox.__qpPrevOutline !== undefined) {
                highlightedBox.style.outline = highlightedBox.__qpPrevOutline
                delete highlightedBox.__qpPrevOutline
        } else {
                highlightedBox.style.outline = ""
        }
        if (highlightedBox.__qpPrevOutlineOffset !== undefined) {
                highlightedBox.style.outlineOffset = highlightedBox.__qpPrevOutlineOffset
                delete highlightedBox.__qpPrevOutlineOffset
        } else {
                highlightedBox.style.outlineOffset = ""
        }
        highlightedBox = null
}

function highlightBox(uid) {
        const el = selectBoxByUid(uid)
        if (!el) {
                clearHoverHighlight()
                return
        }
        if (highlightedBox === el) return
        clearHoverHighlight()
        el.__qpPrevOutline = el.style.outline
        el.__qpPrevOutlineOffset = el.style.outlineOffset
        el.style.outline = "2px dashed #5af"
        el.style.outlineOffset = "8px"
        highlightedBox = el
}

function applySelection(box, property) {
        if (!box) return
        const w = window

        if (typeof w.deselectBox !== "function" || typeof w.selectBox !== "function") {
                console.error("applySelection requires deselectBox/selectBox from Main.kt")
                throw new Error("Selection APIs unavailable")
        }

        // Ensure even the currently-selected box is cycled through deselect/select
        (w.selectedList || []).slice().forEach((b) => w.deselectBox(b))
        w.deselectBox(box)
        setTimeout(() => {
                w.selectBox(box)
                setSelectedProperty(property || w.selectedProperty || "code")
        }, 200)
}
window.applySelection = applySelection

function rerank(results) {
        const now = Date.now()
        const scored = results.map((r) => {
                const key = `${r.provider}:${r.id}`
                const u = anywhereUsage[key] || { count: 0, last: 0 }
                const freq = Math.log(u.count + 1)
                const recency = u.last ? 100000 / (now - u.last + 1000) : 0
                const base = r.score || 0
                return { ...r, score: base + freq + recency, usageKey: key }
        }).sort((a, b) => b.score - a.score)
        if (scored.length > ANYWHERE_RESULT_LIMIT) {
            qpLog(`[anywhere] limiting results ${scored.length} -> ${ANYWHERE_RESULT_LIMIT}`)
        }
        return scored.slice(0, ANYWHERE_RESULT_LIMIT)
}

function openAnywherePalette() {
        triggerQuickInput(() => {
                const ed = ensureEditor()
                if (!ed) {
                        console.warn("No editor available for quick input")
                        return
                }
                const cmd = getQuickInputCommand(ed)
                if (!cmd) return

                ed.trigger("", cmd, (quickInput) => {
                        const qp = quickInput.createQuickPick()
                        qp.matchOnDescription = true
                        qp.matchOnDetail = true
                        qp.placeholder = "Search boxes, properties, or text..."

                        let currentResults = []
                        let currentItemMeta = []

                        const updateItems = async (value) => {
                                const query = (value || "").trim()
                                qpLog("[anywhere] query", query, "providers", anywhereProviders.length)
                                qp.busy = true
                                try {
                                        const pending = []
                                        anywhereProviders.forEach((p) => {
                                                try {
                                                        const res = p.fetcher(query)
                                                        pending.push(Promise.resolve(res).then((items) => {
                                                                qpLog("[anywhere] provider result", p.id, items && items.length)
                                                                return items.map((it, idx) => ({
                                                                        provider: p.id,
                                                                        id: it.id || `${p.id}-${idx}-${it.label}`,
                                                                        label: it.label,
                                                                        description: it.description || "",
                                                                        detail: it.detail || "",
                                                                        boxId: it.boxId,
                                                                        property: it.property,
                                                                        score: it.score || 0,
                                                                        run: it.run
                                                                }))
                                                        }))
                                                } catch (e) {
                                                        console.warn("provider threw", p.id, e)
                                                }
                                        })
                                        const results = (await Promise.all(pending)).flat()
                                        qpLog("[anywhere] combined results", results.length)
                                        currentResults = rerank(results)
                                        const truncated = results.length > currentResults.length

                                        const items = []
                                        currentItemMeta = []

                                        if (truncated) {
                                                items.push({
                                                        label: `Showing ${currentResults.length} of ${results.length}`,
                                                        description: "Type more to narrow results",
                                                        detail: "",
                                                        alwaysShow: true
                                                })
                                                currentItemMeta.push(null)
                                        }

                                        currentResults.forEach((r) => {
                                                items.push({
                                                        label: r.label,
                                                        description: r.description,
                                                        detail: r.detail,
                                                        alwaysShow: true
                                                })
                                                currentItemMeta.push(r)
                                        })

                                        if (items.length === 0) {
                                                items.push({
                                                        label: "No results",
                                                        description: anywhereProviders.length === 0 ? "No providers registered" : "No matches",
                                                        detail: query ? `query: "${query}"` : "",
                                                        alwaysShow: true
                                                })
                                                currentItemMeta.push(null)
                                        }

                                        qp.items = items
                                } finally {
                                        qp.busy = false
                                }
                        }

                        qp.onDidChangeValue(updateItems)
                        qp.onDidAccept(() => {
                                const idx = qp.items.indexOf(qp.selectedItems[0])
                                const meta = idx >= 0 ? currentItemMeta[idx] : null
                                if (!meta) return
                                const r = meta
                                bumpUsage(r.usageKey)
                                qp.hide()
                                if (r.boxId) {
                                        const el = selectBoxByUid(r.boxId)
                                        applySelection(el, r.property)
                                }
                                if (typeof r.run === "function") r.run()
                        })
                        qp.onDidChangeActive(() => {
                                const active = qp.activeItems && qp.activeItems[0]
                                if (!active) {
                                        clearHoverHighlight()
                                        return
                                }
                                const idx = qp.items.indexOf(active)
                                const meta = idx >= 0 ? currentItemMeta[idx] : null
                                if (meta && meta.boxId) highlightBox(meta.boxId); else clearHoverHighlight()
                        })
                        qp.onDidHide(() => {
                                clearHoverHighlight()
                                qp.dispose()
                        })
                        updateItems("")
                        qp.show()
                })
        })
}
window.openAnywherePalette = openAnywherePalette

function getQuickInputCommand(ed) {
        if (!ed) return null
        if (quickInputCommand && quickInputCommandEditor === ed) return quickInputCommand
        quickInputCommand = ed.addCommand(0, (accessor, func) => {
                let quickInputService = accessor.get(globalIS)
                func(quickInputService)
        })
        quickInputCommandEditor = ed
        return quickInputCommand
}

const globalPickMenu_custom = (options) => {
        triggerQuickInput(() => {
                const ed = ensureEditor()
                const cmd = getQuickInputCommand(ed)
                if (!ed || !cmd) return

                ed.trigger("", cmd, (quickInput) => {
                        const qp = quickInput.createQuickPick()
                        qp.matchOnDescription = true
                        qp.matchOnDetail = true
                        qp.placeholder = "Select an option..."

                        // Install side documentation pane (hidden until user presses ArrowRight)
                        const widget = document.querySelector('.quick-input-widget')
                        if (widget) widget.classList.add('qp-has-side-detail')

                        let detailPane = widget?.querySelector(".quickpick-detail-pane")
                        let detailBody
                        if (!detailPane && widget) {
                                detailPane = document.createElement("div")
                                detailPane.className = "quickpick-detail-pane"
                                const title = document.createElement("div")
                                title.className = "quickpick-detail-title"
                                title.textContent = "Documentation"
                                detailBody = document.createElement("div")
                                detailBody.className = "quickpick-detail-body"
                                const hint = document.createElement("div")
                                hint.className = "quickpick-detail-hint"
                                hint.textContent = "Press → to show, ← to hide"
                                detailPane.appendChild(title)
                                detailPane.appendChild(detailBody)
                                detailPane.appendChild(hint)
                                widget.appendChild(detailPane)
                        } else {
                                detailBody = detailPane?.querySelector(".quickpick-detail-body")
                        }

                        const escapeHtml = (s = "") => s
                                .replace(/&/g, "&amp;")
                                .replace(/</g, "&lt;")
                                .replace(/>/g, "&gt;")
                                .replace(/\"/g, "&quot;")
                                .replace(/'/g, "&#39;")

                        const setDetail = (text) => {
                                if (!detailBody) return
                                const lines = (text || "").split(/\r?\n/)
                                const rendered = []
                                let hasContent = false

                                lines.forEach((line) => {
                                        const trimmed = line.trim()
                                        if (trimmed.length === 0) {
                                                rendered.push(`<div class="qp-line">&nbsp;</div>`)
                                                return
                                        }

                                        if (trimmed.startsWith("Boxes:")) {
                                                hasContent = true
                                                const rest = trimmed.substring(6).trim()
                                                const items = rest.split(/\s*,\s*/).filter(Boolean)
                                                const parts = items.map((item) => {
                                                        let base = item
                                                        let mult = ""
                                                        const multMatch = item.match(/^(.*?)[\\s]*[×x][\\s]*(\\d+)$/)
                                                        if (multMatch) {
                                                                base = multMatch[1].trim()
                                                                mult = multMatch[2]
                                                        }

                                                        let name = base
                                                        let props = ""
                                                        const splitIdx = base.indexOf("/{")
                                                        if (splitIdx >= 0) {
                                                                name = base.substring(0, splitIdx)
                                                                props = base.substring(splitIdx)
                                                        }

                                                        const nameHtml = `<strong>${escapeHtml(name)}</strong>`
                                                        const propsHtml = props ? `<span class="qp-box-props">${escapeHtml(props)}</span>` : ""
                                                        const multHtml = mult ? `<span class="qp-box-mult"> × ${escapeHtml(mult)}</span>` : ""
                                                        return `<span class="qp-box-name">${nameHtml}${propsHtml}${multHtml}</span>`
                                                })
                                                rendered.push(`<div class="qp-line qp-boxes"><span class="qp-boxes-label">Boxes:</span> ${parts.join(", ")}</div>`)
                                        } else {
                                                hasContent = hasContent || trimmed.length > 0
                                                rendered.push(`<div class="qp-line">${escapeHtml(line)}</div>`)
                                        }
                                })

                                if (!hasContent) {
                                        detailBody.innerHTML = `<div class="qp-line">No documentation available</div>`
                                } else {
                                        detailBody.innerHTML = rendered.join("")
                                        detailBody.scrollTop = 0
                                }
                        }

                        let detailOpen = false
                        const updateDetailVisibility = () => {
                                if (!detailPane) return
                                if (detailOpen) detailPane.classList.add("visible"); else detailPane.classList.remove("visible")
                        }

                        const toggleDetail = (open) => {
                                detailOpen = open
                                updateDetailVisibility()
                        }

                        const keyHandler = (ev) => {
                                if (ev.key === "ArrowRight") {
                                        toggleDetail(true)
                                } else if (ev.key === "ArrowLeft" && detailOpen) {
                                        toggleDetail(false)
                                } else {
                                        return
                                }
                                ev.preventDefault()
                                ev.stopPropagation()
                        }
                        if (widget && detailPane) {
                                widget.addEventListener("keydown", keyHandler, true)
                        }

                        // Store the original options with their run functions and convert to QuickPick items
                        const optionMeta = []
                        const items = []

                        options.forEach((opt) => {
                                optionMeta.push(opt)
                                items.push({
                                        label: opt.label || "",
                                        description: opt.description || "",
                                        detail: opt.detail || "",
                                        alwaysShow: true
                                })
                        })

                        qp.items = items
                        if (optionMeta.length > 0) setDetail(optionMeta[0].detail || "")
                        updateDetailVisibility()
                        qp.onDidChangeActive(() => {
                                const active = qp.activeItems && qp.activeItems[0]
                                if (!active) return
                                const idx = qp.items.indexOf(active)
                                const meta = idx >= 0 ? optionMeta[idx] : null
                                if (!meta) return
                                setDetail(meta.detail || "")
                                updateDetailVisibility()
                        })

                        qp.onDidAccept(() => {
                                if (!qp.selectedItems || qp.selectedItems.length === 0) return
                                const idx = qp.items.indexOf(qp.selectedItems[0])
                                const meta = idx >= 0 ? optionMeta[idx] : null
                                if (!meta) return
                                qp.hide()
                                if (typeof meta.run === "function") {
                                        qpLog("selection has happened, ", meta)
                                        meta.run()
                                }
                        })

                        qp.onDidHide(() => {
                                if (widget) {
                                        widget.classList.remove('qp-has-side-detail')
                                        widget.removeEventListener("keydown", keyHandler, true)
                                        if (detailPane) widget.removeChild(detailPane)
                                }
                                qp.dispose()
                        })

                        qp.show()
                })
        })
}
window.globalPickMenu_custom = globalPickMenu_custom

const globalPickMenu = () => {
        triggerQuickInput(() => {
                const ed = ensureEditor()
                const cmd = getQuickInputCommand(ed)
                if (!ed || !cmd) return

                ed.trigger("", cmd, (quickInput) => {
                        quickInput.pick(lastActions).then((selected) => {
                                qpLog("selection has happened, ", selected)
                                selected.run()
                        })
                })
        })
}
window.globalPickMenu = globalPickMenu
