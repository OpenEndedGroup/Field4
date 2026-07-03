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
        const key = (...names) => paletteMonacoKeyCode(...names) || 0
        const map = {
                "new-box": [key("KeyN", "KEY_N")],
                "delete-box": [monaco.KeyMod.CtrlCmd | monaco.KeyCode.Backspace],
                "copy-selection": [monaco.KeyMod.CtrlCmd | key("KeyC", "KEY_C")],
                "paste-selection": [monaco.KeyMod.CtrlCmd | key("KeyV", "KEY_V")],
                "insert-from-workspace": [monaco.KeyMod.CtrlCmd | key("KeyT", "KEY_T")],
                "search-docs-and-code": [monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | key("KeyD", "KEY_D")],
                "edit-hashtag": [monaco.KeyMod.CtrlCmd | key("KeyH", "KEY_H")],
                "toggle-notes-markdown-preview": [monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | key("KeyM", "KEY_M")],
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

const paletteEscapeHtml = (value) =>
        String(value ?? "")
                .replace(/&/g, "&amp;")
                .replace(/</g, "&lt;")
                .replace(/>/g, "&gt;")
                .replace(/"/g, "&quot;")
                .replace(/'/g, "&#39;")

const paletteProviderTitle = (provider) => {
        const titles = {
                "box": "Boxes",
                "box-prop": "Properties",
                "field": "Field",
                "editor": "Editor",
                "option": "Options",
        }
        return titles[provider] || String(provider || "Other").replace(/[-_]+/g, " ").replace(/\b\w/g, (m) => m.toUpperCase())
}

const paletteIconFor = (item) => {
        if (item.icon) return item.icon
        if (item.property) return "pr"
        if (item.boxId) return "bx"
        if (item.category === "Editor") return "ed"
        if (item.category === "Properties") return "pr"
        return "cmd"
}

const paletteFuzzyScore = (query, haystack) => {
        const q = String(query || "").trim().toLowerCase()
        if (!q) return 0
        const h = String(haystack || "").toLowerCase()
        if (!h) return -Infinity
        const idx = h.indexOf(q)
        if (idx >= 0) return 120 - idx
        let qi = 0
        let score = 0
        let streak = 0
        for (let hi = 0; hi < h.length && qi < q.length; hi++) {
                if (h[hi] === q[qi]) {
                        qi++
                        streak++
                        score += 6 + streak * 2
                } else {
                        streak = 0
                        score -= 0.15
                }
        }
        return qi === q.length ? score : -Infinity
}

const paletteRenderLabel = (label, query) => {
        const text = String(label || "")
        const q = String(query || "").trim()
        if (!q) return paletteEscapeHtml(text)
        const lower = text.toLowerCase()
        const idx = lower.indexOf(q.toLowerCase())
        if (idx < 0) return paletteEscapeHtml(text)
        return `${paletteEscapeHtml(text.slice(0, idx))}<span class="afield-palette-match">${paletteEscapeHtml(text.slice(idx, idx + q.length))}</span>${paletteEscapeHtml(text.slice(idx + q.length))}`
}

const PALETTE_SHADER_KEYWORDS = new Set([
        "attribute", "break", "buffer", "case", "centroid", "coherent", "const", "continue", "default",
        "discard", "do", "else", "flat", "for", "groupshared", "highp", "if", "in", "inline", "inout",
        "invariant", "layout", "lowp", "mediump", "namespace", "noperspective", "out", "override",
        "patch", "precision", "public", "readonly", "restrict", "return", "sample", "shared", "smooth",
        "static", "struct", "subroutine", "switch", "uniform", "varying", "volatile", "while", "writeonly"
])

const PALETTE_SHADER_TYPES = new Set([
        "void", "bool", "int", "uint", "float", "double", "half",
        "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "uvec2", "uvec3", "uvec4", "bvec2", "bvec3", "bvec4",
        "dvec2", "dvec3", "dvec4", "mat2", "mat3", "mat4", "mat2x2", "mat2x3", "mat2x4", "mat3x2", "mat3x3",
        "mat3x4", "mat4x2", "mat4x3", "mat4x4", "dmat2", "dmat3", "dmat4",
        "float2", "float3", "float4", "float2x2", "float3x3", "float4x4", "half2", "half3", "half4",
        "int2", "int3", "int4", "uint2", "uint3", "uint4", "bool2", "bool3", "bool4", "vector", "matrix",
        "sampler1D", "sampler2D", "sampler3D", "samplerCube", "sampler2DArray", "samplerCubeArray",
        "isampler2D", "usampler2D", "image1D", "image2D", "image3D", "imageCube", "atomic_uint",
        "SamplerState", "SamplerComparisonState", "Texture1D", "Texture2D", "Texture3D", "TextureCube",
        "RWTexture1D", "RWTexture2D", "RWTexture3D", "StructuredBuffer", "RWStructuredBuffer",
        "ByteAddressBuffer", "RWByteAddressBuffer", "ConstantBuffer", "RaytracingAccelerationStructure"
])

const PALETTE_SHADER_LITERALS = new Set(["true", "false", "nullptr"])

const PALETTE_SHADER_BUILTINS = new Set([
        "abs", "acos", "all", "any", "asin", "atan", "ceil", "clamp", "cos", "cross", "degrees", "determinant",
        "distance", "dot", "dFdx", "dFdy", "ddx", "ddy", "equal", "exp", "exp2", "faceforward", "floor", "fract",
        "frac", "fwidth", "greaterThan", "greaterThanEqual", "inversesqrt", "length", "lessThan", "lessThanEqual",
        "lerp", "log", "log2", "max", "min", "mix", "mod", "mul", "normalize", "not", "notEqual", "pow", "radians",
        "reflect", "refract", "round", "rsqrt", "saturate", "sign", "sin", "smoothstep", "sqrt", "step", "tan",
        "tex2D", "texture", "textureGrad", "textureLod", "transpose",
        "gl_FragCoord", "gl_FragDepth", "gl_GlobalInvocationID", "gl_InstanceIndex", "gl_LocalInvocationID",
        "gl_Position", "gl_VertexIndex", "SV_Position", "SV_Target", "SV_DispatchThreadID", "SV_GroupID",
        "SV_GroupIndex", "SV_GroupThreadID", "SV_VertexID", "SV_InstanceID"
])

const paletteShaderLanguages = new Set(["glsl", "slang"])

const paletteHighlightShaderCode = (source) => {
        let out = ""
        let i = 0
        let lineStart = true
        const text = String(source || "")
        const push = (className, value) => {
                out += `<span class="${className}">${paletteEscapeHtml(value)}</span>`
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
                        i = j
                        lineStart = false
                        continue
                }

                const attribute = text.slice(i).match(/^\[[A-Za-z_][A-Za-z0-9_]*(?:\s*\([^)\]]*\))?\]/)
                if (attribute) {
                        push("hljs-meta", attribute[0])
                        i += attribute[0].length
                        lineStart = false
                        continue
                }

                const number = text.slice(i).match(/^(?:0x[0-9a-fA-F]+|(?:\d+\.\d*|\.\d+|\d+)(?:[eE][+-]?\d+)?)[uUlLfFhH]?/)
                if (number) {
                        push("hljs-number", number[0])
                        i += number[0].length
                        lineStart = false
                        continue
                }

                const ident = text.slice(i).match(/^[A-Za-z_][A-Za-z0-9_]*/)
                if (ident) {
                        const word = ident[0]
                        if (PALETTE_SHADER_KEYWORDS.has(word)) push("hljs-keyword", word)
                        else if (PALETTE_SHADER_TYPES.has(word)) push("hljs-type", word)
                        else if (PALETTE_SHADER_LITERALS.has(word)) push("hljs-literal", word)
                        else if (PALETTE_SHADER_BUILTINS.has(word) || word.startsWith("gl_") || word.startsWith("SV_")) push("hljs-built_in", word)
                        else out += paletteEscapeHtml(word)
                        i += word.length
                        lineStart = false
                        continue
                }

                out += paletteEscapeHtml(ch)
                lineStart = ch === "\n" || (lineStart && /\s/.test(ch))
                i++
        }

        return out
}

