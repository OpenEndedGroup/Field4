define("bithero/stacksl", ["require", "vs/editor/editor.api"], function (require, monacoModule) {
    "use strict";

    var monaco = monacoModule && monacoModule.default ? monacoModule.default : monacoModule;
    var languageId = "stacksl";

    monaco.languages.register({
        id: languageId,
        extensions: [".stacksl"],
        aliases: ["StackSL", "stacksl", "stackSL"]
    });

    monaco.languages.setLanguageConfiguration(languageId, {
        comments: {
            lineComment: "#",
            blockComment: ["(", ")"]
        },
        brackets: [
            ["(", ")"]
        ],
        autoClosingPairs: [
            { open: "(", close: ")" }
        ],
        surroundingPairs: [
            { open: "(", close: ")" }
        ]
    });

    var stackWords = [
        "dup", "drop", "swap", "over", "rot", "nip", "tuck",
        "input", "in", "uv", "p", "pos", "center", "centered", "time", "t", "hash", "h",
        "vec2", "v2", "vec3", "v3", "vec4", "v4",
        "0->", "1->", "2->", "3->", "->0", "->1", "->2", "->3",
        "noop"
    ];

    var mathWords = [
        "+", "add", "-", "sub", "*", "mul", "/", "div", "mod", "pow",
        "min", "max", "neg", "negate", "abs", "floor", "ceil", "fract",
        "sqrt", "inversesqrt", "exp", "log", "sin", "cos", "tan",
        "asin", "acos", "atan", "atan2", "normalize",
        "len", "length", "len2", "length2", "len3", "length3", "len4", "length4",
        "average", "avg", "mean", "median", "med", "sort", "intermediate", "vmin", "minimum", "vmax", "maximum",
        "dot", "cross", "mix", "lerp", "step", "smoothstep",
        "lt", "lte", "gt", "gte", "eq", "neq", "and", "or", "not",
        "<", "<=", ">", ">=", "=", "!=", "<>"
    ];

    var creativeWords = [
        "noise", "perlin", "fbm", "pulse", "fm", "fmchirp",
        "palette", "cos-palette", "cosine-palette",
        "luma", "sat", "saturate", "clamp", "mirror", "tonemap",
        "ryb", "ryb2rgb", "from-ryb", "rgb2ryb", "to-ryb",
        "oklab", "oklab2rgb", "from-oklab", "rgb2oklab", "to-oklab",
        "saturation", "sat-color", "contrast", "grade",
        "ema", "ema-diff", "diff-ema", "dema",
        "sample", "sample0", "sample1", "sample2", "sample3",
        "sample-abs", "sample0-abs", "sample1-abs", "sample2-abs", "sample3-abs",
        "prev", "state", "gather", "gather0", "gather1", "gather2", "gather3"
    ];

    var arityVariantArities = Object.create(null);

    function registerArityVariants(word, min, max, includeAll) {
        var arities = [];
        for (var i = min; i <= max; i++) arities.push(i);
        arityVariantArities[word] = arities;
        if (includeAll) arityVariantArities[word + ".all"] = true;
        return arities.map(function (arity) { return word + "." + arity; }).concat(includeAll ? [word + ".all"] : []);
    }

    var arityMathWords = []
        .concat(registerArityVariants("min", 1, 8, true))
        .concat(registerArityVariants("max", 1, 8, true))
        .concat(registerArityVariants("average", 2, 8, true))
        .concat(registerArityVariants("avg", 2, 8, true))
        .concat(registerArityVariants("mean", 2, 8, true))
        .concat(registerArityVariants("median", 2, 8, true))
        .concat(registerArityVariants("med", 2, 8, true))
        .concat(registerArityVariants("sort", 2, 8, true))
        .concat(registerArityVariants("intermediate", 2, 8, true));

    var arityCreativeWords = []
        .concat(registerArityVariants("pulse", 1, 3, false))
        .concat(registerArityVariants("fm", 1, 3, false))
        .concat(registerArityVariants("fmchirp", 1, 9, false))
        .concat(registerArityVariants("palette", 1, 5, false))
        .concat(registerArityVariants("cos-palette", 1, 5, false))
        .concat(registerArityVariants("cosine-palette", 1, 5, false))
        .concat(registerArityVariants("saturation", 1, 2, false))
        .concat(registerArityVariants("sat-color", 1, 2, false))
        .concat(registerArityVariants("contrast", 1, 2, false))
        .concat(registerArityVariants("grade", 1, 4, false))
        .concat(registerArityVariants("ema", 1, 3, false))
        .concat(registerArityVariants("ema-diff", 1, 3, false))
        .concat(registerArityVariants("diff-ema", 1, 3, false))
        .concat(registerArityVariants("dema", 1, 3, false));

    mathWords = mathWords.concat(arityMathWords);
    creativeWords = creativeWords.concat(arityCreativeWords);

    var constants = [
        "pi", "tau", "e",
        "black", "white", "gray", "grey", "red", "green", "blue",
        "cyan", "magenta", "yellow", "orange"
    ];

    var numberSource = "[+\\-]?(?:(?:\\d+\\.\\d*)|(?:\\d*\\.\\d+)|(?:\\d+))(?:[eE][+\\-]?\\d+)?";
    var modifierSource = "pulse|noise|grain|hash|saw|tri|fbm|rad|ang|wx|wy|wr|wa|cx|cy|sq|sh|n|j|c|s|t|r|g|b|a|x|y";
    var numberRegex = new RegExp("^" + numberSource + "$", "i");
    var numberSuffixRegex = new RegExp("^" + numberSource + "[trgba]$", "i");
    var numberRangeRegex = new RegExp("^" + numberSource + "_" + numberSource + "$", "i");
    var numberModifierRegex = new RegExp("^" + numberSource + "(?:" + modifierSource + ")(?:" + numberSource + ")?(?:p" + numberSource + ")?(?:d" + numberSource + ")?$", "i");
    var topModifierRegex = new RegExp("^[\\|\\]](?:" + modifierSource + ")(?:" + numberSource + ")?(?:p" + numberSource + ")?(?:d" + numberSource + ")?$", "i");
    var swizzleRegex = /^\.?[xyzwrgba]{1,4}$/i;
    var numberRangeTokenRegex = new RegExp("(" + numberSource + ")(_)" + "(" + numberSource + ")");
    var topModifierTokenRegex = new RegExp("([\\|\\]])(" + modifierSource + ")(" + numberSource + ")?(?:(p)(" + numberSource + "))?(?:(d)(" + numberSource + "))?(?=$|[\\s()#;])", "i");
    var numberModifierTokenRegex = new RegExp("(" + numberSource + ")(" + modifierSource + ")(" + numberSource + ")?(?:(p)(" + numberSource + "))?(?:(d)(" + numberSource + "))?(?=$|[\\s()#;])", "i");
    var simpleNumberSuffixTokenRegex = new RegExp("(" + numberSource + ")([trgba])\\b", "i");

    function wordSet(words) {
        var out = Object.create(null);
        words.forEach(function (word) { out[String(word).toLowerCase()] = true; });
        return out;
    }

    var pushWordSet = wordSet(stackWords.concat(constants, ["prev", "state"]));
    var unaryWordSet = wordSet([
        "neg", "negate", "abs", "floor", "ceil", "fract", "sqrt", "inversesqrt", "exp", "log",
        "sin", "cos", "tan", "asin", "acos", "atan", "normalize",
        "len", "length", "len2", "length2", "len3", "length3", "len4", "length4",
        "luma", "average", "avg", "mean", "median", "med", "vmin", "minimum", "vmax", "maximum",
        "sat", "saturate", "clamp", "mirror", "tonemap",
        "ryb", "ryb2rgb", "from-ryb", "rgb2ryb", "to-ryb",
        "oklab", "oklab2rgb", "from-oklab", "rgb2oklab", "to-oklab",
        "noise", "perlin", "fbm", "not"
    ]);
    var binaryWordSet = wordSet([
        "+", "add", "-", "sub", "*", "mul", "/", "div", "mod", "pow",
        "atan2", "dot", "cross",
        "lt", "<", "lte", "<=", "gt", ">", "gte", ">=", "eq", "=", "neq", "!=", "<>",
        "and", "or"
    ]);

    function isPushToken(word) {
        return !!pushWordSet[word] ||
            numberRegex.test(word) ||
            numberSuffixRegex.test(word) ||
            numberRangeRegex.test(word) ||
            numberModifierRegex.test(word);
    }

    function effect(consume, produce) {
        return { consume: consume, produce: produce };
    }

    function dynamicEffect(depth, fullArity) {
        return depth < fullArity ? effect(1, 1) : effect(fullArity, 1);
    }

    function arityVariant(word) {
        var match = /^(.+)\.(\d+|all)$/.exec(word);
        if (!match) return null;
        var base = match[1];
        if (match[2] === "all") {
            if (!arityVariantArities[base + ".all"]) return null;
            return { base: base, arity: null, all: true };
        }
        var arity = parseInt(match[2], 10);
        var arities = arityVariantArities[base];
        if (!arities || arities.indexOf(arity) < 0) return null;
        return { base: base, arity: arity, all: false };
    }

    function blastCount(depth, requestedArity, requestedAll) {
        return requestedAll ? Math.max(2, depth) : requestedArity;
    }

    function stackEffectForWord(rawWord, depth, definitionWords) {
        var raw = String(rawWord || "").toLowerCase();
        var variant = arityVariant(raw);
        var word = variant ? variant.base : raw;
        var requestedArity = variant ? variant.arity : null;
        var requestedAll = variant ? variant.all : false;
        if (!word || word === ":" || word === ";" || word === "|" || word === "]") return effect(0, 0);
        if (topModifierRegex.test(word)) return effect(1, 1);
        if (isPushToken(word)) return effect(0, 1);
        if (swizzleRegex.test(word)) return effect(1, 1);

        if (word === "noop") return effect(0, 0);
        if (word === "dup") return effect(1, 2);
        if (word === "drop") return effect(1, 0);
        if (word === "swap") return effect(2, 2);
        if (word === "over") return effect(2, 3);
        if (word === "rot") return effect(3, 3);
        if (word === "nip") return effect(2, 1);
        if (word === "tuck") return effect(2, 3);
        if (word === "vec2" || word === "v2") return effect(2, 1);
        if (word === "vec3" || word === "v3") return effect(3, 1);
        if (word === "vec4" || word === "v4") return effect(4, 1);
        if (word === "->0" || word === "->1" || word === "->2" || word === "->3") return effect(1, 0);
        if (word === "average" || word === "avg" || word === "mean" || word === "median" || word === "med") {
            if (variant) return effect(blastCount(depth, requestedArity, requestedAll), 1);
        }
        if (word === "sort") {
            var sortCount = variant ? blastCount(depth, requestedArity, requestedAll) : Math.max(2, depth);
            return effect(sortCount, sortCount);
        }
        if (word === "intermediate") {
            var intermediateCount = variant ? blastCount(depth, requestedArity, requestedAll) : Math.max(2, depth);
            return effect(intermediateCount, intermediateCount - 1);
        }
        if (unaryWordSet[word]) return effect(1, 1);
        if (binaryWordSet[word]) return effect(2, 1);
        if (word === "min" || word === "max") {
            if (requestedAll || (requestedArity || 0) >= 3) return effect(blastCount(depth, requestedArity, requestedAll), 1);
            return effect(requestedArity || (depth <= 2 ? 1 : 2), 1);
        }
        if (word === "mix" || word === "lerp") return effect(3, 1);
        if (word === "step") return effect(2, 1);
        if (word === "smoothstep") return effect(3, 1);
        if (word === "pulse" || word === "fm" || word === "ema" || word === "ema-diff" || word === "diff-ema" || word === "dema") return requestedArity ? effect(requestedArity, 1) : dynamicEffect(depth, 3);
        if (word === "fmchirp") return requestedArity ? effect(requestedArity, 1) : dynamicEffect(depth, 9);
        if (word === "palette" || word === "cos-palette" || word === "cosine-palette") return requestedArity ? effect(requestedArity, 1) : dynamicEffect(depth, 5);
        if (word === "saturation" || word === "sat-color" || word === "contrast") return requestedArity ? effect(requestedArity, 1) : dynamicEffect(depth, 2);
        if (word === "grade") return requestedArity ? effect(requestedArity, 1) : dynamicEffect(depth, 4);
        if (word === "sample" || word === "sample0" || word === "sample1" || word === "sample2" || word === "sample3" ||
            word === "sample-abs" || word === "sample0-abs" || word === "sample1-abs" || word === "sample2-abs" || word === "sample3-abs" ||
            word === "gather" || word === "gather0" || word === "gather1" || word === "gather2" || word === "gather3") return effect(1, 1);
        if (definitionWords && definitionWords[word]) return effect(1, 1);
        return effect(0, 0);
    }

    function applyEffect(depth, stackEffect) {
        return Math.max(0, depth - stackEffect.consume) + stackEffect.produce;
    }

    function tokenizeForFormat(source) {
        var tokens = [];
        var i = 0;
        var pendingNewlines = 0;

        function flushBlankIfNeeded() {
            if (pendingNewlines > 1) {
                tokens.push({ type: "blank" });
            }
            pendingNewlines = 0;
        }

        while (i < source.length) {
            var ch = source[i];
            if (ch === "\r") {
                i++;
                continue;
            }
            if (ch === "\n") {
                pendingNewlines++;
                i++;
                continue;
            }
            if (/\s/.test(ch)) {
                i++;
                continue;
            }
            flushBlankIfNeeded();

            if (ch === "#") {
                var lineStart = i;
                while (i < source.length && source[i] !== "\n") i++;
                tokens.push({ type: "comment", text: source.slice(lineStart, i).trim() });
                continue;
            }
            if (ch === "/" && source[i + 1] === "/") {
                var slashStart = i;
                while (i < source.length && source[i] !== "\n") i++;
                tokens.push({ type: "comment", text: source.slice(slashStart, i).trim() });
                continue;
            }
            if (ch === "(") {
                var commentStart = i;
                while (i < source.length && source[i] !== ")") i++;
                if (i < source.length) i++;
                tokens.push({ type: "comment", text: source.slice(commentStart, i).trim() });
                continue;
            }

            var wordStart = i;
            while (i < source.length) {
                var c = source[i];
                if (/\s/.test(c) || c === "#" || c === "(") break;
                if (c === "/" && source[i + 1] === "/") break;
                i++;
            }
            if (i > wordStart) {
                tokens.push({ type: "word", text: source.slice(wordStart, i) });
            } else {
                i++;
            }
        }
        return tokens;
    }

    function collectDefinitionWords(tokens) {
        var defs = Object.create(null);
        for (var i = 0; i < tokens.length; i++) {
            if (tokens[i].type === "word" && tokens[i].text === ":") {
                var next = tokens[i + 1];
                if (next && next.type === "word") {
                    defs[next.text.toLowerCase()] = true;
                    i++;
                }
            }
        }
        return defs;
    }

    function formatStackSL(source, options) {
        var tokens = tokenizeForFormat(source || "");
        var definitionWords = collectDefinitionWords(tokens);
        var tabSize = Math.max(1, Math.min(8, (options && options.tabSize) || 4));
        var unit = options && options.insertSpaces === false ? "\t" : new Array(tabSize + 1).join(" ");
        var lines = [];
        var pendingPushes = [];
        var depth = 1;
        var inDefinition = false;
        var expectDefinitionName = false;
        var lastBlank = true;

        function indentForLevel(level) {
            return new Array(level + 1).join(unit);
        }

        function pushFinalLine(text, level) {
            lines.push(indentForLevel(level) + text);
            lastBlank = false;
        }

        function pushFinalBlank() {
            if (!lastBlank && lines.length > 0) {
                lines.push("");
                lastBlank = true;
            }
        }

        function bodyLevel() {
            return inDefinition ? 1 : 0;
        }

        function flushPendingPushes(extraWord) {
            if (extraWord) pendingPushes.push(extraWord);
            if (pendingPushes.length === 0) return;
            pushFinalLine(pendingPushes.join(" "), bodyLevel());
            pendingPushes = [];
        }

        for (var i = 0; i < tokens.length; i++) {
            var token = tokens[i];
            if (token.type === "blank") {
                flushPendingPushes();
                pushFinalBlank();
                continue;
            }
            if (token.type === "comment") {
                flushPendingPushes();
                pushFinalLine(token.text, bodyLevel());
                continue;
            }

            var text = token.text;
            if (text === ":") {
                var name = tokens[i + 1] && tokens[i + 1].type === "word" ? tokens[i + 1].text : "";
                flushPendingPushes();
                pushFinalLine(name ? ": " + name : ":", 0);
                inDefinition = true;
                expectDefinitionName = !name;
                depth = 1;
                if (name) i++;
                continue;
            }
            if (expectDefinitionName) {
                var nameLine = ": " + text;
                if (lines.length > 0 && lines[lines.length - 1] === ":") {
                    lines[lines.length - 1] = nameLine;
                } else {
                    pushFinalLine(nameLine, 0);
                }
                expectDefinitionName = false;
                continue;
            }
            if (text === ";") {
                flushPendingPushes();
                pushFinalLine(";", 0);
                inDefinition = false;
                depth = 1;
                pushFinalBlank();
                continue;
            }

            var stackEffect = stackEffectForWord(text, depth, definitionWords);
            if (stackEffect.consume === 0 && stackEffect.produce > 0) {
                pendingPushes.push(text);
            } else if (stackEffect.consume > 0 && pendingPushes.length > 0) {
                flushPendingPushes(text);
            } else {
                flushPendingPushes();
                pushFinalLine(text, bodyLevel());
            }
            depth = applyEffect(depth, stackEffect);
        }

        flushPendingPushes();
        while (lines.length > 0 && lines[lines.length - 1] === "") lines.pop();
        var formatted = lines.join("\n");
        if (source && /\n\s*$/.test(source)) formatted += "\n";
        return formatted;
    }

    function escapeStackSLHtml(value) {
        return String(value == null ? "" : value)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/\"/g, "&quot;")
            .replace(/'/g, "&#39;");
    }

    var stackSLFlowIdCounter = 0;
    function newStackSLFlowId() {
        stackSLFlowIdCounter += 1;
        return "stacksl-flow-" + Date.now().toString(36) + "-" + stackSLFlowIdCounter.toString(36);
    }

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
            ["sort", stackslDoc("values -- values", "Component-wise ascending stack sort; use .2-.8 or .all.")],
            ["intermediate", stackslDoc("values -- midpoints", "Adjacent midpoints; use .2-.8 or .all.")],
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
            const raw = String(rawWord || "").toLowerCase()
            const variant = arityVariant(raw)
            const word = variant ? variant.base : raw
            const canonical = stackSLCanonicalWord(word)

            if (STACKSL_NUMBER_RANGE_RE.test(raw)) {
                    return stackslDoc("-- vec", "Push a vec4 range spread across four scalar values.")
            }
            if (STACKSL_NUMBER_SUFFIX_RE.test(raw)) {
                    return stackslDoc("-- value", "Push a time-scaled or channel-weighted number.")
            }
            if (STACKSL_NUMBER_MODIFIER_RE.test(raw)) {
                    return stackslDoc("-- value", "Push amount times a time/spatial/noise modifier.")
            }
            if (STACKSL_NUMBER_RE.test(raw)) {
                    return stackslDoc("-- value", "Push the number splatted to vec4.")
            }
            if (STACKSL_COLOR_WORDS.has(raw)) {
                    return stackslDoc("-- color", "Push a named RGBA color.")
            }
            if (raw === "|" || raw === "]") {
                    return stackslDoc("--", "Modifier prefix; combine it with a suffix such as |c2 or ]fbm4.")
            }
            if (STACKSL_TOP_MODIFIER_RE.test(raw)) {
                    return raw.startsWith("]")
                            ? stackslDoc("driver -- out", "Use the consumed value as modifier driver; amplitude is one.")
                            : stackslDoc("amount -- out", "Multiply consumed amount by a time/spatial/noise modifier.")
            }
            if (STACKSL_SWIZZLE_RE.test(raw)) {
                    return stackslDoc("vec -- vec", "Swizzle channels and return a vec4-shaped value.")
            }

            if (word === "min" || word === "max") {
                    if (variant && (variant.all || effect.consume >= 3)) {
                            return stackslDoc(`${effect.consume} values -- value`, `${word} across a stack blast, component-wise.`)
                    }
                    return effect.consume === 1
                            ? stackslDoc("vec -- scalar", `${word} of RGBA channels, splatted.`)
                            : stackslDoc("a b -- out", `${word} component-wise.`)
            }
            if (canonical === "avg" && variant) {
                    return stackslDoc(`${effect.consume} values -- mean`, "Mean across a stack blast, component-wise.")
            }
            if (canonical === "median" && variant) {
                    return stackslDoc(`${effect.consume} values -- median`, "Median across a stack blast, component-wise.")
            }
            if (word === "sort") {
                    return stackslDoc(`${effect.consume} values -- ${effect.produce} values`, "Component-wise ascending stack sort.")
            }
            if (word === "intermediate") {
                    return stackslDoc(`${effect.consume} values -- ${effect.produce} midpoints`, "Adjacent midpoints between consumed stack values.")
            }
            if (canonical === "pulse") {
                    const signatures = {
                            1: "x -- mask",
                            2: "x center -- mask",
                            3: "x center width -- mask",
                    }
                    return stackslDoc(signatures[effect.consume] || "x center width -- mask", effect.consume < 3 ? "Soft band with trailing defaults." : "Soft band around center with the given width.")
            }
            if (canonical === "fm") {
                    const signatures = {
                            1: "phase -- out",
                            2: "phase carrier -- out",
                            3: "phase carrier mod -- out",
                    }
                    return stackslDoc(signatures[effect.consume] || "phase carrier mod -- out", effect.consume < 3 ? "Frequency modulation with trailing defaults." : "Frequency-modulated sine field.")
            }
            if (canonical === "fmchirp" && variant && variant.arity === 8) {
                    return stackslDoc("freq mod modPhase phase chirp modChirp inputPow outputPow -- out", "FMChirp residual curve using input.x as x.")
            }
            if (canonical === "fmchirp") {
                    const signatures = {
                            1: "x -- out",
                            2: "x freq -- out",
                            3: "x freq mod -- out",
                            4: "x freq mod modPhase -- out",
                            5: "x freq mod modPhase phase -- out",
                            6: "x freq mod modPhase phase chirp -- out",
                            7: "x freq mod modPhase phase chirp modChirp -- out",
                            9: "x freq mod modPhase phase chirp modChirp inputPow outputPow -- out",
                    }
                    return stackslDoc(signatures[effect.consume] || signatures[9], effect.consume < 9 ? "Component-wise FMChirp residual curve with trailing defaults." : "Component-wise FMChirp residual curve.")
            }
            if (canonical === "palette") {
                    const signatures = {
                            1: "t -- color",
                            2: "t a -- color",
                            3: "t a b -- color",
                            4: "t a b c -- color",
                            5: "t a b c d -- color",
                    }
                    return stackslDoc(signatures[effect.consume] || signatures[5], effect.consume < 5 ? "Cosine palette with trailing default bands." : "Cosine palette a + b*cos(t*c + d).")
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
                    const signatures = {
                            1: "color -- color",
                            2: "color lift -- color",
                            3: "color lift gain -- color",
                            4: "color lift gain gamma -- color",
                    }
                    return stackslDoc(signatures[effect.consume] || signatures[4], effect.consume < 4 ? "Color grade with trailing defaults." : "Color grade with lift, gain, and gamma.")
            }
            if (canonical === "ema") {
                    const signatures = {
                            1: "current -- smooth",
                            2: "current previous -- smooth",
                            3: "current previous alpha -- smooth",
                    }
                    return stackslDoc(signatures[effect.consume] || signatures[3], effect.consume < 3 ? "EMA with trailing defaults." : "Exponential moving average.")
            }
            if (canonical === "ema-diff") {
                    const signatures = {
                            1: "current -- diff",
                            2: "current previous -- diff",
                            3: "current previous alpha -- diff",
                    }
                    return stackslDoc(signatures[effect.consume] || signatures[3], effect.consume < 3 ? "Current minus EMA with trailing defaults." : "Current minus exponential moving average.")
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
            const raw = String(rawWord || "").toLowerCase()
            const variant = arityVariant(raw)
            const word = variant ? variant.base : raw
            const requestedArity = variant ? variant.arity : null
            const requestedAll = variant ? variant.all : false
            if (!word) return stackslEffect(0, 0)
            if (word === ":" || word === ";") return stackslEffect(0, 0, "definition marker")
            if (word === "|" || word === "]") return stackslEffect(0, 0, "modifier prefix")
            if (STACKSL_TOP_MODIFIER_RE.test(raw)) return stackslEffect(1, 1, raw.startsWith("]") ? "driven modifier" : "top modifier")
            if (isStackSLPushToken(raw)) return stackslEffect(0, 1, "push")
            if (STACKSL_SWIZZLE_RE.test(raw)) return stackslEffect(1, 1, "swizzle")

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

            if (word === "average" || word === "avg" || word === "mean" || word === "median" || word === "med") {
                    if (variant) return stackslEffect(blastCount(depth, requestedArity, requestedAll), 1, "stack blast")
            }
            if (word === "sort") {
                    const count = variant ? blastCount(depth, requestedArity, requestedAll) : Math.max(2, depth)
                    return stackslEffect(count, count, "stack blast")
            }
            if (word === "intermediate") {
                    const count = variant ? blastCount(depth, requestedArity, requestedAll) : Math.max(2, depth)
                    return stackslEffect(count, count - 1, "stack blast")
            }
            if (STACKSL_UNARY_WORDS.has(word)) return stackslEffect(1, 1)
            if (STACKSL_BINARY_WORDS.has(word)) return stackslEffect(2, 1)

            if (word === "min" || word === "max") {
                    if (requestedAll || (requestedArity || 0) >= 3) return stackslEffect(blastCount(depth, requestedArity, requestedAll), 1, "stack blast")
                    return requestedArity
                            ? stackslEffect(requestedArity, 1, requestedArity === 1 ? "reduce top" : "")
                            : depth <= 2 ? stackslEffect(1, 1, "reduce top") : stackslEffect(2, 1)
            }
            if (word === "mix" || word === "lerp") return stackslEffect(3, 1)
            if (word === "step") return stackslEffect(2, 1)
            if (word === "smoothstep") return stackslEffect(3, 1)
            if (word === "pulse" || word === "fm" || word === "ema" || word === "ema-diff" || word === "diff-ema" || word === "dema") {
                    return requestedArity ? stackslEffect(requestedArity, 1, requestedArity < 3 ? "trailing defaults" : "") : dynamicStackSLEffect(depth, 3, "short form")
            }
            if (word === "fmchirp") return requestedArity
                    ? stackslEffect(requestedArity, 1, requestedArity === 8 ? "implicit x" : requestedArity < 9 ? "trailing defaults" : "")
                    : dynamicStackSLEffect(depth, 9, "short form")
            if (word === "palette" || word === "cos-palette" || word === "cosine-palette") {
                    return requestedArity ? stackslEffect(requestedArity, 1, requestedArity < 5 ? "trailing defaults" : "") : dynamicStackSLEffect(depth, 5, "short form")
            }
            if (word === "saturation" || word === "sat-color" || word === "contrast") {
                    return requestedArity ? stackslEffect(requestedArity, 1, requestedArity < 2 ? "trailing defaults" : "") : dynamicStackSLEffect(depth, 2, "short form")
            }
            if (word === "grade") {
                    return requestedArity ? stackslEffect(requestedArity, 1, requestedArity < 4 ? "trailing defaults" : "") : dynamicStackSLEffect(depth, 4, "short form")
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

    const newStackSLFlowValue = (producer, source = null) => ({
            id: `stacksl-flow-${newStackSLFlowId()}`,
            producer,
            source,
    })

    const readStackSLFlowInputs = (stack, count) => {
            const missing = Math.max(0, count - stack.length)
            const available = stack.slice(Math.max(0, stack.length - count))
            const synthetic = []
            for (let i = 0; i < missing; i++) {
                    synthetic.push(newStackSLFlowValue(null))
            }
            return synthetic.concat(available)
    }

    const applyStackSLFlowToken = (stack, token, definitionWords) => {
            const effect = stackSLEffectForWord(token.text, stack.length, definitionWords)
            const consume = Math.max(0, effect.consume || 0)
            const produce = Math.max(0, effect.produce || 0)
            const consumed = readStackSLFlowInputs(stack, consume)
            const rest = consume > 0 ? stack.slice(0, Math.max(0, stack.length - consume)) : stack.slice()
            const lower = token.lower || String(token.text || "").toLowerCase()
            let next = rest
            let produced = []

            const make = (source = null) => newStackSLFlowValue(token, source)

            if (lower === "dup") {
                    const a = consumed[consumed.length - 1] || make()
                    produced = [make(a)]
                    next = rest.concat([a], produced)
            } else if (lower === "drop") {
                    next = rest
            } else if (lower === "swap") {
                    const a = consumed[0]
                    const b = consumed[1]
                    produced = [b, a].filter(Boolean)
                    next = rest.concat(produced)
            } else if (lower === "over") {
                    const a = consumed[0]
                    const b = consumed[1]
                    const copy = make(a)
                    produced = [copy]
                    next = rest.concat([a, b, copy].filter(Boolean))
            } else if (lower === "rot") {
                    const a = consumed[0]
                    const b = consumed[1]
                    const c = consumed[2]
                    produced = [b, c, a].filter(Boolean)
                    next = rest.concat(produced)
            } else if (lower === "nip") {
                    const b = consumed[1]
                    produced = b ? [b] : []
                    next = rest.concat(produced)
            } else if (lower === "tuck") {
                    const a = consumed[0]
                    const b = consumed[1]
                    const copy = make(b)
                    produced = [copy]
                    next = rest.concat([b, a, copy].filter(Boolean))
            } else {
                    produced = []
                    for (let i = 0; i < produce; i++) {
                            produced.push(make(consumed.length === 1 ? consumed[0] : null))
                    }
                    next = rest.concat(produced)
            }

            return { effect, consumed, produced, stack: next }
    }

    const analyzeStackSLFlowAtToken = (tokens, activeToken, initialDepth, definitionWords) => {
            let stack = []
            for (let i = 0; i < initialDepth; i++) {
                    stack.push(newStackSLFlowValue(null))
            }

            let active = null
            let seenActive = false
            const producedIds = new Set()
            const futureConsumers = []

            for (const token of tokens) {
                    const step = applyStackSLFlowToken(stack, token, definitionWords)
                    stack = step.stack
                    if (token === activeToken) {
                            active = step
                            step.produced.forEach((value) => producedIds.add(value.id))
                            seenActive = true
                            continue
                    }
                    if (seenActive && producedIds.size > 0) {
                            const consumesProduced = step.consumed.some((value) => producedIds.has(value.id))
                            if (consumesProduced) futureConsumers.push(token)
                    }
            }

            if (!active) return null
            return {
                    consumedSources: active.consumed
                            .map((value) => value && value.producer)
                            .filter(Boolean),
                    futureConsumers,
                    activeProduced: active.produced,
            }
    }

    const stackSLTokenRange = (monacoApi, token) => {
            return new monacoApi.Range(
                    token.line,
                    token.column,
                    token.line,
                    token.column + Math.max(1, String(token.text || "").length)
            )
    }

    const ensureStackSLFlowLens = (editor) => {
            if (!editor) return null
            if (!editor._afieldStackSLFlowLens) {
                    editor._afieldStackSLFlowLens = {
                            enabled: false,
                            decorations: typeof editor.createDecorationsCollection === "function"
                                    ? editor.createDecorationsCollection([])
                                    : null,
                            legacyIds: [],
                    }
            }
            return editor._afieldStackSLFlowLens
    }

    const setStackSLFlowLensDecorations = (editor, decorations) => {
            const state = ensureStackSLFlowLens(editor)
            if (!state) return
            if (state.decorations && typeof state.decorations.set === "function") {
                    state.decorations.set(decorations)
            } else if (typeof editor.deltaDecorations === "function") {
                    state.legacyIds = editor.deltaDecorations(state.legacyIds || [], decorations)
            }
    }

    const clearStackSLFlowLens = (editor) => {
            setStackSLFlowLensDecorations(editor, [])
    }

    const updateStackSLFlowLens = (editor) => {
            const state = ensureStackSLFlowLens(editor)
            if (!state || !state.enabled) return
            const monacoApi = window.globalMonaco || window.monaco || monaco
            const model = editor && editor.getModel && editor.getModel()
            if (!monacoApi || !model || model.getLanguageId() !== "stacksl") {
                    clearStackSLFlowLens(editor)
                    return
            }
            const position = editor.getPosition && editor.getPosition()
            if (!position) {
                    clearStackSLFlowLens(editor)
                    return
            }

            const source = model.getValue()
            const offset = model.getOffsetAt(position)
            const tokens = tokenizeStackSLForContext(source)
            const parsed = parseStackSLDefinitionsForContext(tokens)
            const context = findStackSLDefinitionContext(parsed, offset)
            const activeToken = findStackSLTokenAtOffset(tokens, offset)
            const activeInContext = activeToken && context.tokens.includes(activeToken) ? activeToken : null
            if (!activeInContext) {
                    clearStackSLFlowLens(editor)
                    return
            }

            const flow = analyzeStackSLFlowAtToken(context.tokens, activeInContext, context.initialDepth, parsed.definitionWords)
            if (!flow) {
                    clearStackSLFlowLens(editor)
                    return
            }

            const sourceTokens = new Set(flow.consumedSources)
            const consumerTokens = new Set(flow.futureConsumers)
            consumerTokens.delete(activeInContext)
            sourceTokens.delete(activeInContext)

            const decorations = []
            for (const token of sourceTokens) {
                    decorations.push({
                            range: stackSLTokenRange(monacoApi, token),
                            options: {
                                    inlineClassName: "afield-stacksl-flow-source",
                                    hoverMessage: { value: "StackSL value source" },
                            },
                    })
            }
            for (const token of consumerTokens) {
                    decorations.push({
                            range: stackSLTokenRange(monacoApi, token),
                            options: {
                                    inlineClassName: "afield-stacksl-flow-consumer",
                                    hoverMessage: { value: "StackSL future consumer" },
                            },
                    })
            }
            setStackSLFlowLensDecorations(editor, decorations)
    }

    const setStackSLFlowLensEnabled = (editor, enabled) => {
            const state = ensureStackSLFlowLens(editor)
            if (!state) return
            state.enabled = !!enabled
            if (state.enabled) updateStackSLFlowLens(editor)
            else clearStackSLFlowLens(editor)
    }

    const installStackSLFlowLens = (editor, monacoApi) => {
            if (!editor || editor._afieldStackSLFlowLensInstalled) return
            editor._afieldStackSLFlowLensInstalled = true
            ensureStackSLFlowLens(editor)

            const action = {
                    id: "toggle-stacksl-flow-lens",
                    label: "Toggle StackSL Flow Lens",
                    contextMenuGroupId: "navigation",
                    contextMenuOrder: 3.5,
                    run: (editorNow) => {
                            const activeEditor = editorNow || editor
                            const state = ensureStackSLFlowLens(activeEditor)
                            setStackSLFlowLensEnabled(activeEditor, !(state && state.enabled))
                    },
            }
            if (typeof editor.addAction === "function") editor.addAction(action)

            const schedule = () => setTimeout(() => updateStackSLFlowLens(editor), 0)
            editor.onDidChangeCursorPosition(schedule)
            editor.onDidChangeModelContent(schedule)
            if (typeof editor.onDidChangeModel === "function") editor.onDidChangeModel(schedule)
            if (typeof editor.onDidFocusEditorText === "function") editor.onDidFocusEditorText(schedule)
    }

    window.installStackSLFlowLens = installStackSLFlowLens
    window.updateStackSLFlowLens = updateStackSLFlowLens

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
                                    ? `<span class="afield-stacksl-signature">${escapeStackSLHtml(doc.signature)}</span>`
                                    : `<span class="afield-stacksl-signature">${escapeStackSLHtml(formatStackSLEffect(effect))}</span>`
                            const arityHtml = doc && doc.signature
                                    ? `<span class="afield-stacksl-muted">(${escapeStackSLHtml(formatStackSLEffect(effect))})</span>`
                                    : ""
                            const detailHtml = doc && doc.detail
                                    ? `<span class="afield-stacksl-muted">${escapeStackSLHtml(doc.detail)}</span>`
                                    : ""
                            const missingHtml = applied.missing > 0
                                    ? `<span class="afield-stacksl-warning-text">needs ${effect.consume}, has ${before.depth}</span>`
                                    : ""
                            wordHtml = `<span class="afield-stacksl-word"><code>${escapeStackSLHtml(activeInContext.text)}</code> ${signatureHtml} ${arityHtml}; after ${escapeStackSLHtml(afterDepth)}${missingHtml ? " · " + missingHtml : ""}${detailHtml ? " · " + detailHtml : ""}</span>`
                    } else if (context.kind === "definition" && activeToken === context.definition.name) {
                            wordHtml = `<span class="afield-stacksl-word"><code>${escapeStackSLHtml(activeToken.text)}</code> definition name</span>`
                    } else if (activeToken.text === ":" || activeToken.text === ";") {
                            wordHtml = `<span class="afield-stacksl-word"><code>${escapeStackSLHtml(activeToken.text)}</code> definition marker</span>`
                    }
            }

            const scopeName = context.kind === "definition" && context.definition && context.definition.name
                    ? `:${context.definition.name.text}`
                    : "root"
            const depthHtml = context.kind === "definition"
                    ? `relative stack <strong>${escapeStackSLHtml(formatStackSLRelativeDepth(before.depth, context.initialDepth))}</strong>`
                    : `stack depth <strong>${before.depth}</strong>`
            const underflowCount = after.underflows
            const underflowHtml = underflowCount > 0
                    ? `<span class="afield-stacksl-warning-text">${underflowCount} default/wrap fill${underflowCount === 1 ? "" : "s"}</span>`
                    : ""
            const warningClass = underflowCount > 0 ? " afield-stacksl-warning" : ""
            const html = `<div class="afield-stacksl-context${warningClass}"><strong>StackSL</strong> <span class="afield-stacksl-scope">${escapeStackSLHtml(scopeName)}</span> ${depthHtml}${wordHtml ? " · " + wordHtml : ""}${underflowHtml ? " · " + underflowHtml : ""}</div>`

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

    window.buildStackSLContextPayload = buildStackSLContextPayload

    monaco.languages.setMonarchTokensProvider(languageId, {
        tokenPostfix: ".stacksl",
        ignoreCase: true,
        stackWords: stackWords,
        mathWords: mathWords,
        creativeWords: creativeWords,
        constants: constants,
        tokenizer: {
            root: [
                [/#.*$/, "comment"],
                [/\/\/.*$/, "comment"],
                [/\(/, "comment", "@parenComment"],

                [/:/, "keyword.control", "@definition"],
                [/;/, "keyword.control"],

                [/(?:0->|1->|2->|3->|->0|->1|->2|->3)/, "keyword"],
                [numberRangeTokenRegex, ["number", "operator.stacksl.range", "number"]],
                [topModifierTokenRegex, ["operator.stacksl.prefix", "number.stacksl.modifier", "number", "number.stacksl.option", "number", "number.stacksl.option", "number"]],
                [numberModifierTokenRegex, ["number", "number.stacksl.modifier", "number", "number.stacksl.option", "number", "number.stacksl.option", "number"]],
                [simpleNumberSuffixTokenRegex, ["number", "number.stacksl.suffix"]],
                [/[+\-]?(?:\d+\.\d*|\d*\.\d+|\d+)(?:[eE][+\-]?\d+)?/, "number"],
                [/[\|\]]/, "operator"],
                [/\.[xyzwrgba]{1,4}\b/, "variable.predefined"],
                [/\b[xyzwrgba]{1,4}\b/, "variable.predefined"],

                [/[^\s()#;]+/, {
                    cases: {
                        "@stackWords": "keyword",
                        "@mathWords": "operator",
                        "@creativeWords": "type.identifier",
                        "@constants": "number.hex",
                        "@default": "identifier"
                    }
                }]
            ],

            definition: [
                [/\s+/, "white"],
                [/[A-Za-z_][A-Za-z0-9_-]*/, "entity.name.function", "@pop"],
                [/./, "invalid", "@pop"]
            ],

            parenComment: [
                [/[^\)]+/, "comment"],
                [/\)/, "comment", "@pop"]
            ]
        }
    });

    monaco.languages.registerDocumentFormattingEditProvider(languageId, {
        provideDocumentFormattingEdits: function (model, options) {
            var formatted = formatStackSL(model.getValue(), options || {});
            return [{
                range: model.getFullModelRange(),
                text: formatted
            }];
        }
    });
});
