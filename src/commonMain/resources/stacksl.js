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
        "+", "add", "-", "sub", "*", "mul", "/", "div", "mod", "pow", "pow.safe",
        "min", "max", "neg", "negate", "1-", "abs", "floor", "ceil", "fract",
        "sqrt", "inversesqrt", "exp", "log", "sin", "cos", "tan",
        "asin", "acos", "atan", "atan2", "normalize",
        "len", "length", "len2", "length2", "len3", "length3", "len4", "length4",
        "sum", "prod", "product", "any", "all", "x+y", "xplusy", "xylen", "rgbnorm", "rgb-normalize",
        "average", "avg", "mean", "median", "med", "sort", "intermediate", "vmin", "minimum", "vmax", "maximum",
        "dot", "distance2", "distance3", "distance", "cross", "standoff", "standoff2", "standoff3", "rotate", "rotates", ">x", ">y", ">z", ">w",
        "tile", "repeat", "polar", "cart", "cartesian", "twist", "spiral", "kaleido", "kaleidoscope",
        "mix", "lerp", "mix.c", "lerp.c", "mix.sc", "lerp.sc", "select", "if", "step", "smoothstep",
        "lt", "lte", "gt", "gte", "eq", "neq", "and", "or", "not",
        "xor", "bitxor", "gray-code", "graycode", "gray-encode", "gray-decode", "ungray", "lfsr",
        "<", "<=", ">", ">=", "=", "!=", "<>"
    ];

    var creativeWords = [
        "noise", "perlin", "fbm", "pulse", "fm", "fmchirp",
        "palette", "cos-palette", "cosine-palette",
        "luma", "sat", "saturate", "clamp", "mirror", "smooth", "sigmoid", "softplus", "threshold", "band", "ramp", "posterize", "bias", "gain", "tonemap",
        "ryb", "ryb2rgb", "from-ryb", "rgb2ryb", "to-ryb",
        "ryb.c", "ryb2rgb.c", "from-ryb.c", "rgb2ryb.c", "to-ryb.c",
        "oklab", "oklab2rgb", "from-oklab", "rgb2oklab", "to-oklab",
        "saturation", "sat-color", "contrast", "grade",
        "alpha", "alpha.c", "alpha.sc",
        "ema", "ema.c", "ema.sc", "ema-diff", "ema-diff.c", "ema-diff.sc", "diff-ema", "diff-ema.c", "diff-ema.sc", "dema", "dema.c", "dema.sc",
        "sample", "sample0", "sample1", "sample2", "sample3",
        "sample.w", "sample0.w", "sample1.w", "sample2.w", "sample3.w",
        "sample-abs", "sample0-abs", "sample1-abs", "sample2-abs", "sample3-abs",
        "sample-abs.w", "sample0-abs.w", "sample1-abs.w", "sample2-abs.w", "sample3-abs.w",
        "prev", "state", "gather", "gather0", "gather1", "gather2", "gather3",
        "gather.w", "gather0.w", "gather1.w", "gather2.w", "gather3.w",
        "crosssample", "cross-sample",
        "crosssample.r", "crosssample.g", "crosssample.b", "crosssample.a",
        "cross-sample.r", "cross-sample.g", "cross-sample.b", "cross-sample.a",
        "rcrosssample", "r-cross-sample",
        "rcrosssample.r", "rcrosssample.g", "rcrosssample.b", "rcrosssample.a",
        "r-cross-sample.r", "r-cross-sample.g", "r-cross-sample.b", "r-cross-sample.a",
        "laplace", "laplacian", "blur", "blur5", "blur9", "sobel", "gradient", "divergence", "curl",
        "bitcrush", "bit-crush"
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
        .concat(registerArityVariants("sigmoid", 1, 2, false))
        .concat(registerArityVariants("softplus", 1, 2, false))
        .concat(registerArityVariants("threshold", 1, 2, false))
        .concat(registerArityVariants("posterize", 1, 2, false))
        .concat(registerArityVariants("clamp", 1, 3, false))
        .concat(registerArityVariants("bias", 1, 2, false))
        .concat(registerArityVariants("gain", 1, 2, false))
        .concat(registerArityVariants("band", 1, 3, false))
        .concat(registerArityVariants("ramp", 1, 3, false))
        .concat(registerArityVariants("tile", 1, 2, false))
        .concat(registerArityVariants("twist", 1, 2, false))
        .concat(registerArityVariants("spiral", 1, 2, false))
        .concat(registerArityVariants("kaleido", 1, 2, false))
        .concat(registerArityVariants("kaleidoscope", 1, 2, false))
        .concat(registerArityVariants("laplace", 1, 2, false))
        .concat(registerArityVariants("laplacian", 1, 2, false))
        .concat(registerArityVariants("blur", 1, 2, false))
        .concat(registerArityVariants("blur5", 1, 2, false))
        .concat(registerArityVariants("blur9", 1, 2, false))
        .concat(registerArityVariants("sobel", 1, 2, false))
        .concat(registerArityVariants("gradient", 1, 2, false))
        .concat(registerArityVariants("divergence", 1, 2, false))
        .concat(registerArityVariants("curl", 1, 2, false))
        .concat(registerArityVariants("grade", 1, 4, false))
        .concat(registerArityVariants("ema", 1, 3, false))
        .concat(registerArityVariants("ema.c", 1, 3, false))
        .concat(registerArityVariants("ema.sc", 1, 3, false))
        .concat(registerArityVariants("ema-diff", 1, 3, false))
        .concat(registerArityVariants("ema-diff.c", 1, 3, false))
        .concat(registerArityVariants("ema-diff.sc", 1, 3, false))
        .concat(registerArityVariants("diff-ema", 1, 3, false))
        .concat(registerArityVariants("diff-ema.c", 1, 3, false))
        .concat(registerArityVariants("diff-ema.sc", 1, 3, false))
        .concat(registerArityVariants("dema", 1, 3, false))
        .concat(registerArityVariants("dema.c", 1, 3, false))
        .concat(registerArityVariants("dema.sc", 1, 3, false))
        .concat(registerArityVariants("bitcrush", 1, 2, false))
        .concat(registerArityVariants("bit-crush", 1, 2, false));

    mathWords = mathWords.concat(arityMathWords);
    creativeWords = creativeWords.concat(arityCreativeWords);

    var constants = [
        "pi", "tau", "e",
        "black", "white", "gray", "grey", "red", "green", "blue",
        "cyan", "magenta", "yellow", "orange"
    ];

    var numberSource = "[+\\-]?(?:(?:\\d+\\.\\d*)|(?:\\d*\\.\\d+)|(?:\\d+))(?:[eE][+\\-]?\\d+)?";
    var modifierSource = "pulse|noise|grain|hash|saw|tri|fbm|rad|ang|wx|wy|wr|wa|cx|cy|sq|sh|q|n|j|c|s|t|r|g|b|a|x|y";
    var modifierUnitSource = "(?:" + modifierSource + ")(?:" + numberSource + ")?(?:p" + numberSource + ")?(?:d" + numberSource + ")?";
    var numberRegex = new RegExp("^" + numberSource + "$", "i");
    var numberSuffixRegex = new RegExp("^" + numberSource + "[trgba]$", "i");
    var numberRangeRegex = new RegExp("^" + numberSource + "_" + numberSource + "$", "i");
    var vecLiteralRegex = new RegExp("^\\[\\s*" + numberSource + "(?:\\s*,\\s*" + numberSource + "){0,3}\\s*\\]$", "i");
    var stackSelectorLiteralRegex = /^\[\s*\d+[xyzwrgba]{1,4}(?:\s*,\s*\d+[xyzwrgba]{1,4}){0,3}\s*\]$/i;
    var numberModifierRegex = new RegExp("^" + numberSource + "(?:" + modifierUnitSource + ")+$", "i");
    var topModifierRegex = new RegExp("^[\\|\\]](?:" + modifierUnitSource + ")+$", "i");
    var swizzleRegex = /^\.?[xyzwrgba]{1,4}$/i;
    var numberRangeTokenRegex = new RegExp("(" + numberSource + ")(_)" + "(" + numberSource + ")");
    var vecLiteralTokenRegex = new RegExp("\\[\\s*" + numberSource + "(?:\\s*,\\s*" + numberSource + "){0,3}\\s*\\]", "i");
    var stackSelectorLiteralTokenRegex = /\[\s*\d+[xyzwrgba]{1,4}(?:\s*,\s*\d+[xyzwrgba]{1,4}){0,3}\s*\]/i;
    var topModifierTokenRegex = new RegExp("([\\|\\]])(" + modifierSource + ")(" + numberSource + ")?(?:(p)(" + numberSource + "))?(?:(d)(" + numberSource + "))?(?=$|[\\s()#;])", "i");
    var numberModifierTokenRegex = new RegExp("(" + numberSource + ")(" + modifierSource + ")(" + numberSource + ")?(?:(p)(" + numberSource + "))?(?:(d)(" + numberSource + "))?(?=$|[\\s()#;])", "i");
    var stackedTopModifierTokenRegex = new RegExp("[\\|\\]](?:" + modifierUnitSource + "){2,}(?=$|[\\s()#;])", "i");
    var stackedNumberModifierTokenRegex = new RegExp(numberSource + "(?:" + modifierUnitSource + "){2,}(?=$|[\\s()#;])", "i");
    var simpleNumberSuffixTokenRegex = new RegExp("(" + numberSource + ")([trgba])\\b", "i");
    var hostGlslIdentifierSource = "[A-Za-z_][A-Za-z0-9_]*";
    var hostGlslIndexSource = "\\[[^\\]\\s]+\\]";
    var hostGlslVec4Source = hostGlslIdentifierSource + "(?:" + hostGlslIndexSource + ")*(?:\\." + hostGlslIdentifierSource + "(?:" + hostGlslIndexSource + ")*)*";
    var externalReadRegex = new RegExp("^\\?" + hostGlslVec4Source + "$");
    var externalWriteRegex = new RegExp("^->\\?" + hostGlslVec4Source + "$");
    var externalReadTokenRegex = new RegExp("(\\?)(" + hostGlslVec4Source + ")");
    var externalWriteTokenRegex = new RegExp("(->\\?)(" + hostGlslVec4Source + ")");
    var localMarkNameSource = "[A-Za-z_][A-Za-z0-9_-]*";
    var localMarkCopyRegex = new RegExp("^=(" + localMarkNameSource + ")$");
    var localMarkMoveRegex = new RegExp("^>(" + localMarkNameSource + ")$");
    var localMarkReadRegex = new RegExp("^<(" + localMarkNameSource + ")$");
    var localMarkMoveReservedRegex = /^>[xyzw]$/i;
    var localMarkCopyTokenRegex = new RegExp("(=)(" + localMarkNameSource + ")");
    var localMarkMoveTokenRegex = new RegExp("(>)(?!(?:x|y|z|w)\\b)(" + localMarkNameSource + ")", "i");
    var localMarkReadTokenRegex = new RegExp("(<)(" + localMarkNameSource + ")");

    function stackSelectorLiteralDepth(word) {
        var text = String(word || "");
        var body = text.slice(1, -1);
        var parts = body.split(",");
        var maxIndex = -1;
        for (var i = 0; i < parts.length; i++) {
            var match = /^\s*(\d+)[xyzwrgba]{1,4}\s*$/i.exec(parts[i]);
            if (!match) return 0;
            maxIndex = Math.max(maxIndex, parseInt(match[1], 10));
        }
        return maxIndex + 1;
    }

    function wordSet(words) {
        var out = Object.create(null);
        words.forEach(function (word) { out[String(word).toLowerCase()] = true; });
        return out;
    }

    var pushWordSet = wordSet(stackWords.concat(constants, ["prev", "state"]));
    var unaryWordSet = wordSet([
        "neg", "negate", "1-", "abs", "floor", "ceil", "fract", "sqrt", "inversesqrt", "exp", "log",
        "sin", "cos", "tan", "asin", "acos", "atan", "normalize",
        "len", "length", "len2", "length2", "len3", "length3", "len4", "length4",
        "luma", "sum", "prod", "product", "any", "all", "x+y", "xplusy", "xylen", "rgbnorm", "rgb-normalize",
        "average", "avg", "mean", "median", "med", "vmin", "minimum", "vmax", "maximum",
        "sat", "saturate", "mirror", "smooth", "repeat", "polar", "cart", "cartesian", "tonemap",
        "ryb", "ryb2rgb", "from-ryb", "rgb2ryb", "to-ryb",
        "oklab", "oklab2rgb", "from-oklab", "rgb2oklab", "to-oklab",
        "noise", "perlin", "fbm", "not",
        "gray-code", "graycode", "gray-encode", "gray-decode", "ungray", "lfsr"
    ]);
    var binaryWordSet = wordSet([
        "+", "add", "-", "sub", "*", "mul", "/", "div", "mod", "pow", "pow.safe",
        "atan2", "dot", "distance2", "distance3", "distance", "cross", "rotate", ">x", ">y", ">z", ">w",
        "lt", "<", "lte", "<=", "gt", ">", "gte", ">=", "eq", "=", "neq", "!=", "<>",
        "and", "or", "xor", "bitxor"
    ]);

    function isPushToken(word) {
        return !!pushWordSet[word] ||
            externalReadRegex.test(word) ||
            vecLiteralRegex.test(word) ||
            stackSelectorLiteralRegex.test(word) ||
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

    function stackEffectForWord(rawWord, depth, definitionWords, localMarkWords) {
        var raw = String(rawWord || "").toLowerCase();
        var variant = arityVariant(raw);
        var word = variant ? variant.base : raw;
        var requestedArity = variant ? variant.arity : null;
        var requestedAll = variant ? variant.all : false;
        if (!word || word === ":" || word === ";" || word === "|" || word === "]") return effect(0, 0);
        if (topModifierRegex.test(word)) return effect(1, 1);
        if (stackSelectorLiteralRegex.test(word)) return effect(stackSelectorLiteralDepth(word), 1);
        if (isPushToken(word)) return effect(0, 1);
        if (swizzleRegex.test(word)) return effect(1, 1);

        if (word === "noop") return effect(0, 0);
        if (externalWriteRegex.test(word)) return effect(1, 0);
        if (localMarkCopyRegex.test(word)) return effect(1, 1);
        if (localMarkMoveRegex.test(word) && !localMarkMoveReservedRegex.test(word)) return effect(1, 0);
        if (localMarkReadRegex.test(word)) return effect(0, 1);
        if (localMarkWords && localMarkWords.has(word)) return effect(0, 1);
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
        if (word === "rotates" || word === "standoff" || word === "standoff2" || word === "standoff3") return effect(3, 1);
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
            return effect(requestedArity || 2, 1);
        }
        if (word === "mix" || word === "lerp" || word === "mix.c" || word === "lerp.c" ||
            word === "mix.sc" || word === "lerp.sc" || word === "select" || word === "if") return effect(3, 1);
        if (word === "alpha" || word === "alpha.c" || word === "alpha.sc") return effect(3, 1);
        if (word === "step") return effect(2, 1);
        if (word === "smoothstep") return effect(3, 1);
        if (word === "sigmoid" || word === "softplus" || word === "threshold" || word === "posterize" || word === "clamp" ||
            word === "bias" || word === "gain" || word === "tile" || word === "twist" ||
            word === "spiral" || word === "kaleido" || word === "kaleidoscope") {
            var fullArity = word === "clamp" ? 3 : 2;
            return requestedArity ? effect(requestedArity, 1) : dynamicEffect(depth, fullArity);
        }
        if (word === "band" || word === "ramp") return requestedArity ? effect(requestedArity, 1) : dynamicEffect(depth, 3);
        if (word === "pulse" || word === "fm" ||
            word === "ema" || word === "ema.c" || word === "ema.sc" ||
            word === "ema-diff" || word === "ema-diff.c" || word === "ema-diff.sc" ||
            word === "diff-ema" || word === "diff-ema.c" || word === "diff-ema.sc" ||
            word === "dema" || word === "dema.c" || word === "dema.sc") return requestedArity ? effect(requestedArity, 1) : dynamicEffect(depth, 3);
        if (word === "fmchirp") return requestedArity ? effect(requestedArity, 1) : dynamicEffect(depth, 9);
        if (word === "palette" || word === "cos-palette" || word === "cosine-palette") return requestedArity ? effect(requestedArity, 1) : dynamicEffect(depth, 5);
        if (word === "saturation" || word === "sat-color" || word === "contrast") return requestedArity ? effect(requestedArity, 1) : dynamicEffect(depth, 2);
        if (word === "bitcrush" || word === "bit-crush") return requestedArity ? effect(requestedArity, 1) : dynamicEffect(depth, 2);
        if (word === "grade") return requestedArity ? effect(requestedArity, 1) : dynamicEffect(depth, 4);
        if (word === "sample" || word === "sample0" || word === "sample1" || word === "sample2" || word === "sample3" ||
            word === "sample.w" || word === "sample0.w" || word === "sample1.w" || word === "sample2.w" || word === "sample3.w" ||
            word === "sample-abs" || word === "sample0-abs" || word === "sample1-abs" || word === "sample2-abs" || word === "sample3-abs" ||
            word === "sample-abs.w" || word === "sample0-abs.w" || word === "sample1-abs.w" || word === "sample2-abs.w" || word === "sample3-abs.w" ||
            word === "gather" || word === "gather0" || word === "gather1" || word === "gather2" || word === "gather3" ||
            word === "gather.w" || word === "gather0.w" || word === "gather1.w" || word === "gather2.w" || word === "gather3.w") return effect(1, 1);
        if (word === "crosssample" || word === "cross-sample" ||
            word === "crosssample.r" || word === "crosssample.g" || word === "crosssample.b" || word === "crosssample.a" ||
            word === "cross-sample.r" || word === "cross-sample.g" || word === "cross-sample.b" || word === "cross-sample.a" ||
            word === "rcrosssample" || word === "r-cross-sample" ||
            word === "rcrosssample.r" || word === "rcrosssample.g" || word === "rcrosssample.b" || word === "rcrosssample.a" ||
            word === "r-cross-sample.r" || word === "r-cross-sample.g" || word === "r-cross-sample.b" || word === "r-cross-sample.a") return effect(2, 1);
        if (word === "laplace" || word === "laplacian" || word === "blur" || word === "blur5" || word === "blur9" ||
            word === "sobel" || word === "gradient" || word === "divergence" || word === "curl") return requestedArity ? effect(requestedArity, 1) : effect(Math.min(2, depth), 1);
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
        var localMarks = new Set();

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
                localMarks = new Set();
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
                localMarks = new Set();
                pushFinalBlank();
                continue;
            }

            var stackEffect = stackEffectForWord(text, depth, definitionWords, localMarks);
            if (stackEffect.consume === 0 && stackEffect.produce > 0) {
                pendingPushes.push(text);
            } else if (stackEffect.consume > 0 && pendingPushes.length > 0) {
                flushPendingPushes(text);
            } else {
                flushPendingPushes();
                pushFinalLine(text, bodyLevel());
            }
            depth = applyEffect(depth, stackEffect);
            var lowerText = String(text || "").toLowerCase();
            var copyName = localMarkCopyRegex.exec(lowerText);
            var moveName = localMarkMoveReservedRegex.test(lowerText) ? null : localMarkMoveRegex.exec(lowerText);
            if (copyName) localMarks.add(copyName[1]);
            if (moveName) localMarks.add(moveName[1]);
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
    const STACKSL_MODIFIER_SOURCE = "pulse|noise|grain|hash|saw|tri|fbm|rad|ang|wx|wy|wr|wa|cx|cy|sq|sh|q|n|j|c|s|t|r|g|b|a|x|y"
    const STACKSL_MODIFIER_UNIT_SOURCE = `(?:${STACKSL_MODIFIER_SOURCE})(?:${STACKSL_NUMBER_SOURCE})?(?:p${STACKSL_NUMBER_SOURCE})?(?:d${STACKSL_NUMBER_SOURCE})?`
    const STACKSL_NUMBER_RE = new RegExp(`^${STACKSL_NUMBER_SOURCE}$`)
    const STACKSL_NUMBER_SUFFIX_RE = new RegExp(`^${STACKSL_NUMBER_SOURCE}[trgba]$`, "i")
    const STACKSL_NUMBER_RANGE_RE = new RegExp(`^${STACKSL_NUMBER_SOURCE}_${STACKSL_NUMBER_SOURCE}$`, "i")
    const STACKSL_VEC_LITERAL_RE = new RegExp(`^\\[\\s*${STACKSL_NUMBER_SOURCE}(?:\\s*,\\s*${STACKSL_NUMBER_SOURCE}){0,3}\\s*\\]$`, "i")
    const STACKSL_STACK_SELECTOR_LITERAL_RE = /^\[\s*\d+[xyzwrgba]{1,4}(?:\s*,\s*\d+[xyzwrgba]{1,4}){0,3}\s*\]$/i
    const STACKSL_NUMBER_MODIFIER_RE = new RegExp(`^${STACKSL_NUMBER_SOURCE}(?:${STACKSL_MODIFIER_UNIT_SOURCE})+$`, "i")
    const STACKSL_TOP_MODIFIER_RE = new RegExp(`^[\\|\\]](?:${STACKSL_MODIFIER_UNIT_SOURCE})+$`, "i")
    const STACKSL_SWIZZLE_RE = /^\.?[xyzwrgba]{1,4}$/i
    const STACKSL_HOST_GLSL_IDENTIFIER_SOURCE = "[A-Za-z_][A-Za-z0-9_]*"
    const STACKSL_HOST_GLSL_INDEX_SOURCE = "\\[[^\\]\\s]+\\]"
    const STACKSL_HOST_GLSL_VEC4_SOURCE = `${STACKSL_HOST_GLSL_IDENTIFIER_SOURCE}(?:${STACKSL_HOST_GLSL_INDEX_SOURCE})*(?:\\.${STACKSL_HOST_GLSL_IDENTIFIER_SOURCE}(?:${STACKSL_HOST_GLSL_INDEX_SOURCE})*)*`
    const STACKSL_EXTERNAL_READ_RE = new RegExp(`^\\?${STACKSL_HOST_GLSL_VEC4_SOURCE}$`)
    const STACKSL_EXTERNAL_WRITE_RE = new RegExp(`^->\\?${STACKSL_HOST_GLSL_VEC4_SOURCE}$`)
    const STACKSL_LOCAL_MARK_NAME_SOURCE = "[A-Za-z_][A-Za-z0-9_-]*"
    const STACKSL_LOCAL_MARK_COPY_RE = new RegExp(`^=(${STACKSL_LOCAL_MARK_NAME_SOURCE})$`)
    const STACKSL_LOCAL_MARK_MOVE_RE = new RegExp(`^>(${STACKSL_LOCAL_MARK_NAME_SOURCE})$`)
    const STACKSL_LOCAL_MARK_READ_RE = new RegExp(`^<(${STACKSL_LOCAL_MARK_NAME_SOURCE})$`)
    const STACKSL_LOCAL_MARK_MOVE_RESERVED_RE = /^>[xyzw]$/i
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
            "neg", "negate", "1-", "abs", "floor", "ceil", "fract", "sqrt", "inversesqrt", "exp", "log",
            "sin", "cos", "tan", "asin", "acos", "atan", "normalize",
            "len", "length", "len2", "length2", "len3", "length3", "len4", "length4",
            "luma", "sum", "prod", "product", "any", "all", "x+y", "xplusy", "xylen", "rgbnorm", "rgb-normalize",
            "average", "avg", "mean", "median", "med", "vmin", "minimum", "vmax", "maximum",
            "sat", "saturate", "mirror", "smooth", "repeat", "polar", "cart", "cartesian", "tonemap",
            "ryb", "ryb2rgb", "from-ryb", "rgb2ryb", "to-ryb",
            "ryb.c", "ryb2rgb.c", "from-ryb.c", "rgb2ryb.c", "to-ryb.c",
            "oklab", "oklab2rgb", "from-oklab", "rgb2oklab", "to-oklab",
            "noise", "perlin", "fbm", "not",
            "gray-code", "graycode", "gray-encode", "gray-decode", "ungray", "lfsr",
    ])

    const STACKSL_BINARY_WORDS = new Set([
            "+", "add", "-", "sub", "*", "mul", "/", "div", "mod", "pow", "pow.safe",
            "atan2", "dot", "distance2", "distance3", "distance", "cross", "rotate", ">x", ">y", ">z", ">w",
            "lt", "<", "lte", "<=", "gt", ">", "gte", ">=", "eq", "=", "neq", "!=", "<>",
            "and", "or", "xor", "bitxor",
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
            ["product", "prod"],
            ["xplusy", "x+y"],
            ["rgb-normalize", "rgbnorm"],
            ["saturate", "sat"],
            ["cartesian", "cart"],
            ["kaleidoscope", "kaleido"],
            ["perlin", "noise"],
            ["cos-palette", "palette"],
            ["cosine-palette", "palette"],
            ["sat-color", "saturation"],
            ["alpha.c", "alpha"],
            ["alpha.sc", "alpha"],
            ["diff-ema", "ema-diff"],
            ["dema", "ema-diff"],
            ["ema.c", "ema"],
            ["ema.sc", "ema"],
            ["ema-diff.c", "ema-diff"],
            ["ema-diff.sc", "ema-diff"],
            ["diff-ema.c", "ema-diff"],
            ["diff-ema.sc", "ema-diff"],
            ["dema.c", "ema-diff"],
            ["dema.sc", "ema-diff"],
            ["from-ryb", "ryb"],
            ["ryb2rgb", "ryb"],
            ["ryb.c", "ryb.c"],
            ["from-ryb.c", "ryb.c"],
            ["ryb2rgb.c", "ryb.c"],
            ["to-ryb", "rgb2ryb"],
            ["rgb2ryb.c", "rgb2ryb.c"],
            ["to-ryb.c", "rgb2ryb.c"],
            ["from-oklab", "oklab"],
            ["oklab2rgb", "oklab"],
            ["to-oklab", "rgb2oklab"],
            ["add", "+"],
            ["sub", "-"],
            ["mul", "*"],
            ["div", "/"],
            ["lerp", "mix"],
            ["lerp.c", "mix.c"],
            ["lerp.sc", "mix.sc"],
            ["if", "select"],
            ["bitxor", "xor"],
            ["graycode", "gray-code"],
            ["gray-encode", "gray-code"],
            ["ungray", "gray-decode"],
            ["bit-crush", "bitcrush"],
            ["cross-sample", "crosssample"],
            ["cross-sample.r", "crosssample.r"],
            ["cross-sample.g", "crosssample.g"],
            ["cross-sample.b", "crosssample.b"],
            ["cross-sample.a", "crosssample.a"],
            ["r-cross-sample", "rcrosssample"],
            ["r-cross-sample.r", "rcrosssample.r"],
            ["r-cross-sample.g", "rcrosssample.g"],
            ["r-cross-sample.b", "rcrosssample.b"],
            ["r-cross-sample.a", "rcrosssample.a"],
            ["laplacian", "laplace"],
            ["blur5", "blur"],
    ])

    const STACKSL_WORD_DOCS = new Map([
            ["noop", stackslDoc("--", "No operation.")],
            ["input", stackslDoc("-- input", "Push the full vec4(x, y, z-or-hash, time).")],
            ["uv", stackslDoc("-- uv", "Push normalized uv as vec4(uv.xy, 0, 1).")],
            ["p", stackslDoc("-- p", "Push centered uv as vec4(uv.xy - 0.5, 0, 1).")],
            ["time", stackslDoc("-- time", "Push time from input.w, splatted to vec4.")],
            ["hash", stackslDoc("-- hash", "Push z/hash from input.z, splatted to vec4.")],
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
            ["1-", stackslDoc("a -- out", "Subtract component-wise from vec4(1).")],
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
            ["distance2", stackslDoc("a b -- scalar", "Distance between a.xy and b.xy, splatted.")],
            ["distance3", stackslDoc("a b -- scalar", "Distance between a.xyz and b.xyz, splatted.")],
            ["distance", stackslDoc("a b -- scalar", "Distance between full vec4 values, splatted.")],
            ["luma", stackslDoc("color -- scalar", "Rec.709 luminance, splatted.")],
            ["sum", stackslDoc("vec -- scalar", "Sum of RGBA channels, splatted.")],
            ["prod", stackslDoc("vec -- scalar", "Product of RGBA channels, splatted.")],
            ["any", stackslDoc("vec -- mask", "Any channel above threshold 0.5, splatted.")],
            ["all", stackslDoc("vec -- mask", "All channels above threshold 0.5, splatted.")],
            ["x+y", stackslDoc("vec -- scalar", "Sum x and y, splatted.")],
            ["xylen", stackslDoc("vec -- scalar", "Length of vec.xy, splatted.")],
            ["rgbnorm", stackslDoc("color -- color", "Normalize rgb while preserving alpha.")],
            ["avg", stackslDoc("vec -- scalar", "Average of RGBA channels, splatted.")],
            ["median", stackslDoc("vec -- scalar", "Median-ish RGBA channel value, splatted.")],
            ["sort", stackslDoc("values -- values", "Component-wise ascending stack sort; use .2-.8 or .all.")],
            ["intermediate", stackslDoc("values -- midpoints", "Adjacent midpoints; use .2-.8 or .all.")],
            ["vmin", stackslDoc("vec -- scalar", "Minimum RGBA channel, splatted.")],
            ["vmax", stackslDoc("vec -- scalar", "Maximum RGBA channel, splatted.")],
            ["sat", stackslDoc("color -- color", "Clamp all channels to 0..1.")],
            ["clamp", stackslDoc("value low high -- out", "GLSL clamp; short form clamps value to 0..1.")],
            ["mirror", stackslDoc("a -- out", "Triangle-wrap values into 0..1.")],
            ["smooth", stackslDoc("a -- out", "Apply smoothstep(0, 1, a).")],
            ["repeat", stackslDoc("a -- out", "Fractional repeat with fract(a).")],
            ["tonemap", stackslDoc("color -- color", "Compress bright colors for feedback.")],
            ["ryb", stackslDoc("ryb -- rgb", "Convert paint-like RYB color to RGB without clamping.")],
            ["ryb.c", stackslDoc("ryb -- rgb", "Convert paint-like RYB color to RGB with 0..1 input/output clamping.")],
            ["rgb2ryb", stackslDoc("rgb -- ryb", "Convert RGB to paint-like RYB without clamping.")],
            ["rgb2ryb.c", stackslDoc("rgb -- ryb", "Convert RGB to paint-like RYB with 0..1 input/output clamping.")],
            ["oklab", stackslDoc("oklab -- rgb", "Convert OKLab-like values to RGB.")],
            ["rgb2oklab", stackslDoc("rgb -- oklab", "Convert RGB to perceptual-ish OKLab.")],
            ["+", stackslDoc("a b -- out", "Add component-wise.")],
            ["-", stackslDoc("a b -- out", "Subtract component-wise.")],
            ["*", stackslDoc("a b -- out", "Multiply component-wise.")],
            ["/", stackslDoc("a b -- out", "Safe divide component-wise.")],
            ["mod", stackslDoc("a b -- out", "Safe modulo component-wise.")],
            ["pow", stackslDoc("base exp -- out", "Raw GLSL pow(base, exp).")],
            ["pow.safe", stackslDoc("base exp -- out", "Power with a safe absolute base.")],
            ["dot", stackslDoc("a b -- scalar", "Dot product, splatted.")],
            ["cross", stackslDoc("a b -- vec", "Cross product of xyz, alpha set to 1.")],
            ["standoff", stackslDoc("a b c -- out", "Move from b toward a by c using safe full-vec4 normalization: safeNormalize(a - b) * c + b.")],
            ["standoff2", stackslDoc("a b c -- out", "Move from b toward a by c using safe xy normalization while carrying all vec4 lanes.")],
            ["standoff3", stackslDoc("a b c -- out", "Move from b toward a by c using safe xyz normalization while carrying all vec4 lanes.")],
            ["rotate", stackslDoc("a b -- out", "Rotate a.xy by b.x and a.zw by b.y.")],
            ["rotates", stackslDoc("a b c -- out", "Rotate and scale a.xy around b.xy using c.xy, and a.zw around b.zw using c.zw.")],
            ["tile", stackslDoc("coord scale -- coord", "Repeat coord.xy by scale.xy with fract, preserving z/w.")],
            ["polar", stackslDoc("coord -- polar", "Convert xy to vec4(radius, angleRadians, z, w).")],
            ["cart", stackslDoc("polar -- coord", "Convert vec4(radius, angleRadians, z, w) back to xy coordinates.")],
            ["twist", stackslDoc("coord amount -- coord", "Rotate coord.xy by radius * amount.x.")],
            ["spiral", stackslDoc("coord amount -- coord", "Polar twist coord.xy by radius and log-radius terms.")],
            ["kaleido", stackslDoc("coord sectors -- coord", "Angular mirror/repeat coord.xy into kaleidoscope sectors.")],
            [">x", stackslDoc("a b -- out", "Copy a, replacing x with b.x.")],
            [">y", stackslDoc("a b -- out", "Copy a, replacing y with b.y.")],
            [">z", stackslDoc("a b -- out", "Copy a, replacing z with b.z.")],
            [">w", stackslDoc("a b -- out", "Copy a, replacing w with b.w.")],
            ["lt", stackslDoc("a b -- mask", "lessThan mask in 0..1.")],
            ["lte", stackslDoc("a b -- mask", "lessThanEqual mask in 0..1.")],
            ["gt", stackslDoc("a b -- mask", "greaterThan mask in 0..1.")],
            ["gte", stackslDoc("a b -- mask", "greaterThanEqual mask in 0..1.")],
            ["eq", stackslDoc("a b -- mask", "equal mask in 0..1.")],
            ["neq", stackslDoc("a b -- mask", "notEqual mask in 0..1.")],
            ["and", stackslDoc("a b -- mask", "Boolean-ish and using threshold 0.5.")],
            ["or", stackslDoc("a b -- mask", "Boolean-ish or using threshold 0.5.")],
            ["not", stackslDoc("a -- mask", "Boolean-ish not using threshold 0.5.")],
            ["xor", stackslDoc("a b -- out", "16-bit lane XOR, normalized back into 0..1.")],
            ["gray-code", stackslDoc("value -- out", "Encode each 16-bit lane as Gray code.")],
            ["gray-decode", stackslDoc("value -- out", "Decode Gray-coded 16-bit lanes back to binary.")],
            ["lfsr", stackslDoc("state -- next", "Step coupled 16-bit LFSR lanes packed in vec4 state.")],
            ["bitcrush", stackslDoc("value bits -- out", "Quantize normalized lanes to the requested bit depth; short form defaults to 4 bits.")],
            ["mix", stackslDoc("a b amount -- out", "Raw component-wise GLSL mix; amount is not clamped.")],
            ["mix.c", stackslDoc("a b amount -- out", "Component-wise mix with amount clamped to 0..1.")],
            ["mix.sc", stackslDoc("a b amount -- out", "Component-wise mix with a soft-clamped 0..1 amount.")],
            ["select", stackslDoc("false true condition -- out", "Boolean-mask selection using condition threshold 0.5.")],
            ["alpha", stackslDoc("baseAlpha modulation range -- alpha", "Modulate an EMA alpha by multiplying its decay rate by pow(range, modulation). Raw version preserves negative base-alpha sign.")],
            ["alpha.c", stackslDoc("baseAlpha modulation range -- alpha", "Clamped alpha modulation: baseAlpha is clamped to 1e-6..0.999999 and modulation to -1..1.")],
            ["alpha.sc", stackslDoc("baseAlpha modulation range -- alpha", "Soft-clamped alpha modulation for smoother bounded control.")],
            ["step", stackslDoc("edge x -- mask", "GLSL step, with defaults if needed.")],
            ["smoothstep", stackslDoc("low high x -- mask", "GLSL smoothstep, with defaults if needed.")],
            ["sigmoid", stackslDoc("x amount -- out", "Logistic S curve around 0.5; short form uses amount 8.")],
            ["softplus", stackslDoc("x amount -- out", "Soft rectifier log(1 + exp(k*x))/k; short form uses amount 4.")],
            ["threshold", stackslDoc("x edge -- mask", "Hard threshold mask; short form uses edge 0.5.")],
            ["band", stackslDoc("x center width -- mask", "Hard centered band mask with trailing defaults.")],
            ["ramp", stackslDoc("x low high -- out", "Clamp x remapped from low..high into 0..1.")],
            ["posterize", stackslDoc("value levels -- out", "Quantize normalized lanes to a level count; short form uses 6 levels.")],
            ["bias", stackslDoc("value amount -- out", "Schlick-style bias curve; short form uses neutral amount 0.5.")],
            ["gain", stackslDoc("value amount -- out", "Schlick-style gain curve; short form uses neutral amount 0.5.")],
            ["noise", stackslDoc("coord -- scalar", "Value noise from coord.xy + coord.zw * 0.07.")],
            ["fbm", stackslDoc("coord -- scalar", "Five-octave fractal noise.")],
            ["sample", stackslDoc("offset -- color", "Sample previous frame at uv + offset.xy without wrapping.")],
            ["sample.w", stackslDoc("offset -- color", "Sample previous frame at fract(uv + offset.xy).")],
            ["sample-abs", stackslDoc("coord -- color", "Sample previous frame at coord.xy without wrapping.")],
            ["sample-abs.w", stackslDoc("coord -- color", "Sample previous frame at fract(coord.xy).")],
            ["gather", stackslDoc("offset -- vec", "textureGather red channel around uv + offset.xy without wrapping.")],
            ["gather.w", stackslDoc("offset -- vec", "textureGather red channel around fract(uv + offset.xy).")],
            ["crosssample", stackslDoc("center radius -- color", "Average four samples at center.xy +/- radius.x/y cross offsets.")],
            ["rcrosssample", stackslDoc("center radius -- color", "Average four samples on a deterministically random rotated cross around center.xy.")],
            ["crosssample.r", stackslDoc("center radius -- taps", "Return four red-channel cross taps in xyzw.")],
            ["crosssample.g", stackslDoc("center radius -- taps", "Return four green-channel cross taps in xyzw.")],
            ["crosssample.b", stackslDoc("center radius -- taps", "Return four blue-channel cross taps in xyzw.")],
            ["crosssample.a", stackslDoc("center radius -- taps", "Return four alpha-channel cross taps in xyzw.")],
            ["rcrosssample.r", stackslDoc("center radius -- taps", "Return four red-channel rotated-cross taps in xyzw.")],
            ["rcrosssample.g", stackslDoc("center radius -- taps", "Return four green-channel rotated-cross taps in xyzw.")],
            ["rcrosssample.b", stackslDoc("center radius -- taps", "Return four blue-channel rotated-cross taps in xyzw.")],
            ["rcrosssample.a", stackslDoc("center radius -- taps", "Return four alpha-channel rotated-cross taps in xyzw.")],
            ["laplace", stackslDoc("center radius -- color", "Four-neighbor Laplacian of the first configured sampler.")],
            ["blur", stackslDoc("center radius -- color", "Five-tap blur of the first configured sampler.")],
            ["blur9", stackslDoc("center radius -- color", "Nine-tap weighted blur of the first configured sampler.")],
            ["sobel", stackslDoc("center radius -- edge", "Sobel edge magnitude from sampler luminance.")],
            ["gradient", stackslDoc("center radius -- vec", "Central-difference luminance gradient as vec4(gx, gy, magnitude, 1).")],
            ["divergence", stackslDoc("center radius -- scalar", "Central-difference divergence of xy texture channels.")],
            ["curl", stackslDoc("center radius -- scalar", "Central-difference 2D curl of xy texture channels.")],
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

    const stackSLDocForWord = (rawWord, effect, definitionWords, localMarkWords = null) => {
            const raw = String(rawWord || "").toLowerCase()
            const variant = arityVariant(raw)
            const word = variant ? variant.base : raw
            const canonical = stackSLCanonicalWord(word)

            if (STACKSL_NUMBER_RANGE_RE.test(raw)) {
                    return stackslDoc("-- vec", "Push a vec4 range spread across four scalar values.")
            }
            if (STACKSL_VEC_LITERAL_RE.test(raw)) {
                    return stackslDoc("-- vec", "Push a numeric vec4 literal; one to four slots use vec word fill rules.")
            }
            if (STACKSL_STACK_SELECTOR_LITERAL_RE.test(raw)) {
                    return stackslDoc("values -- vec", "Build a vec4 from stack-indexed swizzles; 0 is the top stack value.")
            }
            if (STACKSL_NUMBER_SUFFIX_RE.test(raw)) {
                    return stackslDoc("-- value", "Push a time-scaled or channel-weighted number.")
            }
            if (STACKSL_NUMBER_MODIFIER_RE.test(raw)) {
                    return stackslDoc("-- value", "Push amount times one or more chained time/spatial/noise modifiers.")
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
                            ? stackslDoc("driver -- out", "Use the consumed value as modifier driver; chained suffixes transform the result.")
                            : stackslDoc("amount -- out", "Multiply consumed amount by one or more chained time/spatial/noise modifiers.")
            }
            if (STACKSL_SWIZZLE_RE.test(raw)) {
                    return stackslDoc("vec -- vec", "Swizzle channels and return a vec4-shaped value.")
            }
            if (STACKSL_EXTERNAL_READ_RE.test(rawWord || "")) {
                    return stackslDoc("-- vec", "Read a host GLSL vec4 expression.")
            }
            if (STACKSL_EXTERNAL_WRITE_RE.test(rawWord || "")) {
                    return stackslDoc("value --", "Write the top stack value to a host GLSL vec4 expression.")
            }
            if (STACKSL_LOCAL_MARK_COPY_RE.test(raw)) {
                    return stackslDoc("a -- a", "Copy the top stack value into a local mark.")
            }
            if (STACKSL_LOCAL_MARK_MOVE_RE.test(raw) && !STACKSL_LOCAL_MARK_MOVE_RESERVED_RE.test(raw)) {
                    return stackslDoc("a --", "Move the top stack value into a local mark.")
            }
            if (STACKSL_LOCAL_MARK_READ_RE.test(raw) || (localMarkWords && localMarkWords.has(raw))) {
                    return stackslDoc("-- a", "Read a local mark and push it onto the stack.")
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
            if (canonical === "alpha") {
                    const clampDetail = word.endsWith(".c")
                            ? " Base alpha is hard-clamped to 1e-6..0.999999 and modulation to -1..1."
                            : word.endsWith(".sc")
                                    ? " Base alpha and modulation use soft bounded controls."
                                    : " Raw version preserves negative base-alpha sign."
                    return stackslDoc("baseAlpha modulation range -- alpha", "Modulate EMA decay rate by pow(range, modulation)." + clampDetail)
            }
            if (canonical === "ema") {
                    const signatures = {
                            1: "current -- smooth",
                            2: "current previous -- smooth",
                            3: "current previous alpha -- smooth",
                    }
                    const clampDetail = word.endsWith(".c") ? " Alpha is clamped to 0..1." : word.endsWith(".sc") ? " Alpha is soft-clamped to 0..1." : " Alpha is raw/unclamped."
                    return stackslDoc(signatures[effect.consume] || signatures[3], (effect.consume < 3 ? "EMA with trailing defaults." : "Exponential moving average.") + clampDetail)
            }
            if (canonical === "ema-diff") {
                    const signatures = {
                            1: "current -- diff",
                            2: "current previous -- diff",
                            3: "current previous alpha -- diff",
                    }
                    const clampDetail = word.endsWith(".c") ? " Alpha is clamped to 0..1." : word.endsWith(".sc") ? " Alpha is soft-clamped to 0..1." : " Alpha is raw/unclamped."
                    return stackslDoc(signatures[effect.consume] || signatures[3], (effect.consume < 3 ? "Current minus EMA with trailing defaults." : "Current minus exponential moving average.") + clampDetail)
            }
            if (canonical === "bitcrush") {
                    return effect.consume === 1
                            ? stackslDoc("value -- out", "Quantize normalized lanes to default 4-bit depth.")
                            : stackslDoc("value bits -- out", "Quantize normalized lanes to a per-channel bit depth clamped to 1..16.")
            }

            if (/^sample[0-3]$/.test(word)) return STACKSL_WORD_DOCS.get("sample")
            if (/^sample[0-3]\.w$/.test(word)) return STACKSL_WORD_DOCS.get("sample.w")
            if (/^sample[0-3]-abs$/.test(word)) return STACKSL_WORD_DOCS.get("sample-abs")
            if (/^sample[0-3]-abs\.w$/.test(word)) return STACKSL_WORD_DOCS.get("sample-abs.w")
            if (/^gather[0-3]$/.test(word)) return STACKSL_WORD_DOCS.get("gather")
            if (/^gather[0-3]\.w$/.test(word)) return STACKSL_WORD_DOCS.get("gather.w")
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

    const tokenizeStackSLForContext = (source, baseOffset = 0, basePosition = null) => {
            const tokens = []
            let i = 0
            let line = basePosition && basePosition.lineNumber ? basePosition.lineNumber : 1
            let column = basePosition && basePosition.column ? basePosition.column : 1

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
                            const start = baseOffset + i
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
                                            end: baseOffset + i,
                                            line: startLine,
                                            column: startColumn,
                                    })
                            }
                    }
            }
            return tokens
    }

    const stackSLEmbeddedHeaderRegex = /^(?:[ \t]*(?:\r?\n|$))*[ \t]*#stacksl\b[^\n]*(?:\r?\n|$)/i

    const findStackSLEmbeddedRegions = (model) => {
            if (!model || model.getLanguageId() !== "kotlin") return []
            const source = model.getValue()
            const regions = []
            let search = 0
            while (search < source.length) {
                    const open = source.indexOf('"""', search)
                    if (open < 0) break
                    const contentStart = open + 3
                    const close = source.indexOf('"""', contentStart)
                    if (close < 0) break

                    const rawContent = source.slice(contentStart, close)
                    const header = stackSLEmbeddedHeaderRegex.exec(rawContent)
                    if (header) {
                            const bodyStart = contentStart + header[0].length
                            const headerStart = contentStart + header[0].search(/#stacksl\b/i)
                            const headerEnd = headerStart + "#stacksl".length
                            regions.push({
                                    open,
                                    close,
                                    contentStart,
                                    contentEnd: close,
                                    bodyStart,
                                    bodyEnd: close,
                                    headerStart,
                                    headerEnd,
                                    source: source.slice(bodyStart, close),
                                    bodyPosition: model.getPositionAt(bodyStart),
                            })
                    }
                    search = close + 3
            }
            return regions
    }

    const findStackSLEmbeddedRegionAtOffset = (model, offset) => {
            const regions = findStackSLEmbeddedRegions(model)
            return regions.find((region) => region.bodyStart <= offset && offset <= region.bodyEnd) || null
    }

    const resolveStackSLScope = (model, offset) => {
            if (!model) return null
            if (model.getLanguageId() === "stacksl") {
                    return {
                            embedded: false,
                            source: model.getValue(),
                            baseOffset: 0,
                            basePosition: { lineNumber: 1, column: 1 },
                            offset,
                    }
            }
            const embedded = findStackSLEmbeddedRegionAtOffset(model, offset)
            if (!embedded) return null
            return {
                    embedded: true,
                    source: embedded.source,
                    baseOffset: embedded.bodyStart,
                    basePosition: embedded.bodyPosition,
                    offset,
            }
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
                    STACKSL_EXTERNAL_READ_RE.test(word) ||
                    STACKSL_VEC_LITERAL_RE.test(word) ||
                    STACKSL_STACK_SELECTOR_LITERAL_RE.test(word) ||
                    STACKSL_NUMBER_RE.test(word) ||
                    STACKSL_NUMBER_SUFFIX_RE.test(word) ||
                    STACKSL_NUMBER_RANGE_RE.test(word) ||
                    STACKSL_NUMBER_MODIFIER_RE.test(word)
    }

    const dynamicStackSLEffect = (depth, fullArity, shortLabel) => {
            if (depth < fullArity) return stackslEffect(1, 1, shortLabel || "short form")
            return stackslEffect(fullArity, 1)
    }

    const stackSLEffectForWord = (rawWord, depth, definitionWords, localMarkWords = null) => {
            const raw = String(rawWord || "").toLowerCase()
            const variant = arityVariant(raw)
            const word = variant ? variant.base : raw
            const requestedArity = variant ? variant.arity : null
            const requestedAll = variant ? variant.all : false
            if (!word) return stackslEffect(0, 0)
            if (word === ":" || word === ";") return stackslEffect(0, 0, "definition marker")
            if (word === "|" || word === "]") return stackslEffect(0, 0, "modifier prefix")
            if (STACKSL_TOP_MODIFIER_RE.test(raw)) return stackslEffect(1, 1, raw.startsWith("]") ? "driven modifier" : "top modifier")
            if (STACKSL_STACK_SELECTOR_LITERAL_RE.test(raw)) return stackslEffect(stackSelectorLiteralDepth(raw), 1, "stack selector")
            if (isStackSLPushToken(raw)) return stackslEffect(0, 1, "push")
            if (STACKSL_SWIZZLE_RE.test(raw)) return stackslEffect(1, 1, "swizzle")
            if (STACKSL_EXTERNAL_WRITE_RE.test(rawWord || "")) return stackslEffect(1, 0, "host write")
            if (STACKSL_LOCAL_MARK_COPY_RE.test(raw)) return stackslEffect(1, 1, "mark copy")
            if (STACKSL_LOCAL_MARK_MOVE_RE.test(raw) && !STACKSL_LOCAL_MARK_MOVE_RESERVED_RE.test(raw)) return stackslEffect(1, 0, "mark move")
            if (STACKSL_LOCAL_MARK_READ_RE.test(raw)) return stackslEffect(0, 1, "mark read")
            if (localMarkWords && localMarkWords.has(raw)) return stackslEffect(0, 1, "mark read")

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
            if (word === "rotates" || word === "standoff" || word === "standoff2" || word === "standoff3") return stackslEffect(3, 1)

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
                            : stackslEffect(2, 1)
            }
            if (word === "mix" || word === "lerp" || word === "mix.c" || word === "lerp.c" ||
            word === "mix.sc" || word === "lerp.sc" || word === "select" || word === "if") return stackslEffect(3, 1)
            if (word === "alpha" || word === "alpha.c" || word === "alpha.sc") return stackslEffect(3, 1)
            if (word === "step") return stackslEffect(2, 1)
            if (word === "smoothstep") return stackslEffect(3, 1)
            if (word === "sigmoid" || word === "softplus" || word === "threshold" || word === "posterize" ||
                    word === "bias" || word === "gain" || word === "tile" || word === "twist" ||
                    word === "spiral" || word === "kaleido" || word === "kaleidoscope") {
                    return requestedArity ? stackslEffect(requestedArity, 1, requestedArity < 2 ? "trailing defaults" : "") : dynamicStackSLEffect(depth, 2, "short form")
            }
            if (word === "clamp") {
                    return requestedArity ? stackslEffect(requestedArity, 1, requestedArity < 3 ? "trailing defaults" : "") : dynamicStackSLEffect(depth, 3, "short form")
            }
            if (word === "band" || word === "ramp") {
                    return requestedArity ? stackslEffect(requestedArity, 1, requestedArity < 3 ? "trailing defaults" : "") : dynamicStackSLEffect(depth, 3, "short form")
            }
            if (word === "pulse" || word === "fm" ||
                    word === "ema" || word === "ema.c" || word === "ema.sc" ||
                    word === "ema-diff" || word === "ema-diff.c" || word === "ema-diff.sc" ||
                    word === "diff-ema" || word === "diff-ema.c" || word === "diff-ema.sc" ||
                    word === "dema" || word === "dema.c" || word === "dema.sc") {
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
            if (word === "bitcrush" || word === "bit-crush") {
                    return requestedArity ? stackslEffect(requestedArity, 1, requestedArity < 2 ? "default depth" : "") : dynamicStackSLEffect(depth, 2, "short form")
            }
            if (word === "grade") {
                    return requestedArity ? stackslEffect(requestedArity, 1, requestedArity < 4 ? "trailing defaults" : "") : dynamicStackSLEffect(depth, 4, "short form")
            }

            if (word === "sample" || word === "sample0" || word === "sample1" || word === "sample2" || word === "sample3" ||
                    word === "sample.w" || word === "sample0.w" || word === "sample1.w" || word === "sample2.w" || word === "sample3.w" ||
                    word === "sample-abs" || word === "sample0-abs" || word === "sample1-abs" || word === "sample2-abs" || word === "sample3-abs" ||
                    word === "sample-abs.w" || word === "sample0-abs.w" || word === "sample1-abs.w" || word === "sample2-abs.w" || word === "sample3-abs.w" ||
                    word === "gather" || word === "gather0" || word === "gather1" || word === "gather2" || word === "gather3" ||
                    word === "gather.w" || word === "gather0.w" || word === "gather1.w" || word === "gather2.w" || word === "gather3.w") {
                    return stackslEffect(1, 1, word.includes("abs") ? "absolute sample" : "sample")
            }
            if (word === "crosssample" || word === "cross-sample" ||
                    word === "crosssample.r" || word === "crosssample.g" || word === "crosssample.b" || word === "crosssample.a" ||
                    word === "cross-sample.r" || word === "cross-sample.g" || word === "cross-sample.b" || word === "cross-sample.a" ||
                    word === "rcrosssample" || word === "r-cross-sample" ||
                    word === "rcrosssample.r" || word === "rcrosssample.g" || word === "rcrosssample.b" || word === "rcrosssample.a" ||
                    word === "r-cross-sample.r" || word === "r-cross-sample.g" || word === "r-cross-sample.b" || word === "r-cross-sample.a") {
                    return stackslEffect(2, 1, "neighborhood sample")
            }
            if (word === "laplace" || word === "laplacian" || word === "blur" || word === "blur5" || word === "blur9" ||
                    word === "sobel" || word === "gradient" || word === "divergence" || word === "curl") {
                    return requestedArity ? stackslEffect(requestedArity, 1, "neighborhood field") : stackslEffect(Math.min(2, depth), 1, "neighborhood field")
            }

            if (definitionWords && definitionWords.has(word)) return stackslEffect(1, 1, "word call")
            return stackslEffect(0, 0, "unknown")
    }

    const stackSLLocalMarkName = (rawWord, regex) => {
            const match = regex.exec(String(rawWord || "").toLowerCase())
            return match ? match[1] : null
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

    const applyStackSLFlowToken = (stack, token, definitionWords, localMarks) => {
            const lower = token.lower || String(token.text || "").toLowerCase()
            const effect = stackSLEffectForWord(token.text, stack.length, definitionWords, localMarks)
            const consume = Math.max(0, effect.consume || 0)
            const produce = Math.max(0, effect.produce || 0)
            const consumed = readStackSLFlowInputs(stack, consume)
            const rest = consume > 0 ? stack.slice(0, Math.max(0, stack.length - consume)) : stack.slice()
            let next = rest
            let produced = []

            const make = (source = null) => newStackSLFlowValue(token, source)

            const markCopyName = stackSLLocalMarkName(lower, STACKSL_LOCAL_MARK_COPY_RE)
            const markMoveName = STACKSL_LOCAL_MARK_MOVE_RESERVED_RE.test(lower) ? null : stackSLLocalMarkName(lower, STACKSL_LOCAL_MARK_MOVE_RE)
            const markReadName = stackSLLocalMarkName(lower, STACKSL_LOCAL_MARK_READ_RE)

            if (markCopyName) {
                    const value = stack[stack.length - 1] || consumed[consumed.length - 1] || null
                    localMarks.set(markCopyName, value)
                    next = stack.slice()
            } else if (markMoveName) {
                    const value = consumed[consumed.length - 1] || null
                    localMarks.set(markMoveName, value)
                    next = rest
            } else if (markReadName || localMarks.has(lower)) {
                    const value = localMarks.get(markReadName || lower) || make()
                    produced = [make(value)]
                    next = stack.concat(produced)
            } else if (lower === "dup") {
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
            const localMarks = new Map()

            let active = null
            let seenActive = false
            const producedIds = new Set()
            const futureConsumers = []

            for (const token of tokens) {
                    const step = applyStackSLFlowToken(stack, token, definitionWords, localMarks)
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
            if (!monacoApi || !model) {
                    clearStackSLFlowLens(editor)
                    return
            }
            const position = editor.getPosition && editor.getPosition()
            if (!position) {
                    clearStackSLFlowLens(editor)
                    return
            }

            const offset = model.getOffsetAt(position)
            const scope = resolveStackSLScope(model, offset)
            if (!scope) {
                    clearStackSLFlowLens(editor)
                    return
            }
            const tokens = tokenizeStackSLForContext(scope.source, scope.baseOffset, scope.basePosition)
            const parsed = parseStackSLDefinitionsForContext(tokens)
            const context = findStackSLDefinitionContext(parsed, scope.offset)
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
            const scheduleDiagnostics = () => setTimeout(() => updateStackSLDiagnostics(editor), 0)
            editor.onDidChangeCursorPosition(schedule)
            editor.onDidChangeModelContent(() => {
                    schedule()
                    scheduleDiagnostics()
            })
            if (typeof editor.onDidChangeModel === "function") {
                    editor.onDidChangeModel(() => {
                            schedule()
                            scheduleDiagnostics()
                    })
            }
            if (typeof editor.onDidFocusEditorText === "function") editor.onDidFocusEditorText(schedule)
            scheduleDiagnostics()
    }

    window.installStackSLFlowLens = installStackSLFlowLens
    window.updateStackSLFlowLens = updateStackSLFlowLens

    const analyzeStackSLDepth = (tokens, initialDepth, definitionWords) => {
            let depth = initialDepth
            let underflows = 0
            const localMarks = new Set()
            for (const token of tokens) {
                    const effect = stackSLEffectForWord(token.text, depth, definitionWords, localMarks)
                    const applied = applyStackSLEffectToDepth(depth, effect)
                    const raw = String(token.text || "").toLowerCase()
                    const copyName = stackSLLocalMarkName(raw, STACKSL_LOCAL_MARK_COPY_RE)
                    const moveName = STACKSL_LOCAL_MARK_MOVE_RESERVED_RE.test(raw) ? null : stackSLLocalMarkName(raw, STACKSL_LOCAL_MARK_MOVE_RE)
                    if (copyName) localMarks.add(copyName)
                    if (moveName) localMarks.add(moveName)
                    depth = applied.depth
                    underflows += applied.missing
            }
            return { depth, underflows, localMarks }
    }

    const analyzeStackSLWarnings = (tokens, initialDepth, definitionWords, label) => {
            let depth = initialDepth
            const localMarks = new Set()
            const warnings = []
            for (const token of tokens) {
                    const effect = stackSLEffectForWord(token.text, depth, definitionWords, localMarks)
                    const applied = applyStackSLEffectToDepth(depth, effect)
                    if (applied.missing > 0) {
                            warnings.push({
                                    token,
                                    message: `StackSL ${label}: '${token.text}' needs ${effect.consume} value${effect.consume === 1 ? "" : "s"} but stack has ${depth}; Field will fill or wrap defaults.`,
                            })
                    }
                    const raw = String(token.text || "").toLowerCase()
                    const copyName = stackSLLocalMarkName(raw, STACKSL_LOCAL_MARK_COPY_RE)
                    const moveName = STACKSL_LOCAL_MARK_MOVE_RESERVED_RE.test(raw) ? null : stackSLLocalMarkName(raw, STACKSL_LOCAL_MARK_MOVE_RE)
                    if (copyName) localMarks.add(copyName)
                    if (moveName) localMarks.add(moveName)
                    depth = applied.depth
            }

            const explicitValues = depth - initialDepth
            if (explicitValues >= 2) {
                    warnings.push({
                            token: tokens[tokens.length - 1] || null,
                            message: `StackSL ${label}: leaves ${explicitValues} explicit values on the stack; only the top value is returned.`,
                    })
            }
            return warnings
    }

    const stackSLMarkerRange = (model, token) => {
            if (token) {
                    const start = model.getPositionAt(token.start)
                    const end = model.getPositionAt(Math.max(token.start + 1, token.end))
                    return {
                            startLineNumber: start.lineNumber,
                            startColumn: start.column,
                            endLineNumber: end.lineNumber,
                            endColumn: end.column,
                    }
            }
            const line = Math.max(1, model.getLineCount ? model.getLineCount() : 1)
            const maxColumn = model.getLineMaxColumn ? model.getLineMaxColumn(line) : 1
            return {
                    startLineNumber: line,
                    startColumn: Math.max(1, maxColumn - 1),
                    endLineNumber: line,
                    endColumn: maxColumn,
            }
    }

    const stackSLWarningsForSource = (source, baseOffset, basePosition, labelPrefix) => {
            const tokens = tokenizeStackSLForContext(source, baseOffset, basePosition)
            const parsed = parseStackSLDefinitionsForContext(tokens)
            const warnings = []
            for (const definition of parsed.definitions) {
                    warnings.push(...analyzeStackSLWarnings(definition.body, 1, parsed.definitionWords, `${labelPrefix}:${definition.name.text}`))
            }
            warnings.push(...analyzeStackSLWarnings(parsed.root, 1, parsed.definitionWords, `${labelPrefix}:root`))
            return warnings
    }

    const updateStackSLDiagnostics = (editor) => {
            const monacoApi = window.globalMonaco || window.monaco || monaco
            const model = editor && editor.getModel && editor.getModel()
            if (!monacoApi || !monacoApi.editor || !model) return

            const markers = []
            const language = model.getLanguageId && model.getLanguageId()
            if (language === "stacksl") {
                    const base = model.getPositionAt(0)
                    const warnings = stackSLWarningsForSource(model.getValue(), 0, { lineNumber: base.lineNumber, column: base.column }, "model")
                    for (const warning of warnings) {
                            markers.push({
                                    severity: monacoApi.MarkerSeverity.Warning,
                                    message: warning.message,
                                    source: "StackSL",
                                    ...stackSLMarkerRange(model, warning.token),
                            })
                    }
            } else if (language === "kotlin") {
                    for (const region of findStackSLEmbeddedRegions(model)) {
                            const base = model.getPositionAt(region.bodyStart)
                            const warnings = stackSLWarningsForSource(
                                    region.source,
                                    region.bodyStart,
                                    { lineNumber: base.lineNumber, column: base.column },
                                    "embedded"
                            )
                            for (const warning of warnings) {
                                    markers.push({
                                            severity: monacoApi.MarkerSeverity.Warning,
                                            message: warning.message,
                                            source: "StackSL",
                                            ...stackSLMarkerRange(model, warning.token),
                                    })
                            }
                    }
            }
            monacoApi.editor.setModelMarkers(model, "stacksl-diagnostics", markers)
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
            if (!model) return null

            const position = editor.getPosition && editor.getPosition()
            if (!position) return null

            const offset = model.getOffsetAt(position)
            const scope = resolveStackSLScope(model, offset)
            if (!scope) return null
            const tokens = tokenizeStackSLForContext(scope.source, scope.baseOffset, scope.basePosition)
            const parsed = parseStackSLDefinitionsForContext(tokens)
            const context = findStackSLDefinitionContext(parsed, scope.offset)
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
                            const effect = stackSLEffectForWord(activeInContext.text, before.depth, parsed.definitionWords, before.localMarks)
                            const doc = stackSLDocForWord(activeInContext.text, effect, parsed.definitionWords, before.localMarks)
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

            let contextKey = scope.embedded ? "kotlin-stacksl" : "stacksl"
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

    const stackSLWordLookup = (words) => {
            const out = new Set()
            words.forEach((word) => out.add(String(word).toLowerCase()))
            return out
    }
    const stackSLHighlightStackWords = stackSLWordLookup(stackWords)
    const stackSLHighlightMathWords = stackSLWordLookup(mathWords)
    const stackSLHighlightCreativeWords = stackSLWordLookup(creativeWords)
    const stackSLHighlightConstants = stackSLWordLookup(constants)

    const stackSLRangeFromOffsets = (monacoApi, model, start, end) => {
            const startPos = model.getPositionAt(start)
            const endPos = model.getPositionAt(Math.max(start, end))
            return new monacoApi.Range(startPos.lineNumber, startPos.column, endPos.lineNumber, endPos.column)
    }

    const tokenizeStackSLForHighlight = (source, baseOffset) => {
            const tokens = []
            let i = 0

            const push = (kind, start, end, text = null) => {
                    if (end <= start) return
                    tokens.push({
                            kind,
                            start: baseOffset + start,
                            end: baseOffset + end,
                            text: text == null ? source.slice(start, end) : text,
                    })
            }

            while (i < source.length) {
                    const ch = source[i]
                    if (/\s/.test(ch)) {
                            i += 1
                            continue
                    }
                    if (ch === "#") {
                            const start = i
                            while (i < source.length && source[i] !== "\n") i += 1
                            push("comment", start, i)
                            continue
                    }
                    if (ch === "/" && source[i + 1] === "/") {
                            const start = i
                            while (i < source.length && source[i] !== "\n") i += 1
                            push("comment", start, i)
                            continue
                    }
                    if (ch === "(") {
                            const start = i
                            while (i < source.length && source[i] !== ")") i += 1
                            if (i < source.length) i += 1
                            push("comment", start, i)
                            continue
                    }

                    const start = i
                    while (i < source.length) {
                            const c = source[i]
                            if (/\s/.test(c) || c === "#" || c === "(") break
                            if (c === "/" && source[i + 1] === "/") break
                            i += 1
                    }
                    push("word", start, i)
            }
            return tokens
    }

    const stackSLHighlightClassForWord = (rawWord, expectDefinitionName) => {
            const word = String(rawWord || "").toLowerCase()
            if (expectDefinitionName) return "afield-stacksl-token-definition"
            if (word === ":" || word === ";") return "afield-stacksl-token-control"
            if (/^(?:0->|1->|2->|3->|->0|->1|->2|->3)$/.test(word)) return "afield-stacksl-token-keyword"
            if (STACKSL_LOCAL_MARK_COPY_RE.test(word) || STACKSL_LOCAL_MARK_READ_RE.test(word) ||
                    (STACKSL_LOCAL_MARK_MOVE_RE.test(word) && !STACKSL_LOCAL_MARK_MOVE_RESERVED_RE.test(word))) return "afield-stacksl-token-keyword"
            if (STACKSL_NUMBER_RANGE_RE.test(word)) return "afield-stacksl-token-number"
            if (STACKSL_STACK_SELECTOR_LITERAL_RE.test(word)) return "afield-stacksl-token-variable"
            if (STACKSL_TOP_MODIFIER_RE.test(word) || STACKSL_NUMBER_MODIFIER_RE.test(word)) return "afield-stacksl-token-modifier"
            if (STACKSL_NUMBER_SUFFIX_RE.test(word) || STACKSL_NUMBER_RE.test(word)) return "afield-stacksl-token-number"
            if (word === "|" || word === "]") return "afield-stacksl-token-operator"
            if (STACKSL_SWIZZLE_RE.test(word)) return "afield-stacksl-token-variable"
            if (stackSLHighlightStackWords.has(word)) return "afield-stacksl-token-keyword"
            if (stackSLHighlightMathWords.has(word)) return "afield-stacksl-token-operator"
            if (stackSLHighlightCreativeWords.has(word)) return "afield-stacksl-token-creative"
            if (stackSLHighlightConstants.has(word)) return "afield-stacksl-token-constant"
            return "afield-stacksl-token-identifier"
    }

    const ensureStackSLEmbeddedHighlight = (editor) => {
            if (!editor) return null
            if (!editor._afieldStackSLEmbeddedHighlight) {
                    editor._afieldStackSLEmbeddedHighlight = {
                            decorations: typeof editor.createDecorationsCollection === "function"
                                    ? editor.createDecorationsCollection([])
                                    : null,
                            legacyIds: [],
                    }
            }
            return editor._afieldStackSLEmbeddedHighlight
    }

    const setStackSLEmbeddedHighlightDecorations = (editor, decorations) => {
            const state = ensureStackSLEmbeddedHighlight(editor)
            if (!state) return
            if (state.decorations && typeof state.decorations.set === "function") {
                    state.decorations.set(decorations)
            } else if (typeof editor.deltaDecorations === "function") {
                    state.legacyIds = editor.deltaDecorations(state.legacyIds || [], decorations)
            }
    }

    const updateStackSLEmbeddedHighlight = (editor) => {
            const monacoApi = window.globalMonaco || window.monaco || monaco
            const model = editor && editor.getModel && editor.getModel()
            if (!monacoApi || !model || model.getLanguageId() !== "kotlin") {
                    setStackSLEmbeddedHighlightDecorations(editor, [])
                    return
            }

            const decorations = []
            for (const region of findStackSLEmbeddedRegions(model)) {
                    decorations.push({
                            range: stackSLRangeFromOffsets(monacoApi, model, region.headerStart, region.headerEnd),
                            options: {
                                    inlineClassName: "afield-stacksl-token-directive",
                                    hoverMessage: { value: "Embedded StackSL" },
                            },
                    })

                    let expectDefinitionName = false
                    const tokens = tokenizeStackSLForHighlight(region.source, region.bodyStart)
                    for (const token of tokens) {
                            let inlineClassName = "afield-stacksl-token-comment"
                            if (token.kind === "word") {
                                    inlineClassName = stackSLHighlightClassForWord(token.text, expectDefinitionName)
                                    expectDefinitionName = token.text === ":"
                            }
                            decorations.push({
                                    range: stackSLRangeFromOffsets(monacoApi, model, token.start, token.end),
                                    options: { inlineClassName },
                            })
                            if (token.kind === "word" && token.text !== ":" && expectDefinitionName) {
                                    expectDefinitionName = false
                            }
                    }
            }
            setStackSLEmbeddedHighlightDecorations(editor, decorations)
    }

    const installStackSLEmbeddedHighlight = (editor) => {
            if (!editor || editor._afieldStackSLEmbeddedHighlightInstalled) return
            editor._afieldStackSLEmbeddedHighlightInstalled = true
            ensureStackSLEmbeddedHighlight(editor)

            const schedule = () => setTimeout(() => updateStackSLEmbeddedHighlight(editor), 0)
            const scheduleDiagnostics = () => setTimeout(() => updateStackSLDiagnostics(editor), 0)
            editor.onDidChangeModelContent(() => {
                    schedule()
                    scheduleDiagnostics()
            })
            if (typeof editor.onDidChangeModel === "function") {
                    editor.onDidChangeModel(() => {
                            schedule()
                            scheduleDiagnostics()
                    })
            }
            const monacoApi = window.globalMonaco || window.monaco || monaco
            if (monacoApi && monacoApi.editor && typeof monacoApi.editor.onDidChangeModelLanguage === "function") {
                    monacoApi.editor.onDidChangeModelLanguage((event) => {
                            const model = editor.getModel && editor.getModel()
                            if (model && event && event.model === model) {
                                    schedule()
                                    scheduleDiagnostics()
                            }
                    })
            }
            schedule()
            scheduleDiagnostics()
    }

    window.installStackSLEmbeddedHighlight = installStackSLEmbeddedHighlight
    window.updateStackSLEmbeddedHighlight = updateStackSLEmbeddedHighlight
    window.updateStackSLDiagnostics = updateStackSLDiagnostics

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

                [externalWriteTokenRegex, ["keyword", "variable.predefined"]],
                [externalReadTokenRegex, ["keyword", "variable.predefined"]],
                [/(?:0->|1->|2->|3->|->0|->1|->2|->3)/, "keyword"],
                [localMarkCopyTokenRegex, ["keyword", "variable.predefined"]],
                [localMarkReadTokenRegex, ["keyword", "variable.predefined"]],
                [localMarkMoveTokenRegex, ["keyword", "variable.predefined"]],
                [/1-/, "operator"],
                [vecLiteralTokenRegex, "number"],
                [stackSelectorLiteralTokenRegex, "variable.predefined"],
                [numberRangeTokenRegex, ["number", "operator.stacksl.range", "number"]],
                [stackedTopModifierTokenRegex, "number.stacksl.modifier"],
                [stackedNumberModifierTokenRegex, "number.stacksl.modifier"],
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