const paletteTextPreview = (text, empty = "No preview available", language = "") => {
        const raw = String(text || "")
        if (!raw.trim()) return `<div class="afield-palette-doc-empty">${paletteEscapeHtml(empty)}</div>`
        const trimmed = raw.length > 5000 ? `${raw.slice(0, 5000)}\n...` : raw
        const languageClass = language ? ` class="language-${paletteEscapeHtml(language)}"` : ""
        if (paletteShaderLanguages.has(String(language || "").toLowerCase())) {
                return `<pre class="afield-palette-code"><code${languageClass} data-palette-highlighted="shader">${paletteHighlightShaderCode(trimmed)}</code></pre>`
        }
        return `<pre class="afield-palette-code"><code${languageClass}>${paletteEscapeHtml(trimmed)}</code></pre>`
}

const palettePlatformIsMac = () => !!(navigator.platform && /Mac/i.test(navigator.platform))

const paletteMonacoKeyCode = (...names) => {
        if (!window.monaco || !monaco.KeyCode) return undefined
        for (const name of names) {
                const value = monaco.KeyCode[name]
                if (Number.isFinite(Number(value))) return Number(value)
        }
        return undefined
}

const paletteKeyLabelForCode = (code) => {
        if (!Number.isFinite(Number(code))) return ""
        const keyCode = Number(code)
        for (let i = 0; i <= 9; i++) {
                if (keyCode === paletteMonacoKeyCode(`Digit${i}`, `KEY_${i}`)) return String(i)
        }
        for (let i = 0; i < 26; i++) {
                const letter = String.fromCharCode(65 + i)
                if (keyCode === paletteMonacoKeyCode(`Key${letter}`, `KEY_${letter}`)) return letter
        }
        const fixed = [
                [["Backspace"], "Backspace"],
                [["Tab"], "Tab"],
                [["Enter"], "Enter"],
                [["Escape"], "Esc"],
                [["Space"], "Space"],
                [["PageUp"], "PageUp"],
                [["PageDown"], "PageDown"],
                [["End"], "End"],
                [["Home"], "Home"],
                [["LeftArrow"], "Left"],
                [["UpArrow"], "Up"],
                [["RightArrow"], "Right"],
                [["DownArrow"], "Down"],
                [["Insert"], "Insert"],
                [["Delete"], "Delete"],
                [["Quote", "US_QUOTE"], "'"],
                [["Semicolon", "US_SEMICOLON"], ";"],
                [["Comma", "US_COMMA"], ","],
                [["Period", "US_DOT"], "."],
                [["Slash", "US_SLASH"], "/"],
                [["Backslash", "US_BACKSLASH"], "\\"],
                [["BracketLeft", "US_OPEN_SQUARE_BRACKET"], "["],
                [["BracketRight", "US_CLOSE_SQUARE_BRACKET"], "]"],
                [["Minus", "US_MINUS"], "-"],
                [["Equal", "US_EQUAL"], "="],
                [["Backquote", "US_BACKTICK"], "`"],
        ]
        for (const [names, label] of fixed) {
                if (keyCode === paletteMonacoKeyCode(...names)) return label
        }
        for (let i = 1; i <= 24; i++) {
                if (keyCode === paletteMonacoKeyCode(`F${i}`)) return `F${i}`
        }
        return ""
}

const paletteShortcutForKeybinding = (binding) => {
        const raw = Number(binding)
        if (!Number.isFinite(raw) || !window.monaco || !monaco.KeyMod) return ""
        const mac = palettePlatformIsMac()
        const parts = []
        if (raw & monaco.KeyMod.CtrlCmd) parts.push(mac ? "Cmd" : "Ctrl")
        if (raw & monaco.KeyMod.Shift) parts.push("Shift")
        if (raw & monaco.KeyMod.Alt) parts.push(mac ? "Opt" : "Alt")
        if (monaco.KeyMod.WinCtrl && (raw & monaco.KeyMod.WinCtrl)) parts.push(mac ? "Ctrl" : "Win")
        const key = paletteKeyLabelForCode(raw & 0xff)
        if (key) parts.push(key)
        return parts.join("+")
}

