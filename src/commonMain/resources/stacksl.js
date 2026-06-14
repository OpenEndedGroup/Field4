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
        "average", "avg", "mean", "median", "med", "vmin", "minimum", "vmax", "maximum",
        "dot", "cross", "mix", "lerp", "step", "smoothstep",
        "lt", "lte", "gt", "gte", "eq", "neq", "and", "or", "not",
        "<", "<=", ">", ">=", "=", "!=", "<>"
    ];

    var creativeWords = [
        "noise", "perlin", "fbm", "pulse", "fm",
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

    function stackEffectForWord(rawWord, depth, definitionWords) {
        var word = String(rawWord || "").toLowerCase();
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
        if (unaryWordSet[word]) return effect(1, 1);
        if (binaryWordSet[word]) return effect(2, 1);
        if (word === "min" || word === "max") return depth <= 2 ? effect(1, 1) : effect(2, 1);
        if (word === "mix" || word === "lerp") return effect(3, 1);
        if (word === "step") return effect(2, 1);
        if (word === "smoothstep") return effect(3, 1);
        if (word === "pulse" || word === "fm" || word === "ema" || word === "ema-diff" || word === "diff-ema" || word === "dema") return dynamicEffect(depth, 3);
        if (word === "palette" || word === "cos-palette" || word === "cosine-palette") return dynamicEffect(depth, 5);
        if (word === "saturation" || word === "sat-color" || word === "contrast") return dynamicEffect(depth, 2);
        if (word === "grade") return dynamicEffect(depth, 4);
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
                [/([+\-]?(?:\d+\.\d*|\d*\.\d+|\d+)(?:[eE][+\-]?\d+)?)(_)([+\-]?(?:\d+\.\d*|\d*\.\d+|\d+)(?:[eE][+\-]?\d+)?)/, ["number", "operator.stacksl.range", "number"]],
                [/([\|\]])((?:pulse|noise|grain|hash|saw|tri|fbm|rad|ang|wx|wy|wr|wa|cx|cy|sq|sh|n|j|c|s|t|r|g|b|a|x|y)(?:[+\-]?(?:\d+\.\d*|\d*\.\d+|\d+)(?:[eE][+\-]?\d+)?)?(?:p[+\-]?(?:\d+\.\d*|\d*\.\d+|\d+)(?:[eE][+\-]?\d+)?)?(?:d[+\-]?(?:\d+\.\d*|\d*\.\d+|\d+)(?:[eE][+\-]?\d+)?)?)(?=$|[\s()#;])/, ["operator.stacksl.prefix", "number.stacksl.modifier"]],
                [/([+\-]?(?:\d+\.\d*|\d*\.\d+|\d+)(?:[eE][+\-]?\d+)?)((?:pulse|noise|grain|hash|saw|tri|fbm|rad|ang|wx|wy|wr|wa|cx|cy|sq|sh|n|j|c|s|t|r|g|b|a|x|y)(?:[+\-]?(?:\d+\.\d*|\d*\.\d+|\d+)(?:[eE][+\-]?\d+)?)?(?:p[+\-]?(?:\d+\.\d*|\d*\.\d+|\d+)(?:[eE][+\-]?\d+)?)?(?:d[+\-]?(?:\d+\.\d*|\d*\.\d+|\d+)(?:[eE][+\-]?\d+)?)?)(?=$|[\s()#;])/, ["number", "number.stacksl.modifier"]],
                [/([+\-]?(?:\d+\.\d*|\d*\.\d+|\d+)(?:[eE][+\-]?\d+)?)([trgba])\b/, ["number", "number.stacksl.suffix"]],
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
