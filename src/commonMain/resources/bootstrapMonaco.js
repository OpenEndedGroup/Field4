
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
        require(["bithero/glsl", "bithero/slang", "bithero/forth", "bithero/stacksl", "bithero/nano"], function () {
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

const escapeProbeHtml = (value) => String(value ?? "")
        .replace(/&/g, "&amp;")
        .replace(/</g, "&lt;")
        .replace(/>/g, "&gt;")
        .replace(/"/g, "&quot;")
        .replace(/'/g, "&#39;")

const STACKSL_NUMBER_SOURCE = "[+-]?(?:(?:\\d+\\.\\d*)|(?:\\d*\\.\\d+)|(?:\\d+))(?:[eE][+-]?\\d+)?"
const STACKSL_MODIFIER_SOURCE = "pulse|noise|grain|hash|saw|tri|fbm|rad|ang|wx|wy|wr|wa|cx|cy|sq|sh|n|j|c|s|t|r|g|b|a|x|y"
const STACKSL_NUMBER_RE = new RegExp(`^${STACKSL_NUMBER_SOURCE}$`)
const STACKSL_NUMBER_SUFFIX_RE = new RegExp(`^${STACKSL_NUMBER_SOURCE}[trgba]$`, "i")
const STACKSL_NUMBER_RANGE_RE = new RegExp(`^${STACKSL_NUMBER_SOURCE}_${STACKSL_NUMBER_SOURCE}$`, "i")
const STACKSL_NUMBER_MODIFIER_RE = new RegExp(`^${STACKSL_NUMBER_SOURCE}(?:${STACKSL_MODIFIER_SOURCE})(?:${STACKSL_NUMBER_SOURCE})?(?:p${STACKSL_NUMBER_SOURCE})?(?:d${STACKSL_NUMBER_SOURCE})?$`, "i")
const STACKSL_TOP_MODIFIER_RE = new RegExp(`^[\\|\\]](?:${STACKSL_MODIFIER_SOURCE})(?:${STACKSL_NUMBER_SOURCE})?(?:p${STACKSL_NUMBER_SOURCE})?(?:d${STACKSL_NUMBER_SOURCE})?$`, "i")
const STACKSL_SWIZZLE_RE = /^\.?[xyzwrgba]{1,4}$/i
const STACKSL_COLOR_WORDS = new Set([
        "black", "white", "gray", "grey", "red", "green", "blue", "cyan", "magenta", "yellow", "orange",
])

const STACKSL_PUSH_WORDS = new Set([
        "input", "in", "uv", "p", "pos", "center", "centered", "time", "t", "hash", "h",
        "pi", "tau", "e",
        ...STACKSL_COLOR_WORDS,
        "prev", "state",
        "0->", "1->", "2->", "3->",
])

const STACKSL_UNARY_WORDS = new Set([
        "neg", "negate", "abs", "floor", "ceil", "fract", "sqrt", "inversesqrt", "exp", "log",
        "sin", "cos", "tan", "asin", "acos", "atan", "normalize",
        "len", "length", "len2", "length2", "len3", "length3", "len4", "length4",
        "luma", "average", "avg", "mean", "median", "med", "vmin", "minimum", "vmax", "maximum",
        "sat", "saturate", "clamp", "mirror", "tonemap",
        "ryb", "ryb2rgb", "from-ryb", "rgb2ryb", "to-ryb",
        "oklab", "oklab2rgb", "from-oklab", "rgb2oklab", "to-oklab",
        "noise", "perlin", "fbm", "not",
])

const STACKSL_BINARY_WORDS = new Set([
        "+", "add", "-", "sub", "*", "mul", "/", "div", "mod", "pow",
        "atan2", "dot", "cross",
        "lt", "<", "lte", "<=", "gt", ">", "gte", ">=", "eq", "=", "neq", "!=", "<>",
        "and", "or",
])

const stackslEffect = (consume, produce, label = "") => ({ consume, produce, label })
const stackslDoc = (signature, detail) => ({ signature, detail })

const STACKSL_WORD_DOC_ALIASES = new Map([
        ["in", "input"],
        ["pos", "p"],
        ["center", "p"],
        ["centered", "p"],
        ["t", "time"],
        ["h", "hash"],
        ["v2", "vec2"],
        ["v3", "vec3"],
        ["v4", "vec4"],
        ["negate", "neg"],
        ["len", "length"],
        ["len2", "length"],
        ["length2", "length"],
        ["len3", "length3"],
        ["len4", "length4"],
        ["average", "avg"],
        ["mean", "avg"],
        ["med", "median"],
        ["minimum", "vmin"],
        ["maximum", "vmax"],
        ["saturate", "sat"],
        ["clamp", "sat"],
        ["perlin", "noise"],
        ["cos-palette", "palette"],
        ["cosine-palette", "palette"],
        ["sat-color", "saturation"],
        ["diff-ema", "ema-diff"],
        ["dema", "ema-diff"],
        ["from-ryb", "ryb"],
        ["ryb2rgb", "ryb"],
        ["to-ryb", "rgb2ryb"],
        ["from-oklab", "oklab"],
        ["oklab2rgb", "oklab"],
        ["to-oklab", "rgb2oklab"],
        ["add", "+"],
        ["sub", "-"],
        ["mul", "*"],
        ["div", "/"],
        ["lerp", "mix"],
])

const STACKSL_WORD_DOCS = new Map([
        ["noop", stackslDoc("--", "No operation.")],
        ["input", stackslDoc("-- input", "Push the full vec4(uv.x, uv.y, time, hash).")],
        ["uv", stackslDoc("-- uv", "Push normalized uv as vec4(uv.xy, 0, 1).")],
        ["p", stackslDoc("-- p", "Push centered uv as vec4(uv.xy - 0.5, 0, 1).")],
        ["time", stackslDoc("-- time", "Push time from input.z, splatted to vec4.")],
        ["hash", stackslDoc("-- hash", "Push per-pixel hash from input.w, splatted to vec4.")],
        ["pi", stackslDoc("-- value", "Push pi, splatted to vec4.")],
        ["tau", stackslDoc("-- value", "Push tau, splatted to vec4.")],
        ["e", stackslDoc("-- value", "Push e, splatted to vec4.")],
        ["dup", stackslDoc("a -- a a", "Duplicate the top stack value.")],
        ["drop", stackslDoc("a --", "Discard the top stack value.")],
        ["swap", stackslDoc("a b -- b a", "Swap the top two stack values.")],
        ["over", stackslDoc("a b -- a b a", "Copy the second value to the top.")],
        ["rot", stackslDoc("a b c -- b c a", "Rotate the third value to the top.")],
        ["nip", stackslDoc("a b -- b", "Drop the value under the top.")],
        ["tuck", stackslDoc("a b -- b a b", "Copy the top value under the previous value.")],
        ["vec2", stackslDoc("x y -- vec", "Build vec4(x.x, y.x, 0, 1).")],
        ["vec3", stackslDoc("x y z -- vec", "Build vec4(x.x, y.x, z.x, 1).")],
        ["vec4", stackslDoc("x y z w -- vec", "Build vec4 from the .x channel of four stack values.")],
        ["neg", stackslDoc("a -- out", "Negate component-wise.")],
        ["abs", stackslDoc("a -- out", "Absolute value component-wise.")],
        ["floor", stackslDoc("a -- out", "Floor component-wise.")],
        ["ceil", stackslDoc("a -- out", "Ceil component-wise.")],
        ["fract", stackslDoc("a -- out", "Fractional part component-wise.")],
        ["sqrt", stackslDoc("a -- out", "Square root component-wise.")],
        ["inversesqrt", stackslDoc("a -- out", "Inverse square root component-wise.")],
        ["exp", stackslDoc("a -- out", "Exponential component-wise.")],
        ["log", stackslDoc("a -- out", "Natural log component-wise.")],
        ["sin", stackslDoc("phase -- out", "Sine component-wise.")],
        ["cos", stackslDoc("phase -- out", "Cosine component-wise.")],
        ["tan", stackslDoc("phase -- out", "Tangent component-wise.")],
        ["asin", stackslDoc("a -- out", "Arc sine component-wise.")],
        ["acos", stackslDoc("a -- out", "Arc cosine component-wise.")],
        ["atan", stackslDoc("a -- out", "Arc tangent component-wise.")],
        ["atan2", stackslDoc("y x -- angle", "Two-argument atan.")],
        ["normalize", stackslDoc("vec -- out", "Normalize component vector.")],
        ["length", stackslDoc("vec -- scalar", "Length of vec.xy, splatted to vec4.")],
        ["length3", stackslDoc("vec -- scalar", "Length of vec.xyz, splatted to vec4.")],
        ["length4", stackslDoc("vec -- scalar", "Length of full vec4, splatted.")],
        ["luma", stackslDoc("color -- scalar", "Rec.709 luminance, splatted.")],
        ["avg", stackslDoc("vec -- scalar", "Average of RGBA channels, splatted.")],
        ["median", stackslDoc("vec -- scalar", "Median-ish RGBA channel value, splatted.")],
        ["vmin", stackslDoc("vec -- scalar", "Minimum RGBA channel, splatted.")],
        ["vmax", stackslDoc("vec -- scalar", "Maximum RGBA channel, splatted.")],
        ["sat", stackslDoc("color -- color", "Clamp all channels to 0..1.")],
        ["mirror", stackslDoc("a -- out", "Triangle-wrap values into 0..1.")],
        ["tonemap", stackslDoc("color -- color", "Compress bright colors for feedback.")],
        ["ryb", stackslDoc("ryb -- rgb", "Convert paint-like RYB color to RGB.")],
        ["rgb2ryb", stackslDoc("rgb -- ryb", "Convert RGB to paint-like RYB.")],
        ["oklab", stackslDoc("oklab -- rgb", "Convert OKLab-like values to RGB.")],
        ["rgb2oklab", stackslDoc("rgb -- oklab", "Convert RGB to perceptual-ish OKLab.")],
        ["+", stackslDoc("a b -- out", "Add component-wise.")],
        ["-", stackslDoc("a b -- out", "Subtract component-wise.")],
        ["*", stackslDoc("a b -- out", "Multiply component-wise.")],
        ["/", stackslDoc("a b -- out", "Safe divide component-wise.")],
        ["mod", stackslDoc("a b -- out", "Safe modulo component-wise.")],
        ["pow", stackslDoc("base exp -- out", "Power with a safe absolute base.")],
        ["dot", stackslDoc("a b -- scalar", "Dot product, splatted.")],
        ["cross", stackslDoc("a b -- vec", "Cross product of xyz, alpha set to 1.")],
        ["lt", stackslDoc("a b -- mask", "lessThan mask in 0..1.")],
        ["lte", stackslDoc("a b -- mask", "lessThanEqual mask in 0..1.")],
        ["gt", stackslDoc("a b -- mask", "greaterThan mask in 0..1.")],
        ["gte", stackslDoc("a b -- mask", "greaterThanEqual mask in 0..1.")],
        ["eq", stackslDoc("a b -- mask", "equal mask in 0..1.")],
        ["neq", stackslDoc("a b -- mask", "notEqual mask in 0..1.")],
        ["and", stackslDoc("a b -- mask", "Boolean-ish and using threshold 0.5.")],
        ["or", stackslDoc("a b -- mask", "Boolean-ish or using threshold 0.5.")],
        ["not", stackslDoc("a -- mask", "Boolean-ish not using threshold 0.5.")],
        ["mix", stackslDoc("a b amount -- out", "Clamped component-wise mix.")],
        ["step", stackslDoc("edge x -- mask", "GLSL step, with defaults if needed.")],
        ["smoothstep", stackslDoc("low high x -- mask", "GLSL smoothstep, with defaults if needed.")],
        ["noise", stackslDoc("coord -- scalar", "Value noise from coord.xy + coord.zw * 0.07.")],
        ["fbm", stackslDoc("coord -- scalar", "Five-octave fractal noise.")],
        ["sample", stackslDoc("offset -- color", "Sample previous frame at fract(uv + offset.xy).")],
        ["sample-abs", stackslDoc("coord -- color", "Sample previous frame at fract(coord.xy).")],
        ["gather", stackslDoc("offset -- vec", "textureGather red channel around uv + offset.xy.")],
        ["prev", stackslDoc("-- color", "Sample previous frame at the current uv.")],
        ["state", stackslDoc("-- color", "Alias for prev.")],
        ["0->", stackslDoc("-- value", "Read stored red channel at this pixel.")],
        ["1->", stackslDoc("-- value", "Read stored green channel at this pixel.")],
        ["2->", stackslDoc("-- value", "Read stored blue channel at this pixel.")],
        ["3->", stackslDoc("-- value", "Read stored alpha channel at this pixel.")],
        ["->0", stackslDoc("value --", "Write value.x to output red channel.")],
        ["->1", stackslDoc("value --", "Write value.x to output green channel.")],
        ["->2", stackslDoc("value --", "Write value.x to output blue channel.")],
        ["->3", stackslDoc("value --", "Write value.x to output alpha channel.")],
])

const stackSLCanonicalWord = (word) => STACKSL_WORD_DOC_ALIASES.get(word) || word

const stackSLDocForWord = (rawWord, effect, definitionWords) => {
        const word = String(rawWord || "").toLowerCase()
        const canonical = stackSLCanonicalWord(word)

        if (STACKSL_NUMBER_RANGE_RE.test(word)) {
                return stackslDoc("-- vec", "Push a vec4 range spread across four scalar values.")
        }
        if (STACKSL_NUMBER_SUFFIX_RE.test(word)) {
                return stackslDoc("-- value", "Push a time-scaled or channel-weighted number.")
        }
        if (STACKSL_NUMBER_MODIFIER_RE.test(word)) {
                return stackslDoc("-- value", "Push amount times a time/spatial/noise modifier.")
        }
        if (STACKSL_NUMBER_RE.test(word)) {
                return stackslDoc("-- value", "Push the number splatted to vec4.")
        }
        if (STACKSL_COLOR_WORDS.has(word)) {
                return stackslDoc("-- color", "Push a named RGBA color.")
        }
        if (word === "|" || word === "]") {
                return stackslDoc("--", "Modifier prefix; combine it with a suffix such as |c2 or ]fbm4.")
        }
        if (STACKSL_TOP_MODIFIER_RE.test(word)) {
                return word.startsWith("]")
                        ? stackslDoc("driver -- out", "Use the consumed value as modifier driver; amplitude is one.")
                        : stackslDoc("amount -- out", "Multiply consumed amount by a time/spatial/noise modifier.")
        }
        if (STACKSL_SWIZZLE_RE.test(word)) {
                return stackslDoc("vec -- vec", "Swizzle channels and return a vec4-shaped value.")
        }

        if (word === "min" || word === "max") {
                return effect.consume === 1
                        ? stackslDoc("vec -- scalar", `${word} of RGBA channels, splatted.`)
                        : stackslDoc("a b -- out", `${word} component-wise.`)
        }
        if (canonical === "pulse") {
                return effect.consume === 1
                        ? stackslDoc("x -- mask", "Soft band with default center 0.5 and width 0.08.")
                        : stackslDoc("x center width -- mask", "Soft band around center with the given width.")
        }
        if (canonical === "fm") {
                return effect.consume === 1
                        ? stackslDoc("phase -- out", "Frequency modulation with default carrier and modulator.")
                        : stackslDoc("phase carrier mod -- out", "Frequency-modulated sine field.")
        }
        if (canonical === "palette") {
                return effect.consume === 1
                        ? stackslDoc("t -- color", "Cosine palette using the default color bands.")
                        : stackslDoc("t a b c d -- color", "Cosine palette a + b*cos(t*c + d).")
        }
        if (canonical === "saturation") {
                return effect.consume === 1
                        ? stackslDoc("color -- color", "Saturation with default amount 1.")
                        : stackslDoc("color amount -- color", "Adjust color saturation.")
        }
        if (canonical === "contrast") {
                return effect.consume === 1
                        ? stackslDoc("color -- color", "Contrast with default amount 1.2.")
                        : stackslDoc("color amount -- color", "Adjust contrast around mid gray.")
        }
        if (canonical === "grade") {
                return effect.consume === 1
                        ? stackslDoc("color -- color", "Neutral lift/gain/gamma grade.")
                        : stackslDoc("color lift gain gamma -- color", "Color grade with lift, gain, and gamma.")
        }
        if (canonical === "ema") {
                return effect.consume === 1
                        ? stackslDoc("current -- smooth", "EMA with default previous value and alpha 0.08.")
                        : stackslDoc("current previous alpha -- smooth", "Exponential moving average.")
        }
        if (canonical === "ema-diff") {
                return effect.consume === 1
                        ? stackslDoc("current -- diff", "Current minus default EMA.")
                        : stackslDoc("current previous alpha -- diff", "Current minus exponential moving average.")
        }

        if (/^sample[0-3]$/.test(word)) return STACKSL_WORD_DOCS.get("sample")
        if (/^sample[0-3]-abs$/.test(word)) return STACKSL_WORD_DOCS.get("sample-abs")
        if (/^gather[0-3]$/.test(word)) return STACKSL_WORD_DOCS.get("gather")
        if (word === "<") return STACKSL_WORD_DOCS.get("lt")
        if (word === "<=") return STACKSL_WORD_DOCS.get("lte")
        if (word === ">") return STACKSL_WORD_DOCS.get("gt")
        if (word === ">=") return STACKSL_WORD_DOCS.get("gte")
        if (word === "=") return STACKSL_WORD_DOCS.get("eq")
        if (word === "!=" || word === "<>") return STACKSL_WORD_DOCS.get("neq")

        const doc = STACKSL_WORD_DOCS.get(canonical)
        if (doc) return doc
        if (definitionWords && definitionWords.has(word)) {
                return stackslDoc("input -- out", "User-defined StackSL word call.")
        }
        return stackslDoc(`${effect.consume}->${effect.produce}`, effect.label || "Unknown word.")
}

const tokenizeStackSLForContext = (source) => {
        const tokens = []
        let i = 0
        let line = 1
        let column = 1

        const advance = () => {
                const ch = source[i]
                i += 1
                if (ch === "\n") {
                        line += 1
                        column = 1
                } else {
                        column += 1
                }
                return ch
        }

        const skipLineComment = () => {
                while (i < source.length && source[i] !== "\n") advance()
        }

        while (i < source.length) {
                const ch = source[i]
                if (/\s/.test(ch)) {
                        advance()
                } else if (ch === "#") {
                        skipLineComment()
                } else if (ch === "/" && source[i + 1] === "/") {
                        skipLineComment()
                } else if (ch === "(") {
                        while (i < source.length && source[i] !== ")") advance()
                        if (i < source.length) advance()
                } else {
                        const start = i
                        const startLine = line
                        const startColumn = column
                        let text = ""
                        while (i < source.length) {
                                const c = source[i]
                                if (/\s/.test(c) || c === "#" || c === "(") break
                                if (c === "/" && source[i + 1] === "/") break
                                text += c
                                advance()
                        }
                        if (text.length > 0) {
                                tokens.push({
                                        text,
                                        lower: text.toLowerCase(),
                                        start,
                                        end: i,
                                        line: startLine,
                                        column: startColumn,
                                })
                        }
                }
        }
        return tokens
}

const parseStackSLDefinitionsForContext = (tokens) => {
        const root = []
        const definitions = []
        const definitionWords = new Set()
        let index = 0
        while (index < tokens.length) {
                const token = tokens[index]
                if (token.text === ":") {
                        const name = tokens[index + 1] || null
                        const body = []
                        const colon = token
                        index += 2
                        while (index < tokens.length && tokens[index].text !== ";") {
                                body.push(tokens[index])
                                index += 1
                        }
                        const terminator = index < tokens.length ? tokens[index] : null
                        const end = terminator ? terminator.end : ((body[body.length - 1] || name || colon).end)
                        const definition = {
                                colon,
                                name,
                                body,
                                terminator,
                                start: colon.start,
                                end,
                        }
                        definitions.push(definition)
                        if (name && /^[A-Za-z_][A-Za-z0-9_-]*$/.test(name.text)) {
                                definitionWords.add(name.lower)
                        }
                        if (terminator) index += 1
                } else {
                        root.push(token)
                        index += 1
                }
        }
        return { root, definitions, definitionWords }
}

const findStackSLTokenAtOffset = (tokens, offset) => {
        if (!tokens || tokens.length === 0) return null
        const safeOffset = Math.max(0, offset)
        let previous = null
        for (const token of tokens) {
                if (token.start <= safeOffset && safeOffset <= token.end) return token
                if (token.end < safeOffset) previous = token
        }
        if (previous && previous.end === safeOffset) return previous
        return null
}

const findStackSLDefinitionContext = (parsed, offset) => {
        for (const definition of parsed.definitions) {
                if (definition.start <= offset && offset <= definition.end) {
                        return {
                                kind: "definition",
                                definition,
                                tokens: definition.body,
                                initialDepth: 1,
                        }
                }
        }
        return {
                kind: "root",
                definition: null,
                tokens: parsed.root,
                initialDepth: 1,
        }
}

const isStackSLPushToken = (word) => {
        return STACKSL_PUSH_WORDS.has(word) ||
                STACKSL_NUMBER_RE.test(word) ||
                STACKSL_NUMBER_SUFFIX_RE.test(word) ||
                STACKSL_NUMBER_RANGE_RE.test(word) ||
                STACKSL_NUMBER_MODIFIER_RE.test(word)
}

const dynamicStackSLEffect = (depth, fullArity, shortLabel) => {
        if (depth < fullArity) return stackslEffect(1, 1, shortLabel || "short form")
        return stackslEffect(fullArity, 1)
}

const stackSLEffectForWord = (rawWord, depth, definitionWords) => {
        const word = String(rawWord || "").toLowerCase()
        if (!word) return stackslEffect(0, 0)
        if (word === ":" || word === ";") return stackslEffect(0, 0, "definition marker")
        if (word === "|" || word === "]") return stackslEffect(0, 0, "modifier prefix")
        if (STACKSL_TOP_MODIFIER_RE.test(word)) return stackslEffect(1, 1, word.startsWith("]") ? "driven modifier" : "top modifier")
        if (isStackSLPushToken(word)) return stackslEffect(0, 1, "push")
        if (STACKSL_SWIZZLE_RE.test(word)) return stackslEffect(1, 1, "swizzle")

        if (word === "noop") return stackslEffect(0, 0)
        if (word === "dup") return stackslEffect(1, 2)
        if (word === "drop") return stackslEffect(1, 0)
        if (word === "swap") return stackslEffect(2, 2)
        if (word === "over") return stackslEffect(2, 3)
        if (word === "rot") return stackslEffect(3, 3)
        if (word === "nip") return stackslEffect(2, 1)
        if (word === "tuck") return stackslEffect(2, 3)

        if (word === "vec2" || word === "v2") return stackslEffect(2, 1)
        if (word === "vec3" || word === "v3") return stackslEffect(3, 1)
        if (word === "vec4" || word === "v4") return stackslEffect(4, 1)
        if (word === "->0" || word === "->1" || word === "->2" || word === "->3") return stackslEffect(1, 0, "store write")

        if (STACKSL_UNARY_WORDS.has(word)) return stackslEffect(1, 1)
        if (STACKSL_BINARY_WORDS.has(word)) return stackslEffect(2, 1)

        if (word === "min" || word === "max") {
                return depth <= 2 ? stackslEffect(1, 1, "reduce top") : stackslEffect(2, 1)
        }
        if (word === "mix" || word === "lerp") return stackslEffect(3, 1)
        if (word === "step") return stackslEffect(2, 1)
        if (word === "smoothstep") return stackslEffect(3, 1)
        if (word === "pulse" || word === "fm" || word === "ema" || word === "ema-diff" || word === "diff-ema" || word === "dema") {
                return dynamicStackSLEffect(depth, 3, "short form")
        }
        if (word === "palette" || word === "cos-palette" || word === "cosine-palette") {
                return dynamicStackSLEffect(depth, 5, "short form")
        }
        if (word === "saturation" || word === "sat-color" || word === "contrast") {
                return dynamicStackSLEffect(depth, 2, "short form")
        }
        if (word === "grade") {
                return dynamicStackSLEffect(depth, 4, "short form")
        }

        if (word === "sample" || word === "sample0" || word === "sample1" || word === "sample2" || word === "sample3" ||
                word === "sample-abs" || word === "sample0-abs" || word === "sample1-abs" || word === "sample2-abs" || word === "sample3-abs" ||
                word === "gather" || word === "gather0" || word === "gather1" || word === "gather2" || word === "gather3") {
                return stackslEffect(1, 1, word.includes("abs") ? "absolute sample" : "sample")
        }

        if (definitionWords && definitionWords.has(word)) return stackslEffect(1, 1, "word call")
        return stackslEffect(0, 0, "unknown")
}

const applyStackSLEffectToDepth = (depth, effect) => {
        const missing = Math.max(0, effect.consume - depth)
        const nextDepth = Math.max(0, depth - effect.consume) + effect.produce
        return { depth: nextDepth, missing }
}

const analyzeStackSLDepth = (tokens, initialDepth, definitionWords) => {
        let depth = initialDepth
        let underflows = 0
        for (const token of tokens) {
                const effect = stackSLEffectForWord(token.text, depth, definitionWords)
                const applied = applyStackSLEffectToDepth(depth, effect)
                depth = applied.depth
                underflows += applied.missing
        }
        return { depth, underflows }
}

const formatStackSLRelativeDepth = (depth, initialDepth) => {
        const relative = depth - initialDepth
        if (relative === 0) return "0"
        return `${relative > 0 ? "+" : ""}${relative}`
}

const formatStackSLEffect = (effect) => {
        const base = `${effect.consume}->${effect.produce}`
        return effect.label ? `${base} ${effect.label}` : base
}

const buildStackSLContextPayload = (editor, resolveContext, options = {}) => {
        const allowUnfocused = !!options.allowUnfocused
        if (!editor || (!allowUnfocused && typeof editor.hasTextFocus === "function" && !editor.hasTextFocus())) return null
        const model = editor.getModel && editor.getModel()
        if (!model || model.getLanguageId() !== "stacksl") return null

        const position = editor.getPosition && editor.getPosition()
        if (!position) return null

        const source = model.getValue()
        const offset = model.getOffsetAt(position)
        const tokens = tokenizeStackSLForContext(source)
        const parsed = parseStackSLDefinitionsForContext(tokens)
        const context = findStackSLDefinitionContext(parsed, offset)
        const activeToken = findStackSLTokenAtOffset(tokens, offset)
        const activeInContext = activeToken && context.tokens.includes(activeToken) ? activeToken : null
        const tokensBefore = activeInContext
                ? context.tokens.filter((token) => token.end <= activeInContext.start)
                : context.tokens.filter((token) => token.end <= offset)
        const before = analyzeStackSLDepth(tokensBefore, context.initialDepth, parsed.definitionWords)

        let after = before
        let wordHtml = ""
        if (activeToken) {
                if (activeInContext) {
                        const effect = stackSLEffectForWord(activeInContext.text, before.depth, parsed.definitionWords)
                        const doc = stackSLDocForWord(activeInContext.text, effect, parsed.definitionWords)
                        const applied = applyStackSLEffectToDepth(before.depth, effect)
                        after = { depth: applied.depth, underflows: before.underflows + applied.missing }
                        const afterDepth = context.kind === "definition"
                                ? `rel ${formatStackSLRelativeDepth(after.depth, context.initialDepth)}`
                                : `depth ${after.depth}`
                        const signatureHtml = doc && doc.signature
                                ? `<span class="afield-stacksl-signature">${escapeProbeHtml(doc.signature)}</span>`
                                : `<span class="afield-stacksl-signature">${escapeProbeHtml(formatStackSLEffect(effect))}</span>`
                        const arityHtml = doc && doc.signature
                                ? `<span class="afield-stacksl-muted">(${escapeProbeHtml(formatStackSLEffect(effect))})</span>`
                                : ""
                        const detailHtml = doc && doc.detail
                                ? `<span class="afield-stacksl-muted">${escapeProbeHtml(doc.detail)}</span>`
                                : ""
                        const missingHtml = applied.missing > 0
                                ? `<span class="afield-stacksl-warning-text">needs ${effect.consume}, has ${before.depth}</span>`
                                : ""
                        wordHtml = `<span class="afield-stacksl-word"><code>${escapeProbeHtml(activeInContext.text)}</code> ${signatureHtml} ${arityHtml}; after ${escapeProbeHtml(afterDepth)}${missingHtml ? " · " + missingHtml : ""}${detailHtml ? " · " + detailHtml : ""}</span>`
                } else if (context.kind === "definition" && activeToken === context.definition.name) {
                        wordHtml = `<span class="afield-stacksl-word"><code>${escapeProbeHtml(activeToken.text)}</code> definition name</span>`
                } else if (activeToken.text === ":" || activeToken.text === ";") {
                        wordHtml = `<span class="afield-stacksl-word"><code>${escapeProbeHtml(activeToken.text)}</code> definition marker</span>`
                }
        }

        const scopeName = context.kind === "definition" && context.definition && context.definition.name
                ? `:${context.definition.name.text}`
                : "root"
        const depthHtml = context.kind === "definition"
                ? `relative stack <strong>${escapeProbeHtml(formatStackSLRelativeDepth(before.depth, context.initialDepth))}</strong>`
                : `stack depth <strong>${before.depth}</strong>`
        const underflowCount = after.underflows
        const underflowHtml = underflowCount > 0
                ? `<span class="afield-stacksl-warning-text">${underflowCount} default/wrap fill${underflowCount === 1 ? "" : "s"}</span>`
                : ""
        const warningClass = underflowCount > 0 ? " afield-stacksl-warning" : ""
        const html = `<div class="afield-stacksl-context${warningClass}"><strong>StackSL</strong> <span class="afield-stacksl-scope">${escapeProbeHtml(scopeName)}</span> ${depthHtml}${wordHtml ? " · " + wordHtml : ""}${underflowHtml ? " · " + underflowHtml : ""}</div>`

        let contextKey = "stacksl"
        const resolved = resolveContext ? resolveContext(editor) : null
        if (resolved && resolved.uid && resolved.property) contextKey = `${resolved.uid}::${resolved.property}`
        return {
                key: `${contextKey}::${model.getVersionId()}::${offset}`,
                editor,
                html,
                isLiveValue: false,
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
                        const localPayload = buildStackSLContextPayload(editor, resolveContext)
                        if (localPayload) {
                                const state = ensureSymbolContextState()
                                state.latestDesiredKey = localPayload.key
                                state.queued = null
                                setEditorContextBadge(editor, localPayload)
                                state.lastNotifiedKey = localPayload.key
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

                        const buildSuggestions = (payload, opts = {}) => {
                                let idx = opts.offset || 0
                                const rangeObj = rangeForPayload(payload, opts.model || model)
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
                                        const aiSuggestions = buildSuggestions(payload, {offset: 0, ai: true, model})
                                        window.kotlinAiCompletion.ai = aiSuggestions
                                        const baseExisting = window.kotlinAiCompletion.base || []
                                        const hasBase = baseExisting.length > 0
                                        window.kotlinAiCompletion.merged = hasBase ? baseExisting : aiSuggestions
                                        if (!hasBase && aiSuggestions.length > 0) {
                                                window.kotlinAiCompletion.useCacheOnce = true
                                                if (isSuggestWidgetVisible()) {
                                                        try {
                                                                const activeEditor = embEntry ? embEntry.editor : globalEditor
                                                                if (activeEditor && activeEditor.trigger) {
                                                                        // Close then re-open to refresh items only if user still has the picker open.
                                                                        activeEditor.trigger("afield-ai", "hideSuggestWidget", {})
                                                                        setTimeout(() => {
                                                                                activeEditor.trigger("afield-ai", "editor.action.triggerSuggest", {})
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

                                        const baseSuggestions = buildSuggestions(payload, {offset: 0, model})
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
                        setStatusForExecutionMarker(strand, true, mode)

                } else {
                        // Ignore stop messages that are older than the latest start we saw
                        if (watermark < lastWatermark) return

                        setStatusForExecutionMarker(strand, false, mode)

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