const paletteActionKeybindings = (action) => {
        if (!action) return []
        const direct = action.keybindings || action._keybindings
        if (direct && typeof direct[Symbol.iterator] === "function") return Array.from(direct)
        if (Number.isFinite(Number(action.keybinding))) return [Number(action.keybinding)]
        if (action._kbOpts && Number.isFinite(Number(action._kbOpts.primary))) return [Number(action._kbOpts.primary)]
        if (action._kbOpts && action._kbOpts.kbExpr && Number.isFinite(Number(action._kbOpts.kbExpr.primary))) {
                return [Number(action._kbOpts.kbExpr.primary)]
        }
        return []
}

const paletteShortcutForAction = (action) =>
        paletteActionKeybindings(action)
                .map(paletteShortcutForKeybinding)
                .filter(Boolean)
                .join("  ")

const PALETTE_PROPERTY_INFO = {
        code: ["Kotlin (main)", "Main live Kotlin code for the selected box. Run it to produce outputs, mutate properties, or drive graphics state."],
        vertex: ["Vertex Shader (GLSL)", "GLSL vertex stage inherited by graphics modules that use this box."],
        fragment: ["Fragment Shader (GLSL)", "GLSL fragment stage, often the quickest place to sketch material and image behavior."],
        stackSL: ["StackSL Shader Function", "StackSL shader function source for Field's stack-oriented shader workflow."],
        vertex_fragment: ["Shared Vertex+Fragment Shader (Slang)", "Slang source shared by vertex and fragment shader stages."],
        compute: ["Compute Shader (GLSL)", "GLSL compute shader source for GPU data generation or transformation."],
        task: ["Task Shader (GLSL)", "GLSL task shader stage used with mesh shader pipelines."],
        mesh: ["Mesh Shader (GLSL)", "GLSL mesh shader stage used to generate geometry on the GPU."],
        raygen: ["Ray Generation Shader (GLSL)", "Ray tracing ray-generation stage."],
        miss: ["Miss Shader (GLSL)", "Ray tracing miss stage."],
        closesthit: ["Closest Hit Shader (GLSL)", "Ray tracing closest-hit stage."],
        anyhit: ["Any Hit Shader (GLSL)", "Ray tracing any-hit stage."],
        intersection: ["Intersection Shader (GLSL)", "Ray tracing custom intersection stage."],
        callable: ["Callable Shader (GLSL)", "Ray tracing callable shader stage."],
        vertex_s: ["Vertex Shader (Slang)", "Slang vertex shader stage."],
        fragment_s: ["Fragment Shader (Slang)", "Slang fragment shader stage."],
        compute_s: ["Compute Shader (Slang)", "Slang compute shader stage."],
        task_s: ["Task Shader (Slang)", "Slang task shader stage."],
        mesh_s: ["Mesh Shader (Slang)", "Slang mesh shader stage."],
        raygen_s: ["Ray Generation Shader (Slang)", "Slang ray-generation stage."],
        miss_s: ["Miss Shader (Slang)", "Slang miss stage."],
        closesthit_s: ["Closest Hit Shader (Slang)", "Slang closest-hit stage."],
        anyhit_s: ["Any Hit Shader (Slang)", "Slang any-hit stage."],
        intersection_s: ["Intersection Shader (Slang)", "Slang custom intersection stage."],
        callable_s: ["Callable Shader (Slang)", "Slang callable shader stage."],
        preamble: ["Kotlin (preamble)", "Kotlin definitions that support the main code property."],
        introduction: ["Kotlin (introduction)", "Introductory Kotlin source for examples and guided documents."],
        attic: ["Kotlin (attic)", "A scratch holding area for code you may want later."],
        notes: ["Notes (Markdown)", "Markdown notes for the selected box."],
        metadata: ["Metadata", "YAML metadata about this box."],
        contents: ["File contents", "Plain file-like contents associated with this box."],
}

const PALETTE_PROPERTY_LANGUAGE = {
        code: "kotlin",
        vertex: "glsl",
        fragment: "glsl",
        stackSL: "stacksl",
        vertex_fragment: "slang",
        compute: "glsl",
        task: "glsl",
        mesh: "glsl",
        raygen: "glsl",
        miss: "glsl",
        closesthit: "glsl",
        anyhit: "glsl",
        intersection: "glsl",
        callable: "glsl",
        vertex_s: "slang",
        fragment_s: "slang",
        compute_s: "slang",
        task_s: "slang",
        mesh_s: "slang",
        raygen_s: "slang",
        miss_s: "slang",
        closesthit_s: "slang",
        anyhit_s: "slang",
        intersection_s: "slang",
        callable_s: "slang",
        preamble: "kotlin",
        introduction: "kotlin",
        attic: "kotlin",
        notes: "markdown",
        metadata: "yaml",
        contents: "plaintext",
}

const palettePropertyInfo = (property) =>
        PALETTE_PROPERTY_INFO[property] || [property || "Property", "A named property on the selected box."]

const palettePropertyLanguage = (property, box) => {
        if (property === "code" && box && typeof box.code_language === "string" && box.code_language.trim()) {
                return box.code_language.trim()
        }
        if (property === "contents" && box && typeof box.contents_language === "string" && box.contents_language.trim()) {
                return box.contents_language.trim()
        }
        return PALETTE_PROPERTY_LANGUAGE[property] || "plaintext"
}

const palettePropertyCursorPosition = (box, property) => {
        const fallback = { lineNumber: 1, column: 1 }
        if (!box || !property) return fallback
        try {
                const selected = window.currentSelectedCode || []
                const currentProperty = window.currentSelectedProperty || window.selectedProperty || "code"
                if (selected.length === 1 && selected[0] === box && currentProperty === property && window.globalEditor) {
                        const position = window.globalEditor.getPosition && window.globalEditor.getPosition()
                        const lineNumber = Number(position && position.lineNumber)
                        const column = Number(position && position.column)
                        if (lineNumber > 0) return { lineNumber, column: column > 0 ? column : 1 }
                }
        } catch (_) {}
        const raw = box[`${property}_editorState`]
        if (typeof raw !== "string" || !raw.trim()) return fallback
        try {
                const state = JSON.parse(raw)
                const cursor = Array.isArray(state.cursorState) ? state.cursorState[0] : null
                const selection = cursor && cursor.selection
                const position = cursor && cursor.position
                const lineNumber = Math.max(1, Number(
                        selection?.positionLineNumber ||
                        selection?.selectionStartLineNumber ||
                        position?.lineNumber ||
                        1
                ) || 1)
                const column = Math.max(1, Number(
                        selection?.positionColumn ||
                        selection?.selectionStartColumn ||
                        position?.column ||
                        1
                ) || 1)
                return { lineNumber, column }
        } catch (_) {
                return fallback
        }
}

const paletteExcerptForProperty = (text, cursor, radius = 5) => {
        const lines = String(text || "").replace(/\r\n/g, "\n").split("\n")
        if (!lines.length) return { text: "", start: 1, end: 1, centerLine: 1 }
        const target = Math.max(1, Math.min(lines.length, Number(cursor && cursor.lineNumber) || 1))
        const column = Math.max(1, Number(cursor && cursor.column) || 1)
        const start = Math.max(1, target - radius)
        const end = Math.min(lines.length, start + radius * 2)
        const excerptLines = lines.slice(start - 1, end)
        const cursorIndex = target - start
        if (cursorIndex >= 0 && cursorIndex < excerptLines.length) {
                const line = excerptLines[cursorIndex] || ""
                const visualColumn = Math.max(1, Math.min(column, line.length + 1))
                excerptLines.splice(cursorIndex + 1, 0, `${" ".repeat(visualColumn - 1)}^`)
        }
        if (start > 1) excerptLines.unshift("...")
        if (end < lines.length) excerptLines.push("...")
        return {
                text: excerptLines.join("\n"),
                start,
                end,
                centerLine: target,
        }
}

const paletteSelectedBoxes = () => {
        const sources = [window.selectedList, window.currentSelectedCode, window.selected]
        for (const source of sources) {
                const boxes = Array.from(source || [])
                if (boxes.length) return boxes
        }
        return []
}

const paletteBoxUid = (box) => box && (box.uid || box.getAttribute?.("uid") || "")

const paletteBoxName = (box) => {
        const uid = paletteBoxUid(box)
        return boxTitle(box) || uid || "(unnamed)"
}

const paletteBoxListHtml = (boxes, empty = "No boxes selected") => {
        if (!boxes || !boxes.length) return `<div class="afield-palette-doc-empty">${paletteEscapeHtml(empty)}</div>`
        return `<div class="afield-palette-box-list">` + boxes.map((box, index) => {
                const uid = paletteBoxUid(box)
                return `<div class="afield-palette-box-line"><span>${index + 1}</span><strong>${paletteEscapeHtml(paletteBoxName(box))}</strong><code>${paletteEscapeHtml(uid)}</code></div>`
        }).join("") + `</div>`
}

const paletteBoxLabel = (uid) => {
        const box = selectBoxByUid(uid)
        return boxTitle(box) || uid || ""
}

const palettePreviewForAnywhere = (item) => {
        if (!item) return ""
        const box = item.boxId ? selectBoxByUid(item.boxId) : null
        const title = item.boxId ? paletteBoxLabel(item.boxId) : item.label
        const bits = [
                `<div class="afield-palette-doc-title">${paletteEscapeHtml(title || item.label || "")}</div>`,
        ]
        if (item.property && box && typeof box[item.property] === "string") {
                bits.push(`<div class="afield-palette-doc-meta">${paletteEscapeHtml(item.property)} on ${paletteEscapeHtml(item.boxId)}</div>`)
                bits.push(paletteTextPreview(box[item.property], "Property is empty", palettePropertyLanguage(item.property, box)))
        } else {
                if (item.boxId) bits.push(`<div class="afield-palette-doc-meta">${paletteEscapeHtml(item.boxId)}</div>`)
                if (item.description) bits.push(`<p>${paletteEscapeHtml(item.description)}</p>`)
                if (item.detail) bits.push(`<p class="afield-palette-doc-muted">${paletteEscapeHtml(item.detail)}</p>`)
        }
        return bits.join("")
}

const paletteEvaluateDocJs = (item) => {
        if (!item || !item.docJs) return ""
        const selection = paletteSelectedBoxes()
        try {
                const expression = new Function("action", "selection", "boxes", "escapeHtml", `return (${item.docJs});`)
                return expression(item.action || item, selection, selection, paletteEscapeHtml) || ""
        } catch (expressionError) {
                try {
                        const body = new Function("action", "selection", "boxes", "escapeHtml", item.docJs)
                        return body(item.action || item, selection, selection, paletteEscapeHtml) || ""
                } catch (bodyError) {
                        console.warn("palette docJs failed", expressionError, bodyError)
                        return `<div class="afield-palette-doc-empty">Documentation script failed</div>`
                }
        }
}

const palettePreviewForPropertyAction = (item) => {
        const prop = item.propertyPreview
        if (!prop) return ""
        const property = prop.property
        const box = prop.box
        const value = String(prop.value || "")
        const [title, description] = palettePropertyInfo(property)
        const language = palettePropertyLanguage(property, box)
        const bits = [
                `<div class="afield-palette-doc-title">${paletteEscapeHtml(title)}</div>`,
                `<p>${paletteEscapeHtml(description)}</p>`,
        ]
        if (!value.trim()) {
                bits.push(`<div class="afield-palette-warning">This property is empty. Switching to it is useful when you mean to author new ${paletteEscapeHtml(property)} content.</div>`)
                return bits.join("")
        }
        const cursor = palettePropertyCursorPosition(box, property)
        const excerpt = paletteExcerptForProperty(value, cursor)
        bits.push(paletteTextPreview(excerpt.text, "Property is empty", language))
        return bits.join("")
}

const paletteSelectionPreviewForAction = (item) => {
        const id = String(item && item.id || "")
        const boxes = paletteSelectedBoxes()
        if (id === "delete-box") {
                return `<div class="afield-palette-doc-title">Delete selected boxes</div>` +
                        `<div class="afield-palette-warning">This removes the selected boxes from the document.</div>` +
                        paletteBoxListHtml(boxes, "No boxes selected")
        }
        if (id === "-copy" || id === "copy-selection") {
                return `<div class="afield-palette-doc-title">${paletteEscapeHtml(item.label || "Copy selection")}</div>` +
                        `<p>${paletteEscapeHtml(item.detail || "Copy or duplicate the selected boxes.")}</p>` +
                        paletteBoxListHtml(boxes, "No boxes selected")
        }
        if (id === "export-selection-edn" || id === "save-as-template") {
                return `<div class="afield-palette-doc-title">${paletteEscapeHtml(item.label || "Selection")}</div>` +
                        `<p>${paletteEscapeHtml(item.detail || "Use the current selection.")}</p>` +
                        paletteBoxListHtml(boxes, "No boxes selected")
        }
        if (id === "connect" || id === "disconnect" || id === "nest-into") {
                const source = boxes[0]
                const targets = boxes.slice(1)
                const verb = id === "connect" ? "Connect" : id === "disconnect" ? "Disconnect" : "Nest"
                const relationship = id === "connect"
                        ? "The first selected box becomes the parent/source. The remaining boxes become children/targets."
                        : id === "disconnect"
                                ? "The first selected box is disconnected from the remaining selected boxes."
                                : "The remaining selected boxes move into the first selected box's nest."
                return `<div class="afield-palette-doc-title">${verb} boxes</div>` +
                        `<p>${paletteEscapeHtml(relationship)}</p>` +
                        `<div class="afield-palette-doc-source"><span>source</span><code>${paletteEscapeHtml(source ? paletteBoxName(source) : "none")}</code></div>` +
                        `<div class="afield-palette-doc-source"><span>targets</span><code>${paletteEscapeHtml(targets.length ? targets.map(paletteBoxName).join(", ") : "none")}</code></div>` +
                        paletteBoxListHtml(boxes, "Select at least two boxes")
        }
        if (id === "un-nest" || id === "normalize-nested-pan" || id === "move-to-active-layer") {
                return `<div class="afield-palette-doc-title">${paletteEscapeHtml(item.label || "")}</div>` +
                        `<p>${paletteEscapeHtml(item.detail || "")}</p>` +
                        paletteBoxListHtml(boxes, "No boxes selected")
        }
        return ""
}

const palettePreviewForAction = (item) => {
        if (item && item.docHtml) return item.docHtml
        const evaluated = paletteEvaluateDocJs(item)
        if (evaluated) return String(evaluated)
        if (item && item.propertyPreview) return palettePreviewForPropertyAction(item)
        const selectionPreview = paletteSelectionPreviewForAction(item)
        if (selectionPreview) return selectionPreview
        const bits = [`<div class="afield-palette-doc-title">${paletteEscapeHtml(item.label || "")}</div>`]
        if (item.description) bits.push(`<div class="afield-palette-doc-meta">${paletteEscapeHtml(item.description)}</div>`)
        if (item.detail) bits.push(`<p>${paletteEscapeHtml(item.detail)}</p>`)
        if (item.id) bits.push(`<div class="afield-palette-doc-source"><span>id</span><code>${paletteEscapeHtml(item.id)}</code></div>`)
        const prop = item.propertyPreview
        if (prop) {
                bits.push(`<div class="afield-palette-doc-source"><span>property</span><code>${paletteEscapeHtml(prop.property)}</code></div>`)
                bits.push(paletteTextPreview(prop.value, "Property is empty", palettePropertyLanguage(prop.property, prop.box)))
        }
        return bits.join("")
}

class FieldPaletteController {
        constructor() {
                this.items = []
                this.activeIndex = -1
                this.generation = 0
                this.options = {}
                this.root = this.createRoot()
        }

        createRoot() {
                const root = document.createElement("div")
                root.className = "afield-palette"
                root.style.display = "none"
                root.innerHTML = `
                        <div class="afield-palette-shell">
                                <div class="afield-palette-header">
                                        <div class="afield-palette-title"></div>
                                        <input class="afield-palette-input" type="text" spellcheck="false" autocomplete="off">
                                </div>
                                <div class="afield-palette-hint"></div>
                                <div class="afield-palette-body">
                                        <div class="afield-palette-list" role="listbox"></div>
                                        <div class="afield-palette-doc" role="region" aria-label="Palette preview"></div>
                                </div>
                        </div>
                `
                document.body.appendChild(root)
                root.addEventListener("keydown", (event) => {
                        if (event.key === "Escape") this.closeFromKeyboard(event)
                }, true)
                root.addEventListener("keyup", (event) => {
                        if (event.key === "Escape") {
                                event.preventDefault()
                                event.stopPropagation()
                                if (typeof event.stopImmediatePropagation === "function") event.stopImmediatePropagation()
                        }
                }, true)
                root.addEventListener("mousedown", (event) => {
                        if (event.target === root) this.hide({ restoreFocus: true })
                        else event.preventDefault()
                })
                root.addEventListener("click", (event) => {
                        const row = event.target.closest(".afield-palette-row")
                        if (!row) return
                        this.setActive(Number(row.dataset.index || 0))
                        this.accept()
                })
                root.querySelector(".afield-palette-input").addEventListener("input", () => this.refresh())
                root.querySelector(".afield-palette-input").addEventListener("keydown", (event) => this.onKeyDown(event))
                return root
        }

        input() { return this.root.querySelector(".afield-palette-input") }
        list() { return this.root.querySelector(".afield-palette-list") }
        doc() { return this.root.querySelector(".afield-palette-doc") }

        open(options = {}) {
                this.options = options
                this.generation++
                this.root.querySelector(".afield-palette-title").textContent = options.title || "Palette"
                const hint = this.root.querySelector(".afield-palette-hint")
                hint.textContent = options.hint || ""
                hint.style.display = options.hint ? "block" : "none"
                const input = this.input()
                input.value = options.query || ""
                input.placeholder = options.placeholder || "Type to filter..."
                this.root.style.display = "grid"
                this.renderLoading()
                this.refresh()
                requestAnimationFrame(() => {
                        input.focus()
                        input.select()
                })
        }

        hide(options = {}) {
                if (this.root.style.display === "none") return
                const input = this.input()
                if (input) {
                        try { input.blur() } catch (_) {}
                }
                this.root.style.display = "none"
                this.items = []
                this.activeIndex = -1
                this.generation++
                if (typeof this.options.onHide === "function") this.options.onHide()
                if (options.restoreFocus) {
                        requestAnimationFrame(() => {
                                try { ensureEditor() } catch (_) {}
                        })
                }
        }

        closeFromKeyboard(event) {
                event.preventDefault()
                event.stopPropagation()
                if (typeof event.stopImmediatePropagation === "function") event.stopImmediatePropagation()
                this.hide({ restoreFocus: true })
        }

        renderLoading() {
                this.list().innerHTML = `<div class="afield-palette-empty"><span class="afield-suggest-spinner" aria-hidden="true"></span></div>`
                this.doc().innerHTML = `<div class="afield-palette-doc-empty"></div>`
        }

        async refresh() {
                const generation = ++this.generation
                const query = this.input().value || ""
                const source = this.options.getItems
                        ? await Promise.resolve(this.options.getItems(query))
                        : (this.options.items || [])
                if (generation !== this.generation) return
                this.items = this.groupOrderItems(this.rankItems(source || [], query))
                this.activeIndex = this.items.length ? 0 : -1
                this.render(query)
        }

        rankItems(items, query) {
                const q = String(query || "").trim()
                const tokens = q.toLowerCase().split(/\s+/).filter(Boolean)
                const substringMatch = (haystack) => {
                        if (!tokens.length) return 0
                        const lower = String(haystack || "").toLowerCase()
                        if (!tokens.every((token) => lower.includes(token))) return -Infinity
                        return tokens.reduce((score, token) => {
                                const index = lower.indexOf(token)
                                return score + (index === 0 ? 10 : 5) - Math.min(index, 40) * 0.05
                        }, 0)
                }
                return items
                        .map((item, index) => {
                                const haystack = [item.label, item.description, item.detail, item.id, item.category].filter(Boolean).join(" ")
                                const fuzzy = this.options.matchMode === "substring"
                                        ? substringMatch(haystack)
                                        : paletteFuzzyScore(q, haystack)
                                const score = (item.score || 0) + fuzzy - index * 0.001
                                return { item, score, fuzzy, index }
                        })
                        .filter((entry) => !q || Number.isFinite(entry.fuzzy))
                        .sort((a, b) => b.score - a.score || a.index - b.index)
                        .map((entry) => entry.item)
        }

        groupOrderItems(items) {
                const ordered = []
                this.groupedItemsFor(items).forEach((group) => {
                        group.entries.forEach(({ item }) => ordered.push(item))
                })
                return ordered
        }

        groupedItemsFor(items) {
                const groups = []
                const byTitle = new Map()
                ;(items || []).forEach((item, index) => {
                        const title = item.category || paletteProviderTitle(item.provider)
                        let group = byTitle.get(title)
                        if (!group) {
                                group = { title, entries: [] }
                                byTitle.set(title, group)
                                groups.push(group)
                        }
                        group.entries.push({ item, index })
                })
                return groups
        }

        groupedItems() {
                return this.groupedItemsFor(this.items)
        }

        render(query) {
                if (!this.items.length) {
                        this.list().innerHTML = `<div class="afield-palette-empty">No matches</div>`
                        this.doc().innerHTML = `<div class="afield-palette-doc-empty">No preview available</div>`
                        if (typeof this.options.onActive === "function") this.options.onActive(null)
                        return
                }
                const html = []
                this.groupedItems().forEach((group) => {
                        html.push(`<div class="afield-palette-section">${paletteEscapeHtml(group.title)}</div>`)
                        group.entries.forEach(({ item, index }) => {
                                const active = index === this.activeIndex ? " is-active" : ""
                                const unset = item.propertyPreview && !String(item.propertyPreview.value || "").trim() ? " is-unset-property" : ""
                                const shortcut = item.shortcut || paletteShortcutForAction(item.action || item)
                                html.push(`
                                        <div class="afield-palette-row${active}${unset}" role="option" aria-selected="${index === this.activeIndex}" data-index="${index}">
                                                <div class="afield-palette-icon">${paletteEscapeHtml(String(paletteIconFor(item)).slice(0, 3))}</div>
                                                <div class="afield-palette-main">
                                                        <div class="afield-palette-label">${paletteRenderLabel(item.label, query)}</div>
                                                        <div class="afield-palette-detail">${paletteEscapeHtml(item.description || item.detail || item.id || "")}</div>
                                                </div>
                                                <div class="afield-palette-shortcut">${paletteEscapeHtml(shortcut)}</div>
                                        </div>
                                `)
                        })
                })
                this.list().innerHTML = html.join("")
                this.renderDoc()
                const active = this.list().querySelector(".afield-palette-row.is-active")
                if (active) this.scrollActiveIntoView(active)
        }

        scrollActiveIntoView(active) {
                const list = this.list()
                if (!list || !active) return
                active.scrollIntoView({ block: "nearest" })
                const section = list.querySelector(".afield-palette-section")
                const sectionHeight = section ? section.getBoundingClientRect().height : 0
                const listRect = list.getBoundingClientRect()
                const activeRect = active.getBoundingClientRect()
                const topLimit = listRect.top + sectionHeight
                if (activeRect.top < topLimit) {
                        list.scrollTop -= topLimit - activeRect.top
                } else if (activeRect.bottom > listRect.bottom) {
                        list.scrollTop += activeRect.bottom - listRect.bottom
                }
        }

        renderDoc() {
                const item = this.items[this.activeIndex]
                if (!item) {
                        this.doc().innerHTML = `<div class="afield-palette-doc-empty">No preview available</div>`
                        return
                }
                if (typeof item.previewHtml === "function") this.doc().innerHTML = item.previewHtml(item)
                else if (item.previewHtml) this.doc().innerHTML = item.previewHtml
                else this.doc().innerHTML = palettePreviewForAction(item)
                if (typeof this.options.onActive === "function") this.options.onActive(item)
                if (window.hljs && typeof window.hljs.highlightElement === "function") {
                        this.doc().querySelectorAll("pre code").forEach((block) => {
                                if (block.dataset && block.dataset.paletteHighlighted) return
                                try { window.hljs.highlightElement(block) } catch (_) {}
                        })
                }
        }

        setActive(index) {
                if (!this.items.length) return
                this.activeIndex = Math.max(0, Math.min(this.items.length - 1, index))
                this.render(this.input().value || "")
        }

        move(delta) {
                this.setActive(this.activeIndex + delta)
        }

        accept() {
                const item = this.items[this.activeIndex]
                if (!item) return
                this.hide()
                if (typeof item.run === "function") item.run()
        }

        onKeyDown(event) {
                const key = String(event.key || "").toLowerCase()
                const repeatCommandPalette = (event.ctrlKey || event.metaKey) && event.shiftKey && !event.altKey && key === "p"
                if (repeatCommandPalette && this.options.includeEditor && typeof window.openFieldCommandPalette === "function") {
                        event.preventDefault()
                        event.stopPropagation()
                        window.openFieldCommandPalette({ includeEditor: false, editor: window.globalEditor })
                } else if (event.key === "ArrowDown") {
                        event.preventDefault()
                        this.move(1)
                } else if (event.key === "ArrowUp") {
                        event.preventDefault()
                        this.move(-1)
                } else if (event.key === "PageDown") {
                        event.preventDefault()
                        this.move(8)
                } else if (event.key === "PageUp") {
                        event.preventDefault()
                        this.move(-8)
                } else if (event.key === "Enter" || event.key === "Tab") {
                        event.preventDefault()
                        this.accept()
                } else if (event.key === "Escape") {
                        this.closeFromKeyboard(event)
                }
        }
}

let fieldPaletteController = null
function fieldPalette() {
        if (!fieldPaletteController) fieldPaletteController = new FieldPaletteController()
        return fieldPaletteController
}

function openAnywherePalette() {
        ensureEditor()
        fieldPalette().open({
                title: "Open Anything",
                placeholder: "Search boxes, properties, or text...",
                getItems: async (value) => {
                        const query = (value || "").trim()
                        qpLog("[anywhere] query", query, "providers", anywhereProviders.length)
                        const pending = []
                        anywhereProviders.forEach((p) => {
                                try {
                                        const res = p.fetcher(query)
                                        pending.push(Promise.resolve(res).then((items) => {
                                                qpLog("[anywhere] provider result", p.id, items && items.length)
                                                return (items || []).map((it, idx) => ({
                                                        provider: p.id,
                                                        category: paletteProviderTitle(p.id),
                                                        id: it.id || `${p.id}-${idx}-${it.label}`,
                                                        label: it.label,
                                                        description: it.description || "",
                                                        detail: it.detail || "",
                                                        boxId: it.boxId,
                                                        property: it.property,
                                                        score: it.score || 0,
                                                        runSource: it.run,
                                                }))
                                        }))
                                } catch (e) {
                                        console.warn("provider threw", p.id, e)
                                }
                        })
                        const results = rerank((await Promise.all(pending)).flat())
                        return results.map((r) => ({
                                ...r,
                                previewHtml: () => palettePreviewForAnywhere(r),
                                run: () => {
                                        bumpUsage(r.usageKey)
                                        if (r.boxId) {
                                                const el = selectBoxByUid(r.boxId)
                                                applySelection(el, r.property)
                                        }
                                        if (typeof r.runSource === "function") r.runSource()
                                }
                        }))
                },
                onActive: (item) => {
                        if (item && item.boxId) highlightBox(item.boxId); else clearHoverHighlight()
                },
                onHide: clearHoverHighlight,
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

const commandSkipIds = new Set([
        "editor.action.quickCommand",
        "embedded-command-palette",
])

const paletteBaseActionId = (id) => {
        const text = String(id || "")
        const index = text.lastIndexOf(":")
        return index >= 0 ? text.substring(index + 1) : text
}

const paletteCommandIdentity = (item) => {
        const id = item && item.id ? paletteBaseActionId(item.id) : ""
        return id || String(item && item.label || "")
}

const paletteActionMetadata = (action, editor) => {
        const metadata = editor && editor.afieldActionMetadata
        if (!metadata || !action) return null
        const id = String(action.id || "")
        return metadata[id] || metadata[paletteBaseActionId(id)] || null
}

const propertyPreviewForAction = (action) => {
        const id = paletteBaseActionId(action && action.id)
        if (!id.startsWith("switch-property-")) return null
        const property = id.substring("switch-property-".length)
        const selected = paletteSelectedBoxes()
        const box = selected[0]
        const value = box && typeof box[property] === "string" ? box[property] : ""
        return { property, value, box }
}

const palettePropertySwitchKeybindings = (property) => {
        if (!window.monaco || !monaco.KeyMod) return []
        const keys = {
                code: "KeyM",
                vertex: "KeyV",
                fragment: "KeyF",
                stackSL: "KeyM",
                vertex_fragment: "KeyP",
                compute: "KeyD",
        }
        const keyName = keys[property]
        if (!keyName) return []
        const keyCode = paletteMonacoKeyCode(keyName, keyName.replace("Key", "KEY_"))
        return keyCode ? [monaco.KeyMod.Alt | keyCode] : []
}

const normalizeCommandAction = (action, category, editor) => {
        if (!action || !action.id) return null
        const actionId = String(action.id || "")
        const baseId = paletteBaseActionId(actionId)
        if (commandSkipIds.has(actionId) || commandSkipIds.has(baseId)) return null
        const label = action.label || action.alias || action.id
        if (!label || label === "Command Palette") return null
        const metadata = paletteActionMetadata(action, editor)
        const metadataSource = metadata || action
        const propertyPreview = propertyPreviewForAction(action)
        const itemCategory = propertyPreview ? "Properties" : category
        return {
                id: action.id,
                label,
                description: action.description || action.alias || "",
                detail: action.detail || action.id,
                category: itemCategory,
                propertyPreview,
                keybindings: paletteActionKeybindings(metadataSource),
                shortcut: action.shortcut || metadataSource.shortcut || paletteShortcutForAction(metadataSource),
                docHtml: action.docHtml || metadataSource.docHtml || "",
                docJs: action.docJs || metadataSource.docJs || "",
                action: metadataSource,
                previewHtml: (item) => palettePreviewForAction(item),
                run: () => {
                        const runner = action.run || metadataSource.run || action._run || metadataSource._run
                        if (typeof runner === "function") runner(editor || window.globalEditor)
                },
        }
}

const collectDocumentCommandItems = () => {
        if (typeof updateActions === "function") {
                try { updateActions() } catch (e) { console.warn("updateActions failed", e) }
        }
        return paletteDedupeCommandItems([
                ...collectPropertyCommandItems(),
                ...(window.lastActions || [])
                .map((action) => normalizeCommandAction(action, "Document", window.globalEditor))
                .filter(Boolean),
        ])
}

const paletteMergeCommandItem = (target, source) => {
        if (!target || !source) return target
        const sourceKeybindings = source.keybindings || paletteActionKeybindings(source.action || source)
        if ((!target.keybindings || !target.keybindings.length) && sourceKeybindings && sourceKeybindings.length) {
                target.keybindings = sourceKeybindings
        }
        const sourceShortcut = source.shortcut || paletteShortcutForAction(source.action || source)
        if (!target.shortcut && sourceShortcut) target.shortcut = sourceShortcut
        if (!target.docHtml && source.docHtml) target.docHtml = source.docHtml
        if (!target.docJs && source.docJs) target.docJs = source.docJs
        return target
}

const paletteDedupeCommandItems = (items) => {
        const result = []
        const byKey = new Map()
        ;(items || []).forEach((item) => {
                const key = paletteCommandIdentity(item)
                const existing = byKey.get(key)
                if (existing) {
                        paletteMergeCommandItem(existing, item)
                } else {
                        byKey.set(key, item)
                        result.push(item)
                }
        })
        return result
}

const collectPropertyCommandItems = () => {
        const selected = paletteSelectedBoxes()
        const box = selected.length === 1 ? selected[0] : null
        if (!box) return []
        const uid = paletteBoxUid(box)
        const priority = [
                "code", "fragment", "vertex", "stackSL", "vertex_fragment",
                "compute", "task", "mesh", "preamble", "notes", "metadata",
                "contents", "attic", "introduction",
        ]
        const names = [
                ...priority,
                ...Object.keys(PALETTE_PROPERTY_INFO).filter((name) => !priority.includes(name)),
        ]
        return names.map((property, index) => {
                const [title, description] = palettePropertyInfo(property)
                const value = typeof box[property] === "string" ? box[property] : ""
                const keybindings = palettePropertySwitchKeybindings(property)
                const action = {
                        id: `switch-property-${property}`,
                        label: `Switch to ${property}`,
                        description: title,
                        detail: description,
                        run: () => {
                                if (uid && typeof window.switchToProperty === "function") {
                                        window.switchToProperty(uid, property)
                                } else {
                                        window.currentSelectedProperty = property
                                        window.selectedProperty = property
                                }
                        },
                }
                return {
                        id: action.id,
                        label: action.label,
                        description: title,
                        detail: description,
                        category: "Properties",
                        score: 24 - index * 0.01,
                        propertyPreview: { property, value, box },
                        keybindings,
                        shortcut: keybindings.map(paletteShortcutForKeybinding).filter(Boolean).join("  "),
                        action,
                        previewHtml: (item) => palettePreviewForAction(item),
                        run: action.run,
                }
        })
}

const collectEditorCommandItems = (editor) => {
        const ed = editor || window.globalEditor
        if (!ed) return []
        const actions = []
        try {
                if (typeof ed.getSupportedActions === "function") actions.push(...ed.getSupportedActions())
        } catch (_) {}
        try {
                const raw = ed._actions
                if (raw && typeof raw.values === "function") actions.push(...raw.values())
                else if (raw) Object.keys(raw).forEach((key) => actions.push(raw[key]))
        } catch (_) {}
        const seen = new Set()
        return actions
                .map((action) => normalizeCommandAction(action, "Editor", ed))
                .filter(Boolean)
                .filter((item) => {
                        const key = item.id || item.label
                        if (seen.has(key)) return false
                        seen.add(key)
                        return true
                })
}

function openFieldCommandPalette(options = {}) {
        const includeEditor = !!options.includeEditor
        const editor = options.editor || window.globalEditor
        const shortcut = navigator.platform && /Mac/i.test(navigator.platform)
                ? "Cmd+Shift+P"
                : "Ctrl+Shift+P"
        const documentItems = collectDocumentCommandItems()
        const editorItems = includeEditor ? collectEditorCommandItems(editor) : []
        const items = paletteDedupeCommandItems([...documentItems, ...editorItems])
        fieldPalette().open({
                includeEditor,
                title: includeEditor ? "Command Palette" : "Document Commands",
                placeholder: includeEditor ? "Run an editor or Field command..." : "Run a document command...",
                hint: includeEditor ? `Press ${shortcut} again for document-level commands.` : "",
                items,
                matchMode: "substring",
        })
}
window.openFieldCommandPalette = openFieldCommandPalette

const globalPickMenu_custom = (options) => {
        const items = (options || []).map((opt, index) => ({
                id: opt.id || `option-${index}-${opt.label || ""}`,
                label: opt.label || "",
                description: opt.description || "",
                detail: opt.detail || "",
                keybindings: paletteActionKeybindings(opt),
                shortcut: opt.shortcut || paletteShortcutForAction(opt),
                docHtml: opt.docHtml || "",
                docJs: opt.docJs || "",
                category: "Options",
                previewHtml: (item) => palettePreviewForAction(item),
                run: () => {
                        if (typeof opt.run === "function") {
                                qpLog("selection has happened, ", opt)
                                opt.run()
                        }
                },
        }))
        fieldPalette().open({
                title: "Select",
                placeholder: "Filter options...",
                items,
        })
}
window.globalPickMenu_custom = globalPickMenu_custom

const globalPickMenu = () => {
        openFieldCommandPalette({ includeEditor: false, editor: window.globalEditor })
}
window.globalPickMenu = globalPickMenu
